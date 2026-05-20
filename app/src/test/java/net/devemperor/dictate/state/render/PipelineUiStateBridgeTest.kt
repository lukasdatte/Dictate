package net.devemperor.dictate.state.render

import net.devemperor.dictate.state.InsertionTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import net.devemperor.dictate.core.PipelineUiState as CorePipelineUiState
import net.devemperor.dictate.state.PipelineUiState as StatePipelineUiState

/**
 * Roundtrip tests for the Phase-2 cutover bridge in
 * [PipelineUiStateBridge].
 *
 * # What this proves
 *
 * For every branch of either sealed class, going **out and back** via
 * the bridge preserves the **branch identity** and the fields that
 * both world variants share. Asymmetric fields (those that exist only
 * on one side) reset to documented defaults on the roundtrip — those
 * defaults are asserted explicitly so the bridge's lossy contract is
 * locked-in by test, not by KDoc alone.
 *
 * # Why "Branch-Identity" instead of full field-equality
 *
 * Plan §4 Phase 2 phrases the test as "Identity-Mapping für jeden
 * Branch" — branch-level identity, not field-level. The two sealed
 * classes carry different field sets:
 *
 * - Legacy `Running` has `currentStepName` + `hasFailure` —
 *   orchestrator's `Running` has neither (Phase 5.A adds them via
 *   `stepHistory` + `hasFailure`).
 * - Legacy `ReprocessStaging` has `audioDurationSeconds` +
 *   `editableQueue` + `selectedLanguage` + `selectedModel` +
 *   `isStarting` — orchestrator's only carries `(sessionId,
 *   transcript)`.
 * - Orchestrator `Preparing` + `Running` carry `sessionId` — legacy
 *   carries no `sessionId`.
 * - Orchestrator `Running` carries `target` + `startedAtMs` +
 *   `elapsedMs` — legacy carries none of those.
 *
 * Field-level identity is structurally impossible. Branch-level
 * identity + documented defaults is the honest contract; this test
 * cements it.
 *
 * @see PipelineUiStateBridge
 */
class PipelineUiStateBridgeTest {

    // ── Legacy → Orchestrator → Legacy ─────────────────────────────────

    @Test
    fun `roundtrip legacy Idle preserves branch`() {
        val before = CorePipelineUiState.Idle
        val after = before.toOrchestrator().toCoreLegacy()
        assertEquals(CorePipelineUiState.Idle, after)
    }

    @Test
    fun `roundtrip legacy Preparing preserves branch`() {
        val before = CorePipelineUiState.Preparing
        val after = before.toOrchestrator().toCoreLegacy()
        assertEquals(CorePipelineUiState.Preparing, after)
    }

    @Test
    fun `roundtrip legacy Running preserves counters + autoEnter, resets currentStepName + hasFailure`() {
        val before = CorePipelineUiState.Running(
            totalSteps = 3,
            completedSteps = 1,
            currentStepName = "transcription",
            autoEnterActive = true,
            hasFailure = false,
        )
        val after = before.toOrchestrator().toCoreLegacy() as CorePipelineUiState.Running
        assertEquals("totalSteps preserved", 3, after.totalSteps)
        assertEquals("completedSteps preserved", 1, after.completedSteps)
        assertEquals("autoEnterActive preserved", true, after.autoEnterActive)
        // Documented losses:
        assertEquals("currentStepName resets to empty on roundtrip", "", after.currentStepName)
        assertEquals("hasFailure resets to false on roundtrip", false, after.hasFailure)
    }

    @Test
    fun `roundtrip legacy ReprocessStaging preserves targetSessionId, resets detail fields`() {
        val before = CorePipelineUiState.ReprocessStaging(
            targetSessionId = "sid-42",
            audioDurationSeconds = 60L,
            editableQueue = listOf(1, 2, 3),
            selectedLanguage = "en",
            selectedModel = "whisper-1",
            isStarting = true,
        )
        val after = before.toOrchestrator().toCoreLegacy() as CorePipelineUiState.ReprocessStaging
        assertEquals("targetSessionId preserved", "sid-42", after.targetSessionId)
        // Documented losses:
        assertEquals(0L, after.audioDurationSeconds)
        assertEquals(emptyList<Int>(), after.editableQueue)
        assertEquals(null, after.selectedLanguage)
        assertEquals(null, after.selectedModel)
        assertEquals(false, after.isStarting)
    }

