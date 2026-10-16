package org.gnucash.android.test.unit.sync.webdav

import org.assertj.core.api.Assertions.assertThat
import org.gnucash.android.sync.webdav.ActionOutcome
import org.gnucash.android.sync.webdav.DownloadOutcome
import org.gnucash.android.sync.webdav.DownloadResponse
import org.gnucash.android.sync.webdav.GetPrecondition
import org.gnucash.android.sync.webdav.PutPrecondition
import org.gnucash.android.sync.webdav.TransportResponse
import org.gnucash.android.sync.webdav.WebDavActions
import org.gnucash.android.sync.webdav.WebDavClient
import org.gnucash.android.sync.webdav.WebDavTransport
import org.gnucash.android.sync.webdav.succeeded
import org.junit.Test
import java.io.IOException

/**
 * The write half of the client (plan §5② step 1, §7.6).
 *
 * A plain JVM test against a recording fake. What matters is not that a `PUT` is a `PUT`
 * but **what the client does with each answer**, which is where a sync engine quietly
 * loses data.
 */
class WebDavClientActionsTest {

    private val base = "https://cloud.example.com/remote.php/dav/files/u/book"

    // ---------------------------------------------------------------- fixture

    /** One recorded write verb. `body` is kept nullable so "no body" is assertable. */
    private data class Write(
        val method: String,
        val url: String,
        val headers: Map<String, String>,
        val body: ByteArray?
    )

    private class RecordingTransport(
        private val handler: (String, String) -> TransportResponse
    ) : WebDavTransport {

        val writes = mutableListOf<Write>()
        val downloads = mutableListOf<Pair<String, Map<String, String>>>()

        var downloadResponse: DownloadResponse = DownloadResponse(404, ByteArray(0))
        var downloadFailure: Exception? = null

        override fun propfind(url: String, depth: Int, body: String): TransportResponse =
            TransportResponse(207, "<D:multistatus xmlns:D=\"DAV:\"/>")

        override fun execute(
            method: String,
            url: String,
            headers: Map<String, String>,
            body: ByteArray?
        ): TransportResponse {
            writes += Write(method, url, headers, body)
            return handler(method, url)
        }

        override fun download(url: String, headers: Map<String, String>): DownloadResponse {
            downloads += url to headers
            downloadFailure?.let { throw it }
            return downloadResponse
        }

        /** The verbs of [writes] in order, as `"METHOD url"`. */
        fun trace(): List<String> = writes.map { "${it.method} ${it.url}" }
    }

    private fun transport(
        handler: (String, String) -> TransportResponse = { _, _ -> TransportResponse(201, "") }
    ) = RecordingTransport(handler)

    private fun client(t: WebDavTransport) = WebDavClient(base, t)

    private val bytes = "payload".toByteArray()

    // -------------------------------------------------------------------- put

    @Test
    fun `a put goes to the encoded record url and carries its bytes`() {
        val t = transport()
        val outcome = client(t).put("/a b/t1.xml", bytes)

        assertThat(outcome).isInstanceOf(ActionOutcome.Done::class.java)
        assertThat(t.writes).hasSize(1)
        assertThat(t.writes[0].method).isEqualTo("PUT")
        assertThat(t.writes[0].url).isEqualTo("$base/a%20b/t1.xml")
        assertThat(t.writes[0].body).isEqualTo(bytes)
    }

    @Test
    fun `a put with no precondition carries neither conditional header`() {
        val t = transport()
        client(t).put("/t1.xml", bytes)

        assertThat(t.writes[0].headers).doesNotContainKeys("If-Match", "If-None-Match")
    }

    @Test
    fun `a put for a record the base knows is conditional on the base etag`() {
        // Without this header the write is a blind overwrite: a peer's concurrent edit is
        // replaced and *nobody* ever learns there was a conflict.
        val t = transport()
        client(t).put("/t1.xml", bytes, PutPrecondition.MustMatch("e1"))

        assertThat(t.writes[0].headers["If-Match"]).isEqualTo("\"e1\"")
    }

    @Test
    fun `a put for a record the base says is new refuses to clobber a peer's copy`() {
        val t = transport()
        client(t).put("/t1.xml", bytes, PutPrecondition.MustNotExist)

        assertThat(t.writes[0].headers["If-None-Match"]).isEqualTo("*")
        assertThat(t.writes[0].headers).doesNotContainKey("If-Match")
    }

