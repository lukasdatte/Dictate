package net.devemperor.dictate.companion.ui.peers

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import net.devemperor.dictate.companion.CompanionContainer
import net.devemperor.dictate.companion.catalog.PeerIndexSource

/**
 * The Peers destination (peer-katalog.md §8): two tabs — the consumer view ("Subscriptions": peer
 * list + per-peer detail, §8.1) and the offer view ("My offer": visibility + last pickup, §8.2).
 *
 * Layout only; both brains are plain tested ViewModels ([PeerExplorerViewModel], [OfferViewModel]).
 * A graph without a config store (the minimal test graph) shows the honest unavailable line, the
 * ManagementScreen precedent.
 */
@Composable
fun PeersScreen(container: CompanionContainer) {
    val explorerStore = container.peerExplorer
    val configRepository = container.configRepository
    val auditLog = container.catalogAuditLog
    if (explorerStore == null || configRepository == null || auditLog == null) {
        Text("Peer catalog is unavailable in this build.", modifier = Modifier.padding(16.dp))
        return
    }

    val scope = rememberCoroutineScope()
    val explorer = remember {
        PeerExplorerViewModel(
            store = explorerStore,
            indexSource = container.peerIndexSource ?: PeerIndexSource { null },
            discovery = container.peerDiscovery,
            syncRunner = container.catalogSyncRunner,
            subscriber = container.catalogSubscriber,
            scope = scope,
            clock = { container.clock.nowMillis() },
        )
    }
    val offer = remember { OfferViewModel(configRepository, auditLog, scope) }

    var tab by remember { mutableStateOf(0) }
    Column(modifier = Modifier.fillMaxSize()) {
        TabRow(selectedTabIndex = tab) {
            Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Subscriptions") })
            Tab(selected = tab == 1, onClick = { tab = 1; offer.refresh() }, text = { Text("My offer") })
        }
        Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
            when (tab) {
                0 -> ConsumerTab(explorer)
                else -> OfferTab(offer)
            }
        }
    }
}

/** List when nothing is selected, detail once a peer is — the §8.1 two-pane flow, stacked. */
@Composable
private fun ConsumerTab(viewModel: PeerExplorerViewModel) {
    val state by viewModel.state.collectAsState()
    val selected = state.selectedPeer
    if (selected == null) {
        PeerListScreen(viewModel, state)
    } else {
        PeerDetailScreen(viewModel, state, selected)
    }
}
