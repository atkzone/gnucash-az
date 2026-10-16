package org.gnucash.android.test.unit.sync.webdav

import org.assertj.core.api.Assertions.assertThat
import org.gnucash.android.sync.webdav.ActionOutcome
import org.gnucash.android.sync.webdav.PutPrecondition
import org.gnucash.android.sync.webdav.WebDavActions
import org.gnucash.android.sync.webdav.requiresReMerge
import org.gnucash.android.sync.webdav.succeeded
import org.junit.Test

/**
 * The pure write policy (plan §5② step 1, §7.6).
 *
 * A plain JVM test: every case is "given this status code, what does it mean to a sync
 * round", which is exactly the kind of question that must not be answered by a live
 * server behaving well.
 */
class WebDavActionsTest {

    // -------------------------------------------------------------- put / delete

    @Test
    fun `a 2xx that is not a completed write is not Done`() {
        // 202 Accepted means the server took the request but has not applied it. Reading
        // it as Done would let the round record a base for content that is not there —
        // and the next listing would then read "no record" as "it was deleted".
        assertThat(WebDavActions.classifyPut(200)).isEqualTo(ActionOutcome.Done(200))
        assertThat(WebDavActions.classifyPut(201)).isEqualTo(ActionOutcome.Done(201))
        assertThat(WebDavActions.classifyPut(204)).isEqualTo(ActionOutcome.Done(204))
        assertThat(WebDavActions.classifyPut(202)).isInstanceOf(ActionOutcome.Failed::class.java)
    }

    @Test
    fun `a rejected conditional PUT is Stale, never Done and never a plain failure`() {
        // The lost-update guard. 412 says "someone else changed this since your snapshot":
        // the round must re-list and re-merge, so this has to be its own outcome rather
        // than being swallowed as Done (lost change) or as Failed (never converges).
        val outcome = WebDavActions.classifyPut(412)

        assertThat(outcome).isInstanceOf(ActionOutcome.Stale::class.java)
        assertThat(outcome.succeeded).isFalse()
        assertThat(outcome.requiresReMerge).isTrue()
    }

    @Test
    fun `a PUT into a missing collection is Missing, so the caller can create it`() {
        assertThat(WebDavActions.classifyPut(409)).isInstanceOf(ActionOutcome.Missing::class.java)
        assertThat(WebDavActions.classifyPut(404)).isInstanceOf(ActionOutcome.Missing::class.java)
    }

    @Test
    fun `a DELETE of a record that is already gone is AlreadyDone, not a failure`() {
        // The goal state — "the record is not on the server" — already holds. Reporting a
        // failure would retry an applied delete forever, and would make "missing" look the
        // same as "the delete did not work", which are opposite conclusions.
        for (status in listOf(404, 410)) {
            val outcome = WebDavActions.classifyDelete(status)
            assertThat(outcome).`as`("status=%s", status).isInstanceOf(ActionOutcome.AlreadyDone::class.java)
            assertThat(outcome.succeeded).`as`("status=%s", status).isTrue()
        }
        assertThat(WebDavActions.classifyDelete(200)).isEqualTo(ActionOutcome.Done(200))
        assertThat(WebDavActions.classifyDelete(204)).isEqualTo(ActionOutcome.Done(204))
    }

    @Test
    fun `a partially applied DELETE is a failure, not a completed deletion`() {
        // 207 is only defined for collections; a record is not one, so a 207 means the
        // delete was applied to *some* of the resource. Saying "deleted" here would let
        // the tombstone travel while the payload is still there.
        assertThat(WebDavActions.classifyDelete(207)).isInstanceOf(ActionOutcome.Failed::class.java)
    }

    // ------------------------------------------------------------------- mkcol

    @Test
    fun `MKCOL of a collection that already exists is AlreadyDone`() {
        // RFC 4918 §9.3 defines 405 for "a resource already exists at the request URI", and
        // that is precisely what a peer creating the same folder concurrently looks like.
        // As a failure, ensureCollections would abort on the very race it exists to tolerate.
        val outcome = WebDavActions.classifyMkcol(405)

        assertThat(outcome).isInstanceOf(ActionOutcome.AlreadyDone::class.java)
        assertThat(outcome.succeeded).isTrue()
        assertThat(WebDavActions.classifyMkcol(201)).isEqualTo(ActionOutcome.Done(201))
    }

    @Test
    fun `MKCOL whose parent is missing is Missing, so the order of creation is diagnosable`() {
        assertThat(WebDavActions.classifyMkcol(409)).isInstanceOf(ActionOutcome.Missing::class.java)
        assertThat(WebDavActions.classifyMkcol(403)).isInstanceOf(ActionOutcome.Failed::class.java)
    }

    // -------------------------------------------------------------------- move

    @Test
    fun `a MOVE refused because the destination exists is Stale`() {
        assertThat(WebDavActions.classifyMove(412)).isInstanceOf(ActionOutcome.Stale::class.java)
        assertThat(WebDavActions.classifyMove(201)).isEqualTo(ActionOutcome.Done(201))
        assertThat(WebDavActions.classifyMove(204)).isEqualTo(ActionOutcome.Done(204))
        assertThat(WebDavActions.classifyMove(409)).isInstanceOf(ActionOutcome.Missing::class.java)
    }

    // --------------------------------------------------------------------- get

