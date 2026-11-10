package cregit.blobexec

import org.eclipse.jgit.lib.PersonIdent

import java.nio.charset.StandardCharsets.UTF_8
import java.util.{Date, TimeZone}

/** What a rewritten commit gets as author and committer when JGit cannot read
  * one of them from the original commit.
  *
  * `RevCommit.getCommitterIdent` returns null when the commit has no `committer`
  * header, or when the header has no `<` or no `>` (git stores such a commit
  * without complaint). `CommitBuilder.build` then fails with a NullPointerException.
  * The rewritten repository is read back by slickGitLog and persons, which call
  * `getCommitterIdent` themselves, so the rewritten commit must carry a committer
  * that JGit can parse.
  */
object CommitIdents {

  sealed trait NullCommitterRule { def name: String }

  /** The committer is a copy of the author (name, e-mail and date). */
  case object CopyAuthor extends NullCommitterRule { val name = "copy-author" }

  /** The committer has an empty name, an empty e-mail and the author date. */
  case object Empty extends NullCommitterRule { val name = "empty" }

  /** The committer keeps the text of the original header: the text before the
    * date goes in the name (an `@` token goes in the e-mail), the date and the
    * zone come from the header when they parse, else from the author. */
  case object RawHeader extends NullCommitterRule { val name = "raw-header" }

  /** The one place that sets the rule. Change it here and nowhere else. */
  val NullCommitterRule: NullCommitterRule = CopyAuthor

  private val UnknownAuthor: PersonIdent = new PersonIdent("", "", new Date(0L), TimeZone.getTimeZone("UTC"))

  /** (author, committer) for the rewritten commit. `raw` is the raw commit buffer. */
  def resolve(
      sha: String,
      author: PersonIdent,
      committer: PersonIdent,
      raw: Array[Byte],
      rule: NullCommitterRule = NullCommitterRule,
      report: String => Unit = msg => System.err.println(msg)
  ): (PersonIdent, PersonIdent) =
    if (committer != null && author != null) (author, committer)
    else if (committer != null) {
      report(s"warning: commit $sha has no readable author; the author of the rewritten commit is its committer")
      (committer, committer)
    } else {
      val a = if (author != null) author else UnknownAuthor
      report(s"warning: commit $sha has no readable committer; rule ${rule.name}")
      (a, rule match {
        case CopyAuthor => a
        case Empty      => new PersonIdent("", "", a.getWhen, a.getTimeZone)
        case RawHeader  => fromRawHeader(raw, a)
      })
    }

  private def fromRawHeader(raw: Array[Byte], author: PersonIdent): PersonIdent = {
    val text = headerLine(raw, "committer ") match {
      case Some(t) => t
      case None    => return new PersonIdent("", "", author.getWhen, author.getTimeZone)
    }
    val tokens = text.trim.split(' ').filter(_.nonEmpty).toList
    val (rest, when, tz) = tokens.reverse match {
      case zone :: secs :: front if secs.forall(_.isDigit) && secs.nonEmpty && zoneOffset(zone).isDefined =>
        (front.reverse, new Date(secs.toLong * 1000L), TimeZone.getTimeZone(s"GMT$zone"))
      case _ =>
        (tokens, author.getWhen, author.getTimeZone)
    }
    val email = rest.find(_.contains("@")).map(_.stripPrefix("<").stripSuffix(">")).getOrElse("")
    val name = rest.filterNot(t => t.contains("@")).mkString(" ").replaceAll("[<>\n]", "")
    new PersonIdent(name, email.replaceAll("[<>\n]", ""), when, tz)
  }

  private def zoneOffset(z: String): Option[Int] =
    if (z.matches("[+-][0-9]{4}")) Some(z.substring(1, 3).toInt * 60 + z.substring(3, 5).toInt) else None

  /** The first header line that starts with `key`, from the header block only. */
  private def headerLine(raw: Array[Byte], key: String): Option[String] = {
    val s = new String(raw, UTF_8)
    val headerEnd = s.indexOf("\n\n") match { case -1 => s.length; case i => i }
    s.substring(0, headerEnd).split('\n').find(_.startsWith(key)).map(_.substring(key.length))
  }
}
