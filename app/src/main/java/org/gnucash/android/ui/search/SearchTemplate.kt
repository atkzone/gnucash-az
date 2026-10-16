package org.gnucash.android.ui.search

import org.gnucash.android.model.Account
import org.joda.time.LocalDate
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.math.BigDecimal

/** Which search screen a saved entry belongs to. */
enum class SearchTemplateKind {
    /** A [SearchForm] criteria set, saved from the "高级搜索" screen. */
    Form,

    /**
     * A raw full-text query string ([query]), saved from the "全文搜索" screen.
     * Kept as the original text so it can be edited and recompiled by
     * [FullTextQuery] — the compiled SQL depends on the parser's current
     * grammar, so storing the text keeps the favourite reproducible.
     */
    FullText
}

/**
 * A saved, named search ("常用搜索"). Persistence stores [SearchTemplateCodec.encode]
 * of the whole entry in a single opaque column so the payload can evolve without
 * touching the table schema.
 *
 * [kind] tells the two screens apart; [query] carries the raw text for
 * [SearchTemplateKind.FullText] (and is `null` for a form entry).
 */
data class SearchTemplate(
    var name: String = "",
    var form: SearchForm = SearchForm(),
    var id: Long = 0L,
    var sortOrder: Int = 0,
    var createdAt: Long = 0L,
    var updatedAt: Long = 0L,
    var kind: SearchTemplateKind = SearchTemplateKind.Form,
    var query: String? = null
)

/**
 * JSON (de)serialisation of a [SearchForm] and of a whole [SearchTemplate].
 * Pure — no Android/DB dependency beyond `org.json`, so it is fully
 * unit-testable.
 *
 * Account criteria are stored by `uid`; decoding needs a resolver because the
 * `Account` object itself is not part of the serialised payload.
 *
 * The payload doubles as an envelope: a form entry is the bare criteria object
 * (unchanged since the first version), while a [SearchTemplateKind.FullText]
 * entry adds a `kind` discriminator plus the raw `query`. Encoders from either
 * direction stay readable by the other, so the single opaque table column keeps
 * holding both.
 */
object SearchTemplateCodec {
    const val FORM_VERSION = 1

    private const val KEY_VERSION = "form_version"
    private const val KEY_COMPARISON = "comparison"
    private const val KEY_CRITERIA = "criteria"
    /** Discriminator for the payload envelope (absent == [SearchTemplateKind.Form]). */
    private const val KEY_KIND = "kind"
    /** Raw full-text query text ([SearchTemplateKind.FullText] only). */
    private const val KEY_QUERY = "query"
    private const val KIND_FULL_TEXT = "full_text"
    private const val KEY_TYPE = "type"
    private const val KEY_VALUE = "value"
    private const val KEY_COMPARE = "compare"
    private const val KEY_TERMS = "terms"
    private const val KEY_OP = "op"
    private const val KEY_TAGS = "tags"
    private const val KEY_MATCH = "match"
    private const val KEY_UID = "uid"

    private const val TYPE_DESCRIPTION = "description"
    private const val TYPE_NOTE = "note"
    private const val TYPE_MEMO = "memo"
    private const val TYPE_NUMBER = "number"
    private const val TYPE_ANY_FIELD = "any_field"
    private const val TYPE_TAG = "tag"
    private const val TYPE_DATE = "date"
    private const val TYPE_NUMERIC = "numeric"
    private const val TYPE_ACCOUNT = "account"

    fun encode(form: SearchForm): String {
        val root = JSONObject()
        root.put(KEY_VERSION, FORM_VERSION)
        root.put(KEY_COMPARISON, form.comparisonType.name)
        val array = JSONArray()
        for (criterion in form.criteria) {
            array.put(encodeCriterion(criterion))
        }
        root.put(KEY_CRITERIA, array)
        return root.toString()
    }

    fun decode(json: String, accountResolver: (String) -> Account? = { null }): SearchForm {
        val root = JSONObject(json)
        val comparison = enumOrDefault(root.optString(KEY_COMPARISON), ComparisonType.All)
        val form = SearchForm(comparison)
        val array = root.optJSONArray(KEY_CRITERIA) ?: JSONArray()
        val criteria = mutableListOf<SearchCriteria>()
        for (i in 0 until array.length()) {
            decodeCriterion(array.optJSONObject(i), accountResolver)?.let { criteria.add(it) }
        }
        form.restore(criteria, comparison)
        return form
    }

