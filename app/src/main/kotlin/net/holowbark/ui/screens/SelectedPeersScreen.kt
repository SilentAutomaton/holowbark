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
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it; error = null },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Add a peer") },
                    placeholder = { Text("tls://example.org:443") },
                    singleLine = true,
                    isError = error != null,
                    supportingText = {
                        Text(error ?: "Any Yggdrasil peer — your own node, or one on your LAN.")
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
                OutlinedButton(onClick = onBrowsePublic, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.Language, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Browse public peers")
                }
            }
            if (selected.isEmpty()) {
                item {
                    Text(
                        "No peers yet. Without at least one, the overlay cannot connect.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.padding(top = 16.dp),
                    )
                }
            } else {
                items(selected.toList().sorted(), key = { it }) { uri ->
                    SelectedPeerRow(uri = uri, onRemove = { vm.removePeer(uri) })
                }
            }
        }
    }
}

@Composable
private fun SelectedPeerRow(uri: String, onRemove: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = uri,
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onRemove) {
            Icon(
                Icons.Default.Close,
                contentDescription = "Remove peer",
                tint = MaterialTheme.colorScheme.outline,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}
