package org.gnucash.android.ui.search

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.Toast
import androidx.appcompat.app.ActionBar
import androidx.fragment.app.Fragment
import org.gnucash.android.R
import org.gnucash.android.app.actionBar
import org.gnucash.android.databinding.FragmentFullTextSearchBinding

/**
 * The "全文" search screen — the compact keyword search reachable from the home
 * screen's top-right action.
 *
 * It owns no search logic: the query string is turned into a bare SQL `WHERE`
 * fragment by [FullTextQuery] and handed to [SearchResultsFragment], the same
 * way the form-based search does it.
 */
class FullTextSearchFragment : Fragment() {
    private var binding: FragmentFullTextSearchBinding? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val binding = FragmentFullTextSearchBinding.inflate(inflater, container, false)
        this.binding = binding
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val actionBar: ActionBar? = this.actionBar
        actionBar?.setTitle(R.string.title_full_text_search)

        val binding = this.binding ?: return
        binding.btnSearch.setOnClickListener { submit() }
        binding.btnSaveFavorite.setOnClickListener { saveFavorite() }
        binding.btnFavorites.setOnClickListener { showFavorites() }
        binding.btnAdvancedSearch.setOnClickListener { openAdvancedSearch() }
        binding.inputQuery.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                submit()
                true
            } else {
                false
            }
        }
        binding.inputQuery.requestFocus()
    }

    /** Saves the current query as a "常用搜索" entry (same name overwrites). */
    private fun saveFavorite() {
        val binding = this.binding ?: return
        val raw = binding.inputQuery.text?.toString()?.trim().orEmpty()
        if (raw.isEmpty()) {
            Toast.makeText(requireContext(), R.string.full_text_search_empty, Toast.LENGTH_SHORT).show()
            return
        }
        SearchFavoritesUi.promptName(requireContext(), R.string.search_templates_save, null) { name ->
            SearchFavoritesUi.save(requireContext(), SearchFavoritesUi.fullTextTemplate(name, raw))
        }
    }

    /** Lists this screen's saved queries; picking one runs it again. */
    private fun showFavorites() {
        val binding = this.binding ?: return
        SearchFavoritesUi.showList(requireContext(), SearchTemplateKind.FullText) { template ->
            binding.inputQuery.setText(template.query.orEmpty())
            submit()
        }
    }

    /**
     * Hands over to the criteria ("高级搜索") screen in the same container, keeping
     * back navigation — the two screens are two ways to build the same WHERE.
     */
    private fun openAdvancedSearch() {
        parentFragmentManager.beginTransaction()
            .replace(R.id.fragment_container, SearchFormFragment())
            .addToBackStack("advanced_search")
            .commit()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        binding = null
    }

    private fun submit() {
        val binding = this.binding ?: return
        val where = FullTextQuery.build(binding.inputQuery.text?.toString())
        if (where.isEmpty()) {
            Toast.makeText(
                requireContext(),
                R.string.full_text_search_empty,
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        val args = Bundle()
        args.putString(SearchResultsFragment.EXTRA_FORM, where)

        val fragment = SearchResultsFragment()
        fragment.arguments = args

        parentFragmentManager.beginTransaction()
            .replace(R.id.fragment_container, fragment)
            .addToBackStack("full_text_search")
            .commit()
    }
}
