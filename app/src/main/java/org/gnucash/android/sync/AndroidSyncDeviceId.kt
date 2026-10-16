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
package org.gnucash.android.sync

import android.content.Context
import androidx.core.content.edit
import androidx.preference.PreferenceManager
import org.gnucash.android.R
import java.util.UUID

/**
 * This **installation's** identity, stamped onto every tombstone the device writes.
 *
 * ### Why it is not the book, and not the account
 *
 * A tombstone says "this record was deleted". For a conflict centre to explain *anything* later
 * (who removed it, and whether the other side ever saw the deletion), that marker has to name
 * the device it came from. The book uid cannot serve: two installations of the same book are
 * exactly the case this feature is for. A user-chosen name cannot either: it is the field most
 * likely to be left blank, and a blank attribution reads as "unknown device" forever.
 *
 * ### Why it must not silently change
 *
 * The value is generated once and kept. If it changed, every tombstone already written would
 * point at a device that no longer exists, and a later "did you delete this or did I?" question
 * would answer "neither". So this is *read-or-create*, never *regenerate* — including after an
 * app update, which is why it lives in the app's own preferences rather than in the book.
 *
 * It deliberately reads the **default** shared preferences: the same file `androidx.preference`
 * writes to from the settings page. A private file would be a second store whose lifetime is
 * unclear (see the K21 note in the environment document).
 */
object AndroidSyncDeviceId {

    /**
     * The stored id, generating one on first use.
     *
     * An **empty** stored value is treated as absent rather than returned: a blank id is worse
     * than a fresh one, because it would be written into tombstones as "attributed to nobody"
     * and could never be repaired.
     */
    fun of(context: Context): String {
        val store = PreferenceManager.getDefaultSharedPreferences(context)
        val key = context.getString(R.string.key_sync_device_id)
        store.getString(key, null)?.takeIf { it.isNotBlank() }?.let { return it }

        val fresh = UUID.randomUUID().toString()
        store.edit { putString(key, fresh) }
        return fresh
    }
}
