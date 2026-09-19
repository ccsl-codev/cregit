package cregit.blobexec

import org.eclipse.jgit.api.Git
import org.eclipse.jgit.internal.storage.file.FileRepository
import org.eclipse.jgit.lib.Constants.{OBJ_BLOB, OBJ_TAG}
import org.eclipse.jgit.lib._
import org.eclipse.jgit.revwalk.{RevTag, RevWalk}
import org.eclipse.jgit.storage.file.{FileRepositoryBuilder, WindowCacheConfig}
import org.scalatest.BeforeAndAfterAll
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path, Paths}
import scala.jdk.CollectionConverters._

/**
 * End-to-end Walker tests against synthetic jgit-built source repos.
 *
 * Each test creates a fresh tmp dir holding (src.git, dst.git, db.sqlite,
 * cmd.sh), runs Walker.run(), and asserts on dst contents + mapping rows.
 */
class WalkerIntegrationSpec extends AnyFunSuite with Matchers with BeforeAndAfterAll {

  // -- shared fixture helpers ------------------------------------------------

  private val workRoot: Path = Files.createTempDirectory("walker-it-")

  override def afterAll(): Unit = deleteRecursive(workRoot)

  private def freshWorkDir(label: String): Path = {
    val d = Files.createTempDirectory(workRoot, label + "-")
    Files.createDirectories(d.resolve("dst.git"))  // we'll re-create as bare repo
    Files.delete(d.resolve("dst.git"))             // Walker/Main init it
    d
  }

  private def initSrc(dir: Path): Git = {
    Files.createDirectories(dir)
    Git.init().setDirectory(dir.toFile).setBare(false).call()
  }

  private def writeAndCommit(git: Git, files: Map[String, String], message: String): RevCommitLike = {
    val workTree = git.getRepository.getWorkTree.toPath
    files.foreach { case (rel, content) =>
      val p = workTree.resolve(rel)
      Files.createDirectories(p.getParent)
      Files.writeString(p, content)
    }
    files.keys.foreach(p => git.add().addFilepattern(p).call())
    val c = git.commit()
      .setMessage(message)
      .setAuthor("Tester", "t@example.org")
      .setCommitter("Tester", "t@example.org")
      .call()
    RevCommitLike(c.getId, c.getFullMessage)
  }

  private final case class RevCommitLike(id: ObjectId, message: String)

  private def shellScript(dir: Path, body: String): String = {
    val f = Files.createTempFile(dir, "cmd-", ".sh")
    Files.writeString(f, "#!/bin/sh\n" + body)
    f.toFile.setExecutable(true)
    f.toAbsolutePath.toString
  }

  private def openBare(path: Path): FileRepository = {
    val repo = FileRepositoryBuilder.create(path.toFile).asInstanceOf[FileRepository]
    if (!path.toFile.exists() || !path.resolve("HEAD").toFile.exists()) repo.create(true)
    repo
  }

  private def runWalker(
      srcRepo: Repository,
      dstPath: Path,
      dbPath: Path,
      command: String,
      mask: String,
      abortOnError: Boolean = false,
      pipeline: Boolean = false,
      pipelineTrees: Boolean = false,
      parallelism: Int = 4,
      deduplicateOriginalBlobs: Boolean = true
  ): WalkStats = {
    val destinationMayContainObjects = Files.isDirectory(dstPath)
    val dst = openBare(dstPath)
    val mapping = Mapping.open(dbPath, command, mask)
    try {
      val walker = new Walker(
        srcRepo, dst, mapping, mask.r, command, abortOnError, parallelism,
        pipeline = pipeline, pipelineTrees = pipelineTrees,
        deduplicateOriginalBlobs = deduplicateOriginalBlobs,
        destinationMayContainObjects = destinationMayContainObjects
      )
      walker.run()
    } finally {
      mapping.close()
      dst.close()
    }
  }

  private def readBlobUtf8(repo: Repository, id: ObjectId): String = {
    val r = repo.newObjectReader()
    try new String(r.open(id, OBJ_BLOB).getBytes, UTF_8) finally r.close()
  }

