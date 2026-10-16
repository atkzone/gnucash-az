package org.gnucash.android.ui.search

import android.app.DatePickerDialog
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.Toast
import androidx.appcompat.app.ActionBar
import androidx.appcompat.widget.PopupMenu
import androidx.core.view.isVisible
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import org.gnucash.android.R
import org.gnucash.android.app.MenuFragment
import org.gnucash.android.app.actionBar
import org.gnucash.android.databinding.FragmentSearchFormBinding
import org.gnucash.android.databinding.ItemSearchAccountBinding
import org.gnucash.android.databinding.ItemSearchDateBinding
import org.gnucash.android.databinding.ItemSearchDescriptionBinding
import org.gnucash.android.databinding.ItemSearchMemoBinding
import org.gnucash.android.databinding.ItemSearchNotesBinding
import org.gnucash.android.databinding.ItemSearchNumberBinding
import org.gnucash.android.databinding.ItemSearchNumericBinding
import org.gnucash.android.ui.adapter.DefaultItemSelectedListener
import org.gnucash.android.ui.adapter.QualifiedAccountNameAdapter
import org.gnucash.android.ui.adapter.SpinnerArrayAdapter
import org.gnucash.android.ui.adapter.SpinnerItem
import org.gnucash.android.ui.search.SearchResultsFragment.Companion.EXTRA_FORM
import org.gnucash.android.ui.text.DefaultTextWatcher
import org.gnucash.android.ui.util.dialog.DatePickerDialogFragment
import org.gnucash.android.ui.util.widget.CalculatorEditText.OnValueChangedListener
import org.gnucash.android.util.toMillis
import org.joda.time.format.DateTimeFormat
import org.joda.time.format.DateTimeFormatter
import java.math.BigDecimal

class SearchFormFragment : MenuFragment() {
    private val viewModel by viewModels<SearchFormViewModel>()
    private var binding: FragmentSearchFormBinding? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        lifecycleScope.launch {
            viewModel.query.collect { sql ->
                if (!sql.isNullOrEmpty()) {
                    showResults(sql)
                    viewModel.onSearchShown()
                }
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val binding = FragmentSearchFormBinding.inflate(inflater, container, false)
        this.binding = binding
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val actionBar: ActionBar? = this.actionBar
        actionBar?.setTitle(R.string.title_search_form)

        val binding = binding!!
        val form = viewModel.form

        bind(binding, form)
    }

    @Deprecated("Deprecated in Java")
    override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
        super.onCreateOptionsMenu(menu, inflater)
        inflater.inflate(R.menu.search_form_actions, menu)
    }

    @Deprecated("Deprecated in Java")
    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.menu_search_save_template -> {
                showSaveFavourite()
                true
            }

            R.id.menu_search_templates -> {
                showFavourites()
                true
            }

            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun showResults(sqlWhere: String) {
        val args = Bundle()
        args.putString(EXTRA_FORM, sqlWhere)

        val fragment = SearchResultsFragment()
        fragment.arguments = args

        val fm = parentFragmentManager
        fm.beginTransaction()
            .replace(R.id.fragment_container, fragment)
            .addToBackStack("search")
            .commit()
    }

    private fun bind(binding: FragmentSearchFormBinding, form: SearchForm) {
        bindComparison(binding)
        val popupMenu = createAddMenu(binding.menuAdd)
        binding.menuAdd.setOnClickListener { popupMenu.show() }
        binding.btnSearch.setOnClickListener { submitForm(binding) }

        binding.list.removeAllViews()
        for (criterion in form.criteria) {
            when (criterion) {
                is SearchCriteria.Account -> addAccount(binding.list, criterion)
                is SearchCriteria.Date -> addDatePosted(binding.list, criterion)
                is SearchCriteria.Description -> addDescription(binding.list, criterion)
                is SearchCriteria.Memo -> addMemo(binding.list, criterion)
                is SearchCriteria.Note -> addNote(binding.list, criterion)
                is SearchCriteria.Number -> addNumber(binding.list, criterion)
                is SearchCriteria.Numeric -> addNumeric(binding.list, criterion)
                is SearchCriteria.AnyField -> addAnyField(binding.list, criterion)
                is SearchCriteria.Tag -> addTag(binding.list, criterion)
            }
        }
    }

