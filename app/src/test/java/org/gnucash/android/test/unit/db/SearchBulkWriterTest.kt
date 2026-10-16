package org.gnucash.android.test.unit.db

import org.assertj.core.api.Assertions.assertThat
import org.gnucash.android.db.adapter.AccountsDbAdapter
import org.gnucash.android.db.adapter.DatabaseAdapter
import org.gnucash.android.db.adapter.TagsDbAdapter
import org.gnucash.android.db.adapter.TransactionsDbAdapter
import org.gnucash.android.model.Account
import org.gnucash.android.model.Money
import org.gnucash.android.model.Split
import org.gnucash.android.model.Transaction
import org.gnucash.android.test.unit.GnuCashTest
import org.gnucash.android.ui.search.BulkEditField
import org.gnucash.android.ui.search.SearchBulkWriter
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.math.BigDecimal
import java.util.Calendar
import java.util.TimeZone

/**
 * Bulk delete / bulk edit against a real database.
 *
 * The interesting parts are the two things a column-level UPDATE would silently
 * get wrong: a tag edit has to land in **both** the `#tag` text and the mirror
 * table, and editing a transaction must not destroy its splits on the way out.
 */
class SearchBulkWriterTest : GnuCashTest() {
    private lateinit var accountsDbAdapter: AccountsDbAdapter
    private lateinit var transactionsDbAdapter: TransactionsDbAdapter
    private lateinit var tagsDbAdapter: TagsDbAdapter
    private lateinit var writer: SearchBulkWriter
    private val zone: TimeZone = TimeZone.getTimeZone("Asia/Shanghai")

    private lateinit var alpha: Account
    private lateinit var bravo: Account
    private lateinit var lunch: Transaction
    private lateinit var taxi: Transaction
    private lateinit var movie: Transaction

    @Before
    fun setUp() {
        accountsDbAdapter = AccountsDbAdapter.instance
        transactionsDbAdapter = TransactionsDbAdapter.instance
        tagsDbAdapter = TagsDbAdapter.instance
        writer = SearchBulkWriter(transactionsDbAdapter, zone)

        alpha = Account("Bulk alpha")
        bravo = Account("Bulk bravo")
        accountsDbAdapter.addRecord(bravo)
        accountsDbAdapter.addRecord(alpha)

        lunch = save("午饭", "午饭 #餐饮 #报销", millis(2024, 3, 5, 14, 23, 45))
        taxi = save("打车", "打车 #餐饮", millis(2024, 4, 6, 9, 0, 0))
        movie = save("看电影", "", millis(2024, 5, 7, 20, 15, 0))
    }

    @After
    fun tearDown() {
        transactionsDbAdapter.deleteAllRecords()
        tagsDbAdapter.deleteAllRecords()
        accountsDbAdapter.deleteAllRecords()
    }

    // ------------------------------------------------------------- bulk delete

    @Test
    fun `delete removes every listed transaction and leaves the rest`() {
        assertThat(writer.delete(listOf(lunch.uid, movie.uid))).isEqualTo(2)

        assertThat(uidOfAll()).containsExactly(taxi.uid)
    }

    @Test
    fun `delete skips an unknown uid without aborting the batch`() {
        assertThat(writer.delete(listOf("does-not-exist", lunch.uid))).isEqualTo(1)
        assertThat(uidOfAll()).containsExactlyInAnyOrder(taxi.uid, movie.uid)
    }

    @Test
    fun `delete of an empty list is a no-op`() {
        assertThat(writer.delete(emptyList())).isZero
        assertThat(uidOfAll()).hasSize(3)
    }

    @Test
    fun `deleting a transaction also drops the splits it owned`() {
        writer.delete(listOf(lunch.uid))
        // Nothing may be left pointing at the deleted transaction.
        assertThat(transactionsDbAdapter.splitsDbAdapter.getSplitsForTransaction(lunch.uid)).isEmpty()
    }

    // -------------------------------------------------------------- bulk edit

