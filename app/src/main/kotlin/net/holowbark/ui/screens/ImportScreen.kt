package net.holowbark.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.holowbark.R
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import net.holowbark.config.AwgConfigParseException
import net.holowbark.config.parseAwgConf
import net.holowbark.config.toConfString
import net.holowbark.ui.TunnelViewModel
import net.holowbark.ui.Hint
import net.holowbark.ui.SectionHeader
import net.holowbark.ui.contentWidth
import net.holowbark.ui.latencyColor
import net.holowbark.vpn.YggNetworkState
private val SENSITIVE_KEYS = setOf("PrivateKey", "PresharedKey")

private fun redactConfLine(line: String): String {
    val eqIdx = line.indexOf('=')
    if (eqIdx <= 0) return line
    val key = line.substring(0, eqIdx).trim()
    return if (key in SENSITIVE_KEYS) "$key = [REDACTED]" else line
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImportScreen(
    vm: TunnelViewModel,
    onImported: () -> Unit,
) {
    val context = LocalContext.current
    var errorText by rememberSaveable { mutableStateOf<String?>(null) }
    val awgConfig     by vm.awgConfig.collectAsState()
    val rawConf       by vm.rawConfText.collectAsState()
    val protocolName = awgConfig?.protocolName ?: "WireGuard"

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        val text = runCatching {
            context.contentResolver.openInputStream(uri)?.bufferedReader()?.readText()
        }.getOrNull()

        if (text == null) { errorText = "Could not read file"; return@rememberLauncherForActivityResult }

        val config = try {
            parseAwgConf(text)
        } catch (e: AwgConfigParseException) {
            errorText = "Invalid .conf: ${e.message}"; return@rememberLauncherForActivityResult
        } catch (e: Exception) {
            errorText = "Parse error: ${e.message}"; return@rememberLauncherForActivityResult
        }
        vm.saveAwgConfig(config, text, confName(context, uri) ?: config.endpoint)
        onImported()
    }

    errorText?.let { msg ->
        AlertDialog(
            onDismissRequest = { errorText = null },
            title = { Text("Import failed") },
            text  = { Text(msg) },
            confirmButton = { TextButton(onClick = { errorText = null }) { Text("OK") } },
        )
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Server") }) }
    ) { padding ->
        BoxWithConstraints(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            val landscape = maxWidth > maxHeight
            val config: @Composable ColumnScope.() -> Unit = {
                Button(
                    onClick = { picker.launch(arrayOf("*/*")) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Default.FileOpen, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.server_add_conf))
                }
                // Lying down there is no height to share with the config, which the
                // list would squeeze to nothing, so it moves to the second column.
                if (!landscape) SavedServers(vm)

                if (awgConfig == null) {
                    Text(
                        "No config loaded. Import an AmneziaWG or WireGuard .conf file.\n" +
                        "AmneziaWG obfuscation params (Jc, Jmin, Jmax, S1, S2, H1–H4) are supported.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.outline,
                    )
                } else {
                    // Use raw imported text so no fields are lost in round-trip
                    ConfigCard(rawConf ?: awgConfig!!.toConfString(), Modifier.weight(1f))
                }
            }
            val columnModifier = Modifier.fillMaxHeight().padding(horizontal = 12.dp, vertical = 8.dp)

            if (landscape) {
                // Lying down, the checks get a column of their own instead of pressing
                // the config into a strip a few lines high.
                Row(Modifier.fillMaxSize()) {
                    Column(
                        modifier = Modifier.weight(1f).then(columnModifier),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        content = config,
                    )
                    if (awgConfig != null) {
                        Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState())) {
                            SavedServers(vm)
                            ServerChecks(vm)
                        }
                    }
                }
            } else {
                Column(
                    modifier = Modifier.contentWidth().then(columnModifier),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    config()
                    if (awgConfig != null) ServerChecks(vm)
                }
            }
        }
    }
}

/** The picked file's name without `.conf`, which is how the user already knows it. */
private fun confName(context: Context, uri: Uri): String? =
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
        ?.use { if (it.moveToFirst()) it.getString(0) else null }
        ?.removeSuffix(".conf")
        ?.takeIf { it.isNotBlank() }

/**
 * Every imported config, one selected. Only the config the tunnel is running
 * on has no delete button — see [TunnelViewModel.deleteAwgConf].
 */
@Composable
private fun SavedServers(vm: TunnelViewModel) {
    val confs by vm.awgConfs.collectAsState()
    val active by vm.awgConfName.collectAsState()
    val inUse by vm.awgConfInUse.collectAsState()
    var renaming by rememberSaveable { mutableStateOf<String?>(null) }
    var deleting by rememberSaveable { mutableStateOf<String?>(null) }
    var switched by rememberSaveable { mutableStateOf(false) }
    if (confs.isEmpty()) return

    renaming?.let { name -> RenameDialog(name, vm::renameAwgConf, onDone = { renaming = null }) }
    deleting?.let { name ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(stringResource(R.string.server_delete_title, name)) },
            text = { Text(stringResource(R.string.server_delete_text)) },
            confirmButton = {
                TextButton(onClick = { vm.deleteAwgConf(name); deleting = null }) {
                    Text(stringResource(R.string.server_delete_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }

    Column {
        SectionHeader(stringResource(R.string.server_saved_header))
        confs.keys.sorted().forEach { name ->
            ServerRow(
                name = name,
                endpoint = remember(confs[name]) {
                    confs[name]?.let { runCatching { parseAwgConf(it).endpoint }.getOrNull() }.orEmpty()
                },
                selected = name == active,
                deletable = name != inUse,
                onSelect = { vm.selectAwgConf(name); switched = true },
                onRename = { renaming = name },
                onDelete = { deleting = name },
            )
        }
        if (inUse != null) {
            Hint(stringResource(if (switched) R.string.next_connect else R.string.server_in_use_hint))
        }
    }
}

@Composable
private fun ServerRow(
    name: String,
    endpoint: String,
    selected: Boolean,
    deletable: Boolean,
    onSelect: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onSelect)
            .padding(start = 8.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null, modifier = Modifier.padding(12.dp))
        Column(Modifier.weight(1f)) {
            Text(name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                endpoint,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.outline,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(onClick = onRename) {
            Icon(Icons.Default.Edit, contentDescription = stringResource(R.string.server_rename, name),
                tint = MaterialTheme.colorScheme.outline)
        }
        if (deletable) {
            IconButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.server_delete, name),
                    tint = MaterialTheme.colorScheme.outline)
            }
        } else {
            Spacer(Modifier.size(48.dp))
        }
    }
}

