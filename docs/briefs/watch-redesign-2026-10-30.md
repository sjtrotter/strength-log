# Watch redesign, 2026-10-30

> **Changed by the 2026-10-31 product council** (`product-council-2026-10-31.md`, owner
> decided). In this brief:
> - The watch runs a whole prepared workout: corrections, lift choice and offline Finish.
> - Logging is a tap with the Undo chip, not a hold. A wrist test comes before the dial goes.
> - Superset rounds are logged one exercise at a time (#343).
> - GPS distance during outdoor cardio is planned (#284).
> Tracked in #346.

A ground-up proposal for the Wear OS app, written with the `ux-design` skill against
`DESIGN.md` (repo root, this worktree). It starts from the owner's two sentences: "the
current design seems too... busy? too much on the screen maybe?" and "I'm not sure the
watch is really workable as-is." The same-day audit (`ux-audit-watch-2026-10-30.md`) fixed
the dial in place; this brief asks whether the dial should stay at all, and answers no.
Revised after council round 1; the last section lists what changed.

Drawings of every screen, today-versus-redesign pairs for the four main moments, and the
densest faces at 192 dp are in `watch-redesign-2026-10-30-mockups.html` next to this file.
Paths below are relative to `wear/src/main/kotlin/cloud/trotter/log/strength/wear/` unless
they start with a module name.

## The concept in one paragraph

The watch shows one set at a time on a face that never scrolls: the lift's name, the load
to put on the bar in big numerals, and one line saying where you are in the lift. A single
Wear Material 3 `EdgeButton` at the bottom does the one thing the moment needs. Anything
that writes a record (log, start a timed effort, stop it, save a distance) arms after a
short hold and acts on release, so a brush or a doubled tap writes nothing. After every log, an Undo chip
sits at the top of the next face. A ring around the edge appears only when a clock is
running, so the edge always means "time". The rest of a lift (its sets, the next lift,
swap) is on a separate page behind the lift's name. The concentric dial, its rings of day
and set segments, the curved caps bands, the crown peek and the swipe-left gestures go.

## Why the current watch is busy

The dial packs four timescales into concentric rings and asks every face to carry all of
them. Counted from the CI renders in `.shots/wearScreenshots/` and the state code:

| Moment | Today: what one glance has to parse | Redesign |
|---|---|---|
| Overview (`01-overview.png`) | 4 cycle segments in 4 hues, 4 curved labels (DAY A to DAY D), 2 exercise-ring segments, the curved day title, a white disc, START, the lift name on 2 lines, the clipped time pill. 16 things, 8 text runs in 3 orientations, eight or more colors | Time, "Day A", "Squat focus", the lifts chip, the Start button. 5 things, 1 accent |
| A set (`02-set.png`, lifting face) | 4 cycle segments, 4 day labels, the green day-progress arc, 6 set segments (which render as 3 arcs, audit item 2), the status dot, the elapsed clock, the outlined disc, 190, ×5, TAP TO LOG, SET 3 OF 6. 22 things, eight or more colors. And this is the second of two faces per set: READY with START comes first (`ui/DialState.kt:390-410`, `:469-488`) | Time, the lift chip, 190, × 5, "Set 3 of 6 (R3)", Hold to log. 6 things |
| Rest (`03-resting.png`) | 4 cycle segments, 4 day labels, the progress arc, 6 set segments, REST, 1:30, NEXT 190 × 5, TAP TO SKIP, the disc, and a clock ring that does not render (audit item 3). 20 things | The draining edge ring, the Undo chip, "Squat, set 4 of 6", 1:24, "Next 190 × 5", Skip rest. 6 things |
| Timed hold (state code, `ui/DialState.kt:490-515`) | The same 8 cycle items and progress arc, 3 set segments, PLANK · 2, the outlined disc, 0:32, GOAL 0:45, SET 2 OF 3, an invisible clock ring. 17 things | The filling edge ring, "Plank", 0:32, "of 0:45", Hold to stop. 5 things |

Four more things make it harder than the count suggests:

- **The rings change meaning between faces.** The outer ring is the program's days; a
  green arc on today's segment is today's progress; the inner ring is lifts on the
  overview and sets in the workout; a third ring on the disc rim is a clock; a white
  marker means "you are looking here" in a peek or a day browse. Five meanings for
  concentric arcs, which the lifter has to tell apart at arm's length.
- **Most of the input is invisible.** The READY face accepts a tap (start), a 700 ms hold
  (undo, `ui/Dial.kt:916`), a crown turn that means peek on one lift and swap on another
  (`ui/DialState.kt:404`), a swipe left (next lift, `ui/WearApp.kt:856-865`) and a swipe
  right (back). Only the tap is labeled.
- **The state model is wide.** Twelve `DialScreen` values, six disc styles, six tone roles
  and seven text roles (`ui/DialUiState.kt:20-36`) describe what is, for the lifter, three
  moments: about to lift, resting, done.
- **Logging a set takes two taps** where the phone takes one (audit item 1). The lifting
  face exists to receive the second tap, and it shows its biggest numeral when the lifter
  is under the bar and not looking.

The day's identity (letter, color, rotation position) and progress are worth knowing, but
on the wrist they are known already: the lifter picked the day on the phone or walked in
knowing it. They earn one line on the first screen and nothing after that.

## The lifter and the moments

The reference user is about forty, trains alone for maintenance, and looks at the watch
with a wrist turn of one or two seconds, at arm's length, often with chalk on the hands.
Between sets (one to three minutes) the eyes are free and the hands are loading plates.
Under the bar the eyes are off the watch entirely, and the wrist may be bent hard against
the watch (bench press, push-ups, a high plank). With the wrist down the screen goes to
ambient. Near vision starts to decline in the forties, so the number that matters has to
be large and the rest has to stay out of its way.

| Moment | What the lifter needs to know or do | Screen and its one job |
|---|---|---|
| Walking in, or picking up after a break | Which day this is, and the way in | **Today**: say which day it is and start it |
| Loading the bar | The load and reps for the set in front of them | **Set**: show the next set and log it when it's done |
| Racking the bar | Record it, start the rest | Same face, one press |
| Between sets, glancing | Time left, which set is next, its load | **Rest**: say when the next set is due and what it is |
| A wrong log | Take it back at once | **Undo chip** on the face after every log |
| Station taken, out of order | Next lift, swap, look back | **Lift page** behind the lift's name |
| Plank, carry, timed window | Time the effort | **Timed hold**, **Timed reps**: time the effort and log it |
| Treadmill or road | The current interval and its clock | **Cardio**: run the current interval |
| Last set racked | That it counted | **Day done**: confirm the day is logged |
| Wrist down | Time, and whether a rest is running | **Ambient**: keep the time and the rest visible, burn-in safe |
| Phone in the locker | That logging still works | **Queued** status in the time text |
| First launch, or phone missing | What is wrong and the one fix | **Phone states**: say what is missing and offer the fix |

## Screen inventory and flow

Destinations in a `SwipeDismissableNavHost`: Today, Lifts (the day's list), Workout, Lift
page, Swap, Cardio page. The Workout destination is one face whose content follows the
derived state, as the dial does today (`ui/DialState.kt:202-216`). No workout face scrolls;
the list destinations are plain `TransformingLazyColumn` pages with no `EdgeButton`. Swipe
right goes back one level; on Today it leaves the app.

| From | Event | To |
|---|---|---|
| App launch, nothing logged today | | Today |
| App launch, something logged today | | Workout (the implied face rule, `ui/DialState.kt:88-93`, kept) |
| App launch, no snapshot yet | | Loading, then a phone state after 10 s |
| Today | Start / Continue (tap) | Workout, on the first lift with work left |
| Today | Tap the lifts chip | Lifts page; a row opens that lift |
| Workout: Set | Hold to log | Rest (if the set carries a rest), else the next Set; Undo chip on top either way |
| Workout: Set, the last set of the day | Hold to log | Day done, with the Undo chip |
| Workout: Rest | Timer ends (one buzz) | The next set's face: Set, Timed hold ready, Rep window ready or Cardio ready |
| Workout: Rest | Skip rest (tap) | The same next face, at once. Nothing is logged |
| Any face with the Undo chip | Tap the chip | The undone set's face, which is the confirmation |
| Workout face (Set, or a running clock) | Tap the lift chip | Lift page: Undo last set, sets, Next lift, Swap lift, Cancel set; the Cardio page for cardio |
| Lift page, untouched lift with alternates | Swap lift | Swap list; a tap there sends the swap and returns to Set, waiting on phone |
| Timed hold ready | Hold to start | Hold running |
| Hold running | Goal reached (buzz, logs itself) or hold to stop | Rest or next face |
| Rep window ready | Hold to start | Window running |
| Window running | Time's up (buzz) or hold to stop | Rep count |
| Rep count | Choose a count, hold to log | Rest or next face |
| Day done, cardio offered | Start cardio (tap) | Cardio ready |
| Cardio ready | Hold to start | Interval running |
| Timed interval running | Time ends, or hold to stop (shortened) | Next interval; recovery and cool-down start themselves, as today; a shortened set first asks its distance when the set has a distance aim |
| Distance interval running | Hold to end | Dialog: Reached (finished) or Stopped short (shortened, then distance entry) |
| Cardio page | End cardio | Alert dialog, then Cardio done |
| Workout | Swipe right | Today; a running rest keeps running and buzzes |
| Any lit screen | Wrist down | Ambient; wrist up returns |

## Tap safety

The council's first finding was that the first draft let a doubled tap on the rest face log
a set nobody lifted. The rules below replace that design. Every one is a requirement for
the build, with a unit test where it is pure.

1. **Anything that writes a record is a hold, not a tap.** Log set, Start for timed work
   and cardio (a started hold logs itself at the goal), the rep count, the distance, and
   stopping a running clock. While a press is held, the button's container darkens from
   the left. At 0.4 s (1 s to stop a running clock) it arms: the container is full, a light
   tick plays, and the label reads "Release to log" ("Release to start", "Release to
   stop"). Release commits, with the existing confirm cue. Releasing before it arms writes
   nothing. Holding past 3 s disarms it: the fill drains, the label returns, and release
   then writes nothing, so a wrist resting on the edge does nothing.
2. **The instruction is always on the button.** Every hold button's label starts with
   "Hold to" ("Hold to log"); where it does not fit on one line (192 dp at scale 1.3) it
   wraps to two. A tap shorter than the arming time flashes the label and gives one short
   buzz.
3. **Everything else is a tap**, because a stray tap there writes nothing: Today's Start,
   Skip rest, the lift and lifts chips, Retry, Close, the phone-state buttons.
4. **The rest face never logs.** Its button is Skip rest. Today's second tap on the rest
   face is SKIP_REST too (`ui/WearApp.kt:900-906`).
5. **A press must begin on the face it acts on.** A press that started before the face
   changed (rest ending under a finger, a skip landing on the Set face) is ignored.
6. **Undo is on screen after every log.** The face that follows a log carries a tonal
   `CompactButton` at the top, "Undo 190 × 5" (just "Undo" on a 192 dp watch): for the
   whole rest; for 10 s on a Set face that follows a set with no rest; until the app closes
   on Day done. One tap undoes the existing `UndoTarget` (the day's most recent tick), and
   the face it returns to, that set's Set face with the set back to "Now", is the
   confirmation.

What each move costs, against today:

| Move | Today | Redesign |
|---|---|---|
| Log a set | 2 taps (START, TAP TO LOG) | One press, armed at 0.4 s, released |
| Go early during a rest | 3 taps (skip, START, TAP TO LOG) | A tap on Skip rest, then one press |
| Undo the last log | One 0.7 s hold on the disc, invisible | One tap on the chip |
| Start a timed hold | 1 tap | One press, armed at 0.4 s, released |
| Stop a running clock | A hold on the disc | One press, armed at 1 s, released |

Traced: a doubled tap on Log set does nothing, because neither tap arms. A doubled tap on
Skip rest skips once, and the second tap lands on the Set face as a short tap, which only
flashes the label. A sleeve brush is a slide or a short contact and does nothing. A wrist
pressed against the bottom edge through a set stays down past 3 s and disarms. What
remains: a contact that arms and lets go within 3 s without the lifter meaning it, which
the wrist tests must measure, and which the Undo chip fixes in one tap. The chip has the
mirror risk: a stray tap on it undoes the last set. That is visible at once (the set is
"Now" again) and costs one press to put back.

Logging on a tap instead is owner question 11.

## Layout grid

All drawings are for the 454 px CI face, which renders at density 2.0, so the screen is
227 dp across and 1 sp is 2 px at the default font scale. The old dial scaled its 384 px
reference canvas to the measured face, which is 1.18 on this one, so a dial size in
reference px times 1.18 is its size in these drawings; the type table gives both, for
comparison. Wear Material 3 sizes are in dp and sp instead, so a 384 px Pixel Watch 2
(192 dp, also density 2.0) draws the same text at the same pixel size, taking 1.18 times
more of its face. The height budget below sets what each face keeps there.

| Zone | Where on the 454 face | Component |
|---|---|---|
| Time | Curved at 12 o'clock, baseline about 30 px in from the edge | `TimeText` |
| Top chip (Undo after a log) | Centered at y 66, or y 84 inside a ring | `CompactButton`, tonal |
| Title line or lift chip | Centered at y 124 | `CompactButton`, tonal, with a chevron; plain `titleMedium` text when an Undo chip is up |
| Hero | Baseline at y 252, centered | `Text`, `numeralExtraLarge` |
| Detail lines | Baselines at y 300 (and 314 for a second line, with the hero moved up) | `Text`, `bodyLarge` |
| Action | Top edge 116 px above the bottom, bottom following the screen edge | `EdgeButton`, medium size, inside a plain `Box` |
| Edge ring, only while a clock runs | Radius 213, stroke 10 px, open at the bottom where the button sits | `CircularProgressIndicator` with a start and end angle |

Type sizes as drawn, with the role each comes from:

| Role | Use | px at 454 | sp at density 2.0 | Nearest dial role today, px at 454 |
|---|---|---|---|---|
| `numeralExtraLarge` (Barlow Condensed Bold, tabular) | The load, the countdown, the hold | 120 | 60 | `NUMERAL_LARGE`, 72 ref px, 85 |
| `numeralMedium` (Barlow Condensed Bold) | The reps after the load ("× 5") | 64 | 32 | `DISC_LABEL`, 36 ref px, 42 |
| `displaySmall` (Barlow Condensed SemiBold) | Day done headline, the day title on Today | 52 to 56 | 26 to 28 | `DISC_LABEL`, 42 |
| `titleMedium` (system font, medium) | The lift name, "Day A" | 34 | 17 | `BAND`, 26 ref px, 31 |
| `labelMedium` (system font, medium) | EdgeButton, chip and list button labels | 28 to 30 | 14 to 15 | `BAND`, 31 |
| `bodyLarge` (system font) | The detail line | 30 | 15 | `BAND_SECONDARY`, 24 ref px, 28 |
| `bodySmall` / arc text (system font) | TimeText, list secondary lines | 24 to 26 | 12 to 13 | `CYCLE_LABEL`, 18 ref px, 21 |

The smallest text on any lit screen is 12 sp (list secondary lines), at the floor. Every
system-font role follows the font scale. The hero numerals are fitted to the height left
(Compose `BasicText` `autoSize`) and never go below 36 sp.

### Height budget

The faces do not scroll, so each one has to fit. Measured in the drawings, px at density
2.0:

| | 227 dp, scale 1.0 | 227 dp, scale 1.3 | 192 dp, scale 1.0 | 192 dp, scale 1.3 |
|---|---|---|---|---|
| Top of content (below `TimeText`) | 52 | 60 | 52 | 60 |
| Lift chip | 64 | 66 | 64 | 66 |
| Top of the `EdgeButton` | 338 | 338 | 268 | 268 |
| One detail line | 38 | 49 | 38 | 49 |
| Hero with one detail line | 120 (60 sp, capped) | 112 (56 sp) | 98 (49 sp) | 80 (40 sp) |
| Hero with two detail lines | 112 (56 sp) | about 76 (38 sp) | about 56, under the floor | does not fit |
| Rest countdown (Undo chip, title, countdown, next line) | 120 (60 sp) | about 100 (50 sp) | 86 (43 sp) | 57 (28 sp) |

So a 227 dp face keeps two detail lines at either scale, and a 192 dp face keeps one. The
rest face keeps all its lines everywhere and lets the countdown shrink instead, down to
28 sp at 192 dp and 1.3, because the next lift and "set 4 of 6" are what the lifter reads
the rest for, and the draining ring still shows the time at any size. When
only one fits, it is chosen in this order:

1. what the lifter has to do that the hero does not say: the superset partner ("+ Cable row
   90×12"), the phone's authored detail ("Each side · Red band"), or the cardio aim;
2. the position ("Set 3 of 6 (R3)").

The dropped line is on the lift page, one tap away, and the rest face before the set
showed the next set's lift and position (it always does; see Rest). A line too wide for the chord ellipsizes
(the authored detail at 192 dp and 1.3 reads "Each side · Red b…"); the full text is on
the lift page. At 192 dp and 1.3 the button label wraps to two lines ("Hold to" over
"log"), so the hold instruction is on the button at every scale. The mockups draw superset, authored detail, cardio and
rest at 192 dp at both scales. The phone could send a short form of long authored detail
later; that is not in this plan.

## Screens

Copy follows the house rules: sentence case, caps only for set notation (R1, TOP, B/O),
one verb through a flow, facts rather than advice, and no dot-joined meta strings: the
middle dot appears only inside `TimeText`, where it is the platform's separator, and inside
detail the phone formats. The app's "Day A, done." grammar from the phone carries over.

### Today

One job: say which day it is and start it.

```
          10:08              TimeText
         Day A               titleMedium, accentBright
      Squat focus            displaySmall, onSurface (2 lines max)
  ( Squat, Incline DB  › )   CompactButton, tonal: opens the Lifts page
     ┌───────────────┐
     │     Start     │       EdgeButton, filled (primary), a tap
      ╲_____________╱
```

- Data: `WatchDay.dayId`, `title`, `exercises[].name` (`domain/.../sync/WatchSnapshot.kt`).
- Start is a tap: it opens the workout and writes nothing. It also stamps the session's
  start on the watch (see owner question 3).
- Once a set is logged: the chip reads "4 of 9 sets" and the button says Continue.
- With a rest running (lifter swiped back): the chip reads "Rest 1:24", ticking.
- Lifts page: `ListHeader` "Day A lifts", then one `FilledTonalButton` per lift with "6
  sets" or "2 of 6 done" as its secondary label. A superset row reads "Incline DB", "with
  Cable row, 3 rounds"; a cardio block adds "Run", "Cardio, 6 sets". A row opens that lift.
  This replaces the overview's crown selection.
- States: no program, empty day, phone away and a stale snapshot are under "Phone and
  first-run states".

### Set (weighted and reps)

One job: show the next set and log it when it's done.

```
          10:08
       ( Squat › )           CompactButton, tonal: opens the Lift page
      190 × 5                190 numeralExtraLarge + "× 5" numeralMedium in onSurfaceVariant
   Set 3 of 6 (R3)           bodyLarge, onSurfaceVariant
     ┌───────────────┐
     │  Hold to log  │       EdgeButton, filled (primary): arms at 0.4 s, logs on release
      ╲_____________╱
```

- One press logs the set and starts its rest: the audit's item 1, adopted, with the hold
  from "Tap safety". `tick()` already accepts a set with no start stamp
  (`ui/WearApp.kt:574-635`); `startSet()` stays only for timed holds and rep windows
  (`ui/WearApp.kt:530-562`).
- Reps-only lifts: the hero is "× 12" alone.
- Superset round: lift chip "Incline DB", hero "50 × 12", detail "+ Cable row 90×12" (the
  phone's `SetFormatter` summary), then "Round 2 of 3". One press logs the round, as today.
- Authored detail (`WatchExercise.targetDetail`, "Each side · Red band", formatted by the
  phone) is the first detail line, the position the second (audit item 12).
- Prescribed lifts show the round (the load and reps), not the goal label, so the face
  always says what to load (audit item 12).
- Queued edits: `TimeText` reads "2 queued · 10:08" in `onSurfaceVariant`. No dot, no
  motion.
- Just after a set with no rest: the Undo chip replaces `TimeText` for 10 s and the lift's
  name is plain text under it; then the lift chip returns.
- Haptics: a light tick when the press arms, the existing confirm cue on release
  (`ui/DialHaptics.kt`).

Lift page (behind the lift chip), a `TransformingLazyColumn` with no `EdgeButton`:

1. `ListHeader` with the lift's name, then `FilledTonalButton` "Undo last set", secondary
   "Squat R2, 165 × 5", first, for when the chip has timed out. One tap, like the chip.
2. One read-only row per set: "R1  135 × 5" with a check icon and "Done" for logged sets,
   "Now" on the current one, nothing on the rest. Done is a check plus the word, never
   green alone. This replaces the crown peek.
3. `FilledTonalButton` "Next lift", secondary "Incline DB". Only when another lift has
   work left.
4. `FilledTonalButton` "Swap lift", secondary "3 alternates". Only on a lift with nothing
   logged and alternates from the phone (the existing offer rule,
   `ui/DialState.kt:381-384`).
5. On a running rep window: "Cancel set", which discards the attempt as today's hold does
   (`ui/WearApp.kt` `STOP_TIMED_REP`).

### Rest

One job: say when the next set is due and what it is.

```
     ╭─ ring drains ─╮       CircularProgressIndicator, open at the bottom
     ( ↶ Undo 190 × 5 )      CompactButton, tonal, for the whole rest
   Squat, set 4 of 6         titleMedium, onSurface: the next set's lift and position
         1:24                numeralExtraLarge, onSurface
     Next 190 × 5            bodyLarge, onSurface
     ┌───────────────┐
     │   Skip rest   │       EdgeButton, filled tonal, a tap; never logs
      ╲_____________╱
```

- The title line names the next set's lift and position; between lifts it is the next
  lift, "Incline DB, round 1 of 3". This puts back the position the first draft dropped
  (`wear-dial-v2.md` §0, point 3, made the same point about the old rest melt).
- The next line follows the next set's kind: "Next 190 × 5", "Next 0:45 hold", "Next 0:40
  window", "Next 4:00 run".
- Skip rest ends the rest and shows the next set's own face, which carries its own action:
  Hold to log for weighted and reps, Hold to start for timed holds, rep windows and cardio.
  The rest never logs anything, so a set that has a clock is always timed.
- When the rest ends: one buzz (`ui/RestTimerController.kt`, unchanged) and the next set's
  face replaces this one.
- No `TimeText` during a rest: the ring takes the edge (owner question 9).
- Ring: indicator `primary` (the bright day accent, 3.7 to 6.5:1 against the track), track
  `outlineVariant`. The track runs from the bottom left over the top to the bottom right;
  as the rest drains, the indicator's end moves back toward the bottom left.
- On a 192 dp watch the chip reads "Undo" and the countdown shrinks to fit (see the height
  budget); the title and next lines stay.

### Swap

One job: take one of the phone's alternates for this lift.

- From the Lift page. `TransformingLazyColumn`: `ListHeader` "Swap Squat for", one
  `FilledTonalButton` per alternate (`WatchExercise.alternates`, at most three,
  `app/.../sync/WatchSnapshotBuilder.kt:54`), and a closing line in `bodySmall`: "Your
  phone sets the new weights."
- A tap sends the swap. Swap is only offered on a lift with nothing logged, so it clears
  nothing, and the phone refuses anything it did not offer.
- After the tap, the Set face shows the new name in the lift chip over "Waiting on your
  phone" in place of the hero, with no button (the rows still belong to the old lift,
  `ui/DialState.kt:412-441`). The lift chip still opens the Lift page, so Next lift is
  always reachable.

### Timed hold

One job: time the hold and log it.

- Ready: lift chip "Plank", hero "0:45" (the goal), detail "Set 2 of 3" (with ", +25 lb"
  for a loaded hold, `ExerciseStreamUiState.addedLoadDisplay`), button "Hold to start".
  Start is a hold because a started hold logs itself at the goal.
- Running: the ring fills toward the goal; the lift chip stays, so the Lift page is one tap
  away; hero is the elapsed time, "0:32"; detail "of 0:45"; button "Hold to stop", tonal,
  armed at 1 s. At the goal the watch
  buzzes once and logs the set itself, as today (`ui/WearApp.kt` `scheduleTimedHoldTick`).
- Stopping early logs the set. Owner question 6 asks whether it should record the seconds
  actually held; `SetEditDelta.seconds` already carries them
  (`domain/.../sync/SetEditDelta.kt:33`).

### Timed reps (rep window)

One job: time the window, then record the count.

- Ready: "Push-up", hero "0:40" (the window), detail "Aim 20, set 1 of 3" (the aim only
  when `repAim` is set), button "Hold to start".
- Running: the ring drains; the lift chip stays; hero the time left; button "Hold to stop",
  armed at 1 s, which ends the window early and goes to the count. Discarding the attempt
  is "Cancel set" on the Lift page, one tap on the chip away.
- Count: one buzz at time's up, then a `Picker` under the title "Push-up reps". It starts
  blank ("–"), never at the aim: `repAim` is "never a default count"
  (`domain/.../sync/WatchSnapshot.kt:199`). The button, "Hold to log", is disabled until a
  number is chosen. The count is sent as `reps` on the tick delta, as today. The aim, when
  set, shows in the title ("Push-up reps, aim 20") and is never preselected. The cost is a
  crown detent per rep, or a fling.

### Cardio

One job: run the current interval.

- Ready: activity in the chip ("Run"), hero the interval's end ("4:00" or "400 m"), detail
  "Work, set 2 of 6" and the aim when there is one ("aim 1:45"), button "Hold to start".
- Running, time-ended: the ring drains; the Run chip stays, with End cardio behind it;
  hero the time left; button "Hold to stop", tonal, armed at 1 s. Stopping logs the set as shortened with the time done (`CardioSetState.stop`,
  `ui/CardioSetState.kt:92-98`) and the block carries on: the next set is ready, or starts
  itself if it is recovery or cool-down (`ui/CardioSetState.kt:111-114`).
- Running, distance-ended: no ring (the end is a place, not a time); the Run chip stays;
  hero the elapsed time; detail "to 400 m"; button "Hold to end", armed at 1 s. It opens a stock `AlertDialog`, "End
  set 2?", with two buttons: "Reached 400 m" logs it finished at the distance
  (`reached`, `:84-90`), "Stopped short" logs it shortened (`stop`) and goes to distance
  entry; swiping the dialog away keeps running. This is the stop-early-and-continue path
  the first draft lacked.
- Distance entry stays on the wrist. After a shortened set, or a finished set with a
  distance aim (the cases that create an entry today, `ui/CardioSetState.kt:105`), a
  `PickerGroup` of three columns, thousands, hundreds and tens of meters, all starting
  blank, so it reaches 42,990 m and a shortened long run fits; the chosen distance is
  spelled out under it ("3,450 m"). "Hold to save" saves what is chosen; with all blank
  the face says "Not measured" and saving
  leaves the distance blank on purpose, as today's "Clear distance" does. The crown counter
  of one meter per detent (`ui/CardioSetDialState.kt:32-39`) goes.
- Cardio page (behind the Run chip): the block's sets with their status in words
  ("Finished", "Shortened, 350 m", "Now"), then "Skip set" and "End cardio". End cardio
  opens a stock `AlertDialog`: "End cardio?", "Keeps the sets you finished and skips the
  rest." This replaces the question inside the disc (`ui/CardioSetDialState.kt:40-45`).
- Cardio done, in four states that follow the queue (`ui/WearApp.kt:237-251`) and
  `ui/CardioSetDialState.kt:51-61`:
  - **Saving**: the result is built and `sendCardio` has not yet called back. "Run, done.",
    "5 of 6 sets", "Saving on the watch…". Nothing claims saved or sent until `onQueued`
    fires with the queue stamp.
  - **Sending**: `onQueued` has fired, so the result is in the durable queue on the watch,
    and the phone has not acknowledged it. "Run, done.", "5 of 6
    sets", "Saved on the watch, sending to your phone"; `TimeText` "1 queued". No button;
    leaving is safe because the queue is durable.
  - **Couldn't save**: `onFailure` fired, so the watch could not write its queue
    (`saveFailed`). "Couldn't save
    it", "Your sets are still here.", button "Retry" (a tap; it re-sends the same result).
  - **Saved**: the phone acknowledged the stamp. "Saved on your phone", button "Close",
    which returns to Today.

A blank distance saved on the watch stays blank: the phone offers "Enter distance" only for
its own runs (`app/.../ui/day/CardioPresentation.kt:269-275` gates on phone drafts). Phase 4
adds the phone path, so that every finished set with a blank distance, wherever it was
logged, can be filled in on the phone. Until then the watch's "Not measured" is the only
way a distance ends up blank, and it is chosen, not defaulted.

### Day done

One job: confirm the day is logged.

- The Undo chip for the last set at the top, until the app closes.
- "Day A, done." in `displaySmall`, then "9 sets in 48 min" in `bodyLarge`. Minutes only
  when the watch saw both ends of the session (`DialFormat.wholeMinutes`, unchanged). The
  volume line goes (audit owner question 3).
- A last line in `bodySmall`: "Finish it on your phone." The day only advances when the
  phone's Finish runs (`app/.../ui/day/DayViewModel.kt:1129`, `repo.advanceDay`); the watch
  cannot finish a day today. Owner question 5.
- No button, unless cardio is offered: then the button is "Start cardio" (a tap, to the
  Cardio ready face) and the third line names it ("Next: Run, 20 min").

### Ambient

One job: keep the time and the rest visible without burning in.

```
          10:13              numeralLarge, onSurfaceVariant on black
   Squat, set 4 of 6         bodyLarge, onSurfaceVariant
```

- Resting: the second line becomes "Rest, about 2 min" (whole minutes rounded up, under a
  minute "Rest, under a minute"). Ambient repaints once a minute, so seconds would lie and
  "about" says so. The buzz at the end of the rest still fires from the alarm, unchanged.
- Hold, rep window or cardio running: "Plank, running", "Run, set 2 of 6".
- Before anything is logged: "Day A".
- True black, text only, no ring, no button, no accent. Text in `#9A9AA2` (7.5:1 on black;
  today's ambient grays are 2.5:1 and 4.0:1, audit item 4). Low-bit screens use white.
  The ±2 px burn-in orbit stays (`ui/AmbientDial.kt:185-192`).
- On Wear OS a touch in ambient wakes the screen first. If it is not also delivered to the
  button underneath (to be confirmed in the wrist tests), that is one more guard against a
  wrist press.

### Phone and first-run states

Each is a Set-shaped face: a title, one line of what to do, one button. These buttons
write nothing, so they are taps.

| State | Title | Line | Button |
|---|---|---|---|
| Loading (first 10 s) | indeterminate `CircularProgressIndicator`, no text for 2 s, then "Looking for your phone" | | none |
| Phone unreachable | Can't reach your phone | Bring it closer and check Bluetooth. | Try again |
| App not on the phone | strength.log isn't on your phone | Install it there to plan your days. | Install, then `OpenOnPhoneDialog` |
| Phone app not running | Open strength.log on your phone | It sends today's plan to the watch. | Open on phone, then `OpenOnPhoneDialog` |
| Sync error | Sync isn't available | Try again in a moment. | Try again |
| No program | No plan yet | Set one up on your phone. | Open on phone |
| Empty day | Day A | No lifts in this day yet. | Open on phone |
| Stale snapshot, phone unreachable | Today face as normal | Third line: "Last synced yesterday" (or the weekday) | Start, as normal |

- Opening the phone app needs a browsable deep link on the phone (exported, opens the app
  and does nothing else), as the audit noted for item 6. Today the "open the phone app"
  state's tap retries (`ui/WearApp.kt:358-377`).
- "Last synced" needs a date the snapshot does not carry: it has `epoch`, the creation time
  of the phone's sync state, and `revision`, neither of which dates this publish
  (`domain/.../sync/WatchSnapshot.kt:23-85`). The phone adds one additive field,
  `publishedAtMillis`, defaulting to 0 like every other appended field; 0 means "unknown"
  and the line is not shown. The publisher stamps it with `revision` and `epoch`, after the
  content dedupe, which compares content with those stamps left at placeholders
  (`app/.../sync/TodaySnapshotSource.kt:31-32`, `app/.../sync/WearSyncPublisher.kt:94-101`;
  the size guard at `:94` sets it to its widest value too), so a new time never counts as
  new content. The line appears only when the phone is unreachable and that date is before
  today on the watch's calendar. A receive time kept on the watch was the
  first draft's answer; it is worse, because the Data Layer redelivers the cached item on
  every cold start and the watch would have to persist the time separately.

### Tile, complication and ongoing chip

- **Tile**: `protolayout-material3` `primaryLayout`, title "Day A", main text "Squat focus"
  and "4 of 9 sets", and a `textEdgeButton` "Start" or "Continue" that opens the app. The
  progress ring goes; in this design a ring means a running clock. The hand-built box in
  `glance/DayTileLayout.kt` goes.
- **Complication**: unchanged. The ranged value is drawn by the watch face, not by us.
- **Ongoing chip**: status "Rest 1:24" during a rest, else "Day A, 4 of 9 sets"; re-posted on
  each log and at rest start and end (audit item 11). Today it is posted once with a fixed
  title (`ui/WearApp.kt:1046-1048`, `OngoingWorkoutChip.kt`).

## What was cut, and what moved to the phone

| Today | Fate | Why |
|---|---|---|
| Cycle ring: a segment per program day, four to seven labels at 9 sp, today's green progress arc (`ui/Dial.kt:353-387`) | Cut | The lifter knows which day it is; "Day A" on Today says it in one word. Previewing other days is a planning job for the phone's Plan tab |
| Day browse by swiping left on the overview (`ui/DialState.kt:335-363`) | Moved to the phone (Plan) | A hidden gesture for a curiosity glance |
| Exercise ring: a segment per lift or per set (`ui/Dial.kt:387-421`) | Cut; position is text ("Set 3 of 6") | Segments need counting and their gaps did not render (audit item 2) |
| Curved caps bands, top and bottom | Cut; straight sentence-case lines | Caps cost reading speed; curved text is reserved for `TimeText` |
| The 204 px disc as the only target | Replaced by `EdgeButton` | One labeled button with an edge the thumb can find |
| Lifting face and two-tap logging (`ui/DialState.kt:469-488`) | Cut | One press after the set; nobody reads the watch under the bar |
| 700 ms hold on the disc to undo | Replaced by the Undo chip, one tap | An invisible gesture; the chip names the set it will undo |
| Crown peek at past sets, TOOK line (`ui/CrownLayer.kt`) | Replaced by the set rows on the Lift page; TOOK moves to the phone's history | A page with rows needs no hidden mode |
| Crown flick to swap (`ui/DialState.kt:837-853`) | Replaced by the Swap list | The same three alternates, named and tappable |
| Swipe left to the next lift (`ui/WearApp.kt:856-865`) | Replaced by "Next lift" on the Lift page | Unlabeled, and a brushed sleeve could take it |
| Rested badge, bloom (`ui/DialState.kt:542-563`) | Cut | The buzz and the next set's face say the rest is over |
| Time pill (`ui/Dial.kt:172`) | Replaced by `TimeText` | Stock, and it does not clip (#330) |
| Pulsing queued dot | Replaced by "2 queued" in `TimeText` | Motion for a queue that flushes itself |
| Session volume on day done | Cut (owner question 3 in the audit) | A number this lifter does not train for |
| Cardio distance counter, one meter per crown detent | Replaced by a three-column `PickerGroup` on the wrist | A meter counter is not workable mid-run; the phone cannot edit watch results yet |
| Cardio options and End block inside the disc | The Cardio page plus `AlertDialog` | Stock components with room for the words |
| Cardio peek at finished sets | The Cardio page | Same reason as the lift peek |
| Ambient day arc and "DAY A · 12/21" band | Cut to time plus one line | Ambient is a glance at the time and the rest |
| The loading sweep in day A's accent (`ui/LoadingDial.kt`) | Stock indeterminate indicator | No day exists yet, so no accent |
| `DialType`'s font-scale pin and the 9 sp waiver | Gone with the dial | Stock roles follow the font scale; nothing is below 12 sp |

Kept: Skip rest, the alarm-backed rest buzz, auto-log at the hold goal, the undo target
logic (`UndoTarget`, `TickMemory`), swap with its phone-side guard, the pending-edit queue
and its replay, the implied face on launch, timed reps, structured cardio with wrist
distance entry, ambient with burn-in protection, the tile, the complication and the chip.

Capability check against the brief: see today's day and what comes next (Today; the Rest
face's next line; Next lift on the Lift page), log weighted and reps sets (Set), undo (the
chip after every log), rest timer and skipping it (Rest), timed holds and timed reps,
cardio sets, working with the phone away (the queue is untouched, its status moves to
`TimeText`), ambient. Nothing is dropped.

## Wear Material 3 components

The module moves from `androidx.wear.compose:compose-material` to `compose-material3` on
the same release line as the `compose-foundation` 1.6.2 it already uses
(`gradle/libs.versions.toml:9`), plus `compose-navigation` for the nav host.

| Component | Where |
|---|---|
| `AppScaffold` | Around the nav host |
| `TimeText` (time, with a leading text part for "2 queued") | Every lit screen except running clocks and faces with an Undo chip on top |
| `EdgeButton` (filled, filled tonal and disabled colors) | The one action on each workout face, placed in a plain `Box` |
| `CompactButton`, tonal | The lift chip, the lifts chip, the Undo chip |
| `TransformingLazyColumn` inside `ScreenScaffold` with `SurfaceTransformation` | The Lifts, Lift, Swap and Cardio pages, none of which has an `EdgeButton` |
| `ListHeader`, `FilledTonalButton` | Rows on those pages. Tonal, not `Button`, whose default fill is `primary`: the accent stays on the one action |
| `CircularProgressIndicator` (determinate, with start and end angles; indeterminate for loading) | The edge ring; loading |
| `Picker`, `PickerGroup` | The rep count; the cardio distance |
| `AlertDialog` | Ending a distance set, End cardio |
| `OpenOnPhoneDialog` | After Install and Open on phone |
| `Text`, `Icon` | Everywhere |
| `SwipeDismissableNavHost` | Today, Lifts, Workout, Lift, Swap, Cardio |
| `protolayout-material3` `primaryLayout`, `textEdgeButton` | The tile |

The first draft put the workout face's extra rows in a list under the face and relied on
`ScreenScaffold` keeping the `EdgeButton` at the end of a scrolling list and collapsing it
on scroll. That behavior could not be checked offline, so this revision does not depend on
it: workout faces are fixed layouts in a `Box` with the `EdgeButton` aligned to the bottom,
and the lists are separate pages without one. Verify-first, in phase 1: that `EdgeButton`
renders correctly outside `ScreenScaffold` (shape, edge padding, size) on both watch
sizes. If it does not, the drawn fallback is `ScreenScaffold` with the `edgeButton` slot
and no scroll state, which these faces need anyway, since nothing on them scrolls.

Custom pieces, each with what M3 lacks:

- **Press-and-hold on the `EdgeButton`.** Wear Compose Material 3 has no hold-to-confirm
  button. The stock `EdgeButton` gets a pointer handler that arms at 0.4 s or 1 s, commits
  on release, disarms past 3 s, ignores presses that began before the face, swaps the
  label to "Release to …" when armed, and draws the container fill. The KDoc says so, and
  that the class goes if M3 adds one.
- **Ambient layout.** Wear Compose Material 3 has no ambient components. The ambient
  screen is a `Box` with two `Text`s on black and the existing burn-in offset; it uses the
  theme's type roles and no custom drawing.
- **The hero line.** Not a component: a `Text` with an `AnnotatedString` that sets "× 5"
  in a smaller numeral style beside "190". It needs no KDoc exemption.

The recorded deviations in `wear-dial-redesign.md` ("Deliberate deviations") each named
the condition for revisiting them. Local face state was tied to "one dial changing state
in place", the crown instead of list pickers to preserving "the single large tap target",
and the custom tile geometry to "the dial's concentric vocabulary". With the dial and its
single target gone, those reasons go with them and the stock pieces take over. No
Horologist and no Health Services still hold.

## Theme on the watch

Dark only, as today. The identity is the near-black field, Barlow Condensed numerals and
the day's earth accent on the one action; the components are stock.

| Role | Value | Note |
|---|---|---|
| `background` | `#0D0D0F` | Kept for identity; `#000000` saves a little on OLED (owner question 8) |
| `onBackground`, `onSurface` | `#F2F2F0` | 17.3:1 on the background |
| `onSurfaceVariant` | `#9A9AA2` | 6.9:1 on the background, 6.5:1 on `surfaceContainerLow` |
| `outline` | `#808086` | Replaces `TextTertiary` `#6B6B73` (3.7:1) with 4.95:1 |
| `outlineVariant` | `#2A2A30` | Ring track, dividers |
| `surfaceContainerLow` / `surfaceContainer` / `surfaceContainerHigh` | `#16161A` / `#1E1E23` / `#2A2A30` | Chips and list buttons on `surfaceContainer`; the tonal `EdgeButton` on `#2A2A30` with `#F2F2F0` text, 13:1 |
| `primary` | today's `brightHex` | Per day: A `#D2815D`, B `#728F7C`, C `#CCAC5B`, D `#698792`, E `#7C87A2`, F `#AF808C`, G `#9A9A71` |
| `onPrimary` | `#0D0D0F` | On the bright accents: 6.5, 5.5, 8.9, 5.1, 5.4, 5.8 and 6.7:1 for A to G |
| `error` / `onError` | `#C2334D` / `#F2F2F0` | Container use only; it fails as text on near-black (3.6:1) |

The proposal changes one recorded rule. Dial v3 made disc actions neutral white so the
accent stayed identity and never became a control (`wear-dial-v3.md`, #116). The phone has
since adopted "the day accent identifies the day and its one primary action"
(`DESIGN.md` house rules), and the raw accents could not carry a button on near-black (D
is 2.1:1). The bright accents can: as a fill they clear 5:1 against the background and
under dark text on every day, so the one filled button on each face carries the day and
nothing else does. If the owner prefers v3's rule, `primary` becomes `#F2F2F0` and nothing
else changes (owner question 2). Either way `WearTheme.kt:62`'s `primary = dayAccent(0)`
goes.

Typography: the `numeral*` and `display*` roles in Barlow Condensed with tabular figures;
titles, labels, body and arc text in the system font, which follows the watch's font
setting. This ends the font-scale pin (`theme/DialType.kt:83-114`) and resolves `DESIGN.md`
open question 7.

Motion: stock component motion only, plus the ring's real-time drain and the hold fill.
Haptics: a light tick when a press arms, a short buzz when a tap is too short to count, a
confirm when a hold commits, one long buzz at the end of a rest or hold, and an undo cue.

## Implementation cost and phases

Rough sizes: S is under a day of agent work plus review, M a few days, L a week or more.
The whole is L, about seven PRs. The data layer, the pending-edit queue, the rest alarm,
the timed-rep and cardio state machines, `UndoTarget` and `TickMemory` survive; about
1,800 lines of dial drawing and gesture code go (`ui/Dial.kt`, `ui/DialGeometry.kt`,
`ui/CrownLayer.kt`, `ui/CrownInput.kt`, `ui/RotaryDetents.kt`, `ui/LoadingDial.kt`,
`theme/DialType.kt`), and `ui/DialState.kt` (947 lines) is replaced by a smaller face
state.

Nothing replaces the shipped dial until the whole loop exists. The new UI is built beside
the dial and chosen by a Gradle property, `-PwatchM3=true`, read into a `BuildConfig`
field of the release build type; without it the release keeps the dial. Each phase merges
with its own tests and CI screenshots.

The switch cannot live in a debug build. The phone and watch apps must share both the
applicationId and the signing certificate for the Data Layer to connect, and the owner's
phone app comes from Play, signed with the app signing key Google holds
(`docs/RELEASE.md:172`). A debug-signed watch build cannot pair with it, and neither can a
locally built one signed with the upload key. So the wrist test build goes through Play
too: a tag the release workflow recognizes (for example a `-watchm3` suffix) adds the
property and uploads to the internal tracks (`.github/workflows/publish-internal.yml:139-154`)
as usual, signed by Play, and is never promoted. Version codes need a rule, because the
workflow derives them from the numeric version only (`publish-internal.yml:66`) and Play
never reuses a code: if `v0.5.0-watchm3` simply parsed as 0.5.0, it would burn 5000 and
5001 and the real `v0.5.0` would fail to upload. Either a test tag names the last released
version and takes the spare codes inside its step of ten (phone +2 and watch +3, then +4
and +5), or every test upload takes a fresh patch number (`v0.5.0-watchm3`, then
`v0.5.1-watchm3`, then `v0.5.2`). Both keep the next ordinary tag strictly higher; the
choice is made when the tag rule is built in phase 1. A test tag also skips the release,
screenshots and listing jobs (`publish-internal.yml:162-273`), so it neither cuts a GitHub
release nor pushes dial screenshots to the public listing, and it may upload only the wear
bundle. CI builds and ordinary tags leave the property off.

| Phase | Content | Size | Risk |
|---|---|---|---|
| 1. Foundation | `compose-material3` and `compose-navigation` beside `compose-material`; `ColorScheme` and `Typography`; the `watchM3` property and the tag rule in the release workflow; the `EdgeButton`-in-a-`Box` check; the hold handler with its tests (arm at 0.4 s and 1 s, commit on release, disarm past 3 s, release before arming, press began before the face) | M | Low; the hold handler is the one custom piece |
| 2. The set loop | Today, Lifts page, Set, Rest, Undo chip, Lift page, Next lift; the new face state and its JVM tests | L | Medium: every tick and undo test moves |
| 3. Timed work and swap | Timed hold, rep window with the blank `Picker`, Swap list, waiting on phone | M | Low; state machines unchanged |
| 4. Cardio | Cardio faces, the end-set and End cardio dialogs, distance `PickerGroup`, the four done states; on the phone, "Enter distance" for any blank distance and `publishedAtMillis` on the snapshot | M | Medium: the phone change touches `CardioPresentation.kt` |
| 5. Edges | Ambient, phone states, `OpenOnPhoneDialog` with the phone deep link, last-synced line, tile on `protolayout-material3`, chip status | M | Low to medium; the deep link is an exported phone component and gets the security check |
| 6. Switch | A `-watchm3` internal release; the wrist tests below on both watch sizes; then the property becomes the default and `compose-material` goes | S | Medium: this is the moment users meet it |
| 7. Delete | Dial code, its tests and constants, `DialCopy` strings that no longer appear | S | Low |

The audit's quick fixes to the dial (clock ring under the disc, butt caps, contrast) are
worth building only if the switch is more than a release away.

## Risks and what to test on the wrist

- **Wrist extension against the button.** Bench press, push-ups, a high plank, a front
  rack and curls all bend the wrist back into the bottom edge of the watch. Run each with
  the Set face, a running hold and a running cardio interval on screen, on both wrists and
  both watch sizes, and count logs, starts and stops that nobody meant. The design's answer
  is the hold (a press released before 0.4 s does nothing, one held past 3 s disarms); the
  measurement is how often a contact arms and lets go inside that window. If it does, the
  arming time grows to 0.6 s for logs and the disarm comes down to 2 s, and both get
  re-tested.
- **Finding the button.** Hold the `EdgeButton` with a chalked thumb, eyes on the bar, on a
  41 mm Pixel Watch (192 dp). The bottom edge is the landmark; check that the medium size
  is big enough or move to large.
- **The hold itself.** Whether 0.4 s reads as one press or as a wait, whether "Release to
  log" is read in time, and whether a first tap that only flashes the label teaches the
  gesture.
- **Undo chip.** That it is found right after a mis-log, that it names the right set across
  lifts (the last set of a lift, then the day's last set), and how often a thumb reaching
  for something else undoes a set by accident.
- **Legibility at arm's length.** The load in a lit gym and under fluorescent glare, and
  the 12 sp list lines at the largest font setting.
- **Font scale.** Every face at 1.3 and at the largest setting on the 192 dp watch, against
  the height budget: the hero fits, the dropped line is the right one, ellipsized lines are
  still readable.
- **Rest end.** One buzz and the next set's face appearing; check it reads as "go" without
  looking twice.
- **Ambient.** "Rest, about 2 min" against what the lifter expects; the burn-in orbit; a
  low-bit device.
- **Phone away.** Phone in airplane mode: log three sets, see "3 queued" in `TimeText`,
  bring the phone back, watch it clear. Swap with the phone away: the waiting state and
  Next lift. Cardio done with the phone away stays on "sending" and becomes "saved".
- **TalkBack.** Every face reads title, hero, detail and the button in that order; the
  hold buttons expose a plain click action for accessibility services, as today's hold
  does; the edge ring announces "Rest, 1 minute 24 seconds left".
- **Identity.** Whether the watch still feels like strength.log once the dial is gone. The
  numerals and the day's color on the button carry it; the owner judges this one.

## Questions for the owner

1. **Retire the dial.** This replaces the concentric dial, which has been the watch's
   identity since July, with stock Wear components. The drawings show both. Is that the
   direction, or should the next pass simplify the dial instead?
2. **The button's color.** The proposal fills the one action with the day's bright accent
   (dark text, 5:1 or better on every day). Dial v3 kept actions white so the accent only
   meant the day. Accent or white?
3. **Per-set duration and the session start.** Logging in one press stops sending a start
   time for weighted and reps sets, and the peek's TOOK line goes. Nothing reads per-set
   starts today. Let it go? A side effect: without a start tap, the session's start becomes
   the first set's completion (`ui/WearApp.kt:574-635` falls back to it when no start was
   seen), about a minute late. On the watch, Today's Start tap stamps the session start
   locally, so the day-done minutes stay right; it is not sent, because the first set's
   `startedAtMillis` means when that set began and should not carry a second meaning. The
   phone's session window therefore still opens at the first log, a set late. Acceptable,
   or should the wire gain a session-start field?
4. **The phone path for blank distances.** The watch keeps distance entry, with "Not
   measured" as an explicit choice. Phase 4 adds "Enter distance" on the phone for any
   blank distance, including the watch's. Worth the phone change, or is the wrist enough?
5. **Finishing the day.** The watch can log every set but cannot finish the day; the phone's
   Finish advances the rotation and runs the cascade. Keep "Finish it on your phone", or add
   a finish message from the watch (a new wire path, and the cascade would then show on the
   phone later)?
6. **A hold stopped early.** Stopping before the goal logs the set as prescribed today.
   Should it log the seconds actually held?
7. **Fewer reps than planned.** The watch has never been able to log 4 reps when 5 were
   planned; that is a phone edit. The wire already carries reps. Add "Log different reps"
   to the Lift page with a blank `Picker`, or keep that on the phone?
8. **Background.** Keep `#0D0D0F`, or move lit screens to true black as Wear OS does?
9. **The time during a rest.** The edge ring takes the space `TimeText` uses, so a rest hides
   the time of day. Acceptable?
10. **The legacy cardio finisher** (phone audit question 14). If it stays, it is drawn with
    the same cardio faces; if it goes, there is one cardio path.
11. **Hold or tap to log.** The brief keeps the hold as the default, because the council
    held that no stray tap may write a record. If that rule is a strong preference rather
    than absolute, logging on a tap is the alternative: about 0.4 s faster per set, the same
    gesture as the phone's "Log 95 × 5", and no "Release to log" step to learn. It gives
    up the guarantee: a single brush or a doubled tap on the Set face would log a set, a
    bump on a running clock's button would stop it, and the Undo chip would be the only
    fix. Starts and stops could stay holds even then. Is "no stray tap writes" absolute?

## Council round 1

Two reviewers returned CHANGES at 7/10; the coordinator checked their claims against the
code and accepted every item but L. What changed, by ledger id (round 2 then simplified A,
C and the cardio states; see below):

- **A** (blocker; a stray or doubled tap wrote a set, and undo was below the fold): new
  "Tap safety" section. Every action that writes a record is a 0.4 s or 1 s hold with a 3 s
  cancel; the rest face's button is Skip rest and never logs; presses must begin on their
  face, with a 700 ms lockout and one log per 1.5 s; an Undo chip opens a confirm dialog
  from the face after every log, including no-rest and final sets. Tap costs are tabled
  against today. The tap alternative is owner question 11.
- **B** (blocker; the rest's button logged a timed set as prescribed): the rest never logs.
  Skip rest and the end of a rest both lead to the next set's own face, so timed holds, rep
  windows and cardio always start with Hold to start. The rest's next line names the kind.
- **C** (rest hid the set position): the rest's title line is the next set's lift and
  position, "Squat, set 4 of 6"; on small faces the next line carries it, "Set 4: 190 × 5".
- **D** (the design leaned on unverified `ScreenScaffold` scroll behavior): workout faces no
  longer scroll; their extra rows moved to the Lift, Lifts and Cardio pages behind tappable
  chips, and those pages have no `EdgeButton`. The one remaining assumption, `EdgeButton`
  in a plain `Box`, is a phase 1 check with a drawn fallback.
- **E** (192 dp at font scale 1.3 did not fit): a height budget table, a one-detail-line
  rule with a priority order for 192 dp, and drawings of superset, authored detail, cardio
  and rest at 192 dp at scales 1.0 and 1.3.
- **F** (wrist extension presses the bottom edge): the hold and its 3 s cancel are the
  interaction answer; the first risk is now a wrist test across five movements with the
  numbers to change if it fails.
- **G** (no stop-early-and-continue on a distance interval): Hold to end opens a dialog
  with Reached and Stopped short; a stopped set is logged shortened and the block goes on.
- **H** (the phone cannot edit watch-originated distances): distance entry stays on the
  wrist with a blank-starting `PickerGroup` and an explicit "Not measured", and phase 4
  adds the phone path. No distance is blank unless the lifter chose it.
- **I** (cardio done had no saving or failure states): three states, Sending, Couldn't save
  with Retry, and Saved, drawn.
- **J** (the rep `Picker` defaulted to the aim): it starts blank and the button is disabled
  until a count is chosen.
- **K** (phases 1 and 2 replaced the workout before timed work and cardio existed): the new
  UI is built behind a debug-only switch; release builds keep the dial until phase 6, after
  the full loop and the wrist tests.
- **L** (keep `TimeText` during a rest): left as owner question 9, as the ledger says.
- **M** ("Last synced yesterday" with no date in the snapshot): the phone adds an additive
  `publishedAtMillis`; with 0 the line is not shown.
- **N** (the session start becomes the first set's completion): noted under owner question
  3, with Today's Start tap stamping the start.

## Council round 2

Both reviewers returned CHANGES at 8/10 and confirmed the round 1 fixes; hold-to-log was
judged the right answer to A. What changed, by ledger id:

- **O** (when the hold commits was unstated, and committing at 0.4 s made the 3 s cancel
  impossible): a press arms at 0.4 s (1 s to stop) with a light tick, the label turns to
  "Release to log", release commits, and a press held past 3 s disarms and writes nothing.
  The drawings show the container filling, and an armed face.
- **P** (the guards were over-built and undo was slow): the 700 ms lockout and the one log
  per 1.5 s rule are cut; the face-origin rule stays. The Undo chip undoes in one tap with
  no dialog, and the restored Set face is the confirmation. The undo dialog drawing is gone.
- **Q** (a debug-only switch cannot pair with the release phone app): the switch is a
  Gradle property in the release build type. Because Play App Signing signs the owner's
  phone app, the test build ships through the internal tracks from a suffixed tag and is
  never promoted; CI builds and ordinary tags leave it off.
- **R** (the distance picker stopped at 990 m): three columns, up to 42,990 m, with the
  chosen distance spelled out.
- **S** (the small rest face dropped the lift and "of 6"): it keeps "Squat, set 4 of 6" and
  "Next 190 × 5", and the countdown shrinks instead, to 43 sp at 192 dp and 28 sp at 1.3.
- **T** (running faces lost the chip that leads to End cardio and Cancel set): the lift or
  Run chip stays on every running face.
- **U** (cardio done said sent before the queue had it): a Saving state lasts until
  `onQueued` fires; Sending, Couldn't save and Saved follow.
- **V** (at 1.3 the button lost "Hold"): the label wraps to two lines instead, and a short
  tap flashes it with a buzz.
- **W** (where `publishedAtMillis` is stamped): with `revision` and `epoch`, after the
  content dedupe, so a new time is never new content.
- **X** (the session start overloaded the first set's `startedAtMillis`): the start stays on
  the watch, for the day-done minutes; owner question 3 asks about the phone's window.
- **Y** (Undo should come first on the Lift page): it is now the first row.
- **Z** (hold or tap depends on whether "no stray tap writes" is absolute): owner question
  11, with the hold as the default and what tap-to-log gives up.

## Council round 3

Both seats closed every round 2 row and called the owner question on hold or tap fair.
The Claude seat approved at 9/10; Sol scored 9/10 with one open point. Both found the same
remaining defect: the test tag's version codes would collide with the next real release.
Round 3 is the review cap, so the fix was written into the phases section above as a rule
with two acceptable schemes, to be settled when phase 1 builds the tag rule. The Claude
seat's two nits were also applied: the haptics list now names the arm tick and the
short-tap buzz, and test tags skip the release, screenshots and listing jobs.
