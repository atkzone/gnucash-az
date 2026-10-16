package org.gnucash.android.ui.search

/**
 * Editable form backing the search UI. Holds an ordered list of [SearchCriteria]
 * plus the boolean operator **between** criteria.
 *
 * SQL generation is delegated to [SearchQueryBuilder] (single implementation).
 */
data class SearchForm(
    var comparisonType: ComparisonType = ComparisonType.All
) {
    private val _criteria: MutableList<SearchCriteria> = mutableListOf()
    val criteria: List<SearchCriteria> get() = _criteria

    fun addDescription(): SearchCriteria.Description {
        val criterion = SearchCriteria.Description()
        _criteria.add(criterion)
        return criterion
    }

    fun addNote(): SearchCriteria.Note {
        val criterion = SearchCriteria.Note()
        _criteria.add(criterion)
        return criterion
    }

    fun addMemo(): SearchCriteria.Memo {
        val criterion = SearchCriteria.Memo()
        _criteria.add(criterion)
        return criterion
    }

    fun addNumber(): SearchCriteria.Number {
        val criterion = SearchCriteria.Number()
        _criteria.add(criterion)
        return criterion
    }

    fun addDate(): SearchCriteria.Date {
        val criterion = SearchCriteria.Date()
        _criteria.add(criterion)
        return criterion
    }

    fun addNumeric(debcred: Boolean): SearchCriteria.Numeric {
        val match = if (debcred) NumericMatch.HasDebitsOrCredits else null
        val criterion = SearchCriteria.Numeric(match = match)
        _criteria.add(criterion)
        return criterion
    }

    fun addAccount(): SearchCriteria.Account {
        val criterion = SearchCriteria.Account()
        _criteria.add(criterion)
        return criterion
    }

    /** "Any field" free-text criterion (①a 全字段搜索). */
    fun addAnyField(): SearchCriteria.AnyField {
        val criterion = SearchCriteria.AnyField()
        _criteria.add(criterion)
        return criterion
    }

    /** Tag criterion (①b; needs feature ⑧'s tag table to return data). */
    fun addTag(): SearchCriteria.Tag {
        val criterion = SearchCriteria.Tag()
        _criteria.add(criterion)
        return criterion
    }

    /**
     * Convenience: parse a raw query (`打车 地铁` = AND, `打车 | 地铁` = OR)
     * into an "any field" criterion.
     */
    fun addAnyFieldKeywords(raw: String): SearchCriteria.AnyField {
        val criterion = addAnyField()
        val (terms, op) = SearchTerms.parse(raw)
        criterion.terms.addAll(terms)
        criterion.op = op
        return criterion
    }

    fun remove(criterion: SearchCriteria) {
        _criteria.remove(criterion)
    }

    fun clear() {
        _criteria.clear()
    }

    fun buildSQL(): String = SearchQueryBuilder(comparisonType, criteria).build()

    /**
     * Rehydrates the form from a stored criterion list (used by search templates).
     * Internal because callers should go through [SearchTemplate].
     */
    internal fun restore(criteria: List<SearchCriteria>, comparisonType: ComparisonType = this.comparisonType) {
        _criteria.clear()
        _criteria.addAll(criteria)
        this.comparisonType = comparisonType
    }
}
