package net.devemperor.dictate.companion.domain.port

import net.devemperor.dictate.companion.domain.model.InsertionOutcome

/**
 * Places a text into whatever the user is currently typing into.
 *
 * Windows-only in V1 (`JnaWin32TextInserter`); every other OS gets `NoopTextInserter`, so the
 * companion still **runs and is testable** on Linux — the whole reason this is a port (ADR-0018).
 */
interface TextInserter {

    fun insert(text: String): InsertionOutcome

    /** false → the UI shows a banner and `/v1/health` reports `canInsert = false`. */
    val available: Boolean
}
