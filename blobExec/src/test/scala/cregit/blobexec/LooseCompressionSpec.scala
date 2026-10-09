package cregit.blobexec

import org.eclipse.jgit.lib.{Constants, ObjectId}
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters._

/** `--loose-compression`: the zlib level of dst's loose objects, set in memory. */
class LooseCompressionSpec extends AnyFunSuite with Matchers {

  /** Insert one object and return its loose file's zlib header (CMF, FLG). */
  private def headerAfterInsert(level: Option[Int]): (Path, Int, ObjectId) = {
    val dir = Files.createTempDirectory("loose-")
    val path = dir.resolve("dst.git")
    val dst = Main.openOrInitDst(path)
    try {
      level.foreach(Main.setLooseCompression(dst, _))
      val ins = dst.newObjectInserter()
      val id = try ins.insert(Constants.OBJ_BLOB, ("x" * 4096).getBytes("UTF-8")) finally ins.close()
      val file = path.resolve("objects").resolve(id.name.take(2)).resolve(id.name.drop(2))
      val bytes = Files.readAllBytes(file)
      (path, bytes(1) & 0xff, id)
    } finally dst.close()
  }

  test("level 1 writes zlib's fastest level; no setting writes JGit's default") {
    // FLG's top two bits are FLEVEL: 0 = fastest (level 1), 2 = default (6).
    val (fastRepo, fastFlg, fastId) = headerAfterInsert(Some(1))
    val (defRepo, defFlg, defId)    = headerAfterInsert(None)
    (fastFlg >> 6) shouldBe 0
    (defFlg >> 6) shouldBe 2
    fastId shouldEqual defId  // the id hashes the content, never the deflate stream
    Main.DefaultLooseCompression shouldBe 1
  }

  test("the level stays in memory: dst's config file is not changed") {
    val (repo, _, _) = headerAfterInsert(Some(1))
    val config = Files.readAllLines(repo.resolve("config")).asScala.mkString("\n")
    config should not include "compression"
  }
}
