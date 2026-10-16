package org.gnucash.android.ui.search

import android.app.Dialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.viewModels
import org.gnucash.android.R
import org.gnucash.android.databinding.DialogSearchSummaryBinding
import java.math.BigDecimal
import java.text.NumberFormat

/**
 * "合计与统计" dialog for a search result set.
 *
 * It owns no aggregation: the numbers come straight from
 * [SearchResultsViewModel.summary], which was computed by the pure
 * [SearchSummary] when the query last ran.
 */
class SearchSummaryDialogFragment : DialogFragment() {

    private val viewModel: SearchResultsViewModel by viewModels(
        ownerProducer = { requireParentFragment() }
    )

    private var binding: DialogSearchSummaryBinding? = null

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val context = requireContext()
        val binding = DialogSearchSummaryBinding.inflate(LayoutInflater.from(context))
        this.binding = binding

        val summary = viewModel.summary.value
        val container = binding.summaryContainer

        if (summary.rowCount == 0) {
            container.addView(paragraph(getString(R.string.search_summary_empty)))
        } else {
            container.addView(paragraph(getString(R.string.search_summary_count, summary.rowCount)))

            if (summary.totalsByCurrency.isNotEmpty()) {
                container.addView(header(getString(R.string.search_summary_total_header)))
                for (total in summary.totalsByCurrency) {
                    container.addView(row(total.currencyCode, total.amount, null))
                }
            }
            if (summary.byAccount.isNotEmpty()) {
                container.addView(header(getString(R.string.search_summary_by_account)))
                for (category in summary.byAccount) {
                    container.addView(row(categoryLabel(category.label), category.amount, category.count))
                }
            }
            if (summary.byTag.isNotEmpty()) {
                container.addView(header(getString(R.string.search_summary_by_tag)))
                for (category in summary.byTag) {
                    container.addView(row(categoryLabel(category.label), category.amount, category.count))
                }
            }
        }

        return AlertDialog.Builder(context)
            .setTitle(R.string.search_summary_title)
            .setView(binding.root)
            .setPositiveButton(android.R.string.ok, null)
            .create()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        binding = null
    }

    private fun categoryLabel(label: String): String =
        if (label == SearchSummary.UNCATEGORIZED) getString(R.string.search_summary_no_category) else label

    private fun header(text: String): TextView = TextView(requireContext()).apply {
        this.text = text
        textSize = HEADER_TEXT_SIZE
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        setPadding(0, HEADER_PADDING, 0, HEADER_PADDING / 2)
    }

    private fun paragraph(text: String): TextView = TextView(requireContext()).apply {
        this.text = text
        setPadding(0, 0, 0, ROW_PADDING)
    }

    private fun row(label: String, amount: BigDecimal, count: Int?): LinearLayout {
        val context = requireContext()
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, ROW_PADDING, 0, ROW_PADDING)
        }
        val right = if (count == null) {
            formatAmount(amount)
        } else {
            "$count ×  ${formatAmount(amount)}"
        }
        container.addView(
            TextView(context).apply {
                text = label
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
        )
        container.addView(
            TextView(context).apply {
                text = right
                gravity = android.view.Gravity.END
            }
        )
        return container
    }

    private fun formatAmount(amount: BigDecimal): String =
        NumberFormat.getNumberInstance().apply {
            minimumFractionDigits = 2
            maximumFractionDigits = 2
        }.format(amount)

    companion object {
        const val TAG = "SearchSummaryDialogFragment"
        private const val HEADER_TEXT_SIZE = 15f
        private const val HEADER_PADDING = 12
        private const val ROW_PADDING = 4
    }
}
