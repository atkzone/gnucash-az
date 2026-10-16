package org.gnucash.android.ui.transaction

import android.content.Intent
import android.os.Looper
import android.view.View
import android.widget.EditText
import android.widget.Spinner
import org.assertj.core.api.Assertions.assertThat
import org.gnucash.android.R
import org.gnucash.android.db.adapter.AccountsDbAdapter
import org.gnucash.android.db.adapter.TransactionsDbAdapter
import org.gnucash.android.model.Account
import org.gnucash.android.model.Money
import org.gnucash.android.model.Split
import org.gnucash.android.model.Transaction
import org.gnucash.android.test.unit.GnuCashTest
import org.gnucash.android.ui.adapter.QualifiedAccountNameAdapter
import org.gnucash.android.ui.common.FormActivity
import org.gnucash.android.ui.common.UxArgument
import org.gnucash.android.ui.util.widget.CalculatorEditText
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import java.math.BigDecimal

/**
 * Regression guard for "tapping 修改 on any transaction bounces back to the list".
 *
 * The form fragment had no test coverage at all, so a crash raised while *building*
 * the edit view — `setSelection(0)` on the debit spinner whose account list had not
 * loaded yet — shipped unnoticed. Opening [FormActivity] with
 * [UxArgument.SELECTED_TRANSACTION_UID] must land on the form, in edit mode, with
 * the stored values on screen.
 */
class TransactionFormEditTest : GnuCashTest() {

    private lateinit var accountsDbAdapter: AccountsDbAdapter
    private lateinit var transactionsDbAdapter: TransactionsDbAdapter
    private lateinit var sourceAccount: Account
    private lateinit var transferAccount: Account
    private lateinit var transaction: Transaction

    @Before
    fun setUp() {
        // The debit / credit rows are only shown with double entry on; without it
        // the form would (correctly) hide them and the assertions below would lie.
        setDoubleEntryEnabled(true)

        accountsDbAdapter = AccountsDbAdapter.instance
        transactionsDbAdapter = TransactionsDbAdapter.instance

        sourceAccount = Account("Edit source")
        transferAccount = Account("Edit target")
        accountsDbAdapter.addRecord(transferAccount)
        accountsDbAdapter.addRecord(sourceAccount)

        transaction = Transaction("Edit me")
        transaction.notes = "notes"
        transaction.number = "42"
        val split = Split(Money(BigDecimal.TEN, sourceAccount.commodity), sourceAccount)
        transaction.addSplit(split)
        transaction.addSplit(split.createPair(transferAccount))
        transactionsDbAdapter.addRecord(transaction)
    }

    @After
    fun tearDown() {
        transactionsDbAdapter.deleteAllRecords()
        accountsDbAdapter.deleteAllRecords()
    }

    private fun editIntent(): Intent = Intent(context, FormActivity::class.java)
        .putExtra(UxArgument.FORM_TYPE, FormActivity.FormType.TRANSACTION.name)
        .putExtra(UxArgument.SELECTED_TRANSACTION_UID, transaction.uid)
        .putExtra(UxArgument.SELECTED_ACCOUNT_UID, sourceAccount.uid)

    @Test
    fun `editing a transaction opens the form in edit mode`() {
        val controller = Robolectric.buildActivity(FormActivity::class.java, editIntent()).setup()
        val activity = controller.get()

        val fragment = activity.supportFragmentManager
            .findFragmentById(R.id.fragment_container)
        assertThat(fragment).isInstanceOf(TransactionFormFragment::class.java)
        assertThat(activity.isFinishing)
            .`as`("the edit form must not bounce straight back to the list")
            .isFalse()

        val view = requireNotNull(fragment).requireView()
        assertThat(view.findViewById<EditText>(R.id.input_transaction_name).text.toString())
            .isEqualTo("Edit me")
        assertThat(view.findViewById<EditText>(R.id.number).text.toString()).isEqualTo("42")
        assertThat(view.findViewById<EditText>(R.id.notes).text.toString()).isEqualTo("notes")

        val amount = view.findViewById<CalculatorEditText>(R.id.input_transaction_amount).value
        assertThat(amount).isNotNull()
        assertThat(amount!!.abs()).isEqualByComparingTo(BigDecimal.TEN)

        controller.pause().stop().destroy()
    }

