package org.gnucash.android.ui.settings

import org.assertj.core.api.Assertions.assertThat
import org.gnucash.android.sync.ConflictReview
import org.gnucash.android.sync.FieldDiff
import org.gnucash.android.sync.MergeDecision
import org.gnucash.android.sync.Side
import org.gnucash.android.sync.SyncIdentity
import org.gnucash.android.sync.SyncRecordType
import org.gnucash.android.sync.fieldDiffs
import org.junit.Test

/**
 * How the parked conflicts survive the trip from the settings fragment to the dialog.
 *
 * Pure JVM. The payload is what the dialog is rebuilt from, so the round-trip is the contract:
 * anything the encoding quietly drops is something the user never sees, and anything it mangles
 * is something they see wrongly. The two distinctions worth more than the rest are `null` versus
 * `""` (a side without a value versus a side that blanked it) and the caps, which must be
 * reported rather than silently applied.
 */
class ConflictCenterCodecTest {

    private val id = SyncIdentity(SyncRecordType.TRANSACTION, "8f2a1b3c9d")

    private fun review(
        note: String = "both sides changed the record differently",
        decision: MergeDecision? = MergeDecision.Conflict(listOf(FieldDiff("notes", "old", "mine", "theirs")))
    ) = ConflictReview(id, note, decision)

    @Test
    fun `a conflict survives the round trip`() {
        val reviews = listOf(review())
        assertThat(ConflictCenterCodec.decode(ConflictCenterCodec.encode(reviews)).reviews)
            .isEqualTo(reviews)
    }

    @Test
    fun `a delete-versus-edit keeps the side that won`() {
        // The winner is what the row's wording is derived from; losing it would relabel the
        // record as "both edited" and hide which change was kept.
        for (winner in listOf(Side.Local, Side.Remote)) {
            val reviews = listOf(review(decision = MergeDecision.DeleteVsEdit(winner, listOf(FieldDiff("n", null, null, "x")))))
            assertThat(ConflictCenterCodec.decode(ConflictCenterCodec.encode(reviews)).reviews)
                .`as`("$winner")
                .isEqualTo(reviews)
        }
    }

    @Test
    fun `an unreadable record keeps its identity and note`() {
        // It has no decision to carry, but it is still a record the user has to be told about.
        val reviews = listOf(review(note = "the remote record could not be read", decision = null))
        val decoded = ConflictCenterCodec.decode(ConflictCenterCodec.encode(reviews)).reviews.single()
        assertThat(decoded.identity).isEqualTo(id)
        assertThat(decoded.note).isEqualTo("the remote record could not be read")
        assertThat(decoded.decision).isNull()
    }

    @Test
    fun `values with separators and newlines survive verbatim`() {
        // A transaction's notes is free text: `=` and `%` are its own separator/escape, and a
        // multi-line value must not be mistaken for a second record.
        val nasty = "a=b%c\nd\re"
        val reviews = listOf(review(decision = MergeDecision.Conflict(listOf(FieldDiff("notes", nasty, "", nasty)))))
        val decoded = ConflictCenterCodec.decode(ConflictCenterCodec.encode(reviews)).reviews.single()
        val diff = decoded.decision!!.fieldDiffs.single()
        assertThat(diff.base).isEqualTo(nasty)
        assertThat(diff.local).isEqualTo("")
        assertThat(diff.remote).isEqualTo(nasty)
    }

    @Test
    fun `a side with no value stays absent and is not blanked`() {
        // The single most damaging confusion this format could make: `null` means the side has
        // no such field; `""` means it has one, set to nothing.
        val reviews = listOf(
            review(
                decision = MergeDecision.Conflict(
                    listOf(
                        FieldDiff("a", null, "l", "r"),
                        FieldDiff("b", "base", "", ""),
                        FieldDiff("c", null, null, null)
                    )
                )
            )
        )
        val diffs = ConflictCenterCodec.decode(ConflictCenterCodec.encode(reviews))
            .reviews.single().decision!!.fieldDiffs
        assertThat(diffs[0].base).isNull()
        assertThat(diffs[1].local).isEmpty()
        assertThat(diffs[2].base).isNull()
        assertThat(diffs[2].local).isNull()
        assertThat(diffs[2].remote).isNull()
    }

    @Test
    fun `many diffs keep their order and their fields`() {
        val diffs = (0 until 12).map { FieldDiff("f$it", "b$it", "l$it", "r$it") }
        val reviews = listOf(review(decision = MergeDecision.Conflict(diffs)))
        assertThat(ConflictCenterCodec.decode(ConflictCenterCodec.encode(reviews)).reviews.single().decision!!.fieldDiffs)
            .isEqualTo(diffs)
    }

    @Test
    fun `encoding is deterministic`() {
        // Both levels sort their keys, so the same round always produces the same payload — a
        // test can pin it, and two identical situations cannot look different.
        val reviews = listOf(review(), review(decision = MergeDecision.Conflict(emptyList())))
        assertThat(ConflictCenterCodec.encode(reviews))
            .isEqualTo(ConflictCenterCodec.encode(reviews.toList()))
    }

    @Test
    fun `a review with no diffs survives as a review with no diffs`() {
        val reviews = listOf(review(decision = MergeDecision.Conflict(emptyList())))
        val decoded = ConflictCenterCodec.decode(ConflictCenterCodec.encode(reviews)).reviews.single()
        assertThat(decoded.decision!!.fieldDiffs).isEmpty()
    }

    @Test
    fun `an empty list and nothing at all both decode to nothing, losing nothing`() {
        assertThat(ConflictCenterCodec.decode(ConflictCenterCodec.encode(emptyList())).reviews).isEmpty()
        assertThat(ConflictCenterCodec.decode(ConflictCenterCodec.encode(emptyList())).dropped).isZero()
        assertThat(ConflictCenterCodec.decode(null).reviews).isEmpty()
        assertThat(ConflictCenterCodec.decode("").reviews).isEmpty()
    }

