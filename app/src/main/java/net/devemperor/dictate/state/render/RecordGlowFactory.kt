package net.devemperor.dictate.state.render

import androidx.appcompat.content.res.AppCompatResources
import com.google.android.material.button.MaterialButton
import net.devemperor.dictate.R
import net.devemperor.dictate.widget.AmplitudeVisualizerDrawable
import net.devemperor.dictate.widget.BorderGlowAnimation

/**
 * Single builder for the record-button border-glow + breathing [RecordingAnimationController]
 * (P3 DRY).
 *
 * The exact same construction — a [BorderGlowAnimation] (30-bar amplitude visualiser, 0.35 alpha,
 * `ic_baseline_send_20` glyph) prepared on the record button, wrapped in a
 * [RecordingAnimationController] — was written three times: the IME keyboard backend
 * (`DictateInputMethodService`), the floating overlay backend (`DictatePipelineService`), and the
 * PC-dictation Activity (`PcDictationActivity`). Divergence between them would mean the three
 * record buttons breathe differently. This factory is the one place that shape lives.
 *
 * @param recordButton the button the glow is prepared on and the controller drives.
 * @param accentColorProvider the live accent colour (read for the initial glow colour and re-read
 *   by the controller on theme changes).
 * @param animationsEnabled reduced-motion gate.
 * @param pcModeColorProvider the PC-send-mode colour (ADR-0019); defaults to [accentColorProvider].
 * @param pcModeBadge the "PC" badge drawn next to the timer in PC-mode; empty = no badge.
 */
object RecordGlowFactory {

    fun create(
        recordButton: MaterialButton,
        accentColorProvider: () -> Int,
        animationsEnabled: () -> Boolean,
        pcModeColorProvider: () -> Int = accentColorProvider,
        pcModeBadge: String = "",
    ): RecordingAnimationController {
        val ctx = recordButton.context
        val density = ctx.resources.displayMetrics.density
        val animation = BorderGlowAnimation(
            accentColorProvider(),
            AppCompatResources.getDrawable(ctx, R.drawable.ic_baseline_send_20),
            AmplitudeVisualizerDrawable.BarCountMode.Fixed(30),
            0.35f,
            density,
        )
        animation.prepare(recordButton)
        return RecordingAnimationController(
            animation,
            recordButton,
            accentColorProvider,
            animationsEnabled,
            pcModeColorProvider,
            pcModeBadge,
        )
    }
}
