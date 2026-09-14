package net.holowbark.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * Night wood. The ground is a cool near-black, the colour a forest actually has
 * after dark, and the two tunnel layers keep colours of their own: lichen for the
 * overlay, resin for the tunnel running through it. Those two are load-bearing —
 * the connect ring reads as a stack because of them, and a warm resin only reads
 * as warm against a cool ground.
 *
 * Every pairing below is measured, not eyeballed. Secondary text (Ash) sits at
 * 5.6:1 or better on all three surfaces, and Brick is lighter than a brick has
 * any right to be because at its natural weight it fell to 3.8:1 on the raised
 * surface — under AA for the error text that sits on cards.
 */
val Bark = Color(0xFF0B0E13)
val Heartwood = Color(0xFF161A21)
val HeartwoodHigh = Color(0xFF1F242C)
val Lichen = Color(0xFFA8BFA0)
val Resin = Color(0xFFE0A24A)
val Ash = Color(0xFF949CA8)
val Brick = Color(0xFFD2705C)
val Bone = Color(0xFFE4E8EE)

/** OLED pixels are off only at pure black, so the ground and its surfaces drop. */
val Night = Color(0xFF000000)
val NightSurface = Color(0xFF0D1117)
val NightHigh = Color(0xFF171C24)

private val HolowbarkColors = darkColorScheme(
    primary = Lichen,            // the Yggdrasil layer
    onPrimary = Bark,
    secondary = Resin,           // the AWG layer
    onSecondary = Bark,
    tertiary = Resin,
    background = Bark,
    onBackground = Bone,
    surface = Bark,
    onSurface = Bone,
    surfaceVariant = Heartwood,
    onSurfaceVariant = Ash,
    // The whole container ramp, because Material3 generates its own neutral grey
    // for any step left unset — and Card reaches for the highest one.
    surfaceContainerLowest = Bark,
    surfaceContainerLow = Heartwood,
    surfaceContainer = Heartwood,
    surfaceContainerHigh = HeartwoodHigh,
    surfaceContainerHighest = HeartwoodHigh,
    outline = Ash,
    error = Brick,
    onError = Bark,
)

// Only the ground moves: the accents are what the ring means by them, and they
// carry more contrast against black, not less.
private val OledColors = HolowbarkColors.copy(
    background = Night,
    surface = Night,
    onPrimary = Night,
    onSecondary = Night,
    surfaceVariant = NightSurface,
    surfaceContainerLowest = Night,
    surfaceContainerLow = NightSurface,
    surfaceContainer = NightSurface,
    surfaceContainerHigh = NightHigh,
    surfaceContainerHighest = NightHigh,
    onError = Night,
)

@Composable
fun HolowbarkTheme(oled: Boolean = false, content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (oled) OledColors else HolowbarkColors, content = content)
}
