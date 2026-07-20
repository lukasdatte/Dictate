package net.devemperor.dictate.companion.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import net.devemperor.dictate.companion.CompanionContainer
import net.devemperor.dictate.companion.ui.config.ManagementScreen
import net.devemperor.dictate.companion.ui.devices.DevicesScreen
import net.devemperor.dictate.companion.ui.history.HistoryScreen
import net.devemperor.dictate.companion.ui.settings.SettingsScreen
import net.devemperor.dictate.companion.ui.theme.CompanionTheme

enum class Destination(val label: String) {
    HISTORY("History"),
    CONFIG("Config"),
    DEVICES("Devices"),
    SETTINGS("Settings"),
}

/**
 * The window: a navigation rail and one screen at a time.
 *
 * The banner at the top is not decoration. On a platform without text insertion the companion still
 * receives, stores and shows every dictation — it just cannot type it into the foreground window, and
 * a user who is not told that will conclude the product is broken (ADR-0018).
 */
@Composable
fun App(container: CompanionContainer, baseUrl: () -> String) {
    CompanionTheme {
        var destination by remember { mutableStateOf(Destination.HISTORY) }

        Surface(modifier = Modifier.fillMaxSize()) {
            Row {
                NavigationRail {
                    Destination.entries.forEach { entry ->
                        NavigationRailItem(
                            selected = destination == entry,
                            onClick = { destination = entry },
                            icon = {
                                Icon(
                                    imageVector = when (entry) {
                                        Destination.HISTORY -> Icons.AutoMirrored.Filled.List
                                        Destination.CONFIG -> Icons.Default.Tune
                                        Destination.DEVICES -> Icons.Default.Phone
                                        Destination.SETTINGS -> Icons.Default.Settings
                                    },
                                    contentDescription = entry.label,
                                )
                            },
                            label = { Text(entry.label) },
                        )
                    }
                }

                Column(modifier = Modifier.fillMaxSize()) {
                    if (!container.inserter.available) {
                        InsertionUnavailableBanner()
                    }
                    when (destination) {
                        Destination.HISTORY -> HistoryScreen(container)
                        Destination.CONFIG -> ManagementScreen(container)
                        Destination.DEVICES -> DevicesScreen(container, baseUrl)
                        Destination.SETTINGS -> SettingsScreen(container)
                    }
                }
            }
        }
    }
}

@Composable
private fun InsertionUnavailableBanner() {
    Surface(color = MaterialTheme.colorScheme.tertiaryContainer, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(Icons.Default.Info, contentDescription = null)
            Text(
                text = "Text insertion is not available on this platform. Dictations still arrive and " +
                    "are stored here — they are just not typed into the active window.",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}
