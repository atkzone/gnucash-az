package org.gnucash.android.test.unit.sync

import org.assertj.core.api.Assertions.assertThat
import org.gnucash.android.model.SyncBase
import org.gnucash.android.sync.ConflictReview
import org.gnucash.android.sync.MergeDecision
import org.gnucash.android.sync.PlanInput
import org.gnucash.android.sync.RecordCodec
import org.gnucash.android.sync.RecordState
import org.gnucash.android.sync.RemoteState
import org.gnucash.android.sync.ResolveSide
import org.gnucash.android.sync.SyncIdentity
import org.gnucash.android.sync.SyncLayout
import org.gnucash.android.sync.SyncPlan
import org.gnucash.android.sync.SyncRecordType
import org.gnucash.android.sync.SyncRoundPlanner
import org.gnucash.android.sync.SyncVerb
import org.gnucash.android.sync.webdav.PutPrecondition
import org.gnucash.android.sync.webdav.WebDavActions
import org.junit.Test

/**
 * The round planner (plan §5②, §7.2–7.4).
 *
 * This is where the three halves meet, so the cases are organised around the questions a
 * merge has to get right rather than around the code shape: what happens when only one
 * side changed, when both changed, when one side deleted, and — the expensive ones — when
 * the listing cannot be trusted. A plain JVM test: every one of these must be decided
 * without a server behaving well.
 */
class SyncRoundPlannerTest {

    private val tx = SyncRecordType.TRANSACTION

    // ---------------------------------------------------------------- fixtures

    private fun id(key: String) = SyncIdentity(tx, key)

    private fun local(key: String, fields: Map<String, String>) = RecordState(tx, key, fields)

    private fun remote(key: String, fields: Map<String, String>, etag: String? = null) =
        RemoteState(id(key), etag, fields, SyncLayout.pathFor(tx, key))

    private fun remoteUnreadable(key: String, etag: String? = null) =
        RemoteState(id(key), etag, null, SyncLayout.pathFor(tx, key))

    private fun base(
        key: String,
        fields: Map<String, String>,
        etag: String? = null,
        pendingSince: Long? = null
    ) = SyncBase(tx, key, RecordCodec.encode(fields), 0L, etag, pendingSince)

    private fun plan(
        local: List<RecordState> = emptyList(),
        remote: List<RemoteState> = emptyList(),
        bases: List<SyncBase> = emptyList(),
        now: Long = 1_000L,
        complete: Boolean = true,
        resolutions: Map<SyncIdentity, ResolveSide> = emptyMap()
    ): SyncPlan = SyncRoundPlanner.plan(PlanInput(local, remote, bases, now, complete, resolutions))

    private fun SyncPlan.item(key: String) = items.first { it.identity == id(key) }
    private fun SyncPlan.reviewsFor(key: String) = reviews.filter { it.identity == id(key) }
    private fun SyncPlan.hasItem(key: String) = items.any { it.identity == id(key) }

    // --------------------------------------------------------------- gates first

    @Test
    fun `an incomplete listing abandons the round and touches nothing`() {
        // Deliberately *not* an empty listing: with a peer record in hand the round would
        // happily adopt it, which is exactly what must not happen — a truncated listing is
        // not a smaller true listing, it is an untrustworthy one. (An empty listing would be
        // caught by the second gate instead, so it would not isolate this one.)
        val result = plan(
            local = listOf(local("t1", mapOf("a" to "1"))),
            remote = listOf(remote("t2", mapOf("a" to "2"), etag = "e2")),
            bases = emptyList(),
            complete = false
        )

        assertThat(result.abandoned).isTrue()
        assertThat(result.items).isEmpty()
        assertThat(result.tombstones).isEmpty()
    }

    @Test
    fun `an empty listing while records are known abandons the round`() {
        // The classic "wrong folder / partial view" shape: continuing would read every
        // record as remote-deleted.
        val result = plan(
            local = listOf(local("t1", mapOf("a" to "1"))),
            remote = emptyList(),
            bases = listOf(base("t1", mapOf("a" to "1"), etag = "e1"))
        )

        assertThat(result.abandoned).isTrue()
        assertThat(result.items).isEmpty()
    }

