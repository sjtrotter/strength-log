package cloud.trotter.log.strength.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import cloud.trotter.log.strength.R

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
internal val Sans = FontFamily.Default

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

/**
 * Card and sheet-page titles one step under [Typography.titleLarge] — the
 * cardio card on Day and the day-edit picker's page header. Was written at
 * every call site as `titleLarge.copy(fontSize = 19.sp)`; same style, one home.
 */
val CardTitle = TextStyle(
    fontFamily = Condensed,
    fontWeight = FontWeight.Bold,
    fontSize = 19.sp,
    lineHeight = 27.sp,
)

/**
 * The compact card title: a selection card's choice, a day-edit slot row. Also
 * an ex-`titleLarge.copy(fontSize = 17.sp)`. It keeps [CardTitle]'s bold weight
 * and loose leading, which is what separates it from `titleMedium` (semibold,
 * tight) at the same 17 sp.
 */
val CardTitleSmall = TextStyle(
    fontFamily = Condensed,
    fontWeight = FontWeight.Bold,
    fontSize = 17.sp,
    lineHeight = 27.sp,
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
 * All fifteen M3 roles, spelled out. The two-face rule decides the family:
 * display, headline, title and label are [Condensed] (the app's voice for
 * numerals, headings and chrome); body is [Sans], because paragraphs are the
 * one thing a condensed face reads badly at. Display roles carry `tnum` —
 * in this app they render numbers.
 *
 * Six of these roles — `displayMedium`/`displaySmall`, all three headlines and
 * `titleSmall` — no screen sets today. They are specified anyway: a stock M3
 * component reaches for them on its own, and an unspecified role is Roboto at
 * Material's sizes, which is exactly the sameness rule 5 forbids.
 *
 * The band above `titleLarge` is narrow on purpose. 22 sp is the largest type
 * any screen actually sets, and `displayLarge` tops out at 34 rather than
 * Material's 57, so the six roles between them step evenly by 2 sp instead of
 * pretending to a scale this app has no room for.
 */
val AppTypography = Typography(
    displayLarge = TextStyle(
        fontFamily = Condensed,
        fontWeight = FontWeight.Bold,
        fontSize = 34.sp,
        lineHeight = 38.sp,
        fontFeatureSettings = "tnum",
    ),
    displayMedium = TextStyle(
        fontFamily = Condensed,
        fontWeight = FontWeight.Bold,
        fontSize = 32.sp,
        lineHeight = 36.sp,
        fontFeatureSettings = "tnum",
    ),
    displaySmall = TextStyle(
        fontFamily = Condensed,
        fontWeight = FontWeight.Bold,
        fontSize = 30.sp,
        lineHeight = 34.sp,
        fontFeatureSettings = "tnum",
    ),
    headlineLarge = TextStyle(
        fontFamily = Condensed,
        fontWeight = FontWeight.Bold,
        fontSize = 28.sp,
        lineHeight = 32.sp,
    ),
    headlineMedium = TextStyle(
        fontFamily = Condensed,
        fontWeight = FontWeight.Bold,
        fontSize = 26.sp,
        lineHeight = 30.sp,
    ),
    headlineSmall = TextStyle(
        fontFamily = Condensed,
        fontWeight = FontWeight.Bold,
        fontSize = 24.sp,
        lineHeight = 28.sp,
    ),
    titleLarge = TextStyle(
        fontFamily = Condensed,
        fontWeight = FontWeight.Bold,
        fontSize = 22.sp,
        lineHeight = 27.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = Condensed,
        fontWeight = FontWeight.SemiBold,
        fontSize = 17.sp,
        lineHeight = 21.sp,
    ),
    titleSmall = TextStyle(
        fontFamily = Condensed,
        fontWeight = FontWeight.SemiBold,
        fontSize = 15.sp,
        lineHeight = 19.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = Condensed,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 18.sp,
        letterSpacing = 0.5.sp,
    ),
    labelMedium = TextStyle(
        fontFamily = Condensed,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 15.sp,
        letterSpacing = 1.0.sp,
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
    bodyMedium = TextStyle(
        fontFamily = Sans,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    ),
    bodySmall = TextStyle(
        fontFamily = Sans,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 16.sp,
    ),
)
