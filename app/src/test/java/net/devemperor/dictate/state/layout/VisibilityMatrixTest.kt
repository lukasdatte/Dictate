package net.devemperor.dictate.state.layout

import net.devemperor.dictate.state.DictateUiState
import net.devemperor.dictate.state.InsertionTarget
import net.devemperor.dictate.state.LayoutState
import net.devemperor.dictate.state.PipelineUiState
import net.devemperor.dictate.state.RecordingState
import net.devemperor.dictate.state.ResendState
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/**
 * The 25-case visibility-matrix suite per Spec 2 §14.2.
 *
 * Each case is a `(LayoutMode, DictateUiState, expected visibility-map)`
 * tuple — we evaluate every slot's `visibilityPredicate` against the
 * supplied state and assert against the spec-tabulated truth row.
 *
 * # Why parameterised?
 *
 * The 5 LayoutModes × 5 typical states = 25 cells is a natural fit for
 * JUnit's parameterised runner — adding a new layout mode means one row
 * per state, not a whole new test class.
 *
 * # What this catches
 *
 * - A resolver that reads the wrong sub-state field (e.g. `state.audio`
 *   instead of `state.layout`).
 * - A predicate that flipped its boolean polarity.
 * - A copy-paste between SEND_MODE and standard-mode that re-introduces
 *   bug #1.1 #3a (trash/pause visible during pipeline).
 *
 * # JVM-pure
 *
 * Each `DictateUiState` is constructed in memory — no Android Context,
 * no Robolectric (K-1/K-4).
 */
