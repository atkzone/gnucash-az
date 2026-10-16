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
 * One resource the server reported in a `PROPFIND` response.
 *
 * @param path         identity, already decoded and normalised (see [WebDavPath.normalize]).
 * @param href         the raw `href` as it came off the wire, kept for diagnostics and for
 *                     issuing follow-up requests without re-encoding.
 * @param isCollection `true` for a folder (`<resourcetype><collection/></resourcetype>`).
 * @param etag         entity tag with surrounding quotes/whitespace stripped, `null` if the
 *                     server sent none. Not every server emits one, so a missing etag must
 *                     never be read as "unchanged".
 * @param contentLength byte length if the server reported it.
 * @param lastModified raw `getlastmodified` string (RFC 1123), kept unparsed: it is only ever
 *                     used for display, and parsing timezone-less variants is a whole
 *                     sub-project the client can avoid entirely.
 */
data class RemoteEntry(
    val path: String,
    val href: String,
    val isCollection: Boolean,
    val etag: String? = null,
    val contentLength: Long? = null,
    val lastModified: String? = null
)

/**
 * Why a listing could not be trusted. Every value here means the same thing to the
 * caller: **do not infer anything about absence** (plan §7.3, constraint 2).
 */
enum class IncompleteReason {
    /** The transport itself failed: DNS, TLS, timeout, socket reset. */
    Transport,

    /** The server answered, but not with `207 Multi-Status`. */
    HttpStatus,

    /** The body could not be parsed as a `multistatus` document. */
    MalformedResponse,

    /** The document parsed, but at least one `<response>` carried a non-2xx status. */
    FailedResponse,

    /** Recursion went deeper than the configured bound (protects against cycles). */
    DepthExceeded
}

/**
 * The result of listing a remote subtree.
 *
 * The two branches are not a convenience — they are the safety boundary. A remote
 * listing is the **only** evidence a sync engine has for "this record no longer
 * exists over there", so a listing that is merely *partial* must be unable to answer
 * that question at all. That is why there is no `Complete(emptyList())` on the
 * failure path: a truncated listing never degrades into "nothing is there".
 */
sealed interface ListingResult {

    /**
     * Every **file** under the requested root was enumerated. Folders are traversed
     * but not reported (a folder is not a syncable record, and reporting one would
     * make its absence next round look like a deletion).
     *
     * @param files      the enumerated files, deduplicated by [RemoteEntry.path].
     * @param foldersVisited how many collections were traversed — diagnostics only.
     */
    data class Complete(
        val files: List<RemoteEntry>,
        val foldersVisited: Int
    ) : ListingResult

    /** The listing did not run to completion; [reason] says why, [detail] locates it. */
    data class Incomplete(
        val reason: IncompleteReason,
        val detail: String
    ) : ListingResult
}

/**
 * `true` **only** for a listing that ran to completion.
 *
 * This is the single flag a caller may feed to `ThreeWayMerge.planDeletions(...)`.
 * Wiring it any other way (e.g. `files.isEmpty()` ⇒ "everything was deleted")
 * re-introduces the exact failure the plan calls out as risk R-1: one truncated
 * response silently deleting the whole book on both devices.
 */
val ListingResult.confirmsAbsence: Boolean
    get() = this is ListingResult.Complete
