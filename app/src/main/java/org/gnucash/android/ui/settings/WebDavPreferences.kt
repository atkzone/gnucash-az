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
package org.gnucash.android.ui.settings

import android.content.Context
import androidx.core.content.edit
import androidx.preference.PreferenceManager
import org.gnucash.android.R
import org.gnucash.android.sync.webdav.WebDavConnection

/**
 * The WebDAV connection as the user typed it, stored on the device.
 *
 * ### What is stored, and what deliberately is not
 *
 * **Stored:** address, user name, password, and the two transport switches. This is the same
 * shape (and the same plain storage) the ownCloud connection has used for years — see
 * [OwnCloudPreferences]. Keeping it identical avoids a second, differently-behaved credential
 * store in the same settings page.
 *
 * The store is the **default** shared preferences rather than a file of its own, because that
 * is the file `androidx.preference` writes to when the user edits one of those rows. A private
 * file would silently hold a *copy* that the widgets never update — the classic "I changed the
 * address and it still connects to the old one".
 *
 * **Not stored: the backup passphrase.** It is typed per action and never written to disk.
 * Persisting it would mean the "encrypted" remote file is protected by a secret that sits in
 * the same app-private storage as the book itself, which defeats the point of encrypting the
 * remote copy.
 *
 * Nothing here validates: [connection] produces a [WebDavConnection] whose `check()` is the
 * single arbiter of whether the fields are usable. Storing a half-typed address is allowed on
 * purpose — the settings page is where the user is *busy* typing one.
 */
class WebDavPreferences(private val context: Context) {

    private val preferences = PreferenceManager.getDefaultSharedPreferences(context)

    private val keyServerUrl by lazy { context.getString(R.string.key_webdav_server_url) }
    private val keyUsername by lazy { context.getString(R.string.key_webdav_username) }
    private val keyPassword by lazy { context.getString(R.string.key_webdav_password) }
    private val keyTrustAll by lazy { context.getString(R.string.key_webdav_trust_all) }
    private val keyAllowPlaintext by lazy { context.getString(R.string.key_webdav_allow_plaintext) }
    private val keyTwoWaySync by lazy { context.getString(R.string.key_webdav_two_way_sync) }

    var serverUrl: String?
        get() = preferences.getString(keyServerUrl, null)
        set(value) = preferences.edit { putString(keyServerUrl, value) }

    var username: String?
        get() = preferences.getString(keyUsername, null)
        set(value) = preferences.edit { putString(keyUsername, value) }

    var password: String?
        get() = preferences.getString(keyPassword, null)
        set(value) = preferences.edit { putString(keyPassword, value) }

    var trustAllCertificates: Boolean
        get() = preferences.getBoolean(keyTrustAll, false)
        set(value) = preferences.edit { putBoolean(keyTrustAll, value) }

    var allowPlaintext: Boolean
        get() = preferences.getBoolean(keyAllowPlaintext, false)
        set(value) = preferences.edit { putBoolean(keyAllowPlaintext, value) }

    /**
     * Whether the user has agreed to **merge** with this address, not merely to back up to it.
     *
     * Off by default, and deliberately not derived from "an address has been filled in": the two
     * directions write to different places (a single `/backup/<bookUid>.gnca` file versus one
     * file per record under `/<type>/<key>.gncs`), and one of them can delete records on the
     * peer. Deriving it from `isConfigured` would let a user who only ever wanted an encrypted
     * backup find their records being removed by someone else's phone.
     */
    var twoWaySync: Boolean
        get() = preferences.getBoolean(keyTwoWaySync, false)
        set(value) = preferences.edit { putBoolean(keyTwoWaySync, value) }

    /** `true` once an address has been saved — used to decide what the page offers. */
    val isConfigured: Boolean
        get() = !serverUrl.isNullOrBlank()

    /**
     * The connection to hand to [org.gnucash.android.sync.WebDavSyncController].
     *
     * A blank address becomes an **empty string** rather than `null` so it reaches
     * `check()` as [WebDavConnection.Problem.BLANK] and produces the "fill in the address"
     * message — a `null` would have to be special-cased by every caller.
     *
     * The password is copied into a fresh `CharArray` on every call: the array is what the
     * caller can zero afterwards, and handing out a shared one would make that a race.
     */
    fun connection(): WebDavConnection = WebDavConnection(
        serverUrl = serverUrl.orEmpty(),
        username = username,
        password = password?.toCharArray(),
        trustAllCertificates = trustAllCertificates,
        allowPlaintext = allowPlaintext
    )
}
