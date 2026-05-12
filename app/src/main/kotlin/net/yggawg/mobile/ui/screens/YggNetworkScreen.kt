package net.yggawg.mobile.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.yggawg.mobile.ui.VpnStateViewModel
import net.yggawg.mobile.vpn.YggNetworkState
import net.yggawg.mobile.vpn.YggVpnService
import kotlin.math.abs

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun YggNetworkScreen(vm: VpnStateViewModel) {
    val selfAddr      by YggNetworkState.selfAddress.collectAsState()
    val peers         by YggNetworkState.peers.collectAsState()
    val pingMs        by YggNetworkState.pingMs.collectAsState()
    val pinging       by YggNetworkState.pinging.collectAsState()
    val awgConf       by vm.awgConfig.collectAsState()
    val yggDnsEnabled by vm.yggDnsEnabled.collectAsState()
    val ctx           = LocalContext.current

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("Yggdrasil Network") })
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // ── Self address ──────────────────────────────────────────────────
            item {
                SectionCard(title = "My Address") {
                    if (selfAddr.isEmpty()) {
                        Text("VPN not running", color = MaterialTheme.colorScheme.outline,
                            modifier = Modifier.padding(8.dp))
                    } else {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(8.dp),
                        ) {
                            Text(
                                text = selfAddr,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 13.sp,
                                modifier = Modifier.weight(1f),
                            )
                            IconButton(onClick = {
                                val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                cm.setPrimaryClip(ClipData.newPlainText("Yggdrasil address", selfAddr))
                            }) {
                                Icon(Icons.Default.ContentCopy, "Copy address",
                                    modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                }
            }

            // ── AWG server ping ───────────────────────────────────────────────
            item {
                val endpoint = awgConf?.endpoint
                SectionCard(title = "${if (awgConf?.isAwg == true) "AWG" else "WireGuard"} Server Ping") {
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
                                val (text, color) = when {
                                    ms < 0   -> "Timeout" to MaterialTheme.colorScheme.error
                                    ms < 100 -> "${ms}ms" to Color(0xFF4CAF50)
                                    ms < 300 -> "${ms}ms" to Color(0xFFFFD93D)
                                    else     -> "${ms}ms" to MaterialTheme.colorScheme.error
                                }
                                Text(text, color = color, style = MaterialTheme.typography.bodyLarge)
                            }
                        }
                    }
                }
            }

            // ── Yggdrasil DNS ─────────────────────────────────────────────────
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
                                "Community resolvers (Revertron).\nSupports .ygg domains and ad blocking.",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.outline,
                                modifier = Modifier.weight(1f),
                            )
                            Switch(checked = yggDnsEnabled,
                                onCheckedChange = { vm.toggleYggDns() })
                        }
                        if (yggDnsEnabled) {
                            YggVpnService.YGG_DNS_SERVERS.forEach { addr ->
                                Text(addr, style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary)
                            }
                            Text("Takes effect on next connect.",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.outline)
                        }
                    }
                }
            }

            // ── Peers ─────────────────────────────────────────────────────────
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
                PeerRow(peer, onRemove = { vm.removePeer(peer.uri) })
            }

            item { Spacer(Modifier.height(8.dp)) }
        }
    }
}

// ─── Section card ─────────────────────────────────────────────────────────────

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

// ─── Peer row ─────────────────────────────────────────────────────────────────

@Composable
private fun PeerRow(peer: YggNetworkState.PeerInfo, onRemove: () -> Unit) {
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
                val errorText = peer.lastError
                    .takeIf { it.isNotBlank() && it != "null" }
                    ?.let { raw ->
                        // Trim raw JSON dial errors to a short human-readable form
                        if (raw.startsWith("{")) {
                            runCatching {
                                val op  = Regex("\"Op\":\"([^\"]+)\"").find(raw)?.groupValues?.get(1)
                                val err = Regex("\"Err\":\"([^\"]+)\"").find(raw)?.groupValues?.get(1)
                                    ?: Regex("\"Err\":\\{[^}]*\"Err\":\"([^\"]+)\"").find(raw)?.groupValues?.get(1)
                                if (op != null && err != null) "$op: $err" else raw
                            }.getOrDefault(raw)
                        } else raw
                    }
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

// ─── Formatters ───────────────────────────────────────────────────────────────

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
