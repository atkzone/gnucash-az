package org.gnucash.android.test.unit.util

import androidx.preference.Preference
import androidx.preference.PreferenceManager
import org.assertj.core.api.Assertions.assertThat
import org.gnucash.android.R
import org.gnucash.android.test.unit.GnuCashTest
import org.gnucash.android.util.BackupFileFormat
import org.junit.Test

/**
 * Covers the "Import Backup" entry of the backup settings screen and the file name filter
 * behind it. The screen must offer a file-browser entry accepting `.gnca` and `.gnucash`
 * backups, and must refuse everything else with a message naming the supported formats.
 */
class BackupFileFormatTest : GnuCashTest() {

    // ---------------------------------------------------------------- name filter

    @Test
    fun `accepts the gnca extension`() {
        assertThat(BackupFileFormat.isSupportedBackupFileName("my_book.gnca")).isTrue()
    }

    @Test
    fun `accepts the gnucash extension`() {
        assertThat(BackupFileFormat.isSupportedBackupFileName("my_book.gnucash")).isTrue()
    }

    @Test
    fun `matching is case insensitive`() {
        assertThat(BackupFileFormat.isSupportedBackupFileName("BACKUP.GNCA")).isTrue()
        assertThat(BackupFileFormat.isSupportedBackupFileName("Backup.GnuCash")).isTrue()
    }

    @Test
    fun `accepts a compound archive extension`() {
        assertThat(BackupFileFormat.isSupportedBackupFileName("book.gnca.gz")).isTrue()
        assertThat(BackupFileFormat.isSupportedBackupFileName("book.gnca.zip")).isTrue()
        assertThat(BackupFileFormat.isSupportedBackupFileName("book.gnucash.gz")).isTrue()
        assertThat(BackupFileFormat.isSupportedBackupFileName("book.gnucash.zip")).isTrue()
    }

    @Test
    fun `names with spaces and dots are still accepted`() {
        assertThat(BackupFileFormat.isSupportedBackupFileName("my book 2026-10-11.gnca")).isTrue()
    }

    @Test
    fun `surrounding whitespace is ignored`() {
        assertThat(BackupFileFormat.isSupportedBackupFileName("  book.gnca  ")).isTrue()
    }

    @Test
    fun `rejects null blank and empty names`() {
        assertThat(BackupFileFormat.isSupportedBackupFileName(null)).isFalse()
        assertThat(BackupFileFormat.isSupportedBackupFileName("")).isFalse()
        assertThat(BackupFileFormat.isSupportedBackupFileName("   ")).isFalse()
    }

    @Test
    fun `rejects an unsupported extension`() {
        assertThat(BackupFileFormat.isSupportedBackupFileName("statement.csv")).isFalse()
        assertThat(BackupFileFormat.isSupportedBackupFileName("book.xac")).isFalse()
        assertThat(BackupFileFormat.isSupportedBackupFileName("photo.jpg")).isFalse()
    }

    @Test
    fun `rejects a name that only contains a supported extension`() {
        assertThat(BackupFileFormat.isSupportedBackupFileName("gnucash.gnca.bak")).isFalse()
        assertThat(BackupFileFormat.isSupportedBackupFileName("archive.gnca.txt")).isFalse()
    }

    @Test
    fun `rejects a name without extension`() {
        assertThat(BackupFileFormat.isSupportedBackupFileName("book")).isFalse()
        assertThat(BackupFileFormat.isSupportedBackupFileName("gnucash")).isFalse()
    }

    @Test
    fun `rejects a dot file named exactly like an extension`() {
        // ".gnca" is a hidden file with no base name, not a backup of a book.
        assertThat(BackupFileFormat.isSupportedBackupFileName(".gnca")).isFalse()
    }

    @Test
    fun `display extensions names both supported formats`() {
        val display = BackupFileFormat.displayExtensions()
        assertThat(display).contains(".gnca")
        assertThat(display).contains(".gnucash")
    }

    // ---------------------------------------------------------------- screen wiring

    @Test
    fun `backup settings screen contains the import backup entry`() {
        val preference = findBackupPreference(context.getString(R.string.key_import_backup_file))
        assertThat(preference).isNotNull()
    }

    @Test
    fun `the import backup entry is labelled`() {
        val preference = findBackupPreference(context.getString(R.string.key_import_backup_file))!!
        assertThat(preference.title.toString()).isNotBlank()
        assertThat(preference.summary.toString()).isNotBlank()
    }

    @Test
    fun `the unsupported format message names the supported formats`() {
        val message = context.getString(
            R.string.msg_import_backup_unsupported_format,
            BackupFileFormat.displayExtensions()
        )
        assertThat(message).contains(".gnca")
        assertThat(message).contains(".gnucash")
    }

    /**
     * Inflates the backup preferences screen and returns the preference stored under [key].
     */
    private fun findBackupPreference(key: String): Preference? {
        val manager = PreferenceManager(context)
        val screen = manager.createPreferenceScreen(context)
        manager.inflateFromResource(context, R.xml.fragment_backup_preferences, screen)
        return screen.findPreference(key)
    }
}
