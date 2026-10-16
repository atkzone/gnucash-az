package org.gnucash.android.ui.transaction.view

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.PopupMenu
import android.widget.RadioButton
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import org.gnucash.android.R
import org.gnucash.android.databinding.FragmentExtendedViewsBinding
import org.gnucash.android.databinding.ItemExtendedViewBinding

/**
 * "设置扩展显示" — pick **one** extended view (radio) and define **which fields** it adds.
 *
 * Single-select is the whole point: the transaction list can only draw one field set at
 * a time, so the screen makes that explicit instead of hiding it behind a "默认" badge
 * that the user has to set as a second step. The first radio row is 不使用扩展显示, which
 * is how the mode gets turned off — there is no separate toggle any more.
 *
 * The list rules stay in [ExtendedViews] (pure) and every write goes through
 * [ExtendedViewStore], so this class never decides what the selection becomes after a
 * delete.
 */
class ExtendedViewsFragment : Fragment() {

    private var _binding: FragmentExtendedViewsBinding? = null
    private val binding get() = _binding!!

    private val adapter = ExtendedViewAdapter()

    private var views: List<ExtendedView> = emptyList()

    /** Id of the radio row that is ticked, or `null` for 不使用扩展显示. */
    private var selectedId: String? = null

    /**
     * Set while the check boxes are being written from the stored state. Without it the
     * programmatic write would fire the listener, which would save, which would reload,
     * which would write again.
     */
    private var syncingFields = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentExtendedViewsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.list.layoutManager = LinearLayoutManager(view.context)
        binding.list.adapter = adapter
        binding.list.emptyView = binding.empty
        binding.fabAdd.setOnClickListener { showEditDialog(null) }
        for ((field, box) in fieldBoxes()) {
            box.setOnCheckedChangeListener { _, checked -> onFieldToggled(field, checked) }
        }
        // A dialog dismiss does not resume this fragment, so the editor announces its
        // save through a fragment result instead.
        parentFragmentManager.setFragmentResultListener(
            ExtendedViewEditFragment.REQUEST_EXTENDED_VIEWS_CHANGED, viewLifecycleOwner
        ) { _, _ -> reload() }
    }

    override fun onResume() {
        super.onResume()
        reload()
    }

    override fun onDestroyView() {
        // A dangling emptyView/adapter would leak the RecyclerView's observer.
        binding.list.adapter = null
        binding.list.emptyView = null
        _binding = null
        super.onDestroyView()
    }

    private fun store(): ExtendedViewStore = ExtendedViewStore.of(requireContext())

    private fun reload() {
        val store = store()
        views = store.views()
        // A stale pointer means "nothing selected", not "the first one is selected for
        // you" — the row has to agree with what the list will draw.
        selectedId = if (store.isEnabled()) {
            ExtendedViews.active(views, store.defaultId())?.id
        } else {
            null
        }
        adapter.submit(views, selectedId)
        binding.empty.isVisible = views.isEmpty()
        syncFieldBoxes()
    }

    /** @return the four field check boxes, keyed by the field they stand for. */
    private fun fieldBoxes(): Map<ExtendedViewField, CheckBox> = mapOf(
        ExtendedViewField.ACCOUNT to binding.fieldAccount,
        ExtendedViewField.NOTES to binding.fieldNotes,
        ExtendedViewField.NUMBER to binding.fieldNumber,
        ExtendedViewField.TAGS to binding.fieldTags
    )

    private fun selectedView(): ExtendedView? = views.firstOrNull { it.id == selectedId }

    private fun syncFieldBoxes() {
        val active = selectedView()
        syncingFields = true
        for ((field, box) in fieldBoxes()) {
            box.isEnabled = active != null
            box.isChecked = active != null && field in active.fields
        }
        syncingFields = false
        binding.extendedFieldsHeader.isEnabled = active != null
        binding.extendedFieldsHint.isEnabled = active != null
    }

    /**
     * Writes one ticked/un-ticked field onto the selected view.
     *
     * Unticking the **last** field is refused: a view that adds nothing is exactly what
     * 紧凑视图 already is, and storing it would leave the list with nothing to draw.
     */
    private fun onFieldToggled(field: ExtendedViewField, checked: Boolean) {
        if (syncingFields) return
        val id = selectedId ?: return
        val current = selectedView()?.fields ?: return
        val updated = if (checked) current + field else current - field
        if (updated.isEmpty()) {
            Toast.makeText(requireContext(), R.string.toast_extended_view_fields_required, Toast.LENGTH_SHORT)
                .show()
            syncFieldBoxes()
            return
        }
        store().updateFields(id, updated)
        reload()
    }

    private fun select(id: String?) {
        val context = context ?: return
        store().select(id)
        val message = if (id == null) {
            getString(R.string.toast_extended_view_disabled)
        } else {
            getString(
                R.string.toast_extended_view_selected,
                views.firstOrNull { it.id == id }?.name.orEmpty()
            )
        }
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        reload()
    }

    private fun showEditDialog(view: ExtendedView?) {
        ExtendedViewEditFragment.newInstance(view?.id)
            .show(parentFragmentManager, "extended_view_edit")
    }

    private fun confirmDelete(view: ExtendedView) {
        val context = context ?: return
        AlertDialog.Builder(context)
            .setTitle(R.string.title_extended_views)
            .setMessage(getString(R.string.confirm_delete_extended_view, view.name))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.menu_delete_extended_view) { _, _ ->
                val store = store()
                val (remaining, defaultId) = ExtendedViews.remove(
                    store.views(), store.defaultId(), view.id
                )
                store.save(remaining, defaultId)
                Toast.makeText(context, R.string.toast_extended_view_deleted, Toast.LENGTH_SHORT).show()
                reload()
            }
            .show()
    }

    private fun showItemMenu(anchor: View, view: ExtendedView) {
        val popup = PopupMenu(anchor.context, anchor)
        popup.inflate(R.menu.extended_view_item_actions)
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.menu_edit_extended_view -> {
                    showEditDialog(view); true
                }

                R.id.menu_delete_extended_view -> {
                    confirmDelete(view); true
                }

                else -> false
            }
        }
        popup.show()
    }

    private inner class ExtendedViewAdapter :
        RecyclerView.Adapter<ExtendedViewAdapter.ViewHolder>() {

        private var items: List<ExtendedView> = emptyList()
        private var checkedId: String? = null

        fun submit(newItems: List<ExtendedView>, newCheckedId: String?) {
            items = newItems
            checkedId = newCheckedId
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val inflater = LayoutInflater.from(parent.context)
            return ViewHolder(ItemExtendedViewBinding.inflate(inflater, parent, false))
        }

        /** One extra row up front: 不使用扩展显示 (`null`). */
        override fun getItemCount(): Int = items.size + 1

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            holder.bind(if (position == 0) null else items[position - 1], checkedId)
        }

        inner class ViewHolder(private val itemBinding: ItemExtendedViewBinding) :
            RecyclerView.ViewHolder(itemBinding.root) {

            fun bind(view: ExtendedView?, checkedId: String?) {
                val radio: RadioButton = itemBinding.extendedViewRadio
                if (view == null) {
                    itemBinding.extendedViewName.setText(R.string.extended_view_none)
                    itemBinding.extendedViewSummary.isVisible = false
                    itemBinding.extendedViewOverflow.isVisible = false
                    radio.isChecked = checkedId == null
                } else {
                    itemBinding.extendedViewName.text = view.name
                    itemBinding.extendedViewSummary.isVisible = true
                    itemBinding.extendedViewSummary.text = summaryOf(view)
                    itemBinding.extendedViewOverflow.isVisible = true
                    radio.isChecked = view.id == checkedId
                }
                itemBinding.root.setOnClickListener { select(view?.id) }
                itemBinding.extendedViewOverflow.setOnClickListener { showItemMenu(it, view!!) }
            }

            /** Field names in declaration order, so the list reads like the card. */
            private fun summaryOf(view: ExtendedView): String {
                val names = ExtendedViewField.values()
                    .filter { it in view.fields }
                    .map { getString(it.titleRes) }
                return if (names.isEmpty()) {
                    getString(R.string.label_extended_view_field_count, 0)
                } else {
                    names.joinToString(FIELD_SEPARATOR)
                }
            }
        }
    }

    companion object {
        /** Separator between field names in a row summary. */
        private const val FIELD_SEPARATOR = "、"
    }
}