@RunWith(Parameterized::class)
class VisibilityMatrixTest(
    private val caseName: String,
    private val modeProvider: (LayoutCatalog) -> LayoutMode,
    private val state: DictateUiState,
    private val expected: Map<LogicalButtonId, Boolean>,
) {

    private val catalog = LayoutCatalog(testLayoutStrings())

    @Test
    fun `every slot in the mode satisfies the spec truth-table`() {
        // B4-VAL F-34c: pin the cross-mode invariant — every state-builder
        // in this matrix is for the KEYBOARD viewMode. Catalog modes only
        // run on KEYBOARD; if a future helper ever returns a state with a
        // different viewMode the matrix's expectations no longer hold.
        assertEquals(
            "VisibilityMatrixTest only exercises KEYBOARD viewMode (caseName=$caseName)",
            net.devemperor.dictate.state.ViewMode.KEYBOARD, state.viewMode,
        )
        val mode = modeProvider(catalog)
        // For every (logicalId, expected) pair, locate the slot in the
        // mode and assert visibility matches. A slot that is *missing*
        // from the mode is a structural error (see LayoutCatalogTest);
        // here we focus on the boolean truth.
        expected.forEach { (id, expectedVisible) ->
            val slot = mode.slots.firstOrNull { it.logicalId == id }
                ?: error("Mode ${mode.id} is missing slot $id (case=$caseName)")
            val actual = slot.visibilityPredicate(state)
            assertEquals(
                "$caseName: slot $id in ${mode.id} expected visible=$expectedVisible, got $actual",
                expectedVisible, actual,
            )
        }
    }

    companion object {

        // ─── State builders ───────────────────────────────────────────

        private fun idleNoAudio(singleRow: Boolean = false): DictateUiState =
            DictateUiState.initial().copy(
                recording = RecordingState.Idle,
                pipeline = PipelineUiState.Idle,
                layout = LayoutState(singleRowMode = singleRow),
                resend = ResendState(lastAudioExists = false, resendEnabled = false),
            )

        private fun idleWithAudio(singleRow: Boolean = false): DictateUiState =
            DictateUiState.initial().copy(
                recording = RecordingState.Idle,
                pipeline = PipelineUiState.Idle,
                layout = LayoutState(singleRowMode = singleRow),
                resend = ResendState(lastAudioExists = true, resendEnabled = true),
            )

        private fun recordingActive(singleRow: Boolean = false): DictateUiState =
            DictateUiState.initial().copy(
                recording = RecordingState.Active(
                    useBluetooth = false,
                    audioFile = stubAudioFile(),
                ),
                pipeline = PipelineUiState.Idle,
                layout = LayoutState(singleRowMode = singleRow),
            )

        private fun pipelineRunning(singleRow: Boolean = false): DictateUiState =
            DictateUiState.initial().copy(
                recording = RecordingState.Idle,
                pipeline = PipelineUiState.Preparing("s1"),
                layout = LayoutState(singleRowMode = singleRow),
            )

        private fun reprocessStaging(): DictateUiState =
            DictateUiState.initial().copy(
                recording = RecordingState.Idle,
                pipeline = PipelineUiState.ReprocessStaging("s1", "transcript"),
                layout = LayoutState(singleRowMode = false),
            )

        // ─── Expected truth-tables (Spec 2 §8.7 visibility matrix) ────

        /** Two-row idle (with `lastAudio = false`). */
        private val expectedTwoRowIdleNoAudio = mapOf(
            LogicalButtonId.RECORD to true,
            LogicalButtonId.RESEND to false,        // no audio
            LogicalButtonId.BACKSPACE to true,
            LogicalButtonId.AUDIO_FOCUS to false,   // single-row-only
            LogicalButtonId.WIDGET_TOGGLE to true,
            LogicalButtonId.TRASH to false,
            LogicalButtonId.SPACE to true,
            LogicalButtonId.PAUSE to false,
            LogicalButtonId.ENTER to true,
        )

        /** Two-row idle with audio + resend pref on. */
        private val expectedTwoRowIdleWithAudio = expectedTwoRowIdleNoAudio + (LogicalButtonId.RESEND to true)

        /** Two-row while recording-active. */
        private val expectedTwoRowRecording = mapOf(
            LogicalButtonId.RECORD to true,
            LogicalButtonId.RESEND to false,     // recording suppresses resend
            LogicalButtonId.BACKSPACE to true,
            LogicalButtonId.AUDIO_FOCUS to false,
            LogicalButtonId.WIDGET_TOGGLE to true,
            LogicalButtonId.TRASH to true,       // active recording shows cancel
            LogicalButtonId.SPACE to true,
            LogicalButtonId.PAUSE to true,
            LogicalButtonId.ENTER to true,
        )

        /** Single-row idle no audio. */
        private val expectedSingleRowIdleNoAudio = mapOf(
            LogicalButtonId.RECORD to true,
            LogicalButtonId.RESEND to false,
            LogicalButtonId.BACKSPACE to true,
            LogicalButtonId.AUDIO_FOCUS to true,    // single-row-specific
            LogicalButtonId.WIDGET_TOGGLE to true,
            LogicalButtonId.TRASH to false,
            LogicalButtonId.SPACE to true,
            LogicalButtonId.PAUSE to false,
            LogicalButtonId.ENTER to true,
        )

        /** Single-row idle with audio + resend pref on. */
        private val expectedSingleRowIdleWithAudio = expectedSingleRowIdleNoAudio + (LogicalButtonId.RESEND to true)

        /** Single-row while recording. */
        private val expectedSingleRowRecording = mapOf(
            LogicalButtonId.RECORD to true,
            LogicalButtonId.RESEND to false,
            LogicalButtonId.BACKSPACE to true,
            LogicalButtonId.AUDIO_FOCUS to true,
            LogicalButtonId.WIDGET_TOGGLE to true,
            LogicalButtonId.TRASH to true,
            LogicalButtonId.SPACE to true,
            LogicalButtonId.PAUSE to true,
            LogicalButtonId.ENTER to true,
        )

        /** Two-row SEND_MODE — hardcoded false for TRASH/PAUSE (bug #3a fix). */
        private val expectedTwoRowSendMode = mapOf(
            LogicalButtonId.RECORD to true,
            LogicalButtonId.RESEND to false,
            LogicalButtonId.BACKSPACE to true,
            LogicalButtonId.AUDIO_FOCUS to false,
            LogicalButtonId.WIDGET_TOGGLE to false,    // GONE during pipeline (Spec 2 §8.3)
            LogicalButtonId.TRASH to false,            // bug #3a fix
            LogicalButtonId.SPACE to true,
            LogicalButtonId.PAUSE to false,            // bug #3a fix
            LogicalButtonId.ENTER to true,
        )

        /** Single-row SEND_MODE — audio-focus still visible (Spec 2 §8.3). */
        private val expectedSingleRowSendMode = mapOf(
            LogicalButtonId.RECORD to true,
            LogicalButtonId.RESEND to false,
            LogicalButtonId.BACKSPACE to true,
            LogicalButtonId.AUDIO_FOCUS to true,    // single-row keeps audio-focus on
            LogicalButtonId.WIDGET_TOGGLE to false,
            LogicalButtonId.TRASH to false,
            LogicalButtonId.SPACE to true,
            LogicalButtonId.PAUSE to false,
            LogicalButtonId.ENTER to true,
        )

        /** Reprocess staging — pause/trash visible, others gone. */
        private val expectedReprocessStaging = mapOf(
            LogicalButtonId.RECORD to true,    // send-staging button
            LogicalButtonId.RESEND to false,
            LogicalButtonId.BACKSPACE to true,
            LogicalButtonId.AUDIO_FOCUS to false,
            LogicalButtonId.WIDGET_TOGGLE to false,
            LogicalButtonId.TRASH to true,     // cancel-staging
            LogicalButtonId.SPACE to true,
            LogicalButtonId.PAUSE to true,     // visible but disabled (alpha 0.4)
            LogicalButtonId.ENTER to true,
        )

        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun cases(): List<Array<Any>> = listOf(
            // ── KEYBOARD_TWO_ROW (5 states) ──────────────────────────
            row("TWO_ROW + idle (no audio)", { it.KEYBOARD_TWO_ROW }, idleNoAudio(), expectedTwoRowIdleNoAudio),
            row("TWO_ROW + idle (with audio)", { it.KEYBOARD_TWO_ROW }, idleWithAudio(), expectedTwoRowIdleWithAudio),
            row("TWO_ROW + recording", { it.KEYBOARD_TWO_ROW }, recordingActive(), expectedTwoRowRecording),
            row("TWO_ROW + pipeline-running (cross-mode)", { it.KEYBOARD_TWO_ROW }, pipelineRunning(),
                mapOf(
                    // forKeyboard would pick SEND_MODE — but if a render-tick lands the
                    // pipeline state on the TWO_ROW mode (race / stale), what does each
                    // slot say? Per Spec 2 §8.7 / §8.5 truth-tables: predResendVisible
                    // returns false (pipeline != Idle), predTrashVisible / predPauseVisible
                    // return false (recording == Idle, staging is false), record stays true.
                    LogicalButtonId.RECORD to true,
                    LogicalButtonId.RESEND to false,
                    LogicalButtonId.BACKSPACE to true,
                    LogicalButtonId.AUDIO_FOCUS to false,
                    LogicalButtonId.WIDGET_TOGGLE to true,    // viewMode = KEYBOARD
                    LogicalButtonId.TRASH to false,
                    LogicalButtonId.SPACE to true,
                    LogicalButtonId.PAUSE to false,
                    LogicalButtonId.ENTER to true,
                ),
            ),
            row("TWO_ROW + staging (cross-mode)", { it.KEYBOARD_TWO_ROW }, reprocessStaging(),
                mapOf(
                    LogicalButtonId.RECORD to true,
                    LogicalButtonId.RESEND to false,
                    LogicalButtonId.BACKSPACE to true,
                    LogicalButtonId.AUDIO_FOCUS to false,
                    LogicalButtonId.WIDGET_TOGGLE to true,
                    LogicalButtonId.TRASH to true,    // staging triggers predTrashVisible
                    LogicalButtonId.SPACE to true,
                    LogicalButtonId.PAUSE to true,
                    LogicalButtonId.ENTER to true,
                ),
            ),
            // ── KEYBOARD_SINGLE_ROW (5 states) ───────────────────────
            row("SINGLE_ROW + idle (no audio)", { it.KEYBOARD_SINGLE_ROW }, idleNoAudio(singleRow = true), expectedSingleRowIdleNoAudio),
            row("SINGLE_ROW + idle (with audio)", { it.KEYBOARD_SINGLE_ROW }, idleWithAudio(singleRow = true), expectedSingleRowIdleWithAudio),
            row("SINGLE_ROW + recording", { it.KEYBOARD_SINGLE_ROW }, recordingActive(singleRow = true), expectedSingleRowRecording),
            row("SINGLE_ROW + pipeline-running (cross-mode)", { it.KEYBOARD_SINGLE_ROW }, pipelineRunning(singleRow = true),
                expectedSingleRowIdleNoAudio + mapOf(LogicalButtonId.RESEND to false),
            ),
            row("SINGLE_ROW + staging (cross-mode)", { it.KEYBOARD_SINGLE_ROW }, reprocessStaging(),
                mapOf(
                    LogicalButtonId.RECORD to true,
                    LogicalButtonId.RESEND to false,
                    LogicalButtonId.BACKSPACE to true,
                    LogicalButtonId.AUDIO_FOCUS to true,
                    LogicalButtonId.WIDGET_TOGGLE to true,
                    LogicalButtonId.TRASH to true,
                    LogicalButtonId.SPACE to true,
                    LogicalButtonId.PAUSE to true,
                    LogicalButtonId.ENTER to true,
                ),
            ),
            // ── KEYBOARD_TWO_ROW_SEND_MODE (5 states — most hardcoded false) ─
            row("TWO_ROW_SEND + idle (cross-mode)", { it.KEYBOARD_TWO_ROW_SEND_MODE }, idleNoAudio(), expectedTwoRowSendMode),
            row("TWO_ROW_SEND + idle-audio (cross-mode)", { it.KEYBOARD_TWO_ROW_SEND_MODE }, idleWithAudio(), expectedTwoRowSendMode),
            row("TWO_ROW_SEND + recording (cross-mode)", { it.KEYBOARD_TWO_ROW_SEND_MODE }, recordingActive(), expectedTwoRowSendMode),
            row("TWO_ROW_SEND + pipeline-running", { it.KEYBOARD_TWO_ROW_SEND_MODE }, pipelineRunning(), expectedTwoRowSendMode),
            row("TWO_ROW_SEND + staging (cross-mode)", { it.KEYBOARD_TWO_ROW_SEND_MODE }, reprocessStaging(), expectedTwoRowSendMode),
            // ── KEYBOARD_SINGLE_ROW_SEND_MODE (5 states) ─────────────
            row("SINGLE_ROW_SEND + idle (cross-mode)", { it.KEYBOARD_SINGLE_ROW_SEND_MODE }, idleNoAudio(singleRow = true), expectedSingleRowSendMode),
            row("SINGLE_ROW_SEND + idle-audio (cross-mode)", { it.KEYBOARD_SINGLE_ROW_SEND_MODE }, idleWithAudio(singleRow = true), expectedSingleRowSendMode),
            row("SINGLE_ROW_SEND + recording (cross-mode)", { it.KEYBOARD_SINGLE_ROW_SEND_MODE }, recordingActive(singleRow = true), expectedSingleRowSendMode),
            row("SINGLE_ROW_SEND + pipeline-running", { it.KEYBOARD_SINGLE_ROW_SEND_MODE }, pipelineRunning(singleRow = true), expectedSingleRowSendMode),
            row("SINGLE_ROW_SEND + staging (cross-mode)", { it.KEYBOARD_SINGLE_ROW_SEND_MODE }, reprocessStaging(), expectedSingleRowSendMode),
            // ── KEYBOARD_REPROCESS_STAGING (5 states) ────────────────
            row("STAGING + idle (cross-mode)", { it.KEYBOARD_REPROCESS_STAGING }, idleNoAudio(), expectedReprocessStaging),
            row("STAGING + idle-audio (cross-mode)", { it.KEYBOARD_REPROCESS_STAGING }, idleWithAudio(), expectedReprocessStaging),
            row("STAGING + recording (cross-mode)", { it.KEYBOARD_REPROCESS_STAGING }, recordingActive(), expectedReprocessStaging),
            row("STAGING + pipeline-running (cross-mode)", { it.KEYBOARD_REPROCESS_STAGING }, pipelineRunning(), expectedReprocessStaging),
            row("STAGING + staging", { it.KEYBOARD_REPROCESS_STAGING }, reprocessStaging(), expectedReprocessStaging),
        )

        private fun row(
            name: String,
            mode: (LayoutCatalog) -> LayoutMode,
            state: DictateUiState,
            expected: Map<LogicalButtonId, Boolean>,
        ): Array<Any> = arrayOf(name, mode, state, expected)
    }
}

