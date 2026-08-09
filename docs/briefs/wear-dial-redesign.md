# strength.log — Wear OS "Dial" redesign brief

Implementation brief for the watch app. Hand this to Claude Code as the spec.
Reference mockups: `Watch Redesign.dc.html` (turn 2 = this design; turn 1 = alternatives considered).

Target: round Wear OS (Pixel Watch / Galaxy Watch). Design canvas **384×384** logical px.
Scope: watch module only (`wear/`), plus 3 additive fields on the wire protocol.

---

## 0. Why the previous attempt failed

The old screens laid content out in vertical columns and lists, then relied on padding
to keep things off the bezel. On a round screen that produces the overlap bugs we saw:
text clipping at the curve, controls colliding with the progress ring, chrome fighting
the content for the same pixels.

**The fix is structural, not cosmetic.** Do not position anything by trial-and-error
padding. Every element's position is defined as an **inset from the circle edge**, and
there are only four legal radii (§2). If an element doesn't belong to one of those four
zones, it does not go on screen.

---

## 1. Core concept — everything is concentric

The screen is a dial. Each ring is one level of the workout's nesting:

| Zone | Meaning |
|---|---|
| Outer thin ring | the whole day (all sets across all exercises) |
| Inner thick ring | the current exercise's rounds |
| Label band (top) | what/where — two lines of caps, max |
| Center disc | the one thing you can do right now |
| Label band (bottom) | secondary context or the gesture hint |

Consequences to preserve:

- **No lists.** No `ScalingLazyColumn` anywhere in the workout flow. Navigation between
  exercises and sets is the rotary crown, not scrolling.
- **One tap target per screen** — the center disc. It is 176px across; it is reachable
  with chalked hands and without looking.
- **Screens never slide.** There is one layout; state changes re-render it in place.
  The disc changes appearance, the rings change data. You never navigate "forward".
- **No rectangles.** No cards, no rows, no chips in the workout flow.

---

## 2. Layout spec — the only four radii

Canvas is a 384px circle. All values are logical px at that reference size; scale
proportionally for other watch sizes (compute from the measured diameter, never hardcode).

```
Day ring      inset 9px from edge,  stroke 5px
Exercise ring inset 26px from edge, stroke 14px
Label bands   inside a 56px horizontal safe inset; top band baseline zone starts 52px
              from the top edge, bottom band ends 48px from the bottom edge
Center disc   176px diameter, centered
```

Content column padding, as authored in the mock: `padding: 52px 56px 48px`, with the
three zones distributed `space-between`. The disc is fixed size; the bands take the
remainder. **Nothing may be placed outside these zones** — that constraint is what makes
the overlap bug unreachable.

Text rules:
- Label bands: 1 line preferred, 2 lines absolute max, and only in the disc (never a band).
- Long exercise names break inside the **disc**, not the band (see §5 "Today", "Swap").
- Never shrink text below 13px to make something fit. If it doesn't fit, it doesn't belong.

---

## 3. Tokens

Colors — reuse the existing app tokens, do not introduce new ones:

```
background      #0D0D0F
surface         #16161A
border / empty  #2A2A30
accent          #C1440E   (day accent; comes from DayAccentColors — see below)
accent bright   #e0703f   (text-on-dark variant of the accent, for band labels)
success         #3E8E5A
text primary    #F2F2F0
text secondary  #9A9AA2
text tertiary   #6B6B73
ambient dim     #4E4E55
```

The accent must come from the existing per-day accent source (`DayAccentColors` in the
domain module) — do not hardcode `#C1440E`. Every accent surface, ring segment and band
in a session uses that day's accent. Success green is fixed and is used **only** for
completed things and the day-done disc.

Type — Barlow Condensed, tabular numerals, three sizes only:

```
disc numeral    44–58px, weight 700     (weight/reps, timers)
disc label      21–25px, weight 700     ("START", exercise names)
band            13px, weight 600, letter-spacing 2px, ALL CAPS
band secondary  11px, weight 600, letter-spacing 1.5px, ALL CAPS
```

Anything between those sizes is a smell. There is no 16px, no 18px.

---

## 4. Ring vocabulary — strict

| Form | Means | Used for |
|---|---|---|
| Continuous arc | a proportion | day progress; rest draining |
| Segmented arc | countable things, one segment per item | exercise rounds; exercises within the day |
| Accent segment | **you are here** — exactly one, ever | current round / current exercise |
| Green segment or arc | done | logged rounds, day progress |
| Gray segment `#2A2A30` | not yet | upcoming — never a nag, never a hint |
| White segment `#F2F2F0` | **you are looking here** (crown peek only) | scrub marker, distinct from "you are here" |

Segment gaps: ~4° between segments. Segments always start at 12 o'clock and run clockwise.

Disc grammar — the fill states the mode:

