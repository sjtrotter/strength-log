# Product council, 2026-10-31

**Seats:** Claude Opus, Claude Fable, GPT Astra and GPT Sol.
**Rounds:** two. Round 1 was independent papers. In round 2 each seat read the other three
papers, took a position on each split, and checked the others' code claims.
**Owner question:** what the app is for, what the watch is for, what we track, and how plans
get made. The phone redesign of 2026-10-30 polished the surface; the owner suspects the
idea under it was never fully thought through.
**Raw material:** briefs and papers are in `product-council-2026-10-31/`.

## Owner decisions, 2026-10-31

The owner accepts everything the four seats agreed on, and the build order.

On the open splits:

- **GPS.**
  - It is allowed on whichever device runs the workout, phone or watch. This reverses the
    council's "watch only".
  - It is on only while a cardio set that needs distance is running: outdoor running,
    walking, trail, hiking and cycling. A track is a maybe. Treadmills and stationary
    machines are never included.
  - Recording must stay accurate and continuous with the app in the background or the
    phone locked. No smoothing or straight lines across gaps.
  - It is built after workout runs. Tracked in #284, rewritten with these rules.
  - Saving the route is not ruled out; the owner likes looking at it. It may be a separate
    opt-in setting. It is still open, and #284 lists what to settle: drawing without map
    tiles, Health Connect exercise routes, and Data safety.
- **Tab name.** It is **Log**, matching the app's name.
- **Estimated 1RM and volume.** Both go in, inside each exercise's page.
  - The app is opening up to more kinds of lifters, so these are worth showing.
  - Build them into the page's design rather than adding them as stat tiles.
- **Supersets** (an addition by the owner). A superset round is logged one exercise at a
  time.
  - Each exercise in the round gets its own confirmation, and each can be corrected on its
    own.
  - Today one round tick marks both tracks together (`DayScreenBuilder.kt:199`).
  - This belongs to step 3, the one plan model, next to the work that lets hand-built
    plans have supersets.
  - It replaces the phone redesign's "Log round 2" button.

Filed under milestone M9:

