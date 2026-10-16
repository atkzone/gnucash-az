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

import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.gnucash.android.net.TrustAllCertificatesManager
import java.security.SecureRandom
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager

/**
 * The OkHttp-backed [WebDavTransport] — the only part of the WebDAV stack that touches
 * the network, so it is the only part that is not unit-testable and the only part that
 * has to be kept boring.
 *
 * Replacing the ownCloud SDK (plan §5② step 1) means this class has to carry what the
 * SDK used to: Basic auth and the optional "trust a self-signed certificate" switch.
 * The trust logic itself is **reused**, not re-implemented — [TrustAllCertificatesManager]
 * is the same manager `net/TrustExt.kt` installs globally for the Apache client (plan §8,
 * "复用而非新写").
 *
 * @param username            Basic-auth user, or `null` for an anonymous server.
 * @param password            Basic-auth password.
 * @param trustAllCertificates accept self-signed TLS certificates. Off by default: the
 *                             plan's transport is HTTPS, and silently accepting any
 *                             certificate is exactly the kind of default that turns a
 *                             safe-looking sync into a trivially interceptable one.
 * @param client              injectable for callers that already own an OkHttp client
 *                            (or a test that wants an interceptor). Defaults to a
 *                            client with sane timeouts for a listing round-trip.
 */
class WebDavOkHttpTransport(
    username: String? = null,
    password: String? = null,
    trustAllCertificates: Boolean = false,
    private val client: OkHttpClient = defaultClient(trustAllCertificates)
) : WebDavTransport {

    private val authorization: String? =
        if (username.isNullOrEmpty()) null
        else Credentials.basic(username, password.orEmpty(), Charsets.UTF_8)

    override fun propfind(url: String, depth: Int, body: String): TransportResponse {
        val request = Request.Builder()
            .url(url)
            .method("PROPFIND", body.toRequestBody(XML_MEDIA_TYPE))
            .header("Depth", depth.toString())
            .apply { authorization?.let { header("Authorization", it) } }
            .build()

        client.newCall(request).execute().use { response ->
            // IOException is intentionally not caught: WebDavClient turns it into
            // IncompleteReason.Transport, which is the correct, safe outcome.
            return TransportResponse(response.code, response.body?.string().orEmpty())
        }
    }

    /**
     * The write verbs.
     *
     * The one detail worth spelling out: `body == null` is passed straight through to
     * OkHttp rather than being turned into a zero-length body. For `MKCOL` that is not
     * cosmetic — RFC 4918 §9.3 defines the request as having no body, and some servers
     * answer a bodied `MKCOL` with `415 Unsupported Media Type`. OkHttp permits a null body
     * for exactly the methods that must not have one (`MKCOL`/`DELETE`/`MOVE`) and rejects
     * it for the ones that must (`PUT`), so the rule is enforced on both sides.
     */
    override fun execute(
        method: String,
        url: String,
        headers: Map<String, String>,
        body: ByteArray?
    ): TransportResponse {
        val builder = Request.Builder()
            .url(url)
            .method(method, body?.toRequestBody(OCTET_STREAM_MEDIA_TYPE))
        for ((name, value) in headers) builder.header(name, value)
        authorization?.let { builder.header("Authorization", it) }

        client.newCall(builder.build()).execute().use { response ->
            return TransportResponse(
                status = response.code,
                body = response.body?.string().orEmpty(),
                etag = response.header("ETag")?.let(::stripQuotes)
            )
        }
    }

    /**
     * The read half of a record exchange.
     *
     * A failing response's body is **not** read. Error bodies can be arbitrarily large
     * (a proxy's HTML error page, a server-side stack trace) and are never used: the
     * classification depends on the status alone.
     */
    override fun download(url: String, headers: Map<String, String>): DownloadResponse {
        val builder = Request.Builder().url(url).get()
        for ((name, value) in headers) builder.header(name, value)
        authorization?.let { builder.header("Authorization", it) }

        client.newCall(builder.build()).execute().use { response ->
            val bytes = if (response.isSuccessful) response.body?.bytes() ?: ByteArray(0) else ByteArray(0)
            return DownloadResponse(
                status = response.code,
                bytes = bytes,
                etag = response.header("ETag")?.let(::stripQuotes)
            )
        }
    }

    companion object {
        private val XML_MEDIA_TYPE = "application/xml; charset=utf-8".toMediaType()

        /**
         * Book payloads are opaque to HTTP. `application/octet-stream` says so, and avoids
         * a server guessing a charset for what may well be a gzipped archive.
         */
        private val OCTET_STREAM_MEDIA_TYPE = "application/octet-stream".toMediaType()

        /**
         * Entity tags are compared by [WebDavXml] in their stripped form, so the transport
         * strips them too — one representation of an etag in the whole stack instead of two.
         */
        private fun stripQuotes(raw: String): String =
            raw.trim().removePrefix("W/").trim().removeSurrounding("\"")

        private fun defaultClient(trustAllCertificates: Boolean): OkHttpClient {
            val builder = OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                // Longer than the listing timeouts: an upload or download moves a whole
                // book, and a write deadline tuned for a small XML request would abort a
                // legitimate transfer half way with an IOException.
                .readTimeout(60, TimeUnit.SECONDS)
                .writeTimeout(60, TimeUnit.SECONDS)

            if (trustAllCertificates) {
                val trustManager = TrustAllCertificatesManager()
                val sslContext = SSLContext.getInstance("TLS")
                sslContext.init(null, arrayOf<TrustManager>(trustManager), SecureRandom())
                builder.sslSocketFactory(sslContext.socketFactory, trustManager)
            }

            return builder.build()
        }
    }
}
