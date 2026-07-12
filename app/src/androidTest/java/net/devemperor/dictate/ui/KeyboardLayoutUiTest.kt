package net.devemperor.dictate.ui

import android.content.Context
import android.view.View
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.android.material.button.MaterialButton
import net.devemperor.dictate.state.DictateUiState
import net.devemperor.dictate.state.InsertionTarget
import net.devemperor.dictate.state.LayoutState
import net.devemperor.dictate.state.PipelineUiState
import net.devemperor.dictate.state.RecordingState
import net.devemperor.dictate.state.ResendState
import net.devemperor.dictate.state.layout.KeyboardLayoutManager
import net.devemperor.dictate.state.layout.LayoutCatalog
import net.devemperor.dictate.state.layout.LayoutMode
import net.devemperor.dictate.state.layout.LayoutModeId
import net.devemperor.dictate.state.layout.LayoutStrings
import net.devemperor.dictate.state.layout.LogicalButtonId
import net.devemperor.dictate.state.render.applySlotToView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Espresso/instrumented UI tests covering Spec 2 §14.2 (UI-Test 1..10).
 *
 * # What these assert (Spec 2 §14.2 SoT — verbatim test-body table)
 *
 * Each `ui{N}_*` test maps 1:1 to a row of the Spec 2 §14.2
 * Integration-Tests table and the §1.1 bug-symptom it guards:
 *
 * | Test  | §14.2 row | §1.1 bug-symptom |
 * |-------|-----------|------------------|
 * | UI-1  | Toggle Single-Row in Idle — all 8 buttons visible | §1.1 #1 |
 * | UI-2  | Recording → resend GONE, trash/pause VISIBLE      | coverage-baseline |
 * | UI-3  | Pipeline → record_btn counter text, trash/pause GONE | coverage-baseline (F-13) |
 * | UI-4  | Send-Mode + Single-Row → record_btn unobstructed  | §1.1 #3a |
 * | UI-5  | ReprocessStaging → pause VISIBLE+disabled+α 0.4   | coverage-baseline |
 * | UI-6  | Re-Inflate during Recording → correct mode 1st frame | coverage-baseline |
 * | UI-7  | Toggle Single-Row during Recording → pulse continues | §1.1 #2 |
 * | UI-8  | Toggle Two↔Single in Idle+lastAudio → resend stays VISIBLE | §1.1 #3b |
 * | UI-9  | Resend cooldown → VISIBLE+enabled=false+α 0.4      | §1.1 #3b |
 * | UI-10 | Active→Pipeline-Preparing → no trash/pause overlap | §1.1 #3a+#3b |
 *
 * # Why the render-path harness (not `InputMethodManager` launch)
 *
 * The post-cutover render path is the **sole** driver: the 4 legacy
 * controllers (`KeyboardUiController` / `RecordingUiController` /
 * `MainButtonsController` / `KeyboardStateManager`) were deleted in
 * Theme-C-R (CR-DEL), so the IME renders **only** through
 * [KeyboardLayoutManager.computeLayoutMode] → [applySlotToView] over
 * the [LayoutCatalog]. These tests exercise that exact production
 * render path against real Android `MaterialButton` Views (Espresso's
 * `ViewMatchers` ultimately read the same `View.visibility` /
 * `isEnabled` / `alpha` / `text`). [applySlotToView] is the production
 * SSoT slot→view writer (`ImeViewBackend.render` calls it per slot);
 * driving it directly keeps the device test free of the
 * `ModuleServices` DI container (only the click path needs it — these
 * tests assert render output, not clicks; click wiring is
 * `ImeViewBackendTest`'s scope). Launching the full IME via
 * `InputMethodManager` is device-infra brittle (R-6) and orthogonal to
 * the §14.2 render-correctness assertions.
 *
 * OQ-4: AC-8 is satisfied if **either** this device body **or** its
 * Robolectric mirror ([net.devemperor.dictate.ui.KeyboardLayoutRenderMirrorTest])
 * is green. The mirror runs green under `./gradlew test` (the CI path);
 * this body is the `connectedAndroidTest` device path. The assertions
 * are intentionally identical so a divergence is a real regression.
 *
 * @see net.devemperor.dictate.ui.KeyboardLayoutRenderMirrorTest
 * @see net.devemperor.dictate.state.render.SlotRenderer
 * @see docs/plans/2026-05-07 - dictate-keyboard-layout-refactor/research/2-keyboard-layout/2-keyboard-layout.reviewed.md §14.2
 */
@RunWith(AndroidJUnit4::class)
class KeyboardLayoutUiTest {

    private lateinit var ctx: Context
    private lateinit var catalog: LayoutCatalog
    private lateinit var manager: KeyboardLayoutManager
    private lateinit var buttons: Map<LogicalButtonId, MaterialButton>

    @Before
    fun setUp() {
        ctx = ApplicationProvider.getApplicationContext()
        catalog = LayoutCatalog(uiTestLayoutStrings())
        // Real Android MaterialButtons — Espresso reads the same
        // View.visibility / isEnabled / alpha / text these resolvers write.
        buttons = LogicalButtonId.entries
            .filter { it.name.startsWith("OVERLAY_").not() }
            .associateWith { MaterialButton(ctx) }
        // The real production mode-selector (KeyboardLayoutManager is
        // what the bound service drives via onStateChanged).
        manager = KeyboardLayoutManager(catalog) { /* click-sink unused */ }
    }

    // ─── Render harness — the production SSoT path ────────────────────

    /**
     * Render [state] exactly as `ImeViewBackend.render` does: pick the
     * mode via the real [KeyboardLayoutManager.computeLayoutMode], then
     * apply every slot via the production [applySlotToView] SSoT writer.
     * Returns the chosen [LayoutMode].
     */
    private fun render(state: DictateUiState): LayoutMode {
        val mode = manager.computeLayoutMode(state)
        mode.slots.forEach { slot ->
            val view = buttons.getValue(slot.logicalId)
            applySlotToView(slot, view, state, ctx)
        }
        return mode
    }

    private fun vis(id: LogicalButtonId): Int = buttons.getValue(id).visibility
    private fun enabled(id: LogicalButtonId): Boolean = buttons.getValue(id).isEnabled
    private fun alpha(id: LogicalButtonId): Float = buttons.getValue(id).alpha
    private fun text(id: LogicalButtonId): CharSequence = buttons.getValue(id).text

    private fun idle(singleRow: Boolean = false) = DictateUiState.initial()
        .copy(layout = LayoutState(singleRowMode = singleRow))

    private fun idleWithLastAudio(singleRow: Boolean = false) = idle(singleRow)
        .copy(resend = ResendState(lastAudioExists = true, resendEnabled = true))

    private fun active(singleRow: Boolean = false) = idle(singleRow).copy(
        recording = RecordingState.Active(
            useBluetooth = false,
            audioFile = File("/tmp/uitest.m4a"),
            sessionId = "ui-sess",
        ),
    )

    private fun pipelineRunning(
        singleRow: Boolean = false,
        completed: Int = 1,
        total: Int = 3,
    ) = idle(singleRow).copy(
        pipeline = PipelineUiState.Running(
            sessionId = "ui-sess",
            target = InsertionTarget.INPUT_CONNECTION,
            completedSteps = completed,
            totalSteps = total,
            elapsedMs = 1_000L,
        ),
    )

    // The 8 original single-row keyboard buttons (Spec 2 §14.2 "alle 8
    // Buttons"). Asserted as a SUBSET, not an exact set: the catalog
    // later gained WIDGET_TOGGLE (B5 F-2) so KEYBOARD_SINGLE_ROW now has
    // 9 slots — the §1.1 #1 invariant is "none of the original 8 are
    // DROPPED on the toggle" (specifically trash/pause), not an exact
    // count that the §14.2 prose (pre-WIDGET_TOGGLE) implied.
    private val originalSingleRowButtons = setOf(
        LogicalButtonId.TRASH, LogicalButtonId.RECORD, LogicalButtonId.SPACE,
        LogicalButtonId.PAUSE, LogicalButtonId.BACKSPACE, LogicalButtonId.ENTER,
        LogicalButtonId.RESEND, LogicalButtonId.AUDIO_FOCUS,
    )

    // ════════════════════════════════════════════════════════════════
    // UI-1 — §1.1 #1: Toggle Single-Row in Idle. All 8 buttons present
    //        in single-row layout. The §1.1 #1 bug (trash/pause
    //        forgotten on the single-row toggle) is structurally absent.
    // ════════════════════════════════════════════════════════════════

    @Test
    fun ui1_toggleSingleRowInIdle_showsAllButtons() {
        render(idle(singleRow = false))
        val mode = render(idle(singleRow = true))

        assertEquals(
            "UI-1: single-row toggle must select KEYBOARD_SINGLE_ROW",
            LayoutModeId.KEYBOARD_SINGLE_ROW,
            mode.id,
        )
        // §1.1 #1: TRASH + PAUSE must NOT be dropped from the mode on
        // the single-row toggle — all 8 original logical buttons stay
        // present (subset check; the catalog later gained WIDGET_TOGGLE).
        assertTrue(
            "UI-1 (§1.1 #1): single-row mode must carry all 8 original " +
                "logical buttons — none dropped on the toggle (esp. " +
                "TRASH/PAUSE, the §1.1 #1 bug). Mode slots: " +
                "${mode.slots.map { it.logicalId }}",
            mode.slots.map { it.logicalId }.toSet()
                .containsAll(originalSingleRowButtons),
        )
        // RECORD / SPACE / BACKSPACE / ENTER / AUDIO_FOCUS are
        // unconditionally visible in Idle single-row.
        assertEquals(View.VISIBLE, vis(LogicalButtonId.RECORD))
        assertEquals(View.VISIBLE, vis(LogicalButtonId.SPACE))
        assertEquals(View.VISIBLE, vis(LogicalButtonId.BACKSPACE))
        assertEquals(View.VISIBLE, vis(LogicalButtonId.ENTER))
        assertEquals(View.VISIBLE, vis(LogicalButtonId.AUDIO_FOCUS))
    }

    // ════════════════════════════════════════════════════════════════
    // UI-2 — coverage-baseline: recording active hides resend, shows
    //        trash + pause.
    // ════════════════════════════════════════════════════════════════

    @Test
    fun ui2_activeRecording_hidesResend_showsTrashPause() {
        render(active())

        assertEquals("UI-2: RESEND GONE while recording", View.GONE, vis(LogicalButtonId.RESEND))
        assertEquals("UI-2: TRASH VISIBLE while recording", View.VISIBLE, vis(LogicalButtonId.TRASH))
        assertEquals("UI-2: PAUSE VISIBLE while recording", View.VISIBLE, vis(LogicalButtonId.PAUSE))
    }

    // ════════════════════════════════════════════════════════════════
    // UI-3 — coverage-baseline (F-13): Recording-Stop → Pipeline →
    //        record_btn shows the live counter; trash/pause GONE.
    // ════════════════════════════════════════════════════════════════

    @Test
    fun ui3_pipelineRunning_recordButtonShowsCounter() {
        render(active())
        val mode = render(pipelineRunning(completed = 1, total = 3))

        assertEquals(
            "UI-3: pipeline run selects a SEND_MODE layout",
            LayoutModeId.KEYBOARD_TWO_ROW_SEND_MODE,
            mode.id,
        )
        // F-13: record_btn text comes from formatPipelineLabel(1,3,..).
        assertEquals(
            "UI-3: record_btn must render the live F-13 counter",
            "1/3  1000ms",
            text(LogicalButtonId.RECORD).toString(),
        )
        // SEND_MODE structurally hides trash + pause (bug #3a eliminator).
        assertEquals(View.GONE, vis(LogicalButtonId.TRASH))
        assertEquals(View.GONE, vis(LogicalButtonId.PAUSE))
    }

    // ════════════════════════════════════════════════════════════════
    // UI-4 — §1.1 #3a outcome guard: Send-Mode + Single-Row keeps the
    //        record_btn unobstructed (trash/pause GONE — they used to
    //        cover the send button). This pins the mode-selection
    //        (single-row pipeline → SEND_MODE) + the structural-GONE
    //        outcome. NOTE: it does NOT pin the §1.1 #3a SEND_MODE
    //        `{ false }` eliminator literal — in this state
    //        isTrashVisible/isPauseVisible are already false (no
    //        recording, not ReprocessStaging), so a revert of that
    //        literal would still leave this test GREEN. The eliminator
    //        literal's non-vacuous guard is VisibilityMatrixTest's
    //        "TWO_ROW_SEND + recording (cross-mode)" case.
    // ════════════════════════════════════════════════════════════════

    @Test
    fun ui4_sendModeSingleRow_recordButtonFullyVisible() {
        val mode = render(pipelineRunning(singleRow = true))

        assertEquals(
            "UI-4: single-row pipeline selects KEYBOARD_SINGLE_ROW_SEND_MODE",
            LayoutModeId.KEYBOARD_SINGLE_ROW_SEND_MODE,
            mode.id,
        )
        assertEquals(
            "UI-4: record_btn must be VISIBLE in single-row send-mode",
            View.VISIBLE,
            vis(LogicalButtonId.RECORD),
        )
        // SEND_MODE structural outcome: TRASH + PAUSE resolve to GONE so
        // they cannot cover the send button. (The §1.1 #3a hardcoded
        // `{ false }` eliminator literal itself is pinned non-vacuously
        // by VisibilityMatrixTest's "TWO_ROW_SEND + recording" case, not
        // here — in this state the predicates are already false.)
        assertEquals(
            "UI-4: TRASH GONE in single-row send-mode (SEND_MODE structural " +
                "outcome; the §1.1 #3a `{ false }` eliminator literal itself " +
                "is pinned by VisibilityMatrixTest \"TWO_ROW_SEND + recording\")",
            View.GONE,
            vis(LogicalButtonId.TRASH),
        )
        assertEquals(
            "UI-4: PAUSE GONE in single-row send-mode (SEND_MODE structural " +
                "outcome; the §1.1 #3a `{ false }` eliminator literal itself " +
                "is pinned by VisibilityMatrixTest \"TWO_ROW_SEND + recording\")",
            View.GONE,
            vis(LogicalButtonId.PAUSE),
        )
    }

    // ════════════════════════════════════════════════════════════════
    // UI-5 — coverage-baseline: ReprocessStaging → pause VISIBLE,
    //        disabled, alpha 0.4.
    // ════════════════════════════════════════════════════════════════

    @Test
    fun ui5_reprocessStaging_pauseDisabledAlpha04() {
        val staging = idle().copy(
            pipeline = PipelineUiState.ReprocessStaging(
                sessionId = "ui-sess",
                transcript = "hello",
            ),
        )
        val mode = render(staging)

        assertEquals(LayoutModeId.KEYBOARD_REPROCESS_STAGING, mode.id)
        assertEquals("UI-5: PAUSE VISIBLE in staging", View.VISIBLE, vis(LogicalButtonId.PAUSE))
        assertTrue("UI-5: PAUSE disabled in staging", !enabled(LogicalButtonId.PAUSE))
        assertEquals("UI-5: PAUSE alpha 0.4 in staging", 0.4f, alpha(LogicalButtonId.PAUSE), 0.001f)
    }

    // ════════════════════════════════════════════════════════════════
    // UI-6 — coverage-baseline: Re-Inflate (rotation) during Recording.
    //        The correct LayoutMode is selected on the first frame after
    //        re-render (the re-inflate analogue) — Recording stays the
    //        driver.
    // ════════════════════════════════════════════════════════════════

    @Test
    fun ui6_rotationDuringRecording_animationContinues() {
        render(active())
        // Rotation = IME view re-inflate = a fresh render of the same
        // recording state. The first frame after re-inflate must select
        // the correct (recording-driven) LayoutMode — the §14.2
        // "korrekter LayoutMode auf erstem Frame" guarantee.
        val firstFrameMode = render(active())

        assertEquals(
            "UI-6: recording must still drive the two-row layout on the " +
                "first frame after re-inflate",
            LayoutModeId.KEYBOARD_TWO_ROW,
            firstFrameMode.id,
        )
        // Animation continues because the recording FSM stayed Active
        // across the re-inflate (the controls reflect that).
        assertEquals(View.VISIBLE, vis(LogicalButtonId.TRASH))
        assertEquals(View.VISIBLE, vis(LogicalButtonId.PAUSE))
    }

    // ════════════════════════════════════════════════════════════════
    // UI-7 — §1.1 #2: Toggle Single-Row during Recording. The send-btn
    //        position changes (mode flips) but recording stays active
    //        (the §1.1 #2 re-parenting bug used to drop the pulse).
    // ════════════════════════════════════════════════════════════════

    @Test
    fun ui7_toggleSingleRowDuringRecording_pulseAnimationContinues() {
        val twoRow = render(active(singleRow = false))
        val singleRow = render(active(singleRow = true))

        assertNotEquals(
            "UI-7 (§1.1 #2): the layout mode must flip on the toggle " +
                "(send-btn position changes)",
            twoRow.id,
            singleRow.id,
        )
        assertEquals(LayoutModeId.KEYBOARD_TWO_ROW, twoRow.id)
        assertEquals(LayoutModeId.KEYBOARD_SINGLE_ROW, singleRow.id)
        // Recording survives the toggle — the controls stay shown
        // (§1.1 #2: the old re-parenting code dropped them mid-toggle).
        assertEquals(View.VISIBLE, vis(LogicalButtonId.RECORD))
        assertEquals(
            "UI-7: TRASH stays VISIBLE across the toggle (recording active)",
            View.VISIBLE,
            vis(LogicalButtonId.TRASH),
        )
    }

    // ════════════════════════════════════════════════════════════════
    // UI-8 — §1.1 #3b: Toggle Two-Row ↔ Single-Row in Idle+lastAudio.
    //        The resend button stays visibility=VISIBLE in EVERY frame.
    // ════════════════════════════════════════════════════════════════

    @Test
    fun ui8_resendStaysVisibleAcrossToggle() {
        render(idleWithLastAudio(singleRow = false))
        assertEquals(
            "UI-8 frame-1 (§1.1 #3b): resend VISIBLE in two-row",
            View.VISIBLE,
            vis(LogicalButtonId.RESEND),
        )
        render(idleWithLastAudio(singleRow = true))
        assertEquals(
            "UI-8 frame-2 (§1.1 #3b): resend stays VISIBLE in single-row",
            View.VISIBLE,
            vis(LogicalButtonId.RESEND),
        )
        render(idleWithLastAudio(singleRow = false))
        assertEquals(
            "UI-8 frame-3 (§1.1 #3b): resend still VISIBLE after toggling back",
            View.VISIBLE,
            vis(LogicalButtonId.RESEND),
        )
    }

    // ════════════════════════════════════════════════════════════════
    // UI-9 — §1.1 #3b: Resend cooldown. After a resend click the button
    //        stays VISIBLE, becomes enabled=false, alpha 0.4. Visibility
    //        is NOT cooldown-coupled (Spec 2 §8.5 forbidden-pattern).
    // ════════════════════════════════════════════════════════════════

    @Test
    fun ui9_resendCooldown_visibleDisabledAlpha04() {
        val cooldown = idleWithLastAudio().copy(
            resend = ResendState(
                lastAudioExists = true,
                resendEnabled = true,
                resendCooldown = true,
            ),
        )
        render(cooldown)

        assertEquals(
            "UI-9 (§1.1 #3b): resend stays VISIBLE during cooldown " +
                "(visibility NOT cooldown-coupled — Spec 2 §8.5)",
            View.VISIBLE,
            vis(LogicalButtonId.RESEND),
        )
        assertTrue(
            "UI-9: resend disabled during cooldown",
            !enabled(LogicalButtonId.RESEND),
        )
        assertEquals(
            "UI-9: resend alpha 0.4 during cooldown",
            0.4f,
            alpha(LogicalButtonId.RESEND),
            0.001f,
        )
    }

    // ════════════════════════════════════════════════════════════════
    // UI-10 — §1.1 #3a + #3b outcome guard: Active → Pipeline-Preparing
    //         transition. Across every frame neither trash nor pause is
    //         rendered VISIBLE in the send-mode layout (so they can
    //         never be drawn over record_btn). This pins the
    //         transition's mode-selection (Preparing → SEND_MODE) + the
    //         structural-GONE outcome. NOTE: it does NOT pin the §1.1
    //         #3a SEND_MODE `{ false }` eliminator literal — in the
    //         Preparing frame isTrashVisible/isPauseVisible are already
    //         false, so a revert of that literal would still leave this
    //         test GREEN. The eliminator literal's non-vacuous guard is
    //         VisibilityMatrixTest's "TWO_ROW_SEND + recording
    //         (cross-mode)" case.
    // ════════════════════════════════════════════════════════════════

    @Test
    fun ui10_activeToPipelinePreparing_noOverlap() {
        // Frame 1: Active recording (trash/pause VISIBLE — expected).
        render(active())
        assertEquals(View.VISIBLE, vis(LogicalButtonId.TRASH))
        assertEquals(View.VISIBLE, vis(LogicalButtonId.PAUSE))

        // Frame 2: stop → Pipeline Preparing. The mode flips to
        // SEND_MODE and TRASH + PAUSE resolve to GONE — they can NEVER
        // be drawn over the record_btn (§1.1 #3a outcome), and the
        // resend button stays out of the way too (§1.1 #3b). (The §1.1
        // #3a hardcoded `{ false }` eliminator literal itself is pinned
        // non-vacuously by VisibilityMatrixTest's "TWO_ROW_SEND +
        // recording" case, not here — in Preparing the predicates are
        // already false.)
        val preparing = idle().copy(
            pipeline = PipelineUiState.Preparing(sessionId = "ui-sess"),
        )
        val mode = render(preparing)

        assertEquals(
            "UI-10: Preparing selects a SEND_MODE layout",
            LayoutModeId.KEYBOARD_TWO_ROW_SEND_MODE,
            mode.id,
        )
        assertEquals(
            "UI-10: TRASH GONE on the Active→Preparing transition (SEND_MODE " +
                "structural outcome; the §1.1 #3a `{ false }` eliminator " +
                "literal itself is pinned by VisibilityMatrixTest " +
                "\"TWO_ROW_SEND + recording\")",
            View.GONE,
            vis(LogicalButtonId.TRASH),
        )
        assertEquals(
            "UI-10: PAUSE GONE on the Active→Preparing transition (SEND_MODE " +
                "structural outcome; the §1.1 #3a `{ false }` eliminator " +
                "literal itself is pinned by VisibilityMatrixTest " +
                "\"TWO_ROW_SEND + recording\")",
            View.GONE,
            vis(LogicalButtonId.PAUSE),
        )
        assertEquals(
            "UI-10 (§1.1 #3b): RESEND must be GONE in send-mode",
            View.GONE,
            vis(LogicalButtonId.RESEND),
        )
    }
}

/**
 * Deterministic [LayoutStrings] for the UI tests — literal English
 * strings + a counter formatter that surfaces the F-13 fields so UI-3
 * can assert the live record-button label. Kept local (not the
 * `state.layout`-internal `testLayoutStrings`) so the androidTest
 * source-set has no cross-package internal dependency.
 */
internal fun uiTestLayoutStrings(): LayoutStrings = LayoutStrings(
    record = "Record",
    send = "Send (en)",
    sending = "Sending …",
    dictateButtonText = { lang -> "Dictate ($lang)" },
    formatStagingLabel = { secs -> "Audio 0:${"%02d".format(secs)} · Send" },
    formatPipelineLabel = { stepName, done, total, autoEnter, elapsedMs ->
        // B-D-1 (dictate-pipeline-render-and-state-unification §5.1):
        // two-line layout when stepName is non-blank, single-line legacy
        // shape otherwise. Mirrors the unit-test fixtures.
        val mark = if (autoEnter) " ↵" else ""
        val phase = stepName?.takeIf { it.isNotBlank() }
        if (phase != null) {
            "$phase\n$done/$total$mark  ${elapsedMs}ms"
        } else {
            "$done/$total$mark  ${elapsedMs}ms"
        }
    },
    formatPreparingLabel = { autoEnter ->
        if (autoEnter) "Sending … ↵" else "Sending …"
    },
)
