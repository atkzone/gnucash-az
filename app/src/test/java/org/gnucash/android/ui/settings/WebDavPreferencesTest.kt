package org.gnucash.android.ui.settings

import androidx.core.content.edit
import androidx.preference.PreferenceManager
import org.assertj.core.api.Assertions.assertThat
import org.gnucash.android.R
import org.gnucash.android.sync.webdav.WebDavConnection
import org.gnucash.android.test.unit.GnuCashTest
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * The stored WebDAV connection.
 *
 * Two things are worth a Robolectric test here rather than a plain one:
 *
 *  - **the store has to be the same one the preference widgets write to.** A private file would
 *    hold a copy the widgets never touch, and the page would keep dialling the old address while
 *    showing the new one — `a value written by the preference widgets is what gets dialled`
 *    pins that down;
 *  - **the password must survive the round trip.** Dropping it silently produces anonymous
 *    requests that answer `401` forever, which is the exact failure `WebDavConnection` was
 *    written to make impossible.
 */
class WebDavPreferencesTest : GnuCashTest() {

    private lateinit var preferences: WebDavPreferences

    @Before
    fun setUp() {
        preferences = WebDavPreferences(context)
        clearWebDavKeys()
    }

    @After
    fun tearDown() {
        clearWebDavKeys()
    }

    /** Clears only this feature's keys, so the app's other book preferences stay untouched. */
    private fun clearWebDavKeys() {
        val store = PreferenceManager.getDefaultSharedPreferences(context)
        store.edit {
            remove(context.getString(R.string.key_webdav_server_url))
            remove(context.getString(R.string.key_webdav_username))
            remove(context.getString(R.string.key_webdav_password))
            remove(context.getString(R.string.key_webdav_trust_all))
            remove(context.getString(R.string.key_webdav_allow_plaintext))
            remove(context.getString(R.string.key_webdav_two_way_sync))
        }
    }

    @Test
    fun `an untouched page is not configured and says the address is empty`() {
        assertThat(preferences.isConfigured).isFalse()
        assertThat(preferences.serverUrl).isNull()
        assertThat(preferences.trustAllCertificates).isFalse()
        // Off by default: silently accepting any certificate is how a safe-looking sync becomes
        // trivially interceptable.
        assertThat(preferences.allowPlaintext).isFalse()

        val check = preferences.connection().check()
        assertThat((check as WebDavConnection.Check.Invalid).problem)
            .isEqualTo(WebDavConnection.Problem.BLANK)
    }

    @Test
    fun `a blank address is offered as a blank string, not as nothing`() {
        // `null` would have to be special-cased by every caller; an empty string reaches
        // check() as BLANK and produces the "fill in the address" message.
        assertThat(preferences.connection().serverUrl).isEmpty()
    }

    @Test
    fun `a filled page round trips into a usable connection`() {
        preferences.serverUrl = "https://Cloud.Example.com/Remote.php/dav/files/Me/Book/"
        preferences.username = "alice"
        preferences.password = "s3cret"
        preferences.trustAllCertificates = true

        val check = preferences.connection().check()
        assertThat(check).isInstanceOf(WebDavConnection.Check.Ready::class.java)
        val ready = check as WebDavConnection.Check.Ready

        // Host is folded, the path keeps its case, and the trailing slash is dropped: the root
        // below is the coordinate system every record path is relative to.
        assertThat(ready.baseUrl).isEqualTo("https://cloud.example.com/Remote.php/dav/files/Me/Book")
        assertThat(ready.username).isEqualTo("alice")
        assertThat(String(ready.password!!)).isEqualTo("s3cret")
        assertThat(ready.trustAllCertificates).isTrue()
        assertThat(preferences.isConfigured).isTrue()
    }

    @Test
    fun `a password without a user name is refused rather than dropped`() {
        preferences.serverUrl = "https://cloud.example.com/dav"
        preferences.password = "s3cret"

        val check = preferences.connection().check()
        assertThat((check as WebDavConnection.Check.Invalid).problem)
            .isEqualTo(WebDavConnection.Problem.PASSWORD_WITHOUT_USER)
    }

    @Test
    fun `a value written by the preference widgets is what gets dialled`() {
        // The settings rows persist through androidx.preference; if this class read a private
        // file instead, editing the address in the UI would have no effect at all.
        val store = PreferenceManager.getDefaultSharedPreferences(context)
        store.edit {
            putString(context.getString(R.string.key_webdav_server_url), "https://cloud.example.com/dav")
            putString(context.getString(R.string.key_webdav_username), "bob")
            putString(context.getString(R.string.key_webdav_password), "hunter2")
            putBoolean(context.getString(R.string.key_webdav_allow_plaintext), true)
        }

        val connection = preferences.connection()
        assertThat(connection.serverUrl).isEqualTo("https://cloud.example.com/dav")
        assertThat(connection.username).isEqualTo("bob")
        assertThat(String(connection.password!!)).isEqualTo("hunter2")
        assertThat(connection.allowPlaintext).isTrue()
    }

    @Test
    fun `each call hands out its own password array`() {
        // The array is what a caller can zero after use; a shared one would make that a race
        // that wipes the credentials out from under the next action.
        preferences.password = "s3cret"
        val first = preferences.connection().password!!
        val second = preferences.connection().password!!
        assertThat(first).isNotSameAs(second)

        first.fill('\u0000')
        assertThat(String(second)).isEqualTo("s3cret")
        assertThat(preferences.connection().password).isNotNull()
    }

    @Test
    fun `merging with the peer is an explicit opt in, not something an address implies`() {
        // A user who only ever wanted an encrypted backup must not have their records deleted
        // by another device just because they filled in an address.
        assertThat(preferences.twoWaySync).isFalse()

        preferences.twoWaySync = true
        assertThat(WebDavPreferences(context).twoWaySync).isTrue()

        // Written through the same store the switch widget writes to, so the two cannot drift.
        val store = PreferenceManager.getDefaultSharedPreferences(context)
        assertThat(store.getBoolean(context.getString(R.string.key_webdav_two_way_sync), false))
            .isTrue()
    }
}