    @Test
    fun `an abandoned round keeps the pending markers it had`() {
        val result = plan(
            local = listOf(local("t1", mapOf("a" to "1"))),
            remote = emptyList(),
            bases = listOf(base("t1", mapOf("a" to "1"), etag = "e1", pendingSince = 500L)),
            complete = false
        )
        assertThat(result.pending).containsEntry(id("t1"), 500L)
    }

    // --------------------------------------------------------------- simple merge

    @Test
    fun `a record created only here is pushed as a create`() {
        val result = plan(local = listOf(local("t1", mapOf("a" to "1"))))

        val item = result.item("t1")
        assertThat(item.verb).isEqualTo(SyncVerb.PUSH)
        assertThat(item.localFields).isEqualTo(mapOf("a" to "1"))
        assertThat(item.precondition).isEqualTo(PutPrecondition.MustNotExist)
    }

    @Test
    fun `a record known only to the peer is pulled`() {
        val result = plan(remote = listOf(remote("t1", mapOf("a" to "1"), etag = "e1")))

        val item = result.item("t1")
        assertThat(item.verb).isEqualTo(SyncVerb.PULL)
        assertThat(item.remoteFields).isEqualTo(mapOf("a" to "1"))
        assertThat(item.remoteEtag).isEqualTo("e1")
    }

    @Test
    fun `a local edit with an unchanged peer is pushed under If-Match`() {
        val result = plan(
            local = listOf(local("t1", mapOf("a" to "2"))),
            remote = listOf(remote("t1", mapOf("a" to "1"), etag = "e2")),
            bases = listOf(base("t1", mapOf("a" to "1"), etag = "e1"))
        )

        val item = result.item("t1")
        assertThat(item.verb).isEqualTo(SyncVerb.PUSH)
        // The precondition points at the peer's *current* etag, not the base's.
        assertThat(item.precondition).isEqualTo(PutPrecondition.MustMatch("e2"))
    }

    @Test
    fun `a peer edit with an untouched local side is pulled`() {
        val result = plan(
            local = listOf(local("t1", mapOf("a" to "1"))),
            remote = listOf(remote("t1", mapOf("a" to "2"), etag = "e2")),
            bases = listOf(base("t1", mapOf("a" to "1"), etag = "e1"))
        )

        assertThat(result.item("t1").verb).isEqualTo(SyncVerb.PULL)
        assertThat(result.item("t1").remoteFields).isEqualTo(mapOf("a" to "2"))
        assertThat(result.reviews).isEmpty()
    }

    @Test
    fun `the same edit on both sides is not a conflict`() {
        val result = plan(
            local = listOf(local("t1", mapOf("a" to "2"))),
            remote = listOf(remote("t1", mapOf("a" to "2"), etag = "e2")),
            bases = listOf(base("t1", mapOf("a" to "1"), etag = "e1"))
        )

        assertThat(result.item("t1").verb).isEqualTo(SyncVerb.NONE)
        assertThat(result.reviews).isEmpty()
    }

    @Test
    fun `different edits on both sides become a review and nothing is written`() {
        val result = plan(
            local = listOf(local("t1", mapOf("a" to "2"))),
            remote = listOf(remote("t1", mapOf("a" to "3"), etag = "e2")),
            bases = listOf(base("t1", mapOf("a" to "1"), etag = "e1"))
        )

        assertThat(result.item("t1").verb).isEqualTo(SyncVerb.NONE)
        assertThat(result.item("t1").decision).isInstanceOf(MergeDecision.Conflict::class.java)
        assertThat(result.reviewsFor("t1")).hasSize(1)
    }

    @Test
    fun `a create on both sides with different content is a conflict`() {
        val result = plan(
            local = listOf(local("t1", mapOf("a" to "1"))),
            remote = listOf(remote("t1", mapOf("a" to "2"), etag = "e1"))
        )

        assertThat(result.item("t1").decision).isInstanceOf(MergeDecision.Conflict::class.java)
        assertThat(result.reviewsFor("t1")).hasSize(1)
    }

    @Test
    fun `an unreadable base degrades to no common ancestor, not to an empty one`() {
        val garbage = SyncBase(tx, "t1", "not-a-payload-line", 0L, "e1", null)
        val result = plan(
            local = listOf(local("t1", mapOf("a" to "1"))),
            remote = listOf(remote("t1", mapOf("a" to "2"), etag = "e2")),
            bases = listOf(garbage)
        )

        // Without a usable ancestor the two sides look like "created on both sides
        // differently" — a review, never a silent adopt-and-overwrite.
        assertThat(result.item("t1").decision).isInstanceOf(MergeDecision.Conflict::class.java)
    }