| Disc | Means | Tap does |
|---|---|---|
| **Filled** accent | ready — act now | starts the set (or begins the exercise) |
| **Outlined** (surface fill, 2px accent border) | in progress | finishes the set / ticks |
| **Flat** (surface fill, no border) | waiting on the clock | skips the wait |
| **Dashed** border | nothing to act on | nothing |
| **Dimmed** (62% opacity) | read-only, you're browsing | nothing (release crown to return) |
| **Filled green** | day complete | dismiss |

---

## 5. Screens

All seven are the same composable with different data. Mock reference: option `2b`.

### 1 · Today
- Day ring: empty gray (nothing logged yet).
- Exercise ring: one segment **per exercise** (not per set) — e.g. 3 segments; first is accent.
- Top band: `DAY A · LOWER` in accent bright.
- Disc: filled accent, exercise name (may wrap to 2 lines, 21–23px), sub-label `BEGIN · 6 SETS`.
- Bottom band: `3 EXERCISES · 21 SETS` in tertiary.
- Crown rotates between exercises; tap begins the accented one.

### 2 · Ready (set preview)
- Day ring: continuous green arc = sets logged today / total sets today.
- Exercise ring: one segment per round; logged = green, current = accent, upcoming = gray.
- Top band: `SQUAT · TOP` (exercise short name · set kind) in secondary.
- Disc: **filled** accent — `START` (25px, 3px tracking) over `235 × 5` (19px).
- Bottom band: `SET 5 OF 6`.
- Tap → stamps `startedAtMillis`, goes to Lifting.

### 3 · Lifting
- Rings unchanged from Ready.
- Top band: pulsing accent dot + live elapsed `0:47`. The elapsed timer lives **in the band** —
  it must never be drawn over or near the disc numeral.
- Disc: **outlined** — `235×5` as one numeral group (46px weight, `×5` at 27px in secondary),
  then `TAP WHEN RACKED` (13px, accent bright).
- Bottom band: `SET 5 OF 6`. (Amended after the on-wrist round, #151. This line used to
  read `HOLD TO UNDO`, which contradicted §6 below: the undo is a long-press *on a logged
  set's disc*, and a set in progress isn't logged yet. The hold is offered on Ready, Rest
  over and Day done and nowhere else, so the hint was naming a gesture that did nothing
  where it was written. The offer is the honest half and it stayed; the hint went, and the
  band now carries the set position the timed-hold screen already shows.)
- Tap anywhere in the disc → stamps `completedAtMillis`, emits the delta, auto-starts rest.

### 4 · Rest (auto-started)
- Exercise ring: the segmented ring **melts into one continuous arc** which drains in real
  time. This shape change is the signal that a clock is running — do not also add an icon.
- Top band: `REST`.
- Disc: **flat** — `1:24` (58px) over `NEXT 175 × 8` (15px).
- Bottom band: `TAP TO SKIP`.
- Skipping logs the rest actually taken, same as expiry.

### 5 · Rest over
- One haptic buzz. Arc re-segments; round marker steps forward; disc re-fills accent with a
  10px halo bloom that decays over 400ms.
- Top band: `✓ RESTED 2:30` in success green.
- Disc: **filled** — `START` over the next set's `175 × 8`.
- Bottom band: `SET 6 OF 6 · B/O`.
- This is the whole "next button appears" behaviour — a state change, not a new screen.
  Never buzz twice, never nag.

### 6 · Timed hold (planks, carries)
- Disc: **outlined** — `0:28` counting up (58px) over `GOAL 0:45`.
- Exercise ring: the only screen where the arc **fills** rather than drains.
- At goal: one buzz + auto-tick, then straight to Rest.
- Bottom band: `SET 2 OF 3`.

### 7 · Day done
- Day ring closes fully green. **The inner ring is gone** — the only screen with one ring.
- Disc: **filled green**, `DONE` (40px, dark text `#0D0D0F`) over `38 MIN · 12,450 LB`.
- Bottom band: `✓ SYNCED · 21 SETS`.
- Stats come from real logged data (active time from the set timestamps, volume from the sets).

---

## 6. Crown layer (option `2c`)

**Peek** — rotating the crown during a set scrubs the round segments. A **white** marker
shows where you're looking; the accent marker stays where you are. The disc dims to 62%
and shows that set's logged result (`225×5`, `TOOK 0:52`). Bottom band: `↺ RELEASE TO RETURN`.
Read-only: no tap action while peeking. This is where per-set times surface.

**Undo** — long-press (700ms) on a logged set's disc. The disc's own ring fills as a progress
indicator around a `UNDO / SET 5` label; releasing early cancels. Decision: **one confident
tap locks a set in**; undo is deliberate. Do not implement tap-again-to-undo.

**Swap** — crown flicks between the alternates the *phone already prescribed* for that
exercise. Disc shows the alternate's name with `USE THIS`; bottom band `1 OF 2 ALTERNATES`.
The watch never invents alternates and never re-plans; a swap applies to today only and is
sent as an edit for the phone to reconcile.

---

## 7. Edge states (option `2d`)

