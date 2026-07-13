package net.devemperor.dictate.companion.ui.pairing

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toPainter
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/**
 * Scan the QR **or** type the code — the two are equals, not a first choice and a fallback.
 *
 * A user who will not grant the keyboard app camera access still has to be able to pair, and a user
 * whose camera cannot read a screen at that angle needs the same. Both paths carry the same one-time
 * token; the countdown next to them explains why it will stop working.
 */
@Composable
fun PairingDialog(viewModel: PairingViewModel, onDismiss: () -> Unit) {
    val state by viewModel.state.collectAsState()

    LaunchedEffect(Unit) {
        viewModel.start()
        while (true) {
            delay(1_000)
            viewModel.tick()
        }
    }

    // The dialog learns about a successful pairing by watching the device list — the HTTP route that
    // did the pairing knows nothing about a UI, and should not.
    LaunchedEffect(state.pairedDeviceName) {
        if (state.pairedDeviceName != null) {
            delay(1_200)
            onDismiss()
        }
    }

    AlertDialog(
        onDismissRequest = {
            viewModel.cancel()
            onDismiss()
        },
        title = { Text(state.pairedDeviceName?.let { "Paired with $it" } ?: "Pair a phone") },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                if (state.pairedDeviceName != null) {
                    Text("Done — the phone can now send dictations to this PC.")
                    return@Column
                }

                val qr = remember(state.uri) {
                    state.uri.takeIf { it.isNotEmpty() }?.let { QrCodes.encode(it) }
                }
                qr?.let {
                    Image(
                        painter = it.toPainter(),
                        contentDescription = "Pairing QR code",
                        modifier = Modifier.size(240.dp),
                    )
                }

                Spacer(Modifier.size(12.dp))
                Text("Or type this on the phone:", style = MaterialTheme.typography.bodyMedium)
                Text(state.baseUrl, style = MaterialTheme.typography.bodySmall)
                Text(state.token, style = MaterialTheme.typography.headlineSmall)

                Spacer(Modifier.size(8.dp))
                Text(
                    text = if (state.expired) {
                        "This code has expired — ask for a new one."
                    } else {
                        "Expires in ${state.remainingSeconds / 60}:${"%02d".format(state.remainingSeconds % 60)}"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            // A new code burns the old one — a token that was displayed has been on a screen, and
            // handing out two live ones at once would defeat "one token, one device".
            TextButton(onClick = viewModel::start) { Text("New code") }
        },
        dismissButton = {
            TextButton(onClick = {
                viewModel.cancel()
                onDismiss()
            }) { Text("Close") }
        },
    )
}