    @Test
    fun `a description edit applies to every selected transaction only`() {
        assertThat(writer.update(listOf(lunch.uid, taxi.uid), BulkEditField.Description, "改后", false))
            .isEqualTo(2)

        assertThat(reload(lunch.uid).description).isEqualTo("改后")
        assertThat(reload(taxi.uid).description).isEqualTo("改后")
        assertThat(reload(movie.uid).description).isEqualTo("看电影")
    }

    @Test
    fun `a value is trimmed before it is written`() {
        writer.update(listOf(lunch.uid), BulkEditField.Number, "  42  ", false)
        assertThat(reload(lunch.uid).number).isEqualTo("42")
    }

    @Test
    fun `a blank value clears the field`() {
        writer.update(listOf(lunch.uid), BulkEditField.Notes, "   ", false)
        assertThat(reload(lunch.uid).notes).isEmpty()
    }

    @Test
    fun `editing a transaction does not drop its splits`() {
        val before = transactionsDbAdapter.splitsDbAdapter.getSplitsForTransaction(lunch.uid)
        assertThat(before).hasSize(2)

        writer.update(listOf(lunch.uid), BulkEditField.Description, "改后", false)

        // The write goes through addRecord, which deletes splits that are missing
        // from the model — so an un-loaded model would silently empty the
        // transaction. Two splits must still be there.
        assertThat(transactionsDbAdapter.splitsDbAdapter.getSplitsForTransaction(lunch.uid)).hasSize(2)
    }

    @Test
    fun `a date edit changes the day and keeps the time of day`() {
        assertThat(writer.update(listOf(lunch.uid), BulkEditField.Date, "2026-01-31", false))
            .isEqualTo(1)

        val calendar = Calendar.getInstance(zone).apply { timeInMillis = reload(lunch.uid).datePosted }
        assertThat(calendar.get(Calendar.YEAR)).isEqualTo(2026)
        assertThat(calendar.get(Calendar.MONTH)).isEqualTo(Calendar.JANUARY)
        assertThat(calendar.get(Calendar.DAY_OF_MONTH)).isEqualTo(31)
        assertThat(calendar.get(Calendar.HOUR_OF_DAY)).isEqualTo(14)
        assertThat(calendar.get(Calendar.MINUTE)).isEqualTo(23)
    }

    @Test
    fun `an unparsable date changes nothing and reports zero`() {
        val before = reload(lunch.uid).datePosted
        assertThat(writer.update(listOf(lunch.uid), BulkEditField.Date, "31/01/2026 maybe", false))
            .isZero
        assertThat(reload(lunch.uid).datePosted).isEqualTo(before)
    }

    // ------------------------------------------------------------ tag edits

    @Test
    fun `adding a tag writes the text and the mirror`() {
        assertThat(writer.update(listOf(lunch.uid, taxi.uid), BulkEditField.Tag, "旅行", false))
            .isEqualTo(2)

        // Text is the source of truth ...
        assertThat(reload(lunch.uid).notes).isEqualTo("午饭 #餐饮 #报销 #旅行")
        assertThat(reload(taxi.uid).notes).isEqualTo("打车 #餐饮 #旅行")
        // ... and the mirror the queryable copy, re-derived from that text.
        assertThat(tagsDbAdapter.getTagNamesForTransaction(lunch.uid))
            .containsExactly("报销", "旅行", "餐饮")
    }

    @Test
    fun `adding an already present tag does not duplicate it`() {
        writer.update(listOf(taxi.uid), BulkEditField.Tag, "餐饮", false)
        assertThat(reload(taxi.uid).notes).isEqualTo("打车 #餐饮")
        assertThat(tagsDbAdapter.getTagNamesForTransaction(taxi.uid)).containsExactly("餐饮")
    }

    @Test
    fun `removing a tag clears it from the text and the mirror`() {
        assertThat(writer.update(listOf(lunch.uid), BulkEditField.Tag, "餐饮", true)).isEqualTo(1)

        assertThat(reload(lunch.uid).notes).isEqualTo("午饭 #报销")
        assertThat(tagsDbAdapter.getTagNamesForTransaction(lunch.uid)).containsExactly("报销")
    }

