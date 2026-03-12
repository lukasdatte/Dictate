package net.devemperor.dictate.keyboard

import android.annotation.SuppressLint
import android.content.res.ColorStateList
import android.view.inputmethod.InputConnection

/**
 * Controller for the QWERTZ keyboard. Implements the state machine for shift, ctrl,
 * and layout switching, and dispatches all key actions to the InputConnection.
 *
 * Responsibilities:
 * - Shift state machine (OFF -> SINGLE -> CAPS_LOCK -> OFF)
 * - Ctrl modifier toggle with combo execution (A/C/V/X/Z)
 * - Layout switching (QWERTZ <-> NUMBERS <-> SYMBOLS)
 * - Character input with shift/ctrl handling
 * - Backspace repeat delegation to AcceleratingRepeatHandler
 * - Space cursor-swipe delegation to CursorSwipeTouchHandler
 * - Tab input
 * - Color theming delegation
 *
 * NOT responsible for:
 * - View creation (QwertzKeyboardView)
 * - Layout definitions (QwertzLayoutProvider)
 * - InputConnection lifecycle (provided by the service)
 *
 * @param view the keyboard view to control
 * @param inputConnectionProvider provides the current InputConnection (may be null)
 * @param vibrate haptic feedback callback
 * @param deleteOneCharacter callback for single character deletion
 * @param performEnterAction callback for enter/IME action
 */
