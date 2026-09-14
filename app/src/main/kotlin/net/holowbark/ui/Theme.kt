package net.holowbark.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * Bark and lichen. Yggdrasil is the world-tree, so the ground is warm near-black
 * rather than the usual neutral grey, and the two tunnel layers get colours of
 * their own: lichen for the overlay, resin for the tunnel running through it. Those
 * two are load-bearing — the connect ring reads as a stack because of them.
 */
val Bark = Color(0xFF14100E)
val Heartwood = Color(0xFF241C18)
val Lichen = Color(0xFFA8BFA0)
val Resin = Color(0xFFE0A24A)
// Light enough to stay readable on the warm surfaces as well as on Bark: at
// 0xFF8C8279 secondary text measured 4.45:1 against Heartwood, below AA.
val Ash = Color(0xFF9A8F85)
val Brick = Color(0xFFC2604E)

private val HolowbarkColors = darkColorScheme(
    primary = Lichen,            // the Yggdrasil layer
    onPrimary = Bark,
    secondary = Resin,           // the AWG layer
    onSecondary = Bark,
    tertiary = Resin,
    background = Bark,
    onBackground = Color(0xFFEDE6DF),
    surface = Bark,
    onSurface = Color(0xFFEDE6DF),
    surfaceVariant = Heartwood,
    onSurfaceVariant = Ash,
    surfaceContainer = Heartwood,
    surfaceContainerHigh = Color(0xFF2E2420),
    outline = Ash,
    error = Brick,
    onError = Bark,
)

@Composable
fun HolowbarkTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = HolowbarkColors, content = content)
}
