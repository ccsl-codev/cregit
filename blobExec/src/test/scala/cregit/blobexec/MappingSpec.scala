package cregit.blobexec

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

import java.nio.file.Files

class MappingSpec extends AnyFunSuite with Matchers {

  private def withMapping[A](command: String = "/bin/cat", mask: String = ".*")(body: Mapping => A): A = {
    val m = Mapping.openInMemory(command, mask)
    try body(m) finally m.close()
  }

  test("schema is created and put/get round-trips work for all maps (keyed by full path)") {
    withMapping() { m =>
      m.getBlob("orig", "src/foo.c") shouldBe None
      m.putBlob("orig", "src/foo.c", "newblob")
      m.getBlob("orig", "src/foo.c") shouldBe Some("newblob")

      // Same orig blob under a different full path is a distinct row.
      m.putBlob("orig", "lib/foo.c", "otherblob")
      m.getBlob("orig", "lib/foo.c") shouldBe Some("otherblob")
      m.getBlob("orig", "src/foo.c") shouldBe Some("newblob")

      m.putCommit("origc", "newc")
      m.getCommit("origc") shouldBe Some("newc")

      m.putTree("origt", "newt")
      m.getTree("origt") shouldBe Some("newt")
    }
  }

  test("processed_at is stamped automatically and stays put on INSERT OR IGNORE") {
    import java.sql.DriverManager
    val tmp = Files.createTempFile("mapping-pa-", ".db")
    try {
      val m1 = Mapping.open(tmp, "/bin/cat", ".*")
      try m1.putBlob("orig", "src/x.c", "new") finally m1.close()

      // Read the timestamp directly via JDBC.
      val conn = DriverManager.getConnection(s"jdbc:sqlite:${tmp.toAbsolutePath}")
      try {
        val readAt: () => Long = () => {
          val rs = conn.createStatement().executeQuery(
            "SELECT processed_at FROM blob_map WHERE orig_blob='orig' AND path='src/x.c'")
          try { rs.next(); rs.getLong(1) } finally rs.close()
        }
        val first = readAt()
        first should be > 0L

        // Sleep past one second so a new strftime('%s','now') would differ,
        // then re-put. With INSERT OR IGNORE the row is preserved → same ts.
        Thread.sleep(1100)
        val m2 = Mapping.open(tmp, "/bin/cat", ".*")
        try m2.putBlob("orig", "src/x.c", "new") finally m2.close()
        readAt() shouldEqual first
      } finally conn.close()
    } finally Files.deleteIfExists(tmp)
  }

  test("inTx commits on success") {
    withMapping() { m =>
      m.inTx {
        m.putCommit("c1", "n1")
        m.putCommit("c2", "n2")
      }
      m.getCommit("c1") shouldBe Some("n1")
      m.getCommit("c2") shouldBe Some("n2")
    }
  }

  test("inTx rolls back on throw") {
    withMapping() { m =>
      m.putCommit("c0", "n0")
      val ex = intercept[RuntimeException] {
        m.inTx {
          m.putCommit("c1", "n1")
          throw new RuntimeException("boom")
        }
      }
      ex.getMessage shouldEqual "boom"
      m.getCommit("c0") shouldBe Some("n0")
      m.getCommit("c1") shouldBe None
    }
  }

  test("open on existing file is idempotent and preserves rows") {
    val tmp = Files.createTempFile("mapping-spec-", ".db")
    try {
      val m1 = Mapping.open(tmp, "/bin/cat", ".*")
      try {
        m1.putCommit("c1", "n1")
        m1.putBlob("b1", "x.c", "nb1")
      } finally m1.close()

      val m2 = Mapping.open(tmp, "/bin/cat", ".*")
      try {
        m2.getCommit("c1") shouldBe Some("n1")
        m2.getBlob("b1", "x.c") shouldBe Some("nb1")
      } finally m2.close()
    } finally Files.deleteIfExists(tmp)
  }

  test("meta mismatch on command throws") {
    val tmp = Files.createTempFile("mapping-spec-", ".db")
    try {
      val m1 = Mapping.open(tmp, "/bin/cat", ".*")
      m1.close()
      val ex = intercept[Mapping.MetaMismatchException] {
        Mapping.open(tmp, "/bin/echo", ".*")
      }
      ex.getMessage should include("command")
    } finally Files.deleteIfExists(tmp)
  }

