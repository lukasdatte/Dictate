package net.devemperor.dictate.core

import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.inputmethod.InputConnection
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.button.MaterialButton
import net.devemperor.dictate.DictateUtils
import net.devemperor.dictate.R
import net.devemperor.dictate.keyboard.BackspaceSwipeHandler
import net.devemperor.dictate.keyboard.CursorSwipeTouchHandler
import net.devemperor.dictate.keyboard.EnterOverlayHandler
import net.devemperor.dictate.keyboard.KeyPressAnimator
import net.devemperor.dictate.widget.PulseLayout
import net.devemperor.dictate.widget.RecordingAnimation
import net.devemperor.dictate.widget.RipplePulseAnimation

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
        fun onLanguageCycled()
        fun onEditAction(actionId: Int)
    }

    // Recording animation (strategy pattern — swappable at runtime)
    private var recordingAnimation: RecordingAnimation = RipplePulseAnimation(views.recordPulseLayout)

    fun registerAllListeners() {
        registerEditBarListeners()
        registerMainButtonListeners()
        registerEmojiListeners()
        initializeOverlayCharacters()
        recordingAnimation.prepare(views.recordButton)
    }

    // ── Edit Bar ──

    private fun registerEditBarListeners() {
        views.editNumbersButton.setOnClickListener {
            callback.onVibrate()
            callback.onSmallModeToggled()
        }

        views.editNumbersButton.setOnLongClickListener {
            callback.onVibrate()
            callback.onLanguageCycled()
            true
        }

        views.editSettingsButton.setOnClickListener { callback.onSettingsClicked() }
        views.editHistoryButton.setOnClickListener { callback.onHistoryClicked() }
        views.pipelineCancelBtn.setOnClickListener { callback.onPipelineCancelClicked() }

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
        views.recordButton.setOnClickListener {
            callback.onVibrate()
            callback.onRecordClicked()
        }
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
            { sp.getInt("net.devemperor.dictate.accent_color", -14700810) },
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
        val animatedViews = arrayOf(
            views.editSettingsButton, views.recordButton, views.resendButton, views.trashButton,
            views.pauseButton, views.emojiPickerCloseButton,
            views.editUndoButton, views.editRedoButton, views.editCutButton, views.editCopyButton,
            views.editPasteButton, views.editEmojiButton, views.editNumbersButton,
            views.editKeyboardButton, views.editHistoryButton,
            views.infoYesButton, views.infoNoButton
        )
        for (view in animatedViews) {
            keyPressAnimator.applyPressAnimation(view)
        }
    }

    // ── Recording Animation (Strategy Pattern) ──

    fun applyRecordingIconState(active: Boolean) {
        if (!sp.getBoolean("net.devemperor.dictate.animations", true)) return

        if (active) {
            recordingAnimation.start()
        } else {
            recordingAnimation.cancel()
        }
    }

    fun updateRecordButtonIconWhileRecording(isRecording: Boolean, usesBluetooth: Boolean) {
        if (!isRecording) return
        if (usesBluetooth) {
            views.recordButton.setCompoundDrawablesRelativeWithIntrinsicBounds(
                R.drawable.ic_baseline_send_20, 0, R.drawable.ic_baseline_bluetooth_20, 0
            )
        } else {
            views.recordButton.setCompoundDrawablesRelativeWithIntrinsicBounds(
                R.drawable.ic_baseline_send_20, 0, 0, 0
            )
        }
    }

    fun cancelPulseAnimation() = recordingAnimation.cancel()

    fun pausePulseAnimation() = recordingAnimation.pause()

    fun resumePulseAnimation() = recordingAnimation.resume()

    /** Update the pulse color to match the current accent color. */
    fun updatePulseColor(accentColor: Int) {
        views.recordPulseLayout.pulseColor = DictateUtils.darkenColor(accentColor, 0.1f)
    }

    // ── Button Color Theming ──

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

        updatePulseColor(accentColor)
    }

    private fun applyButtonColor(button: MaterialButton, color: Int) {
        button.setBackgroundColor(color)
    }

    // ── Small Mode Animation ──

    fun animateSmallModeToggle(animate: Boolean) {
        val animationsEnabled = sp.getBoolean("net.devemperor.dictate.animations", true)
        val target = if (stateManager.isSmallMode) 180f else 0f

        if (animate && animationsEnabled) {
            views.editNumbersButton.animate()
                .rotation(target)
                .setDuration(200)
                .setInterpolator(DecelerateInterpolator())
                .start()
        } else {
            views.editNumbersButton.rotation = target
        }
    }

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

/** All button views managed by [MainButtonsController]. */
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
    val recordPulseLayout: PulseLayout
)
