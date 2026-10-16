package org.gnucash.android.test.unit.sync

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.gnucash.android.sync.BackupDownloadOutcome
import org.gnucash.android.sync.BackupUploadOutcome
import org.gnucash.android.sync.EncryptedBackup
import org.gnucash.android.sync.RemoteBackup
import org.gnucash.android.sync.webdav.DownloadResponse
import org.gnucash.android.sync.webdav.TransportResponse
import org.gnucash.android.sync.webdav.WebDavClient
import org.gnucash.android.sync.webdav.WebDavTransport
import org.junit.Test
import java.io.IOException

/**
 * One-way remote backup and restore (plan §5② step 2, feature ② v1).
 *
 * A plain JVM test driving a recording fake transport, for the same reason the WebDAV
 * tests are: the interesting cases are the ones a real server will never stage for you —
 * "the remote is encrypted and the user has no passphrase", "the remote was written by a
 * newer build" — and each of those must be a *refusal* rather than a durable silent
 * corruption of the local book.
 */
class RemoteBackupTest {

    private companion object {
        const val TEST_ITERATIONS = 1_000
        const val BOOK_UID = "8f2a1c40-0d3b-4f6e-9a11-7c5e2b8d4f01"
        val PASS = "correct horse battery staple".toCharArray()

        /**
         * A realistic book: the gzip magic the app's own `.gnca` backups start with, then
         * bytes standing in for the compressed XML. The restore guard recognises containers
         * by their leading bytes, so a fixture without one would exercise the wrong branch.
         */
        val BOOK = byteArrayOf(0x1F, 0x8B.toByte(), 0x08, 0x00) +
            "gzipped GnuCash XML, standing in for a real backup".toByteArray()

        /** What a login form or a captive portal answers a `GET` with: a perfectly valid `200`. */
        val LOGIN_PAGE = (
            "<!DOCTYPE html><html><head><title>Sign in</title></head>" +
                "<body><form action=\"/login\"></form></body></html>"
            ).toByteArray()
    }

    private val base = "https://cloud.example.com/remote.php/dav/files/u/book"

    // ---------------------------------------------------------------- fixture

    private data class Write(
        val method: String,
        val url: String,
        val headers: Map<String, String>,
        val body: ByteArray?
    )

    private class RecordingTransport(
        private val handler: (String, String) -> TransportResponse
    ) : WebDavTransport {

        val writes = mutableListOf<Write>()
        val downloads = mutableListOf<String>()

        var downloadResponse: DownloadResponse = DownloadResponse(404, ByteArray(0))
        var downloadFailure: Exception? = null

        override fun propfind(url: String, depth: Int, body: String): TransportResponse =
            TransportResponse(207, "<D:multistatus xmlns:D=\"DAV:\"/>")

        override fun execute(
            method: String,
            url: String,
            headers: Map<String, String>,
            body: ByteArray?
        ): TransportResponse {
            writes += Write(method, url, headers, body)
            return handler(method, url)
        }

        override fun download(url: String, headers: Map<String, String>): DownloadResponse {
            downloads += url
            downloadFailure?.let { throw it }
            return downloadResponse
        }

        fun trace(): List<String> = writes.map { "${it.method} ${it.url}" }

        fun putBody(): ByteArray = writes.first { it.method == "PUT" }.body!!
    }

    /** Answers `201 Created` to everything unless told otherwise. */
    private fun transport(
        handler: (String, String) -> TransportResponse = { _, _ -> TransportResponse(201, "") }
    ) = RecordingTransport(handler)

    private fun client(t: WebDavTransport) = WebDavClient(base, t)

    private fun expectedPath() = "${RemoteBackup.pathFor(BOOK_UID)}"

    private fun encrypted(bytes: ByteArray = BOOK) =
        EncryptedBackup.encrypt(bytes, PASS, TEST_ITERATIONS)