    private fun bindComparison(binding: FragmentSearchFormBinding) {
        val context: Context = binding.root.context
        val comparisons = listOf(
            SpinnerItem(ComparisonType.All, context.getString(R.string.search_criteria_all)),
            SpinnerItem(ComparisonType.Any, context.getString(R.string.search_criteria_any)),
            SpinnerItem(ComparisonType.None, context.getString(R.string.search_criteria_none))
        )
        binding.groupingCombo.adapter = SpinnerArrayAdapter(context, comparisons)
        binding.groupingCombo.onItemSelectedListener =
            DefaultItemSelectedListener(false) { parent: AdapterView<*>,
                                                 view: View?,
                                                 position: Int,
                                                 id: Long ->
                viewModel.setComparison(comparisons[position].value)
            }
        // Reflect the form's state (it may have been replaced by applying a
        // template). The listener above is idempotent, so this is safe.
        val index = comparisons.indexOfFirst { it.value == viewModel.form.comparisonType }
        if (index > 0) {
            binding.groupingCombo.setSelection(index)
        }
    }

    private fun bind(binding: ItemSearchDescriptionBinding, criterion: SearchCriteria.Description) {
        val context: Context = binding.root.context
        val adapter = createStringComparisonAdapter(context)
        binding.comparison.adapter = adapter
        binding.comparison.onItemSelectedListener =
            DefaultItemSelectedListener { parent: AdapterView<*>,
                                          view: View?,
                                          position: Int,
                                          id: Long ->
                val item = adapter.getItem(position)!!
                criterion.compare = item.value
            }
        binding.comparison.post {
            binding.comparison.setSelection(adapter.getValuePosition(criterion.compare))
        }
        binding.inputTransactionName.setText(criterion.value)
        binding.inputTransactionName.addTextChangedListener(DefaultTextWatcher { s ->
            criterion.value = s.toString()
        })
        binding.deleteBtn.setOnClickListener {
            viewModel.remove(criterion)
            removeItem(binding.root)
        }
    }

    private fun bind(binding: ItemSearchNotesBinding, criterion: SearchCriteria.Note) {
        val context: Context = binding.root.context
        val adapter = createStringComparisonAdapter(context)
        binding.comparison.adapter = adapter
        binding.comparison.onItemSelectedListener =
            DefaultItemSelectedListener { parent: AdapterView<*>,
                                          view: View?,
                                          position: Int,
                                          id: Long ->
                val item = adapter.getItem(position)!!
                criterion.compare = item.value
            }
        binding.comparison.post {
            binding.comparison.setSelection(adapter.getValuePosition(criterion.compare))
        }
        binding.notes.setText(criterion.value)
        binding.notes.addTextChangedListener(DefaultTextWatcher { s ->
            criterion.value = s.toString()
        })
        binding.deleteBtn.setOnClickListener {
            viewModel.remove(criterion)
            removeItem(binding.root)
        }
    }

    private fun bind(binding: ItemSearchMemoBinding, criterion: SearchCriteria.Memo) {
        val context: Context = binding.root.context
        val adapter = createStringComparisonAdapter(context)
        binding.comparison.adapter = adapter
        binding.comparison.onItemSelectedListener =
            DefaultItemSelectedListener { parent: AdapterView<*>,
                                          view: View?,
                                          position: Int,
                                          id: Long ->
                val item = adapter.getItem(position)!!
                criterion.compare = item.value
            }
        binding.comparison.post {
            binding.comparison.setSelection(adapter.getValuePosition(criterion.compare))
        }
        binding.inputSplitMemo.setText(criterion.value)
        binding.inputSplitMemo.addTextChangedListener(DefaultTextWatcher { s ->
            criterion.value = s.toString()
        })
        binding.deleteBtn.setOnClickListener {
            viewModel.remove(criterion)
            removeItem(binding.root)
        }
    }