class QwertzKeyboardController(
    private val view: QwertzKeyboardView,
    private val inputConnectionProvider: () -> InputConnection?,
    private val vibrate: () -> Unit,
    private val deleteOneCharacter: () -> Unit,
    private val performEnterAction: () -> Unit
) : QwertzKeyboardView.KeyActionCallback {

    // ── State ──

    private var shiftState = ShiftState.OFF
    private var ctrlActive = false
    private var currentLayout = QwertzKeyboardLayout.QWERTZ

    // ── Handlers ──

    private val acceleratingRepeatHandler = AcceleratingRepeatHandler(
        vibrate = vibrate
    )

    private val cursorSwipeTouchHandler = CursorSwipeTouchHandler(
        onTap = { commitSpace() },
        onCursorMove = { direction ->
            val ic = inputConnectionProvider() ?: return@CursorSwipeTouchHandler
            vibrate()
            // commitText newCursorPosition is relative to end of inserted text (empty string = current pos):
            // 2 = one right, -1 = one left (matches DictateInputMethodService lines 746/751)
            val cursorOffset = if (direction > 0) 2 else -1
            ic.commitText("", cursorOffset)
        }
    )

    init {
        view.callback = this
        refreshLayout()
    }

    // ── KeyActionCallback implementation ──

    override fun onKeyAction(keyDef: QwertzKeyDef) {
        when (keyDef.keyAction) {
            KeyAction.COMMIT_TEXT -> handleCharacterInput(keyDef)
            KeyAction.BACKSPACE -> handleBackspace()
            KeyAction.ENTER -> handleEnter()
            KeyAction.SHIFT -> handleShiftToggle()
            KeyAction.SWITCH_LAYOUT -> handleLayoutSwitch(keyDef)
            KeyAction.SPACE -> handleSpace()
            KeyAction.TAB -> handleTab()
            KeyAction.CTRL_MODIFIER -> handleCtrlToggle()
        }
    }

    override fun onKeyLongPress(keyDef: QwertzKeyDef) {
        when (keyDef.keyAction) {
            KeyAction.BACKSPACE -> {
                acceleratingRepeatHandler.start(deleteOneCharacter)
            }
            else -> { /* no long-press behavior for other keys */ }
        }
    }

    override fun onKeyReleased(keyDef: QwertzKeyDef) {
        when (keyDef.keyAction) {
            KeyAction.BACKSPACE -> {
                acceleratingRepeatHandler.stop()
            }
            else -> { /* no release behavior for other keys */ }
        }
    }

    // ── Public API ──

    /**
     * Sets the keyboard layout externally (e.g. auto-show numbers for number fields).
     */
    fun setLayout(layout: QwertzKeyboardLayout) {
        currentLayout = layout
        refreshLayout()
    }

    /**
     * Checks if the cursor is at the beginning of the text field (or the field is empty)
     * and activates SINGLE shift so the first letter is automatically capitalized.
     * Call this when the QWERTZ keyboard becomes visible.
     */
    fun checkAutoShiftAtCursor() {
        if (shiftState != ShiftState.OFF || currentLayout != QwertzKeyboardLayout.QWERTZ) return

        val ic = inputConnectionProvider() ?: return
        val before = ic.getTextBeforeCursor(1, 0)
        // Empty field or cursor at position 0: no text before cursor
        if (before == null || before.isEmpty()) {
            shiftState = ShiftState.SINGLE
            refreshLayout()
        }
    }

    /**
     * Applies theme colors to the keyboard view and updates modifier visuals.
     *
     * @param accentColor primary accent (Enter, active Shift/Ctrl)
     * @param accentColorMedium medium shade (letters, numbers, space)
     * @param accentColorDark dark shade (Backspace, modifiers)
     */
    fun applyColors(accentColor: Int, accentColorMedium: Int, accentColorDark: Int) {
        view.applyColors(accentColor, accentColorMedium, accentColorDark)
        updateCtrlVisual()
    }

    // ── Input handling ──

    private fun handleCharacterInput(keyDef: QwertzKeyDef) {
        val ic = inputConnectionProvider() ?: return
        vibrate()

        if (ctrlActive) {
            handleCtrlCombo(keyDef, ic)
            ctrlActive = false
            updateCtrlVisual()
            return
        }

        val text = when (shiftState) {
            ShiftState.OFF -> keyDef.output
            ShiftState.SINGLE, ShiftState.CAPS_LOCK -> {
                // shiftOutput overrides standard uppercase logic (e.g. eszett stays eszett)
                keyDef.shiftOutput ?: keyDef.output?.uppercase()
            }
        }

        if (text != null) {
            ic.commitText(text, 1)
        }

        resetShiftIfSingle()
    }

    private fun handleCtrlCombo(keyDef: QwertzKeyDef, ic: InputConnection) {
        when (keyDef.output?.lowercase()) {
            "a" -> ic.performContextMenuAction(android.R.id.selectAll)
            "c" -> ic.performContextMenuAction(android.R.id.copy)
            "v" -> ic.performContextMenuAction(android.R.id.paste)
            "x" -> ic.performContextMenuAction(android.R.id.cut)
            "z" -> {
                // undo is available on API 30+, but performContextMenuAction handles it gracefully
                @Suppress("NewApi")
                ic.performContextMenuAction(android.R.id.undo)
            }
        }
    }

    private fun handleBackspace() {
        vibrate()
        deleteOneCharacter()
    }

    private fun handleEnter() {
        vibrate()
        performEnterAction()
        // Auto-shift after enter: capitalize the first letter of a new line
        if (shiftState == ShiftState.OFF && currentLayout == QwertzKeyboardLayout.QWERTZ) {
            shiftState = ShiftState.SINGLE
            refreshLayout()
        }
    }

    private fun handleSpace() {
        // Space tap is handled by CursorSwipeTouchHandler.onTap (same commitSpace() call).
        // This is called by the click listener as a fallback when the touch handler
        // doesn't consume events.
        commitSpace()
    }

    /**
     * Commits a space character, resets single-shift, and checks for auto-shift.
     * Shared between the click listener fallback (handleSpace) and CursorSwipeTouchHandler.onTap.
     */
    private fun commitSpace() {
        val ic = inputConnectionProvider() ?: return
        vibrate()
        ic.commitText(" ", 1)
        // Reset shift first (e.g. after typing a shifted letter then space),
        // then check for auto-shift. Order matters: checkAutoShiftAfterSpace
        // only activates when shiftState is OFF.
        resetShiftIfSingle()
        checkAutoShiftAfterSpace(ic)
    }

    private fun handleTab() {
        val ic = inputConnectionProvider() ?: return
        vibrate()
        ic.commitText("\t", 1)
    }

    // ── Auto-Shift ──

    /**
     * Activates SINGLE shift when the user types a space after a sentence-ending
     * punctuation mark (. ! ?), so the next letter is automatically capitalized.
     *
     * Checks the two characters before the cursor: if they match ". " or "! " or "? ",
     * shift is set to SINGLE. Only applies in QWERTZ layout and when shift is currently OFF.
     */
    private fun checkAutoShiftAfterSpace(ic: InputConnection) {
        if (shiftState != ShiftState.OFF || currentLayout != QwertzKeyboardLayout.QWERTZ) return

        // getTextBeforeCursor(2) returns up to 2 chars before the cursor position.
        // After commitText(" ", 1) the cursor is after the space, so these 2 chars
        // are the punctuation + the space we just inserted.
        val before = ic.getTextBeforeCursor(2, 0) ?: return
        if (before.length == 2 && before[1] == ' ' && before[0] in SENTENCE_END_CHARS) {
            shiftState = ShiftState.SINGLE
            refreshLayout()
        }
    }

    // ── State machine: Shift ──

    private fun handleShiftToggle() {
        vibrate()
        shiftState = shiftState.next()
        refreshLayout()
    }

    /**
     * Resets shift from SINGLE back to OFF after a character is committed.
     * CAPS_LOCK stays active until explicitly toggled.
     */
    private fun resetShiftIfSingle() {
        if (shiftState == ShiftState.SINGLE) {
            shiftState = ShiftState.OFF
            refreshLayout()
        }
    }

    // ── State machine: Ctrl ──

    private fun handleCtrlToggle() {
        vibrate()
        ctrlActive = !ctrlActive
        updateCtrlVisual()
    }

    private fun updateCtrlVisual() {
        val ctrlButton = view.findButtonForAction(KeyAction.CTRL_MODIFIER) ?: return
        // Active ctrl: accent color; inactive: uses default color tier (DARK)
        // We read the current colors from the view's applied colors via the button's current tint
        // For simplicity, toggle between the known color tiers
        if (ctrlActive) {
            // Use the same accent color as the Enter button (ACCENT tier)
            val enterButton = view.findButtonForAction(KeyAction.ENTER)
            val accentColor = enterButton?.backgroundTintList?.defaultColor
            if (accentColor != null) {
                ctrlButton.backgroundTintList = ColorStateList.valueOf(accentColor)
            }
        } else {
            // Use the same dark color as Backspace (DARK tier)
            val backspaceButton = view.findButtonForAction(KeyAction.BACKSPACE)
            val darkColor = backspaceButton?.backgroundTintList?.defaultColor
            if (darkColor != null) {
                ctrlButton.backgroundTintList = ColorStateList.valueOf(darkColor)
            }
        }
    }

    // ── Layout switching ──

    private fun handleLayoutSwitch(keyDef: QwertzKeyDef) {
        vibrate()
        // Layout switching uses the button label to determine the target layout.
        // Labels are defined in QwertzLayoutProvider: "123", "=\<", "ABC"
        currentLayout = when (keyDef.label) {
            LABEL_NUMBERS -> QwertzKeyboardLayout.NUMBERS
            LABEL_SYMBOLS -> QwertzKeyboardLayout.SYMBOLS
            else -> QwertzKeyboardLayout.QWERTZ // "ABC" and any unknown label -> QWERTZ
        }
        // Reset shift when switching away from QWERTZ
        if (currentLayout != QwertzKeyboardLayout.QWERTZ) {
            shiftState = ShiftState.OFF
        }
        refreshLayout()
    }

    companion object {
        /** Label of the "123" switch button (QWERTZ->NUMBERS, SYMBOLS->NUMBERS). */
        private const val LABEL_NUMBERS = "123"
        /** Label of the "=\<" switch button (NUMBERS->SYMBOLS). */
        private const val LABEL_SYMBOLS = "=\\<"
        /** Characters that end a sentence and trigger auto-shift. */
        private val SENTENCE_END_CHARS = charArrayOf('.', '!', '?')
    }

    // ── Layout refresh ──

    @SuppressLint("ClickableViewAccessibility")
    private fun refreshLayout() {
        val shiftActive = shiftState != ShiftState.OFF
        val keys = QwertzLayoutProvider.getLayout(currentLayout, shiftActive)
        view.buildLayout(keys)

        // Re-attach the cursor swipe handler to the space button.
        // Wraps both press animation and swipe handling in a single touch listener
        // because setOnTouchListener replaces the view's default touch listener.
        val spaceButton = view.findButtonForAction(KeyAction.SPACE)
        spaceButton?.setOnTouchListener { v, event ->
            view.keyPressAnimator.handlePressAnimationEvent(v, event)
            cursorSwipeTouchHandler.onTouch(v, event)
        }

        // Update shift visuals (icon + letter case)
        if (currentLayout == QwertzKeyboardLayout.QWERTZ) {
            view.updateShiftVisuals(shiftActive, shiftState == ShiftState.CAPS_LOCK)
        }

        // Update ctrl visual state
        updateCtrlVisual()
    }
}
