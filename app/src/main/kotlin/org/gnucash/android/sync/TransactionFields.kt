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

/**
 * A transaction **and its splits**, as they cross the wire (feature ② v2, plan §7.1 / §7.4).
 *
 * Pure by design — no `Cursor`, no `Transaction`, no Android — so the mapping can be tested
 * exhaustively without a database.
 *
 * ### Why the splits ride inside the transaction
 *
 * The other record type that could have been its own file — `SPLIT` — deliberately is not.
 * Two independent reasons, and each one alone would be enough:
 *
 *  1. **The write path replaces the whole split set.** `TransactionsDbAdapter.addRecord`
 *     inserts/replaces every split of the transaction and then sweeps away any row of that
 *     transaction whose uid is not in the set. A record whose write destroys its siblings is
 *     not a record — so a split is only ever meaningful *as part of* its transaction.
 *  2. **A split's own figures are not self-describing.** `value` is denominated in the
 *     *transaction's* commodity and `quantity` in the *account's*, both of which are resolved
 *     from other rows. A split file would have to carry its parent's commodity anyway.
 *
 * Consequently `DbLocalRecordStore` does **not** support `SPLIT` as a standalone type, and
 * says so by throwing rather than by answering "none".
 *
 * ### Keys are the column names — including the two that are named backwards
 *
 * Every transaction-level key is the `transactions` column it came from. Two of those columns
 * are legacy-named and read *inverted* against the model:
 *
 * | wire key      | column                     | model field |
 * |---------------|----------------------------|-------------|
 * | `name`        | `TransactionEntry.COLUMN_DESCRIPTION` | `Transaction.description` |
 * | `description` | `TransactionEntry.COLUMN_NOTES`       | `Transaction.notes` |
 * | `timestamp`   | `COLUMN_DATE_POSTED`       | `Transaction.datePosted` |
 * | `num`         | `COLUMN_NUMBER`            | `Transaction.number` |
 *
 * The constants below are pinned to `TransactionEntry` by a test, so the wire cannot drift
 * from the row; the Kotlin properties keep the *model's* names so this file still reads.
 *
 * Split keys are `split.<index>.<column>` and index the `splits` columns directly (no legacy
 * inversion on that table).
 *
 * ### Split order is part of the record
 *
 * `splits._id` is the only ordering the book has, and it is what the register shows. The
 * payload therefore carries an **explicit index** per split rather than relying on map order:
 * a re-ordered split list is a different record, and the decode refuses an index set that is
 * not exactly `0..n-1` so a gap cannot silently drop a split.
 *
 * ### What is deliberately *not* here
 *
 *  * `is_exported` — device-local export bookkeeping ("this device already wrote it out").
 *    Shipping it would let one device's export suppress the other device's, and the flag
 *    changes on every export, so the merge would see edits nobody made.
 *  * `scheduled_action_uid` (transaction) and `sched_account_uid` (split) — references into
 *    `scheduled_actions`, a record type that does **not** participate in this merge. Both
 *    columns carry a real foreign key, so shipping a peer's uid would either fail the insert
 *    or silently null the link. A pulled transaction therefore arrives unlinked from any
 *    schedule; that is the honest outcome, because the schedule is not on this device either.
 *  * split `transaction_uid` — it is the record's own identity, so shipping it would only
 *    create a second place for it to disagree with itself.
 *  * split `created_at` / `modified_at` — owned by the local table (`SplitsDbAdapter`'s bind
 *    does not carry them, so a `REPLACE` always takes the column default).
 *
 * `created_at` on the *transaction* **is** shipped, unlike on an account. That column is not
 * merely a row timestamp here: `TransactionEntry.COLUMN_DATE_ENTERED` *is*
 * `CommonColumns.COLUMN_CREATED_AT`, so it holds the GnuCash `date-entered` — real book data,
 * and unlike the account case it is bound explicitly (`TransactionsDbAdapter.bind`) and
 * therefore survives the write. `modified_at` is still local-owned.
 */
