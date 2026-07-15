package net.devemperor.dictate.companion.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.nativeKeyCode
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.unit.dp
import net.devemperor.dictate.companion.domain.model.ChordModifier

/**
 * The "Keyboard shortcuts" settings section (D6, §5.4).
 *
 * One row per command showing its current chord; "Change" arms a capture field that reads the next
 * key combination via [onPreviewKeyEvent] and hands it to the pure [ChordSettingsViewModel.rebind].
 * A single "Reset to defaults" restores every binding. The Compose layer holds no policy — modifier
 * extraction and the AWT→Win32 VK fix are the only glue, and even that fix is unit-tested in
 * [KeyCapture].
 */
@Composable
fun ChordSettingsSection(viewModel: ChordSettingsViewModel) {
    val state by viewModel.state.collectAsState()

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Keyboard shortcuts", style = MaterialTheme.typography.titleSmall)
        Text(
            "How each remote action is typed on this PC. Rebind if an app expects a different combination.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        state.rows.forEach { row ->
            val editing = state.editing == row.command
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(ChordLabels.describe(row.command), modifier = Modifier.width(200.dp))
                Text(row.label, modifier = Modifier.width(160.dp))

                if (editing) {
                    val focus = remember { FocusRequester() }
                    Text(
                        "Press keys…",
                        modifier = Modifier
                            .focusRequester(focus)
                            .onPreviewKeyEvent { event ->
                                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                                val modifiers = buildSet {
                                    if (event.isCtrlPressed) add(ChordModifier.CTRL)
                                    if (event.isShiftPressed) add(ChordModifier.SHIFT)
                                    if (event.isAltPressed) add(ChordModifier.ALT)
                                    if (event.isMetaPressed) add(ChordModifier.WIN)
                                }
                                viewModel.rebind(row.command, modifiers, KeyCapture.win32VkFor(event.key.nativeKeyCode))
                                true
                            },
                    )
                    androidx.compose.runtime.LaunchedEffect(row.command) { focus.requestFocus() }
                } else {
                    OutlinedButton(onClick = { viewModel.startEditing(row.command) }) { Text("Change") }
                }
            }
            if (editing && state.error != null) {
                Text(state.error!!, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
        }

        TextButton(onClick = viewModel::resetToDefaults) { Text("Reset to defaults") }
    }
}
