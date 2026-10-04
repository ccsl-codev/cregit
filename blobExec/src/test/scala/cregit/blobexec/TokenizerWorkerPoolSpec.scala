package cregit.blobexec

import org.scalatest.BeforeAndAfterAll
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path, Paths}
import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Future}

class TokenizerWorkerPoolSpec extends AnyFunSuite with Matchers with BeforeAndAfterAll {

  import ChildRunner.Outcome.{Exited, Killed}

  private def exited(outcome: ChildRunner.Outcome): (Int, Array[Byte], String) = outcome match {
    case Exited(status, stdout, stderr) => (status, stdout, stderr)
    case other                          => fail(s"expected Exited, got $other")
  }

  private def request(
      pool: TokenizerWorkerPool,
      filename: String,
      body: String,
      sha: String,
      timeoutSeconds: Int = 2
  ): ChildRunner.Outcome =
    pool.invoke(body.getBytes(UTF_8), sha, filename, s"src/$filename", timeoutSeconds)

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
         |        my $child = fork() // exit 3;
         |        exec 'sleep', '300' if $child == 0;
         |        open(my $pidFile, '>', "$0.grandchild") or exit 3;
         |        print {$pidFile} $child;
         |        close($pidFile);
         |        sleep 300 while 1;
         |    } elsif ($filename eq 'slow.c') {
         |        sleep 2;
         |        my $out = uc($body);
         |        print "RES 0 ", length($out), " 0\n", $out;
         |    } elsif ($filename eq 'crash.c') {
         |        print "RES 33 0 5\ncrash";
         |    } elsif ($filename eq 'timeout.c') {
         |        print "RES 124 0 7\ntimeout";
         |    } elsif ($filename eq 'budget.c') {
         |        print "RES 0 ", length($timeout), " 0\n", $timeout;
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
    val pool = new TokenizerWorkerPool(Seq(worker.toString), Map.empty, size = 1)
    try {
      val (exit, stdout, stderr) = exited(request(pool, "hello.c", "hello", "a" * 40))
      exit shouldEqual 0
      stdout shouldEqual "HELLO".getBytes(UTF_8)
      stderr shouldBe empty

      val (crashExit, crashOut, crashErr) =
        exited(request(pool, "crash.c", "bad", "b" * 40))
      crashExit shouldEqual BlobExec.ParserCrashExitCode
      crashOut shouldBe empty
      crashErr shouldEqual "crash"
    } finally pool.close()
  }

  test("sends each request's own budget to the worker") {
    val pool = new TokenizerWorkerPool(Seq(worker.toString), Map.empty, size = 1)
    try {
      exited(request(pool, "budget.c", "x", "9" * 40, timeoutSeconds = 7))._2 shouldEqual "7".getBytes(UTF_8)
      exited(request(pool, "budget.c", "x", "9" * 40, timeoutSeconds = 21))._2 shouldEqual "21".getBytes(UTF_8)
    } finally pool.close()
  }

  test("times out a wedged worker, kills its children, replaces it, and serves the next request") {
    val pool = new TokenizerWorkerPool(Seq(worker.toString), Map.empty, size = 1)
    try {
      val started = System.nanoTime()
      val hung = request(pool, "hang.c", "hang", "c" * 40, timeoutSeconds = 1)
      val elapsed = (System.nanoTime() - started).nanos

      hung shouldBe a[Killed]
      elapsed should be < 15.seconds
      val grandchild = Files.readString(Paths.get(s"$worker.grandchild")).trim.toLong
      eventuallyStopped(Seq(grandchild)) shouldBe true
      val (nextExit, nextOut, nextErr) =
        exited(request(pool, "next.c", "next", "d" * 40, timeoutSeconds = 1))
      nextExit shouldEqual 0
      nextOut shouldEqual "NEXT".getBytes(UTF_8)
      nextErr shouldBe empty
    } finally pool.close()
  }

  test("serves pool-sized requests in parallel") {
    implicit val ec: ExecutionContext = ExecutionContext.global
    val pool = new TokenizerWorkerPool(Seq(worker.toString), Map.empty, size = 3)
    try {
      val started = System.nanoTime()
      val requests = (1 to 3).map { n =>
        Future(exited(request(pool, "slow.c", s"body$n", f"$n%040x", timeoutSeconds = 5)))
      }
      val results = Await.result(Future.sequence(requests), 10.seconds)
      val elapsed = (System.nanoTime() - started).nanos

      results.map(_._1) should contain only 0
      results.map(r => new String(r._2, UTF_8)).toSet shouldEqual Set("BODY1", "BODY2", "BODY3")
      elapsed should be < 4.seconds
    } finally pool.close()
  }

  test("maps worker timeout status and respawns after mid-request death") {
    val pool = new TokenizerWorkerPool(Seq(worker.toString), Map.empty, size = 1)
    try {
      val timedOut = request(pool, "timeout.c", "slow", "e" * 40)
      timedOut shouldBe a[Killed]

      val died = exited(request(pool, "die.c", "die", "f" * 40))
      died._1 should not equal 0
      died._2 shouldBe empty
      died._3 should include("worker died")

      val recovered = exited(request(pool, "ok.c", "ok", "1" * 40))
      recovered._1 shouldEqual 0
      recovered._2 shouldEqual "OK".getBytes(UTF_8)
    } finally pool.close()
  }

  test("close terminates every worker process") {
    val pool = new TokenizerWorkerPool(Seq(worker.toString), Map.empty, size = 2)
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
