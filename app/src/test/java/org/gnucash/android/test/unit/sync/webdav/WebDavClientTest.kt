package org.gnucash.android.test.unit.sync.webdav

import org.assertj.core.api.Assertions.assertThat
import org.gnucash.android.sync.ThreeWayMerge
import org.gnucash.android.sync.webdav.DownloadResponse
import org.gnucash.android.sync.webdav.IncompleteReason
import org.gnucash.android.sync.webdav.ListingResult
import org.gnucash.android.sync.webdav.RemoteEntry
import org.gnucash.android.sync.webdav.TransportResponse
import org.gnucash.android.sync.webdav.WebDavClient
import org.gnucash.android.sync.webdav.WebDavPath
import org.gnucash.android.sync.webdav.WebDavTransport
import org.gnucash.android.sync.webdav.confirmsAbsence
import org.junit.Test
import java.io.IOException

/**
 * Traversal policy for the WebDAV client (plan §5② step 1, §7.3 constraint 2).
 *
 * The class is a plain JVM test on purpose: the interesting behaviour is not "does it
 * list a server correctly" (a real server would catch that instantly) but "what does it
 * do when the server answers badly", and a fake transport makes those cases trivial to
 * stage and impossible to miss.
 */
class WebDavClientTest {

    // ---------------------------------------------------------------- fixtures

    /** A Nextcloud-shaped base: the href prefix is NOT the same as the path we ask for. */
    private val base = "https://cloud.example.com/remote.php/dav/files/u/book"
    private val hrefPrefix = "/remote.php/dav/files/u/book"
    private val rootUrl = "$base/"
    private val subUrl = "$base/sub"

    private class FakeTransport(private val handler: (String) -> TransportResponse) : WebDavTransport {
        val calls = mutableListOf<String>()
        val depths = mutableListOf<Int>()

        override fun propfind(url: String, depth: Int, body: String): TransportResponse {
            calls += url
            depths += depth
            return handler(url)
        }

        // This fixture is about traversal only; the write verbs are exercised by
        // WebDavClientActionsTest, which has its own recording fake.
        override fun execute(
            method: String,
            url: String,
            headers: Map<String, String>,
            body: ByteArray?
        ): TransportResponse = throw UnsupportedOperationException("listing-only fake: $method")

        override fun download(url: String, headers: Map<String, String>): DownloadResponse =
            throw UnsupportedOperationException("listing-only fake: GET")
    }

    private fun ms(vararg responses: String) =
        """<?xml version="1.0" encoding="utf-8"?>""" +
            """<D:multistatus xmlns:D="DAV:">${responses.joinToString("")}</D:multistatus>"""

    private fun res(
        href: String,
        collection: Boolean = false,
        etag: String? = null,
        status: String = "HTTP/1.1 200 OK"
    ): String {
        val type = if (collection) "<D:collection/>" else ""
        val etagXml = etag?.let { "<D:getetag>$it</D:getetag>" } ?: ""
        return "<D:response><D:href>$href</D:href><D:propstat><D:prop>" +
            "<D:resourcetype>$type</D:resourcetype>$etagXml</D:prop>" +
            "<D:status>$status</D:status></D:propstat></D:response>"
    }

    private fun entry(path: String, etag: String? = null) =
        RemoteEntry(path = path, href = path, isCollection = false, etag = etag)

    private fun client(transport: WebDavTransport, maxDepth: Int = WebDavClient.MAX_DEPTH) =
        WebDavClient(base, transport, maxDepth)

    // --------------------------------------------------------------- happy path

