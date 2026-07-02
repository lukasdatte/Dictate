package net.devemperor.dictate.core

import android.media.AudioDeviceInfo
import android.media.AudioManager
import net.devemperor.dictate.state.Action

/**
 * FGS-side interruption-signal classification (F-036 implementation,
 * 2026-07-02) — the pure, JVM-testable seams behind the two
 * interruption producers wired in [DictatePipelineService]:
 *
 *  - [AudioFocusChangeClassifier] — audio-focus-change → actions to
 *    dispatch (F-007 fix: one classification authority instead of the
 *    old inline `granted = GAIN || GAIN_TRANSIENT` that paused
 *    dictation on notification ducks).
 *  - [HeadsetDeviceClassifier] — removed-audio-device → is it an
 *    external mic whose loss should interrupt the recording?
 *
 * Both operate on primitive framework values (int constants /
 * booleans) so unit tests need no Android framework objects.
 *
 * @see net.devemperor.dictate.state.Action.InterruptionAction
 * @see net.devemperor.dictate.state.InterruptionModule
 * @see docs/research/2026-07-02 - recording-interruption-handling.md
 */

/**
 * Classifies an `OnAudioFocusChangeListener` callback value into the
 * orchestrator actions to dispatch.
 *
 * Contract table (legacy-parity for the grant flag, interruption
 * semantics per Gap-2 fallback — audio-focus-based call detection, no
 * `READ_PHONE_STATE`):
 *
 * | focusChange                        | grant flag        | interruption |
 * |------------------------------------|-------------------|--------------|
 * | `AUDIOFOCUS_GAIN` (all 4 variants) | `granted = true`  | —            |
 * | `AUDIOFOCUS_LOSS` (hard)           | `granted = false` | ✔ dispatched |
 * | `AUDIOFOCUS_LOSS_TRANSIENT`        | — (kept, parity)  | ✔ dispatched |
 * | `AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK` | —               | — (F-007)    |
 * | anything else                      | —                 | —            |
 *
 * Why transient loss keeps the grant flag: the legacy-parity contract
 * (see F-007) deliberately did not flip the flag on transient losses —
 * focus returns by definition, and the flag feeds only the
 * pref-toggle idempotency gates in `AudioModule`. Why duck-only loss
 * is fully ignored: a notification ding must not pause a dictation —
 * that was exactly the F-007 production bug.
 */
object AudioFocusChangeClassifier {

    fun actionsFor(focusChange: Int): List<Action> = when (focusChange) {
        AudioManager.AUDIOFOCUS_GAIN,
        AudioManager.AUDIOFOCUS_GAIN_TRANSIENT,
        AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK,
        AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE,
        ->
            listOf(Action.AudioAction.OnAudioFocusGrantChanged(granted = true))

        AudioManager.AUDIOFOCUS_LOSS ->
            listOf(
                Action.AudioAction.OnAudioFocusGrantChanged(granted = false),
                Action.InterruptionAction.AudioFocusInterrupted,
            )

        AudioManager.AUDIOFOCUS_LOSS_TRANSIENT ->
            listOf(Action.InterruptionAction.AudioFocusInterrupted)

        // Duck-only loss (notification ding) — recording continues.
        AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> emptyList()

        // Unknown / future constants — fail-closed, dispatch nothing.
        else -> emptyList()
    }
}

/**
 * Classifies an [android.media.AudioDeviceInfo] (by its primitive
 * `type` + `isSource` fields) as an external microphone whose removal
 * interrupts a recording.
 *
 * Coordinated with [BluetoothScoManager]: an SCO drop also surfaces
 * here via `TYPE_BLUETOOTH_SCO` removal — the SCO manager handles the
 * *route* fallback, this classifier handles the *recording-state*
 * reaction (pause). Output-only devices (e.g. `TYPE_WIRED_HEADPHONES`,
 * `isSource = false`) never interrupt: losing a speaker does not
 * corrupt the capture path.
 */
object HeadsetDeviceClassifier {

    /**
     * External input-capable device types. `TYPE_BLE_HEADSET` is an
     * API-31 constant — compile-time inlined, safe on minSdk 26 (older
     * OS versions simply never report it).
     */
    private val EXTERNAL_MIC_TYPES = setOf(
        AudioDeviceInfo.TYPE_WIRED_HEADSET,
        AudioDeviceInfo.TYPE_USB_HEADSET,
        AudioDeviceInfo.TYPE_USB_DEVICE,
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
        AudioDeviceInfo.TYPE_BLE_HEADSET,
    )

    fun isExternalMicInput(type: Int, isSource: Boolean): Boolean =
        isSource && type in EXTERNAL_MIC_TYPES
}
