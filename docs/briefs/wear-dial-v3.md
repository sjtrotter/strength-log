# strength.log — Dial v3: the cycle ring, neutral actions, two faces

Third on-wrist pass (user direction, 2026-07-31). Three connected changes: the
outer ring becomes the program cycle, the disc's action colors stop following
the day accent, and the watch gains exactly two faces with honest gestures.
Amends `wear-dial-redesign.md` / `wear-dial-v2.md` / `wear-curved-bands.md`;
where they disagree, v3 wins.

Scope: `wear/` + one additive wire field (+ its stamp in
`WatchSnapshotBuilder`). Phone UI untouched.

---

## 1. The cycle ring (outer)

The outer ring stops being a bare progress arc and becomes **the program
cycle**: one segment per program day, in program order, clockwise from 12,
4° gaps (the exercise ring's own segment grammar).

- **Today's segment**: its day accent at full strength, carrying a curved
  label in that day's `onAccentHex`.
- **Every other day**: its own day accent dimmed to ~30% alpha, label in
  `TextTertiary`. The color does the talking; the label is quiet.
- **Labels are curved** (same `basicCurvedText` machinery as the bands, new
  smaller role is NOT allowed — labels render at BAND_SECONDARY, 12sp floor
  holds). Adaptive rule, derived not tuned:
  - segment sweep ≥ sweep("DAY X") + 8° margin → label `DAY X`
  - else if ≥ sweep("X") + 8° → label `X`
  - else no label (color alone).
  Bottom-half segments flip like the bottom band so letters read upright.
- **Day progress moves into today's segment**: a success-green arc riding the
  segment's inner edge (stroke ~6 ref px), sweeping `dayProgress` × the
  segment's own sweep. "Where am I in the cycle" and "how far through today"
  are one glance at one place.
- **Geometry** (reference px, derive everything in `DialGeometry`):
  cycle ring inset 9, stroke 22 (was 5 — it now carries type); exercise ring
  inset moves 26 → 40, stroke 14 unchanged; disc stays 204. `bandArc()`
  already derives from the exercise ring's inner edge and the disc rim, so the
  label bands shrink their annulus automatically — verify the band line still
  fits it and state the numbers in the PR.
- **Wire**: additive `WatchSnapshot.cycle: List<WatchCycleDay> = emptyList()`
  — per day: `dayId`, `title`, and its exercises' short names + set counts
  (the minimum the overview's day-browse preview needs; NOT full sets — the
  watch never logs against a browsed day). Stamped by `WatchSnapshotBuilder`
  from the program in order.
  Accent per segment = its index (the same accent-by-position rule everything
  else uses). Empty list (old phone) → render as `listOf(day.dayId)`: one
  full-circle segment, today, correct by construction. Codec compat matches
  the earlier additive fields (restSeconds precedent).
- **Ambient**: unchanged from v2 — the thin outline ring + progress, no
  segments, no labels, no accents. Burn-in wins.
- **Day-done (§5.7 v1)**: the cycle ring stays (it is context, not progress);
  today's segment simply reads fully green.

The top band stops carrying `DAY C · …` — day identity lives on the ring now.
On the overview face the top band shows the day *title* alone (`FULL BODY`);
in-workout bands are unchanged.

## 2. Action colors leave the day palette

The disc is the machine's controls; the rings/bands are the day's identity.
Controls stop borrowing the day accent:

- `FILLED` (start/go) → fill `TextPrimary` #F2F2F0, text `Background` #0D0D0F.
- `OUTLINED` (in progress / tap to log) → border `TextPrimary`, surface fill.
- `FILLED_GREEN` (day done), `FLAT`, `DASHED`, `DIMMED` — unchanged.
- The rest-over bloom follows the disc it blooms from → `TextPrimary` at the
  same alpha curve.
- The **clock ring and undo hold-fill stay day-accent**: they are the day's
  time passing, not a control surface. (Single deliberate exception; document
  it where the colors are resolved.)
- No new hexes: these are existing tokens re-rolled. `onDayAccent` no longer
  decides disc text (disc text color now follows the disc style, statically);
  keep `accentBright` for bands.

## 3. Two faces, honest gestures

v1's "there is no navigation" bends: there are now exactly **two** faces, and
platform gestures move between them. No third face, ever.

- **Overview face** (today's TODAY screen, renamed in code if it clarifies):
  cycle ring, exercise ring = one segment per lift, disc, bands. The disc is
  the way in: `START` over the current lift's (short) name before anything is
  logged; `CONTINUE` over `SET n OF m` once something is. Tap → the workout
  face at the current set's READY. The old separate `BEGIN_EXERCISE` tap
  state dies — copy finally matches action.
- **Workout face**: READY / LIFTING / REST / REST_OVER / TIMED_HOLD /
  DAY_DONE exactly as they are.
- **Swipe right** (the platform dismiss gesture, via the Wear two-level
  swipe-dismiss machinery — `SwipeDismissableNavHost` or `SwipeToDismissBox`,
  whichever fits the single-composable dial cleanly): workout face → overview
  face; overview face → app exits (system behavior). Rest keeps running
  through all of it (deadline-anchored already) and the overview face draws
  the clock ring too — innermost = now, on both faces.
- **Swipe left is contextual** (user direction, 2026-07-31):
  - **On the workout face at a set's start (READY, REST_OVER)**: cycle to the
    next exercise's READY, wrapping, same order as the day.
  - **On the overview face**: cycle through the program's *days* — a
    read-only preview of day B, C, … wrapping back to today. The previewed
    day renders with its own accent, title, and exercise segments; its cycle
    segment carries the **white "you are looking here" marker** (the peek
    vocabulary, §4 v1) while today's segment keeps the accent "you are here";
    the disc is **DIMMED** (read-only — browsing another day is never one
    slip from starting it). Swiping back around to today restores the live
    disc. Crown roles unchanged on both faces.
  - No swipe-left anywhere else (LIFTING/REST must not lose a set to a
    brushed sleeve).
- Process-death restore lands on the face the state implies: mid-workout
  state → workout face; nothing begun → overview. `rememberSaveable` the face
  only if the state can't already decide it.

## 4. Tests

- Geometry: cycle segment sweeps + gaps, label-fits rule at 3/5/7-day
  programs, progress sub-arc bounds within today's segment, new ring budget
  (bands still fit their annulus).
- State: overview disc copy (START/CONTINUE), tap → READY, swipe-left
  next-exercise wrap, old BEGIN state gone; day-browse renders the previewed
  day read-only (DIMMED disc, no tap), white marker on its cycle segment,
  accent stays on today's, wraps back to live.
- Wire: builder stamps `cycleDayIds` in order; empty-list fallback renders
  one-segment ring.
- Colors: FILLED/OUTLINED resolve day-independent; clock ring still accent.
- All existing wear tests updated; §11 untouched.

## 5. On-device gate (non-negotiable, learned from #113)

JVM tests cannot render curved text or gestures. Before the PR is called
done: install on the watch, launch, walk overview → workout → swipe back →
swipe-left cycle → start a set → rest, and scan the crash buffer. The
deploying reviewer does this; the implementing agent must NOT touch devices.