    @Test
    fun `removing a tag also strips it from the description`() {
        // Tags can live in the description, and a leftover there would be
        // extracted straight back into the mirror on the same save.
        val tagged = reload(movie.uid)
        tagged.description = "看电影 #娱乐"
        transactionsDbAdapter.addRecord(tagged, DatabaseAdapter.UpdateMethod.Update)
        assertThat(tagsDbAdapter.getTagNamesForTransaction(movie.uid)).containsExactly("娱乐")

        assertThat(writer.update(listOf(movie.uid), BulkEditField.Tag, "娱乐", true)).isEqualTo(1)

        assertThat(reload(movie.uid).description).isEqualTo("看电影")
        assertThat(tagsDbAdapter.getTagNamesForTransaction(movie.uid)).isEmpty()
    }

    @Test
    fun `a blank tag name changes nothing`() {
        assertThat(writer.update(listOf(lunch.uid), BulkEditField.Tag, "  ", false)).isZero
        assertThat(reload(lunch.uid).notes).isEqualTo("午饭 #餐饮 #报销")
        assertThat(tagsDbAdapter.getTagNamesForTransaction(lunch.uid))
            .containsExactly("报销", "餐饮")
    }

    @Test
    fun `an unknown uid is skipped and reports zero`() {
        assertThat(writer.update(listOf("does-not-exist"), BulkEditField.Description, "x", false))
            .isZero
    }

    @Test
    fun `an empty selection reports zero`() {
        assertThat(writer.update(emptyList(), BulkEditField.Description, "x", false)).isZero
    }

    // ----------------------------------------------------- one transaction
    //
    // Both writes now run the whole batch inside a single transaction. The loop shape
    // itself is unchanged (a failing row is still skipped, not fatal), so the thing
    // worth proving is that wrapping it did not quietly shorten it.

    @Test
    fun `a batch wider than one chunk writes every row exactly once`() {
        // Two full chunks plus a partial one. The chunking is what keeps the write lock
        // from being held for the whole batch, and the chunk boundary is exactly where a
        // row can be silently skipped — every row has to land, in every chunk.
        val count = SearchBulkWriter.BATCH_SIZE * 2 + 7
        val uids = (1..count).map { index ->
            save("批量 $index", "", millis(2024, 6, 1, 0, index % 60, 0)).uid
        }
        assertThat(uidOfAll()).hasSize(count + 3)

        assertThat(writer.update(uids, BulkEditField.Number, "N", false)).isEqualTo(count)
        assertThat(uids.all { reload(it).number == "N" })
            .`as`("每一块都要真的写进去，不只是最后一块")
            .isTrue()

        assertThat(writer.delete(uids)).isEqualTo(count)

        // Only the three fixtures planted in setUp survive.
        assertThat(uidOfAll()).containsExactlyInAnyOrder(lunch.uid, taxi.uid, movie.uid)
    }

    // ------------------------------------------------------------------ helpers

    private fun reload(uid: String): Transaction = transactionsDbAdapter.getRecord(uid)

    private fun uidOfAll(): List<String> = transactionsDbAdapter
        .getAllRecords(null, null, null)
        .map { it.uid }

    private fun save(description: String, notes: String, datePosted: Long): Transaction {
        val transaction = Transaction(description)
        transaction.notes = notes
        transaction.datePosted = datePosted
        val split = Split(Money(BigDecimal.TEN, alpha.commodity), alpha)
        transaction.addSplit(split)
        transaction.addSplit(split.createPair(bravo))
        transactionsDbAdapter.addRecord(transaction)
        return transaction
    }

    private fun millis(
        year: Int,
        month: Int,
        day: Int,
        hour: Int,
        minute: Int,
        second: Int
    ): Long = Calendar.getInstance(zone).apply {
        clear()
        set(year, month - 1, day, hour, minute, second)
    }.timeInMillis
}
