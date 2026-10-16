package org.gnucash.android.test.unit.sync.webdav

import org.assertj.core.api.Assertions.assertThat
import org.gnucash.android.sync.webdav.MultiStatusResult
import org.gnucash.android.sync.webdav.WebDavXml
import org.junit.Test

/**
 * The `207 Multi-Status` reader (plan §5② step 1).
 *
 * The point of these cases is not "does the happy path work" (it does, and every real
 * server would catch a bug there immediately). It is the two directions that fail
 * *silently* in production:
 *  - a body the parser mis-reads as "fewer entries than it really has" → the caller
 *    concludes records are gone;
 *  - a body the parser accepts whose shape differs from what the client assumes.
 */
class WebDavXmlTest {

    // ------------------------------------------------------------------ helpers

    private fun multistatus(vararg responses: String, prefix: String = "D") =
        """<?xml version="1.0" encoding="utf-8"?>
           |<$prefix:multistatus xmlns:$prefix="DAV:">${responses.joinToString("")}</$prefix:multistatus>
        """.trimMargin()

    private fun response(
        href: String,
        resourceType: String = "",
        etag: String? = null,
        length: Long? = null,
        lastModified: String? = null,
        status: String = "HTTP/1.1 200 OK",
        prefix: String = "D"
    ): String {
        val body = buildString {
            append("<$prefix:resourcetype>$resourceType</$prefix:resourcetype>")
            etag?.let { append("<$prefix:getetag>$it</$prefix:getetag>") }
            length?.let { append("<$prefix:getcontentlength>$it</$prefix:getcontentlength>") }
            lastModified?.let { append("<$prefix:getlastmodified>$it</$prefix:getlastmodified>") }
        }
        return "<$prefix:response><$prefix:href>$href</$prefix:href>" +
            "<$prefix:propstat><$prefix:prop>$body</$prefix:prop>" +
            "<$prefix:status>$status</$prefix:status>" +
            "</$prefix:propstat></$prefix:response>"
    }

    private fun parse(xml: String): MultiStatusResult = WebDavXml.parseMultiStatus(xml)

    private fun entries(xml: String): List<org.gnucash.android.sync.webdav.RemoteEntry> =
        (parse(xml) as MultiStatusResult.Parsed).entries

    // ----------------------------------------------------------- request body

    @Test
    fun `the PROPFIND body asks for exactly the properties the client reads`() {
        val body = WebDavXml.propfindBody()
        assertThat(body).contains("propfind")
        assertThat(body).contains("resourcetype")
        assertThat(body).contains("getetag")
        assertThat(body).contains("getcontentlength")
        assertThat(body).contains("getlastmodified")
    }

    // -------------------------------------------------------------- happy path

    @Test
    fun `a collection and a file are told apart by resourcetype`() {
        val xml = multistatus(
            response("/book/", resourceType = "<D:collection/>"),
            response("/book/txn-1.xml", length = 1234, etag = "\"abc\"")
        )
        val list = entries(xml)
        assertThat(list).hasSize(2)
        assertThat(list[0].isCollection).isTrue()
        assertThat(list[1].isCollection).isFalse()
    }

    @Test
    fun `an empty resourcetype means a file`() {
        val list = entries(multistatus(response("/book/a.xml")))
        assertThat(list.single().isCollection).isFalse()
    }

    @Test
    fun `a lowercase namespace prefix parses the same way`() {
        val list = entries(
            multistatus(response("/book/a.xml", etag = "\"x\"", prefix = "d"), prefix = "d")
        )
        assertThat(list.single().path).isEqualTo("/book/a.xml")
        assertThat(list.single().etag).isEqualTo("x")
    }

    @Test
    fun `no namespace prefix at all parses the same way`() {
        // Some proxies strip the prefix and rely on a default namespace.
        val xml = """<?xml version="1.0"?>
            |<multistatus xmlns="DAV:"><response><href>/book/a.xml</href>
            |<propstat><prop><resourcetype/></prop><status>HTTP/1.1 200 OK</status>
            |</propstat></response></multistatus>
        """.trimMargin()
        assertThat(entries(xml).single().path).isEqualTo("/book/a.xml")
    }

    // ------------------------------------------------------------- etag hygiene

    @Test
    fun `etag quotes and the weak marker are stripped`() {
        val list = entries(
            multistatus(
                response("/book/a.xml", etag = "\"abc\""),
                response("/book/b.xml", etag = "W/\"def\"")
            )
        )
        assertThat(list.map { it.etag }).containsExactly("abc", "def")
    }

