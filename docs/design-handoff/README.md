# Handoff: strength.log visual design pass (day screen + design tokens)

## Overview

A redesign of strength.log's visual layer: refreshed tokens (type, derived colors, spacing, radii, motion), a redesigned **set row** (single line), the **exercise-card state family** (expanded / superset / collapsed / done), the **day header** (tab strip + override), the cardio finisher, the DONE moment, and footer. Behaviors are unchanged — this restyles what `STRENGTH_TRACKER_SPEC.md` and `docs/PLAN.md` already pin.

## About the design files

The files in this bundle are **design references created in HTML** — prototypes showing intended look and behavior, not production code. The task is to **recreate these designs in the existing Kotlin/Jetpack Compose codebase** (`sjtrotter/strength-log`), extending the existing theme layer (`ui/theme/Color.kt`, `Type.kt`, `Theme.kt`) and components (`ui/components/`). 1 CSS px in the reference = 1 dp/sp.

- `day_screen_reference.html` — open in a browser. Interactive: tabs retint the screen per day accent, steppers work, editing the TOP set's weight runs the cascade flash, ticking all sets auto-collapses a card, superset rounds tick as one.
- `tokens/*.css` — the token values as CSS custom properties (single source for every number below).
- `components_reference.html` — component states on one page.

## Fidelity

**High-fidelity.** Colors, type sizes, spacing, radii, and motion durations are final and intended to be matched exactly. Where a value below disagrees with the HTML, the HTML wins.

## Design tokens

### Color (Color.kt)

Pinned (unchanged): `Background 0xFF0D0D0F`, `Surface 0xFF16161A`, `Border 0xFF2A2A30`, day accents `A 0xFFC1440E / B 0xFF2D5A3D / C 0xFFB8860B / D 0xFF1F4E5F`, on-accent pairings (white on A/B/D, Background on C), `Done 0xFF3E8E5A`.

New / changed:

| Token | Value | Use |
|---|---|---|
| `Surface2` | `0xFF1D1D22` | raised controls: stepper capsule, unchecked tick, gear tab |
| `Surface3` | `0xFF26262C` | pressed state of raised controls |
| `BorderStrong` | `0xFF3A3A42` | focus/emphasis outline |
| `TextFaint` | `0xFF6B6B73` | remove ×, ↳ marker, footer blurb |
| `Error` | **`0xFFC2334D`** | was M3-default `0xFFB3261E`; recolored so error never reads as Day-A terracotta. White text = 4.8:1. **Update the pinned contrast test deliberately.** |
| accent soft | accent @ 12% alpha over Surface (14% for dark B/D) | TOP-row fill, override pill, cascade flash |
| accent border | accent @ 55% mixed into Border | suggested-tab border |

### Type (Type.kt)

Display face: **Barlow Condensed** (OFL, bundled TTFs in `fonts/` — Medium 500, SemiBold 600, Bold 700). Replaces Oswald; keep `oswald_variable.ttf` around if a fallback is wanted. Body stays platform sans. All numerals tabular (`FontFeatureSettings "tnum"` where available).

| Role | Face/weight | Size/line | Tracking | Used for |
|---|---|---|---|---|
| displayXl | Cond 700 | 40/44 | — | wizard hero, live GOAL preview |
| displayLarge | Cond 700 | 34/38 | — | GOAL numeral |
| display2 (new) | Cond 700 | 28/32 | — | weight stepper value |
| display3 (new) | Cond 600 | 22/26 | — | reps value, collapsed summaries (summaries at 14/500) |
| titleLarge | Cond 700 | 22/27 | — | exercise names, day title |
| labelLarge | Cond 500 | 14/18 | +0.5 | tab letters (600), add-set, quiet buttons |
| labelSmall (new) | Cond 500 | 11/14 | +1.2, CAPS | badges, GOAL caption, overline, override pill |
| bodyLarge | Sans 400 | 16/22 | — | running copy |
| bodySmall | Sans 400 | 12/16 | — | helper lines, emphasis line, footer |

### Spacing, radii, sizing

4dp grid. Screen gutter 16 · card padding 16 · gap between cards 12 · card radius **12** (was 10) · tab radius 10 · control radius 8 · tick radius 6 · pills full. Touch ≥ 48dp stays test-enforced (visual sizes below sit on ≥48dp hit boxes via `minimumInteractiveComponentSize`). Set-row min height 52dp.

### Motion

- press feedback 120ms decelerate; DONE press scales to 0.985 with a light spring
- tick 200ms: fill + ✓ pops scale 0.7→1.0 spring
- collapse/expand 320ms decelerate (animate content height)
- **cascade**: on TOP-weight change, each affected row (R1→R4, then B/O) flashes accent-soft background fading to transparent over 650ms, staggered 45ms per row, value text flashes accent→text color. Numbers update immediately; the flash is the "the math just moved" signal.

## Screens / views

### Day screen (see `day_screen_reference.html`)

Vertical stack, 16dp gutters, 12dp gaps:

