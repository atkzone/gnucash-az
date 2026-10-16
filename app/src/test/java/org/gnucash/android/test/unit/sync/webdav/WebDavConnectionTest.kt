package org.gnucash.android.test.unit.sync.webdav

import org.assertj.core.api.Assertions.assertThat
import org.gnucash.android.sync.BackupUploadOutcome
import org.gnucash.android.sync.RemoteBackup
import org.gnucash.android.sync.webdav.DownloadResponse
import org.gnucash.android.sync.webdav.TransportResponse
import org.gnucash.android.sync.webdav.WebDavClient
import org.gnucash.android.sync.webdav.WebDavConnection
import org.gnucash.android.sync.webdav.WebDavTransport
import org.gnucash.android.sync.webdav.WebDavTransportFactory
import org.junit.Test

/**
 * The WebDAV connection the settings page reads (plan §5② step 2, §5② v1).
 *
 * Every case here is a shape that produces **no error** on the wire: the request simply
 * goes to the wrong root, or goes out unauthenticated, and the user sees a sync that does
 * nothing. That is why the checks are values ([WebDavConnection.Problem]) rather than
 * exceptions — the caller has to be able to say *which* field is wrong.
 *
 * A plain JVM test: the connection logic has no Android dependencies, and the transport is
 * faked at the seam, so a whole sync can be driven end to end without a server.
 */
class WebDavConnectionTest {

    private companion object {
        const val URL = "https://cloud.example.com/remote.php/dav/files/alice/books"
        const val UID = "8f2a1c40-0d3b-4f6e-9a11-7c5e2b8d4f01"
    }

    // ---------------------------------------------------------------- fixture

    /** Records what the connection handed to the platform layer, and where the writes went. */
    private class CapturingTransportFactory : WebDavTransportFactory {
        var username: String? = "unset"
        var password: String? = "unset"
        var trustAll: Boolean? = null
        val requests = mutableListOf<String>()

        override fun create(
            username: String?,
            password: String?,
            trustAllCertificates: Boolean
        ): WebDavTransport {
            this.username = username
            this.password = password
            this.trustAll = trustAllCertificates
            return object : WebDavTransport {
                override fun propfind(url: String, depth: Int, body: String) =
                    TransportResponse(207, "<D:multistatus xmlns:D=\"DAV:\"/>")

                override fun execute(
                    method: String,
                    url: String,
                    headers: Map<String, String>,
                    body: ByteArray?
                ): TransportResponse {
                    requests += "$method $url"
                    return TransportResponse(201, "")
                }

                override fun download(url: String, headers: Map<String, String>) =
                    DownloadResponse(404, ByteArray(0))
            }
        }
    }

    private fun ready(connection: WebDavConnection): WebDavConnection.Check.Ready {
        val result = connection.check()
        assertThat(result).isInstanceOf(WebDavConnection.Check.Ready::class.java)
        return result as WebDavConnection.Check.Ready
    }

    private fun problem(connection: WebDavConnection): WebDavConnection.Problem {
        val result = connection.check()
        assertThat(result).isInstanceOf(WebDavConnection.Check.Invalid::class.java)
        return (result as WebDavConnection.Check.Invalid).problem
    }

    private fun problemOf(url: String) = problem(WebDavConnection(url))

    // ------------------------------------------------------------- normalising

    @Test
    fun `a well formed address is accepted as it stands`() {
        assertThat(ready(WebDavConnection(URL)).baseUrl).isEqualTo(URL)
    }

    @Test
    fun `whitespace picked up with a copy and paste is trimmed away`() {
        assertThat(ready(WebDavConnection("  $URL \n")).baseUrl).isEqualTo(URL)
    }

    @Test
    fun `the scheme and host are lower cased while the path keeps its case`() {
        // The path is the identity of the folder on the server; folding its case would ask
        // for a different one.
        assertThat(ready(WebDavConnection("HTTPS://Cloud.Example.COM/remote.php/DAV/Books")).baseUrl)
            .isEqualTo("https://cloud.example.com/remote.php/DAV/Books")
    }

    @Test
    fun `a trailing slash is dropped, however many there are`() {
        assertThat(ready(WebDavConnection("$URL/")).baseUrl).isEqualTo(URL)
        assertThat(ready(WebDavConnection("$URL///")).baseUrl).isEqualTo(URL)
    }

    @Test
    fun `an empty segment inside the path collapses`() {
        assertThat(ready(WebDavConnection("https://host//a//b//")).baseUrl)
            .isEqualTo("https://host/a/b")
    }

