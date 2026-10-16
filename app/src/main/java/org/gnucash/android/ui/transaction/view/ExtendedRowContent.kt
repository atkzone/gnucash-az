package org.gnucash.android.ui.transaction.view

/**
 * The optional lines of one transaction card, already resolved for display.
 *
 * `null` means "do not draw that line" — either because the field is not part of
 * the active view or because the transaction has nothing in it. Keeping this
 * decision here rather than in the view holder is what stops the empty case from
 * being rendered as a blank strip: a transaction without notes must not grow a
 * gap just because 备注 happens to be ticked.
 */
data class ExtendedRowContent(
    val account: String?,
    val notes: String?,
    val number: String?,
    val tags: List<String>
) {
    val isEmpty: Boolean
        get() = account == null && notes == null && number == null && tags.isEmpty()

    /**
     * `true` when one of the extra lines has something to draw.
     *
     * Deliberately **ignores [account]**: the account is the card's own secondary row, not
     * one of the lines this content fills in. Folding it in here would open the extra
     * container for a view whose only field is the account — and the container would then
     * be a visibly empty strip.
     */
    val hasExtraLines: Boolean
        get() = notes != null || number != null || tags.isNotEmpty()
}

object ExtendedRowContentBuilder {

    /**
     * @param account fully-qualified name of the other side of the split, if known.
     * @param tags    tag names as stored, in display order.
     */
    fun build(
        fields: Set<ExtendedViewField>,
        account: String?,
        notes: String?,
        number: String?,
        tags: List<String>
    ): ExtendedRowContent = ExtendedRowContent(
        account = value(fields, ExtendedViewField.ACCOUNT, account),
        notes = value(fields, ExtendedViewField.NOTES, notes),
        number = value(fields, ExtendedViewField.NUMBER, number),
        tags = if (ExtendedViewField.TAGS in fields) {
            tags.mapNotNull { it.trim().takeIf { name -> name.isNotEmpty() } }
        } else {
            emptyList()
        }
    )

    private fun value(
        fields: Set<ExtendedViewField>,
        field: ExtendedViewField,
        raw: String?
    ): String? = if (field in fields) raw?.takeIf { it.isNotBlank() } else null
}