  test("meta mismatch on mask throws") {
    val tmp = Files.createTempFile("mapping-spec-", ".db")
    try {
      val m1 = Mapping.open(tmp, "/bin/cat", ".*")
      m1.close()
      val ex = intercept[Mapping.MetaMismatchException] {
        Mapping.open(tmp, "/bin/cat", """\.c$""")
      }
      ex.getMessage should include("mask")
    } finally Files.deleteIfExists(tmp)
  }

  // The mask widening (per-language masks -> one universal mask) is exactly the
  // change this refusal exists for, so pin it with the real strings rather than
  // ".*" vs "\.c$". A project whose mask changes MUST be rebuilt: its tree_map
  // rows were assembled from the files the old mask selected, so reusing them
  // would produce a repository that silently omits every newly selected file —
  // and the omission is invisible afterwards, because the trees look complete.
  //
  // If someone later "fixes" this into a warning to make a resume work, this test
  // is what says no. The rows below are the ones that would be reused.
  test("a widened mask is refused on resume, not silently reused") {
    val oldMask = """\.(c|cc|cp|cpp|cxx|h|hh|hpp)$"""
    val newMask = """(?i)\.(c|c\+\+|cc|cp|cpp|cxx|h|h\+\+|hh|hpp|hxx|java|rs|tcc)$"""
    val tmp = Files.createTempFile("mapping-mask-widen-", ".db")
    try {
      val m1 = Mapping.open(tmp, "/bin/cat", oldMask)
      try {
        m1.putTree("origtree", "newtree")
        m1.putCommit("origcommit", "newcommit")
        m1.putBlob("origblob", "src/a.cpp", "newblob")
      } finally m1.close()

      val ex = intercept[Mapping.MetaMismatchException] {
        Mapping.open(tmp, "/bin/cat", newMask)
      }
      ex.getMessage should include("mask")
      ex.getMessage should include(oldMask)
      ex.getMessage should include(newMask)

      // Same mask still resumes, and the rows are still there — the refusal is
      // about the mask changing, not about resuming at all.
      val m2 = Mapping.open(tmp, "/bin/cat", oldMask)
      try {
        m2.getTree("origtree") shouldBe Some("newtree")
        m2.getBlob("origblob", "src/a.cpp") shouldBe Some("newblob")
      } finally m2.close()
    } finally Files.deleteIfExists(tmp)
  }

  test("the warm-DB fallback refuses a widened mask too") {
    // shard_build.sh passes a prior run's DB as --warm. Its blob ids were minted
    // under that run's mask, so a mask change makes them foreign; openWarm has
    // its own check and it must not drift from checkOrSetMeta's.
    val oldMask = """\.(c|cc|cp|cpp|cxx|h|hh|hpp)$"""
    val newMask = """(?i)\.(c|c\+\+|cc|cp|cpp|cxx|h|h\+\+|hh|hpp|hxx|java|rs|tcc)$"""
    val warm = Files.createTempFile("mapping-warm-", ".db")
    val cold = Files.createTempFile("mapping-cold-", ".db")
    try {
      val w = Mapping.open(warm, "/bin/cat", oldMask)
      try w.putBlob("origblob", "src/a.cpp", "newblob") finally w.close()

      Files.deleteIfExists(cold)
      val ex = intercept[Mapping.MetaMismatchException] {
        Mapping.open(cold, "/bin/cat", newMask, Some(warm))
      }
      ex.getMessage should include("mask")
    } finally {
      Files.deleteIfExists(warm)
      Files.deleteIfExists(cold)
    }
  }

  // -- --mask-widened ---------------------------------------------------------
  //
  // The opt-in beside the refusal above. Everything here exists because reusing
  // blob_map across a widening is only safe under conditions that have to be
  // CHECKED, not assumed, and one of those conditions is not obvious: blob_map
  // holds identity rows for blobs the old mask did NOT select, and a wider mask
  // selects some of them. Serving one of those as a cache hit puts raw source
  // into the tokenized repository, which is the one outcome that silently
  // corrupts the dataset's meaning.

  private val OldCMask  = """\.[ch]$"""
  private val OldCppMask = """\.(c|cc|cp|cpp|cxx|h|hh|hpp)$"""
  private val UniversalMask = """(?i)\.(c|c\+\+|cc|cp|cpp|cxx|h|h\+\+|hh|hpp|hxx|java|rs|tcc)$"""

