package org.gnucash.android.ui.template

import android.app.Dialog
import android.os.Bundle
import android.widget.Spinner
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import org.gnucash.android.R
import org.gnucash.android.databinding.DialogTransactionTemplateBinding
import org.gnucash.android.db.adapter.TransactionTemplateDbAdapter
import org.gnucash.android.ui.account.AccountConditions
import org.gnucash.android.ui.account.AccountSearchDialogFragment
import org.gnucash.android.ui.adapter.QualifiedAccountNameAdapter
import timber.log.Timber
import java.math.BigDecimal

/**
 * Create/edit dialog for a saved transaction template (feature ①d).
 *
 * The two sides of the transaction are chosen **explicitly** — a debit account
 * and a credit account — which is also what makes the resulting template
 * replayable: `TransactionFormFragment` only has to place the two splits.
 *
 * The amount is deliberately optional: leaving the field blank stores "空值", so
 * the amount is asked for each time the template is used (see
 * [TransactionTemplateCodec] — blank must never decay to `0`).
 */
class TransactionTemplateEditFragment : DialogFragment() {

    private var _binding: DialogTransactionTemplateBinding? = null
    private val binding get() = _binding!!

    private val templatesDbAdapter by lazy { TransactionTemplateDbAdapter.instance }

    private var templateId: Long = 0L
    private var existing: TransactionTemplate? = null

    private var debitAdapter: QualifiedAccountNameAdapter? = null
    private var creditAdapter: QualifiedAccountNameAdapter? = null

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        _binding = DialogTransactionTemplateBinding.inflate(layoutInflater)
        templateId = arguments?.getLong(ARG_TEMPLATE_ID) ?: 0L
        existing = if (templateId > 0L) loadOrNull(templateId) else null

        val titleId = if (templateId > 0L) {
            R.string.title_edit_transaction_template
        } else {
            R.string.title_new_transaction_template
        }

