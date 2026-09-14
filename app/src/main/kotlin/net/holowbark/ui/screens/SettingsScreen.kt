package net.holowbark.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import net.holowbark.R
import net.holowbark.ui.TunnelViewModel
import net.holowbark.ui.contentWidth

/**
 * Everything that is not connecting. Grouped by what the user is trying to fix:
 * what to connect to, what to connect through, and what to do when it breaks.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    vm: TunnelViewModel,
    onBack: () -> Unit,
    onOpenServer: () -> Unit,
    onOpenPeers: () -> Unit,
    onOpenSplit: () -> Unit,
    onOpenNetwork: () -> Unit,
    onOpenLogs: () -> Unit,
) {
    val awgConfig by vm.awgConfig.collectAsState()
    val selectedPeers by vm.selectedPeers.collectAsState()
    val autoRecover by vm.autoRecoverEnabled.collectAsState()
    val oled by vm.oledTheme.collectAsState()
    val bypassedApps by vm.bypassedApps.collectAsState()
    val bypassedSubnets by vm.bypassedSubnets.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
        Column(
            modifier = Modifier.contentWidth().verticalScroll(rememberScrollState()),
        ) {
            SettingsRow(
                icon = Icons.Default.VpnKey,
                title = "Server",
                subtitle = awgConfig?.endpoint ?: "No config imported",
                onClick = onOpenServer,
            )
            SettingsRow(
                icon = Icons.Default.Hub,
                title = "Peers",
                subtitle = if (selectedPeers.isEmpty()) "None selected"
                           else "${selectedPeers.size} selected",
                onClick = onOpenPeers,
            )
            SettingsRow(
                icon = Icons.Default.CallSplit,
                title = stringResource(R.string.split_title),
                subtitle = bypassSummary(bypassedApps.size, bypassedSubnets.size),
                onClick = onOpenSplit,
            )
            SettingsRow(
                icon = Icons.Default.Lan,
                title = "Network",
                subtitle = "Address, live peers, DNS, discovery",
                onClick = onOpenNetwork,
            )

            HorizontalDivider(Modifier.padding(vertical = 8.dp))

            SettingsSwitch(
                icon = Icons.Default.Contrast,
                title = stringResource(R.string.oled_title),
                subtitle = stringResource(R.string.oled_subtitle),
                checked = oled,
                onToggle = vm::toggleOledTheme,
            )
            SettingsSwitch(
                icon = Icons.Default.HealthAndSafety,
                title = "Auto-recover",
                subtitle = "Rebuild the overlay when the server stops answering. " +
                    "Only probes while the tunnel is idle.",
                checked = autoRecover,
                onToggle = vm::toggleAutoRecover,
            )

            HorizontalDivider(Modifier.padding(vertical = 8.dp))

            SettingsRow(
                icon = Icons.Default.Terminal,
                title = "Logs",
                subtitle = "The last 500 lines",
                onClick = onOpenLogs,
            )
            IdentityRow(vm)
        }
    }
        }
}

/** Names both halves, because an empty one still leaves the other in force. */
@Composable
private fun bypassSummary(apps: Int, subnets: Int): String {
    if (apps == 0 && subnets == 0) return stringResource(R.string.split_nothing_bypasses)
    return stringResource(
        R.string.split_summary,
        pluralStringResource(R.plurals.split_apps, apps, apps),
        pluralStringResource(R.plurals.split_subnets, subnets, subnets),
    )
}

@Composable
private fun IdentityRow(vm: TunnelViewModel) {
    var confirming by rememberSaveable { mutableStateOf(false) }

    if (confirming) {
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text("Regenerate identity?") },
            text = {
                Text(
                    "Your overlay address is derived from this key, so a new key means " +
                    "a new address — and any server allow-listing the old one stops " +
                    "accepting you until it is updated."
                )
            },
            confirmButton = {
                TextButton(onClick = { vm.resetYggKey(); confirming = false }) {
                    Text("Regenerate")
                }
            },
            dismissButton = {
                TextButton(onClick = { confirming = false }) { Text("Keep it") }
            },
        )
    }

    SettingsRow(
        icon = Icons.Default.Fingerprint,
        title = "Identity",
        subtitle = "Regenerate the Yggdrasil key",
        onClick = { confirming = true },
    )
}

@Composable
private fun SettingsRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.outline)
        Spacer(Modifier.width(20.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
    }
}

@Composable
private fun SettingsSwitch(
    icon: ImageVector,
    title: String,
    subtitle: String,
    checked: Boolean,
    onToggle: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onToggle)
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.outline)
        Spacer(Modifier.width(20.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = { onToggle() })
    }
}
