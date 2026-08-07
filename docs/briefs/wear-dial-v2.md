# strength.log — Dial v2: readable on an actual wrist

Amendment to `wear-dial-redesign.md`, from the first on-wrist test (issue #99).
Where the two disagree, this brief wins. Scope: `wear/` only. No wire changes.

---

## 0. What the on-wrist test proved

The v1 brief was authored on a 384px HTML canvas and judged on a monitor. On the
watch, that canvas maps 1:1 to *physical* pixels (Pixel Watch: 384px, density
2.0), so every "px" in the brief landed at half the dp size the mock appeared to
promise. Three consequences:

1. **Type is unreadable.** The 13px band renders at ~6.5sp; Wear Material's
   smallest sanctioned size is 10sp, and 12sp is the practical floor. Only the
   big numerals survive.
2. **Bands clip at the bezel.** The 56px straight side inset ignores that at the
   band's height the visible screen is a *chord*, narrower than the inset box.
3. **The melt destroys information.** Morphing the segmented exercise ring into
   the draining rest arc replaces one meaning with another at the same radius —
   and hides the round count exactly when the lifter is mid-rest wondering
   what's left.

The scaling *mechanism* (measure the face, derive everything) is correct and
stays. The reference values were wrong. The ring model gains one ring.

---

## 1. Type scale v2

Same `DialTypography` mechanism, new reference values — on the 384px canvas,
sized so the Pixel Watch (density 2.0) lands exactly on the sp column:

| Role | v1 ref px | v2 ref px | on-wrist |
|---|---|---|---|
| `NUMERAL_LARGE` | 58 | **72** | 36sp |
| `NUMERAL` | 44 | **60** | 30sp |
| `DISC_LABEL` | 25 | **36** | 18sp |
| `DISC_LABEL_SMALL` | 21 | **30** | 15sp |
| `BAND` | 13 | **26** | 13sp |
| `BAND_SECONDARY` | 11 | **24** | 12sp |
| `CYCLE_LABEL`¹ | — | **18** | 9sp |

Tracking scales with the same factor (START's 3px → 6px, band 2px → 4px,
secondary 1.5px → 3px). **Nothing on the dial may render below 12sp** — that is
acceptance criterion 1 and a test, not a guideline. The font-scale pin
(physical-size type, ignore system font scale) stays, with its existing
rationale in `DialType.kt`.

¹ Added in dial v3 for the cycle ring's day labels (issue #152), after the
floor above had already shipped and been tested on-wrist. It is the one named
exception: the ring segment's colour is the identification, the label only
names it, and 18 is what fits inside the ring's 22px stroke.

## 2. Geometry v2

Rings unchanged. The disc grows to hold the bigger type; the bands move to make
room; the fixed side inset dies.

```
Day ring        inset 9,  stroke 5      (unchanged)
Exercise ring   inset 26, stroke 14     (unchanged)
Center disc     204 diameter            (was 176)
Top band        starts 48 from top      (was 52)
Bottom band     ends 44 from bottom     (was 48)
Clock ring      stroke 7, on the disc rim (see §3) — replaces HOLD_RING_STROKE 4
Bloom           10 wide, outside the disc rim (unchanged)
```

**Bands are chord-constrained, not inset-constrained.** `BAND_SIDE_INSET` is
deleted. New pure function in `DialGeometry`:

```kotlin
/** Widest a horizontal text row may be if the whole row (its far edge
 *  included) stays inside the circle: the chord at the row's far y,
 *  minus a small safety margin. */
fun bandMaxWidthPx(diameterPx: Float, rowFarEdgeYPx: Float): Float
```

`= 2 * sqrt(r² − (r − y)²) − 2·safety` with `safety = px(4f)`, where `y` is the
band row's edge *nearer the pole* (top edge for the top band, bottom edge for
the bottom band — the narrowest chord the row touches). The Band composable gets
`Modifier.widthIn(max = …)` from this; text ellipsizes inside it, one line,
never wraps, and by construction can never touch the bezel. Unit-test the math
(y = r ⇒ full diameter minus margin; y → 0 ⇒ → 0; symmetry).

