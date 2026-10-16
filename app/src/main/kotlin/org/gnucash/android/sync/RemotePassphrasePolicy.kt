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
 * What the user typed into the remote-backup passphrase dialog.
 *
 * The two fields are kept **as typed** — no trimming — because a passphrase's leading and
 * trailing spaces are part of it, and silently editing a secret is how "the same passphrase
 * works on my other phone" happens. (Same rule as [org.gnucash.android.sync.webdav.WebDavConnection].)
 *
 * @param irreversibleAcknowledged the user ticked "I understand this cannot be undone".
 *        Required only when a passphrase is actually being set — see [RemotePassphrasePolicy].
 */
data class PassphraseEntry(
    val passphrase: String,
    val confirmation: String,
    val irreversibleAcknowledged: Boolean = false
)

/** The outcome of reading the passphrase dialog: set one, set none, or refuse to proceed. */
sealed interface PassphraseDecision {

    /**
     * No passphrase: the backup goes up **in the clear**, and stays openable by desktop
     * GnuCash. This is the one branch that needs no acknowledgement, because backing out of
     * it is possible: a later upload can replace the slot with an encrypted one.
     */
    data object None : PassphraseDecision

    /**
     * Encrypt with [passphrase].
     *
     * Held as a `CharArray` rather than a `String` so the caller can zero it after the
     * upload; `String` would keep the secret alive until the next GC and in every heap dump.
     */
    class Use(val passphrase: CharArray) : PassphraseDecision {
        /** Redacted: a decision found in a log must not leak the passphrase. */
        override fun toString(): String = "Use(passphrase=[redacted])"
    }

    /** The dialog cannot be accepted until [problem] is fixed. */
    data class Invalid(val problem: Problem) : PassphraseDecision

    /** Everything that can be wrong with a passphrase entry, as a value the UI can switch on. */
    enum class Problem {
        /**
         * The confirmation field has something in it but the passphrase field does not.
         *
         * Kept apart from [CONFIRMATION_MISMATCH] because silently ignoring the second field
         * would upload the book in the clear while the user believes they set a passphrase —
         * a mismatch the user would only discover by opening the remote file.
         */
        CONFIRMATION_WITHOUT_PASSPHRASE,

        /** The two fields disagree — almost always a typo in one of them. */
        CONFIRMATION_MISMATCH,

        /**
         * A passphrase was typed but the irreversible warning was not acknowledged.
         *
         * A forgotten passphrase is **not** recoverable: the remote file is the only copy and
         * nothing can open it without the passphrase. Requiring the tick makes that a decision
         * the user made rather than a checkbox they scrolled past.
         */
        ACKNOWLEDGEMENT_MISSING
    }
}

/**
 * Turns a [PassphraseEntry] into a decision, with **no** Android dependency so the rules can
 * be asserted one by one (see `RemotePassphrasePolicyTest`).
 *
 * Four rules, in this order — the order matters, because each one is a distinct thing the
 * user has to be told:
 *
 *  1. both fields empty ⇒ [PassphraseDecision.None]. "I do not want encryption" is a
 *     legitimate answer, and it is the only one that comes back without an acknowledgement.
 *     Note that **whitespace is not empty**: a passphrase of `"   "` is a real (if unwise)
 *     passphrase, and treating it as "none" would encrypt nothing while reporting otherwise.
 *  2. passphrase empty, confirmation filled ⇒
 *     [PassphraseDecision.Problem.CONFIRMATION_WITHOUT_PASSPHRASE];
 *  3. the two disagree ⇒ [PassphraseDecision.Problem.CONFIRMATION_MISMATCH];
 *  4. agreed but unacknowledged ⇒ [PassphraseDecision.Problem.ACKNOWLEDGEMENT_MISSING].
 */
object RemotePassphrasePolicy {

    fun decide(entry: PassphraseEntry): PassphraseDecision {
        val passphrase = entry.passphrase
        val confirmation = entry.confirmation

        if (passphrase.isEmpty() && confirmation.isEmpty()) return PassphraseDecision.None
        if (passphrase.isEmpty()) {
            return PassphraseDecision.Invalid(
                PassphraseDecision.Problem.CONFIRMATION_WITHOUT_PASSPHRASE
            )
        }
        if (passphrase != confirmation) {
            return PassphraseDecision.Invalid(PassphraseDecision.Problem.CONFIRMATION_MISMATCH)
        }
        if (!entry.irreversibleAcknowledged) {
            return PassphraseDecision.Invalid(PassphraseDecision.Problem.ACKNOWLEDGEMENT_MISSING)
        }
        return PassphraseDecision.Use(passphrase.toCharArray())
    }
}