  /** A widening that says every id resolves, collecting the report. */
  private def widening(resolves: String => Boolean = _ => true) = {
    val log = new StringBuilder
    (Mapping.MaskWidening(
      newBlobResolves = resolves,
      report = m => { log.append(m); log.append('\n'); () },
      nowEpochSeconds = () => 1700000000L
    ), log)
  }

  /** A blob map as a real run leaves it: tokenized rows for the paths the mask
    * selected, identity rows for every other blob the walker passed through, plus
    * trees, a commit and a ref. */
  private def seedUnderOldMask(tmp: java.nio.file.Path, mask: String): Unit = {
    val m = Mapping.open(tmp, "/bin/cat", mask)
    try {
      m.putBlob("b1", "src/a.c", "tok1")
      m.putBlob("b2", "src/b.h", "tok2")
      // Identity rows. These are the dangerous ones: under the universal mask
      // a.cpp and T.java ARE selected, so a reused row would serve raw source.
      m.putBlob("b3", "src/a.cpp", "b3")
      m.putBlob("b4", "src/T.java", "b4")
      // And these are paths no mask will ever select.
      m.putBlob("b5", "README.md", "b5")
      m.putBlob("b6", "CMakeLists.txt", "b6")
      m.putTree("t1", "nt1")
      m.putCommit("c1", "nc1")
      m.putRef(Mapping.RefRow("refs/heads/main", "head", "c1", "nc1", "c1", "nc1"))
    } finally m.close()
  }

  test("without the flag a mask change is still refused: the default does not move") {
    val tmp = Files.createTempFile("mapping-widen-default-", ".db")
    try {
      seedUnderOldMask(tmp, OldCMask)
      val ex = intercept[Mapping.MetaMismatchException] {
        Mapping.open(tmp, "/bin/cat", UniversalMask)
      }
      ex.getMessage should include("mask")
      // ...and it changed nothing, so the flag can still be used afterwards.
      val m = Mapping.open(tmp, "/bin/cat", OldCMask)
      try {
        m.getMeta("mask") shouldBe Some(OldCMask)
        m.getTree("t1") shouldBe Some("nt1")
        m.getCommit("c1") shouldBe Some("nc1")
        m.getBlob("b3", "src/a.cpp") shouldBe Some("b3")
      } finally m.close()
    } finally Files.deleteIfExists(tmp)
  }

  test("with the flag: tokenized rows are kept, identity rows and trees and commits go") {
    val tmp = Files.createTempFile("mapping-widen-", ".db")
    try {
      seedUnderOldMask(tmp, OldCMask)
      val (w, log) = widening()
      val m = Mapping.open(tmp, "/bin/cat", UniversalMask, None, Some(w))
      try {
        // Kept: the real tokenizations.
        m.getBlob("b1", "src/a.c") shouldBe Some("tok1")
        m.getBlob("b2", "src/b.h") shouldBe Some("tok2")
        // Gone: every identity row, so the newly selected paths are cache MISSES
        // and get tokenized instead of passing through as raw source.
        m.getBlob("b3", "src/a.cpp") shouldBe None
        m.getBlob("b4", "src/T.java") shouldBe None
        m.getBlob("b5", "README.md") shouldBe None
        m.getBlob("b6", "CMakeLists.txt") shouldBe None
        // Gone: trees, commits and the refs that name them.
        m.getTree("t1") shouldBe None
        m.getCommit("c1") shouldBe None
        m.getRef("refs/heads/main") shouldBe None
        m.allCommitOrigShas shouldBe empty
        // The stored mask is now the new one, with the old one kept as provenance.
        m.getMeta("mask") shouldBe Some(UniversalMask)
        m.getMeta(Mapping.MaskWidenedFromKey) shouldBe Some(OldCMask)
        m.getMeta(Mapping.MaskWidenedAtKey) shouldBe Some("1700000000")
      } finally m.close()
      log.toString should include("KEPT    2 tokenized blob_map rows")
      log.toString should include("DROPPED 4 identity blob_map rows")
      log.toString should include("DROPPED 1 tree_map rows")
    } finally Files.deleteIfExists(tmp)
  }

  test("after a widening the same run resumes normally, with no flag needed") {
    val tmp = Files.createTempFile("mapping-widen-again-", ".db")
    try {
      seedUnderOldMask(tmp, OldCMask)
      val (w, _) = widening()
      Mapping.open(tmp, "/bin/cat", UniversalMask, None, Some(w)).close()
      val m = Mapping.open(tmp, "/bin/cat", UniversalMask)
      try m.getBlob("b1", "src/a.c") shouldBe Some("tok1") finally m.close()
    } finally Files.deleteIfExists(tmp)
  }

