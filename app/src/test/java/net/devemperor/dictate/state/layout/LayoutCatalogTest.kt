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
    fun `OVERLAY_5BUTTON has all overlay slots in row order (Variante 2a + P4 third row)`() {
        // dictate-widget-integration §6.5 Variante 2a:
        //   Row 1: RECORD (merged ex-RECORD + ex-SEND, FillRemaining)
        //   Row 2: TRASH / PAUSE / CLOSE
        // P4 (widget-third-row):
        //   Row 3: DELETE / SPACE / ENTER — direct-editing row, only
        //   rendered while an InputConnection is available (canCommitToHost).
        val rows = catalog.OVERLAY_5BUTTON.rows
        assertEquals("OVERLAY_5BUTTON must have exactly three rows.", 3, rows.size)
        assertEquals(
            "Row 1 must be the single merged RECORD slot (Variante 2a).",
            listOf(LogicalButtonId.OVERLAY_RECORD),
            rows[0].slots.map { it.logicalId },
        )
        assertEquals(
            "Row 2 must be TRASH / PAUSE / RECORD_SECONDARY / CLOSE in left-to-right order.",
            listOf(
                LogicalButtonId.OVERLAY_TRASH,
                LogicalButtonId.OVERLAY_PAUSE,
                LogicalButtonId.OVERLAY_RECORD_SECONDARY,
                LogicalButtonId.OVERLAY_CLOSE,
            ),
            rows[1].slots.map { it.logicalId },
        )
        assertEquals(
            "Row 3 must be DELETE / SPACE / ENTER in left-to-right order (P4).",
            listOf(
                LogicalButtonId.OVERLAY_DELETE,
                LogicalButtonId.OVERLAY_SPACE,
                LogicalButtonId.OVERLAY_ENTER,
            ),
            rows[2].slots.map { it.logicalId },
        )
    }

    // ─── P4 third-row (Delete | Space | Enter) ─────────────────────────

    @Test
    fun `overlay third-row slots are visible only when canCommitToHost (InputConnection available)`() {
        // The whole row is gated on the canonical "input field available"
        // predicate — `DictateUiState.canCommitToHost` (== imeViewVisible).
        // WIDGET (imeViewVisible=true) shows the row; HOVER
        // (imeViewVisible=false) hides every slot so the user cannot type
        // into a null InputConnection.
        val thirdRowIds = listOf(
            LogicalButtonId.OVERLAY_DELETE,
            LogicalButtonId.OVERLAY_SPACE,
            LogicalButtonId.OVERLAY_ENTER,
        )
        val widgetState = overlayState(imeViewVisible = true)
        val hoverState = overlayState(imeViewVisible = false)
        thirdRowIds.forEach { id ->
            val slot = catalog.OVERLAY_5BUTTON.slots.first { it.logicalId == id }
            assertTrue(
                "$id must be VISIBLE when canCommitToHost (WIDGET / imeViewVisible=true)",
                slot.visibilityPredicate(widgetState),
            )
            assertFalse(
                "$id must be GONE when !canCommitToHost (HOVER / imeViewVisible=false)",
                slot.visibilityPredicate(hoverState),
            )
        }
    }

    @Test
    fun `overlay DELETE slot dispatches Backspace`() {
        val slot = catalog.OVERLAY_5BUTTON.slots.first { it.logicalId == LogicalButtonId.OVERLAY_DELETE }
        assertEquals(
            net.devemperor.dictate.state.Action.KeyboardInputAction.Backspace,
            slot.actionResolver(overlayState(imeViewVisible = true), net.devemperor.dictate.testutil.fakeModuleServices()),
        )
    }

    @Test
    fun `overlay SPACE slot dispatches SpaceKey`() {
        val slot = catalog.OVERLAY_5BUTTON.slots.first { it.logicalId == LogicalButtonId.OVERLAY_SPACE }
        assertEquals(
            net.devemperor.dictate.state.Action.KeyboardInputAction.SpaceKey,
            slot.actionResolver(overlayState(imeViewVisible = true), net.devemperor.dictate.testutil.fakeModuleServices()),
        )
    }

    @Test
    fun `overlay ENTER slot reuses the keyboard enter resolvers (action + icon)`() {
        val slot = catalog.OVERLAY_5BUTTON.slots.first { it.logicalId == LogicalButtonId.OVERLAY_ENTER }
        val visibleState = overlayState(imeViewVisible = true)
        // Action mirrors resolveEnterAction: EnterKey when canCommitToHost.
        assertEquals(
            net.devemperor.dictate.state.Action.KeyboardInputAction.EnterKey,
            slot.actionResolver(visibleState, net.devemperor.dictate.testutil.fakeModuleServices()),
        )
        // Icon mirrors resolveEnterIcon (host-editor-aware; no EditorInfo
        // on the baseline → the return-arrow drawable).
        assertEquals(
            resolveEnterIcon(visibleState),
            slot.iconResolver(visibleState),
        )
    }

    // ─── P2 overlay secondary-record mic button ────────────────────────

    @Test
    fun `overlay RECORD_SECONDARY is visible while pipeline live + recording Idle, in HOVER too`() {
        // P2 / ADR-0009: the widget twin of the keyboard RECORD_SECONDARY
        // slot. 2026-07-12 — the imeViewVisible gate is REMOVED: with
        // HOVER-send enabled (ADR-0009 deferred-insertion + ADR-0011
        // headless-completion) a secondary recording started in HOVER CAN be
        // sent (its result defers to a pending part), so the old "startbar-
        // aber-nicht-sendbar" anti-pattern rationale is void. Visibility now
        // gates on pipeline-live AND recording Idle only.
        val slot = catalog.OVERLAY_5BUTTON.slots
            .first { it.logicalId == LogicalButtonId.OVERLAY_RECORD_SECONDARY }
        val running = PipelineUiState.Running(
            sessionId = "s1",
            target = net.devemperor.dictate.state.InsertionTarget.INPUT_CONNECTION,
        )
        val activeRecording = RecordingState.Active(
            useBluetooth = false, audioFile = stubAudioFile(), sessionId = "sec-rec",
        )

        // Visible: pipeline Preparing/Running + recording Idle + IME visible.
        assertTrue(
            "visible while Preparing + recording Idle + imeViewVisible",
            slot.visibilityPredicate(
                overlaySecondaryState(PipelineUiState.Preparing("s1"), RecordingState.Idle, imeViewVisible = true),
            ),
        )
        assertTrue(
            "visible while Running + recording Idle + imeViewVisible",
            slot.visibilityPredicate(
                overlaySecondaryState(running, RecordingState.Idle, imeViewVisible = true),
            ),
        )

        // Hidden: no live pipeline run.
        assertFalse(
            "hidden when no pipeline run is live",
            slot.visibilityPredicate(
                overlaySecondaryState(PipelineUiState.Idle, RecordingState.Idle, imeViewVisible = true),
            ),
        )
        // Hidden: a recording is already in flight (single-MediaRecorder gate).
        assertFalse(
            "hidden while a recording is Active",
            slot.visibilityPredicate(
                overlaySecondaryState(running, activeRecording, imeViewVisible = true),
            ),
        )
        // VISIBLE in HOVER too (2026-07-12): the button now appears while the
        // IME-View is hidden — the secondary recording is both startable AND
        // sendable (deferred to a pending part).
        assertTrue(
            "visible when imeViewVisible=false (HOVER — startable and now sendable)",
            slot.visibilityPredicate(
                overlaySecondaryState(running, RecordingState.Idle, imeViewVisible = false),
            ),
        )
    }

    @Test
    fun `overlay RECORD_SECONDARY reuses resolveSecondaryRecordAction (start from Idle)`() {
        val slot = catalog.OVERLAY_5BUTTON.slots
            .first { it.logicalId == LogicalButtonId.OVERLAY_RECORD_SECONDARY }
        val running = PipelineUiState.Running(
            sessionId = "s1",
            target = net.devemperor.dictate.state.InsertionTarget.INPUT_CONNECTION,
        )
        val action = slot.actionResolver(
            overlaySecondaryState(running, RecordingState.Idle, imeViewVisible = true),
            net.devemperor.dictate.testutil.fakeModuleServices(),
        )
        assertTrue(
            "tap must arm a fresh recording (StartRecording), got $action",
            action is net.devemperor.dictate.state.Action.RecordingAction.StartRecording,
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
    fun `forKeyboard returns REVIEW_PANEL when the review panel is open (ADR-0013)`() {
        // The review panel outranks every other mode (it opens only after the
        // pipeline FSM is Idle) and ignores the single-row setting, like staging.
        val open = net.devemperor.dictate.state.ReviewPanelState(
            open = true, sessionId = "s1", output = "out", message = "why",
        )
        val singleRow = DictateUiState.initial().copy(
            reviewPanel = open,
            layout = net.devemperor.dictate.state.LayoutState(singleRowMode = true),
        )
        val twoRow = DictateUiState.initial().copy(reviewPanel = open)
        assertSame(catalog.KEYBOARD_REVIEW_PANEL, catalog.forKeyboard(singleRow))
        assertSame(catalog.KEYBOARD_REVIEW_PANEL, catalog.forKeyboard(twoRow))
    }

    @Test
    fun `REVIEW_PANEL hides every grid button`() {
        val state = DictateUiState.initial().copy(
            reviewPanel = net.devemperor.dictate.state.ReviewPanelState(open = true, sessionId = "s1"),
        )
        catalog.KEYBOARD_REVIEW_PANEL.slots.forEach { slot ->
            assertEquals(
                "REVIEW_PANEL must hide ${slot.logicalId}",
                false, slot.visibilityPredicate(state),
            )
        }
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

    /**
     * A minimal overlay-surface state. [imeViewVisible] is the sole axis
     * the P4 third-row visibility keys on (== `DictateUiState.canCommitToHost`):
     * `true` models WIDGET with an available InputConnection, `false`
     * models HOVER without one.
     */
    private fun overlayState(imeViewVisible: Boolean): DictateUiState =
        DictateUiState.initial().copy(imeViewVisible = imeViewVisible)

    /**
     * State for the P2 overlay secondary-record visibility matrix: the
     * three axes its predicate keys on — pipeline sub-state, recording
     * sub-state, and [imeViewVisible] (the IME-hidden gate).
     */
    private fun overlaySecondaryState(
        pipe: PipelineUiState,
        recording: RecordingState,
        imeViewVisible: Boolean,
    ): DictateUiState =
        DictateUiState.initial().copy(
            pipeline = pipe,
            recording = recording,
            imeViewVisible = imeViewVisible,
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
