package org.gnucash.android.ui.transaction.view

import androidx.annotation.StringRes
import org.gnucash.android.R

/**
 * One optional line of a transaction card, as offered by "设置扩展显示".
 *
 * The set is deliberately small and closed: every entry has to be something the
 * card can actually render from the cursor it already has, otherwise the
 * settings screen would be able to promise a field nobody draws. The declaration
 * order is also the on-screen order, so [DEFAULT] reads top-to-bottom.
 */
enum class ExtendedViewField(@StringRes val titleRes: Int) {
    ACCOUNT(R.string.extended_field_account),
    NOTES(R.string.extended_field_notes),
    NUMBER(R.string.extended_field_number),
    TAGS(R.string.extended_field_tags);

    companion object {
        /** What a view shows when its owner has not removed anything yet. */
        val DEFAULT: Set<ExtendedViewField> = values().toSet()

        /** @return the field named [name], or `null` for anything unknown. */
        fun ofName(name: String?): ExtendedViewField? =
            values().firstOrNull { it.name == name }
    }
}
