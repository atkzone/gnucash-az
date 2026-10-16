package org.gnucash.android.test.unit.sync

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.gnucash.android.db.DatabaseSchema.AccountEntry
import org.gnucash.android.db.DatabaseSchema.SplitEntry
import org.gnucash.android.db.DatabaseSchema.TransactionEntry
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
import org.gnucash.android.sync.AccountFields
import org.gnucash.android.sync.DbLocalRecordStore
import org.gnucash.android.sync.RecordState
import org.gnucash.android.sync.SplitFields
import org.gnucash.android.sync.SyncIdentity
import org.gnucash.android.sync.SyncRecordType
import org.gnucash.android.sync.TransactionFields
import org.gnucash.android.sync.UnsupportedRecordTypeException
import org.gnucash.android.test.unit.GnuCashTest
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * The production local store, against a real book database (plan §5②, §7).
 *
 * Every case here is about a way the store could quietly do the wrong thing rather than
 * throw: an account that is present but not listed reads as a deletion, a write that is
 * declined but reported as applied re-files the base, a delete that cascades destroys data
 * the merge never agreed to lose, a split written on its own takes its siblings with it. The
 * tests are written relative to a known set of rows so they do not depend on whatever else
 * the book happens to contain.
 */
class DbLocalRecordStoreTest : GnuCashTest() {

    private lateinit var accounts: AccountsDbAdapter
    private lateinit var transactions: TransactionsDbAdapter
    private lateinit var splits: SplitsDbAdapter
    private lateinit var store: DbLocalRecordStore

    @Before
    fun setUp() {
        accounts = AccountsDbAdapter.instance
        transactions = TransactionsDbAdapter.instance
        splits = SplitsDbAdapter.instance
        // Transactions first: their splits go with them, and the account rows they hang off
        // are removed second.
        transactions.deleteAllRecords()
        accounts.deleteAllRecords()
        store = DbLocalRecordStore(accounts, transactions, splits)
    }

    @After
    fun tearDown() {
        transactions.deleteAllRecords()
        accounts.deleteAllRecords()
    }

    // --------------------------------------------------------------------- fixtures

    private fun createLocally(
        name: String,
        type: AccountType = AccountType.BANK,
        commodity: Commodity = Commodity.DEFAULT_COMMODITY,
        configure: (Account) -> Unit = {}
    ): Account {
        val account = Account(name, commodity)
        account.type = type
        configure(account)
        return accounts.addRecord(account)
    }

    private fun createRoot(name: String): Account = createLocally(name, AccountType.ROOT)

    /** A two-leg transaction between [from] and [to], stored in the book. */
    private fun createTransactionLocally(
        description: String,
        from: Account,
        to: Account,
        amount: String = "10.00",
        configure: (Transaction) -> Unit = {}
    ): Transaction {
        val transaction = Transaction(description)
        val split = Split(Money(amount, from.commodity), from)
        transaction.addSplit(split)
        transaction.addSplit(split.createPair(to))
        configure(transaction)
        return transactions.addRecord(transaction)
    }

    /** Two accounts plus a balanced transaction between them. */
    private fun twoAccountTransaction(description: String = "Groceries"): Transaction {
        val a = createLocally("Checking")
        val b = createLocally("Expenses", AccountType.EXPENSE)
        return createTransactionLocally(description, a, b)
    }

    /** The store's view of one account, as the merge would see it. */
    private fun mapped(uid: String): AccountFields = AccountFields.fromMap(
        store.list(listOf(SyncRecordType.ACCOUNT)).single { it.key == uid }.fields
    )!!

    /** The store's view of one transaction, as the merge would see it. */
    private fun mappedTransaction(uid: String): TransactionFields = TransactionFields.fromMap(
        store.list(listOf(SyncRecordType.TRANSACTION)).single { it.key == uid }.fields
    )!!

    private fun listedAccountUids(): List<String> =
        store.list(listOf(SyncRecordType.ACCOUNT)).map { it.key }

    private fun listedTransactionUids(): List<String> =
        store.list(listOf(SyncRecordType.TRANSACTION)).map { it.key }

    private fun stateOf(fields: AccountFields) =
        RecordState(SyncRecordType.ACCOUNT, fields.uid, fields.toMap())

    private fun stateOf(fields: TransactionFields) =
        RecordState(SyncRecordType.TRANSACTION, fields.uid, fields.toMap())

    // --------------------------------------------------------------------- reading

    @Test
    fun `the store declares exactly the account and the transaction type`() {
        assertThat(store.supportedTypes)
            .containsExactlyInAnyOrder(SyncRecordType.ACCOUNT, SyncRecordType.TRANSACTION)
    }

    @Test
    fun `every account in the book is listed`() {
        val a = createLocally("Checking")
        val b = createLocally("Petty Cash", AccountType.CASH)

        assertThat(listedAccountUids()).containsExactlyInAnyOrder(a.uid, b.uid)
    }

