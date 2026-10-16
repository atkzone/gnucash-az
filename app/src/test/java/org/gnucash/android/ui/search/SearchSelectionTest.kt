package org.gnucash.android.ui.search

import org.assertj.core.api.Assertions.assertThat
import org.junit.Test

/** UID-keyed selection set behind the search result bulk actions. */
class SearchSelectionTest {

    @Test
    fun `starts empty`() {
        val selection = SearchSelection()
        assertThat(selection.isEmpty).isTrue
        assertThat(selection.size).isZero
        assertThat(selection.snapshot()).isEmpty()
    }

    @Test
    fun `toggle selects then deselects and reports the resulting state`() {
        val selection = SearchSelection()
        assertThat(selection.toggle("a")).isTrue
        assertThat(selection.isSelected("a")).isTrue
        assertThat(selection.toggle("a")).isFalse
        assertThat(selection.isSelected("a")).isFalse
        assertThat(selection.isEmpty).isTrue
    }

    @Test
    fun `snapshot keeps the order rows were ticked`() {
        val selection = SearchSelection()
        selection.toggle("b")
        selection.toggle("a")
        selection.toggle("c")
        assertThat(selection.snapshot()).containsExactly("b", "a", "c")
    }

    @Test
    fun `select all does not duplicate an already ticked uid`() {
        val selection = SearchSelection()
        selection.toggle("a")
        selection.selectAll(listOf("a", "b", "c"))
        assertThat(selection.size).isEqualTo(3)
        assertThat(selection.snapshot()).containsExactly("a", "b", "c")
    }

    @Test
    fun `clear empties the set`() {
        val selection = SearchSelection()
        selection.selectAll(listOf("a", "b"))
        selection.clear()
        assertThat(selection.isEmpty).isTrue
    }

    @Test
    fun `an unknown uid is not selected`() {
        assertThat(SearchSelection().isSelected("nope")).isFalse
    }
}