    @Test
    fun `a subtree is walked level by level and reported as files only`() {
        val transport = FakeTransport { url ->
            when (url) {
                rootUrl -> TransportResponse(
                    207, ms(
                        res("$hrefPrefix/", collection = true),
                        res("$hrefPrefix/t1.xml", etag = "\"1\""),
                        res("$hrefPrefix/t2.xml", etag = "\"2\""),
                        res("$hrefPrefix/sub", collection = true)
                    )
                )
                subUrl -> TransportResponse(
                    207, ms(
                        res("$hrefPrefix/sub", collection = true),
                        res("$hrefPrefix/sub/t3.xml", etag = "\"3\"")
                    )
                )
                else -> TransportResponse(404, "")
            }
        }

        val result = client(transport).listRecursive()

        assertThat(result).isInstanceOf(ListingResult.Complete::class.java)
        val complete = result as ListingResult.Complete
        assertThat(complete.files.map { it.path })
            .containsExactlyInAnyOrder("/t1.xml", "/t2.xml", "/sub/t3.xml")
        assertThat(complete.foldersVisited).isEqualTo(2)
        assertThat(transport.calls).containsExactlyInAnyOrder(rootUrl, subUrl)
    }

    @Test
    fun `the collection itself is never reported as a file`() {
        // An empty but successfully listed folder. The self entry must be dropped: as a
        // file it would be a phantom record whose absence next round reads as a delete.
        val transport = FakeTransport { TransportResponse(207, ms(res("$hrefPrefix/", collection = true))) }

        val complete = client(transport).listRecursive() as ListingResult.Complete

        assertThat(complete.files).isEmpty()
        assertThat(complete.foldersVisited).isEqualTo(1)
    }

    @Test
    fun `the collection itself is dropped even when the server omits the collection type`() {
        // Not every server fills in <resourcetype>. Without the identity-based drop, a
        // folder the server forgot to type would be listed as a *file*, and "that file
        // is gone" next round is indistinguishable from a user deleting a transaction.
        val transport = FakeTransport {
            TransportResponse(207, ms(res("$hrefPrefix/"), res("$hrefPrefix/t1.xml")))
        }

        val complete = client(transport).listRecursive() as ListingResult.Complete

        assertThat(complete.files.map { it.path }).containsExactly("/t1.xml")
    }

    @Test
    fun `a nested collection echoing itself is dropped one level down too`() {
        // The self entry arrives as a plain file here as well, so this also pins the
        // "compare against the requested path, not against the root" detail: a filter
        // written as `relative == ROOT` passes at the root and fails here.
        val transport = FakeTransport { url ->
            when (url) {
                rootUrl -> TransportResponse(207, ms(res("$hrefPrefix/sub", collection = true)))
                subUrl -> TransportResponse(
                    207, ms(
                        res("$hrefPrefix/sub"),                 // the self entry, one level down
                        res("$hrefPrefix/sub/t3.xml")
                    )
                )
                else -> TransportResponse(404, "")
            }
        }

        val complete = client(transport).listRecursive() as ListingResult.Complete

        assertThat(complete.files.map { it.path }).containsExactly("/sub/t3.xml")
    }

    @Test
    fun `every request uses Depth 1 and never infinity`() {
        // Depth: infinity is the one shape whose truncation is undetectable, so the
        // client must not use it even as an optimisation.
        val transport = FakeTransport { url ->
            if (url == rootUrl) TransportResponse(207, ms(res("$hrefPrefix/sub", collection = true)))
            else TransportResponse(207, ms(res("$hrefPrefix/sub/x.xml")))
        }

        client(transport).listRecursive()

        assertThat(transport.depths).isNotEmpty()
        assertThat(transport.depths).allMatch { it == WebDavClient.DEPTH_ONE }
    }

    @Test
    fun `a path with a space is percent-encoded in the request`() {
        val transport = FakeTransport { url ->
            if (url == rootUrl) {
                TransportResponse(207, ms(res("$hrefPrefix/a%20b", collection = true)))
            } else {
                TransportResponse(207, ms(res("$hrefPrefix/a%20b/x.xml")))
            }
        }

        val complete = client(transport).listRecursive() as ListingResult.Complete

        assertThat(transport.calls).contains("$base/a%20b")
        assertThat(complete.files.map { it.path }).containsExactly("/a b/x.xml")
    }