    @Test
    fun `a hidden account is still listed`() {
        val hidden = createLocally("Secret") { it.isHidden = true }

        // `fetchAllRecords()` — the app's display query — excludes hidden accounts and ROOT.
        // Listing through it would make every hidden account look deleted here, and the next
        // round would delete the peer's copy of it.
        assertThat(listedAccountUids()).contains(hidden.uid)
    }

    @Test
    fun `the root account is never listed`() {
        val root = createRoot("Root Account")
        val normal = createLocally("Checking")

        assertThat(accounts.getRecordOrNull(root.uid)).isNotNull()   // it really is in the book
        assertThat(listedAccountUids()).containsExactly(normal.uid)
    }

    @Test
    fun `a top level account travels without a parent reference`() {
        val top = createLocally("Checking")

        // The row does name a parent — the book's own ROOT — but that uid is minted here and no
        // peer can ever dereference it. Shipping it would make every top level account defer for
        // ever on the other side, so "top level" has to travel as *no parent*.
        assertThat(accounts.getRecordOrNull(top.uid)!!.parentUID).isEqualTo(accounts.rootAccountUID)
        assertThat(mapped(top.uid).parentUid).isNull()
        assertThat(store.list(listOf(SyncRecordType.ACCOUNT)).single { it.key == top.uid }.fields)
            .doesNotContainKey(AccountFields.KEY_PARENT_ACCOUNT_UID)
    }

    @Test
    fun `every transaction in the book is listed with its splits`() {
        val transaction = twoAccountTransaction()
        val payload = mappedTransaction(transaction.uid)

        assertThat(listedTransactionUids()).containsExactly(transaction.uid)
        assertThat(payload.splits).hasSize(2)
        // An unreconciled split's date is the `NEVER` sentinel, which must reach the wire as
        // absence and not as some enormous negative number the other device would then adopt.
        assertThat(payload.splits.filter { it.reconciledDate != null }).isEmpty()
        // Drift audit: nothing the mapper writes may be a key it does not declare, which is what
        // the store's "the payload is what storing it would produce" comparison leans on.
        assertThat(payload.toMap().keys.filterNot { TransactionFields.isDeclaredKey(it) }).isEmpty()
    }

    @Test
    fun `a payload carrying a field from a newer peer is still applied`() {
        val transaction = twoAccountTransaction()
        val payload = mappedTransaction(transaction.uid)
        transactions.deleteRecord(transaction.uid)

        // Forward compatibility, at the only place it can be lost: the comparison between the
        // payload and what storing it would produce has to ignore keys this build does not know,
        // or every record from a newer peer would be refused for ever.
        val fromNewerPeer = payload.toMap() +
            ("future_field" to "x") +
            (TransactionFields.splitPrefix(0) + "future_field" to "y")

        assertThat(
            store.write(RecordState(SyncRecordType.TRANSACTION, transaction.uid, fromNewerPeer))
        ).isTrue()
        assertThat(mappedTransaction(transaction.uid)).isEqualTo(payload)
    }

    @Test
    fun `a transaction's splits keep the order the book has them in`() {
        val a = createLocally("Checking")
        val b = createLocally("Brokerage", AccountType.ASSET)
        val c = createLocally("Cash", AccountType.CASH)
        val transaction = Transaction("Split three ways")
        transaction.addSplit(Split(Money("10.00", a.commodity), a))
        transaction.addSplit(
            Split(Money("6.00", b.commodity), b).also { it.type = TransactionType.CREDIT }
        )
        transaction.addSplit(
            Split(Money("4.00", c.commodity), c).also { it.type = TransactionType.CREDIT }
        )
        transactions.addRecord(transaction)

        // The register's order is `splits._id`, and it is part of the record: a payload that
        // reordered the legs would be a different transaction.
        assertThat(mappedTransaction(transaction.uid).splits.map { it.accountUid })
            .containsExactly(a.uid, b.uid, c.uid)
    }

    @Test
    fun `a template transaction is never listed`() {
        val real = twoAccountTransaction()
        val template = real.copy()
        template.isTemplate = true
        transactions.addRecord(template)

        assertThat(transactions.getRecordOrNull(template.uid)).isNotNull()   // it is in the book
        assertThat(listedTransactionUids()).containsExactly(real.uid)
    }

    @Test
    fun `asking for a type the store cannot represent is an error, not an empty answer`() {
        twoAccountTransaction()

        // An empty list would be read by the merge as "every one of those was deleted here".
        // SPLIT is the interesting one: it is a real record type in the book, and it is still
        // not independently syncable, because its write goes through its transaction.
        assertThatThrownBy { store.list(listOf(SyncRecordType.SPLIT)) }
            .isInstanceOf(UnsupportedRecordTypeException::class.java)
        assertThatThrownBy { store.list(listOf(SyncRecordType.SPLIT, SyncRecordType.ACCOUNT)) }
            .isInstanceOf(UnsupportedRecordTypeException::class.java)
    }

