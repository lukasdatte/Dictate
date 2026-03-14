package net.devemperor.dictate.widget

import android.view.View

/**
 * A [RecordingAnimation] that delegates to a [PulseLayout] wrapper.
 *
 * The PulseLayout must already wrap the target button in the XML layout.
 * This class simply bridges the [RecordingAnimation] lifecycle to the
 * PulseLayout's start/pause/resume/stop API.
 *
 * @param pulseLayout The PulseLayout wrapping the animated button.
 */
class RipplePulseAnimation(
    private val pulseLayout: PulseLayout
) : RecordingAnimation {

    override fun prepare(target: View) {
        // PulseLayout is already configured via XML attributes.
        // Dynamic configuration (e.g., color from SharedPreferences) can be set here:
        // pulseLayout.pulseColor = ...
    }

    override fun start() = pulseLayout.startPulse()

    override fun pause() = pulseLayout.pausePulse()

    override fun resume() = pulseLayout.resumePulse()

    override fun cancel() = pulseLayout.stopPulse()
}