    // ------------------------------------------------------------------ deletions

    @Test
    fun `deleting here and untouched there deletes it there`() {
        // The peer still has the (unchanged) record, so this round removes the peer's copy.
        // No tombstone is needed: the remote delete *is* the record's absence, and a record
        // that is still present on the peer has not reached confirmed absence yet.
        val result = plan(
            local = emptyList(),
            remote = listOf(remote("t1", mapOf("a" to "1"), etag = "e1")),
            bases = listOf(base("t1", mapOf("a" to "1"), etag = "e1"))
        )

        val item = result.item("t1")
        assertThat(item.verb).isEqualTo(SyncVerb.DELETE_REMOTE)
        assertThat(item.precondition).isEqualTo(PutPrecondition.MustMatch("e1"))
        assertThat(result.tombstones).isEmpty()
    }

    @Test
    fun `the last record deleted here still travels as a tombstone`() {
        // The empty-listing gate is lifted by our own delete, so this is not abandoned.
        val result = plan(
            local = emptyList(),
            remote = emptyList(),
            bases = listOf(base("t1", mapOf("a" to "1"), etag = "e1"))
        )

        assertThat(result.abandoned).isFalse()
        assertThat(result.tombstones).contains(id("t1"))
        assertThat(result.items).isEmpty()
    }

    @Test
    fun `a peer absence seen once is watched, never deleted`() {
        val result = plan(
            local = listOf(local("t1", mapOf("a" to "1")), local("t2", mapOf("a" to "1"))),
            remote = listOf(remote("t2", mapOf("a" to "1"), etag = "e2")),
            bases = listOf(
                base("t1", mapOf("a" to "1"), etag = "e1"),
                base("t2", mapOf("a" to "1"), etag = "e1")
            ),
            now = 555L
        )

        assertThat(result.abandoned).isFalse()
        assertThat(result.tombstones).isEmpty()
        assertThat(result.pending).containsEntry(id("t1"), 555L)
        assertThat(result.hasItem("t1")).isFalse()          // nothing is done to it
    }

    @Test
    fun `a peer absence seen twice is promoted to a tombstone and the local copy is deleted`() {
        val result = plan(
            local = listOf(local("t1", mapOf("a" to "1")), local("t2", mapOf("a" to "1"))),
            remote = listOf(remote("t2", mapOf("a" to "1"), etag = "e2")),
            bases = listOf(
                base("t1", mapOf("a" to "1"), etag = "e1", pendingSince = 100L),
                base("t2", mapOf("a" to "1"), etag = "e1")
            ),
            now = 555L
        )

        assertThat(result.tombstones).contains(id("t1"))
        assertThat(result.item("t1").verb).isEqualTo(SyncVerb.DELETE_LOCAL)
        assertThat(result.pending).doesNotContainKey(id("t1"))
    }

    @Test
    fun `a peer absence with a local edit keeps the edit and does not tombstone`() {
        val result = plan(
            local = listOf(local("t1", mapOf("a" to "2")), local("t2", mapOf("a" to "1"))),
            remote = listOf(remote("t2", mapOf("a" to "1"), etag = "e2")),
            bases = listOf(
                base("t1", mapOf("a" to "1"), etag = "e1", pendingSince = 100L),
                base("t2", mapOf("a" to "1"), etag = "e1")
            )
        )

        assertThat(result.tombstones).isEmpty()
        val item = result.item("t1")
        assertThat(item.verb).isEqualTo(SyncVerb.PUSH)
        assertThat(item.precondition).isEqualTo(PutPrecondition.MustNotExist)
        assertThat(result.reviewsFor("t1")).isNotEmpty()
    }

    @Test
    fun `a peer edit against a local delete adopts the edit and does not tombstone`() {
        val result = plan(
            local = listOf(local("t2", mapOf("a" to "1"))),
            remote = listOf(
                remote("t1", mapOf("a" to "2"), etag = "e2"),
                remote("t2", mapOf("a" to "1"), etag = "e2")
            ),
            bases = listOf(
                base("t1", mapOf("a" to "1"), etag = "e1"),
                base("t2", mapOf("a" to "1"), etag = "e1")
            )
        )

        assertThat(result.tombstones).isEmpty()
        assertThat(result.item("t1").verb).isEqualTo(SyncVerb.PULL)
        assertThat(result.reviewsFor("t1")).isNotEmpty()
    }