    // --------------------------------------------------------------------- writing: accounts

    @Test
    fun `a record read from the book and written back keeps every field`() {
        val source = createLocally("Savings", AccountType.BANK) {
            it.code = "2000"
            it.description = "the good one"
            it.notes = "note with = and %"
            it.isFavorite = true
            it.isHidden = true
        }
        val original = mapped(source.uid)
        accounts.deleteRecord(source.uid)
        assertThat(listedAccountUids()).doesNotContain(source.uid)

        assertThat(store.write(stateOf(original))).isTrue()

        assertThat(mapped(source.uid)).isEqualTo(original)
    }

    @Test
    fun `writing a record that already exists updates it in place`() {
        val existing = createLocally("Old name")
        val fields = mapped(existing.uid).copy(name = "New name")

        assertThat(store.write(stateOf(fields))).isTrue()

        assertThat(listedAccountUids()).containsOnlyOnce(existing.uid)
        assertThat(mapped(existing.uid).name).isEqualTo("New name")
    }

    @Test
    fun `a payload whose uid disagrees with the record key is refused`() {
        val fields = AccountFields(
            uid = "acc-x", name = "X", type = AccountType.BANK.name, currencyCode = "CNY"
        )

        val result = store.write(RecordState(SyncRecordType.ACCOUNT, "acc-other", fields.toMap()))

        assertThat(result).isFalse()
        assertThat(listedAccountUids()).doesNotContain("acc-x", "acc-other")
    }

    @Test
    fun `an unreadable payload is refused rather than guessed at`() {
        assertThat(store.write(RecordState(SyncRecordType.ACCOUNT, "acc-x", mapOf("uid" to "acc-x"))))
            .isFalse()
        assertThat(listedAccountUids()).isEmpty()
    }

    @Test
    fun `a record for a type the store cannot represent is refused`() {
        val fields = AccountFields(
            uid = "acc-x", name = "X", type = AccountType.BANK.name, currencyCode = "CNY"
        )

        val result = store.write(RecordState(SyncRecordType.SPLIT, "acc-x", fields.toMap()))

        assertThat(result).isFalse()
    }

    @Test
    fun `a peer root account never replaces the local one`() {
        val root = createRoot("Root Account")
        val impostor = AccountFields(
            uid = root.uid, name = "Hijacked", type = AccountType.ROOT.name, currencyCode = "CNY"
        )

        assertThat(store.write(stateOf(impostor))).isFalse()
        assertThat(accounts.getRecordOrNull(root.uid)!!.name).isEqualTo("Root Account")
    }

    @Test
    fun `an unknown account type is refused`() {
        val fields = AccountFields(
            uid = "acc-x", name = "X", type = "NOT_A_TYPE", currencyCode = "CNY"
        )

        assertThat(store.write(stateOf(fields))).isFalse()
        assertThat(listedAccountUids()).doesNotContain("acc-x")
    }

    @Test
    fun `a record whose commodity cannot be resolved is refused`() {
        val fields = AccountFields(
            uid = "acc-x", name = "X", type = AccountType.BANK.name,
            currencyCode = "ZZZ", commodityUid = null
        )

        assertThat(store.write(stateOf(fields))).isFalse()
        assertThat(listedAccountUids()).doesNotContain("acc-x")
    }

    @Test
    fun `a child whose parent is not in the book yet is deferred, not written`() {
        val fields = AccountFields(
            uid = "acc-child", name = "Child", type = AccountType.BANK.name,
            currencyCode = Commodity.DEFAULT_COMMODITY.currencyCode,
            commodityUid = Commodity.DEFAULT_COMMODITY.uid,
            parentUid = "acc-parent-not-here"
        )

        // `AccountsDbAdapter.bind` walks the parent chain to recompute full_name, so writing
        // a dangling reference would throw mid-write. Waiting a round converges; guessing does not.
        assertThat(store.write(stateOf(fields))).isFalse()
        assertThat(listedAccountUids()).doesNotContain("acc-child")
    }

    @Test
    fun `a peer's top level account is attached to the local root`() {
        val fields = AccountFields(
            uid = "acc-top", name = "Peer Checking", type = AccountType.BANK.name,
            currencyCode = Commodity.DEFAULT_COMMODITY.currencyCode,
            commodityUid = Commodity.DEFAULT_COMMODITY.uid
        )

        // An absent parent is the one case the writer is allowed to *decide*: it means "top
        // level", and the local ROOT is this book's answer to that — not a reference it guessed.
        assertThat(store.write(stateOf(fields))).isTrue()
        assertThat(accounts.getRecordOrNull("acc-top")!!.parentUID)
            .isEqualTo(accounts.rootAccountUID)
        assertThat(mapped("acc-top").parentUid).isNull()
    }