    @Test
    fun `an unreadable payload is reported as a loss, not as emptiness`() {
        // "Nothing could be read" and "there was nothing to read" must not look the same: the
        // first means records are missing from the screen the user is about to judge by.
        val decoded = ConflictCenterCodec.decode("this is not a payload at all\nneither is this")
        assertThat(decoded.reviews).isEmpty()
        assertThat(decoded.dropped).isEqualTo(1)
    }

    @Test
    fun `a row whose identity will not parse is dropped and counted`() {
        // The identity is replaced with something that is not a record token; the payload is
        // otherwise well-formed, so the loss is exactly one row.
        val broken = ConflictCenterCodec.encode(listOf(review()))
            .replace("transaction:8f2a1b3c9d", "not-an-identity")
        assertThat(broken).doesNotContain("transaction:8f2a1b3c9d")

        val decoded = ConflictCenterCodec.decode(broken)
        assertThat(decoded.reviews).isEmpty()
        assertThat(decoded.dropped).isEqualTo(1)
    }

    @Test
    fun `a missing note is an empty note, not a lost record`() {
        // The note is log text; without it the record is still perfectly showable. Written by
        // hand so the test says exactly which key is absent, rather than trimming one out of a
        // generated payload and hoping the escaping lined up.
        val payload = "r0=id%3Dtransaction:8f2a1b3c9d%0Adecision%3Dconflict"
        val decoded = ConflictCenterCodec.decode(payload)
        assertThat(decoded.reviews).hasSize(1)
        assertThat(decoded.reviews.single().note).isEmpty()
        assertThat(decoded.dropped).isZero()
    }

    @Test
    fun `too many records are capped, and the cap is announced`() {
        val reviews = (0 until ConflictCenterCodec.MAX_REVIEWS + 5).map { index ->
            ConflictReview(SyncIdentity(SyncRecordType.ACCOUNT, "key$index"), "n", MergeDecision.Conflict(emptyList()))
        }
        val decoded = ConflictCenterCodec.decode(ConflictCenterCodec.encode(reviews))
        assertThat(decoded.reviews).hasSize(ConflictCenterCodec.MAX_REVIEWS)
        assertThat(decoded.capped).isTrue()
        // The ones kept are the first ones, in order — not an arbitrary subset.
        assertThat(decoded.reviews.first().identity.key).isEqualTo("key0")
    }

    @Test
    fun `a payload within the cap is not announced as capped`() {
        val reviews = (0 until ConflictCenterCodec.MAX_REVIEWS).map { index ->
            ConflictReview(SyncIdentity(SyncRecordType.ACCOUNT, "key$index"), "n", MergeDecision.Conflict(emptyList()))
        }
        assertThat(ConflictCenterCodec.decode(ConflictCenterCodec.encode(reviews)).capped).isFalse()
    }

    @Test
    fun `an over-long value is cut and the cut is announced`() {
        val long = "x".repeat(ConflictCenterCodec.MAX_VALUE_CHARS + 50)
        val reviews = listOf(review(decision = MergeDecision.Conflict(listOf(FieldDiff("notes", long, null, null)))))
        val decoded = ConflictCenterCodec.decode(ConflictCenterCodec.encode(reviews))

        assertThat(decoded.truncated).isTrue()
        val cut = decoded.reviews.single().decision!!.fieldDiffs.single().base!!
        assertThat(cut).hasSize(ConflictCenterCodec.MAX_VALUE_CHARS + 1)
        assertThat(cut).endsWith("…")
    }

    @Test
    fun `a value exactly at the limit is not cut and is not announced`() {
        val exact = "x".repeat(ConflictCenterCodec.MAX_VALUE_CHARS)
        val reviews = listOf(review(decision = MergeDecision.Conflict(listOf(FieldDiff("notes", exact, null, null)))))
        val decoded = ConflictCenterCodec.decode(ConflictCenterCodec.encode(reviews))

        assertThat(decoded.truncated).isFalse()
        assertThat(decoded.reviews.single().decision!!.fieldDiffs.single().base).isEqualTo(exact)
    }

    @Test
    fun `a real value that ends in an ellipsis is not reported as cut`() {
        // The cut is recorded when it happens, never inferred from the text: inferring it would
        // put "the value was shortened" on a value that is complete.
        val ellipsis = "x".repeat(ConflictCenterCodec.MAX_VALUE_CHARS - 1) + "…"
        val reviews = listOf(review(decision = MergeDecision.Conflict(listOf(FieldDiff("notes", ellipsis, null, null)))))
        val decoded = ConflictCenterCodec.decode(ConflictCenterCodec.encode(reviews))

        assertThat(decoded.truncated).isFalse()
        assertThat(decoded.reviews.single().decision!!.fieldDiffs.single().base).isEqualTo(ellipsis)
    }

    @Test
    fun `a decision that is never parked comes back as no decision`() {
        // Documented lossiness: only the two reviewable decisions are encoded as themselves.
        val reviews = listOf(review(decision = MergeDecision.KeepLocal))
        assertThat(ConflictCenterCodec.decode(ConflictCenterCodec.encode(reviews)).reviews.single().decision)
            .isNull()
    }

    @Test
    fun `an unreadable winner comes back as no decision rather than the wrong side`() {
        val payload = ConflictCenterCodec.encode(listOf(review(decision = MergeDecision.DeleteVsEdit(Side.Local, emptyList()))))
            .replace("deleteVsEdit:local", "deleteVsEdit:neither")
        assertThat(ConflictCenterCodec.decode(payload).reviews.single().decision).isNull()
    }
}
