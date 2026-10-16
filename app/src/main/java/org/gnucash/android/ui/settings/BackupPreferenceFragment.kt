/*
 * Copyright (c) 2012 Ngewi Fet <ngewif@gmail.com>
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
package org.gnucash.android.ui.settings

import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.DialogInterface
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.EditText
import androidx.core.content.edit
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.SwitchPreference
import androidx.preference.TwoStatePreference
import org.gnucash.android.BuildConfig
import org.gnucash.android.R
import org.gnucash.android.app.GnuCashApplication.Companion.activeBookUID
import org.gnucash.android.app.GnuCashApplication.Companion.getBookPreferences
import org.gnucash.android.app.GnuCashApplication.Companion.shouldBackupForImport
import org.gnucash.android.app.takePersistableUriPermission
import org.gnucash.android.db.adapter.AccountsDbAdapter
import org.gnucash.android.db.adapter.BooksDbAdapter
import org.gnucash.android.db.adapter.SplitsDbAdapter
import org.gnucash.android.db.adapter.SyncStateDbAdapter
import org.gnucash.android.db.adapter.TransactionsDbAdapter
import org.gnucash.android.export.DropboxHelper.authenticateDropbox
import org.gnucash.android.export.DropboxHelper.deleteDropboxToken
import org.gnucash.android.export.DropboxHelper.hasDropboxToken
import org.gnucash.android.export.ExportFormat
import org.gnucash.android.export.Exporter.Companion.getExportTime
import org.gnucash.android.export.Exporter.Companion.sanitizeFilename
import org.gnucash.android.importer.ImportAsyncTask
import org.gnucash.android.sync.AndroidBookTransfer
import org.gnucash.android.sync.AndroidSyncDeviceId
import org.gnucash.android.sync.ConflictReview
import org.gnucash.android.sync.DbLocalRecordStore
import org.gnucash.android.sync.PassphraseDecision
import org.gnucash.android.sync.PassphraseEntry
import org.gnucash.android.sync.RemotePassphrasePolicy
import org.gnucash.android.sync.SyncOutcome
import org.gnucash.android.sync.SyncRunner
import org.gnucash.android.sync.WebDavSyncController
import org.gnucash.android.sync.WebDavSyncResult
import org.gnucash.android.sync.webdav.WebDavConnection
import org.gnucash.android.sync.webdav.WebDavOkHttpTransport
import org.gnucash.android.sync.webdav.WebDavTransportFactory
import org.gnucash.android.ui.settings.dialog.OwnCloudDialogFragment
import org.gnucash.android.ui.snackLong
import org.gnucash.android.util.BackupFileFormat
import org.gnucash.android.util.BackupManager
import org.gnucash.android.util.BackupManager.backupActiveBookAsync
import org.gnucash.android.util.chooseContent
import org.gnucash.android.util.getDocumentName
import org.joda.time.format.DateTimeFormat
import timber.log.Timber

/**
 * Fragment for displaying general preferences
 *
 * @author Ngewi Fet <ngewif@gmail.com>
 */
class BackupPreferenceFragment : GnuPreferenceFragment() {
    override val titleId: Int = R.string.title_backup_prefs

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        addPreferencesFromResource(R.xml.fragment_backup_preferences)

        val context = requireContext()

        if (BuildConfig.DEBUG) {
            val deleteTransactionBackup =
                findPreference<SwitchPreference>(getString(R.string.key_delete_transaction_backup))!!
            deleteTransactionBackup.isChecked = false

            val importBookBackup =
                findPreference<SwitchPreference>(getString(R.string.key_import_book_backup))!!
            importBookBackup.isChecked = false
        }

        //if we are returning from DropBox authentication, save the key which was generated
        var preference = findPreference<Preference>(getString(R.string.key_default_export_email))!!
        if (preference.summaryProvider == null) {
            preference.setSummaryProvider { preference ->
                val textPreference = preference as EditTextPreference
                val email = textPreference.text
                if (email.isNullOrEmpty() || email.trim().isEmpty()) {
                    return@setSummaryProvider getString(R.string.summary_default_export_email)
                }
                email
            }
        }

