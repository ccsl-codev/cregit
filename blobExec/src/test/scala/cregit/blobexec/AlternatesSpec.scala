package cregit.blobexec

import org.eclipse.jgit.api.Git
import org.eclipse.jgit.internal.storage.file.FileRepository
import org.eclipse.jgit.lib.ObjectId
import org.scalatest.BeforeAndAfterAll
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters._
import scala.sys.process._

/** `--alternates`: dst borrows src's objects instead of copying the original
  * blobs, and `git repack -a -d` (no -l) then makes dst whole. The output must be
  * the same objects, refs and mapping rows as a run that copies. */
class AlternatesSpec extends AnyFunSuite with Matchers with BeforeAndAfterAll {

  private val workRoot: Path = Files.createTempDirectory("alternates-")

  override def afterAll(): Unit = deleteRecursive(workRoot)

  private def deleteRecursive(p: Path): Unit =
    if (Files.exists(p)) {
      if (Files.isDirectory(p)) Files.list(p).iterator().asScala.toList.foreach(deleteRecursive)
      Files.delete(p)
    }

  private val Mask = """\.[ch]$"""

  private def commit(git: Git, files: Map[String, String], message: String): Unit = {
    val wt = git.getRepository.getWorkTree.toPath
    files.foreach { case (rel, body) =>
      val p = wt.resolve(rel)
      Files.createDirectories(p.getParent)
      Files.writeString(p, body)
    }
    files.keys.foreach(f => git.add().addFilepattern(f).call())
    git.commit().setMessage(message)
      .setAuthor("Tester", "t@example.org").setCommitter("Tester", "t@example.org").call()
    ()
  }

  /** Five commits on master plus a side branch. a.c/b.h are tokenized; the rest
    * pass through, among them a blob that only an old commit holds. */
  private def sourceRepo(dir: Path): Git = {
    Files.createDirectories(dir)
    val git = Git.init().setDirectory(dir.toFile).setBare(false).call()
    commit(git, Map("a.c" -> "int a;\n", "README.md" -> "readme 1\n", "docs/x.txt" -> "x\n"), "c1")
    commit(git, Map("b.h" -> "int b;\n", "README.md" -> "readme 2\n"), "c2")
    commit(git, Map("a.c" -> "int a = BOOM;\n", "docs/y.txt" -> "y\n"), "c3")
    git.branchCreate().setName("side").call()
    commit(git, Map("lib/z.c" -> "int z;\n", "README.md" -> "readme 3\n"), "c4")
    commit(git, Map("docs/x.txt" -> "x2\n"), "c5")
    git
  }

  /** Uppercases its input; fails on a BOOM blob unless `flag` exists. */
  private def command(dir: Path, flag: Path): String = {
    val f = dir.resolve("cmd.sh")
    Files.writeString(f,
      s"""#!/bin/sh
         |c=$$(cat)
         |case "$$c" in *BOOM*) [ -e '$flag' ] || exit 1 ;; esac
         |printf '%s\\n' "$$c" | tr a-z A-Z
         |""".stripMargin)
    f.toFile.setExecutable(true)
    f.toAbsolutePath.toString
  }

  private def walk(src: FileRepository, dstPath: Path, db: Path, cmd: String,
                   borrow: Boolean, abortOnError: Boolean = false,
                   commitsPerTransaction: Int = Walker.DefaultCommitsPerTransaction): WalkStats = {
    val incremental = Files.isDirectory(dstPath)
    val dst = Main.openOrInitDst(dstPath, Option.when(borrow)(src.getObjectsDirectory.toPath))
    val mapping = Mapping.open(db, cmd, Mask)
    try new Walker(src, dst, mapping, Mask.r, cmd, abortOnError, 2, pipeline = true,
                   destinationMayContainObjects = incremental, borrowOriginalObjects = borrow,
                   denylist = BlobDenylist.empty, commitsPerTransaction = commitsPerTransaction).run()
    finally { mapping.close(); dst.close() }
  }

  private def git(gitDir: Path, args: String*): (Int, String) = {
    val out = new StringBuilder
    val rc = Process(Seq("git", "--git-dir", gitDir.toString) ++ args)
      .!(ProcessLogger(l => out.append(l).append('\n'), _ => ()))
    (rc, out.toString)
  }

  private def reachable(gitDir: Path): Vector[String] = {
    val (rc, out) = git(gitDir, "rev-list", "--objects", "--all")
    rc shouldBe 0
    out.linesIterator.map(_.takeWhile(_ != ' ')).toVector.sorted
  }

  private def refs(gitDir: Path): String = git(gitDir, "for-each-ref", "--format=%(objectname) %(refname)")._2

  private def table(db: Path, sql: String): Vector[String] = {
    val c = java.sql.DriverManager.getConnection(s"jdbc:sqlite:$db")
    try {
      val rs = c.createStatement().executeQuery(sql)
      val b = Vector.newBuilder[String]
      while (rs.next()) b += (1 to rs.getMetaData.getColumnCount).map(rs.getString).mkString(" ")
      b.result()
    } finally c.close()
  }

