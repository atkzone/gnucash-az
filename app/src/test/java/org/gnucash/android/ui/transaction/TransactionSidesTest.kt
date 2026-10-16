package org.gnucash.android.ui.transaction

import org.assertj.core.api.Assertions.assertThat
import org.gnucash.android.model.Account
import org.gnucash.android.model.Money
import org.gnucash.android.model.Split
import org.gnucash.android.model.Transaction
import org.gnucash.android.model.TransactionType
import org.gnucash.android.test.unit.GnuCashTest
import org.junit.Test
import java.math.BigDecimal

/**
 * Which account is on which side of a stored transaction.
 *
 * This is the rule that decides what the two spinners show when a transaction is
 * reopened, so it is asserted on its own rather than only through the form: the
 * old rule — "the side that is not the account the form was opened from" — gave
 * the debit account when the form was opened from the credit side, which is
 * exactly the path 搜索结果 → 修改 takes.
 */
class TransactionSidesTest : GnuCashTest() {

    private val debit = Account("借方账户")
    private val credit = Account("贷方账户")

    private fun split(account: Account, type: TransactionType): Split =
        Split(Money(BigDecimal.TEN, debit.commodity), account).apply { this.type = type }

    private fun transaction(vararg splits: Split): Transaction =
        Transaction("转账").apply { splits.forEach { addSplit(it) } }

    @Test
    fun `each side is read from its own split type`() {
        val transaction = transaction(
            split(debit, TransactionType.DEBIT),
            split(credit, TransactionType.CREDIT)
        )
        assertThat(TransactionSides.debitAccountUid(transaction)).isEqualTo(debit.uid)
        assertThat(TransactionSides.creditAccountUid(transaction)).isEqualTo(credit.uid)
    }

    @Test
    fun `the answer does not depend on the order the splits are stored in`() {
        val transaction = transaction(
            split(credit, TransactionType.CREDIT),
            split(debit, TransactionType.DEBIT)
        )
        assertThat(TransactionSides.debitAccountUid(transaction)).isEqualTo(debit.uid)
        assertThat(TransactionSides.creditAccountUid(transaction)).isEqualTo(credit.uid)
    }

    @Test
    fun `a side that is missing answers null, not the other side`() {
        val onlyDebit = transaction(split(debit, TransactionType.DEBIT))
        assertThat(TransactionSides.debitAccountUid(onlyDebit)).isEqualTo(debit.uid)
        assertThat(TransactionSides.creditAccountUid(onlyDebit)).isNull()
    }

    @Test
    fun `no transaction answers null on both sides`() {
        assertThat(TransactionSides.debitAccountUid(null)).isNull()
        assertThat(TransactionSides.creditAccountUid(null)).isNull()
    }
}