/**
 * Stand-alone predicate tests for the four central helpers in
 * [net.devemperor.dictate.state.layout.LayoutPredicates]. These run
 * outside the parameterised matrix so a regression on the helper itself
 * names the helper, not a per-mode case.
 */
class LayoutPredicatesTest {

    @Test
    fun `isResendVisible true only when all four conditions hold`() {
        val base = DictateUiState.initial().copy(
            recording = RecordingState.Idle,
            pipeline = PipelineUiState.Idle,
            resend = ResendState(lastAudioExists = true, resendEnabled = true),
        )
        assertEquals(true, isResendVisible(base))
        assertEquals(false, isResendVisible(base.copy(resend = base.resend.copy(lastAudioExists = false))))
        assertEquals(false, isResendVisible(base.copy(resend = base.resend.copy(resendEnabled = false))))
        assertEquals(false, isResendVisible(base.copy(
            recording = RecordingState.Active(useBluetooth = false, audioFile = stubAudioFile()),
        )))
        assertEquals(false, isResendVisible(base.copy(pipeline = PipelineUiState.Preparing("s1"))))
    }

    @Test
    fun `isResendVisible does NOT read resendCooldown (forbidden pattern j)`() {
        // Spec 2 §8.5 architecture-note: cooldown must NOT gate visibility.
        val base = DictateUiState.initial().copy(
            recording = RecordingState.Idle,
            pipeline = PipelineUiState.Idle,
            resend = ResendState(lastAudioExists = true, resendEnabled = true, resendCooldown = true),
        )
        assertEquals(
            "Cooldown-true must leave isResendVisible unchanged (Spec 2 bug #3b fix)",
            true, isResendVisible(base),
        )
    }