    @Test
    fun `an address with no path at all is accepted`() {
        assertThat(ready(WebDavConnection("https://host")).baseUrl).isEqualTo("https://host")
        assertThat(ready(WebDavConnection("https://host/")).baseUrl).isEqualTo("https://host")
    }

    @Test
    fun `an explicit port survives`() {
        assertThat(ready(WebDavConnection("https://host:8443/dav")).baseUrl)
            .isEqualTo("https://host:8443/dav")
    }

    @Test
    fun `a percent encoded space is left encoded rather than decoded`() {
        // Decoding here would hand the client a root that never matches the hrefs the
        // server sends back, and every record would read as deleted.
        assertThat(ready(WebDavConnection("https://host/my%20books")).baseUrl)
            .isEqualTo("https://host/my%20books")
    }

    // -------------------------------------------------------------- refusals

    @Test
    fun `an empty address is refused`() {
        assertThat(problemOf("")).isEqualTo(WebDavConnection.Problem.BLANK)
        assertThat(problemOf("   ")).isEqualTo(WebDavConnection.Problem.BLANK)
    }

    @Test
    fun `an address with no scheme is refused rather than read as a host`() {
        // The most common paste of all: a naive parser reads this as a host called
        // `cloud.example.com/remote.php`.
        assertThat(problemOf("cloud.example.com/remote.php/dav")).isEqualTo(WebDavConnection.Problem.MISSING_SCHEME)
        assertThat(problemOf("https:/host/dav")).isEqualTo(WebDavConnection.Problem.MISSING_SCHEME)
        assertThat(problemOf("https:")).isEqualTo(WebDavConnection.Problem.MISSING_SCHEME)
    }

    @Test
    fun `a scheme that is not http is refused`() {
        assertThat(problemOf("ftp://host/dav")).isEqualTo(WebDavConnection.Problem.UNSUPPORTED_SCHEME)
        assertThat(problemOf("dav://host/dav")).isEqualTo(WebDavConnection.Problem.UNSUPPORTED_SCHEME)
    }

    @Test
    fun `an address with no host is refused`() {
        assertThat(problemOf("https:///dav")).isEqualTo(WebDavConnection.Problem.MISSING_HOST)
        assertThat(problemOf("https://")).isEqualTo(WebDavConnection.Problem.MISSING_HOST)
        assertThat(problemOf("https://:8080/dav")).isEqualTo(WebDavConnection.Problem.MISSING_HOST)
    }

    @Test
    fun `credentials inside the address are refused rather than silently dropped`() {
        // OkHttp does not turn URL user-info into an Authorization header, so this would
        // authenticate nothing — and would put the password into every logged URL.
        assertThat(problemOf("https://alice:secret@host/dav"))
            .isEqualTo(WebDavConnection.Problem.CREDENTIALS_IN_URL)
        assertThat(problemOf("https://alice@host/dav"))
            .isEqualTo(WebDavConnection.Problem.CREDENTIALS_IN_URL)
    }

    @Test
    fun `a query string or a fragment is refused`() {
        assertThat(problemOf("https://host/dav?token=1"))
            .isEqualTo(WebDavConnection.Problem.QUERY_OR_FRAGMENT)
        assertThat(problemOf("https://host/dav#top"))
            .isEqualTo(WebDavConnection.Problem.QUERY_OR_FRAGMENT)
    }

    @Test
    fun `a dot segment in the path is refused`() {
        assertThat(problemOf("https://host/a/../b")).isEqualTo(WebDavConnection.Problem.RELATIVE_SEGMENT)
        assertThat(problemOf("https://host/a/./b")).isEqualTo(WebDavConnection.Problem.RELATIVE_SEGMENT)
    }

    @Test
    fun `a percent encoded separator is refused, in either case`() {
        assertThat(problemOf("https://host/a/%2Fb")).isEqualTo(WebDavConnection.Problem.ENCODED_SEPARATOR)
        assertThat(problemOf("https://host/a/%2fb")).isEqualTo(WebDavConnection.Problem.ENCODED_SEPARATOR)
    }

    @Test
    fun `a literal space in the address is refused instead of being silently encoded`() {
        assertThat(problemOf("https://host/my books"))
            .isEqualTo(WebDavConnection.Problem.UNESCAPED_CHARACTER)
        assertThat(problemOf("https://host/dav\ttab"))
            .isEqualTo(WebDavConnection.Problem.UNESCAPED_CHARACTER)
    }

