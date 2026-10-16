package org.gnucash.android.ui.account

import android.app.Dialog
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.widget.ArrayAdapter
import androidx.appcompat.app.AlertDialog
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.gnucash.android.R
import org.gnucash.android.databinding.DialogAccountSearchBinding
import org.gnucash.android.db.DatabaseSchema.AccountEntry
import org.gnucash.android.db.adapter.AccountsDbAdapter
import org.gnucash.android.model.Account
import timber.log.Timber

/**
 * Account picker with a filter box.
 *
 * The transaction form's debit/credit spinners are fine for a handful of
 * accounts, but a real book has hundreds of them and scrolling a spinner is not
 * how anyone finds "Assets:Bank:Checking". This dialog narrows the list as the
 * user types and hands the chosen account back through the fragment-result API,
 * so the caller (the form) keeps owning the spinner it will update.
 *
 * It deliberately loads the accounts itself rather than reusing
 * `QualifiedAccountNameAdapter`: the caller passes the exact WHERE fragment its
 * spinner uses, so the dialog can never offer an account the spinner would
 * refuse.
 */
class AccountSearchDialogFragment : DialogFragment() {

    private lateinit var binding: DialogAccountSearchBinding
    private lateinit var listAdapter: ArrayAdapter<String>
    private var options: List<AccountOption> = emptyList()
    private var shown: List<AccountOption> = emptyList()

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val binding = DialogAccountSearchBinding.inflate(layoutInflater)
        this.binding = binding

        listAdapter = ArrayAdapter(
            requireContext(),
            android.R.layout.simple_list_item_1,
            mutableListOf<String>()
        )
        binding.listAccounts.adapter = listAdapter
        binding.listAccounts.setOnItemClickListener { _, _, position, _ ->
            val option = shown.getOrNull(position) ?: return@setOnItemClickListener
            val requestKey = requireArguments().getString(EXTRA_REQUEST_KEY) ?: DEFAULT_REQUEST_KEY
            parentFragmentManager.setFragmentResult(
                requestKey,
                bundleOf(EXTRA_ACCOUNT_UID to option.uid)
            )
            dismiss()
        }

        binding.inputAccountSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) = render(s?.toString())
        })

        val args = requireArguments()
        loadAccounts(
            args.getString(EXTRA_WHERE),
            args.getStringArray(EXTRA_WHERE_ARGS),
            args.getString(EXTRA_SELECTED_UID)
        )

        return AlertDialog.Builder(requireContext(), theme)
            .setTitle(args.getInt(EXTRA_TITLE, R.string.title_select_account))
            .setView(binding.root)
            .setNegativeButton(R.string.btn_cancel, null)
            .create()
    }

    private fun loadAccounts(where: String?, whereArgs: Array<String?>?, selectedUid: String?) {
        val accountsDbAdapter = AccountsDbAdapter.instance
        val orderBy = AccountEntry.COLUMN_FAVORITE + " DESC, " +
                AccountEntry.COLUMN_FULL_NAME + " ASC"
        lifecycleScope.launch {
            val loaded = withContext(Dispatchers.IO) {
                try {
                    accountsDbAdapter.getAllRecords(where, whereArgs, orderBy).map { it.toOption() }
                } catch (e: Exception) {
                    // A picker that silently shows nothing is worse than an empty
                    // one with a logged reason; the empty view explains the state.
                    Timber.e(e, "Failed to load accounts for the picker")
                    emptyList()
                }
            }
            if (!isAdded) return@launch
            options = loaded
            render(binding.inputAccountSearch.text?.toString())
            // Open next to the account the form already had, so the usual
            // "pick a neighbour of the current one" flow needs no scrolling.
            val position = shown.indexOfFirst { it.uid == selectedUid }
            if (position > 0) binding.listAccounts.setSelection(position)
        }
    }

    private fun render(query: String?) {
        shown = AccountSearchFilter.filter(options, query)
        listAdapter.clear()
        listAdapter.addAll(shown.map { it.qualifiedName })
        listAdapter.notifyDataSetChanged()
        binding.emptyAccounts.isVisible = shown.isEmpty()
        binding.listAccounts.isVisible = shown.isNotEmpty()
    }

    private fun Account.toOption(): AccountOption {
        val qualified = fullName?.takeIf { it.isNotBlank() } ?: name
        return AccountOption(uid, qualified, name)
    }

    companion object {
        const val DEFAULT_REQUEST_KEY: String = "account_search_result"
        const val EXTRA_ACCOUNT_UID: String = "account_search_account_uid"

        private const val EXTRA_TITLE = "account_search_title"
        private const val EXTRA_WHERE = "account_search_where"
        private const val EXTRA_WHERE_ARGS = "account_search_where_args"
        private const val EXTRA_REQUEST_KEY = "account_search_request_key"
        private const val EXTRA_SELECTED_UID = "account_search_selected_uid"

        fun newInstance(
            titleRes: Int,
            requestKey: String,
            where: String?,
            whereArgs: Array<String?>?,
            selectedUid: String?
        ): AccountSearchDialogFragment {
            val args = Bundle()
            args.putInt(EXTRA_TITLE, titleRes)
            args.putString(EXTRA_REQUEST_KEY, requestKey)
            args.putString(EXTRA_WHERE, where)
            args.putStringArray(EXTRA_WHERE_ARGS, whereArgs)
            args.putString(EXTRA_SELECTED_UID, selectedUid)
            return AccountSearchDialogFragment().apply { arguments = args }
        }
    }
}