data class TransactionFields(
    val uid: String,
    val description: String,
    val notes: String,
    val datePosted: Long,
    val createdAt: Long,
    val currencyCode: String,
    val commodityUid: String,
    val number: String,
    val isTemplate: Boolean = false,
    val splits: List<SplitFields> = emptyList()
) {

    /**
     * `true` when this transaction is part of the merge at all.
     *
     * Template transactions are scheduled-transaction scaffolding: they exist to be copied by
     * a `scheduled_actions` row, and every one of them is created as a *fresh* uid (see
     * `TransactionFormFragment`), so a synced transaction can never be turned into a template
     * in place — which means leaving them out cannot be mistaken for "the user deleted it".
     * They also carry exactly the `scheduled_action_uid` foreign key this format refuses to
     * ship. So they are excluded from the listing and refused on write.
     */
    val isSyncable: Boolean get() = !isTemplate

    /** The record's identity: the GnuCash GUID, which is stable across devices. */
    val identity: SyncIdentity get() = SyncIdentity(SyncRecordType.TRANSACTION, uid)

    fun toMap(): Map<String, String> = buildMap {
        put(KEY_UID, uid)
        put(KEY_DESCRIPTION, description)
        put(KEY_NOTES, notes)
        put(KEY_DATE_POSTED, datePosted.toString())
        put(KEY_CREATED_AT, createdAt.toString())
        put(KEY_CURRENCY_CODE, currencyCode)
        put(KEY_COMMODITY_UID, commodityUid)
        put(KEY_NUMBER, number)
        // Always written, never implied by absence: `true` and `false` would otherwise share a
        // representation and a `true → false` edit would leave nothing for the merge to see.
        put(KEY_IS_TEMPLATE, isTemplate.toString())
        splits.forEachIndexed { index, split ->
            val prefix = splitPrefix(index)
            put(prefix + SplitFields.KEY_UID, split.uid)
            put(prefix + SplitFields.KEY_TYPE, split.type)
            put(prefix + SplitFields.KEY_VALUE_NUM, split.valueNum.toString())
            put(prefix + SplitFields.KEY_VALUE_DENOM, split.valueDenom.toString())
            put(prefix + SplitFields.KEY_QUANTITY_NUM, split.quantityNum.toString())
            put(prefix + SplitFields.KEY_QUANTITY_DENOM, split.quantityDenom.toString())
            put(prefix + SplitFields.KEY_MEMO, split.memo)
            put(prefix + SplitFields.KEY_ACCOUNT_UID, split.accountUid)
            put(prefix + SplitFields.KEY_RECONCILE_STATE, split.reconcileState)
            split.reconciledDate?.let { put(prefix + SplitFields.KEY_RECONCILE_DATE, it.toString()) }
        }
    }

    companion object {
        // Mirrors `TransactionEntry`, not the Kotlin property names — see the class KDoc for
        // the two inverted ones (`name` / `description`).
        const val KEY_UID = "uid"
        const val KEY_DESCRIPTION = "name"
        const val KEY_NOTES = "description"
        const val KEY_DATE_POSTED = "timestamp"
        const val KEY_CREATED_AT = "created_at"
        const val KEY_CURRENCY_CODE = "currency_code"
        const val KEY_COMMODITY_UID = "commodity_uid"
        const val KEY_NUMBER = "num"
        const val KEY_IS_TEMPLATE = "is_template"

        /** Every transaction-level key [toMap] can emit. */
        val ALL_KEYS: Set<String> = setOf(
            KEY_UID, KEY_DESCRIPTION, KEY_NOTES, KEY_DATE_POSTED, KEY_CREATED_AT,
            KEY_CURRENCY_CODE, KEY_COMMODITY_UID, KEY_NUMBER, KEY_IS_TEMPLATE
        )

        private const val SPLIT_ROOT = "split"
        private const val SPLIT_SEPARATOR = '.'

        /** The `split.<index>.` prefix a split's keys carry. */
        fun splitPrefix(index: Int): String = "$SPLIT_ROOT$SPLIT_SEPARATOR$index$SPLIT_SEPARATOR"

        /**
         * Whether [key] belongs to this format at all.
         *
         * Used by the store to compare a payload against what it would write: unknown keys are
         * dropped from that comparison, so a peer running a newer build can add a field —
         * transaction-level *or* inside a split — without this build refusing every one of its
         * transactions. A well-formed split key whose *field* is not one of
         * [SplitFields.ALL_KEYS] is therefore **not** declared.
         */
        fun isDeclaredKey(key: String): Boolean {
            if (key in ALL_KEYS) return true
            val field = parseSplitKey(key)?.second ?: return false
            return field in SplitFields.ALL_KEYS
        }

        /** The declared keys in [fields], in no particular order. */
        fun declaredSubset(fields: Map<String, String>): Map<String, String> =
            fields.filterKeys { isDeclaredKey(it) }

        /**
         * Decodes a field map, or `null` when it is not a usable transaction.
         *
         * Strict on purpose. A map without a uid, description, date, commodity or split list is
         * not a *partial* transaction, it is a corrupted one, and defaulting the missing parts
         * would put a guessed transaction into the book. Unknown extra keys are ignored.
         */
        fun fromMap(fields: Map<String, String>): TransactionFields? {
            val uid = fields[KEY_UID]?.takeIf { it.isNotEmpty() } ?: return null
            val description = fields[KEY_DESCRIPTION] ?: return null
            val notes = fields[KEY_NOTES] ?: return null
            val datePosted = fields[KEY_DATE_POSTED]?.toLongOrNull() ?: return null
            val createdAt = fields[KEY_CREATED_AT]?.toLongOrNull() ?: return null
            val currencyCode = fields[KEY_CURRENCY_CODE] ?: return null
            val commodityUid = fields[KEY_COMMODITY_UID]?.takeIf { it.isNotEmpty() } ?: return null
            val number = fields[KEY_NUMBER] ?: return null
            val splits = decodeSplits(fields) ?: return null
            return TransactionFields(
                uid = uid,
                description = description,
                notes = notes,
                datePosted = datePosted,
                createdAt = createdAt,
                currencyCode = currencyCode,
                commodityUid = commodityUid,
                number = number,
                isTemplate = fields[KEY_IS_TEMPLATE]?.trim()?.lowercase() == "true",
                splits = splits
            )
        }

        /**
         * Groups the `split.*` keys by index and requires the indices to be exactly `0..n-1`.
         *
         * A missing index is not a smaller transaction: the split that lived there would
         * vanish, and (worse) the ones after it would be renumbered on the next write, so the
         * record would never match its own base again. Refusing is the only reading that does
         * not quietly drop a leg of a double entry.
         */
        private fun decodeSplits(fields: Map<String, String>): List<SplitFields>? {
            val byIndex = LinkedHashMap<Int, MutableMap<String, String>>()
            for ((key, value) in fields) {
                val index = splitIndexOf(key) ?: continue
                val field = splitFieldOf(key) ?: continue
                byIndex.getOrPut(index) { LinkedHashMap() }[field] = value
            }
            if (byIndex.isEmpty()) return emptyList()
            val ordered = ArrayList<SplitFields>(byIndex.size)
            for (index in 0 until byIndex.size) {
                val split = byIndex[index] ?: return null
                ordered += splitFrom(split) ?: return null
            }
            return ordered
        }

        private fun splitFrom(fields: Map<String, String>): SplitFields? {
            val uid = fields[SplitFields.KEY_UID]?.takeIf { it.isNotEmpty() } ?: return null
            val type = fields[SplitFields.KEY_TYPE]?.takeIf { it.isNotEmpty() } ?: return null
            val valueNum = fields[SplitFields.KEY_VALUE_NUM]?.toLongOrNull() ?: return null
            val valueDenom = fields[SplitFields.KEY_VALUE_DENOM]?.toLongOrNull() ?: return null
            val quantityNum = fields[SplitFields.KEY_QUANTITY_NUM]?.toLongOrNull() ?: return null
            val quantityDenom =
                fields[SplitFields.KEY_QUANTITY_DENOM]?.toLongOrNull() ?: return null
            val memo = fields[SplitFields.KEY_MEMO] ?: return null
            val accountUid =
                fields[SplitFields.KEY_ACCOUNT_UID]?.takeIf { it.isNotEmpty() } ?: return null
            val reconcileState = fields[SplitFields.KEY_RECONCILE_STATE]
                ?.takeIf { it.length == 1 } ?: return null
            // A denominator of zero (or a negative one) is not a small amount, it is a division
            // by zero waiting to happen: `Money` divides by it, and `computeSplitBalances`
            // filters on `quantity_denom > 0`. Refuse rather than let it reach the database.
            if (valueDenom <= 0 || quantityDenom <= 0) return null
            return SplitFields(
                uid = uid,
                type = type,
                valueNum = valueNum,
                valueDenom = valueDenom,
                quantityNum = quantityNum,
                quantityDenom = quantityDenom,
                memo = memo,
                accountUid = accountUid,
                reconcileState = reconcileState,
                reconciledDate = fields[SplitFields.KEY_RECONCILE_DATE]?.toLongOrNull()
            )
        }

        /**
         * Parses `split.<index>.<field>` into its two halves, or `null` for anything else.
         *
         * Deliberately one function rather than two: an index and a field have to come from the
         * *same* successful parse, otherwise `split..uid` would be "declared" to the drift audit
         * while carrying no index, and the two answers would disagree about the same key.
         */
        private fun parseSplitKey(key: String): Pair<Int, String>? {
            if (!key.startsWith(SPLIT_ROOT)) return null
            val rest = key.substring(SPLIT_ROOT.length)
            if (rest.isEmpty() || rest[0] != SPLIT_SEPARATOR) return null
            val body = rest.substring(1)
            val index = body.substringBefore(SPLIT_SEPARATOR).toIntOrNull() ?: return null
            val field = body.substringAfter(SPLIT_SEPARATOR, "")
            if (field.isEmpty() || field.contains(SPLIT_SEPARATOR)) return null
            return index to field
        }

        private fun splitIndexOf(key: String): Int? = parseSplitKey(key)?.first

        private fun splitFieldOf(key: String): String? = parseSplitKey(key)?.second
    }
}

