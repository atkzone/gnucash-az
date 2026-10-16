package org.gnucash.android.test.unit.util

import org.assertj.core.api.Assertions.assertThat
import org.gnucash.android.util.BookPayload
import org.junit.Test
import java.nio.charset.StandardCharsets

/**
 * The restore payload guard (plan §5② step 2, §5② v1).
 *
 * The guard exists because the restore writes the downloaded bytes over the local book
 * *before* anything hands them to the importer — so "the server answered `200` with a login
 * page" has to be caught here or not at all. Every case below is a shape a real network
 * produces, not a synthetic one.
 *
 * A plain JVM test (no `GnuCashTest`): the guard itself has no Android dependencies, so
 * there is no reason to pay for a Robolectric sandbox twice over.
 */
class BookPayloadTest {

    private fun bytes(vararg value: Int) = ByteArray(value.size) { value[it].toByte() }

    private fun utf8(text: String) = text.toByteArray(StandardCharsets.UTF_8)

    private val gzip = bytes(0x1F, 0x8B, 0x08, 0x00)
    private val sqlite = "SQLite format 3\u0000".toByteArray(StandardCharsets.US_ASCII)
    private val xml = utf8("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<gnucash>")

    private fun accepted(payload: ByteArray): BookPayload.Kind {
        val verdict = BookPayload.inspect(payload)
        assertThat(verdict).isInstanceOf(BookPayload.Verdict.Acceptable::class.java)
        return (verdict as BookPayload.Verdict.Acceptable).kind
    }

    private fun rejected(payload: ByteArray): BookPayload.Verdict.Rejected {
        val verdict = BookPayload.inspect(payload)
        assertThat(verdict).isInstanceOf(BookPayload.Verdict.Rejected::class.java)
        return verdict as BookPayload.Verdict.Rejected
    }

    // ---------------------------------------------------------------- the containers

    @Test
    fun `a gzipped book is accepted`() {
        assertThat(accepted(gzip + utf8("compressed payload"))).isEqualTo(BookPayload.Kind.GZIP)
    }

    @Test
    fun `every zip signature is accepted`() {
        // A book can arrive as an ordinary archive, an empty one, or one split across
        // volumes; all three are the same magic pair, and refusing two of them would make a
        // perfectly good backup look corrupt.
        assertThat(accepted(bytes(0x50, 0x4B, 0x03, 0x04) + utf8("x"))).isEqualTo(BookPayload.Kind.ZIP)
        assertThat(accepted(bytes(0x50, 0x4B, 0x05, 0x06) + utf8("x"))).isEqualTo(BookPayload.Kind.ZIP)
        assertThat(accepted(bytes(0x50, 0x4B, 0x07, 0x08) + utf8("x"))).isEqualTo(BookPayload.Kind.ZIP)
    }

    @Test
    fun `a sqlite book is accepted`() {
        assertThat(accepted(sqlite + utf8("binary"))).isEqualTo(BookPayload.Kind.SQLITE)
    }

    @Test
    fun `plain gnu cash xml is accepted`() {
        assertThat(accepted(xml)).isEqualTo(BookPayload.Kind.XML)
    }

    @Test
    fun `an xml root without a declaration is accepted`() {
        assertThat(accepted(utf8("<gnucash xmlns=\"http://www.gnucash.org/XML/gnc\">")))
            .isEqualTo(BookPayload.Kind.XML)
    }

    @Test
    fun `a byte order mark and blank lines do not hide a book`() {
        assertThat(accepted(bytes(0xEF, 0xBB, 0xBF) + utf8("\n\n  <?xml version=\"1.0\"?>")))
            .isEqualTo(BookPayload.Kind.XML)
    }

    // -------------------------------------------------------------------- refusals

    @Test
    fun `an empty payload is refused as empty`() {
        assertThat(rejected(ByteArray(0)).reason).isEqualTo(BookPayload.Rejection.EMPTY)
    }

