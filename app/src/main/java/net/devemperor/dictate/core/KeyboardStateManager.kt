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
    private val getPromptAreaMode: () -> KeyboardUiController.PromptAreaMode
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
        views.pauseButton.visibility = if (isActive) View.VISIBLE else View.GONE
        views.trashButton.visibility = if (isActive) View.VISIBLE else View.GONE
    }

    private fun applyPromptsVisibility() {
        val mode = getPromptAreaMode()
        val isActive = isRecording() || isPaused()

        // Prompts container (combination of all axes)
        val showPrompts = when {
            isSmallMode -> false
            contentArea == ContentArea.EMOJI_PICKER -> false
            isActive || isPipelineRunning() -> true
            else -> isRewordingEnabled()
        }
        views.promptsCl.visibility = if (showPrompts) View.VISIBLE else View.GONE

        // Prompts content: RecyclerView vs pipeline progress
        views.promptsRv?.visibility =
            if (mode == KeyboardUiController.PromptAreaMode.PROMPT_BUTTONS) View.VISIBLE else View.GONE
        views.pipelineProgressLl?.visibility =
            if (mode == KeyboardUiController.PromptAreaMode.PIPELINE_PROGRESS) View.VISIBLE else View.GONE

        // Recording controls: only visible when active AND in PROMPT_BUTTONS mode
        // (pipeline progress replaces the recording indicator)
        val showRecControls = isActive && mode == KeyboardUiController.PromptAreaMode.PROMPT_BUTTONS && contentArea == ContentArea.QWERTZ
        views.promptRecordingControlsLl?.visibility =
            if (showRecControls) View.VISIBLE else View.GONE

        if (showPrompts) {
            applyPromptsLayout(mode)
        }
    }

    /** Adjusts prompts container height and RecyclerView span count for the current state. */
    private fun applyPromptsLayout(mode: KeyboardUiController.PromptAreaMode) {
        val isPipeline = mode == KeyboardUiController.PromptAreaMode.PIPELINE_PROGRESS
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