        val keyDefaultExportFormat = getString(R.string.key_default_export_format)
        preference = findPreference(keyDefaultExportFormat)!!
        if (preference.summaryProvider == null) {
            preference.setSummaryProvider { preference ->
                val listPreference = preference as ListPreference
                val value = listPreference.value
                if (value.isNullOrEmpty()) {
                    return@setSummaryProvider getString(R.string.summary_default_export_format)
                }
                val format = ExportFormat.of(value)
                getString(format.labelId)
            }
        }

        preference = findPreference(getString(R.string.key_restore_backup))!!
        preference.setOnPreferenceClickListener { _ ->
            restoreBackup()
            true
        }

        preference = findPreference(getString(R.string.key_import_backup_file))!!
        preference.setOnPreferenceClickListener { _ ->
            // `.gnca` / `.gnucash` have no registered MIME type, so the picker is opened for
            // any file (see DocumentExt.chooseContent) and the name is validated afterwards.
            // Filtering by MIME type here would hide the very files the user has to select.
            chooseContent(REQUEST_IMPORT_BACKUP_FILE)
            true
        }

        preference = findPreference(getString(R.string.key_create_backup))!!
        preference.setOnPreferenceClickListener { _ ->
            val activity = activity ?: return@setOnPreferenceClickListener false
            backupActiveBookAsync(activity) { result: Boolean ->
                val msg =
                    if (result) R.string.toast_backup_successful else R.string.toast_backup_failed
                snackLong(msg)
            }
            true
        }

        preference = findPreference(getString(R.string.key_backup_location))!!
        preference.setOnPreferenceClickListener { _ ->
            val bookName = BooksDbAdapter.instance.activeBookDisplayName
            val fileName =
                sanitizeFilename(bookName) + "_" + getString(R.string.label_backup_filename)

            val createIntent = Intent(Intent.ACTION_CREATE_DOCUMENT)
                .setType(BackupManager.MIME_TYPE)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .putExtra(Intent.EXTRA_TITLE, fileName)
            try {
                startActivityForResult(createIntent, REQUEST_BACKUP_FILE)
            } catch (e: ActivityNotFoundException) {
                Timber.e(e, "Cannot create document for backup")
                snackLong(R.string.toast_install_file_manager)
            }
            true
        }

        val defaultBackupLocation = BackupManager.getBookBackupFileUri(context, activeBookUID!!)
        if (defaultBackupLocation != null) {
            preference.summary = defaultBackupLocation.getDocumentName(context)
        }

        var switch = findPreference<TwoStatePreference>(getString(R.string.key_dropbox_sync))!!
        switch.setOnPreferenceClickListener { _ ->
            toggleDropboxSync(switch)
            toggleDropboxPreference(switch)
            false
        }
        toggleDropboxPreference(switch)

        switch = findPreference(getString(R.string.key_owncloud_sync))!!
        switch.setOnPreferenceClickListener { _ ->
            toggleOwnCloudSync(switch)
            toggleOwnCloudEnabled(switch)
            false
        }
        toggleOwnCloudEnabled(switch)

