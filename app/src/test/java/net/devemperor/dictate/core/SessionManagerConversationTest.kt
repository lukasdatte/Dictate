package net.devemperor.dictate.core

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import net.devemperor.dictate.ai.runner.ConversationResult
import net.devemperor.dictate.database.DictateDatabase
import net.devemperor.dictate.database.entity.MessageRole
import net.devemperor.dictate.database.entity.ResponseFormatKind
import net.devemperor.dictate.database.entity.SessionOrigin
import net.devemperor.dictate.database.entity.SessionStatus
import net.devemperor.dictate.database.entity.SessionType
import net.devemperor.dictate.database.entity.StepType
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Drives the SessionManager conversation APIs (ADR-0012) against a real
 * in-memory Room DB: turn persistence, reconstruction, continuation, and the
 * regenerate-vs-continue distinction.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SessionManagerConversationTest {

    private lateinit var db: DictateDatabase
    private lateinit var sm: SessionManager

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, DictateDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        sm = SessionManager(db)
    }

    @After
    fun tearDown() = db.close()

    private fun newSession(id: String = "s1"): String {
        sm.createSession(
            id = id, type = SessionType.RECORDING, targetApp = null, language = "en",
            audioFilePath = null, audioDurationSeconds = 1, parentId = null,
            origin = SessionOrigin.KEYBOARD, queuedPromptIds = null,
            initialStatus = SessionStatus.RECORDED
        )
        return id
    }

    private fun result(output: String, message: String? = "did it", format: ResponseFormatKind = ResponseFormatKind.JSON_SCHEMA) =
        ConversationResult(message, output, 5, 7, "gpt-test", format)

    @Test
    fun `first turn persists system + user rows and a merged step`() {
        val s = newSession()
        val stepId = sm.appendConversationTurn(
            sessionId = s, userMessageContent = "USER-MSG-0", inputText = "raw transcript",
            result = result("clean output"), provider = "OPENAI",
            previousTranscriptionId = null, durationMs = 12, systemPromptForFirstTurn = "SYS"
        )

        // step is a single CONVERSATION_TURN with the structured fields
        val chain = db.processingStepDao().getCurrentChain(s)
        assertEquals(1, chain.size)
        val step = chain[0]
        assertEquals(StepType.CONVERSATION_TURN.name, step.stepType)
        assertEquals("clean output", step.outputText)
        assertEquals("did it", step.assistantMessage)
        assertEquals(ResponseFormatKind.JSON_SCHEMA.name, step.responseFormat)
        assertEquals(0, step.chainIndex)

        // conversation rows: SYSTEM (turn -1) + USER (turn 0)
        val msgs = db.conversationMessageDao().getBySession(s)
        assertEquals(2, msgs.size)
        assertEquals(MessageRole.SYSTEM.name, msgs[0].role)
        assertEquals("SYS", msgs[0].content)
        assertEquals(MessageRole.USER.name, msgs[1].role)
        assertEquals("USER-MSG-0", msgs[1].content)
        assertEquals(0, msgs[1].turnIndex)

        assertEquals("clean output", sm.getFinalOutput(s))
        assertEquals("did it", sm.getAssistantMessage(s))
        assertTrue(stepId.isNotEmpty())
    }

    @Test
    fun `a completed uninserted turn is crash-recoverable via findPendingInsertion (ADR-0013)`() {
        // The review-held invariant: appendConversationTurn persists
        // final_output_text, and heldForReview leaves inserted_at NULL, so after
        // process death the session re-surfaces as a pending part. Without the
        // final_output_text persistence this query would miss it.
        val s = newSession()
        sm.appendConversationTurn(s, "U0", "raw", result("held output"), "OPENAI", null, 1, "SYS")
        sm.finalizeCompleted(s)

        val pending = db.sessionDao().findPendingInsertion(0L)
        assertEquals(listOf(s), pending.map { it.id })
        assertEquals("held output", pending.single().finalOutputText)

        // Once acknowledged (Insert/Discard mark inserted), it no longer surfaces.
        sm.markInserted(s, 999L)
        assertTrue(db.sessionDao().findPendingInsertion(0L).isEmpty())
    }

    @Test
    fun `loadConversation reconstructs system and one turn`() {
        val s = newSession()
        sm.appendConversationTurn(s, "U0", "raw", result("O0", "M0"), "OPENAI", null, 1, "SYS")

        val snap = sm.loadConversation(s)
        assertEquals("SYS", snap.systemContent)
        assertEquals(1, snap.turns.size)
        assertEquals("U0", snap.turns[0].userContent)
        assertEquals("O0", snap.turns[0].assistantOutput)
        assertEquals("M0", snap.turns[0].assistantMessage)
    }

    @Test
    fun `continuation appends a new turn without a second system row`() {
        val s = newSession()
        sm.appendConversationTurn(s, "U0", "raw", result("O0"), "OPENAI", null, 1, "SYS")
        sm.appendConversationTurn(s, "U1", "O0", result("O1"), "OPENAI", null, 1, "SYS")

        val chain = db.processingStepDao().getCurrentChain(s)
        assertEquals(2, chain.size)
        assertEquals(1, chain[1].chainIndex)

        val msgs = db.conversationMessageDao().getBySession(s)
        // SYSTEM + USER0 + USER1 = 3, only one SYSTEM
        assertEquals(3, msgs.size)
        assertEquals(1, msgs.count { it.role == MessageRole.SYSTEM.name })
        val userTurns = msgs.filter { it.role == MessageRole.USER.name }.map { it.turnIndex }
        assertEquals(listOf(0, 1), userTurns)

        assertEquals("O1", sm.getFinalOutput(s))
        assertEquals(2, sm.loadConversation(s).turns.size)
    }

    @Test
    fun `regenerate creates a new version at same index and leaves history unchanged`() {
        val s = newSession()
        sm.appendConversationTurn(s, "U0", "raw", result("O0-v1"), "OPENAI", null, 1, "SYS")
        val msgsBefore = db.conversationMessageDao().getBySession(s).size

        sm.regenerateConversationTurn(s, chainIndex = 0, result = result("O0-v2", "redone"), provider = "OPENAI", durationMs = 3)

        // versions at chain_index 0: v1 (non-current) + v2 (current)
        val versions = db.processingStepDao().getVersionsAtIndex(s, 0)
        assertEquals(2, versions.size)
        val current = versions.first { it.isCurrent }
        assertEquals(2, current.version)
        assertEquals("O0-v2", current.outputText)
        assertEquals("redone", current.assistantMessage)

        // conversation USER rows unchanged (no new row for a regenerate)
        assertEquals(msgsBefore, db.conversationMessageDao().getBySession(s).size)
        assertEquals("O0-v2", sm.getFinalOutput(s))
        // reconstruction now sees the regenerated output as the current turn
        assertEquals("O0-v2", sm.loadConversation(s).turns[0].assistantOutput)
    }

    @Test
    fun `error turn persists user row + error step and keeps prior output`() {
        val s = newSession()
        // seed a transcription so getFinalOutput has a fallback
        sm.addTranscriptionVersion(s, "the transcript", "whisper", "OPENAI", durationMs = 1)

        sm.appendConversationTurnError(
            sessionId = s, userMessageContent = "U0", inputText = "the transcript",
            model = "gpt-test", provider = "OPENAI", previousTranscriptionId = null,
            errorMessage = "boom", durationMs = 4, systemPromptForFirstTurn = "SYS"
        )

        val chain = db.processingStepDao().getCurrentChain(s)
        assertEquals(1, chain.size)
        assertEquals("ERROR", chain[0].status)
        assertNull(chain[0].outputText)

        // user row exists for audit
        assertEquals(1, db.conversationMessageDao().getUserMessages(s).size)
        // getFinalOutput falls back to the transcription
        assertEquals("the transcript", sm.getFinalOutput(s))
        // failed turn is not replayed
        assertEquals(0, sm.loadConversation(s).turns.size)
        assertNull(sm.getAssistantMessage(s))
    }

    @Test
    fun `append over an invalidated chain index bumps version instead of colliding (K6)`() {
        val s = newSession()
        // Three linear turns at chain 0, 1, 2 (all version 1).
        sm.appendConversationTurn(s, "U0", "raw", result("O0"), "OPENAI", null, 1, "SYS")
        sm.appendConversationTurn(s, "U1", "O0", result("O1"), "OPENAI", null, 1, "SYS")
        sm.appendConversationTurn(s, "U2", "O1", result("O2"), "OPENAI", null, 1, "SYS")

        // Regenerate the middle turn → invalidateDownstream marks chain 2 v1
        // is_current = 0, so getMaxChainIndex (which counts only current rows)
        // drops back to 1.
        sm.regenerateConversationTurn(s, chainIndex = 1, result = result("O1-v2"), provider = "OPENAI", durationMs = 1)

        // A fresh append now lands at chain_index 2, where an invalidated
        // version=1 row already exists. Before the fix, appendProcessingStep
        // hardcoded version=1 → UNIQUE(session_id, chain_index, version)
        // collision → SQLiteConstraintException. It must bump to version 2.
        val stepId = sm.appendConversationTurn(s, "U2b", "O1-v2", result("O2b"), "OPENAI", null, 1, "SYS")
        assertTrue(stepId.isNotEmpty())

        val versions = db.processingStepDao().getVersionsAtIndex(s, 2)
        assertEquals(2, versions.size) // invalidated v1 + fresh v2
        val current = versions.first { it.isCurrent }
        assertEquals(2, current.version)
        assertEquals("O2b", current.outputText)
        assertEquals("O2b", sm.getFinalOutput(s))
    }

    @Test
    fun `getTurnUserMessage returns the persisted user content at a chain index`() {
        val s = newSession()
        sm.appendConversationTurn(s, "U0", "raw", result("O0"), "OPENAI", null, 1, "SYS")
        sm.appendConversationTurn(s, "U1", "O0", result("O1"), "OPENAI", null, 1, "SYS")

        assertEquals("U0", sm.getTurnUserMessage(s, 0))
        assertEquals("U1", sm.getTurnUserMessage(s, 1))
        assertNull(sm.getTurnUserMessage(s, 2))
    }

    @Test
    fun `loadConversation after regenerate-middle + append yields one clean turn per index (G2-1)`() {
        val s = newSession()
        // Three linear turns at chain 0, 1, 2.
        sm.appendConversationTurn(s, "U0", "raw", result("O0"), "OPENAI", null, 1, "SYS")
        sm.appendConversationTurn(s, "U1", "O0", result("O1"), "OPENAI", null, 1, "SYS")
        sm.appendConversationTurn(s, "U2", "O1", result("O2"), "OPENAI", null, 1, "SYS")

        // Regenerate the MIDDLE turn → invalidateDownstream marks chain 2 v1
        // is_current = 0. The USER row at turn_index 2 ("U2") is intentionally
        // left in place (regenerate does not touch conversation rows).
        sm.regenerateConversationTurn(s, chainIndex = 1, result = result("O1-v2"), provider = "OPENAI", durationMs = 1)

        // A fresh append lands at chain_index 2 and writes a SECOND USER row at
        // turn_index 2 ("U2b"). Before G2-1, loadConversation joined ALL USER
        // rows by turn_index without seq disambiguation, so both the orphaned
        // "U2" and the new "U2b" mapped onto the single current step at index 2
        // → a 4-entry snapshot with a phantom turn (U2 paired with O2b) plus a
        // duplicate. The replay then fed the model a corrupted history.
        sm.appendConversationTurn(s, "U2b", "O1-v2", result("O2b"), "OPENAI", null, 1, "SYS")

        val turns = sm.loadConversation(s).turns
        assertEquals(3, turns.size)
        assertEquals(listOf("U0", "U1", "U2b"), turns.map { it.userContent })
        assertEquals(listOf("O0", "O1-v2", "O2b"), turns.map { it.assistantOutput })
        // The turns carry their real chain index (contiguous here).
        assertEquals(listOf(0, 1, 2), turns.map { it.chainIndex })
    }

    @Test
    fun `loadConversation skips a sandwiched ERROR turn but keeps real chain indices (G2-1)`() {
        val s = newSession()
        sm.addTranscriptionVersion(s, "the transcript", "whisper", "OPENAI", durationMs = 1)
        // Turn 0 SUCCESS, turn 1 ERROR (its own USER row), turn 2 SUCCESS.
        sm.appendConversationTurn(s, "U0", "raw", result("O0"), "OPENAI", null, 1, "SYS")
        sm.appendConversationTurnError(
            sessionId = s, userMessageContent = "U1err", inputText = "O0",
            model = "gpt-test", provider = "OPENAI", previousTranscriptionId = null,
            errorMessage = "boom", durationMs = 1, systemPromptForFirstTurn = null
        )
        sm.appendConversationTurn(s, "U2", "O0", result("O2"), "OPENAI", null, 1, "SYS")

        val turns = sm.loadConversation(s).turns
        // The ERROR turn is skipped, so the list is shorter than the chain — the
        // surviving turns must still report their real chain index (0 and 2, NOT
        // positional 0 and 1). regenerateConversationTurnBlocking relies on this
        // to build a correct replay instead of the pre-G2-1 positional take().
        assertEquals(2, turns.size)
        assertEquals(listOf("U0", "U2"), turns.map { it.userContent })
        assertEquals(listOf(0, 2), turns.map { it.chainIndex })
    }
}
