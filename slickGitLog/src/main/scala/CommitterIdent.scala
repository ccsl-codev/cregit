import org.eclipse.jgit.lib.PersonIdent
import org.eclipse.jgit.revwalk.RevCommit
import org.eclipse.jgit.util.RawParseUtils

/** The committer of a commit. JGit returns null when the line after the first `author` header is not the `committer` header
  * (a commit with two `author` headers, or no `committer` header, or a committer line without angle brackets). The committer
  * header is then read from the whole header block; when there is none, the author is used. This is the rule RawHeader of
  * cregit blobExec (CommitIdents.scala), so the databases of the original and of the rewritten repository agree. */
object CommitterIdent {
  def of(rc: RevCommit): PersonIdent = {
    val c = rc.getCommitterIdent
    if (c != null) c else fromHeader(rc.getRawBuffer).getOrElse(rc.getAuthorIdent)
  }

  private def fromHeader(raw: Array[Byte]): Option[PersonIdent] = {
    var p = 0
    val key = "committer ".getBytes("US-ASCII")
    while (p < raw.length && raw(p) != '\n') {
      val eol = RawParseUtils.nextLF(raw, p)
      if (raw.length - p >= key.length && key.indices.forall(i => raw(p + i) == key(i))) {
        return Option(RawParseUtils.parsePersonIdent(raw, p + key.length))
      }
      p = eol
    }
    None
  }
}
