package net.devemperor.dictate.core

import android.media.AudioFocusRequest
import android.media.AudioManager

/**
 * Thin abstraction over [AudioManager.requestAudioFocus] / [AudioManager.abandonAudioFocusRequest].
 *
 * Exists for testability: [AudioManager] is `final` and the project's policy K-1
 * mandates handwritten fakes (no Mockito), so [RecordingStateController] takes a
 * [AudioFocusGate] instead of the concrete [AudioManager]. Production wires
 * [RealAudioFocusGate]; unit tests substitute a counter-based fake.
 *
 * Quality-Gate K8: pulled out as a seam so [RecordingStateController.setAudioFocusRuntime]
 * (Block 3c) can be exercised without an Android device or Robolectric.
 */
interface AudioFocusGate {

    /**
     * Requests audio focus.
     *
     * @return `true` iff the request was [AudioManager.AUDIOFOCUS_REQUEST_GRANTED];
     *         `false` for any other outcome (failed, delayed, etc.).
     */
    fun request(): Boolean

    /** Abandons the focus request previously made via [request]. */
    fun abandon()
}

/**
 * Production [AudioFocusGate] that delegates to the real Android [AudioManager].
 *
 * Constructed once during service initialisation in
 * [DictateInputMethodService.initLongLivedObjects] and held by
 * [RecordingStateController].
 */
class RealAudioFocusGate(
    private val audioManager: AudioManager,
    private val request: AudioFocusRequest
) : AudioFocusGate {
    override fun request(): Boolean =
        audioManager.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED

    override fun abandon() {
        audioManager.abandonAudioFocusRequest(request)
    }
}
