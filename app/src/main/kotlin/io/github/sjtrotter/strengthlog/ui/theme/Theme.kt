package io.github.sjtrotter.strengthlog.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

/**
 * Dark-only color scheme (spec §8.5). Day A's terracotta stands in for M3's
 * generic `primary` role — the day accents themselves are looked up per-day
 * via [dayAccent], not through the color scheme.
 */
private val AppColorScheme = darkColorScheme(
    background = Background,
    onBackground = TextPrimary,
    surface = Surface,
    onSurface = TextPrimary,
    surfaceVariant = Surface,
    onSurfaceVariant = TextSecondary,
    primary = dayAccent(0),
    onPrimary = TextPrimary,
    error = Error,
    onError = TextPrimary,
    outline = Border,
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
