package cregit.blobexec

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}

/** The memo layout has to match tokenizeByBlobId/tokenBySha.pl exactly, and the
  * two are written in different languages, so the agreement is asserted rather
  * than assumed. A silent disagreement here would look like a successful
  * invalidation that purged nothing. */
class TokenizerMemoSpec extends AnyFunSuite with Matchers {

  private def withTempDir[A](body: Path => A): A = {
    val d = Files.createTempDirectory("memo-spec-")
    try body(d)
    finally {
      def rm(p: Path): Unit = {
        if (Files.isDirectory(p)) { val s = Files.list(p); try s.forEach(rm) finally s.close() }
        Files.deleteIfExists(p)
        ()
      }
      rm(d)
    }
  }

  test("sha1Hex matches a known Digest::SHA value") {
    // printf 'int a;\n' | sha1sum
    TokenizerMemo.sha1Hex("int a;\n".getBytes(UTF_8)) shouldEqual
      "56f54d1636dfec63c3e1586e5e4bdc9a455bb9f6"
    // printf 'fn main() {}\n' | sha1sum
    TokenizerMemo.sha1Hex("fn main() {}\n".getBytes(UTF_8)) shouldEqual
      "c135f41eadc2f7248d34e03e64a04e8420bf5e4b"
  }

  test("the path is <root>/xx/yy/<sha1>, the fan-out tokenBySha.pl builds") {
    val sha = "56f54d1636dfec63c3e1586e5e4bdc9a455bb9f6"
    TokenizerMemo.relativePathFor(sha) shouldEqual
      "56/f5/56f54d1636dfec63c3e1586e5e4bdc9a455bb9f6"
    // Both halves come from the FRONT of the sha (substr 0,2 then 2,2), which is
    // easy to get wrong as 0,2 then -2.
    TokenizerMemo.relativePathFor(sha) should startWith("56/f5/")
  }

  test("sha1Hex agrees with perl's Digest::SHA on bytes perl might mangle") {
    // The real inputs are arbitrary source bytes, including CRLF and high bytes.
    // Perl reads the blob from stdin and hashes it; if either side treated the
    // bytes as text the keys would diverge for exactly those files.
    val perl = Option(System.getenv("PATH")).toVector
      .flatMap(_.split(java.io.File.pathSeparator))
      .map(p => Path.of(p, "perl"))
      .find(Files.isExecutable)
    assume(perl.isDefined, "perl not on PATH")

    val samples = Vector(
      "int a;\n",
      "a\r\nb\r\n",
      "no trailing newline",
      "caf\u00e9 \u00fcber\n",
      ""
    )
    withTempDir { d =>
      samples.foreach { s =>
        val f = d.resolve("input.bin")
        Files.write(f, s.getBytes(UTF_8))
        val pb = new ProcessBuilder(
          perl.get.toString, "-MDigest::SHA=sha1_hex", "-e",
          "local $/; open(F, '<', $ARGV[0]) or die; binmode F; print sha1_hex(<F> // '')",
          f.toString)
        pb.redirectErrorStream(true)
        val proc = pb.start()
        val out = new String(proc.getInputStream.readAllBytes(), UTF_8).trim
        proc.waitFor()
        withClue(s"sample [${s.replace("\n", "\\n").replace("\r", "\\r")}] perl said [$out]: ") {
          out shouldEqual TokenizerMemo.sha1Hex(s.getBytes(UTF_8))
        }
      }
    }
  }

  test("purge deletes the entries of the named blobs and leaves the rest alone") {
    withTempDir { root =>
      val contents = Map(
        "blob-rs-1" -> "fn one() {}\n",
        "blob-rs-2" -> "fn two() {}\n",
        "blob-c-1"  -> "int c;\n"
      )
      contents.values.foreach { s =>
        val p = TokenizerMemo.entryFor(root, TokenizerMemo.sha1Hex(s.getBytes(UTF_8)))
        Files.createDirectories(p.getParent)
        Files.writeString(p, "TOKENS FOR " + s)
      }

      val report = TokenizerMemo.purge(
        root,
        Vector("blob-rs-1", "blob-rs-2"),
        sha => contents.get(sha).map(_.getBytes(UTF_8)))

      report.examined shouldEqual 2L
      report.deleted shouldEqual 2L
      report.missing shouldEqual 0L
      report.unreadable shouldEqual 0L

      Files.exists(TokenizerMemo.entryFor(root,
        TokenizerMemo.sha1Hex(contents("blob-rs-1").getBytes(UTF_8)))) shouldBe false
      // The whole point of being selective: the C entry is still a valid
      // tokenization and re-doing it would cost 88% of the pipeline for nothing.
      Files.exists(TokenizerMemo.entryFor(root,
        TokenizerMemo.sha1Hex(contents("blob-c-1").getBytes(UTF_8)))) shouldBe true
    }
  }

  test("a blob with no memo entry is counted missing, not deleted") {
    withTempDir { root =>
      val report = TokenizerMemo.purge(root, Vector("absent"), _ => Some("x\n".getBytes(UTF_8)))
      report.deleted shouldEqual 0L
      report.missing shouldEqual 1L
    }
  }

  test("a blob whose bytes cannot be read is counted, not skipped in silence") {
    withTempDir { root =>
      val report = TokenizerMemo.purge(root, Vector("oversized"), _ => None)
      report.examined shouldEqual 1L
      report.unreadable shouldEqual 1L
      report.deleted shouldEqual 0L
    }
  }
}