    @Test
    fun `plain http is refused unless it is allowed on purpose`() {
        assertThat(problemOf("http://host/dav")).isEqualTo(WebDavConnection.Problem.PLAINTEXT)

        val allowed = WebDavConnection("http://host/dav", allowPlaintext = true)
        assertThat(ready(allowed).baseUrl).isEqualTo("http://host/dav")
    }

    @Test
    fun `a password without a user name is refused`() {
        // Basic auth is only built when a user name is present, so the password would be
        // dropped and every request would go out anonymous.
        assertThat(problem(WebDavConnection(URL, username = null, password = "secret".toCharArray())))
            .isEqualTo(WebDavConnection.Problem.PASSWORD_WITHOUT_USER)
        assertThat(problem(WebDavConnection(URL, username = "   ", password = "secret".toCharArray())))
            .isEqualTo(WebDavConnection.Problem.PASSWORD_WITHOUT_USER)
    }

    @Test
    fun `check answers with a refusal instead of throwing, whatever it is given`() {
        val inputs = listOf(
            "", "   ", "://", "https:", "https:/", "https://", "https:///", "http://x",
            "HTTPS://x", "https://x?", "https://x#", "https://@x", "https://:1", "://x",
            "https://x/a/..", "https://x/%2f", "https://x/\u0000", "我的 账本", "https://x/%"
        )
        for (input in inputs) {
            // The whole point: an answer, never an exception — a settings page renders both.
            WebDavConnection(input).check()
        }
    }

    // -------------------------------------------------------------- credentials

    @Test
    fun `an anonymous connection is accepted`() {
        val ready = ready(WebDavConnection(URL))
        assertThat(ready.username).isNull()
        assertThat(ready.password).isNull()
    }

    @Test
    fun `a user name without a password is accepted`() {
        // An empty password is a legitimate Basic-auth credential; refusing it would lock
        // out servers configured that way.
        val ready = ready(WebDavConnection(URL, username = "alice", password = CharArray(0)))
        assertThat(ready.username).isEqualTo("alice")
        assertThat(ready.password).isNull()
    }

    @Test
    fun `a user name is trimmed but a password never is`() {
        val ready = ready(WebDavConnection(URL, username = " alice ", password = " sp ace ".toCharArray()))
        assertThat(ready.username).isEqualTo("alice")
        assertThat(ready.password?.concatToString()).isEqualTo(" sp ace ")
    }

    @Test
    fun `the summary of a ready connection never contains the password`() {
        val ready = ready(WebDavConnection(URL, username = "alice", password = "topsecret".toCharArray()))
        assertThat(ready.toString()).doesNotContain("topsecret").contains("[redacted]")
    }

    // ------------------------------------------------------------------ wiring

    @Test
    fun `connect hands the checked credentials over and writes to the normalised root`() {
        val connection = WebDavConnection(
            serverUrl = "HTTPS://Cloud.Example.com//dav//books/",
            username = " alice ",
            password = "sp ace".toCharArray()
        )
        val factory = CapturingTransportFactory()

        val client = ready(connection).connect(factory)
        val outcome = RemoteBackup.upload(client, UID, byteArrayOf(0x1F, 0x8B.toByte()), passphrase = null)

        assertThat(factory.username).isEqualTo("alice")
        assertThat(factory.password).isEqualTo("sp ace")
        assertThat(factory.trustAll).isFalse()
        assertThat(outcome).isInstanceOf(BackupUploadOutcome.Stored::class.java)
        // The whole chain: the typed URL, the normalisation, the seam and the record layout
        // — and nothing in it is allowed to re-encode or re-case the result.
        assertThat(factory.requests).containsExactly(
            "MKCOL https://cloud.example.com/dav/books/backup",
            "PUT https://cloud.example.com/dav/books/backup/$UID.gnca"
        )
    }

    @Test
    fun `trust all certificates is carried through to the transport untouched`() {
        val factory = CapturingTransportFactory()
        ready(WebDavConnection(URL, trustAllCertificates = true)).connect(factory)
        assertThat(factory.trustAll).isTrue()
    }

    @Test
    fun `a connection without credentials still builds a client`() {
        val factory = CapturingTransportFactory()
        val client: WebDavClient = ready(WebDavConnection(URL)).connect(factory)
        assertThat(factory.username).isNull()
        assertThat(factory.password).isNull()
        // A client that lists nothing is still a client: the anonymous case must not be
        // short-circuited into "no connection".
        assertThat(client).isNotNull()
    }
}