    @Test
    fun `an unreadable peer record is left alone and never deleted`() {
        val result = plan(
            local = listOf(local("t1", mapOf("a" to "1"))),
            remote = listOf(remoteUnreadable("t1", etag = "e1")),
            bases = listOf(base("t1", mapOf("a" to "1"), etag = "e1"))
        )

        assertThat(result.item("t1").verb).isEqualTo(SyncVerb.NONE)
        assertThat(result.tombstones).isEmpty()
        assertThat(result.reviewsFor("t1")).isNotEmpty()
    }

    // ------------------------------------------------------------------- pending

    @Test
    fun `a first absence is stamped, and a second one promotes rather than re-stamps`() {
        // The two-round convergence, fed back the way the executor feeds it: the marker a
        // round produces is what the next round reads as `pendingSince`.
        val roundOne = plan(
            local = listOf(local("t1", mapOf("a" to "1")), local("t2", mapOf("a" to "1"))),
            remote = listOf(remote("t2", mapOf("a" to "1"), etag = "e2")),
            bases = listOf(
                base("t1", mapOf("a" to "1"), etag = "e1"),
                base("t2", mapOf("a" to "1"), etag = "e1")
            ),
            now = 100L
        )
        assertThat(roundOne.pending).containsEntry(id("t1"), 100L)
        assertThat(roundOne.tombstones).isEmpty()

        val roundTwo = plan(
            local = listOf(local("t1", mapOf("a" to "1")), local("t2", mapOf("a" to "1"))),
            remote = listOf(remote("t2", mapOf("a" to "1"), etag = "e2")),
            bases = listOf(
                base("t1", mapOf("a" to "1"), etag = "e1", pendingSince = roundOne.pending.getValue(id("t1"))),
                base("t2", mapOf("a" to "1"), etag = "e1")
            ),
            now = 200L
        )
        assertThat(roundTwo.tombstones).contains(id("t1"))
        assertThat(roundTwo.pending).isEmpty()
    }

    @Test
    fun `a record that reappears stops being watched`() {
        val result = plan(
            local = listOf(local("t1", mapOf("a" to "1")), local("t2", mapOf("a" to "1"))),
            remote = listOf(
                remote("t1", mapOf("a" to "1"), etag = "e1"),
                remote("t2", mapOf("a" to "1"), etag = "e2")
            ),
            bases = listOf(
                base("t1", mapOf("a" to "1"), etag = "e1", pendingSince = 100L),
                base("t2", mapOf("a" to "1"), etag = "e1")
            )
        )

        assertThat(result.pending).isEmpty()
        assertThat(result.tombstones).isEmpty()
    }

    // -------------------------------------------------------------- preconditions

    @Test
    fun `the chosen precondition turns into the right conditional header`() {
        val create = plan(local = listOf(local("t1", mapOf("a" to "1")))).item("t1")
        assertThat(WebDavActions.putHeaders(create.precondition))
            .isEqualTo(mapOf("If-None-Match" to "*"))

        val update = plan(
            local = listOf(local("t1", mapOf("a" to "2"))),
            remote = listOf(remote("t1", mapOf("a" to "1"), etag = "e2")),
            bases = listOf(base("t1", mapOf("a" to "1"), etag = "e1"))
        ).item("t1")
        assertThat(WebDavActions.putHeaders(update.precondition))
            .isEqualTo(mapOf("If-Match" to "\"e2\""))
    }

    @Test
    fun `a push of an existing record with no known etag writes without a precondition`() {
        val result = plan(
            local = listOf(local("t1", mapOf("a" to "2"))),
            remote = listOf(remote("t1", mapOf("a" to "1"), etag = null)),
            bases = listOf(base("t1", mapOf("a" to "1"), etag = null))
        )
        assertThat(result.item("t1").precondition).isEqualTo(PutPrecondition.None)
    }

    private fun SyncPlan.itemOrNull(key: String) = items.firstOrNull { it.identity == id(key) }

