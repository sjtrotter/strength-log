# A fitness test as a first-screen goal: design (revision 5)

Design seat, 2026-11-27, against main 1264c37a (v0.16.0). Read-only; nothing in the repo changed. Revisions 1
to 3 applied council rounds 1 to 3 (ledgers at the end); revision 4 applied the owner's decisions of 2026-11-24
(§7); revision 5 applies the owner's design of 2026-11-27 and the council's take on it (§8, §9), then council
round 1 on revision 5 (ledger at the end).

The owner's request: the first setup screen (Lift / Run / Sprint / Both) offers the fitness test as the goal,
and the user builds the test by searching one exercise database, not a curated event list. A test event is a
picked entry plus a scoring format the user sets; the user types the passing number. No named-test presets.

## 0. What exists today, verified in code

- Setup steps: `SetupFlowStateBuilder.steps()` builds a subset of `SetupStep { GOAL, ORDER, SUB_GOAL, RUN_GOAL,
  TEST, WEEK, EQUIPMENT, ABOUT, LIFTS, RECENT, EVENT, WHERE, TOGETHER, PLAN }` (enum order is the canonical
  screen order; `currentStep` uses ordinals; the draft stores the step by name). `PlanBranch { LIFT, RUN, BOTH,
  SPRINT }` is stored by name in the recipe. `answers()` keeps `mains` on every branch but RUN.