    @Test
    fun `isTrashVisible true for recording-active and staging`() {
        val recording = DictateUiState.initial().copy(
            recording = RecordingState.Active(useBluetooth = false, audioFile = stubAudioFile()),
        )
        val paused = DictateUiState.initial().copy(
            recording = RecordingState.Paused(useBluetooth = false, audioFile = stubAudioFile()),
        )
        val staging = DictateUiState.initial().copy(
            pipeline = PipelineUiState.ReprocessStaging("s1", "transcript"),
        )
        val preparing = DictateUiState.initial().copy(
            recording = RecordingState.Preparing(useBluetooth = false, audioFile = stubAudioFile()),
        )
        assertEquals(true, isTrashVisible(recording))
        assertEquals(true, isTrashVisible(paused))
        assertEquals(true, isTrashVisible(staging))
        // Preparing is NOT active-or-paused (Spec 1 KDoc on isActiveOrPaused).
        assertEquals(false, isTrashVisible(preparing))
    }

    @Test
    fun `isPauseVisible mirrors isTrashVisible`() {
        // Same truth table — kept as a separate helper for callsite-readability
        // (see Predicates.kt KDoc).
        val cases = listOf(
            DictateUiState.initial(),
            DictateUiState.initial().copy(
                recording = RecordingState.Active(useBluetooth = false, audioFile = stubAudioFile()),
            ),
            DictateUiState.initial().copy(
                pipeline = PipelineUiState.ReprocessStaging("s1", "transcript"),
            ),
        )
        cases.forEach { state ->
            assertEquals(
                "isTrashVisible vs isPauseVisible drifted for $state",
                isTrashVisible(state), isPauseVisible(state),
            )
        }
    }

    @Test
    fun `isWidgetToggleVisible true only when viewMode is KEYBOARD`() {
        val keyboard = DictateUiState.initial().copy(viewMode = net.devemperor.dictate.state.ViewMode.KEYBOARD)
        val widget = DictateUiState.initial().copy(viewMode = net.devemperor.dictate.state.ViewMode.WIDGET)
        val hover = DictateUiState.initial().copy(viewMode = net.devemperor.dictate.state.ViewMode.HOVER)
        assertEquals(true, isWidgetToggleVisible(keyboard))
        assertEquals(false, isWidgetToggleVisible(widget))
        assertEquals(false, isWidgetToggleVisible(hover))
    }
}

// Suppress: `caseName` parameter is consumed by JUnit's @Parameters(name = "{0}")
// reflective naming — the compiler doesn't see that use.
@Suppress("unused") private val unusedAnchor = Unit
@Suppress("unused") private val unusedAnchor2: (InsertionTarget) -> Unit = { _ -> }