    @Test
    fun `a review carries the identity it belongs to`() {
        val result = plan(
            local = listOf(local("t1", mapOf("a" to "1"))),
            remote = listOf(remote("t1", mapOf("a" to "2"), etag = "e1"))
        )
        val review: ConflictReview = result.reviewsFor("t1").first()
        assertThat(review.identity).isEqualTo(id("t1"))
        assertThat(result.itemOrNull("t1")).isNotNull()
    }

    // ---------------------------------------------------------- the user's answer

    /** A genuine two-sided conflict: both sides edited the same field away from the base. */
    private fun conflictedPlan(resolutions: Map<SyncIdentity, ResolveSide> = emptyMap()) = plan(
        local = listOf(local("t1", mapOf("a" to "2"))),
        remote = listOf(remote("t1", mapOf("a" to "3"), etag = "e2")),
        bases = listOf(base("t1", mapOf("a" to "1"), etag = "e1")),
        resolutions = resolutions
    )

    @Test
    fun `with no answer a two-sided conflict stays parked`() {
        val result = conflictedPlan()

        assertThat(result.item("t1").verb).isEqualTo(SyncVerb.NONE)
        assertThat(result.reviewsFor("t1")).hasSize(1)
        assertThat(result.resolutionsApplied).isEmpty()
        assertThat(result.resolutionsDropped).isEmpty()
    }

    @Test
    fun `keeping this device's version pushes it and does not park the record again`() {
        val result = conflictedPlan(mapOf(id("t1") to ResolveSide.LOCAL))

        val item = result.item("t1")
        assertThat(item.verb).isEqualTo(SyncVerb.PUSH)
        assertThat(item.localFields).isEqualTo(mapOf("a" to "2"))
        // The precondition points at the peer's *current* etag: forcing a push must still prove
        // the peer has not moved again, or a blind write would clobber a change nobody saw.
        assertThat(item.precondition).isEqualTo(PutPrecondition.MustMatch("e2"))
        assertThat(result.reviewsFor("t1")).isEmpty()
        assertThat(result.resolutionsApplied).containsExactly(id("t1"))
        assertThat(result.resolutionsDropped).isEmpty()
    }

    @Test
    fun `taking the peer's version pulls it and does not park the record again`() {
        val result = conflictedPlan(mapOf(id("t1") to ResolveSide.REMOTE))

        val item = result.item("t1")
        assertThat(item.verb).isEqualTo(SyncVerb.PULL)
        assertThat(item.remoteFields).isEqualTo(mapOf("a" to "3"))
        assertThat(item.remoteEtag).isEqualTo("e2")
        assertThat(result.reviewsFor("t1")).isEmpty()
        assertThat(result.resolutionsApplied).containsExactly(id("t1"))
    }

    @Test
    fun `a forced push against a peer with no etag writes without a precondition`() {
        // The peer has a copy but told us no entity tag, so there is nothing to prove the write
        // against. `None` is the only honest choice; inventing an `If-Match` would fail forever.
        val result = plan(
            local = listOf(local("t1", mapOf("a" to "2"))),
            remote = listOf(remote("t1", mapOf("a" to "3"), etag = null)),
            bases = listOf(base("t1", mapOf("a" to "1"), etag = null)),
            resolutions = mapOf(id("t1") to ResolveSide.LOCAL)
        )

        assertThat(result.item("t1").verb).isEqualTo(SyncVerb.PUSH)
        assertThat(result.item("t1").precondition).isEqualTo(PutPrecondition.None)
    }

    @Test
    fun `an answer that is no longer needed is dropped rather than left to resurface`() {
        // Only this device changed, so there is nothing to resolve — the record is pushed by the
        // ordinary rule. The stored answer must go, or it would surface again on some later
        // round and force a write the user never asked for *then*.
        val result = plan(
            local = listOf(local("t1", mapOf("a" to "2"))),
            remote = listOf(remote("t1", mapOf("a" to "1"), etag = "e2")),
            bases = listOf(base("t1", mapOf("a" to "1"), etag = "e1")),
            resolutions = mapOf(id("t1") to ResolveSide.REMOTE)
        )

        assertThat(result.item("t1").verb).isEqualTo(SyncVerb.PUSH)
        assertThat(result.resolutionsApplied).isEmpty()
        assertThat(result.resolutionsDropped).containsExactly(id("t1"))
        assertThat(result.reviews).isEmpty()
    }

