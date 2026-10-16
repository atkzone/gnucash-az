package org.gnucash.android.ui.account

import org.gnucash.android.db.DatabaseSchema.AccountEntry
import org.gnucash.android.model.AccountType

/**
 * The WHERE fragments that decide which accounts are pickable.
 *
 * One definition shared by the spinner adapters and the filterable account
 * pickers, so a picker can never offer an account its spinner would reject (or
 * hide one the spinner accepts).
 */
object AccountConditions {

    /**
     * Every account a transaction side may point at: no root, no placeholder, no
     * template.
     *
     * The same fragment serves both sides. It deliberately does **not** exclude
     * "the account the form was opened from": with both sides shown explicitly,
     * the opening account is a perfectly legal answer for either one — and
     * excluding it made the true credit account unselectable when editing
     * (the spinner then fell back to another row, usually the debit account).
     * "Both sides identical" is rejected at save time instead.
     */
    fun selectable(): Pair<String, Array<String?>> = Pair(
        (AccountEntry.COLUMN_TYPE + " != ?"
                + " AND " + AccountEntry.COLUMN_TEMPLATE + " = 0"
                + " AND " + AccountEntry.COLUMN_PLACEHOLDER + " = 0"),
        arrayOf<String?>(AccountType.ROOT.name)
    )
}
