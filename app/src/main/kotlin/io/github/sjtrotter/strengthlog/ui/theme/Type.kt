package io.github.sjtrotter.strengthlog.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import io.github.sjtrotter.strengthlog.R

/**
 * Condensed display face for numerals and labels (spec §8.5). Design-pass
 * restyle (docs/design-handoff): Barlow Condensed, OFL-licensed, bundled as
 * three static-weight TTFs — see app/src/main/font-licenses/barlow-condensed/
 * OFL.txt — so the app never needs network access to render it. Replaces the
 * Oswald variable font; the handoff offered keeping Oswald as a fallback, but
 * this repo doesn't keep dead assets (docs/briefs/restyle-day-screen.md).
 */
val Condensed = FontFamily(
    Font(R.font.barlow_condensed_medium, weight = FontWeight.Medium),
    Font(R.font.barlow_condensed_semibold, weight = FontWeight.SemiBold),
    Font(R.font.barlow_condensed_bold, weight = FontWeight.Bold),
)

/** Body copy stays the platform default sans — spec only opinionates on display type. */
private val Sans = FontFamily.Default

/**
 * Component-intrinsic sizes that don't map to one of [AppTypography]'s five
 * M3 slots — the stepper's weight/reps numerals, the DONE button, the set-row
 * kind label, the day-tab letter, and the collapsed-summary line. Named here
 * (not inlined at call sites) so every size in the restyle has exactly one
 * home (CLAUDE.md rule 2, SSOT); the mapping brief calls out `DisplayXl`/
 * `StepperValue`/`StepperRepsValue` by name as the pattern to follow for any
 * size that doesn't fit a Typography slot — the rest below follow the same
 * reasoning for full HTML fidelity (docs/design-handoff/day_screen_reference.html).
 * All numerals tabular via `FontFeatureSettings("tnum")`.
 */

/** Wizard hero / live GOAL preview — token `--type-display-xl`; not consumed by this restyle. */
val DisplayXl = TextStyle(
    fontFamily = Condensed,
    fontWeight = FontWeight.Bold,
    fontSize = 40.sp,
    lineHeight = 44.sp,
    fontFeatureSettings = "tnum",
)

/** Weight stepper value — token `--type-display-2`, the hero of the set row. */
val StepperValue = TextStyle(
    fontFamily = Condensed,
    fontWeight = FontWeight.Bold,
    fontSize = 28.sp,
    lineHeight = 32.sp,
    fontFeatureSettings = "tnum",
)

/** Reps stepper value — token `--type-display-3`. */
val StepperRepsValue = TextStyle(
    fontFamily = Condensed,
    fontWeight = FontWeight.SemiBold,
    fontSize = 22.sp,
    lineHeight = 26.sp,
    fontFeatureSettings = "tnum",
)

/** Set-row kind label (R1…R4, TOP, B/O, plain numbers) — `.klab` in the reference. */
val SetKindLabel = TextStyle(
    fontFamily = Condensed,
    fontWeight = FontWeight.Medium,
    fontSize = 13.sp,
    lineHeight = 13.sp,
    letterSpacing = 0.5.sp,
)

/** Collapsed-card summary line (`90×10 · 90×10 · 90×9`) — `.summary` in the reference. */
val SummaryLine = TextStyle(
    fontFamily = Condensed,
    fontWeight = FontWeight.Medium,
    fontSize = 14.sp,
    lineHeight = 18.sp,
    letterSpacing = 0.5.sp,
    fontFeatureSettings = "tnum",
)

/** Day-tab letter (A/B/C/D, gear glyph) — `.tab` in the reference. The gear glyph uses 15sp. */
val TabLetter = TextStyle(
    fontFamily = Condensed,
    fontWeight = FontWeight.SemiBold,
    fontSize = 16.sp,
    lineHeight = 16.sp,
)

/** Stepper ± glyph — `.sb` in the reference. */
val StepperGlyph = TextStyle(
    fontFamily = Condensed,
    fontWeight = FontWeight.SemiBold,
    fontSize = 18.sp,
    lineHeight = 18.sp,
)

/** Set-done tick's ✓ glyph — `.tick` in the reference. */
val TickGlyph = TextStyle(
    fontFamily = Condensed,
    fontWeight = FontWeight.Bold,
    fontSize = 15.sp,
    lineHeight = 15.sp,
)

/** Remove-set × glyph — `.rm` in the reference (plain sans, not condensed). */
val RemoveGlyph = TextStyle(
    fontFamily = Sans,
    fontWeight = FontWeight.Normal,
    fontSize = 15.sp,
    lineHeight = 15.sp,
)

/** DONE button label — `.donebtn` in the reference. */
val DoneButtonLabel = TextStyle(
    fontFamily = Condensed,
    fontWeight = FontWeight.Bold,
    fontSize = 18.sp,
    lineHeight = 18.sp,
    letterSpacing = 1.5.sp,
)

/**
 * The type scale M3 screens (#9-14) actually need: a big condensed numeral
 * for the GOAL block, a condensed label for badges/chips/buttons, a condensed
 * title for day/screen headers, a condensed caps overline/badge label, and
 * two plain-sans body sizes. Every other [Typography] role is left at the M3
 * default and is unused by this app.
 */
val AppTypography = Typography(
    displayLarge = TextStyle(
        fontFamily = Condensed,
        fontWeight = FontWeight.Bold,
        fontSize = 34.sp,
        lineHeight = 38.sp,
        fontFeatureSettings = "tnum",
    ),
    titleLarge = TextStyle(
        fontFamily = Condensed,
        fontWeight = FontWeight.Bold,
        fontSize = 22.sp,
        lineHeight = 27.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = Condensed,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 18.sp,
        letterSpacing = 0.5.sp,
    ),
    labelSmall = TextStyle(
        fontFamily = Condensed,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 14.sp,
        letterSpacing = 1.2.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = Sans,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 22.sp,
    ),
    bodySmall = TextStyle(
        fontFamily = Sans,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 16.sp,
    ),
)