    @Test
    fun `an answer for a record this round does not carry survives untouched`() {
        // A round that carries only accounts must not quietly delete a transaction's answer.
        val result = plan(
            local = listOf(local("t1", mapOf("a" to "1"))),
            remote = listOf(remote("t1", mapOf("a" to "1"), etag = "e1")),
            bases = listOf(base("t1", mapOf("a" to "1"), etag = "e1")),
            resolutions = mapOf(id("unseen") to ResolveSide.LOCAL)
        )

        assertThat(result.resolutionsApplied).isEmpty()
        assertThat(result.resolutionsDropped).isEmpty()
    }

    @Test
    fun `an abandoned round consumes no answer at all`() {
        // Nothing was decided, so nothing may be spent — including the user's choices.
        val result = plan(
            local = listOf(local("t1", mapOf("a" to "2"))),
            remote = emptyList(),
            bases = listOf(base("t1", mapOf("a" to "1"), etag = "e1")),
            complete = false,
            resolutions = mapOf(id("t1") to ResolveSide.LOCAL)
        )

        assertThat(result.abandoned).isTrue()
        assertThat(result.resolutionsApplied).isEmpty()
        assertThat(result.resolutionsDropped).isEmpty()
    }

    // ------------------------------------------- answers over deletions and unreadable peers

    @Test
    fun `keeping this device's version where this device deleted the record deletes it on the peer`() {
        // The whole reason the answer is a *side* and not a verb: here "keep this device's
        // version" means the local **absence** wins, i.e. propagate the deletion. Reading it as
        // "push" would resurrect a record the user deliberately deleted.
        val result = plan(
            local = listOf(local("t2", mapOf("a" to "1"))),
            remote = listOf(
                remote("t1", mapOf("a" to "2"), etag = "e2"),
                remote("t2", mapOf("a" to "1"), etag = "e2")
            ),
            bases = listOf(
                base("t1", mapOf("a" to "1"), etag = "e1"),
                base("t2", mapOf("a" to "1"), etag = "e1")
            ),
            resolutions = mapOf(id("t1") to ResolveSide.LOCAL)
        )

        val item = result.item("t1")
        assertThat(item.verb).isEqualTo(SyncVerb.DELETE_REMOTE)
        assertThat(item.precondition).isEqualTo(PutPrecondition.MustMatch("e2"))
        assertThat(result.reviewsFor("t1")).isEmpty()
        assertThat(result.resolutionsApplied).containsExactly(id("t1"))
    }

    @Test
    fun `taking the peer's version where this device deleted the record is the rule, and is spent`() {
        // This answer agrees with the default rule (an edit beats a deletion), and that is the
        // point: agreeing still counts as answered, so the override is consumed rather than
        // left to force the same pull again next round.
        val result = plan(
            local = listOf(local("t2", mapOf("a" to "1"))),
            remote = listOf(
                remote("t1", mapOf("a" to "2"), etag = "e2"),
                remote("t2", mapOf("a" to "1"), etag = "e2")
            ),
            bases = listOf(
                base("t1", mapOf("a" to "1"), etag = "e1"),
                base("t2", mapOf("a" to "1"), etag = "e1")
            ),
            resolutions = mapOf(id("t1") to ResolveSide.REMOTE)
        )

        assertThat(result.item("t1").verb).isEqualTo(SyncVerb.PULL)
        assertThat(result.resolutionsApplied).containsExactly(id("t1"))
    }

    @Test
    fun `keeping this device's version over an unreadable peer pushes it out`() {
        val result = plan(
            local = listOf(local("t1", mapOf("a" to "1"))),
            remote = listOf(remoteUnreadable("t1", etag = "e1")),
            bases = listOf(base("t1", mapOf("a" to "1"), etag = "e1")),
            resolutions = mapOf(id("t1") to ResolveSide.LOCAL)
        )

        val item = result.item("t1")
        assertThat(item.verb).isEqualTo(SyncVerb.PUSH)
        assertThat(item.precondition).isEqualTo(PutPrecondition.MustMatch("e1"))
        assertThat(result.reviewsFor("t1")).isEmpty()
        assertThat(result.resolutionsApplied).containsExactly(id("t1"))
    }

