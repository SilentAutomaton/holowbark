package net.holowbark.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import net.holowbark.R
import net.holowbark.ui.Hint
import net.holowbark.ui.InstalledApp
import net.holowbark.ui.SectionHeader
import net.holowbark.ui.TunnelViewModel
import net.holowbark.ui.contentWidth
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
    val allowList by vm.appsAllowList.collectAsState()
    val apps by vm.installedApps.collectAsState()
    val tunnelState by vm.tunnelStatus.collectAsState()
    var query by rememberSaveable { mutableStateOf("") }
    var addingSubnet by rememberSaveable { mutableStateOf(false) }

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

        // Only worth saying while there is a tunnel for the change to miss.
        val showHint = tunnelState.overall != VpnState.DISCONNECTED &&
            tunnelState.overall != VpnState.IDLE

        val subnetSection: LazyListScope.() -> Unit = {
            if (showHint) item { Hint(stringResource(R.string.next_connect)) }
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
        }

        val appSection: LazyListScope.() -> Unit = {
            item {
                SectionHeader(title = stringResource(R.string.split_apps_header))
                AppsModeSelector(allowList = allowList, onSelect = vm::setAppsAllowList)
                Hint(
                    when {
                        allowList && bypassedApps.isEmpty() ->
                            stringResource(R.string.split_apps_none_inside)
                        allowList -> stringResource(R.string.split_mode_inside_help)
                        else -> stringResource(R.string.split_mode_outside_help)
                    }
                )
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

        BoxWithConstraints(Modifier.fillMaxSize().padding(padding)) {
            if (maxWidth > maxHeight) {
                // Lying down there is room for both lists at once, and the apps —
                // the long list, the one being searched — get the height instead of
                // spending it on a header the subnets already used.
                Row(Modifier.fillMaxSize()) {
                    LazyColumn(Modifier.weight(1f).fillMaxHeight(), content = subnetSection)
                    VerticalDivider(Modifier.padding(vertical = 8.dp))
                    LazyColumn(Modifier.weight(1.6f).fillMaxHeight(), content = appSection)
                }
            } else {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                    LazyColumn(Modifier.contentWidth()) {
                        subnetSection()
                        item { HorizontalDivider(Modifier.padding(vertical = 8.dp)) }
                        appSection()
                    }
                }
            }
        }
    }
}

@Composable
private fun AddSubnetDialog(onDismiss: () -> Unit, onAdd: (String) -> Boolean) {
    var text by rememberSaveable { mutableStateOf("") }
    var invalid by rememberSaveable { mutableStateOf(false) }

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

/**
 * Which side of the tunnel the ticked apps are on. Two exclusive choices, so a
 * segmented row rather than a switch: a switch would have to be labelled with one
 * of the two states and leave the reader to infer the other.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppsModeSelector(allowList: Boolean, onSelect: (Boolean) -> Unit) {
    val label = stringResource(R.string.split_mode_label)
    SingleChoiceSegmentedButtonRow(
        modifier = Modifier.fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 4.dp)
            .semantics { contentDescription = label },
    ) {
        SegmentedButton(
            selected = !allowList,
            onClick = { onSelect(false) },
            shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
        ) {
            Text(stringResource(R.string.split_mode_outside), maxLines = 1)
        }
        SegmentedButton(
            selected = allowList,
            onClick = { onSelect(true) },
            shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
        ) {
            Text(stringResource(R.string.split_mode_inside), maxLines = 1)
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
            // One toggleable row, merged, so a screen reader reads the app and its
            // state together instead of announcing a nameless checkbox after it.
            .semantics(mergeDescendants = true) {}
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
