package net.holowbark.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.holowbark.ui.TunnelViewModel
import kotlinx.coroutines.delay
import net.holowbark.ui.components.MeshRing
import net.holowbark.ui.peerErrorSummary
import net.holowbark.vpn.YggNetworkState
import net.holowbark.vpn.LayerState
import net.holowbark.vpn.TunnelStatus
import net.holowbark.vpn.VpnState

/**
 * The whole app in one control: a ring that shows what the tunnel is doing and a
 * button that changes it. Everything else lives behind the gear.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectScreen(
    vm: TunnelViewModel,
    onRequestVpnPermission: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val status by vm.tunnelStatus.collectAsState()
    val awgConfig by vm.awgConfig.collectAsState()
    val selectedPeers by vm.selectedPeers.collectAsState()
    val error by vm.errorMessage.collectAsState()
    val livePeers by YggNetworkState.peers.collectAsState()

    // "Finding peers…" forever tells the user nothing. After a while, say what is
    // actually happening instead: how many peers answered, and why the rest did not.
    var stalled by remember { mutableStateOf(false) }
    LaunchedEffect(status.overall, status.ygg) {
        stalled = false
        if (status.overall == VpnState.CONNECTING && status.ygg != LayerState.UP) {
            delay(STALL_AFTER_MS)
            stalled = true
        }
    }

    error?.let { message ->
        AlertDialog(
            onDismissRequest = vm::clearError,
            text = { Text(message) },
            confirmButton = { TextButton(onClick = vm::clearError) { Text("OK") } },
        )
    }

    val hasConfig = awgConfig != null
    val labels = statusLabels(
        status = status,
        hasConfig = hasConfig,
        peerCount = selectedPeers.size,
        stallDetail = if (stalled) stallDetail(selectedPeers.size, livePeers) else null,
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Holowbark") },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Box(contentAlignment = Alignment.Center) {
                MeshRing(
                    yggState = status.ygg,
                    yggPeers = status.yggPeers,
                    awgState = status.awg,
                )
                ConnectButton(
                    label = labels.action,
                    enabled = hasConfig,
                    // The ring is decoration to a screen reader; the button carries
                    // the whole state as a sentence.
                    description = "${labels.action}. ${labels.state}. ${labels.detail}",
                    onClick = {
                        if (status.overall.isStoppable()) vm.disconnect()
                        else onRequestVpnPermission()
                    },
                )
            }

            Spacer(Modifier.height(32.dp))

            Text(
                text = labels.state,
                style = MaterialTheme.typography.titleMedium,
                color = labels.color(),
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = labels.detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
                textAlign = TextAlign.Center,
                fontFamily = if (labels.detailIsAddress) FontFamily.Monospace else null,
                fontSize = if (labels.detailIsAddress) 12.sp else 13.sp,
            )

            // Restarting the tunnel layer is something you reach for while watching
            // it fail, so it stays here rather than in settings.
            if (status.overall == VpnState.CONNECTED || status.overall == VpnState.CONNECTING) {
                Spacer(Modifier.height(24.dp))
                TextButton(
                    onClick = vm::restartAwg,
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = if (status.awg == LayerState.ERROR)
                            MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outline,
                    ),
                ) {
                    Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Restart tunnel")
                }
            }
        }
    }
}

@Composable
private fun ConnectButton(
    label: String,
    enabled: Boolean,
    description: String,
    onClick: () -> Unit,
) {
    // The fill alone sits at 1.1:1 against the background, so the border is what
    // makes this read as a control at all — and what satisfies the 3:1 WCAG asks
    // of a non-text UI component. Ash rather than a layer colour, so the button
    // edge does not compete with what the rings are saying.
    val edge = MaterialTheme.colorScheme.outline
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        border = BorderStroke(2.dp, if (enabled) edge else edge.copy(alpha = 0.4f)),
        modifier = Modifier.size(168.dp).semantics { contentDescription = description },
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = label.uppercase(),
                style = MaterialTheme.typography.titleMedium,
                letterSpacing = 2.sp,
                color = if (enabled) MaterialTheme.colorScheme.onSurface
                        else MaterialTheme.colorScheme.outline,
            )
        }
    }
}

/**
 * What to say once the overlay has plainly failed to come up. Facts only: the
 * counts, and Yggdrasil's own verdict on why. "Check your connection" would say
 * nothing the user cannot already see.
 */
private fun stallDetail(selected: Int, live: List<YggNetworkState.PeerInfo>): String {
    val answered = live.count { it.up }
    val reason = live.firstNotNullOfOrNull { peerErrorSummary(it.lastError) }
    val counts = when {
        selected == 0 -> "No peers selected"
        answered == 0 -> "None of $selected peers answered"
        else -> "$answered of $selected peers answered"
    }
    return if (reason != null) "$counts — $reason" else counts
}

private fun VpnState.isStoppable() =
    this == VpnState.CONNECTED || this == VpnState.CONNECTING || this == VpnState.ERROR

/** Long enough that a slow network is not accused of being blocked. */
private const val STALL_AFTER_MS = 15_000L

private class StatusLabels(
    val action: String,
    val state: String,
    val detail: String,
    val detailIsAddress: Boolean = false,
    val isError: Boolean = false,
) {
    @Composable
    fun color() = if (isError) MaterialTheme.colorScheme.error
                  else MaterialTheme.colorScheme.onBackground
}

/** One word for what the tunnel is doing, and the one fact that matters in it. */
@Composable
private fun statusLabels(
    status: TunnelStatus,
    hasConfig: Boolean,
    peerCount: Int,
    stallDetail: String?,
): StatusLabels {
    if (!hasConfig) {
        return StatusLabels("Connect", "No server", "Import a config in Settings to begin")
    }
    return when (status.overall) {
        VpnState.CONNECTED -> StatusLabels(
            action = "Disconnect",
            state = "Connected",
            detail = status.yggAddress.ifEmpty { "$peerCount peers" },
            detailIsAddress = status.yggAddress.isNotEmpty(),
        )
        VpnState.CONNECTING -> StatusLabels(
            action = "Cancel",
            state = "Connecting",
            detail = when {
                status.ygg == LayerState.UP -> "Reaching server…"
                stallDetail != null -> stallDetail
                else -> "Finding peers…"
            },
        )
        VpnState.ERROR -> StatusLabels(
            action = "Retry",
            state = "Error",
            detail = when {
                status.ygg == LayerState.ERROR -> "The overlay could not start"
                status.awg == LayerState.ERROR -> "The server did not answer"
                else -> "See Logs in Settings"
            },
            isError = true,
        )
        else -> StatusLabels(
            action = "Connect",
            state = "Disconnected",
            detail = if (peerCount > 0) "$peerCount peers selected" else "No peers selected",
        )
    }
}
