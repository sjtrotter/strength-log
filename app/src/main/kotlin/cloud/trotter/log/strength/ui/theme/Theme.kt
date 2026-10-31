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
    val border: Color,
)

internal val DarkAppPalette = AppPalette(true, DarkTextFaint, DarkDone, DarkFocusRing, DarkBorder)
internal val LightAppPalette = AppPalette(false, LightTextFaint, LightDone, LightFocusRing, LightBorder)
internal val LocalAppPalette = staticCompositionLocalOf { DarkAppPalette }

/** Container roles are their accent sunk into the scheme's card surface. */
internal fun containerOf(accent: Color, surface: Color) = accent.copy(alpha = 0.25f).compositeOver(surface)

/**
 * Dark color scheme (spec §8.5). `primary` is neutral ink; day accents are
 * applied explicitly via [dayAccent] where a control acts for a day. A
 * scheme-wide day color shows Day A on every day and fails contrast as text.
 * All forty-eight roles are given a value here so a stock M3 component
 * introduced later cannot fall back to baseline Material lavender.
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
    primary = DarkTextPrimary,
    onPrimary = DarkBackground,
    primaryContainer = DarkSurface3,
    onPrimaryContainer = DarkTextPrimary,
    inversePrimary = LightTextPrimary,
    secondary = DarkTextSecondary,
    onSecondary = DarkBackground,
    secondaryContainer = containerOf(DarkTextSecondary, DarkSurface),
    onSecondaryContainer = DarkTextPrimary,
    tertiary = DarkTextSecondary,
    onTertiary = DarkBackground,
    tertiaryContainer = containerOf(DarkTextSecondary, DarkSurface),
    onTertiaryContainer = DarkTextPrimary,
    error = DarkError,
    onError = DarkBackground,
    errorContainer = DarkErrorContainer,
    onErrorContainer = DarkTextPrimary,
    outline = DarkOutline,
    outlineVariant = DarkBorderStrong,
    inverseSurface = DarkTextPrimary,
    inverseOnSurface = DarkBackground,
    // Sheets and dialogs dim toward the app's own black, not the pure #000 the
    // baseline hands out — consumers re-alpha this (the modal sheet takes it at
    // 32%), so the role itself stays opaque.
    scrim = DarkBackground,
    primaryFixed = DarkTextPrimary,
    primaryFixedDim = DarkSurface3,
    onPrimaryFixed = DarkBackground,
    onPrimaryFixedVariant = DarkTextPrimary,
    secondaryFixed = DarkTextSecondary,
    secondaryFixedDim = containerOf(DarkTextSecondary, DarkSurface),
    onSecondaryFixed = DarkBackground,
    onSecondaryFixedVariant = DarkTextPrimary,
    tertiaryFixed = DarkTextSecondary,
    tertiaryFixedDim = containerOf(DarkTextSecondary, DarkSurface),
    onTertiaryFixed = DarkBackground,
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
    primary = LightTextPrimary,
    onPrimary = LightSurface,
    primaryContainer = LightSurface3,
    onPrimaryContainer = LightTextPrimary,
    inversePrimary = DarkTextPrimary,
    secondary = LightTextSecondary,
    onSecondary = LightSurface,
    secondaryContainer = containerOf(LightTextSecondary, LightSurface),
    onSecondaryContainer = LightTextPrimary,
    tertiary = LightTextSecondary,
    onTertiary = LightSurface,
    tertiaryContainer = containerOf(LightTextSecondary, LightSurface),
    onTertiaryContainer = LightTextPrimary,
    error = LightError,
    onError = LightSurface,
    errorContainer = LightErrorContainer,
    onErrorContainer = LightOnErrorContainer,
    outline = LightOutline,
    outlineVariant = LightBorderStrong,
    inverseSurface = LightTextPrimary,
    inverseOnSurface = LightSurface,
    scrim = DarkBackground,
    primaryFixed = LightTextPrimary,
    primaryFixedDim = LightSurface3,
    onPrimaryFixed = LightSurface,
    onPrimaryFixedVariant = LightTextPrimary,
    secondaryFixed = LightTextSecondary,
    secondaryFixedDim = containerOf(LightTextSecondary, LightSurface),
    onSecondaryFixed = LightSurface,
    onSecondaryFixedVariant = LightTextPrimary,
    tertiaryFixed = LightTextSecondary,
    tertiaryFixedDim = containerOf(LightTextSecondary, LightSurface),
    onTertiaryFixed = LightSurface,
    onTertiaryFixedVariant = LightTextPrimary,
)

// Ripple drawing replaces the input color's alpha with the configured alpha for
// each interaction state. AppRippleConfiguration is therefore the single source
// of the authored pressed, 4% hover/drag, and M3's default 10% focus values for
// both paths: the wrapper ripple() in AppIndication and stock M3 ripples.
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
        focusedAlpha = 0.10f,
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
    // retaining the authored theme-specific pressed and 4/4/10 percent alpha structure.
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
