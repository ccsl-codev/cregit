package cregit.blobexec

import org.scalatest.BeforeAndAfterAll
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}
import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Future}

class TokenizerWorkerPoolSpec extends AnyFunSuite with Matchers with BeforeAndAfterAll {

  private val tmpDir = Files.createTempDirectory("tokenizer-worker-pool-")
  private val worker = tmpDir.resolve("fake-worker.pl")

  override def beforeAll(): Unit = {
    Files.writeString(
      worker,
      """|#!/usr/bin/env perl
         |use strict;
         |use warnings;
         |
         |binmode STDIN;
         |binmode STDOUT;
         |$| = 1;
         |
         |sub read_exact {
         |    my ($length) = @_;
         |    my $value = '';
         |    while (length($value) < $length) {
         |        my $read = read(STDIN, my $chunk, $length - length($value));
         |        exit 2 unless defined($read) && $read > 0;
         |        $value .= $chunk;
         |    }
         |    return $value;
         |}
         |
         |print "READY\n";
         |while (defined(my $header = <STDIN>)) {
         |    chomp $header;
         |    my ($tag, $sha, $body_len, $fn_len, $path_len, $timeout) = split / /, $header;
         |    exit 2 unless $tag eq 'REQ';
         |    my $filename = read_exact($fn_len);
         |    my $path = read_exact($path_len);
         |    my $body = read_exact($body_len);
         |
         |    if ($filename eq 'hang.c') {
         |        sleep 300 while 1;
         |    } elsif ($filename eq 'slow.c') {
         |        sleep 2;
         |        my $out = uc($body);
         |        print "RES 0 ", length($out), " 0\n", $out;
         |    } elsif ($filename eq 'crash.c') {
         |        print "RES 33 0 5\ncrash";
         |    } elsif ($filename eq 'timeout.c') {
         |        print "RES 124 0 7\ntimeout";
         |    } elsif ($filename eq 'die.c') {
         |        print STDERR "worker died\n";
         |        exit 7;
         |    } else {
         |        my $out = uc($body);
         |        print "RES 0 ", length($out), " 0\n", $out;
         |    }
         |}
         |""".stripMargin
    )
    worker.toFile.setExecutable(true)
  }

  override def afterAll(): Unit = deleteRecursive(tmpDir)

  test("round-trips bytes and preserves parser crash status") {
    val pool = new TokenizerWorkerPool(Seq(worker.toString), Map.empty, size = 1, timeoutSeconds = 2)
    try {
      val (exit, stdout, stderr) = pool.invoke(
        "hello".getBytes(UTF_8), "a" * 40, "hello.c", "src/hello.c")
      exit shouldEqual 0
      stdout shouldEqual "HELLO".getBytes(UTF_8)
      stderr shouldBe empty

      val (crashExit, crashOut, crashErr) =
        pool.invoke("bad".getBytes(UTF_8), "b" * 40, "crash.c", "src/crash.c")
      crashExit shouldEqual BlobExec.ParserCrashExitCode
      crashOut shouldBe empty
      crashErr shouldEqual "crash"
    } finally pool.close()
  }

  test("times out a wedged worker, replaces it, and serves the next request") {
    val pool = new TokenizerWorkerPool(Seq(worker.toString), Map.empty, size = 1, timeoutSeconds = 1)
    try {
      val started = System.nanoTime()
      val hung = pool.invoke("hang".getBytes(UTF_8), "c" * 40, "hang.c", "src/hang.c")
      val elapsed = (System.nanoTime() - started).nanos

      hung shouldEqual ((BlobExec.TimeoutExitCode, Array.emptyByteArray, ""))
      elapsed should be < 15.seconds
      val (nextExit, nextOut, nextErr) =
        pool.invoke("next".getBytes(UTF_8), "d" * 40, "next.c", "src/next.c")
      nextExit shouldEqual 0
      nextOut shouldEqual "NEXT".getBytes(UTF_8)
      nextErr shouldBe empty
    } finally pool.close()
  }

  test("serves pool-sized requests in parallel") {
    implicit val ec: ExecutionContext = ExecutionContext.global
    val pool = new TokenizerWorkerPool(Seq(worker.toString), Map.empty, size = 3, timeoutSeconds = 5)
    try {
      val started = System.nanoTime()
      val requests = (1 to 3).map { n =>
        Future(pool.invoke(s"body$n".getBytes(UTF_8), f"$n%040x", "slow.c", s"src/slow$n.c"))
      }
      val results = Await.result(Future.sequence(requests), 10.seconds)
      val elapsed = (System.nanoTime() - started).nanos

      results.map(_._1) should contain only 0
      results.map(r => new String(r._2, UTF_8)).toSet shouldEqual Set("BODY1", "BODY2", "BODY3")
      elapsed should be < 4.seconds
    } finally pool.close()
  }

  test("maps worker timeout status and respawns after mid-request death") {
    val pool = new TokenizerWorkerPool(Seq(worker.toString), Map.empty, size = 1, timeoutSeconds = 2)
    try {
      val timedOut = pool.invoke("slow".getBytes(UTF_8), "e" * 40, "timeout.c", "src/timeout.c")
      timedOut._1 shouldEqual BlobExec.TimeoutExitCode
      timedOut._2 shouldBe empty
      timedOut._3 shouldBe empty

      val died = pool.invoke("die".getBytes(UTF_8), "f" * 40, "die.c", "src/die.c")
      died._1 should not equal 0
      died._2 shouldBe empty
      died._3 should include("worker died")

      val recovered = pool.invoke("ok".getBytes(UTF_8), "1" * 40, "ok.c", "src/ok.c")
      recovered._1 shouldEqual 0
      recovered._2 shouldEqual "OK".getBytes(UTF_8)
    } finally pool.close()
  }

  test("close terminates every worker process") {
    val pool = new TokenizerWorkerPool(Seq(worker.toString), Map.empty, size = 2, timeoutSeconds = 2)
    val pids = pool.currentWorkerPids
    pids should have size 2

    pool.close()

    eventuallyStopped(pids) shouldBe true
  }

  private def eventuallyStopped(pids: Seq[Long]): Boolean = {
    val deadline = System.nanoTime() + 5.seconds.toNanos
    while (System.nanoTime() < deadline && pids.exists(isAlive)) Thread.sleep(25)
    pids.forall(pid => !isAlive(pid))
  }

  private def isAlive(pid: Long): Boolean = {
    val handle = ProcessHandle.of(pid)
    handle.isPresent && handle.get().isAlive
  }

  private def deleteRecursive(path: Path): Unit = {
    if (Files.isDirectory(path)) {
      val entries = Files.list(path)
      try entries.forEach(deleteRecursive) finally entries.close()
    }
    Files.deleteIfExists(path)
    ()
  }
}