    @Test
    fun `a missing etag is null, not an empty string`() {
        // "no etag" must stay distinguishable from "etag = ''" — a caller comparing
        // etags to detect change must not see two different things as equal.
        val list = entries(multistatus(response("/book/a.xml")))
        assertThat(list.single().etag).isNull()
        assertThat(list.single().contentLength).isNull()
        assertThat(list.single().lastModified).isNull()
    }

    @Test
    fun `hrefs are normalised so encoded and raw spellings share one identity`() {
        val list = entries(
            multistatus(
                response("/book%20a/txn-1.xml"),
                response("/book a/txn-2.xml")
            )
        )
        assertThat(list.map { it.path }).containsExactly("/book a/txn-1.xml", "/book a/txn-2.xml")
    }

    @Test
    fun `document order is preserved`() {
        val list = entries(
            multistatus(
                response("/book/c.xml"),
                response("/book/a.xml"),
                response("/book/b.xml")
            )
        )
        assertThat(list.map { it.path }).containsExactly("/book/c.xml", "/book/a.xml", "/book/b.xml")
    }

    // --------------------------------------------------------- failed responses

    @Test
    fun `a response with a 5xx status is reported as failed and not as an entry`() {
        // This is the single most important case in the file: a 207 whose body carries
        // a per-resource error. Ignoring the error would silently drop whatever that
        // resource was, and the next round would read the drop as a deletion.
        val xml = multistatus(
            response("/book/a.xml"),
            response("/book/locked.xml", status = "HTTP/1.1 503 Service Unavailable")
        )
        val result = parse(xml) as MultiStatusResult.Parsed
        assertThat(result.entries.map { it.path }).containsExactly("/book/a.xml")
        assertThat(result.failedResponses).hasSize(1)
        assertThat(result.failedResponses.single()).contains("/book/locked.xml")
    }

    @Test
    fun `a propstat without a status is a failure, not an optimistic success`() {
        val xml = multistatus(
            "<D:response><D:href>/book/a.xml</D:href>" +
                "<D:propstat><D:prop><D:resourcetype/></D:prop></D:propstat></D:response>"
        )
        val result = parse(xml) as MultiStatusResult.Parsed
        assertThat(result.entries).isEmpty()
        assertThat(result.failedResponses).hasSize(1)
    }

    @Test
    fun `a response without an href is a failure`() {
        val xml = multistatus("<D:response><D:propstat><D:prop/></D:propstat></D:response>")
        val result = parse(xml) as MultiStatusResult.Parsed
        assertThat(result.failedResponses).hasSize(1)
    }

    @Test
    fun `the first successful propstat wins when a 404 propstat is also present`() {
        val xml = multistatus(
            "<D:response><D:href>/book/a.xml</D:href>" +
                "<D:propstat><D:prop><D:resourcetype/></D:prop>" +
                "<D:status>HTTP/1.1 404 Not Found</D:status></D:propstat>" +
                "<D:propstat><D:prop><D:resourcetype/><D:getetag>\"ok\"</D:getetag></D:prop>" +
                "<D:status>HTTP/1.1 200 OK</D:status></D:propstat></D:response>"
        )
        val result = parse(xml) as MultiStatusResult.Parsed
        assertThat(result.failedResponses).isEmpty()
        assertThat(result.entries.single().etag).isEqualTo("ok")
    }

    // ------------------------------------------------------------ malformed input

    @Test
    fun `a blank body is malformed`() {
        assertThat(parse("")).isInstanceOf(MultiStatusResult.Malformed::class.java)
    }

    @Test
    fun `a truncated document is malformed`() {
        val truncated = "<D:multistatus xmlns:D=\"DAV:\"><D:response><D:href>/a</D:href>"
        assertThat(parse(truncated)).isInstanceOf(MultiStatusResult.Malformed::class.java)
    }

    @Test
    fun `a non-multistatus root is malformed`() {
        assertThat(parse("<html><body>503</body></html>"))
            .isInstanceOf(MultiStatusResult.Malformed::class.java)
    }

    @Test
    fun `a DOCTYPE payload is refused rather than resolved`() {
        // XXE: accepting this would let a compromised server read local files. The
        // parser refuses the document outright instead of merely ignoring the entity.
        val xxe = """<?xml version="1.0"?>
            |<!DOCTYPE multistatus [ <!ENTITY xxe SYSTEM "file:///etc/passwd"> ]>
            |<D:multistatus xmlns:D="DAV:"><D:response><D:href>&xxe;</D:href></D:response></D:multistatus>
        """.trimMargin()
        val result = parse(xxe)
        assertThat(result).isInstanceOf(MultiStatusResult.Malformed::class.java)
        assertThat((result as MultiStatusResult.Malformed).reason).contains("DOCTYPE")
    }
}