| Step | Issue |
| --- | --- |
| 1. Theme and accessibility fixes | #340 |
| 2. Workout runs (closes #296) | #341 |
| 3. One plan model | #342 |
| 3. Supersets logged per exercise | #343 |
| 4. Phone flow | #344 |
| 5. Log | #345 |
| 6. Watch | #346 |
| 7. GPS distance | #284 |
| Bug: the generator ignores equipment | #339 |

## The answer

All four seats ended in the same place.

The app is a workout logger with optional help making a plan.

- The goal math stays, with the §11 numbers unchanged.
- The math becomes one way to write a lift's targets, not the backbone everything hangs off.
- The August "quiet coach" cure is rejected. More commentary cannot turn a bodyweight
  formula (`GoalCalculator.kt:30`) into coaching.

The thing that is actually unfinished is underneath the screens: the plan and the workout
share one set of rows.

- The live log is both next time's prescription and today's record. Every half-built part
  the owner can feel traces back to that:
  - a swap mid-workout deletes the sets already logged (`TrackerRepository.kt:668-670`);
  - a prescription edit does the same (`:545-547`);
  - an authored plan forgets last session's weights (`:1310-1314`);
  - checks reset at midnight rather than at Finish (`CheckmarkReset.kt:33`);
  - a late watch tick can land in the next workout (#296, `SetEditApplier.kt:45-47`);
  - progress exists only for the generated mains (`JournalBuilder.kt:58-71`).
- `docs/PLAN.md` A13 says watch updates carry the run they belong to. The code has no run
  ID anywhere (`SetEditDelta.kt:20-51`). Run identity was specified and never built.

## Decisions, as the council would put them to the owner

All four seats answer yes to 1–6.

1. **Workout runs first.** Starting a workout creates a run. What was prescribed, what is
   in progress and what was done live apart. Every phone write and every watch update
   carries the run ID. This comes before the screen redesign.
   - **The owner sees:** a swap keeps the sets already logged, and a workout open past
     midnight survives.
2. **Each lift in a plan remembers or keeps.** Every slot is either "remember what I did",
   where next time starts from last time, or "keep to plan", where the prescription holds.
   - "Reset to plan" is on remembered slots.
   - Fixed targets protect PT or test-prep plans from one bad day.
3. **One kind of plan.** Generated or hand-built is only where a plan came from.
   - Supersets, settings and rotation work the same in both.
   - Cardio has one typed form; the prose finisher and the `CardioDay` side channel go.
   - Plan settings move into the plan.
4. **Today is the overview.** It shows Resume when a workout is open, the suggested next
   workout with a direct Start, the other workouts, one fact line, and Start empty.
   Picking a workout opens its preview. The preview is last week's Today, moved one level
   down. Start begins the run.
5. **History for every exercise.**
   - A Sessions view and an Exercises view, covering everything ever performed: accessories,
     authored lifts, holds and cardio.
   - Each exercise page shows last time, the best set, a trend, and the goal line where a
     goal exists.
   - Adherence is completed versus prescribed, which the run gives for free, plus "rotation
     complete" on the receipt.
6. **New plan has four doors:** Make me one (the three-screen wizard), Build your own,
   Duplicate and Start empty.
   - No template catalog: the generator's defaults are the starter template.
   - The wizard stops being the only way in.
7. **The watch runs a prepared workout end to end.**
   - On the watch: log with a tap and an Undo chip; correct the reps, load or duration of
     the set in front of you; skip; pick another lift; rest; Finish, including offline.
   - On the phone: plan editing, swaps outside the offered alternates, and history.
   - A wrist test gates replacing the dial, and the hold arming time stays one constant so
     a failed test can flip it back.
   - All four agree. This reverses last week's hold-to-log and the phone-only Finish.
8. **Distance is measured on the watch only**, through Wear Health Services, outdoors.
   - It never uses phone GPS and never stores routes.
   - Health Connect gains distance (and heart rate, if recorded) writes; today it writes
     neither (`HealthConnectPermissions.kt:36`).
   - The seats split on when to build it (below).

## Still split, owner's call

| Question | Opus | Fable | Astra | Sol |
| --- | --- | --- | --- | --- |
| When to build watch GPS | A spike after the watch work | Last, and only if the owner runs intervals | Prototype right after runs | Prototype right after runs |
| Tab names | Today, History, Plans | Today, Log, Plans | Today, Progress, Plans | Today, History, Plans |
| Estimated 1RM | Cut | Optional, off by default, in exercise detail | Optional, labeled, in detail | Optional, labeled, in detail |
| Volume | Cut | In exercise detail only | In detail | In detail |
| Plan import | Later | No (restore covers it) | A reviewed structured import | Later |

On GPS, Fable reduces the question to a fact about the owner: do they run intervals
without the phone? If yes, the watch prototype is worth building. If no, close #284 and keep
manual distance plus Health Connect linking.

## What changes in last week's redesigns

**Kept:**

- the one-set screen and "Log 190 × 5";
- the rest band with Undo;
- Finish in the app bar;
- the Settings split;
- sessions first in history;
- one receipt with the cascade inside it;
- the theme and accessibility fixes;
- Wear Material 3.

**Changed:**

- **Today:** becomes the overview, and its old content becomes the preview.
- **Lifts tab:** becomes Exercises, for everything.
- **The wizard:** becomes one of four doors.
- **The chevron by the lift name:** becomes a labeled "All sets" (or "Sets and options").
- **On the watch:** tap replaces hold, and corrections and Finish move onto the watch.

**Held until the data change:**

- the Plan "Training" sub-screen (it encodes the generated/authored split);
- cardio Redo with in-place Health Connect amends (Fable would cut it);
- the 15-PR UI sequence, which would be built on rows that are about to move.

## Build order (merged)

1. **Theme and accessibility fixes** (phone redesign phase 1). Nothing moves; contrast holds
   in both themes. Fable put this first because it depends on nothing.
2. **Workout runs:** the run record, run IDs on the wire, Finish that is safe to replay,
   and the backup and wire formats bumped.
3. **One plan model:** origin as provenance, per-slot remember or keep, typed cardio only,
   session notes into Room, and authored standard lifts seeded from goal math instead of
   0 lb × 10 (`TrackerRepository.kt:686`).
4. **Phone flow:** Today overview, preview, the set screen, the receipt, Start empty, and
   the New plan doors.
5. **History:** Sessions and Exercises for everything.
6. **Watch:** the Wear M3 set loop with tap and Undo, corrections, lift choice, offline
   Finish, and the wrist test before it replaces the dial.
7. **Distance:** Health Connect distance writes and run linking, then the Health Services
   prototype, with timing per the split above.

Astra and Sol would move the GPS prototype up to step 3.

## Facts checked across seats

**Confirmed by two or more seats:**

- No run ID exists (`SetEditDelta.kt:20`), and PLAN A13 is wrong about it.
- A late tick can reach the next run of a one-day plan (`SetEditApplier.kt:45-47`, #296).
- Swaps and prescription edits delete live logs.
- Authored slots refuse a superset partner (`TrackerRepository.kt:742`).
- An authored standard lift seeds at 0 lb × 10.
- The watch declares no sensor permissions.
- Health Connect writes no distance.
- A watch hold sends no measured seconds (`TickDelta.kt:17-37`).
- The external Health Connect reader keeps only strength sessions (`HealthConnectReader.kt:87`).

**Generator equipment fallback** (`ProgramGenerator.kt:393-398`): when no accessory fits the
lifter's equipment, `bestAccessory` returns one anyway ("never leave a hole"), and nothing
marks it. Confirmed by all four seats and by the lead. It is a bug to file on its own.

**TOP cascade** (`SetEditor.kt:21-40`): editing a TOP weight rewrites every ramp and
back-off row, including rows already done. The pinned §11 numbers describe the cascade
before any row is done, so skipping done rows changes no pinned value. It belongs in the
run work.

**Corrections to the papers:**

- Opus's round 1 line numbers for `Cardio.kt` and `Types.kt` were past the ends of those
  files. Opus corrected them in round 2.
- "The watch cannot finish a day" is too broad. Standalone cardio sends `finishesDay`
  (`WearApp.kt:180`). Only strength lacks a watch Finish.