    @Test
    fun `taking an unreadable peer's version cannot be honoured, so the record stays parked`() {
        // There is no content to adopt. The record must remain parked **and** the answer must
        // survive, so a later round where the peer is readable can finally honour it — rather
        // than the choice being dropped on the floor and the row silently coming back forever.
        val result = plan(
            local = listOf(local("t1", mapOf("a" to "1"))),
            remote = listOf(remoteUnreadable("t1", etag = "e1")),
            bases = listOf(base("t1", mapOf("a" to "1"), etag = "e1")),
            resolutions = mapOf(id("t1") to ResolveSide.REMOTE)
        )

        assertThat(result.item("t1").verb).isEqualTo(SyncVerb.NONE)
        assertThat(result.reviewsFor("t1")).hasSize(1)
        assertThat(result.resolutionsApplied).isEmpty()
        assertThat(result.resolutionsDropped).isEmpty()
    }

    @Test
    fun `keeping this device's version where the peer confirmed a deletion recreates it as a create`() {
        // The peer is gone for good (a second consecutive absence promotes it) and the user says
        // their own version wins. The write must therefore be a **create** (`If-None-Match: *`):
        // an `If-Match` against an etag the peer has already dropped would fail the same way every
        // round — an answer that could never land.
        val result = plan(
            local = listOf(local("t1", mapOf("a" to "2")), local("t2", mapOf("a" to "1"))),
            remote = listOf(remote("t2", mapOf("a" to "1"), etag = "e2")),
            bases = listOf(
                base("t1", mapOf("a" to "1"), etag = "e1", pendingSince = 100L),
                base("t2", mapOf("a" to "1"), etag = "e1")
            ),
            resolutions = mapOf(id("t1") to ResolveSide.LOCAL)
        )

        val item = result.item("t1")
        assertThat(item.verb).isEqualTo(SyncVerb.PUSH)
        assertThat(item.localFields).isEqualTo(mapOf("a" to "2"))
        assertThat(item.precondition).isEqualTo(PutPrecondition.MustNotExist)
        assertThat(result.reviewsFor("t1")).isEmpty()
        assertThat(result.resolutionsApplied).containsExactly(id("t1"))
    }

    @Test
    fun `taking the peer's version of a confirmed deletion accepts the deletion here`() {
        // "The peer's version" of a record the peer deleted **is** its absence, so honouring the
        // answer means deleting the copy here — and recording that evidence, like any other
        // accepted deletion, so the record is not simply gone without a trace.
        val result = plan(
            local = listOf(local("t1", mapOf("a" to "2")), local("t2", mapOf("a" to "1"))),
            remote = listOf(remote("t2", mapOf("a" to "1"), etag = "e2")),
            bases = listOf(
                base("t1", mapOf("a" to "1"), etag = "e1", pendingSince = 100L),
                base("t2", mapOf("a" to "1"), etag = "e1")
            ),
            resolutions = mapOf(id("t1") to ResolveSide.REMOTE)
        )

        assertThat(result.item("t1").verb).isEqualTo(SyncVerb.DELETE_LOCAL)
        assertThat(result.tombstones).contains(id("t1"))
        assertThat(result.reviewsFor("t1")).isEmpty()
        assertThat(result.resolutionsApplied).containsExactly(id("t1"))
    }

    @Test
    fun `an answer for a record that is only under observation is kept`() {
        // A first-seen absence is merely *watched* — the round decided nothing, so there is
        // nothing for the answer to be moot about. Dropping it here would throw the user's choice
        // away one round before the round that needs it (the second absence parks exactly this
        // record).
        val result = plan(
            local = listOf(local("t1", mapOf("a" to "2")), local("t2", mapOf("a" to "1"))),
            remote = listOf(remote("t2", mapOf("a" to "1"), etag = "e2")),
            bases = listOf(
                base("t1", mapOf("a" to "1"), etag = "e1"),
                base("t2", mapOf("a" to "1"), etag = "e1")
            ),
            resolutions = mapOf(id("t1") to ResolveSide.LOCAL)
        )

        assertThat(result.hasItem("t1")).isFalse()
        assertThat(result.resolutionsApplied).isEmpty()
        assertThat(result.resolutionsDropped).isEmpty()
    }
}
