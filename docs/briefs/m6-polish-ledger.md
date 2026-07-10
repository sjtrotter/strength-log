# M6 briefs + accumulated polish ledger — #21, #22, #23

M6 waits for the design pass (D8): #21/#22 would be redone if styled before
the direction lands.

## Ledger (debts recorded by reviews; fold into the issues below)

- **A7 long-press-to-repeat on steppers** — spec'd in PLAN A7, deliberately
  skipped in #40's Stepper ("not worth the state" — A7 overrules). → #21.
- **A7 content descriptions** on steppers/toggles for TalkBack; font-scale
  tolerance; predictive back. → #21.
- **Oswald OFL license is repo-only** (app/src/main/font-licenses/) — not
  packaged in the APK. Needs an in-app licenses surface (or assets/ + link)
  before release. → #23.
- **Manifest lint warnings** (MissingApplicationIcon, RedundantLabel) → #22.
- **Manual §8.2 behavioral gate** (PLAN verification gates) — a human pass
  over the day screen on a real device once M3 completes; agents cannot do
  this (no device). Ask the user; provide a checklist from spec §8.2.
- **Manual §11.4 watch-restart gate** after M5 — same, user-run.
- **Standalone Cardio+Core day cards** (spec §6.4, SEPARATE_DAYS/BOTH cardio
  placements): ProgramGenerator emits them (`GeneratedProgram.cardioDays`) but
  no schema/UI consumes them — `WizardViewModel.finish()` takes only
  `.program`, so users choosing those placements currently get their extra
  cardio guidance nowhere. Needs a product decision first (they live outside
  the rotation, so the day-tab model doesn't fit); candidate for a v1.1/M-later
  issue.

## #21 Gym-floor usability & a11y pass (tier:sonnet)

A7 checklist end-to-end: ≥48dp verified everywhere (tests exist for
components; audit screens), stepper long-press auto-repeat (one
implementation in the shared Stepper), content descriptions on every
interactive element, TalkBack sanity pass via semantics tests
(compose-ui-test on Robolectric), font-scale 1.3/2.0 render without clipped
numerals (the condensed display face is the risk), edge-to-edge insets,
predictive back. Keep-screen-on already shipped in #10.

## #22 App icon & visual polish (tier:sonnet, AFTER design pass)

Adaptive icon (foreground/background layers, monochrome for themed icons),
splash to match, fix the two manifest lint warnings, review every screen
against the final design tokens. Icon art direction comes from the design
session output — do not invent one ahead of it.

## #23 Privacy policy page + Play release prep (tier:sonnet)

Static privacy page (GitHub Pages in this repo): truthfully "no data
collected, no data shared, everything on-device"; required by Play for the
Health Connect permissions (#17). Data-safety form answers documented in the
repo. In-app licenses surface (Oswald OFL + any library notices — the ledger
item). Signing: keys live OUTSIDE the repo (public repo!) — user-held;
document the release-build steps without embedding secrets. `exportSchema`
already true; confirm migration-test scaffolding exists before any schema v2.
