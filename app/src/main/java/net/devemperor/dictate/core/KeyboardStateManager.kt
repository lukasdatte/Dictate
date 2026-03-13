package net.devemperor.dictate.core

import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.constraintlayout.widget.ConstraintLayout

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
    val trashButton: View
)

class KeyboardStateManager(
    private val views: KeyboardViews,
    // Lambda queries: state lives in the responsible managers
    private val isRecording: () -> Boolean,
    private val isPaused: () -> Boolean,
    private val isPipelineRunning: () -> Boolean,
    private val isRewordingEnabled: () -> Boolean,
    private val onKeepScreenAwakeChanged: (Boolean) -> Unit,
    private val infoBarController: InfoBarController? = null
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
        // Content area (own state, mutually exclusive)
        views.mainButtonsCl.visibility =
            if (contentArea == ContentArea.MAIN_BUTTONS) View.VISIBLE else View.GONE
        views.editButtonsLl.visibility =
            if (contentArea == ContentArea.MAIN_BUTTONS && !isSmallMode) View.VISIBLE
            else if (contentArea == ContentArea.MAIN_BUTTONS && isSmallMode) View.VISIBLE
            else View.GONE
        views.qwertzContainer.visibility =
            if (contentArea == ContentArea.QWERTZ) View.VISIBLE else View.GONE
        views.emojiPickerCl.visibility =
            if (contentArea == ContentArea.EMOJI_PICKER) View.VISIBLE else View.GONE

        // Recording controls (queried state)
        val isActive = isRecording() || isPaused()
        views.pauseButton.visibility = if (isActive) View.VISIBLE else View.GONE
        views.trashButton.visibility = if (isActive) View.VISIBLE else View.GONE

        // Prompts (combination of all axes)
        val showPrompts = when {
            isSmallMode -> false
            contentArea == ContentArea.EMOJI_PICKER -> false
            isRecording() || isPaused() || isPipelineRunning() -> true
            else -> isRewordingEnabled()
        }
        views.promptsCl.visibility = if (showPrompts) View.VISIBLE else View.GONE

        // Overlay: always GONE (shown on demand by EnterOverlayHandler)
        views.overlayCharactersLl.visibility = View.GONE

        // Notify InfoBarController about state change
        infoBarController?.onStateChanged(contentArea, isSmallMode)
    }
}