        setupWebDav()
    }

    // ------------------------------------------------------------------ WebDAV (② v1)

    /**
     * The WebDAV connection as the user typed it.
     *
     * A getter rather than a field: the store is the default shared preferences, so a value
     * captured at create time would go stale the moment the user edits a row.
     */
    private val webDavPreferences: WebDavPreferences
        get() = WebDavPreferences(requireContext())

    /**
     * Wires the WebDAV rows (plan §5② step 2 / §8: "put my book up" / "bring it down").
     *
     * The address, user name and password rows persist themselves (`androidx.preference`), and
     * [WebDavPreferences] reads that same store — there is no second copy to keep in sync.
     */
    private fun setupWebDav() {
        findPreference<EditTextPreference>(getString(R.string.key_webdav_server_url))!!
            .setSummaryProvider { preference ->
                (preference as EditTextPreference).text
                    ?.takeIf { it.isNotBlank() }
                    ?: getString(R.string.summary_webdav_server_url)
            }

        findPreference<EditTextPreference>(getString(R.string.key_webdav_username))!!
            .setSummaryProvider { preference ->
                (preference as EditTextPreference).text
                    ?.takeIf { it.isNotBlank() }
                    ?: getString(R.string.summary_webdav_username)
            }

        // The password row must never render the secret in its summary — a settings page is
        // exactly the screen someone else is most likely to be looking at.
        findPreference<EditTextPreference>(getString(R.string.key_webdav_password))!!
            .setSummaryProvider { preference ->
                if ((preference as EditTextPreference).text.isNullOrEmpty()) {
                    getString(R.string.summary_webdav_password_unset)
                } else {
                    getString(R.string.summary_webdav_password_set)
                }
            }

        findPreference<Preference>(getString(R.string.key_webdav_test_connection))!!
            .setOnPreferenceClickListener {
                testWebDavConnection()
                true
            }

        findPreference<Preference>(getString(R.string.key_webdav_backup_now))!!
            .setOnPreferenceClickListener {
                // The passphrase is asked for *before* anything is read or sent: it decides
                // what goes on the wire (§8), and cancelling must not have exported the book.
                askPassphrase(setting = true) { passphrase -> uploadToWebDav(passphrase) }
                true
            }

        findPreference<Preference>(getString(R.string.key_webdav_restore_now))!!
            .setOnPreferenceClickListener {
                askPassphrase(setting = false) { passphrase -> restoreFromWebDav(passphrase) }
                true
            }

        setupTwoWaySync()
    }

    /**
     * Wires the two-way rows.
     *
     * The switch asks before it takes effect, because it is the one setting on this page that
     * lets another device delete records here. Two facts about `androidx.preference` make the
     * confirmation expressible, and both are easy to get wrong:
     *
     *  - a change listener returning `false` **cancels** the toggle, so the dialog can be shown
     *    before anything is stored — but whether the widget redraws itself as off in the
     *    meantime is up to the implementation, so the accepting branch has to set the state
     *    itself rather than assume the row is already on;
     *  - `setChecked()` is **not** a user action and does not re-enter the change listener, which
     *    is what stops "confirm ⇒ write back ⇒ ask again" from becoming an endless dialog.
     */
    private fun setupTwoWaySync() {
        val syncNow = findPreference<Preference>(getString(R.string.key_webdav_sync_now))!!
        syncNow.setOnPreferenceClickListener {
            syncNow()
            true
        }

        val switch = findPreference<SwitchPreference>(getString(R.string.key_webdav_two_way_sync))!!
        switch.isChecked = webDavPreferences.twoWaySync

        // Turning it **off** needs no confirmation: it can only stop merges, never start them.
        switch.setOnPreferenceChangeListener { _, newValue ->
            if (newValue != true) {
                refreshSyncNowRow(syncNow)
                return@setOnPreferenceChangeListener true
            }
            confirmTwoWaySync { confirmed ->
                if (confirmed) {
                    webDavPreferences.twoWaySync = true
                    switch.isChecked = true
                    refreshSyncNowRow(syncNow)
                }
            }
            // The row must not read "on" until the answer is yes.
            false
        }
        refreshSyncNowRow(syncNow)
    }

    private fun refreshSyncNowRow(row: Preference) {
        // A live gate, not a cosmetic one: [syncNow] re-reads the store anyway, so a row that
        // somehow stayed enabled still cannot start a merge.
        row.isEnabled = webDavPreferences.twoWaySync
    }

    private fun confirmTwoWaySync(onDecided: (Boolean) -> Unit) {
        val activity = activity ?: return onDecided(false)
        AlertDialog.Builder(activity)
            .setTitle(R.string.title_webdav_two_way_sync)
            .setMessage(R.string.msg_confirm_two_way_sync)
            .setNegativeButton(R.string.btn_cancel) { _, _ -> onDecided(false) }
            .setPositiveButton(R.string.btn_ok) { _, _ -> onDecided(true) }
            .show()
    }

    private fun testWebDavConnection() {
        val connection = webDavPreferences.connection()
        if (showIfNotUsable(connection)) return

        val controller = webDavController()
        runWebDav({ controller.testConnection(connection) }) { result ->
            when (result) {
                is WebDavSyncResult.Checked -> snackLong(WebDavNoticeText.of(result.notice))
                is WebDavSyncResult.NotUsable -> showNotUsable(result.problem, result.detail)
                // testConnection has only the two shapes above; anything else would be a
                // programming error, so it is logged rather than silently toasted.
                else -> Timber.e("unexpected result from testConnection: %s", result)
            }
        }
    }

    private fun uploadToWebDav(passphrase: CharArray?) {
        val connection = webDavPreferences.connection()
        if (showIfNotUsable(connection)) return

        val controller = webDavController()
        snackLong(R.string.webdav_upload_started)
        runWebDav({ controller.backup(connection, passphrase) }) { result ->
            when (result) {
                is WebDavSyncResult.Uploaded -> snackLong(WebDavNoticeText.of(result.notice))
                is WebDavSyncResult.Failed -> snackLong(WebDavNoticeText.of(result.notice))
                is WebDavSyncResult.LocalExportFailed ->
                    snackLong(R.string.webdav_export_failed)

                is WebDavSyncResult.NotUsable -> showNotUsable(result.problem, result.detail)
                else -> Timber.e("unexpected result from backup: %s", result)
            }
        }
    }

    private fun restoreFromWebDav(passphrase: CharArray?) {
        val connection = webDavPreferences.connection()
        if (showIfNotUsable(connection)) return

        val controller = webDavController()
        runWebDav({ controller.restore(connection, passphrase) }) { result ->
            when (result) {
                is WebDavSyncResult.Downloaded -> confirmRestore(result)
                is WebDavSyncResult.Failed -> snackLong(WebDavNoticeText.of(result.notice))
                is WebDavSyncResult.NotUsable -> showNotUsable(result.problem, result.detail)
                else -> Timber.e("unexpected result from restore: %s", result)
            }
        }
    }

    // ------------------------------------------------------------------ WebDAV (② v2)

    /**
     * Runs one bidirectional round (plan §7).
     *
     * The switch is re-read here rather than trusted from the row's state: the row is a view, and
     * the row being on while the store says off is exactly the state a user reaches by turning it
     * on and backing out of a declined confirmation. The store is the only source of truth for
     * whether this device may merge with the peer.
     */
    private fun syncNow() {
        if (!webDavPreferences.twoWaySync) {
            snackLong(R.string.webdav_sync_disabled)
            return
        }
        val connection = webDavPreferences.connection()
        if (showIfNotUsable(connection)) return

        val runner = syncRunner()
        snackLong(R.string.webdav_sync_started)
        runWebDav({ runner.runOnce(connection) }) { outcome ->
            when (outcome) {
                is SyncOutcome.Ran -> showSyncOutcome(outcome)
                is SyncOutcome.NotUsable -> showNotUsable(outcome.problem, outcome.detail)
                is SyncOutcome.Failed -> snackLong(SyncNoticeText.of(outcome))
            }
        }
    }

    /**
     * Reports a round: the sentence first, then the numbers — and only when they add something.
     *
     * [SyncNotice.deservesCounts] decides that, so the page cannot drift into printing
     * "uploaded 0, downloaded 0, …" under the steady state.
     */
    private fun showSyncOutcome(outcome: SyncOutcome.Ran) {
        snackLong(SyncNoticeText.of(outcome.notice))
        val result = outcome.result
        // Conflicts come before the count line, and outside the `deservesCounts` gate: the
        // records are already parked, and "there is nothing worth counting" is not a reason
        // to hide something the user has to deal with.
        if (result.reviews.isNotEmpty()) showConflicts(result.reviews)
        if (!outcome.notice.deservesCounts) return
        snackLong(
            getString(
                R.string.webdav_sync_counts,
                result.uploaded, result.downloaded,
                result.remoteDeleted, result.localDeleted,
                result.deferred, result.failed
            )
        )
    }

    /**
     * Opens the conflict centre on the records this round parked.
     *
     * `parentFragmentManager`, like every other dialog on this screen: the preferences
     * fragment is hosted directly by [PreferenceActivity], so the two managers are the same
     * here — and the parent one is the correct choice if it ever stops being true.
     */
    private fun showConflicts(reviews: List<ConflictReview>) {
        ConflictCenterDialogFragment.newInstance(reviews)
            .show(parentFragmentManager, ConflictCenterDialogFragment.TAG)
    }

    private fun syncRunner(): SyncRunner {
        val context = requireContext()
        return SyncRunner(
            state = SyncStateDbAdapter.instance,
            store = DbLocalRecordStore(
                AccountsDbAdapter.instance,
                TransactionsDbAdapter.instance,
                SplitsDbAdapter.instance
            ),
            transportFactory = WEB_DAV_TRANSPORT,
            deviceId = AndroidSyncDeviceId.of(context)
        )
    }

    /**
     * Asks before importing, and only then writes the fetched book to disk.
     *
     * The order matters and is not cosmetic: nothing is staged until the user says yes, so
     * discarding the dialog leaves no copy of the book lying in the cache.
     */
    private fun confirmRestore(result: WebDavSyncResult.Downloaded) {
        val activity = activity ?: return
        AlertDialog.Builder(activity)
            .setTitle(R.string.title_confirm_restore_remote)
            .setMessage(R.string.msg_confirm_restore_remote)
            .setNegativeButton(R.string.btn_cancel) { _, _ ->
                // Dismisses itself. The bytes are dropped with the dialog: no staging file.
            }
            .setPositiveButton(R.string.btn_import) { _, _ ->
                snackLong(WebDavNoticeText.of(result.notice))
                val uri = AndroidBookTransfer.stageRestoredBook(activity, result.book)
                ImportAsyncTask(activity, shouldBackupForImport(activity)).execute(uri)
            }
            .show()
    }

    /**
     * Marks the offending row and says what is wrong, **without touching the network**.
     *
     * @return `true` when the connection is unusable, so the caller stops.
     */
    private fun showIfNotUsable(connection: WebDavConnection): Boolean {
        val check = connection.check()
        if (check !is WebDavConnection.Check.Invalid) return false
        showNotUsable(check.problem, check.detail)
        return true
    }

    /**
     * Marks the offending row and says what is wrong, **without touching the network**.
     *
     * Takes the typed problem rather than one feature's result type, so the one-way backup path
     * and the two-way round path explain an unusable address identically — the same eleven
     * problems, the same row highlighted.
     */
    private fun showNotUsable(problem: WebDavConnection.Problem, detail: String) {
        val message = getString(WebDavNoticeText.of(problem))
        WebDavNoticeText.preferenceKeyOf(problem)?.let { keyId ->
            findPreference<Preference>(getString(keyId))?.let { row ->
                // Put the text on the row the user has to fix, instead of a page-level error
                // they would have to map back to a field themselves.
                row.summary = message
            }
        }
        // The typed problem is what the user sees; the layer's own words are for the log.
        Timber.i("Unusable WebDAV connection: %s", detail)
        snackLong(message)
    }

    private fun webDavController(): WebDavSyncController {
        val context = requireContext()
        val bookUid = activeBookUID!!
        return WebDavSyncController(
            bookUid = bookUid,
            transportFactory = WEB_DAV_TRANSPORT,
            exportBook = { AndroidBookTransfer.exportBook(context, bookUid) }
        )
    }

    /**
     * Runs a network action off the main thread and delivers the result back on it.
     *
     * The result is dropped when the fragment is no longer attached: the action has already
     * happened by then, and touching the view would crash on the way out.
     */
    private fun <T : Any> runWebDav(work: () -> T, done: (T) -> Unit) {
        val host = activity ?: return
        Thread {
            val outcome = try {
                work()
            } catch (e: Throwable) {
                Timber.e(e, "WebDAV action threw")
                null
            }
            host.runOnUiThread {
                if (isAdded && activity === host && outcome != null) done(outcome)
            }
        }.start()
    }

    /**
     * Collects a passphrase for an upload ([setting] = `true`) or for opening an existing
     * encrypted backup.
     *
     * Only the setting direction is confirmed twice and gated on the acknowledgement: using an
     * existing passphrase cannot make anything irreversible, so asking the user to tick a box
     * there would be noise. The rules themselves live in [RemotePassphrasePolicy] and are
     * asserted there — this method only renders them.
     */
    private fun askPassphrase(setting: Boolean, onDecided: (CharArray?) -> Unit) {
        val activity = activity ?: return
        val view = LayoutInflater.from(activity)
            .inflate(R.layout.dialog_backup_passphrase, null)
        val passphrase = view.findViewById<EditText>(R.id.input_backup_passphrase)
        val confirmation = view.findViewById<EditText>(R.id.input_backup_passphrase_confirm)
        val acknowledgement =
            view.findViewById<CheckBox>(R.id.check_backup_passphrase_irreversible)

        if (!setting) {
            // Opening an existing backup: one field, no acknowledgement.
            confirmation.visibility = android.view.View.GONE
            acknowledgement.visibility = android.view.View.GONE
        }

        val dialog = AlertDialog.Builder(activity)
            .setTitle(R.string.title_backup_passphrase)
            .setView(view)
            .setNegativeButton(R.string.btn_cancel) { _, _ ->
                // Dismisses itself; the caller simply gets no passphrase and does nothing.
            }
            .setPositiveButton(R.string.btn_ok, null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener {
                val entry = PassphraseEntry(
                    passphrase = passphrase.text.toString(),
                    confirmation = if (setting) confirmation.text.toString()
                    else passphrase.text.toString(),
                    irreversibleAcknowledged = !setting || acknowledgement.isChecked
                )
                when (val decision = RemotePassphrasePolicy.decide(entry)) {
                    is PassphraseDecision.None -> {
                        dialog.dismiss()
                        onDecided(null)
                    }

                    is PassphraseDecision.Use -> {
                        dialog.dismiss()
                        onDecided(decision.passphrase)
                    }

                    // Stay open: dismissing would throw the typed passphrase away and make the
                    // user type both fields again to fix a one-character difference.
                    is PassphraseDecision.Invalid ->
                        passphrase.error = getString(WebDavNoticeText.of(decision.problem))
                }
            }
        }
        dialog.show()
    }

    /**
     * Toggles the checkbox of the DropBox Sync preference if a DropBox account is linked
     *
     * @param preference DropBox Sync preference
     */
    fun toggleDropboxPreference(preference: TwoStatePreference) {
        val context = preference.context
        preference.isChecked = hasDropboxToken(context)
    }

    /**
     * Toggles the checkbox of the ownCloud Sync preference if an ownCloud account is linked
     *
     * @param preference ownCloud Sync preference
     */
    fun toggleOwnCloudEnabled(preference: TwoStatePreference) {
        val context = preference.context
        val preferences = OwnCloudPreferences(context)
        preference.isChecked = preferences.isSync
    }

    /**
     * Toggles the authorization state of a DropBox account.
     * If a link exists, it is removed else DropBox authorization is started
     */
    private fun toggleDropboxSync(preference: TwoStatePreference) {
        val context = preference.context
        if (!hasDropboxToken(context)) {
            authenticateDropbox(context)
        } else {
            deleteDropboxToken(context)
        }
    }

    /**
     * Toggles synchronization with ownCloud on or off
     */
    private fun toggleOwnCloudSync(preference: TwoStatePreference) {
        val context = preference.context
        val preferences = OwnCloudPreferences(context)

        if (preferences.isSync) {
            preferences.isSync = false
        } else {
            OwnCloudDialogFragment.newInstance(preference)
                .show(parentFragmentManager, OwnCloudDialogFragment.TAG)
        }
    }

    /**
     * Opens a dialog for a user to select a backup to restore and then restores the backup
     */
    private fun restoreBackup() {
        Timber.i("Opening GnuCash XML backups for restore")
        val activity = activity ?: return
        val bookUID = activeBookUID

        val defaultBackupFile = BackupManager.getBookBackupFileUri(activity, bookUID!!)
        if (defaultBackupFile != null) {
            AlertDialog.Builder(activity)
                .setTitle(R.string.title_confirm_restore_backup)
                .setMessage(R.string.msg_confirm_restore_backup_into_new_book)
                .setNegativeButton(R.string.btn_cancel) { _, _ ->
                    // Dismisses itself
                }
                .setPositiveButton(R.string.btn_restore) { _, _ ->
                    ImportAsyncTask(activity).execute(defaultBackupFile)
                }
                .show()
            return  //stop here if the default backup file exists
        }

        //If no default location was set, look in the internal SD card location
        if (BackupManager.getBackupList(activity, bookUID).isEmpty()) {
            AlertDialog.Builder(activity)
                .setTitle(R.string.title_no_backups_found)
                .setMessage(R.string.msg_no_backups_to_restore_from)
                .setNegativeButton(R.string.btn_cancel) { _, _ ->
                    // Dismisses itself
                }
                .show()
            return
        }

        val adapter = ArrayAdapter<String>(activity, android.R.layout.select_dialog_singlechoice)
        val dateFormatter = DateTimeFormat.longDateTime()
        for (backupFile in BackupManager.getBackupList(activity, bookUID)) {
            val time = getExportTime(backupFile.name)
            if (time > 0) adapter.add(dateFormatter.print(time))
            else  //if no timestamp was found in the filename, just use the name
                adapter.add(backupFile.name)
        }

        AlertDialog.Builder(activity)
            .setTitle(R.string.title_select_backup_to_restore)
            .setNegativeButton(R.string.alert_dialog_cancel) { _, _ ->
                // Dismisses itself
            }
            .setAdapter(adapter) { _, which ->
                val backupFile = BackupManager.getBackupList(activity, bookUID)[which]
                ImportAsyncTask(activity).execute(Uri.fromFile(backupFile))
            }
            .show()
    }

    /**
     * Imports a book backup that the user picked in a file browser.
     *
     * The file name is validated against [BackupFileFormat] before anything is imported, so a
     * wrong pick gets an actionable message instead of the generic "error importing" toast.
     * Importing always opens a new book, hence the confirmation dialog.
     *
     * @param uri the document picked by the user
     */
    private fun importBackup(uri: Uri) {
        val activity = activity ?: return
        val fileName = uri.getDocumentName(activity)

        if (!BackupFileFormat.isSupportedBackupFileName(fileName)) {
            Timber.w("Refused to import unsupported backup file: %s", fileName)
            AlertDialog.Builder(activity)
                .setTitle(R.string.title_import_backup_unsupported_format)
                .setMessage(
                    getString(
                        R.string.msg_import_backup_unsupported_format,
                        BackupFileFormat.displayExtensions()
                    )
                )
                .setPositiveButton(R.string.btn_ok) { _, _ ->
                    // Dismisses itself
                }
                .show()
            return
        }

        AlertDialog.Builder(activity)
            .setTitle(R.string.title_confirm_import_backup)
            .setMessage(R.string.msg_confirm_import_backup_into_new_book)
            .setNegativeButton(R.string.btn_cancel) { _, _ ->
                // Dismisses itself
            }
            .setPositiveButton(R.string.btn_import) { _, _ ->
                val backup = shouldBackupForImport(activity)
                ImportAsyncTask(activity, backup).execute(uri)
            }
            .show()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        when (requestCode) {
            REQUEST_LINK_TO_DBX -> {
                // if we are in a preference header fragment, this may return null
                val preference =
                    findPreference<TwoStatePreference>(getString(R.string.key_dropbox_sync))
                        ?: return
                toggleDropboxPreference(preference)
            }

            REQUEST_BACKUP_FILE -> if (resultCode == Activity.RESULT_OK) {
                val backupFileUri: Uri = data?.data ?: return
                val context = requireContext()
                context.takePersistableUriPermission(data)

                getBookPreferences(context).edit {
                    putString(BackupManager.KEY_BACKUP_FILE, backupFileUri.toString())
                }

                val preference =
                    findPreference<Preference>(getString(R.string.key_backup_location))!!
                preference.summary = backupFileUri.getDocumentName(preference.context)
            }

            REQUEST_IMPORT_BACKUP_FILE -> if (resultCode == Activity.RESULT_OK) {
                val uri: Uri = data?.data ?: return
                importBackup(uri)
            }

            else -> super.onActivityResult(requestCode, resultCode, data)
        }
    }

    companion object {
        /**
         * Builds the OkHttp transport from the credentials `check()` just validated.
         *
         * A lambda rather than a stored instance, per
         * [WebDavConnection.Check.Ready.connect]: the transport is always built from the same
         * fields that were checked, so "a validated connection + a transport made of something
         * else" cannot be expressed.
         */
        private val WEB_DAV_TRANSPORT = WebDavTransportFactory { username, password, trustAll ->
            WebDavOkHttpTransport(username, password, trustAll)
        }

        /**
         * Collects references to the UI elements and binds click listeners
         */
        private const val REQUEST_LINK_TO_DBX = 0x11

        /**
         * Request code for the backup file where to save backups
         */
        private const val REQUEST_BACKUP_FILE = 0x13

        /**
         * Request code for picking a book backup file (`*.gnca` / `*.gnucash`) to import
         */
        private const val REQUEST_IMPORT_BACKUP_FILE = 0x14
    }
}