    private fun bind(binding: ItemSearchNumberBinding, criterion: SearchCriteria.Number) {
        val context: Context = binding.root.context
        val adapter = createStringComparisonAdapter(context)
        binding.comparison.adapter = adapter
        binding.comparison.onItemSelectedListener =
            DefaultItemSelectedListener { parent: AdapterView<*>,
                                          view: View?,
                                          position: Int,
                                          id: Long ->
                val item = adapter.getItem(position)!!
                criterion.compare = item.value
            }
        binding.comparison.post {
            binding.comparison.setSelection(adapter.getValuePosition(criterion.compare))
        }
        binding.number.setText(criterion.value)
        binding.number.addTextChangedListener(DefaultTextWatcher { s ->
            criterion.value = s.toString()
        })
        binding.deleteBtn.setOnClickListener {
            viewModel.remove(criterion)
            removeItem(binding.root)
        }
    }

    private fun bind(binding: ItemSearchDateBinding, criterion: SearchCriteria.Date) {
        val context: Context = binding.root.context
        val adapter = createDateComparisonAdapter(context)
        binding.comparison.adapter = adapter
        binding.comparison.onItemSelectedListener =
            DefaultItemSelectedListener { parent: AdapterView<*>,
                                          view: View?,
                                          position: Int,
                                          id: Long ->
                val item = adapter.getItem(position)!!
                criterion.compare = item.value
            }
        binding.comparison.post {
            binding.comparison.setSelection(adapter.getValuePosition(criterion.compare))
        }
        val date = criterion.value
        binding.dateText.text = dateFormatter.print(date)
        binding.dateText.setOnClickListener {
            val listener = DatePickerDialog.OnDateSetListener { view, year, month, dayOfMonth ->
                criterion.set(year, month + 1, dayOfMonth)
                binding.dateText.text = dateFormatter.print(criterion.value!!)
            }
            val dateMillis = criterion.value?.toMillis() ?: System.currentTimeMillis()
            DatePickerDialogFragment.newInstance(listener, dateMillis)
                .show(parentFragmentManager, "date_fragment")
        }
        binding.deleteBtn.setOnClickListener {
            viewModel.remove(criterion)
            removeItem(binding.root)
        }
    }

    private fun bind(binding: ItemSearchNumericBinding, criterion: SearchCriteria.Numeric) {
        val bindingRoot = this@SearchFormFragment.binding ?: return
        val isValue = (criterion.match != null)

        val context: Context = binding.root.context
        val adapter: SpinnerArrayAdapter<Compare>
        if (isValue) {
            val matchAdapter = createDebitCreditComparisonAdapter(context)
            binding.match.isVisible = true
            binding.match.adapter = matchAdapter
            binding.match.onItemSelectedListener =
                DefaultItemSelectedListener { parent: AdapterView<*>,
                                              view: View?,
                                              position: Int,
                                              id: Long ->
                    val item = matchAdapter.getItem(position)!!
                    criterion.match = item.value
                }
            binding.match.post {
                val value = criterion.match ?: NumericMatch.HasDebitsOrCredits
                binding.match.setSelection(matchAdapter.getValuePosition(value))
            }
            adapter = createNumericDebitCreditComparisonAdapter(context)
        } else {
            binding.match.isVisible = false
            adapter = createNumericComparisonAdapter(context)
        }
        binding.comparison.adapter = adapter
        binding.comparison.onItemSelectedListener =
            DefaultItemSelectedListener { parent: AdapterView<*>,
                                          view: View?,
                                          position: Int,
                                          id: Long ->
                val item = adapter.getItem(position)!!
                criterion.compare = item.value
            }
        binding.comparison.post {
            binding.comparison.setSelection(adapter.getValuePosition(criterion.compare))
        }
        binding.inputSplitAmount.setValue(criterion.value, true)
        binding.inputSplitAmount.bindKeyboard(bindingRoot.calculatorKeyboard)
        binding.inputSplitAmount.addValueChangedListener(object : OnValueChangedListener {
            override fun onValueChanged(value: BigDecimal?) {
                criterion.value = value
            }
        })
        binding.deleteBtn.setOnClickListener {
            viewModel.remove(criterion)
            removeItem(binding.root)
        }
    }