    @Test
    fun `a rejected conditional put is not reported as applied`() {
        // Bite target: route 412 to Done and this goes red. The distinction is the whole
        // point of the conditional write — 412 means our snapshot is stale, so the round
        // must re-list rather than record a base for content it did not write.
        val t = transport { _, _ -> TransportResponse(412, "") }

        val outcome = client(t).put("/t1.xml", bytes, PutPrecondition.MustMatch("e1"))

        assertThat(outcome).isInstanceOf(ActionOutcome.Stale::class.java)
        assertThat(outcome.succeeded).isFalse()
    }

    @Test
    fun `the etag a put returns is carried into the outcome as the new base`() {
        val t = transport { _, _ -> TransportResponse(204, "", etag = "e9") }

        val outcome = client(t).put("/t1.xml", bytes)

        assertThat(outcome).isEqualTo(ActionOutcome.Done(204, etag = "e9"))
    }

    @Test
    fun `a transport failure on a put is a Failed with the reason, never an exception`() {
        val t = transport { _, _ -> throw IOException("connection reset by peer") }

        val outcome = client(t).put("/t1.xml", bytes)

        assertThat(outcome).isInstanceOf(ActionOutcome.Failed::class.java)
        val failed = outcome as ActionOutcome.Failed
        assertThat(failed.status).isEqualTo(WebDavActions.STATUS_NO_RESPONSE)
        assertThat(failed.detail).contains("connection reset by peer")
    }

    // ----------------------------------------------------------------- delete

    @Test
    fun `deleting a record that is already gone counts as done`() {
        // Bite target: route 404 to Failed and this goes red. A delete applied by an
        // earlier, interrupted round must not be retried forever.
        val t = transport { _, _ -> TransportResponse(404, "") }

        val outcome = client(t).delete("/t1.xml")

        assertThat(outcome).isInstanceOf(ActionOutcome.AlreadyDone::class.java)
        assertThat(outcome.succeeded).isTrue()
    }

    @Test
    fun `a delete is conditional on the base etag when one is supplied`() {
        val t = transport { _, _ -> TransportResponse(204, "") }
        client(t).delete("/t1.xml", PutPrecondition.MustMatch("e2"))

        assertThat(t.writes[0].headers["If-Match"]).isEqualTo("\"e2\"")
        assertThat(t.writes[0].body).isNull()
    }

    // ------------------------------------------------------------------ mkcol

    @Test
    fun `mkcol of an existing collection counts as done`() {
        // Bite target: route 405 to Failed and this goes red. Two devices creating the same
        // folder is normal, and treating it as a failure aborts the upload that follows.
        val t = transport { _, _ -> TransportResponse(405, "") }

        assertThat(client(t).mkcol("/a")).isInstanceOf(ActionOutcome.AlreadyDone::class.java)
    }

    @Test
    fun `mkcol never carries a body`() {
        // RFC 4918 §9.3: a MKCOL request has no body, and servers may answer a bodied one
        // with 415. The client must pass "no body" through rather than an empty array.
        val t = transport()
        client(t).mkcol("/a")

        assertThat(t.writes[0].body).isNull()
    }

    // ------------------------------------------------------- ensureCollections

    @Test
    fun `every ancestor collection is created, root first`() {
        val t = transport()
        val failure = client(t).ensureCollections("/a/b/c.xml")

        assertThat(failure).isNull()
        assertThat(t.trace()).containsExactly(
            "MKCOL $base/a",
            "MKCOL $base/a/b"
        )
    }

    @Test
    fun `an already existing ancestor does not stop the rest from being created`() {
        // 405 on /a is the concurrent-creation race, not a failure.
        val t = transport { _, url -> if (url.endsWith("/a")) TransportResponse(405, "") else TransportResponse(201, "") }

        val failure = client(t).ensureCollections("/a/b/c.xml")

        assertThat(failure).isNull()
        assertThat(t.trace()).containsExactly("MKCOL $base/a", "MKCOL $base/a/b")
    }

    @Test
    fun `a hard failure on one ancestor stops the walk there`() {
        // Continuing past a real failure would mask which level broke, and would issue
        // MKCOLs whose 409s carry no new information.
        val t = transport { _, url -> if (url.endsWith("/a")) TransportResponse(500, "") else TransportResponse(201, "") }

        val failure = client(t).ensureCollections("/a/b/c.xml")

        assertThat(failure).isInstanceOf(ActionOutcome.Failed::class.java)
        assertThat(t.trace()).containsExactly("MKCOL $base/a")
    }

    @Test
    fun `nothing is requested when the record sits directly in the client root`() {
        val t = transport()

        assertThat(client(t).ensureCollections("/c.xml")).isNull()
        assertThat(t.writes).isEmpty()
    }