  private def fileAtHead(repo: Repository, branch: String, path: String): Option[String] = {
    val ref = repo.exactRef("refs/heads/" + branch)
    if (ref eq null) return None
    val rw = new RevWalk(repo)
    try {
      val tip = rw.parseCommit(ref.getObjectId)
      val tw = org.eclipse.jgit.treewalk.TreeWalk.forPath(repo, path, tip.getTree)
      if (tw eq null) None
      else
        try Some(readBlobUtf8(repo, tw.getObjectId(0))) finally tw.close()
    } finally rw.close()
  }

  // -- tests -----------------------------------------------------------------

  test("identity command: dst matches src semantically and footers are correct") {
    val w = freshWorkDir("identity")
    val srcDir = w.resolve("src")
    val dstDir = w.resolve("dst.git")
    val db     = w.resolve("map.sqlite")
    val cmd    = shellScript(w, "cat")

    val git = initSrc(srcDir)
    val c1 = writeAndCommit(git, Map("a.c" -> "int main(){return 0;}\n",
                                     "a.h" -> "#define X 1\n",
                                     "README.md" -> "hello\n"), "first")
    val c2 = writeAndCommit(git, Map("b.c" -> "int b(){return 1;}\n"), "second")
    git.branchCreate().setName("feature").call()
    git.checkout().setName("feature").call()
    val c3 = writeAndCommit(git, Map("a.c" -> "int main(){return 42;}\n"), "third")
    git.checkout().setName("master").call()

    val stats = runWalker(git.getRepository, dstDir, db, cmd, """\.[ch]$""")

    stats.commitsProcessed shouldEqual 3
    stats.refsProjected should be >= 1  // at least master + feature; HEAD handled separately
    stats.aborted shouldBe false

    val dst = openBare(dstDir)
    try {
      // .md unchanged
      fileAtHead(dst, "master", "README.md") shouldBe Some("hello\n")
      // .c unchanged content (cat is identity)
      fileAtHead(dst, "master", "a.c") shouldBe Some("int main(){return 0;}\n")
      fileAtHead(dst, "feature", "a.c") shouldBe Some("int main(){return 42;}\n")

      // Every commit message ends with Former-commit-id: <orig>
      val rw = new RevWalk(dst)
      try {
        val mapping = Mapping.open(db, cmd, """\.[ch]$""")
        try Seq(c1, c2, c3).foreach { orig =>
          val newSha = mapping.getCommit(orig.id.name).getOrElse(fail(s"missing commit_map for ${orig.id.name}"))
          val rc = rw.parseCommit(ObjectId.fromString(newSha))
          rc.getFullMessage.linesIterator.toList.last shouldEqual s"${FormerCommitFooter.Key}: ${orig.id.name}"
        }
        finally mapping.close()
      } finally rw.close()
    } finally dst.close()
  }

  test("non-matching blobs are recorded in blob_map with full path as identity rows") {
    val w = freshWorkDir("identity-rows")
    val srcDir = w.resolve("src")
    val dstDir = w.resolve("dst.git")
    val db     = w.resolve("map.sqlite")
    val cmd    = shellScript(w, "cat")

    val git = initSrc(srcDir)
    writeAndCommit(git, Map(
      "src/main.c"       -> "int x;\n",
      "docs/notes.md"    -> "hi\n",
      "lib/extra/text.txt" -> "leave-me-alone\n"
    ), "first")

    runWalker(git.getRepository, dstDir, db, cmd, """\.[ch]$""")

    import java.sql.DriverManager
    val conn = DriverManager.getConnection(s"jdbc:sqlite:${db.toAbsolutePath}")
    try {
      // Every blob_map row uses the FULL repo-root path, not the basename.
      val pathsRs = conn.createStatement()
        .executeQuery("SELECT path, orig_blob, new_blob FROM blob_map ORDER BY path")
      val rows = Iterator.continually(pathsRs)
        .takeWhile(_.next())
        .map(r => (r.getString(1), r.getString(2), r.getString(3)))
        .toVector
      try {
        val pathSet = rows.map(_._1).toSet
        pathSet shouldEqual Set("src/main.c", "docs/notes.md", "lib/extra/text.txt")

        // For non-matching blobs (the .md and .txt) orig and new must be equal.
        rows.filterNot(_._1.endsWith(".c")).foreach { case (p, o, n) =>
          assert(o == n, s"non-matching blob at $p should be identity-mapped: orig=$o new=$n")
        }

        // processed_at column exists and is > 0 (epoch seconds).
        val tsRs = conn.createStatement().executeQuery("SELECT MIN(processed_at) FROM blob_map")
        try { tsRs.next(); tsRs.getLong(1) should be > 0L } finally tsRs.close()
      } finally pathsRs.close()
    } finally conn.close()
  }

