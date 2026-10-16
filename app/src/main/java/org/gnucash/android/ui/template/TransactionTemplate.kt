package org.gnucash.android.ui.template

import org.json.JSONObject
import java.math.BigDecimal

/**
 * A named, replayable *transaction* template (feature ①d).
 *
 * Persisted as [TransactionTemplateCodec.encode] in a single opaque column
 * (`transaction_templates.payload_json`), so the field set can grow without a
 * schema change — the same trick the saved-search templates use.
 *
 * ## The "空值" amount
 * [amount] is **nullable on purpose**: a template may pin a fixed amount, or
 * deliberately leave it open ("空值") so the amount is typed each time the
 * template is used. `null` is therefore *not* the same thing as `0` and must
 * survive a round trip byte-for-byte — see [TransactionTemplateCodec].
 *
 * Everything else is plain text/`uid` data; accounts are stored by `uid` and
 * resolved to [org.gnucash.android.model.Account] only when the template is
 * actually applied.
 */
data class TransactionTemplate(
    /** Display name; also the key used for "same name overwrites". */
    var name: String = "",

    /** Account holding the **debit** (借) split. `null` = let the caller decide. */
    var debitAccountUid: String? = null,

    /** Account holding the **credit** (贷) split. `null` = let the caller decide. */
    var creditAccountUid: String? = null,

    /**
     * The transaction amount as a magnitude, or `null` for "空值" (fill in later).
     * Never negative — the side is expressed by which account is the debit one.
     */
    var amount: BigDecimal? = null,

    var description: String = "",
    var notes: String = "",
    var number: String = "",

    /** Memo of the debit split. */
    var debitMemo: String = "",

    /** Memo of the credit split. */
    var creditMemo: String = "",

    var id: Long = 0L,
    var sortOrder: Int = 0,
    var createdAt: Long = 0L,
    var updatedAt: Long = 0L,

    /**
     * Set by [TransactionTemplateDbAdapter] when the stored payload could not be
     * decoded. Such a row stays listed (so it can be inspected/renamed/deleted)
     * but must refuse to be applied.
     */
    var decodeFailed: Boolean = false
) {

    /** `true` when the amount is pinned; `false` for "空值" (prompt at use time). */
    val hasFixedAmount: Boolean get() = amount != null

    /** Both sides named — the minimum for a replayable two-split transaction. */
    val isUsable: Boolean
        get() = !decodeFailed &&
                !debitAccountUid.isNullOrEmpty() &&
                !creditAccountUid.isNullOrEmpty() &&
                debitAccountUid != creditAccountUid
}

/**
 * JSON (de)serialisation of a [TransactionTemplate].
 *
 * Pure — depends only on `org.json`, no Android/DB, so it is fully
 * unit-testable. Two invariants matter:
 *
 * 1. **"空值" is preserved.** A `null` amount omits the key entirely; it must
 *    never come back as `0`, or a template meant to prompt for the amount would
 *    silently post a zero transaction.
 * 2. **Absent keys stay absent.** Only non-empty strings are written, so a
 *    template saved with blank fields round-trips to blanks (not to `"null"`).
 */
object TransactionTemplateCodec {

    private const val KEY_VERSION = "version"

    private const val KEY_DEBIT_ACCOUNT = "debit_account_uid"
    private const val KEY_CREDIT_ACCOUNT = "credit_account_uid"
    private const val KEY_AMOUNT = "amount"
    private const val KEY_DESCRIPTION = "description"
    private const val KEY_NOTES = "notes"
    private const val KEY_NUMBER = "number"
    private const val KEY_DEBIT_MEMO = "debit_memo"
    private const val KEY_CREDIT_MEMO = "credit_memo"

    /** Payload format version. Bump only for a breaking change. */
    const val CURRENT_VERSION = 1

    fun encode(template: TransactionTemplate): String {
        val json = JSONObject()
        json.put(KEY_VERSION, CURRENT_VERSION)

        template.debitAccountUid?.takeIf { it.isNotEmpty() }
            ?.let { json.put(KEY_DEBIT_ACCOUNT, it) }
        template.creditAccountUid?.takeIf { it.isNotEmpty() }
            ?.let { json.put(KEY_CREDIT_ACCOUNT, it) }

        // null ⇒ no key at all. A present key with "0" means the user really
        // pinned a zero amount.
        template.amount?.let { json.put(KEY_AMOUNT, it.toPlainString()) }

        putIfNotEmpty(json, KEY_DESCRIPTION, template.description)
        putIfNotEmpty(json, KEY_NOTES, template.notes)
        putIfNotEmpty(json, KEY_NUMBER, template.number)
        putIfNotEmpty(json, KEY_DEBIT_MEMO, template.debitMemo)
        putIfNotEmpty(json, KEY_CREDIT_MEMO, template.creditMemo)

        return json.toString()
    }

    /**
     * Decodes a payload produced by [encode].
     *
     * @throws org.json.JSONException when [json] is not a JSON object at all.
     * @throws IllegalArgumentException when [json] is `null`-ish/blank, or when a
     *         stored amount is not a parseable number (a corrupt payload must be
     *         reported, never quietly turned into a zero amount).
     */
    fun decode(json: String?): TransactionTemplate {
        require(!json.isNullOrBlank()) { "Empty transaction-template payload" }
        val obj = JSONObject(json)

        return TransactionTemplate(
            debitAccountUid = obj.optStringOrNull(KEY_DEBIT_ACCOUNT),
            creditAccountUid = obj.optStringOrNull(KEY_CREDIT_ACCOUNT),
            amount = obj.optAmount(KEY_AMOUNT),
            description = obj.optString(KEY_DESCRIPTION, ""),
            notes = obj.optString(KEY_NOTES, ""),
            number = obj.optString(KEY_NUMBER, ""),
            debitMemo = obj.optString(KEY_DEBIT_MEMO, ""),
            creditMemo = obj.optString(KEY_CREDIT_MEMO, "")
        )
    }

    private fun putIfNotEmpty(json: JSONObject, key: String, value: String) {
        if (value.isNotEmpty()) json.put(key, value)
    }
}

/** Absent key ⇒ `null`; present-but-blank is treated as absent too. */
private fun JSONObject.optStringOrNull(key: String): String? =
    if (!has(key) || isNull(key)) null else optString(key).takeIf { it.isNotEmpty() }

/** Absent key ⇒ "空值" (`null`). Present ⇒ must parse as a decimal. */
private fun JSONObject.optAmount(key: String): BigDecimal? {
    if (!has(key) || isNull(key)) return null
    val raw = optString(key)
    return BigDecimal(raw)
}