    @Test
    fun `editing shows the debit and credit account rows`() {
        val controller = Robolectric.buildActivity(FormActivity::class.java, editIntent()).setup()
        val fragment = controller.get().supportFragmentManager
            .findFragmentById(R.id.fragment_container)
        val view = requireNotNull(fragment).requireView()

        assertThat(view.findViewById<View>(R.id.layout_debit_entry).visibility)
            .isEqualTo(View.VISIBLE)
        assertThat(view.findViewById<View>(R.id.layout_double_entry).visibility)
            .isEqualTo(View.VISIBLE)
        assertThat(view.findViewById<Spinner>(R.id.input_debit_account_spinner)).isNotNull()
        assertThat(view.findViewById<Spinner>(R.id.input_transfer_account_spinner)).isNotNull()

        controller.pause().stop().destroy()
    }

    /**
     * The reported bug: 修改 opened from the **credit** side showed the same account
     * on both rows.
     *
     * 搜索结果 → 修改 always opens the form from the credit account
     * (`getDefaultAccountUID(CREDIT)`). The credit row used to be filled from "the
     * split whose account is not the opening one" — which, opened from the credit
     * side, *is* the debit split — and the credit list also excluded the opening
     * account, so the real credit account was not even selectable and the row fell
     * back to the first entry (the debit account).
     */
    @Test
    fun `editing from the credit side keeps the two rows apart`() {
        val controller = Robolectric
            .buildActivity(FormActivity::class.java, editIntentFrom(transferAccount))
            .setup()
        val fragment = controller.get().supportFragmentManager
            .findFragmentById(R.id.fragment_container)
        val view = requireNotNull(fragment).requireView()

        val debitSpinner = view.findViewById<Spinner>(R.id.input_debit_account_spinner)
        val creditSpinner = view.findViewById<Spinner>(R.id.input_transfer_account_spinner)
        awaitAccounts(debitSpinner)
        awaitAccounts(creditSpinner)

        val debitUid = selectedUid(debitSpinner)
        val creditUid = selectedUid(creditSpinner)
        assertThat(debitUid).`as`("借方账户").isEqualTo(sourceAccount.uid)
        assertThat(creditUid).`as`("贷方账户").isEqualTo(transferAccount.uid)
        assertThat(debitUid).`as`("两侧不能是同一个账户").isNotEqualTo(creditUid)

        controller.pause().stop().destroy()
    }

    /** Opens the edit form from [account], the way the list and the search results do. */
    private fun editIntentFrom(account: Account): Intent =
        Intent(context, FormActivity::class.java)
            .putExtra(UxArgument.FORM_TYPE, FormActivity.FormType.TRANSACTION.name)
            .putExtra(UxArgument.SELECTED_TRANSACTION_UID, transaction.uid)
            .putExtra(UxArgument.SELECTED_ACCOUNT_UID, account.uid)

    private fun selectedUid(spinner: Spinner): String? =
        (spinner.adapter as QualifiedAccountNameAdapter).getUID(spinner.selectedItemPosition)

    /**
     * The account lists load on an IO coroutine and come back on the main looper,
     * which a Robolectric test has to drain by hand.
     */
    private fun awaitAccounts(spinner: Spinner) {
        val adapter = spinner.adapter as? QualifiedAccountNameAdapter ?: return
        val deadline = System.currentTimeMillis() + 5_000
        while (adapter.count == 0 && System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
        shadowOf(Looper.getMainLooper()).idle()
    }
}
