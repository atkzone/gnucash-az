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
package org.gnucash.android.util

import java.nio.charset.StandardCharsets

/**
 * What a byte stream *is*, when something claims it is a book.
 *
 * ### Why a guard and not just "hand it to the importer"
 *
 * The importers already fail on garbage (`GncXmlImporter` throws on a non-XML stream,
 * `SqliteImporter` on a non-database one). That failure is loud but **too late**: the
 * remote restore path writes the downloaded payload over the local book *first* and only
 * then hands it to the importer. So "the server answered `200` with a login page" does
 * not produce an error — it produces a book that is now an HTML file, discovered days
 * later. This type is the check that runs **before** anything is overwritten.
 *
 * The shapes it positively refuses are the ones a real network actually produces:
 *
 *  - an **HTML page** — a captive portal, a reverse proxy in front of the WebDAV share,
 *    an SSO login form, or simply a mis-typed folder that points at a web root. All of
 *    these answer `200` with a perfectly valid web page;
 *  - **zero bytes** — some servers answer an empty `200` for a collection rather than a
 *    `404`;
 *  - anything else that is not one of the containers the importer understands.
 *
 * ### The magics are owned here
 *
 * [classify] is the single place that decides "sqlite / gzip / zip / xml", and
 * [org.gnucash.android.importer.ImporterFactory] delegates to it, so the stream the
 * importer opens and the payload a restore accepts can never disagree about what a book
 * looks like. Two copies of the magic numbers would drift the first time one of them
 * gained a new container.
 *
 * The whole file is free of Android dependencies, so it is unit testable —
 * see `BookPayloadTest`.
 */
object BookPayload {

    /** The containers a book can arrive in. */
    enum class Kind {
        /** Gzipped GnuCash XML — what this app writes as `.gnca`. */
        GZIP,

        /** A zip archive (`.gnca.zip` / `.gnucash.zip`). */
        ZIP,

        /** Plain GnuCash XML (`.gnucash`). */
        XML,

        /** A desktop GnuCash SQLite book, which `SqliteImporter` can read. */
        SQLITE,

        /** None of the above. Not proof of anything on its own — see [inspect]. */
        UNKNOWN
    }

    /** Why a payload was refused. */
    enum class Rejection {
        /** The remote answered with no bytes at all. */
        EMPTY,

        /** The payload is a web page, not a file. */
        WEB_PAGE,

        /** The payload is not one of the containers a book can arrive in. */
        UNRECOGNISED
    }

    /** The outcome of [inspect]. */
    sealed interface Verdict {
        /** Safe to overwrite a local book with. */
        data class Acceptable(val kind: Kind) : Verdict

        /** Must not be written anywhere near a book. */
        data class Rejected(val reason: Rejection, val detail: String) : Verdict
    }

    // -------------------------------------------------------------------- magics

    /** `PK\x03\x04` — a zip local file header. */
    const val ZIP_MAGIC: Int = 0x504B0304

    /** `PK\x05\x06` — an empty zip archive. */
    const val ZIP_MAGIC_EMPTY: Int = 0x504B0506

    /** `PK\x07\x08` — a zip split across volumes. */
    const val ZIP_MAGIC_SPANNED: Int = 0x504B0708

    private val GZIP_HEADER: ByteArray = byteArrayOf(0x1F, 0x8B.toByte())

    private val SQLITE_HEADER: ByteArray = "SQLite format 3\u0000".toByteArray(StandardCharsets.US_ASCII)

    /**
     * How much of the head is scanned for an HTML marker.
     *
     * A window rather than a prefix because real login pages routinely start with a
     * comment, a `<!DOCTYPE …>` variant or a `<meta>` block before `<html>`; and any
     * payload whose first kilobyte contains `<html` is a web page, whatever precedes it.
     */
    private const val SNIFF_WINDOW = 1024

    // ------------------------------------------------------------------ classify