  // processed_at is a wall-clock stamp, so it is left out.
  private val MappingQueries = Seq(
    "SELECT orig_commit, new_commit FROM commit_map ORDER BY 1",
    "SELECT orig_blob, path, new_blob FROM blob_map ORDER BY 1, 2",
    "SELECT orig_tree, new_tree FROM tree_map ORDER BY 1")

  private def alternates(dst: Path): Path = dst.resolve("objects/info/alternates")

  /** What run_pipeline_process.sh's dissolve_alternates does, minus logging. */
  private def dissolve(dst: Path): Unit = {
    git(dst, "repack", "-a", "-d")._1 shouldBe 0
    Files.delete(alternates(dst))
    git(dst, "fsck", "--connectivity-only", "--no-dangling")._1 shouldBe 0
  }

  test("a borrowing walk copies no original blob and, once repacked, equals a copying walk") {
    val w = Files.createTempDirectory(workRoot, "equal-")
    val src = sourceRepo(w.resolve("src")).getRepository.asInstanceOf[FileRepository]
    val flag = Files.createFile(w.resolve("flag"))
    val cmd = command(w, flag)

    val copied = walk(src, w.resolve("copy.git"), w.resolve("copy.db"), cmd, borrow = false)
    val borrowed = walk(src, w.resolve("borrow.git"), w.resolve("borrow.db"), cmd, borrow = true)

    copied.originalBlobCopies should be > 0L
    copied.originalBlobsBorrowed shouldBe 0L
    borrowed.originalBlobCopies shouldBe 0L
    borrowed.originalBlobsBorrowed shouldBe copied.originalBlobCopyRequests
    borrowed.commitsProcessed shouldBe 5

    val b = w.resolve("borrow.git")
    Files.readString(alternates(b)).trim shouldBe src.getObjectsDirectory.toPath.toAbsolutePath.normalize.toString

    // Without the file, dst is missing the borrowed blobs: the pack is not optional.
    Files.move(alternates(b), b.resolve("objects/info/alternates.aside"))
    git(b, "fsck", "--connectivity-only", "--no-dangling")._1 should not be 0
    Files.move(b.resolve("objects/info/alternates.aside"), alternates(b))

    dissolve(b)
    reachable(b) shouldBe reachable(w.resolve("copy.git"))
    refs(b) shouldBe refs(w.resolve("copy.git"))
    for (q <- MappingQueries)
      table(w.resolve("borrow.db"), q) shouldBe table(w.resolve("copy.db"), q)

    // A non-bare clone, as step 6 makes, reads every file.
    val clone = w.resolve("clone")
    Process(Seq("git", "clone", "-q", b.toString, clone.toString)).! shouldBe 0
    Files.readString(clone.resolve("README.md")) shouldBe "readme 3\n"
    Files.readString(clone.resolve("a.c")) shouldBe "INT A = BOOM;\n"
  }

  test("an interrupted borrowing walk resumes to the same result, with grouped transactions") {
    val w = Files.createTempDirectory(workRoot, "resume-")
    val src = sourceRepo(w.resolve("src")).getRepository.asInstanceOf[FileRepository]
    val flag = w.resolve("flag")
    val cmd = command(w, flag)
    val dst = w.resolve("dst.git")
    val db = w.resolve("dst.db")

    // c3's BOOM blob aborts the walk: c1 and c2 are done and must be durable even
    // though they share one still-open transaction group.
    val first = walk(src, dst, db, cmd, borrow = true, abortOnError = true, commitsPerTransaction = 100)
    first.aborted shouldBe true
    table(db, "SELECT count(*) FROM commit_map") shouldBe Vector("2")

    Files.createFile(flag)
    val second = walk(src, dst, db, cmd, borrow = true, abortOnError = true, commitsPerTransaction = 100)
    second.aborted shouldBe false
    second.commitsProcessed shouldBe 3
    dissolve(dst)

    // Reference: one uninterrupted copying walk, one commit per transaction.
    val ref = w.resolve("ref.git")
    walk(src, ref, w.resolve("ref.db"), cmd, borrow = false, commitsPerTransaction = 1).aborted shouldBe false
    reachable(dst) shouldBe reachable(ref)
    refs(dst) shouldBe refs(ref)
    for (q <- MappingQueries) table(db, q) shouldBe table(w.resolve("ref.db"), q)
  }

  test("a resume reuses the alternates file, rewriting it rather than appending") {
    val w = Files.createTempDirectory(workRoot, "rewrite-")
    val src = sourceRepo(w.resolve("src")).getRepository.asInstanceOf[FileRepository]
    val dst = w.resolve("dst.git")
    Main.openOrInitDst(dst, Some(src.getObjectsDirectory.toPath)).close()
    Main.openOrInitDst(dst, Some(src.getObjectsDirectory.toPath)).close()
    Files.readAllLines(alternates(dst)).asScala.toList shouldBe
      List(src.getObjectsDirectory.toPath.toAbsolutePath.normalize.toString)
    // And dst opened that way really reads src's objects.
    val r = Main.openOrInitDst(dst, Some(src.getObjectsDirectory.toPath))
    try r.getObjectDatabase.has(ObjectId.fromString(src.resolve("HEAD").name)) shouldBe true
    finally r.close()
  }
}
