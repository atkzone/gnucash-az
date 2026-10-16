package org.gnucash.android.util

/**
 * The single place where the `#tag` text convention is parsed and produced
 * (feature ⑧).
 *
 * GnuCash desktop has no tag entity: a "tag" is just a `#word` token inside
 * description / notes / memo. To stay compatible the app keeps that text **and**
 * mirrors the tags into `tags` / `transaction_tags` (plan §6.3), so the same
 * rules are needed by the writer, the transaction form, the importers and the
 * display layer. They live here rather than in any of those call sites.
 *
 * Rules (deliberately narrow so ordinary text is never mangled):
 *  - a tag starts at a `#` which is at the beginning of the text or preceded by a
 *    non-tag character (so `a#b` / `issue#5` / `C#` is *not* a tag); a CJK
 *    character also counts as a boundary, so `午饭#餐饮` *is* a tag;
 *  - it ends at whitespace or a punctuation character;
 *  - the name excludes the leading `#`; an empty name is not a tag.
 */
object TagCodec {
    const val PREFIX: Char = '#'

    /**
     * Characters that terminate a tag name. Whitespace is handled separately by
     * [Char.isWhitespace]. `#` is included so `#a#b` cannot produce a longer name.
     */
    private const val TERMINATORS = "#,，。、;；:：!！?？\"'“”‘’()（）[]【】{}<>《》/\\|+*&=@\$%^*~`"

    /** Characters that count as part of a tag name. */
    fun isTagChar(c: Char): Boolean = !c.isWhitespace() && TERMINATORS.indexOf(c) < 0

    /**
     * True for CJK ideographs. A `#` directly after one of these still starts a
     * tag (`午饭#餐饮`), while `issue#5` or `C#` do not — the difference matters
     * for the app's primary audience.
     */
    private fun isCjk(c: Char): Boolean =
        c.code in 0x4E00..0x9FFF || c.code in 0x3400..0x4DBF || c.code in 0xF900..0xFAFF

    /** Whether a tag token starts at index [i] of [text]. */
    private fun startsTagAt(text: String, i: Int): Boolean {
        if (i < 0 || i >= text.length || text[i] != PREFIX) return false
        if (i == 0) return true
        val previous = text[i - 1]
        return !isTagChar(previous) || isCjk(previous)
    }

    /**
     * Cleans a user-supplied tag name: trims, drops a leading `#`, trims again.
     * Returns `null` when nothing is left (so callers never create an empty tag).
     */
    fun normalizeName(raw: String?): String? {
        val trimmed = raw?.trim() ?: return null
        val withoutPrefix = trimmed.trimStart(PREFIX).trim()
        return withoutPrefix.ifEmpty { null }
    }

    /**
     * All distinct tag names (without `#`) contained in [text], in first-seen
     * order. Matching is exact on the name; case-insensitive de-duplication is the
     * registry's job (`tags.name` is `COLLATE NOCASE`).
     */
    fun extract(text: String?): List<String> {
        if (text.isNullOrEmpty()) return emptyList()
        val found = LinkedHashSet<String>()
        var i = 0
        while (i < text.length) {
            if (!startsTagAt(text, i)) {
                i++
                continue
            }
            var end = i + 1
            while (end < text.length && isTagChar(text[end])) end++
            val name = text.substring(i + 1, end).trim()
            if (name.isNotEmpty()) {
                found.add(name)
                i = end
            } else {
                i++
            }
        }
        return found.toList()
    }

    /**
     * [text] with every tag token removed, along with at most one space that
     * separated it from the surrounding text. Used before showing text in the UI
     * (the tags themselves are shown as chips).
     */
    fun stripTags(text: String?): String {
        if (text.isNullOrEmpty()) return text.orEmpty()
        val sb = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            if (startsTagAt(text, i)) {
                var end = i + 1
                while (end < text.length && isTagChar(text[end])) end++
                if (end > i + 1) {
                    // drop the space we already emitted in front of the tag, if any
                    if (sb.isNotEmpty() && sb[sb.length - 1] == ' ') {
                        sb.setLength(sb.length - 1)
                    }
                    i = end
                    // and one space right after the tag, so "a #t b" -> "a b"
                    if (i < text.length && text[i] == ' ') i++
                    continue
                }
            }
            sb.append(text[i])
            i++
        }
        return sb.toString().trim()
    }

    /** Renders [tags] as a canonical `#a #b` block (empty when there are none). */
    fun formatTags(tags: Collection<String>): String {
        return tags.mapNotNull { normalizeName(it) }
            .distinctBy { it.lowercase(java.util.Locale.ROOT) }
            .joinToString(" ") { "$PREFIX$it" }
    }

    /**
     * Returns [text] with a canonical trailing tag block for [tags].
     *
     * Existing tag tokens are removed first, so this is idempotent and never
     * duplicates a tag. Tags live in the transaction **notes** (one place per
     * transaction, matching the transaction-level tag model).
     */
    fun appendTags(text: String?, tags: Collection<String>): String {
        val base = stripTags(text)
        val block = formatTags(tags)
        return when {
            block.isEmpty() -> base
            base.isEmpty() -> block
            else -> "$base $block"
        }
    }
}
