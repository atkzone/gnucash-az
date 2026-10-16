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

import org.gnucash.android.db.DatabaseSchema.SplitEntry
import org.gnucash.android.db.adapter.AccountsDbAdapter
import org.gnucash.android.db.adapter.SplitsDbAdapter
import org.gnucash.android.db.adapter.TransactionsDbAdapter
import org.gnucash.android.model.Account
import org.gnucash.android.model.AccountType
import org.gnucash.android.model.Commodity
import org.gnucash.android.model.Money
import org.gnucash.android.model.Split
import org.gnucash.android.model.Transaction
import org.gnucash.android.model.TransactionType
import org.gnucash.android.util.NEVER
import timber.log.Timber
import java.math.BigDecimal

/**
 * The production [LocalRecordStore]: the book's account and transaction rows (feature ② v2).
 *
 * The seam exists because the merge must be decidable without a book; this class is the other
 * end of it, where the decisions finally touch `accounts`, `transactions` and `splits`. It is
 * deliberately thin — every rule about *what* to change lives in [SyncRoundPlanner] /
 * [ThreeWayMerge], and the only judgement left here is *whether a change can be applied at all*.
 *
 * ### Two record types, and why not three
 *
 * [SyncRecordType.ACCOUNT] and [SyncRecordType.TRANSACTION] are supported.
 * [SyncRecordType.SPLIT] is **not**, and asking for it throws rather than answering "none":
 * a split's write goes through its transaction (the adapter replaces the transaction's whole
 * split set and sweeps the leftovers), so a standalone split record has no meaning. See
 * [TransactionFields] for the full reasoning.
 *
 * ### The ways a write is declined
 *
 * Each of these is a case where applying the change would be worse than waiting a round, and
 * each returns `false` (with a log) so the round leaves the record's base alone:
 *
 *  * **accounts**
 *     1. **the record is malformed or its uid disagrees with its payload** — writing it would
 *        create a row that its own base could never match again;
 *     2. **the record is a ROOT account** — a peer's root is that peer's book, not ours;
 *     3. **the commodity cannot be resolved** — `accounts` has a real foreign key on
 *        `commodity_uid`, so a guessed or dangling commodity would either be rejected by SQLite
 *        or, worse, poison every later listing (`buildModelInstance` resolves the uid back);
 *     4. **the parent account is not in the book yet** — `AccountsDbAdapter.bind` walks the
 *        parent chain to recompute `full_name`, and a dangling uid throws *mid-write*, after
 *        the statement has already been built. Hierarchy order is a fact of nature here;
 *        deferring converges in a round or two (the parent is pulled by the same round that
 *        wants the child), whereas re-parenting the account under the local root would silently
 *        flatten the hierarchy on *both* sides.
 *  * **transactions**
 *     5. **the payload is malformed, or a template** — see [TransactionFields];
 *     6. **the transaction's commodity cannot be resolved** — real foreign key, as above;
 *     7. **a split's account is not in the book yet** — `splits.account_uid` is a foreign key
 *        with `ON DELETE CASCADE` and the split's `quantity` is denominated in that account's
 *        commodity, so there is nothing to write the row against. Same convergence argument as
 *        (4): the account arrives in the same round it is needed by.
 *     8. **the splits do not balance** — `TransactionsDbAdapter.addRecord` *repairs* an
 *        unbalanced transaction by creating a split in an imbalance account, and it will
 *        **create that account** if the book has none. Adopting a peer's transaction must not
 *        conjure a locally-minted account and a locally-minted split: the record would then
 *        differ from its own base forever.
 *     9. **the payload is not what storing it would produce** — the last and most general gate.
 *        The model normalises as it goes (`Transaction.description` trims; `Money` re-derives
 *        `num`/`denom` from the commodity's decimal places; an unknown split type silently
 *        decays to DEBIT; a repeated split uid is dropped). Any of those would leave the stored
 *        record differing from the payload it was built from, so the next round would read it as
 *        a local edit and push it back — forever. Refusing an unrepresentable payload is the
 *        only honest answer, and checking it centrally beats enumerating the normalisations.
 *
 * The one case (4) used to be un-convergeable: deleting an account left its children's
 * `parent_account_uid` pointing at the vanished uid — `deleteRecord` repairs `full_name`'s
 * sibling reference (`default_transfer_account_uid`) in the database but `parent_account_uid`
 * only in the in-memory cache. `bind` walks the parent chain, so a child in that state could
 * never be written again and was deferred every round (visible in [SyncRoundResult.deferred]).
 * [delete] now repairs the rows as well: the account's descendants are re-pointed at the local
 * ROOT, which is exactly what "no parent" means on the wire (see [list]). A reference the book
 * merely does not have *yet* still defers under (4) — that one converges on its own.
 *
 * ### Delete does not cascade *by decision*
 *
 * [delete] for an account uses the plain `deleteRecord`, never `recursiveDeleteAccount`.
 * Adopting a peer's account deletion must not also destroy the transactions and sub-accounts
 * hanging off it: those are separate record types with their own tombstones, and a merge that
 * has not agreed to delete them has not agreed to delete them. (`deleteRecord` does re-parent
 * orphaned children to the local ROOT — a local repair, not a deletion.)
 *
 * That intent has to be *enforced*, because the database does not share it: `splits.account_uid`
 * is declared `ON DELETE CASCADE` and `PRAGMA foreign_keys=ON`, so removing the account row
 * would take that account's splits with it. A transaction with a leg in the account would lose
 * that leg, be left unbalanced (or with no legs at all), and could never be written again (the
 * transaction writer refuses an unbalanced payload) — an account deletion silently rewriting a
 * *different* record type. [delete] therefore **refuses while anything still posts to the
 * account**: an account is deleted only once it is unreferenced. The transactions go when their
 * own record type says so; the account's turn comes the round after.
 */
