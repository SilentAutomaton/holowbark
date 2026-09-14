package net.holowbark.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.holowbark.R
import net.holowbark.ui.TunnelViewModel
import net.holowbark.vpn.YggNetworkState
import kotlin.math.abs
import net.holowbark.ui.copyToClipboard
import net.holowbark.ui.latencyColor
import net.holowbark.ui.peerErrorSummary

/** Narrower than this a card stops holding its own content, so it gets the row. */
private val CARD_MIN_WIDTH = 360.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun YggNetworkScreen(vm: TunnelViewModel, onBack: () -> Unit) {
    val selfAddr      by YggNetworkState.selfAddress.collectAsState()
    val peers         by YggNetworkState.peers.collectAsState()
    val pingMs        by YggNetworkState.pingMs.collectAsState()
    val pinging       by YggNetworkState.pinging.collectAsState()
    val awgConf       by vm.awgConfig.collectAsState()
    val yggDnsEnabled by vm.yggDnsEnabled.collectAsState()
    val multicastEnabled by vm.multicastEnabled.collectAsState()
    val multicastPassword by vm.multicastPassword.collectAsState()
    val ctx           = LocalContext.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Network") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        }
    ) { padding ->
        // Each card is a self-contained block, so on anything wider than a phone
        // held upright they sit side by side instead of stretching. Staggered
        // rather than a plain grid: the cards differ in height, and a grid row
        // sized to its tallest card leaves holes under the short ones.
        LazyVerticalStaggeredGrid(
            columns = StaggeredGridCells.Adaptive(CARD_MIN_WIDTH),
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 12.dp),
            verticalItemSpacing = 12.dp,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                SectionCard(title = "My Address") {
                    Column(modifier = Modifier.padding(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (selfAddr.isEmpty()) {
                            Text("VPN not running", color = MaterialTheme.colorScheme.outline)
                        } else {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = selfAddr,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 13.sp,
                                    modifier = Modifier.weight(1f),
                                )
                                IconButton(onClick = {
                                    ctx.copyToClipboard("Yggdrasil address", selfAddr)
                                }) {
                                    Icon(Icons.Default.ContentCopy, "Copy address",
                                        modifier = Modifier.size(18.dp))
                                }
                            }
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                "Persistent identity key. Takes effect on next connect.",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.outline,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(
                                onClick = { vm.resetYggKey() },
                                colors = ButtonDefaults.textButtonColors(
                                    contentColor = MaterialTheme.colorScheme.error),
                            ) {
                                Text("Regenerate", style = MaterialTheme.typography.labelMedium)
                            }
                        }
                    }
                }
            }

            item {
                val endpoint = awgConf?.endpoint
                SectionCard(title = "${awgConf?.protocolName ?: "WireGuard"} Server Ping") {
                    Column(modifier = Modifier.padding(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (endpoint == null) {
                            Text("No config loaded", color = MaterialTheme.colorScheme.outline)
                        } else {
                            Text(endpoint, fontFamily = FontFamily.Monospace, fontSize = 12.sp,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Button(
                                onClick = { vm.pingAwgServer() },
                                enabled = endpoint != null && !pinging && selfAddr.isNotEmpty(),
                            ) {
                                if (pinging) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(16.dp),
                                        strokeWidth = 2.dp,
                                        color = MaterialTheme.colorScheme.onPrimary,
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text("Pinging…")
                                } else {
                                    Icon(Icons.Default.Refresh, null,
                                        modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(8.dp))
                                    Text("Ping")
                                }
                            }
                            pingMs?.let { ms ->
                                Text(
                                    text = if (ms < 0) "Timeout" else "${ms}ms",
                                    color = latencyColor(ms),
                                    style = MaterialTheme.typography.bodyLarge,
                                )
                            }
                        }
                    }
                }
            }

            item {
                val serverKey by vm.serverKey.collectAsState()
                val probing by YggNetworkState.probing.collectAsState()
                val probeFound by YggNetworkState.probeFound.collectAsState()
                SectionCard(title = stringResource(R.string.net_server_key)) {
                    Column(modifier = Modifier.padding(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = serverKey.ifEmpty {
                                stringResource(R.string.net_server_key_unknown)
                            },
                            fontFamily = if (serverKey.isEmpty()) FontFamily.Default
                                         else FontFamily.Monospace,
                            fontSize = 12.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            stringResource(R.string.net_server_key_caption),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline,
                        )
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Button(
                                onClick = { vm.probeServer() },
                                enabled = serverKey.isNotEmpty() && !probing && selfAddr.isNotEmpty(),
                            ) {
                                if (probing) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(16.dp),
                                        strokeWidth = 2.dp,
                                        color = MaterialTheme.colorScheme.onPrimary,
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text(stringResource(R.string.net_probing))
                                } else {
                                    Icon(Icons.Default.Refresh, null,
                                        modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(8.dp))
                                    Text(stringResource(R.string.net_probe))
                                }
                            }
                            probeFound?.let { found ->
                                Text(
                                    text = stringResource(
                                        if (found) R.string.net_probe_found
                                        else R.string.net_probe_missing
                                    ),
                                    color = if (found) MaterialTheme.colorScheme.primary
                                            else MaterialTheme.colorScheme.error,
                                    style = MaterialTheme.typography.bodyLarge,
                                )
                            }
                        }
                        Text(
                            stringResource(R.string.net_probe_caption),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline,
                        )
                    }
                }
            }

            item {
                SectionCard(title = "Yggdrasil DNS") {
                    Column(modifier = Modifier.padding(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                ".ygg domains via Yggdrasil overlay; all other DNS via your WireGuard server's DNS.",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.outline,
                                modifier = Modifier.weight(1f),
                            )
                            Switch(checked = yggDnsEnabled,
                                onCheckedChange = { vm.toggleYggDns() })
                        }
                        if (yggDnsEnabled) {
                            Text("Takes effect on next connect.",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.outline)
                        }
                    }
                }
            }

            item {
                SectionCard(title = "Local network discovery") {
                    Column(modifier = Modifier.padding(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                "Find your other devices on this Wi-Fi directly, with no " +
                                "internet. The passphrase decides whose devices — every " +
                                "device of yours needs the same one.",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.outline,
                                modifier = Modifier.weight(1f),
                            )
                            Switch(
                                checked = multicastEnabled,
                                enabled = multicastPassword.isNotEmpty(),
                                onCheckedChange = { vm.toggleMulticast() },
                            )
                        }
                        OutlinedTextField(
                            value = multicastPassword,
                            onValueChange = { vm.setMulticastPassword(it) },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("Passphrase") },
                            singleLine = true,
                            supportingText = {
                                Text(
                                    if (multicastPassword.isEmpty())
                                        "Needed before discovery can be switched on."
                                    else
                                        "Takes effect on next connect."
                                )
                            },
                        )
                    }
                }
            }


            val sortedPeers = peers.sortedByDescending { it.up }
            item {
                val upCount = peers.count { it.up }
                SectionCard(title = "Peers ($upCount / ${peers.size} up)") {
                    if (peers.isEmpty()) {
                        Text(
                            if (selfAddr.isEmpty()) "VPN not running"
                            else "No peers connected yet",
                            color = MaterialTheme.colorScheme.outline,
                            modifier = Modifier.padding(8.dp),
                        )
                    }
                }
            }

            items(sortedPeers, key = { it.uri }) { peer ->
                LivePeerRow(peer, onRemove = { vm.removePeer(peer.uri) })
            }

            item { Spacer(Modifier.height(8.dp)) }
        }
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            )
            HorizontalDivider(thickness = 0.5.dp)
            content()
        }
    }
}

