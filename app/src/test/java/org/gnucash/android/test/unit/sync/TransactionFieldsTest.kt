package org.gnucash.android.test.unit.sync

import org.assertj.core.api.Assertions.assertThat
import org.gnucash.android.sync.SplitFields
import org.gnucash.android.sync.SyncIdentity
import org.gnucash.android.sync.SyncRecordType
import org.gnucash.android.sync.TransactionFields
import org.junit.Test

/**
 * The transaction field map: the one shape a transaction *and its splits* have as it crosses the
 * wire (plan §7.1).
 *
 * Pure, so it runs without Robolectric. The tests are about **losslessness** first (a field the
 * mapper drops is a field the merge can never see change; a *split* it drops is a leg of a double
 * entry gone) and strictness second (a malformed map must decode to `null` rather than to a
 * guessed transaction).
 */
class TransactionFieldsTest {

    private val debit = SplitFields(
        uid = "split-1",
        type = "DEBIT",
        valueNum = 2500,
        valueDenom = 100,
        quantityNum = 2500,
        quantityDenom = 100,
        memo = "card",
        accountUid = "acc-1",
        reconcileState = "n"
    )

    private val credit = SplitFields(
        uid = "split-2",
        type = "CREDIT",
        valueNum = 2500,
        valueDenom = 100,
        quantityNum = 2500,
        quantityDenom = 100,
        memo = "food",
        accountUid = "acc-2",
        reconcileState = "c",
        reconciledDate = 1_700_000_000_123L
    )

    private val full = TransactionFields(
        uid = "txn-1",
        description = "Weekly shop",
        notes = "note with = and %25\nsecond line",
        datePosted = 1_700_000_000_000L,
        createdAt = 1_699_999_999_000L,
        currencyCode = "CNY",
        commodityUid = "cur-cny",
        number = "42",
        splits = listOf(debit, credit)
    )

    // ------------------------------------------------------------------ losslessness

    @Test
    fun `a fully populated transaction survives a map round trip`() {
        assertThat(TransactionFields.fromMap(full.toMap())).isEqualTo(full)
    }

    @Test
    fun `a transaction with no splits survives a map round trip`() {
        val empty = full.copy(splits = emptyList())
        assertThat(TransactionFields.fromMap(empty.toMap())).isEqualTo(empty)
    }

    @Test
    fun `the legs come back in the order they went out`() {
        val three = full.copy(
            splits = listOf(
                debit,
                credit.copy(uid = "split-3", accountUid = "acc-3"),
                debit.copy(uid = "split-4", accountUid = "acc-4")
            )
        )

        // The book's only ordering is `splits._id`, and the register shows it: a re-ordered
        // payload is a different record, so the index has to survive the text format.
        val decoded = TransactionFields.fromMap(three.toMap())!!

        assertThat(decoded.splits.map { it.uid })
            .containsExactly("split-1", "split-3", "split-4")
        assertThat(decoded).isEqualTo(three)
    }

    @Test
    fun `every key the mapper emits is a declared key`() {
        assertThat(full.toMap().keys).allMatch { TransactionFields.isDeclaredKey(it) }
    }

    @Test
    fun `the template flag is always written, so a true to false edit leaves a trace`() {
        val off = full.copy(isTemplate = false)
        val on = full.copy(isTemplate = true)

        // Both directions carry the key: absence would make `true` and `false` have the same
        // representation, and the merge would never see the edit that turned it off.
        assertThat(off.toMap()).containsEntry(TransactionFields.KEY_IS_TEMPLATE, "false")
        assertThat(on.toMap()).containsEntry(TransactionFields.KEY_IS_TEMPLATE, "true")
        assertThat(off.toMap().keys).isEqualTo(on.toMap().keys)
    }

    @Test
    fun `an unset reconcile date is absent rather than a sentinel value`() {
        // `NEVER` is `Long.MIN_VALUE`, which does not survive a round trip through the UTC text
        // timestamp the column stores — so "never reconciled" has to be spelled as absence.
        val withoutDate = full.copy(splits = listOf(debit))

        assertThat(withoutDate.toMap())
            .doesNotContainKey(TransactionFields.splitPrefix(0) + SplitFields.KEY_RECONCILE_DATE)
        assertThat(TransactionFields.fromMap(withoutDate.toMap())!!.splits[0].reconciledDate)
            .isNull()
        assertThat(full.toMap())
            .containsEntry(
                TransactionFields.splitPrefix(1) + SplitFields.KEY_RECONCILE_DATE,
                "1700000000123"
            )
    }

    @Test
    fun `an empty memo is written, because empty is a value and absence is not`() {
        val blank = full.copy(splits = listOf(debit.copy(memo = "")))

        assertThat(blank.toMap())
            .containsEntry(TransactionFields.splitPrefix(0) + SplitFields.KEY_MEMO, "")
        assertThat(TransactionFields.fromMap(blank.toMap())).isEqualTo(blank)
    }

    // ------------------------------------------------------------------ strictness

    @Test
    fun `a map missing a required field is not a transaction`() {
        val complete = full.toMap()
        for (required in listOf(
            TransactionFields.KEY_UID,
            TransactionFields.KEY_DESCRIPTION,
            TransactionFields.KEY_NOTES,
            TransactionFields.KEY_DATE_POSTED,
            TransactionFields.KEY_CREATED_AT,
            TransactionFields.KEY_CURRENCY_CODE,
            TransactionFields.KEY_COMMODITY_UID,
            TransactionFields.KEY_NUMBER
        )) {
            assertThat(TransactionFields.fromMap(complete - required))
                .describedAs("a map without $required")
                .isNull()
        }
    }

