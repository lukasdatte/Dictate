package net.devemperor.dictate.keyboard

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.MotionEvent
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.view.inputmethod.InputConnection

/**
 * Handles enter button overlay character selection via touch-drag.
 *
 * Extracted from DictateInputMethodService enterButton.setOnTouchListener.
 * When the overlay is visible (triggered by long-press), the user can drag
 * across overlay characters and release to commit the selected one.
 *
 * @param overlayCharactersLl the LinearLayout containing overlay character TextViews
 * @param inputConnectionProvider provides current InputConnection
 * @param accentColorProvider provides current accent color for highlighting
 * @param keyPressAnimationHandler optional handler for press animations
 */
class EnterOverlayHandler(
    private val overlayCharactersLl: LinearLayout,
    private val inputConnectionProvider: () -> InputConnection?,
    private val accentColorProvider: () -> Int,
    private val keyPressAnimationHandler: ((View, MotionEvent) -> Unit)? = null
) : View.OnTouchListener {

    private var selectedCharacter: TextView? = null

    override fun onTouch(v: View, event: MotionEvent): Boolean {
        keyPressAnimationHandler?.invoke(v, event)

        if (overlayCharactersLl.visibility != View.VISIBLE) return false

        when (event.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                for (i in 0 until overlayCharactersLl.childCount) {
                    val charView = overlayCharactersLl.getChildAt(i) as TextView
                    if (isPointInsideView(event.rawX, charView)) {
                        if (selectedCharacter != charView) {
                            selectedCharacter = charView
                            highlightSelectedCharacter(charView)
                        }
                        break
                    }
                }
            }

            MotionEvent.ACTION_UP -> {
                selectedCharacter?.let { selected ->
                    inputConnectionProvider()?.commitText(selected.text, 1)
                    selectedCharacter = null
                }
                overlayCharactersLl.visibility = View.GONE
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                selectedCharacter = null
                overlayCharactersLl.visibility = View.GONE
                return true
            }
        }
        return false
    }

    private fun isPointInsideView(x: Float, view: View): Boolean {
        val location = IntArray(2)
        view.getLocationOnScreen(location)
        return x > location[0] && x < location[0] + view.width
    }

    private fun highlightSelectedCharacter(selectedView: TextView) {
        val accentColor = accentColorProvider()
        val accentColorDark = Color.argb(
            Color.alpha(accentColor),
            (Color.red(accentColor) * 0.8f).toInt(),
            (Color.green(accentColor) * 0.8f).toInt(),
            (Color.blue(accentColor) * 0.8f).toInt()
        )
        for (i in 0 until overlayCharactersLl.childCount) {
            val charView = overlayCharactersLl.getChildAt(i) as TextView
            val bg = charView.background as GradientDrawable
            bg.setColor(if (charView == selectedView) accentColorDark else accentColor)
        }
    }
}
