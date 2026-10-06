package cregit.blobexec

import org.eclipse.jgit.api.Git
import org.eclipse.jgit.internal.storage.file.FileRepository
import org.eclipse.jgit.lib.{ObjectId, Repository}
import org.eclipse.jgit.revwalk.RevWalk
import org.eclipse.jgit.storage.file.FileRepositoryBuilder
import org.eclipse.jgit.treewalk.TreeWalk
import org.scalatest.BeforeAndAfterAll
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}

class FailedBlobSpec extends AnyFunSuite with Matchers with BeforeAndAfterAll {

  private val workRoot: Path = Files.createTempDirectory("failed-blob-")

  override def afterAll(): Unit = deleteRecursive(workRoot)

  private def tokenizer(dir: Path, onB: String): String = {
    val f = dir.resolve("tok.sh")
    Files.writeString(
      f,
      s"""|#!/bin/sh
          |if [ "$$BFG_FILENAME" = "b.c" ]; then
          |  $onB
          |else
          |  tr a-z A-Z
          |fi
          |""".stripMargin
    )
    f.toFile.setExecutable(true)
    f.toAbsolutePath.toString
  }

  private val hang  = "sleep 300 & sleep 300"
  private val crash = s"cat >/dev/null; exit ${BlobExec.ParserCrashExitCode}"
  private val error = "cat; exit 1"

  private val failures = Seq[(String, String, WalkStats => Long)](
    ("timed-out", hang, _.blobsTimedOut),
    ("crashed", crash, _.blobsParserCrashed),
    ("errored", error, _.blobsTokenizerFailed))

  private def run(src: Repository, dstPath: Path, dbPath: Path, command: String, mode: String): WalkStats = {
    val dst = FileRepositoryBuilder.create(dstPath.toFile).asInstanceOf[FileRepository]
    dst.create(true)
    val mapping = Mapping.open(dbPath, command, """\.c$""")
    try
      new Walker(
        src, dst, mapping, """\.c$""".r, command,
        abortOnError = false,
        parallelism = 2,
        pipeline = mode == "pipeline",
        pipelineTrees = mode == "pipeline-trees",
        blobTimeoutSeconds = 1
      ).run()
    finally {
      mapping.close()
      dst.close()
    }
  }

  private final case class Fixture(src: Repository, commits: Vector[ObjectId], dir: Path)

  private def fixture(extraCommitInDeep: Boolean): Fixture = {
    val dir = Files.createTempDirectory(workRoot, "fixture-")
    val srcDir = dir.resolve("src")
    Files.createDirectories(srcDir.resolve("deep"))
    val git = Git.init().setDirectory(srcDir.toFile).call()
    def commit(msg: String) =
      git.commit().setMessage(msg).setAuthor("Tester", "t@example.org")
        .setCommitter("Tester", "t@example.org").call().getId
    Files.writeString(srcDir.resolve("a.c"), "alpha\n")
    Files.writeString(srcDir.resolve("deep/b.c"), "beta\n")
    git.add().addFilepattern(".").call()
    val first = commit("two blobs, one of which fails")
    val rest =
      if (!extraCommitInDeep) Vector.empty
      else {
        Files.writeString(srcDir.resolve("deep/c.c"), "gamma\n")
        git.add().addFilepattern(".").call()
        Vector(commit("a sibling of the failing blob"))
      }
    Fixture(git.getRepository, first +: rest, dir)
  }

  for (mode <- Seq("serial", "pipeline", "pipeline-trees")) {
    for ((kind, onB, counted) <- failures)
      test(s"[$mode] a $kind blob is excluded and the commit is still folded") {
        val f = fixture(extraCommitInDeep = false)
        val dstPath = f.dir.resolve("dst.git")
        val dbPath  = f.dir.resolve("blobmap.db")
        val command = tokenizer(f.dir, onB)

        val stats = run(f.src, dstPath, dbPath, command, mode)

        stats.aborted shouldBe false
        stats.commitsProcessed shouldEqual 1
        (stats.blobsTimedOut + stats.blobsParserCrashed + stats.blobsTokenizerFailed) shouldEqual 1
        counted(stats) shouldEqual 1
        Main.exitStatus(stats) shouldEqual 0
        val m = Mapping.open(dbPath, command, """\.c$""")
        try {
          val newCommit = m.getCommit(f.commits.head.name)
          newCommit shouldBe defined
          filesIn(dstPath, newCommit.get) shouldEqual Map("a.c" -> "ALPHA\n")
          m.getBlob(blobIdAt(f.src, f.commits.head, "deep/b.c").name, "deep/b.c") shouldEqual None
        } finally m.close()
      }

    test(s"[$mode] a failed blob is tried once per run, not once per commit") {
      val f = fixture(extraCommitInDeep = true)
      val dstPath = f.dir.resolve("dst.git")
      val dbPath  = f.dir.resolve("blobmap.db")
      val command = tokenizer(f.dir, hang)

      val stats = run(f.src, dstPath, dbPath, command, mode)

      stats.commitsProcessed shouldEqual 2
      stats.blobsTimedOut shouldEqual 1
      val m = Mapping.open(dbPath, command, """\.c$""")
      try {
        val second = m.getCommit(f.commits(1).name)
        second shouldBe defined
        filesIn(dstPath, second.get) shouldEqual Map("a.c" -> "ALPHA\n", "deep/c.c" -> "GAMMA\n")
      } finally m.close()
    }
  }

  private def blobIdAt(repo: Repository, commitId: ObjectId, path: String): ObjectId = {
    val rw = new RevWalk(repo)
    try {
      val tw = TreeWalk.forPath(repo, path, rw.parseCommit(commitId).getTree)
      try tw.getObjectId(0) finally tw.close()
    } finally rw.close()
  }

  private def filesIn(dstPath: Path, commitSha: String): Map[String, String] = {
    val dst = FileRepositoryBuilder.create(dstPath.toFile).asInstanceOf[FileRepository]
    val rw = new RevWalk(dst)
    val tw = new TreeWalk(dst)
    try {
      tw.addTree(rw.parseCommit(ObjectId.fromString(commitSha)).getTree)
      tw.setRecursive(true)
      val out = Map.newBuilder[String, String]
      while (tw.next())
        out += tw.getPathString -> new String(dst.open(tw.getObjectId(0)).getBytes, UTF_8)
      out.result()
    } finally { tw.close(); rw.close(); dst.close() }
  }

  private def deleteRecursive(p: Path): Unit = {
    if (Files.isDirectory(p)) {
      val s = Files.list(p)
      try s.forEach(deleteRecursive) finally s.close()
    }
    Files.deleteIfExists(p)
    ()
  }
}