    @Test
    fun `a partial GET is never accepted as a whole record`() {
        // 206 would be stored as a complete book, turning "the record is corrupt" into
        // something much harder to notice than "the record is missing".
        assertThat(WebDavActions.classifyGet(200)).isEqualTo(ActionOutcome.Done(200))
        assertThat(WebDavActions.classifyGet(206)).isInstanceOf(ActionOutcome.Failed::class.java)
        assertThat(WebDavActions.classifyGet(404)).isInstanceOf(ActionOutcome.Missing::class.java)
        assertThat(WebDavActions.classifyGet(412)).isInstanceOf(ActionOutcome.Stale::class.java)
    }

    @Test
    fun `succeeded covers exactly Done and AlreadyDone`() {
        assertThat(ActionOutcome.Done(201).succeeded).isTrue()
        assertThat(ActionOutcome.AlreadyDone(405, "exists").succeeded).isTrue()
        assertThat(ActionOutcome.Stale(412, "stale").succeeded).isFalse()
        assertThat(ActionOutcome.Missing(409, "gone").succeeded).isFalse()
        assertThat(ActionOutcome.Failed(500, "boom").succeeded).isFalse()
    }

    @Test
    fun `only Stale asks the round to re-merge instead of retrying`() {
        assertThat(ActionOutcome.Stale(412, "x").requiresReMerge).isTrue()
        assertThat(ActionOutcome.Failed(500, "x").requiresReMerge).isFalse()
        assertThat(ActionOutcome.Missing(409, "x").requiresReMerge).isFalse()
        assertThat(ActionOutcome.AlreadyDone(404, "x").requiresReMerge).isFalse()
    }

    // ------------------------------------------------------- collectionsToEnsure

    @Test
    fun `the collections a nested record needs are reported root-first`() {
        // Order is not cosmetic: MKCOL /a/b before /a exists answers 409, so any other
        // order fails on the first nested folder.
        assertThat(WebDavActions.collectionsToEnsure("/a/b/c.xml", "/"))
            .containsExactly("/a", "/a/b")
    }

    @Test
    fun `a record directly in the root needs no collections created`() {
        assertThat(WebDavActions.collectionsToEnsure("/c.xml", "/")).isEmpty()
    }

    @Test
    fun `a record under a configured sub-root is created relative to that root`() {
        assertThat(WebDavActions.collectionsToEnsure("/book/a/b/c.xml", "/book"))
            .containsExactly("/book/a", "/book/a/b")
        assertThat(WebDavActions.collectionsToEnsure("/book/c.xml", "/book")).isEmpty()
    }

    @Test
    fun `the client root itself is never among the collections to create`() {
        // The root is the folder the user configured; it is created out of band, and
        // MKCOL-ing it would be an unrequested write outside the sync's own subtree.
        assertThat(WebDavActions.collectionsToEnsure("/book/c.xml", "/book")).doesNotContain("/book")
        assertThat(WebDavActions.collectionsToEnsure("/a/c.xml", "/")).doesNotContain("/")
    }

    @Test
    fun `a path outside the root, or the root itself, needs nothing`() {
        assertThat(WebDavActions.collectionsToEnsure("/other/a/c.xml", "/book")).isEmpty()
        assertThat(WebDavActions.collectionsToEnsure("/book", "/book")).isEmpty()
    }

    @Test
    fun `a collection path with spaces is normalised before the ancestors are derived`() {
        assertThat(WebDavActions.collectionsToEnsure("/a%20b/c%20d/c.xml", "/"))
            .containsExactly("/a b", "/a b/c d")
    }

    @Test
    fun `parentPath walks one level up and stops at the root`() {
        assertThat(WebDavActions.parentPath("/a/b")).isEqualTo("/a")
        assertThat(WebDavActions.parentPath("/a")).isEqualTo("/")
        assertThat(WebDavActions.parentPath("/")).isNull()
    }

    // ------------------------------------------------------------------ headers

    @Test
    fun `a PUT carries exactly one precondition, chosen by the caller`() {
        // If-Match and If-None-Match are alternatives, not a conjunction. Making that
        // structural beats remembering it at every call site.
        assertThat(WebDavActions.putHeaders(PutPrecondition.None)).isEmpty()
        assertThat(WebDavActions.putHeaders(PutPrecondition.MustNotExist))
            .containsExactlyEntriesOf(mapOf("If-None-Match" to "*"))
        assertThat(WebDavActions.putHeaders(PutPrecondition.MustMatch("e1")))
            .containsExactlyEntriesOf(mapOf("If-Match" to "\"e1\""))
    }

    @Test
    fun `an etag is re-quoted for the header and the wildcard stays bare`() {
        assertThat(WebDavActions.quoteEtag("abc")).isEqualTo("\"abc\"")
        assertThat(WebDavActions.quoteEtag("\"abc\"")).isEqualTo("\"abc\"")
        assertThat(WebDavActions.quoteEtag("*")).isEqualTo("*")
        assertThat(WebDavActions.ifMatch("abc")).containsExactlyEntriesOf(mapOf("If-Match" to "\"abc\""))
    }

    @Test
    fun `MOVE states both the destination and the overwrite policy explicitly`() {
        assertThat(WebDavActions.moveHeaders("https://h/book/a.xml", overwrite = false))
            .containsExactlyEntriesOf(
                mapOf("Destination" to "https://h/book/a.xml", "Overwrite" to "F")
            )
        assertThat(WebDavActions.moveHeaders("https://h/book/a.xml", overwrite = true)["Overwrite"])
            .isEqualTo("T")
    }
}