  // THE check. A narrowing is refused on the evidence of the rows, and the
  // databases are left exactly as they were — the operator can still resume
  // under the old mask afterwards, which is the proof that nothing was touched.
  test("a NARROWING mask is refused, names the path, and leaves the databases untouched") {
    val tmp = Files.createTempFile("mapping-narrow-", ".db")
    try {
      // Tokenized under the C++ mask, so src/a.cpp is a real tokenization.
      val seed = Mapping.open(tmp, "/bin/cat", OldCppMask)
      try {
        seed.putBlob("b1", "src/a.c", "tok1")
        seed.putBlob("b2", "src/a.cpp", "tok2")
        seed.putBlob("b3", "README.md", "b3")
        seed.putTree("t1", "nt1")
        seed.putCommit("c1", "nc1")
        seed.putRef(Mapping.RefRow("refs/heads/main", "head", "c1", "nc1", "c1", "nc1"))
      } finally seed.close()

      val (w, log) = widening()
      val ex = intercept[Mapping.MaskNarrowedException] {
        // C only. It drops .cpp, so it is a narrowing wearing the flag's name.
        Mapping.open(tmp, "/bin/cat", OldCMask, None, Some(w))
      }
      ex.getMessage should include("src/a.cpp")
      ex.getMessage should include(OldCMask)
      ex.getMessage should include(OldCppMask)
      ex.getMessage should include("NARROWING")
      log.toString shouldBe ""

      // Untouched: every row and the recorded mask.
      val m = Mapping.open(tmp, "/bin/cat", OldCppMask)
      try {
        m.getMeta("mask") shouldBe Some(OldCppMask)
        m.getMeta(Mapping.MaskWidenedFromKey) shouldBe None
        m.getBlob("b1", "src/a.c") shouldBe Some("tok1")
        m.getBlob("b2", "src/a.cpp") shouldBe Some("tok2")
        m.getBlob("b3", "README.md") shouldBe Some("b3")
        m.getTree("t1") shouldBe Some("nt1")
        m.getCommit("c1") shouldBe Some("nc1")
        m.getRef("refs/heads/main").map(_.newCommit) shouldBe Some("nc1")
      } finally m.close()
    } finally Files.deleteIfExists(tmp)
  }

  // An identity row on a path no mask selects must NOT be read as a narrowing.
  // Every blob map on this machine holds such rows (README.md, CMakeLists.txt,
  // .pdf), so a check over all rows rather than tokenized ones refuses every
  // project and the feature never fires once.
  test("identity rows on never-selected paths do not count as a narrowing") {
    val tmp = Files.createTempFile("mapping-identity-ok-", ".db")
    try {
      val seed = Mapping.open(tmp, "/bin/cat", OldCMask)
      try {
        seed.putBlob("b1", "src/a.c", "tok1")
        seed.putBlob("b5", "README.md", "b5")
        seed.putBlob("b6", "docs/manual.pdf", "b6")
        seed.putBlob("b7", "Makefile", "b7")
      } finally seed.close()
      val (w, _) = widening()
      val m = Mapping.open(tmp, "/bin/cat", UniversalMask, None, Some(w))
      try m.getMeta("mask") shouldBe Some(UniversalMask) finally m.close()
    } finally Files.deleteIfExists(tmp)
  }

  test("the narrowing check reads the basename, the way Walker matches the mask") {
    // Walker matches against tw.getNameString, not the full path. Replicate it,
    // or the check answers a question the walker never asks.
    Mapping.basename("src/kernel/a.c") shouldEqual "a.c"
    Mapping.basename("a.c") shouldEqual "a.c"
    Mapping.basename("dir.c/plain") shouldEqual "plain"
  }

  test("a new_blob missing from dst is refused, and nothing is changed") {
    val tmp = Files.createTempFile("mapping-dangling-", ".db")
    try {
      seedUnderOldMask(tmp, OldCMask)
      val (w, log) = widening(resolves = _ => false)
      val ex = intercept[Mapping.DanglingNewBlobException] {
        Mapping.open(tmp, "/bin/cat", UniversalMask, None, Some(w))
      }
      ex.getMessage should include("does not exist in the destination repository")
      ex.getMessage should include("step 2")
      log.toString shouldBe ""

      val m = Mapping.open(tmp, "/bin/cat", OldCMask)
      try {
        m.getMeta("mask") shouldBe Some(OldCMask)
        m.getTree("t1") shouldBe Some("nt1")
        m.getBlob("b3", "src/a.cpp") shouldBe Some("b3")
      } finally m.close()
    } finally Files.deleteIfExists(tmp)
  }