    @Test
    fun `an absolute-URL href is reduced to its path`() {
        val transport = FakeTransport {
            TransportResponse(207, ms(res("$base/t1.xml")))
        }

        val complete = client(transport).listRecursive() as ListingResult.Complete

        assertThat(complete.files.map { it.path }).containsExactly("/t1.xml")
    }

    // --------------------------------------------- incomplete listings (the point)

    @Test
    fun `a non-207 root response is incomplete, not an empty listing`() {
        // The crucial distinction: a 500 tells us nothing, so it must not be read as
        // "the folder is empty" — which the sync layer would read as "everything is gone".
        for (status in listOf(500, 502, 404, 401, 302, 200)) {
            val transport = FakeTransport { TransportResponse(status, "") }
            val result = client(transport).listRecursive()
            assertThat(result).`as`("status=%s", status).isInstanceOf(ListingResult.Incomplete::class.java)
            assertThat((result as ListingResult.Incomplete).reason)
                .`as`("status=%s", status).isEqualTo(IncompleteReason.HttpStatus)
        }
    }

    @Test
    fun `a 207 with one failed inner response is incomplete`() {
        val transport = FakeTransport {
            TransportResponse(
                207, ms(
                    res("$hrefPrefix/t1.xml"),
                    res("$hrefPrefix/locked.xml", status = "HTTP/1.1 503 Service Unavailable")
                )
            )
        }

        val result = client(transport).listRecursive() as ListingResult.Incomplete

        assertThat(result.reason).isEqualTo(IncompleteReason.FailedResponse)
        assertThat(result.detail).contains("locked.xml")
    }

    @Test
    fun `a malformed 207 body is incomplete`() {
        val transport = FakeTransport { TransportResponse(207, "<D:multistatus xmlns:D=\"DAV:\"><D:resp") }

        val result = client(transport).listRecursive()

        assertThat((result as ListingResult.Incomplete).reason).isEqualTo(IncompleteReason.MalformedResponse)
    }

    @Test
    fun `a transport failure is incomplete, never an exception and never empty`() {
        val transport = FakeTransport { throw IOException("connection reset by peer") }

        val result = client(transport).listRecursive()

        assertThat((result as ListingResult.Incomplete).reason).isEqualTo(IncompleteReason.Transport)
    }

    @Test
    fun `a failure one level down invalidates the whole listing`() {
        // The reason Depth: 1 plus local recursion exists at all: a partial tree is not
        // a listing, and this is the case Depth: infinity would have hidden.
        val transport = FakeTransport { url ->
            when (url) {
                rootUrl -> TransportResponse(207, ms(res("$hrefPrefix/sub", collection = true)))
                else -> TransportResponse(503, "")
            }
        }

        val result = client(transport).listRecursive()

        assertThat((result as ListingResult.Incomplete).reason).isEqualTo(IncompleteReason.HttpStatus)
        assertThat(transport.calls).containsExactly(rootUrl, subUrl)
    }

    @Test
    fun `a tree deeper than the bound is incomplete instead of silently truncated`() {
        val transport = FakeTransport { url ->
            when (url.removePrefix(base)) {
                "/" -> TransportResponse(207, ms(res("$hrefPrefix/deep", collection = true)))
                "/deep" -> TransportResponse(207, ms(res("$hrefPrefix/deep/deeper", collection = true)))
                "/deep/deeper" -> TransportResponse(207, ms(res("$hrefPrefix/deep/deeper/x.xml")))
                else -> TransportResponse(404, "")
            }
        }

        val result = client(transport, maxDepth = 1).listRecursive()

        assertThat((result as ListingResult.Incomplete).reason).isEqualTo(IncompleteReason.DepthExceeded)
    }

    @Test
    fun `an href outside the client root is refused`() {
        val transport = FakeTransport { TransportResponse(207, ms(res("/elsewhere/t1.xml"))) }

        val result = client(transport).listRecursive()

        assertThat((result as ListingResult.Incomplete).reason).isEqualTo(IncompleteReason.MalformedResponse)
    }

