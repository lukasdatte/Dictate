package net.devemperor.dictate.state.layout

import net.devemperor.dictate.state.DictateUiState
import net.devemperor.dictate.state.LayoutState
import net.devemperor.dictate.state.PipelineUiState
import net.devemperor.dictate.state.RecordingState
import net.devemperor.dictate.state.ResendState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * Structural integrity tests for [LayoutCatalog].
 *
 * **Scope:** the data invariants every layout-mode must satisfy
 * (no duplicate logical-ids per mode, every slot has the right id-set
 * for its backend) and the `forKeyboard(state)` selector decision tree.
 *
 * **Not in scope:** per-predicate truth tables — those live in
 * `VisibilityMatrixTest` (Spec 2 §14.2 25-case suite).
 */
class LayoutCatalogTest {

    private val strings = testLayoutStrings()
    private val catalog = LayoutCatalog(strings)

    // ─── Structural invariants ─────────────────────────────────────────

    @Test
    fun `all keyboard modes use IME_VIEW backend`() {
        listOf(
            catalog.KEYBOARD_TWO_ROW,
            catalog.KEYBOARD_SINGLE_ROW,
            catalog.KEYBOARD_TWO_ROW_SEND_MODE,
            catalog.KEYBOARD_SINGLE_ROW_SEND_MODE,
            catalog.KEYBOARD_REPROCESS_STAGING,
        ).forEach { mode ->
            assertEquals(
                "Mode ${mode.id} must target IME_VIEW backend",
                BackendType.IME_VIEW, mode.backend,
            )
        }
    }

    @Test
    fun `OVERLAY_5BUTTON targets OVERLAY_WINDOW backend`() {
        assertEquals(BackendType.OVERLAY_WINDOW, catalog.OVERLAY_5BUTTON.backend)
    }

    @Test
    fun `OVERLAY_5BUTTON is still the empty B5 placeholder`() {
        // B4-VAL F-31: structural reminder that the OVERLAY_5BUTTON body
        // is a placeholder until B5/C16 ships the 5-button layout
        // (Record / Send / Pause / Trash / Close). When B5 supplies the
        // body, this assertion flips from pass to fail — that's the
        // trigger to delete this test and exercise OVERLAY_5BUTTON for
        // real.
        assertEquals(
            "OVERLAY_5BUTTON.rows must remain empty until B5/C16 ships the body.",
            emptyList<RowDescriptor>(),
            catalog.OVERLAY_5BUTTON.rows,
        )
    }

    @Test
    fun `no LayoutMode contains duplicate logical button ids`() {
        catalog.allModes().forEach { mode ->
            val ids = mode.slots.map { it.logicalId }
            val unique = ids.toSet()
            assertEquals(
                "Mode ${mode.id} has duplicate logical ids: $ids",
                ids.size, unique.size,
            )
        }
    }

    @Test
    fun `all LayoutModeId enum values are represented in allModes`() {
        val expected = LayoutModeId.entries.toSet()
        val actual = catalog.allModes().map { it.id }.toSet()
        assertEquals(expected, actual)
    }

    @Test
    fun `KEYBOARD_TWO_ROW has all nine keyboard-modus slots`() {
        // Per Spec 2 §8.1 the two-row layout contains all nine logical
        // keyboard buttons (record / resend / backspace / audio-focus /
        // widget-toggle / trash / space / pause / enter), each visible
        // or hidden per predicate. The audio-focus slot is gone-by-default
        // but the slot itself must exist so the render-loop's silent-skip
        // guard never trips when the user toggles into single-row.
        val expected = setOf(
            LogicalButtonId.RECORD,
            LogicalButtonId.RESEND,
            LogicalButtonId.BACKSPACE,
            LogicalButtonId.AUDIO_FOCUS,
            LogicalButtonId.WIDGET_TOGGLE,
            LogicalButtonId.TRASH,
            LogicalButtonId.SPACE,
            LogicalButtonId.PAUSE,
            LogicalButtonId.ENTER,
        )
        val actual = catalog.KEYBOARD_TWO_ROW.slots.map { it.logicalId }.toSet()
        assertEquals(expected, actual)
    }

    @Test
    fun `KEYBOARD_SINGLE_ROW has all nine keyboard-modus slots`() {
        val expected = setOf(
            LogicalButtonId.RECORD,
            LogicalButtonId.RESEND,
            LogicalButtonId.BACKSPACE,
            LogicalButtonId.AUDIO_FOCUS,
            LogicalButtonId.WIDGET_TOGGLE,
            LogicalButtonId.TRASH,
            LogicalButtonId.SPACE,
            LogicalButtonId.PAUSE,
            LogicalButtonId.ENTER,
        )
        val actual = catalog.KEYBOARD_SINGLE_ROW.slots.map { it.logicalId }.toSet()
        assertEquals(expected, actual)
    }

    @Test
    fun `WIDGET_TOGGLE slot exists in all 5 KEYBOARD modes (Phase-B S-6 anchor)`() {
        val modes = listOf(
            catalog.KEYBOARD_TWO_ROW,
            catalog.KEYBOARD_SINGLE_ROW,
            catalog.KEYBOARD_TWO_ROW_SEND_MODE,
            catalog.KEYBOARD_SINGLE_ROW_SEND_MODE,
            catalog.KEYBOARD_REPROCESS_STAGING,
        )
        modes.forEach { mode ->
            val slot = mode.slots.firstOrNull { it.logicalId == LogicalButtonId.WIDGET_TOGGLE }
            assertNotNull(
                "Mode ${mode.id} must declare a WIDGET_TOGGLE slot " +
                    "(silent-skip guard in §6 ImeViewBackend renders error otherwise)",
                slot,
            )
        }
    }