    // ── Orchestrator → Legacy → Orchestrator ───────────────────────────

    @Test
    fun `roundtrip orchestrator Idle preserves branch`() {
        val before = StatePipelineUiState.Idle
        val after = before.toCoreLegacy().toOrchestrator()
        assertEquals(StatePipelineUiState.Idle, after)
    }

    @Test
    fun `roundtrip orchestrator Preparing resets sessionId + autoEnterActive`() {
        val before = StatePipelineUiState.Preparing(sessionId = "sid-prep", autoEnterActive = true)
        val after = before.toCoreLegacy().toOrchestrator() as StatePipelineUiState.Preparing
        // Documented losses (legacy Preparing is parameterless):
        assertEquals("", after.sessionId)
        assertEquals(false, after.autoEnterActive)
    }

    @Test
    fun `roundtrip orchestrator Running preserves counters + autoEnter, resets sessionId + target`() {
        val before = StatePipelineUiState.Running(
            sessionId = "sid-run",
            target = InsertionTarget.REPROCESS_STAGING,
            autoEnterActive = true,
            completedSteps = 2,
            totalSteps = 4,
            startedAtMs = 1000L,
            elapsedMs = 500L,
        )
        val after = before.toCoreLegacy().toOrchestrator() as StatePipelineUiState.Running
        assertEquals("completedSteps preserved", 2, after.completedSteps)
        assertEquals("totalSteps preserved", 4, after.totalSteps)
        assertEquals("autoEnterActive preserved", true, after.autoEnterActive)
        // Documented losses:
        assertEquals("", after.sessionId)
        assertEquals(InsertionTarget.INPUT_CONNECTION, after.target)
        assertEquals(0L, after.startedAtMs)
        assertEquals(0L, after.elapsedMs)
    }

    @Test
    fun `roundtrip orchestrator ReprocessStaging preserves sessionId, resets transcript`() {
        val before = StatePipelineUiState.ReprocessStaging(sessionId = "sid-stage", transcript = "hello world")
        val after = before.toCoreLegacy().toOrchestrator() as StatePipelineUiState.ReprocessStaging
        assertEquals("sessionId preserved", "sid-stage", after.sessionId)
        // Documented loss:
        assertEquals("", after.transcript)
    }

    // ── Branch identity (all branches, both directions) ────────────────

    @Test
    fun `every legacy branch maps to a unique orchestrator branch`() {
        assertTrue(CorePipelineUiState.Idle.toOrchestrator() is StatePipelineUiState.Idle)
        assertTrue(CorePipelineUiState.Preparing.toOrchestrator() is StatePipelineUiState.Preparing)
        assertTrue(
            CorePipelineUiState.Running(0, 0, "", false, false).toOrchestrator()
                is StatePipelineUiState.Running,
        )
        assertTrue(
            CorePipelineUiState.ReprocessStaging("", 0L, emptyList(), null).toOrchestrator()
                is StatePipelineUiState.ReprocessStaging,
        )
    }

    @Test
    fun `every orchestrator branch maps to a unique legacy branch`() {
        assertTrue(StatePipelineUiState.Idle.toCoreLegacy() is CorePipelineUiState.Idle)
        assertTrue(StatePipelineUiState.Preparing("").toCoreLegacy() is CorePipelineUiState.Preparing)
        assertTrue(
            StatePipelineUiState.Running("", InsertionTarget.INPUT_CONNECTION).toCoreLegacy()
                is CorePipelineUiState.Running,
        )
        assertTrue(
            StatePipelineUiState.ReprocessStaging("", "").toCoreLegacy()
                is CorePipelineUiState.ReprocessStaging,
        )
    }
}
