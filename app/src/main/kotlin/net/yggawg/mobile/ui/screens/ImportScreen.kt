package net.yggawg.mobile.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.yggawg.mobile.config.AwgConfigParseException
import net.yggawg.mobile.config.parseAwgConf
import net.yggawg.mobile.config.toConfString
import net.yggawg.mobile.ui.VpnStateViewModel
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
    vm: VpnStateViewModel,
    onImported: () -> Unit,
) {
    val context = LocalContext.current
    var errorText by remember { mutableStateOf<String?>(null) }
    val awgConfig     by vm.awgConfig.collectAsState()
    val rawConf       by vm.rawConfText.collectAsState()
    val protocolLabel = if (awgConfig?.isAwg == true) "AmneziaWG" else "WireGuard"

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
        vm.saveAwgConfig(config, text)
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
        topBar = { TopAppBar(title = { Text("$protocolLabel Config") }) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Button(
                onClick = { picker.launch(arrayOf("*/*")) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Default.FileOpen, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(if (awgConfig != null) "Replace .conf file" else "Open .conf file")
            }

            if (awgConfig == null) {
                Text(
                    "No config loaded. Import an AmneziaWG or WireGuard .conf file.\n" +
                    "AmneziaWG obfuscation params (Jc, Jmin, Jmax, S1, S2, H1–H4) are supported.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.outline,
                )
            } else {
                // Use raw imported text so no fields are lost in round-trip
                val displayText = rawConf ?: awgConfig!!.toConfString()
                val lines = remember(displayText) {
                    displayText.lines().map { redactConfLine(it) }
                }

                Card(modifier = Modifier.fillMaxWidth().weight(1f)) {
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
        }
    }
}
