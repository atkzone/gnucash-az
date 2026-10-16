package org.gnucash.android.test.unit.sync

import org.assertj.core.api.Assertions.assertThat
import org.gnucash.android.sync.BackupNotice
import org.gnucash.android.sync.ConnectionNotice
import org.gnucash.android.sync.EncryptedBackup
import org.gnucash.android.sync.WebDavSyncController
import org.gnucash.android.sync.WebDavSyncResult
import org.gnucash.android.sync.webdav.DownloadResponse
import org.gnucash.android.sync.webdav.TransportResponse
import org.gnucash.android.sync.webdav.WebDavConnection
import org.gnucash.android.sync.webdav.WebDavTransport
import org.gnucash.android.sync.webdav.WebDavTransportFactory
import org.junit.Test
import java.io.IOException

/**
 * The settings page's action orchestration.
 *
 * Two kinds of assertion dominate, and both are about orderings that produce **no error** when
 * they are wrong:
 *
 *  - **an unusable address must produce zero requests** ([an unusable address never reaches the
 *    network]) — otherwise a typo in the URL is reported as a network problem;
 *  - **nothing may be uploaded when the local export yielded nothing** ([an empty export is
 *    refused instead of uploaded]) — otherwise a good remote backup is replaced by a zero-byte
 *    object and the upload reports success.
 */
class WebDavSyncControllerTest {

    private companion object {
        const val BOOK_UID = "2b7d9e11-4c8a-4d2f-9a03-6f1e5c8b7a20"
        const val BASE = "https://cloud.example.com/remote.php/dav/files/u/book"
        val PASS = "correct horse battery staple".toCharArray()
        val WRONG_PASS = "battery horse staple correct".toCharArray()

        /** A book as the app writes it: the gzip magic, then compressed XML. */
        val BOOK = byteArrayOf(0x1F, 0x8B.toByte(), 0x08, 0x00) +
            "gzipped GnuCash XML, standing in for a real backup".toByteArray()

        val LOGIN_PAGE = (
            "<!DOCTYPE html><html><head><title>Sign in</title></head>" +
                "<body><form action=\"/login\"></form></body></html>"
            ).toByteArray()

        val ADDRESS = WebDavConnection(BASE)
    }

    private class FakeTransport : WebDavTransport {

        val requests = mutableListOf<String>()
        val requestBodies = mutableListOf<ByteArray?>()

        var propfindStatus = 207
        var propfindBody = "<D:multistatus xmlns:D=\"DAV:\"/>"
        var propfindFailure: Exception? = null

        var writeStatus = 201
        var writeFailure: Exception? = null

        var downloadResponse: DownloadResponse = DownloadResponse(404, ByteArray(0))
        var downloadFailure: Exception? = null

        override fun propfind(url: String, depth: Int, body: String): TransportResponse {
            requests += "PROPFIND depth=$depth $url"
            propfindFailure?.let { throw it }
            return TransportResponse(propfindStatus, propfindBody)
        }

        override fun execute(
            method: String,
            url: String,
            headers: Map<String, String>,
            body: ByteArray?
        ): TransportResponse {
            requests += "$method $url"
            requestBodies += body
            writeFailure?.let { throw it }
            return TransportResponse(writeStatus, "")
        }

        override fun download(url: String, headers: Map<String, String>): DownloadResponse {
            requests += "GET $url"
            downloadFailure?.let { throw it }
            return downloadResponse
        }

        fun uploadedBody(): ByteArray = requestBodies.first { it != null && it.isNotEmpty() }!!
    }

    private val transport = FakeTransport()

    private fun controller(book: () -> ByteArray = { BOOK }) = WebDavSyncController(
        bookUid = BOOK_UID,
        transportFactory = WebDavTransportFactory { _, _, _ -> transport },
        exportBook = book
    )

    // ------------------------------------------------------- validate before dialling

    @Test
    fun `an unusable address never reaches the network`() {
        // One assertion per action, because a single missing `check()` in any of the three is
        // exactly the bug this guards.
        val missingScheme = WebDavConnection("cloud.example.com/dav")

        assertThat(problemOf(controller().testConnection(missingScheme)))
            .isEqualTo(WebDavConnection.Problem.MISSING_SCHEME)
        assertThat(problemOf(controller().backup(missingScheme, null)))
            .isEqualTo(WebDavConnection.Problem.MISSING_SCHEME)
        assertThat(problemOf(controller().restore(missingScheme, null)))
            .isEqualTo(WebDavConnection.Problem.MISSING_SCHEME)

        assertThat(transport.requests).isEmpty()
    }

