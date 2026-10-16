package org.gnucash.android.ui.transaction.view

import org.assertj.core.api.Assertions.assertThat
import org.junit.Test
import java.io.File

/**
 * The wiring of the extended view, which no unit test of the pieces can reach.
 *
 * The pieces all pass on their own — the store round-trips, the row builder hides
 * the right lines, the list fragment compiles — and the feature can still be
 * *invisible*: the menu entry never appears, the card has no view to fill, or the
 * mode is read once and never refreshed after the settings screen changed the
 * selection. None of those produce a failure anywhere else.
 *
 * Deliberately a plain JVM test: it reads a handful of source files, so it needs
 * no device and runs in the fast pass.
 */
class ExtendedViewWiringTest {

    private fun sourceText(relative: String): String {
        val candidates = listOf(File(relative), File("app/$relative"))
        var dir = File(System.getProperty("user.dir") ?: ".")
        while (true) {
            for (candidate in candidates) {
                val file = File(dir, candidate.path)
                if (file.isFile) return file.readText().replace("\r\n", "\n")
            }
            dir = dir.parentFile ?: break
        }
        throw AssertionError("could not locate $relative from ${System.getProperty("user.dir")}")
    }

    @Test
    fun `the overflow menu offers the extended display entry at the top level`() {
        val menu = sourceText(MENU)
        assertThat(menu)
            .`as`("设置扩展显示 必须是一级菜单项")
            .contains("menu_extended_view_settings")
        assertThat(menu)
            .`as`("二级菜单已被取消：不再有嵌套的 <menu> 与独立的开关项")
            .doesNotContain("<menu>")
            .doesNotContain("menu_toggle_extended")
    }

    @Test
    fun `the card carries the optional lines the view can ask for`() {
        val card = sourceText(CARD)
        for (id in listOf("extended_fields", "extended_notes", "extended_number", "extended_tags")) {
            assertThat(card).`as`(id).contains("@+id/$id")
        }
    }

    @Test
    fun `the settings screen is a radio list with the field checkboxes`() {
        val row = sourceText(ITEM_ROW)
        assertThat(row).`as`("单选列表：每行一个 RadioButton").contains("@+id/extended_view_radio")
        val screen = sourceText(FRAGMENT_LAYOUT)
        for (id in listOf("field_account", "field_notes", "field_number", "field_tags")) {
            assertThat(screen).`as`(id).contains("@+id/$id")
        }
    }

    @Test
    fun `the list draws the active view and hides everything without one`() {
        val list = sourceText(LIST)
        assertThat(list)
            .`as`("the fields must be resolved from the store's active view")
            .contains("extendedViewStore.activeFields()")
        assertThat(list)
            .`as`("the compact / single-entry branch has to clear the extended lines")
            .contains("renderExtended(null)")
        assertThat(list)
            .`as`("the normal branch has to draw them")
            .contains("renderExtended(text)")
        assertThat(list)
            .`as`("容器默认 GONE；判据必须用 hasExtraLines，忽略由卡片自己画的账户行")
            .contains("extendedFieldsRow.isVisible = content?.hasExtraLines == true")
    }

    @Test
    fun `the mode is persisted and re-read on the way back`() {
        val list = sourceText(LIST)
        assertThat(list)
            .`as`("进紧凑模式要把扩展显示单选回落到「不使用」")
            .contains("extendedViewStore.select(null)")
        assertThat(list)
            .`as`("returning from 设置扩展显示 must pick up the new selection")
            .contains("extendedFields = extendedViewStore.activeFields()")
    }

    @Test
    fun `the settings screen is reachable and registered`() {
        assertThat(sourceText(LIST)).contains("ExtendedViewsActivity.show(requireContext())")
        assertThat(sourceText(MANIFEST))
            .`as`("an unregistered activity is an ActivityNotFoundException at runtime")
            .contains("org.gnucash.android.ui.transaction.view.ExtendedViewsActivity")
    }

    @Test
    fun `the management screen selects one view and writes the field set through the store`() {
        val screen = sourceText(FRAGMENT)
        assertThat(screen)
            .`as`("选择即写入（单选）")
            .contains("store().select(id)")
        assertThat(screen)
            .`as`("字段勾选面板走 updateFields，而不是自己拼存储")
            .contains("store().updateFields(id, updated)")
        assertThat(screen)
            .`as`("删掉的默认项不能再出现")
            .contains("ExtendedViews.remove(")
            .doesNotContain("menu_set_extended_view_default")

        // Creating and renaming live in the editor dialog the list opens.
        val editor = sourceText(EDIT_FRAGMENT)
        assertThat(editor).contains("ExtendedViews.newId(")
        assertThat(editor).contains("ExtendedViews.add(")
        assertThat(editor).contains("ExtendedViews.rename(")
    }

    companion object {
        private const val LIST =
            "src/main/java/org/gnucash/android/ui/transaction/TransactionsListFragment.kt"
        private const val FRAGMENT =
            "src/main/java/org/gnucash/android/ui/transaction/view/ExtendedViewsFragment.kt"
        private const val EDIT_FRAGMENT =
            "src/main/java/org/gnucash/android/ui/transaction/view/ExtendedViewEditFragment.kt"
        private const val MENU = "src/main/res/menu/transactions_list_actions.xml"
        private const val CARD = "src/main/res/layout/cardview_transaction.xml"
        private const val ITEM_ROW = "src/main/res/layout/item_extended_view.xml"
        private const val FRAGMENT_LAYOUT = "src/main/res/layout/fragment_extended_views.xml"
        private const val MANIFEST = "src/main/AndroidManifest.xml"
    }
}
