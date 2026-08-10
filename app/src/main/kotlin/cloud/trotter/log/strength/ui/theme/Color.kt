package cloud.trotter.log.strength.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import cloud.trotter.log.strength.domain.theme.DayAccentColors

internal val DarkBackground = Color(0xFF0D0D0F)
internal val DarkSurface = Color(0xFF16161A)
internal val DarkBorder = Color(0xFF2A2A30)

// Design-pass derived surfaces (docs/design-handoff/tokens/colors.css) — raised
// controls (stepper capsule, unchecked tick, gear tab) and their pressed state.
internal val DarkSurface2 = Color(0xFF1D1D22)
internal val DarkSurface3 = Color(0xFF26262C)

// Focus/emphasis outline (design-pass; part of the token set, kept for parity
// with colors.css even though today's restyle has no dedicated focus ring).
internal val DarkBorderStrong = Color(0xFF3A3A42)

internal val DarkTextPrimary = Color(0xFFF2F2F0)
internal val DarkTextSecondary = Color(0xFF9A9AA2)

/**
 * Keyboard/d-pad focus ring ([BorderStrong] is the design-pass token for this,
 * but at 1.7:1 against [Background] it cannot carry a focus indicator on its
 * own — [TextSecondary] measures ~7:1 and is already in the palette).
 */
internal val DarkFocusRing = DarkTextSecondary

// Faint text: remove-set glyph, superset "↳" marker, footer blurb (design-pass).
internal val DarkTextFaint = Color(0xFF6B6B73)

// Design-pass recolor: was M3-default 0xFFB3261E, which read too close to Day
// A's terracotta. Cooler crimson so an error never looks like a Day-A accent;
// white text stays >= 4.5:1 (measured 4.84:1 — see DayAccentTest).
internal val DarkError = Color(0xFFC2334D)

// The "completed" green for spec §8's ✓ semantics (CheckmarkToggle today,
// any future done-state tinting) — the one accent that isn't day-specific.
internal val DarkDone = Color(0xFF3E8E5A)

internal val LightBackground = Color(0xFFF1EFEA)
internal val LightSurface = Color(0xFFFAF9F6)
internal val LightSurface2 = Color(0xFFE9E6E0)
internal val LightSurface3 = Color(0xFFE1DDD5)
internal val LightBorder = Color(0xFFD8D4CC)
internal val LightBorderStrong = Color(0xFFC4BFB5)
internal val LightTextPrimary = Color(0xFF1B1B1E)
internal val LightTextSecondary = Color(0xFF5C5C64)
internal val LightTextFaint = Color(0xFF8B8B92)
internal val LightError = Color(0xFFC2334D)
internal val LightDone = Color(0xFF37774E)
internal val LightFocusRing = LightTextSecondary

// Screen code speaks in authored token names; their getters resolve through
// the installed scheme so the same call sites render in either expression.
val Background: Color @Composable get() = MaterialTheme.colorScheme.background
val Surface: Color @Composable get() = MaterialTheme.colorScheme.surface
val Surface2: Color @Composable get() = MaterialTheme.colorScheme.surfaceContainerHigh
val Surface3: Color @Composable get() = MaterialTheme.colorScheme.surfaceContainerHighest
val Border: Color @Composable get() = MaterialTheme.colorScheme.outline
val BorderStrong: Color @Composable get() = MaterialTheme.colorScheme.outlineVariant
val TextPrimary: Color @Composable get() = MaterialTheme.colorScheme.onBackground
val TextSecondary: Color @Composable get() = MaterialTheme.colorScheme.onSurfaceVariant
val TextFaint: Color @Composable get() = LocalAppPalette.current.textFaint
val Error: Color @Composable get() = MaterialTheme.colorScheme.error
val Done: Color @Composable get() = LocalAppPalette.current.done
val FocusRing: Color @Composable get() = LocalAppPalette.current.focusRing

