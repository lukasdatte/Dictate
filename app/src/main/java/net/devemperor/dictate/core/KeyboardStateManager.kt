package net.devemperor.dictate.core

import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.StaggeredGridLayoutManager
import com.google.android.material.button.MaterialButton

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
 * Beyond the original visibility-driven set, the DTO also carries the
 * [actionRow] / [inputRow] / [recordPulseLayout] handles plus the five
 * input-row buttons that the [KeyboardLayoutModeController] re-parents
 * when switching between two-row and single-row layout. The pulse
 * **wrapper** is intentionally exposed (NOT the bare `record_btn`) — moving
 * the inner button alone breaks the [net.devemperor.dictate.widget.PulseLayout]
 * animation by cutting its container reference.
 */
data class KeyboardViews(
    // [mainButtonsClTyped] is the parent of [actionRow] and [inputRow] and
    // serves a dual role: visibility target for the ContentArea axis (was
    // formerly a separate `mainButtonsCl: View` field — consolidated 2026-05-06
    // because two fields for the same XML id are a maintenance trap) AND
    // the TransitionManager scene root for [KeyboardLayoutModeController].
    // It is a LinearLayout in the current XML but typed as the shared
    // `ViewGroup` supertype so a future re-skin (e.g. switch to a
    // ConstraintLayout root) does not require a signature change.
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
    // Block 0a / Chunk 3: layout-mode wiring consumed by
    // [KeyboardLayoutModeController]. All fields are non-null since the
    // service unconditionally resolves the corresponding XML IDs in
    // [DictateInputMethodService.onCreateInputView]. The pulse **wrapper**
    // is intentionally exposed (NOT the bare `record_btn`) — moving the
    // inner button alone breaks the
    // [net.devemperor.dictate.widget.PulseLayout] animation by cutting its
    // container reference.
    val actionRow: ConstraintLayout,
    val inputRow: ConstraintLayout,
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
    // === Own state (lives only here, nowhere else) ===
    var contentArea: ContentArea = ContentArea.MAIN_BUTTONS
        private set
    var isSmallMode: Boolean = false
        private set

    /**
     * Setter-injected so that the controller can be constructed *after*
     * [KeyboardStateManager] in the service while still receiving the
     * [applyVisibility] re-render hook for free. The controller is consulted
     * after every visibility recomputation so a content-area switch back to
     * `MAIN_BUTTONS` (e.g. closing QWERTZ) re-applies the persisted
     * single-row mode without an explicit second call site.
     */
    private var layoutModeController: KeyboardLayoutModeController? = null

    fun setLayoutModeController(controller: KeyboardLayoutModeController) {
        layoutModeController = controller
    }

    /**
     * Drops the reference to the previously-injected layout-mode controller.
     *
     * Called from the service's `cleanupOldControllers()` so the discarded
     * controller (which holds direct references to invalidated `action_row`
     * / `input_row` views) does not survive into the upcoming
     * `onCreateInputView` until the new controller is wired in. Defensive:
     * an early `applyVisibility()` call between cleanup and re-wiring would
     * otherwise reach into a stale controller.
     */
    fun clearLayoutModeController() {
        layoutModeController = null
    }

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
        views.overlayCharactersLl.visibility = View.GONE
        infoBarController?.onStateChanged(contentArea, isSmallMode)
        // Block 1 / Chunk 3: re-apply the persisted single-row layout after a
        // visibility recompute. The controller's [refresh] is a no-op while
        // `main_buttons_cl` is GONE (SmallMode-Vorrang, Plan-Z. 222-229) and
        // therefore safe to call unconditionally — see Plan-Z. 437-438.
        layoutModeController?.refresh()
    }

    private fun applyContentAreaVisibility() {
        views.mainButtonsClTyped.visibility =
            if (contentArea == ContentArea.MAIN_BUTTONS) View.VISIBLE else View.GONE
        views.editButtonsLl.visibility =
            if (contentArea == ContentArea.MAIN_BUTTONS || contentArea == ContentArea.QWERTZ) View.VISIBLE
            else View.GONE
        views.qwertzContainer.visibility =
            if (contentArea == ContentArea.QWERTZ) View.VISIBLE else View.GONE
        views.emojiPickerCl.visibility =
            if (contentArea == ContentArea.EMOJI_PICKER) View.VISIBLE else View.GONE
    }

    private fun applyRecordingControlsVisibility() {
        val isActive = isRecording() || isPaused()
        val isStaging = isReprocessStaging()
        // Pause button: visible during recording; also visible but DISABLED ("blind") during ReprocessStaging.
        views.pauseButton.visibility = if (isActive || isStaging) View.VISIBLE else View.GONE
        views.pauseButton.isEnabled = isActive
        views.pauseButton.alpha = if (isActive) 1.0f else 0.4f
        // Trash button: visible during recording AND ReprocessStaging (cancel action in both cases)
        views.trashButton.visibility = if (isActive || isStaging) View.VISIBLE else View.GONE
    }

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
        views.promptsCl.visibility = if (showPrompts) View.VISIBLE else View.GONE

        // Prompts content: RecyclerView vs pipeline progress.
        // ReprocessStaging shows the RecyclerView (queue editing), NOT pipeline progress.
        views.promptsRv?.visibility =
            if (!isPipelineProgress) View.VISIBLE else View.GONE
        views.pipelineProgressLl?.visibility =
            if (isPipelineProgress) View.VISIBLE else View.GONE

        // Recording controls: only visible when active AND NOT in pipeline progress mode
        // (pipeline progress replaces the recording indicator)
        val showRecControls = isActive && !isPipelineProgress && contentArea == ContentArea.QWERTZ
        views.promptRecordingControlsLl?.visibility =
            if (showRecControls) View.VISIBLE else View.GONE

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
