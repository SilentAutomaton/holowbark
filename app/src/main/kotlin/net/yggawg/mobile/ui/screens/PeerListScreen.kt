package net.yggawg.mobile.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.CheckBoxOutlineBlank
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import net.yggawg.mobile.peers.models.Peer
import net.yggawg.mobile.ui.VpnStateViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PeerListScreen(
    vm: VpnStateViewModel,
    countryKey: String,
    onBack: () -> Unit,
) {
    val peers         by vm.currentCountryPeers.collectAsState()
    val selectedPeers by vm.selectedPeers.collectAsState()

    LaunchedEffect(countryKey) {
        vm.loadPeersForCountry(countryKey)
    }

    val countryLabel = countryKey.substringAfter('/')
        .replace('-', ' ')
        .replaceFirstChar { it.uppercaseChar() }

    val upCount = peers.count { it.up }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(countryLabel)
                        Text("$upCount up / ${peers.size} total",
                            style = MaterialTheme.typography.labelMedium)
                    }
                },
            )
        },
        bottomBar = {
            if (selectedPeers.isNotEmpty()) {
                Surface(tonalElevation = 3.dp) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("${selectedPeers.size} selected")
                        Button(onClick = { vm.applySelectedPeers(); onBack() }) {
                            Icon(Icons.Default.Check, contentDescription = null)
                            Spacer(Modifier.width(4.dp))
                            Text("Apply")
                        }
                    }
                }
            }
        }
    ) { padding ->
        if (peers.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else {
            LazyColumn(contentPadding = padding) {
                items(peers.sortedWith(compareBy(nullsLast()) { it.responseMs }), key = { it.address }) { peer ->
                    PeerRow(
                        peer = peer,
                        selected = peer.address in selectedPeers,
                        onToggle = { vm.togglePeer(peer.address) },
                    )
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                }
            }
        }
    }
}

@Composable
private fun PeerRow(peer: Peer, selected: Boolean, onToggle: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        IconButton(onClick = onToggle, modifier = Modifier.size(24.dp)) {
            Icon(
                imageVector = if (selected) Icons.Default.CheckBox else Icons.Default.CheckBoxOutlineBlank,
                contentDescription = if (selected) "Deselect" else "Select",
                tint = if (selected) MaterialTheme.colorScheme.primary
                       else MaterialTheme.colorScheme.outline,
            )
        }

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = peer.address,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
            )
            peer.ip?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
        }

        Column(horizontalAlignment = Alignment.End) {
            // Up/down indicator
            Text(
                text = if (peer.up) "●" else "○",
                color = if (peer.up) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.outline,
                style = MaterialTheme.typography.labelSmall,
            )
            // Latency
            peer.responseMs?.let {
                Text(
                    text = "${it}ms",
                    style = MaterialTheme.typography.labelSmall,
                    color = when {
                        it < 100 -> MaterialTheme.colorScheme.primary
                        it < 300 -> MaterialTheme.colorScheme.secondary
                        else     -> MaterialTheme.colorScheme.error
                    }
                )
            }
        }
    }
}
