package net.devemperor.dictate.keyboard

import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputConnection
import java.util.Collections

/**
 * Handles backspace button touch gestures: swipe-left-to-select-words.
 *
 * Extracted from DictateInputMethodService backspaceButton.setOnTouchListener.
 * Pattern: analogous to [CursorSwipeTouchHandler] — pure View.OnTouchListener with callbacks.
 *
 * Swipe behavior:
 * - Swipe left while holding backspace → progressively select words to the left
 * - Swipe right → reduce selection
 * - Release with selection → delete selected text
 * - Release without selection → normal click/long-press behavior
 *
 * @param inputConnectionProvider provides current InputConnection (may be null)
 * @param vibrate callback for haptic feedback
 * @param onDeleteCancelled called when swipe-select starts to cancel any running auto-delete
 * @param keyPressAnimationHandler optional handler for press animations on the button
 */
class BackspaceSwipeHandler(
    private val inputConnectionProvider: () -> InputConnection?,
    private val vibrate: () -> Unit,
    private val onDeleteCancelled: () -> Unit,
    private val keyPressAnimationHandler: ((View, MotionEvent) -> Unit)? = null
) : View.OnTouchListener {

    // Swipe-to-select-words state
    private var isSwipeSelectingWords = false
    private var backspaceStartX = 0f
    private var swipeBaseCursor = -1
    private var swipeWordBoundaries: List<Int>? = null
    private var swipeSelectedSteps = 0

    override fun onTouch(v: View, event: MotionEvent): Boolean {
        keyPressAnimationHandler?.invoke(v, event)

        val ic = inputConnectionProvider()
        val density = v.resources.displayMetrics.density
        val stepPx = (24f * density + 0.5f).toInt()
        val activationPx = maxOf(
            ViewConfiguration.get(v.context).scaledTouchSlop,
            (8f * density + 0.5f).toInt()
        )

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                isSwipeSelectingWords = false
                swipeSelectedSteps = 0
                swipeWordBoundaries = null
                swipeBaseCursor = -1
                backspaceStartX = event.x
                return false // allow click/long-press detection
            }

            MotionEvent.ACTION_MOVE -> {
                val dx = event.x - backspaceStartX

                if (dx < -activationPx) {
                    if (!isSwipeSelectingWords) {
                        isSwipeSelectingWords = true

                        // Cancel system long-press to avoid auto-delete kick-in
                        v.cancelLongPress()
                        v.parent?.requestDisallowInterceptTouchEvent(true)

                        // Stop auto-delete if it was started via long-press
                        onDeleteCancelled()

                        if (ic != null) {
                            val et = ic.getExtractedText(ExtractedTextRequest(), 0)
                            if (et?.text != null) {
                                swipeBaseCursor = maxOf(et.selectionStart, et.selectionEnd)
                                val before = et.text.subSequence(0, swipeBaseCursor).toString()
                                swipeWordBoundaries = computeWordBoundaries(before)
                            }
                        }
                        if (swipeWordBoundaries == null) {
                            swipeWordBoundaries = Collections.singletonList(0)
                            swipeBaseCursor = 0
                        }
                    }

                    // Step size defines when next word gets added to selection
                    val boundaries = swipeWordBoundaries
                    if (ic != null && boundaries != null && boundaries.isNotEmpty()) {
                        val maxSteps = boundaries.size - 1
                        val steps = ((-dx).toInt() / stepPx).coerceIn(0, maxSteps)

                        if (steps != swipeSelectedSteps) {
                            swipeSelectedSteps = steps
                            ic.setSelection(boundaries[steps], swipeBaseCursor)
                            vibrate()
                        }
                    }
                    return true // consume while swipe-selecting
                } else if (isSwipeSelectingWords) {
                    // Moving back right reduces selection
                    val boundaries = swipeWordBoundaries
                    if (ic != null && boundaries != null && boundaries.isNotEmpty()) {
                        val steps = ((-dx).toInt() / stepPx).coerceIn(0, boundaries.size - 1)

                        if (steps != swipeSelectedSteps) {
                            swipeSelectedSteps = steps
                            ic.setSelection(boundaries[steps], swipeBaseCursor)
                            vibrate()
                        }
                        if (steps == 0) {
                            ic.setSelection(swipeBaseCursor, swipeBaseCursor)
                        }
                    }
                    return true
                }

                return false // not yet swiping -> keep default handling
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (isSwipeSelectingWords) {
                    if (ic != null) {
                        if (swipeSelectedSteps > 0) {
                            ic.commitText("", 1)
                            vibrate()
                        } else {
                            ic.setSelection(swipeBaseCursor, swipeBaseCursor)
                        }
                    }
                    isSwipeSelectingWords = false
                    return true // consume
                }
                return false // no swipe-select -> allow click/long-press outcomes
            }

            else -> return false
        }
    }

    companion object {
        /**
         * Computes progressive word boundaries to the left of the cursor for swipe selection.
         * Returns absolute start indices (0..cursor): boundaries[0] = cursor,
         * boundaries[1] = start of previous "word incl. preceding spaces", etc.
         */
        fun computeWordBoundaries(before: String): List<Int> {
            val res = mutableListOf<Int>()
            var pos = before.length
            res.add(pos)

            while (pos > 0) {
                var i = pos

                // 1) skip whitespace to the left
                while (i > 0 && before[i - 1].isWhitespace()) i--

                // 2) skip non-alnum punctuation to the left
                while (i > 0) {
                    val c = before[i - 1]
                    if (c.isLetterOrDigit() || c.isWhitespace()) break
                    i--
                }

                // 3) skip letters/digits (the word)
                while (i > 0 && before[i - 1].isLetterOrDigit()) i--

                // 4) also include preceding spaces so each step removes "space + word"
                while (i > 0 && before[i - 1].isWhitespace()) i--

                if (i == pos) i--
                pos = i
                res.add(pos)
            }

            return res
        }
    }
}