    @Test
    fun `a Depth-1 response reaching sideways is refused`() {
        // Asking about /sub and being handed a sibling of the root means the tree we
        // are walking is not the tree we think we are walking.
        val transport = FakeTransport { url ->
            when (url) {
                rootUrl -> TransportResponse(207, ms(res("$hrefPrefix/sub", collection = true)))
                else -> TransportResponse(207, ms(res("$hrefPrefix/t1.xml")))
            }
        }

        val result = client(transport).listRecursive()

        assertThat((result as ListingResult.Incomplete).reason).isEqualTo(IncompleteReason.MalformedResponse)
    }

    // ------------------------------------- the wire from "listing" to "deletion"

    @Test
    fun `an incomplete listing can never produce a tombstone, for any reason`() {
        // This is the cross-module invariant the whole round exists to pin down: the
        // only thing standing between a flaky network and "both devices deleted the
        // book" is that an untrusted listing cannot answer "is it gone?".
        //
        // `present` is deliberately NON-empty: with an empty listing the merge layer's
        // other gate ("empty while records are known") would refuse the round anyway,
        // and this test would pass without the completeness flag doing anything. T1
        // present keeps gate 2 out of the picture so the flag is the only thing left
        // deciding the outcome — which is exactly what has to be proven.
        for (reason in IncompleteReason.entries) {
            val listing = ListingResult.Incomplete(reason, "synthetic")
            val plan = ThreeWayMerge.planDeletions(
                watched = setOf("T1", "T2"),
                present = setOf("T1"),
                previousPending = mapOf("T1" to 1L),
                now = 100L,
                listingComplete = listing.confirmsAbsence
            )
            assertThat(plan.abandoned).`as`("reason=%s", reason).isTrue()
            assertThat(plan.tombstones).`as`("reason=%s", reason).isEmpty()
            assertThat(plan.pending).`as`("reason=%s", reason).containsOnlyKeys("T1")
        }
    }

    @Test
    fun `a complete listing drives the ordinary two-round deletion planning`() {
        val listing = ListingResult.Complete(
            files = listOf(entry("/T1.xml", etag = "\"1\""), entry("/T3.xml")),
            foldersVisited = 1
        )
        assertThat(listing.confirmsAbsence).isTrue()

        // The record key is the file's stem — the orchestrator's job, shown here so the
        // listing and the merge are wired together rather than asserted separately.
        val present = listing.files.map { WebDavPath.name(it.path).substringBeforeLast('.') }.toSet()
        assertThat(present).containsExactlyInAnyOrder("T1", "T3")

        // T2 was known before and is not listed now ⇒ first confirmed absence ⇒ pending.
        val first = ThreeWayMerge.planDeletions(
            watched = setOf("T1", "T2"),
            present = present,
            previousPending = emptyMap(),
            now = 100L,
            listingComplete = listing.confirmsAbsence
        )
        assertThat(first.abandoned).isFalse()
        assertThat(first.tombstones).isEmpty()
        assertThat(first.pending).containsOnlyKeys("T2")

        // Second consecutive absence ⇒ promoted to a tombstone.
        val second = ThreeWayMerge.planDeletions(
            watched = setOf("T1", "T2"),
            present = present,
            previousPending = first.pending,
            now = 200L,
            listingComplete = listing.confirmsAbsence
        )
        assertThat(second.tombstones).containsExactly("T2")
    }

    @Test
    fun `a successfully empty listing still does not delete everything in one round`() {
        // Complete + empty is trusted as "the folder really is empty", but the merge
        // layer's own second gate refuses to act on it while records are known — so the
        // worst case is a sync that does not converge, never a wipe.
        val listing = ListingResult.Complete(files = emptyList(), foldersVisited = 1)
        assertThat(listing.confirmsAbsence).isTrue()

        val plan = ThreeWayMerge.planDeletions(
            watched = setOf("T1", "T2"),
            present = emptySet(),
            previousPending = emptyMap(),
            now = 100L,
            listingComplete = listing.confirmsAbsence
        )

        assertThat(plan.abandoned).isTrue()
        assertThat(plan.tombstones).isEmpty()
    }
}