    // ------------------------------------------------------------------ writing: transactions

    @Test
    fun `a transaction read from the book and written back keeps every field`() {
        val a = createLocally("Checking")
        val b = createLocally("Groceries", AccountType.EXPENSE)
        val c = createLocally("Cash", AccountType.CASH)
        val source = Transaction("Weekly shop")
        source.notes = "note with = and %25 and a newline\nsecond line"
        source.number = "42"
        source.datePosted = 1_700_000_000_000L
        val legA = Split(Money("25.00", a.commodity), a)
        legA.memo = "card"
        val legB = Split(Money("15.00", b.commodity), b).also { it.type = TransactionType.CREDIT }
        legB.memo = "food"
        legB.reconciledState = Split.RECONCILED_CLEARED
        legB.reconciledDate = 1_700_000_000_123L
        val legC = Split(Money("10.00", c.commodity), c).also { it.type = TransactionType.CREDIT }
        source.addSplit(legA)
        source.addSplit(legB)
        source.addSplit(legC)
        transactions.addRecord(source)

        val original = mappedTransaction(source.uid)
        transactions.deleteRecord(source.uid)
        assertThat(listedTransactionUids()).doesNotContain(source.uid)

        assertThat(store.write(stateOf(original))).isTrue()

        val restored = mappedTransaction(source.uid)
        assertThat(restored).isEqualTo(original)
        // And the legs came back in the same order — the book's only ordering is `splits._id`.
        assertThat(restored.splits.map { it.accountUid }).containsExactly(a.uid, b.uid, c.uid)
        assertThat(restored.splits.map { it.memo }).containsExactly("card", "food", "")
        assertThat(restored.splits[1].reconcileState)
            .isEqualTo(Split.RECONCILED_CLEARED.toString())
    }

    @Test
    fun `writing a transaction replaces its whole split set`() {
        val a = createLocally("Checking")
        val b = createLocally("Groceries", AccountType.EXPENSE)
        val c = createLocally("Cash", AccountType.CASH)
        val source = Transaction("Weekly shop")
        source.addSplit(Split(Money("25.00", a.commodity), a))
        source.addSplit(
            Split(Money("15.00", b.commodity), b).also { it.type = TransactionType.CREDIT }
        )
        source.addSplit(
            Split(Money("10.00", c.commodity), c).also { it.type = TransactionType.CREDIT }
        )
        transactions.addRecord(source)
        assertThat(mappedTransaction(source.uid).splits).hasSize(3)

        // The peer consolidated two legs into one. What gets stored must be *the payload*, not
        // the payload glued onto what was there before. Note this passes even though the store
        // never hands the adapter the record's own id: the `REPLACE` write itself drops the old
        // legs through `splits.transaction_uid ON DELETE CASCADE` (pinned by the adapter-level
        // test below). Miss either half and the record never matches its own base again.
        val payload = mappedTransaction(source.uid)
        val consolidated = payload.copy(
            splits = listOf(payload.splits[0], payload.splits[2].copy(valueNum = 2500))
        )

        assertThat(store.write(stateOf(consolidated))).isTrue()

        assertThat(mappedTransaction(source.uid).splits).hasSize(2)
        assertThat(splits.getSplitsForTransaction(source.uid)).hasSize(2)
        assertThat(splits.getSplitsForTransaction(source.uid).map { it.uid })
            .containsExactlyInAnyOrder(consolidated.splits[0].uid, consolidated.splits[1].uid)
    }

    @Test
    fun `the adapter drops a transaction's old legs through REPLACE alone`() {
        val a = createLocally("Checking")
        val c = createLocally("Cash", AccountType.CASH)
        val original = Transaction("Weekly shop")
        original.addSplit(Split(Money("25.00", a.commodity), a))
        original.addSplit(
            Split(Money("15.00", c.commodity), c).also { it.type = TransactionType.CREDIT }
        )
        original.addSplit(
            Split(Money("10.00", c.commodity), c).also { it.type = TransactionType.CREDIT }
        )
        transactions.addRecord(original)
        assertThat(splits.getSplitsForTransaction(original.uid)).hasSize(3)

        // A *fresh* model, so its id is zero and the adapter's own "sweep the splits that are no
        // longer in the set" step never runs. The dropped leg still goes, because `addRecord`'s
        // default is `REPLACE`: deleting the transaction row cascades through
        // `splits.transaction_uid ON DELETE CASCADE`. The store leans on exactly this to make a
        // payload *replace* a record's whole split set, so pin it at the adapter — if the update
        // method, the foreign key, or `PRAGMA foreign_keys=ON` ever changes, this goes red here
        // instead of the store quietly merging split sets and reporting success.
        val replacement = Transaction("Weekly shop")
        replacement.setUID(original.uid)
        replacement.addSplit(Split(Money("25.00", a.commodity), a))
        replacement.addSplit(
            Split(Money("25.00", c.commodity), c).also { it.type = TransactionType.CREDIT }
        )
        assertThat(replacement.id).isZero()

        transactions.addRecord(replacement)

        assertThat(splits.getSplitsForTransaction(original.uid)).hasSize(2)
        assertThat(splits.getSplitsForTransaction(original.uid).map { it.uid })
            .containsExactlyInAnyOrder(replacement.splits[0].uid, replacement.splits[1].uid)
    }

