# strength.log — The journal (issue #102)

The Log screen records history; it doesn't let you *read* it. A paper training
journal earns flipping-back — the story of a lift climbing toward its goal is
the emotional core of this program, and right now it's invisible. This brief
turns the Log screen into the journal, and gives the cascade the one moment of
ceremony this app will ever allow itself.

Scope: `:app` (ui/log, ui/day for the ceremony) + pure derivations. No schema
changes — everything below is derived from `WorkoutSessionEntity` /
`SessionSetEntity` / program state that already exists. No wear/ changes.

---

## 0. Color rules (checked, not felt)

The seven `DayAccentColors` were validated as a *chart* palette against
`#0D0D0F` and fail (adjacent-hue separation, contrast for B/D/E/F). They are
identity accents, not series colors. Consequences, non-negotiable:

- **Never two day accents as competing series on one chart.** Every chart
  below is single-series small-multiples. No legends anywhere (a single series
  is named by its title).
- **Line/text marks use the day's *bright* accent variant** (the text-on-dark
  mapping the wear bands and day-screen pills already use — reuse that source;
  never invent hexes). Fills at cell size may use the base accent **only**
  with a visible letter/label on or beside them — color is never the only
  carrier.
- Success green keeps its meaning (done) and is never a series color.
- Values, axis labels and captions wear text tokens (primary/secondary/faint),
  never the accent.

## 1. The journal screen

The Log route becomes the journal: a `LazyColumn` of four sections. Existing
behavior (session rows, expansion, Health Connect section, bodyweight prompt)
is preserved — sections 1–3 land *above* it. Section headers in the app's
existing quiet caps style.

### 1.1 TRAJECTORY — one card per main lift

For each of the four mains (from program state), a card with a **step-after
line** of its top-set weight per session:

- Data: per session (ascending date), the heaviest *done* set of kind TOP for
  that exercise, in display units. Sessions without the lift are skipped.
  Fewer than 2 points → the card shows the current prescription and
  `GOAL n` as text, no plot.
- Mark: 2px step-after line in the lift's home-day bright accent. 8px round
  markers only where the weight sets a new all-time high (a progression, the
  visible click of the engine). No marker elsewhere.
- **GOAL reference line**: 1px dashed, `TextTertiary`, labeled `GOAL 235` at
  its right end — the current goal from program state. When the trajectory has
  met it, the label turns success green — quietly, no animation.
- Direct labels: the latest point's weight only (condensed numerals). Nothing
  else is labeled; y-axis is at most two faint gridlines with faint values,
  x-axis is unlabeled (the card caption carries `12 SESSIONS · SINCE MAY 4`).
- Canvas-drawn in Compose; geometry derived from measured size; no chart
  library dependency.

### 1.2 VOLUME — weekly tonnage

One bar chart, last 12 ISO weeks including this one:

- Data: Σ (weightLb × reps) over *done* sets per week, display units.
  Weeks with no training render an empty slot (gap in the rhythm is
  information), not a zero-height bar artifact.
- Marks: thin bars, ≤12dp, 2px gaps, 4px rounded top, flat baseline. Single
  hue: the app ember accent `#C1440E` — via its existing token, not a literal.
- Direct labels: the max week and the current week only (`12.4K`), faint.
  Nothing on every bar.

### 1.3 CALENDAR — the month grid

Current month, 7-column grid, chevrons to page back (no paging forward past
the current month):

- A trained day: a filled pill in that session's day accent carrying the day
  *letter* in its `onAccentHex` — letter always present (identity is the
  letter, the accent is flavor). Multiple sessions a day: the letter of the
  first, a small dot for more.
- Untrained days: faint numeral only. Today: hairline ring in TextSecondary.
- Tap on a trained day scrolls the session list to that session (if loaded) —
  best-effort, no new state.

### 1.4 Sessions + everything existing

Unchanged, below the new sections.

## 2. Cascade ceremony

When DONE-advance fires a cascade (goal met → new goal, new ramp), that is the
payoff of the entire progression engine — currently silent. Once, at that
moment, the app marks it:

- **Where**: the DONE flow in the day screen's ViewModel already computes the
  advance; surface its result as a one-shot UI event carrying (lift name, met
  goal, new goal) — derived from before/after program state, stored nowhere.
- **What**: a full-screen scrim over the day screen — `background` at ~94%,
  centered: the lift's name in caps (secondary), the met goal in display
  numerals with a strike drawn through it, then the new goal large in the
  day's bright accent, caption `NEW RAMP` (tertiary). One `CONFIRM` haptic on
  entry. Tap anywhere dismisses; back dismisses. No confetti, no particles, no
  emoji — the strike and the new number are the whole event.
- **Once means once**: the event fires on the transition only. Process death
  during the scrim loses it — it is a moment, not data, and must not be
  persisted, replayed, or queued.
- If multiple lifts cascade on one DONE (possible on a deload edit), stack the
  content lines in one scrim; never show two scrims.
- The journal shows the aftermath permanently: the trajectory card's new-high
  marker and moved goal line are the durable record. No badge shelf, no
  trophy case.

## 3. Derivations

All chart/calendar/trajectory data building is pure Kotlin in the log package
(builder-style, like `LogScreenBuilder`), unit-tested: trajectory series +
new-high markers, goal-met coloring, weekly buckets with empty weeks, calendar
cells, ceremony event derivation (met/new goal pairs). Composables draw
prebuilt models and stay dumb.

## 4. Acceptance

1. Journal sections render from real session history; each derivation has unit
   tests including empty-history (new install: sections collapse to nothing —
   the session list's existing empty state leads).
2. Color rules in §0 hold everywhere (review check).
3. Ceremony fires exactly once per cascade transition, never on restore,
   restart, or re-entering the day screen; unit test the event derivation.
4. Session list, Health Connect, bodyweight prompt behave exactly as before.
5. Spec §11 pinned tests untouched; goal math only *read*, never recomputed;
   no schema/storage changes; no new dependencies.
