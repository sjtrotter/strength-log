package cloud.trotter.log.strength.ui.theme

import androidx.compose.foundation.Indication
import androidx.compose.foundation.LocalIndication
import androidx.compose.material.ripple.RippleAlpha
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RippleConfiguration
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver

/** Container roles are their accent sunk into the card surface — dark, not pastel. */
internal fun containerOf(accent: Color) = accent.copy(alpha = 0.25f).compositeOver(Surface)

/**
 * Dark-only color scheme (spec §8.5). Day A's terracotta stands in for M3's
 * generic `primary` role — the day accents themselves are looked up per-day
 * via [dayAccent], not through the color scheme. All forty-eight roles are
 * given a value here — not just the ones today's screens read — so a stock M3
 * component introduced later cannot fall back to baseline Material lavender.
 * `ThemeCompletenessTest` holds that line role by role.
 */
internal val AppColorScheme = darkColorScheme(
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
    // Sheets and dialogs dim toward the app's own black, not the pure #000 the
    // baseline hands out — consumers re-alpha this (the modal sheet takes it at
    // 32%), so the role itself stays opaque.
    scrim = Background,
    // M3's "fixed" roles are the ones meant to survive a light/dark swap. This
    // app is dark-only, so nothing swaps: Fixed is the accent at identity
    // strength with its contrast-pinned on-color (DayAccentTest guards the
    // ratio), FixedDim is that same accent sunk into the card surface, and its
    // text is the one this scheme already uses on accent containers.
    primaryFixed = dayAccent(0),
    primaryFixedDim = containerOf(dayAccent(0)),
    onPrimaryFixed = onDayAccent(0),
    onPrimaryFixedVariant = TextPrimary,
    secondaryFixed = TextSecondary,
    secondaryFixedDim = containerOf(TextSecondary),
    onSecondaryFixed = Background,
    onSecondaryFixedVariant = TextPrimary,
    tertiaryFixed = dayAccent(3),
    tertiaryFixedDim = containerOf(dayAccent(3)),
    onTertiaryFixed = onDayAccent(3),
    onTertiaryFixedVariant = TextPrimary,
)

// Ripple drawing replaces the input color's alpha with the configured alpha for
// each interaction state. AppRippleConfiguration is therefore the single source
// of the 5% pressed, 4% hover/drag, and 0% focus values for both paths: the
// wrapper ripple() in AppIndication and ripples created by stock M3 components.
// Removing that configuration would restore Material's defaults, including a
// 10% pressed alpha, so the configuration is load-bearing.
internal val AppRippleColor = AppColorScheme.onSurfaceVariant
internal const val AppRipplePressedAlpha = 0.05f
internal const val AppRippleBounded = true
internal val AppIndication: Indication = ripple(
    bounded = AppRippleBounded,
    color = AppRippleColor,
)
internal val AppRippleConfiguration = RippleConfiguration(
    color = AppRippleColor,
    rippleAlpha = RippleAlpha(
        pressedAlpha = AppRipplePressedAlpha,
        focusedAlpha = 0f,
        hoveredAlpha = 0.04f,
        draggedAlpha = 0.04f,
    ),
)

/**
 * App-wide theme wrapper. Applies the near-black palette, the condensed type
 * scale and the app's corner scale everywhere so no screen falls back to
 * default Material (CLAUDE.md rule 5) — there is no light variant to switch
 * to in v1.
 */
@Composable
fun AppTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = AppColorScheme,
        typography = AppTypography,
        shapes = AppShapes,
    ) {
        CompositionLocalProvider(
            LocalIndication provides AppIndication,
            LocalRippleConfiguration provides AppRippleConfiguration,
            content = content,
        )
    }
}
