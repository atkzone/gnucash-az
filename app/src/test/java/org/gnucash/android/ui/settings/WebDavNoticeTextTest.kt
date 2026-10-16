package org.gnucash.android.ui.settings

import org.assertj.core.api.Assertions.assertThat
import org.gnucash.android.R
import org.gnucash.android.sync.BackupNotice
import org.gnucash.android.sync.ConnectionNotice
import org.gnucash.android.sync.PassphraseDecision
import org.gnucash.android.sync.webdav.WebDavConnection
import org.junit.Test

/**
 * The outcome → string mapping, checked for the one mistake an exhaustive `when` cannot catch:
 * a branch that was copied and pasted without changing the resource.
 *
 * Every family must map onto **pairwise distinct** strings, because two outcomes sharing a
 * message means one of them is lying about what happened — and the user's next move differs
 * for every one of them.
 *
 * A plain JVM test: it only reads the generated `R` constants, no `Context` is involved.
 */
class WebDavNoticeTextTest {

    private fun idsOf(values: List<Int>): List<Int> = values

    @Test
    fun `every backup notice has its own message`() {
        val ids = idsOf(BackupNotice.entries.map { WebDavNoticeText.of(it) })
        assertThat(ids).doesNotContain(0)
        assertThat(ids).doesNotHaveDuplicates()
        assertThat(ids).hasSameSizeAs(BackupNotice.entries)
    }

    @Test
    fun `every connection notice has its own message`() {
        val ids = idsOf(ConnectionNotice.entries.map { WebDavNoticeText.of(it) })
        assertThat(ids).doesNotContain(0)
        assertThat(ids).doesNotHaveDuplicates()
    }

    @Test
    fun `every connection problem has its own message`() {
        val ids = idsOf(WebDavConnection.Problem.entries.map { WebDavNoticeText.of(it) })
        assertThat(ids).doesNotContain(0)
        assertThat(ids).doesNotHaveDuplicates()
    }

    @Test
    fun `every passphrase problem has its own message`() {
        val ids = idsOf(PassphraseDecision.Problem.entries.map { WebDavNoticeText.of(it) })
        assertThat(ids).doesNotContain(0)
        assertThat(ids).doesNotHaveDuplicates()
    }

    @Test
    fun `a bad address is reported on the address row`() {
        // Nine of the eleven problems are one wrong string, and the page must point at it
        // rather than leaving the user to guess which field the message is about.
        val addressProblems = listOf(
            WebDavConnection.Problem.BLANK,
            WebDavConnection.Problem.MISSING_SCHEME,
            WebDavConnection.Problem.UNSUPPORTED_SCHEME,
            WebDavConnection.Problem.MISSING_HOST,
            WebDavConnection.Problem.CREDENTIALS_IN_URL,
            WebDavConnection.Problem.QUERY_OR_FRAGMENT,
            WebDavConnection.Problem.UNESCAPED_CHARACTER,
            WebDavConnection.Problem.ENCODED_SEPARATOR,
            WebDavConnection.Problem.RELATIVE_SEGMENT
        )
        val keys = addressProblems.map { WebDavNoticeText.preferenceKeyOf(it) }
        assertThat(keys).doesNotContainNull()
        assertThat(keys.toSet()).hasSize(1)
        assertThat(keys.first()).isEqualTo(R.string.key_webdav_server_url)
    }

    @Test
    fun `the two non-address problems point at their own switches`() {
        // "plain http" is fixed by a switch, not by editing the address; and a password with
        // no user name is fixed in the user-name row.
        assertThat(WebDavNoticeText.preferenceKeyOf(WebDavConnection.Problem.PLAINTEXT))
            .isEqualTo(R.string.key_webdav_allow_plaintext)
        assertThat(WebDavNoticeText.preferenceKeyOf(WebDavConnection.Problem.PASSWORD_WITHOUT_USER))
            .isEqualTo(R.string.key_webdav_username)
    }

    @Test
    fun `the passphrase prompt is not the failure message`() {
        // The single most costly confusion this feature can have: telling the user their
        // backup is broken when all that is missing is a passphrase.
        assertThat(WebDavNoticeText.of(BackupNotice.RESTORE_NEEDS_PASSPHRASE))
            .isNotEqualTo(WebDavNoticeText.of(BackupNotice.RESTORE_WRONG_PASSPHRASE))
        assertThat(WebDavNoticeText.of(BackupNotice.RESTORE_NEEDS_PASSPHRASE))
            .isNotEqualTo(WebDavNoticeText.of(BackupNotice.RESTORE_UNREADABLE))
    }
}
