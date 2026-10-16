package org.gnucash.android.ui.transaction.view

import org.assertj.core.api.Assertions.assertThat
import org.gnucash.android.test.unit.GnuCashTest
import org.junit.Before
import org.junit.Test

/**
 * What the transaction list actually reads out of the preferences.
 *
 * The interesting case is the one nobody configures: the mode is switched on
 * before any view exists. Answering `null` there would make the menu item look
 * broken, so it has to answer the built-in default — and answering a *stale*
 * pointer would silently render a view the user deleted.
 */
class ExtendedViewStoreTest : GnuCashTest() {

    private lateinit var store: ExtendedViewStore

    @Before
    fun setUp() {
        store = ExtendedViewStore.of(context)
        store.save(emptyList(), null)
        store.setEnabled(false)
    }

    @Test
    fun `the mode is off until it is switched on`() {
        assertThat(store.isEnabled()).isFalse
        assertThat(store.activeFields()).isNull()
    }

    @Test
    fun `switched on with nothing configured falls back to every field`() {
        store.setEnabled(true)
        assertThat(store.activeFields())
            .containsExactlyInAnyOrderElementsOf(ExtendedViewField.DEFAULT)
    }

    @Test
    fun `switched on uses the default view's fields`() {
        store.save(
            listOf(
                ExtendedView("v1", "只要备注", setOf(ExtendedViewField.NOTES)),
                ExtendedView(
                    "v2", "账户与标签",
                    setOf(ExtendedViewField.ACCOUNT, ExtendedViewField.TAGS)
                )
            ),
            "v2"
        )
        store.setEnabled(true)
        assertThat(store.activeFields())
            .containsExactlyInAnyOrder(ExtendedViewField.ACCOUNT, ExtendedViewField.TAGS)
    }

    @Test
    fun `a dangling default falls back to the first stored view`() {
        store.save(listOf(ExtendedView("v1", "编号", setOf(ExtendedViewField.NUMBER))), "gone")
        store.setEnabled(true)
        assertThat(store.activeFields()).containsExactly(ExtendedViewField.NUMBER)
    }

    @Test
    fun `views and default survive being reopened`() {
        val views = listOf(
            ExtendedView("v1", "备注", setOf(ExtendedViewField.NOTES)),
            ExtendedView("v2", "标签", setOf(ExtendedViewField.TAGS))
        )
        store.save(views, "v1")

        val reopened = ExtendedViewStore.of(context)
        assertThat(reopened.views()).isEqualTo(views)
        assertThat(reopened.defaultId()).isEqualTo("v1")
    }

    @Test
    fun `clearing the default leaves no pointer behind`() {
        store.save(listOf(ExtendedView("v1", "备注", setOf(ExtendedViewField.NOTES))), "v1")
        store.save(store.views(), null)
        assertThat(store.defaultId()).isNull()
    }

    @Test
    fun `switching the mode off is remembered across instances`() {
        store.setEnabled(true)
        assertThat(ExtendedViewStore.of(context).isEnabled()).isTrue
        store.setEnabled(false)
        assertThat(ExtendedViewStore.of(context).isEnabled()).isFalse
        assertThat(ExtendedViewStore.of(context).activeFields()).isNull()
    }

    // --- select(): the radio list's single write path -------------------------

    @Test
    fun `selecting a view sets the default and turns the mode on`() {
        store.save(
            listOf(
                ExtendedView("v1", "备注", setOf(ExtendedViewField.NOTES)),
                ExtendedView("v2", "标签", setOf(ExtendedViewField.TAGS))
            ),
            null
        )
        store.select("v2")

        assertThat(store.isEnabled()).isTrue
        assertThat(store.defaultId()).isEqualTo("v2")
        assertThat(store.activeFields()).containsExactly(ExtendedViewField.TAGS)
    }

    @Test
    fun `selecting null turns the mode off instead of storing a blank pointer`() {
        store.save(listOf(ExtendedView("v1", "备注", setOf(ExtendedViewField.NOTES))), "v1")
        store.select("v1")
        assertThat(store.isEnabled()).isTrue

        store.select(null)

        assertThat(store.isEnabled()).isFalse
        assertThat(store.activeFields()).isNull()
        // The stored views are untouched — selecting "不用" is not a delete.
        assertThat(store.views()).hasSize(1)
    }

    @Test
    fun `selecting an unknown id leaves the pointer where it was`() {
        store.save(listOf(ExtendedView("v1", "备注", setOf(ExtendedViewField.NOTES))), "v1")
        store.select("v1")
        store.select("gone")

        assertThat(store.defaultId()).isEqualTo("v1")
    }

    // --- updateFields(): the checkbox panel's write path ----------------------

    @Test
    fun `updateFields replaces the field set and keeps the pointer`() {
        store.save(
            listOf(
                ExtendedView("v1", "备注", setOf(ExtendedViewField.NOTES)),
                ExtendedView("v2", "标签", setOf(ExtendedViewField.TAGS))
            ),
            "v2"
        )
        store.updateFields("v1", setOf(ExtendedViewField.ACCOUNT, ExtendedViewField.NUMBER))

        assertThat(store.views().first { it.id == "v1" }.fields)
            .containsExactlyInAnyOrder(ExtendedViewField.ACCOUNT, ExtendedViewField.NUMBER)
        assertThat(store.defaultId()).isEqualTo("v2")
    }

    @Test
    fun `updateFields refuses an empty set`() {
        store.save(listOf(ExtendedView("v1", "备注", setOf(ExtendedViewField.NOTES))), "v1")
        store.updateFields("v1", emptySet())

        assertThat(store.views().first { it.id == "v1" }.fields)
            .containsExactly(ExtendedViewField.NOTES)
    }

    @Test
    fun `updateFields with an unknown id changes nothing`() {
        store.save(listOf(ExtendedView("v1", "备注", setOf(ExtendedViewField.NOTES))), "v1")
        store.updateFields("gone", setOf(ExtendedViewField.TAGS))

        assertThat(store.views()).hasSize(1)
        assertThat(store.views().first().fields).containsExactly(ExtendedViewField.NOTES)
    }
}