    @Test
    fun `writing a transaction that is already here updates it in place`() {
        val transaction = twoAccountTransaction("Old description")
        val payload = mappedTransaction(transaction.uid).copy(notes = "added by the peer")

        assertThat(store.write(stateOf(payload))).isTrue()

        assertThat(listedTransactionUids()).containsOnlyOnce(transaction.uid)
        assertThat(mappedTransaction(transaction.uid).notes).isEqualTo("added by the peer")
    }

    @Test
    fun `a transaction payload whose uid disagrees with the record key is refused`() {
        val payload = TransactionFields(
            uid = "txn-x", description = "X", notes = "", datePosted = 1L, createdAt = 1L,
            currencyCode = Commodity.DEFAULT_COMMODITY.currencyCode,
            commodityUid = Commodity.DEFAULT_COMMODITY.uid, number = ""
        )

        val result = store.write(RecordState(SyncRecordType.TRANSACTION, "txn-other", payload.toMap()))

        assertThat(result).isFalse()
        assertThat(listedTransactionUids()).doesNotContain("txn-x", "txn-other")
    }

    @Test
    fun `a template payload is refused`() {
        val real = twoAccountTransaction()
        val payload = mappedTransaction(real.uid)
            .copy(uid = "txn-template", isTemplate = true)

        // Templates are scheduled-transaction scaffolding: they carry a foreign key into a
        // record type this merge does not define, and (because every template is created with a
        // fresh uid) leaving them out can never be mistaken for "the user deleted it".
        assertThat(store.write(stateOf(payload))).isFalse()
        assertThat(listedTransactionUids()).doesNotContain("txn-template")
        assertThat(transactions.getRecordOrNull("txn-template")).isNull()
    }

    @Test
    fun `a split whose account is not in the book yet defers the whole transaction`() {
        val transaction = twoAccountTransaction()
        val payload = mappedTransaction(transaction.uid)
        val orphan = payload.copy(
            splits = payload.splits.mapIndexed { index, split ->
                if (index == 0) split.copy(accountUid = "acc-nowhere") else split
            }
        )
        transactions.deleteRecord(transaction.uid)
        assertThat(listedTransactionUids()).doesNotContain(transaction.uid)

        // `splits.account_uid` is a foreign key and the split's `quantity` is denominated in
        // that account's commodity, so there is nothing to write the row against. The account
        // arrives in the same round that needs it, so waiting converges.
        assertThat(store.write(stateOf(orphan))).isFalse()
        assertThat(listedTransactionUids()).doesNotContain(transaction.uid)
    }

    @Test
    fun `a transaction whose splits do not balance is refused, and mints no imbalance account`() {
        val transaction = twoAccountTransaction()
        val payload = mappedTransaction(transaction.uid)
        // Make the credit leg smaller than the debit leg: 25 in, 10 out.
        val unbalanced = payload.copy(
            splits = payload.splits.mapIndexed { index, split ->
                if (index == 0) split.copy(valueNum = 2500) else split.copy(valueNum = 1000)
            }
        )
        transactions.deleteRecord(transaction.uid)
        val accountCount = accounts.getAllRecords(null, null).size

        assertThat(store.write(stateOf(unbalanced))).isFalse()

        // The real damage a "helpful" write would do: `TransactionsDbAdapter.addRecord` repairs
        // an unbalanced transaction by creating a split against an imbalance account, and it
        // creates that account if the book has none. Adopting a peer's record must never mint
        // local rows.
        assertThat(accounts.getAllRecords(null, null)).hasSize(accountCount)
        assertThat(listedTransactionUids()).doesNotContain(transaction.uid)
    }

    @Test
    fun `a payload the model cannot reproduce is refused`() {
        val transaction = twoAccountTransaction("Groceries")
        val payload = mappedTransaction(transaction.uid)
        transactions.deleteRecord(transaction.uid)

        // `Transaction.description` trims on assignment, so storing this would leave a row that
        // differs from the payload it came from — and the next round would read that difference
        // as a local edit and push it back, forever.
        val untrimmed = payload.copy(description = "  Groceries  ")

        assertThat(store.write(stateOf(untrimmed))).isFalse()
        assertThat(listedTransactionUids()).doesNotContain(transaction.uid)
    }

