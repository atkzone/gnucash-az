package org.gnucash.android.ui.account

/**
 * One account as the picker sees it: the qualified name is what the user reads,
 * the short name is the leaf the user usually remembers.
 */
data class AccountOption(
    val uid: String,
    val qualifiedName: String,
    val shortName: String
)

/**
 * Matching rules behind the account picker.
 *
 * Kept as pure Kotlin (no Android imports) on purpose: account lists run into the
 * hundreds, so "which rows survive the query, and in which order" is the part
 * worth pinning down with tests instead of eyeballing the dialog.
 *
 * Rules:
 *  - the query is split on whitespace; **every** token must match (AND), so
 *    `ass bank` finds "Assets:Bank" without the user typing the colon;
 *  - matching is case-insensitive and substring-based, on either the qualified
 *    name or the short name;
 *  - results keep the caller's order except that prefix hits bubble up, first on
 *    the qualified name, then on the short name (so typing "ba" puts
 *    "Assets:Bank" above "Liabilities:Barclays").
 *  - a blank query returns the list untouched, so opening the dialog shows
 *    everything.
 */
object AccountSearchFilter {

    private val WHITESPACE = Regex("\\s+")

    fun filter(options: List<AccountOption>, query: String?): List<AccountOption> {
        val tokens = tokenize(query)
        if (tokens.isEmpty()) return options

        val head = tokens.first()
        return options
            .withIndex()
            .filter { matches(it.value, tokens) }
            .sortedWith(
                compareBy(
                    { if (it.value.qualifiedName.startsWith(head, ignoreCase = true)) 0 else 1 },
                    { if (it.value.shortName.startsWith(head, ignoreCase = true)) 0 else 1 },
                    { it.index }
                )
            )
            .map { it.value }
    }

    /** Splits a raw query into the tokens that must all match. */
    fun tokenize(query: String?): List<String> =
        query.orEmpty().trim().split(WHITESPACE).filter { it.isNotEmpty() }

    private fun matches(option: AccountOption, tokens: List<String>): Boolean =
        tokens.all { token ->
            option.qualifiedName.contains(token, ignoreCase = true) ||
                option.shortName.contains(token, ignoreCase = true)
        }
}
