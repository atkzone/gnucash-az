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
 * Lists a remote collection subtree, and — critically — says whether the listing can
 * be trusted.
 *
 * ### Why this is not just "return the files"
 *
 * A sync engine's deletion rule is "the record is gone over there" (plan §7.3,
 * constraint 2). The **only** evidence for that is a listing that ran to completion.
 * Every branch of this class is therefore written so that an imperfect listing cannot
 * masquerade as an empty one: a `500`, a truncated body, a single failed `<response>`
 * inside an otherwise fine `207` — each yields [ListingResult.Incomplete], never
 * `Complete(emptyList())`.
 *
 * The failure direction is chosen on purpose: the worst case is "sync refuses to run
 * today", not "sync deleted the book on both devices".
 *
 * ### Why `Depth: 1` plus local recursion
 *
 * `Depth: infinity` looks cheaper, but it is the one shape that *cannot* be checked:
 * if the server silently stops half way through a large tree, the client receives a
 * pristine `207` containing a subset of the files and has no way to tell. Recursing
 * one level at a time means the client owns the traversal, so a failure on any single
 * level is visible and aborts the whole listing.
 */
class WebDavClient(
    baseUrl: String,
    private val transport: WebDavTransport,
    private val maxDepth: Int = MAX_DEPTH
) {

    /** Base URL without a trailing slash; all requests are built from this. */
    private val base = baseUrl.trimEnd('/')

    /**
     * The path component of [baseUrl] (its "root"), normalised. Used to turn the
     * absolute `href`s a server returns into paths relative to our root — Nextcloud
     * answers with `/remote.php/dav/files/<user>/<dir>/...`, and comparing that
     * against a plain `<dir>/...` would make every record look absent.
     */
    private val rootPath = pathOf(baseUrl)

    /**
     * Enumerates every **file** in the subtree rooted at this client's URL.
     *
     * Folders are traversed but not reported — a folder is not a syncable record, and
     * reporting one would turn "next round's listing has no such folder" into a
     * phantom deletion.
     */
    fun listRecursive(): ListingResult {
        val visited = LinkedHashSet<String>()
        val files = LinkedHashMap<String, RemoteEntry>()
        val queue = ArrayDeque<Pair<String, Int>>()
        queue += ROOT to 0

        while (queue.isNotEmpty()) {
            val (path, depth) = queue.removeFirst()
            if (!visited.add(path)) continue
            if (depth > maxDepth) {
                return ListingResult.Incomplete(
                    IncompleteReason.DepthExceeded,
                    "recursion below $path exceeds the depth bound of $maxDepth"
                )
            }

            when (val level = listOneLevel(path)) {
                is LevelResult.Bad -> return ListingResult.Incomplete(level.reason, level.detail)
                is LevelResult.Ok -> for (child in level.children) {
                    if (child.isCollection) {
                        queue += child.path to depth + 1
                    } else {
                        // First writer wins: a server that repeats an href must not
                        // produce two files with the same identity.
                        files.putIfAbsent(child.path, child)
                    }
                }
            }
        }

        return ListingResult.Complete(files.values.toList(), visited.size)
    }

    // -------------------------------------------------------------------- actions

    /**
     * `PUT`s a record, optionally under a precondition.
     *
     * The precondition is not a nicety. Between the listing that produced the base and
     * this write, the peer may have written the same record; an unconditional `PUT`
     * would then *silently* overwrite it — the classic lost update, and one that leaves
     * both sides' logs green. With `If-Match` the server answers `412`, which arrives
     * here as [ActionOutcome.Stale] and sends the round back to re-list and re-merge.
     *
     * @param precondition see [PutPrecondition]; use [PutPrecondition.MustNotExist] for a
     *                     record the base says is **new**, and
     *                     [PutPrecondition.MustMatch] for one the base already knows.
     */
    fun put(
        path: String,
        bytes: ByteArray,
        precondition: PutPrecondition = PutPrecondition.None
    ): ActionOutcome = when (val call = call(WebDavActions.METHOD_PUT, path, WebDavActions.putHeaders(precondition), bytes)) {
        is Call.Broken -> ActionOutcome.Failed(WebDavActions.STATUS_NO_RESPONSE, call.detail)
        is Call.Ok ->
            // The server echoes the entity tag of what it stored; keeping it lets the
            // caller record a base that will still match next round.
            WebDavActions.classifyPut(call.response.status).let {
                if (it is ActionOutcome.Done) it.copy(etag = call.response.etag) else it
            }
    }

    /**
     * `GET`s a record's payload.
     *
     * A missing record is [DownloadOutcome.Refused] with [ActionOutcome.Missing], *not*
     * an empty body: "the peer no longer has it" and "the peer has an empty file" are
     * different facts, and only the first one is a deletion.
     */
    fun download(path: String, precondition: GetPrecondition = GetPrecondition.None): DownloadOutcome {
        val headers = when (precondition) {
            GetPrecondition.None -> emptyMap()
            is GetPrecondition.MustMatch -> WebDavActions.ifMatch(precondition.etag)
        }
        return try {
            val response = transport.download(urlFor(path), headers)
            when (val outcome = WebDavActions.classifyGet(response.status)) {
                is ActionOutcome.Done -> DownloadOutcome.Got(response.bytes, response.etag)
                else -> DownloadOutcome.Refused(outcome)
            }
        } catch (e: Exception) {
            DownloadOutcome.Refused(
                ActionOutcome.Failed(WebDavActions.STATUS_NO_RESPONSE, describe(e))
            )
        }
    }

    /** `DELETE`s a record. A `404` is [ActionOutcome.AlreadyDone]: the goal state holds. */
    fun delete(path: String, precondition: PutPrecondition = PutPrecondition.None): ActionOutcome =
        when (val call = call(WebDavActions.METHOD_DELETE, path, WebDavActions.putHeaders(precondition), null)) {
            is Call.Broken -> ActionOutcome.Failed(WebDavActions.STATUS_NO_RESPONSE, call.detail)
            is Call.Ok -> WebDavActions.classifyDelete(call.response.status)
        }

    /** Creates one collection. A `405` is [ActionOutcome.AlreadyDone]: it is already there. */
    fun mkcol(path: String): ActionOutcome =
        when (val call = call(WebDavActions.METHOD_MKCOL, path, emptyMap(), null)) {
            is Call.Broken -> ActionOutcome.Failed(WebDavActions.STATUS_NO_RESPONSE, call.detail)
            is Call.Ok -> WebDavActions.classifyMkcol(call.response.status)
        }

    /**
     * Moves (renames) a record.
     *
     * `Destination` must be an **absolute** URL per RFC 4918, so it is built from this
     * client's base rather than from the relative path — a relative destination is one of
     * the shapes servers disagree about, and a move that lands somewhere else is a lost
     * record plus a phantom tombstone.
     *
     * @param overwrite `Overwrite: F` makes "the destination already exists" a `412`
     *                  instead of a silent clobber; that is [ActionOutcome.Stale].
     */
    fun move(from: String, to: String, overwrite: Boolean = false): ActionOutcome =
        when (val call = call(
            WebDavActions.METHOD_MOVE,
            from,
            WebDavActions.moveHeaders(urlFor(to), overwrite),
            null
        )) {
            is Call.Broken -> ActionOutcome.Failed(WebDavActions.STATUS_NO_RESPONSE, call.detail)
            is Call.Ok -> WebDavActions.classifyMove(call.response.status)
        }

    /**
     * Creates whatever ancestor collections [path] needs, **root-first**.
     *
     * A `PUT` into a collection that does not exist answers `409 Conflict` — so a sync
     * engine that uploaded the record directly would have every upload into a fresh
     * sub-folder rejected, and would report it as "the record is unwritable" rather than
     * "the folder is missing".
     *
     * @return `null` when everything needed was already there or was just created
     *         (including the common "none were needed" case); otherwise the **first**
     *         outcome that is not [ActionOutcome.succeeded]. Traversal stops there on
     *         purpose — continuing past a real failure would mask which level broke.
     */
    fun ensureCollections(path: String): ActionOutcome? {
        // `ROOT`, not `rootPath`: record paths live in the *relative* coordinate system
        // that [listRecursive] reports and [urlFor] consumes. `rootPath` is the path
        // component of the base URL, which is a different space entirely — passing it here
        // would make every relative path look like it lies outside the root, and nothing
        // would ever be created.
        for (collection in WebDavActions.collectionsToEnsure(path, ROOT)) {
            val outcome = mkcol(collection)
            if (!outcome.succeeded) return outcome
        }
        return null
    }

    /** [ensureCollections] followed by [put] — the ordered pair a round actually needs. */
    fun putCreatingCollections(
        path: String,
        bytes: ByteArray,
        precondition: PutPrecondition = PutPrecondition.None
    ): ActionOutcome {
        ensureCollections(path)?.let { return it }
        return put(path, bytes, precondition)
    }

    // ------------------------------------------------------------------ internals

    /**
     * A write verb that either reached the server or did not.
     *
     * The split exists so a transport failure keeps the exception's own words: collapsing
     * it into a sentinel status would leave the log saying "HTTP 0" and nothing else, which
     * is precisely the kind of entry that makes a sync bug take a day to find.
     */
    private sealed interface Call {
        data class Ok(val response: TransportResponse) : Call
        data class Broken(val detail: String) : Call
    }

    private fun call(
        method: String,
        path: String,
        headers: Map<String, String>,
        body: ByteArray?
    ): Call = try {
        Call.Ok(transport.execute(method, urlFor(path), headers, body))
    } catch (e: Exception) {
        Call.Broken(describe(e))
    }

    private fun describe(e: Exception): String = "${e.javaClass.simpleName}: ${e.message}"


    private sealed interface LevelResult {
        data class Ok(val children: List<RemoteEntry>) : LevelResult
        data class Bad(val reason: IncompleteReason, val detail: String) : LevelResult
    }

    /** One `Depth: 1` `PROPFIND`, with the self-entry removed and every failure surfaced. */
    private fun listOneLevel(path: String): LevelResult {
        val response = try {
            transport.propfind(urlFor(path), DEPTH_ONE, WebDavXml.propfindBody())
        } catch (e: Exception) {
            return LevelResult.Bad(
                IncompleteReason.Transport,
                "$path: ${e.javaClass.simpleName}: ${e.message}"
            )
        }

        if (response.status != MULTI_STATUS) {
            return LevelResult.Bad(IncompleteReason.HttpStatus, "$path: HTTP ${response.status}")
        }

        return when (val parsed = WebDavXml.parseMultiStatus(response.body)) {
            is MultiStatusResult.Malformed ->
                LevelResult.Bad(IncompleteReason.MalformedResponse, "$path: ${parsed.reason}")

            is MultiStatusResult.Parsed -> {
                if (parsed.failedResponses.isNotEmpty()) {
                    // A 207 can still contain per-resource failures. Trusting the rest
                    // would silently drop whatever that resource was.
                    return LevelResult.Bad(
                        IncompleteReason.FailedResponse,
                        "$path: ${parsed.failedResponses.joinToString("; ")}"
                    )
                }

                val children = mutableListOf<RemoteEntry>()
                for (entry in parsed.entries) {
                    val relative = relativize(entry.href)
                        ?: return LevelResult.Bad(
                            IncompleteReason.MalformedResponse,
                            "$path: href outside the collection: ${entry.href}"
                        )
                    // A Depth: 1 response echoes the collection itself. Dropping it is not
                    // cosmetic: reported as a file, the folder would become a phantom
                    // record whose absence next round reads as a deletion. The comparison
                    // is against the *requested* path, so it also holds one level down.
                    if (relative == path) continue
                    if (!WebDavPath.isUnder(path, relative)) {
                        // Depth: 1 must not reach sideways or upward. Refusing keeps a
                        // misbehaving server from steering the traversal into another
                        // subtree (or into a cycle).
                        return LevelResult.Bad(
                            IncompleteReason.MalformedResponse,
                            "$path: href outside the requested collection: ${entry.href}"
                        )
                    }
                    children += entry.copy(path = relative)
                }
                LevelResult.Ok(children)
            }
        }
    }

    /**
     * Maps an absolute `href` onto our coordinate system, or `null` when it points
     * outside this client's root. Refusing (rather than guessing or dropping) keeps the
     * failure on the safe side: the round is abandoned instead of being misread.
     */
    private fun relativize(href: String): String? {
        // RFC 4918 allows an href to be absolute, and some servers (SabreDAV among
        // them) do send `https://host/path/...` rather than a bare path. Reducing it
        // to its path first keeps those servers from looking like a protocol violation.
        val normalized = if (href.contains("://")) pathOf(href) else WebDavPath.normalize(href)
        return when {
            normalized == rootPath -> ROOT
            rootPath == ROOT -> normalized
            normalized.startsWith("$rootPath/") -> normalized.removePrefix(rootPath)
            else -> null
        }
    }

    private fun urlFor(path: String): String =
        base + if (path == ROOT) "/" else WebDavPath.encodePath(path)

    /**
     * Extracts the path from an absolute URL without `java.net.URI`, which rejects
     * plenty of URLs real servers are configured with (spaces in a hostname, unusual
     * schemes). Anything unparseable degrades to `/`, i.e. "the root is the base".
     */
    private fun pathOf(url: String): String {
        val withoutScheme = url.substringAfter("://", url)
        val slash = withoutScheme.indexOf('/')
        if (slash < 0) return ROOT
        val raw = withoutScheme.substring(slash)
        return WebDavPath.normalize(raw.substringBefore('?').substringBefore('#'))
    }

    companion object {
        private const val ROOT = "/"

        /** `207 Multi-Status` — the **only** status that can carry a listing. */
        const val MULTI_STATUS = 207

        /** Per-level depth **excluding** the collection itself, per RFC 4918. */
        const val DEPTH_ONE = 1

        /**
         * Bound on recursion. Deep enough for any plausible book layout, shallow enough
         * that a server which keeps inventing child collections cannot spin forever.
         */
        const val MAX_DEPTH = 8
    }
}
