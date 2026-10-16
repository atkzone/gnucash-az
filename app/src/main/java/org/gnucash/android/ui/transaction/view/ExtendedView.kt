package org.gnucash.android.ui.transaction.view

/**
 * A named bundle of [ExtendedViewField]s — one "扩展视图".
 *
 * [id] is what the default pointer and the persisted payload key off, so it must
 * survive a rename; the user-visible name is free text and may repeat.
 */
data class ExtendedView(
    val id: String,
    val name: String,
    val fields: Set<ExtendedViewField>
) {
    val isEmpty: Boolean get() = fields.isEmpty()
}
