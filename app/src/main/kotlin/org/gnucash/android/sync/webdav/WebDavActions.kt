/*
 * Copyright (c) 2026 GnuCash Pocket contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.gnucash.android.sync.webdav

/**
 * How one write verb ended.
 *
 * The vocabulary is deliberately five cases rather than "success / failure", because the
 * four non-[Done] outcomes demand four **different** follow-ups, and collapsing any two of
 * them is how a sync engine ends up either looping forever or silently dropping a change:
 *
 * | outcome | what the round must do |
 * |---|---|
 * | [Done] | record the new base (incl. [Done.etag]) |
 * | [AlreadyDone] | treat as success, but do not claim *we* changed it |
 * | [Stale] | re-list and re-merge; **never** retry the same write |
 * | [Missing] | the target or a parent is gone — create it, or drop the attempt |
 * | [Failed] | log and leave the local state untouched |
 */
sealed interface ActionOutcome {

    /**
     * The server did what was asked.
     *
     * @param etag the entity tag the server reported for the stored representation, when
     *             it sent one. Recording it is what keeps the *next* round's `If-Match`
     *             from being answered with a permanent `412`.
     */
    data class Done(val status: Int, val etag: String? = null) : ActionOutcome

    /**
     * The goal state already holds, so nothing was changed — and nothing needs to be.
     *
     * Kept distinct from [Done] because the round must not record a base as if *it* had
     * produced the current remote content. Two examples: `DELETE` on a record that is
     * already gone (`404`), and `MKCOL` on a collection that already exists (`405`, which
     * is exactly what a peer creating the same folder concurrently looks like).
     */
    data class AlreadyDone(val status: Int, val because: String) : ActionOutcome

    /**
     * A **precondition** rejected the write: our view of the resource is out of date.
     *
     * This is a control signal, not an error. The peer (or another client) changed the
     * record after we listed it, so the merge that produced this write is based on a stale
     * snapshot; the round must re-list, re-merge, and only then decide again. Retrying the
     * same write is guaranteed to fail and would spin forever.
     */
    data class Stale(val status: Int, val detail: String) : ActionOutcome

    /** The target, or a collection above it, does not exist. */
    data class Missing(val status: Int, val detail: String) : ActionOutcome

    /** Anything else — a status we cannot act on, or [STATUS_NO_RESPONSE] for a transport failure. */
    data class Failed(val status: Int, val detail: String) : ActionOutcome
}

/** `true` for the two outcomes after which the round may continue as if the verb worked. */
val ActionOutcome.succeeded: Boolean
    get() = this is ActionOutcome.Done || this is ActionOutcome.AlreadyDone

/** `true` when the round has to go back to listing before it may write anything else. */
val ActionOutcome.requiresReMerge: Boolean
    get() = this is ActionOutcome.Stale

/**
 * A `GET` either produced bytes or was refused.
 *
 * The success branch carries the payload, so it cannot be expressed as an [ActionOutcome]
 * without making every write carry a nullable `ByteArray`. The refusal branch *is* an
 * [ActionOutcome], so `Missing` / `Stale` / `Failed` mean exactly the same thing here as
 * they do for a write.
 */
sealed interface DownloadOutcome {
    data class Got(val bytes: ByteArray, val etag: String?) : DownloadOutcome
    data class Refused(val reason: ActionOutcome) : DownloadOutcome
}

/** What a `PUT`/`DELETE` must prove before the server accepts it. */
sealed interface PutPrecondition {

    /** No precondition. Correct only when the merge already decided nothing else can race. */
    data object None : PutPrecondition

    /**
     * `If-None-Match: *` — "create, but only if it is not there yet".
     *
     * The right choice for a record the base says is new. Without it, a peer that created
     * the same record between our listing and our upload would be **silently overwritten**
     * by us, and the conflict would never be seen.
     */
    data object MustNotExist : PutPrecondition

    /**
     * `If-Match: <etag>` — "write, but only if the remote is still what we based this on".
     *
     * The right choice for a record the base already knows. Note that `If-Match` uses the
     * **strong** comparison (RFC 9110 §13.1.1), so an etag that arrived as weak (`W/"x"`)
     * cannot match; [WebDavXml] drops the `W/` marker, so such a server answers `412`
     * forever. Every server targeted here (Nextcloud, 坚果云, mod_dav, SabreDAV) emits
     * strong etags, and the failure direction if one did not is "sync stops converging"
     * rather than "a change is lost" — which is the direction to prefer, but it is a real
     * limitation and is recorded as such in the plan.
     */
    data class MustMatch(val etag: String) : PutPrecondition
}

/** What a `GET` must prove. */
sealed interface GetPrecondition {
    data object None : GetPrecondition
    data class MustMatch(val etag: String) : GetPrecondition
}

