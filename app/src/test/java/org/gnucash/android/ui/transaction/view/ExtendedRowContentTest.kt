package org.gnucash.android.ui.transaction.view

import org.assertj.core.api.Assertions.assertThat
import org.junit.Test

/**
 * What an extended view actually puts on a card.
 *
 * The value of this layer is the *hiding*: an unticked field must not draw, and a
 * ticked field whose value happens to be blank must not draw an empty strip
 * either — that is the difference between "显示备注" and "the rows grew a gap".
 */
class ExtendedRowContentTest {

    private val all = ExtendedViewField.DEFAULT

    @Test
    fun `an unticked field is never drawn even when there is a value`() {
        val content = ExtendedRowContentBuilder.build(
            fields = setOf(ExtendedViewField.ACCOUNT),
            account = "Assets:Bank",
            notes = "记得报销",
            number = "42",
            tags = listOf("差旅")
        )
        assertThat(content.account).isEqualTo("Assets:Bank")
        assertThat(content.notes).isNull()
        assertThat(content.number).isNull()
        assertThat(content.tags).isEmpty()
    }

    @Test
    fun `a blank value does not produce an empty line`() {
        val content = ExtendedRowContentBuilder.build(
            fields = all, account = "Assets:Bank", notes = "   ", number = "", tags = emptyList()
        )
        assertThat(content.notes).isNull()
        assertThat(content.number).isNull()
        assertThat(content.account).isEqualTo("Assets:Bank")
        assertThat(content.isEmpty).isFalse
    }

    @Test
    fun `nothing ticked means nothing drawn`() {
        val content = ExtendedRowContentBuilder.build(
            fields = emptySet(), account = "a", notes = "b", number = "c", tags = listOf("d")
        )
        assertThat(content.isEmpty).isTrue
    }

    @Test
    fun `tags are only collected when the field is ticked`() {
        val without = ExtendedRowContentBuilder.build(
            setOf(ExtendedViewField.NOTES), "a", "n", "1", listOf("差旅", "餐饮")
        )
        assertThat(without.tags).isEmpty()

        val with = ExtendedRowContentBuilder.build(
            setOf(ExtendedViewField.TAGS), "a", "n", "1", listOf("差旅", "餐饮")
        )
        assertThat(with.tags).containsExactly("差旅", "餐饮")
    }

    @Test
    fun `blank tag names are dropped`() {
        val content = ExtendedRowContentBuilder.build(
            setOf(ExtendedViewField.TAGS), null, null, null, listOf(" 差旅 ", "", "   ")
        )
        assertThat(content.tags).containsExactly("差旅")
    }

    @Test
    fun `the account label is passed through exactly as resolved`() {
        val content = ExtendedRowContentBuilder.build(all, null, null, null, emptyList())
        assertThat(content.account).isNull()
        val named = ExtendedRowContentBuilder.build(all, "支出:餐饮", null, null, emptyList())
        assertThat(named.account).isEqualTo("支出:餐饮")
    }

    /**
     * The predicate the list uses to open the extra container.
     *
     * The account is deliberately excluded: it is drawn by the card's own secondary row, so
     * a view whose only field is the account must NOT open the container — that would show
     * up as a visibly empty strip between the title and the amount.
     */
    @Test
    fun `extra lines follow notes, number and tags but never the account`() {
        val accountOnly = ExtendedRowContentBuilder.build(
            fields = setOf(ExtendedViewField.ACCOUNT),
            account = "Assets:Bank",
            notes = null,
            number = null,
            tags = emptyList()
        )
        assertThat(accountOnly.account).isEqualTo("Assets:Bank")
        assertThat(accountOnly.hasExtraLines)
            .`as`("只勾账户不能把空的扩展容器撑开")
            .isFalse

        assertThat(
            ExtendedRowContentBuilder.build(
                setOf(ExtendedViewField.NOTES), "a", "记得报销", null, emptyList()
            ).hasExtraLines
        ).`as`("有备注就是一行").isTrue
        assertThat(
            ExtendedRowContentBuilder.build(
                setOf(ExtendedViewField.NUMBER), "a", null, "42", emptyList()
            ).hasExtraLines
        ).`as`("有编号就是一行").isTrue
        assertThat(
            ExtendedRowContentBuilder.build(
                setOf(ExtendedViewField.TAGS), "a", null, null, listOf("差旅")
            ).hasExtraLines
        ).`as`("有标签就是一行").isTrue
        assertThat(
            ExtendedRowContentBuilder.build(
                setOf(ExtendedViewField.NOTES), "a", "   ", null, emptyList()
            ).hasExtraLines
        ).`as`("只剩空白不算一行").isFalse
    }
}