@Composable
private fun LivePeerRow(peer: YggNetworkState.PeerInfo, onRemove: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        ),
    ) {
        Row(
            modifier = Modifier.padding(start = 12.dp, top = 10.dp, bottom = 10.dp, end = 4.dp),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // Status dot
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(if (peer.up) Color(0xFF4CAF50) else MaterialTheme.colorScheme.error)
                    .align(Alignment.CenterVertically)
            )

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                // URI
                Text(
                    text = peer.uri,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )

                // Inbound badge + error
                val errorText = peerErrorSummary(peer.lastError)
                if (peer.inbound || errorText != null) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (peer.inbound) {
                            Badge { Text("inbound", style = MaterialTheme.typography.labelSmall) }
                        }
                        if (errorText != null) {
                            Text(
                                text = errorText,
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.labelSmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }

                // Stats row
                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (peer.latencyMs >= 0) StatChip("${peer.latencyMs.fmtMs()}")
                    if (peer.uptimeSec >= 0) StatChip("up ${peer.uptimeSec.fmtUptime()}")
                    StatChip("↑${peer.bytesSent.fmtBytes()}")
                    StatChip("↓${peer.bytesRecvd.fmtBytes()}")
                }
            }

            // Remove button
            IconButton(
                onClick = onRemove,
                modifier = Modifier.size(32.dp).align(Alignment.CenterVertically),
            ) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = "Remove peer",
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.outline,
                )
            }
        }
    }
}

@Composable
private fun StatChip(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontSize = 10.sp,
    )
}

private fun Double.fmtMs() = when {
    this < 1.0  -> "<1ms"
    this < 1000 -> "${"%.1f".format(this)}ms"
    else        -> "${"%.2f".format(this / 1000)}s"
}

private fun Double.fmtUptime(): String {
    val t = toLong()
    val h = t / 3600; val m = (t % 3600) / 60; val s = t % 60
    return when {
        h > 0 -> "${h}h ${m}m"
        m > 0 -> "${m}m ${s}s"
        else  -> "${s}s"
    }
}

private fun Long.fmtBytes(): String {
    val v = abs(this)
    return when {
        v >= 1_073_741_824L -> "${"%.1f".format(v / 1_073_741_824.0)}G"
        v >= 1_048_576L     -> "${"%.1f".format(v / 1_048_576.0)}M"
        v >= 1_024L         -> "${"%.1f".format(v / 1_024.0)}K"
        else                -> "${v}B"
    }
}