    @Test
    fun `a payload with two legs sharing one uid is refused`() {
        val transaction = twoAccountTransaction()
        val payload = mappedTransaction(transaction.uid)
        transactions.deleteRecord(transaction.uid)

        // `Transaction.addSplit` keeps only the first split for a uid, so the stored record
        // would have one leg where the payload had two.
        val duplicated = payload.copy(
            splits = listOf(payload.splits[0], payload.splits[0].copy(memo = "second"))
        )

        assertThat(store.write(stateOf(duplicated))).isFalse()
        assertThat(listedTransactionUids()).doesNotContain(transaction.uid)
    }

    @Test
    fun `an unreadable transaction payload is refused`() {
        assertThat(
            store.write(
                RecordState(SyncRecordType.TRANSACTION, "txn-x", mapOf("uid" to "txn-x"))
            )
        ).isFalse()
        assertThat(listedTransactionUids()).isEmpty()
    }

    @Test
    fun `a transaction whose commodity cannot be resolved is refused`() {
        val transaction = twoAccountTransaction()
        val payload = mappedTransaction(transaction.uid)
        transactions.deleteRecord(transaction.uid)

        val unknownCommodity = payload.copy(commodityUid = "commodity-nowhere", currencyCode = "ZZZ")

        assertThat(store.write(stateOf(unknownCommodity))).isFalse()
        assertThat(listedTransactionUids()).doesNotContain(transaction.uid)
    }

    // --------------------------------------------------------------------- deleting

    @Test
    fun `deleting removes the account and is idempotent`() {
        val account = createLocally("Checking")

        assertThat(store.delete(SyncIdentity(SyncRecordType.ACCOUNT, account.uid))).isTrue()
        assertThat(listedAccountUids()).doesNotContain(account.uid)
        assertThat(store.delete(SyncIdentity(SyncRecordType.ACCOUNT, account.uid))).isFalse()
    }

    @Test
    fun `deleting an account does not take its sub-accounts with it`() {
        val parent = createLocally("Parent")
        val child = createLocally("Child") { it.parentUID = parent.uid }

        assertThat(store.delete(SyncIdentity(SyncRecordType.ACCOUNT, parent.uid))).isTrue()

        // Only the account itself goes: its sub-accounts (and transactions) are separate
        // records that the merge has not agreed to delete.
        assertThat(accounts.getRecordOrNull(child.uid)).isNotNull()
        assertThat(listedAccountUids()).contains(child.uid)
    }

    @Test
    fun `deleting an account re points its children at the local root`() {
        val parent = createLocally("Parent")
        val child = createLocally("Child") { it.parentUID = parent.uid }
        assertThat(accounts.getRecordOrNull(child.uid)!!.parentUID).isEqualTo(parent.uid)

        assertThat(store.delete(SyncIdentity(SyncRecordType.ACCOUNT, parent.uid))).isTrue()

        // The child is a separate record and survives, but it must not keep naming a uid that no
        // longer exists: `bind` walks the parent chain to recompute `full_name`, so a dangling
        // reference is a child that can never be written again — the one case deferral cannot
        // converge out of. Re-parenting to the local ROOT is what "no parent" means here.
        // Read the *row*, not the model. `deleteRecord` repairs the in-memory cache's parentUID
        // whether or not the database was touched, so only the columns can show whether the
        // repair really happened — and a re-pointed parent with an un-recomputed `full_name` is
        // a row that disagrees with itself.
        assertThat(accounts.getAttribute(child.uid, AccountEntry.COLUMN_PARENT_ACCOUNT_UID))
            .isEqualTo(accounts.rootAccountUID)
        assertThat(accounts.getAttribute(child.uid, AccountEntry.COLUMN_FULL_NAME))
            .isEqualTo("Child")
        assertThat(mapped(child.uid).parentUid).isNull()
    }

    @Test
    fun `a child can be written again once its parent is deleted`() {
        val parent = createLocally("Parent")
        val child = createLocally("Child") { it.parentUID = parent.uid }
        store.delete(SyncIdentity(SyncRecordType.ACCOUNT, parent.uid))

        // What the peer's next round carries: the child, renamed, with the parent reference gone.
        val fromPeer = mapped(child.uid).copy(name = "Renamed child")

        assertThat(store.write(stateOf(fromPeer))).isTrue()
        assertThat(mapped(child.uid).name).isEqualTo("Renamed child")
    }

