package net.holowbark.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * How wide a column of rows and prose is allowed to get. A settings row stretched
 * across a tablet is a row nobody can tie back to its own switch, and a caption
 * that long is one nobody reads to the end.
 */
val CONTENT_MAX_WIDTH = 640.dp

/**
 * For the scrolling container of a screen that is a list; centre it in a Box.
 * The cap goes first: fillMaxWidth would otherwise fix the width at the parent's
 * and leave the alignment nothing to centre.
 */
fun Modifier.contentWidth(): Modifier = widthIn(max = CONTENT_MAX_WIDTH).fillMaxWidth()

/** Shared by every screen that shows a round-trip time, so the bands agree. */
@Composable
@ReadOnlyComposable
fun latencyColor(ms: Number): Color = when {
    ms.toDouble() < 0   -> MaterialTheme.colorScheme.error
    ms.toDouble() < 100 -> MaterialTheme.colorScheme.primary
    ms.toDouble() < 300 -> MaterialTheme.colorScheme.secondary
    else                -> MaterialTheme.colorScheme.error
}

/**
 * Yggdrasil reports a dial failure as a raw JSON blob. Reduce it to the two fields
 * a person can act on — what was attempted and what went wrong — or null when there
 * is nothing worth showing.
 */
fun peerErrorSummary(lastError: String): String? =
    lastError.takeIf { it.isNotBlank() && it != "null" }?.let { raw ->
        if (!raw.startsWith("{")) return@let raw
        runCatching {
            val op = Regex("\"Op\":\"([^\"]+)\"").find(raw)?.groupValues?.get(1)
            val err = Regex("\"Err\":\"([^\"]+)\"").find(raw)?.groupValues?.get(1)
                ?: Regex("\"Err\":\\{[^}]*\"Err\":\"([^\"]+)\"").find(raw)?.groupValues?.get(1)
            if (op != null && err != null) "$op: $err" else raw
        }.getOrDefault(raw)
    }

fun Context.copyToClipboard(label: String, text: String) {
    val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
}

/** A single unwrapped monospace line the user can scroll sideways to read in full. */
@Composable
fun MonospaceLine(
    text: String,
    color: Color = MaterialTheme.colorScheme.onSurface,
    fontSize: TextUnit = 11.sp,
    modifier: Modifier = Modifier,
) {
    androidx.compose.material3.Text(
        text = text,
        color = color,
        fontFamily = FontFamily.Monospace,
        fontSize = fontSize,
        lineHeight = fontSize * 1.3f,
        maxLines = 1,
        softWrap = false,
        modifier = modifier.horizontalScroll(rememberScrollState()),
    )
}