    @Test
    fun `plain http is refused before dialling unless it is allowed`() {
        val plain = WebDavConnection("http://cloud.example.com/dav")
        assertThat(problemOf(controller().backup(plain, null)))
            .isEqualTo(WebDavConnection.Problem.PLAINTEXT)
        assertThat(transport.requests).isEmpty()

        // …and once allowed on purpose, it is not refused here (the request is attempted).
        val allowed = WebDavConnection("http://cloud.example.com/dav", allowPlaintext = true)
        assertThat(controller().backup(allowed, null)).isInstanceOf(WebDavSyncResult.Uploaded::class.java)
        assertThat(transport.requests).isNotEmpty()
    }

    // ------------------------------------------------------- connection test

    @Test
    fun `a listing root reports ready and is probed at depth one`() {
        val result = controller().testConnection(ADDRESS)
        assertThat((result as WebDavSyncResult.Checked).notice).isEqualTo(ConnectionNotice.READY)
        assertThat(transport.requests.single()).contains("PROPFIND depth=1")
    }

    @Test
    fun `a 200 with a web page is not a webdav root`() {
        transport.propfindStatus = 200
        transport.propfindBody = String(LOGIN_PAGE)
        val result = controller().testConnection(ADDRESS)
        assertThat((result as WebDavSyncResult.Checked).notice)
            .isEqualTo(ConnectionNotice.NOT_A_WEBDAV_ROOT)
    }

    @Test
    fun `an unreachable server is reported as unreachable, not as a refusal`() {
        transport.propfindFailure = IOException("connection refused")
        val result = controller().testConnection(ADDRESS)
        assertThat((result as WebDavSyncResult.Checked).notice).isEqualTo(ConnectionNotice.UNREACHABLE)
    }

    // ------------------------------------------------------- upload

    @Test
    fun `an empty export is refused instead of uploaded`() {
        // The zero-byte PUT is the dangerous one: it replaces a good remote backup with
        // nothing, and every layer above would call it a success.
        val result = controller { ByteArray(0) }.backup(ADDRESS, null)
        assertThat(result).isInstanceOf(WebDavSyncResult.LocalExportFailed::class.java)
        assertThat(transport.requests).isEmpty()
    }

    @Test
    fun `a failed local export is reported, not thrown`() {
        val result = controller { throw IOException("no space left on device") }.backup(ADDRESS, null)
        assertThat((result as WebDavSyncResult.LocalExportFailed).detail)
            .contains("no space left on device")
        assertThat(transport.requests).isEmpty()
    }

    @Test
    fun `an upload without a passphrase puts the book up verbatim`() {
        val result = controller().backup(ADDRESS, null)
        assertThat((result as WebDavSyncResult.Uploaded).notice)
            .isEqualTo(BackupNotice.UPLOAD_STORED_PLAIN)
        assertThat(transport.uploadedBody()).isEqualTo(BOOK)
        assertThat(transport.requests).anyMatch { it.endsWith("/backup/$BOOK_UID.gnca") }
    }

    @Test
    fun `an empty passphrase is the same as no passphrase`() {
        // `RemoteBackup` treats an empty key as "none"; the controller must not turn that into
        // an encryption failure.
        val result = controller().backup(ADDRESS, CharArray(0))
        assertThat((result as WebDavSyncResult.Uploaded).notice)
            .isEqualTo(BackupNotice.UPLOAD_STORED_PLAIN)
        assertThat(transport.uploadedBody()).isEqualTo(BOOK)
    }

    @Test
    fun `an upload with a passphrase wraps the book in a container`() {
        val result = controller().backup(ADDRESS, PASS)
        assertThat((result as WebDavSyncResult.Uploaded).notice)
            .isEqualTo(BackupNotice.UPLOAD_STORED_ENCRYPTED)

        val body = transport.uploadedBody()
        assertThat(EncryptedBackup.isEncrypted(body)).isTrue()
        // …and it really is the book inside, not a container holding something else.
        assertThat(EncryptedBackup.decrypt(body, PASS)).isEqualTo(BOOK)
    }