/**
 * The **pure** WebDAV write policy (plan §5② step 1, §7.6).
 *
 * Nothing here touches the network, the disk, or the clock: given a status code it says
 * what that status *means to a sync round*. That is the whole reason it is a separate
 * object — the risky part of the write half is not "how do I send a `PUT`" (OkHttp does
 * that) but "is `405` a failure or a success", and that question must be exhaustively
 * assertable.
 *
 * **The recurring theme: idempotency is not the same as success.** A round may be
 * re-run — after a crash, after a `412`, after the user taps sync twice. Every classifier
 * below therefore maps "the goal state already holds" onto [ActionOutcome.AlreadyDone]
 * rather than onto an error, so that re-running converges instead of stalling.
 */
object WebDavActions {

    const val METHOD_PUT = "PUT"
    const val METHOD_GET = "GET"
    const val METHOD_DELETE = "DELETE"
    const val METHOD_MKCOL = "MKCOL"
    const val METHOD_MOVE = "MOVE"

    /**
     * Sentinel status for "no HTTP answer at all" (DNS, TLS, timeout, socket reset).
     *
     * Zero is not a valid status code, so it cannot collide with a real reply — the point
     * being that "the server said no" and "we never reached the server" must not be
     * readable as the same thing.
     */
    const val STATUS_NO_RESPONSE = 0

    // ------------------------------------------------------------------ classifiers

    /**
     * `PUT` (RFC 4918 §9.7).
     *
     * `200` / `201` / `204` are the success family. Other `2xx` are **not**: `202 Accepted`
     * means the server took the request but has not applied it yet, and reading that as
     * [ActionOutcome.Done] would let the round record a base for content that does not
     * exist — after which the next listing sees nothing where the base claims a record,
     * and proposes deleting it.
     */
    fun classifyPut(status: Int): ActionOutcome = when (status) {
        200, 201, 204 -> ActionOutcome.Done(status)
        412 -> ActionOutcome.Stale(status, "the remote changed since the snapshot (If-Match/If-None-Match)")
        409 -> ActionOutcome.Missing(status, "a parent collection is missing")
        404 -> ActionOutcome.Missing(status, "the target collection is gone")
        423 -> ActionOutcome.Failed(status, "the record is locked")
        507 -> ActionOutcome.Failed(status, "the server is out of space")
        else -> ActionOutcome.Failed(status, "unexpected status for PUT")
    }

    /**
     * `DELETE` (RFC 4918 §9.6).
     *
     * `404` / `410` are **[ActionOutcome.AlreadyDone]**, not failures: the goal state —
     * "the record is not on the server" — already holds. Reporting them as failures would
     * mean a deletion that was applied by an earlier, interrupted round is retried forever,
     * and it would also make "the record is missing" indistinguishable from "the delete
     * did not work", which are opposite conclusions.
     *
     * `207` is refused: it means the server partially applied the delete (only defined for
     * collections, which records are not), and a partially deleted record must not be
     * reported as deleted.
     */
    fun classifyDelete(status: Int): ActionOutcome = when (status) {
        200, 204 -> ActionOutcome.Done(status)
        404, 410 -> ActionOutcome.AlreadyDone(status, "the record is already gone (HTTP $status)")
        412 -> ActionOutcome.Stale(status, "the remote changed since the snapshot")
        207 -> ActionOutcome.Failed(status, "a partial delete (207) is not a completed delete")
        else -> ActionOutcome.Failed(status, "unexpected status for DELETE")
    }

    /**
     * `MKCOL` (RFC 4918 §9.3).
     *
     * `405` is **[ActionOutcome.AlreadyDone]**: RFC 4918 specifies `405 Method Not Allowed`
     * for "a resource already exists at the request URI", and that is exactly what two
     * devices creating the same folder looks like. Treating it as a failure would make
     * `ensureCollections` abort on the very race it exists to tolerate.
     *
     * `409` is [ActionOutcome.Missing] — an *ancestor* is absent. Because collections are
     * created root-first, in practice this means the client's own root is not there, i.e.
     * the configured folder does not exist.
     */
    fun classifyMkcol(status: Int): ActionOutcome = when (status) {
        201, 200, 204 -> ActionOutcome.Done(status)
        405 -> ActionOutcome.AlreadyDone(status, "the collection already exists")
        409 -> ActionOutcome.Missing(status, "a parent collection is missing")
        403 -> ActionOutcome.Failed(status, "the parent is not writable")
        else -> ActionOutcome.Failed(status, "unexpected status for MKCOL")
    }

    /**
     * `MOVE` (RFC 4918 §9.9).
     *
     * `412` here means "the destination exists and `Overwrite: F`" — a stale view, so it is
     * [ActionOutcome.Stale] for the same reason a failed `If-Match` is: the round must
     * re-list before deciding again.
     */
    fun classifyMove(status: Int): ActionOutcome = when (status) {
        200, 201, 204 -> ActionOutcome.Done(status)
        412 -> ActionOutcome.Stale(status, "the destination exists and Overwrite was false")
        409 -> ActionOutcome.Missing(status, "a parent collection is missing")
        403 -> ActionOutcome.Failed(status, "the destination is not writable")
        else -> ActionOutcome.Failed(status, "unexpected status for MOVE")
    }

