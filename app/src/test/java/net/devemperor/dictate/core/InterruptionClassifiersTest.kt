package net.devemperor.dictate.core

import android.media.AudioDeviceInfo
import android.media.AudioManager
import net.devemperor.dictate.state.Action
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Producer-classification tests for the FGS-side interruption seams
 * (F-036 / F-007, 2026-07-02): which audio-focus-change values count
 * as an interruption, and which removed audio devices count as an
 * external mic. Both classifiers operate on primitive framework
 * constants — no Android objects needed.
 */
class InterruptionClassifiersTest {

    // ─── AudioFocusChangeClassifier ─────────────────────────────────

    @Test
    fun `all four GAIN variants dispatch granted=true only`() {
        val gains = listOf(
            AudioManager.AUDIOFOCUS_GAIN,
            AudioManager.AUDIOFOCUS_GAIN_TRANSIENT,
            AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK,
            AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE,
        )
        gains.forEach { gain ->
            assertEquals(
                "focusChange=$gain",
                listOf<Action>(Action.AudioAction.OnAudioFocusGrantChanged(granted = true)),
                AudioFocusChangeClassifier.actionsFor(gain),
            )
        }
    }

    @Test
    fun `hard LOSS dispatches grant-false AND the interruption`() {
        assertEquals(
            listOf(
                Action.AudioAction.OnAudioFocusGrantChanged(granted = false),
                Action.InterruptionAction.AudioFocusInterrupted,
            ),
            AudioFocusChangeClassifier.actionsFor(AudioManager.AUDIOFOCUS_LOSS),
        )
    }

    @Test
    fun `transient LOSS dispatches the interruption but keeps the grant flag (legacy parity)`() {
        assertEquals(
            listOf<Action>(Action.InterruptionAction.AudioFocusInterrupted),
            AudioFocusChangeClassifier.actionsFor(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT),
        )
    }

    @Test
    fun `duck-only loss dispatches nothing — a notification ding must not pause dictation (F-007)`() {
        assertTrue(
            AudioFocusChangeClassifier
                .actionsFor(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK)
                .isEmpty(),
        )
    }

    @Test
    fun `unknown focus-change values fail closed (dispatch nothing)`() {
        assertTrue(AudioFocusChangeClassifier.actionsFor(Int.MIN_VALUE).isEmpty())
        assertTrue(AudioFocusChangeClassifier.actionsFor(0).isEmpty())
    }

    // ─── HeadsetDeviceClassifier ────────────────────────────────────

    @Test
    fun `input-capable headset types classify as external mic`() {
        val micTypes = listOf(
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_USB_DEVICE,
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            AudioDeviceInfo.TYPE_BLE_HEADSET,
        )
        micTypes.forEach { type ->
            assertTrue(
                "type=$type",
                HeadsetDeviceClassifier.isExternalMicInput(type, isSource = true),
            )
        }
    }

    @Test
    fun `output-only devices never classify — losing a speaker does not corrupt capture`() {
        // Same types but isSource=false (the output half of a headset)…
        assertFalse(
            HeadsetDeviceClassifier.isExternalMicInput(
                AudioDeviceInfo.TYPE_WIRED_HEADSET,
                isSource = false,
            ),
        )
        // …and genuinely output-only device types.
        assertFalse(
            HeadsetDeviceClassifier.isExternalMicInput(
                AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
                isSource = false,
            ),
        )
        assertFalse(
            HeadsetDeviceClassifier.isExternalMicInput(
                AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
                isSource = false,
            ),
        )
    }

    @Test
    fun `the built-in mic never classifies — it cannot disconnect`() {
        assertFalse(
            HeadsetDeviceClassifier.isExternalMicInput(
                AudioDeviceInfo.TYPE_BUILTIN_MIC,
                isSource = true,
            ),
        )
    }
}