// Per-day earth-tone accents (spec §8.5, extended to 7 in the wear-companion
// §8.5 amendment). Both the accent hexes AND their on-accent contrast colors
// are SSOT in DayAccentColors (:domain, shared with :wear) — read straight
// from there, with no truncated local copy that could drift out of sync with
// the watch. Cycling past the 7th day lives in the domain lookups.
//
// On-accent contrast is chosen per accent so every pairing meets WCAG AA
// (>= 4.5:1): only C (goldenrod) needs Background (dark) text; every other
// accent takes TextPrimary. DayAccentTest pins the ratios for all 7.

/**
 * SSOT for day-accent color: 0-based day index (matches `ProgramGenerator`'s
 * A-Z day ids). Programs beyond 7 days cycle back to A's accent — day index 7
 * reads as A, 8 as B, and so on (domain-side `floorMod`).
 */
fun dayAccent(dayIndex: Int): Color = Color(DayAccentColors.hex(dayIndex))

/** Text color to use on [dayAccent], resolved to this expression's ink/paper. */
@Composable
fun onDayAccent(dayIndex: Int): Color = onDayAccent(dayIndex, LocalAppPalette.current.isDark)

internal fun onDayAccent(dayIndex: Int, isDark: Boolean): Color {
    val needsDarkInk = Math.floorMod(dayIndex, DayAccentColors.count) == 2
    return when {
        isDark && needsDarkInk -> DarkBackground
        isDark -> DarkTextPrimary
        needsDarkInk -> LightTextPrimary
        else -> LightSurface
    }
}

// The low-luminance accents (B/D/E/G); colors.css gives them slightly more
// soft-fill/border presence (14%/60%) than the brighter A/C/F (12%/55%) so
// they read at the same visual weight against the near-black surfaces.
private val DarkerAccentIndices = setOf(1, 3, 4, 6)

private fun isDarkerAccent(dayIndex: Int): Boolean =
    Math.floorMod(dayIndex, DayAccentColors.count) in DarkerAccentIndices

/**
 * TOP-row fill / override pill / cascade flash: the day accent at low alpha,
 * meant to sit over [Surface] as a translucent tint (design tokens:
 * `--accent-soft` is `color-mix(accent, transparent)`, i.e. the accent itself
 * at reduced alpha — not a color mixed into the surface).
 */
fun accentSoft(dayIndex: Int): Color {
    val alpha = if (isDarkerAccent(dayIndex)) 0.14f else 0.12f
    return dayAccent(dayIndex).copy(alpha = alpha)
}

/**
 * Suggested-tab border: the day accent mixed into [Border] (design token
 * `--accent-border`, an opaque `color-mix(accent, border)` — unlike
 * [accentSoft] this is a solid blended color, not an alpha tint).
 */
@Composable
fun accentBorder(dayIndex: Int): Color {
    val fraction = if (isDarkerAccent(dayIndex)) 0.60f else 0.55f
    return lerp(Border, dayAccent(dayIndex), fraction)
}

/**
 * The day accent as a *foreground* color on [Background]: chart lines, markers
 * and the cascade scrim's new number (docs/briefs/journal.md §0). The raw
 * accents are identity fills, chosen for on-accent contrast, and six of the
 * seven sit between 2.1:1 and 3.8:1 against the near-black — only C, the
 * goldenrod, is legible unlifted, and the rest are unreadable as a hairline
 * mark.
 *
 * The lift itself is `DayAccentColors.brightHex`, same as the watch's: the phone
 * used to derive it here and the watch derived a different one of its own, so a
 * day read one way in the pocket and another on the wrist (#150). [DayAccentTest]
 * still pins the contrast floor from this side of the fence.
 */
fun accentBright(dayIndex: Int): Color = Color(DayAccentColors.brightHex(dayIndex))

fun accentDeep(dayIndex: Int): Color = Color(DayAccentColors.deepHex(dayIndex))

/** Accent foreground resolved for the current paper/near-black expression. */
@Composable
fun accentEmphasis(dayIndex: Int): Color =
    if (LocalAppPalette.current.isDark) accentBright(dayIndex) else accentDeep(dayIndex)
