package io.github.sjtrotter.strengthlog.ui.theme

import androidx.compose.ui.graphics.Color

// Spec §8.5 — dark theme only in v1; there is deliberately no light palette.
val Background = Color(0xFF0D0D0F)
val Surface = Color(0xFF16161A)
val Border = Color(0xFF2A2A30)

val TextPrimary = Color(0xFFF2F2F0)
val TextSecondary = Color(0xFF9A9AA2)
val Error = Color(0xFFB3261E)

// The "completed" green for spec §8's ✓ semantics (CheckmarkToggle today,
// any future done-state tinting) — the one accent that isn't day-specific.
val Done = Color(0xFF3E8E5A)

// Per-day earth-tone accents, spec §8.5, in day order A-D. These four hexes
// are pinned by the spec — accessibility fixes go in DayOnAccents, not here.
private val DayAccents = listOf(
    Color(0xFFC1440E), // A
    Color(0xFF2D5A3D), // B
    Color(0xFFB8860B), // C
    Color(0xFF1F4E5F), // D
)

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