  test("transforming command uppercases matching files only") {
    val w = freshWorkDir("transform")
    val srcDir = w.resolve("src")
    val dstDir = w.resolve("dst.git")
    val db     = w.resolve("map.sqlite")
    val cmd    = shellScript(w, "tr a-z A-Z")

    val git = initSrc(srcDir)
    writeAndCommit(git, Map(
      "a.c"      -> "abc\n",
      "a.h"      -> "def\n",
      "README.md" -> "leave-me-alone\n"
    ), "only commit")

    val stats = runWalker(git.getRepository, dstDir, db, cmd, """\.[ch]$""")
    stats.aborted shouldBe false

    val dst = openBare(dstDir)
    try {
      fileAtHead(dst, "master", "a.c") shouldBe Some("ABC\n")
      fileAtHead(dst, "master", "a.h") shouldBe Some("DEF\n")
      fileAtHead(dst, "master", "README.md") shouldBe Some("leave-me-alone\n")
    } finally dst.close()
  }

  test("deduplicating pipeline preserves output and copies a repeated original blob once per run") {
    val w = freshWorkDir("original-blob-dedup")
    val srcDir = w.resolve("src")
    val dstDir = w.resolve("dst.git")
    val db     = w.resolve("map.sqlite")
    val baselineDstDir = w.resolve("baseline-dst.git")
    val baselineDb     = w.resolve("baseline-map.sqlite")
    val cmd    = shellScript(w, "tr a-z A-Z")

    val git = initSrc(srcDir)
    val unchanged = "stable documentation\n"
    writeAndCommit(git, Map("a.c" -> "version 0\n", "README.md" -> unchanged), "c0")
    (1 until 12).foreach { i =>
      writeAndCommit(git, Map("a.c" -> s"version $i\n"), s"c$i")
    }

    val baseline = runWalker(
      git.getRepository, baselineDstDir, baselineDb, cmd, """\.c$""",
      pipelineTrees = true, parallelism = 4,
      deduplicateOriginalBlobs = false
    )
    baseline.blobCommandExecutions shouldEqual 12L
    baseline.originalBlobCopyRequests shouldEqual 12L
    baseline.originalBlobCopies shouldEqual 12L
    baseline.originalBlobCacheHits shouldEqual 0L
    baseline.originalBlobBytesCopied shouldEqual unchanged.getBytes(UTF_8).length.toLong * 12L

    val first = runWalker(
      git.getRepository, dstDir, db, cmd, """\.c$""",
      pipelineTrees = true, parallelism = 4,
      deduplicateOriginalBlobs = true
    )

    first.blobCommandExecutions shouldEqual 12L
    first.originalBlobCopyRequests shouldEqual 12L
    first.originalBlobCopies shouldEqual 1L
    first.originalBlobAlreadyPresent shouldEqual 11L
    first.originalBlobCacheHits shouldEqual 11L
    first.originalBlobDestinationLookups shouldEqual 0L
    first.originalBlobBytesCopied shouldEqual unchanged.getBytes(UTF_8).length.toLong
    first.originalBlobBytesAvoided shouldEqual unchanged.getBytes(UTF_8).length.toLong * 11L

    val baselineDst = openBare(baselineDstDir)
    val dst = openBare(dstDir)
    try {
      fileAtHead(dst, "master", "README.md") shouldBe Some(unchanged)
      dst.exactRef("refs/heads/master").getObjectId shouldEqual
        baselineDst.exactRef("refs/heads/master").getObjectId
    } finally {
      dst.close()
      baselineDst.close()
    }

    writeAndCommit(git, Map("a.c" -> "version 12\n"), "c12")
    val incremental = runWalker(
      git.getRepository, dstDir, db, cmd, """\.c$""",
      pipelineTrees = true, parallelism = 4,
      deduplicateOriginalBlobs = true
    )
    incremental.commitsProcessed shouldEqual 1
    incremental.blobCommandExecutions shouldEqual 1L
    incremental.originalBlobCopyRequests shouldEqual 1L
    incremental.originalBlobCopies shouldEqual 0L
    incremental.originalBlobAlreadyPresent shouldEqual 1L
    incremental.originalBlobCacheHits shouldEqual 0L
    incremental.originalBlobDestinationLookups shouldEqual 1L
    incremental.originalBlobBytesAvoided shouldEqual unchanged.getBytes(UTF_8).length.toLong
  }