class DbLocalRecordStore(
    private val accounts: AccountsDbAdapter,
    private val transactions: TransactionsDbAdapter,
    private val splits: SplitsDbAdapter
) : LocalRecordStore {

    override val supportedTypes: Set<SyncRecordType> =
        setOf(SyncRecordType.ACCOUNT, SyncRecordType.TRANSACTION)

    // ------------------------------------------------------------------ reading

    /**
     * Every account and every non-template transaction in the book, as field maps.
     *
     * Accounts are read with `getAllRecords(null, null)` — the raw query — and **not** with
     * `AccountsDbAdapter.fetchAllRecords()`. The latter is the app's *display* query and
     * excludes hidden accounts and ROOT; using it here would make every account the user has
     * hidden look, to the merge, like an account the user deleted, and the next round would
     * delete the peer's copy of it. Only ROOT is filtered, in [AccountFields.isSyncable].
     *
     * The same reasoning excludes template transactions in [TransactionFields.isSyncable].
     *
     * Cost note: `TransactionsDbAdapter.buildModelInstance` loads each transaction's splits
     * with a query of its own, so this is one query per transaction. Sync runs are infrequent
     * and background, and correctness comes from reusing the app's own row interpretation
     * rather than from a second, hand-written one.
     */
    override fun list(types: Collection<SyncRecordType>): List<RecordState> {
        requireSupported(types)
        val out = ArrayList<RecordState>()
        if (SyncRecordType.ACCOUNT in types) {
            // The book's ROOT is a per-device anchor: its uid is minted by whichever device
            // created the book, and no other book can ever dereference it. Reading it as a real
            // parent would put it on the wire and leave **every top level account** deferred for
            // ever on the other side (`parentIsPresent` can never answer true there), so it is
            // read as "no parent" — which is what it means here, and what the writer attaches a
            // parentless account to on the far side.
            val rootUid = accounts.rootAccountUID
            out += accounts.getAllRecords(null, null)
                .map { it.toFields(rootUid) }
                .filter { it.isSyncable }
                .map { RecordState(SyncRecordType.ACCOUNT, it.uid, it.toMap()) }
        }
        if (SyncRecordType.TRANSACTION in types) {
            out += transactions.getAllRecords(null, null)
                .map { it.toTransactionFields() }
                .filter { it.isSyncable }
                .map { RecordState(SyncRecordType.TRANSACTION, it.uid, it.toMap()) }
        }
        return out
    }

    // ------------------------------------------------------------------ writing

    override fun write(state: RecordState): Boolean = when (state.type) {
        SyncRecordType.ACCOUNT -> writeAccount(state)
        SyncRecordType.TRANSACTION -> writeTransaction(state)
        else -> {
            Timber.w(
                "Refusing to write a %s record: this store only holds %s",
                state.type.id, supportedTypes.joinToString { it.id }
            )
            false
        }
    }

    private fun writeAccount(state: RecordState): Boolean {
        val fields = AccountFields.fromMap(state.fields)
        if (fields == null) {
            Timber.w("Refusing to write account %s: the payload is not a usable account", state.key)
            return false
        }
        if (fields.uid != state.key) {
            // The record's identity and its payload disagree. Writing it would file the row
            // under one uid while its base is keyed by another, so the next round would see
            // a phantom deletion on both sides.
            Timber.w("Refusing to write account %s: the payload names %s", state.key, fields.uid)
            return false
        }
        if (!fields.isSyncable) {
            Timber.w("Refusing to write the book's ROOT account (%s) from a peer", fields.uid)
            return false
        }
        val type = AccountType.entries.firstOrNull { it.name == fields.type }
        if (type == null) {
            Timber.w(
                "Refusing to write account %s: unknown account type '%s'",
                fields.uid, fields.type
            )
            return false
        }
        val commodity = resolveCommodity(fields.commodityUid, fields.currencyCode)
        if (commodity == null) {
            Timber.w(
                "Refusing to write account %s: no commodity for '%s'",
                fields.uid, fields.currencyCode
            )
            return false
        }
        if (!parentIsPresent(fields.parentUid)) {
            Timber.w(
                "Deferring account %s: its parent %s is not in the book yet",
                fields.uid, fields.parentUid
            )
            return false
        }

        val account = Account(fields.name, commodity)
        account.setUID(fields.uid)
        account.type = type
        account.parentUID = fields.parentUid
        account.defaultTransferAccountUID = fields.defaultTransferAccountUid
        account.code = fields.code
        account.description = fields.description
        account.notes = fields.notes
        account.isPlaceholder = fields.isPlaceholder
        account.isHidden = fields.isHidden
        account.isFavorite = fields.isFavorite
        account.isTemplate = fields.isTemplate
        // REPLACE on the uid: an account that is already here is updated in place rather
        // than duplicated. `full_name` is recomputed by the adapter from the parent chain.
        accounts.addRecord(account)
        return true
    }

    private fun writeTransaction(state: RecordState): Boolean {
        val fields = TransactionFields.fromMap(state.fields)
        if (fields == null) {
            Timber.w(
                "Refusing to write transaction %s: the payload is not a usable transaction",
                state.key
            )
            return false
        }
        if (fields.uid != state.key) {
            Timber.w("Refusing to write transaction %s: the payload names %s", state.key, fields.uid)
            return false
        }
        if (!fields.isSyncable) {
            Timber.w(
                "Refusing to write transaction %s: templates are not part of the merge", fields.uid
            )
            return false
        }
        val commodity = resolveCommodity(fields.commodityUid, fields.currencyCode)
        if (commodity == null) {
            Timber.w(
                "Refusing to write transaction %s: no commodity for '%s'",
                fields.uid, fields.commodityUid
            )
            return false
        }
        val transaction = buildTransaction(fields, commodity) ?: return false

        // The stored record must be the payload, or the next round reads the difference as a
        // local edit. Compared on *declared* keys only, so a peer from a newer build can carry
        // fields this one does not know without every one of its transactions being refused.
        val produced = transaction.toTransactionFields().toMap()
        if (produced != TransactionFields.declaredSubset(state.fields)) {
            Timber.w(
                "Refusing to write transaction %s: storing it would not reproduce the payload " +
                    "(the model normalises something this format cannot express)",
                fields.uid
            )
            return false
        }

        // `addRecord`'s default is `UpdateMethod.Replace`, so this is a `REPLACE INTO
        // transactions`: the existing row is deleted and re-inserted, and `splits.transaction_uid
        // ... ON DELETE CASCADE` (with `PRAGMA foreign_keys=ON`) removes that record's old legs
        // before the payload's legs go in. That is what makes the payload *replace* the whole
        // split set instead of merging into it — the adapter's own "sweep the splits no longer in
        // the set" step is a no-op on this path, so the store need not tell it the row exists.
        // (`DbLocalRecordStoreTest.the adapter drops a transaction's old legs through REPLACE
        // alone` pins that mechanism; if it ever stops holding, this write stops replacing sets.)
        transactions.addRecord(transaction)
        return true
    }

    override fun delete(identity: SyncIdentity): Boolean = when (identity.recordType) {
        SyncRecordType.ACCOUNT -> deleteAccount(identity)
        SyncRecordType.TRANSACTION -> deleteTransaction(identity)
        else -> {
            Timber.w(
                "Refusing to delete a %s record: this store only holds %s",
                identity.recordType.id, supportedTypes.joinToString { it.id }
            )
            false
        }
    }

    private fun deleteAccount(identity: SyncIdentity): Boolean {
        val existing = accounts.getRecordOrNull(identity.key) ?: return false
        if (existing.type == AccountType.ROOT) {
            Timber.w("Refusing to delete the book's ROOT account (%s)", identity.key)
            return false
        }
        if (splits.getRecordsCount(SplitEntry.COLUMN_ACCOUNT_UID + " = ?", arrayOf(identity.key)) > 0L) {
            // `splits.account_uid` is `ON DELETE CASCADE`, so deleting the row would take this
            // account's splits with it and leave their transactions unbalanced (or with no legs).
            // Those transactions are a *different* record type with their own tombstones, and the
            // merge has not agreed to touch them — so this deletion waits until nothing posts
            // here. It converges: the account's turn comes the round after the legs are gone.
            Timber.w("Deferring delete of account %s: transactions still post to it", identity.key)
            return false
        }
        val deleted = accounts.deleteRecord(identity.key)
        if (deleted) {
            // `deleteRecord` leaves the account's children naming a uid that is now gone — it
            // repairs `default_transfer_account_uid` in the database but only the *in-memory*
            // cache for `parent_account_uid`. Left like that a child is the one case deferral
            // cannot converge from: `bind` walks the parent chain to recompute `full_name`, so
            // it can never be written again. Re-point them at the local ROOT, which is what
            // "this account has no parent" means (and what the writer does with a parentless
            // payload), so the children stay both writable and mergeable.
            accounts.reassignDescendantAccounts(identity.key, accounts.rootAccountUID)
        }
        return deleted
    }

    private fun deleteTransaction(identity: SyncIdentity): Boolean {
        val existing = transactions.getRecordOrNull(identity.key) ?: return false
        if (existing.isTemplate) {
            Timber.w(
                "Refusing to delete template transaction %s: templates are not part of the merge",
                identity.key
            )
            return false
        }
        // Not `recursiveDeleteAccount`-style recursion, and deliberately no cascade of our own:
        // the splits go because they are *part of* this record, not because they are a record
        // type the merge agreed to delete. `splits.transaction_uid` is declared
        // `ON DELETE CASCADE` for exactly that reason.
        return transactions.deleteRecord(identity.key)
    }

    // ------------------------------------------------------------------ helpers

    private fun requireSupported(types: Collection<SyncRecordType>) {
        types.firstOrNull { it !in supportedTypes }?.let { throw UnsupportedRecordTypeException(it) }
    }

    /**
     * Resolves the commodity a row's foreign key will point at.
     *
     * Prefers the authoritative `commodity_uid` (so a security keeps its exact commodity),
     * falls back to the currency code, and refuses rather than guessing. The last check is not
     * paranoia: `CommoditiesDbAdapter.getCurrency` answers with a *static* constant for the
     * common currencies even when no row exists, and both `accounts` and `transactions` have a
     * real foreign key on `commodity_uid` — so a static answer could be written into the row and
     * then fail to resolve when the record is read back.
     */
    private fun resolveCommodity(commodityUid: String?, currencyCode: String): Commodity? {
        val commodities = accounts.commoditiesDbAdapter
        commodityUid?.let { uid ->
            commodities.getRecordOrNull(uid)?.let { return it }
        }
        val byCode = commodities.getCurrency(currencyCode) ?: return null
        return byCode.takeIf { commodities.getRecordOrNull(it.uid) != null }
    }

    /**
     * Whether `parentUid` can be dereferenced.
     *
     * A missing or empty parent is fine: the adapter attaches such an account to the local
     * ROOT, which is a local decision and not a dangling reference. Anything else must
     * actually be a row, because the adapter will look it up by uid while computing
     * `full_name`.
     */
    private fun parentIsPresent(parentUid: String?): Boolean =
        parentUid.isNullOrEmpty() || accounts.getRecordOrNull(parentUid) != null

    /**
     * Builds the row set to store, or `null` when this transaction cannot be applied yet.
     *
     * Split order is the payload's order: the splits are handed to the adapter in sequence and
     * `splits._id` (the book's only ordering) is assigned by insertion. `transaction_uid` is
     * never taken from the payload — `addSplit` stamps it from the parent, which is the one
     * place it can be right.
     */
    private fun buildTransaction(fields: TransactionFields, commodity: Commodity): Transaction? {
        val splits = ArrayList<Split>(fields.splits.size)
        for (splitFields in fields.splits) {
            val account = accounts.getRecordOrNull(splitFields.accountUid)
            if (account == null) {
                // `splits.account_uid` is a foreign key, and the split's `quantity` is
                // denominated in that account's commodity — there is nothing to write against.
                Timber.w(
                    "Deferring transaction %s: the account %s of one of its splits is not in " +
                        "the book yet",
                    fields.uid, splitFields.accountUid
                )
                return null
            }
            val type = transactionTypeOf(splitFields.type)
            if (type == null) {
                Timber.w(
                    "Refusing to write transaction %s: unknown split type '%s'",
                    fields.uid, splitFields.type
                )
                return null
            }
            val split = Split(Money(splitFields.valueNum, splitFields.valueDenom, commodity), account)
            split.setUID(splitFields.uid)
            // `quantity` is in the *account's* commodity, which is why the account had to be
            // resolvable first. `value` is in the transaction's, which is why it was built
            // above with `commodity`.
            split.quantity = Money(
                splitFields.quantityNum, splitFields.quantityDenom, account.commodity
            )
            split.type = type
            split.memo = splitFields.memo
            split.reconciledState = splitFields.reconcileState[0]
            split.reconciledDate = splitFields.reconciledDate ?: NEVER
            splits += split
        }

        if (!balances(splits, commodity)) {
            // `TransactionsDbAdapter.addRecord` would "fix" this by creating a split against an
            // imbalance account — minting both the account and the split on this device. The
            // peer's transaction would then never match its own base here.
            Timber.w(
                "Refusing to write transaction %s: its splits do not balance; applying it " +
                    "would create a local imbalance split",
                fields.uid
            )
            return null
        }

        val transaction = Transaction(fields.description)
        transaction.setUID(fields.uid)
        transaction.notes = fields.notes
        transaction.datePosted = fields.datePosted
        // `date_entered` *is* the `created_at` column for transactions; see TransactionFields.
        transaction.dateEntered = fields.createdAt
        transaction.commodity = commodity
        transaction.number = fields.number
        transaction.isTemplate = fields.isTemplate
        // `scheduledActionUID` is deliberately left null: it references `scheduled_actions`,
        // which is not part of this merge, and the column has a real foreign key.
        transaction.splits = splits
        return transaction
    }

    /**
     * Whether the splits sum to zero in the transaction's commodity.
     *
     * Mirrors `Transaction.imbalance`: a split whose quantity is in another commodity makes the
     * transaction multi-currency, and the app itself stops balancing there (it answers zero), so
     * this does too — refusing a transaction the app would happily store is not safety.
     */
    private fun balances(splits: List<Split>, commodity: Commodity): Boolean {
        var total = BigDecimal.ZERO
        for (split in splits) {
            if (split.quantity.commodity.uid != commodity.uid) return true
            val amount = split.value.asBigDecimal()
            total = if (split.type == TransactionType.DEBIT) total - amount else total + amount
        }
        return total.compareTo(BigDecimal.ZERO) == 0
    }

    private fun transactionTypeOf(value: String): TransactionType? =
        TransactionType.entries.firstOrNull { it.value == value }

    /**
     * The account's fields as they cross the wire.
     *
     * [rootUid] is the book's own ROOT: a parent that *is* the ROOT is not a parent for transfer
     * purposes (see [list]), so it is dropped here rather than shipped as a reference no peer
     * could resolve.
     */
    private fun Account.toFields(rootUid: String) = AccountFields(
        uid = uid,
        name = name,
        type = type.name,
        currencyCode = commodity.currencyCode,
        commodityUid = commodity.uid,
        parentUid = parentUID.takeIf { it != rootUid },
        defaultTransferAccountUid = defaultTransferAccountUID,
        code = code,
        description = description,
        notes = notes,
        isPlaceholder = isPlaceholder,
        isHidden = isHidden,
        isFavorite = isFavorite,
        isTemplate = isTemplate
    )

    private fun Transaction.toTransactionFields() = TransactionFields(
        uid = uid,
        description = description,
        notes = notes,
        datePosted = datePosted,
        createdAt = dateEntered,
        currencyCode = commodity.currencyCode,
        commodityUid = commodity.uid,
        number = number,
        isTemplate = isTemplate,
        splits = splits.map { it.toSplitFields() }
    )

    private fun Split.toSplitFields() = SplitFields(
        uid = uid,
        type = type.value,
        valueNum = value.numerator,
        valueDenom = value.denominator,
        quantityNum = quantity.numerator,
        quantityDenom = quantity.denominator,
        memo = memo,
        accountUid = accountUID.orEmpty(),
        reconcileState = reconciledState.toString(),
        // `NEVER` is `Long.MIN_VALUE` and does not survive the UTC text timestamp the column
        // stores, so "never reconciled" travels as *absence*. The test for "has a date" is the
        // app's own (`CsvTransactionsExporter`: `reconciledDate > 0L`), which is robust to
        // whatever a `NEVER` round trip through the column happens to come back as.
        reconciledDate = reconciledDate.takeIf { it > 0L }
    )
}
