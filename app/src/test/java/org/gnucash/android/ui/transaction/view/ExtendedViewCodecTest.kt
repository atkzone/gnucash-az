package org.gnucash.android.ui.transaction.view

import org.assertj.core.api.Assertions.assertThat
import org.junit.Test

/**
 * The stored payload of the extended views.
 *
 * One record per line, `id TAB name TAB field,field`. The two properties worth
 * pinning are that a name typed by the user can never break the record structure,
 * and that a single bad line cannot take the whole list with it — the payload is
 * whatever an older install left in the preferences.
 */
class ExtendedViewCodecTest {

    @Test
    fun `round trips ids names and fields`() {
        val views = listOf(
            ExtendedView("v1", "备注视图", setOf(ExtendedViewField.NOTES, ExtendedViewField.NUMBER)),
            ExtendedView("v2", "标签", setOf(ExtendedViewField.TAGS))
        )
        assertThat(ExtendedViewCodec.decode(ExtendedViewCodec.encode(views))).isEqualTo(views)
    }

    @Test
    fun `fields are written in declaration order, not insertion order`() {
        val view = ExtendedView(
            "v1",
            "乱序",
            linkedSetOf(ExtendedViewField.TAGS, ExtendedViewField.ACCOUNT, ExtendedViewField.NOTES)
        )
        assertThat(ExtendedViewCodec.encode(listOf(view)))
            .isEqualTo("v1\t乱序\tACCOUNT,NOTES,TAGS")
    }

    @Test
    fun `a name carrying the separators survives a round trip`() {
        val nasty = "a\tb\nc\\d|e"
        val views = listOf(ExtendedView("v1", nasty, setOf(ExtendedViewField.NOTES)))
        val encoded = ExtendedViewCodec.encode(views)
        // One record per line: the name's own newline must not split it in two.
        assertThat(encoded.split('\n')).hasSize(1)
        assertThat(ExtendedViewCodec.decode(encoded)).isEqualTo(views)
    }

    @Test
    fun `an empty payload decodes to no views`() {
        assertThat(ExtendedViewCodec.decode(null)).isEmpty()
        assertThat(ExtendedViewCodec.decode("")).isEmpty()
    }

    @Test
    fun `a malformed line is dropped instead of failing the decode`() {
        val payload = "v1\t好\tNOTES\nbroken line\nv2\t也好\tTAGS"
        val decoded = ExtendedViewCodec.decode(payload)
        assertThat(decoded.map { it.id }).containsExactly("v1", "v2")
    }

    @Test
    fun `a line with a blank id or name is dropped`() {
        val payload = "\t无名\tNOTES\nv2\t   \tTAGS\nv3\t正常\tNOTES"
        assertThat(ExtendedViewCodec.decode(payload).map { it.id }).containsExactly("v3")
    }

    @Test
    fun `a duplicated id keeps the first record only`() {
        val payload = "v1\t第一\tNOTES\nv1\t第二\tTAGS"
        val decoded = ExtendedViewCodec.decode(payload)
        assertThat(decoded).hasSize(1)
        assertThat(decoded[0].name).isEqualTo("第一")
    }

    @Test
    fun `an unknown field name is skipped, not fatal`() {
        val payload = "v1\t来自未来\tNOTES,FUTURE_FIELD,TAGS"
        val decoded = ExtendedViewCodec.decode(payload)
        assertThat(decoded).hasSize(1)
        assertThat(decoded[0].fields)
            .containsExactly(ExtendedViewField.NOTES, ExtendedViewField.TAGS)
    }

    @Test
    fun `a view with no fields survives as an empty field set`() {
        val payload = "v1\t只要账户\t"
        val decoded = ExtendedViewCodec.decode(payload)
        assertThat(decoded).hasSize(1)
        assertThat(decoded[0].fields).isEmpty()
    }

    @Test
    fun `re-encoding an untouched list is byte-identical`() {
        val views = listOf(
            ExtendedView("v1", "一", setOf(ExtendedViewField.NUMBER, ExtendedViewField.ACCOUNT))
        )
        val once = ExtendedViewCodec.encode(views)
        val twice = ExtendedViewCodec.encode(ExtendedViewCodec.decode(once))
        assertThat(twice).isEqualTo(once)
    }
}
