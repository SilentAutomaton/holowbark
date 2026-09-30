package net.holowbark.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.holowbark.R
import net.holowbark.ui.CONTENT_MAX_WIDTH
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
    onAddServer: () -> Unit,
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
        val ring: @Composable (Dp) -> Unit = { diameter ->
            RingAndButton(
                status = status,
                labels = labels,
                diameter = diameter,
                onClick = {
                    if (!hasConfig) onAddServer()
                    else if (status.overall.isStoppable()) vm.disconnect()
                    else onRequestVpnPermission()
                },
            )
        }
        val caption: @Composable ColumnScope.() -> Unit = {
            TunnelStatusText(labels)
            Spacer(Modifier.height(24.dp))
            // Restarting the tunnel layer is something you reach for while watching
            // it fail, so it stays here rather than in settings. It keeps its place
            // in the layout when it does not apply: the ring is what the eye holds
            // on to, and it must not move because a button underneath appeared.
            RestartTunnelButton(
                awgState = status.awg,
                onClick = vm::restartAwg,
                visible = status.overall == VpnState.CONNECTED ||
                    status.overall == VpnState.CONNECTING,
            )
        }

        BoxWithConstraints(
            modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp),
            contentAlignment = Alignment.Center,
        ) {
            // The ring is the one thing that has to fit whole, so it is measured
            // against the shorter side of whatever space is left.
            val diameter = (minOf(maxWidth, maxHeight) * RING_SHARE)
                .coerceIn(RING_MIN, RING_MAX)

            if (maxWidth > maxHeight) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(32.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ring(diameter)
                    Column(
                        // A width, not a maximum: the row is centred, so a caption
                        // that grew with the text would shift the ring sideways.
                        modifier = Modifier.width(CONTENT_MAX_WIDTH / 2)
                            .verticalScroll(rememberScrollState()),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        content = caption,
                    )
                }
            } else {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    ring(diameter)
                    Spacer(Modifier.height(32.dp))
                    caption()
                }
            }
        }
    }
}

/** Below this the button is too small for DISCONNECT at the larger type size. */
private val LABEL_FULL_SIZE_MIN = 150.dp

/** One value for both fonts the detail line can be set in, so it measures the same. */
private val DETAIL_LINE_HEIGHT = 18.sp

/** The share of the shorter side the ring may take, and the range it stays in. */
private const val RING_SHARE = 0.75f
private val RING_MIN = 160.dp
private val RING_MAX = 300.dp

/** Close to the proportion the fixed sizes had — 168 of a 260 ring — with a little
 *  more room, because the label inside grows with the system font scale. */
private const val BUTTON_SHARE = 0.68f

@Composable
private fun RingAndButton(
    status: TunnelStatus,
    labels: StatusLabels,
    diameter: Dp,
    onClick: () -> Unit,
) {
    Box(contentAlignment = Alignment.Center) {
        MeshRing(
            yggState = status.ygg,
            yggPeers = status.yggPeers,
            awgState = status.awg,
            diameter = diameter,
        )
        ConnectButton(
            label = labels.action,
            // The ring is decoration to a screen reader; the button carries
            // the whole state as a sentence.
            description = "${labels.action}. ${labels.state}. ${labels.detail}",
            diameter = diameter * BUTTON_SHARE,
            onClick = onClick,
        )
    }
}

@Composable
private fun TunnelStatusText(labels: StatusLabels) {
    Text(
        text = labels.state,
        style = MaterialTheme.typography.titleMedium,
        color = labels.color(),
    )
    Spacer(Modifier.height(6.dp))
    // Long press selects the address for copying; nothing else here is worth it.
    if (labels.detailIsAddress) SelectionContainer { TunnelDetailText(labels) }
    else TunnelDetailText(labels)
}

@Composable
private fun TunnelDetailText(labels: StatusLabels) {
    Text(
        text = labels.detail,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.outline,
        textAlign = TextAlign.Center,
        fontFamily = if (labels.detailIsAddress) FontFamily.Monospace else null,
        fontSize = if (labels.detailIsAddress) 12.sp else 13.sp,
        // Two lines whatever it says: the stall message wraps where an address does
        // not, and monospace does not measure like the body font. Either would move
        // the ring on a state change.
        minLines = 2,
        lineHeight = DETAIL_LINE_HEIGHT,
    )
}

@Composable
private fun RestartTunnelButton(awgState: LayerState, onClick: () -> Unit, visible: Boolean) {
    TextButton(
        onClick = onClick,
        enabled = visible,
        modifier = Modifier
            .alpha(if (visible) 1f else 0f)
            .then(if (visible) Modifier else Modifier.clearAndSetSemantics {}),
        colors = ButtonDefaults.textButtonColors(
            contentColor = if (awgState == LayerState.ERROR)
                MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outline,
        ),
    ) {
        Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(8.dp))
        Text("Restart tunnel")
    }
}

@Composable
private fun ConnectButton(
    label: String,
    description: String,
    diameter: Dp,
    onClick: () -> Unit,
) {
    // The fill alone sits at 1.1:1 against the background, so the border is what
    // makes this read as a control at all — and what satisfies the 3:1 WCAG asks
    // of a non-text UI component. Ash rather than a layer colour, so the button
    // edge does not compete with what the rings are saying.
    val edge = MaterialTheme.colorScheme.outline
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        border = BorderStroke(2.dp, edge),
        modifier = Modifier.size(diameter).semantics { contentDescription = description },
    ) {
        Box(contentAlignment = Alignment.Center) {
            // DISCONNECT is four characters longer than CONNECT and has to fit the
            // same circle, which in landscape is the width of a phone's short side.
            // A step down the type scale rather than a computed size, so the system
            // font scale still decides how big the step actually is.
            val small = diameter < LABEL_FULL_SIZE_MIN
            Text(
                text = label.uppercase(),
                style = if (small) MaterialTheme.typography.labelLarge
                        else MaterialTheme.typography.titleMedium,
                letterSpacing = if (small) 1.sp else 2.sp,
                maxLines = 1,
                // Past a point — a very large font scale — the word gives way
                // rather than spilling out of the circle.
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 8.dp),
                color = MaterialTheme.colorScheme.onSurface,
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
        return StatusLabels(
            action = stringResource(R.string.connect_add_server),
            state = stringResource(R.string.connect_no_server),
            detail = stringResource(R.string.connect_no_server_detail),
        )
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
