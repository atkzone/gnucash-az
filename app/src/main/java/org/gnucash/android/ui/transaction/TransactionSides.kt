package org.gnucash.android.ui.transaction

import org.gnucash.android.model.Transaction
import org.gnucash.android.model.TransactionType

/**
 * Which account sits on each side of a stored transaction.
 *
 * The transaction form shows the two sides *explicitly* (借方账户 / 贷方账户), so
 * the two spinners have to be filled from the split **type** — never from "the
 * split that is not the account the form was opened from". The latter is what
 * used to make editing show the same account twice: opening the form from the
 * credit side (which is exactly what "搜索结果 → 修改" does, via
 * `getDefaultAccountUID(CREDIT)`) made `getTransferSplit` answer with the *debit*
 * split, so the credit spinner selected the debit account.
 */
object TransactionSides {

    fun debitAccountUid(transaction: Transaction?): String? =
        accountUid(transaction, TransactionType.DEBIT)

    fun creditAccountUid(transaction: Transaction?): String? =
        accountUid(transaction, TransactionType.CREDIT)

    private fun accountUid(transaction: Transaction?, type: TransactionType): String? =
        transaction?.splits
            ?.firstOrNull { it.type == type }
            ?.accountUID
            ?.takeIf { it.isNotEmpty() }
}
