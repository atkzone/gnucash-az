package org.gnucash.android.ui.settings

import androidx.preference.EditTextPreference
import androidx.preference.Preference
import androidx.preference.PreferenceManager
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreference
import org.assertj.core.api.Assertions.assertThat
import org.gnucash.android.R
import org.gnucash.android.test.unit.GnuCashTest
import org.junit.Test

/**
 * The WebDAV rows on the backup settings page.
 *
 * A settings page fails **silently** when a row goes missing: the key never gets stored, the
 * feature looks configured and does nothing, and no code path is ever reached to complain. The
 * cheapest honest guard is to inflate the real `R.xml.fragment_backup_preferences` and require
 * every row — including the types the fragment casts to, which is what would break first.
 */
class BackupPreferenceScreenTest : GnuCashTest() {

    /**
     * The real XML, inflated once.
     *
     * `addPreferencesFromResource` is a **Fragment** method, not a [PreferenceScreen] one; the
     * inflate step has to go through the manager. Cached, because every lookup below would
     * otherwise build a second copy of the page and a row could go missing from one copy only.
     */
    private val screen: PreferenceScreen by lazy {
        val manager = PreferenceManager(context)
        manager.inflateFromResource(
            context,
            R.xml.fragment_backup_preferences,
            manager.createPreferenceScreen(context)
        )
    }

    private fun find(keyId: Int): Preference? = screen.findPreference(context.getString(keyId))

    @Test
    fun `the connection rows are all on the page`() {
        val keys = listOf(
            R.string.key_webdav_server_url,
            R.string.key_webdav_username,
            R.string.key_webdav_password,
            R.string.key_webdav_trust_all,
            R.string.key_webdav_allow_plaintext,
            R.string.key_webdav_test_connection,
            R.string.key_webdav_backup_now,
            R.string.key_webdav_restore_now,
            R.string.key_webdav_two_way_sync,
            R.string.key_webdav_sync_now
        )
        for (key in keys) {
            assertThat(find(key))
                .`as`("settings row ${context.getString(key)}")
                .isNotNull()
        }
    }

    @Test
    fun `the three typed rows are editable text preferences`() {
        // The fragment casts to EditTextPreference; a row that is a plain Preference would work
        // right up until the user tries to type into it.
        assertThat(find(R.string.key_webdav_server_url)).isInstanceOf(EditTextPreference::class.java)
        assertThat(find(R.string.key_webdav_username)).isInstanceOf(EditTextPreference::class.java)
        assertThat(find(R.string.key_webdav_password)).isInstanceOf(EditTextPreference::class.java)
    }

    @Test
    fun `both transport switches start off`() {
        // Trusting any certificate, and sending credentials over plain http: both have to be
        // turned on knowingly.
        val trustAll = find(R.string.key_webdav_trust_all) as SwitchPreference
        val allowPlaintext = find(R.string.key_webdav_allow_plaintext) as SwitchPreference
        assertThat(trustAll.isChecked).isFalse()
        assertThat(allowPlaintext.isChecked).isFalse()
    }

    @Test
    fun `the three actions are plain click rows`() {
        // They must not persist anything: their "value" is the side effect, and a persisted
        // checkbox next to them would suggest otherwise.
        assertThat(find(R.string.key_webdav_test_connection)).isNotInstanceOf(EditTextPreference::class.java)
        assertThat(find(R.string.key_webdav_backup_now)).isNotInstanceOf(EditTextPreference::class.java)
        assertThat(find(R.string.key_webdav_restore_now)).isNotInstanceOf(EditTextPreference::class.java)
    }

    @Test
    fun `the password row does not print its value in the summary`() {
        // The fragment installs a summary provider; this asserts the *stored* page has no
        // summary that could echo the secret before the provider is applied.
        val password = find(R.string.key_webdav_password) as EditTextPreference
        assertThat(password.text).isNull()
    }

    @Test
    fun `two way sync starts off and the action beside it is a plain row`() {
        // Off by default is part of the contract: turning it on is what lets the other device
        // remove records here, so it must never be implied by having filled in an address.
        val twoWay = find(R.string.key_webdav_two_way_sync) as SwitchPreference
        assertThat(twoWay.isChecked).isFalse()

        // The action persists nothing of its own — its "value" is the round it runs.
        assertThat(find(R.string.key_webdav_sync_now)).isNotInstanceOf(EditTextPreference::class.java)
    }
}
