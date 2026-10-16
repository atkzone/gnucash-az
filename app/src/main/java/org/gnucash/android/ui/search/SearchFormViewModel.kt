package org.gnucash.android.ui.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.joda.time.LocalDate

class SearchFormViewModel : ViewModel() {
    val form = SearchForm()

    private val _query = MutableStateFlow<String?>(null)
    val query: Flow<String?> = _query

    fun onSearchClicked() {
        showResultsPage()
    }

    private fun showResultsPage() {
        viewModelScope.launch {
            _query.emit(form.buildSQL())
        }
    }

    fun onSearchShown() {
        _query.value = null
    }

    fun setComparison(value: ComparisonType) {
        form.comparisonType = value
    }

    fun addDescription(): SearchCriteria.Description {
        return form.addDescription()
    }

    fun addNote(): SearchCriteria.Note {
        return form.addNote()
    }

    fun addMemo(): SearchCriteria.Memo {
        return form.addMemo()
    }

    fun addNumber(): SearchCriteria.Number {
        return form.addNumber()
    }

    fun addDate(): SearchCriteria.Date {
        return form.addDate().apply {
            value = LocalDate.now()
        }
    }

    fun addNumeric(): SearchCriteria.Numeric {
        return form.addNumeric(false)
    }

    fun addValue(): SearchCriteria.Numeric {
        return form.addNumeric(true)
    }

    fun addAccount(): SearchCriteria.Account {
        return form.addAccount()
    }

    fun addAnyField(): SearchCriteria.AnyField {
        return form.addAnyField()
    }

    fun addTag(): SearchCriteria.Tag {
        return form.addTag()
    }

    fun remove(item: SearchCriteria) {
        form.remove(item)
    }

    /**
     * Snapshots the current form into a new (unsaved, id == 0) template.
     * Criteria are copied so later edits to the form do not leak into the
     * snapshot (and vice versa).
     */
    fun createTemplate(name: String): SearchTemplate {
        val snapshot = SearchForm(form.comparisonType)
        snapshot.restore(form.criteria.map { it.copyForApply() }, form.comparisonType)
        return SearchTemplate(name = name, form = snapshot)
    }

    /**
     * Replaces the current form with the criteria stored in [template].
     *
     * @return `false` when the template carries no usable criterion (i.e. it is
     * empty or its payload could not be decoded), so callers can tell the user
     * instead of silently searching for everything.
     */
    fun applyTemplate(template: SearchTemplate): Boolean {
        val criteria = template.form.criteria.map { it.copyForApply() }
        if (criteria.none { !it.isEmpty() }) return false
        form.restore(criteria, template.form.comparisonType)
        return true
    }
}