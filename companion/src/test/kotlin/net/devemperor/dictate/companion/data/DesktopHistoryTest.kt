package net.devemperor.dictate.companion.data

import net.devemperor.dictate.companion.domain.session.SessionOrigin
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The desktop-dictation history reads (desktop-host.md §9.3). The phone-mirror `pageHistory` JOINs
 * dispatch_state and scopes to PHONE_SYNC, so a locally-dictated session can never appear there; these
 * reads are its DESKTOP_DICTATION counterpart. Pins that a completed local take surfaces (with its
 * transcript AND final output), and that the rows a user must NOT see there — phone-sync sessions,
 * in-flight takes, and REVIEW_REFINEMENT S2 takes (whose transcript belongs to the reviewed parent) —
 * are excluded.
 */
class DesktopHistoryTest {

    private val database = CompanionDatabase.inMemory()
    private val sessions = DesktopSessionRepository(database)
    private val queries = database.companionQueries

    private fun seedCompletedDesktopTake(id: String, createdAt: Long, transcript: String, output: String) {
        sessions.createDictationSession(
            id = id, createdAt = createdAt, language = "de",
            audioFilePath = null, audioFilePathsJson = "[]", durationSeconds = 0L,
        )
        sessions.insertTranscription(
            TranscriptionRecord(
                id = "t-$id", sessionId = id, version = 1, isCurrent = true, text = transcript,
                modelUsed = "whisper-1", provider = "OPENAI", promptTokens = 0, completionTokens = 0,
                durationMs = 0, createdAt = createdAt,
            )
        )
        sessions.completeWithFinalOutput(id, output)
    }

    @Test
    fun pageDesktopHistory_returnsCompletedLocalTakesNewestFirst_withTranscriptAndOutput() {
        seedCompletedDesktopTake("s-1", createdAt = 100, transcript = "hallo welt", output = "Hallo Welt.")
        seedCompletedDesktopTake("s-2", createdAt = 200, transcript = "wie gehts", output = "Wie geht's?")

        assertEquals(2L, sessions.countDesktopHistory(""))
        val page = sessions.pageDesktopHistory("", limit = 10, offset = 0)
        assertEquals(listOf("s-2", "s-1"), page.map { it.sessionId })  // newest first
        val newest = page.first()
        assertEquals("Wie geht's?", newest.finalOutputText)
        assertEquals("wie gehts", newest.transcriptText)              // raw transcript before post-processing
    }

    @Test
    fun pageDesktopHistory_filtersByFinalOutputSubstring() {
        seedCompletedDesktopTake("s-1", createdAt = 100, transcript = "hallo welt", output = "Hallo Welt.")
        seedCompletedDesktopTake("s-2", createdAt = 200, transcript = "wie gehts", output = "Wie geht's?")

        assertEquals(1L, sessions.countDesktopHistory("welt"))
        assertEquals(listOf("s-1"), sessions.pageDesktopHistory("welt", 10, 0).map { it.sessionId })
    }

    @Test
    fun pageDesktopHistory_excludesInFlightTakes() {
        // Created but never completed → status TRANSCRIBING, no final output to re-insert.
        sessions.createDictationSession(
            id = "s-open", createdAt = 100, language = null,
            audioFilePath = null, audioFilePathsJson = "[]", durationSeconds = 0L,
        )

        assertEquals(0L, sessions.countDesktopHistory(""))
        assertEquals(emptyList<String>(), sessions.pageDesktopHistory("", 10, 0).map { it.sessionId })
    }

    @Test
    fun pageDesktopHistory_excludesReviewRefinementTakes() {
        seedCompletedDesktopTake("parent", createdAt = 100, transcript = "hallo", output = "Hallo.")
        // An S2 refinement take: COMPLETED, origin REVIEW_REFINEMENT, hung off the parent — its
        // transcript belongs to the parent's conversation, not a standalone history row.
        sessions.createRefinementSession(
            id = "s2", createdAt = 150, language = "de",
            audioFilePath = null, audioFilePathsJson = "[]", durationSeconds = 0L,
            parentSessionId = "parent",
        )

        assertEquals(listOf("parent"), sessions.pageDesktopHistory("", 10, 0).map { it.sessionId })
    }

    @Test
    fun pageDesktopHistory_excludesPhoneSyncSessions() {
        seedCompletedDesktopTake("s-1", createdAt = 100, transcript = "hallo welt", output = "Hallo Welt.")
        // A phone-sync mirror row (host_origin PHONE_SYNC) must never surface in the desktop history.
        queries.upsertSyncSession(
            id = "ph-1", createdAt = 200, origin = SessionOrigin.KEYBOARD,
            finalOutputText = "phone text", insertedAt = null,
        )

        assertEquals(1L, sessions.countDesktopHistory(""))
        assertEquals(listOf("s-1"), sessions.pageDesktopHistory("", 10, 0).map { it.sessionId })
    }
}