Fit sanity at reference: disc top sits at 90; top band occupies ~48–79 ✓.
Bottom band top edge ~309 vs disc bottom 294 ✓.

## 3. The clock ring — rings nest, they never transform

**Delete the melt.** The exercise ring is always segmented and stays visible on
every in-session screen, REST and TIMED_HOLD included. `DialGeometry.trimToFraction`,
`DialMotion.melt`, and the gap-closing animation go away entirely — no dead code.

`DialUiState.arc` keeps its meaning (a running clock's fraction) but renders as
a **third, innermost ring on the disc's own rim**: stroke 7 (reference px),
drawn just inside the disc edge — exactly where the undo hold-fill already
lives. One radius, one stroke, one meaning: **the ring touching the disc is the
clock running right now.**

- **Rest** drains it: full sweep → nothing, clockwise from 12, accent color.
- **Timed hold** fills it: nothing → full, accent.
- **Undo hold-fill** is the same ring (shared constant; `HOLD_RING_STROKE` is
  subsumed). It never coexists with a rest/hold arc — undo is only offered on
  READY and REST_OVER, which carry no arc.
- Radius = timescale, attention = innermost: day ring moves over an hour, the
  exercise ring over minutes, the clock ring over seconds.

Motion table changes (v1 §8 rows "Rest begins"/"Rest ends" are replaced):

| Event | Motion |
|---|---|
| Rest begins | clock ring sweeps in to full (180ms); exercise ring untouched |
| Rest ends | clock ring reaches zero and vanishes; bloom fires outside the rim as before; nothing re-segments |
| Timed hold at goal | clock ring completes, then the existing buzz → auto-tick |

**Peek keeps the clock.** `withPeek` no longer nulls `arc` — the white marker
lives on the exercise ring, which is now always segmented, so the two coexist.
While peeking, the clock ring dims with the disc (62%), same one-object read.

**Ambient parity.** The ambient dial draws the same nesting: during a rest, the
clock ring appears at the rim radius as an outline arc in ambient gray — no
filled shapes, no accent, exactly as the other rings.

## 4. Copy diet

Bigger type means fewer characters. Verified against the widest real strings;
the chord/ellipsis machinery is the safety net, not the plan.

- Today bottom band: `3 EXERCISES · 21 SETS` → **`3 LIFTS · 21 SETS`**.
- Lifting disc hint: `TAP WHEN RACKED` → **`TAP TO LOG`** (BAND role; the old
  string cannot fit a 204 disc at 13sp).
- Day-done disc becomes **three lines**: `DONE` (NUMERAL), then `38 MIN`, then
  `12,450 LB` (both BAND) — the joined stats line cannot fit. `dayDoneStats()`
  returns parts, not a joined string.
- Everything else keeps its copy; bands ellipsize inside the chord.

## 5. What must not change

Tap/hold/crown semantics, screen decision table (`dialUiState`), the wire, the
rest controller, haptics, `RoundState` colors, the accent system, the
one-tap-target rule, and every v1 acceptance criterion not superseded here.
`:domain` and phone are untouched; spec §11 pinned tests are out of reach by
construction but must still pass.

## 6. Acceptance

1. No dial text below 12sp on a 192dp face (test over the type scale).
2. Band rows are chord-constrained; `bandMaxWidthPx` unit-tested; no fixed side
   inset remains.
3. Exercise ring is segmented and visible on TODAY, READY, LIFTING, REST,
   REST_OVER, TIMED_HOLD. The only screen without it is DAY_DONE.
4. The clock ring exists iff a clock runs (rest, timed hold, undo fill), always
   on the disc rim, always stroke 7.
5. Melt machinery deleted; no references to `trimToFraction` remain.
6. Existing wear unit tests updated, new ones for §2/§3; all green.
7. Ambient draws the clock ring as outline-only, no accent, during rests.