    @Test
    fun `an html page is refused as a web page`() {
        val page = utf8("<!DOCTYPE html>\n<html><head><title>Sign in</title></head></html>")
        val verdict = rejected(page)
        assertThat(verdict.reason).isEqualTo(BookPayload.Rejection.WEB_PAGE)
        assertThat(verdict.detail).contains("web page")
    }

    @Test
    fun `a page that only starts after a comment is still refused`() {
        // A real login form often opens with a comment or a meta block; anchoring the test
        // to a `<html` prefix would let exactly those through.
        val page = utf8("<!-- served by reverse proxy -->\n<html lang=\"en\">")
        assertThat(rejected(page).reason).isEqualTo(BookPayload.Rejection.WEB_PAGE)
    }

    @Test
    fun `web page detection ignores case and leading whitespace`() {
        assertThat(rejected(utf8("\n\r  <HTML>")).reason).isEqualTo(BookPayload.Rejection.WEB_PAGE)
        assertThat(rejected(utf8("<!DOCTYPE HTML PUBLIC>")).reason).isEqualTo(BookPayload.Rejection.WEB_PAGE)
    }

    @Test
    fun `a json error body is refused as unrecognised`() {
        val verdict = rejected(utf8("{\"error\":\"unauthorized\",\"code\":401}"))
        assertThat(verdict.reason).isEqualTo(BookPayload.Rejection.UNRECOGNISED)
        assertThat(verdict.detail).contains("GnuCash book")
    }

    @Test
    fun `a proxy error page in plain text is refused as unrecognised`() {
        assertThat(rejected(utf8("502 Bad Gateway\nnginx")).reason)
            .isEqualTo(BookPayload.Rejection.UNRECOGNISED)
    }

    @Test
    fun `arbitrary binary that matches nothing is refused`() {
        assertThat(rejected(bytes(0x00, 0x01, 0x02, 0x03, 0xFE, 0xFF)).reason)
            .isEqualTo(BookPayload.Rejection.UNRECOGNISED)
    }

    // ------------------------------------------------------------------ edge cases

    @Test
    fun `a container signature wins over an html-looking payload`() {
        // A gzipped book whose compressed bytes happen to spell `<html` must not be thrown
        // away: the container is recognised first, so the web-page test never runs on it.
        val suspicious = gzip + utf8("<html>this is inside the compressed stream")

        assertThat(accepted(suspicious)).isEqualTo(BookPayload.Kind.GZIP)
    }

    @Test
    fun `a two byte gzip magic is accepted because that is what the importer recognises`() {
        // Deliberately the same threshold `ImporterFactory` uses (`GZIPInputStream.GZIP_MAGIC`
        // is two bytes), because both call `classify`: a stricter guard here would make the
        // restore refuse a file the importer would happily open. A *truncated* gzip is caught
        // later and elsewhere — by the GCM tag when the payload is encrypted, and by the
        // decompressor when it is not.
        assertThat(accepted(bytes(0x1F, 0x8B))).isEqualTo(BookPayload.Kind.GZIP)
    }

    @Test
    fun `a truncated sqlite header is not accepted`() {
        val short = sqlite.copyOf(sqlite.size - 1)
        assertThat(rejected(short).reason).isEqualTo(BookPayload.Rejection.UNRECOGNISED)
    }

    @Test
    fun `classify reports the container for a prefix and unknown for anything else`() {
        assertThat(BookPayload.classify(gzip.copyOf(4))).isEqualTo(BookPayload.Kind.GZIP)
        assertThat(BookPayload.classify(utf8("hello"))).isEqualTo(BookPayload.Kind.UNKNOWN)
    }

    @Test
    fun `the detail of each refusal names what was wrong`() {
        assertThat(rejected(ByteArray(0)).detail).contains("no bytes")
        assertThat(rejected(utf8("nonsense bytes here")).detail).contains("not a GnuCash book")
    }
}
