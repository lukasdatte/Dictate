package net.devemperor.dictate.history

import net.devemperor.dictate.database.entity.SessionEntity
import net.devemperor.dictate.database.entity.SessionStatus

/**
 * Pure row predicates for the in-keyboard history panel (Paket 3 / ADR-0014).
 *
 * Kept out of the entity so both the RecyclerView bind path and the tests can
 * reuse them without an Android dependency.
 */

/**
 * True when a session is a **pending insertion**: completed, with text, and not
 * yet inserted into a host editor.
 *
 * MUST stay byte-identical to the computed-boolean ORDER-BY key in
 * `SessionDao.pagedHistoryPanel` and to the WHERE of
 * `SessionDao.findPendingInsertion` (minus its freshness floor). A parity test
 * (`SessionRowPredicatesTest`) pins the equivalence — the SQL cannot call this
 * Kotlin, so the two representations are kept in sync by test, not by compiler.
 */
fun SessionEntity.isPendingInsertion(): Boolean =
    statusEnum == SessionStatus.COMPLETED &&
        insertedAt == null &&
        finalOutputText != null

/**
 * Cheap enable-gate for the panel's "insert" button: does this row carry any
 * text worth committing? Read straight off the entity (no DB round-trip);
 * `SessionManager.getFinalOutput` stays the authoritative source at insert time.
 * A bare recording still in progress (no output, no transcript yet) returns
 * false so its button is disabled.
 */
fun SessionEntity.hasInsertableText(): Boolean =
    !finalOutputText.isNullOrEmpty() || !inputText.isNullOrEmpty()
