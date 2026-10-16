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

import org.gnucash.android.model.AccountType

/**
 * An account as it crosses the wire (feature ② v2, plan §7.1 / §7.4).
 *
 * This is the **only** shape in which an account is compared or transferred: the merge, the
 * base snapshot, the tombstone bookkeeping and the conflict diff all speak [Map]s of these
 * keys, so "what counts as a change to an account" is decided in exactly one place.
 *
 * Pure by design — no `Cursor`, no `Account`, no Android — so the mapping can be tested
 * exhaustively without a database, and the store that *does* touch the database is left with
 * nothing but I/O.
 *
 * ### Keys are the column names
 *
 * Every key is the `accounts` column the value came from, so a field on the wire can always
 * be traced back to the row it came from. [DbLocalRecordStore] is the one place that moves
 * values in and out of those columns, and `DbLocalRecordStoreTest` pins these constants to
 * `AccountEntry` so the two cannot drift apart silently.
 *
 * ### What is deliberately *not* here
 *
 * * `full_name` — denormalised. `AccountsDbAdapter.bind` recomputes it from the parent chain
 *   on every write, so shipping it would let one device's rename conflict with itself on
 *   another (each would push a name derived from a parent the other has not seen yet).
 * * `color` / `color_code` — cosmetic, and its "unset" sentinel (`Account.DEFAULT_COLOR`)
 *   is a specific light grey that survives a hex round trip, so "never chosen" and "chose
 *   that grey" cannot be told apart afterwards.
 * * `created_at` / `modified_at` — owned by the local table: `DatabaseAdapter.bindBaseModel`
 *   binds only the uid, so a `REPLACE` always takes `DEFAULT CURRENT_TIMESTAMP`. Carrying
 *   them would promise a fidelity the write path cannot deliver.
 * * the account's *balance* — derived from its splits, which are their own record type.
 *
 * The commodity travels **twice on purpose**: `commodity_uid` is the authoritative
 * reference the row stores, and `currency_code` is the human-readable handle a peer can act
 * on even when it has no matching commodity row.
 */
