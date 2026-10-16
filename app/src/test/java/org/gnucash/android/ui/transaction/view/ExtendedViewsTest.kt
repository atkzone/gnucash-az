package org.gnucash.android.ui.transaction.view

import org.assertj.core.api.Assertions.assertThat
import org.junit.Test

/**
 * The list rules behind "设置扩展显示".
 *
 * Pure JVM, and pointed at the two things that go wrong silently: a default
 * pointer left dangling after a delete (the list then quietly renders *some*
 * view), and an id reused after a delete (a rename then lands on the wrong row).
 */
class ExtendedViewsTest {

    private fun view(
        id: String,
        name: String = id,
        fields: Set<ExtendedViewField> = ExtendedViewField.DEFAULT
    ) = ExtendedView(id, name, fields)

    @Test
    fun `a blank name is refused and a name is trimmed`() {
        assertThat(ExtendedViews.normalizedName(null)).isNull()
        assertThat(ExtendedViews.normalizedName("   ")).isNull()
        assertThat(ExtendedViews.normalizedName("  备注  ")).isEqualTo("备注")
    }

    @Test
    fun `a new id never reuses one that is still live`() {
        assertThat(ExtendedViews.newId(listOf(view("v1"), view("v2")))).isEqualTo("v3")

        // The next id must clear the *highest survivor*, not the size: after the
        // middle one is dropped the list still holds v3, and a count-based id
        // would hand the newcomer "v3" — already taken.
        val (remaining, _) =
            ExtendedViews.remove(listOf(view("v1"), view("v2"), view("v3")), "v1", "v2")
        assertThat(remaining.map { it.id }).containsExactly("v1", "v3")
        assertThat(ExtendedViews.newId(remaining)).isEqualTo("v4")
    }

    @Test
    fun `an id that is not ours does not disturb the numbering`() {
        assertThat(ExtendedViews.newId(listOf(view("custom"), view("v4")))).isEqualTo("v5")
        assertThat(ExtendedViews.newId(emptyList())).isEqualTo("v1")
    }

    @Test
    fun `the first view becomes the default`() {
        val (views, defaultId) = ExtendedViews.add(emptyList(), null, view("v1"))
        assertThat(views).hasSize(1)
        assertThat(defaultId).isEqualTo("v1")
    }

    @Test
    fun `adding keeps an existing default`() {
        val (views, defaultId) =
            ExtendedViews.add(listOf(view("v1"), view("v2")), "v1", view("v3"))
        assertThat(views.map { it.id }).containsExactly("v1", "v2", "v3")
        assertThat(defaultId).isEqualTo("v1")
    }

    @Test
    fun `adding repairs a dangling default`() {
        val (_, defaultId) = ExtendedViews.add(listOf(view("v1")), "gone", view("v2"))
        assertThat(defaultId).isEqualTo("v1")
    }

    @Test
    fun `renaming keeps the id and trims the name`() {
        val views = listOf(view("v1", "旧名"))
        val renamed = ExtendedViews.rename(views, "v1", "  新名  ")
        assertThat(renamed).hasSize(1)
        assertThat(renamed[0].id).isEqualTo("v1")
        assertThat(renamed[0].name).isEqualTo("新名")
    }

    @Test
    fun `a blank rename leaves every name untouched`() {
        val views = listOf(view("v1", "旧名"))
        assertThat(ExtendedViews.rename(views, "v1", "   ")).isEqualTo(views)
    }

    @Test
    fun `renaming an unknown id touches nothing`() {
        val views = listOf(view("v1", "旧名"))
        assertThat(ExtendedViews.rename(views, "nope", "新名")).isEqualTo(views)
    }

    @Test
    fun `deleting the default hands the pointer to a survivor`() {
        val views = listOf(view("v1"), view("v2"), view("v3"))
        val (remaining, defaultId) = ExtendedViews.remove(views, "v1", "v1")
        assertThat(remaining.map { it.id }).containsExactly("v2", "v3")
        assertThat(defaultId).isEqualTo("v2")
    }

    @Test
    fun `deleting a non-default view keeps the pointer`() {
        val (remaining, defaultId) =
            ExtendedViews.remove(listOf(view("v1"), view("v2")), "v1", "v2")
        assertThat(remaining.map { it.id }).containsExactly("v1")
        assertThat(defaultId).isEqualTo("v1")
    }

    @Test
    fun `deleting the last view clears the pointer`() {
        val (remaining, defaultId) = ExtendedViews.remove(listOf(view("v1")), "v1", "v1")
        assertThat(remaining).isEmpty()
        assertThat(defaultId).isNull()
    }

    @Test
    fun `deleting an unknown id changes nothing`() {
        val views = listOf(view("v1"))
        val (remaining, defaultId) = ExtendedViews.remove(views, "v1", "nope")
        assertThat(remaining).isEqualTo(views)
        assertThat(defaultId).isEqualTo("v1")
    }

    @Test
    fun `an unknown default is never stored`() {
        val views = listOf(view("v1"))
        assertThat(ExtendedViews.setDefault(views, "v1", "nope")).isEqualTo("v1")
        assertThat(ExtendedViews.setDefault(views, "v1", "v1")).isEqualTo("v1")
        assertThat(ExtendedViews.setDefault(views, "v1", null)).isNull()
    }

    @Test
    fun `the active view follows a dangling pointer back to a real one`() {
        val views = listOf(view("v1", "一"), view("v2", "二"))
        assertThat(ExtendedViews.active(views, "v2")?.name).isEqualTo("二")
        assertThat(ExtendedViews.active(views, "gone")?.name).isEqualTo("一")
        assertThat(ExtendedViews.active(views, null)?.name).isEqualTo("一")
        assertThat(ExtendedViews.active(emptyList(), "v1")).isNull()
    }
}