    private fun bind(binding: ItemSearchAccountBinding, criterion: SearchCriteria.Account) {
        val context: Context = binding.root.context
        val adapter = createAccountComparisonAdapter(context)
        binding.comparison.adapter = adapter
        binding.comparison.onItemSelectedListener =
            DefaultItemSelectedListener { parent: AdapterView<*>,
                                          view: View?,
                                          position: Int,
                                          id: Long ->
                val item = adapter.getItem(position)!!
                criterion.compare = item.value
            }
        binding.comparison.post {
            binding.comparison.setSelection(adapter.getValuePosition(criterion.compare))
        }
        val accountsAdapter = QualifiedAccountNameAdapter(context, viewLifecycleOwner)
            .load { adapter ->
                criterion.value?.let { account ->
                    binding.accountsListSpinner.post {
                        binding.accountsListSpinner.setSelection(adapter.getValuePosition(account))
                    }
                }
            }
        binding.accountsListSpinner.adapter = accountsAdapter
        binding.accountsListSpinner.onItemSelectedListener =
            DefaultItemSelectedListener { parent: AdapterView<*>,
                                          view: View?,
                                          position: Int,
                                          id: Long ->
                criterion.value = accountsAdapter.getAccount(position)!!
            }
        binding.deleteBtn.setOnClickListener {
            viewModel.remove(criterion)
            removeItem(binding.root)
        }
    }

    private fun createAddMenu(v: View): PopupMenu {
        val popupMenu = PopupMenu(v.context, v)
        val menu = popupMenu.menu
        menu.add(0, MENU_DESCRIPTION, 0, R.string.search_field_description)
        menu.add(0, MENU_NOTE, 0, R.string.search_field_notes)
        menu.add(0, MENU_MEMO, 0, R.string.search_field_memo)
        menu.add(0, MENU_NUMBER, 0, R.string.search_field_number)
        menu.add(0, MENU_DATE_POSTED, 0, R.string.search_field_date_posted)
        menu.add(0, MENU_AMOUNT, 0, R.string.search_field_amount)
        menu.add(0, MENU_VALUE, 0, R.string.search_field_value)
        menu.add(0, MENU_ACCOUNT, 0, R.string.search_field_account)
        menu.add(1, MENU_ANY_FIELD, 0, R.string.search_field_any_field)
        menu.add(1, MENU_TAG, 0, R.string.search_field_tag)

        popupMenu.setOnMenuItemClickListener { item ->
            return@setOnMenuItemClickListener when (item.itemId) {
                MENU_DESCRIPTION -> addDescription()
                MENU_NOTE -> addNote()
                MENU_MEMO -> addMemo()
                MENU_NUMBER -> addNumber()
                MENU_DATE_POSTED -> addDatePosted()
                MENU_AMOUNT -> addNumeric()
                MENU_VALUE -> addValue()
                MENU_ACCOUNT -> addAccount()
                MENU_ANY_FIELD -> addAnyField()
                MENU_TAG -> addTag()
                else -> false
            }
        }
        return popupMenu
    }

    private fun addDescription(): Boolean {
        val binding = binding ?: return false
        addDescription(binding.list, viewModel.addDescription())
        return true
    }

    private fun addDescription(parent: ViewGroup, criterion: SearchCriteria.Description) {
        val bindingItem = ItemSearchDescriptionBinding.inflate(layoutInflater, parent, true)
        bind(bindingItem, criterion)
    }

