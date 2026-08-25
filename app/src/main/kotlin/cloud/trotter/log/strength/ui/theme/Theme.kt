package cloud.trotter.log.strength.ui.theme

import androidx.compose.foundation.Indication
import androidx.compose.foundation.LocalIndication
import androidx.compose.material.ripple.RippleAlpha
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RippleConfiguration
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import cloud.trotter.log.strength.domain.theme.ThemePreference

@Immutable
internal data class AppPalette(
    val isDark: Boolean,
    val textFaint: Color,
    val done: Color,
    val focusRing: Color,
)

internal val DarkAppPalette = AppPalette(true, DarkTextFaint, DarkDone, DarkFocusRing)
internal val LightAppPalette = AppPalette(false, LightTextFaint, LightDone, LightFocusRing)
internal val LocalAppPalette = staticCompositionLocalOf { DarkAppPalette }

/** Container roles are their accent sunk into the scheme's card surface. */
internal fun containerOf(accent: Color, surface: Color) = accent.copy(alpha = 0.25f).compositeOver(surface)

/**
 * Dark color scheme (spec §8.5). Day A's terracotta stands in for M3's
 * generic `primary` role — the day accents themselves are looked up per-day
 * via [dayAccent], not through the color scheme. All forty-eight roles are
 * given a value here — not just the ones today's screens read — so a stock M3
 * component introduced later cannot fall back to baseline Material lavender.
 * `ThemeCompletenessTest` holds that line role by role.
 */
internal val DarkAppColorScheme = darkColorScheme(
    background = DarkBackground,
    onBackground = DarkTextPrimary,
    surface = DarkSurface,
    onSurface = DarkTextPrimary,
    surfaceVariant = DarkSurface,
    onSurfaceVariant = DarkTextSecondary,
    // surfaceTint = surface disables M3's tonal-elevation tinting — surfaces
    // stay flat near-black at any elevation.
    surfaceTint = DarkSurface,
    // The container-surface ramp reuses the spec's surfaces (design-pass:
    // Surface2/Surface3 are the "raised control" ramp — steppers, ticks) instead
    // of M3's violet-cast dark neutrals.
    surfaceDim = DarkBackground,
    surfaceBright = DarkBorder,
    surfaceContainerLowest = DarkBackground,
    surfaceContainerLow = DarkSurface,
    surfaceContainer = DarkSurface,
    surfaceContainerHigh = DarkSurface2,
    surfaceContainerHighest = DarkSurface3,
    primary = dayAccent(0),
    onPrimary = onDayAccent(0, true),
    primaryContainer = containerOf(dayAccent(0), DarkSurface),
    onPrimaryContainer = DarkTextPrimary,
    inversePrimary = dayAccent(0),
    secondary = DarkTextSecondary,
    onSecondary = DarkBackground,
    secondaryContainer = containerOf(DarkTextSecondary, DarkSurface),
    onSecondaryContainer = DarkTextPrimary,
    tertiary = dayAccent(3),
    onTertiary = onDayAccent(3, true),
    tertiaryContainer = containerOf(dayAccent(3), DarkSurface),
    onTertiaryContainer = DarkTextPrimary,
    error = DarkError,
    onError = DarkTextPrimary,
    errorContainer = containerOf(DarkError, DarkSurface),
    onErrorContainer = DarkTextPrimary,
    outline = DarkBorder,
    outlineVariant = DarkBorderStrong,
    inverseSurface = DarkTextPrimary,
    inverseOnSurface = DarkBackground,
    // Sheets and dialogs dim toward the app's own black, not the pure #000 the
    // baseline hands out — consumers re-alpha this (the modal sheet takes it at
    // 32%), so the role itself stays opaque.
    scrim = DarkBackground,
    // M3's "fixed" roles are the ones meant to survive a light/dark swap. This
    // dark expression keeps Fixed at identity strength with its contrast-pinned
    // on-color (DayAccentTest guards the
    // ratio), FixedDim is that same accent sunk into the card surface, and its
    // text is the one this scheme already uses on accent containers.
    primaryFixed = dayAccent(0),
    primaryFixedDim = containerOf(dayAccent(0), DarkSurface),
    onPrimaryFixed = onDayAccent(0, true),
    onPrimaryFixedVariant = DarkTextPrimary,
    secondaryFixed = DarkTextSecondary,
    secondaryFixedDim = containerOf(DarkTextSecondary, DarkSurface),
    onSecondaryFixed = DarkBackground,
    onSecondaryFixedVariant = DarkTextPrimary,
    tertiaryFixed = dayAccent(3),
    tertiaryFixedDim = containerOf(dayAccent(3), DarkSurface),
    onTertiaryFixed = onDayAccent(3, true),
    onTertiaryFixedVariant = DarkTextPrimary,
)

