package net.devemperor.dictate.core

import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.StaggeredGridLayoutManager

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
data class KeyboardViews(
    val mainButtonsCl: View,
    val editButtonsLl: ConstraintLayout,
    val promptsCl: ConstraintLayout,
    val emojiPickerCl: ConstraintLayout,
    val qwertzContainer: FrameLayout,
    val overlayCharactersLl: LinearLayout,
    val pauseButton: View,
    val trashButton: View,
    val promptRecordingControlsLl: LinearLayout? = null,
    val promptTrashBtn: View? = null,
    val promptsRv: RecyclerView? = null,
    val pipelineProgressLl: View? = null
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
    }

    private fun applyContentAreaVisibility() {
        views.mainButtonsCl.visibility =
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
