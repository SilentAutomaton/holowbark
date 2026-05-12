package net.yggawg.mobile.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.yggawg.mobile.AppLogger

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogsScreen() {
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
                actions = {
                    IconButton(onClick = {
                        val text = lines.joinToString("\n") { l ->
                            "${l.time} ${l.level}/${l.tag}: ${l.msg}"
                        }
                        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        cm.setPrimaryClip(ClipData.newPlainText("Holowbark logs", text))
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
    val color = when (line.level) {
        AppLogger.Level.E -> Color(0xFFFF6B6B)
        AppLogger.Level.W -> Color(0xFFFFD93D)
        AppLogger.Level.I -> Color(0xFFFFFFFF)
        AppLogger.Level.D -> Color(0xFFAAAAAA)
        AppLogger.Level.V -> Color(0xFF666666)
    }
    val hScroll = rememberScrollState()
    Text(
        text = "${line.time} ${line.level}/${line.tag}: ${line.msg}",
        color = color,
        fontFamily = FontFamily.Monospace,
        fontSize = 11.sp,
        lineHeight = 14.sp,
        maxLines = 1,
        softWrap = false,
        modifier = Modifier.horizontalScroll(hScroll),
    )
}
