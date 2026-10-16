package org.gnucash.android.test.unit.export

import org.assertj.core.api.Assertions.assertThat
import org.gnucash.android.db.DatabaseSchema.TransactionEntry
import org.gnucash.android.db.adapter.TagsDbAdapter
import org.gnucash.android.export.ExportFormat
import org.gnucash.android.export.ExportParams
import org.gnucash.android.export.xml.GncXmlExporter
import org.gnucash.android.importer.xml.GncXmlImporter
import org.gnucash.android.model.Account
import org.gnucash.android.model.Money
import org.gnucash.android.model.Split
import org.gnucash.android.model.Transaction
import org.gnucash.android.test.unit.BookHelperTest
import org.gnucash.android.ui.search.ComparisonType
import org.gnucash.android.ui.search.SearchForm
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.StringWriter
import java.math.BigDecimal
import java.nio.charset.StandardCharsets

/**
 * V7 of the plan's verification list: the `#tag` convention must survive a full
 * XML round trip (export → import), both as **text** (the source of truth, so
 * desktop GnuCash still sees it) and as the **queryable mirror** (feature ⑧).
 *
 * The mirror is deliberately *not* serialised into the XML — it is derived. So
 * the real question is whether re-importing the file rebuilds it. It does, but
 * only because `GncXmlHandler` saves transactions through
 * [org.gnucash.android.db.adapter.TransactionsDbAdapter.insert], which reaches the
 * dual-write hook in `addRecord`. That coupling is exactly what this test guards:
 * move the import onto a lower-level write path and the search-by-tag assertion
 * below goes red.
 */
class TagXmlRoundTripTest : BookHelperTest() {

    private lateinit var tagsDbAdapter: TagsDbAdapter

    private val lunchNotes = "午饭 #餐饮 #报销"
    private val lunchMemo = "打车 #出行"

    @Test
    fun `the hashtag text is present in the exported xml`() {
        val xml = exportTaggedTransaction()

        // Bite: if the exporter ever stopped writing the notes slot (or the memo),
        // the whole round trip below would silently "pass" over lost data.
        assertThat(xml).contains("#餐饮")
        assertThat(xml).contains("#报销")
        assertThat(xml).contains("#出行")
    }

    @Test
    fun `importing the exported xml keeps the hashtag text in notes and memo`() {
        val imported = roundTrip()

        assertThat(imported.notes).isEqualTo(lunchNotes)
        assertThat(imported.splits.map { it.memo }).contains(lunchMemo)
    }

    @Test
    fun `importing the exported xml rebuilds the tag mirror`() {
        val imported = roundTrip()

        tagsDbAdapter = TagsDbAdapter(accountsDbAdapter.holder)
        assertThat(tagsDbAdapter.getTagNamesForTransaction(imported.uid))
            .containsExactlyInAnyOrder("出行", "报销", "餐饮")
    }

    @Test
    fun `a tag search still matches after the round trip`() {
        val imported = roundTrip()

        tagsDbAdapter = TagsDbAdapter(accountsDbAdapter.holder)
        assertThat(searchUidsByTag("餐饮")).containsExactly(imported.uid)
    }

    // ---------------------------------------------------------------------

    /** Builds a tagged transaction, exports the book and re-imports the XML. */
    private fun roundTrip(): Transaction {
        val xml = exportTaggedTransaction()
        val bookUID = GncXmlImporter.parse(
            context,
            ByteArrayInputStream(xml.toByteArray(StandardCharsets.UTF_8))
        )
        // Re-import rewrites the book in place; rebind the adapters to be safe.
        setUpDbAdapters(bookUID)

        val imported = transactionsDbAdapter.getAllRecords(null, null)
            .firstOrNull { it.description == "TagXmlLunch" }
        assertThat(imported).`as`("the tagged transaction must survive the round trip").isNotNull()
        return imported!!
    }

    /** Imports an account chart (so a root account exists), adds a tagged
     *  transaction and returns the generated XML. */
    private fun exportTaggedTransaction(): String {
        val bookUID = importGnuCashXml("acctchrt_common.gnucash")

        val alpha = Account("XmlTagAlpha")
        val bravo = Account("XmlTagBravo")
        accountsDbAdapter.addRecord(bravo)
        accountsDbAdapter.addRecord(alpha)

        val transaction = Transaction("TagXmlLunch")
        transaction.notes = lunchNotes
        val split = Split(Money(BigDecimal.TEN, alpha.commodity), alpha)
        split.memo = lunchMemo
        transaction.addSplit(split)
        transaction.addSplit(split.createPair(bravo))
        transactionsDbAdapter.addRecord(transaction)

        val params = ExportParams(ExportFormat.XML).apply { isCompressed = false }
        val writer = StringWriter()
        GncXmlExporter(context, params, bookUID).export(bookUID, writer)
        return writer.toString().replace("\r\n", "\n")
    }

    private fun searchUidsByTag(tag: String): List<String> {
        val form = SearchForm(ComparisonType.All)
        form.addTag().apply {
            tags.add(tag)
            op = ComparisonType.Any
        }
        val cursor = transactionsDbAdapter.fetchSearch(form.buildSQL()) ?: return emptyList()
        val out = mutableListOf<String>()
        val index = cursor.getColumnIndex(TransactionEntry.COLUMN_UID)
            .takeIf { it >= 0 }
            ?: cursor.getColumnIndexOrThrow("t." + TransactionEntry.COLUMN_UID)
        cursor.use {
            while (it.moveToNext()) {
                out.add(it.getString(index))
            }
        }
        return out
    }
}
