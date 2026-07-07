# Restyle: design-pass tokens + day screen (tier:sonnet, AFTER PR #41 merges)

The design pass landed: `docs/design-handoff/` (vendored 2026-07-07). Its
README is the contract; **where the README and the reference HTML disagree,
the HTML wins** (the designer's stated fidelity rule). This brief adds only
the codebase-mapping decisions the handoff leaves open. Behaviors are
untouched — this is a visual layer change on top of the merged day screen.

## Sequencing

Hard dependency: PR #41 (day screen) must be merged first — this rewrites the
same files. One PR, one implementation agent, normal adversarial loop.

## Mapping decisions (Fable)

1. **Fonts**: copy the three Barlow Condensed TTFs to `app/src/main/res/font/`
   (lowercase snake_case names), move the OFL alongside the existing license
   layout (`app/src/main/font-licenses/barlow-condensed/OFL.txt`). **Delete
   Oswald** (`oswald_variable.ttf` + its license dir + `Condensed` references)
   — the handoff offers keeping it as fallback; we don't keep dead assets.
   The #23 licenses-surface ledger item now covers Barlow instead.
2. **Type roles**: keep the five existing `Typography` slot mappings
   (displayLarge 34 GOAL, titleLarge 22, labelLarge 14, bodyLarge, bodySmall)
   on the new face, and set M3's `labelSmall` slot to the 11sp caps role.
   `displayXl` (40) and `display2`/`display3` (28/22 stepper values) do NOT
   go into Typography slots — they're component-intrinsic sizes; define them
   as named `TextStyle` vals in `Type.kt` (e.g. `DisplayXl`, `StepperValue`,
   `StepperRepsValue`) so nothing squats on unrelated M3 slots. Tabular
   numerals via `FontFeatureSettings("tnum")` on the display styles.
3. **Color**: new constants per `tokens/colors.css` (Surface2/Surface3/
   BorderStrong/TextFaint); Error → `0xFFC2334D`; `onError` stays TextPrimary
   (verified 4.84:1). ADD an error/on-error pairing assertion to
   `DayAccentTest`'s contrast math (the day-accent pins themselves are
   unchanged). `accentSoft(dayIndex)`/`accentBorder(dayIndex)` live next to
   `dayAccent` in Color.kt (SSOT; note B/D use 14%/60% vs A/C 12%/55% — from
   colors.css, the darker accents need slightly more presence).
4. **Theme**: update `darkColorScheme` roles that reference changed constants
   (error, surfaces if used); card radius 10→12 in AppCard; control radii per
   the handoff's spacing tokens.
5. **Components**: `SetRow` is a NEW composable in `ui/components` (it is the
   most-repeated element; the day screen consumes it, the watch does not —
   don't generalize it). Stepper capsule + CheckmarkToggle pop restyle the
   existing components in place (public signatures stay source-compatible;
   the 48dp hit-box tests must keep passing). The ticked-row 55% fade and
   TOP-row bleed are SetRow concerns, not Stepper concerns.
6. **Motion**: all transient — `animate*AsState`/`Animatable`; nothing
   persisted (consistent with A6). The ~420ms auto-collapse delay is a UI
   animation delay on the collapse animation trigger, NOT a change to
   `DayScreenBuilder`'s collapse decision (builder output stays pure and
   already-tested). Cascade flash: keyed off TOP-weight-change events in the
   UI layer (e.g. snapshot of previous values in the composable), never off
   new ViewModel state. Honor `prefers-reduced-motion` equivalent: wrap
   durations so animations collapse to instant when
   `LocalAccessibilityManager`-style disable is signaled — if that's more
   than trivial, skip and note it (don't invent machinery).
7. **Handoff nits** (known, don't "fix" the reference files): typography.css
   has one stale comment saying "Oswald numerals" — the face is Barlow
   Condensed everywhere that matters; reference files are vendored read-only.

## Verification

`./gradlew test` (contrast tests updated deliberately, 48dp tests green),
`:app:assembleDebug`, `:app:lintDebug`. Compose previews updated for every
restyled component + SetRow states (plain/TOP/ticked/superset-subrow).
No device use — visual QA is the user comparing against
`day_screen_reference.html` (tell them exactly that in the PR body).