    private fun ByteArray.replaceAscii(old: String, new: String): ByteArray {
        require(old.length == new.length)
        val pattern = old.toByteArray(Charsets.US_ASCII)
        val replacement = new.toByteArray(Charsets.US_ASCII)
        val out = copyOf()
        outer@ for (i in 0..out.size - pattern.size) {
            for (j in pattern.indices) if (out[i + j] != pattern[j]) continue@outer
            replacement.copyInto(out, i)
            return out
        }
        error("pattern not found: $old")
    }

    // ------------------------------------------------------------------ upload

    @Test
    fun `an upload without a passphrase stores the book verbatim`() {
        val t = transport()
        val outcome = RemoteBackup.upload(client(t), BOOK_UID, BOOK, passphrase = null)

        assertThat(outcome).isInstanceOf(BackupUploadOutcome.Stored::class.java)
        assertThat((outcome as BackupUploadOutcome.Stored).encrypted).isFalse()
        assertThat(t.putBody()).isEqualTo(BOOK)
        assertThat(EncryptedBackup.isEncrypted(t.putBody())).isFalse()
    }

    @Test
    fun `an empty passphrase means no encryption`() {
        val t = transport()
        val outcome = RemoteBackup.upload(client(t), BOOK_UID, BOOK, passphrase = CharArray(0))

        assertThat((outcome as BackupUploadOutcome.Stored).encrypted).isFalse()
        assertThat(t.putBody()).isEqualTo(BOOK)
    }

    @Test
    fun `an upload with a passphrase stores an encrypted container`() {
        val t = transport()
        val outcome = RemoteBackup.upload(client(t), BOOK_UID, BOOK, PASS, TEST_ITERATIONS)

        assertThat((outcome as BackupUploadOutcome.Stored).encrypted).isTrue()
        val stored = t.putBody()
        assertThat(EncryptedBackup.isEncrypted(stored)).isTrue()
        assertThat(EncryptedBackup.decrypt(stored, PASS)).isEqualTo(BOOK)
        // The plaintext must not be sitting in the uploaded bytes anywhere.
        assertThat(String(stored, Charsets.ISO_8859_1)).doesNotContain(String(BOOK, Charsets.ISO_8859_1))
    }

    @Test
    fun `an upload creates the backup collection before the file`() {
        val t = transport()
        RemoteBackup.upload(client(t), BOOK_UID, BOOK, PASS, TEST_ITERATIONS)

        val trace = t.trace()
        assertThat(trace).hasSize(2)
        assertThat(trace[0]).isEqualTo("MKCOL $base/${RemoteBackup.FOLDER}")
        assertThat(trace[1]).isEqualTo("PUT $base${expectedPath()}")
    }

    @Test
    fun `an upload the server refuses is reported with its status`() {
        val t = transport { method, _ ->
            if (method == "PUT") TransportResponse(507, "") else TransportResponse(201, "")
        }
        val outcome = RemoteBackup.upload(client(t), BOOK_UID, BOOK, PASS, TEST_ITERATIONS)

        assertThat(outcome).isInstanceOf(BackupUploadOutcome.Failed::class.java)
        assertThat((outcome as BackupUploadOutcome.Failed).status).isEqualTo(507)
    }

    // ---------------------------------------------------------------- download

    @Test
    fun `a download of a plain backup returns it verbatim`() {
        val t = transport().apply { downloadResponse = DownloadResponse(200, BOOK, null) }
        val outcome = RemoteBackup.download(client(t), BOOK_UID, passphrase = null)

        assertThat(outcome).isInstanceOf(BackupDownloadOutcome.Got::class.java)
        outcome as BackupDownloadOutcome.Got
        assertThat(outcome.book).isEqualTo(BOOK)
        assertThat(outcome.encrypted).isFalse()
        assertThat(t.downloads).containsExactly("$base${expectedPath()}")
    }