1. **Tab strip** — gear tab + one 40×40dp tab per day, 8dp gaps, radius 10, condensed 600 16sp letter.
   - gear: Surface2 fill, Border hairline, TextSecondary glyph
   - idle day: Surface fill, Border hairline, letter in that day's accent
   - selected: accent fill, on-accent letter, no border
   - suggested (not selected): 1.5dp accent outline at 2dp offset + 8dp accent dot top-right (2dp bg ring)
2. **Day header** — overline `DAY A` (labelSmall caps, accent) · day title (titleLarge) · emphasis line (bodySmall, TextSecondary). When viewed ≠ suggested: **override pill** — accent-soft fill, accent text, labelSmall caps, full-round, 4×10dp padding: `OVERRIDE · SUGGESTED NEXT: DAY B`.
3. **Exercise cards** — Surface fill, Border hairline, radius 12, padding 16.
   - Header row: title (+ badges under it) left; **GOAL block** right (labelSmall `GOAL` over displayLarge accent numeral; caption `/hand` when per-hand). Header tap toggles collapse.
   - Badges: `MAIN` accent fill / `+1 WARM-UP` hairline outline / `✓` done-green fill; labelSmall caps, 3×8dp, radius 4.
   - Mains helper line (bodySmall, TextSecondary): "Change the TOP set — ramp & back-off recalculate."
   - **Set row** (the element to get right): `[kind 30dp] [weight capsule] [reps capsule] [spacer] [tick 28dp] [× remove]`, 8dp gaps, min height 52dp.
     - kind label: Cond 500 13sp caps TextSecondary (R1–R4, B/O; plain numbers for regular/extra)
     - stepper capsule: Surface2 fill, Border hairline, radius 8, 40dp tall; − / + segments 32dp wide (press → Surface3); weight value display2 min-width 52dp, reps value display3 min-width 36dp
     - **TOP row**: accent-soft fill, 3dp accent left bar, radius 8, bleeds 10dp into card padding (row wider than siblings); kind label accent Cond 700
     - ticked row: steppers fade to 55% opacity
     - remove ×: TextFaint, 15sp, plain glyph
   - **Superset**: primary rows numbered; partner rides as sub-row — indented 30dp, `↳` TextFaint marker, dashed hairline top border, no tick/remove. One tick per round; both rows dim.
   - `+ ADD SET` — full-width, 1.5dp dashed Border, radius 8, labelLarge caps TextSecondary.
   - **Done/collapsed card**: 3dp done-green left edge, ✓ chip, body collapses to summary line (Cond 500 14sp tabular, TextSecondary): `90×10 · 90×10 · 90×9`; supersets `60×12(50×15) / 60×11(50×14)`; untouched `3 sets · GOAL 90`.
4. **Cardio finisher card** — collapsible, default closed: title 19sp + `EASY · ZONE 2` outline badge + chevron; body = one helper line. Copy reminds: lift first, then run.
5. **DONE button** — full-width 56dp, current day's accent fill, radius 12, Cond 700 18sp caps +1.5 tracking: `DONE — ADVANCE TO DAY B`.
6. **Footer** — philosophy blurb (bodySmall TextFaint) · `Clear today's checkmarks` quiet pill button (hairline, labelLarge) · `Keep screen on` switch (40×24dp track, accent when on).

### Interactions & behavior (all pre-existing, restyled)

- Weight steps 5 lb / 2.5 kg (2.5 / 1.25 under 20 lb) via existing `WeightStepper`; reps step 1, min 1; never below 1 set.
- TOP-weight edit cascades R1–R4 + B/O (pinned math: 235 → 130/165/190/210/175; 245 → 135/170/195/220/185) with the cascade flash above.
- All sets ticked → auto-collapse ~420ms later (green edge + chip + summary); header tap overrides either way; ticks reset per calendar date; DONE writes session, clears ticks, advances rotation; overriding day selection shows the pill, nothing else.

## State management

No new state. Existing screen state (selected day, suggested day, per-set values/ticks, per-card collapse override, keep-screen-on) covers everything; the only additions are transient animation states (cascade flash, tick pop) — `Animatable`/`animate*AsState`, never persisted.

## Assets

- `fonts/BarlowCondensed-{Medium,SemiBold,Bold}.ttf` (+ OFL license) → `app/src/main/res/font/`, referenced from `Type.kt` (drop-in replacement for the Oswald `FontFamily`).
- No icons/images: iconography stays text glyphs (`⚙ ✓ + − × ↳ ▼`) by design.

## Files

- `day_screen_reference.html` — interactive day screen (self-contained + `tokens/`)
- `components_reference.html` — component states
- `tokens/colors.css · typography.css · spacing.css` — token SSOT
- `fonts/` — Barlow Condensed TTFs + OFL

## Suggested implementation order

1. `Type.kt` (Barlow + new roles) and `Color.kt` (Surface2/3, TextFaint, Error recolor + test update)
2. `Stepper.kt` → capsule; `CheckmarkToggle.kt` → pop; new `SetRow`
3. `ExerciseCard` family (header/GOAL/badges/summary/collapse)
4. Day strip + header + override pill; cardio card; DONE; footer
5. Motion pass: cascade flash, collapse animation

Out of scope here (design direction carries over): wizard, setup, day-edit sheet, log, custom-exercise flow, watch.
