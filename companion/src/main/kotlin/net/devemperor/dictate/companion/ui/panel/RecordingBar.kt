package net.devemperor.dictate.companion.ui.panel

import androidx.compose.animation.animateColor
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb

/**
 * The Compose-canvas recreation of the Android recording waveform (desktop-host.md §7, F19).
 *
 * All geometry and color math comes from [RecordingBarDesign] — the tested 1:1 port of the Android
 * parameters. This file only maps those numbers onto `drawRoundRect`: 30 pill bars around the
 * vertical center, age-fade alpha left→right, pastel accent color.
 */
@Composable
fun RecordingBar(
    levels: List<Float>,
    accent: Color,
    modifier: Modifier = Modifier,
) {
    val barColor = Color(RecordingBarDesign.hsvToRgb(RecordingBarDesign.pastel(RecordingBarDesign.rgbToHsv(accent.toArgb()))))

    Canvas(modifier = modifier) {
        val count = levels.size
        if (count == 0) return@Canvas
        // Android geometry (AmplitudeVisualizerDrawable.kt:220-222): the gap is 2% of the WHOLE bars
        // area, so with 30 bars the gaps are wider than the pills — that sparseness is the look.
        val spacing = RecordingBarDesign.barSpacing(size.width)
        val barWidth = RecordingBarDesign.barWidth(size.width, count).coerceAtLeast(1f)
        val centerY = size.height / 2f

        levels.forEachIndexed { index, amplitude ->
            val barHeight = RecordingBarDesign.barHeightFraction(amplitude) * size.height
            drawRoundRect(
                color = barColor,
                alpha = RecordingBarDesign.ageFadeAlpha(index, count),
                topLeft = Offset(index * (barWidth + spacing), centerY - barHeight / 2f),
                size = Size(barWidth, barHeight),
                cornerRadius = CornerRadius(RecordingBarDesign.capCornerRadius(barWidth)),
            )
        }
    }
}

/**
 * The recording surface tint: amplitude-coupled glow (§7.3 — `v = baseV + 0.35 × level`, pause
 * baseline +0.12) combined with the 1500 ms breathing pulse (peak ↔ darken(peak, 0.18), infinite
 * reverse; the old red ripple stays removed, 2026-05-23).
 */
@Composable
fun recordingGlowColor(base: Color, glowLevel: Float, paused: Boolean): Color {
    val hsv = RecordingBarDesign.rgbToHsv(base.toArgb())
    val level = if (paused) 0f else glowLevel
    val baseline = if (paused) RecordingBarDesign.PAUSE_GLOW_BASELINE else 0f
    val glowTarget = Color(
        RecordingBarDesign.hsvToRgb(
            floatArrayOf(hsv[0], hsv[1], RecordingBarDesign.glowValue(hsv[2] + baseline, level))
        )
    )
    // Amplitude changes arrive at 10 Hz; the short tween keeps the glow from stepping visibly.
    val glow by animateColorAsState(
        targetValue = glowTarget,
        animationSpec = tween(durationMillis = RecordingBarDesign.UPDATE_PERIOD_MILLIS.toInt(), easing = LinearEasing),
        label = "recording-glow",
    )

    if (paused) return glow // the pulse breathes only while actually recording

    val pulse = rememberInfiniteTransition(label = "recording-pulse")
    val pulsed by pulse.animateColor(
        initialValue = glow,
        targetValue = Color(RecordingBarDesign.darken(glow.toArgb())),
        animationSpec = infiniteRepeatable(
            animation = tween(RecordingBarDesign.PULSE_PERIOD_MILLIS, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "recording-pulse-color",
    )
    return pulsed
}