/**
 * One split as it crosses the wire (feature ② v2, plan §7.1).
 *
 * Amounts travel as the raw GnuCash fraction (`num`/`denom`) rather than as a decimal, because
 * that is what the rows hold and a decimal round trip is not guaranteed to land back on the
 * same fraction. `value` is denominated in the transaction's commodity and `quantity` in the
 * split's account's — neither is on the wire, because both are resolved from other records and
 * a second copy of a derivable value is a second place for the truth to disagree.
 *
 * [reconciledDate] is nullable and omitted when unset, mirroring the `NEVER` sentinel: that
 * sentinel is `Long.MIN_VALUE`, which does not survive a round trip through the UTC text
 * timestamp the column stores, so "no date" has to be spelled as *absence*.
 */
data class SplitFields(
    val uid: String,
    val type: String,
    val valueNum: Long,
    val valueDenom: Long,
    val quantityNum: Long,
    val quantityDenom: Long,
    val memo: String,
    val accountUid: String,
    val reconcileState: String,
    val reconciledDate: Long? = null
) {
    companion object {
        // These mirror `SplitEntry` exactly — that table has no legacy renaming.
        const val KEY_UID = "uid"
        const val KEY_TYPE = "type"
        const val KEY_VALUE_NUM = "value_num"
        const val KEY_VALUE_DENOM = "value_denom"
        const val KEY_QUANTITY_NUM = "quantity_num"
        const val KEY_QUANTITY_DENOM = "quantity_denom"
        const val KEY_MEMO = "memo"
        const val KEY_ACCOUNT_UID = "account_uid"
        const val KEY_RECONCILE_STATE = "reconcile_state"
        const val KEY_RECONCILE_DATE = "reconcile_date"

        /** Every field a split's keys can name; the split half of the wire format. */
        val ALL_KEYS: Set<String> = setOf(
            KEY_UID, KEY_TYPE, KEY_VALUE_NUM, KEY_VALUE_DENOM, KEY_QUANTITY_NUM,
            KEY_QUANTITY_DENOM, KEY_MEMO, KEY_ACCOUNT_UID, KEY_RECONCILE_STATE,
            KEY_RECONCILE_DATE
        )
    }
}
