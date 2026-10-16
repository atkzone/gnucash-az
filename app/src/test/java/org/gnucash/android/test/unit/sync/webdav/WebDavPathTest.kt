package org.gnucash.android.test.unit.sync.webdav

import org.assertj.core.api.Assertions.assertThat
import org.gnucash.android.sync.webdav.WebDavPath
import org.junit.Test

/**
 * Path arithmetic for the WebDAV layer (plan §5② step 1).
 *
 * Every case here corresponds to a way the client would ask for, or recognise, a
 * *different resource than the user means* — the class of bug that shows up as "the
 * other device's edits vanished" rather than as an error.
 */
class WebDavPathTest {

    // ------------------------------------------------------------- encodeSegment

    @Test
    fun `a space becomes percent-20, never a plus`() {
        // A '+' is a legal character in a path, so encoding a space as '+' would name
        // a file called "a+b" instead of "a b".
        assertThat(WebDavPath.encodeSegment("a b")).isEqualTo("a%20b")
    }

    @Test
    fun `a CJK segment is percent-encoded as UTF-8 bytes`() {
        assertThat(WebDavPath.encodeSegment("账本")).isEqualTo("%E8%B4%A6%E6%9C%AC")
    }

    @Test
    fun `unreserved and sub-delim characters are left alone`() {
        assertThat(WebDavPath.encodeSegment("book-1.2_3~!\$&'()*+,;=:@"))
            .isEqualTo("book-1.2_3~!\$&'()*+,;=:@")
    }

    @Test
    fun `a slash inside a segment is encoded`() {
        // Callers split on '/' first; if one leaked through it must not become a
        // separator, or the request silently addresses a different path.
        assertThat(WebDavPath.encodeSegment("a/b")).isEqualTo("a%2Fb")
    }

    @Test
    fun `question mark and hash are encoded so they cannot start a query or fragment`() {
        assertThat(WebDavPath.encodeSegment("a?b#c")).isEqualTo("a%3Fb%23c")
    }

    // --------------------------------------------------------------- encodePath

    @Test
    fun `encodePath keeps the separators`() {
        assertThat(WebDavPath.encodePath("/我的 账本/子 目录"))
            .isEqualTo("/%E6%88%91%E7%9A%84%20%E8%B4%A6%E6%9C%AC/%E5%AD%90%20%E7%9B%AE%E5%BD%95")
    }

    // ------------------------------------------------------------------- decode

    @Test
    fun `decode undoes one level of escaping`() {
        assertThat(WebDavPath.decode("/a%20b/%E8%B4%A6%E6%9C%AC")).isEqualTo("/a b/账本")
    }

    @Test
    fun `decode does not run twice`() {
        // A file literally named "100%20off" is advertised as "100%2520off". Decoding
        // it twice would yield "100 off" — a different resource.
        assertThat(WebDavPath.decode("100%2520off")).isEqualTo("100%20off")
    }

    @Test
    fun `a malformed escape is left verbatim instead of throwing`() {
        // One exotic filename must not fail the whole listing.
        assertThat(WebDavPath.decode("a%ZZb")).isEqualTo("a%ZZb")
        assertThat(WebDavPath.decode("100%")).isEqualTo("100%")
    }

    // ---------------------------------------------------------------- normalize

    @Test
    fun `normalize collapses a trailing slash and doubled separators`() {
        assertThat(WebDavPath.normalize("/book//sub/")).isEqualTo("/book/sub")
    }

    @Test
    fun `normalize decodes before comparing so two spellings are one identity`() {
        assertThat(WebDavPath.normalize("/book%20a")).isEqualTo("/book a")
    }

    @Test
    fun `normalize treats backslashes as separators`() {
        assertThat(WebDavPath.normalize("\\book\\sub")).isEqualTo("/book/sub")
    }

    @Test
    fun `the root normalises to a single slash`() {
        assertThat(WebDavPath.normalize("/")).isEqualTo("/")
        assertThat(WebDavPath.normalize("")).isEqualTo("/")
    }

    // ------------------------------------------------------------ join and name

    @Test
    fun `join concatenates while normalising both halves`() {
        assertThat(WebDavPath.join("/book/", "sub/file.xml")).isEqualTo("/book/sub/file.xml")
    }

    @Test
    fun `join of a root base returns the child`() {
        assertThat(WebDavPath.join("/", "file.xml")).isEqualTo("/file.xml")
    }

    @Test
    fun `name returns the last segment`() {
        assertThat(WebDavPath.name("/book/sub/txn-1.xml")).isEqualTo("txn-1.xml")
    }

    // ------------------------------------------------------------------ isUnder

    @Test
    fun `isUnder distinguishes a child from a sibling with a shared prefix`() {
        assertThat(WebDavPath.isUnder("/book", "/book/sub")).isTrue()
        // "/book2" must NOT count as under "/book" — string prefix alone would say yes.
        assertThat(WebDavPath.isUnder("/book", "/book2")).isFalse()
    }

    @Test
    fun `a resource is considered under itself`() {
        assertThat(WebDavPath.isUnder("/book", "/book")).isTrue()
    }

    @Test
    fun `everything is under the root`() {
        assertThat(WebDavPath.isUnder("/", "/any/thing")).isTrue()
    }
}
