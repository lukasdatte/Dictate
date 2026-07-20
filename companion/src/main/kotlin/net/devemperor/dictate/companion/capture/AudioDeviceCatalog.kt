package net.devemperor.dictate.companion.capture

import net.devemperor.dictate.companion.domain.CompanionSettings
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.Mixer
import javax.sound.sampled.TargetDataLine

/**
 * Enumerates the input devices that can actually capture the fixed [CaptureFormat], and remembers the
 * user's choice through the [CompanionSettings] façade (desktop-host.md §4.2).
 *
 * The remembered device is a mixer *name*, not an index: indices reshuffle when a USB mic is plugged
 * in, a name is stable. If the remembered device is gone at capture time [resolveMixer] falls back to
 * the system default rather than failing the take (R5 mitigation) — a dictation must not die because
 * a headset was unplugged.
 */
class AudioDeviceCatalog(private val settings: CompanionSettings) {

    /** Every mixer that offers a [TargetDataLine] in the capture format. */
    fun available(): List<AudioDeviceRef> =
        AudioSystem.getMixerInfo()
            .filter { supportsCapture(it) }
            .map { AudioDeviceRef(it.name) }

    /** The persisted selection, or `null` when the user never chose (⇒ system default). */
    fun selected(): AudioDeviceRef? = settings.audioInputDevice?.let(::AudioDeviceRef)

    /** Persists the user's choice; `null` clears it back to the system default. */
    fun select(device: AudioDeviceRef?) {
        settings.audioInputDevice = device?.mixerName
    }

    /**
     * Resolves [device] (or the persisted selection when `null`) to a concrete [Mixer], falling back
     * to the system default mixer (`null`) when the named device is unavailable.
     */
    fun resolveMixer(device: AudioDeviceRef? = selected()): Mixer? {
        val name = device?.mixerName ?: return null
        val info = AudioSystem.getMixerInfo().firstOrNull { it.name == name && supportsCapture(it) }
            ?: return null
        return AudioSystem.getMixer(info)
    }

    private fun supportsCapture(info: Mixer.Info): Boolean = try {
        val mixer = AudioSystem.getMixer(info)
        mixer.isLineSupported(DataLine.Info(TargetDataLine::class.java, CaptureFormat.audioFormat()))
    } catch (e: Exception) {
        // A mixer that throws on interrogation is simply not a candidate — never a crash.
        false
    }
}