    @Test
    fun `deleting an account that still has splits is deferred, and damages nothing`() {
        val checking = createLocally("Checking")
        val expenses = createLocally("Expenses", AccountType.EXPENSE)
        val transaction = createTransactionLocally("Groceries", checking, expenses)
        assertThat(splits.getSplitsForTransaction(transaction.uid)).hasSize(2)

        // Removing the account row would cascade (`splits.account_uid ... ON DELETE CASCADE`)
        // and take the Checking leg with it, leaving the transaction unbalanced — the account
        // deletion would silently rewrite a transaction, which is a *different* record type the
        // merge never agreed to touch. So the deletion waits.
        assertThat(store.delete(SyncIdentity(SyncRecordType.ACCOUNT, checking.uid))).isFalse()
        assertThat(accounts.getRecordOrNull(checking.uid)).isNotNull()

        // And nothing was damaged on the way: the transaction still has both legs, so the record
        // the merge sees is unchanged.
        assertThat(mappedTransaction(transaction.uid).splits).hasSize(2)
    }

    @Test
    fun `an account can be deleted once nothing posts to it`() {
        val checking = createLocally("Checking")
        val expenses = createLocally("Expenses", AccountType.EXPENSE)
        val transaction = createTransactionLocally("Groceries", checking, expenses)

        assertThat(store.delete(SyncIdentity(SyncRecordType.ACCOUNT, checking.uid))).isFalse()

        // The transaction goes by its own record type, by its own decision; the account's turn
        // comes the round after. (Converges: nothing else refers to Checking.)
        assertThat(store.delete(SyncIdentity(SyncRecordType.TRANSACTION, transaction.uid))).isTrue()
        assertThat(store.delete(SyncIdentity(SyncRecordType.ACCOUNT, checking.uid))).isTrue()
        assertThat(listedAccountUids()).doesNotContain(checking.uid)
    }

    @Test
    fun `the root account cannot be deleted`() {
        val root = createRoot("Root Account")

        assertThat(store.delete(SyncIdentity(SyncRecordType.ACCOUNT, root.uid))).isFalse()
        assertThat(accounts.getRecordOrNull(root.uid)).isNotNull()
    }

    @Test
    fun `deleting a transaction removes it and its splits`() {
        val transaction = twoAccountTransaction()
        assertThat(splits.getSplitsForTransaction(transaction.uid)).hasSize(2)

        assertThat(store.delete(SyncIdentity(SyncRecordType.TRANSACTION, transaction.uid))).isTrue()

        assertThat(listedTransactionUids()).doesNotContain(transaction.uid)
        // The legs go with the record: they are *part of* it, not a record type the merge
        // agreed to delete separately (`splits.transaction_uid` is `ON DELETE CASCADE`).
        assertThat(splits.getSplitsForTransaction(transaction.uid)).isEmpty()
        assertThat(store.delete(SyncIdentity(SyncRecordType.TRANSACTION, transaction.uid))).isFalse()
    }

    @Test
    fun `a template transaction cannot be deleted`() {
        val a = createLocally("Checking")
        val b = createLocally("Groceries", AccountType.EXPENSE)
        val template = Transaction("Schedule me")
        template.addSplit(Split(Money("10.00", a.commodity), a))
        template.addSplit(
            Split(Money("10.00", b.commodity), b).also { it.type = TransactionType.CREDIT }
        )
        template.isTemplate = true
        transactions.addRecord(template)

        assertThat(store.delete(SyncIdentity(SyncRecordType.TRANSACTION, template.uid))).isFalse()
        assertThat(transactions.getRecordOrNull(template.uid)).isNotNull()
    }

    @Test
    fun `a record for a type the store cannot represent cannot be deleted`() {
        assertThat(store.delete(SyncIdentity(SyncRecordType.SPLIT, "s1"))).isFalse()
    }

    // --------------------------------------------------------------------- drift

    @Test
    fun `the account field keys are the account columns they came from`() {
        assertThat(AccountFields.KEY_UID).isEqualTo(AccountEntry.COLUMN_UID)
        assertThat(AccountFields.KEY_NAME).isEqualTo(AccountEntry.COLUMN_NAME)
        assertThat(AccountFields.KEY_TYPE).isEqualTo(AccountEntry.COLUMN_TYPE)
        assertThat(AccountFields.KEY_CURRENCY_CODE).isEqualTo(AccountEntry.COLUMN_CURRENCY)
        assertThat(AccountFields.KEY_COMMODITY_UID).isEqualTo(AccountEntry.COLUMN_COMMODITY_UID)
        assertThat(AccountFields.KEY_PARENT_ACCOUNT_UID)
            .isEqualTo(AccountEntry.COLUMN_PARENT_ACCOUNT_UID)
        assertThat(AccountFields.KEY_DEFAULT_TRANSFER_ACCOUNT_UID)
            .isEqualTo(AccountEntry.COLUMN_DEFAULT_TRANSFER_ACCOUNT_UID)
        assertThat(AccountFields.KEY_CODE).isEqualTo(AccountEntry.COLUMN_CODE)
        assertThat(AccountFields.KEY_DESCRIPTION).isEqualTo(AccountEntry.COLUMN_DESCRIPTION)
        assertThat(AccountFields.KEY_NOTES).isEqualTo(AccountEntry.COLUMN_NOTES)
        assertThat(AccountFields.KEY_IS_PLACEHOLDER).isEqualTo(AccountEntry.COLUMN_PLACEHOLDER)
        assertThat(AccountFields.KEY_IS_HIDDEN).isEqualTo(AccountEntry.COLUMN_HIDDEN)
        assertThat(AccountFields.KEY_IS_FAVORITE).isEqualTo(AccountEntry.COLUMN_FAVORITE)
        assertThat(AccountFields.KEY_IS_TEMPLATE).isEqualTo(AccountEntry.COLUMN_TEMPLATE)
    }

