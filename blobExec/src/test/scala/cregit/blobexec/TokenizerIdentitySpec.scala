package cregit.blobexec

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

class TokenizerIdentitySpec extends AnyFunSuite with Matchers {

  test("one extension round-trips") {
    TokenizerIdentity.parse("rs=deadbeefcafe") shouldBe
      Right(TokenizerIdentity(Map("rs" -> "deadbeefcafe")))
  }

  test("several extensions round-trip, and render is sorted and stable") {
    val id = TokenizerIdentity.parse("h=aaaaaaaa,c=bbbbbbbb,rs=cccccccc").toOption.get
    id.extensions shouldBe Set("c", "h", "rs")
    id.render shouldEqual "c=bbbbbbbb,h=aaaaaaaa,rs=cccccccc"
    // A rendering that depended on map order would make every log line and every
    // refusal message a different string for the same fact.
    TokenizerIdentity.parse("c=bbbbbbbb,rs=cccccccc,h=aaaaaaaa").toOption.get.render shouldEqual id.render
  }

  test("c++ and h++ are extensions, because CregitLanguages.pm says so") {
    val id = TokenizerIdentity.parse("c++=aaaaaaaa,h++=bbbbbbbb").toOption.get
    id.extensions shouldBe Set("c++", "h++")
  }

  test("an empty spec is refused, not read as 'no extensions'") {
    // "no extensions" would make --tokenizer-identity a silent no-op, which is
    // the whole failure mode this mechanism exists to remove.
    TokenizerIdentity.parse("").isLeft shouldBe true
    TokenizerIdentity.parse("   ").isLeft shouldBe true
  }

  test("a leading dot is refused: the tables spell extensions without one") {
    TokenizerIdentity.parse(".rs=deadbeefcafe").isLeft shouldBe true
  }

  test("an uppercase extension is refused rather than silently lowercased") {
    TokenizerIdentity.parse("RS=deadbeefcafe").isLeft shouldBe true
  }

  test("a value that is not a long-enough hex digest is refused") {
    TokenizerIdentity.parse("rs=zzzz").isLeft shouldBe true
    TokenizerIdentity.parse("rs=ab").isLeft shouldBe true
    TokenizerIdentity.parse("rs=").isLeft shouldBe true
    TokenizerIdentity.parse("rs").isLeft shouldBe true
  }

  test("a quote or a LIKE wildcard cannot get into an extension") {
    // These strings reach a SQL LIKE pattern in Mapping. The alphabet is the
    // defence, so it is asserted here rather than trusted there.
    Seq("r's", "r%s", "r_s", "rs;", "rs)", "r s").foreach { bad =>
      TokenizerIdentity.parse(s"$bad=deadbeefcafe").isLeft shouldBe true
      TokenizerIdentity.parseExtensions(bad).isLeft shouldBe true
    }
  }

  test("an extension named twice is refused: one of the two values would win silently") {
    TokenizerIdentity.parse("rs=aaaaaaaa,rs=bbbbbbbb").isLeft shouldBe true
  }

  test("extension lists for --retokenize parse and reject the same way") {
    TokenizerIdentity.parseExtensions("rs") shouldBe Right(Set("rs"))
    TokenizerIdentity.parseExtensions("c,h,cpp") shouldBe Right(Set("c", "h", "cpp"))
    TokenizerIdentity.parseExtensions("") .isLeft shouldBe true
    TokenizerIdentity.parseExtensions(".rs").isLeft shouldBe true
    TokenizerIdentity.parseExtensions("c,,h").isLeft shouldBe true
  }
}