    /**
     * `GET`.
     *
     * Only `200` is [ActionOutcome.Done]: `206 Partial Content` (which a `Range`-happy
     * implementation could provoke) would be stored as a whole book if it were accepted,
     * which is a corrupt record rather than a missing one — much harder to notice.
     */
    fun classifyGet(status: Int): ActionOutcome = when (status) {
        200 -> ActionOutcome.Done(status)
        206 -> ActionOutcome.Failed(status, "a partial response (206) is not a whole record")
        404, 410 -> ActionOutcome.Missing(status, "the record is not there")
        412 -> ActionOutcome.Stale(status, "the record changed since the snapshot")
        403 -> ActionOutcome.Failed(status, "forbidden")
        else -> ActionOutcome.Failed(status, "unexpected status for GET")
    }

    // ------------------------------------------------------------------ path helpers

    /**
     * The collections that must exist for [path] to be writable, **root-first** and
     * excluding the file itself.
     *
     * `("/a/b/c.xml", root = "/")` → `["/a", "/a/b"]`. The order is the whole point:
     * `MKCOL /a/b` before `/a` exists answers `409`, so creating them in any other order
     * fails on the first nested folder.
     *
     * The client's root is **never** included. It is the folder the user configured, it is
     * created out of band (a Nextcloud account's root, for instance), and trying to `MKCOL`
     * it would be an unrequested write outside the sync's own subtree. If it is genuinely
     * absent, the listing for this round has already failed.
     *
     * Returns an empty list when [path] names a file directly inside the root, or lies
     * outside it.
     *
     * [rootPath] is expressed in the **same coordinate system as [path]** — for the client
     * that is the relative space `listRecursive()` reports in, whose root is `"/"`. It is
     * *not* the path component of the base URL.
     */
    fun collectionsToEnsure(path: String, rootPath: String): List<String> {
        val root = WebDavPath.normalize(rootPath)
        val target = WebDavPath.normalize(path)
        if (target == root || !WebDavPath.isUnder(root, target)) return emptyList()

        val relative = if (root == "/") target else target.removePrefix(root)
        val segments = relative.split('/').filter { it.isNotEmpty() }
        // The last segment is the file; zero or one segment means it sits in the root.
        if (segments.size <= 1) return emptyList()

        val collections = mutableListOf<String>()
        var current = root
        for (i in 0 until segments.size - 1) {
            current = WebDavPath.join(current, segments[i])
            collections += current
        }
        return collections
    }

    /** The containing collection of [path], or `null` when [path] is the root. */
    fun parentPath(path: String): String? {
        val normalized = WebDavPath.normalize(path)
        if (normalized == "/") return null
        val parent = normalized.substringBeforeLast('/', "")
        return if (parent.isEmpty()) "/" else parent
    }

    // ------------------------------------------------------------------- headers

    /** `If-Match: <etag>` — the conditional-write header for a record the base knows. */
    fun ifMatch(etag: String): Map<String, String> = mapOf("If-Match" to quoteEtag(etag))

    /** `If-None-Match: *` — "create only". */
    fun ifNoneMatchAny(): Map<String, String> = mapOf("If-None-Match" to "*")

    /**
     * The precondition headers for a `PUT`/`DELETE`. Exactly zero or one header is
     * produced: `If-Match` and `If-None-Match` are alternatives, not a conjunction, and
     * making that structural is cheaper than remembering it at every call site.
     */
    fun putHeaders(precondition: PutPrecondition): Map<String, String> = when (precondition) {
        PutPrecondition.None -> emptyMap()
        PutPrecondition.MustNotExist -> ifNoneMatchAny()
        is PutPrecondition.MustMatch -> ifMatch(precondition.etag)
    }

    /**
     * `MOVE` headers. `Destination` is required to be an absolute URL (RFC 4918 §9.9.3),
     * and `Overwrite` is sent explicitly rather than relying on the default, because
     * "silently replace the destination" and "refuse because the destination exists" are
     * different policies and the caller should have chosen one.
     */
    fun moveHeaders(destinationUrl: String, overwrite: Boolean): Map<String, String> = mapOf(
        "Destination" to destinationUrl,
        "Overwrite" to if (overwrite) "T" else "F"
    )

    /**
     * Re-quotes a bare etag for a conditional header.
     *
     * Etags reach the client from [WebDavXml], which strips quotes and the `W/` marker for
     * comparison. A request header needs them back, including for the wildcard, which is
     * the one value that must stay unquoted.
     */
    fun quoteEtag(etag: String): String {
        val trimmed = etag.trim()
        if (trimmed == "*") return "*"
        return "\"" + trimmed.removeSurrounding("\"") + "\""
    }
}
