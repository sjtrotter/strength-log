package io.github.sjtrotter.strengthlog.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver

/** Container roles are their accent sunk into the card surface — dark, not pastel. */
private fun containerOf(accent: Color) = accent.copy(alpha = 0.25f).compositeOver(Surface)

/**
 * Dark-only color scheme (spec §8.5). Day A's terracotta stands in for M3's
 * generic `primary` role — the day accents themselves are looked up per-day
 * via [dayAccent], not through the color scheme. Every role a stock M3
 * component might read (tonal buttons, chips, switches, snackbars) is
 * overridden so nothing ever falls back to baseline Material lavender.
 */
private val AppColorScheme = darkColorScheme(
    background = Background,
    onBackground = TextPrimary,
    surface = Surface,
    onSurface = TextPrimary,
    surfaceVariant = Surface,
    onSurfaceVariant = TextSecondary,
    // surfaceTint = surface disables M3's tonal-elevation tinting — surfaces
    // stay flat near-black at any elevation.
    surfaceTint = Surface,
    // The container-surface ramp reuses the spec's surfaces (design-pass:
    // Surface2/Surface3 are the "raised control" ramp — steppers, ticks) instead
    // of M3's violet-cast dark neutrals.
    surfaceDim = Background,
    surfaceBright = Border,
    surfaceContainerLowest = Background,
    surfaceContainerLow = Surface,
    surfaceContainer = Surface,
    surfaceContainerHigh = Surface2,
    surfaceContainerHighest = Surface3,
    primary = dayAccent(0),
    onPrimary = onDayAccent(0),
    primaryContainer = containerOf(dayAccent(0)),
    onPrimaryContainer = TextPrimary,
    inversePrimary = dayAccent(0),
    secondary = TextSecondary,
    onSecondary = Background,
    secondaryContainer = containerOf(TextSecondary),
    onSecondaryContainer = TextPrimary,
    tertiary = dayAccent(3),
    onTertiary = onDayAccent(3),
    tertiaryContainer = containerOf(dayAccent(3)),
    onTertiaryContainer = TextPrimary,
    error = Error,
    onError = TextPrimary,
    errorContainer = containerOf(Error),
    onErrorContainer = TextPrimary,
    outline = Border,
    outlineVariant = Border,
    inverseSurface = TextPrimary,
    inverseOnSurface = Background,
)

/**
 * App-wide theme wrapper. Applies the near-black palette and condensed
 * type scale everywhere so no screen falls back to default Material
 * (CLAUDE.md rule 5) — there is no light variant to switch to in v1.
 */
@Composable
fun AppTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = AppColorScheme,
        typography = AppTypography,
        content = content,
    )
}