    private fun addNote(): Boolean {
        val binding = binding ?: return false
        addNote(binding.list, viewModel.addNote())
        return true
    }

    private fun addNote(parent: ViewGroup, criterion: SearchCriteria.Note) {
        val bindingItem = ItemSearchNotesBinding.inflate(layoutInflater, parent, true)
        bind(bindingItem, criterion)
    }

    private fun addMemo(): Boolean {
        val binding = binding ?: return false
        addMemo(binding.list, viewModel.addMemo())
        return true
    }

    private fun addMemo(parent: ViewGroup, criterion: SearchCriteria.Memo) {
        val bindingItem = ItemSearchMemoBinding.inflate(layoutInflater, parent, true)
        bind(bindingItem, criterion)
    }

    private fun addNumber(): Boolean {
        val binding = binding ?: return false
        addNumber(binding.list, viewModel.addNumber())
        return true
    }

    private fun addNumber(parent: ViewGroup, criterion: SearchCriteria.Number) {
        val bindingItem = ItemSearchNumberBinding.inflate(layoutInflater, parent, true)
        bind(bindingItem, criterion)
    }

    private fun addDatePosted(): Boolean {
        val binding = binding ?: return false
        addDatePosted(binding.list, viewModel.addDate())
        return true
    }

    private fun addDatePosted(parent: ViewGroup, criterion: SearchCriteria.Date) {
        val bindingItem = ItemSearchDateBinding.inflate(layoutInflater, parent, true)
        bind(bindingItem, criterion)
    }

    private fun addNumeric(): Boolean {
        val binding = binding ?: return false
        addNumeric(binding.list, viewModel.addNumeric())
        return true
    }

    private fun addNumeric(parent: ViewGroup, criterion: SearchCriteria.Numeric) {
        val bindingItem = ItemSearchNumericBinding.inflate(layoutInflater, parent, true)
        bind(bindingItem, criterion)
    }

    private fun addValue(): Boolean {
        val binding = binding ?: return false
        addValue(binding.list, viewModel.addValue())
        return true
    }

    private fun addValue(parent: ViewGroup, criterion: SearchCriteria.Numeric) {
        val bindingItem = ItemSearchNumericBinding.inflate(layoutInflater, parent, true)
        bind(bindingItem, criterion)
    }

    private fun addAccount(): Boolean {
        val binding = binding ?: return false
        addAccount(binding.list, viewModel.addAccount())
        return true
    }

    private fun addAccount(parent: ViewGroup, criterion: SearchCriteria.Account) {
        val bindingItem = ItemSearchAccountBinding.inflate(layoutInflater, parent, true)
        bind(bindingItem, criterion)
    }

    private fun addAnyField(): Boolean {
        val binding = binding ?: return false
        addAnyField(binding.list, viewModel.addAnyField())
        return true
    }

    private fun addAnyField(parent: ViewGroup, criterion: SearchCriteria.AnyField) {
        val bindingItem = ItemSearchDescriptionBinding.inflate(layoutInflater, parent, true)
        bind(bindingItem, criterion)
    }

    private fun addTag(): Boolean {
        val binding = binding ?: return false
        addTag(binding.list, viewModel.addTag())
        return true
    }

    private fun addTag(parent: ViewGroup, criterion: SearchCriteria.Tag) {
        val bindingItem = ItemSearchDescriptionBinding.inflate(layoutInflater, parent, true)
        bind(bindingItem, criterion)
    }

    private fun bind(binding: ItemSearchDescriptionBinding, criterion: SearchCriteria.AnyField) {
        val context: Context = binding.root.context
        val adapter = createStringComparisonAdapter(context)
        binding.comparison.adapter = adapter
        binding.comparison.onItemSelectedListener =
            DefaultItemSelectedListener { parent: AdapterView<*>,
                                          view: View?,
                                          position: Int,
                                          id: Long ->
                val item = adapter.getItem(position)!!
                criterion.compare = item.value
            }
        binding.comparison.post {
            binding.comparison.setSelection(adapter.getValuePosition(criterion.compare))
        }
        binding.inputTransactionName.setText(displayText(criterion))
        binding.inputTransactionName.addTextChangedListener(DefaultTextWatcher { s ->
            applyQuery(criterion, s.toString())
        })
        binding.deleteBtn.setOnClickListener {
            viewModel.remove(criterion)
            removeItem(binding.root)
        }
    }

