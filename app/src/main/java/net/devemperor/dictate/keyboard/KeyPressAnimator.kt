package net.devemperor.dictate.keyboard

import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.Interpolator

/**
 * Extracted press animation logic for keyboard buttons.
 *
 * Replaces the private handlePressAnimationEvent/animateKeyPress methods
 * in DictateInputMethodService. Can be shared between QwertzKeyboardView
 * and the existing keyboard buttons.
 *
 * @param animationsEnabled whether animations are active; when false, scale is reset immediately
 */
class KeyPressAnimator(
    var animationsEnabled: Boolean = true
) {
    companion object {
        /** Scale factor when a key is pressed. */
        const val KEY_PRESS_SCALE = 0.92f
        /** Animation duration in milliseconds. */
        const val KEY_PRESS_ANIM_DURATION = 80L
        /** Interpolator for smooth deceleration. */
        private val INTERPOLATOR: Interpolator = DecelerateInterpolator()
    }

    /**
     * Sets an OnTouchListener on the given [view] that applies press/release animation.
     *
     * The listener returns false so that other touch handling (click, long-press)
     * still works normally.
     */
    fun applyPressAnimation(view: View) {
        view.setOnTouchListener { v, event ->
            handlePressAnimationEvent(v, event)
            false
        }
    }

    /**
     * Handles a touch event for press animation. Call this from an existing
     * OnTouchListener if you need to combine animation with other touch handling.
     */
    fun handlePressAnimationEvent(view: View, event: MotionEvent) {
        if (!animationsEnabled) {
            view.animate().cancel()
            view.scaleX = 1f
            view.scaleY = 1f
            return
        }
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> animateKeyPress(view, pressed = true)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> animateKeyPress(view, pressed = false)
        }
    }

    /**
     * Animates a key press or release on the given [view].
     *
     * @param view the view to animate
     * @param pressed true for press-down, false for release
     */
    fun animateKeyPress(view: View, pressed: Boolean) {
        if (!animationsEnabled) {
            view.animate().cancel()
            if (view.scaleX != 1f) view.scaleX = 1f
            if (view.scaleY != 1f) view.scaleY = 1f
            return
        }
        val targetScale = if (pressed) KEY_PRESS_SCALE else 1f
        view.animate()
            .scaleX(targetScale)
            .scaleY(targetScale)
            .setDuration(KEY_PRESS_ANIM_DURATION)
            .setInterpolator(INTERPOLATOR)
            .start()
    }
}
