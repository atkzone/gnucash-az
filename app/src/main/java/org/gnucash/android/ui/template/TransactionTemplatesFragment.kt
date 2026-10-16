package org.gnucash.android.ui.template

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.PopupMenu
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import org.gnucash.android.R
import org.gnucash.android.databinding.FragmentTransactionTemplatesBinding
import org.gnucash.android.databinding.ItemTransactionTemplateBinding
import org.gnucash.android.db.adapter.AccountsDbAdapter
import org.gnucash.android.db.adapter.TransactionTemplateDbAdapter
import org.gnucash.android.ui.common.FormActivity
import org.gnucash.android.ui.common.UxArgument
import timber.log.Timber
import java.math.BigDecimal

/**
 * Lists the saved transaction templates (feature ①d) and offers the three things
 * the feature needs: **create / edit / delete** a template, and **replay** one
 * into the new-transaction screen.
 *
 * Replaying is a plain hand-off: the form gets the template id and does the
 * filling in — this screen never builds a [org.gnucash.android.model.Transaction]
 * itself, so there is exactly one place that knows how a template becomes a
 * transaction.
 */
class TransactionTemplatesFragment : Fragment() {

    private var _binding: FragmentTransactionTemplatesBinding? = null
    private val binding get() = _binding!!

    private val templatesDbAdapter by lazy { TransactionTemplateDbAdapter.instance }
    private val accountsDbAdapter by lazy { AccountsDbAdapter.instance }

    private val adapter = TransactionTemplateAdapter()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentTransactionTemplatesBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.list.layoutManager = LinearLayoutManager(view.context)
        binding.list.adapter = adapter
        binding.list.emptyView = binding.empty
        binding.fabAdd.setOnClickListener { showEditDialog(null) }
        // The edit dialog reports success through a fragment result, so the list
        // refreshes even though a dialog dismiss does not resume this fragment.
        parentFragmentManager.setFragmentResultListener(
            REQUEST_TEMPLATES_CHANGED, viewLifecycleOwner
        ) { _, _ -> reload() }
    }

    override fun onResume() {
        super.onResume()
        reload()
    }

    override fun onDestroyView() {
        // A dangling emptyView/binding would leak the RecyclerView's observer.
        binding.list.adapter = null
        binding.list.emptyView = null
        _binding = null
        super.onDestroyView()
    }

    private fun reload() {
        val templates = try {
            templatesDbAdapter.getAll()
        } catch (e: Exception) {
            Timber.e(e, "Could not load transaction templates")
            binding.list.isVisible = false
            binding.empty.setText(R.string.toast_transaction_template_unreadable)
            binding.empty.isVisible = true
            return
        }
        binding.empty.setText(R.string.label_no_transaction_templates)
        adapter.submit(templates)
        binding.list.isVisible = true
        binding.empty.isVisible = templates.isEmpty()
    }

    /** Opens the new-transaction screen pre-filled from [template]. */
    private fun use(template: TransactionTemplate) {
        val context = context ?: return
        if (!template.isUsable) {
            Toast.makeText(context, R.string.toast_transaction_template_unreadable, Toast.LENGTH_SHORT).show()
            return
        }
        val intent = Intent(context, FormActivity::class.java)
            .putExtra(UxArgument.FORM_TYPE, FormActivity.FormType.TRANSACTION.name)
            // The form needs an "origin" account: the debit side of the template,
            // which is also where the amount/commodity defaults come from.
            .putExtra(UxArgument.SELECTED_ACCOUNT_UID, template.debitAccountUid)
            .putExtra(UxArgument.SELECTED_TEMPLATE_ID, template.id)
        startActivity(intent)
    }

    private fun showEditDialog(templateId: Long?) {
        TransactionTemplateEditFragment.newInstance(templateId)
            .show(parentFragmentManager, "transaction_template_edit")
    }

    private fun confirmDelete(template: TransactionTemplate) {
        val context = context ?: return
        AlertDialog.Builder(context)
            .setTitle(R.string.title_transaction_templates)
            .setMessage(getString(R.string.confirm_delete_transaction_template, template.name))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.menu_delete) { _, _ ->
                try {
                    templatesDbAdapter.delete(template.id)
                    Toast.makeText(context, R.string.toast_transaction_template_deleted, Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    Timber.e(e, "Could not delete transaction template #%d", template.id)
                    Toast.makeText(context, R.string.toast_transaction_template_delete_failed, Toast.LENGTH_SHORT).show()
                }
                reload()
            }
            .show()
    }

    private fun showItemMenu(anchor: View, template: TransactionTemplate) {
        val popup = PopupMenu(anchor.context, anchor)
        popup.inflate(R.menu.transaction_template_item_actions)
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.menu_use_transaction_template -> {
                    use(template); true
                }

                R.id.menu_edit_transaction_template -> {
                    showEditDialog(template.id); true
                }

                R.id.menu_delete_transaction_template -> {
                    confirmDelete(template); true
                }

                else -> false
            }
        }
        popup.show()
    }

    private fun labelFor(uid: String?): String {
        if (uid.isNullOrEmpty()) return getString(R.string.label_transaction_template_no_account)
        return try {
            accountsDbAdapter.getAccountName(uid)
        } catch (e: Exception) {
            Timber.e(e, "Could not resolve account %s for a transaction template", uid)
            uid
        }
    }

    private fun amountLabel(template: TransactionTemplate): String =
        template.amount?.let { formatAmount(it) } ?: getString(R.string.label_transaction_template_no_amount)

    private fun formatAmount(amount: BigDecimal): String = amount.stripTrailingZeros().toPlainString()

    private inner class TransactionTemplateAdapter :
        RecyclerView.Adapter<TransactionTemplateAdapter.ViewHolder>() {

        private var items: List<TransactionTemplate> = emptyList()

        fun submit(newItems: List<TransactionTemplate>) {
            items = newItems
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val inflater = LayoutInflater.from(parent.context)
            return ViewHolder(ItemTransactionTemplateBinding.inflate(inflater, parent, false))
        }

        override fun getItemCount(): Int = items.size

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            holder.bind(items[position])
        }

        inner class ViewHolder(private val itemBinding: ItemTransactionTemplateBinding) :
            RecyclerView.ViewHolder(itemBinding.root) {

            fun bind(template: TransactionTemplate) {
                itemBinding.templateName.text = template.name
                itemBinding.templateAccounts.text = getString(
                    R.string.transaction_template_accounts_summary,
                    labelFor(template.debitAccountUid),
                    labelFor(template.creditAccountUid)
                )
                itemBinding.templateAmount.text = if (template.decodeFailed) {
                    getString(R.string.toast_transaction_template_unreadable)
                } else {
                    amountLabel(template)
                }
                itemBinding.root.setOnClickListener { use(template) }
                itemBinding.templateOverflow.setOnClickListener { showItemMenu(it, template) }
            }
        }
    }

    companion object {
        /** Key used by [TransactionTemplateEditFragment] to announce a save. */
        const val REQUEST_TEMPLATES_CHANGED = "transaction_templates_changed"
    }
}
