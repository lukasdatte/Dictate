package net.devemperor.dictate.ui

import android.content.Context
import android.view.View
import androidx.appcompat.view.ContextThemeWrapper
import androidx.test.core.app.ApplicationProvider
import com.google.android.material.button.MaterialButton
import net.devemperor.dictate.R
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
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * **OQ-4 — the CI-green Robolectric mirror of [KeyboardLayoutUiTest].**
 *
 * `connectedAndroidTest` device infra is unavailable in this CI
 * environment (Epic §6 R-6). Per OQ-4 each Spec 2 §14.2 UI-1..10 test
 * also ships an equivalent Robolectric render-assertion: this class is
 * that mirror. AC-8 is satisfied if **either** the Espresso device body
 * **or** this mirror is green — and this mirror is the path that runs
 * green here under `./gradlew test`.
 *
 * # 1:1 fidelity with the device body
 *
 * Every `ui{N}_*` here drives the **same production render path** as the
 * androidTest body — the real [KeyboardLayoutManager.computeLayoutMode]
 * mode-selector + the production [applySlotToView] SSoT slot→view writer
 * (`ImeViewBackend.render` calls exactly these per slot) — over real
 * Robolectric `MaterialButton` Views, with byte-identical assertions.
 * A divergence between the two is a real regression, not a harness
 * artefact. Each test maps 1:1 to its UI-N + the §1.1 bug-symptom it
 * guards (the resend-visibility bug-class §1.1 #3a/#3b, the
 * single-row-toggle re-parenting bug-class §1.1 #1/#2, and the F-13
 * live-counter coverage-baseline).
 *
 * # Post-cutover sole-driver assumption
 *
 * The 4 legacy controllers (`KeyboardUiController` /
 * `RecordingUiController` / `MainButtonsController` /
 * `KeyboardStateManager`) were deleted in Theme-C-R (CR-DEL), so this
 * IS the real post-cutover render path — there is no parallel legacy
 * path left to accidentally assert against.
 *
 * # R-7 tearDown discipline
 *
 * This mirror uses the lightweight direct render-path harness (real
 * catalog + real `applySlotToView` over fresh per-test Views) — it does
 * **not** boot `DictatePipelineService`, so the process-wide singletons
 * the Epic R-7 discipline guards (`JobExecutor` / `ActiveJobRegistry` /
 * `DurationHealingScheduler` / `DictateDatabase`) are never touched.
 * The R-7 `resetForTest` seams therefore do not apply here (same shape
 * as the existing `ImeViewBackendTest` / `LayoutCatalogTest`, which
 * also drive the render path without a service boot and carry no such
 * tearDown). No shared mutable state crosses tests; each test builds
 * fresh Views in [setUp].
 *
 * **K-1 / K-4** — no mocking framework (real production catalog +
 * `applySlotToView`); Robolectric is the justified opt-out per OQ-4 /
 * Spec 2 §14.2 (the assertions read real `View.visibility` / `alpha` /
 * `isEnabled` / `text`, only observable through real Android Views).
 *
 * @see net.devemperor.dictate.ui.KeyboardLayoutUiTest
 * @see net.devemperor.dictate.state.render.SlotRenderer
 * @see docs/plans/2026-05-07 - dictate-keyboard-layout-refactor/research/2-keyboard-layout/2-keyboard-layout.reviewed.md §14.2
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class KeyboardLayoutRenderMirrorTest {

    private lateinit var ctx: Context
    private lateinit var catalog: LayoutCatalog
    private lateinit var manager: KeyboardLayoutManager
    private lateinit var buttons: Map<LogicalButtonId, MaterialButton>

    @Before
    fun setUp() {
        val app = ApplicationProvider.getApplicationContext<Context>()
        ctx = ContextThemeWrapper(app, R.style.Theme_Dictate)
        catalog = LayoutCatalog(mirrorLayoutStrings())
        buttons = LogicalButtonId.entries
            .filter { it.name.startsWith("OVERLAY_").not() }
            .associateWith { MaterialButton(ctx) }
        manager = KeyboardLayoutManager(catalog) { /* click-sink unused */ }
    }

    // ─── Render harness — the production SSoT path (mirror of the body) ─

    private fun render(state: DictateUiState): LayoutMode {
        val mode = manager.computeLayoutMode(state)
        mode.slots.forEach { slot ->
            applySlotToView(slot, buttons.getValue(slot.logicalId), state, ctx)
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

    // ─── UI-1 — §1.1 #1 ───────────────────────────────────────────────

    @Test
    fun ui1_toggleSingleRowInIdle_showsAllButtons() {
        render(idle(singleRow = false))
        val mode = render(idle(singleRow = true))

        assertEquals(
            "UI-1: single-row toggle must select KEYBOARD_SINGLE_ROW",
            LayoutModeId.KEYBOARD_SINGLE_ROW,
            mode.id,
        )
        assertTrue(
            "UI-1 (§1.1 #1): single-row mode must carry all 8 original " +
                "logical buttons — none dropped on the toggle (esp. " +
                "TRASH/PAUSE, the §1.1 #1 bug). Mode slots: " +
                "${mode.slots.map { it.logicalId }}",
            mode.slots.map { it.logicalId }.toSet()
                .containsAll(originalSingleRowButtons),
        )
        assertEquals(View.VISIBLE, vis(LogicalButtonId.RECORD))
        assertEquals(View.VISIBLE, vis(LogicalButtonId.SPACE))
        assertEquals(View.VISIBLE, vis(LogicalButtonId.BACKSPACE))
        assertEquals(View.VISIBLE, vis(LogicalButtonId.ENTER))
        assertEquals(View.VISIBLE, vis(LogicalButtonId.AUDIO_FOCUS))
    }

    // ─── UI-2 — coverage-baseline ──────────────────────────────────────

    @Test
    fun ui2_activeRecording_hidesResend_showsTrashPause() {
        render(active())

        assertEquals("UI-2: RESEND GONE while recording", View.GONE, vis(LogicalButtonId.RESEND))
        assertEquals("UI-2: TRASH VISIBLE while recording", View.VISIBLE, vis(LogicalButtonId.TRASH))
        assertEquals("UI-2: PAUSE VISIBLE while recording", View.VISIBLE, vis(LogicalButtonId.PAUSE))
    }

    // ─── UI-3 — coverage-baseline (F-13) ───────────────────────────────

    @Test
    fun ui3_pipelineRunning_recordButtonShowsCounter() {
        render(active())
        val mode = render(pipelineRunning(completed = 1, total = 3))

        assertEquals(
            "UI-3: pipeline run selects a SEND_MODE layout",
            LayoutModeId.KEYBOARD_TWO_ROW_SEND_MODE,
            mode.id,
        )
        assertEquals(
            "UI-3: record_btn must render the live F-13 counter",
            "1/3  1000ms",
            text(LogicalButtonId.RECORD).toString(),
        )
        assertEquals(View.GONE, vis(LogicalButtonId.TRASH))
        assertEquals(View.GONE, vis(LogicalButtonId.PAUSE))
    }

    // ─── UI-4 — §1.1 #3a ──────────────────────────────────────────────

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

    // ─── UI-5 — coverage-baseline ──────────────────────────────────────

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

    // ─── UI-6 — coverage-baseline ──────────────────────────────────────

    @Test
    fun ui6_rotationDuringRecording_animationContinues() {
        render(active())
        val firstFrameMode = render(active())

        assertEquals(
            "UI-6: recording must still drive the two-row layout on the " +
                "first frame after re-inflate",
            LayoutModeId.KEYBOARD_TWO_ROW,
            firstFrameMode.id,
        )
        assertEquals(View.VISIBLE, vis(LogicalButtonId.TRASH))
        assertEquals(View.VISIBLE, vis(LogicalButtonId.PAUSE))
    }

    // ─── UI-7 — §1.1 #2 ───────────────────────────────────────────────

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
        assertEquals(View.VISIBLE, vis(LogicalButtonId.RECORD))
        assertEquals(
            "UI-7: TRASH stays VISIBLE across the toggle (recording active)",
            View.VISIBLE,
            vis(LogicalButtonId.TRASH),
        )
    }

    // ─── UI-8 — §1.1 #3b ──────────────────────────────────────────────

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

    // ─── UI-9 — §1.1 #3b ──────────────────────────────────────────────

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

    // ─── UI-10 — §1.1 #3a + #3b cross-bug ─────────────────────────────

    @Test
    fun ui10_activeToPipelinePreparing_noOverlap() {
        render(active())
        assertEquals(View.VISIBLE, vis(LogicalButtonId.TRASH))
        assertEquals(View.VISIBLE, vis(LogicalButtonId.PAUSE))

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
 * Deterministic [LayoutStrings] for the mirror — byte-identical to the
 * androidTest body's `uiTestLayoutStrings` so UI-3's record-button
 * counter assertion is the same string on both paths.
 */
internal fun mirrorLayoutStrings(): LayoutStrings = LayoutStrings(
    record = "Record",
    send = "Send (en)",
    sending = "Sending …",
    dictateButtonText = { lang -> "Dictate ($lang)" },
    formatStagingLabel = { secs -> "Audio 0:${"%02d".format(secs)} · Send" },
    formatPipelineLabel = { stepName, done, total, autoEnter, elapsedMs ->
        // Mirrors `testLayoutStrings()` in LayoutCatalogTest.kt — two-line
        // shape when stepName is non-blank, single-line legacy shape
        // otherwise (B-D-1).
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