    /**
     * Tags reuse the single-line text row; the string-comparison spinner is not
     * meaningful for a tag list, so it is hidden.
     */
    private fun bind(binding: ItemSearchDescriptionBinding, criterion: SearchCriteria.Tag) {
        binding.comparison.isVisible = false
        binding.inputTransactionName.setText(criterion.tags.joinToString(", "))
        binding.inputTransactionName.addTextChangedListener(DefaultTextWatcher { s ->
            criterion.tags = splitTags(s.toString()).toMutableList()
        })
        binding.deleteBtn.setOnClickListener {
            viewModel.remove(criterion)
            removeItem(binding.root)
        }
    }

    private fun displayText(criterion: SearchCriteria.AnyField): String {
        val joiner = if (criterion.op == ComparisonType.Any) " | " else " "
        return criterion.effectiveTerms().joinToString(joiner)
    }

    private fun applyQuery(criterion: SearchCriteria.AnyField, raw: String) {
        val (terms, op) = SearchTerms.parse(raw)
        // Drop the legacy single value, otherwise clearing the field would keep
        // filtering on a stale term.
        criterion.value = null
        criterion.terms = terms.toMutableList()
        criterion.op = op
    }

    private fun splitTags(raw: String): List<String> =
        raw.split(',', '\uFF0C', '\u3001', ' ', '\u3000')
            .map { it.trim() }
            .filter { it.isNotEmpty() }

    private fun removeItem(view: View) {
        val binding = binding ?: return
        val parent = binding.list
        parent.removeView(view)
    }

    private fun createStringComparisonAdapter(context: Context): SpinnerArrayAdapter<StringCompare> {
        val items = listOf(
            SpinnerItem(
                StringCompare.Contains,
                context.getString(R.string.search_compare_contains)
            ),
            SpinnerItem(
                StringCompare.Equals,
                context.getString(R.string.search_compare_equals)
            )
        )
        return SpinnerArrayAdapter(context, items)
    }

    private fun createDateComparisonAdapter(context: Context): SpinnerArrayAdapter<Compare> {
        val items = listOf(
            SpinnerItem(Compare.LessThan, context.getString(R.string.search_compare_date_lt)),
            SpinnerItem(
                Compare.LessThanOrEqualTo,
                context.getString(R.string.search_compare_date_lte)
            ),
            SpinnerItem(Compare.EqualTo, context.getString(R.string.search_compare_date_eq)),
            SpinnerItem(Compare.NotEqualTo, context.getString(R.string.search_compare_date_neq)),
            SpinnerItem(Compare.GreaterThan, context.getString(R.string.search_compare_date_gt)),
            SpinnerItem(
                Compare.GreaterThanOrEqualTo,
                context.getString(R.string.search_compare_date_gte)
            )
        )
        return SpinnerArrayAdapter(context, items)
    }

    private fun createNumericComparisonAdapter(context: Context): SpinnerArrayAdapter<Compare> {
        val items = listOf(
            SpinnerItem(Compare.LessThan, context.getString(R.string.search_compare_lt)),
            SpinnerItem(Compare.LessThanOrEqualTo, context.getString(R.string.search_compare_lte)),
            SpinnerItem(Compare.EqualTo, context.getString(R.string.search_compare_eq)),
            SpinnerItem(Compare.NotEqualTo, context.getString(R.string.search_compare_neq)),
            SpinnerItem(Compare.GreaterThan, context.getString(R.string.search_compare_gt)),
            SpinnerItem(
                Compare.GreaterThanOrEqualTo,
                context.getString(R.string.search_compare_gte)
            )
        )
        return SpinnerArrayAdapter(context, items)
    }