  test("cache hit: re-run on unchanged src skips the external command") {
    val w = freshWorkDir("cache")
    val srcDir = w.resolve("src")
    val dstDir = w.resolve("dst.git")
    val db     = w.resolve("map.sqlite")
    val counter = w.resolve("counter").toAbsolutePath.toString
    Files.writeString(Paths.get(counter), "")
    val cmd = shellScript(w,
      s"""# Append one line per invocation (atomic on POSIX for small writes
         |# to O_APPEND files; we count lines as the invocation total).
         |echo tick >> "$counter"
         |cat
         |""".stripMargin)

    val git = initSrc(srcDir)
    writeAndCommit(git, Map("a.c" -> "x\n", "b.c" -> "y\n"), "c1")

    runWalker(git.getRepository, dstDir, db, cmd, """\.c$""")
    val firstRunCount = countLines(counter)
    firstRunCount shouldEqual 2

    // Incremental re-run: no changes to src, no new commits walked.
    val stats2 = runWalker(git.getRepository, dstDir, db, cmd, """\.c$""")
    stats2.commitsProcessed shouldEqual 0
    countLines(counter) shouldEqual firstRunCount  // unchanged
  }

  private def countLines(path: String): Int =
    Files.readString(Paths.get(path)).linesIterator.count(_.nonEmpty)

  test("incremental run: new commit only processes the new blob") {
    val w = freshWorkDir("incremental")
    val srcDir = w.resolve("src")
    val dstDir = w.resolve("dst.git")
    val db     = w.resolve("map.sqlite")
    val counter = w.resolve("counter").toAbsolutePath.toString
    Files.writeString(Paths.get(counter), "")
    val cmd = shellScript(w,
      s"""echo tick >> "$counter"
         |cat
         |""".stripMargin)

    val git = initSrc(srcDir)
    writeAndCommit(git, Map("a.c" -> "alpha\n"), "first")
    runWalker(git.getRepository, dstDir, db, cmd, """\.c$""")
    countLines(counter) shouldEqual 1

    // Add a new commit with a brand-new blob
    writeAndCommit(git, Map("b.c" -> "beta\n"), "second")
    val stats = runWalker(git.getRepository, dstDir, db, cmd, """\.c$""")
    stats.commitsProcessed shouldEqual 1
    // Counter went from 1 to 2: only b.c needed running. a.c was tree-cached.
    countLines(counter) shouldEqual 2

    val dst = openBare(dstDir)
    try {
      fileAtHead(dst, "master", "a.c") shouldBe Some("alpha\n")
      fileAtHead(dst, "master", "b.c") shouldBe Some("beta\n")
    } finally dst.close()
  }

  test("annotated tag is rewritten as a tag object pointing at the mapped commit") {
    val w = freshWorkDir("anntag")
    val srcDir = w.resolve("src")
    val dstDir = w.resolve("dst.git")
    val db     = w.resolve("map.sqlite")
    val cmd    = shellScript(w, "cat")

    val git = initSrc(srcDir)
    writeAndCommit(git, Map("a.c" -> "hi\n"), "first")
    git.tag().setName("v1.0").setAnnotated(true).setMessage("release v1.0").call()

    runWalker(git.getRepository, dstDir, db, cmd, """\.c$""")

    val dst = openBare(dstDir)
    try {
      val ref = dst.exactRef("refs/tags/v1.0")
      ref should not be null
      val rw = new RevWalk(dst)
      try {
        val any = rw.parseAny(ref.getObjectId)
        any.getType shouldEqual OBJ_TAG
        val tag = any.asInstanceOf[RevTag]
        tag.getTagName shouldEqual "v1.0"
        tag.getFullMessage should include("release v1.0")
        // The mapped commit it points at must exist in dst.
        val pointed = rw.parseCommit(tag.getObject.getId)
        pointed.getFullMessage.linesIterator.toList.last should startWith(FormerCommitFooter.Key + ": ")
      } finally rw.close()
    } finally dst.close()
  }