    /**
     * Encodes a whole [template] (kind-aware).
     *
     * A [SearchTemplateKind.Form] entry stays byte-identical to the historical
     * [encode] of its form, so rows written by previous versions keep decoding
     * unchanged; only a [SearchTemplateKind.FullText] entry carries the envelope
     * with a `kind` discriminator.
     */
    fun encode(template: SearchTemplate): String = when (template.kind) {
        SearchTemplateKind.FullText -> JSONObject().apply {
            put(KEY_VERSION, FORM_VERSION)
            put(KEY_KIND, KIND_FULL_TEXT)
            put(KEY_QUERY, template.query.orEmpty())
        }.toString()

        SearchTemplateKind.Form -> encode(template.form)
    }

    /**
     * The kind of a stored payload. Anything that is not an explicit full-text
     * envelope reads as [SearchTemplateKind.Form] — that keeps every row written
     * before this distinction existed working untouched.
     */
    fun kindOf(json: String): SearchTemplateKind {
        val kind = try {
            JSONObject(json).optString(KEY_KIND)
        } catch (e: JSONException) {
            return SearchTemplateKind.Form
        }
        return if (kind == KIND_FULL_TEXT) SearchTemplateKind.FullText else SearchTemplateKind.Form
    }

    /** The stored raw query of a full-text entry, or `null` for any other kind. */
    fun fullTextQueryOf(json: String): String? {
        if (kindOf(json) != SearchTemplateKind.FullText) return null
        return try {
            JSONObject(json).optString(KEY_QUERY).takeIf { it.isNotEmpty() }
        } catch (e: JSONException) {
            null
        }
    }

    private fun encodeCriterion(criterion: SearchCriteria): JSONObject = JSONObject().also { o ->
        when (criterion) {
            is SearchCriteria.Description -> {
                o.put(KEY_TYPE, TYPE_DESCRIPTION)
                o.putStringOrNull(KEY_VALUE, criterion.value)
                o.put(KEY_COMPARE, criterion.compare.name)
                o.putStrings(KEY_TERMS, criterion.terms)
                o.put(KEY_OP, criterion.op.name)
            }

            is SearchCriteria.Note -> {
                o.put(KEY_TYPE, TYPE_NOTE)
                o.putStringOrNull(KEY_VALUE, criterion.value)
                o.put(KEY_COMPARE, criterion.compare.name)
                o.putStrings(KEY_TERMS, criterion.terms)
                o.put(KEY_OP, criterion.op.name)
            }

            is SearchCriteria.Memo -> {
                o.put(KEY_TYPE, TYPE_MEMO)
                o.putStringOrNull(KEY_VALUE, criterion.value)
                o.put(KEY_COMPARE, criterion.compare.name)
                o.putStrings(KEY_TERMS, criterion.terms)
                o.put(KEY_OP, criterion.op.name)
            }

            is SearchCriteria.Number -> {
                o.put(KEY_TYPE, TYPE_NUMBER)
                o.putStringOrNull(KEY_VALUE, criterion.value)
                o.put(KEY_COMPARE, criterion.compare.name)
                o.putStrings(KEY_TERMS, criterion.terms)
                o.put(KEY_OP, criterion.op.name)
            }

            is SearchCriteria.AnyField -> {
                o.put(KEY_TYPE, TYPE_ANY_FIELD)
                o.putStringOrNull(KEY_VALUE, criterion.value)
                o.put(KEY_COMPARE, criterion.compare.name)
                o.putStrings(KEY_TERMS, criterion.terms)
                o.put(KEY_OP, criterion.op.name)
            }

            is SearchCriteria.Tag -> {
                o.put(KEY_TYPE, TYPE_TAG)
                o.putStrings(KEY_TAGS, criterion.tags)
                o.put(KEY_OP, criterion.op.name)
            }

            is SearchCriteria.Date -> {
                o.put(KEY_TYPE, TYPE_DATE)
                criterion.value?.let { o.put(KEY_VALUE, it.toString()) }
                o.put(KEY_COMPARE, criterion.compare.name)
            }

            is SearchCriteria.Numeric -> {
                o.put(KEY_TYPE, TYPE_NUMERIC)
                criterion.value?.let { o.put(KEY_VALUE, it.toPlainString()) }
                criterion.match?.let { o.put(KEY_MATCH, it.name) }
                o.put(KEY_COMPARE, criterion.compare.name)
            }

            is SearchCriteria.Account -> {
                o.put(KEY_TYPE, TYPE_ACCOUNT)
                criterion.resolvedUid()?.let { o.put(KEY_UID, it) }
                o.put(KEY_COMPARE, criterion.compare.name)
            }
        }
    }