data class AccountFields(
    val uid: String,
    val name: String,
    val type: String,
    val currencyCode: String,
    val commodityUid: String? = null,
    val parentUid: String? = null,
    val defaultTransferAccountUid: String? = null,
    val code: String? = null,
    val description: String? = null,
    val notes: String? = null,
    val isPlaceholder: Boolean = false,
    val isHidden: Boolean = false,
    val isFavorite: Boolean = false,
    val isTemplate: Boolean = false
) {

    /**
     * `false` for the book's ROOT account only.
     *
     * ROOT is a per-book anchor: its uid is minted by whichever device created the book and
     * every other account in the book hangs off it, so two devices can never agree on it.
     * Both the local listing and the writer treat it as device-local (see the guards in
     * [DbLocalRecordStore]) — but hidden, favourite and template accounts are ordinary user
     * data and are **not** filtered here. Dropping them quietly is how "the user hid an
     * account" turns into "the peer deleted an account".
     */
    val isSyncable: Boolean get() = type != AccountType.ROOT.name

    /** The record's identity: the GnuCash GUID, which is stable across devices. */
    val identity: SyncIdentity get() = SyncIdentity(SyncRecordType.ACCOUNT, uid)

    fun toMap(): Map<String, String> = buildMap {
        put(KEY_UID, uid)
        put(KEY_NAME, name)
        put(KEY_TYPE, type)
        put(KEY_CURRENCY_CODE, currencyCode)
        commodityUid?.let { put(KEY_COMMODITY_UID, it) }
        parentUid?.let { put(KEY_PARENT_ACCOUNT_UID, it) }
        defaultTransferAccountUid?.let { put(KEY_DEFAULT_TRANSFER_ACCOUNT_UID, it) }
        code?.let { put(KEY_CODE, it) }
        description?.let { put(KEY_DESCRIPTION, it) }
        notes?.let { put(KEY_NOTES, it) }
        // Booleans are always written, never implied by absence: otherwise `true` and
        // `false` would have the same representation (key present vs key absent would then
        // be the *only* difference), and a `true → false` edit would leave nothing behind
        // for the merge to see. Nullables above are the opposite case: "no value" is a real
        // state for them, so absence carries it.
        put(KEY_IS_PLACEHOLDER, isPlaceholder.toString())
        put(KEY_IS_HIDDEN, isHidden.toString())
        put(KEY_IS_FAVORITE, isFavorite.toString())
        put(KEY_IS_TEMPLATE, isTemplate.toString())
    }

    companion object {
        // The constants mirror `AccountEntry`, not the Kotlin property names, because the
        // wire format is the row.
        const val KEY_UID = "uid"
        const val KEY_NAME = "name"
        const val KEY_TYPE = "type"
        const val KEY_CURRENCY_CODE = "currency_code"
        const val KEY_COMMODITY_UID = "commodity_uid"
        const val KEY_PARENT_ACCOUNT_UID = "parent_account_uid"
        const val KEY_DEFAULT_TRANSFER_ACCOUNT_UID = "default_transfer_account_uid"
        const val KEY_CODE = "code"
        const val KEY_DESCRIPTION = "description"
        const val KEY_NOTES = "notes"
        const val KEY_IS_PLACEHOLDER = "is_placeholder"
        const val KEY_IS_HIDDEN = "is_hidden"
        const val KEY_IS_FAVORITE = "favorite"
        const val KEY_IS_TEMPLATE = "is_template"

        /** Every key [toMap] can emit; used to prove nothing is dropped on the way out. */
        val ALL_KEYS: Set<String> = setOf(
            KEY_UID, KEY_NAME, KEY_TYPE, KEY_CURRENCY_CODE, KEY_COMMODITY_UID,
            KEY_PARENT_ACCOUNT_UID, KEY_DEFAULT_TRANSFER_ACCOUNT_UID, KEY_CODE,
            KEY_DESCRIPTION, KEY_NOTES, KEY_IS_PLACEHOLDER, KEY_IS_HIDDEN,
            KEY_IS_FAVORITE, KEY_IS_TEMPLATE
        )

        /**
         * Decodes a field map, or `null` when it is not a usable account.
         *
         * Strict on purpose. A map without a uid, name, type or currency is not a *partial*
         * account, it is a corrupted one, and defaulting the missing parts would put a
         * guessed account into the book. Unknown extra keys are ignored, so a peer running a
         * newer build can add fields without breaking this one.
         *
         * Note that an **empty** currency code is accepted: a security (STOCK/MUTUAL)
         * account's commodity is not a currency, and refusing it here would make every such
         * account unsyncable.
         */
        fun fromMap(fields: Map<String, String>): AccountFields? {
            val uid = fields[KEY_UID]?.takeIf { it.isNotEmpty() } ?: return null
            val name = fields[KEY_NAME] ?: return null
            val type = fields[KEY_TYPE]?.takeIf { it.isNotEmpty() } ?: return null
            val currencyCode = fields[KEY_CURRENCY_CODE] ?: return null
            return AccountFields(
                uid = uid,
                name = name,
                type = type,
                currencyCode = currencyCode,
                commodityUid = fields[KEY_COMMODITY_UID]?.takeIf { it.isNotEmpty() },
                parentUid = fields[KEY_PARENT_ACCOUNT_UID]?.takeIf { it.isNotEmpty() },
                defaultTransferAccountUid =
                    fields[KEY_DEFAULT_TRANSFER_ACCOUNT_UID]?.takeIf { it.isNotEmpty() },
                code = fields[KEY_CODE],
                description = fields[KEY_DESCRIPTION],
                notes = fields[KEY_NOTES],
                isPlaceholder = fields.booleanOrFalse(KEY_IS_PLACEHOLDER),
                isHidden = fields.booleanOrFalse(KEY_IS_HIDDEN),
                isFavorite = fields.booleanOrFalse(KEY_IS_FAVORITE),
                isTemplate = fields.booleanOrFalse(KEY_IS_TEMPLATE)
            )
        }

        /**
         * Reads a flag written by [toMap], tolerating an absent key.
         *
         * A missing key means `false` (the value the local row had before the flag was ever
         * written), and anything that is not `true` is also `false`: a flag is a hint, and
         * the safe reading of a garbled hint is the one that changes nothing the user did
         * not ask for.
         */
        private fun Map<String, String>.booleanOrFalse(key: String): Boolean =
            this[key]?.trim()?.lowercase() == "true"
    }
}
