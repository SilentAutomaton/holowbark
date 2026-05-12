package net.yggawg.mobile.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import net.yggawg.mobile.ui.VpnStateViewModel
import net.yggawg.mobile.vpn.LayerState
import net.yggawg.mobile.vpn.TunnelStatus
import net.yggawg.mobile.vpn.VpnState

@Composable
fun HomeScreen(
    vm: VpnStateViewModel,
    onRequestVpnPermission: () -> Unit,
    onNavigateImport: () -> Unit,
    onNavigateCountries: () -> Unit,
    onRestartAwg: () -> Unit = {},
) {
    val tunnelStatus  by vm.tunnelStatus.collectAsState()
    val awgConfig     by vm.awgConfig.collectAsState()
    val selectedPeers by vm.selectedPeers.collectAsState()
    val errorMsg      by vm.errorMessage.collectAsState()

    errorMsg?.let { msg ->
        AlertDialog(
            onDismissRequest = { vm.clearError() },
            text = { Text(msg) },
            confirmButton = { TextButton(onClick = { vm.clearError() }) { Text("OK") } },
        )
    }

    val protocolLabel = if (awgConfig?.isAwg == true) "AmneziaWG" else "WireGuard"

    Scaffold(
        floatingActionButton = {
            ConnectFab(
                state = tunnelStatus.overall,
                hasConfig = awgConfig != null,
                onConnect = onRequestVpnPermission,
                onDisconnect = vm::disconnect,
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            LayerStatusCard(tunnelStatus, protocolLabel, onRestartAwg)

            AwgConfigCard(
                protocolLabel = protocolLabel,
                endpoint = awgConfig?.endpoint,
                onImportClick = onNavigateImport,
            )

            PeersCard(
                selectedCount = selectedPeers.size,
                onBrowseClick = onNavigateCountries,
            )
        }
    }
}

@Composable
private fun ConnectFab(
    state: VpnState,
    hasConfig: Boolean,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
) {
    if (!hasConfig) return
    FloatingActionButton(
        onClick = {
            when (state) {
                VpnState.CONNECTED, VpnState.CONNECTING -> onDisconnect()
                else -> onConnect()
            }
        },
        containerColor = when (state) {
            VpnState.CONNECTED  -> MaterialTheme.colorScheme.error
            VpnState.CONNECTING -> MaterialTheme.colorScheme.tertiary
            else                -> MaterialTheme.colorScheme.primary
        }
    ) {
        Icon(
            imageVector = when (state) {
                VpnState.CONNECTED  -> Icons.Default.Stop
                VpnState.CONNECTING -> Icons.Default.HourglassTop
                else                -> Icons.Default.PlayArrow
            },
            contentDescription = if (state == VpnState.CONNECTED) "Disconnect" else "Connect",
        )
    }
}

@Composable
private fun LayerStatusCard(status: TunnelStatus, protocolLabel: String, onRestartAwg: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            // Overall label
            val (overallLabel, overallColor) = when (status.overall) {
                VpnState.CONNECTED    -> "Connected"    to MaterialTheme.colorScheme.primary
                VpnState.CONNECTING   -> "Connecting…"  to MaterialTheme.colorScheme.tertiary
                VpnState.DISCONNECTED,
                VpnState.IDLE         -> "Disconnected" to MaterialTheme.colorScheme.outline
                VpnState.ERROR        -> "Error"        to MaterialTheme.colorScheme.error
            }
            Row(verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Default.Circle, contentDescription = null, tint = overallColor,
                    modifier = Modifier.size(12.dp))
                Text(overallLabel, style = MaterialTheme.typography.titleMedium, color = overallColor)
            }

            HorizontalDivider()

            // Yggdrasil layer row
            LayerRow(
                label = "Yggdrasil",
                state = status.ygg,
                detail = when {
                    status.yggAddress.isNotEmpty() && status.yggPeers > 0 ->
                        "${status.yggAddress} · ${status.yggPeers} peer(s)"
                    status.yggAddress.isNotEmpty() -> status.yggAddress
                    else -> null
                },
            )

            // tunnel layer row
            LayerRow(
                label = protocolLabel,
                state = status.awg,
                detail = when (status.awg) {
                    LayerState.STARTING -> if (status.ygg == LayerState.UP)
                        "Pinging server…" else "Waiting for Ygg peers…"
                    else -> null
                },
            )

            // Restart AWG — shown whenever VPN is running so the user can retry the tunnel
            if (status.overall == VpnState.CONNECTED || status.overall == VpnState.CONNECTING) {
                val awgError = status.awg == LayerState.ERROR
                OutlinedButton(
                    onClick = onRestartAwg,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = if (awgError) MaterialTheme.colorScheme.error
                                       else MaterialTheme.colorScheme.secondary,
                    ),
                    border = androidx.compose.foundation.BorderStroke(
                        1.dp,
                        if (awgError) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.secondary,
                    ),
                ) {
                    Icon(Icons.Default.Refresh, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Restart $protocolLabel")
                }
            }
        }
    }
}

@Composable
private fun LayerRow(label: String, state: LayerState, detail: String?) {
    val (dot, color) = when (state) {
        LayerState.UP       -> "●" to MaterialTheme.colorScheme.primary
        LayerState.STARTING -> "◑" to MaterialTheme.colorScheme.tertiary
        LayerState.ERROR    -> "✕" to MaterialTheme.colorScheme.error
        LayerState.IDLE     -> "○" to MaterialTheme.colorScheme.outline
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(dot, color = color, style = MaterialTheme.typography.bodyMedium)
        Column(modifier = Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            if (detail != null) {
                Text(detail, style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline)
            }
        }
        Text(
            text = when (state) {
                LayerState.UP       -> "UP"
                LayerState.STARTING -> "…"
                LayerState.ERROR    -> "ERR"
                LayerState.IDLE     -> "—"
            },
            style = MaterialTheme.typography.labelSmall,
            color = color,
        )
    }
}

@Composable
private fun AwgConfigCard(protocolLabel: String, endpoint: String?, onImportClick: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("$protocolLabel Config", style = MaterialTheme.typography.titleSmall)
            if (endpoint != null) {
                Text(endpoint, style = MaterialTheme.typography.bodyMedium)
            } else {
                Text("No config imported", style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.outline)
            }
            OutlinedButton(onClick = onImportClick, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.FileOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(if (endpoint != null) "Replace .conf" else "Import .conf")
            }
        }
    }
}

@Composable
private fun PeersCard(selectedCount: Int, onBrowseClick: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Yggdrasil Peers", style = MaterialTheme.typography.titleSmall)
            Text(
                if (selectedCount > 0) "$selectedCount peer(s) selected" else "No peers selected",
                style = MaterialTheme.typography.bodyMedium,
                color = if (selectedCount > 0) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.outline,
            )
            OutlinedButton(onClick = onBrowseClick, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.Language, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Browse by country")
            }
        }
    }
}
