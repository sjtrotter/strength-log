# strength.log — Plate math (issue #101)

You walk to the bar knowing "235". What you need at that moment is
"45 + 25 + 2.5 a side". Every lifter re-derives this between sets, tired. The
app already knows the weight, the unit, and whether the exercise uses a
barbell — so derive it, in one place, and say it quietly.

Scope: `:domain` + phone day screen. The watch surface is deliberately deferred
(the dial is mid-rework on another branch); nothing here may touch `wear/`.

---

## 1. Domain — `PlateMath`

New pure object in `domain/units/PlateMath.kt`:

```kotlin
object PlateMath {
    /** The bar the standard denominations assume: 45 lb / 20 kg. */
    fun barWeight(unit: WeightUnit): Double

    /**
     * Plates on ONE side for [displayWeight] (already in [unit]), heaviest
     * first — or null when the weight can't be loaded exactly: below the bar,
     * or leaves a remainder no standard plate covers. An empty list is a valid
     * result: the empty bar.
     */
    fun perSide(displayWeight: Double, unit: WeightUnit): List<Double>?
}
```

- Denominations (per side, standard gym): **lb** 45 / 35 / 25 / 10 / 5 / 2.5,
  **kg** 25 / 20 / 15 / 10 / 5 / 2.5 / 1.25.
- Greedy from heaviest: `remaining = (displayWeight − bar) / 2`, take the
  largest plate ≤ remaining, repeat. If a remainder survives the smallest
  plate (beyond a 1e-9 epsilon), return **null** — silence over a wrong
  answer. Never round, never approximate.
- No stored state, no config in v1 (bar/plate customization is a possible
  later setting; do not build it now).

**Pinned test vectors** (lb) — these come straight from spec §11's numbers, so
they double as a cross-check against the goal math:

| total | per side |
|---|---|
| 235 | 45, 45, 5 |
| 245 | 45, 45, 10 |
| 130 | 25, 10, 5, 2.5 |
| 165 | 45, 10, 5 |
| 190 | 45, 25, 2.5 |
| 210 | 45, 25, 10, 2.5 |
| 135 | 45 |
| 45 | (empty — bar only) |
| 40 | null (below the bar) |
| 137 | null (2.5-lb-per-side remainder → 1 lb leftover) |

kg vectors: 60 → 20; 20 → empty; 102.5 → 25, 15, 1.25; 19 → null.

## 2. Phone — one quiet line on the exercise card

On the **expanded** card of a **barbell** exercise (library entry's `equipment`
contains `Equipment.BARBELL`; TRAP_BAR and everything else stays silent — trap
bar weights vary too much to guess), show one line in the same quiet register
as "Last time:" / "Best:" (`bodySmall`, `TextFaint`), placed with the set
rows so it reads as part of the work, directly above them:

```
Plates: 45 + 25 + 2.5 a side          (normal case)
Plates: empty bar                     (weight == bar)
```

- The line tracks the **first undone set's** weight (the set the lifter loads
  next) and therefore updates as sets tick and as the stepper edits weights.
  Ramp days change it set by set — that is the point.
- Formatted with the existing `WeightStepper.format` per plate (so `2.5` keeps
  its decimal and `45` doesn't grow one). No unit suffix — the card already
  lives in the display unit.
- When `perSide` returns null, all sets are done, or the exercise isn't
  barbell: **no line at all.** Never an error state, never a dash.
- Main slot only in v1 (superset partners are accessory-shaped; skip).
- Derivation lives in `DayScreenBuilder` next to the other card-state
  derivations, unit-tested there; the composable renders a prebuilt string or
  nothing (`plateLine: String?` on the card state).

## 3. Acceptance

1. `PlateMath` vectors above pass, in `:domain` tests, pure Kotlin.
2. Barbell cards show the line for the next undone set; non-barbell cards and
   finished cards show nothing.
3. Editing a weight with the stepper updates the line immediately.
4. Spec §11 pinned tests untouched and green; no wear/ changes; no schema or
   storage changes anywhere.
