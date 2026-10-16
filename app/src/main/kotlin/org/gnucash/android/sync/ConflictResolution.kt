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

/**
 * The side of a parked record the user told us to keep (feature ② v3).
 *
 * The conflict centre is a **mirror**: it shows what the round parked and why. This is
 * the one thing it lets the user *do* — say which side wins — and the only way for that
 * answer to mean anything is for the **next** round to read it. A round cannot apply it
 * on the spot: the round that parked the conflict has, by definition, already decided to
 * touch nothing, and the peer may have moved again by the time the user looks at the
 * screen. So the choice is persisted as an override, keyed by [SyncIdentity], and the
 * planner consults it on the next round ([SyncRoundPlanner]) — see [SyncPlan.resolutionsApplied].
 *
 * Why a side and not a verb: a verb ("push"/"pull") would be wrong half the time, because
 * for a record deleted on one side "keep local" means *delete it on the peer* and "keep
 * remote" means *delete it here*. The user chooses whose version of the record survives;
 * whether that is a write or a deletion is the planner's problem, not the user's.
 */
enum class ResolveSide(val id: String) {
    /** Keep this device's version (write it over the peer, or propagate our deletion). */
    LOCAL("local"),

    /** Keep the peer's version (adopt it here, or accept its deletion). */
    REMOTE("remote");

    companion object {
        private val byId = entries.associateBy { it.id }

        /**
         * Resolves a persisted [id], or `null` when the row is not one we wrote.
         *
         * `null` rather than a default: an override we cannot read must be **ignored**, not
         * guessed at. Guessing would silently force a side the user never chose, which is
         * exactly the class of "the button did something else" bug this feature exists to
         * avoid.
         */
        fun fromId(id: String?): ResolveSide? = id?.let { byId[it] }
    }
}

/**
 * One stored override: "for this record, the next round keeps [side]".
 *
 * Kept as a plain pair rather than folded into [SyncBase] because a resolution can exist
 * for a record that has **no base row**: "created on both devices with different content"
 * is a conflict with no common ancestor ([SyncRoundPlannerTest] pins that), so tying the
 * override to the base row would make the most common conflict unresolvable.
 */
data class StoredResolution(
    val identity: SyncIdentity,
    val side: ResolveSide
)