    private fun decodeCriterion(
        o: JSONObject?,
        accountResolver: (String) -> Account?
    ): SearchCriteria? {
        if (o == null) return null
        val op = enumOrDefault(o.optString(KEY_OP), ComparisonType.All)
        return when (o.optString(KEY_TYPE)) {
            TYPE_DESCRIPTION -> SearchCriteria.Description(
                value = o.stringOrNull(KEY_VALUE),
                compare = enumOrDefault(o.optString(KEY_COMPARE), StringCompare.Contains),
                terms = o.strings(KEY_TERMS).toMutableList(),
                op = op
            )

            TYPE_NOTE -> SearchCriteria.Note(
                value = o.stringOrNull(KEY_VALUE),
                compare = enumOrDefault(o.optString(KEY_COMPARE), StringCompare.Contains),
                terms = o.strings(KEY_TERMS).toMutableList(),
                op = op
            )

            TYPE_MEMO -> SearchCriteria.Memo(
                value = o.stringOrNull(KEY_VALUE),
                compare = enumOrDefault(o.optString(KEY_COMPARE), StringCompare.Contains),
                terms = o.strings(KEY_TERMS).toMutableList(),
                op = op
            )

            TYPE_NUMBER -> SearchCriteria.Number(
                value = o.stringOrNull(KEY_VALUE),
                compare = enumOrDefault(o.optString(KEY_COMPARE), StringCompare.Contains),
                terms = o.strings(KEY_TERMS).toMutableList(),
                op = op
            )

            TYPE_ANY_FIELD -> SearchCriteria.AnyField(
                value = o.stringOrNull(KEY_VALUE),
                compare = enumOrDefault(o.optString(KEY_COMPARE), StringCompare.Contains),
                terms = o.strings(KEY_TERMS).toMutableList(),
                op = op
            )

            TYPE_TAG -> SearchCriteria.Tag(
                tags = o.strings(KEY_TAGS).toMutableList(),
                op = op
            )

            TYPE_DATE -> SearchCriteria.Date(
                value = o.stringOrNull(KEY_VALUE)?.let { LocalDate.parse(it) },
                compare = enumOrDefault(o.optString(KEY_COMPARE), Compare.LessThanOrEqualTo)
            )

            TYPE_NUMERIC -> SearchCriteria.Numeric(
                value = o.stringOrNull(KEY_VALUE)?.let { BigDecimal(it) },
                match = enumOrNull<NumericMatch>(o.optString(KEY_MATCH)),
                compare = enumOrDefault(o.optString(KEY_COMPARE), Compare.EqualTo)
            )

            TYPE_ACCOUNT -> {
                val uid = o.stringOrNull(KEY_UID)
                SearchCriteria.Account(
                    value = uid?.let(accountResolver),
                    compare = enumOrDefault(o.optString(KEY_COMPARE), ComparisonType.Any),
                    uid = uid
                )
            }

            else -> null
        }
    }

    private inline fun <reified T : Enum<T>> enumOrDefault(name: String, fallback: T): T =
        enumOrNull<T>(name) ?: fallback

    private inline fun <reified T : Enum<T>> enumOrNull(name: String): T? {
        if (name.isEmpty()) return null
        return enumValues<T>().firstOrNull { it.name == name }
    }

    private fun JSONObject.putStringOrNull(key: String, value: String?) {
        if (value != null) put(key, value)
    }

    private fun JSONObject.stringOrNull(key: String): String? {
        if (!has(key) || isNull(key)) return null
        return getString(key)
    }

    private fun JSONObject.putStrings(key: String, values: List<String>) {
        val array = JSONArray()
        values.forEach { array.put(it) }
        put(key, array)
    }

    private fun JSONObject.strings(key: String): List<String> {
        val array = optJSONArray(key) ?: return emptyList()
        val out = mutableListOf<String>()
        for (i in 0 until array.length()) out.add(array.optString(i))
        return out
    }
}
