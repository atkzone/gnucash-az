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
 * What a user typed into the sync settings page, reduced to what the WebDAV stack needs
 * and **checked** before a single request is made.
 *
 * ### Why a validator and not just "pass the string through"
 *
 * Every field here can be wrong in a way that produces *no error at all*, only wrong
 * results — which is exactly the class of bug this project keeps paying for:
 *
 *  - a URL with **embedded credentials** (`https://alice:secret@host/dav`) parses fine,
 *    authenticates nothing, and answers `401` forever while the user swears the password
 *    is right. Worse, naively keeping it would put the password into every request URL and
 *    into every log line that records one;
 *  - a URL **without a scheme** (`cloud.example.com/dav`) is the single most common paste,
 *    and a naive parser reads it as a host named `cloud.example.com/dav`;
 *  - **plain `http://`** puts the password on the wire in the clear;
 *  - a **trailing/doubled slash** or a `.`/`..` segment shifts the root, and since the sync
 *    root is the coordinate system every record path is relative to, a root that is one
 *    level off makes *every* remote record look absent — which the merge layer reads as
 *    "the peer deleted everything";
 *  - a **literal space** in the path is not an error anywhere: OkHttp quietly encodes it to
 *    `%20` on the wire, while the client compares server-supplied `href`s against the
 *    *unencoded* root, so nothing ever matches and the sync silently does nothing;
 *  - a **password with no user name** is silently dropped: the transport only builds an
 *    `Authorization` header when a user name is present, so the requests go out anonymous.
 *
 * The result is a typed [Problem] rather than a stringly-typed failure, because the user's
 * next move differs per problem (fix the URL / switch to https / fill in a user name), and
 * the settings page needs to say which one it is.
 *
 * Free of Android dependencies: [Check.Ready.connect] takes the transport as a factory, so
 * the OkHttp implementation stays in the Android layer and this file stays unit testable
 * (see `WebDavConnectionTest`).
 */
