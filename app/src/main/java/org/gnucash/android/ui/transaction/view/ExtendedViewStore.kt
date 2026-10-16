package org.gnucash.android.ui.transaction.view

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import org.gnucash.android.R
import org.gnucash.android.app.GnuCashApplication.Companion.getBookPreferences

/**
 * Persistence for the extended views, kept in the **book** preferences.
 *
 * Book-scoped on purpose: two books are two different sets of accounts, and a
 * "备注 + 编号" view tuned for one is noise in the other. The whole list lives in
 * a single string preference so the schema never has to change for it.
 */
class ExtendedViewStore(
    private val preferences: SharedPreferences,
    private val keyViews: String,
    private val keyEnabled: String,
    private val keyDefault: String
) {

    fun isEnabled(): Boolean = preferences.getBoolean(keyEnabled, false)

    fun setEnabled(enabled: Boolean) {
        preferences.edit { putBoolean(keyEnabled, enabled) }
    }

    /**
     * Picks [id] as the one extended view in use and turns the mode on; `null` turns it
     * off.
     *
     * Selection *is* the switch — the settings screen is a radio list whose first row is
     * 不使用扩展显示, so there is no second place that can disagree with it.
     */
    fun select(id: String?) {
        if (id == null) {
            setEnabled(false)
            return
        }
        val current = views()
        save(current, ExtendedViews.setDefault(current, defaultId(), id))
        setEnabled(true)
    }

    /**
     * Replaces the field set of [id].
     *
     * An empty set is refused: a view that adds nothing is what 紧凑视图 already is, and
     * storing it would leave the list with nothing to draw at all.
     */
    fun updateFields(id: String, fields: Set<ExtendedViewField>) {
        if (fields.isEmpty()) return
        val current = views()
        if (current.none { it.id == id }) return
        save(current.map { if (it.id == id) it.copy(fields = fields) else it }, defaultId())
    }

    /** Stored views, in the order the user arranged them. */
    fun views(): List<ExtendedView> =
        ExtendedViewCodec.decode(preferences.getString(keyViews, null))

    /** Id of the view the list uses, or `null` when the pointer is unset. */
    fun defaultId(): String? =
        preferences.getString(keyDefault, null)?.takeIf { it.isNotBlank() }

    fun save(views: List<ExtendedView>, defaultId: String?) {
        preferences.edit {
            putString(keyViews, ExtendedViewCodec.encode(views))
            if (defaultId.isNullOrBlank()) {
                remove(keyDefault)
            } else {
                putString(keyDefault, defaultId)
            }
        }
    }

    /**
     * The fields the transaction list should draw, or `null` for "not an extended
     * view" (the toggle is off).
     *
     * With the toggle on but nothing configured yet this answers the built-in
     * default rather than `null`: switching the mode on has to show *something*,
     * and "all fields" is the only defensible guess when the user has not chosen.
     */
    fun activeFields(): Set<ExtendedViewField>? {
        if (!isEnabled()) return null
        val active = ExtendedViews.active(views(), defaultId())
        return active?.fields ?: ExtendedViewField.DEFAULT
    }

    companion object {
        fun of(context: Context): ExtendedViewStore {
            val appContext = context.applicationContext
            return ExtendedViewStore(
                getBookPreferences(appContext),
                appContext.getString(R.string.key_extended_views),
                appContext.getString(R.string.key_use_extended_view),
                appContext.getString(R.string.key_extended_view_default)
            )
        }
    }
}