    @Test
    fun `the client root itself is never created`() {
        // The configured folder is created out of band; MKCOL-ing it would be a write
        // outside the sync's own subtree.
        val t = transport()

        client(t).ensureCollections("/a/b/c.xml")

        assertThat(t.writes.map { it.url }).doesNotContain(base, "$base/")
    }

    @Test
    fun `putCreatingCollections creates the folders and only then uploads`() {
        val t = transport { method, _ -> if (method == "PUT") TransportResponse(201, "") else TransportResponse(201, "") }

        val outcome = client(t).putCreatingCollections("/a/b/c.xml", bytes)

        assertThat(outcome).isInstanceOf(ActionOutcome.Done::class.java)
        assertThat(t.trace()).containsExactly(
            "MKCOL $base/a",
            "MKCOL $base/a/b",
            "PUT $base/a/b/c.xml"
        )
    }

    @Test
    fun `a failed folder creation aborts the upload instead of uploading into nowhere`() {
        // Uploading anyway would earn a 409 and be reported as "the record is unwritable",
        // hiding the real cause (the folder could not be created).
        val t = transport { method, url ->
            if (method == "MKCOL" && url.endsWith("/a")) TransportResponse(403, "") else TransportResponse(201, "")
        }

        val outcome = client(t).putCreatingCollections("/a/b/c.xml", bytes)

        assertThat(outcome).isInstanceOf(ActionOutcome.Failed::class.java)
        assertThat(t.writes.map { it.method }).containsExactly("MKCOL")
    }

    // ------------------------------------------------------------------- move

    @Test
    fun `a move states an absolute encoded destination and an explicit overwrite policy`() {
        val t = transport { _, _ -> TransportResponse(201, "") }
        client(t).move("/old name.xml", "/new name.xml", overwrite = false)

        assertThat(t.writes[0].method).isEqualTo("MOVE")
        assertThat(t.writes[0].url).isEqualTo("$base/old%20name.xml")
        assertThat(t.writes[0].headers["Destination"]).isEqualTo("$base/new%20name.xml")
        assertThat(t.writes[0].headers["Overwrite"]).isEqualTo("F")
        assertThat(t.writes[0].body).isNull()
    }

    @Test
    fun `a move refused because the destination exists is stale`() {
        val t = transport { _, _ -> TransportResponse(412, "") }

        assertThat(client(t).move("/a.xml", "/b.xml")).isInstanceOf(ActionOutcome.Stale::class.java)
    }

    // --------------------------------------------------------------- download

    @Test
    fun `a download returns the bytes and the etag`() {
        val t = transport()
        t.downloadResponse = DownloadResponse(200, "book".toByteArray(), etag = "e5")

        val outcome = client(t).download("/t1.xml")

        assertThat(outcome).isInstanceOf(DownloadOutcome.Got::class.java)
        val got = outcome as DownloadOutcome.Got
        assertThat(got.bytes).isEqualTo("book".toByteArray())
        assertThat(got.etag).isEqualTo("e5")
        assertThat(t.downloads[0].first).isEqualTo("$base/t1.xml")
    }

    @Test
    fun `a missing download is refused as Missing, not as an empty payload`() {
        // "The peer no longer has it" and "the peer has an empty file" are different facts,
        // and only the first is a deletion.
        val t = transport()
        t.downloadResponse = DownloadResponse(404, ByteArray(0))

        val outcome = client(t).download("/t1.xml")

        assertThat(outcome).isInstanceOf(DownloadOutcome.Refused::class.java)
        assertThat((outcome as DownloadOutcome.Refused).reason)
            .isInstanceOf(ActionOutcome.Missing::class.java)
    }

    @Test
    fun `a conditional download carries the base etag`() {
        val t = transport()
        t.downloadResponse = DownloadResponse(200, ByteArray(0))
        client(t).download("/t1.xml", GetPrecondition.MustMatch("e1"))

        assertThat(t.downloads[0].second["If-Match"]).isEqualTo("\"e1\"")
    }

    @Test
    fun `a download transport failure is a refusal, never an exception`() {
        val t = transport()
        t.downloadFailure = IOException("timeout")

        val outcome = client(t).download("/t1.xml")

        assertThat((outcome as DownloadOutcome.Refused).reason)
            .isInstanceOf(ActionOutcome.Failed::class.java)
        assertThat((outcome.reason as ActionOutcome.Failed).detail).contains("timeout")
    }
}
