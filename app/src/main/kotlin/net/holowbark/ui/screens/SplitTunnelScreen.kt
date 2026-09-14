package net.holowbark.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import net.holowbark.R
import net.holowbark.ui.InstalledApp
import net.holowbark.ui.TunnelViewModel
import net.holowbark.vpn.VpnState

/** Adaptive icons render at their intrinsic size, which is far more than a row needs. */
private const val ICON_PX = 96

private val ROW_HEIGHT = 56.dp

/**
 * What leaves the device outside the tunnel: whole apps, and subnets that stay on
 * the local network. Both are read when the tunnel is built, so neither changes a
 * running one.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SplitTunnelScreen(vm: TunnelViewModel, onBack: () -> Unit) {
    val subnets by vm.bypassedSubnets.collectAsState()
    val bypassedApps by vm.bypassedApps.collectAsState()
    val apps by vm.installedApps.collectAsState()
    val tunnelState by vm.tunnelStatus.collectAsState()
    var query by remember { mutableStateOf("") }
    var addingSubnet by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { vm.loadInstalledApps() }

    if (addingSubnet) {
        AddSubnetDialog(
            onDismiss = { addingSubnet = false },
            onAdd = vm::addBypassedSubnet,
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.split_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back),
                        )
                    }
                },
            )
        },
    ) { padding ->
        val matching = remember(apps, query) {
            val needle = query.trim().lowercase()
            apps.orEmpty().filter {
                needle.isEmpty() ||
                    it.label.lowercase().contains(needle) ||
                    it.packageName.lowercase().contains(needle)
            }
        }

        LazyColumn(modifier = Modifier.fillMaxSize().padding(padding)) {
            // Only worth saying while there is a tunnel for the change to miss.
            if (tunnelState.overall != VpnState.DISCONNECTED &&
                tunnelState.overall != VpnState.IDLE) {
                item { Hint(stringResource(R.string.split_next_connect)) }
            }

            item {
                SectionHeader(
                    title = stringResource(R.string.split_subnets_header),
                    action = {
                        IconButton(onClick = { addingSubnet = true }) {
                            Icon(
                                Icons.Default.Add,
                                contentDescription = stringResource(R.string.split_add_subnet),
                            )
                        }
                    },
                )
            }

            if (subnets.isEmpty()) {
                item { Hint(stringResource(R.string.split_subnets_empty)) }
            } else {
                items(subnets.toList().sorted(), key = { it }) { subnet ->
                    SubnetRow(subnet = subnet, onRemove = { vm.removeBypassedSubnet(subnet) })
                }
            }

            item {
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                SectionHeader(title = stringResource(R.string.split_apps_header))
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text(stringResource(R.string.split_apps_search)) },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                )
            }

            when {
                apps == null -> item {
                    Box(Modifier.fillMaxWidth().padding(32.dp), Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
                matching.isEmpty() -> item {
                    Hint(stringResource(R.string.split_apps_no_match, query.trim()))
                }
                else -> items(matching, key = { it.packageName }) { app ->
                    AppRow(
                        app = app,
                        checked = app.packageName in bypassedApps,
                        onToggle = { vm.toggleBypassedApp(app.packageName) },
                    )
                }
            }
        }
    }
}

@Composable
private fun AddSubnetDialog(onDismiss: () -> Unit, onAdd: (String) -> Boolean) {
    var text by remember { mutableStateOf("") }
    var invalid by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.split_add_subnet)) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it; invalid = false },
                label = { Text(stringResource(R.string.split_subnet_label)) },
                isError = invalid,
                supportingText = {
                    if (invalid) Text(stringResource(R.string.split_subnet_invalid))
                },
                singleLine = true,
            )
        },
        confirmButton = {
            TextButton(
                onClick = { if (onAdd(text)) onDismiss() else invalid = true },
                enabled = text.isNotBlank(),
            ) {
                Text(stringResource(R.string.split_add_subnet))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}

@Composable
private fun SubnetRow(subnet: String, onRemove: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = ROW_HEIGHT)
            .padding(start = 20.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = subnet,
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onRemove) {
            Icon(
                Icons.Default.Close,
                contentDescription = stringResource(R.string.split_remove_subnet, subnet),
            )
        }
    }
}

@Composable
private fun AppRow(app: InstalledApp, checked: Boolean, onToggle: () -> Unit) {
    val context = LocalContext.current
    // Only the visible rows are composed, so the icon is read when it is needed
    // rather than for every installed app at once.
    val icon: ImageBitmap? = remember(app.packageName) {
        runCatching {
            context.packageManager.getApplicationIcon(app.packageName)
                .toBitmap(ICON_PX, ICON_PX).asImageBitmap()
        }.getOrNull()
    }

    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = ROW_HEIGHT)
            // One toggleable row, so a screen reader reads the app and its state
            // together instead of announcing a nameless checkbox after it.
            .toggleable(value = checked, role = Role.Checkbox, onValueChange = { onToggle() })
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Image(bitmap = icon, contentDescription = null, modifier = Modifier.size(32.dp))
        } else {
            Spacer(Modifier.size(32.dp))
        }
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(
                app.label,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                app.packageName,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(12.dp))
        Checkbox(checked = checked, onCheckedChange = null)
    }
}

@Composable
private fun SectionHeader(title: String, action: @Composable (() -> Unit)? = null) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.weight(1f),
        )
        action?.invoke()
    }
}

@Composable
private fun Hint(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.outline,
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
    )
}