internal val LightAppColorScheme = lightColorScheme(
    background = LightBackground,
    onBackground = LightTextPrimary,
    surface = LightSurface,
    onSurface = LightTextPrimary,
    surfaceVariant = LightSurface,
    onSurfaceVariant = LightTextSecondary,
    surfaceTint = LightSurface,
    surfaceDim = LightBackground,
    surfaceBright = LightSurface,
    surfaceContainerLowest = LightSurface,
    surfaceContainerLow = LightSurface,
    surfaceContainer = LightSurface,
    surfaceContainerHigh = LightSurface2,
    surfaceContainerHighest = LightSurface3,
    primary = dayAccent(0),
    onPrimary = onDayAccent(0, false),
    primaryContainer = containerOf(dayAccent(0), LightSurface),
    onPrimaryContainer = LightTextPrimary,
    inversePrimary = dayAccent(0),
    secondary = LightTextSecondary,
    onSecondary = LightSurface,
    secondaryContainer = containerOf(LightTextSecondary, LightSurface),
    onSecondaryContainer = LightTextPrimary,
    tertiary = dayAccent(3),
    onTertiary = onDayAccent(3, false),
    tertiaryContainer = containerOf(dayAccent(3), LightSurface),
    onTertiaryContainer = LightTextPrimary,
    error = LightError,
    onError = LightSurface,
    errorContainer = containerOf(LightError, LightSurface),
    onErrorContainer = LightTextPrimary,
    outline = LightBorder,
    outlineVariant = LightBorderStrong,
    inverseSurface = LightTextPrimary,
    inverseOnSurface = LightSurface,
    scrim = DarkBackground,
    primaryFixed = dayAccent(0),
    primaryFixedDim = containerOf(dayAccent(0), LightSurface),
    onPrimaryFixed = onDayAccent(0, false),
    onPrimaryFixedVariant = LightTextPrimary,
    secondaryFixed = LightTextSecondary,
    secondaryFixedDim = containerOf(LightTextSecondary, LightSurface),
    onSecondaryFixed = LightSurface,
    onSecondaryFixedVariant = LightTextPrimary,
    tertiaryFixed = dayAccent(3),
    tertiaryFixedDim = containerOf(dayAccent(3), LightSurface),
    onTertiaryFixed = onDayAccent(3, false),
    onTertiaryFixedVariant = LightTextPrimary,
)

// Ripple drawing replaces the input color's alpha with the configured alpha for
// each interaction state. AppRippleConfiguration is therefore the single source
// of the authored pressed, 4% hover/drag, and 0% focus values for both paths: the
// wrapper ripple() in AppIndication and ripples created by stock M3 components.
// Removing that configuration would restore Material's defaults, including a
// 10% pressed alpha, so the configuration is load-bearing.
internal const val DarkAppRipplePressedAlpha = 0.10f
internal const val LightAppRipplePressedAlpha = 0.08f
internal const val AppRippleBounded = true
internal fun appIndication(color: Color): Indication = ripple(bounded = AppRippleBounded, color = color)
internal fun appRippleConfiguration(color: Color, pressedAlpha: Float) = RippleConfiguration(
    color = color,
    rippleAlpha = RippleAlpha(
        pressedAlpha = pressedAlpha,
        focusedAlpha = 0f,
        hoveredAlpha = 0.04f,
        draggedAlpha = 0.04f,
    ),
)

/**
 * App-wide theme wrapper. Resolves the stored/system appearance, then applies
 * the complete authored palette, condensed type scale and corner scale so no
 * screen falls back to baseline Material.
 */
@Composable
fun AppTheme(
    preference: ThemePreference = ThemePreference.SYSTEM,
    systemInDarkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val isDark = preference == ThemePreference.DARK ||
        (preference == ThemePreference.SYSTEM && systemInDarkTheme)
    val scheme = if (isDark) DarkAppColorScheme else LightAppColorScheme
    val palette = if (isDark) DarkAppPalette else LightAppPalette
    // White-based feedback disappears on paper; use scheme content while
    // retaining the authored theme-specific pressed and 4/4/0 percent alpha structure.
    val rippleColor = scheme.onSurfaceVariant
    val indication = appIndication(rippleColor)
    val pressedAlpha = if (isDark) DarkAppRipplePressedAlpha else LightAppRipplePressedAlpha
    val rippleConfiguration = appRippleConfiguration(rippleColor, pressedAlpha)
    MaterialTheme(
        colorScheme = scheme,
        typography = AppTypography,
        shapes = AppShapes,
    ) {
        CompositionLocalProvider(
            LocalAppPalette provides palette,
            LocalIndication provides indication,
            LocalRippleConfiguration provides rippleConfiguration,
            content = content,
        )
    }
}
