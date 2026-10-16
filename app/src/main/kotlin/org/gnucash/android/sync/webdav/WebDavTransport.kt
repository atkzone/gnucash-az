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
 * A raw HTTP reply reduced to what the **listing** path needs.
 *
 * @param status the HTTP status code.
 * @param body   the response body as text. Only ever read for `207` listings; for a write
 *               verb the body is discarded, which is why [etag] is carried separately
 *               rather than parsed out of it.
 * @param etag   the `ETag` response header with quotes stripped, or `null`. A `PUT`
 *               answers with the entity tag of the representation it just stored, and
 *               keeping it is what lets the caller record a base that will still match on
 *               the next round — see [ActionOutcome.Done.etag].
 */
data class TransportResponse(
    val status: Int,
    val body: String,
    val etag: String? = null
)

/**
 * A `GET` reply.
 *
 * Separate from [TransportResponse] because a book is **bytes**, not text: decoding the
 * payload as UTF-8 to fit the other type would corrupt it silently, and the corruption
 * would only surface later as "the other device's book cannot be opened".
 */
data class DownloadResponse(
    val status: Int,
    val bytes: ByteArray,
    val etag: String? = null
)

/**
 * The one thing a WebDAV client needs from the platform.
 *
 * Keeping this a small, method-per-verb interface (rather than calling OkHttp directly)
 * is what makes the traversal *and* the write policy below testable without a server:
 * the interesting cases are *how the client reacts to a lying or half-broken server*, and
 * those are cheap to stage as a fake transport but almost impossible to stage for real.
 *
 * Implementations must not swallow `IOException` — let it propagate; the client maps it
 * to a failed outcome carrying the exception's own words.
 */
interface WebDavTransport {

    /**
     * Issues a `PROPFIND`.
     *
     * @param url   absolute, already percent-encoded.
     * @param depth `0` or `1`. The client deliberately never asks for `infinity`.
     * @param body  an XML body from [WebDavXml.propfindBody].
     */
    fun propfind(url: String, depth: Int, body: String): TransportResponse

    /**
     * Issues a write verb (`MKCOL`, `PUT`, `DELETE`, `MOVE`).
     *
     * @param url     absolute, already percent-encoded.
     * @param headers request headers, already built by [WebDavActions] — including the
     *                preconditions (`If-Match` / `If-None-Match`) and `Destination`. The
     *                transport does not synthesise or filter any of them, so what the
     *                policy layer decided is exactly what goes on the wire.
     * @param body    the payload, or `null` for verbs that must carry none. `MKCOL` with a
     *                body is a protocol violation (some servers answer `415`), which is why
     *                "no body" is expressible at all rather than being an empty array.
     */
    fun execute(method: String, url: String, headers: Map<String, String>, body: ByteArray?): TransportResponse

    /**
     * Issues a `GET`.
     *
     * @param headers preconditions only; an implementation must not add a `Range` header,
     *                because a `206 Partial Content` would be stored as a whole book.
     */
    fun download(url: String, headers: Map<String, String>): DownloadResponse
}
