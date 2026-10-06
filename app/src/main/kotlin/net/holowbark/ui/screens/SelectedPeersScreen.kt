package net.holowbark.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Language
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import net.holowbark.ui.TunnelViewModel
import net.holowbark.ui.contentWidth
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import net.holowbark.R
import net.holowbark.ui.Hint
import net.holowbark.ui.SectionHeader
import net.holowbark.ui.SwitchRow
import net.holowbark.ui.peerErrorSummary
import net.holowbark.vpn.VpnState
import net.holowbark.vpn.YggNetworkState
import java.net.URI

/**
 * Everything the tunnel will dial, whether it came from the public list or was
 * typed in. This is the only place a peer outside the public list can be seen.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SelectedPeersScreen(
    vm: TunnelViewModel,
    onBrowsePublic: () -> Unit,
    onBack: () -> Unit,
) {
    val selected by vm.selectedPeers.collectAsState()
    val selection by vm.selection.collectAsState()
    val autoPeerSearch by vm.autoPeerSearch.collectAsState()
    val live by YggNetworkState.peers.collectAsState()
    val selfAddr by YggNetworkState.selfAddress.collectAsState()
    val tunnelState by vm.tunnelStatus.collectAsState()
    val multicastEnabled by vm.multicastEnabled.collectAsState()
    val multicastPassword by vm.multicastPassword.collectAsState()
    var input by rememberSaveable { mutableStateOf("") }
    var error by rememberSaveable { mutableStateOf<String?>(null) }

    fun add() {
        error = vm.addCustomPeer(input)?.message
        if (error == null) input = ""
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Peers") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
        bottomBar = {
            if (selected.isNotEmpty()) {
                Surface(tonalElevation = 3.dp) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "${selected.size} selected",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Button(onClick = { vm.applySelectedPeers(); onBack() }) {
                            Text("Apply")
                        }
                    }
                }
            }
        },
    ) { padding ->
        // The overlay reports its peers only while it runs; before that there is
        // no state to show, not a peer that is down.
        val running = selfAddr.isNotEmpty()
        val sorted = remember(selected) { selected.toList().sorted() }
        val matched = remember(sorted, live) {
            sorted.associateWith { uri -> live.firstOrNull { sameEndpoint(it.uri, uri) } }
        }
        val others = remember(matched, live) {
            val taken = matched.values.filterNotNull().toSet()
            live.filter { it !in taken }
        }
        val showHint = tunnelState.overall != VpnState.DISCONNECTED &&
            tunnelState.overall != VpnState.IDLE

        val searchSection: LazyListScope.() -> Unit = {
            item {
                SwitchRow(
                    title = stringResource(R.string.peers_auto_title),
                    subtitle = stringResource(R.string.peers_auto_subtitle),
                    checked = autoPeerSearch,
                    onToggle = { vm.setAutoPeerSearch(!autoPeerSearch) },
                )
            }
        }

        val addSection: LazyListScope.() -> Unit = {
            item {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it; error = null },
                    modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 16.dp),
                    label = { Text("Add a peer") },
                    placeholder = { Text("tls://example.org:443") },
                    singleLine = true,
                    isError = error != null,
                    supportingText = {
                        Text(error ?: "Any Yggdrasil peer: your own node, or one on your LAN.")
                    },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { add() }),
                    trailingIcon = {
                        IconButton(onClick = { add() }, enabled = input.isNotBlank()) {
                            Icon(Icons.Default.Add, contentDescription = "Add peer")
                        }
                    },
                )
            }
            item {
                OutlinedButton(
                    onClick = onBrowsePublic,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                ) {
                    Icon(Icons.Default.Language, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Browse public peers")
                }
            }
        }

        val peerSection: LazyListScope.() -> Unit = {
            item { SectionHeader(stringResource(R.string.peers_selected_header)) }
            if (sorted.isEmpty()) {
                item {
                    Hint(
                        if (autoPeerSearch) stringResource(R.string.peers_auto_empty)
                        else "No peers yet. Without at least one, the overlay cannot connect."
                    )
                }
            } else {
                items(sorted, key = { it }) { uri ->
                    PeerRow(
                        uri = uri,
                        live = matched[uri],
                        running = running,
                        auto = uri in selection.derived,
                        onRemove = { vm.removePeer(uri) },
                    )
                }
            }
            if (others.isNotEmpty()) {
                item { SectionHeader(stringResource(R.string.peers_other_header)) }
                items(others, key = { "live_" + it.uri }) { peer ->
                    PeerRow(uri = peer.uri, live = peer, running = running, auto = false, onRemove = null)
                }
            }
        }

        val discoverySection: LazyListScope.() -> Unit = {
            item {
                SectionHeader(stringResource(R.string.discovery_header))
                SwitchRow(
                    title = stringResource(R.string.discovery_title),
                    subtitle = stringResource(R.string.discovery_subtitle),
                    checked = multicastEnabled,
                    enabled = multicastPassword.isNotEmpty(),
                    onToggle = vm::toggleMulticast,
                )
                OutlinedTextField(
                    value = multicastPassword,
                    onValueChange = vm::setMulticastPassword,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                    label = { Text(stringResource(R.string.discovery_passphrase)) },
                    singleLine = true,
                    supportingText = {
                        if (multicastPassword.isEmpty()) {
                            Text(stringResource(R.string.discovery_passphrase_needed))
                        }
                    },
                )
                if (showHint) Hint(stringResource(R.string.next_connect))
            }
        }

        BoxWithConstraints(Modifier.fillMaxSize().padding(padding)) {
            if (maxWidth > maxHeight) {
                // Lying down the list gets its own column and the height with it,
                // instead of starting under the field and the discovery settings.
                Row(Modifier.fillMaxSize()) {
                    LazyColumn(Modifier.weight(1f).fillMaxHeight()) {
                        searchSection()
                        addSection()
                        discoverySection()
                    }
                    VerticalDivider(Modifier.padding(vertical = 8.dp))
                    LazyColumn(Modifier.weight(1.6f).fillMaxHeight(), content = peerSection)
                }
            } else {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                    LazyColumn(Modifier.contentWidth()) {
                        searchSection()
                        addSection()
                        peerSection()
                        item { HorizontalDivider(Modifier.padding(vertical = 8.dp)) }
                        discoverySection()
                    }
                }
            }
        }
    }
}

// ponytail: scheme, host and port only; two peers that differ only in their query
// would share one state line. Yggdrasil reports the URI it dialed, which need not
// match the stored string character for character.
private fun sameEndpoint(a: String, b: String): Boolean = a == b || runCatching {
    val x = URI(a)
    val y = URI(b)
    x.scheme == y.scheme && x.host.equals(y.host, ignoreCase = true) && x.port == y.port
}.getOrDefault(false)

@Composable
private fun PeerRow(
    uri: String,
    live: YggNetworkState.PeerInfo?,
    running: Boolean,
    auto: Boolean,
    onRemove: (() -> Unit)?,
) {
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)
            .padding(start = 20.dp, end = if (onRemove != null) 8.dp else 20.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = uri,
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall,
            )
            if (auto) {
                Text(
                    text = stringResource(R.string.peer_auto),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            if (running) PeerState(live, origin = if (onRemove == null) live?.let { originLabel(it) } else null)
        }
        if (onRemove != null) {
            IconButton(onClick = onRemove) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = "Remove peer",
                    tint = MaterialTheme.colorScheme.outline,
                )
            }
        }
    }
}

@Composable
private fun originLabel(peer: YggNetworkState.PeerInfo) =
    stringResource(if (peer.inbound) R.string.peer_inbound else R.string.peer_discovered)

/** An icon and a word for the state, so it never rests on colour alone. */
@Composable
private fun PeerState(live: YggNetworkState.PeerInfo?, origin: String?) {
    val up = live?.up == true
    val icon = when {
        live == null -> Icons.Default.Schedule
        up -> Icons.Default.CheckCircle
        else -> Icons.Default.ErrorOutline
    }
    val tint = when {
        live == null -> MaterialTheme.colorScheme.outline
        up -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.error
    }
    val parts = listOfNotNull(
        stringResource(when {
            live == null -> R.string.peer_waiting
            up -> R.string.peer_up
            else -> R.string.peer_down
        }),
        origin,
        live?.takeIf { up && it.latencyMs >= 0 }?.let {
            stringResource(R.string.peer_latency, if (it.latencyMs < 1) "<1" else "%.0f".format(it.latencyMs))
        },
        live?.takeIf { up && it.uptimeSec >= 0 }?.let {
            stringResource(R.string.peer_uptime, it.uptimeSec.fmtUptime())
        },
        live?.takeIf { !up }?.let { peerErrorSummary(it.lastError) },
    )
    Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(6.dp))
        Text(
            text = parts.joinToString(" · "),
            style = MaterialTheme.typography.labelMedium,
            color = if (live != null && !up) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.outline,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
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
