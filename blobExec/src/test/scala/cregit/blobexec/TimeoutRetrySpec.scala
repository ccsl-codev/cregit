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

/**
 * A blob whose tokenizer was killed on its budget must be *retryable*: the run
 * reports it and refuses to publish, but simply running again retries the blob
 * with no surgery on the mapping database.
 *
 * Three durable writes each independently hide such a blob from the next run —
 * its own `blob_map` row, any `tree_map` row above it (a tree hit short-circuits
 * the whole subtree), and its commit's `commit_map` row — so all three are
 * asserted absent here. The blobs that tokenized correctly in the same commit
 * are content-addressed and must survive, or every retry would redo the commit.
 */
class TimeoutRetrySpec extends AnyFunSuite with Matchers with BeforeAndAfterAll {

  private val workRoot: Path = Files.createTempDirectory("timeout-retry-")

  override def afterAll(): Unit = deleteRecursive(workRoot)

  /** The tokenizer under test: it hangs on `b.c` only while `hangMarker` exists,
    * so both runs can use the identical command path (the memo's `meta` table
    * refuses a command change, exactly as in production). */
  private def tokenizer(dir: Path, hangMarker: Path): String = {
    val f = dir.resolve("tok.sh")
    Files.writeString(
      f,
      s"""|#!/bin/sh
          |if [ "$$BFG_FILENAME" = "b.c" ] && [ -e "${hangMarker.toAbsolutePath}" ]; then
          |  sleep 300 & sleep 300
          |else
          |  tr a-z A-Z
          |fi
          |""".stripMargin
    )
    f.toFile.setExecutable(true)
    f.toAbsolutePath.toString
  }

  private def run(
      src: Repository,
      dstPath: Path,
      dbPath: Path,
      command: String,
      mode: String
  ): WalkStats = {
    val dstExisted = Files.isDirectory(dstPath)
    val dst = FileRepositoryBuilder.create(dstPath.toFile).asInstanceOf[FileRepository]
    if (!dstExisted) dst.create(true)
    val mapping = Mapping.open(dbPath, command, """\.c$""")
    try
      new Walker(
        src, dst, mapping, """\.c$""".r, command,
        abortOnError = false,
        parallelism = 2,
        pipeline = mode == "pipeline",
        pipelineTrees = mode == "pipeline-trees",
        destinationMayContainObjects = dstExisted,
        blobTimeoutSeconds = 1
      ).run()
    finally {
      mapping.close()
      dst.close()
    }
  }

  private def withMapping[A](dbPath: Path, command: String)(body: Mapping => A): A = {
    val m = Mapping.open(dbPath, command, """\.c$""")
    try body(m) finally m.close()
  }

  // All three walkers persist a commit in their own way, so all three have to
  // suppress the same three rows. `--shard` shares resolveMisses with the serial
  // walker and writes no commit rows at all.
  for (mode <- Seq("serial", "pipeline", "pipeline-trees"))
  test(s"[$mode] a timed-out blob is retried by simply running again, and good work is kept") {
    val dir = Files.createTempDirectory(workRoot, "fixture-")
    val srcDir = dir.resolve("src")
    Files.createDirectories(srcDir)
    val git = Git.init().setDirectory(srcDir.toFile).setBare(false).call()
    Files.writeString(srcDir.resolve("a.c"), "alpha\n")
    Files.createDirectories(srcDir.resolve("deep"))
    Files.writeString(srcDir.resolve("deep/b.c"), "beta\n")
    git.add().addFilepattern("a.c").call()
    git.add().addFilepattern("deep/b.c").call()
    val commit = git.commit()
      .setMessage("two blobs, one of which will wedge")
      .setAuthor("Tester", "t@example.org")
      .setCommitter("Tester", "t@example.org")
      .call()

    val src        = git.getRepository
    val commitSha  = commit.getId.name
    val topTreeSha = commit.getTree.getId.name
    val aSha       = blobIdAt(src, commit.getId, "a.c").name
    val bSha       = blobIdAt(src, commit.getId, "deep/b.c").name
    val subTreeSha = blobIdAt(src, commit.getId, "deep").name

    val hangMarker = dir.resolve("HANG")
    Files.writeString(hangMarker, "hang b.c\n")
    val command = tokenizer(dir, hangMarker)
    val dstPath = dir.resolve("dst.git")
    val dbPath  = dir.resolve("blobmap.db")

    // -- run 1: b.c wedges -----------------------------------------------------
    val first = run(src, dstPath, dbPath, command, mode)

    first.blobsTimedOut shouldEqual 1
    first.aborted shouldBe false          // a timeout is not an abort
    first.commitsProcessed shouldEqual 0  // the containing commit was not folded

    withMapping(dbPath, command) { m =>
      // The three writes that would each hide the retry, all absent:
      m.getBlob(bSha, "deep/b.c") shouldEqual None
      m.getTree(subTreeSha) shouldEqual None
      m.getTree(topTreeSha) shouldEqual None
      m.getCommit(commitSha) shouldEqual None
      // ...and the work that was genuinely correct, kept:
      m.getBlob(aSha, "a.c") shouldBe defined
      m.getBlob(aSha, "a.c").get should not equal aSha  // tokenized, not raw
    }

    // -- run 2: same command, nothing cleared by hand --------------------------
    Files.delete(hangMarker)
    val second = run(src, dstPath, dbPath, command, mode)

    second.blobsTimedOut shouldEqual 0
    second.aborted shouldBe false
    second.commitsProcessed shouldEqual 1
    // a.c came from the memo rather than being re-tokenized: the retry costs one
    // blob, not the whole commit.
    second.blobsCacheHit should be >= 1

    withMapping(dbPath, command) { m =>
      m.getCommit(commitSha) shouldBe defined
      m.getTree(topTreeSha) shouldBe defined
      val bNew = m.getBlob(bSha, "deep/b.c")
      bNew shouldBe defined
      bNew.get should not equal bSha  // the retry tokenized it
      readBlob(dstPath, bNew.get) shouldEqual "BETA\n"
    }
  }

  // -- helpers ---------------------------------------------------------------

  private def blobIdAt(repo: Repository, commitId: ObjectId, path: String): ObjectId = {
    val rw = new RevWalk(repo)
    try {
      val tree = rw.parseCommit(commitId).getTree
      val tw   = TreeWalk.forPath(repo, path, tree)
      try tw.getObjectId(0) finally tw.close()
    } finally rw.close()
  }

  private def readBlob(dstPath: Path, sha: String): String = {
    val dst = FileRepositoryBuilder.create(dstPath.toFile).asInstanceOf[FileRepository]
    try {
      val r = dst.newObjectReader()
      try new String(r.open(ObjectId.fromString(sha)).getBytes, UTF_8) finally r.close()
    } finally dst.close()
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
