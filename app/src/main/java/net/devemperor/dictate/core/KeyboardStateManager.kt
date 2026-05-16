package net.devemperor.dictate.core

import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.StaggeredGridLayoutManager
import com.google.android.material.button.MaterialButton
import net.devemperor.dictate.core.audit.VisibilityWriteAuditLogger

/**
 * Deterministic visibility calculator for the keyboard UI.
 *
 * Owns only the state that lives nowhere else (contentArea, isSmallMode).
 * Queries recording/pipeline/rewording state via lambdas from their respective managers.
 * Computes all view visibilities from this combined state in [applyVisibility].
 *
 * This eliminates:
 * - infoClVisibilityBeforeQwertz workaround (content area switch handles it)
 * - Hybrid modes like QWERTZ_RECORDING (contentArea=QWERTZ + isRecording()=true)
 * - previousMode tracking
 * - showEmojiPicker() missing isSmallMode guard (small mode auto-closes QWERTZ/Emoji)
 */
/**
 * View handles consumed by the keyboard's visibility/layout machinery.
 *
 * # C15 cleanup (Spec 2 §11.8 5d)
 *
 * The `action_row` / `input_row` legacy row-containers are gone — the
 * MotionLayout refactor (Spec 2 §7.1 / §11.1) flattens all nine buttons
 * into direct MotionLayout children. The five input-row button handles
 * stay (PulseLayout-wrapper + space / backspace / enter / resend /
 * audio-focus) because the legacy
 * [net.devemperor.dictate.core.MainButtonsController] still uses them for
 * theme application + animation glue.
 *
 * The pulse **wrapper** is intentionally exposed (NOT the bare
 * `record_btn`) — moving the inner button alone breaks the
 * [net.devemperor.dictate.widget.PulseLayout] animation by cutting its
 * container reference.
 */
data class KeyboardViews(
    // [mainButtonsClTyped] is the MotionLayout that hosts the nine
    // state-driven buttons (C15: was previously a LinearLayout wrapping
    // action_row + input_row). Typed as the shared `ViewGroup` supertype
    // so the ContentArea visibility axis stays MotionLayout-agnostic and
    // a future re-skin (e.g. switch to a flat ConstraintLayout root)
    // does not require a signature change.
    val mainButtonsClTyped: ViewGroup,
    val editButtonsLl: ConstraintLayout,
    val promptsCl: ConstraintLayout,
    val emojiPickerCl: ConstraintLayout,
    val qwertzContainer: FrameLayout,
    val overlayCharactersLl: LinearLayout,
    val pauseButton: View,
    val trashButton: View,
    val promptRecordingControlsLl: LinearLayout?,
    val promptTrashBtn: View?,
    val promptsRv: RecyclerView?,
    val pipelineProgressLl: View?,
    // The pulse wrapper + four legacy "input-row" button handles. The
    // KeyboardLayoutModeController that previously re-parented these is
    // gone (C15) — they stay only because MainButtonsController +
    // RecordingUiController still resolve them by reference for
    // theme/animation glue.
    val recordPulseLayout: View,
    val spaceButton: MaterialButton,
    val backspaceButton: MaterialButton,
    val enterButton: MaterialButton,
    val resendButton: MaterialButton,
    val audioFocusButtonInRow: MaterialButton
)

