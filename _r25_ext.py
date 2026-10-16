# -*- coding: utf-8 -*-
"""R25-B：扩展视图（单选框 + 一级菜单「设置扩展显示」+ 显示口径收敛）。"""
import sys

sys.path.insert(0, ".")
from _r25_apply import patch  # noqa: E402

MENU = "app/src/main/res/menu/transactions_list_actions.xml"
LIST = "app/src/main/java/org/gnucash/android/ui/transaction/TransactionsListFragment.kt"
STORE = "app/src/main/java/org/gnucash/android/ui/transaction/view/ExtendedViewStore.kt"
ITEM_ACTIONS = "app/src/main/res/menu/extended_view_item_actions.xml"
ROW = "app/src/main/java/org/gnucash/android/ui/transaction/view/ExtendedRowContent.kt"

# ------------------------------------------------------------ transactions_list_actions
OLD_MENU = '''    <!-- 二级菜单：切换扩展视图 + 定义它显示哪些字段 -->
    <item
        android:id="@+id/menu_extended_view"
        android:icon="@android:drawable/ic_menu_view"
        android:title="@string/menu_extended_view"
        app:showAsAction="never|withText">
        <menu>
            <item
                android:id="@+id/menu_toggle_extended"
                android:checkable="true"
                android:title="@string/menu_show_extended_view" />
            <item
                android:id="@+id/menu_extended_view_settings"
                android:title="@string/menu_extended_view_settings" />
        </menu>
    </item>
</menu>'''
NEW_MENU = '''    <!-- 一级菜单：不再套子菜单。开关不在这里 —— 扩展视图用不用由
         "设置扩展显示"页里那个单选决定（第一行就是"不使用扩展显示"）。 -->
    <item
        android:id="@+id/menu_extended_view_settings"
        android:icon="@android:drawable/ic_menu_view"
        android:title="@string/menu_extended_view_settings"
        app:showAsAction="never|withText" />
</menu>'''
patch(MENU, [(OLD_MENU, NEW_MENU)])

# --------------------------------------------------------- transactions_list_actions(项)
OLD_ITEM = '''<menu xmlns:android="http://schemas.android.com/apk/res/android">
    <item
        android:id="@+id/menu_set_extended_view_default"
        android:title="@string/menu_set_extended_view_default" />
    <item'''
NEW_ITEM = '''<menu xmlns:android="http://schemas.android.com/apk/res/android">
    <!-- 没有"设为默认"：改成单选之后，勾中它就是默认，多一步反而是两处状态。 -->
    <item'''
patch(ITEM_ACTIONS, [(OLD_ITEM, NEW_ITEM)])

# ------------------------------------------------------------------------ ExtendedViewStore
OLD_ENABLED = '''    fun isEnabled(): Boolean = preferences.getBoolean(keyEnabled, false)

    fun setEnabled(enabled: Boolean) {
        preferences.edit { putBoolean(keyEnabled, enabled) }
    }
'''
NEW_ENABLED = '''    fun isEnabled(): Boolean = preferences.getBoolean(keyEnabled, false)

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
'''
patch(STORE, [(OLD_ENABLED, NEW_ENABLED)])

# ---------------------------------------------------------------------- ExtendedRowContent
OLD_ROW = '''    val isEmpty: Boolean
        get() = account == null && notes == null && number == null && tags.isEmpty()
'''
NEW_ROW = '''    val isEmpty: Boolean
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
'''
patch(ROW, [(OLD_ROW, NEW_ROW)])

# ------------------------------------------------------------------- TransactionsListFragment
OLD_IMPORT = '''import androidx.core.view.isVisible
'''
NEW_IMPORT = '''import androidx.core.content.edit
import androidx.core.view.isVisible
'''

OLD_FIELD = '''    /** Extended-view mode; persisted, so it survives leaving the screen. */
    private var useExtendedView = false

    /** Fields the active extended view draws, or `null` when it is not in use. */
'''
NEW_FIELD = '''    /** Fields the active extended view draws, or `null` when it is not in use. */
'''

