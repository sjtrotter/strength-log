# strength.log — Curved bands & short names (issues #110, #111)

Second on-wrist pass on dial v2. Two findings, one surface: the words on the
watch. Scope: `wear/` + `:domain` catalog + one stamp line in
`WatchSnapshotBuilder`. No wire-shape changes, no phone UI changes.

---

## 1. The bands curve with the face (#110)

The label bands live in the annulus between the disc and the exercise ring. On
a round face that space is an arc, and straight text wastes it — the chord
constraint (dial v2 §2) was the honest fix for the wrong geometry. Curve the
text instead:

- **Top band**: arced over 12 o'clock, centered, reading clockwise
  (left-to-right for the wearer). **Bottom band**: arced under 6 o'clock,
  centered, angle-flipped so it reads upright (the standard Wear convention).
- **Implementation**: `CurvedLayout` + `curvedText` from
  `androidx.wear.compose.foundation` (already a dependency at 1.6.2), with
  `CurvedTextStyle` carrying the dial's own type — Barlow Condensed, the BAND /
  BAND_SECONDARY sizes and tracking from `DialTypography`. The type identity is
  non-negotiable; if some style attribute can't ride `CurvedTextStyle` at this
  version, bump the wear-compose catalog version (stable only) rather than
  dropping the font. Fallback if the API genuinely can't carry the type:
  hand-drawn `drawTextOnPath` — but prefer `curvedText` (it keeps text
  semantics for TalkBack, which the bands have today).
- **Geometry moves to `DialGeometry`** like everything else: a band arc is a
  baseline radius (place the glyph boxes centered in the annulus between the
  exercise ring's inner edge and the disc's outer edge — derive it, don't
  tune it) plus a **max sweep**. Replace `bandMaxWidthPx` with the pure
  function(s) the curved layout needs (e.g. `bandSweepDeg(textWidthPx,
  radiusPx)` = width/radius in degrees, and a max-sweep constant ≤ ~120° per
  band). Unit-test the math; delete the chord function and its tests — no dead
  code. (The ambient center numeral's disc-width bound stays.)
- **Overflow**: long text ellipsizes at the max sweep (curvedText's overflow
  handling; verify it exists at this version, else truncate in the state
  layer). Never let text run past ±(maxSweep/2) from the pole.
- **The pulsing dot** (LIFTING elapsed, queued status) becomes a
  `curvedComposable` leading the text on the same arc.
- **Ambient parity**: `AmbientDial`'s top and bottom texts curve identically
  (same geometry helpers, ambient colors). Center numeral stays straight.
- Bloom, rings, disc, tap/hold/crown: untouched.

## 2. Colloquial short names (#111)

The watch says `CONVENTIONAL DEADLIFT`; a lifter says "deadlift".

- `ExerciseEntry` gains `val shortName: String? = null` — the **colloquial gym
  name**, set ONLY where it usefully differs from `name`. Guidance for the
  data pass over the catalog (184 entries; most stay null):
  - Drop qualifiers that don't disambiguate in speech: Conventional Deadlift →
    `Deadlift`, Barbell Back Squat → `Squat`, Barbell Bench Press → `Bench
    Press`, Barbell Overhead Press → `Overhead Press`.
  - Use the abbreviation lifters actually say where it's universal: Romanian
    Deadlift → `RDL`, dumbbell variants may use `DB …`.
  - Aim ≤ 14 characters; never invent a name nobody uses; when in doubt, leave
    it null. Two exercises may share a short name only if no plausible program
    puts both on one day.
- **One stamp point**: `WatchSnapshotBuilder` sends
  `entry?.shortName ?: entry?.name ?: pe.exerciseId` wherever it stamps a name
  the watch displays (main and superset partner alike). The wire DTO shape is
  unchanged — the phone simply sends the shorter string. Custom exercises have
  no shortName and fall back to their full name.
- **Display-only**: phone UI, history, backup/CSV, Health Connect all keep the
  full name. Nothing but the watch snapshot reads `shortName`.
- Tests: builder test that a short-named entry reaches `WatchExercise.name`
  as the short name and an entry without one keeps the full name; a catalog
  test that every non-null shortName is non-blank, ≤ 20 chars, and differs
  from its `name`.

## 3. Acceptance

1. Top and bottom bands render as arcs concentric with the rings, upright at
   both poles, in Barlow Condensed at the dial's band sizes, on every screen
   including ambient.
2. Long band copy ellipsizes along the arc; nothing renders past the max
   sweep or outside the band annulus.
3. Chord machinery deleted; band geometry lives in `DialGeometry` with tests.
4. `CONVENTIONAL DEADLIFT` reads `DEADLIFT` on the watch (band, disc, NEXT
   line, peek, swap) and remains `Conventional Deadlift` everywhere on the
   phone.
5. All wear + app unit tests green; spec §11 untouched; no new permissions;
   wire DTO shape unchanged.
