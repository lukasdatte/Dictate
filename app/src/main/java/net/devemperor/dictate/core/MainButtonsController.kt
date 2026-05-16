package net.devemperor.dictate.core

import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.inputmethod.InputConnection
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.button.MaterialButton
import net.devemperor.dictate.DictateUtils
import net.devemperor.dictate.R
import net.devemperor.dictate.preferences.Pref
import net.devemperor.dictate.preferences.get
import net.devemperor.dictate.keyboard.BackspaceSwipeHandler
import net.devemperor.dictate.keyboard.CursorSwipeTouchHandler
import net.devemperor.dictate.keyboard.EnterOverlayHandler
import net.devemperor.dictate.keyboard.KeyPressAnimator
import net.devemperor.dictate.widget.PulseLayout

/**
 * Manages main keyboard button UI: registration, recording visuals, theming, and animations.
 *
 * Responsibilities:
 * - Button click/long-click/touch listener registration (delegates actions to [Callback])
 * - Recording animation via [RecordingAnimation] strategy (ripple pulse by default)
 * - Button color theming ([applyTheme])
 * - Key press animations ([initializeKeyPressAnimations])
 * - Overlay characters initialization
 * - Small mode toggle animation ([animateSmallModeToggle])
 *
 * Does NOT handle recording coordination, Bluetooth/SCO, audio focus, or pipeline logic.
 * Those remain in the Service and are invoked via the [Callback] interface.
 */
