package org.gnucash.android.ui.template

import android.os.Bundle
import android.os.Looper
import android.view.View
import android.widget.Spinner
import org.assertj.core.api.Assertions.assertThat
import org.gnucash.android.R
import org.gnucash.android.db.adapter.AccountsDbAdapter
import org.gnucash.android.model.Account
import org.gnucash.android.test.unit.GnuCashTest
import org.gnucash.android.ui.account.AccountSearchDialogFragment
import org.gnucash.android.ui.adapter.QualifiedAccountNameAdapter
import org.gnucash.android.ui.transaction.view.ExtendedViewsActivity
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf

/**
 * The two account rows of the template dialog must offer the same filterable
 * picker the transaction form has.
 *
 * A template is saved once and then replayed for months, so picking the wrong
 * account here is expensive — and a real book has more accounts than anyone
 * scrolls through. The test drives the dialog the way the user does: press the
 * button, and feed the picker's answer back through the fragment-result API.
 */
class TransactionTemplateSearchTest : GnuCashTest() {

    private lateinit var accountsDbAdapter: AccountsDbAdapter
    private lateinit var debit: Account
    private lateinit var credit: Account
    private lateinit var activity: ExtendedViewsActivity

    @Before
    fun setUp() {
        accountsDbAdapter = AccountsDbAdapter.instance
        debit = Account("模板借方")
        credit = Account("模板贷方")
        accountsDbAdapter.addRecord(credit)
        accountsDbAdapter.addRecord(debit)
        activity = Robolectric.buildActivity(ExtendedViewsActivity::class.java).setup().get()
    }

    @After
    fun tearDown() {
        accountsDbAdapter.deleteAllRecords()
    }

    private fun showDialog(): TransactionTemplateEditFragment {
        val fragment = TransactionTemplateEditFragment.newInstance(null)
        fragment.show(activity.supportFragmentManager, "transaction_template_edit")
        shadowOf(Looper.getMainLooper()).idle()
        return fragment
    }

    /** The account lists arrive on the main looper, which a Robolectric test drains by hand. */
    private fun awaitAccounts(spinner: Spinner) {
        val adapter = spinner.adapter as? QualifiedAccountNameAdapter ?: return
        val deadline = System.currentTimeMillis() + 5_000
        while (adapter.count == 0 && System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test
    fun `both sides offer a picker`() {
        val dialog = showDialog().dialog!!
        assertThat(dialog.findViewById<View>(R.id.btn_search_debit_account)).isNotNull()
        assertThat(dialog.findViewById<View>(R.id.btn_search_credit_account)).isNotNull()
    }

    @Test
    fun `the credit button opens the filterable account picker`() {
        val dialog = showDialog().dialog!!
        dialog.findViewById<View>(R.id.btn_search_credit_account).performClick()
        shadowOf(Looper.getMainLooper()).idle()

        val picker = activity.supportFragmentManager
            .findFragmentByTag(AccountSearchDialogFragment.DEFAULT_REQUEST_KEY)
        assertThat(picker)
            .`as`("the search button must open the account picker")
            .isInstanceOf(AccountSearchDialogFragment::class.java)
    }

    @Test
    fun `a picked account lands on the credit spinner`() {
        val dialog = showDialog().dialog!!
        val spinner = dialog.findViewById<Spinner>(R.id.input_credit_account_spinner)
        awaitAccounts(spinner)

        activity.supportFragmentManager.setFragmentResult(
            TransactionTemplateEditFragment.REQUEST_CREDIT_ACCOUNT_SEARCH,
            Bundle().apply { putString(AccountSearchDialogFragment.EXTRA_ACCOUNT_UID, credit.uid) }
        )
        shadowOf(Looper.getMainLooper()).idle()

        val adapter = spinner.adapter as QualifiedAccountNameAdapter
        assertThat(adapter.getUID(spinner.selectedItemPosition)).isEqualTo(credit.uid)
    }

    @Test
    fun `an unknown account leaves the spinner alone`() {
        val dialog = showDialog().dialog!!
        val spinner = dialog.findViewById<Spinner>(R.id.input_debit_account_spinner)
        awaitAccounts(spinner)
        val before = spinner.selectedItemPosition

        activity.supportFragmentManager.setFragmentResult(
            TransactionTemplateEditFragment.REQUEST_DEBIT_ACCOUNT_SEARCH,
            Bundle().apply { putString(AccountSearchDialogFragment.EXTRA_ACCOUNT_UID, "no-such-uid") }
        )
        shadowOf(Looper.getMainLooper()).idle()

        // -1 answers both "no such account" and "not loaded yet"; falling back to
        // row 0 would silently swap in an account the user never picked.
        assertThat(spinner.selectedItemPosition).isEqualTo(before)
    }
}
