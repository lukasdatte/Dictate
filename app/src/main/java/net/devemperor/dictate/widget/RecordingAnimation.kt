package net.devemperor.dictate.widget

import android.view.View

/**
 * Strategy interface for recording indicator animations.
 *
 * Implementations provide different visual effects (ripple, rotation, elevation, etc.)
 * while sharing a common lifecycle. The active animation can be swapped at runtime
 * via SharedPreferences without changing any controller code.
 *
 * Lifecycle:
 * 1. [prepare] — called once after the target view is inflated
 * 2. [start] — recording begins
 * 3. [pause] / [resume] — recording paused/resumed
 * 4. [cancel] — recording stopped, view state must be fully reset
 */
interface RecordingAnimation {
    fun prepare(target: View)
    fun start()
    fun pause()
    fun resume()
    fun cancel()
    fun onAmplitude(level: Float) {}
    fun onTimerTick(timerText: String) {}
    fun updateColor(color: Int) {}
}
