package org.gnucash.android.test.unit.sync

import org.assertj.core.api.Assertions.assertThat
import org.gnucash.android.model.AccountType
import org.gnucash.android.sync.AccountFields
import org.gnucash.android.sync.SyncIdentity
import org.gnucash.android.sync.SyncRecordType
import org.junit.Test

/**
 * The account field map: the one shape an account has as it crosses the wire (plan §7.1).
 *
 * Pure, so it runs without Robolectric. The tests are about **losslessness** first (a field
 * the mapper drops is a field the merge can never see change, and a *boolean* it drops is an
 * edit that leaves no trace at all) and strictness second (a malformed map must decode to
 * `null` rather than to a guessed account).
 */
class AccountFieldsTest {

    private val full = AccountFields(
        uid = "acc-1",
        name = "Checking",
        type = AccountType.BANK.name,
        currencyCode = "CNY",
        commodityUid = "cur-cny",
        parentUid = "acc-root",
        defaultTransferAccountUid = "acc-2",
        code = "1000",
        description = "main account",
        notes = "note with = and %25",
        isPlaceholder = true,
        isHidden = true,
        isFavorite = true,
        isTemplate = true
    )

    // ------------------------------------------------------------------ losslessness

    @Test
    fun `a fully populated account survives a map round trip`() {
        assertThat(AccountFields.fromMap(full.toMap())).isEqualTo(full)
    }

    @Test
    fun `a minimal account survives a map round trip`() {
        val minimal = AccountFields(uid = "acc-2", name = "Cash", type = AccountType.CASH.name, currencyCode = "CNY")
        assertThat(AccountFields.fromMap(minimal.toMap())).isEqualTo(minimal)
    }

    @Test
    fun `every key the mapper emits is a declared key`() {
        assertThat(full.toMap().keys.toList()).isSubsetOf(AccountFields.ALL_KEYS.toList())
    }

    @Test
    fun `booleans are always written, so a true to false edit leaves a trace`() {
        val off = AccountFields(uid = "a", name = "a", type = AccountType.CASH.name, currencyCode = "CNY")
        val on = off.copy(isHidden = true)

        // Both directions carry the key: absence would make `true` and `false` have the same
        // representation, and the merge would never see the edit that turned it off.
        assertThat(off.toMap()).containsEntry(AccountFields.KEY_IS_HIDDEN, "false")
        assertThat(on.toMap()).containsEntry(AccountFields.KEY_IS_HIDDEN, "true")
        assertThat(off.toMap().keys).isEqualTo(on.toMap().keys)
    }

    @Test
    fun `null fields are omitted rather than written as empty strings`() {
        val fields = full.copy(parentUid = null, commodityUid = null, notes = null).toMap()

        assertThat(fields).doesNotContainKey(AccountFields.KEY_PARENT_ACCOUNT_UID)
        assertThat(fields).doesNotContainKey(AccountFields.KEY_COMMODITY_UID)
        assertThat(fields).doesNotContainKey(AccountFields.KEY_NOTES)
    }

    @Test
    fun `an empty currency code is accepted, because a security account has no currency`() {
        val stock = AccountFields(uid = "acc-3", name = "AAPL", type = AccountType.STOCK.name, currencyCode = "")

        val decoded = AccountFields.fromMap(stock.toMap())

        assertThat(decoded).isEqualTo(stock)
        assertThat(decoded!!.currencyCode).isEmpty()
    }

    // ------------------------------------------------------------------ strictness

    @Test
    fun `a map missing a required field is not an account`() {
        val complete = full.toMap()
        for (required in listOf(
            AccountFields.KEY_UID,
            AccountFields.KEY_NAME,
            AccountFields.KEY_TYPE,
            AccountFields.KEY_CURRENCY_CODE
        )) {
            assertThat(AccountFields.fromMap(complete - required))
                .describedAs("a map without $required")
                .isNull()
        }
    }

    @Test
    fun `an empty uid or type is not an account`() {
        assertThat(AccountFields.fromMap(full.toMap() + (AccountFields.KEY_UID to ""))).isNull()
        assertThat(AccountFields.fromMap(full.toMap() + (AccountFields.KEY_TYPE to ""))).isNull()
    }

    @Test
    fun `an empty optional field decodes as absent rather than as an empty value`() {
        val decoded = AccountFields.fromMap(
            full.toMap() + (AccountFields.KEY_PARENT_ACCOUNT_UID to "")
        )

        assertThat(decoded!!.parentUid).isNull()
    }

    @Test
    fun `keys a newer peer added are ignored rather than fatal`() {
        val decoded = AccountFields.fromMap(full.toMap() + ("something_new" to "x"))

        assertThat(decoded).isEqualTo(full)
    }

    @Test
    fun `a missing or garbled flag reads as false`() {
        val base = full.copy(isHidden = true).toMap()

        assertThat(AccountFields.fromMap(base - AccountFields.KEY_IS_HIDDEN)!!.isHidden).isFalse()
        assertThat(AccountFields.fromMap(base + (AccountFields.KEY_IS_HIDDEN to ""))!!.isHidden).isFalse()
        assertThat(AccountFields.fromMap(base + (AccountFields.KEY_IS_HIDDEN to "yes"))!!.isHidden).isFalse()
        assertThat(AccountFields.fromMap(base + (AccountFields.KEY_IS_HIDDEN to "TRUE"))!!.isHidden).isTrue()
    }

    // ------------------------------------------------------------------ identity

    @Test
    fun `the record identity is the account type plus the GUID`() {
        assertThat(full.identity).isEqualTo(SyncIdentity(SyncRecordType.ACCOUNT, "acc-1"))
    }

    @Test
    fun `only the root account is unsyncable`() {
        assertThat(full.copy(type = AccountType.ROOT.name).isSyncable).isFalse()
        for (type in AccountType.entries.filterNot { it == AccountType.ROOT }) {
            assertThat(full.copy(type = type.name).isSyncable)
                .describedAs("%s accounts are syncable", type)
                .isTrue()
        }
    }

    @Test
    fun `hidden and template accounts are still syncable`() {
        // Dropping them would read, to the merge, as "the user deleted them here".
        assertThat(full.copy(isHidden = true, isTemplate = true).isSyncable).isTrue()
    }
}
