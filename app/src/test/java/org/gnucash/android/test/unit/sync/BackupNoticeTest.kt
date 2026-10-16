package org.gnucash.android.test.unit.sync

import org.assertj.core.api.Assertions.assertThat
import org.gnucash.android.sync.BackupDownloadOutcome
import org.gnucash.android.sync.BackupNotice
import org.gnucash.android.sync.BackupNotices
import org.gnucash.android.sync.BackupUploadOutcome
import org.gnucash.android.sync.ConnectionNotice
import org.gnucash.android.sync.webdav.IncompleteReason
import org.gnucash.android.sync.webdav.ListingResult
import org.gnucash.android.sync.webdav.RemoteEntry
import org.gnucash.android.sync.webdav.WebDavActions
import org.junit.Test

/**
 * The outcome → notice mapping.
 *
 * The whole value of this layer is that it **does not collapse** things the user has to tell
 * apart, so most of these tests are of the shape "two outcomes that look similar must not
 * produce the same notice".
 */
class BackupNoticeTest {

    private fun uploadFailed(status: Int) =
        BackupNotices.upload(BackupUploadOutcome.Failed(status, "detail"))

    private fun downloadFailed(status: Int) =
        BackupNotices.download(BackupDownloadOutcome.Failed(status, "detail"))

    // ------------------------------------------------------------------ upload

    @Test
    fun `a plain upload reports itself as plain`() {
        val notice = BackupNotices.upload(BackupUploadOutcome.Stored(encrypted = false, etag = null))
        assertThat(notice).isEqualTo(BackupNotice.UPLOAD_STORED_PLAIN)
        assertThat(notice.isSuccess).isTrue()
    }

    @Test
    fun `an encrypted upload is reported as encrypted`() {
        // The desktop-GnuCash-cannot-open-it warning hinges on this being a different notice,
        // so the two must never share a branch.
        val notice = BackupNotices.upload(BackupUploadOutcome.Stored(encrypted = true, etag = "e"))
        assertThat(notice).isEqualTo(BackupNotice.UPLOAD_STORED_ENCRYPTED)
        assertThat(notice).isNotEqualTo(BackupNotice.UPLOAD_STORED_PLAIN)
        assertThat(notice.isSuccess).isTrue()
    }

    @Test
    fun `an upload that never reached the server is not a refusal`() {
        // status 0 is the transport's "no response" sentinel. Folding it into "the server
        // refused" sends the user to check credentials that were never sent.
        assertThat(uploadFailed(WebDavActions.STATUS_NO_RESPONSE))
            .isEqualTo(BackupNotice.UPLOAD_UNREACHABLE)
        assertThat(uploadFailed(403)).isEqualTo(BackupNotice.UPLOAD_REFUSED)
        assertThat(BackupNotice.UPLOAD_UNREACHABLE.isSuccess).isFalse()
    }

    // ------------------------------------------------------------------ download

    @Test
    fun `a fetched backup reports whether it was encrypted`() {
        val plain = BackupNotices.download(BackupDownloadOutcome.Got(ByteArray(4), encrypted = false))
        val encrypted = BackupNotices.download(BackupDownloadOutcome.Got(ByteArray(4), encrypted = true))
        assertThat(plain).isEqualTo(BackupNotice.RESTORE_GOT_PLAIN)
        assertThat(encrypted).isEqualTo(BackupNotice.RESTORE_GOT_ENCRYPTED)
        assertThat(plain.isSuccess).isTrue()
        assertThat(encrypted.isSuccess).isTrue()
    }

    @Test
    fun `a missing passphrase is a prompt, not a failure`() {
        val notice = BackupNotices.download(BackupDownloadOutcome.PassphraseRequired)
        assertThat(notice).isEqualTo(BackupNotice.RESTORE_NEEDS_PASSPHRASE)
        // Reported as an error, the user would conclude their backup is broken. It is not.
        assertThat(notice.needsPassphrase).isTrue()
        assertThat(notice.isSuccess).isFalse()
    }

