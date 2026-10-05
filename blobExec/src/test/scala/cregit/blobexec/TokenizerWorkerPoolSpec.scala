package cregit.blobexec

import org.eclipse.jgit.internal.storage.dfs.{DfsRepositoryDescription, InMemoryRepository}
import org.scalatest.BeforeAndAfterAll
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Paths}
import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.jdk.CollectionConverters._

class TokenizerWorkerPoolSpec extends AnyFunSuite with Matchers with BeforeAndAfterAll {

  private val tmpDir = Files.createTempDirectory("tokenizer-worker-pool-")
  private val worker = tmpDir.resolve("fake-worker.pl")
  private val grandchildPidFile = Paths.get(s"$worker.grandchild")

  override def beforeAll(): Unit = {
    Files.writeString(
      worker,
      """|#!/usr/bin/env perl
         |use strict;
         |use warnings;
         |$| = 1;
         |sub reply { my ($exit, $out) = @_; print "RES $exit ", length($out), " 0\n", $out }
         |print "READY\n";
         |while (my $header = <STDIN>) {
         |    my (undef, $bodyLength, $nameLength, $timeout) = split ' ', $header;
         |    read(STDIN, my $name, $nameLength);
         |    read(STDIN, my $body, $bodyLength) if $bodyLength;
         |    if ($name eq 'hang.c') {
         |        my $child = fork() // exit 3;
         |        exec 'sleep', '300' if $child == 0;
         |        open(my $pids, '>', "$0.grandchild") or exit 3;
         |        print {$pids} $child;
         |        close($pids);
         |        sleep 300;
         |    }
         |    exit 7 if $name eq 'die.c';
         |    sleep 2 if $name eq 'slow.c';
         |    if    ($name eq 'crash.c')   { reply(33, '') }
         |    elsif ($name eq 'timeout.c') { reply(124, '') }
         |    elsif ($name eq 'budget.c')  { reply(0, $timeout) }
         |    else                         { reply(0, uc $body) }
         |}
         |""".stripMargin
    )
    worker.toFile.setExecutable(true)
  }

  override def afterAll(): Unit =
    Files.walk(tmpDir).sorted(java.util.Comparator.reverseOrder()).forEach(p => Files.delete(p))

  private def withPool[A](size: Int)(body: TokenizerWorkerPool => A): A = {
    val pool = new TokenizerWorkerPool(worker.toString, size)
    try body(pool) finally pool.close()
  }

  private def stdout(pool: TokenizerWorkerPool, filename: String, body: String, timeoutSeconds: Int = 2) = {
    pool.invoke(body.getBytes(UTF_8), filename, timeoutSeconds) match {
      case ChildRunner.Outcome.Exited(0, out, _) => new String(out, UTF_8)
      case other                                 => fail(s"expected exit 0, got $other")
    }
  }

  test("returns the worker's output and sends each request's own budget") {
    withPool(1) { pool =>
      stdout(pool, "hello.c", "hello") shouldEqual "HELLO"
      stdout(pool, "budget.c", "x", timeoutSeconds = 7) shouldEqual "7"
      stdout(pool, "budget.c", "x", timeoutSeconds = 21) shouldEqual "21"
    }
  }

  test("a worker's timeout and parser crash take BlobExec's timeout and crash paths") {
    val inserter = new InMemoryRepository(new DfsRepositoryDescription("pool-spec")).newObjectInserter()
    withPool(1) { pool =>
      def run(filename: String): (BlobExec.Outcome, String) = {
        var called = "none"
        val outcome = BlobExec.run(
          "body".getBytes(UTF_8), "a" * 40, filename, s"src/$filename", "tokenBySha.pl",
          abortOnError = true, inserter = inserter,
          onTimeout = () => called = "timeout", onParserCrash = () => called = "parser-crash",
          workerPool = Some(pool))
        (outcome, called)
      }
      run("timeout.c") shouldEqual ((BlobExec.Outcome.Skip, "timeout"))
      run("crash.c") shouldEqual ((BlobExec.Outcome.Skip, "parser-crash"))
      run("die.c") shouldEqual ((BlobExec.Outcome.Skip, "timeout"))
      run("ok.c")._1 shouldBe a[BlobExec.Outcome.Replace]
    }
  }

  test("kills a wedged worker with its children, replaces it, and serves the next request") {
    withPool(1) { pool =>
      val started = System.nanoTime()
      pool.invoke("x".getBytes(UTF_8), "hang.c", 1) shouldBe a[ChildRunner.Outcome.Killed]
      (System.nanoTime() - started).nanos should be < 15.seconds
      eventuallyStopped(Seq(Files.readString(grandchildPidFile).trim.toLong)) shouldBe true
      stdout(pool, "next.c", "next") shouldEqual "NEXT"
    }
  }

  test("a worker that dies in a request fails that blob only") {
    withPool(1) { pool =>
      pool.invoke("x".getBytes(UTF_8), "die.c", 2) should not matchPattern {
        case ChildRunner.Outcome.Exited(0, _, _) =>
      }
      stdout(pool, "ok.c", "ok") shouldEqual "OK"
    }
  }

  test("serves pool-sized requests in parallel") {
    implicit val ec: ExecutionContext = ExecutionContext.global
    withPool(3) { pool =>
      val started = System.nanoTime()
      val requests = (1 to 3).map(n => Future(stdout(pool, "slow.c", s"body$n", timeoutSeconds = 5)))
      Await.result(Future.sequence(requests), 10.seconds).toSet shouldEqual Set("BODY1", "BODY2", "BODY3")
      (System.nanoTime() - started).nanos should be < 4.seconds
    }
  }

  test("close stops every worker") {
    val pool = new TokenizerWorkerPool(worker.toString, 2)
    val pids = ProcessHandle.current().children().iterator().asScala
      .filter(_.info().commandLine().orElse("").contains(worker.toString))
      .map(_.pid()).toVector
    pids should have size 2
    pool.close()
    eventuallyStopped(pids) shouldBe true
  }

  private def eventuallyStopped(pids: Seq[Long]): Boolean = {
    def alive(pid: Long) = ProcessHandle.of(pid).map[Boolean](_.isAlive).orElse(false)
    val deadline = System.nanoTime() + 5.seconds.toNanos
    while (System.nanoTime() < deadline && pids.exists(alive)) Thread.sleep(25)
    !pids.exists(alive)
  }
}
