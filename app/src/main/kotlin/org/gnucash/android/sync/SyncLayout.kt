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
package org.gnucash.android.sync

/**
 * A record's stable identity across devices (feature ② v2).
 *
 * The pair `(recordType, key)` is the join between four independent views of the
 * same record: what is on this device, what the peer listed, what the last
 * successful sync recorded as the base, and what is in the tombstone table. If
 * any of those four spell an identity differently, every record looks absent and
 * the next round proposes deleting all of them (plan §7.3, constraint 2) — which
 * is why the identity is a single small type rather than "a string key" plus a
 * type parameter passed around separately.
 *
 * @param key the GnuCash GUID — the only identifier stable across devices and
 *            across an export/import round trip.
 */
data class SyncIdentity(val recordType: SyncRecordType, val key: String) {

    override fun toString(): String = token

    /**
     * The wire/persistence token, `"<recordTypeId>:<key>"`.
     *
     * [ThreeWayMerge.planDeletions] operates on plain strings (it must stay a
     * pure string-keyed function so it can be reasoned about in isolation), so
     * the planner feeds it tokens rather than bare keys. Tokens keep the record
     * type in the key, which is what stops a transaction and an account that
     * happen to share a GUID from being collapsed into one entry.
     */
    val token: String get() = "${recordType.id}:$key"

    companion object {
        /** Parses a [token], or `null` when it is not one (foreign or corrupt input). */
        fun fromToken(token: String): SyncIdentity? {
            val separator = token.indexOf(':')
            if (separator <= 0 || separator == token.length - 1) return null
            val type = SyncRecordType.fromId(token.substring(0, separator)) ?: return null
            return SyncIdentity(type, token.substring(separator + 1))
        }
    }
}

/**
 * The on-the-wire **layout** of a synced record: how a record's identity maps
 * onto a remote path, and back.
 *
 * ### Why one file per record, in a type-named folder
 *
 * The merge is per record and keyed by GUID, so the remote side has to be
 * addressable per record too. A single archive file would force a full
 * download-and-repack on every round, and — worse — would make "the listing
 * came back small" indistinguishable from "records were deleted". One file per
 * record means the listing *is* the record set, and a `DELETE` removes exactly
 * one record.
 *
 * ### Why the parsing is strict
 *
 * [parse] returns `null` for anything that is not exactly `/<type>/<key>.gncs`.
 * It is fed straight from a `PROPFIND` body, i.e. from a server we do not
 * control, so "close enough" is not good enough: a path that half-parses into a
 * *different* identity than the one that was written would read as one phantom
 * record plus one deletion. Refusing means the file is simply not ours and is
 * ignored, which is the only answer that cannot move data.
 *
 * The path is deliberately **flat** (one collection per record type, records
 * directly inside it). A deep layout buys nothing here — records are addressed
 * by GUID, not by name — and every extra level is another place a server can
 * disagree about.
 */
object SyncLayout {

    /** Extension for a synced record file. Chosen to be recognisable and inert. */
    const val EXTENSION: String = "gncs"

    private const val SUFFIX = ".$EXTENSION"

    /** The remote path for [identity], e.g. `/transaction/8f2a….gncs`. */
    fun pathFor(identity: SyncIdentity): String = pathFor(identity.recordType, identity.key)

    /**
     * The remote path for one record.
     *
     * @throws IllegalArgumentException when [key] cannot be a path segment. The key
     *         comes from our own database (a GUID) rather than from the network, so
     *         an invalid one is a programming error, not hostile input — throwing
     *         keeps it from silently producing a path that `parse` then refuses,
     *         which would show up as "the record we just wrote is not there".
     */
    fun pathFor(type: SyncRecordType, key: String): String {
        require(key.isNotBlank()) { "a record key must not be blank" }
        require('/' !in key) { "a record key must not contain a path separator: $key" }
        require(!key.startsWith(".")) { "a record key must not start with a dot: $key" }
        return "/${type.id}/$key$SUFFIX"
    }

    /**
     * Parses a remote path back into an identity, or `null` when it is not one of
     * ours. Tolerates an absolute URL, a query string and a trailing slash by
     * normalising first, but is otherwise strict — see the class doc.
     */
    fun parse(path: String): SyncIdentity? {
        val cleaned = path
            .replace('\\', '/')
            .substringBefore('?')
            .substringBefore('#')
        // Reduce an absolute URL to its path first. The client already relativises hrefs
        // before they reach here, so this is belt-and-braces — but it costs nothing and
        // keeps a stray absolute spelling from reading as "not ours" (i.e. as a deletion).
        val pathOnly = if (cleaned.contains("://")) {
            val afterScheme = cleaned.substringAfter("://")
            val slash = afterScheme.indexOf('/')
            if (slash < 0) "" else afterScheme.substring(slash)
        } else {
            cleaned
        }
        val segments = pathOnly.split('/').filter { it.isNotEmpty() }
        if (segments.size != 2) return null                      // only /<type>/<key>.gncs
        val type = SyncRecordType.fromId(segments[0]) ?: return null
        val name = segments[1]
        if (!name.endsWith(SUFFIX)) return null
        val key = name.removeSuffix(SUFFIX)
        if (key.isBlank() || key.startsWith(".")) return null
        return SyncIdentity(type, key)
    }
}