- Run-side tests: `RunGoal.FITNESS_TEST` with `RunAnswers.test: FitnessTest { TWO_MILE, ONE_AND_HALF_MILE, COOPER,
  PACER, HAMR }`, `goalSeconds` (timed tests), `testScore` (Cooper meters, PACER/HAMR shuttles), `dateEpochDay`.
  `RunLayout.runEntries` gives the test plan `TEST_INTERVALS` on Q, `TEMPO` on Q2 (Some or Racing), EASY on L.
  `runGoal()` makes `PlanGoal.Timed` for the two timed tests, `PlanGoal.Shuttle` for a scored PACER/HAMR, and
  nothing for Cooper (#618). `Taper.factor` cuts WORK to 0.5 in the last ten days of a Timed goal up to 10K or a
  Shuttle goal. Run plans carry 0 to 2 strength blocks (chosen on Your plan) under Strength rules (D8); Both
  plans' blocks use the lift sub-goal. The ladder exit for tests is 20 min or 2 miles nonstop for Some runners
  (`shortLadderGate`), and `weeksNeeded` adds `EVENT_WEEKS_AFTER_LADDER = 4`. Ladder level 1 is ten 60 s runs each
  followed by a 90 s walk: 1,500 s (`RunWalkLadder.block`; `PlannerBothTest` relies on it). Runs are not
  `ExerciseEntry` values; the picker in `DayEditLogic.kt` (`ExercisePicker.filter`) runs `ExerciseSearch(query)
  .matches(entry.name)` plus `availableWith(equipment)` over library entries only.
- Test events (#646): `TestEvents.all` = `aft_hrp` (2:00 window, Army form, fault and rest text), `aft_plank`
  (hold, ceiling 220 s), `custom_pushup_window`, `custom_situp_window` (user window). `TestEvent.id` is 1..40
  characters; `TestRef(eventId, version, windowSeconds)` is a result's identity; `TestEvents.find(id)` and
  `matches(ref)` are the only resolvers, read at `SetupFlowStateBuilder:80` and `:200`, `TestPrep:37`,
  `TrackerRepository:2131` (`benchmarkOpening`), `DayViewModel:2185` and `:3298`, `PlanScreenBuilder:62`,
  `PlanTestGoal:40`. `JournalBuilder:241` names a trend from the result's stored `rulesJson`, not the catalog.
  `TestSetType { REPS_IN_WINDOW, REPS_CONTINUOUS, REPS_CADENCE, MAX_HOLD }` and `TestStop { TIME_UP, STOPPED,
  LIMIT }` exist. `Benchmark.target` (Benchmark.kt:22) throws for `REPS_CONTINUOUS` and `REPS_CADENCE`;
  `Benchmark.runnable` is window or hold only. `DayViewModel:1986` and `:2252` derive the stop from `event.timed`
  alone: a non-hold result is always `TIME_UP`. `DayViewModel:1741` lets a benchmark hold run past its target even
  on a `pressureCaution` entry (`runPastTarget = benchmark != null || (pastTarget && !pressureCaution)`).
  `DayViewModel:3304` benchmarks with `eachSide = false`.
- `TestPrep.aim` and `target` read only `event.setType` and `event.timed`; `slot` reads only `event.exerciseId`
  (`ExerciseLibrary.get`) and the answer. `SetupAnswers.tests: List<TestAnswer>` and `testDateEpochDay` are
  allowed only on LIFT or non-sprint BOTH (`init`). `withTestPrep` appends three sets of each event's movement at
  half the anchor (floors 5 reps / 20 s) to the first three days that carry non-balance lift slots, after `fitDay`
  and `VolumeBalance.balance` have run. Benchmarks open every 21 days and never in the last 7 days (case 49).
- The library: 194 entries, `TrackingType` from `GoalSource` (`Weighted`, `Reps(targetReps)`, `Time(targetSeconds,
  addedWeightLb)`). `mainCapable` on eight weighted entries: `bb_back_squat`, `trap_dl`, `conv_dl`, `sumo_dl`,
  `bb_bench`, `incline_db`, `ohp`, `bb_row`; `Planner.mainChoices` lists mains per row (squat, hinge, horizontal
  press, overhead press, pull); custom exercises are never `mainCapable`. About 35 REPS entries (push-up,
  hand-release push-up, dip, pull-up, neutral-grip pull-up, inverted row, sit-up, decline sit-up, reverse crunch,
  V-up, hanging raises, sit-to-stand, pistol squat, back extension, Nordic, plyometrics and others). TIMED entries:
  `plank`, `high_plank`, `hollow_hold`, `side_plank` (each side), `dead_hang`, `wall_sit` (`pressureCaution`,
  `holdCeilingSeconds` 120), `single_leg_stance` (each side, cap 30), and three with added weight: `weighted_plank`
  (25 lb), `suitcase_carry` and `farmers_carry` (50 lb). `ExerciseCatalog` (:data) is the library plus the user's
  custom entries.
- Lift rules (`LiftRules.kt:21`): a MAIN is ramped only for A year or more, Strength and not 65+; otherwise
  straight sets; 65+ mains take `Range(6, 10)` at two sets. Nothing schedules a max attempt.
- Budget arithmetic (`SessionBudget`): 300 s warm-up; a window or hold set costs 15 s plus its seconds; rest
  follows `RestPolicy` by role (push-ups are ACCESSORY: 90 s; sit-ups and planks are CORE: 60 s) after every set.
  `easyRunSeconds(minutes, strength, long)` sizes an easy run to `minutes × 60 − 300 − 1500` on a strength-block
  day, floor 1,200 s, limited to 75 percent of the long run; three callers: `runDraft`, `RunReplan.next`,
  `CardioJudge` (`EasyRunContext.stopSeconds`). `fitDay` trims lifts with the dose ladder (step 1 drops accessories
  and isolation and keeps one CORE at two sets, step 2 drops the SECONDARY, step 6 drops the CORE). `liftDraft`
  runs layout, `fitDay`, then `VolumeBalance.balance`. The strength block on a full gym under Strength rules is
  pinned at 1,182 s for block 0 and 1,140 s for block 1 (`PlannerRunTest` D14).
- Storage: `plan.goalJson` (run goal), `plan.testGoalJson` (`TestGoalDto` v1: eventId, version, windowSeconds,
  required), `plan.settingsJson` (`PlanSettings.goalsLb`), the v2 recipe (`SetupRecipeDto` with `tests:
  List<TestRecipeDto>`; `SetJson` ignores unknown keys), `test_result` rows (eventId, eventVersion, windowSeconds,
  count or seconds, stop, rulesJson), backup `CURRENT_SCHEMA_VERSION = 35`, `SetupDraft.VERSION = 1`.
  `BackupCodec.validateTestResults` (BackupCodec.kt:711) checks the stop name, exactly one number, the `TestRef`
  constructor and `TestEventJson.validateSnapshot` (snapshot id, version and exerciseId equal the row's, window
  rule); it never consults `TestEvents.all`.
- Branch-gated readers: `PlanRepository.planned` loads cardio history for RUN and BOTH; `TrackerRepository.nextPhase`
  re-plans RUN and non-sprint BOTH; `PlanScreenBuilder` derives `run` the same way; `ResetDay` accepts LIFT. The
  design writes only those branches, so none of them changes.

## 1. The flow

### 1.1 Shape

"Fitness test" is a first-screen entry, not a stored branch. The draft holds it as a flag; `answers()` maps it
onto today's three shapes, which every reader already handles. `chooseFitnessTest()` sets the draft's `branch`
to RUN; `answers()` lifts it to BOTH or LIFT from the picks, and passes every other draft answer through
unchanged (`goal`, `cardioFirst`, `together`, `strengthBlocks`, `mains`), so a prefilled legacy plan keeps its
own values:

| Picks | Answers written |
|---|---|
| Reps or hold events only, or with a weighted lift | `LIFT` + `tests` (today's Lift-plus-tests plan) |
| A weighted lift only | `LIFT`, no `tests`; the requirement lives on the typed lift goal (§3.3) |
| A run only, draft branch RUN | `RUN`, `run.goal = FITNESS_TEST` (today's Run test plan, 0 to 2 strength blocks under D8) |
| A run only, draft branch BOTH (a legacy Both plan with a run test and lifting days) | `BOTH`, `run.goal = FITNESS_TEST`, `cardioFirst` as stored: nothing dropped |
| A run plus any other pick | `BOTH`, `run.goal = FITNESS_TEST`, `tests` when present |

So an existing test plan and a new one have the same shape; prefill, re-plan, Reset day, the Plan screen and
backups need no new arm. What is new: the entry and its one screen with one picker, one date typed once, the prep
inside the budget, the max-unbroken benchmark and the beginner case.

```
Goal ─► Your test ─► Your week ─► Equipment ─► About ─► [Your lifts] ─► [Recent numbers]
     ─► [Where you are] ─► [Lifting and running] ─► Your plan
```

Six to eleven screens. RUN_GOAL and EVENT are never shown on this entry: the picks decide them. LIFTS and
RECENT are hidden only for the shape whose lifting is 25-minute blocks: a cardio-first, same-visit plan with no
weighted pick; everywhere else they follow today's rules (on LIFT, on a lift-first Both, on Separate days, and
whenever a weighted lift is picked). SUB_GOAL and ORDER are not shown on a new draft, since §3.2 sets `goal` and
`cardioFirst`; they show only on a prefilled draft whose `goal` or `cardioFirst` differs from what §3.2 would set,
so a legacy Lift/Muscle-plus-tests or Both/Muscle-plus-2-mile plan keeps every screen it has today on Review
answers. Together is shown on the Both rule when the plan is Both.

### 1.2 Screens

Goal (first screen), `GoalStep`. One new `ChoiceRow`, last, so the four existing rows keep their place in tests
and renders. Run's body line loses "a fitness test".

```
┌──────────────────────────────────────────┐
│ ←  Set up                        1 of 8  │
│                                          │
│ What are you training for?               │
│                                          │
│ ● Lift                                   │
│   Strength, muscle, or fitness           │
│ ○ Run                                    │
│   5K to ultra, or regular cardio         │
│ ○ Sprint                                 │
│   40 yards to 400 meters                 │
│ ○ Both                                   │
│   Lifting and running in one plan        │
│ ○ Fitness test                           │
│   A timed run, push-ups, a plank, a      │
│   deadlift: pick the events yours scores │
│                                          │
│ Why we ask                               │
│                              [  Next  ]  │
└──────────────────────────────────────────┘
```

Your test (`SetupStep.TEST`, the existing step generalized; `TestStep` in `SetupFlowTests.kt`). One "Add an
event" button opens the picker sheet (below). Each pick becomes a block with its format, its fields and one line
of rules; one date closes the screen. Nothing is pre-selected. A user building the Army test by hand:

```
┌──────────────────────────────────────────┐
│ ←  Set up                        2 of 8  │
│                                          │
│ Your test                                │
│ Your agency or school sets the passing   │
│ numbers. Enter yours.                    │
│                                          │
│ 2-mile run                            ✕  │
│  Required time   [ 16:00 ]               │
│ Hand-Release Push-Up                  ✕  │
│  (● In a window) (○ Max unbroken)        │
│  Window [ 2:00 ]                         │
│  Army rules                        [on]  │
│  Required reps   [ 40 ]                  │
│  Your last count (optional)  [ 31 ]      │
│  From the front leaning rest, lower ...  │
│ Plank                                 ✕  │
│  Army rules                        [on]  │
│  Required hold   [ 3:40 ]                │
│  Your last hold (optional)   [      ]    │
│  Forearms flat, elbows under the ...     │
│ Trap-Bar Deadlift                     ✕  │
│  Heaviest for [ 3 ] reps                 │
│  Required weight [ 340 lb ]              │
│  Trained under the Strength rules. No    │
│  max attempts are scheduled.             │
│                                          │
│ [ Add an event ]                         │
│                                          │
│ Date                                     │
│  Add a date                              │
│                                          │
│ Why we ask                               │
│                              [  Next  ]  │
└──────────────────────────────────────────┘
```

The picker sheet (`AppModalBottomSheet`, the day editor's add-exercise shape: an `OutlinedTextField` search,
`FilterChip`s, `ListItem` rows):

```
┌──────────────────────────────────────────┐
│ Add an event                             │
│ Search [ push                         ]  │
│ (All) (Strength) (Cardio) (Holds)        │
│                                          │
│ Push-Up                           reps   │
│ Hand-Release Push-Up              reps   │
│ Pike Push-Up                      reps   │
│ Diamond Push-Up                   reps   │
└──────────────────────────────────────────┘
```

Rows carry the entry's catalog name and a supporting word for what it scores: "reps", "hold", "weight for reps",
"run". A tap adds the pick with its first eligible format and closes the sheet; an already-picked entry shows a
check and is disabled, so a test never holds one entry twice. A test holds one run (the draft has one `test`):
once a run is picked the other four runs are disabled with "One run per test" until its block is removed. The
search is `ExerciseSearch`, with its db/bb/kb/sl synonyms, over the union of eligible library entries and the five
runs (§2); the filter is All by default. No equipment filter: the test is what the agency scores, whatever the
gear; the gear question comes later and §3.3 handles any pick it cannot train.

Rules on this screen:
- Next needs at least one pick and a valid required number for each (the existing `canAdvance` TEST rule extended
  to run and weighted picks; the run time keeps Your event's `INVALID_GOAL_TIME` behavior). Last counts stay
  optional; 0 means "none yet" and is accepted by `testAnswer` and `canAdvance` only on an entry with an easier
  movement (§3.6); elsewhere the field's floor is 1.
- Format: a REPS entry shows a two-way `SegmentedButton` (In a window, Max unbroken); a hold, a weighted lift and
  a run have one format each and show no choice. Switching the format changes the event's identity (§2.2), keeps
  Required and Last, and clears the window, so a max-unbroken pick never carries a stale 60 into its `TestRef`.
- Window: shown for In a window, prefilled 1:00 (the civilian norm: Cooper police norms, FBI, USAF sit-ups;
  practice), 0:10 to 60:00. When Army rules are on the window is the edition's and the field is read-only.
- Army rules: a `ListItem` with a trailing `Switch`, shown only when `TestEvents.editions(exerciseId, format)` is
  non-empty: today the hand-release push-up in a window and the plank hold. On, the pick's identity is the edition
  (`aft_hrp` v1, `aft_plank` v1), the edition's form text shows under the fields, the plank shows "Ceiling 3:40".
  Off by default: a plank is not an Army plank unless the user says so (§2.3).
- Rules line: one generic line per format (§2.4) when no edition is on.
- Heaviest for N: N is 1 to 10 (`RecentNumber` already bounds reps at 10), prefilled 3 for a hinge pick and 5
  otherwise (the AFT deadlift is a 3-rep max; five is the Strength TOP set; practice). Required weight in the
  user's unit, kept whatever the equipment turns out to be (§3.3).
- Required and Last on a `pressureCaution` entry are limited to `TestEvents.requiredLimit(event)`, the entry's
  `holdCeilingSeconds` (the wall sit: 2:00), with the line "Held to 2:00 at most here"; the same limit binds the
  Plan screen's editor and the domain model itself (`TestAnswer` and `TestEventGoal` reject a number above it), so
  no prep aim can exceed half of it (§3.8).
- Cooper keeps owner decision 2 (revision 51): no date for the run goal until #618 gives it a shape. A date
  typed here still applies to the reps and hold events.
- Picks that change the lifting rules recompute the draft's `goal` (§3.2); the entry starts at General fitness.
- The ✕ on a block removes the pick.

Your week, Equipment, About your training: unchanged. About shows the running block (New / Some / Racing,
running injury) when the test has a run.

Your lifts and Recent numbers: today's rules (§1.1), except on a cardio-first same-visit plan with no weighted
pick, where both are hidden so a run-plus-push-ups tester is not asked for five mains or recent numbers for
25-minute blocks. With a weighted pick on, that pick's row on Your lifts lists only the picked lift when the gear
allows it and the user has not excluded it (`availableWith(equipment, excluded)`); the other rows are as today. A
reps-only tester who lifts a year or more sees Recent numbers as today.

Where you are: shown when the test has a run; the label asks for a run without walk breaks, as today for
Fitness test runners.

Lifting and running (Together): shown on the Both rule (`offersTogether`) when the plan is Both; the default is
Same visit on every day count (§3.1), except Separate days at five or six days with a weighted pick on a press or
pull row, because the same-visit strength blocks hold only the squat and the hinge and the run row still keeps
its interval day at three run days (§3.3). The default is derived in one place, `answers()`: `both.together =
draft.together ?: SetupFlowStateBuilder.testTogether(draft)`, evaluated from the current `days` and picks whenever
the draft has no explicit choice, so Your test coming before Your week does not matter (a new draft has `days = 3`,
SetupDraft.kt:32, and `setDays` never touches `together`, SetupFlowViewModel.kt:213). No pick writes `together`.
Only a draft begun by `chooseFitnessTest()` ever has `together == null`: `draftFrom` and `decode` fill a null
`together` on a legacy plan or draft from `SetupAnswers.together()` over the stored answers (§4), because a legacy
Both recipe stores `together = null` (SetupRecipeJson.kt:78, :107) and resolves it to Separate days at four or more
days, or at three days lift first under Strength for A year or more (SetupAnswers.kt:82-87); filling it keeps that
resolved choice through Review answers and a save, so an existing plan is never flipped to Same visit and rebuilt.
Without this the planner's own default would apply (`SetupAnswers.together()`, SetupAnswers.kt:82-83: Separate
days at four or more days when unset), which is what loses the interval day. The Together screen shows the
derived default selected while `draft.together` is null; a tap writes `draft.together` and is kept through every
later change of days or picks. At two to four days the Separate days row carries the templated line "Trains
{lift}, but leaves {run} no interval day at {days} days" ("Trains Barbell Bench Press, but leaves the 2-mile run
no interval day at four days"); at one day Together is not offered. Whatever the user chooses, Your plan says
where the lift is and is not trained.

Your plan: the existing preview plus one line per event with its actual dose or why it is not trained (§3.9).

## 2. What the user picks

### 2.1 One database, four formats

The picker searches one list: every library entry that takes at least one format, plus the five runs. There is
no curated event list and no named test (owner, 2026-11-24 and 2026-11-27). What the app can log, prepare and
benchmark differs by format, and the screen says which.

| Format | Eligible entries | Filter | Logs | Prepares | Benchmarks |
|---|---|---|---|---|---|
| In a window (`REPS_IN_WINDOW`) | REPS entries outside `PLYOMETRIC` | Strength | count in the typed window | 3 × half the anchor in the window | yes: one window, count written `TIME_UP` (case 51) |
| Max unbroken (`REPS_CONTINUOUS`) | REPS entries outside `PLYOMETRIC` | Strength | count until the user stops | 3 × half the anchor as reps | yes, new: one open set, count written `STOPPED` (case 59) |
| Max hold (`MAX_HOLD`) | TIMED entries with `addedWeightLb == 0` | Holds | seconds | 3 × half the anchor, ending at target | yes: runs past its target to the 10-minute limit, with the edition's ceiling shown; a `pressureCaution` entry ends at its cap (case 60) |
| Heaviest for N (`HEAVIEST_FOR_REPS`) | `mainCapable` weighted entries | Strength | weight × reps in the ordinary log | a MAIN or hinge SECONDARY under the slot rules | no scheduled attempt: met by a logged non-ramp set at or above the weight with at least N reps (case 61) |
| Time for a distance | 2-mile, 1.5-mile (`FitnessTest`) | Cardio | the run | test intervals, tempo, easy, taper | logged runs feed `GoalProgress.bestTimed` |
| Shuttles | PACER, HAMR | Cardio | typed shuttle score | the test plan toward `PlanGoal.Shuttle` | the app does not play the beeps: "Run the test with your school's audio." |
| Distance in a time | Cooper 12-minute | Cardio | the run | the test plan | no goal shape yet (#618): no date, no progress row |

Holds are TIMED minus the carries and the weighted plank (`addedWeightLb > 0`): a carry is not a hold test, and
a loaded plank test has no agency behind it. Heaviest for N is limited to the eight `mainCapable` entries: the
eligibility restriction the council agreed for test lifts, the Strength-rules barbell and dumbbell mains that
agencies test (`Planner.mainChoices` itself lists more candidates per row, Planner.kt:125; a wider list is a
follow-up, not a scope change here). Plyometrics are out of both rep formats (no test scores max box jumps).
Every other REPS or TIMED entry is eligible on its tracking type alone, with no per-entry curation; the user
knows their test. Worked examples beyond the Army: Marine pull-ups (max unbroken), FBI push-ups
(max unbroken) and sit-ups (1:00 window), the Senior Fitness Test's 30-second chair stand (sit-to-stand in a 0:30
window) and single-leg stance (max hold, each side) for a 65+ user, a PE class's wall sit (max hold, capped).

Not supported, named on the Why sheet and in the sheet's footer: cadence push-ups and curl-ups (FitnessGram's
3 s metronome; `REPS_CADENCE` stays in the enum and is never offered), shuttle audio (PACER and HAMR keep the
typed score), Cooper's distance-in-time goal (#618), runs at other distances (the Marine 3-mile, the FBI 300 m),
the sprint-drag-carry, and custom exercises as test events (the planner and the Start movement check run in
`:domain` against `ExerciseLibrary`; a custom entry's event would need the Room catalog at every resolve site).
Each is a follow-up when a user asks. Point tables are not built in (owner rule from #646).

### 2.2 Identity: derived events

A pick's identity is a `TestEvent` resolved by `TestEvents.find`. Two kinds exist:

- Editions, `TestEvents.all`: the four authored entries as today (`aft_hrp` v1, `aft_plank` v1,
  `custom_pushup_window` v1, `custom_situp_window` v1). `all` is the edition table, not a picker list; case 48 is
  untouched.
- Derived, `TestEvents.derived(exerciseId, format)`: id `"<exerciseId>:<FORMAT>"` (`pullup:REPS_CONTINUOUS`,
  `plank:MAX_HOLD`, `sit_to_stand:REPS_IN_WINDOW`), version 1, `name = "<entry name>, <format noun>"` ("Pull-Up /
  Chin-Up, max unbroken"), the entry's `exerciseId`, `setType` = the format, `custom = (format == REPS_IN_WINDOW)`
  (only a window event takes a typed window; `TestEvents.window` returns the typed value for any custom event, so
  a custom max-unbroken or hold event would carry a stale window into its `TestRef`), `ceilingSeconds =
  holdCeilingSeconds` only on a `pressureCaution` entry, one generic rules line (§2.4), `sourceUrl` the repo,
  `sourceDate` 2026-11-27. `TestFormat` is stored by name in the id, so its constants are never renamed, and a
  derived event's text is part of its version: a copy change to the generic line is version 2 (TestEvent's KDoc).
  Two picks spell their derived id as the existing custom edition, so a new pick continues the old identity
  instead of splitting a user's history: `pushup` in a window is `custom_pushup_window`, `situp` in a window is
  `custom_situp_window`; `find("pushup:REPS_IN_WINDOW")` and `find("situp:REPS_IN_WINDOW")` return null, so no
  event ever resolves under an id other than its own (`validateSnapshot` requires `rules.id == ref.eventId`). The
  longest library id is 23 characters (`weighted_neutral_pullup`) and the longest derived suffix 16
  (`:REPS_CONTINUOUS`), inside `TestEvent.id`'s 40; a test pins the bound for every eligible entry.

`find(id)` returns the edition when `all` has it, else parses `exerciseId:FORMAT`, requires the entry in the
library, the format eligible for it and the spelling canonical, and derives; `matches(ref)` is unchanged. Stored `TestRef` values are
never rewritten: results compare by id, and a rewrite would split a history. A legacy `aft_hrp` answer reads back
as hand-release push-up, In a window, Army rules on, straight from the edition (`exerciseId`, `setType`,
`windowSeconds`), so `TestDraft(eventId, required, lastResult, windowSeconds)` needs no new field: the format and
the rules choice are in the id.

### 2.3 Army rules as versioned protocol metadata

`TestEvents.editions(exerciseId, format)` lists the authored editions for a pick: `aft_hrp` for the hand-release
push-up in a window, `aft_plank` for the plank hold, none for anything else. The switch on Your test picks the
edition; it is never on by default, so a plank is the user's own plank until they say Army. An edition carries its
form, faults, rest and ceiling verbatim and shows them under the fields; a future Navy or Marine plank is one more
edition with its own id and version, and the switch becomes a choice. Editions keep versions: a rule change is a
new version, and a result keeps the version it was scored under (case 48).

### 2.4 The generic rules line

One line per format, stored verbatim in the derived event's `form` (so a result's snapshot says what it was
scored under) and shown on Your test when no edition is on:
- In a window: "Your test's count in its window, under your test's own standard."
- Max unbroken: "Reps without rest until you stop, under your test's own standard."
- Max hold: "Held until form breaks or you stop." On a `pressureCaution` entry: "Held to 2:00 at most here. Keep
  breathing."
- Heaviest for N: "Trained under the Strength rules. No max attempts are scheduled."
`faults`, `rest` and `scoring` read "Your own." and "None: a typed required number.", as the custom editions do.

## 3. How the plan trains a whole test

### 3.1 Composition

No planner arm. `answers()` writes LIFT, RUN or BOTH (§1.1) and the existing `Planner.plan` arms run. With a run
and reps or hold events (cardio first, same visit; "+S" is the 25-minute strength block, "+P" the prep slots):

| Days | Rotation (Some or Racing) | Prep days |
|---|---|---|
| 1 | E+S+P | 1 |
| 2 | E+S+P, L(easy)+S+P | 2 |
| 3 | E+S+P, Q(test intervals)+S+P, L(easy), R | 2 |
| 4 | E+S+P, Q, E+S+P, L(easy), R | 2 |
| 5 | E+S+P, Q, E+S+P, Q2(tempo), L(easy), R | 2 |
| 6 | E+S+P, Q, E, Q2, E+S+P, L(easy), R | 2 |

A New runner has no Q2 (at 5 days: E+S, Q, E, E+S, L, R) and starts on the ladder, which caps the run row at
three days: at 4 days same visit that is three ladder days plus a rest, which Your plan already states through
`LadderWeeks`; the Together Why sheet says it too.

The run leads (cardio first) because it is the event that needs the longest build: a New runner's ladder weeks
plus four event weeks, against calisthenics gains that appear in four to eight weeks (§3.5). That is a
scheduling reason, practice; the same-session cost that argues for primary-first is Eddens 2018 and Schumann
2022, already cited on the Order screen. Separate days stays a choice on Together, defaulting to Same visit so
the quality run survives at four days (the two-day run row has no quality session).

Without a run the plan is the Lift layout with the prep: today's Lift-plus-tests plan. With a run and no reps or
hold events it is today's Run test plan, strength blocks and D8 included. A legacy lift-first Both test plan
reaches `bothDraft`'s lift-first path, which is fine: its prep comes from `liftDraft`'s layout (§3.4).

### 3.2 Lifting rules

The lift sub-goal is a draft value the entry's actions set; `answers()` passes `draft.goal` through as today,
and `slotGoal` keeps its two cases and gains none:

| Draft action | `goal` | Why |
|---|---|---|
| `chooseFitnessTest()`, or the last run and weighted picks removed | `GENERAL` | Today's Lift-plus-tests default; a push-up tester is not asked for ramped mains |
| A run picked | `STRENGTH`; on a draft whose branch is LIFT also `cardioFirst = true` | Parity with the Run door, whose blocks are Strength under D8; the run leads (§3.1) |
| A weighted lift picked | `STRENGTH` | The lift needs the Strength rules on its main |

Case 54's 1,182 s block is the Strength block that the run pick brings. Prefill of a legacy plan keeps the
recipe's goal and order untouched until the user changes a pick, and shows SUB_GOAL and ORDER when they differ
from this table (§1.1).

### 3.3 Weighted picks (heaviest for N)

Revision 4's deadlift chip, its `trap_dl`/`conv_dl`/`sumo_dl` list and its hex-bar line are gone; the rule is one
sentence per clause and holds for any of the eight `mainCapable` entries:

- Binding: a weighted pick is the main of its row (`mainRow(entry.pattern)`): Your lifts limits that row to the
  pick when it passes `availableWith(equipment, excluded)`. One pick per row; the sheet disables a second entry on
  a taken row with "One lift per row". When the pick fails `availableWith` (a Dumbbells preset and a barbell
  bench; every deadlift excluded on About), Your lifts keeps the row's ordinary default, and the lift is not
  trained: Your plan and the Fitness test card say so with the gear it needs.
- Storage: the recipe gains one optional key, `lifts: [{exerciseId, reps}]` (replacing revision 4's `testLift`),
  validated when present (a `mainCapable` library id, reps 1..10); the weight lives only in
  `PlanSettings.goalsLb[exerciseId]` (`createPlannedPlan` adds it to the settings it already writes). Prefill
  reads both back: `draftFrom(answers, recipe, settings, unit)`. A goal edit on the Plan screen is never shadowed
  by a stale recipe number. No `TestRef`, no `test_result` row and no new result payload: the result is the
  ordinary session log, which already stores weight and reps.
- Where it is trained: on a Lift plan and on Separate days the lift days carry it as a MAIN (ramped for A year or
  more under Strength and not 65+, else straight sets; 65+ at two sets of 6 to 10, `LiftRules.kt:21-31`). On Same
  visit the 25-minute strength blocks hold only the squat and the hinge (`strengthBlock`, RunLayout.kt:76 takes
  the main of row k or 1 − k as the SECONDARY), so a hinge pick is the SECONDARY (two sets) of block 1, absent at
  one day (only block 0, the squat) and on any day the dose ladder reaches step 2, a squat pick is block 0's
  SECONDARY, and a press or pull pick is not trained on Same visit. No new block layout. Separate days has its
  own cost: `bothDraft` gives a cardio-first plan `(days + 1) / 2` run days (BothLayout.kt:24-29), and the run row
  carries a Q session only from three run days up (RunLayout.kt:25-30), so at four days Separate days leaves the
  2-mile run with E and L and no interval day, and at one day Separate days is the run plan alone
  (BothLayout.kt:17-19). So the default flips to Separate days only at five or six days, where the run keeps
  E, Q, L, R and the two lift days carry the pick as a MAIN on one and its row's SECONDARY on the other
  (`DayLayout` spreads the mains over the lifting days, DayLayout.kt:66). The default is derived, never written by
  a pick (§1.2). At two to four days the default stays Same visit, the Together row for Separate days says "Trains
  {lift}, but leaves {run} no interval day at {days} days", and
  Your plan and the card say "not trained on Same visit: the strength blocks hold the squat and the hinge". At one
  day the line is "not trained at one day a week: the strength block holds the squat". The Together Why sheet says
  both in one line each. No max attempt is ever scheduled (§3.8).
- Met: one rule, `LiftTestProgress.best(reps, sets) = sets.filter { PersonalBests.counts(it) && it.reps >= N }
  .maxOfOrNull { it.weightLb }`, where `PersonalBests.counts` is already "done, not RAMP, non-zero"
  (PersonalBests.kt:20), so TOP, BACKOFF, WORK and EXTRA sets count and RAMP never does (`SetKind`, Types.kt:22);
  `met` when `best >= goalsLb[exerciseId]`. A ramped lifter's TOP set counts, so 275 × 5 TOP meets "heaviest for 5
  at 275"; 225 × 4 against "heaviest for 5 at 225" is not met; 225 × 5 WORK is (case 61). The sets come from the
  raw query, not the collapsed trajectory: `HistoryRepository.topSetHistoryFlow` reduces each session to one
  `TopSetRow` with its weight alone (HistoryRepository.kt:283-288), so a new `HistoryRepository.liftSetsFlow`
  maps `SessionDao.observeTrajectorySets()` to `LiftSetRow(sessionId, completedAt, exerciseId, weightLb, reps,
  kind, done)` for the recipe's lift ids, and `PlanViewModel` (the card) and `LogViewModel` (History) both feed
  it to `LiftTestProgress`. History's trajectory card today decides `goalMet` by top weight alone
  (JournalBuilder.kt:141); for a lift the recipe names, `JournalBuilder.trajectories` takes the recipe's N and the
  raw sets and uses the one rule, so the card and History give one verdict: a session with 230 × 1 TOP and
  225 × 5 BACKOFF meets "heaviest for 5 at 225" on both (case 61). Lifts with a typed goal and no test keep the
  top-weight rule.
- Display and edit: the Fitness test card (§4) gets a row per recipe lift from `goalsLb` and the log, whatever the
  slot's role or whether the program holds the lift: "Trap-Bar Deadlift, heaviest for 3 · Best 315 lb · Required
  340 lb", "not trained with this equipment (needs a trap bar)" when no day holds it. The card's editor edits the
  Required weight for these rows through the existing goal write, `PlanRepository.setPlanGoal(planId, exerciseId,
  goalLb)` (PlanRepository.kt:157), so a hinge SECONDARY or an untrained lift has an edit path; N changes only on
  Review answers (it is a recipe fact). With no `TestGoal` the card still shows for a lift-only test (it is built
  from the recipe and the settings).

### 3.4 Prep inside the budget

Today prep is appended after fitting and balancing, so on a strength-block day the easy run has already taken
the minutes and the prep always overflows; raising the minutes only lengthens the easy run. Change, in PR A, for
every plan with tests (new or existing):

- The layouts place the prep before fitting. `runDraft` appends `TestPrep.slots(answers)` to each strength block
  on the first three block days; `liftDraft`'s layout appends it to the first three lifting days. `withTestPrep`
  keeps only the goal and the `TestPrep` reason. One slot per exercise per day, in both layouts: when the block
  or the lifting day already picked the event's movement (a run plan's block 0 and the Lift layout's day A both
  pick the plank, `DayLayout` training 0 at `CORE_ANTI_EXT`), the prep slot replaces it, so a day never holds two
  plank slots as it does today.
- One helper, `TestPrep.reservedSeconds(day)`, sums the budget seconds of the day's linked slots
  (`prescription?.test != null`, `SessionBudget`'s own per-set arithmetic). `easyRunSeconds` gains a
  `reservedSeconds` parameter subtracted beside the 1,500 s strength allowance before the floor, and all three
  callers pass it: `runDraft` at creation (from the slots it just placed), `RunReplan.next` at a rotation wrap
  (from the stored day), and `CardioJudge` at Finish (from the stored day), so the easy run's growth stops at the
  reserved length. The lift-first Both reserve in `bothDraft` is untouched: its prep sits inside the lift days.
- `fitDay` skips prep slots at every step: they are never dropped and their sets are never cut. The ladder then
  trims the strength block around them and, when nothing fits, raises `OverBudget` with the final excess as it
  does today; no second reason is appended. Prep slots are never in the droppable list.
- `VolumeBalance.balance` skips prep slots in its cap pass and its floor pass, and leaves them out of the weekly
  credit, so every non-prep slot of a Lift plan equals the no-tests plan's, except a same-movement slot the prep
  replaces, on days with no `ShortSession`, and the three-set dose holds (case 52's seeds, case 58).
- `TestPrep.aim` and `target` key on `setType` and `timed` already, so a derived event trains with no new rule;
  `slot` changes in three places. (1) The rule's target equals the prescription for every rep format: today
  TestPrep.kt:44 sets `RepTarget.Fixed` from the `RepsInTime` aim but keeps the entry's own `targetReps` for a
  `Reps` target, so a max-unbroken pull-up would carry prescription 5 and rule 6 and the first full exposure would
  jump to 7 (Progression.kt:524); the fix sets the rule from the `Reps` aim too, and case 59 pins 5 → 6. (2)
  `Prescription(target, eachSide = entry.eachSide, test = ref)`: today's default `false` overrides the library flag
  (`eachSideOf`, ExerciseEntry.kt:87), so a side-plank or single-leg-stance prep would run one side; with the flag
  copied, training, the benchmark and `reservedSeconds` (each-side sets cost both sides, as `SessionBudget` prices
  them today) cover both sides. (3) `TestPrep.slots(answers)` places no slot for an entry that fails
  `availableWith(answers.equipment, answers.excluded)`: a pull-up pick on the Dumbbells, Kettlebells or
  Machines preset (no `PULLUP_BAR`, SetupAnswers.kt:19-25), an inverted row without a rack, a glute-ham raise
  without the machine, or anything excluded on About. The goal keeps the event, no benchmark can open (there is no
  linked slot), and Your plan and the card say "not trained with this equipment (needs a pull-up bar)", the same
  path as a weighted pick (§3.3). The aim needs no cap of its own: on a `pressureCaution` entry the anchor is at
  most `requiredLimit` (§3.8), so half of it is at most 1:00.

Arithmetic, from `SessionBudget` and `RestPolicy` (15 s setup per set, rest after every set, push-up ACCESSORY
90 s, sit-up and plank CORE 60 s):
- Push-ups and sit-ups in 1:00 (case 54): push-ups 3 × (15 + 60 + 90) = 495 s, sit-ups 3 × (15 + 60 + 60) = 405 s, total 900 s.
- AFT hand-release push-up and plank (case 55): HRP 3 × (15 + 120 + 90) = 675 s, plank at 110 s 3 × (15 + 110 + 60) = 555 s, total 1,230 s.
- Easy run on a block day: `max(1200, minutes × 60 − 300 − 1500 − prep)`. At 60 minutes with case 54's prep
  that is 1,200 s (the floor binds). Day A then holds 300 + 1,200 + 900 + 1,182 = 3,582 s, inside 3,600 with no
  trim; the interval day B holds 300 + 852 (4 × 123 + 3 × 120) + 900 + 1,140 = 3,192 s. At 45 minutes A's block
  budget is 300 s: step 1 leaves 540 s (squat 300, plank 240), step 2 leaves the 240 s plank, which fits;
  B's budget is 648 s and step 1 fits (498 s). At 30 minutes the ladder empties both blocks: A is over by
  300 + 1,200 + 900 − 1,800 = 600 s and B by 300 + 852 + 900 − 1,800 = 252 s (the interval trim loop checks the
  day without its lifts, 1,152 s, so the four reps stay).

### 3.5 Dates, tapers and the test week

- One date, typed once on Your test. `answers()` writes it to `run.dateEpochDay` when the run's goal shape takes a
  date (the existing guard in `runAnswers()`: never for Cooper, PACER and HAMR only with a score) and to
  `testDateEpochDay` when there are reps or hold events. When both are written they are one fact stored twice;
  the repository's date write (`PlanRepository.setGoalDate`, one transaction) keeps `goalJson.byEpochDay` and
  `testGoalJson.byEpochDay` equal on any plan whose run goal is `FITNESS_TEST` and that has a test goal, from
  either editor. No new `init` invariant: a legacy recipe or plan carrying two different dates stays readable and
  reads back as the run goal's date (the taper reads it); its first date save equalizes them.
- The run tapers as today (`Taper.factor`, last ten days at half WORK; Bosquet 2007 via audit row 15, weak).
- The reps and hold side has no taper beyond the existing quiet week (no benchmark in the last seven days, case
  49). The research found no trial on tapering before a calisthenics test; prep stays through test week. The Why
  sheet says so.
- `withTestWeeks`: a plan with reps or hold events and a date under four weeks away gets `PlanReason.DateTooClose`
  unless the run side already raised it (`TEST_WEEKS = 4`, practice: measurable push-up gains appeared after four
  weeks in Kotarsky 2018 and the research's structured programs ran 8 to 17 weeks).
- A run practice test two to three weeks out is coaching convention (preference) and is not built; a logged run
  already feeds `GoalProgress.bestTimed`. Follow-up, not in these PRs.

### 3.6 The beginner and the unknown baseline

- Unknown (Last blank): the first session on each prep day opens as a benchmark (case 49: due when no result),
  which gives a real count; prep aims at half the required number as today. Benchmarks never set training
  targets (council 2026-11-21), so the aim stays until the user types a Last count on Review answers. The Why
  sheet says the first session measures where you are.
- None yet (Last = 0, "none yet", a new allowed value: `TestAnswer.lastResult` 0..3600, `TestPrep.aim` floors at
  5 reps / 20 s): the prep slot takes the event's easier movement from a small table in `TestPrep` keyed by
  exercise, not event: `hand_release_pushup`, `pushup` → `knee_pushup`; `plank` → `knee_plank`; `pullup`,
  `neutral_pullup` → `negative_pullup` (a new bodyweight `V_PULL` REPS entry on `PULLUP_BAR`, the jump-up-and-lower
  rep), three new library entries. Each takes the last `subRank` of its pattern (`knee_pushup` H_PUSH 21 after
  `hand_release_pushup` 20, `knee_plank` CORE_ANTI_EXT 8 after `high_plank` 7, `negative_pullup` V_PULL 13 after
  `dead_hang` 12), because `ExerciseLibrary.byPattern` feeds `accessoryCandidates` (DayLayout.kt:205) and
  `mainChoices`; a test pins that no plan without tests on any of the six presets holds one of the three. Zero is
  offered only for entries in the table: `testAnswer` accepts
  `lastResult = 0` only when `TestPrep.easier(exerciseId)` is non-null, the field's floor is 1 elsewhere, and the
  Why sheet says "Type none yet only for push-ups, planks and pull-ups; for other events the plan starts at the
  floor". A user with no dips, handstand push-ups, pistol squats or Nordics still gets 3 × 5 at the floor;
  easier variants for those are a follow-up when a user asks. The slot keeps `Prescription(test = ref)` so
  the Plan card can say "Prep on knee push-ups"; the benchmark opens only when the slot's movement is the event's
  (the existing Start movement check), so a modified rep is never recorded as a test result. Moving on is by
  Review answers (type a Last count once the first reps come). The variation ladder is preference (research:
  incline, floor, then deficit or weighted; no trial ranks it).

### 3.7 Evidence lines for the Why sheets, graded as the research grades them

- Goal (the Fitness test row's part of the sheet): "Pick the events your test scores. Each is trained the way
  the evidence supports: the run gets intervals a little faster than goal pace, tempo and easy runs (interval
  training improves field-test performance: moderate, military data; the 95 percent factor is practice);
  push-ups, pull-ups and planks get the test movement itself, submaximal (in one small four-week trial, practicing
  push-ups beat bench pressing for push-up performance, Kotarsky 2018, moderate-to-weak; no trial covers planks or
  pull-ups); a lift uses the Strength rules. Point tables are not built in; type the number your test requires."
- Your test: the existing `setup_flow_why_tests` and `setup_flow_why_test` texts, joined and extended: benchmarks
  every three weeks, none in the last week (preference); interval reps aim slightly faster than the target pace
  (practice); a date tapers the run's last ten days (weak); no taper is known for push-ups or planks, so prep
  stays through test week; the half-anchor dose is practice; low-load push-up work without rep progression did
  not move endurance in eight weeks, so prep progresses its reps (Kikuchi 2017, weak). For sit-ups: core work
  without sit-ups held sit-up pass rates in 2,616 soldiers (Childs 2009, strong for that narrow claim), so the
  prep is practice of the test's counting and pacing, not volume. Holds: training holds end at their target;
  only the benchmark runs open (no trial shows failure-length holds beat submaximal ones, isometric notes §1,
  weak-contested; test protocols run to failure with a ceiling, §2, moderate for reliability). The wall sit is
  held to a cap and never to failure (pressure rises during a large-muscle hold: the 2021 safety meta-analysis,
  moderate; excessive rises during wall squats in older resistant-hypertensive adults, weak; the submaximal
  4 × 2 min protocol is what the trials used, Edwards 2023, whose strong grade is for resting-pressure reduction;
  the 2:00 cap itself is practice). A
  weighted lift is trained under the Strength rules with no scheduled max attempt (novices under-rate true
  maximal efforts, Zourdos 2016 and Ormsbee 2019, moderate; no autoregulation trial covers novices or older
  adults, a gap). Then the not-supported list (§2.1).
- Your plan: the existing `TestPrep` plural; the ladder length; `LadderDateTooClose(fitnessTest = true)`; "Prep on
  knee push-ups" when §3.6 applies; the per-event dose lines (§3.9).

### 3.8 Safety defaults, each with its evidence grade

- No scheduled max attempt on a weighted lift, for anyone. Under a year or 65+: straight sets, as today
  (`LiftRules.kt:21`); a year or more under Strength: the ramped MAIN with its TOP set. The goal is met by a logged
  set, never by an attempt the plan asks for. Evidence: novices under-rate near-max efforts (Zourdos 2016,
  Ormsbee 2019, moderate); no outcome trial in novices or older adults (gap); the rule itself is practice.
- A `pressureCaution` hold (the wall sit) gets a capped hold, not a max hold. One limit,
  `TestEvents.requiredLimit(event)` = the entry's `holdCeilingSeconds` on a `pressureCaution` entry, else 3600,
  binds every holder of the number: `TestEventGoal.required` and `TestAnswer.lastResult` in their `init` (the
  domain refuses a recipe or answer above it; no shipped recipe can carry one, since the wall sit was never an
  event before; the `init` looks the event up through `TestEvents.find` and the library, which a model class does
  nowhere else, so it carries a one-line comment saying the cap is a safety rule the number must never escape, and
  an unknown id takes the 3600 default), `testAnswer` on Your test, and `TestGoalDraft.requiredValue` on the Plan screen's editor
  (TestGoalDraft.kt:14, today 1..3600). The prep aim then needs no cap: half an anchor of at most 2:00 is at most
  1:00, under the 2-minute holds the pressure trials used. Finish's hold ceiling, `max(entry cap, required)`
  (TrackerRepository.kt:2502), never exceeds the cap because Required cannot. The derived event's `ceilingSeconds`
  is the cap and the benchmark row is a hold at it. "Keep breathing" stays under its clock (#643). Evidence: the
  trials with outcome data held the wall squat for a fixed, submaximal 2:00 (Edwards 2023 protocol description;
  its strong grade is for resting-pressure reduction, not the acute response); pressure rises during a hold, more
  with large muscles and higher intensity (2021 safety meta-analysis, moderate); excessive rises during wall squats
  in older resistant-hypertensive adults (weak); the isometric notes' default "wall sit: target-time, submaximal,
  no max hold" is an inference (preference). The 2:00 cap is the heaviest dose the evidence used; the half-anchor
  prep is practice.
- One pure rule decides how far a benchmark hold may run and what stop it writes, and nothing transient feeds it.
  Today the writers (`DayViewModel:1987`, `:2253`) test `seconds >= HoldClock.limitSeconds(seconds, true)`, which is
  `max(seconds, 600)`, and the saved `KEY_HOLD_PAST_TARGET` cannot replace that: `toggleDone` calls `clearHold()`
  (`:2236`, which removes the key at `:3053`) before `logTestResult` runs, and a later edit (`updateBenchmarkResult`,
  `:1977`) has no hold at all. So `Benchmark` gains `holdLimit(event, pressureCaution) = HoldClock.limitSeconds(
  event.ceilingSeconds ?: HoldClock.PAST_TARGET_LIMIT_SECONDS, pastTarget = !pressureCaution)` (the same seconds
  `target` uses at Benchmark.kt:24, so it needs no `ref`) and `stop(event, pressureCaution, row)`:
  `REPS_IN_WINDOW` → `TIME_UP`, `REPS_CONTINUOUS` → `STOPPED`, `MAX_HOLD` → `LIMIT` when `row.seconds >=
  holdLimit`, else `STOPPED`. The clock gate at `:1741` uses the same inputs (`runPastTarget = (benchmark != null
  || pastTarget) && !pressureCaution`, and a benchmark clock's limit is `holdLimit`), and both writers call `stop`
  with the card's `prescriptionTest.event` and `holdPressureCaution`, which the edit path has as well. The AFT plank
  (target 220, past target on, limit 600) writes `LIMIT` at 600, `STOPPED` at 192 and at a hand stop of 300, and an
  edit to 590 or 300 keeps `STOPPED` (case 51 and the existing 590 edit test stand; the 300 s rows are pinned in
  case 60); the wall sit (target 120, past target off, limit 120) writes `LIMIT` at 120 (case 60).
- Every other hold: prep ends at its target (#643 default; preference, evidence-consistent); the benchmark runs
  past its target to the 10-minute limit, with the edition's ceiling shown and buzzed once (case 51; test
  protocols, isometric notes §2, moderate). An each-side entry (side plank, single-leg stance) preps and benchmarks
  both sides and records the shorter, the way an each-side training hold is logged (#628): the prep prescription
  carries `entry.eachSide` (§3.4) and `DayViewModel:3304` drops its `eachSide = false` override. Springer 2007
  scores each leg (moderate); the shorter side is the conservative result (practice).
- Submaximal prep by default: half the anchor, floors 5 reps and 20 s (preference, from Kotarsky's progression
  and the research's 50 to 70 percent convention); all-out effort only in the benchmark every 21 days
  (preference).
- Easier variants are recorded separately: the knee push-up and knee plank slots keep the link for display and
  never open as a benchmark (§3.6, case 57).
- A 65+ user's balance picks keep Otago's support rule in the Why sheet: "stand beside something stable"
  (Otago, strong for the program; the line is preference).

### 3.9 Dose lines on Your plan and the card

Your plan lists one line per pick, derived from the preview's program (slots with `prescription.test`, the
weighted pick's slot by exercise id, the run's layout), not from a new `PlanReason`:
- "Push-ups: 3 × 15 in 1:00 on days A and B"; "Plank: 3 × 1:50 on days A and B"; "Pull-ups: 3 × 5 on day A"
  (one prep day at one day a week).
- "Trap-Bar Deadlift: 2 × 5 on day B"; "Barbell Bench Press: not trained with this equipment (needs a barbell,
  bench and rack)"; "Barbell Bench Press: not trained on Same visit: the strength blocks hold the squat and the
  hinge"; "Barbell Bench Press: not trained at one day a week: the strength block holds the squat"; "Pull-ups: not
  trained with this equipment (needs a pull-up bar)".
- "2-mile run: intervals on day B, easy runs on A and C"; "Cooper run: trained with the test intervals; no
  distance goal yet"; "PACER: run the test with your school's audio."
The Fitness test card shows the same reasons on its rows once the plan exists.

## 4. The old paths, drafts and the Plan screen

| Path | Decision | Why |
|---|---|---|
| `RunGoal.FITNESS_TEST` row under Run and Both | Fold: the row leaves `RunGoalStep`; the constant, the planner and the recipe keep it | New plans reach the same rules through the entry; existing plans keep their recipes |
| `SetupStep.TEST` on Lift and Both | Fold: `offersTests(draft) = draft.fitnessTest || draft.tests.isNotEmpty() && (LIFT or non-sprint BOTH)` | New drafts get the events step only through the entry; a legacy draft or a mixed-goal plan (a 5K goal plus push-ups) keeps its events step, so nothing is dropped |
| `SetupAnswers.init` acceptance of `tests` on LIFT and BOTH | Keep | Unchanged; it is what the entry writes |
| The EVENT screen's `FITNESS_TEST` block | Move to Your test as the run picks' fields | Same fields, one screen |
| `TestStep`'s chip loop over `TestEvents.all` | Replace with the picker sheet and per-pick blocks | `all` is the edition table now |
| The Plan screen's two cards | Keep both; share the date | The cardio card keeps its target editor and shuttle attempts, the test card its required numbers; a run-only test has only the cardio card. The test card gains the lift rows (§3.3) and shows whenever the recipe carries `lifts` or a `TestGoal` exists |

Drafts and the entry flag:
- `SetupDraft` gains `fitnessTest: Boolean? = null` (null only in a draft written before this field existed; a new
  draft always stores true or false, and readers test `== true`), `testRun: Boolean = false` (a run is picked;
  `test` names which), `testLifts: List<LiftTestDraft> = emptyList()` (`LiftTestDraft(exerciseId, reps, goalLb)`), and keeps
  `tests: List<TestDraft>` as is: a derived or edition id in `eventId` carries the format and the rules choice.
  While `fitnessTest` is set, `answers()` derives the branch from `draft.branch` and the picks (§1.1), writes the
  date per §3.5, and passes `goal`, `cardioFirst`, `together`, `strengthBlocks` and `mains` through unchanged.
- `chooseFitnessTest()` sets the flag, `branch = RUN`, `runGoal = FITNESS_TEST`, `cardioFirst = true`,
  `goal = GENERAL`, step GOAL. Picks set `goal` per §3.2. `setBranch` clears the flag and the entry's answers
  (`tests`, `testDate`, `testRun`, `testLifts`) and resets `runGoal` to `CARDIO_FITNESS` when it was
  `FITNESS_TEST`, so no retired door is reachable with a half-answered test.
- Actions: `onFitnessTest`, `onTestPick(TestPick)` (adds with the first eligible format; a run pick sets `testRun`
  and `test`, and the sheet disables the other runs while one is picked; a weighted pick on a press or pull row
  never writes `together`: the default is derived in `answers()`, §1.2), `onTestRemove(id)`, `onTestFormat(exerciseId, TestFormat)` (swaps the
  derived id, keeps required and last, clears `windowSeconds`), `onTestEdition(exerciseId, editionId?)` (the Army
  switch), `onTestWindow`, `onTestRequired`, `onTestLast` as today, `onLiftReps(exerciseId, Int)`,
  `onLiftGoal(exerciseId, Double?)`, `onTestDate`.
- `SetupDraftJson.decode` normalizes a legacy draft, recognized by `fitnessTest == null` (kotlinx cannot tell a
  missing `Boolean` key from `false`, so the field is nullable; `SetupDraft.VERSION` stays 1, since `decode` rejects
  any other `v` today and a bump would reset every saved draft): `runGoal == FITNESS_TEST` on RUN or non-sprint BOTH (with or
  without tests), or `tests` non-empty on LIFT, becomes `fitnessTest = true` with `branch`, `goal`, `cardioFirst`,
  `together`, `strengthBlocks`, `test`, `goalSeconds`, `testScore`, `tests`, windows and the date (`eventDate ?:
  testDate`) kept, and `testRun` synthesized as `runGoal == FITNESS_TEST` (the field does not exist in a legacy
  draft, SetupDraft.kt:45, and its default `false` would drop the run), and on a BOTH draft a null `together`
  filled from `SetupAnswers.together()` over the draft's days, goal, experience and `cardioFirst`, for the same
  reason as `draftFrom` (§1.2). A new draft whose user removed the run stores `testRun = false` with the key
  present and is not touched. A BOTH draft with another run goal plus tests
  stays a Both draft and keeps its events step (the fold rule above). A saved step of RUN_GOAL or EVENT maps to
  TEST. A `TestDraft` whose id no longer resolves is dropped (today's `?: return@forEach`).
- `draftFrom(answers, recipe, settings, unit)` (prefill, Review answers), with the recipe's `lifts` read beside the
  answers in `PlanEntity.setupAnswers`, applies the same mapping: RUN with `FITNESS_TEST`, LIFT with tests, BOTH
  with `FITNESS_TEST` (with or without tests), or any recipe carrying `lifts` (a lift-only test) become the
  entry's draft with everything kept: `testLifts` from the recipe with `goalLb` from `goalsLb[exerciseId]` in the
  user's unit, and `together = answers.both.together ?: answers.together()` on a BOTH recipe, so the recipe's
  resolved default becomes the draft's explicit choice and `testTogether` never applies to it (§1.2). On Review
  answers nothing changes for those plans: the same shape, the same answers, the same
  screens (§1.1); a Both/Muscle plus 2-mile plan stays Both, Muscle, lift first, with its run test. A BOTH plan
  with another run goal plus tests is not converted.
- Downgrade: no new `SetupStep` constant, and `SetupDraftJson` ignores unknown keys, so an older build reads a
  new draft (without the entry's fields) and a new plan's recipe (`lifts` is an optional key `SetJson` ignores).
  An older build's `TestEvents.find` returns null for a derived id, so its `TestStep` drops that block and its
  `benchmarkOpening` and card skip the event; a result row keeps its snapshot and is readable. Play never
  downgrades anyway.

### 4.1 Data model

Domain: `TestFormat { REPS_IN_WINDOW, REPS_CONTINUOUS, MAX_HOLD, HEAVIEST_FOR_REPS }` (stored by name inside
derived ids) with `setType: TestSetType?` (null for the weight shape) and `TestFormat.eligible(entry):
List<TestFormat>`; `TestPick` sealed (`Library(entry)`, `Run(FitnessTest)`); `TestEvents.derived`, `derivedId`,
`editions`, `requiredLimit`, `find` extended; `Benchmark.target`, `row` and `runnable` for `REPS_CONTINUOUS`
(`Reps(0)`, one empty WORK row), `Benchmark.holdLimit` and `Benchmark.stop`; the pressure-caution ceiling in
`derived`; `TestAnswer.lastResult` 0..3600 and the `requiredLimit` check in `TestEventGoal` and `TestAnswer`;
`TestPrep.slots` (gear check, `eachSide`, the rule target from the `Reps` aim), `TestPrep.reservedSeconds`,
`TestPrep.easier` (the table by exercise); `easyRunSeconds(reservedSeconds)`; `fitDay` and `VolumeBalance` prep
exemptions; `TEST_WEEKS`; `LiftTestProgress` (`PersonalBests.counts` plus the reps); library `knee_pushup`,
`knee_plank`, `negative_pullup` at trailing `subRank`s. No new branch, no `slotGoal` case, no catalog
of tests, no `custom_plank_hold` (revision 4's entry is the derived `plank:MAX_HOLD`). The union search lives in
`:app` (`TestPickSearch` in `ui/wizard`): the run labels are string resources (SetupFlowRun.kt:154-158) and
`:domain` has no second copy of them; it calls `ExerciseSearch` and `TestFormat.eligible`.

Data: recipe stays v2 with one optional `lifts` key, written by `encode`, validated as optional; `TestGoalDto`
stays v1 (a derived id is a string in `eventId`); `test_result` unchanged (a max-unbroken result is `count` with
`STOPPED`, a shape the table and the v35 validator already accept). Room 34 unchanged. Backup stays at 35: the
v35 validator checks a result's identity against its own snapshot, never against `TestEvents.all`, so a derived id
imports as it is; the recipe's new key passes `SetJson`'s unknown-key tolerance on an older validator and is
validated on the new one; nothing new is carried outside the recipe string and the settings. No new weight or
distance payload exists to version (§3.3). `PlanRepository.setGoalDate(planId, date)` writes both goal columns in
one transaction; `setPlanGoal` (PlanRepository.kt:157) is the lift rows' weight write. `HistoryRepository.liftSetsFlow`
over `SessionDao.observeTrajectorySets()`. `CardioJudge` passes the stored day's reserve.

App, benchmark execution (ships in PR A with the domain changes, §6): both writers at `DayViewModel:1986` and
`:2252` call `Benchmark.stop(event, holdPressureCaution, row)`; the clock gate at `:1741` and the benchmark clock's
limit use `Benchmark.holdLimit`; the each-side benchmark at `:3304`; `JournalBuilder.trajectories` taking the
recipe's N and the raw sets for one met verdict; `TestGoalDraft.requiredValue` bound by `requiredLimit`.

App, setup (PR B): no new `SetupStep`; the draft fields above; the actions above; `TestPickSearch`; `steps()`,
`offersTests`, `answers()`, `draftFrom`, `canAdvance(TEST)`, `goalTimeFloor` for the entry; the test card's lift
rows and their weight editor; `planName`: today's names ("Fitness test, N days" on RUN, "Lifting and Fitness
test, N days" on BOTH), and the same "Fitness test" label on a LIFT plan built through the entry.

## 5. Pinned numbers (spec §11.2)

No pinned number moves. Cases 21, 48, 50, 51 and 53 are untouched. Case 49's sentence gains a third row shape
(PR A): "one WORK row (0 reps for a window or a max-unbroken set; 220 s for the AFT plank)". Reason: the
max-unbroken benchmark is built in PR A; the existing numbers stand.

- Case 52: numbers kept (HRP 40 seeds 20 in 2:00, plank 220 seeds 110 s, custom sit-ups 45 in 1:00 seed 23,
  three sets on at most three lifting days). PR A amends only the placement sentence: prep is placed by the
  layouts before fitting and is exempt from the dose ladder and volume balance. PR B amends the entry sentence
  ("Lift setup offers Fitness test after Sub goal; non-sprint Both setup offers Fitness test after Run goal"
  becomes "Setup offers Fitness test on the first screen; Your test adds events from one picker over the library
  and the runs, each with a format") and moves `SetupFlowViewModelTest > a Lift plan with a test goal is created
  with its goal and prep slots` to the entry. Reason: the owner's 2026-11-24 and 2026-11-27 requests.
- Case 54, new (PR A): a 1.5-mile, push-ups and sit-ups test picked by hand (push-up and sit-up in a 1:00 window,
  which derive to `custom_pushup_window` and `custom_situp_window`). Both, cardio first, same visit, Strength
  rules, three days, 60 minutes, full gym, Some runner with a 25-minute nonstop run, 1.5-mile required 13:00
  (780 s), push-ups required 30 in 1:00, sit-ups required 35 in 1:00, date 84 days out. Plan: A easy run plus
  strength block plus prep, B test intervals plus strength block plus prep, C easy run, D rest. Prep on A and B:
  push-ups 3 × 15 in 1:00, sit-ups 3 × 18 in 1:00, 900 s. A: easy run 1,200 s, total 3,582 s. B: four 400 m reps
  at 123 s (`780 × 400 / 2414 × 0.95`) with 120 s recoveries, 852 s, total 3,192 s. C: easy run 3,300 s. No
  `ShortSession`, no `OverBudget`; `fullDoseMinutes` = 60. Goals: `Timed(ROAD_RUN, 2414, 780, date)` and a
  two-event `TestGoal` with the same date; no `DateTooClose`. Tests: `PlannerTestPrepTest > case 54 a run and
  calisthenics test is a run-first plan with prep inside the budget`, `PlanRepositoryTest > case 54 the date write
  moves both goals in one transaction`, `CardioJudgeTest > case 54 day A at full success keeps its 1,200 s stop`.
- Case 55, new (PR A): the Army events picked by hand, Army rules on for the push-up and plank. Both, cardio
  first, same visit, Strength, four days, 60 minutes, full gym, A year or more, New runner with no longest run,
  `aft_hrp` 40 (last 31), `aft_plank` 3:40, 2-mile 16:00, trap-bar deadlift heaviest for 3 at 340 lb, date 70 days
  out. The ladder row applies (three ladder days at level 1, 1,500 s each, plus a rest). Prep on A and B: HRP
  3 × 16 in 2:00 (`(31 + 1) / 2`), plank 3 × 110 s, 1,230 s; the prep plank replaces block 0's plank. A:
  300 + 1,500 + 1,230 + 300 (the squat after step 1) = 3,330 s, `ShortSession(A, 1)`. B: 300 + 1,500 + 1,230 + 498
  (trap-bar deadlift 300 and the core slot 198 after step 1) = 3,528 s, `ShortSession(B, 1)`, the deadlift kept as
  the hinge SECONDARY at 2 × 5 under Strength rules; `goalsLb["trap_dl"] = 340`, recipe `lifts = [trap_dl: 3]`;
  `LadderWeeks(8)`; `DateTooClose` (eight ladder weeks plus four event weeks need 84 days). After the ladder the
  400 m aim is 113 s (case 21). Tests: `PlannerTestPrepTest > case 55 a four-event test ladders a new runner, preps
  the push-up and plank and keeps the deadlift goal`, `PlanScreenBuilderTest > case 55 the test card shows the
  deadlift row from the typed goal`, `SetupFlowStateBuilderTest > case 55 a dumbbell gym leaves the deadlift
  untrained and keeps its requirement`.
- Case 56, new (PR A): case 54 at 30 minutes. The ladder empties both strength blocks, the prep stays:
  `OverBudget(A, 600)` and `OverBudget(B, 252)`, Your plan shows "Over by 10 min" and "Over by 5 min", the
  droppable list is empty; `fitDay` also leaves `ShortSession(A, 6)` and `ShortSession(B, 6)`, which the test
  checks beside the `OverBudget` reasons. Test: `PlannerTestPrepTest > case 56 prep that does not fit reports the
  day over and is never droppable`.
- Case 57, new (PR A): none yet. `aft_hrp` required 40, last 0: the prep slot is `knee_pushup` 3 × 5 in 2:00 linked
  to `aft_hrp`; no benchmark opens on it; a Last of 12 on Review answers returns the slot to the hand-release
  push-up at 3 × 6. Test: `PlannerTestPrepTest > case 57 a zero last count preps the easier movement at the floor`,
  `StartBenchmarkTest > case 57 a prep slot on another movement never opens as a benchmark`.
- Case 58, new (PR A): the Lift regression. Lift, General fitness, six days, 90 minutes, full gym, with a plank
  max hold (derived `plank:MAX_HOLD`, Army rules off, required 220 s) and sit-ups in 1:00 (required 45). The prep
  slots keep three sets each on the first three lifting days. Every non-prep slot equals the no-tests plan's,
  except a same-movement slot the prep replaces, on days with no `ShortSession`: day A's ordinary two-set plank is
  replaced by the linked prep plank, so the non-prep core count is 10 sets where the no-tests plan has 12, and
  the cap pass that trims those core sets never touches a prep slot, the floor pass never grows one, and neither
  counts prep as credit. The equality depends on the budget not binding, which holds at 90 minutes (the
  full-body General days are about 1,900 s plus the prep). Test: `PlannerTestPrepTest > case 58 prep is invisible
  to volume balance`, plus the same assertion in case 52's test at 60 minutes, worded the same way.
- Case 59, new (PR A): derived identity and the max-unbroken benchmark. Pull-ups, max unbroken, required 20, last
  8, full gym: `TestEvents.find("pullup:REPS_CONTINUOUS")` is a v1 event on `pullup` with `REPS_CONTINUOUS`, null
  window, `custom = false`, name "Pull-Up / Chin-Up, max unbroken"; `TestEvents.derivedId("pushup",
  REPS_IN_WINDOW)` is `custom_pushup_window` and `derivedId("situp", REPS_IN_WINDOW)` is `custom_situp_window`;
  `find("aft_hrp")` is the edition; `find("pushup:REPS_IN_WINDOW")` (non-canonical), `find("plank:REPS_IN_WINDOW")`,
  `find("farmers_carry:MAX_HOLD")` and `find("box_jump:REPS_CONTINUOUS")` (ineligible) are null; every eligible
  derived id is 40 characters or fewer. A draft that started In a window with 1:00 and switched to Max unbroken
  carries no window: its `TestRef` is `("pullup:REPS_CONTINUOUS", 1, null)`. The prep slot is pull-ups 3 × 5
  (`(8 + 1) / 2 = 4`, floor 5) as `Reps(5)` with the rule's target `Fixed(5)`, and one full exposure moves it to 6
  (Progression's bodyweight step), not 7. On the Dumbbells preset no slot is placed, the goal keeps the event, and
  Your plan says "Pull-ups: not trained with this equipment (needs a pull-up bar)". A due benchmark opens as one
  WORK row of 0 reps with the heading "Pull-Up / Chin-Up, max unbroken" and no aim; logging 14 writes count 14,
  window null, `STOPPED`, with the derived rules snapshot. A v35 backup carrying that result imports. Tests:
  `TestEventsTest > case 59 a derived event resolves by exercise and format and the two custom ids stay the derived
  spelling`, `SetupFlowStateBuilderTest > case 59 switching to max unbroken clears the window`,
  `PlannerTestPrepTest > case 59 a max unbroken prep rule starts at its aim and a missing pull-up bar places no
  slot`, `FinishTestPrepTest > case 59 a full max unbroken exposure moves the aim from 5 to 6`, `BenchmarkTest >
  case 59 a max unbroken benchmark row is one empty WORK row`, `DayViewModelBenchmarkTest > case 59 a max unbroken
  benchmark writes its count as STOPPED`, `BackupCodecTest > case 59 a derived test result imports at v35`.
- Case 60, new (PR A): the capped hold and the stop rule. Wall sit, max hold, required 1:30, last 1:50, 65+ lifter,
  Lift, General, three days: the derived `wall_sit:MAX_HOLD` carries `ceilingSeconds = 120` and `requiredLimit` 120;
  prep is 3 × 55 s (`110 / 2 / 5 × 5`) ending at target; `Benchmark.holdLimit` is 120 (`limitSeconds(120, false)`);
  the benchmark row is a 120 s hold, the clock ends at 120 and writes `LIMIT`, never running past; a stop at 95 s
  writes 95 `STOPPED`. `TestEventGoal(ref, 130)` and `TestAnswer(goal, lastResult = 130)` on this event throw;
  Your test and the Plan screen's editor refuse Required or Last above 2:00. The AFT plank's `holdLimit` is 600
  (`limitSeconds(220, true)`): its benchmark writes `LIMIT` at 600, `STOPPED` at 192 and `STOPPED` at a hand stop
  of 300, and an edit of a 600 s result to 590 or to 300 writes `STOPPED` (the existing 590 edit test stands). A
  side plank max hold preps and benchmarks both sides and records the shorter. Tests: `TestEventsTest > case 60 a
  pressure caution hold derives with its cap and refuses numbers above it`, `BenchmarkTest > case 60 the hold limit
  is the cap on a pressure caution entry and ten minutes otherwise`, `PlannerTestPrepTest > case 60 a capped hold
  seeds from its anchor and an each side hold keeps both sides`, `StartBenchmarkTest > case 60 a pressure caution
  benchmark opens at its cap`, `DayViewModelBenchmarkTest > case 60 a capped hold benchmark ends at the cap and
  writes LIMIT`, `DayViewModelBenchmarkTest > case 60 an AFT plank stopped by hand at 300 s writes STOPPED and keeps
  it on edit`, `SetupFlowStateBuilderTest > case 60 a required or last hold above the cap does not advance`,
  `PlanTestGoalScreenTest > case 60 the editor refuses a required hold above the cap`.
- Case 61, new (PR A): heaviest for N. Barbell bench press, heaviest for 5, required 225 lb, Under a year, Lift,
  Strength, three days, full gym: the recipe carries `lifts = [bb_bench: 5]` and `goalsLb["bb_bench"] = 225`; the
  press row is the bench and its MAIN is straight sets (no ramp) at 3 × 5; `LiftTestProgress.best` over done sets
  205 × 5 WORK, 225 × 4 WORK and 215 × 6 WORK is 215, not met; adding 225 × 5 meets. For A year or more the same
  plan ramps the bench, and a done 230 × 5 TOP set meets while 240 × 3 RAMP never counts. The card row reads
  "Barbell Bench Press, heaviest for 5 · Best 215 lb · Required 225 lb", and History's bench card gives the same
  verdict from the same raw sets (not met at 225 × 4; a session holding 230 × 1 TOP and 225 × 5 BACKOFF meets on
  both). With the Dumbbells preset the row is "not trained with this equipment (needs a barbell, bench and rack)"
  and the requirement is kept. With a 2-mile run added (Both, cardio first, Some runner): at four days the default
  stays Same visit, no block holds the bench, Your plan reads "not trained on Same visit: the strength blocks hold
  the squat and the hinge", and the Together row for Separate days reads "Trains Barbell Bench Press, but leaves
  the 2-mile run no interval day at four days" (two run days: E, L); at five days the default is Separate days, the
  three run days are E, Q, L, R with the interval day kept, and the two lift days carry the bench as a MAIN on one
  and the press SECONDARY on the other. The forward flow is pinned: pick the bench on Your test (days still 3),
  set four days on Your week, `answers().both.together` is Same visit; set five, it is Separate days; tap Same
  visit on Together at five days and change to six, it stays Same visit. Case 55 (hinge pick, four days) stays
  Same visit. Tests:
  `PlannerTestPrepTest > case 61 an under a year lifter's test lift is straight sets with no attempt`,
  `LiftTestProgressTest > case 61 a weight goal is met by any non ramp set with the reps`, `PlanScreenBuilderTest >
  case 61 the card shows the lift row from the recipe and the log`, `JournalBuilderTest > case 61 History's goal
  verdict matches the card for a test lift from the raw sets`, `SetupFlowStateBuilderTest > case 61 a run plus
  bench test derives Same visit at four days and Separate days at five, and keeps an explicit choice`,
  `SetupFlowStateBuilderTest > case 55 a hinge pick at four days derives Same visit`, `PlannerBothTest > case 61 the
  five day Separate plan keeps the interval day and trains the bench`.

## 6. PR breakdown

Two PRs, each shippable.

PR A, domain, data and benchmark execution (Opus or codex from this spec): `TestFormat` and eligibility,
`TestPick`, `TestEvents.derived`/`derivedId`/`editions`/`requiredLimit` and the extended `find`, the max-unbroken
benchmark in `Benchmark`, the pressure-caution ceiling, prep placed before fitting and exempt from the ladder and
volume balance, `TestPrep.reservedSeconds` and the easy-run reserve at creation, at a rotation wrap and at Finish,
`TestPrep.slots` (gear check, `eachSide`, the `Reps` rule target, the capped aim), `lastResult` 0 and the
easier-movement table by exercise, library `knee_pushup`, `knee_plank` and `negative_pullup`, `TEST_WEEKS`, the
recipe key `lifts`, `createPlannedPlan` writing `goalsLb`, `setGoalDate`, `LiftTestProgress`, `benchmarkOpening`
accepting `REPS_CONTINUOUS`, `HistoryRepository.liftSetsFlow`, the no-tests-unchanged library test, and the
execution fixes in `:app` that those formats need the moment they can exist: `Benchmark.stop` in both writers,
`Benchmark.holdLimit` in the clock gate, the each-side benchmark, `JournalBuilder.trajectories` taking the recipe's
N and the raw sets, `TestGoalDraft.requiredValue` bound by `requiredLimit`. They
ship together because a v35 backup can carry a plan whose links are derived events before any screen writes one:
`benchmarkOpening` would then open a max-unbroken or capped benchmark that the old view model records as `TIME_UP`
or runs past its cap. Cases 54 to 61's tests, case 49's sentence and case 52's placement sentence in the spec and
`docs/PLAN.md`. PR A changes existing test plans
only where something rebuilds a day: Review answers and Reset day (a Lift day) now place the prep inside the
budget, so a day that ran over silently is trimmed around the prep or says "Over by N min", and a day that held
two plank slots keeps one. A stored plan is otherwise untouched: `RunReplan.next` returns null for a
`FITNESS_TEST` plan except at the ladder exit (no phases), Finish never shrinks a run, so an existing Some or
Racing test plan keeps its grown easy run and its overflowing day until the user rebuilds it; Finish only stops
further growth at the reserve. Lift plans' other slots stay as they are (case 58); the pinned seeds stay, and PR A
runs case 52's tests. No setup screen writes a derived id or a `lifts` key yet; the only way one reaches a device
before PR B is a backup written by a PR B build, and PR A already executes it correctly.

PR B, app (Sonnet-grade screens; the picker's union search, the builder mapping, draft normalization and the date
write want Opus review): the Goal row, Your test (the picker sheet with search and filters, `TestPickSearch`,
per-pick blocks, the format segmented button, the Army rules switch, Window, Required, Last, Heaviest for N,
Required weight, the run fields moved from Your event, one run per test, "none yet" where an easier movement
exists, one date, the hint), Your lifts' row binding for a weighted pick, ABOUT/WHERE/TOGETHER/LIFTS/RECENT gating,
`chooseFitnessTest`, the pick actions setting `goal`, `cardioFirst` and (at five or six days) `together`, the
Together row's trade-off line, `setBranch` clearing,
`SetupDraftJson.decode` normalization with the `testRun` synthesis, `draftFrom(answers, recipe, settings, unit)`,
the Run goal row removed, `offersTests` folded, plan names, Why sheets with the graded lines and the
not-supported list, the dose lines on Your plan, the test card's lift rows with their weight editor and the shared
date edit from both cards, strings, pilots, the `SetupFlowPreviewShotsTest` renders for the two changed screens
and the sheet, DESIGN.md (the 2026-11-23 #646 setup line gains "superseded 2026-11-27: one picker over the library
and the runs"), wizard design revision 58, case 52's entry sentence, cases 59 to 61's builder tests, and Review
answers round-trip tests on a Both/Muscle plus 2-mile plan at four days (keeps Sub goal, Order, Your lifts and
Recent numbers, and `together` stays Separate days through Review answers and a save), on a lift-only test (restores the pick, N and the Required weight), on a legacy `aft_hrp` plan (restores
hand-release push-up, In a window, Army rules on, window 2:00) and on a legacy Run test draft (keeps its run).

## 7. Owner decisions (2026-11-24)

Decided on the revision 3 design: (1) no named tests, the user builds the test from the events; (2) the deadlift
stays a pickable event with its required weight, bound to a weighted deadlift, untrained when the gear has none;
(3) the two old doors retire as designed.

## 8. Decision log

- 2026-11-27, owner, on how the user builds the test: "it shouldn't be a subset or separate from the exercise
  (or cardio) lists we have now, it should be ONE cohesive exercise database (and we can reuse the search
  functions we have for it)", and "a selector that searches strength, cardio, and maybe 'holds' (for planks etc)
  - that would alleviate the friction of searching both sides into one list. (also that wouldn't be that bad
  isn't that just a join anyway?)". Named-test presets stay out (2026-11-24 stands). Council (Opus and Sol,
  take 2): agree with changes, folded in §9.

## 9. Changes from revision 4

Replaced: the ten chips (five runs, four catalog events, one deadlift) with one picker over the library and the
runs, filtered All / Strength / Cardio / Holds, searched with `ExerciseSearch`; a pick plus a format is the event
(§1.2, §2). The deadlift chip, its three-deadlift hinge list, its hex-bar line and the recipe's `testLift` with
heaviest-for-N on any of the eight `mainCapable` entries, the recipe's `lifts` list and `goalsLb`, met only by a
logged set with the weight and the reps (§3.3, case 61). Revision 4's `custom_plank_hold` entry with the derived
`plank:MAX_HOLD`. `TestEvents.all` as a picker list with `all` as the edition table plus derived events resolved
by `find`; the two custom ids are the derived spelling for push-ups and sit-ups in a window, so nothing stored
is rewritten and no history splits (§2.2). Army rules from the only plank on offer to a versioned edition offered
by a switch when the pick matches, off by default (§2.3). Added: the max-unbroken benchmark (`Benchmark.kt:22`
stops throwing for `REPS_CONTINUOUS`; the stop mapping goes by `setType`), so pull-ups and untimed push-ups work
(case 59); the capped hold on `pressureCaution` entries and the benchmark gate fix (case 60); the each-side
benchmark; format eligibility separate from tracking type, and the log / prepare / benchmark distinction with a
named not-supported list (§2.1); safety defaults with grades (§3.8); the hint "Your agency or school sets the
passing numbers. Enter yours." with Required blank; dose lines per pick (§3.9); the v35 answer (no bump, §4.1);
cases 59 to 61 and case 49's third row shape. Kept with the same numbers: cases 54 to 58 (their inputs restated
as picks), the first-screen entry, no `PlanBranch`, the three mappings, the prep reserve from all three callers,
`fitDay` prep slots, the `VolumeBalance` exemptions, replacement dedup, screen gating, legacy draft
normalization, the one-date rule, the beginner case, the old-door folding and every ledger item of rounds 1 to 3.

After council round 1 on revision 5 (ledger at the end): the met rule counts every done non-RAMP set; History and
the card share one helper; derived events are `custom` only for a window and a format switch clears the window;
`LIMIT` comes from the limit the clock ran under; every library pick takes the gear check; "none yet" exists only
where an easier movement does, and `negative_pullup` is one; press and pull picks default a Both plan to Separate
days and are named untrained on Same visit; prep prescriptions carry `eachSide`; the max-unbroken rule target
equals its aim; one `requiredLimit` binds Your test, the Plan editor and the aim; the execution fixes ship in PR A;
legacy drafts synthesize `testRun`; one run per test; plyometrics out; the `mainCapable` sentence corrected; the
Edwards grade corrected; the union search in `:app`; the card edits a lift row's weight.

After council round 2 (ledger at the end): the stop and the clock limit come from one pure rule,
`Benchmark.holdLimit` and `Benchmark.stop`, with no saved hold state; `requiredLimit` moves into the domain model's
`init`, so the aim needs no cap and case 60 uses an enterable Last (1:50, seed 55 s); the met verdict reads raw sets
through `HistoryRepository.liftSetsFlow` with `PersonalBests.counts` as the one rule; the Separate-days default
flips only at five or six days, where the run keeps its interval day, with the trade-off named on Together at two
to four days and the one-day line corrected; `setPlanGoal` named; `fitnessTest: Boolean?` for legacy detection;
trailing `subRank`s and a no-tests-unchanged test for the three library entries.

After council round 3 (ledger at the end): no pick writes `together`; `answers()` derives the Both default in one
place from the current days and picks whenever the user has not chosen, so the planner's own four-day Separate
days default never reaches a fitness-test plan; the trade-off copy is templated; `holdLimit` reads the ceiling as
`target` does; the `init` catalog lookup carries its comment; case 61 names the bench MAIN on one lift day and
SECONDARY on the other.

## Revision 4 (owner decisions, 2026-11-24)

Removed: the Which test? step (`SetupStep.PICK_TEST`), the `NamedFitnessTest` catalog and its gate, the Army and
police pre-fills, the "Police and academy tests" template, the recipe's `namedTest` key and the draft's
`namedTest` field, `onNamedTest`, the wave-2 table, every "Not trained here" line that came from a catalog
entry, and the owner decision list. Kept with the same numbers: cases 54 to 58 (their inputs are now the same
events picked by hand), the prep budget, the deadlift binding and its untrained path, the one-date rule, the
beginner case, the old-door folding and every ledger item of rounds 1 to 3. Re-derived: the flow (six to eleven
screens, Your test is step 2), the Goal row's body line, the Your test wireframe and rules, §2, the Why sheet's
Goal line, the draft and recipe fields, `draftFrom`'s signature, `planName`, and both PR lists. With no new
`SetupStep` constant, an older build now reads a new draft and recipe without a reset.

## Revision 1 ledger (council round 1, 2026-11-24)

Opus:
- S1 blocker, fixed: prep is placed before fitting, `easyRunSeconds` reserves its seconds at creation and in
  `RunReplan`, `fitDay` exempts prep; cases 54 to 56 recomputed with `SessionBudget` arithmetic (§3.4, §5).
- S2 blocker, dissolved by S3: the entry writes LIFT, RUN or BOTH, so `planned`, `nextPhase`, `PlanScreenBuilder`,
  `ResetDay` and `planPreview` are unchanged (§0, §1.1).
- S3 should, taken: no `PlanBranch.TEST`; a draft flag mapped onto today's shapes; `namedTest` an optional recipe
  key; no backup bump (§1.1, §4.1).
- S4 should, taken: `answers()` sets `goal`; `slotGoal` gains no case (§3.2).
- S5 should, taken: a run-only test writes RUN with its 0 to 2 blocks under D8; run-plus-calisthenics blocks are
  Strength for parity, stated (§3.2).
- S6 should, dissolved by S3 (no TEST `init` rule).
- S7 should, taken: the lift event resolves after Equipment on Your lifts (§3.3).
- S8 should, taken: the weight lives only in `goalsLb`; the recipe keeps the lift id (§3.3).
- S9 should, taken: `chooseFitnessTest`/`setBranch` clear or set the entry's answers; `SetupDraftJson.decode`
  normalizes legacy drafts (§4).
- S10 should, taken: Review answers changes nothing on existing test plans; the mapping keeps `goal`,
  `cardioFirst`, `together` and `strengthBlocks` (§4). Decision 3 names the one loss for new users.
- S11, S12 nits, dissolved by S3 (no planner copy of the date; `answers()` applies the existing date guard).
- S13 nit, fixed: `withTestWeeks` adds `DateTooClose` only when absent (§3.5).
- S14 nit, taken: no option groups until the Air Force PFA (§2.1).
- S15 nit, fixed: the gate reworded; the AFT deadlift cited from the Army announcement (§2.1).
- S16 nit, fixed: Kotarsky graded; run-first rests on the ladder length alone; Childs 2009 on the sit-up line
  (§3.1, §3.7).
- S17 nit, taken: "or build your own", "Your last count (optional)"; the Senior Fitness Test in wave 2 (§1.2, §2.2).
- S18 nit, fixed: the New-runner rows and the three-ladder-days-plus-rest case are stated (§3.1).
- S19 nit, fixed: PR A names its effect on existing test plans and runs case 52 (§6).

Sol:
- S1 blocker, fixed as Opus S4: `goal` is set in the answers (§3.2).
- S2 blocker, dissolved by S3: no excluded branch exists (§0).
- S3 blocker, dissolved by S3: no TEST recipe rules; Together is written as today for BOTH (§4).
- S4 blocker, fixed: legacy drafts normalized before steps or answers; a legacy draft with tests keeps its events
  step (§4).
- S5 blocker, fixed: mixed-goal plans are not converted; the two-date precedence is the run goal's date (§3.5, §4).
- S6 blocker, fixed: the lift event binds to the chosen hinge main after Equipment; where it is and is not trained
  is stated; the test card shows the deadlift row from `goalsLb` regardless of slot role (§3.3).
- S7 should, taken: no card merge; the date is shared through one repository transaction; run-only plans keep
  their cardio card (§4).
- S8 should, fixed structurally: prep sits inside `fitDay`, which raises the one `OverBudget` with the final
  excess. The 2,970 s figure described the appended-after-fit shape this revision replaces; case 56 now pins
  600 s over at 30 minutes (§3.4, §5).
- S9 should, fixed: the 95 percent factor and half-anchor dose are practice, the practice-test timing and
  benchmark cadence preference, Kotarsky restricted to push-ups and graded (§3.5, §3.7).
- S10 should, taken: "Police and academy tests", a template with the user's own standards (§2.1).
- S11 should, fixed: the unknown baseline benchmarks first; "none yet" preps the easier movement at the floor
  and never records a modified rep as a result (§3.6, case 57).
- S12 nit, taken in part: case 52's entry sentence moves to PR B; no backup bump is needed, so the v35 arm is
  untouched (§5, §4.1).
- S13 nit, taken: no persisted branch, no option groups (§1.1, §2.1).

## Revision 2 ledger (council round 2, 2026-11-24)

Opus:
- S1 blocker, fixed: one helper `TestPrep.reservedSeconds(day)` over the day's linked slots; `easyRunSeconds` takes
  it from all three callers, `CardioJudge` included, so `Progression.grown`'s easy stop holds the reserve;
  `CardioJudgeTest > case 54 day A at full success keeps its 1,200 s stop` named (§0, §3.4, §5).
- S2 should, fixed: case 54 pins no trim, A = 3,582 s, B = 3,192 s, C = 3,300 s, `fullDoseMinutes` = 60 (§3.4, §5).
- S3 should, fixed: ladder level 1 is 1,500 s; case 55 pins A = 3,330 s and B = 3,528 s with `ShortSession(_, 1)`
  on both (§0, §5).
- S4 should, taken: the lift event binds only to `trap_dl`, `conv_dl` or `sumo_dl`; otherwise the requirement is
  kept and the deadlift named as not trained; the hex-bar note for conventional or sumo (§1.2, §3.3, decision 2).
  Revision 5 generalizes the binding to any `mainCapable` entry and drops the hex-bar note (§3.3).
- S5 should, fixed: the §3.2 table and `cardioFirst` are draft actions; `answers()` passes `goal`, `cardioFirst`,
  `together`, `strengthBlocks` and `mains` through; §3.4's lift-first sentence corrected (§1.1, §3.1, §3.2, §4).
- S6 should, fixed: a run test on a draft whose branch is BOTH stays BOTH with its lifting sub-goal and order;
  `decode` and `draftFrom` convert BOTH with `FITNESS_TEST` with or without tests; a Review answers test on a
  Both/Muscle plus 2-mile plan is in PR B (§1.1, §4, §6).
- S7 should, fixed: prep exempt from the cap pass, the floor pass and the weekly credit; case 58 pins the Lift
  regression and case 52's test asserts the non-prep slots (§3.4, §5).
- N1 nit, fixed: A's easy run 1,200 s, B the 852 s interval block, C 3,300 s (§5).
- N2 nit, fixed: case 56 also pins `OverBudget(B, 252)` (§3.4, §5).
- N3 nit, fixed: `draftFrom(answers, settings, unit)` reads `goalsLb` in the user's unit (§3.3, §4).
- N4 nit, fixed: `decode` converts BOTH only with `FITNESS_TEST`; LIFT with tests (§4).
- N5 nit, fixed: PR A names the easy-run drop at the next wrap on existing cardio-first Both test plans (§6).
- N6 nit, fixed: ramps need A year or more, Strength and not 65+ (§3.3).
- N7 nit, taken: RECENT is gated with LIFTS on the entry (§1.2).

Sol:
- S1 should, fixed as Opus S7 (§3.4, case 58).
- S2 should, fixed: lift-only maps to LIFT with the typed goal and the card row built from the recipe's
  `testLift` and `goalsLb`, no `TestGoal` needed; run-plus-lift maps to BOTH, which keeps `mains` (§1.1, §3.3).
  Revision 5: the recipe key is `lifts` (§3.3).
- S3 should, fixed as Opus S4: a REPS back extension can no longer carry a pound goal; the requirement survives as
  untrained (§3.3, case 55's dumbbell test).
- S4 should, fixed: 1,500 s; case 55's two day totals and case 54's easy-run sentence corrected (§5).

## Revision 3 ledger (council round 3, 2026-11-24)

Opus:
- S1 should, fixed with the first fix: SUB_GOAL, ORDER, LIFTS and RECENT are hidden only for a cardio-first
  same-visit plan with no lift event; elsewhere LIFTS and RECENT follow today's rules, and SUB_GOAL and ORDER
  show on a prefilled draft whose `goal` or `cardioFirst` differs from §3.2; decision 3 restated (§1.1, §1.2, §7).
- S2 should, fixed: the one-slot replacement rule applies to both layouts; case 58 and the case 52 assertion
  restated as "every non-prep slot equals the no-tests plan's, except a same-movement slot the prep replaces, on
  days with no ShortSession", with the budget dependence stated (§3.4, §5).
- N1 nit, fixed: PR A's effect on existing plans corrected: no rebuild at a wrap for `FITNESS_TEST` plans, Finish
  never shrinks a run, only Review answers and Reset day rebuild (§6).
- N2 nit, fixed: `onNamedTest` applies the chip rule, so the pre-fills set STRENGTH (§3.2). Moot since revision 4.
- N3 nit, fixed: the run chip on a LIFT-branch draft also sets `cardioFirst = true` (§3.2).
- N4 nit, fixed: the deadlift binding uses `availableWith(equipment, excluded)`; an excluded deadlift takes the
  untrained path (§1.2, §3.3).
- N5 nit, fixed: case 56 pins `ShortSession(A, 6)` and `ShortSession(B, 6)` beside the `OverBudget` reasons (§5).

Sol:
- S1 should, fixed as Opus S2: the blanket equality is gone; case 58 accounts for the replaced plank (10 ordinary
  core sets against 12) and the budget (§3.4, §5).
- S2 should, fixed: `draftFrom(answers, recipe, settings, unit)` takes the recipe's `namedTest` and `testLift`,
  converts lift-only recipes, restores the lift chip and the Required weight; a lift-only Review answers
  round-trip test is in PR B (§4, §6). Revision 5: the recipe's `lifts` list, N and the Required weight.

## Revision 5 ledger (council take 2, 2026-11-27)

Opus (agree with changes):
1. Holds = TIMED minus carries; `pressureCaution` gets a capped hold: taken (§2.1, §3.8, case 60).
2. Heaviest for N only on `mainCapable`, bound to its Your lifts row, met on weight and reps, straight sets under a
   year and 65+, no scheduled attempt: taken (§3.3, §3.8, case 61).
3. Build the `REPS_CONTINUOUS` benchmark: taken (§2.1, case 59).
4. `TestEvents.find` derives `exerciseId:FORMAT`, old ids stay, stored refs never rewritten, v35 confirmed: taken;
   the two custom ids are the derived spelling so new picks continue old histories (§2.2, §4.1).
5. Army rules offered when the pick matches an edition, generic line otherwise: taken (§2.3, §2.4).
6. One picker over a sealed `TestPick`, runs out of the library: taken (§1.2, §4.1).
7. Fixed copy for Cooper, PACER/HAMR and cadence tests: taken (§2.1, §3.9).

Sol (agree with changes):
1. The link-free hint, requirements blank: taken (§1.2).
2. One picker, default All, Strength / Cardio / Holds, sources keep their identity: taken (§1.2, §2.2).
3. Format eligibility explicit; log, prepare and benchmark distinguished; cadence and shuttle audio named as
   unsupported: taken (§2.1).
4. Army form, faults, rest and ceilings as versioned protocol metadata, never the default: taken (§2.3).
5. Compatibility before storage changes: `aft_hrp`, `aft_plank`, `custom_*`, versions, windows, snapshots and
   legacy resolution kept; v35 import stays; no new weight payload exists to version (§2.2, §3.3, §4.1).
6. Budget reservation and dedup kept; actual dose or "not trained" shown; submaximal prep, optional benchmarks,
   easier variants recorded separately; ceilings and pressure cautions honored without a blanket 65+ exclusion:
   taken (§3.4, §3.6, §3.8, §3.9).

## Revision 5 round 1 ledger (council, 2026-11-27; Opus 7/10, Sol 6/10)

Each finding was checked against the cited code before it was accepted; none is disputed.

| Id | Seat | Severity | Outcome |
|---|---|---|---|
| S1 | Opus | should | Fixed. `LiftTestProgress.best` counts every done set but RAMP (`SetKind`, Types.kt:22) with at least N reps; case 61 adds the ramped lifter whose 230 × 5 TOP meets and whose 240 × 3 RAMP never counts (§3.3, case 61). Same as Sol S2. |
| S2 | Opus | should | Fixed. One rule for both verdicts, `LiftTestProgress` over `PersonalBests.counts` plus the reps; the raw sets reach History and the card through `HistoryRepository.liftSetsFlow` (round 2 Opus N2 / Sol S3 named the data path); lifts with a goal and no test keep the top-weight rule; case 61 asserts History (§3.3). |
| S3 | Opus | should | Fixed. Derived events are `custom` only for `REPS_IN_WINDOW`; `onTestFormat` clears the window; case 59 pins a null window after a switch (§1.2, §2.2, case 59). |
| S4 | Opus | should | Fixed in round 2 after a wrong first fix (the saved `KEY_HOLD_PAST_TARGET` is cleared before the write, round 2 Opus S1 / Sol S1): one pure rule, `Benchmark.holdLimit` and `Benchmark.stop`, shared by the clock gate and both writers; the AFT plank keeps `LIMIT` at 600 and `STOPPED` at 192 and 300, the wall sit writes `LIMIT` at 120; §3.8 reworded (§3.8, §2.1, case 60). |
| S5 | Opus | should | Fixed. `TestPrep.slots` places no slot for an entry failing `availableWith(equipment, excluded)`; the goal keeps the event, no benchmark can open, Your plan and the card say "not trained with this equipment (needs a pull-up bar)"; case 59 pins pull-ups on the Dumbbells preset (§3.4, §3.9, case 59). |
| S6 | Opus | should | Fixed, both halves. Zero is accepted only for entries with an easier movement (`TestPrep.easier`), the field's floor is 1 elsewhere and the Why sheet says so; `negative_pullup` is added and mapped from `pullup` and `neutral_pullup`; dips, handstand push-ups, pistols and Nordics stay at the floor and are a named follow-up (§1.2, §3.6). |
| S7 | Opus | nit | Fixed. The `mainCapable` sentence now gives the true reason: the agreed eligibility restriction for test lifts, with `Planner.mainChoices` (Planner.kt:125) named as listing more (§2.1). Same as Sol S10. |
| S8 | Opus | nit | Taken. The card's editor edits the Required weight for lift rows through `PlanRepository.setPlanGoal(planId, exerciseId, goalLb)` (PlanRepository.kt:157; round 1 named the wrong method); N changes on Review answers only (§3.3). |
| S9 | Opus | nit | Taken. The union search is `TestPickSearch` in `:app`, where the run labels live (SetupFlowRun.kt:154-158); `:domain` keeps `TestFormat.eligible` and `ExerciseSearch` (§4.1). |
| S10 | Opus | nit | Taken. `find` returns null for the non-canonical `pushup:REPS_IN_WINDOW` and `situp:REPS_IN_WINDOW`; `TestFormat` is stored by name; a generic-line copy change is a new version (§2.2, case 59). |
| S11 | Opus | nit | Fixed. The rule's target comes from the `Reps` aim as it does from `RepsInTime` (TestPrep.kt:44); case 59 pins rule 5 and the step to 6 (§3.4, case 59). Same as Sol S4. |
| S12 | Opus | nit | Taken. One run per test, the sheet disables the other runs while one is picked; `testAnswer` and `canAdvance` accept Last = 0 where S6 allows it (§1.2). Same as Sol S8. |
| S13 | Opus | nit | Taken. `PLYOMETRIC` entries are out of both rep formats; the Nordic stays eligible (§2.1, case 59 pins `box_jump:REPS_CONTINUOUS` as null). |
| S1 | Sol | blocker | Fixed without a new layout. `strengthBlock` (RunLayout.kt:76) takes only the squat and hinge rows, so a press or pull weighted pick is named "not trained on Same visit: the strength blocks hold the squat and the hinge" and the default flips to Separate days only where the run keeps its interval day (five or six days; narrowed in round 2, Opus S3); case 61 adds the run-plus-bench rows (§1.2, §3.3, §3.9, case 61). |
| S2 | Sol | blocker | Fixed as Opus S1 (§3.3, case 61). |
| S3 | Sol | blocker | Fixed. Prep prescriptions carry `entry.eachSide` (TestPrep.kt:43 defaulted it to false, overriding the flag through `eachSideOf`, ExerciseEntry.kt:87), so training, the benchmark and `reservedSeconds` cover both sides; case 60 pins the side plank (§3.4, §3.8, case 60). |
| S4 | Sol | should | Fixed as Opus S11; case 59 pins 5 → 6 through `FinishTestPrepTest` (§3.4, case 59). |
| S5 | Sol | should | Fixed; the round 1 text contradicted itself (round 2 Opus S2 / Sol S2). One `TestEvents.requiredLimit(event)` binds `TestEventGoal` and `TestAnswer` in their `init`, `testAnswer` and `TestGoalDraft.requiredValue` (TestGoalDraft.kt:14); Last and Required cannot exceed 2:00, so the aim is at most 1:00 with no cap of its own and Finish's `max(cap, required)` (TrackerRepository.kt:2502) never exceeds the cap; case 60 uses Last 1:50 and pins the 55 s seed and the editor (§1.2, §3.8, case 60). |
| S6 | Sol | should | Fixed. The execution fixes (stop mapping and limit-based `LIMIT`, pressure-caution gate, each-side benchmark, `mainLifts` N, the editor bound) move into PR A, which is renamed "domain, data and benchmark execution"; the reason (a v35 backup from a PR B build) is stated (§4.1, §6). |
| S7 | Sol | should | Fixed. `decode` recognizes a legacy draft by the missing `fitnessTest` key and synthesizes `testRun = runGoal == FITNESS_TEST`; a new draft whose user removed the run stores the key and is untouched; PR B adds a legacy Run test draft round trip (§4, §6). |
| S8 | Sol | should | Fixed as Opus S12 (§1.2, §4). |
| S9 | Sol | nit | Fixed. Edwards 2023's strong grade is kept for resting-pressure reduction only; the acute response cites the 2021 safety meta-analysis (moderate) and the wall-squat abstract (weak); the 2:00 cap is practice (§3.7, §3.8). |
| S10 | Sol | nit | Fixed as Opus S7 (§2.1). |

Follow-ups, not in these PRs: easier variants for dips, handstand push-ups, pistol squats and Nordics; a wider
heaviest-for-N list beyond `mainCapable`; a same-visit block shape that trains a press or pull pick; custom
exercises as test events; other run distances; cadence tests; shuttle audio; Cooper's goal shape (#618).

## Revision 5 round 2 ledger (council, 2026-11-27; Opus 7/10, Sol 8/10)

Each finding was checked against the cited code (DayViewModel.kt:2236 and :3053, BothLayout.kt:17-29,
RunLayout.kt:25-30, HistoryRepository.kt:283-288, PersonalBests.kt:20, PlanRepository.kt:157, SetupDraftJson's
`v == VERSION` check, the three patterns' last `subRank`s); none is disputed.

| Id | Seat | Severity | Outcome |
|---|---|---|---|
| S1 | Opus | should | Fixed. `clearHold()` (:2236) removes `KEY_HOLD_PAST_TARGET` (:3053) before `logTestResult`, and `updateBenchmarkResult` (:1977) has no hold, so the round 1 fix read a cleared flag. Replaced by one pure rule in `:domain`: `Benchmark.holdLimit(event, pressureCaution) = HoldClock.limitSeconds(target.seconds, !pressureCaution)` and `Benchmark.stop(event, pressureCaution, row)`, used by the clock gate at :1741 and both writers from the card's `prescriptionTest.event` and `holdPressureCaution`. Case 60 pins the AFT plank's hand stop at 300 s as `STOPPED` and an edit to 300 keeping it; the existing 590 edit test stands (§3.8, §4.1, case 60). Same as Sol S1. |
| S2 | Opus | should | Fixed, Sol's direction with a smaller rule. `requiredLimit` moves into `TestEventGoal` and `TestAnswer` `init`, so neither Required nor Last can exceed the cap anywhere (domain, Your test, Plan editor) and the aim needs no `min()`: half an anchor of at most 2:00 is at most 1:00, under the fixed 2:00 holds the pressure trials used (Edwards 2023 protocol; the cap is the heaviest dose the evidence used, practice). Case 60 uses Last 1:50 and pins 3 × 55 s, the domain's refusal of 130 s, and the editor's refusal; §3.8 and the round 1 ledger corrected (§1.2, §3.4, §3.8, case 60). Same as Sol S2. |
| S3 | Opus | should | Fixed with the smaller option. The default flips to Separate days only at five or six days, where a cardio-first plan has three run days and the row keeps Q (RunLayout.kt:25-30); at two to four days the default stays Same visit and the Together row for Separate days says "Trains the bench, but leaves the 2-mile no interval day at four days". Case 61 pins Same visit at four days (two run days E, L) and Separate days at five (E, Q, L, R, the bench a MAIN on the lift days) (§1.2, §3.3, case 61). |
| N1 | Opus | nit | Fixed. No flip at one day (`bothDraft` returns the run plan alone there, BothLayout.kt:17-19, and Together is not offered); the one-day line reads "not trained at one day a week: the strength block holds the squat" (§3.3, §3.9). |
| N2 | Opus | nit | Fixed. `LiftTestProgress.best` is `PersonalBests.counts(set) && set.reps >= N` over the raw sets of `SessionDao.observeTrajectorySets()`, exposed as `HistoryRepository.liftSetsFlow` and fed to `JournalBuilder.trajectories` and the card (§3.3, §4.1). Same as Sol S3. |
| N3 | Opus | nit | Fixed. `fitnessTest: Boolean? = null`; null marks a legacy draft. Not a `VERSION` bump: `decode` rejects any other `v` today, so a bump would reset every saved draft (§4). |
| N4 | Opus | nit | Fixed. `PlanRepository.setPlanGoal(planId, exerciseId, goalLb)` (PlanRepository.kt:157) in §3.3, §4.1 and the round 1 ledger. Same as Sol S4. |
| N5 | Opus | nit | Fixed. `knee_pushup` H_PUSH 21, `knee_plank` CORE_ANTI_EXT 8, `negative_pullup` V_PULL 13, each after its pattern's last entry; PR A pins that no plan without tests on any preset holds one of them (§3.6, §4.1, §6). |
| S1 | Sol | should | Fixed as Opus S1; the 590 edit regression stands beside the capped-hold case (§3.8, case 60). |
| S2 | Sol | should | Fixed as Opus S2: the domain rejects Last above 2:00, so the pinned seed comes from an enterable Last (1:50, 55 s) rather than 3 × 2:00 from a 5:00 that cannot exist (§3.8, case 60). |
| S3 | Sol | should | Fixed as Opus N2; case 61 adds the same-session 230 × 1 TOP plus 225 × 5 BACKOFF row that meets on History and the card (§3.3, case 61). |
| S4 | Sol | nit | Fixed as Opus N4 (§3.3). |

## Revision 5 round 3 ledger (council, 2026-11-27; Opus 8/10, Sol 9/10)

Verified: `SetupDraft.kt:32` (`days = 3`), `SetupFlowViewModel.kt:213` (`setDays` copies only `days`),
`SetupAnswers.kt:82-83` (a null `together` resolves to Separate days at four or more days),
`SetupFlowStateBuilder.kt:138` (`answers()` passes `draft.together` through, null included), `DayLayout.kt:66`.

| Id | Seat | Severity | Outcome |
|---|---|---|---|
| S1 | Opus, Sol | should | Fixed. The pick no longer writes `together`. `answers()` sets `both.together = draft.together ?: testTogether(draft)` for a fitness-test draft, where `testTogether` is Separate days at five or six days with a press or pull pick and Same visit otherwise, evaluated from the current days and picks; an explicit tap on Together is kept. Case 61 pins the forward flow (bench picked at three days, four days gives Same visit, five gives Separate days, an explicit Same visit survives a change to six) and case 55 at four days stays Same visit (§1.2, §3.3, §4, case 61). |
| N1 | Opus | nit | Fixed. The Together line is templated: "Trains {lift}, but leaves {run} no interval day at {days} days" (§1.2, §3.3, case 61). |
| N2 | Opus | nit | Fixed. `holdLimit` reads `event.ceilingSeconds ?: HoldClock.PAST_TARGET_LIMIT_SECONDS`, the seconds `target` uses at Benchmark.kt:24, and takes no `ref` (§3.8). |
| N3 | Opus | nit | Fixed. The `init` catalog lookup in `TestEventGoal` and `TestAnswer` carries a one-line comment on why a model validates against the catalog, and an unknown id takes the 3600 default (§3.8). |
| S2 | Sol | nit | Fixed. Case 61's five-day Separate plan: the bench is a MAIN on one lift day and the press SECONDARY on the other (DayLayout.kt:66) (§3.3, case 61). |

## Revision 5 ledger after the plain verdicts (council, 2026-11-27; both CHANGES, one reason)

| Id | Seat | Severity | Outcome |
|---|---|---|---|
| F1 | Opus, Sol | should | Fixed narrowly. `testTogether` applies only to a draft begun by `chooseFitnessTest()`, the only draft whose `together` is null: `draftFrom` fills a legacy BOTH recipe's null `together` (SetupRecipeJson.kt:78, :107) from `SetupAnswers.together()` over the stored answers, and `decode` does the same for a legacy BOTH draft, so the resolved choice (Separate days at four or more days, or three days lift first under Strength for A year or more, SetupAnswers.kt:82-87) becomes the draft's explicit choice and Review answers plus a save rebuild nothing. PR B's Both/Muscle plus 2-mile round trip runs at four days and asserts `together` stays Separate days (§1.2, §4, §6). |