    @Test
    fun `the transaction field keys are the columns they came from, inversions and all`() {
        assertThat(TransactionFields.KEY_UID).isEqualTo(TransactionEntry.COLUMN_UID)
        // The `transactions` table names these two backwards against the model: the transaction's
        // *description* lives in a column called `name`, and its *notes* in one called
        // `description`. Pinning both here is what makes that impossible to forget.
        assertThat(TransactionFields.KEY_DESCRIPTION).isEqualTo(TransactionEntry.COLUMN_DESCRIPTION)
        assertThat(TransactionFields.KEY_NOTES).isEqualTo(TransactionEntry.COLUMN_NOTES)
        assertThat(TransactionFields.KEY_DATE_POSTED).isEqualTo(TransactionEntry.COLUMN_DATE_POSTED)
        assertThat(TransactionFields.KEY_CREATED_AT).isEqualTo(TransactionEntry.COLUMN_DATE_ENTERED)
        assertThat(TransactionFields.KEY_CURRENCY_CODE).isEqualTo(TransactionEntry.COLUMN_CURRENCY)
        assertThat(TransactionFields.KEY_COMMODITY_UID)
            .isEqualTo(TransactionEntry.COLUMN_COMMODITY_UID)
        assertThat(TransactionFields.KEY_NUMBER).isEqualTo(TransactionEntry.COLUMN_NUMBER)
        assertThat(TransactionFields.KEY_IS_TEMPLATE).isEqualTo(TransactionEntry.COLUMN_TEMPLATE)
    }

    @Test
    fun `the split field keys are the split columns they came from`() {
        assertThat(SplitFields.KEY_UID).isEqualTo(SplitEntry.COLUMN_UID)
        assertThat(SplitFields.KEY_TYPE).isEqualTo(SplitEntry.COLUMN_TYPE)
        assertThat(SplitFields.KEY_VALUE_NUM).isEqualTo(SplitEntry.COLUMN_VALUE_NUM)
        assertThat(SplitFields.KEY_VALUE_DENOM).isEqualTo(SplitEntry.COLUMN_VALUE_DENOM)
        assertThat(SplitFields.KEY_QUANTITY_NUM).isEqualTo(SplitEntry.COLUMN_QUANTITY_NUM)
        assertThat(SplitFields.KEY_QUANTITY_DENOM).isEqualTo(SplitEntry.COLUMN_QUANTITY_DENOM)
        assertThat(SplitFields.KEY_MEMO).isEqualTo(SplitEntry.COLUMN_MEMO)
        assertThat(SplitFields.KEY_ACCOUNT_UID).isEqualTo(SplitEntry.COLUMN_ACCOUNT_UID)
        assertThat(SplitFields.KEY_RECONCILE_STATE).isEqualTo(SplitEntry.COLUMN_RECONCILE_STATE)
        assertThat(SplitFields.KEY_RECONCILE_DATE).isEqualTo(SplitEntry.COLUMN_RECONCILE_DATE)
    }

    @Test
    fun `the columns that are device-local are not on the wire`() {
        // `is_exported` says "this device already wrote it out"; shipping it would let one
        // device's export suppress the other's.
        assertThat(TransactionFields.ALL_KEYS).doesNotContain(TransactionEntry.COLUMN_EXPORTED)
        // Both of these are foreign keys into `scheduled_actions`, which is not merged here.
        assertThat(TransactionFields.ALL_KEYS)
            .doesNotContain(TransactionEntry.COLUMN_SCHEDX_ACTION_UID)
        // A split key is only ever declared with its index: the bare column name is not a key,
        // and the split's own schedule link is not carried either.
        assertThat(
            TransactionFields.isDeclaredKey(
                TransactionFields.splitPrefix(0) + SplitFields.KEY_RECONCILE_DATE
            )
        ).isTrue()
        assertThat(TransactionFields.isDeclaredKey(SplitFields.KEY_RECONCILE_DATE)).isFalse()
        assertThat(TransactionFields.isDeclaredKey("split.0.${SplitEntry.COLUMN_SCHEDX_ACTION_ACCOUNT_UID}"))
            .isFalse()
    }
}
