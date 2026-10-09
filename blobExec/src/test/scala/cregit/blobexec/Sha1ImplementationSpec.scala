package cregit.blobexec

import org.eclipse.jgit.lib.{Constants, ObjectInserter}
import org.eclipse.jgit.util.sha1.SHA1
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

/** blobExec hashes with the JDK's SHA-1 unless told otherwise; ids must not change. */
class Sha1ImplementationSpec extends AnyFunSuite with Matchers {

  private val P = Main.Sha1ImplementationProperty

  private def withProperty[A](value: Option[String])(body: => A): A = {
    val saved = Option(System.getProperty(P))
    value match { case Some(v) => System.setProperty(P, v); case None => System.clearProperty(P) }
    try body
    finally saved match { case Some(v) => System.setProperty(P, v); case None => System.clearProperty(P) }
  }

  private def idOf(bytes: Array[Byte]): String =
    new ObjectInserter.Formatter().idFor(Constants.OBJ_BLOB, bytes).name

  private val samples: Seq[Array[Byte]] =
    Seq("", "a", "int main(){return 0;}\n", "x" * 100000).map(_.getBytes("UTF-8")) :+
      Array.tabulate[Byte](70000)(i => (i * 31 % 251).toByte)

  test("unset, it becomes jdkNative; an operator's choice is kept") {
    withProperty(None) {
      Main.defaultSha1Implementation()
      System.getProperty(P) shouldBe "jdkNative"
      SHA1.newInstance().getClass.getSimpleName shouldBe "SHA1Native"
    }
    withProperty(Some("java")) {
      Main.defaultSha1Implementation()
      System.getProperty(P) shouldBe "java"
      SHA1.newInstance().getClass.getSimpleName shouldBe "SHA1Java"
    }
  }

  test("both implementations give the same object ids") {
    val java   = withProperty(Some("java"))(samples.map(idOf))
    val native = withProperty(Some("jdkNative"))(samples.map(idOf))
    native shouldEqual java
    java.head shouldBe "e69de29bb2d1d6434b8b29ae775ad8c2e48c5391"  // git's empty blob
  }
}
