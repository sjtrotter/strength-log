package io.github.sjtrotter.strengthlog.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import io.github.sjtrotter.strengthlog.domain.theme.DayAccentColors

// Spec §8.5 — dark theme only in v1; there is deliberately no light palette.
val Background = Color(0xFF0D0D0F)
val Surface = Color(0xFF16161A)
val Border = Color(0xFF2A2A30)

// Design-pass derived surfaces (docs/design-handoff/tokens/colors.css) — raised
// controls (stepper capsule, unchecked tick, gear tab) and their pressed state.
val Surface2 = Color(0xFF1D1D22)
val Surface3 = Color(0xFF26262C)

// Focus/emphasis outline (design-pass; part of the token set, kept for parity
// with colors.css even though today's restyle has no dedicated focus ring).
val BorderStrong = Color(0xFF3A3A42)

val TextPrimary = Color(0xFFF2F2F0)
val TextSecondary = Color(0xFF9A9AA2)

// Faint text: remove-set glyph, superset "↳" marker, footer blurb (design-pass).
val TextFaint = Color(0xFF6B6B73)

// Design-pass recolor: was M3-default 0xFFB3261E, which read too close to Day
// A's terracotta. Cooler crimson so an error never looks like a Day-A accent;
// white text stays >= 4.5:1 (measured 4.84:1 — see DayAccentTest).
val Error = Color(0xFFC2334D)

// The "completed" green for spec §8's ✓ semantics (CheckmarkToggle today,
// any future done-state tinting) — the one accent that isn't day-specific.
val Done = Color(0xFF3E8E5A)

// Per-day earth-tone accents, spec §8.5, in day order A-D. The hexes
// themselves are SSOT in DayAccentColors (:domain, shared with :wear);
// accessibility fixes go in DayOnAccents, not here.
private val DayAccents = List(4) { i -> Color(DayAccentColors.hex(i)) }

// Text drawn on top of each accent, chosen per accent so every pairing meets
// WCAG AA (>= 4.5:1). Measured: A/TextPrimary 4.57, B/TextPrimary 7.09,
// C/Background 5.97 (TextPrimary on goldenrod is only 2.90 — C needs dark
// text), D/TextPrimary 8.10. DayAccentTest pins these ratios.
private val DayOnAccents = listOf(
    TextPrimary, // A
    TextPrimary, // B
    Background,  // C
    TextPrimary, // D
)

/**
 * SSOT for day-accent color: 0-based day index (matches
 * `ProgramGenerator`'s A-Z day ids). Programs beyond 4 days cycle back to
 * A's accent — day index 4 (5th day) reads as A, 5 as B, and so on.
 */
fun dayAccent(dayIndex: Int): Color = DayAccents[Math.floorMod(dayIndex, DayAccents.size)]

/** Text color to use on [dayAccent] for the same index — same cycling rule. */
fun onDayAccent(dayIndex: Int): Color = DayOnAccents[Math.floorMod(dayIndex, DayOnAccents.size)]

// B/D (indices 1 and 3) are the darker accents; colors.css gives them slightly
// more soft-fill/border presence (14%/60%) than A/C (12%/55%) so they read at
// the same visual weight against the near-black surfaces.
private fun isDarkerAccent(dayIndex: Int): Boolean {
    val i = Math.floorMod(dayIndex, DayAccents.size)
    return i == 1 || i == 3
}

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
fun accentBorder(dayIndex: Int): Color {
    val fraction = if (isDarkerAccent(dayIndex)) 0.60f else 0.55f
    return lerp(Border, dayAccent(dayIndex), fraction)
}
