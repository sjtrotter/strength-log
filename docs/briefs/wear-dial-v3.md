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

**Amendment, second on-wrist round (issue #152):** the labels above were built
at BAND_SECONDARY on the assumption that "no new smaller role" was the safer
call. On the wrist it read as too large — the segments already carry the
identification in colour, and the word sitting on top of them only names what
the colour already said. The owner waived the 12sp floor for this row alone
and it got its own step, `CYCLE_LABEL` at 18 reference px (9sp on the Pixel
Watch face). 18 isn't a taste call: the ring's stroke is 22 reference px and
the band styles carry a 1.2em line height, so 22 / 1.2 = 18.33 — 18 is the
largest whole step whose line box still fits inside the ring it rides.

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

## 5. Swap, finally (issue #90 — amends redesign §6)

v1 §6 said "the crown flicks between the alternates the phone already
prescribed" and left it there, because the snapshot carried no alternates. It
does now (`WatchExercise.alternates`, ranked by the phone's own
`substitutionsFor` + equipment filter, capped at 3 — a wrist is not a picker).
The gesture that grew around it:

- **Offered only on a lift with nothing logged against it.** Not a mode, a
  derived rule, and it settles two problems at once. The phone's swap *clears
  the slot's log* (§8.3), so offering it only while there is nothing to clear
  makes the destructive half free; and it is the same condition under which
  the crown's peek has nothing to scrub, so the two never fight over the
  crown. REST_OVER always follows a tick, DAY_DONE has no unlogged lift, the
  overview keeps SELECT_EXERCISE — all of it falls out, none of it is cased.
- **The list runs from the lift itself downward.** No preview is index −1:
  flicking forward proposes the best-ranked replacement, flicking back off the
  top puts the original lift back. Nothing to learn, nothing to cancel.
- **The preview disc is OUTLINED, not FILLED.** Both would mean "the tap
  acts", but READY's own disc is a FILLED `START` one flick away, and a swap
  preview that looked like the start button is a mis-tap waiting to happen.
- It ends by itself after 4s of a still crown — longer than the peek's 1.5s,
  because a peek ends by not looking and this ends in a tap.
- **A swap is acked by identity, not by label.** `WatchExercise.exerciseId` rides
  the wire beside the name so the queue settles on what the slot *is*. Two
  catalog entries may share a display name, and a name match against an unchanged
  snapshot would drop a swap that never landed — the exact failure the queue
  exists to prevent. Name matching survives only as the documented degradation
  for a publisher too old to send ids.
- **The snapshot is the authority document, so it can also say no.** When a fresh
  snapshot's prescription for a slot no longer offers the pending swap's target —
  a deleted custom exercise, a narrowed equipment set — the phone could only ever
  answer INVALID, so the watch drops the request itself and gives the lift back.
  Without that terminal condition the lift would sit read-only until the day
  turned over, waiting for an answer that was never coming.
- **Between confirm and the phone's answer the lift reads `SWAPPING`, DIMMED,
  no tap.** The name is already the new one (the client echoes that much) but
  the rows under it still belong to the exercise being replaced, and seeding is
  phone-authoritative — drawing `235 × 5` under a lift nobody prescribed 235 for
  is the one optimistic echo that could hurt someone. The swipe stays live, so
  offline the lifter is never trapped: only the lift they asked to have replaced
  is un-loggable, and the queued count in the top band says why.

## 6. On-device gate (non-negotiable, learned from #113)

JVM tests cannot render curved text or gestures. Before the PR is called
done: install on the watch, launch, walk overview → workout → swipe back →
swipe-left cycle → start a set → rest, and scan the crash buffer. The
deploying reviewer does this; the implementing agent must NOT touch devices.