    @Test
    fun `a download of an encrypted backup returns the book in the clear`() {
        val t = transport().apply { downloadResponse = DownloadResponse(200, encrypted(), null) }
        val outcome = RemoteBackup.download(client(t), BOOK_UID, PASS)

        assertThat(outcome).isInstanceOf(BackupDownloadOutcome.Got::class.java)
        outcome as BackupDownloadOutcome.Got
        assertThat(outcome.book).isEqualTo(BOOK)
        assertThat(outcome.encrypted).isTrue()
    }

    @Test
    fun `an encrypted backup is refused rather than returned when no passphrase is given`() {
        // The hard guard: handing these bytes back would let the caller write ciphertext
        // over the book and report success.
        val t = transport().apply { downloadResponse = DownloadResponse(200, encrypted(), null) }
        val outcome = RemoteBackup.download(client(t), BOOK_UID, passphrase = null)

        assertThat(outcome).isEqualTo(BackupDownloadOutcome.PassphraseRequired)
    }

    @Test
    fun `an empty passphrase does not open an encrypted backup`() {
        val t = transport().apply { downloadResponse = DownloadResponse(200, encrypted(), null) }
        assertThat(RemoteBackup.download(client(t), BOOK_UID, CharArray(0)))
            .isEqualTo(BackupDownloadOutcome.PassphraseRequired)
    }

    @Test
    fun `a wrong passphrase is reported as a wrong passphrase`() {
        val t = transport().apply { downloadResponse = DownloadResponse(200, encrypted(), null) }
        val outcome = RemoteBackup.download(client(t), BOOK_UID, "nope".toCharArray())

        assertThat(outcome).isEqualTo(BackupDownloadOutcome.WrongPassphrase)
    }

    @Test
    fun `a plain backup is returned even when a passphrase is set`() {
        // The remote may predate the passphrase; refusing here would make "set a
        // passphrase" block restoring your own older plain backup.
        val t = transport().apply { downloadResponse = DownloadResponse(200, BOOK, null) }
        val outcome = RemoteBackup.download(client(t), BOOK_UID, PASS)

        assertThat(outcome).isInstanceOf(BackupDownloadOutcome.Got::class.java)
        outcome as BackupDownloadOutcome.Got
        assertThat(outcome.book).isEqualTo(BOOK)
        assertThat(outcome.encrypted).isFalse()
    }

    @Test
    fun `a missing backup is reported as missing`() {
        val t = transport().apply { downloadResponse = DownloadResponse(404, ByteArray(0)) }
        assertThat(RemoteBackup.download(client(t), BOOK_UID, PASS))
            .isEqualTo(BackupDownloadOutcome.Missing)
    }

    @Test
    fun `a backup written by a newer version is reported as unreadable`() {
        val newer = encrypted().replaceAscii("version: 1", "version: 2")
        val t = transport().apply { downloadResponse = DownloadResponse(200, newer, null) }
        val outcome = RemoteBackup.download(client(t), BOOK_UID, PASS)

        assertThat(outcome).isInstanceOf(BackupDownloadOutcome.Unreadable::class.java)
        assertThat((outcome as BackupDownloadOutcome.Unreadable).detail).contains("version")
    }

    // ---------------------------------------------------- the not-a-book guard

    @Test
    fun `a login page served with a 200 is refused, not restored over the book`() {
        // The durable failure the guard exists for: a restore writes these bytes over the
        // local book *before* the importer sees them, so "the server answered 200 with a
        // login form" would otherwise be discovered only after the book had been replaced.
        val t = transport().apply { downloadResponse = DownloadResponse(200, LOGIN_PAGE, null) }
        val outcome = RemoteBackup.download(client(t), BOOK_UID, passphrase = null)

        assertThat(outcome).isInstanceOf(BackupDownloadOutcome.NotABook::class.java)
        assertThat((outcome as BackupDownloadOutcome.NotABook).detail).contains("web page")
    }