@Composable
private fun RenameDialog(name: String, onRename: (String, String) -> Boolean, onDone: () -> Unit) {
    // Selected from the start, so typing replaces the old name.
    var text by rememberSaveable(stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(name, TextRange(0, name.length)))
    }
    var rejected by rememberSaveable { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    val submit = { if (onRename(name, text.text)) onDone() else rejected = true }
    AlertDialog(
        onDismissRequest = onDone,
        title = { Text(stringResource(R.string.server_rename_title)) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it; rejected = false },
                label = { Text(stringResource(R.string.server_rename_label)) },
                singleLine = true,
                modifier = Modifier.focusRequester(focus),
                // Lying down the keyboard covers the dialog's buttons.
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { submit() }),
                isError = rejected,
                supportingText = if (rejected) {
                    { Text(stringResource(R.string.server_rename_taken)) }
                } else null,
            )
        },
        confirmButton = {
            TextButton(onClick = submit) { Text(stringResource(R.string.server_rename_confirm)) }
        },
        dismissButton = {
            TextButton(onClick = onDone) { Text(stringResource(R.string.cancel)) }
        },
    )
}

@Composable
private fun ConfigCard(displayText: String, modifier: Modifier) {
    val lines = remember(displayText) {
        displayText.lines().map { redactConfLine(it) }
    }

    Card(modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = "Current config",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            )
            HorizontalDivider(thickness = 0.5.dp)
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(1.dp),
            ) {
                items(lines) { line ->
                    val hScroll = rememberScrollState()
                    Text(
                        text = line,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        lineHeight = 16.sp,
                        maxLines = 1,
                        softWrap = false,
                        color = when {
                            line.startsWith("[") -> MaterialTheme.colorScheme.primary
                            line.endsWith("[REDACTED]") -> MaterialTheme.colorScheme.outline
                            else -> MaterialTheme.colorScheme.onSurface
                        },
                        modifier = Modifier.horizontalScroll(hScroll),
                    )
                }
            }
        }
    }
}

/**
 * Ping and nodeinfo probe of the configured server. Both travel over the mesh,
 * so with no tunnel the section says so instead of showing dead buttons.
 */
@Composable
private fun ServerChecks(vm: TunnelViewModel) {
    val selfAddr by YggNetworkState.selfAddress.collectAsState()
    val serverKey by vm.serverKey.collectAsState()
    val pingMs by YggNetworkState.pingMs.collectAsState()
    val pinging by YggNetworkState.pinging.collectAsState()
    val probing by YggNetworkState.probing.collectAsState()
    val probeFound by YggNetworkState.probeFound.collectAsState()
    val online = selfAddr.isNotEmpty()

    Column {
        SectionHeader(stringResource(R.string.net_server_key))
        Text(
            text = serverKey.ifEmpty { stringResource(R.string.net_server_key_unknown) },
            fontFamily = if (serverKey.isEmpty()) FontFamily.Default else FontFamily.Monospace,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 8.dp),
        )
        Hint(stringResource(R.string.net_server_key_caption))

        SectionHeader(stringResource(R.string.net_checks_header))
        if (!online) {
            Hint(stringResource(R.string.net_checks_offline))
            return@Column
        }
        CheckRow(
            label = stringResource(R.string.net_ping),
            busyLabel = stringResource(R.string.net_pinging),
            busy = pinging,
            onClick = vm::pingAwgServer,
            result = pingMs?.let { ms ->
                if (ms < 0) stringResource(R.string.net_ping_timeout)
                else stringResource(R.string.net_ping_ms, ms.toInt())
            },
            resultColor = pingMs?.let { latencyColor(it) },
        )
        CheckRow(
            label = stringResource(R.string.net_probe),
            busyLabel = stringResource(R.string.net_probing),
            busy = probing,
            onClick = vm::probeServer,
            enabled = serverKey.isNotEmpty(),
            result = probeFound?.let {
                stringResource(if (it) R.string.net_probe_found else R.string.net_probe_missing)
            },
            resultColor = probeFound?.let {
                if (it) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
            },
        )
        Hint(stringResource(R.string.net_probe_caption))
    }
}

@Composable
private fun CheckRow(
    label: String,
    busyLabel: String,
    busy: Boolean,
    onClick: () -> Unit,
    result: String?,
    resultColor: Color?,
    enabled: Boolean = true,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        OutlinedButton(onClick = onClick, enabled = enabled && !busy) {
            if (busy) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
                Text(busyLabel)
            } else {
                Text(label)
            }
        }
        if (result != null && resultColor != null) {
            Text(result, color = resultColor, style = MaterialTheme.typography.bodyLarge)
        }
    }
}