class KeyboardStateManager(
    private val views: KeyboardViews,
    // Lambda queries: state lives in the responsible managers
    private val isRecording: () -> Boolean,
    private val isPaused: () -> Boolean,
    private val isPipelineRunning: () -> Boolean,
    private val isRewordingEnabled: () -> Boolean,
    private val onKeepScreenAwakeChanged: (Boolean) -> Unit,
    private val infoBarController: InfoBarController? = null,
    /**
     * Returns `true` iff the prompt area should render the pipeline progress list
     * instead of the regular prompt buttons. Corresponds to
     * `state is PipelineUiState.Running` on the [KeyboardUiController] — NOT `Preparing`,
     * because during upload the prompt buttons remain visible.
     */
    private val isPipelineProgressVisible: () -> Boolean,
    /**
     * Returns `true` iff the controller is in [PipelineUiState.ReprocessStaging]
     * (Phase 7 / Finding SEC-7-1). Required so that [refresh] preserves the
     * correct visibility for ReprocessStaging without being overwritten when
     * called from rotation / content-area switches / layout rebuilds.
     */
    private val isReprocessStaging: () -> Boolean = { false }
) {
    /**
     * CR3 (Spec 2 §10 / §11.8 5c — "5c thinned-KSM-bodies +
     * Strict-Mode-Logging"). KSM stays the **sole live writer** of the
     * ContentArea / Promptbar / overlay-reset visibility axes through
     * CR3 (the new controllers attach **dormant** — render-path-cutover.md
     * §6 RR-2). This logger instruments every KSM visibility write so
     * the no-double-write acceptance is *observable*: the dormant
     * controllers also report their intended writes, and the ledger
     * proves KSM is the only owner that actually reached the view
     * (`doubleWriteCount == 0`). `null` = no audit (unit tests / the
     * pre-CR3 contract); behaviour is byte-identical when absent.
     *
     * Wired **after construction** (via [attachAuditLogger]) because
     * the shared logger is binder-owned and KSM is built in the
     * IME-View inflate path *before* the service-bind may complete (the
     * established bind↔inflate race the IME's
     * `attachImeViewBackendIfReady` consolidation point already
     * handles). CR4 removes the KSM drive call-sites and arms the
     * controllers in the same chunk — the sole live writer flips KSM →
     * controller with zero overlap, still proven by this same ledger.
     */
    private var auditLogger: VisibilityWriteAuditLogger? = null

    /**
     * CR3 — wire the shared Strict-Mode ledger after construction (see
     * [auditLogger] for why this is post-ctor). Idempotent: re-wiring
     * the same logger on a view-recreate is a benign no-op.
     */
    fun attachAuditLogger(logger: VisibilityWriteAuditLogger) {
        auditLogger = logger
    }

    // === Own state (lives only here, nowhere else) ===
    var contentArea: ContentArea = ContentArea.MAIN_BUTTONS
        private set
    var isSmallMode: Boolean = false
        private set

    // C15 — Spec 2 §11.8 5d removed the KeyboardLayoutModeController
    // setter-injection (`setLayoutModeController` / `clearLayoutModeController`).
    // The two-row vs. single-row layout switch now lives in the
    // MotionScene XML and is driven by ImeViewBackend.render → MotionLayout
    // transitionToState. KSM no longer participates in layout-mode work.

    // === Setters for own state ===

    fun setContentArea(area: ContentArea) {
        contentArea = area
        applyVisibility()
    }

    fun setSmallMode(enabled: Boolean) {
        isSmallMode = enabled
        if (enabled && contentArea != ContentArea.MAIN_BUTTONS) {
            contentArea = ContentArea.MAIN_BUTTONS
        }
        applyVisibility()
    }

    // === Trigger for external state changes ===

    /** Called by the service when recording/pipeline state changes. */
    fun refresh() {
        onKeepScreenAwakeChanged(isRecording() || isPaused())
        applyVisibility()
    }

    // === Deterministic visibility calculation ===

    private fun applyVisibility() {
        applyContentAreaVisibility()
        applyRecordingControlsVisibility()
        applyPromptsVisibility()
        // TODO(CR4): remove once `OverlayResetHandler` is armed in
        // production — mirrors the new path's defensive reset (B4-VAL
        // F-33). CR3: KSM stays the sole LIVE writer; the dormant
        // OverlayResetHandler reports its intended GONE to the same
        // ledger (RR-2 no-double-write proof, Spec 2 §10).
        writeVisibility(views.overlayCharactersLl, View.GONE)
        infoBarController?.onStateChanged(contentArea, isSmallMode)
        // C15 — layoutModeController?.refresh() removed. The single-row
        // axis lives in DictateUiState now; ImeViewBackend re-renders
        // against state emissions, not against KSM refresh callbacks.
    }

    /**
     * Owns the three IME content-area containers (`mainButtonsCl` /
     * `qwertzContainer` / `emojiPickerCl`) until the new
     * [net.devemperor.dictate.state.render.ContentAreaController] is wired
     * into production. Both implementations read the same SoT
     * (`state.layout.contentArea`); the new path is a parallel
     * RenderBackend ready to take over once the IME-side attach lands.
     *
     * TODO(D-13 follow-up): delete this method once
     * `ContentAreaController` attaches in production (B4-VAL F-33).
     */
    private fun applyContentAreaVisibility() {
        writeVisibility(
            views.mainButtonsClTyped,
            if (contentArea == ContentArea.MAIN_BUTTONS) View.VISIBLE else View.GONE,
        )
        writeVisibility(
            views.editButtonsLl,
            if (contentArea == ContentArea.MAIN_BUTTONS || contentArea == ContentArea.QWERTZ) View.VISIBLE
            else View.GONE,
        )
        writeVisibility(
            views.qwertzContainer,
            if (contentArea == ContentArea.QWERTZ) View.VISIBLE else View.GONE,
        )
        writeVisibility(
            views.emojiPickerCl,
            if (contentArea == ContentArea.EMOJI_PICKER) View.VISIBLE else View.GONE,
        )
    }

    /**
     * The single KSM visibility-write seam (CR3, Spec 2 §11.8 5c).
     * Reports the write to the [auditLogger] (tagged
     * `"KeyboardStateManager"`) **before** performing it, so the
     * Strict-Mode ledger can prove KSM is the sole *live* writer per
     * axis while the new controllers are dormant (RR-2, Spec 2 §10).
     * The actual mutation is unconditional — KSM IS the live owner
     * until CR4. `null` logger = unchanged behaviour.
     */
    private fun writeVisibility(view: View?, target: Int) {
        if (view == null) return
        // KSM is the sole LIVE writer of these axes through CR3 → live=true.
        auditLogger?.logWrite(view.id, "KeyboardStateManager", target, /* live = */ true)
        view.visibility = target
    }

    private fun applyRecordingControlsVisibility() {
        val isActive = isRecording() || isPaused()
        val isStaging = isReprocessStaging()
        // Pause button: visible during recording; also visible but DISABLED ("blind") during ReprocessStaging.
        writeVisibility(views.pauseButton, if (isActive || isStaging) View.VISIBLE else View.GONE)
        views.pauseButton.isEnabled = isActive
        views.pauseButton.alpha = if (isActive) 1.0f else 0.4f
        // Trash button: visible during recording AND ReprocessStaging (cancel action in both cases)
        writeVisibility(views.trashButton, if (isActive || isStaging) View.VISIBLE else View.GONE)
    }

    /**
     * Owns the prompt-container visibility + `pipelineProgress` swap +
     * QWERTZ-side recording controls until the new
     * [net.devemperor.dictate.state.render.PromptVisibilityController]
     * is wired into production. Same SoT (`state.layout.smallMode` +
     * `state.layout.contentArea` + recording / pipeline / rewording
     * axes) is read by both implementations.
     *
     * TODO(D-13 follow-up): delete this method once
     * `PromptVisibilityController` attaches in production (B4-VAL F-33).
     */
    private fun applyPromptsVisibility() {
        val isPipelineProgress = isPipelineProgressVisible() && !isReprocessStaging()
        val isActive = isRecording() || isPaused()
        val isStaging = isReprocessStaging()

        // Prompts container (combination of all axes)
        val showPrompts = when {
            isSmallMode -> false
            contentArea == ContentArea.EMOJI_PICKER -> false
            isActive || isPipelineRunning() || isStaging -> true
            else -> isRewordingEnabled()
        }
        writeVisibility(views.promptsCl, if (showPrompts) View.VISIBLE else View.GONE)

        // Prompts content: RecyclerView vs pipeline progress.
        // ReprocessStaging shows the RecyclerView (queue editing), NOT pipeline progress.
        writeVisibility(
            views.promptsRv,
            if (!isPipelineProgress) View.VISIBLE else View.GONE,
        )
        writeVisibility(
            views.pipelineProgressLl,
            if (isPipelineProgress) View.VISIBLE else View.GONE,
        )

        // Recording controls: only visible when active AND NOT in pipeline progress mode
        // (pipeline progress replaces the recording indicator)
        val showRecControls = isActive && !isPipelineProgress && contentArea == ContentArea.QWERTZ
        writeVisibility(
            views.promptRecordingControlsLl,
            if (showRecControls) View.VISIBLE else View.GONE,
        )

        if (showPrompts) {
            applyPromptsLayout()
        }
    }

    /** Adjusts prompts container height and RecyclerView span count for the current state. */
    private fun applyPromptsLayout() {
        val promptHeightDp = if (contentArea == ContentArea.QWERTZ) 36 else 72
        val newHeight = (promptHeightDp * views.promptsCl.resources.displayMetrics.density).toInt()
        val lp = views.promptsCl.layoutParams
        if (lp.height != newHeight) {
            lp.height = newHeight
            views.promptsCl.layoutParams = lp
        }

        val targetSpanCount = if (contentArea == ContentArea.QWERTZ) 1 else 2
        (views.promptsRv?.layoutManager as? StaggeredGridLayoutManager)?.let {
            if (it.spanCount != targetSpanCount) it.spanCount = targetSpanCount
        }
    }
}
