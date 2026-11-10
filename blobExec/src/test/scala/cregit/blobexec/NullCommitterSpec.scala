package cregit.blobexec

import org.eclipse.jgit.api.Git
import org.eclipse.jgit.internal.storage.file.FileRepository
import org.eclipse.jgit.lib.{CommitBuilder, Constants, ObjectId, PersonIdent, Repository}
import org.eclipse.jgit.revwalk.RevWalk
import org.eclipse.jgit.storage.file.FileRepositoryBuilder
import org.scalatest.BeforeAndAfterAll
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}

/** A commit whose committer header JGit cannot read: git stores it, JGit's
  * RevCommit.getCommitterIdent returns null, and the rewrite used to stop with a
  * NullPointerException in CommitBuilder.build. */
class NullCommitterSpec extends AnyFunSuite with Matchers with BeforeAndAfterAll {

  private val workRoot: Path = Files.createTempDirectory("null-committer-")

  override def afterAll(): Unit = deleteRecursive(workRoot)

  private val authorLine = "author Alice Author <alice@example.org> 1700000000 +0200"

  private val variants = Seq(
    "no angle brackets" -> "committer bob@example.org 1700000100 -0300",
    "an empty committer value" -> "committer ",
    "no committer header" -> "")

  private final case class Fixture(repo: Repository, badSha: String, dir: Path)

  private def fixture(committerLine: String): Fixture = {
    val dir = Files.createTempDirectory(workRoot, "fixture-")
    val srcDir = dir.resolve("src")
    Files.createDirectories(srcDir)
    val git = Git.init().setDirectory(srcDir.toFile).call()
    Files.writeString(srcDir.resolve("a.c"), "alpha\n")
    git.add().addFilepattern(".").call()
    val first = git.commit().setMessage("a normal commit")
      .setAuthor("Tester", "t@example.org").setCommitter("Tester", "t@example.org").call()
    Files.writeString(srcDir.resolve("a.c"), "alpha\nbeta\n")
    git.add().addFilepattern(".").call()
    val head = git.getRepository.getFullBranch
    // The second tree comes from a normal commit; the branch then moves to a raw commit on that tree.
    val second = git.commit().setMessage("a commit with a broken committer header")
      .setAuthor("Tester", "t@example.org").setCommitter("Tester", "t@example.org").call()
    val raw = new StringBuilder
    raw.append(s"tree ${second.getTree.getId.name}\n")
    raw.append(s"parent ${first.getId.name}\n")
    raw.append(authorLine).append('\n')
    if (committerLine.nonEmpty) raw.append(committerLine).append('\n')
    raw.append("\nthe message of the broken commit\n")
    val ins = git.getRepository.newObjectInserter()
    val bad = try { val id = ins.insert(Constants.OBJ_COMMIT, raw.toString.getBytes(UTF_8)); ins.flush(); id } finally ins.close()
    val ru = git.getRepository.updateRef(head)
    ru.setNewObjectId(bad)
    ru.setForceUpdate(true)
    ru.update()
    Fixture(git.getRepository, bad.name, dir)
  }

  private def tokenizer(dir: Path): String = {
    val f = dir.resolve("tok.sh")
    Files.writeString(f, "#!/bin/sh\ntr a-z A-Z\n")
    f.toFile.setExecutable(true)
    f.toAbsolutePath.toString
  }

  private def walk(f: Fixture, mode: String): (WalkStats, Path, Path, String) = {
    val dstPath = f.dir.resolve("dst.git")
    val dbPath  = f.dir.resolve("blobmap.db")
    val command = tokenizer(f.dir)
    val dst = FileRepositoryBuilder.create(dstPath.toFile).asInstanceOf[FileRepository]
    dst.create(true)
    val mapping = Mapping.open(dbPath, command, """\.c$""")
    try {
      val stats = new Walker(
        f.repo, dst, mapping, """\.c$""".r, command,
        abortOnError = false,
        parallelism = 2,
        pipeline = mode == "pipeline",
        pipelineTrees = mode == "pipeline-trees"
      ).run()
      (stats, dstPath, dbPath, command)
    } finally { mapping.close(); dst.close() }
  }