  test("the reachability check probes only retained rows, never identity ones") {
    // An identity row's new_blob is the ORIGINAL blob id, which lives in src and
    // need not be in dst at all until tree assembly copies it. Probing one would
    // refuse a perfectly good resume.
    val tmp = Files.createTempFile("mapping-probe-set-", ".db")
    try {
      seedUnderOldMask(tmp, OldCMask)
      val probed = scala.collection.mutable.Set.empty[String]
      val (w, _) = widening(resolves = id => { probed += id; true })
      Mapping.open(tmp, "/bin/cat", UniversalMask, None, Some(w)).close()
      probed shouldEqual Set("tok1", "tok2")
    } finally Files.deleteIfExists(tmp)
  }

  test("the command is still checked under the flag: a different tokenizer is refused") {
    // The widening argument is about WHICH blobs, and says nothing about a
    // different program producing different tokens from the same bytes.
    val tmp = Files.createTempFile("mapping-widen-cmd-", ".db")
    try {
      seedUnderOldMask(tmp, OldCMask)
      val (w, _) = widening()
      val ex = intercept[Mapping.MetaMismatchException] {
        Mapping.open(tmp, "/bin/echo", UniversalMask, None, Some(w))
      }
      ex.getMessage should include("command")
    } finally Files.deleteIfExists(tmp)
  }

  test("the flag is a no-op on a fresh blob map and on an unchanged mask") {
    val fresh = Files.createTempFile("mapping-widen-fresh-", ".db")
    Files.deleteIfExists(fresh)
    try {
      val (w1, log1) = widening()
      val m1 = Mapping.open(fresh, "/bin/cat", UniversalMask, None, Some(w1))
      try m1.getMeta("mask") shouldBe Some(UniversalMask) finally m1.close()
      log1.toString should include("nothing to reuse")
      log1.toString should include("nothing to check")

      val (w2, log2) = widening()
      val m2 = Mapping.open(fresh, "/bin/cat", UniversalMask, None, Some(w2))
      try m2.getMeta(Mapping.MaskWidenedFromKey) shouldBe None finally m2.close()
      log2.toString should include("ordinary resume")
    } finally Files.deleteIfExists(fresh)
  }

  test("rowCounts separates tokenized rows from identity rows") {
    withMapping() { m =>
      m.putBlob("b1", "a.c", "tok1")
      m.putBlob("b2", "b.c", "tok2")
      m.putBlob("b3", "README.md", "b3")
      m.putTree("t1", "nt1")
      m.putCommit("c1", "nc1")
      val c = m.rowCounts
      c.blobTokenized shouldEqual 2L
      c.blobIdentity shouldEqual 1L
      c.tree shouldEqual 1L
      c.commit shouldEqual 1L
      c.ref shouldEqual 0L
    }
  }

  test("the sample is spread across the table, bounded, and deterministic") {
    val tmp = Files.createTempFile("mapping-sample-", ".db")
    try {
      val m = Mapping.open(tmp, "/bin/cat", OldCMask)
      try {
        (0 until 100).foreach(i => m.putBlob(f"orig$i%03d", f"f$i%03d.c", f"tok$i%03d"))
        m.sampleTokenizedNewBlobs(10) should have size 10
        // Spread, not the first ten: a head sample only sees the first commit.
        m.sampleTokenizedNewBlobs(10).map(_._2) shouldEqual
          m.sampleTokenizedNewBlobs(10).map(_._2)
        m.sampleTokenizedNewBlobs(10).map(_._2).toSet should contain("tok090")
        // Asking for more than exists returns what exists.
        m.sampleTokenizedNewBlobs(1000) should have size 100
      } finally m.close()
    } finally Files.deleteIfExists(tmp)
  }

  test("the sample of an all-identity blob map is empty rather than an error") {
    withMapping() { m =>
      m.putBlob("b1", "README.md", "b1")
      m.sampleTokenizedNewBlobs(16) shouldBe empty
    }
  }

  test("allCommitOrigShas returns all rows") {
    withMapping() { m =>
      m.putCommit("a", "x")
      m.putCommit("b", "y")
      m.putCommit("c", "z")
      m.allCommitOrigShas.toSet shouldEqual Set("a", "b", "c")
    }
  }
}