data class WebDavConnection(
    /** The WebDAV base URL, **including** the remote folder backups live under. */
    val serverUrl: String,
    val username: String? = null,
    val password: CharArray? = null,
    /** Accept a self-signed TLS certificate. Off by default; see [WebDavOkHttpTransport]. */
    val trustAllCertificates: Boolean = false,
    /** Permit `http://`. Off by default: plain HTTP sends the user name and password in the clear. */
    val allowPlaintext: Boolean = false
) {

    /**
     * Checks the fields and normalises the URL.
     *
     * Never throws: a refused connection is as ordinary an answer as an accepted one, and
     * a settings page has to render it either way.
     */
    fun check(): Check {
        val raw = serverUrl.trim()
        if (raw.isEmpty()) {
            return invalid(Problem.BLANK, "the server address is empty")
        }

        val schemeEnd = raw.indexOf("://")
        if (schemeEnd < 0) {
            return invalid(
                Problem.MISSING_SCHEME,
                "the address needs an http:// or https:// prefix: '$raw'"
            )
        }
        val scheme = raw.substring(0, schemeEnd).lowercase()
        if (scheme != "http" && scheme != "https") {
            return invalid(
                Problem.UNSUPPORTED_SCHEME,
                "'$scheme://' is not a WebDAV scheme; use http:// or https://"
            )
        }
        if (scheme == "http" && !allowPlaintext) {
            return invalid(
                Problem.PLAINTEXT,
                "http:// sends the address and any password in the clear; use https:// " +
                    "(or turn on plain HTTP knowingly)"
            )
        }
        // A space (or any control character) inside the address is the quietest of the lot:
        // every layer tolerates it and one of them encodes it, so the root the client asks
        // for and the root it compares against stop agreeing. Say so instead.
        val stray = raw.firstOrNull { it.isWhitespace() || it.isISOControl() }
        if (stray != null) {
            return invalid(
                Problem.UNESCAPED_CHARACTER,
                "the address contains a literal space or control character at position " +
                    "${raw.indexOf(stray)}; encode it instead of typing it (a space is %20)"
            )
        }

        // Authority = everything before the first '/', '?' or '#'; the rest is path/
        // query/fragment. Splitting here (rather than at the first '/') means a URL with no
        // path at all is still separated from its query string.
        val rest = raw.substring(schemeEnd + 3)
        val authorityEnd = rest.indexOfFirst { it == '/' || it == '?' || it == '#' }
        val authority = if (authorityEnd < 0) rest else rest.substring(0, authorityEnd)
        val tail = if (authorityEnd < 0) "" else rest.substring(authorityEnd)

        if (authority.isEmpty()) {
            return invalid(Problem.MISSING_HOST, "the address has no host: '$raw'")
        }
        if ('@' in authority) {
            return invalid(
                Problem.CREDENTIALS_IN_URL,
                "the address embeds a user name or password; put them in the user name and " +
                    "password fields instead, so they are not written into every request URL"
            )
        }
        // `host:port` — a bare `:8080` has an empty host and would produce requests to
        // `https://:8080/...`, which every transport rejects only once it is too late to
        // explain which field is wrong.
        if (authority.substringBefore(':').isEmpty()) {
            return invalid(Problem.MISSING_HOST, "the address has no host: '$raw'")
        }
        if ('?' in tail || '#' in tail) {
            return invalid(
                Problem.QUERY_OR_FRAGMENT,
                "the address must not carry a query string or fragment: '$tail'"
            )
        }

        val segments = tail.split('/').filter { it.isNotEmpty() }
        for (segment in segments) {
            if (segment == "." || segment == "..") {
                return invalid(
                    Problem.RELATIVE_SEGMENT,
                    "'$segment' in the remote path is not a real folder name and would move " +
                        "the sync root"
                )
            }
            // A percent-encoded separator is the trap `WebDavPath` documents: it is a
            // literal `%2F` on the wire but a `/` after one harmless-looking decode, so the
            // root the client requests and the root it compares hrefs against would differ.
            if (segment.contains("%2F", ignoreCase = true)) {
                return invalid(
                    Problem.ENCODED_SEPARATOR,
                    "the remote path contains an encoded '/', which does not name a folder: '$segment'"
                )
            }
        }

        // Normalise: lower-case the scheme (done) and the authority (hosts are
        // case-insensitive, ports are digits), keep the path's own case, drop empty
        // segments so `/a//b/` and `/a/b` are the same root.
        val path = if (segments.isEmpty()) "" else "/" + segments.joinToString("/")
        val baseUrl = "$scheme://${authority.lowercase()}$path"

        // A user name is trimmed — a copy-pasted " alice " is a typo, not a name. A
        // password is *never* trimmed: leading and trailing spaces can be part of it, and
        // silently editing a secret is how "the same password works on my phone" happens.
        val user = username?.trim()?.takeIf { it.isNotEmpty() }
        val secret = password?.takeIf { it.isNotEmpty() }
        if (user == null && secret != null) {
            return invalid(
                Problem.PASSWORD_WITHOUT_USER,
                "a password was given without a user name, so no credentials would be sent at all"
            )
        }

        return Check.Ready(
            baseUrl = baseUrl,
            username = user,
            password = secret,
            trustAllCertificates = trustAllCertificates
        )
    }

    private fun invalid(problem: Problem, detail: String) = Check.Invalid(problem, detail)

    /** The result of [check]. */
    sealed interface Check {

        /**
         * The fields are usable; [baseUrl] is the normalised absolute URL.
         *
         * @param baseUrl the WebDAV root, without a trailing slash. It is the coordinate
         *        system every record path is relative to.
         */
        data class Ready(
            val baseUrl: String,
            val username: String?,
            val password: CharArray?,
            val trustAllCertificates: Boolean
        ) : Check {

            /**
             * Builds the client for this connection.
             *
             * The transport is a **factory** rather than a value on purpose: it is handed
             * the credentials that were just checked, so a caller cannot pair a validated
             * connection with a transport built from something else.
             *
             * Note the unavoidable `String` copy of the password: OkHttp's `Credentials.basic`
             * takes `String`, so the `CharArray` cannot be the last copy. The array is still
             * the form the settings layer should hold and clear.
             */
            fun connect(newTransport: WebDavTransportFactory): WebDavClient =
                WebDavClient(
                    baseUrl,
                    newTransport.create(username, password?.concatToString(), trustAllCertificates)
                )

            /** Redacted: a connection found in a log or a crash report must not leak the password. */
            override fun toString(): String =
                "Ready(baseUrl=$baseUrl, username=$username, password=[redacted], " +
                    "trustAllCertificates=$trustAllCertificates)"
        }

        /** The fields cannot be used. [problem] is the machine-readable reason. */
        data class Invalid(val problem: Problem, val detail: String) : Check
    }

    /** Everything that can be wrong with a connection, as a value a UI can switch on. */
    enum class Problem {
        BLANK,
        MISSING_SCHEME,
        UNSUPPORTED_SCHEME,
        MISSING_HOST,
        CREDENTIALS_IN_URL,
        QUERY_OR_FRAGMENT,
        UNESCAPED_CHARACTER,
        ENCODED_SEPARATOR,
        RELATIVE_SEGMENT,
        PLAINTEXT,
        PASSWORD_WITHOUT_USER
    }
}

/**
 * Creates the platform transport for a checked connection — the one seam between the pure
 * connection logic and OkHttp.
 *
 * A `fun interface` so an implementation can be a lambda
 * (`WebDavTransportFactory { user, pass, trust -> WebDavOkHttpTransport(user, pass, trust) }`),
 * and so tests can hand in a fake without touching the network.
 */
fun interface WebDavTransportFactory {
    fun create(username: String?, password: String?, trustAllCertificates: Boolean): WebDavTransport
}
