# Light theme — design brief (split from #29)

Owner (2026-08-10): light theme ships in v1; tablet stays parked. The rule for
this work: a light *expression* of the authored identity, not an inversion.
Near-black becomes warm paper; the earth accents keep their hue identity; the
ink does what the off-white text did. Dark remains the default character of
the app — light is an offering.

## Palette (authoritative — deviations need this file amended)

| Token | Dark (today) | Light |
|---|---|---|
| Background | #0D0D0F | **#F1EFEA** (warm paper, never pure white) |
| Surface (cards) | #16161A | **#FAF9F6** |
| Surface2 (raised controls) | existing | **#E9E6E0** |
| Surface3 (if present) | existing | **#E1DDD5** |
| Border | #2A2A30 | **#D8D4CC** |
| BorderStrong | #3A3A42 | **#C4BFB5** |
| TextPrimary | #F2F2F0 | **#1B1B1E** (ink) |
| TextSecondary | #9A9AA2 | **#5C5C64** |
| TextFaint | existing | **#8B8B92** |
| Scrim | dark | stays dark (a scrim dims in both worlds) |
| Done / Error | existing | darken until ≥4.5:1 on #F1EFEA, keep hue |

Chosen light foregrounds: **Done #37774E (4.67:1)** and **Error #C2334D
(4.72:1)** on #F1EFEA. Error already cleared the floor, so its authored cooler
crimson did not need to move; Done is darkened 20% toward the light theme's ink.

Day accents: hues are the identity and do not change. Each accent gains a
**`deepHex`** variant in `:domain` beside `brightHex` (same SSOT pattern the
wrist round pinned): the accent darkened along its own hue until it reads
≥4.5:1 against #F1EFEA. `brightHex` serves dark surfaces; `deepHex` serves
light. A pinned test computes/asserts every ratio, exactly as brightHex's did.
Accent FILLS (selected cards, tabs, START) keep the base accent with on-accent
ink chosen per-theme by contrast; accent TEXT and chart strokes use
bright-on-dark / deep-on-light.

`deepHex` uses one 30% sRGB move toward ink (#1B1B1E), mirroring `brightHex`'s
single-rule derivation. The pinned A–G results and their contrast on #F1EFEA are:
**#8F3813 (6.66:1), #284734 (8.95:1), #896611 (4.60:1), #1E3F4C
(9.77:1), #323F5D (9.12:1), #693745 (8.18:1), #535228 (7.01:1)**.

## Mechanics

- `lightColorScheme` mirror of the phase-1 dark scheme — every role explicit,
  no baseline leakage in either scheme; the existing no-baseline theme test
  runs against both.
- Preference: `theme` in SettingsStore — `SYSTEM` (default), `DARK`, `LIGHT` —
  a Setup row (SelectionCard Radio trio), carried in backup schema (defaulted
  SYSTEM so old backups restore).
- `AppTheme` resolves preference + `isSystemInDarkTheme()`. Widget/RemoteViews
  keep their own current styling this pass (they read fixed colors — a
  follow-up if demand); the WATCH is out of scope (a lit dial is the wrist's
  identity, and OLED battery physics agree).
- Charts/journal: `accentBright()` consumers switch to a theme-resolved
  `accentEmphasis()` that returns bright-on-dark, deep-on-light. Calendar,
  trajectories, share card (share card stays DARK always — it is a designed
  artifact, not a themed surface; document that).
- Previews: add light previews beside dark for the key screens.

## What must survive

Condensed numerals, per-day accents, restrained copy, the one-accent rule,
authored states (the loading sweep's accent works on paper), press feedback
(ripple alphas may need a light-side value — M3's default dark-content ripple
on light surfaces: verify the phase-2 AppRippleConfiguration reads sensible on
paper; amend alphas per-theme only if visibly wrong). Pinned copy and §11 are
untouched by definition.