        val dialog = AlertDialog.Builder(requireContext())
            .setTitle(titleId)
            .setView(binding.root)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.btn_save, null)
            .create()

        // The positive button is wired in onStart so a validation failure can keep
        // the dialog open instead of losing the user's input.
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { onSaveClicked() }
        }

        setupAccountSearch()
        prefill()
        return dialog
    }

    /**
     * Wires the two filterable pickers.
     *
     * The spinners stay — they show the current choice — but a real book has more
     * accounts than anyone wants to scroll: the picker narrows the list as the user
     * types. Same split of duties as the transaction form, and the same WHERE
     * fragment ([AccountConditions.selectable]) the spinners below use, so the
     * dialog can never offer an account the spinner would refuse.
     */
    private fun setupAccountSearch() {
        binding.btnSearchDebitAccount.setOnClickListener {
            openAccountSearch(REQUEST_DEBIT_ACCOUNT_SEARCH)
        }
        binding.btnSearchCreditAccount.setOnClickListener {
            openAccountSearch(REQUEST_CREDIT_ACCOUNT_SEARCH)
        }
        // Registered on the manager the picker is shown on, and scoped to this
        // dialog, so a result arriving after the dialog is gone cannot touch it.
        parentFragmentManager.setFragmentResultListener(REQUEST_DEBIT_ACCOUNT_SEARCH, this) { _, r ->
            applyPickedAccount(binding.inputDebitAccountSpinner, debitAdapter, r)
        }
        parentFragmentManager.setFragmentResultListener(REQUEST_CREDIT_ACCOUNT_SEARCH, this) { _, r ->
            applyPickedAccount(binding.inputCreditAccountSpinner, creditAdapter, r)
        }
    }

    private fun openAccountSearch(requestKey: String) {
        val conditions = AccountConditions.selectable()
        val debit = requestKey == REQUEST_DEBIT_ACCOUNT_SEARCH
        val spinner = if (debit) binding.inputDebitAccountSpinner else binding.inputCreditAccountSpinner
        val adapter = if (debit) debitAdapter else creditAdapter
        val titleRes = if (debit) {
            R.string.title_search_debit_account
        } else {
            R.string.title_search_credit_account
        }
        AccountSearchDialogFragment.newInstance(
            titleRes,
            requestKey,
            conditions.first,
            conditions.second,
            adapter?.getUID(spinner.selectedItemPosition)
        ).show(parentFragmentManager, AccountSearchDialogFragment.DEFAULT_REQUEST_KEY)
    }

    /**
     * Applies the account chosen in the picker.
     *
     * `getPosition` answers -1 both for "no such account" and for "the list has
     * not loaded yet"; either way the spinner must be left alone — falling back to
     * row 0 would silently swap in an account the user never picked.
     */
    private fun applyPickedAccount(
        spinner: Spinner,
        adapter: QualifiedAccountNameAdapter?,
        result: Bundle
    ) {
        val uid = result.getString(AccountSearchDialogFragment.EXTRA_ACCOUNT_UID) ?: return
        val adapter = adapter ?: return
        val position = adapter.getPosition(uid)
        if (position < 0) return
        spinner.setSelection(position)
    }

    private fun loadOrNull(id: Long): TransactionTemplate? = try {
        templatesDbAdapter.getRecord(id)
    } catch (e: Exception) {
        Timber.e(e, "Could not load transaction template #%d for editing", id)
        null
    }

    private fun prefill() {
        val template = existing
        if (template != null) {
            binding.inputTemplateName.setText(template.name)
            binding.inputTemplateDescription.setText(template.description)
            binding.inputTemplateNotes.setText(template.notes)
            binding.inputTemplateNumber.setText(template.number)
            binding.inputTemplateAmount.setText(
                template.amount?.stripTrailingZeros()?.toPlainString().orEmpty()
            )
        }
        loadAccounts(template?.debitAccountUid, template?.creditAccountUid)
    }

    private fun loadAccounts(debitUid: String?, creditUid: String?) {
        val context = requireContext()
        // Assign the adapters (and attach them) *before* loading, so the load
        // callback always sees a fully wired spinner.
        val debit = QualifiedAccountNameAdapter(context, this)
        val credit = QualifiedAccountNameAdapter(context, this)
        debitAdapter = debit
        creditAdapter = credit
        binding.inputDebitAccountSpinner.adapter = debit
        binding.inputCreditAccountSpinner.adapter = credit
        debit.load { adapter -> selectAccount(binding.inputDebitAccountSpinner, adapter, debitUid) }
        credit.load { adapter -> selectAccount(binding.inputCreditAccountSpinner, adapter, creditUid) }
    }

    /**
     * Selects [uid] on [spinner], keeping a real selection on screen.
     *
     * `setSelection(0)` throws [IndexOutOfBoundsException] when the adapter is
     * still empty, so the fallback is only taken once the list actually has rows
     * (an unknown uid — e.g. a deleted account — then lands on the first entry).
     */
    private fun selectAccount(spinner: Spinner, adapter: QualifiedAccountNameAdapter, uid: String?) {
        val position = adapter.getPosition(uid)
        when {
            position >= 0 -> spinner.setSelection(position)
            adapter.count > 0 -> spinner.setSelection(0)
        }
    }

    private fun onSaveClicked() {
        val context = context ?: return
        val name = binding.inputTemplateName.getText().toString().trim()
        if (name.isEmpty()) {
            toast(R.string.toast_transaction_template_name_required)
            return
        }

        val debitUid = debitAdapter?.getUID(binding.inputDebitAccountSpinner.selectedItemPosition)
        val creditUid = creditAdapter?.getUID(binding.inputCreditAccountSpinner.selectedItemPosition)
        if (debitUid.isNullOrEmpty() || creditUid.isNullOrEmpty() || debitUid == creditUid) {
            toast(R.string.toast_transaction_template_accounts_required)
            return
        }

        val amountText = binding.inputTemplateAmount.getText().toString().trim()
        val amount: BigDecimal?
        if (amountText.isEmpty()) {
            // "空值" — deliberately stored as an absent amount, not 0.
            amount = null
        } else {
            amount = try {
                BigDecimal(amountText)
            } catch (e: NumberFormatException) {
                Timber.e(e, "Rejected a transaction-template amount that is not a number: %s", amountText)
                binding.inputTemplateAmount.error =
                    getString(R.string.toast_transaction_template_amount_invalid)
                return
            }
        }

        val template = TransactionTemplate(
            name = name,
            debitAccountUid = debitUid,
            creditAccountUid = creditUid,
            amount = amount,
            description = binding.inputTemplateDescription.getText().toString().trim(),
            notes = binding.inputTemplateNotes.getText().toString().trim(),
            number = binding.inputTemplateNumber.getText().toString().trim()
        )

        try {
            if (templateId > 0L) {
                template.id = templateId
                template.sortOrder = existing?.sortOrder ?: 0
                templatesDbAdapter.update(template)
            } else {
                // Same name means "replace the earlier template", mirroring the
                // saved-search behaviour.
                templatesDbAdapter.saveByName(template)
            }
        } catch (e: Exception) {
            Timber.e(e, "Could not save transaction template '%s'", name)
            Toast.makeText(context, R.string.toast_transaction_template_save_failed, Toast.LENGTH_SHORT).show()
            return
        }

        Toast.makeText(context, R.string.toast_transaction_template_saved, Toast.LENGTH_SHORT).show()
        parentFragmentManager.setFragmentResult(
            TransactionTemplatesFragment.REQUEST_TEMPLATES_CHANGED,
            Bundle.EMPTY
        )
        dismiss()
    }

    private fun toast(resId: Int) {
        val context = context ?: return
        Toast.makeText(context, resId, Toast.LENGTH_SHORT).show()
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }

    companion object {
        private const val ARG_TEMPLATE_ID = "template_id"

        /** Fragment-result keys of the two account pickers. */
        internal const val REQUEST_DEBIT_ACCOUNT_SEARCH = "template_debit_account_search"
        internal const val REQUEST_CREDIT_ACCOUNT_SEARCH = "template_credit_account_search"

        /** @param templateId `null` (or 0) opens the dialog in "new template" mode. */
        fun newInstance(templateId: Long?): TransactionTemplateEditFragment {
            val fragment = TransactionTemplateEditFragment()
            fragment.arguments = Bundle().apply {
                putLong(ARG_TEMPLATE_ID, templateId ?: 0L)
            }
            return fragment
        }
    }
}
