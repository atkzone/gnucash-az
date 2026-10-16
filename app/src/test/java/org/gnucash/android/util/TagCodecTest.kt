package org.gnucash.android.util

import org.assertj.core.api.Assertions.assertThat
import org.junit.Test

/**
 * Unit tests for [TagCodec] — the single place that parses/produces the `#tag`
 * text convention (feature ⑧).
 *
 * Pure logic, so no Robolectric here: the rules must hold identically for the
 * writer, the importers and the display layer.
 */
class TagCodecTest {

    @Test
    fun `extract finds a tag preceded by whitespace`() {
        assertThat(TagCodec.extract("午饭 #餐饮")).containsExactly("餐饮")
    }

    @Test
    fun `extract finds a tag at the start of the text`() {
        assertThat(TagCodec.extract("#餐饮 午饭")).containsExactly("餐饮")
    }

    @Test
    fun `extract finds a tag directly after a CJK character`() {
        // Chinese text usually has no space before '#', so a CJK boundary counts.
        assertThat(TagCodec.extract("午饭#餐饮")).containsExactly("餐饮")
    }

    @Test
    fun `extract ignores a hash inside a word`() {
        // "issue#5" and "C#" must not become tags: '#' is glued to a latin word.
        assertThat(TagCodec.extract("issue#5 and C# are not tags")).isEmpty()
    }

    @Test
    fun `extract returns several tags in first seen order and de-duplicates`() {
        assertThat(TagCodec.extract("a #x b #y c #x")).containsExactly("x", "y")
    }

    @Test
    fun `extract stops the name at punctuation`() {
        assertThat(TagCodec.extract("see #餐饮, then #出行。"))
            .containsExactly("餐饮", "出行")
    }

    @Test
    fun `extract ignores a bare hash and an empty name`() {
        assertThat(TagCodec.extract("a # b")).isEmpty()
        assertThat(TagCodec.extract("#")).isEmpty()
    }

    @Test
    fun `extract handles null and empty text`() {
        assertThat(TagCodec.extract(null)).isEmpty()
        assertThat(TagCodec.extract("")).isEmpty()
    }

    @Test
    fun `extract ignores a second hash glued to a latin tag name`() {
        // "#a#b": the second '#' follows a latin char, so it cannot start a tag.
        assertThat(TagCodec.extract("#a#b #c")).containsExactly("a", "c")
    }

    @Test
    fun `extract treats a hash after a CJK char as a new tag`() {
        assertThat(TagCodec.extract("#甲#乙")).containsExactly("甲", "乙")
    }

    @Test
    fun `stripTags removes a tag in the middle and keeps the rest readable`() {
        assertThat(TagCodec.stripTags("午饭 和 咖啡 #餐饮")).isEqualTo("午饭 和 咖啡")
    }

    @Test
    fun `stripTags removes a leading tag`() {
        assertThat(TagCodec.stripTags("#餐饮 午饭")).isEqualTo("午饭")
    }

    @Test
    fun `stripTags removes a tag directly after a CJK character`() {
        assertThat(TagCodec.stripTags("午饭#餐饮")).isEqualTo("午饭")
    }

    @Test
    fun `stripTags of only tags is empty`() {
        assertThat(TagCodec.stripTags("#a #b")).isEmpty()
    }

    @Test
    fun `stripTags leaves text without tags untouched`() {
        assertThat(TagCodec.stripTags("午饭 和 咖啡")).isEqualTo("午饭 和 咖啡")
    }

    @Test
    fun `stripTags does not mangle a hash glued to a latin word`() {
        assertThat(TagCodec.stripTags("see issue#5 now")).isEqualTo("see issue#5 now")
    }

    @Test
    fun `normalizeName drops a leading hash and trims`() {
        assertThat(TagCodec.normalizeName("  #餐饮 ")).isEqualTo("餐饮")
        assertThat(TagCodec.normalizeName("##餐饮")).isEqualTo("餐饮")
        assertThat(TagCodec.normalizeName("餐饮")).isEqualTo("餐饮")
    }

    @Test
    fun `normalizeName rejects blank input`() {
        assertThat(TagCodec.normalizeName(null)).isNull()
        assertThat(TagCodec.normalizeName("   ")).isNull()
        assertThat(TagCodec.normalizeName("#")).isNull()
    }

    @Test
    fun `formatTags renders a canonical block and de-duplicates case insensitively`() {
        assertThat(TagCodec.formatTags(listOf("餐饮", "Food", "food")))
            .isEqualTo("#餐饮 #Food")
    }

    @Test
    fun `appendTags strips then appends, so it is idempotent`() {
        val once = TagCodec.appendTags("午饭 #旧的", listOf("新的"))
        assertThat(once).isEqualTo("午饭 #新的")
        assertThat(TagCodec.appendTags(once, listOf("新的"))).isEqualTo(once)
    }

    @Test
    fun `appendTags on empty text yields just the block`() {
        assertThat(TagCodec.appendTags("", listOf("a", "b"))).isEqualTo("#a #b")
        assertThat(TagCodec.appendTags(null, listOf("a"))).isEqualTo("#a")
    }

    @Test
    fun `appendTags with no tags returns the stripped text`() {
        assertThat(TagCodec.appendTags("午饭 #旧", emptyList())).isEqualTo("午饭")
    }

    @Test
    fun `round trip extract then append is stable`() {
        val text = "打车 报销 #出行 #交通"
        val tags = TagCodec.extract(text)
        assertThat(TagCodec.extract(TagCodec.appendTags(text, tags))).isEqualTo(tags)
    }
}