    /**
     * The container [prefix] starts with, decided from its leading bytes alone.
     *
     * @param prefix the head of the stream; anything from 4 bytes up is enough to
     *               recognise every container except SQLite, which needs its 16.
     */
    fun classify(prefix: ByteArray): Kind {
        if (prefix.hasPrefix(SQLITE_HEADER)) return Kind.SQLITE
        if (prefix.hasPrefix(GZIP_HEADER)) return Kind.GZIP
        if (prefix.size >= 4) {
            // Big-endian: the four bytes read as the `PK\x03\x04` the constants above are
            // written as. The comparison this replaces (in `ImporterFactory`) assembled them
            // little-endian while keeping the same big-endian constants, so it could never
            // match a real zip — the branch was dead, and every zipped backup fell through to
            // the XML importer and failed there. Centralising the sniff is what surfaced it.
            val signature = ((prefix[0].toInt() and 0xFF) shl 24) or
                ((prefix[1].toInt() and 0xFF) shl 16) or
                ((prefix[2].toInt() and 0xFF) shl 8) or
                (prefix[3].toInt() and 0xFF)
            if (signature == ZIP_MAGIC || signature == ZIP_MAGIC_EMPTY || signature == ZIP_MAGIC_SPANNED) {
                return Kind.ZIP
            }
        }
        if (looksLikeXml(prefix)) return Kind.XML
        return Kind.UNKNOWN
    }

    // ------------------------------------------------------------------- inspect

    /**
     * Decides whether [bytes] may be handed to the importer as a book.
     *
     * The binary containers are recognised first, so a gzipped book that happens to
     * contain the characters `<html` somewhere inside its compressed payload is still
     * accepted — the web-page test only ever runs on a payload no container matched.
     */
    fun inspect(bytes: ByteArray): Verdict {
        if (bytes.isEmpty()) {
            return Verdict.Rejected(Rejection.EMPTY, "the remote returned no bytes at all")
        }
        return when (val kind = classify(bytes)) {
            Kind.GZIP, Kind.ZIP, Kind.XML, Kind.SQLITE -> Verdict.Acceptable(kind)
            Kind.UNKNOWN -> if (looksLikeWebPage(bytes)) {
                Verdict.Rejected(
                    Rejection.WEB_PAGE,
                    "the remote returned a web page, not a book — the address may point at a " +
                        "login page or the wrong folder rather than at the WebDAV share"
                )
            } else {
                Verdict.Rejected(
                    Rejection.UNRECOGNISED,
                    "the payload is not a GnuCash book (no gzip, zip, sqlite or XML signature)"
                )
            }
        }
    }

    // ------------------------------------------------------------------ internals

    private fun looksLikeWebPage(bytes: ByteArray): Boolean {
        val window = bytes.copyOf(minOf(bytes.size, SNIFF_WINDOW))
        // One byte maps to one char in ISO-8859-1, so this is a faithful lowercase of the
        // raw head and never throws on a malformed UTF-8 sequence.
        val head = String(window, StandardCharsets.ISO_8859_1).lowercase()
        return head.contains("<!doctype html") || head.contains("<html")
    }

    /** Skips a UTF-8 BOM and leading ASCII whitespace, then looks for an XML root. */
    private fun looksLikeXml(prefix: ByteArray): Boolean {
        var i = 0
        if (prefix.size >= 3 &&
            prefix[0] == 0xEF.toByte() && prefix[1] == 0xBB.toByte() && prefix[2] == 0xBF.toByte()
        ) {
            i = 3
        }
        while (i < prefix.size && isAsciiSpace(prefix[i])) i++
        return startsWithIgnoringCase(prefix, i, "<?xml") ||
            startsWithIgnoringCase(prefix, i, "<gnucash") ||
            startsWithIgnoringCase(prefix, i, "<book")
    }

    private fun isAsciiSpace(byte: Byte): Boolean =
        byte == ' '.code.toByte() || byte == '\t'.code.toByte() ||
            byte == '\n'.code.toByte() || byte == '\r'.code.toByte()

    private fun ByteArray.hasPrefix(prefix: ByteArray): Boolean {
        if (size < prefix.size) return false
        for (i in prefix.indices) if (this[i] != prefix[i]) return false
        return true
    }

    /** [literal] must be lower case; the comparison folds the haystack, never the needle. */
    private fun startsWithIgnoringCase(bytes: ByteArray, offset: Int, literal: String): Boolean {
        if (offset + literal.length > bytes.size) return false
        for (j in literal.indices) {
            var b = bytes[offset + j].toInt() and 0xFF
            if (b in 'A'.code..'Z'.code) b += 32
            if (b != literal[j].code) return false
        }
        return true
    }
}
