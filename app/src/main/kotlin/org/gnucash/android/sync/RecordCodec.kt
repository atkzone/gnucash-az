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
 * The canonical text encoding of one record's fields — the format a record is
 * stored and transmitted in (feature ② v2).
 *
 * ### Why not JSON
 *
 * A field map here has a handful of known keys and free-form values that
 * routinely contain newlines, `=` signs, `%` and CJK text (a transaction's
 * `notes` and `description`). Line-oriented `key=value` is what makes the
 * payload **diffable and inspectable** — the whole point of the v2 file-per-
 * record layout is that a human can look at what is on the server.
 *
 * ### The one thing that has to be right
 *
 * The encoding must be **lossless and deterministic**, because the base snapshot
 * is compared for equality against a freshly decoded record. If `encode` mangled
 * a value (say, dropped the difference between `a\nb` and `a b`), then a record
 * that has not actually changed would decode differently from its base, and the
 * merge would report a conflict — or worse, a *deletion* — every single round.
 * So:
 *
 *  * keys are sorted, so the same map always produces byte-identical text;
 *  * `%`, `\n`, `\r` and `=` are percent-escaped in **both** key and value, so
 *    the separator `=` and the line separator are unambiguous;
 *  * decoding is the exact inverse; an unknown escape is left verbatim rather
 *    than guessed at.
 *
 * ### Strictness on decode
 *
 * [decode] **throws** on a malformed line (one with no separator). "A few fields
 * went missing" is not a smaller version of the record — it is a different record
 * that would be adopted as if the peer had edited it. [decodeOrNull] is the
 * forgiving front door the round uses, so a single unreadable remote file becomes
 * "leave that one alone" rather than "delete it" or "silently adopt half of it".
 */
object RecordCodec {

    private const val SEPARATOR = '='
    private const val ESCAPE = '%'

    /**
     * Encodes [fields] to canonical text: one `key=value` line per field, keys
     * sorted, `\n`-separated, no trailing newline. An empty map encodes to `""`.
     */
    fun encode(fields: Map<String, String>): String =
        fields.entries
            .sortedBy { it.key }
            .joinToString("\n") { "${escape(it.key)}$SEPARATOR${escape(it.value)}" }

    /** UTF-8 bytes of [encode] — what gets `PUT` to the server. */
    fun encodeBytes(fields: Map<String, String>): ByteArray =
        encode(fields).toByteArray(Charsets.UTF_8)

    /**
     * Decodes canonical text back to a field map.
     *
     * @throws IllegalArgumentException when a line has no `=` separator, i.e. the
     *         payload is not something this codec produced. Callers that must not
     *         fail the whole round should use [decodeOrNull].
     */
    fun decode(text: String): Map<String, String> {
        if (text.isEmpty()) return emptyMap()
        val fields = LinkedHashMap<String, String>()
        for (line in text.split('\n')) {
            if (line.isEmpty()) continue
            val separator = line.indexOf(SEPARATOR)
            require(separator >= 0) { "malformed payload: a line has no '$SEPARATOR' separator" }
            // Last writer wins on a duplicate key, deterministically (sorted input
            // means duplicates can only arise from a hand-edited file).
            fields[unescape(line.substring(0, separator))] = unescape(line.substring(separator + 1))
        }
        return fields
    }

    /** UTF-8 bytes to a field map; see [decode]. */
    fun decodeBytes(bytes: ByteArray): Map<String, String> =
        decode(String(bytes, Charsets.UTF_8))

    /** [decode] for untrusted input: `null` instead of throwing. */
    fun decodeOrNull(text: String): Map<String, String>? =
        try {
            decode(text)
        } catch (_: IllegalArgumentException) {
            null
        }

    /** [decodeBytes] for untrusted input: `null` instead of throwing. */
    fun decodeBytesOrNull(bytes: ByteArray): Map<String, String>? =
        decodeOrNull(String(bytes, Charsets.UTF_8))

    private fun escape(s: String): String {
        if (s.none { it == ESCAPE || it == '\n' || it == '\r' || it == SEPARATOR }) return s
        val out = StringBuilder(s.length + 8)
        for (c in s) {
            when (c) {
                ESCAPE -> out.append("%25")
                '\n' -> out.append("%0A")
                '\r' -> out.append("%0D")
                SEPARATOR -> out.append("%3D")
                else -> out.append(c)
            }
        }
        return out.toString()
    }

    private fun unescape(s: String): String {
        if (!s.contains(ESCAPE)) return s
        val out = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == ESCAPE && i + 2 < s.length) {
                when (val hex = s.substring(i + 1, i + 3).uppercase()) {
                    "25" -> { out.append(ESCAPE); i += 3 }
                    "0A" -> { out.append('\n'); i += 3 }
                    "0D" -> { out.append('\r'); i += 3 }
                    "3D" -> { out.append(SEPARATOR); i += 3 }
                    // Not one of ours (or a truncated escape): keep the '%' verbatim
                    // and move on by one, so `%20` survives as the literal `%20`.
                    else -> { out.append(c); i += 1 }
                }
            } else {
                out.append(c)
                i += 1
            }
        }
        return out.toString()
    }
}