OLD_ONCREATE = '''        useAbsoluteDate = isAbsoluteDate(context)
        useExtendedView = extendedViewStore.isEnabled()
        //if there was a local override of the global setting, respect it
'''
NEW_ONCREATE = '''        useAbsoluteDate = isAbsoluteDate(context)
        //if there was a local override of the global setting, respect it
'''

OLD_PREPARE = '''        val itemDate = menu.findItem(R.id.menu_toggle_date)
        itemDate.isChecked = useAbsoluteDate

        val itemExtended = menu.findItem(R.id.menu_toggle_extended)
        itemExtended.isChecked = useExtendedView
        itemExtended.isEnabled = useDoubleEntry
    }
'''
NEW_PREPARE = '''        val itemDate = menu.findItem(R.id.menu_toggle_date)
        itemDate.isChecked = useAbsoluteDate
    }
'''

OLD_SELECTED = '''            R.id.menu_toggle_compact -> {
                val checked = !item.isChecked
                item.isChecked = checked
                useCompactView = checked
                // The two display modes are alternatives, not layers.
                if (checked) setExtendedViewEnabled(false)
                refresh()
                true
            }

            R.id.menu_toggle_extended -> {
                val checked = !item.isChecked
                item.isChecked = checked
                setExtendedViewEnabled(checked)
                if (checked) useCompactView = false
                refresh()
                true
            }
'''
NEW_SELECTED = '''            R.id.menu_toggle_compact -> {
                val checked = !item.isChecked
                item.isChecked = checked
                useCompactView = checked
                // The two display modes are alternatives, not layers: a compact row has
                // no line to put extended fields on, so turning compact on turns the
                // extended display off. It goes through the stored selection, which is
                // the same switch the settings screen shows.
                if (checked) {
                    extendedViewStore.select(null)
                    extendedFields = extendedViewStore.activeFields()
                }
                saveCompactView(checked)
                refresh()
                true
            }
'''

OLD_SETTER = '''    /** Turns the extended view on/off and persists the choice. */
    private fun setExtendedViewEnabled(enabled: Boolean) {
        extendedViewStore.setEnabled(enabled)
        useExtendedView = enabled
        extendedFields = extendedViewStore.activeFields()
        requireActivity().invalidateOptionsMenu()
    }
'''
NEW_SETTER = '''    /**
     * Remembers the compact choice for this book.
     *
     * It used to go to `savedInstanceState` only, so it survived a rotation and nothing
     * else — reopening the app brought the tall card back while the menu still claimed
     * the compact mode was on.
     */
    private fun saveCompactView(compact: Boolean) {
        getBookPreferences(requireContext()).edit {
            putBoolean(getString(R.string.key_use_compact_list), compact)
        }
    }
'''

OLD_RESUME = '''        // Re-read the extended view: the user may have just come back from
        // "设置扩展显示" with a renamed view or a different default.
        useExtendedView = extendedViewStore.isEnabled()
        extendedFields = extendedViewStore.activeFields()
        refresh()
'''
NEW_RESUME = '''        // Re-read the extended display: the user may have just come back from
        // "设置扩展显示" having ticked another set of fields, and the loader restart below
        // is what actually redraws the rows with them.
        extendedFields = extendedViewStore.activeFields()
        refresh()
'''

OLD_VISIBLE = '''            extendedFieldsRow.isVisible = content != null &&
                (content.notes != null || content.number != null || content.tags.isNotEmpty())
'''
NEW_VISIBLE = '''            // Same predicate the pure test asserts; it ignores the account on purpose
            // because the account line is drawn by the card itself, not in here.
            extendedFieldsRow.isVisible = content?.hasExtraLines == true
'''

patch(LIST, [
    (OLD_IMPORT, NEW_IMPORT),
    (OLD_FIELD, NEW_FIELD),
    (OLD_ONCREATE, NEW_ONCREATE),
    (OLD_PREPARE, NEW_PREPARE),
    (OLD_SELECTED, NEW_SELECTED),
    (OLD_SETTER, NEW_SETTER),
    (OLD_RESUME, NEW_RESUME),
    (OLD_VISIBLE, NEW_VISIBLE),
])
print("R25-B done")