  test("annotated tag is recorded in ref_map with kind='annotated_tag'") {
    val w = freshWorkDir("tagrec-ann")
    val srcDir = w.resolve("src")
    val dstDir = w.resolve("dst.git")
    val db     = w.resolve("map.sqlite")
    val cmd    = shellScript(w, "cat")

    val git = initSrc(srcDir)
    writeAndCommit(git, Map("a.c" -> "hi\n"), "first")
    git.tag().setName("v1.0").setAnnotated(true).setMessage("release v1.0").call()

    runWalker(git.getRepository, dstDir, db, cmd, """\.c$""")

    val m = Mapping.open(db, cmd, """\.c$""")
    try {
      val row = m.getRef("refs/tags/v1.0").getOrElse(fail("missing ref_map row"))
      row.kind shouldEqual "annotated_tag"
      // annotated tag: orig_target != orig_commit (tag object SHA vs commit SHA)
      row.origTarget should not equal row.origCommit
      row.newTarget should not equal row.newCommit
      // new_commit must equal commit_map[orig_commit]
      m.getCommit(row.origCommit) shouldBe Some(row.newCommit)
    } finally m.close()
  }

  test("lightweight tag is recorded in ref_map with kind='lightweight_tag'") {
    val w = freshWorkDir("tagrec-light")
    val srcDir = w.resolve("src")
    val dstDir = w.resolve("dst.git")
    val db     = w.resolve("map.sqlite")
    val cmd    = shellScript(w, "cat")

    val git = initSrc(srcDir)
    writeAndCommit(git, Map("a.c" -> "hi\n"), "first")
    git.tag().setName("v0.1").setAnnotated(false).call()

    runWalker(git.getRepository, dstDir, db, cmd, """\.c$""")

    val m = Mapping.open(db, cmd, """\.c$""")
    try {
      val row = m.getRef("refs/tags/v0.1").getOrElse(fail("missing ref_map row"))
      row.kind shouldEqual "lightweight_tag"
      row.origTarget shouldEqual row.origCommit
      row.newTarget shouldEqual row.newCommit
    } finally m.close()
  }

  test("retag (same name, new target) replaces the ref_map row") {
    val w = freshWorkDir("tagretag")
    val srcDir = w.resolve("src")
    val dstDir = w.resolve("dst.git")
    val db     = w.resolve("map.sqlite")
    val cmd    = shellScript(w, "cat")

    val git = initSrc(srcDir)
    writeAndCommit(git, Map("a.c" -> "v1\n"), "first")
    git.tag().setName("rel").setAnnotated(true).setMessage("first cut").call()
    runWalker(git.getRepository, dstDir, db, cmd, """\.c$""")

    val firstNewTarget = {
      val m = Mapping.open(db, cmd, """\.c$""")
      try m.getRef("refs/tags/rel").get.newTarget finally m.close()
    }

    // Retag onto a new commit (delete + re-tag).
    git.tagDelete().setTags("rel").call()
    writeAndCommit(git, Map("a.c" -> "v2\n"), "second")
    git.tag().setName("rel").setAnnotated(true).setMessage("second cut").call()
    runWalker(git.getRepository, dstDir, db, cmd, """\.c$""")

    val m2 = Mapping.open(db, cmd, """\.c$""")
    try {
      val row = m2.getRef("refs/tags/rel").get
      row.newTarget should not equal firstNewTarget
      // Single row in ref_map for that ref name.
      m2.allRefNames.count(_ == "refs/tags/rel") shouldEqual 1
    } finally m2.close()
  }

  test("tag deleted in src disappears from dst and ref_map") {
    val w = freshWorkDir("tagdel")
    val srcDir = w.resolve("src")
    val dstDir = w.resolve("dst.git")
    val db     = w.resolve("map.sqlite")
    val cmd    = shellScript(w, "cat")

    val git = initSrc(srcDir)
    writeAndCommit(git, Map("a.c" -> "hi\n"), "first")
    git.tag().setName("doomed").setAnnotated(true).setMessage("RIP").call()
    runWalker(git.getRepository, dstDir, db, cmd, """\.c$""")

    // Sanity: tag exists in dst and DB.
    val dst1 = openBare(dstDir)
    try dst1.exactRef("refs/tags/doomed") should not be null finally dst1.close()

    // Delete the tag in src and re-run.
    git.tagDelete().setTags("doomed").call()
    runWalker(git.getRepository, dstDir, db, cmd, """\.c$""")

    val dst2 = openBare(dstDir)
    try {
      Option(dst2.exactRef("refs/tags/doomed")) shouldBe None
    } finally dst2.close()
    val m = Mapping.open(db, cmd, """\.c$""")
    try m.getRef("refs/tags/doomed") shouldBe None finally m.close()
  }