    @Test
    fun `a refused upload is reported as refused`() {
        transport.writeStatus = 403
        val result = controller().backup(ADDRESS, null)
        assertThat((result as WebDavSyncResult.Failed).notice)
            .isEqualTo(BackupNotice.UPLOAD_REFUSED)
    }

    @Test
    fun `an upload that never reached the server says so`() {
        transport.writeFailure = IOException("host is down")
        val result = controller().backup(ADDRESS, null)
        assertThat((result as WebDavSyncResult.Failed).notice)
            .isEqualTo(BackupNotice.UPLOAD_UNREACHABLE)
    }

    // ------------------------------------------------------- restore

    @Test
    fun `a plain remote backup comes back with its bytes`() {
        transport.downloadResponse = DownloadResponse(200, BOOK)
        val result = controller().restore(ADDRESS, null)
        assertThat((result as WebDavSyncResult.Downloaded).book).isEqualTo(BOOK)
        assertThat(result.notice).isEqualTo(BackupNotice.RESTORE_GOT_PLAIN)
        assertThat(transport.requests).anyMatch { it.endsWith("/backup/$BOOK_UID.gnca") }
    }

    @Test
    fun `an encrypted backup without a passphrase never yields bytes`() {
        transport.downloadResponse = DownloadResponse(200, EncryptedBackup.encrypt(BOOK, PASS, 1_000))
        val result = controller().restore(ADDRESS, null)
        assertThat(result).isInstanceOf(WebDavSyncResult.Failed::class.java)
        assertThat((result as WebDavSyncResult.Failed).notice)
            .isEqualTo(BackupNotice.RESTORE_NEEDS_PASSPHRASE)
        assertThat(result.notice.needsPassphrase).isTrue()
    }

    @Test
    fun `an encrypted backup comes back in the clear when the passphrase is right`() {
        transport.downloadResponse = DownloadResponse(200, EncryptedBackup.encrypt(BOOK, PASS, 1_000))
        val result = controller().restore(ADDRESS, PASS)
        assertThat((result as WebDavSyncResult.Downloaded).book).isEqualTo(BOOK)
        assertThat(result.notice).isEqualTo(BackupNotice.RESTORE_GOT_ENCRYPTED)
    }

    @Test
    fun `a wrong passphrase is reported as a wrong passphrase`() {
        transport.downloadResponse = DownloadResponse(200, EncryptedBackup.encrypt(BOOK, PASS, 1_000))
        val result = controller().restore(ADDRESS, WRONG_PASS)
        assertThat((result as WebDavSyncResult.Failed).notice)
            .isEqualTo(BackupNotice.RESTORE_WRONG_PASSPHRASE)
    }

    @Test
    fun `a login page served with a 200 is not handed back as a book`() {
        transport.downloadResponse = DownloadResponse(200, LOGIN_PAGE)
        val result = controller().restore(ADDRESS, null)
        assertThat(result).isInstanceOf(WebDavSyncResult.Failed::class.java)
        assertThat((result as WebDavSyncResult.Failed).notice)
            .isEqualTo(BackupNotice.RESTORE_NOT_A_BOOK)
    }

    @Test
    fun `an absent remote backup is its own outcome`() {
        transport.downloadResponse = DownloadResponse(404, ByteArray(0))
        val result = controller().restore(ADDRESS, null)
        assertThat((result as WebDavSyncResult.Failed).notice)
            .isEqualTo(BackupNotice.RESTORE_NOTHING_THERE)
    }

    @Test
    fun `restoring never writes to the remote`() {
        // One-way in the other direction: "bring it down" must not create, move or delete
        // anything over there, however broken the local state is.
        transport.downloadResponse = DownloadResponse(200, BOOK)
        controller().restore(ADDRESS, null)
        assertThat(transport.requests).noneMatch { it.startsWith("PUT") }
        assertThat(transport.requests).noneMatch { it.startsWith("DELETE") }
        assertThat(transport.requests).noneMatch { it.startsWith("MOVE") }
        assertThat(transport.requests).noneMatch { it.startsWith("MKCOL") }
    }

    @Test
    fun `a read that never reached the server says so`() {
        transport.downloadFailure = IOException("no route to host")
        val result = controller().restore(ADDRESS, null)
        assertThat((result as WebDavSyncResult.Failed).notice)
            .isEqualTo(BackupNotice.RESTORE_UNREACHABLE)
    }

    private fun problemOf(result: WebDavSyncResult): WebDavConnection.Problem =
        (result as WebDavSyncResult.NotUsable).problem
}
