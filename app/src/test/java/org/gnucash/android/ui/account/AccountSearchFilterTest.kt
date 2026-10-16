package org.gnucash.android.ui.account

import org.assertj.core.api.Assertions.assertThat
import org.junit.Test

/**
 * Matching rules of the account picker.
 *
 * Pure logic, so it runs without Robolectric — the dialog itself only renders
 * whatever this returns.
 */
class AccountSearchFilterTest {

    private val assets = AccountOption("a1", "Assets:Bank:Checking", "Checking")
    private val assetsSavings = AccountOption("a2", "Assets:Bank:Savings", "Savings")
    private val expenses = AccountOption("e1", "Expenses:Food:Lunch", "Lunch")
    private val liabilities = AccountOption("l1", "Liabilities:Bank:Loan", "Loan")
    private val chinese = AccountOption("c1", "支出:餐饮:午饭", "午饭")
    private val all = listOf(assets, assetsSavings, expenses, liabilities, chinese)

    @Test
    fun `a blank query keeps every account in its original order`() {
        assertThat(AccountSearchFilter.filter(all, "")).isEqualTo(all)
        assertThat(AccountSearchFilter.filter(all, "   ")).isEqualTo(all)
        assertThat(AccountSearchFilter.filter(all, null)).isEqualTo(all)
    }

    @Test
    fun `matching is case insensitive`() {
        assertThat(AccountSearchFilter.filter(all, "checking")).containsExactly(assets)
        assertThat(AccountSearchFilter.filter(all, "CHECKING")).containsExactly(assets)
    }

    @Test
    fun `a token matches anywhere in the qualified name`() {
        // "bank" is neither a prefix nor the leaf name — still a hit.
        assertThat(AccountSearchFilter.filter(all, "bank"))
            .containsExactlyInAnyOrder(assets, assetsSavings, liabilities)
    }

    @Test
    fun `the leaf name matches too`() {
        assertThat(AccountSearchFilter.filter(all, "sav")).containsExactly(assetsSavings)
    }

    @Test
    fun `several tokens must all match`() {
        // "assets sav" is not a substring of the qualified name, but both tokens
        // are, so a user can skip the hierarchy separators when typing.
        assertThat(AccountSearchFilter.filter(all, "assets sav")).containsExactly(assetsSavings)
        assertThat(AccountSearchFilter.filter(all, "assets lunch")).isEmpty()
    }

    @Test
    fun `extra whitespace never defeats a query`() {
        assertThat(AccountSearchFilter.filter(all, "  assets   sav  "))
            .containsExactly(assetsSavings)
    }

    @Test
    fun `a qualified-name prefix hit is listed before a plain substring hit`() {
        // Both contain "ba"; only the second one starts with it. The input order
        // puts the non-prefix hit first, so only the ranking can move it down.
        val substring = AccountOption("s1", "Assets:Bank", "Bank")
        val prefix = AccountOption("p1", "Bank:Assets", "Assets")

        assertThat(AccountSearchFilter.filter(listOf(substring, prefix), "ba"))
            .containsExactly(prefix, substring)
    }

    @Test
    fun `a leaf-name prefix hit is listed before a plain substring hit`() {
        val substring = AccountOption("s1", "Expenses:Bank fees", "Fees")
        val leafPrefix = AccountOption("p1", "Clearing:Old bank", "Bank charge")

        assertThat(AccountSearchFilter.filter(listOf(substring, leafPrefix), "ban"))
            .containsExactly(leafPrefix, substring)
    }

    @Test
    fun `input order is kept when nothing is a prefix hit`() {
        // All three only contain "bank", so the caller's order must survive.
        assertThat(AccountSearchFilter.filter(all, "bank"))
            .containsExactly(assets, assetsSavings, liabilities)
    }

    @Test
    fun `chinese account names match`() {
        assertThat(AccountSearchFilter.filter(all, "午饭")).containsExactly(chinese)
        assertThat(AccountSearchFilter.filter(all, "餐饮")).containsExactly(chinese)
    }

    @Test
    fun `an unknown query matches nothing`() {
        assertThat(AccountSearchFilter.filter(all, "zzz")).isEmpty()
    }

    @Test
    fun `tokenize splits on whitespace runs`() {
        assertThat(AccountSearchFilter.tokenize(" a  b ")).containsExactly("a", "b")
        assertThat(AccountSearchFilter.tokenize("")).isEmpty()
        assertThat(AccountSearchFilter.tokenize(null)).isEmpty()
    }
}