  test("branch is recorded in ref_map with kind='head'") {
    val w = freshWorkDir("branchrec")
    val srcDir = w.resolve("src")
    val dstDir = w.resolve("dst.git")
    val db     = w.resolve("map.sqlite")
    val cmd    = shellScript(w, "cat")

    val git = initSrc(srcDir)
    writeAndCommit(git, Map("a.c" -> "hi\n"), "first")
    git.branchCreate().setName("feature").call()

    runWalker(git.getRepository, dstDir, db, cmd, """\.c$""")

    val m = Mapping.open(db, cmd, """\.c$""")
    try {
      // Both master and feature recorded.
      val master  = m.getRef("refs/heads/master").getOrElse(fail("missing master in ref_map"))
      val feature = m.getRef("refs/heads/feature").getOrElse(fail("missing feature in ref_map"))
      master.kind  shouldEqual "head"
      feature.kind shouldEqual "head"
      // For head kind, target equals commit (no tag object in the chain).
      master.origTarget  shouldEqual master.origCommit
      master.newTarget   shouldEqual master.newCommit
      feature.origTarget shouldEqual feature.origCommit
      feature.newTarget  shouldEqual feature.newCommit
      // And new_commit matches commit_map.
      m.getCommit(master.origCommit) shouldBe Some(master.newCommit)
    } finally m.close()
  }

  test("branch deleted in src disappears from dst and ref_map") {
    val w = freshWorkDir("branchdel")
    val srcDir = w.resolve("src")
    val dstDir = w.resolve("dst.git")
    val db     = w.resolve("map.sqlite")
    val cmd    = shellScript(w, "cat")

    val git = initSrc(srcDir)
    writeAndCommit(git, Map("a.c" -> "hi\n"), "first")
    git.branchCreate().setName("ephemeral").call()
    runWalker(git.getRepository, dstDir, db, cmd, """\.c$""")

    val dst1 = openBare(dstDir)
    try dst1.exactRef("refs/heads/ephemeral") should not be null finally dst1.close()

    git.branchDelete().setBranchNames("ephemeral").setForce(true).call()
    runWalker(git.getRepository, dstDir, db, cmd, """\.c$""")

    val dst2 = openBare(dstDir)
    try {
      Option(dst2.exactRef("refs/heads/ephemeral")) shouldBe None
      // master is untouched.
      Option(dst2.exactRef("refs/heads/master")) should not be None
    } finally dst2.close()
    val m = Mapping.open(db, cmd, """\.c$""")
    try {
      m.getRef("refs/heads/ephemeral") shouldBe None
      m.getRef("refs/heads/master") should not be None
    } finally m.close()
  }

  test("meta mismatch on incremental run aborts") {
    val w = freshWorkDir("metamis")
    val srcDir = w.resolve("src")
    val dstDir = w.resolve("dst.git")
    val db     = w.resolve("map.sqlite")
    val cmd1   = shellScript(w, "cat")
    val cmd2   = shellScript(w, "tr a-z A-Z")

    val git = initSrc(srcDir)
    writeAndCommit(git, Map("a.c" -> "hi\n"), "first")
    runWalker(git.getRepository, dstDir, db, cmd1, """\.c$""")

    intercept[Mapping.MetaMismatchException] {
      runWalker(git.getRepository, dstDir, db, cmd2, """\.c$""")
    }
  }

