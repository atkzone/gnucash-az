package org.gnucash.android.ui.search

import org.assertj.core.api.Assertions.assertThat
import org.gnucash.android.test.unit.GnuCashTest
import org.joda.time.LocalDate
import org.junit.Test
import java.math.BigDecimal

/**
 * Round-trip tests for [SearchTemplateCodec]: a saved template must rebuild the
 * exact same search (asserted via the generated SQL, not just object equality).
 */
class SearchTemplateTest : GnuCashTest() {

    @Test
    fun `round trip preserves generated SQL and comparison`() {
        val form = SearchForm(ComparisonType.None)
        form.addDescription().apply {
            terms = mutableListOf("alpha", "beta")
            op = ComparisonType.Any
            compare = StringCompare.Contains
        }
        form.addMemo().apply { value = "zebra" }
        form.addTag().apply {
            tags = mutableListOf("food")
            op = ComparisonType.Any
        }
        form.addAnyField().apply { value = "coffee" }

        val restored = SearchTemplateCodec.decode(SearchTemplateCodec.encode(form))

        assertThat(restored.comparisonType).isEqualTo(ComparisonType.None)
        assertThat(restored.buildSQL()).isEqualTo(form.buildSQL())
    }

    @Test
    fun `date and numeric round trip`() {
        val form = SearchForm()
        form.addDate().apply {
            value = LocalDate(2026, 10, 11)
            compare = Compare.GreaterThan
        }
        form.addNumeric(false).apply {
            value = BigDecimal("123.45")
            match = NumericMatch.HasDebits
            compare = Compare.EqualTo
        }

        val restored = SearchTemplateCodec.decode(SearchTemplateCodec.encode(form))

        assertThat(restored.buildSQL()).isEqualTo(form.buildSQL())
    }

    @Test
    fun `account uid survives round trip without a resolver`() {
        val form = SearchForm()
        form.addAccount().apply {
            uid = "account-uid-1"
            compare = ComparisonType.Any
        }

        val restored = SearchTemplateCodec.decode(SearchTemplateCodec.encode(form))

        val criterion = restored.criteria.single() as SearchCriteria.Account
        assertThat(criterion.resolvedUid()).isEqualTo("account-uid-1")
        // A missing resolver must NOT silently drop the account filter.
        assertThat(restored.buildSQL()).isEqualTo(form.buildSQL())
    }

    @Test
    fun `account resolver receives the stored uid`() {
        val form = SearchForm()
        form.addAccount().apply { uid = "account-uid-2" }
        val json = SearchTemplateCodec.encode(form)

        var seen: String? = null
        SearchTemplateCodec.decode(json) { uid ->
            seen = uid
            null
        }

        assertThat(seen).isEqualTo("account-uid-2")
    }

    @Test
    fun `empty form round trips to empty SQL`() {
        val restored = SearchTemplateCodec.decode(SearchTemplateCodec.encode(SearchForm()))
        assertThat(restored.buildSQL()).isEmpty()
        assertThat(restored.criteria).isEmpty()
    }

    // ---- 常用搜索: the two kinds ----------------------------------------------

    @Test
    fun `a form favourite keeps the legacy payload and reads back as Form`() {
        val form = SearchForm()
        form.addDescription().apply { value = "alpha" }
        val template = SearchTemplate(name = "alpha", form = form)

        val json = SearchTemplateCodec.encode(template)

        // Byte-identical to the historical encoder: every row written before the
        // two kinds existed must keep decoding unchanged.
        assertThat(json).isEqualTo(SearchTemplateCodec.encode(form))
        assertThat(SearchTemplateCodec.kindOf(json)).isEqualTo(SearchTemplateKind.Form)
        assertThat(SearchTemplateCodec.fullTextQueryOf(json)).isNull()
        assertThat(SearchTemplateCodec.decode(json).buildSQL()).isEqualTo(form.buildSQL())
    }

    @Test
    fun `a full text favourite round trips its raw query`() {
        val template = SearchTemplate(
            name = "通勤",
            kind = SearchTemplateKind.FullText,
            query = "打车 | 地铁 not 加油"
        )

        val json = SearchTemplateCodec.encode(template)

        assertThat(SearchTemplateCodec.kindOf(json)).isEqualTo(SearchTemplateKind.FullText)
        assertThat(SearchTemplateCodec.fullTextQueryOf(json)).isEqualTo("打车 | 地铁 not 加油")
        // The stored text — not the compiled SQL — is the payload, so it recompiles
        // to exactly the search the screen would run today.
        assertThat(FullTextQuery.build(SearchTemplateCodec.fullTextQueryOf(json)))
            .isEqualTo(FullTextQuery.build("打车 | 地铁 not 加油"))
    }

    @Test
    fun `an empty full text query is read back as absent`() {
        val json = SearchTemplateCodec.encode(
            SearchTemplate(name = "blank", kind = SearchTemplateKind.FullText, query = null)
        )
        assertThat(SearchTemplateCodec.kindOf(json)).isEqualTo(SearchTemplateKind.FullText)
        assertThat(SearchTemplateCodec.fullTextQueryOf(json)).isNull()
    }

    @Test
    fun `a corrupt payload degrades to the Form kind instead of throwing`() {
        assertThat(SearchTemplateCodec.kindOf("not-json")).isEqualTo(SearchTemplateKind.Form)
        assertThat(SearchTemplateCodec.fullTextQueryOf("not-json")).isNull()
    }

    @Test
    fun `a full text envelope decodes to an empty form so it cannot search everything`() {
        val json = SearchTemplateCodec.encode(
            SearchTemplate(name = "全文", kind = SearchTemplateKind.FullText, query = "地铁")
        )
        // A full-text entry must never be applied as a criteria set: an empty
        // form is what the advanced screen refuses to run.
        assertThat(SearchTemplateCodec.decode(json).criteria).isEmpty()
        assertThat(SearchTemplateCodec.decode(json).buildSQL()).isEmpty()
    }
}
