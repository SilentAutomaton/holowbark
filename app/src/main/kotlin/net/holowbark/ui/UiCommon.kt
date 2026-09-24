package net.holowbark.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
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
    Text(
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

@Composable
fun SectionHeader(title: String, action: @Composable (() -> Unit)? = null) {
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
fun Hint(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.outline,
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
    )
}

/**
 * One control for the whole row, so a screen reader names the switch by its
 * title instead of announcing a bare switch after the text.
 */
@Composable
fun SwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onToggle: () -> Unit,
    enabled: Boolean = true,
    icon: ImageVector? = null,
) {
    Row(
        modifier = Modifier.fillMaxWidth()
            .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = { onToggle() })
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.outline)
            Spacer(Modifier.width(20.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}
