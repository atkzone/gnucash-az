package org.gnucash.android.ui.settings

import org.assertj.core.api.Assertions.assertThat
import org.gnucash.android.R
import org.gnucash.android.sync.ConflictKind
import org.gnucash.android.sync.FieldDeltaKind
import org.gnucash.android.sync.ResolveSide
import org.gnucash.android.sync.SyncRecordType
import org.gnucash.android.test.unit.GnuCashTest
import org.junit.Test

/**
 * The words for the conflict centre.
 *
 * The compiler already guarantees every enum constant has a branch and every id resolves. What
 * it cannot see is the failure that actually happens when these are written: a copied branch
 * that was never reworded, so two situations render identically and the user is told the wrong
 * one. Every group is therefore asserted to have **distinct** text as well as present text.
 */
class ConflictCenterTextTest : GnuCashTest() {

    private fun conflictKinds() = ConflictKind.entries.map { context.getString(ConflictCenterText.of(it)) }
    private fun deltas() = FieldDeltaKind.entries.map { context.getString(ConflictCenterText.of(it)) }
    private fun recordTypes() = SyncRecordType.entries.map { context.getString(ConflictCenterText.of(it)) }

    @Test
    fun `every situation has its own sentence`() {
        assertThat(conflictKinds()).doesNotHaveDuplicates().doesNotContainNull()
        assertThat(conflictKinds()).allSatisfy { assertThat(it).isNotBlank() }
    }

    @Test
    fun `every field difference has its own sentence`() {
        assertThat(deltas()).doesNotHaveDuplicates().doesNotContainNull()
        assertThat(deltas()).allSatisfy { assertThat(it).isNotBlank() }
    }

    @Test
    fun `every record type has its own name`() {
        assertThat(recordTypes()).doesNotHaveDuplicates().doesNotContainNull()
        assertThat(recordTypes()).allSatisfy { assertThat(it).isNotBlank() }
    }

    @Test
    fun `the two sides of a delete-versus-edit say which one was kept`() {
        // These are the pair most likely to be written once and copied: they look symmetric but
        // say opposite things about what happened to the user's data.
        val deletedThere = context.getString(ConflictCenterText.of(ConflictKind.DELETED_THERE_EDITED_HERE))
        val deletedHere = context.getString(ConflictCenterText.of(ConflictKind.DELETED_HERE_EDITED_THERE))
        assertThat(deletedThere).isNotEqualTo(deletedHere)
        assertThat(deletedThere).contains("云端删")
        assertThat(deletedHere).contains("本机删")
    }

    @Test
    fun `an unreadable record is not described as a conflict`() {
        // The one row where saying "the two sides disagree" would be a fabrication: nothing
        // about the peer's copy is known.
        val unreadable = context.getString(ConflictCenterText.of(ConflictKind.UNREADABLE))
        val bothEdited = context.getString(ConflictCenterText.of(ConflictKind.BOTH_EDITED))
        assertThat(unreadable).isNotEqualTo(bothEdited)
        assertThat(unreadable).contains("读不出来")
    }

    @Test
    fun `the row summary keeps the record kind, the situation and the count apart`() {
        // Positional arguments: a reordered list compiles and prints the count as the record
        // kind. Pin the whole line.
        val line = context.getString(
            R.string.conflict_center_row,
            context.getString(ConflictCenterText.of(SyncRecordType.ACCOUNT)),
            context.getString(ConflictCenterText.of(ConflictKind.BOTH_EDITED)),
            3
        )
        assertThat(line).contains("账户").contains("两端都改了").contains("3")
    }

    @Test
    fun `a field line shows all three sides, with an absent one named`() {
        // `null` is "that side has no value", and the line has to say so rather than leaving a
        // gap the user would read as an empty string.
        val line = context.getString(
            R.string.conflict_center_field,
            "notes",
            context.getString(ConflictCenterText.of(FieldDeltaKind.LOCAL_CHANGED)),
            "old",
            "mine",
            context.getString(R.string.conflict_center_side_absent)
        )
        assertThat(line).contains("notes").contains("本机改过")
        assertThat(line).contains("old").contains("mine")
        assertThat(line).contains(context.getString(R.string.conflict_center_side_absent))
    }

    // ------------------------------------------------------------ the two answers

    @Test
    fun `every way to settle a conflict has its own label`() {
        val labels = ResolveSide.entries.map { context.getString(ConflictCenterText.of(it)) }
        assertThat(labels).doesNotHaveDuplicates().doesNotContainNull()
        assertThat(labels).allSatisfy { assertThat(it).isNotBlank() }
        // The two buttons sit next to each other; identical or reversed text would make the
        // choice a coin flip.
        assertThat(labels).containsExactly("保留本机", "采用云端")
    }

    @Test
    fun `an unreadable record offers only one way to settle it`() {
        // Adopting the peer's version needs the peer's **content**, and an unreadable row has
        // none. Offering the button anyway would store an answer that could never be honoured:
        // every round would ignore it and the row would keep coming back while the user believes
        // they settled it.
        assertThat(ConflictCenterText.sidesFor(ConflictKind.UNREADABLE))
            .containsExactly(ResolveSide.LOCAL)
    }

    @Test
    fun `every other situation offers both sides, keep-local first`() {
        val others = ConflictKind.entries - ConflictKind.UNREADABLE
        assertThat(others).isNotEmpty()
        assertThat(others).allSatisfy {
            assertThat(ConflictCenterText.sidesFor(it))
                .containsExactly(ResolveSide.LOCAL, ResolveSide.REMOTE)
        }
    }
}
