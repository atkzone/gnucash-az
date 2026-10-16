package org.gnucash.android.ui.transaction.view

import android.app.Dialog
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import org.gnucash.android.R
import org.gnucash.android.databinding.DialogExtendedViewEditBinding

/**
 * Create / rename dialog for one extended view. **Name only.**
 *
 * Which fields a view shows is set on the "设置扩展显示" screen itself, next to the radio
 * that picks it: two places to edit the same property is how they drift apart. A new
 * view therefore starts from [ExtendedViewField.DEFAULT] and is handed the selection, so
 * the user can tick what it should add straight away.
 *
 * A refused save (blank name) keeps the dialog open so the typed text is not lost, and
 * never touches the stored payload.
 */
class ExtendedViewEditFragment : DialogFragment() {

    private var _binding: DialogExtendedViewEditBinding? = null
    private val binding get() = _binding!!

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        _binding = DialogExtendedViewEditBinding.inflate(layoutInflater)
        val store = ExtendedViewStore.of(requireContext())
        val viewId = arguments?.getString(ARG_VIEW_ID)
        val existing = viewId?.let { id -> store.views().firstOrNull { it.id == id } }

        val titleId = if (existing != null) {
            R.string.title_edit_extended_view
        } else {
            R.string.title_new_extended_view
        }

        val dialog = AlertDialog.Builder(requireContext())
            .setTitle(titleId)
            .setView(binding.root)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.btn_save, null)
            .create()

        // Wired in onShow so a validation failure can keep the dialog open.
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { onSaveClicked(existing) }
        }

        binding.inputExtendedViewName.setText(existing?.name.orEmpty())
        return dialog
    }

    private fun onSaveClicked(existing: ExtendedView?) {
        val context = context ?: return
        val name = ExtendedViews.normalizedName(binding.inputExtendedViewName.getText().toString())
        if (name == null) {
            Toast.makeText(context, R.string.toast_extended_view_name_required, Toast.LENGTH_SHORT).show()
            return
        }

        val store = ExtendedViewStore.of(context)
        val views = store.views()
        val defaultId = store.defaultId()
        if (existing == null) {
            val created = ExtendedView(ExtendedViews.newId(views), name, ExtendedViewField.DEFAULT)
            val (withNew, _) = ExtendedViews.add(views, defaultId, created)
            // The view that was just created is the one the user wants to look at, so it
            // takes the selection (and the selection is what turns the mode on).
            store.save(withNew, ExtendedViews.setDefault(withNew, defaultId, created.id))
            store.setEnabled(true)
        } else {
            store.save(ExtendedViews.rename(views, existing.id, name), defaultId)
        }

        Toast.makeText(context, R.string.toast_extended_view_saved, Toast.LENGTH_SHORT).show()
        parentFragmentManager.setFragmentResult(REQUEST_EXTENDED_VIEWS_CHANGED, Bundle.EMPTY)
        dismiss()
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }

    companion object {
        private const val ARG_VIEW_ID = "extended_view_id"

        /** Key used to announce that the stored views changed. */
        const val REQUEST_EXTENDED_VIEWS_CHANGED = "extended_views_changed"

        /** @param viewId `null` opens the dialog in "new view" mode. */
        fun newInstance(viewId: String?): ExtendedViewEditFragment {
            val fragment = ExtendedViewEditFragment()
            fragment.arguments = Bundle().apply { putString(ARG_VIEW_ID, viewId) }
            return fragment
        }
    }
}