class MainButtonsController(
    private val views: MainButtonViews,
    private val sp: SharedPreferences,
    private val stateManager: KeyboardStateManager,
    private val callback: Callback,
    private val inputConnectionProvider: () -> InputConnection?,
    private val keyPressAnimator: KeyPressAnimator
) {
    /**
     * G15 (CR1, render-path-cutover.md §3 / §7 A2) — the two
     * `edit_numbers_btn` animations are now owned by the extracted
     * [EditNumbersAnimator] helper (Spec 2 §9.2). The controller's
     * `animateSmallModeToggle` / `animateEditNumbersBounce` methods stay
     * as **thin delegations** so the legacy IME call-sites (still the
     * live drivers in CR1 — additive, no behaviour change) keep working
     * byte-identically. CR4 re-points those call-sites to the helper
     * directly and deletes the controller (CR-DEL).
     */
    private val editNumbersAnimator = EditNumbersAnimator(
        editNumbersButton = views.editNumbersButton,
        animationsEnabled = { sp.get(Pref.Animations) },
        isSmallMode = { stateManager.isSmallMode },
    )
    interface Callback {
        fun onVibrate()
        fun onRecordClicked()
        fun onRecordLongClicked()
        fun onResendClicked()
        fun onResendLongClicked()
        fun onBackspaceClicked()
        fun onBackspaceLongClicked()
        fun onBackspaceDeleteCancelled()
        fun onTrashClicked()
        fun onPauseClicked()
        fun onEnterClicked()
        fun onKeyboardToggleClicked()
        fun onKeyboardLongClicked()
        fun onEmojiToggleClicked()
        fun onEmojiCloseClicked()
        fun onSettingsClicked()
        fun onHistoryClicked()
        fun onPipelineCancelClicked()
        fun onSmallModeToggled()
        // Block 0c: replaces the now-redundant onLanguageCycled long-press
        // (the language pill in the prompts row covers cycling explicitly).
        // Wired to the editNumbersButton long-press in Chunk 3 (Block 1).
        fun onSingleRowModeToggled()
        // Block 0c: emitted by the audio-focus toggle button (Edit-Bar +
        // Single-Row variant). Wired to the actual button in Chunk 2 (Block 2).
        fun onAudioFocusToggled()
        fun onEditAction(actionId: Int)
    }

    private val recordClickListener = View.OnClickListener {
        callback.onVibrate()
        callback.onRecordClicked()
    }

    /**
     * Block 2: shared click listener for both audio-focus buttons (Edit-Bar +
     * Single-Row variant). Both buttons forward the same callback so the user
     * sees identical behaviour from either entry point — the visible icon
     * synchronisation is handled by [refreshAudioFocusIcon].
     */
    private val audioFocusClickListener = View.OnClickListener {
        callback.onVibrate()
        callback.onAudioFocusToggled()
    }

    fun registerAllListeners() {
        registerEditBarListeners()
        registerMainButtonListeners()
        registerEmojiListeners()
        initializeOverlayCharacters()
    }

    // ── Edit Bar ──

    private fun registerEditBarListeners() {
        views.editNumbersButton.setOnClickListener {
            callback.onVibrate()
            callback.onSmallModeToggled()
        }

        // Block 1 / Chunk 3: long-press toggles SingleRowMode. The previous
        // `onLanguageCycled` long-press was removed in Chunk 1 — language
        // cycling lives on the dedicated language pill. Returning `true`
        // suppresses the click that would otherwise follow the long-press
        // (the SmallMode toggle is on the short click).
        views.editNumbersButton.setOnLongClickListener {
            callback.onVibrate()
            callback.onSingleRowModeToggled()
            true
        }

        views.editSettingsButton.setOnClickListener { callback.onSettingsClicked() }
        views.editHistoryButton.setOnClickListener { callback.onHistoryClicked() }
        views.pipelineCancelBtn.setOnClickListener { callback.onPipelineCancelClicked() }

        // Block 2: Edit-Bar audio-focus button. Wired via shared listener with
        // the Single-Row variant in registerMainButtonListeners().
        views.editAudioFocusButton.setOnClickListener(audioFocusClickListener)

        views.editKeyboardButton.setOnClickListener {
            callback.onVibrate()
            callback.onKeyboardToggleClicked()
        }

        views.editKeyboardButton.setOnLongClickListener {
            callback.onVibrate()
            callback.onKeyboardLongClicked()
            true
        }

        // Edit actions (undo, redo, cut, copy, paste)
        val editActions = arrayOf(
            views.editUndoButton to android.R.id.undo,
            views.editRedoButton to android.R.id.redo,
            views.editCutButton to android.R.id.cut,
            views.editCopyButton to android.R.id.copy,
            views.editPasteButton to android.R.id.paste
        )
        for ((button, actionId) in editActions) {
            button.setOnClickListener {
                callback.onVibrate()
                callback.onEditAction(actionId)
            }
        }
    }

    // ── Main Buttons ──

    private fun registerMainButtonListeners() {
        // Record button
        views.recordButton.setCompoundDrawablesRelativeWithIntrinsicBounds(
            R.drawable.ic_baseline_mic_20, 0, R.drawable.ic_baseline_folder_open_20, 0
        )
        views.recordButton.setOnClickListener(recordClickListener)

        // Long click
        views.recordButton.setOnLongClickListener {
            callback.onVibrate()
            callback.onRecordLongClicked()
            true
        }

        // Resend button
        views.resendButton.setOnClickListener {
            callback.onVibrate()
            callback.onResendClicked()
        }
        views.resendButton.setOnLongClickListener {
            callback.onVibrate()
            callback.onResendLongClicked()
            true
        }

        // Backspace: click, long-press (accelerated delete), touch (swipe-to-select)
        views.backspaceButton.setOnClickListener {
            callback.onVibrate()
            callback.onBackspaceClicked()
        }
        views.backspaceButton.setOnLongClickListener {
            callback.onBackspaceLongClicked()
            true
        }
        views.backspaceButton.setOnTouchListener(BackspaceSwipeHandler(
            inputConnectionProvider,
            { callback.onVibrate() },
            { callback.onBackspaceDeleteCancelled() },
            { v, event -> keyPressAnimator.handlePressAnimationEvent(v, event) }
        ))

        // Trash button
        views.trashButton.setOnClickListener {
            callback.onVibrate()
            callback.onTrashClicked()
        }

        // Space button: cursor swipe + tap
        val spaceTouchHandler = CursorSwipeTouchHandler(
            CursorSwipeTouchHandler.DEFAULT_SWIPE_THRESHOLD,
            onTap = {
                callback.onVibrate()
                inputConnectionProvider()?.commitText(" ", 1)
            },
            onCursorMove = { direction ->
                callback.onVibrate()
                inputConnectionProvider()?.commitText("", if (direction > 0) 2 else -1)
            },
            onSwipeStateChanged = { isSwiping ->
                if (isSwiping) {
                    views.spaceButton.setCompoundDrawablesRelativeWithIntrinsicBounds(
                        R.drawable.ic_baseline_keyboard_double_arrow_left_24, 0,
                        R.drawable.ic_baseline_keyboard_double_arrow_right_24, 0
                    )
                } else {
                    views.spaceButton.setCompoundDrawablesRelativeWithIntrinsicBounds(0, 0, 0, 0)
                }
            },
            consumeTouchEvents = false
        )
        views.spaceButton.setOnTouchListener { v, event ->
            keyPressAnimator.handlePressAnimationEvent(v, event)
            if (inputConnectionProvider() == null) {
                views.spaceButton.setCompoundDrawablesRelativeWithIntrinsicBounds(0, 0, 0, 0)
                return@setOnTouchListener false
            }
            spaceTouchHandler.onTouch(v, event)
        }

        // Pause button
        views.pauseButton.setOnClickListener {
            callback.onVibrate()
            callback.onPauseClicked()
        }

        // Block 2: Single-Row audio-focus button — same listener as the
        // Edit-Bar variant. Visibility is gated by SingleRowMode (Block 1).
        views.audioFocusButton.setOnClickListener(audioFocusClickListener)

        // Enter button: click, long-press (show overlay), touch (overlay character selection)
        views.enterButton.setOnClickListener {
            callback.onVibrate()
            callback.onEnterClicked()
        }
        views.enterButton.setOnLongClickListener {
            callback.onVibrate()
            views.overlayCharactersLl.visibility = View.VISIBLE
            true
        }
        views.enterButton.setOnTouchListener(EnterOverlayHandler(
            views.overlayCharactersLl,
            inputConnectionProvider,
            { sp.get(Pref.AccentColor) },
            { v, event -> keyPressAnimator.handlePressAnimationEvent(v, event) }
        ))
    }

    // ── Emoji ──

    private fun registerEmojiListeners() {
        views.editEmojiButton.setOnClickListener {
            callback.onVibrate()
            callback.onEmojiToggleClicked()
        }

        views.emojiPickerCloseButton.setOnClickListener {
            callback.onVibrate()
            callback.onEmojiCloseClicked()
        }

        views.emojiPickerView.setOnEmojiPickedListener { emoji ->
            callback.onVibrate()
            if (emoji != null) {
                inputConnectionProvider()?.commitText(emoji.emoji, 1)
            }
        }
    }

    // ── Overlay Characters ──

    private fun initializeOverlayCharacters() {
        val context = views.overlayCharactersLl.context
        val density = context.resources.displayMetrics.density
        for (i in 0 until 8) {
            val charView = LayoutInflater.from(context)
                .inflate(R.layout.item_overlay_characters, views.overlayCharactersLl, false) as TextView
            val bg = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = (4 * density + 0.5f)
                setStroke((1 * density + 0.5f).toInt(), Color.BLACK)
            }
            charView.background = bg
            views.overlayCharactersLl.addView(charView)
        }
    }

    // ── Key Press Animations ──

    fun initializeKeyPressAnimations() {
        val animatedViews = listOf(
            views.editSettingsButton, views.recordButton, views.resendButton, views.trashButton,
            views.pauseButton, views.emojiPickerCloseButton,
            views.editUndoButton, views.editRedoButton, views.editCutButton, views.editCopyButton,
            views.editPasteButton, views.editEmojiButton, views.editNumbersButton,
            views.editKeyboardButton, views.editHistoryButton,
            views.infoYesButton, views.infoNoButton,
            // Block 2: audio-focus buttons are tap-down-animated like every
            // other press surface. Both buttons exist in the layout now
            // (Chunk 2 wired the IDs).
            views.editAudioFocusButton, views.audioFocusButton
        )
        for (view in animatedViews) {
            keyPressAnimator.applyPressAnimation(view)
        }
    }

    // ── Button Color Theming ──

    /**
     * Enable/disable the resend button.
     *
     * Used by the Phase-5 short-press path to suppress double-clicks while
     * the asynchronous DB lookup + insertion strategy is in flight. The
     * service flips this to `false` in the click moment and re-enables it
     * after a 500 ms cooldown via [android.os.Handler.postDelayed].
     */
    fun setResendEnabled(enabled: Boolean) {
        views.resendButton.isEnabled = enabled
    }

    /**
     * Update the record button's text label.
     *
     * Used by the language-chip-curation flow (Phase 2): when the
     * effective language changes — either via the chip's PopupMenu or
     * because the pipeline state moved into/out of ReprocessStaging —
     * the record button label must follow so the user sees a consistent
     * "current language" indication across the keyboard surface.
     */
    fun updateRecordButtonText(text: String) {
        views.recordButton.text = text
    }

    /**
     * Block 2: synchronise both audio-focus buttons' icon + contentDescription.
     *
     * Called from three sites in the service:
     *  - On user toggle ([Callback.onAudioFocusToggled]) — after the SP-write.
     *  - On external SP change (Settings → SwitchPreference) — via the
     *    `audioFocusListener` registered in [DictateInputMethodService.onCreateInputView].
     *  - Once after view-recreate ([registerAllListeners] tail / Service
     *    [DictateInputMethodService.setupKeyboard]) so the freshly inflated
     *    buttons reflect the persisted [Pref.AudioFocus] value.
     *
     * Icons are state-flipped on purpose — `volume_off` is shown when
     * AudioFocus is ENABLED (because pressing the button would un-mute, i.e.
     * disable the focus grab); `volume_up` is shown when AudioFocus is
     * DISABLED (the button would re-enable auto-pause).
     *
     * Quality-Gate Nice-to-have B2-8: contentDescriptions are state phrases,
     * not action phrases — TalkBack announces "Audio-Fokus aktiv …" instead
     * of "Audio-Fokus deaktivieren", which matches the toggle semantics.
     */
    fun refreshAudioFocusIcon(enabled: Boolean) {
        val context = views.editAudioFocusButton.context
        val iconRes = if (enabled) {
            R.drawable.ic_baseline_volume_off_24
        } else {
            R.drawable.ic_baseline_volume_up_24
        }
        val descriptionRes = if (enabled) {
            R.string.dictate_audio_focus_state_on
        } else {
            R.string.dictate_audio_focus_state_off
        }
        val description = context.getString(descriptionRes)
        val drawable = androidx.core.content.ContextCompat.getDrawable(context, iconRes)

        views.editAudioFocusButton.foreground = drawable
        views.editAudioFocusButton.contentDescription = description
        views.audioFocusButton.foreground = drawable?.constantState?.newDrawable()
        views.audioFocusButton.contentDescription = description
    }

    fun applyTheme(accentColor: Int) {
        val accentMedium = DictateUtils.darkenColor(accentColor, 0.18f)
        val accentDark = DictateUtils.darkenColor(accentColor, 0.35f)

        applyButtonColor(views.editSettingsButton, accentMedium)
        applyButtonColor(views.recordButton, accentColor)
        applyButtonColor(views.resendButton, accentMedium)
        applyButtonColor(views.backspaceButton, accentDark)
        applyButtonColor(views.editKeyboardButton, accentDark)
        applyButtonColor(views.trashButton, accentMedium)
        applyButtonColor(views.spaceButton, accentMedium)
        applyButtonColor(views.pauseButton, accentMedium)
        applyButtonColor(views.enterButton, accentDark)
        applyButtonColor(views.editUndoButton, accentMedium)
        applyButtonColor(views.editRedoButton, accentMedium)
        applyButtonColor(views.editCutButton, accentMedium)
        applyButtonColor(views.editCopyButton, accentMedium)
        applyButtonColor(views.editPasteButton, accentMedium)
        applyButtonColor(views.editEmojiButton, accentMedium)
        applyButtonColor(views.editNumbersButton, accentMedium)
        applyButtonColor(views.editHistoryButton, accentMedium)
        applyButtonColor(views.emojiPickerCloseButton, accentColor)
        // Block 2: theme the audio-focus buttons in the same accentMedium tier
        // as pause/trash. Both buttons are non-null since Chunk 2 added the
        // XML IDs.
        applyButtonColor(views.editAudioFocusButton, accentMedium)
        applyButtonColor(views.audioFocusButton, accentMedium)
    }

    private fun applyButtonColor(button: MaterialButton, color: Int) {
        button.setBackgroundColor(color)
    }

    // ── Small Mode Animation ──

    fun animateSmallModeToggle(animate: Boolean) =
        editNumbersAnimator.animateSmallModeToggle(animate)

    /**
     * Thin delegation to [EditNumbersAnimator.animateEditNumbersBounce]
     * (G15 extraction, CR1). Full rationale — incl. the Quality-Gate K6
     * orthogonal-axis note — lives on the helper method.
     */
    fun animateEditNumbersBounce() =
        editNumbersAnimator.animateEditNumbersBounce()

    // ── Overlay Characters Update (called from onStartInputView) ──

    fun updateOverlayCharacters(characters: String, accentColor: Int) {
        for (i in 0 until views.overlayCharactersLl.childCount) {
            val charView = views.overlayCharactersLl.getChildAt(i) as TextView
            if (i >= characters.length) {
                charView.visibility = View.GONE
            } else {
                charView.visibility = View.VISIBLE
                charView.text = characters.substring(i, i + 1)
                val bg = charView.background as GradientDrawable
                bg.setColor(accentColor)
            }
        }
    }
}

