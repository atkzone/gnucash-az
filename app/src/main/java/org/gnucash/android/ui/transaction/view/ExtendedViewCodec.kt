package org.gnucash.android.ui.transaction.view

/**
 * Text payload for the extended views, stored as one book preference.
 *
 * One record per line, `id TAB name TAB field,field`; the separators inside the
 * free-text fields are backslash-escaped, so a name may contain anything the
 * user can type without breaking the record structure. Decoding is total: a
 * malformed line is dropped instead of throwing, because the payload is user
 * data from an older install and losing the whole list over one bad line would
 * be the worse outcome.
 */
object ExtendedViewCodec {

    private const val RECORD_SEPARATOR = '\n'
    private const val PART_SEPARATOR = '\t'
    private const val FIELD_SEPARATOR = ','

    fun encode(views: List<ExtendedView>): String =
        views.joinToString(RECORD_SEPARATOR.toString()) { encodeRecord(it) }

    private fun encodeRecord(view: ExtendedView): String {
        // Enum order, not insertion order: the payload has to be canonical so a
        // re-save of an untouched view produces the identical string.
        val fields = ExtendedViewField.values().filter { it in view.fields }
        return escape(view.id) + PART_SEPARATOR + escape(view.name) + PART_SEPARATOR +
                fields.joinToString(FIELD_SEPARATOR.toString()) { it.name }
    }

    /** @return the decoded views; never `null`, and never throws. */
    fun decode(payload: String?): List<ExtendedView> {
        if (payload.isNullOrEmpty()) return emptyList()
        val views = ArrayList<ExtendedView>()
        val seen = HashSet<String>()
        for (line in payload.split(RECORD_SEPARATOR)) {
            if (line.isEmpty()) continue
            val view = decodeRecord(line) ?: continue
            if (!seen.add(view.id)) continue
            views.add(view)
        }
        return views
    }

    private fun decodeRecord(line: String): ExtendedView? {
        val parts = line.split(PART_SEPARATOR)
        if (parts.size != 3) return null
        val id = unescape(parts[0]).takeIf { it.isNotBlank() } ?: return null
        val name = unescape(parts[1]).takeIf { it.isNotBlank() } ?: return null
        val fields = parts[2]
            .split(FIELD_SEPARATOR)
            .mapNotNull { ExtendedViewField.ofName(it) }
            .toSet()
        return ExtendedView(id, name, fields)
    }

    private fun escape(value: String): String {
        val builder = StringBuilder(value.length + 8)
        for (char in value) {
            when (char) {
                '\\' -> builder.append("\\\\")
                '\n' -> builder.append("\\n")
                '\r' -> builder.append("\\r")
                '\t' -> builder.append("\\t")
                else -> builder.append(char)
            }
        }
        return builder.toString()
    }

    private fun unescape(value: String): String {
        val builder = StringBuilder(value.length)
        var index = 0
        while (index < value.length) {
            val char = value[index]
            if (char == '\\' && index + 1 < value.length) {
                index++
                when (val escaped = value[index]) {
                    '\\' -> builder.append('\\')
                    'n' -> builder.append('\n')
                    'r' -> builder.append('\r')
                    't' -> builder.append('\t')
                    // An unknown escape is kept verbatim rather than dropped.
                    else -> builder.append('\\').append(escaped)
                }
            } else {
                builder.append(char)
            }
            index++
        }
        return builder.toString()
    }
}
