package net.devemperor.dictate.keyboard

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.util.AttributeSet
import android.util.TypedValue
import android.view.MotionEvent
import android.view.ViewGroup
import android.widget.LinearLayout
import com.google.android.material.button.MaterialButton
import net.devemperor.dictate.R

/**
 * Custom ViewGroup that renders a keyboard layout from QwertzKeyDef lists.
 *
 * Builds a horizontal LinearLayout per row with weighted MaterialButtons.
 * No XML layout needed -- everything is built programmatically from KeyDef lists.
 *
 * Responsibilities:
 * - Create MaterialButtons and arrange them in LinearLayouts
 * - Apply press animations
 * - Set button colors (by ColorTier)
 * - Layout switching (clearViews + rebuild)
 *
 * NOT responsible for:
 * - Input handling (delegated to KeyActionCallback)
 * - State management (Shift, Caps, Ctrl)
 */
class QwertzKeyboardView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    /**
     * Callback interface for key actions.
     * Naming: "Callback" for consistency with RecordingCallback, PipelineCallback.
     */
    interface KeyActionCallback {
        fun onKeyAction(keyDef: QwertzKeyDef)
        fun onKeyLongPress(keyDef: QwertzKeyDef)
        fun onKeyReleased(keyDef: QwertzKeyDef)
    }

    /** Press animation handler, shared instance. Set [KeyPressAnimator.animationsEnabled] from outside. */
    val keyPressAnimator = KeyPressAnimator()

    /** Callback for key events; set by the controller. */
    var callback: KeyActionCallback? = null

    /** Current key definitions, stored for color updates and shift visual changes. */
    private var currentKeys: List<List<QwertzKeyDef>> = emptyList()

    /** Flat mapping from QwertzKeyDef to its MaterialButton, for efficient lookup. */
    private val buttonMap = mutableMapOf<QwertzKeyDef, MaterialButton>()

    /** Current color values for theming. */
    private var accentColor: Int = DEFAULT_ACCENT
    private var accentColorMedium: Int = DEFAULT_MEDIUM
    private var accentColorDark: Int = DEFAULT_DARK

    init {
        orientation = VERTICAL
        val hPad = dpToPx(4)
        val vPad = dpToPx(2)
        setPadding(hPad, vPad, hPad, vPad)
    }

    /**
     * Builds the keyboard UI from key definitions. Removes all existing views first.
     *
     * @param keys rows of key definitions
     */
    fun buildLayout(keys: List<List<QwertzKeyDef>>) {
        removeAllViews()
        buttonMap.clear()
        currentKeys = keys

        keys.forEachIndexed { rowIndex, row ->
            val rowLayout = createRowLayout(rowIndex)

            for (keyDef in row) {
                val button = createKeyButton(keyDef)
                val lp = LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, keyDef.widthWeight)
                val margin = dpToPx(2)
                lp.setMargins(margin, 0, margin, 0)
                rowLayout.addView(button, lp)
                buttonMap[keyDef] = button
            }

            val rowLp = LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dpToPx(ROW_HEIGHT_DP)
            )
            val verticalMargin = dpToPx(1)
            rowLp.setMargins(0, verticalMargin, 0, verticalMargin)
            addView(rowLayout, rowLp)
        }

        applyColorsInternal()
    }

    /**
     * Sets the theme colors and applies them to all buttons.
     *
     * @param accentColor primary accent (Enter, active Shift/Ctrl)
     * @param accentColorMedium medium shade (letters, numbers, space)
     * @param accentColorDark dark shade (Backspace, modifiers)
     */
    fun applyColors(accentColor: Int, accentColorMedium: Int, accentColorDark: Int) {
        this.accentColor = accentColor
        this.accentColorMedium = accentColorMedium
        this.accentColorDark = accentColorDark
        applyColorsInternal()
    }

    /**
     * Updates shift-related visuals: letter case and shift icon.
     *
     * @param shiftActive true if shift is currently active (SINGLE or CAPS_LOCK)
     * @param capsLock true if caps lock is engaged
     */
    fun updateShiftVisuals(shiftActive: Boolean, capsLock: Boolean) {
        for ((keyDef, button) in buttonMap) {
            // Update letter labels to reflect shift state
            if (keyDef.keyAction == KeyAction.COMMIT_TEXT && keyDef.output != null) {
                val display = if (shiftActive) {
                    keyDef.shiftOutput ?: keyDef.output.uppercase()
                } else {
                    keyDef.output
                }
                button.text = display
            }

            // Update shift icon and background:
            // OFF: outline icon, dark background
            // SINGLE: filled icon, accent background
            // CAPS_LOCK: filled icon, accent background, underline indicator
            if (keyDef.keyAction == KeyAction.SHIFT && keyDef.iconResId != 0) {
                val iconRes = if (shiftActive) {
                    R.drawable.ic_keyboard_shift_filled_24
                } else {
                    R.drawable.ic_keyboard_shift_24
                }
                button.setIconResource(iconRes)

                val bgColor = if (shiftActive) accentColor else accentColorDark
                button.backgroundTintList = ColorStateList.valueOf(bgColor)

                // CAPS_LOCK gets a white border stroke as visual indicator
                if (capsLock) {
                    button.strokeWidth = dpToPx(CAPS_LOCK_UNDERLINE_DP)
                    button.strokeColor = ColorStateList.valueOf(Color.WHITE)
                } else {
                    button.strokeWidth = 0
                }
            }
        }
    }

    /**
     * Finds the MaterialButton for a given key action. Useful for the controller
     * to access specific buttons (e.g., Ctrl button for visual toggle).
     *
     * @param action the key action to search for
     * @return the first matching button, or null
     */
    fun findButtonForAction(action: KeyAction): MaterialButton? {
        val entry = buttonMap.entries.firstOrNull { it.key.keyAction == action }
        return entry?.value
    }

    // ── Private helpers ──

    private fun createRowLayout(rowIndex: Int): LinearLayout {
        return LinearLayout(context).apply {
            orientation = HORIZONTAL

            // Row 2 (ASDF row) gets half-key padding for staggered layout,
            // but only on the 5-row QWERTZ layout (NUMBERS/SYMBOLS have 4 rows)
            if (rowIndex == 2 && currentKeys.size == QWERTZ_ROW_COUNT) {
                val halfKeyPad = dpToPx(STAGGER_PADDING_DP)
                setPadding(halfKeyPad, 0, halfKeyPad, 0)
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun createKeyButton(keyDef: QwertzKeyDef): MaterialButton {
        val button = MaterialButton(context, null, com.google.android.material.R.attr.materialButtonOutlinedStyle)

        // Remove Material default minimum sizes so weight-based sizing works
        button.minHeight = 0
        button.minimumHeight = 0
        button.minWidth = 0
        button.minimumWidth = 0

        // Remove default padding for compact layout
        button.setPadding(0, 0, 0, 0)
        button.insetTop = 0
        button.insetBottom = 0

        // Remove stroke (outlined style has a border by default)
        button.strokeWidth = 0

        // Set corner radius for slightly rounded keys
        button.cornerRadius = dpToPx(CORNER_RADIUS_DP)

        // Text or icon
        if (keyDef.iconResId != 0) {
            button.setIconResource(keyDef.iconResId)
            button.iconGravity = MaterialButton.ICON_GRAVITY_TEXT_START
            button.iconPadding = 0
            button.iconSize = dpToPx(ICON_SIZE_DP)
            // Tint icon to white for contrast
            button.iconTint = ColorStateList.valueOf(Color.WHITE)
            button.text = ""
        } else {
            button.text = keyDef.label
            button.setTextColor(Color.WHITE)
            val textSize = if (keyDef.label.length == 1 && keyDef.keyAction == KeyAction.COMMIT_TEXT) {
                TEXT_SIZE_LETTER_SP
            } else {
                TEXT_SIZE_FUNCTIONAL_SP
            }
            button.setTextSize(TypedValue.COMPLEX_UNIT_SP, textSize)
        }

        // Accessibility: content description for icon-only and functional keys
        button.contentDescription = getContentDescription(keyDef)

        // Click listener
        button.setOnClickListener {
            callback?.onKeyAction(keyDef)
        }

        // Touch listener for press animation and repeatable key handling
        button.setOnTouchListener { v, event ->
            // Press animation
            keyPressAnimator.handlePressAnimationEvent(v, event)

            // Repeatable key long-press / release delegation
            if (keyDef.repeatable) {
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        // Start long-press detection via postDelayed
                        v.tag = Runnable { callback?.onKeyLongPress(keyDef) }
                        v.postDelayed(v.tag as Runnable, LONG_PRESS_DELAY_MS)
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        // Cancel pending long-press and notify release
                        (v.tag as? Runnable)?.let { v.removeCallbacks(it) }
                        callback?.onKeyReleased(keyDef)
                    }
                }
            }

            false // Don't consume -- let click listener work
        }

        return button
    }

    /**
     * Returns an accessibility content description for the given key.
     * For character keys, the label itself is sufficient. For icon-only functional keys,
     * a descriptive string is returned so screen readers can announce the key's function.
     */
    private fun getContentDescription(keyDef: QwertzKeyDef): String {
        // Character keys: the label is self-descriptive
        if (keyDef.keyAction == KeyAction.COMMIT_TEXT && keyDef.label.isNotEmpty()) {
            return keyDef.label
        }
        // Functional and icon keys
        return when (keyDef.keyAction) {
            KeyAction.BACKSPACE -> "Backspace"
            KeyAction.ENTER -> "Enter"
            KeyAction.SHIFT -> "Shift"
            KeyAction.SPACE -> "Space"
            KeyAction.TAB -> "Tab"
            KeyAction.CTRL_MODIFIER -> "Ctrl"
            KeyAction.SWITCH_LAYOUT -> keyDef.label.ifEmpty { "Switch layout" }
            KeyAction.COMMIT_TEXT -> keyDef.output ?: keyDef.label
        }
    }

    private fun applyColorsInternal() {
        for ((keyDef, button) in buttonMap) {
            val bgColor = when (keyDef.colorTier) {
                ColorTier.ACCENT -> accentColor
                ColorTier.MEDIUM -> accentColorMedium
                ColorTier.DARK -> accentColorDark
            }
            button.backgroundTintList = ColorStateList.valueOf(bgColor)
        }
    }

    private fun dpToPx(dp: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            dp.toFloat(),
            resources.displayMetrics
        ).toInt()
    }

    companion object {
        /** Row height in dp. */
        private const val ROW_HEIGHT_DP = 42

        /** Left/right padding for staggered row 2 (ASDF) in dp. */
        private const val STAGGER_PADDING_DP = 12

        /** Button corner radius in dp. */
        private const val CORNER_RADIUS_DP = 6

        /** Icon size in dp for functional keys. */
        private const val ICON_SIZE_DP = 20

        /** Text size for single-character keys (letters, digits). */
        private const val TEXT_SIZE_LETTER_SP = 14f

        /** Text size for multi-character functional keys (123, ABC, Ctrl). */
        private const val TEXT_SIZE_FUNCTIONAL_SP = 12f

        /** Long-press delay before triggering repeat in ms. */
        private const val LONG_PRESS_DELAY_MS = 400L

        /** Default accent color (Material Blue). */
        private const val DEFAULT_ACCENT = 0xFF2196F3.toInt()

        /** Default medium color. */
        private const val DEFAULT_MEDIUM = 0xFF555555.toInt()

        /** Default dark color. */
        private const val DEFAULT_DARK = 0xFF333333.toInt()

        /** Number of rows in the QWERTZ layout (used to detect stagger-eligible layouts). */
        private const val QWERTZ_ROW_COUNT = 5

        /** Stroke width in dp used as underline indicator for CAPS_LOCK state. */
        private const val CAPS_LOCK_UNDERLINE_DP = 2
    }
}
