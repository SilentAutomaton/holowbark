package net.holowbark.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import net.holowbark.AppLogger
import net.holowbark.ui.MonospaceLine
import net.holowbark.ui.copyToClipboard

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogsScreen(onBack: () -> Unit) {
    val lines by AppLogger.lines.collectAsState()
    val listState = rememberLazyListState()
    val ctx = LocalContext.current

    // Auto-scroll to bottom when new lines arrive (instant to avoid measure-pass crash)
    LaunchedEffect(lines.size) {
        if (lines.isNotEmpty()) listState.scrollToItem(lines.size - 1)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Logs") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = {
                        ctx.copyToClipboard("Holowbark logs", lines.joinToString("\n") {
                            "${it.time} ${it.level}/${it.tag}: ${it.msg}"
                        })
                    }) {
                        Icon(Icons.Default.ContentCopy, contentDescription = "Copy logs")
                    }
                    IconButton(onClick = { AppLogger.clear() }) {
                        Icon(Icons.Default.Clear, contentDescription = "Clear logs")
                    }
                }
            )
        }
    ) { padding ->
        if (lines.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp)) {
                Text("No log output yet.\nConnect the VPN to see activity.",
                    color = MaterialTheme.colorScheme.outline)
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(1.dp),
            ) {
                items(lines.size) { idx ->
                    LogLine(lines[idx])
                }
            }
        }
    }
}

@Composable
private fun LogLine(line: AppLogger.Line) {
    MonospaceLine(
        text = "${line.time} ${line.level}/${line.tag}: ${line.msg}",
        color = when (line.level) {
            AppLogger.Level.E -> Color(0xFFFF6B6B)
            AppLogger.Level.W -> Color(0xFFFFD93D)
            AppLogger.Level.I -> Color(0xFFFFFFFF)
            AppLogger.Level.D -> Color(0xFFAAAAAA)
            AppLogger.Level.V -> Color(0xFF666666)
        },
    )
}
