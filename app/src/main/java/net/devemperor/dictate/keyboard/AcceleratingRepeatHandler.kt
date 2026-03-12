package net.devemperor.dictate.keyboard

import android.os.Handler
import android.os.Looper

/**
 * Reusable handler for accelerating repeat on long-press (e.g. Backspace).
 *
 * Extracted from the backspace long-press logic in DictateInputMethodService.
 * Starts with a base delay and accelerates over time through configurable thresholds.
 *
 * Usage:
 * ```
 * val handler = AcceleratingRepeatHandler()
 * // on long-press start:
 * handler.start { deleteOneCharacter() }
 * // on release:
 * handler.stop()
 * ```
 *
 * @param handler the Handler to post repeat callbacks on (default: main looper)
 * @param vibrate optional callback invoked when the repeat rate changes (speed-up feedback)
 */
class AcceleratingRepeatHandler(
    private val handler: Handler = Handler(Looper.getMainLooper()),
    private val vibrate: (() -> Unit)? = null
) {
    private var isRepeating = false
    private var startTime = 0L
    private var currentDelay = BASE_DELAY
    private var repeatRunnable: Runnable? = null

    companion object {
        /** Initial delay between repeats in milliseconds. */
        private const val BASE_DELAY = 50L
        /** Thresholds: elapsed time (ms) -> new delay (ms). */
        private val ACCELERATION_THRESHOLDS = listOf(
            1500L to 25L,
            3000L to 10L,
            5000L to 5L
        )
    }

    /**
     * Starts repeating the given [action] with accelerating rate.
     *
     * If already repeating, the previous repeat is stopped first.
     *
     * @param action the action to repeat (e.g. deleteOneCharacter)
     */
    fun start(action: () -> Unit) {
        stop()
        isRepeating = true
        startTime = System.currentTimeMillis()
        currentDelay = BASE_DELAY

        repeatRunnable = object : Runnable {
            override fun run() {
                if (!isRepeating) return
                action()

                val elapsed = System.currentTimeMillis() - startTime
                for ((threshold, delay) in ACCELERATION_THRESHOLDS) {
                    if (elapsed > threshold && currentDelay > delay) {
                        vibrate?.invoke()
                        currentDelay = delay
                        break
                    }
                }

                handler.postDelayed(this, currentDelay)
            }
        }
        handler.post(repeatRunnable!!)
    }

    /**
     * Stops all pending repeat callbacks. Idempotent -- safe to call multiple times.
     */
    fun stop() {
        isRepeating = false
        repeatRunnable?.let { handler.removeCallbacks(it) }
        repeatRunnable = null
    }
}