  test("gitlink (submodule) entry is passed through unchanged") {
    val w = freshWorkDir("gitlink")
    val srcDir = w.resolve("src")
    val dstDir = w.resolve("dst.git")
    val db     = w.resolve("map.sqlite")
    val cmd    = shellScript(w, "cat")

    // Start with a regular jgit init, then commit a tree containing a
    // gitlink entry by building the tree manually.
    val git = initSrc(srcDir)
    val src = git.getRepository
    val fakeGitlinkId = ObjectId.fromString("0123456789abcdef0123456789abcdef01234567")
    val ins = src.newObjectInserter()
    try {
      val cBytes = "int x;\n".getBytes(UTF_8)
      val cBlobId = ins.insert(OBJ_BLOB, cBytes)

      val tf = new TreeFormatter
      tf.append("a.c", FileMode.REGULAR_FILE, cBlobId)
      tf.append("sub", FileMode.GITLINK, fakeGitlinkId)
      val treeId = ins.insert(tf)

      val cb = new CommitBuilder
      cb.setTreeId(treeId)
      cb.setAuthor(new PersonIdent("T", "t@x"))
      cb.setCommitter(new PersonIdent("T", "t@x"))
      cb.setMessage("with gitlink\n")
      val commitId = ins.insert(cb)
      ins.flush()

      // Point master at the new commit.
      val ru = src.getRefDatabase.newUpdate("refs/heads/master", true)
      ru.setNewObjectId(commitId)
      ru.setForceUpdate(true)
      ru.update()
    } finally ins.close()

    runWalker(src, dstDir, db, cmd, """\.c$""")

    val dst = openBare(dstDir)
    try {
      val ref = dst.exactRef("refs/heads/master")
      val rw = new RevWalk(dst)
      try {
        val rc = rw.parseCommit(ref.getObjectId)
        val tw = org.eclipse.jgit.treewalk.TreeWalk.forPath(dst, "sub", rc.getTree)
        tw should not be null
        try {
          tw.getFileMode(0) shouldEqual FileMode.GITLINK
          tw.getObjectId(0) shouldEqual fakeGitlinkId
        } finally tw.close()
      } finally rw.close()
    } finally dst.close()
  }

  // -- shared dir helpers ----------------------------------------------------


  // -- large pass-through blob (regression) ----------------------------------
  //
  // redis/redis failed after 1,684s of real work with:
  //   org.eclipse.jgit.errors.LargeObjectException: 12e1ac54... exceeds size limit
  //     at cregit.blobexec.Walker.ensureOriginalBlobAvailable
  //
  // A blob that does not match the mask is copied verbatim, so its size is
  // whatever the project committed, not the size of a source file.
  // ObjectLoader.getBytes refuses anything above JGit's stream threshold, so the
  // copy path must stream instead of materialising.
  //
  // The threshold is lowered here so a small blob reproduces it in milliseconds
  // rather than needing a 50 MB fixture.
  private def withStreamFileThreshold[T](bytes: Int)(body: => T): T = {
    val lowered = new WindowCacheConfig
    lowered.setStreamFileThreshold(bytes)
    lowered.install()
    try body
    finally new WindowCacheConfig().install()   // back to jgit's defaults
  }

  /** Read a blob without getBytes, which would itself throw under a lowered
    * threshold and fail the test for the wrong reason. */
  private def streamBlob(repo: Repository, id: ObjectId): Array[Byte] = {
    val r = repo.newObjectReader()
    try {
      val in = r.open(id, OBJ_BLOB).openStream()
      try in.readAllBytes() finally in.close()
    } finally r.close()
  }

  test("an unmasked blob above jgit's stream threshold is copied, not refused") {
    val dir = freshWorkDir("large-passthrough")
    val git = initSrc(dir.resolve("src"))
    // 64 KiB of non-random bytes, well above the 4 KiB threshold set below.
    val big = ("payload-" * 8192)
    try {
      writeAndCommit(git, Map("keep.c" -> "int main(){}\n", "fixture.bin" -> big), "add both")
    } finally git.close()

    val srcRepo = openBare(dir.resolve("src/.git"))
    val stats = try {
      withStreamFileThreshold(4 * 1024) {
        runWalker(
          srcRepo,
          dstPath = dir.resolve("dst.git"),
          dbPath  = dir.resolve("db.sqlite"),
          command = shellScript(dir, "cat"),
          mask    = "\\.c$"          // fixture.bin is NOT masked: verbatim copy
        )
      }
    } finally srcRepo.close()

    stats.aborted shouldBe false

    val dst = openBare(dir.resolve("dst.git"))
    try {
      fileAtHead(dst, "master", "keep.c") shouldBe Some("int main(){}\n")
      val head = dst.exactRef("refs/heads/master")
      val rw = new RevWalk(dst)
      val id = try {
        val tw = org.eclipse.jgit.treewalk.TreeWalk.forPath(
          dst, "fixture.bin", rw.parseCommit(head.getObjectId).getTree)
        try tw.getObjectId(0) finally tw.close()
      } finally rw.close()

      id should not be ObjectId.zeroId
      new String(streamBlob(dst, id), UTF_8) shouldBe big
    } finally dst.close()
  }