/**
 * All button views managed by [MainButtonsController].
 *
 * Chunk 2 (Block 2) introduced the two audio-focus buttons in the layout —
 * `edit_audio_focus_btn` (always visible in the edit bar) and
 * `audio_focus_btn` (in the action_row, gone until SingleRowMode is enabled
 * by Chunk 3 / Block 1). Both fields are non-null; the service wires the
 * concrete views in [DictateInputMethodService.onCreateInputView].
 */
data class MainButtonViews(
    val recordButton: MaterialButton,
    val resendButton: MaterialButton,
    val backspaceButton: MaterialButton,
    val trashButton: MaterialButton,
    val spaceButton: MaterialButton,
    val pauseButton: MaterialButton,
    val enterButton: MaterialButton,
    val editSettingsButton: MaterialButton,
    val editUndoButton: MaterialButton,
    val editRedoButton: MaterialButton,
    val editCutButton: MaterialButton,
    val editCopyButton: MaterialButton,
    val editPasteButton: MaterialButton,
    val editEmojiButton: MaterialButton,
    val editNumbersButton: MaterialButton,
    val editKeyboardButton: MaterialButton,
    val editHistoryButton: MaterialButton,
    val emojiPickerCloseButton: MaterialButton,
    val emojiPickerView: androidx.emoji2.emojipicker.EmojiPickerView,
    val overlayCharactersLl: LinearLayout,
    val pipelineCancelBtn: MaterialButton,
    val infoYesButton: Button,
    val infoNoButton: Button,
    val recordPulseLayout: PulseLayout,
    val editAudioFocusButton: MaterialButton,
    val audioFocusButton: MaterialButton
)