  for ((label, line) <- variants) {
    test(s"the fixture is a commit that JGit cannot read a committer from ($label)") {
      val f = fixture(line)
      val rw = new RevWalk(f.repo)
      try {
        val rc = rw.parseCommit(ObjectId.fromString(f.badSha))
        rc.getAuthorIdent should not be null
        rc.getCommitterIdent shouldBe null
      } finally rw.close()
    }

    test(s"the unchanged construction fails with a NullPointerException ($label)") {
      val f = fixture(line)
      val rw = new RevWalk(f.repo)
      try {
        val rc = rw.parseCommit(ObjectId.fromString(f.badSha))
        val cb = new CommitBuilder
        cb.setAuthor(rc.getAuthorIdent)
        cb.setCommitter(rc.getCommitterIdent)
        cb.setMessage("m")
        cb.setTreeId(rc.getTree.getId)
        val ins = f.repo.newObjectInserter()
        try an[NullPointerException] should be thrownBy ins.insert(cb) finally ins.close()
      } finally rw.close()
    }

    for (mode <- Seq("serial", "pipeline", "pipeline-trees"))
      test(s"[$mode] the rewrite finishes and the committer is the author ($label)") {
        val f = fixture(line)
        val (stats, dstPath, dbPath, command) = walk(f, mode)
        stats.aborted shouldBe false
        stats.commitsProcessed shouldEqual 2
        Main.exitStatus(stats) shouldEqual 0
        val m = Mapping.open(dbPath, command, """\.c$""")
        try {
          val newSha = m.getCommit(f.badSha)
          newSha shouldBe defined
          val dst = FileRepositoryBuilder.create(dstPath.toFile).asInstanceOf[FileRepository]
          val rw = new RevWalk(dst)
          try {
            val rc = rw.parseCommit(ObjectId.fromString(newSha.get))
            rc.getAuthorIdent.getEmailAddress shouldEqual "alice@example.org"
            rc.getCommitterIdent should not be null
            rc.getCommitterIdent.getName shouldEqual rc.getAuthorIdent.getName
            rc.getCommitterIdent.getEmailAddress shouldEqual rc.getAuthorIdent.getEmailAddress
            rc.getCommitterIdent.getWhen shouldEqual rc.getAuthorIdent.getWhen
            rc.getFullMessage should include(f.badSha)
          } finally { rw.close(); dst.close() }
        } finally m.close()
      }
  }

  test("a normal commit keeps its own author and committer") {
    val a = new PersonIdent("A", "a@x.org")
    val c = new PersonIdent("C", "c@x.org")
    CommitIdents.resolve("sha", a, c, Array.emptyByteArray, report = _ => ()) shouldEqual ((a, c))
  }

  test("rule copy-author") {
    val a = new PersonIdent("A", "a@x.org")
    val (_, c) = CommitIdents.resolve("sha", a, null, Array.emptyByteArray, CommitIdents.CopyAuthor, _ => ())
    c shouldEqual a
  }

  test("rule empty: empty name and e-mail, the author date") {
    val a = new PersonIdent("A", "a@x.org")
    val (_, c) = CommitIdents.resolve("sha", a, null, Array.emptyByteArray, CommitIdents.Empty, _ => ())
    c.getName shouldEqual ""
    c.getEmailAddress shouldEqual ""
    c.getWhen shouldEqual a.getWhen
  }

  test("rule raw-header: the text, the e-mail token and the date of the header") {
    val a = new PersonIdent("A", "a@x.org")
    val raw = ("tree 0\n" + authorLine + "\ncommitter Bob Builder bob@example.org 1700000100 -0300\n\nm\n").getBytes(UTF_8)
    val (_, c) = CommitIdents.resolve("sha", a, null, raw, CommitIdents.RawHeader, _ => ())
    c.getName shouldEqual "Bob Builder"
    c.getEmailAddress shouldEqual "bob@example.org"
    c.getWhen.getTime shouldEqual 1700000100000L
  }

  test("rule raw-header without a committer header falls back to an empty committer") {
    val a = new PersonIdent("A", "a@x.org")
    val raw = ("tree 0\n" + authorLine + "\n\nm\n").getBytes(UTF_8)
    val (_, c) = CommitIdents.resolve("sha", a, null, raw, CommitIdents.RawHeader, _ => ())
    c.getName shouldEqual ""
    c.getEmailAddress shouldEqual ""
  }

  test("a commit without a readable author takes its committer as author") {
    val c = new PersonIdent("C", "c@x.org")
    val (a, c2) = CommitIdents.resolve("sha", null, c, Array.emptyByteArray, report = _ => ())
    a shouldEqual c
    c2 shouldEqual c
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