  // -- oversized MASK-MATCHED blob (regression) -------------------------------
  //
  // qualcomm/qcom-embedded-power-measurement ended a whole project with rc 1:
  //   org.eclipse.jgit.errors.LargeObjectException: 205b0f65... exceeds size limit
  //     at cregit.blobexec.Walker.readBlob
  // on src/libraries/libexcel/excel.cpp — 102,897,757 bytes, 2,398,232 lines, a
  // dumpcpp dump of Microsoft Excel's COM type library. The mask DID select it
  // (.cpp), so the streaming fix on the pass-through path does not apply: the
  // tokenizer cannot be handed 98 MB, so the blob has to be excluded outright.

  test("the oversized limit is jgit's own threshold, not a constant above it") {
    val jgitDefault = new WindowCacheConfig().getStreamFileThreshold.toLong
    // Read from jgit, so the two cannot drift. An earlier draft of this fix used
    // a hand-picked 64 MiB, which leaves 50-64 MiB passing the size check and
    // then throwing inside getBytes anyway.
    Walker.MaxBlobBytes shouldEqual jgitDefault
    jgitDefault shouldEqual 50L * 1024 * 1024
    Walker.isOversized(200L * 1024 * 1024) shouldBe true
    Walker.isOversized(55L * 1024 * 1024) shouldBe true    // the band the 64 MiB constant missed
    Walker.isOversized(4096L) shouldBe false
  }

  /** Run the exclusion end to end on `mode`, with jgit's threshold lowered so a
    * 78 KB fixture reproduces a 98 MB blob in milliseconds.
    *
    * The fixture size is the point: 78 KB is far BELOW Walker.MaxBlobBytes, so
    * the size fast path cannot see it and only the LargeObjectException catch
    * can. That is exactly the band a bare size check gets wrong. */
  private def checkOversizedExclusion(label: String, pipeline: Boolean, pipelineTrees: Boolean): Unit = {
    val dir = freshWorkDir("oversized-masked-" + label)
    val git = initSrc(dir.resolve("src"))
    val generated = "// generated by dumpcpp\n" * 3400     // ~78 KB, >> the 4 KiB threshold below
    try {
      writeAndCommit(
        git,
        Map("keep.c" -> "int main(){}\n", "generated/excel.c" -> generated),
        "add a normal source file and a generated one")
    } finally git.close()

    // Proof that the fast path is not what fires here.
    Walker.isOversized(generated.length.toLong) shouldBe false

    val srcRepo = openBare(dir.resolve("src/.git"))
    val stats = try {
      withStreamFileThreshold(4 * 1024) {
        runWalker(
          srcRepo,
          dstPath = dir.resolve("dst.git"),
          dbPath  = dir.resolve("db.sqlite"),
          command = shellScript(dir, "cat"),
          mask    = "\\.c$",                  // generated/excel.c IS masked
          pipeline = pipeline,
          pipelineTrees = pipelineTrees
        )
      }
    } finally srcRepo.close()

    withClue(s"mode=$label: ") {
      stats.aborted shouldBe false             // the walk carries on
      stats.commitsProcessed shouldEqual 1
      stats.blobsOversized shouldEqual 1L
      stats.blobsTimedOut shouldEqual 0L       // not a timeout: must not block publication
    }

    val dst = openBare(dir.resolve("dst.git"))
    try {
      withClue(s"mode=$label: ") {
        fileAtHead(dst, "master", "keep.c") shouldBe Some("int main(){}\n")
        // Excluded means absent. Leaving it as raw source would put 2.4M
        // untokenized lines through blame and into the dataset.
        fileAtHead(dst, "master", "generated/excel.c") shouldBe None
      }
    } finally dst.close()
  }

  test("a mask-matched blob jgit refuses to materialise is excluded, not fatal (serial)") {
    checkOversizedExclusion("serial", pipeline = false, pipelineTrees = false)
  }

  test("a mask-matched blob jgit refuses to materialise is excluded, not fatal (pipeline)") {
    // The mode run_pipeline_process.sh actually uses for S/M projects.
    checkOversizedExclusion("pipeline", pipeline = true, pipelineTrees = false)
  }

  test("a mask-matched blob jgit refuses to materialise is excluded, not fatal (pipeline-trees)") {
    checkOversizedExclusion("pipeline-trees", pipeline = false, pipelineTrees = true)
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