    @Test
    fun `a wrong passphrase is not another prompt`() {
        // The next move differs: here the user must re-check what they typed, and asking
        // "please enter a passphrase" again would loop them.
        val notice = BackupNotices.download(BackupDownloadOutcome.WrongPassphrase)
        assertThat(notice).isEqualTo(BackupNotice.RESTORE_WRONG_PASSPHRASE)
        assertThat(notice.needsPassphrase).isFalse()
    }

    @Test
    fun `not-a-book is kept apart from unreadable`() {
        // "Question the address" versus "the file is ours but corrupt": different fixes.
        val notABook = BackupNotices.download(BackupDownloadOutcome.NotABook("a login page"))
        val unreadable = BackupNotices.download(BackupDownloadOutcome.Unreadable("bad magic"))
        assertThat(notABook).isEqualTo(BackupNotice.RESTORE_NOT_A_BOOK)
        assertThat(unreadable).isEqualTo(BackupNotice.RESTORE_UNREADABLE)
        assertThat(notABook).isNotEqualTo(unreadable)
    }

    @Test
    fun `an absent backup is its own outcome`() {
        assertThat(BackupNotices.download(BackupDownloadOutcome.Missing))
            .isEqualTo(BackupNotice.RESTORE_NOTHING_THERE)
    }

    @Test
    fun `a refused read is not an unreachable server`() {
        assertThat(downloadFailed(WebDavActions.STATUS_NO_RESPONSE))
            .isEqualTo(BackupNotice.RESTORE_UNREACHABLE)
        assertThat(downloadFailed(500)).isEqualTo(BackupNotice.RESTORE_REFUSED)
    }

    @Test
    fun `exactly the four stored-or-fetched outcomes count as success`() {
        val successes = BackupNotice.entries.filter { it.isSuccess }
        assertThat(successes).containsExactlyInAnyOrder(
            BackupNotice.UPLOAD_STORED_PLAIN,
            BackupNotice.UPLOAD_STORED_ENCRYPTED,
            BackupNotice.RESTORE_GOT_PLAIN,
            BackupNotice.RESTORE_GOT_ENCRYPTED
        )
    }

    @Test
    fun `only the missing-passphrase notice asks for a passphrase`() {
        assertThat(BackupNotice.entries.filter { it.needsPassphrase })
            .containsExactly(BackupNotice.RESTORE_NEEDS_PASSPHRASE)
    }

    // ------------------------------------------------------------------ connection

    @Test
    fun `an empty but complete listing is a usable root`() {
        // A brand-new WebDAV folder has no entries. Treating "no entries" as a failure would
        // reject the very first connection a new user makes.
        assertThat(BackupNotices.connection(ListingResult.Complete(emptyList(), 1)))
            .isEqualTo(ConnectionNotice.READY)
    }

    @Test
    fun `a listing that did not complete never says ready`() {
        val byReason = IncompleteReason.entries.associateWith { reason ->
            BackupNotices.connection(ListingResult.Incomplete(reason, "detail"))
        }
        assertThat(byReason[IncompleteReason.Transport]).isEqualTo(ConnectionNotice.UNREACHABLE)
        assertThat(byReason[IncompleteReason.HttpStatus]).isEqualTo(ConnectionNotice.NOT_A_WEBDAV_ROOT)
        assertThat(byReason[IncompleteReason.MalformedResponse]).isEqualTo(ConnectionNotice.BAD_RESPONSE)
        assertThat(byReason[IncompleteReason.FailedResponse]).isEqualTo(ConnectionNotice.BAD_RESPONSE)
        assertThat(byReason[IncompleteReason.DepthExceeded]).isEqualTo(ConnectionNotice.TOO_DEEP)
        assertThat(byReason.values).doesNotContain(ConnectionNotice.READY)
    }

    @Test
    fun `a complete listing with entries is still ready`() {
        val entries = listOf(
            RemoteEntry(
                path = "/backup/book.gnca",
                href = "https://cloud.example.com/backup/book.gnca",
                isCollection = false,
                etag = "e"
            )
        )
        assertThat(BackupNotices.connection(ListingResult.Complete(entries, 1)))
            .isEqualTo(ConnectionNotice.READY)
    }
}