    private fun createNumericDebitCreditComparisonAdapter(context: Context): SpinnerArrayAdapter<Compare> {
        val items = listOf(
            SpinnerItem(Compare.LessThan, context.getString(R.string.search_compare_debcred_lt)),
            SpinnerItem(
                Compare.LessThanOrEqualTo,
                context.getString(R.string.search_compare_date_lte)
            ),
            SpinnerItem(Compare.EqualTo, context.getString(R.string.search_compare_debcred_eq)),
            SpinnerItem(Compare.NotEqualTo, context.getString(R.string.search_compare_debcred_neq)),
            SpinnerItem(Compare.GreaterThan, context.getString(R.string.search_compare_debcred_gt)),
            SpinnerItem(
                Compare.GreaterThanOrEqualTo,
                context.getString(R.string.search_compare_debcred_gte)
            )
        )
        return SpinnerArrayAdapter(context, items)
    }

    private fun createDebitCreditComparisonAdapter(context: Context): SpinnerArrayAdapter<NumericMatch> {
        val items = listOf(
            SpinnerItem(
                NumericMatch.HasDebitsOrCredits,
                context.getString(R.string.search_compare_numeric_any)
            ),
            SpinnerItem(
                NumericMatch.HasDebits,
                context.getString(R.string.search_compare_numeric_debit)
            ),
            SpinnerItem(
                NumericMatch.HasCredits,
                context.getString(R.string.search_compare_numeric_credit)
            )
        )
        return SpinnerArrayAdapter(context, items)
    }

    private fun createAccountComparisonAdapter(context: Context): SpinnerArrayAdapter<ComparisonType> {
        val items = listOf(
            SpinnerItem(
                ComparisonType.Any,
                context.getString(R.string.search_compare_account_any)
            ),
            SpinnerItem(
                ComparisonType.None,
                context.getString(R.string.search_compare_account_none)
            )
        )
        return SpinnerArrayAdapter(context, items)
    }

    //region 常用搜索 (saved searches)

    /** Saves the current criteria as a “常用搜索” entry (same name overwrites). */
    private fun showSaveFavourite() {
        val context = context ?: return
        SearchFavoritesUi.promptName(context, R.string.search_templates_save, null) { name ->
            SearchFavoritesUi.save(context, viewModel.createTemplate(name))
        }
    }

    /** Lists this screen's saved criteria; picking one fills the form. */
    private fun showFavourites() {
        val context = context ?: return
        SearchFavoritesUi.showList(context, SearchTemplateKind.Form) { template ->
            applyTemplate(template)
        }
    }

    /** Applies a saved entry to the form, then rebuilds the rows from the new form. */
    private fun applyTemplate(template: SearchTemplate) {
        val binding = this.binding ?: return
        if (!viewModel.applyTemplate(template)) {
            val context = context ?: return
            Toast.makeText(context, R.string.search_templates_invalid, Toast.LENGTH_SHORT).show()
            return
        }
        bind(binding, viewModel.form)
    }

    //endregion

    private fun submitForm(binding: FragmentSearchFormBinding) {
        // First, prepare the form for submission.
        val activity = activity ?: return
        val currentFocus = activity.currentFocus
        currentFocus?.clearFocus()

        // Submit the form.
        binding.root.post {
            viewModel.onSearchClicked()
        }
    }

    companion object {
        private const val MENU_DESCRIPTION = 0
        private const val MENU_NOTE = 1
        private const val MENU_MEMO = 2
        private const val MENU_NUMBER = 3
        private const val MENU_DATE_POSTED = 4
        private const val MENU_AMOUNT = 5
        private const val MENU_VALUE = 6
        private const val MENU_ACCOUNT = 7
        private const val MENU_ANY_FIELD = 8
        private const val MENU_TAG = 9

        private val dateFormatter: DateTimeFormatter = DateTimeFormat.fullDate()
    }
}