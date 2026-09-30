package net.holowbark.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.NetworkCheck
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import net.holowbark.R
import net.holowbark.peers.models.Peer
import net.holowbark.peers.models.peersToSelect
import net.holowbark.ui.Hint
import net.holowbark.ui.TunnelViewModel
import net.holowbark.ui.contentWidth
import net.holowbark.peers.models.countryDisplayName
import net.holowbark.ui.latencyColor
import net.holowbark.vpn.VpnState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PeerListScreen(
    vm: TunnelViewModel,
    countryKey: String,
    onBack: () -> Unit,
) {
    // Local and keyed by country, so a list read for another country never shows here.
    val loaded by produceState<List<Peer>?>(null, countryKey) {
        value = vm.repo.getPeersForCountry(countryKey)
    }
    val peers = loaded.orEmpty()
    val selectedPeers by vm.selectedPeers.collectAsState()
    val probes by vm.probeResults.collectAsState()
    val probing by vm.isProbing.collectAsState()
    val tunnel by vm.tunnelStatus.collectAsState()
    // With the tunnel up this app's sockets go through it, so a check would time
    // the tunnel, not the path from this device.
    val tunnelUp = tunnel.overall != VpnState.IDLE && tunnel.overall != VpnState.DISCONNECTED

    val countryLabel = countryDisplayName(countryKey)

    val measured = peers.any { it.up != null }
    val upCount = peers.count { it.up == true }
    val candidates = remember(peers, probes) { peersToSelect(peers, probes).map { it.address } }
    val showRemove = peers.any { it.address in selectedPeers } && candidates.all { it in selectedPeers }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                title = {
                    Column {
                        Text(countryLabel, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (loaded != null) Text(
                            if (measured) stringResource(R.string.peers_country_status, upCount, peers.size)
                            else pluralStringResource(R.plurals.peers_country_unmeasured, peers.size, peers.size),
                            style = MaterialTheme.typography.labelMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                },
                actions = {
                    if (showRemove) {
                        TextButton(onClick = { vm.unselectPeers(peers.map { it.address }) }) {
                            Text(stringResource(R.string.peers_remove_all))
                        }
                    } else {
                        TextButton(
                            onClick = { vm.selectPeers(candidates) },
                            enabled = candidates.isNotEmpty(),
                        ) {
                            Text(stringResource(R.string.peers_add_all))
                        }
                    }
                    CheckButton(
                        probing = probing,
                        enabled = !tunnelUp && peers.isNotEmpty(),
                        onClick = { vm.probePeers(peers.map { it.address }) },
                    )
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
        if (loaded == null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else if (peers.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding).padding(24.dp)) {
                Text(
                    stringResource(R.string.peers_country_empty),
                    color = MaterialTheme.colorScheme.outline,
                )
            }
        } else {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            LazyColumn(modifier = Modifier.contentWidth(), contentPadding = padding) {
                if (!measured) item { Hint(stringResource(R.string.peers_unmeasured_hint)) }
                if (tunnelUp) item { Hint(stringResource(R.string.peers_check_disconnect)) }
                items(peers.sortedWith(compareBy(nullsLast()) { it.responseMs }), key = { it.address }) { peer ->
                    PublicPeerRow(
                        peer = peer,
                        selected = peer.address in selectedPeers,
                        probe = probes[peer.address],
                        onToggle = { vm.togglePeer(peer.address) },
                    )
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                }
            }
        }
            }
    }
}

@Composable
private fun CheckButton(probing: Boolean, enabled: Boolean, onClick: () -> Unit) {
    if (probing) {
        val label = stringResource(R.string.peers_checking)
        Box(Modifier.size(48.dp).semantics { contentDescription = label }, contentAlignment = Alignment.Center) {
            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        }
    } else {
        IconButton(onClick = onClick, enabled = enabled) {
            Icon(Icons.Default.NetworkCheck, contentDescription = stringResource(R.string.peers_check))
        }
    }
}

/** One control for the whole row, so the checkbox is named by the peer it selects. */
@Composable
private fun PublicPeerRow(peer: Peer, selected: Boolean, probe: Int?, onToggle: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(value = selected, role = Role.Checkbox, onValueChange = { onToggle() })
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Checkbox(checked = selected, onCheckedChange = null)

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
            probe?.let {
                Text(
                    text = if (it >= 0) stringResource(R.string.peer_probe_ms, it)
                           else stringResource(R.string.peer_probe_none),
                    style = MaterialTheme.typography.labelSmall,
                    color = latencyColor(it),
                )
            }
        }

        peer.up?.let { up ->
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = if (up) "●" else "○",
                    color = if (up) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.outline,
                    style = MaterialTheme.typography.labelSmall,
                )
                peer.responseMs?.let {
                    Text(
                        text = "${it}ms",
                        style = MaterialTheme.typography.labelSmall,
                        color = latencyColor(it),
                    )
                }
            }
        }
    }
}