**Ambient** — same dial, burn-in safe: outline-only arc in `#4E4E55`, no accent, no filled
shapes, dim gray type. Shows `RESTING`, `DAY A · 12/21`, and the time during a rest.
Repaint at minute cadence. A rest or timed hold schedules one exact wakeup at its
deadline for the haptic; the interactive countdown remains composition-driven.
Punctuality holds while the screen is lit or ambient; true Doze (screen off,
still) defers exact alarms — as it also ignored the old design's wake lock.

**Phone away / offline** — top band shows a pulsing dot + `2 QUEUED`. Status lives in the
top band and nowhere else. Logging and timers work fully offline; deltas flush on reconnect.

**Loading** — the spinner is a single accent segment sweeping the day rim. Disc shows the
wordmark. No dots, no new vocabulary.

**Empty (no program)** — disc has a **dashed** border, `NO PROGRAM` + `set up on your phone`.
Day ring is drawn but fully gray. Setup stays phone-only by design.

**Interactive time pill** — every lit face whose bottom band is free shows the localized short
wall clock as straight BAND-size tabular text in a quiet, borderless raised-surface capsule,
centred at the bottom pole (`r=120px`, `24px` high, `10px` horizontal padding at reference
size). The pill yields whenever workout content owns `bottomBand`, never the reverse. It updates
at the next minute boundary and once per minute thereafter. Ambient never composes the pill: it
already carries time and permits neither filled shapes nor continuously moving pixels.

This resolves issue #167 deviation 1 for interactive faces: elapsed workout time keeps
primacy in its band, while faces that previously omitted `TimeText` now carry wall time via
the yielding pill. Ambient retains its existing centre-or-bottom-band wall clock treatment.

---

## 8. Motion & haptics (option `2e`)

| Event | Motion | Haptic |
|---|---|---|
| Tap START | disc scales 1.0 → 0.94 → 1.0 (140ms) and hollows out; elapsed fades into the top band | light click |
| Tick | disc collapses to its ring stroke; current round segment snaps green sweeping clockwise (220ms) | confirm |
| Rest begins | segmented ring dissolves into one continuous arc (180ms), then drains in real time | — |
| Rest ends | arc re-segments; disc fills with a 10px halo bloom decaying over 400ms | one long buzz, once |
| Timed hold reaches goal | arc completes | one long buzz, then auto-tick |
| Day complete | inner ring retracts to center and vanishes; outer ring closes; disc flips green | confirm |

No slide transitions, no cross-fades between "screens", no spring overshoot on the rings.

---

## 9. Implementation notes

**One composable renders every screen:**

```kotlin
@Composable
fun Dial(
    dayProgress: Float,          // 0f..1f  → continuous outer arc
    rounds: List<RoundState>,    // DONE | CURRENT | UPCOMING | PEEKED → inner segments
    restArc: Float? = null,      // non-null → inner ring is a draining/filling arc instead
    topBand: BandContent,
    bottomBand: BandContent?,
    disc: DiscState,             // Filled | Outlined | Flat | Dashed | Dimmed | Complete
    onDiscTap: () -> Unit,
    onDiscLongPress: () -> Unit,
)
```

- Two `Canvas` arcs + a `Box` with the disc. No `ScalingLazyColumn`, no nested scroll,
  no `Scaffold` vignette fighting the rings.
- Derive every radius from the measured diameter so it holds on 41mm and 45mm alike.
- Timers are **watch-local UI state**. Never synced, never persisted to the wire. Survive
  process death via a start-timestamp in local storage, not by ticking in a service.
- Keep the existing architecture: phone is source of truth, watch is read-only over the
  program, snapshot echo reconciles, **cascade/progression math never runs on the wrist**.

**Wire protocol — 3 additive fields (option `1f`):**

```
WatchSet     + restSeconds: Int          // phone-computed from heaviness + set kind; 0 = no timer
WatchSet     + holdSeconds: Int?         // TIMED goal; watch runs the countdown
SetEditDelta + startedAtMillis: Long?    // stamped by START
SetEditDelta + completedAtMillis: Long?  // stamped by the tick
```

The start/complete delta is the calorie-calculation input: active time per set is
`completedAt − startedAt`; rest taken is the gap to the next `startedAt`. Both are derived
on the phone from timestamps — the watch sends facts, not calculations.

---

## 10. Acceptance criteria

1. No element renders outside the four zones in §2, at 41mm and 45mm, with the longest
   exercise name in the catalogue.
2. Exactly one accent ring segment on screen at any time.
3. Exactly one tap target in the workout flow (the disc), ≥176px at reference size.
4. Rest starts automatically on tick; ends with exactly one buzz; the next action appears
   in place without navigation.
5. A set can be locked in with one tap and undone only by a deliberate long-press.
6. Every logged set carries a start and complete timestamp.
7. The whole flow works with the phone disconnected, and flushes on reconnect.
8. Ambient mode draws no filled shapes and no accent color.
9. No `ScalingLazyColumn`, no cards, no rectangles in the workout flow.
