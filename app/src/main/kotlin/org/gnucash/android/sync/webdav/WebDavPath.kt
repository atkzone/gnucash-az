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

import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets

/**
 * WebDAV path arithmetic — the part that silently breaks interoperability.
 *
 * Two mistakes are cheap to make and expensive to notice:
 *
 *  1. Encoding the **whole** path instead of each **segment** turns the separators
 *     into `%2F`, and the server then looks for one file literally called
 *     `a/b/c.xml`.
 *  2. Decoding a `href` **twice** turns a legitimate `%2520` (a file whose name
 *     really contains `%20`) into a space, so the client asks for a different
 *     resource than the server advertised — a one-character change that shows up
 *     as "the other device's edits vanish".
 *
 * Everything here is a pure function of its argument; no `java.io.File`, no
 * `android.net.Uri`, no server assumptions. Escaping follows RFC 3986 `pchar`.
 */
object WebDavPath {

    /** `pchar` characters that need no escaping inside a path segment (RFC 3986 §3.3). */
    private const val SEGMENT_SAFE =
        "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789" +
            "-._~!\$&'()*+,;=:@"

    private fun isHexDigit(c: Char): Boolean =
        c in '0'..'9' || c in 'a'..'f' || c in 'A'..'F'

    private fun hexValue(c: Char): Int = when (c) {
        in '0'..'9' -> c - '0'
        in 'a'..'f' -> c - 'a' + 10
        else -> c - 'A' + 10
    }

    /**
     * Percent-encodes a single path segment (UTF-8 bytes, uppercase hex).
     *
     * `"我的 账本"` → `"%E6%88%91%E7%9A%84%20%E8%B4%A6%E6%9C%AC"`. A `/` inside the
     * argument is encoded too — callers split on `/` *before* calling here.
     */
    fun encodeSegment(segment: String): String {
        val out = StringBuilder(segment.length)
        for (byte in segment.toByteArray(StandardCharsets.UTF_8)) {
            val c = (byte.toInt() and 0xFF).toChar()
            if (c in SEGMENT_SAFE) {
                out.append(c)
            } else {
                out.append('%')
                out.append("0123456789ABCDEF"[(byte.toInt() and 0xFF) shr 4])
                out.append("0123456789ABCDEF"[byte.toInt() and 0x0F])
            }
        }
        return out.toString()
    }

    /** Encodes a path **segment by segment**, preserving the `/` separators. */
    fun encodePath(path: String): String =
        path.split('/').joinToString("/") { encodeSegment(it) }

    /**
     * Percent-decodes **once**. A malformed escape (`%ZZ`) and a dangling `%` are
     * left verbatim rather than throwing — a listing that contains one odd name
     * must not fail wholesale, or a single exotic filename would look like a
     * deleted record.
     */
    fun decode(s: String): String {
        if (!s.contains('%')) return s
        val bytes = ByteArrayOutputStream(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '%' && i + 2 < s.length && isHexDigit(s[i + 1]) && isHexDigit(s[i + 2])) {
                bytes.write((hexValue(s[i + 1]) shl 4) or hexValue(s[i + 2]))
                i += 3
            } else {
                // Not an escape: emit the character's own UTF-8 bytes so mixed
                // encoded/raw input still decodes to the right string.
                bytes.write(c.toString().toByteArray(StandardCharsets.UTF_8))
                i += 1
            }
        }
        return String(bytes.toByteArray(), StandardCharsets.UTF_8)
    }

    /**
     * Canonical form used for **identity**: exactly one leading `/`, no trailing
     * slash (except the root), no doubled separators. Decoding happens first, so
     * `"/book%20a/"` and `"/book a"` are the same key.
     *
     * Identity matters because it is the join between "what the server listed" and
     * "what we know about locally" — if those two spellings drift, every record
     * looks absent and the next round proposes deleting all of them.
     */
    fun normalize(path: String): String {
        val decoded = decode(path).replace('\\', '/')
        val segments = decoded.split('/').filter { it.isNotEmpty() }
        if (segments.isEmpty()) return "/"
        return "/" + segments.joinToString("/")
    }

    /** Joins a base path and a (possibly multi-segment) child, normalised. */
    fun join(base: String, child: String): String {
        val b = normalize(base)
        val c = normalize(child)
        if (c == "/") return b
        return if (b == "/") c else "$b$c"
    }

    /** The last path segment — the record key as seen on the wire. */
    fun name(path: String): String =
        normalize(path).substringAfterLast('/')

    /**
     * `true` when [child] is [parent] or lies underneath it. Used to drop the
     * `Depth: 1` self-entry and to bound recursion.
     */
    fun isUnder(parent: String, child: String): Boolean {
        val p = normalize(parent)
        val c = normalize(child)
        return c == p || p == "/" || c.startsWith("$p/")
    }
}