    @Test
    fun `an empty uid or commodity uid is not a transaction`() {
        assertThat(TransactionFields.fromMap(full.toMap() + (TransactionFields.KEY_UID to ""))).isNull()
        assertThat(
            TransactionFields.fromMap(full.toMap() + (TransactionFields.KEY_COMMODITY_UID to ""))
        ).isNull()
    }

    @Test
    fun `a non-numeric date is not a transaction`() {
        assertThat(
            TransactionFields.fromMap(full.toMap() + (TransactionFields.KEY_DATE_POSTED to "yesterday"))
        ).isNull()
    }

    @Test
    fun `keys a newer peer added are ignored rather than fatal`() {
        val decoded = TransactionFields.fromMap(
            full.toMap() + ("something_new" to "x") + ("split.0.something_new" to "y")
        )

        assertThat(decoded).isEqualTo(full)
    }

    @Test
    fun `a gap in the split indices is not a transaction`() {
        // A missing index is not a smaller transaction: the split that lived there would vanish
        // and the ones after it would be renumbered on the next write, so the record would never
        // match its own base again.
        val legZero = full.copy(splits = listOf(debit)).toMap()
        val legTwo = full.copy(splits = listOf(credit)).toMap()
            .mapKeys { (key, _) -> key.replaceFirst(TransactionFields.splitPrefix(0), TransactionFields.splitPrefix(2)) }

        assertThat(TransactionFields.fromMap(legZero + legTwo)).isNull()
    }

    @Test
    fun `a split missing a required field is not a transaction`() {
        val prefix = TransactionFields.splitPrefix(0)
        for (required in listOf(
            SplitFields.KEY_UID,
            SplitFields.KEY_TYPE,
            SplitFields.KEY_VALUE_NUM,
            SplitFields.KEY_VALUE_DENOM,
            SplitFields.KEY_QUANTITY_NUM,
            SplitFields.KEY_QUANTITY_DENOM,
            SplitFields.KEY_MEMO,
            SplitFields.KEY_ACCOUNT_UID,
            SplitFields.KEY_RECONCILE_STATE
        )) {
            assertThat(TransactionFields.fromMap(full.toMap() - (prefix + required)))
                .describedAs("a split without $required")
                .isNull()
        }
    }

    @Test
    fun `a split with no account or uid is not a transaction`() {
        val prefix = TransactionFields.splitPrefix(0)
        assertThat(
            TransactionFields.fromMap(full.toMap() + (prefix + SplitFields.KEY_ACCOUNT_UID to ""))
        ).isNull()
        assertThat(
            TransactionFields.fromMap(full.toMap() + (prefix + SplitFields.KEY_UID to ""))
        ).isNull()
    }

    @Test
    fun `a reconcile state that is not a single character is not a transaction`() {
        val prefix = TransactionFields.splitPrefix(0)
        assertThat(
            TransactionFields.fromMap(full.toMap() + (prefix + SplitFields.KEY_RECONCILE_STATE to "yes"))
        ).isNull()
        assertThat(
            TransactionFields.fromMap(full.toMap() + (prefix + SplitFields.KEY_RECONCILE_STATE to ""))
        ).isNull()
    }

    @Test
    fun `a denominator of zero or less is not a transaction`() {
        val prefix = TransactionFields.splitPrefix(0)
        for (broken in listOf("0", "-100")) {
            assertThat(
                TransactionFields.fromMap(full.toMap() + (prefix + SplitFields.KEY_VALUE_DENOM to broken))
            ).describedAs("value denom %s", broken).isNull()
            assertThat(
                TransactionFields.fromMap(
                    full.toMap() + (prefix + SplitFields.KEY_QUANTITY_DENOM to broken)
                )
            ).describedAs("quantity denom %s", broken).isNull()
        }
    }

    @Test
    fun `a non-numeric amount is not a transaction`() {
        val prefix = TransactionFields.splitPrefix(0)
        assertThat(
            TransactionFields.fromMap(full.toMap() + (prefix + SplitFields.KEY_VALUE_NUM to "10.00"))
        ).isNull()
    }

    // ------------------------------------------------------------------ key discipline

    @Test
    fun `only well formed split keys are declared`() {
        assertThat(TransactionFields.isDeclaredKey("split.0.uid")).isTrue()
        assertThat(TransactionFields.isDeclaredKey("split.10.uid")).isTrue()
        for (malformed in listOf(
            "split", "splitting", "split.uid", "split..uid", "split.0", "split.0.",
            "split.0.uid.x", "split.x.uid",
            // Well formed, but naming a field this format does not carry — so it is a newer
            // peer's addition, and the store must ignore it rather than refuse the record.
            "split.0.future_field", "split.0.sched_account_uid"
        )) {
            assertThat(TransactionFields.isDeclaredKey(malformed))
                .describedAs("%s should not be a declared key", malformed)
                .isFalse()
        }
    }

    @Test
    fun `declaredSubset keeps declared keys and drops the rest`() {
        val mixed = full.toMap() + ("is_exported" to "1") + ("whatever" to "x")

        val subset = TransactionFields.declaredSubset(mixed)

        assertThat(subset).isEqualTo(full.toMap())
    }

    // ------------------------------------------------------------------ identity

    @Test
    fun `the record identity is the transaction type plus the GUID`() {
        assertThat(full.identity).isEqualTo(SyncIdentity(SyncRecordType.TRANSACTION, "txn-1"))
    }

    @Test
    fun `a template transaction is not syncable, an ordinary one is`() {
        assertThat(full.copy(isTemplate = true).isSyncable).isFalse()
        assertThat(full.copy(isTemplate = false).isSyncable).isTrue()
    }
}
