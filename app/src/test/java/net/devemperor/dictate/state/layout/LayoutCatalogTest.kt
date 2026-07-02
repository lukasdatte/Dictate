package net.devemperor.dictate.state.layout

import net.devemperor.dictate.state.DictateUiState
import net.devemperor.dictate.state.LayoutState
import net.devemperor.dictate.state.PipelineUiState
import net.devemperor.dictate.state.RecordingState
import net.devemperor.dictate.state.ResendState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    fun `OVERLAY_5BUTTON has all four overlay slots in row order (Variante 2a)`() {
        // dictate-widget-integration §6.5 Variante 2a:
        //   Row 1: RECORD (merged ex-RECORD + ex-SEND, FillRemaining)
        //   Row 2: TRASH / PAUSE / CLOSE
        // The previous standalone OVERLAY_SEND was deleted per
        // §8.2 Chunk 2.4 / §10.2 OQ-1 (user-decision).
        val rows = catalog.OVERLAY_5BUTTON.rows
        assertEquals("OVERLAY_5BUTTON must have exactly two rows.", 2, rows.size)
        assertEquals(
            "Row 1 must be the single merged RECORD slot (Variante 2a).",
            listOf(LogicalButtonId.OVERLAY_RECORD),
            rows[0].slots.map { it.logicalId },
        )
        assertEquals(
            "Row 2 must be TRASH / PAUSE / CLOSE in left-to-right order.",
            listOf(
                LogicalButtonId.OVERLAY_TRASH,
                LogicalButtonId.OVERLAY_PAUSE,
                LogicalButtonId.OVERLAY_CLOSE,
            ),
            rows[1].slots.map { it.logicalId },
        )
    }

    @Test
    fun `OVERLAY_5BUTTON has no MotionScene transition (sceneStateId is null)`() {
        // Spec 3 §3.1 + LayoutMode KDoc — the overlay surface is a flat
        // WindowManager-attached layout, not a MotionLayout target.
        assertEquals(null, catalog.OVERLAY_5BUTTON.sceneStateId)
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
    fun `KEYBOARD_TWO_ROW has all ten keyboard-modus slots`() {
        // Per Spec 2 §8.1 the two-row layout contains all logical keyboard
        // buttons (record / resend / backspace / audio-focus /
        // widget-toggle / trash / space / pause / enter — plus the
        // ADR-0009 secondary-record mic), each visible or hidden per
        // predicate. The audio-focus slot is gone-by-default but the slot
        // itself must exist so the render-loop's silent-skip guard never
        // trips when the user toggles into single-row; RECORD_SECONDARY is
        // listed with `{ false }` here for the same reason (it only ever
        // shows in the SEND_MODE layouts).
        val expected = setOf(
            LogicalButtonId.RECORD,
            LogicalButtonId.RESEND,
            LogicalButtonId.RECORD_SECONDARY,
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
    fun `KEYBOARD_SINGLE_ROW has all ten keyboard-modus slots`() {
        val expected = setOf(
            LogicalButtonId.RECORD,
            LogicalButtonId.RESEND,
            LogicalButtonId.RECORD_SECONDARY,
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

    @Test
    fun `RECORD_SECONDARY slot exists in all 5 KEYBOARD modes (ADR-0009 anchor)`() {
        // Mirrors the WIDGET_TOGGLE anchor: the slot is declared in every
        // keyboard mode (predicate `{ false }` outside SEND_MODE) so the
        // render-loop's silent-skip guard never trips and the view can
        // never linger stale across mode switches.
        val modes = listOf(
            catalog.KEYBOARD_TWO_ROW,
            catalog.KEYBOARD_SINGLE_ROW,
            catalog.KEYBOARD_TWO_ROW_SEND_MODE,
            catalog.KEYBOARD_SINGLE_ROW_SEND_MODE,
            catalog.KEYBOARD_REPROCESS_STAGING,
        )
        modes.forEach { mode ->
            val slot = mode.slots.firstOrNull { it.logicalId == LogicalButtonId.RECORD_SECONDARY }
            assertNotNull(
                "Mode ${mode.id} must declare a RECORD_SECONDARY slot " +
                    "(silent-skip guard in §6 ImeViewBackend renders error otherwise)",
                slot,
            )
        }
    }

    @Test
    fun `RECORD_SECONDARY is visible in SEND_MODE modes only while recording is Idle`() {
        // Spec criteria 1 + 4: visible iff pipeline live AND no recording
        // in flight (single-MediaRecorder gate, belt-and-braces to the
        // forKeyboard precedence which already leaves SEND_MODE when a
        // recording starts).
        val pipelineLiveIdleRecording = stateWithPipeline(
            PipelineUiState.Running(
                sessionId = "s1",
                target = net.devemperor.dictate.state.InsertionTarget.INPUT_CONNECTION,
            ),
            singleRow = false,
        )
        val pipelineLiveRecordingActive = stateRecordingWithPipeline(
            PipelineUiState.Running(
                sessionId = "s1",
                target = net.devemperor.dictate.state.InsertionTarget.INPUT_CONNECTION,
            ),
            singleRow = false,
        )
        listOf(catalog.KEYBOARD_TWO_ROW_SEND_MODE, catalog.KEYBOARD_SINGLE_ROW_SEND_MODE)
            .forEach { mode ->
                val slot = mode.slots.first { it.logicalId == LogicalButtonId.RECORD_SECONDARY }
                assertTrue(
                    "visible in ${mode.id} while pipeline live + recording Idle",
                    slot.visibilityPredicate(pipelineLiveIdleRecording),
                )
                assertFalse(
                    "hidden in ${mode.id} while a recording is in flight",
                    slot.visibilityPredicate(pipelineLiveRecordingActive),
                )
            }
        listOf(
            catalog.KEYBOARD_TWO_ROW,
            catalog.KEYBOARD_SINGLE_ROW,
            catalog.KEYBOARD_REPROCESS_STAGING,
        ).forEach { mode ->
            val slot = mode.slots.first { it.logicalId == LogicalButtonId.RECORD_SECONDARY }
            assertFalse(
                "structurally hidden in ${mode.id}",
                slot.visibilityPredicate(pipelineLiveIdleRecording),
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
    fun `SEND_MODE record button stays ENABLED during Preparing (#AE-OPTIK2 regression)`() {
        // Pre-fix the record-slot's enabledResolver disabled the view during
        // Preparing (the 500ms–2s upload window). Android's onTouchEvent
        // swallows touches on isEnabled=false views — the result was that
        // the double-tap-to-toggle-auto-enter feature was silently
        // unreachable in the most common landing window. The fix removed
        // the enabledResolver entirely; resolveRecordActionPipeline (post
        // #AE-DEEP2) is the single source of truth for "is this click
        // meaningful?" and already accepts both Preparing and Running.
        for ((label, mode) in listOf(
            "TWO_ROW_SEND_MODE" to catalog.KEYBOARD_TWO_ROW_SEND_MODE,
            "SINGLE_ROW_SEND_MODE" to catalog.KEYBOARD_SINGLE_ROW_SEND_MODE,
        )) {
            val recordSlot = mode.slots.first { it.logicalId == LogicalButtonId.RECORD }
            val preparingState = stateWithPipeline(
                PipelineUiState.Preparing("s1"),
                singleRow = label == "SINGLE_ROW_SEND_MODE",
            )
            assertEquals(
                "$label record-slot must be ENABLED during Preparing — Android " +
                    "swallows clicks on disabled views, killing the auto-enter toggle.",
                true,
                recordSlot.enabledResolver(preparingState),
            )
        }
    }

    @Test
    fun `forKeyboard returns SEND_MODE_SINGLE_ROW for pipeline-running single-row state`() {
        val state = stateWithPipeline(PipelineUiState.Preparing("s1"), singleRow = true)
        assertSame(catalog.KEYBOARD_SINGLE_ROW_SEND_MODE, catalog.forKeyboard(state))
    }

    @Test
    fun `info-bar force-expand — pipeline error masks singleRowMode (idle)`() {
        // ADR-0006-completion regression: error bars now flow through
        // InfoBarSelector, so the 2026-05-22 force-expand ("komplett
        // expandierter Modus" whenever an info message is present)
        // applies to them by construction. A single-row user still gets
        // the two-row layout while the error bar is up.
        val state = stateIdle(singleRow = true).copy(
            infoHints = net.devemperor.dictate.state.InfoHintState(
                pipelineError = net.devemperor.dictate.state.PipelineErrorHint(
                    kind = net.devemperor.dictate.state.PipelineErrorKind.INTERNET_ERROR,
                    providerKey = null,
                    occurredAt = 1L,
                ),
            ),
        )
        assertSame(catalog.KEYBOARD_TWO_ROW, catalog.forKeyboard(state))
    }

    @Test
    fun `info-bar force-expand — engagement hint masks singleRowMode during pipeline`() {
        val state = stateWithPipeline(PipelineUiState.Preparing("s1"), singleRow = true).copy(
            infoHints = net.devemperor.dictate.state.InfoHintState(
                engagementHint = net.devemperor.dictate.state.EngagementHint.UPDATE,
            ),
        )
        assertSame(catalog.KEYBOARD_TWO_ROW_SEND_MODE, catalog.forKeyboard(state))
    }

    @Test
    fun `info-bar force-expand ends when the hint clears`() {
        // The override is transient + computed — clearing the hint
        // restores the persisted single-row preference.
        val state = stateIdle(singleRow = true)
        assertSame(catalog.KEYBOARD_SINGLE_ROW, catalog.forKeyboard(state))
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
    fun `RED-PROOF forKeyboard — live recording wins over SEND_MODE (two-row)`() {
        // ADR-0009 secondary-recording precedence: while a recording is
        // live the user needs the recording controls (timer / pause /
        // trash / stop&send), even though a pipeline run is processing in
        // the background. `recordingLive` must therefore outrank
        // `isPipelineLive` in forKeyboard. Written FIRST as the red-proof
        // for Chunk 2 (fails on the pre-change catalog, which returned
        // KEYBOARD_TWO_ROW_SEND_MODE here).
        val state = stateRecordingWithPipeline(
            PipelineUiState.Running(
                sessionId = "s1",
                target = net.devemperor.dictate.state.InsertionTarget.INPUT_CONNECTION,
            ),
            singleRow = false,
        )
        assertSame(catalog.KEYBOARD_TWO_ROW, catalog.forKeyboard(state))
    }

    @Test
    fun `forKeyboard — live recording wins over SEND_MODE (single-row)`() {
        // Single-row twin of the red-proof above: the precedence holds
        // under the user's single-row preference too.
        val state = stateRecordingWithPipeline(
            PipelineUiState.Preparing(sessionId = "s1"),
            singleRow = true,
        )
        assertSame(catalog.KEYBOARD_SINGLE_ROW, catalog.forKeyboard(state))
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

    private fun stateRecordingWithPipeline(pipe: PipelineUiState, singleRow: Boolean): DictateUiState =
        DictateUiState.initial().copy(
            recording = RecordingState.Active(
                useBluetooth = false, audioFile = stubAudioFile(), sessionId = "sec-rec",
            ),
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
    // F-15 — language-aware: the label reflects the effective language
    // code passed by `resolveRecordButtonText` (state.language.effective).
    dictateButtonText = { effectiveLanguage -> "Dictate ($effectiveLanguage)" },
    formatStagingLabel = { secs -> "Audio 0:${"%02d".format(secs)} · Send" },
    formatPipelineLabel = { stepName, done, total, autoEnter, elapsedMs ->
        // Mirrors the production formatter's two-line layout for tests
        // that exercise B-D-1: when stepName is non-blank, the test
        // fixture renders "<stepName>\n<N>/<M>[ ↵] <ms>ms"; otherwise
        // the single-line legacy shape. Tests asserting on the exact
        // String shape pin which arm they want.
        val mark = if (autoEnter) " ↵" else ""
        val phase = stepName?.takeIf { it.isNotBlank() }
        if (phase != null) {
            "$phase\n$done/$total$mark  ${elapsedMs}ms"
        } else {
            "$done/$total$mark  ${elapsedMs}ms"
        }
    },
    formatPreparingLabel = { autoEnter ->
        // #AE-DEEP2: mirrors the production lambda's " ↵" suffix so tests
        // exercising the upload-window double-tap see the same visual.
        if (autoEnter) "Sending … ↵" else "Sending …"
    },
)

/** A throwaway file used only for action-resolver tests' allocate fixtures. */
internal fun stubAudioFile(): File = File("/tmp/dictate-test-stub.m4a")
