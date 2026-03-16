package net.devemperor.dictate.core

import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

/**
 * Processes raw MediaRecorder amplitude values into normalized 0.0–1.0 levels.
 *
 * Standalone class with no View/Animation dependencies — can be used by any consumer.
 *
 * Processing pipeline:
 * 1. Log-normalization (human perception of loudness is logarithmic)
 * 2. Asymmetric EMA smoothing (fast attack for responsiveness, slow decay for visual comfort)
 * 3. Clamped to 0.0–1.0
 *
 * @param attackFactor EMA factor when getting louder (higher = more responsive, 0.0–1.0)
 * @param decayFactor EMA factor when getting quieter (lower = smoother fade, 0.0–1.0)
 * @param effectiveMax practical maximum amplitude for speech (MediaRecorder max is 32767)
 */
class AmplitudeProcessor(
    private val attackFactor: Float = 1.0f,
    private val decayFactor: Float = 1.0f,
    private val effectiveMax: Int = 12000
) {
    private var smoothedLevel: Float = 0f

    /**
     * Processes a raw amplitude value from MediaRecorder.getMaxAmplitude().
     *
     * @param rawAmplitude raw amplitude (0–32767)
     * @return normalized, smoothed level (0.0–1.0)
     */
    fun process(rawAmplitude: Int): Float {
        // Log-normalize: ln(1 + raw) / ln(1 + max)
        val clamped = min(rawAmplitude, effectiveMax).coerceAtLeast(0)
        val normalized = if (clamped == 0) 0f
        else (ln(1.0 + clamped) / ln(1.0 + effectiveMax)).toFloat()

        // Asymmetric EMA: fast attack, slow decay
        val factor = if (normalized > smoothedLevel) attackFactor else decayFactor
        smoothedLevel = smoothedLevel + factor * (normalized - smoothedLevel)

        return min(1f, max(0f, smoothedLevel))
    }

    /** Resets the processor state. Call when recording stops. */
    fun reset() {
        smoothedLevel = 0f
    }
}