    // ─── forKeyboard(state) decision tree (Spec 2 §8.6) ────────────────

    @Test
    fun `forKeyboard returns KEYBOARD_TWO_ROW for idle two-row state`() {
        val state = stateIdle(singleRow = false)
        assertSame(catalog.KEYBOARD_TWO_ROW, catalog.forKeyboard(state))
    }

    @Test
    fun `forKeyboard returns KEYBOARD_SINGLE_ROW for idle single-row state`() {
        val state = stateIdle(singleRow = true)
        assertSame(catalog.KEYBOARD_SINGLE_ROW, catalog.forKeyboard(state))
    }

    @Test
    fun `forKeyboard returns SEND_MODE_TWO_ROW for pipeline-running two-row state`() {
        val state = stateWithPipeline(PipelineUiState.Preparing("s1"), singleRow = false)
        assertSame(catalog.KEYBOARD_TWO_ROW_SEND_MODE, catalog.forKeyboard(state))
    }

    @Test
    fun `forKeyboard returns SEND_MODE_SINGLE_ROW for pipeline-running single-row state`() {
        val state = stateWithPipeline(PipelineUiState.Preparing("s1"), singleRow = true)
        assertSame(catalog.KEYBOARD_SINGLE_ROW_SEND_MODE, catalog.forKeyboard(state))
    }

    @Test
    fun `forKeyboard returns REPROCESS_STAGING regardless of singleRow setting`() {
        // Spec 2 §8.8 Edge-Case 1: staging has only a two-row variant; even
        // if the user has enabled single-row, the mode falls back to the
        // dedicated staging layout (workflow-fokussiert: queue + language chip).
        val singleRow = stateWithPipeline(
            PipelineUiState.ReprocessStaging("s1", "transcript"),
            singleRow = true,
        )
        val twoRow = stateWithPipeline(
            PipelineUiState.ReprocessStaging("s2", "transcript"),
            singleRow = false,
        )
        assertSame(catalog.KEYBOARD_REPROCESS_STAGING, catalog.forKeyboard(singleRow))
        assertSame(catalog.KEYBOARD_REPROCESS_STAGING, catalog.forKeyboard(twoRow))
    }

    @Test
    fun `forKeyboard treats pipeline Running same as Preparing for mode-selection`() {
        // Spec 2 §8.6: both `Preparing` and `Running` count as `isPipelineLive`,
        // so both select the SEND_MODE family.
        val running = stateWithPipeline(
            PipelineUiState.Running(sessionId = "s1", target = net.devemperor.dictate.state.InsertionTarget.INPUT_CONNECTION),
            singleRow = false,
        )
        assertSame(catalog.KEYBOARD_TWO_ROW_SEND_MODE, catalog.forKeyboard(running))
    }

    // ─── Defensive: every slot type-checks ─────────────────────────────

    @Test
    fun `every slot in every mode survives a null-safe resolver evaluation`() {
        // Smoke-test: feed each slot a baseline DictateUiState and make
        // sure no resolver throws. This catches obvious mistakes such as
        // a resolver that unconditionally reads a sub-state field that
        // doesn't exist on the baseline.
        val state = stateIdle(singleRow = false)
        catalog.allModes().forEach { mode ->
            mode.slots.forEach { slot ->
                try {
                    slot.visibilityPredicate(state)
                    slot.enabledResolver(state)
                    slot.alphaResolver(state)
                    slot.iconResolver(state)
                    slot.textResolver(state)
                    // actionResolver intentionally not invoked here — it
                    // may call services.audioFileFactory.allocate() (the
                    // record-btn path), which we exercise in
                    // ActionResolversTest with a fake-services fixture.
                } catch (t: Throwable) {
                    fail("Slot ${slot.logicalId} in ${mode.id} threw: ${t.message}")
                }
            }
        }
        assertTrue("Smoke run passed", true)
    }

    // ─── Helpers ───────────────────────────────────────────────────────

    private fun stateIdle(singleRow: Boolean): DictateUiState =
        DictateUiState.initial().copy(
            recording = RecordingState.Idle,
            pipeline = PipelineUiState.Idle,
            layout = LayoutState(singleRowMode = singleRow),
            resend = ResendState(lastAudioExists = false, resendEnabled = false),
        )

    private fun stateWithPipeline(pipe: PipelineUiState, singleRow: Boolean): DictateUiState =
        DictateUiState.initial().copy(
            recording = RecordingState.Idle,
            pipeline = pipe,
            layout = LayoutState(singleRowMode = singleRow),
        )
}

// ──── Shared test fixture ────────────────────────────────────────────

/**
 * Default [LayoutStrings] for tests — literal English strings, deterministic
 * formatting functions. Tests that care about i18n details override
 * specific fields.
 */
internal fun testLayoutStrings(): LayoutStrings = LayoutStrings(
    record = "Record",
    send = "Send (en)",
    sending = "Sending …",
    dictateButtonText = { "Dictate (en)" },
    formatStagingLabel = { secs -> "Audio 0:${"%02d".format(secs)} · Send" },
    formatPipelineLabel = { done, total, autoEnter, elapsedMs ->
        val mark = if (autoEnter) " ↵" else ""
        "$done/$total$mark  ${elapsedMs}ms"
    },
)

/** A throwaway file used only for action-resolver tests' allocate fixtures. */
internal fun stubAudioFile(): File = File("/tmp/dictate-test-stub.m4a")
