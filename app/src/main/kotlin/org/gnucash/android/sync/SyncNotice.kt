/*
 * Copyright (c) 2026 GnuCash Pocket contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.gnucash.android.sync

import org.gnucash.android.sync.webdav.WebDavConnection

/**
 * What one bidirectional round amounts to, from the user's point of view.
 *
 * ### Why the counts are not the answer
 *
 * [SyncRoundResult] carries six counts and they are all *diagnostics*. The user cannot act on
 * "uploaded 3, downloaded 2, failed 1" — they can only act on **which of these eight situations
 * they are in**, because each one has a different next move:
 *
 *  - [NOTHING_TO_DO] ⇒ nothing; the two sides already agree (this is the steady state, and it
 *    must not read like an error);
 *  - [SYNCED] ⇒ nothing; it worked;
 *  - [DEFERRED_SOMETHING] ⇒ nothing **this** device can do. A pull was declined because the
 *    local store cannot represent the record yet (a split whose account is not in the book).
 *    It will be retried next round; a run of non-zero values means the drain is stuck and the
 *    peer's data is not arriving, which is invisible if it is folded into "synced";
 *  - [CONFLICTS_TO_REVIEW] ⇒ open the conflict centre. Nothing was lost — the losing side is
 *    parked, not overwritten — but the user has to choose;
 *  - [SOMETHING_FAILED] ⇒ retry, and if it persists look at the server. A record that should
 *    have moved did not, and the round kept the base consistent so the next attempt is honest;
 *  - [RESTART_NEEDED] ⇒ the peer moved under us (`412` on a conditional write). The round
 *    stopped where it was; the committed part stands and the next round re-lists. Telling the
 *    user this is "failed" would invite a manual retry that is actually correct — it is, so
 *    say what happened;
 *  - [WRONG_FOLDER] ⇒ **check the address**. The listing came back empty while this device
 *    knows records exist. That is the "you pointed at somebody else's folder (or a fresh
 *    subfolder)" shape, and it is the one outcome where the user's next move is on the
 *    settings page rather than on the server — so it may never be reported as a network error;
 *  - [LISTING_INCOMPLETE] ⇒ the peer could not be listed completely (partial `207`, a
 *    half-answered recursion). Nothing was written. Retry; the address is fine.
 *
 * The ordering of [SyncNotices.of] is the answer to "several of these are true at once".
 * Severity, not convenience: the situation that most needs the user's attention wins.
 */
enum class SyncNotice {
    NOTHING_TO_DO,
    SYNCED,
    DEFERRED_SOMETHING,
    CONFLICTS_TO_REVIEW,
    SOMETHING_FAILED,
    RESTART_NEEDED,
    WRONG_FOLDER,
    LISTING_INCOMPLETE;

    /**
     * Whether the six counts are worth putting on screen next to this notice.
     *
     * `false` only for the steady state: "nothing to do — uploaded 0, downloaded 0, deleted 0,
     * failed 0" is six numbers that say exactly what the sentence already said, and a settings
     * page that shows them every time teaches the user to stop reading the line. Every other
     * notice *is* about numbers the user may want to see, including the ones where the round
     * moved nothing because it failed or deferred.
     */
    val deservesCounts: Boolean
        get() = this != NOTHING_TO_DO
}

/**
 * Why a round refused to decide anything.
 *
 * Two shapes, deliberately not one: an incomplete listing means "try again", an empty listing
 * while records are known means "you are probably in the wrong folder". Collapsing them into a
 * single "the round was abandoned" sends half the users to the retry button and the other half
 * to a folder they will never find.
 */
enum class SyncAbandon {

    /**
     * The peer could not be listed completely. A partial view is not evidence of a deletion, so
     * the round writes nothing at all — not even a `pending` marker.
     */
    LISTING_INCOMPLETE,

    /**
     * The listing came back **empty** while the base knows records exist. Almost always a wrong
     * or freshly-created folder; occasionally "everything was deleted on the peer", which the
     * next round will settle once something is present. Either way, refusing is free.
     */
    EMPTY_REMOTE
}

/**
 * The result of asking for one round: the fields were unusable, the round broke, or it ran.
 *
 * [NotUsable] carries no [SyncRoundResult] by construction — nothing was attempted, so there is
 * no "how many records moved" to report, and every count would be a lie (see
 * [SyncRunner.runOnce]).
 */
sealed interface SyncOutcome {

    /**
     * The typed fields are unusable, so **no request was made at all**.
     *
     * Kept apart from a failed round because nothing was attempted: reporting this as a network
     * failure would send the user to debug a server that was never contacted.
     */
    data class NotUsable(
        val problem: WebDavConnection.Problem,
        val detail: String
    ) : SyncOutcome

    /**
     * The round started and threw before it could produce a [SyncRoundResult].
     *
     * Its own branch rather than "a round with `failed = 1`": that result shape would claim the
     * bookkeeping ran (base/tombstone/pending all written) when in fact the round stopped
     * somewhere unpredictable. What the user should take from it — "try again, then look at the
     * log" — is the same, but a caller that branches on the *shape* must not be misled.
     */
    data class Failed(val detail: String?) : SyncOutcome

    /** The round ran. [notice] is what the user should be told; [result] is the diagnostics. */
    data class Ran(
        val notice: SyncNotice,
        val result: SyncRoundResult
    ) : SyncOutcome
}

/**
 * Maps a finished round onto the one situation the user is in.
 *
 * The `when` below is ordered by severity — that ordering **is** the specification, and each
 * step is asserted with a result that has the earlier conditions deliberately false, so a
 * reordered branch turns a test red rather than silently changing which message wins.
 */
object SyncNotices {

    fun of(result: SyncRoundResult): SyncNotice = when {
        // Nothing was written, so nothing else that follows can be true. Both shapes are
        // refusals to act, and their reasons point at different fixes.
        result.abandoned -> abandonNotice(result.abandonReason)

        // The peer moved under us mid-round: part of the plan was applied and the rest was not
        // attempted. The next round finishes the job, so this outranks "some record failed".
        result.restartNeeded -> SyncNotice.RESTART_NEEDED

        // A record that should have moved did not. More urgent than a conflict: a conflict is a
        // question, this is data that is not where it was supposed to go.
        result.failed > 0 -> SyncNotice.SOMETHING_FAILED

        // The round succeeded but parked an edit for the user to choose between.
        result.reviews.isNotEmpty() -> SyncNotice.CONFLICTS_TO_REVIEW

        // Pulls this device declined to apply: retried next round, invisible if folded into
        // "synced".
        result.deferred > 0 -> SyncNotice.DEFERRED_SOMETHING

        // The steady state. It has to be its own notice precisely because it is not an event.
        !result.movedAnything -> SyncNotice.NOTHING_TO_DO

        else -> SyncNotice.SYNCED
    }

    /**
     * An abandoned round with no recorded reason is read as the incomplete listing: it is the
     * one that "try again" fixes, which is the safer of the two advices to give by default.
     */
    private fun abandonNotice(reason: SyncAbandon?): SyncNotice = when (reason) {
        SyncAbandon.EMPTY_REMOTE -> SyncNotice.WRONG_FOLDER
        SyncAbandon.LISTING_INCOMPLETE, null -> SyncNotice.LISTING_INCOMPLETE
    }
}
