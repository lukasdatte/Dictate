package net.devemperor.dictate.keyboard

import android.annotation.SuppressLint
import android.view.inputmethod.InputConnection
import net.devemperor.dictate.state.insertion.ControlOp
import net.devemperor.dictate.state.insertion.InsertionPolicy
import net.devemperor.dictate.state.insertion.InsertionRequest
import net.devemperor.dictate.state.insertion.InsertionService

/**
 * Controller for the QWERTZ keyboard. Implements the state machine for shift
 * and layout switching, and dispatches all key actions to the InputConnection.
 *
 * Responsibilities:
 * - Shift state machine (OFF -> SINGLE -> CAPS_LOCK -> OFF)
 * - Layout switching (QWERTZ <-> NUMBERS <-> SYMBOLS)
 * - Character input with shift handling
 * - Backspace repeat delegation to AcceleratingRepeatHandler
 * - Space cursor-swipe delegation to CursorSwipeTouchHandler
 * - Color theming delegation
 *
 * NOT responsible for:
 * - View creation (QwertzKeyboardView)
 * - Layout definitions (QwertzLayoutProvider)
 * - InputConnection lifecycle (provided by the service)
 *
 * @param view the keyboard view to control
 * @param inputConnectionProvider provides the current InputConnection (may be null).
 *   READ-only here (cursor/auto-shift text peeks); all WRITES go through
 *   [insertionService] (P4 keystroke-path migration).
 * @param insertionService supplies the single InsertionService that owns all
 *   host-IC writes (may be null when the IME-View is detached → write is a
 *   no-op). Character/space commits and cursor moves route through it with the
 *   KEYSTROKE policy / ControlOp, reproducing the legacy raw-commit behaviour.
 * @param vibrate haptic feedback callback
 * @param deleteOneCharacter callback for single character deletion
 * @param performEnterAction callback for enter/IME action
 */
class QwertzKeyboardController(
    private val view: QwertzKeyboardView,
    private val inputConnectionProvider: () -> InputConnection?,
    private val insertionService: () -> InsertionService?,
    private val vibrate: () -> Unit,
    private val deleteOneCharacter: () -> Unit,
    private val performEnterAction: () -> Unit,
    private val onCloseKeyboard: () -> Unit,
    private val onRecord: () -> Unit = {},
    private val onLayoutRebuilt: () -> Unit = {}
) : QwertzKeyboardView.KeyActionCallback {

    // ── State ──

    private var shiftState = ShiftState.OFF
    private var currentLayout = QwertzKeyboardLayout.QWERTZ

    // ── Handlers ──

    private val acceleratingRepeatHandler = AcceleratingRepeatHandler(
        vibrate = vibrate
    )

    private val cursorSwipeTouchHandler = CursorSwipeTouchHandler(
        onTap = { commitSpace() },
        onCursorMove = { direction ->
            // READ the IC only to keep the legacy null-guard (no IC = no-op);
            // the WRITE goes through the InsertionService (P4).
            inputConnectionProvider() ?: return@CursorSwipeTouchHandler
            vibrate()
            // Pass the raw swipe direction (>0 = right, <0 = left). The service
            // owns the selection-safe, grapheme-clamped move (F-021).
            insertionService()?.control(ControlOp.CursorMove(direction))
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
            KeyAction.TAB, KeyAction.CTRL_MODIFIER -> { /* not used in current layouts */ }
            KeyAction.CLOSE_KEYBOARD -> { vibrate(); onCloseKeyboard() }
            KeyAction.RECORD -> { vibrate(); onRecord() }
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
     * Applies theme colors to the keyboard view.
     *
     * @param accentColor primary accent (Enter, active Shift)
     * @param accentColorMedium medium shade (letters, numbers, space)
     * @param accentColorDark dark shade (Backspace, modifiers)
     */
    fun applyColors(accentColor: Int, accentColorMedium: Int, accentColorDark: Int) {
        view.applyColors(accentColor, accentColorMedium, accentColorDark)
    }

    // ── Input handling ──

    private fun handleCharacterInput(keyDef: QwertzKeyDef) {
        // READ the IC only to keep the legacy null-guard (no IC = no-op); the
        // character WRITE goes through the InsertionService (P4).
        inputConnectionProvider() ?: return
        vibrate()

        val text = when (shiftState) {
            ShiftState.OFF -> keyDef.output
            ShiftState.SINGLE, ShiftState.CAPS_LOCK -> {
                // shiftOutput overrides standard uppercase logic (e.g. eszett stays eszett)
                keyDef.shiftOutput ?: keyDef.output?.uppercase()
            }
        }

        if (text != null) {
            insertionService()?.insert(
                InsertionRequest(text, null, InsertionPolicy.KEYSTROKE, null, null))
        }

        resetShiftIfSingle()
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
        // `ic` is still READ here — the auto-shift check below peeks the text
        // before the cursor. Only the space WRITE goes through the
        // InsertionService (P4).
        val ic = inputConnectionProvider() ?: return
        vibrate()
        insertionService()?.insert(
            InsertionRequest(" ", null, InsertionPolicy.KEYSTROKE, null, null))
        // Reset shift first (e.g. after typing a shifted letter then space),
        // then check for auto-shift. Order matters: checkAutoShiftAfterSpace
        // only activates when shiftState is OFF.
        resetShiftIfSingle()
        checkAutoShiftAfterSpace(ic)
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

        // Notify caller so external state (e.g. recording icon) can be re-applied
        onLayoutRebuilt()
    }
}
