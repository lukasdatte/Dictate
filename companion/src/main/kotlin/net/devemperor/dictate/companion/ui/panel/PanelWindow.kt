package net.devemperor.dictate.companion.ui.panel

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.rememberWindowState
import net.devemperor.dictate.companion.pipeline.DesktopDictationController
import net.devemperor.dictate.companion.pipeline.DictationPhase
import net.devemperor.dictate.companion.pipeline.PipelineUi
import net.devemperor.dictate.companion.pipeline.RecordingUi
import net.devemperor.dictate.companion.ui.theme.CompanionTheme

/**
 * The warm dictation panel (desktop-host.md §6.2): a frameless, always-on-top Compose window that
 * exists from process start and is only ever shown/hidden — never re-created per hotkey — so the
 * toggle stays under 100 ms (F5). Fixed bottom-center in v1 (§6.2).
 *
 * On first composition the Win32 `WS_EX_NOACTIVATE` spike style is applied via [applyFocusFreeStyle]
 * (a platform lambda; `{ false }` off-Windows) and its outcome reported to [control] — see
 * `ComposePanelWindowControl` for how that gates against the manual spike verdict (§6.3).
 */
@Composable
fun PanelWindow(
    controller: DesktopDictationController,
    viewModel: PanelViewModel,
    control: ComposePanelWindowControl,
    applyFocusFreeStyle: (java.awt.Window) -> Boolean,
) {
    val visible by control.visible.collectAsState()
    val ui by viewModel.state.collectAsState()

    Window(
        visible = visible,
        onCloseRequest = { controller.discard() },
        title = "Dictate",
        undecorated = true,
        resizable = false,
        alwaysOnTop = true,
        focusable = false,
        state = rememberWindowState(
            position = WindowPosition.Aligned(Alignment.BottomCenter),
            width = PANEL_WIDTH,
            height = PANEL_HEIGHT,
        ),
    ) {
        LaunchedEffect(Unit) { control.onFocusFreeStyle(applyFocusFreeStyle(window)) }
        CompanionTheme {
            Surface(modifier = Modifier.fillMaxSize(), shape = RectangleShape, tonalElevation = 3.dp) {
                PanelContent(ui, controller)
            }
        }
    }
}

@Composable
private fun PanelContent(ui: PanelUi, controller: DesktopDictationController) {
    val dictation = ui.dictation
    when {
        dictation.recording is RecordingUi.Active ->
            RecordingRow(ui, dictation.recording as RecordingUi.Active, controller)

        dictation.phase == DictationPhase.TRANSCRIBING || dictation.phase == DictationPhase.POST_PROCESSING ->
            ProgressRow(if (dictation.phase == DictationPhase.TRANSCRIBING) "Transcribing…" else "Polishing…")

        dictation.phase == DictationPhase.REVIEW && dictation.review != null ->
            ReviewRow(ui, dictation.review!!, controller)

        dictation.pipeline is PipelineUi.Failed ->
            ProgressRow("Dictation failed: ${(dictation.pipeline as PipelineUi.Failed).errorKey}", showSpinner = false)

        else -> ProgressRow("Ready", showSpinner = false)
    }
}

/**
 * The §7.3 recording layout, left→right: send/stop icon, amplitude bars, `MM:SS` timer — plus the
 * pause/resume and discard controls the desktop needs (the phone has gestures for these).
 */
@Composable
private fun RecordingRow(ui: PanelUi, recording: RecordingUi.Active, controller: DesktopDictationController) {
    val glow = recordingGlowColor(MaterialTheme.colorScheme.surfaceVariant, ui.glowLevel, recording.paused)
    Row(
        modifier = Modifier.fillMaxSize().background(glow).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // The send icon doubles as "stop and run the pipeline" — the Android widget's semantics.
        IconButton(onClick = { controller.stopRecording() }) {
            Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Stop and transcribe", tint = MaterialTheme.colorScheme.primary)
        }
        RecordingBar(
            levels = ui.levels,
            accent = MaterialTheme.colorScheme.primary,
            modifier = Modifier.weight(1f).height(PANEL_HEIGHT - 24.dp),
        )
        Text(ui.timerText, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        if (recording.paused) {
            IconButton(onClick = { controller.resumeRecording() }) {
                Icon(Icons.Default.PlayArrow, contentDescription = "Resume recording")
            }
        } else {
            IconButton(onClick = { controller.pauseRecording() }) {
                Icon(Icons.Default.Pause, contentDescription = "Pause recording")
            }
        }
        IconButton(onClick = { controller.discard() }) {
            Icon(Icons.Default.Close, contentDescription = "Discard take", tint = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
private fun ProgressRow(text: String, showSpinner: Boolean = true) {
    Row(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (showSpinner) CircularProgressIndicator(modifier = Modifier.size(20.dp))
        Text(text, style = MaterialTheme.typography.bodyLarge)
    }
}

/**
 * The review panel (ADR-0013, spec §8.4/§8.5) — also the `insertion.confirmBeforeInsert` gate (F21).
 * Renders `message` only when non-blank (output-only otherwise, ADR-0013 §5) and offers Insert /
 * Re-dictate / Discard. Insert is disabled while a re-dictate is in flight (K1); Discard stays live
 * because it doubles as the refinement's Cancel (§8.4). During the S2 recording the row shows the
 * amplitude bars + a Stop button; during the follow-up turn it shows a "Refining…" spinner.
 */
@Composable
private fun ReviewRow(ui: PanelUi, review: net.devemperor.dictate.companion.pipeline.ReviewUi, controller: DesktopDictationController) {
    Row(
        modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        when {
            review.refinementRecording -> {
                RecordingBar(
                    levels = ui.levels,
                    accent = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f).height(PANEL_HEIGHT - 24.dp),
                )
                IconButton(onClick = { controller.stopRefinement() }) {
                    Icon(Icons.Default.Stop, contentDescription = "Stop re-dictation", tint = MaterialTheme.colorScheme.primary)
                }
            }
            review.refining -> {
                CircularProgressIndicator(modifier = Modifier.size(20.dp))
                Text("Refining…", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            }
            else -> {
                Column(modifier = Modifier.weight(1f)) {
                    if (!review.message.isNullOrBlank()) {
                        Text(review.message!!, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                    }
                    Text(review.output, maxLines = if (review.message.isNullOrBlank()) 2 else 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                    if (review.error != null) {
                        Text("Re-dictate failed: ${review.error}", maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                    }
                }
                IconButton(onClick = { controller.confirmInsert() }) {
                    Icon(Icons.Default.Check, contentDescription = "Insert", tint = MaterialTheme.colorScheme.primary)
                }
                IconButton(onClick = { controller.startRefinement() }) {
                    Icon(Icons.Default.Refresh, contentDescription = "Re-dictate")
                }
            }
        }
        // Discard is always live: a plain acknowledge on a settled review, a Cancel while refining (§8.4).
        IconButton(onClick = { controller.discard() }) {
            Icon(Icons.Default.Close, contentDescription = "Discard", tint = MaterialTheme.colorScheme.error)
        }
    }
}

private val PANEL_WIDTH = 560.dp
private val PANEL_HEIGHT = 72.dp