    @Test
    fun `an empty 200 is refused rather than treated as an empty book`() {
        val t = transport().apply { downloadResponse = DownloadResponse(200, ByteArray(0), null) }
        val outcome = RemoteBackup.download(client(t), BOOK_UID, passphrase = null)

        assertThat(outcome).isInstanceOf(BackupDownloadOutcome.NotABook::class.java)
        assertThat((outcome as BackupDownloadOutcome.NotABook).detail).contains("no bytes")
    }

    @Test
    fun `a body with no recognisable container is refused`() {
        val t = transport().apply {
            downloadResponse = DownloadResponse(200, "{\"error\":\"unauthorized\"}".toByteArray(), null)
        }
        val outcome = RemoteBackup.download(client(t), BOOK_UID, passphrase = null)

        assertThat(outcome).isInstanceOf(BackupDownloadOutcome.NotABook::class.java)
    }

    @Test
    fun `the guard also runs on what an encrypted container holds`() {
        // Uploading a non-book is just as restorable a mistake as being served one, and GCM
        // authenticates the bytes without saying anything about what they are.
        val sealedPage = EncryptedBackup.encrypt(LOGIN_PAGE, PASS, TEST_ITERATIONS)
        val t = transport().apply { downloadResponse = DownloadResponse(200, sealedPage, null) }
        val outcome = RemoteBackup.download(client(t), BOOK_UID, PASS)

        assertThat(outcome).isInstanceOf(BackupDownloadOutcome.NotABook::class.java)
    }

    @Test
    fun `every container a book can arrive in still comes back as a book`() {
        val payloads = listOf(
            byteArrayOf(0x1F, 0x8B.toByte()) + "x".toByteArray(),
            byteArrayOf(0x50, 0x4B, 0x03, 0x04) + "x".toByteArray(),
            "<?xml version=\"1.0\"?><gnucash>".toByteArray(),
            "SQLite format 3\u0000".toByteArray(Charsets.US_ASCII)
        )
        for (payload in payloads) {
            val t = transport().apply { downloadResponse = DownloadResponse(200, payload, null) }
            assertThat(RemoteBackup.download(client(t), BOOK_UID, passphrase = null))
                .isInstanceOf(BackupDownloadOutcome.Got::class.java)
        }
    }

    @Test
    fun `a transport failure keeps its own words`() {
        val t = transport().apply { downloadFailure = IOException("connection reset by peer") }
        val outcome = RemoteBackup.download(client(t), BOOK_UID, PASS)

        assertThat(outcome).isInstanceOf(BackupDownloadOutcome.Failed::class.java)
        outcome as BackupDownloadOutcome.Failed
        assertThat(outcome.status).isZero()
        assertThat(outcome.detail).contains("connection reset by peer")
    }

    // -------------------------------------------------------------------- paths

    @Test
    fun `the remote path is the same whether or not the backup is encrypted`() {
        // One slot per book: a mode switch must not be able to leave a stale second file
        // behind for a later restore to pick up by mistake.
        val plain = transport()
        RemoteBackup.upload(client(plain), BOOK_UID, BOOK, passphrase = null)
        val secret = transport()
        RemoteBackup.upload(client(secret), BOOK_UID, BOOK, PASS, TEST_ITERATIONS)

        assertThat(plain.trace().last()).isEqualTo(secret.trace().last())
        assertThat(expectedPath()).isEqualTo("/${RemoteBackup.FOLDER}/$BOOK_UID.${RemoteBackup.EXTENSION}")
    }

    @Test
    fun `a book uid that cannot be a path segment is rejected`() {
        assertThatThrownBy { RemoteBackup.pathFor("") }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { RemoteBackup.pathFor("   ") }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { RemoteBackup.pathFor("a/b") }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { RemoteBackup.pathFor(".hidden") }
            .isInstanceOf(IllegalArgumentException::class.java)
    }
}
