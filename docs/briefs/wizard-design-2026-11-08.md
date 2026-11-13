# Setup by goal, generator from the log: design

Date: 2026-11-08. Base: main at `3c8cef00`. Design pass only; nothing built.

Inputs, in order: the evidence review (`wizard-evidence-2026-11-08/report.md`), the inventory (`wizard-inventory-2026-11-08.md`), r4 synthesis Parts 3 and 4, `docs/PLAN.md` A13 to A18, spec §6 and §11, DESIGN.md house rules and the `prose-whip` copy subset (there is no `docs/design/copy-style.md`; DESIGN.md says so). File references use the inventory's shorthand (`domain:`, `data:`, `app:`, `transfer:`).

Status: revision 4, the owner's decisions applied (§13). Normative once PLAN.md gains amendment A19 (§5). §9 lists what was decided and the few questions still open.

## 1. Scope and non-goals

**Audience.** People who lift or run, from a first session to competitive lifting, including adults 65 and older. The app plans and progresses general strength, muscle size, general fitness, running from 60 m sprints to ultras, field fitness tests, and any mix of lifting and cardio. Competitive lifters are in scope through a Lift sub-goal "Meet" (a date, a build and a taper, like a running event) that follows the core as the first follow-up branch; this document leaves its seam (§3.3) and does not design it.

In scope: a short setup by goal; a planner that turns answers into a plan; plans the user builds by hand as first-class citizens of the same engine; progression that turns the log plus per-set effort into the next targets; effort logging (words or reps left for sets, Easy / Good / Hard for cardio sessions); rest by lift role; a lowered weight carrying to next time; rest entries in the rotation; a beep-start sprint timer; one "why" disclosure per screen.

There is no legacy engine. Existing plans are converted once at upgrade into the user's own plans under the new engine (§4.4); workout history is kept untouched; the old formula, its constants and its pinned numbers go.

Non-goals, each with where it goes instead:

| Not here | Where |
|---|---|
| GPS distance tracking | Its own design after this one. §4 leaves `cardio_set_result` able to hold the reading at the planned target (#526) and names the future table it must not collide with |
| Meet branch (competition date, attempt build, taper) | The first follow-up after the core; §3.3 names the seam |
| Dated programs (week-by-week percentages such as 5/3/1 or a 12-week peak) | A follow-up; §3.3 names the seam. User-built plans without dates are in scope now |
| Calendar weekdays (item 12) | Rotation stays (owner decision). Reminder time is a later You-screen setting |
| Heart-rate zones | No sensor path exists. Effort bands use the talk test and words. Tanaka's formula is recorded in §7 for when a sensor arrives |
| Velocity-based loading, 1RM tests, nutrition | Out, per the evidence review |
| Effort logging on the watch | A watch-logged set carries no effort and counts as on target |
| Redesigning #527 to #536 | §4.5 says how each fits; #534 (Target heading) and #535 (swap search fall-through) are untouched |

## 2. The flow

Six screens for a lifter, seven or eight for a runner, every one skippable with a default, Next always enabled. Every screen has one `TextButton` "Why we ask" that opens a `ModalBottomSheet` holding the disclosure in §6. No bodyweight, no age in years, no experience level once real numbers are entered. Each answer gets a new `SavedStateHandle` key (the Parcelable rule); the finish write order, restore lock and first-run latch from `WizardViewModel` stay.

```
Goal ─► Sub-goal ─► Your week ─► Equipment ─► About your training ─► [branch] ─► Your plan
                                                                       Lift: Your lifts, Recent numbers (trained only)
                                                                       Run:  Your event, Where you are
                                                                       Sprint: Your event
                                                                       Both: lift sub-goal, run sub-goal, Together
```

### 2.1 Common questions

| Screen | Question and options | Default | Grade (why asked) | What it changes |
|---|---|---|---|---|
| Goal | Lift / Run / Sprint / Both | Lift | Preference. Goal type changes structure; numeric outcome targets are not asked (goal setting has no independent adherence effect) | Which branch runs |
| Sub-goal | Lift: Strength, Muscle, General fitness. Run: First 5K, 5K to 10K, Half marathon, Marathon, Ultra, Middle distance, Fitness test, Just cardio fitness. Sprint: 40-yard, 60 m, 100 m, 200 m, 400 m | General fitness; Just cardio fitness; 100 m | Preference | Sets, reps, effort, session mix |
| Your week | Days a week you can keep: 1 to 6. Minutes a session: 30, 45, 60, 75, 90 | 3 days, 60 min | Days: moderate-strong (frequency is outcome-neutral at matched volume; two strength days is the floor). Minutes: weak, but time is the top reported barrier | Split, slots per day, rest entries, cardio session count |
| Equipment | Preset: Full gym, Home barbell, Dumbbells, Kettlebells, Machines only, Bodyweight; "Choose exactly" expands the 11 boxes. Smallest step row: bar 5 lb / 2.5 lb / 1 lb (kg 2.5 / 1.25 / 0.5), dumbbells 5 / 2.5 lb (2 / 1 kg), stack 10 / 5 lb (5 / 2.5 kg) | Full gym; 5 lb, 5 lb, 10 lb (2.5, 2, 5 kg); unit from the device locale | Practice. Load steps decide load-first versus reps-first progression | Exercise pool, per-lift step, progression method |
| About your training | Lifting: New, Under a year, A year or more. Running (run branches): New, Some, Racing. Work around: Pain now, Injury in the past year, Bone or joint condition, 65 or older; plus Running injury in the past year (run), Hamstring strain in the past 12 months (sprint), Falls or balance trouble (65+) | New; nothing to work around | Experience: moderate. Exclusions: moderate (affect, safety) | Starting effort, cadence, deloads, calibration, test eligibility, older-adult path |
| Your plan | The rotation as a list of days with set counts and the cardio line; "Change split" when days allow a choice | | | Start writes the plan |

The older-adult path is the 65+ answer: it is a modifier the planner applies to any branch (loads at 70 to 80%, 3 RIR or more, no failure, no 1RM, balance block on lifting days, a more cautious restart), not a fifth goal card.

Rotation entries by days, per branch. R is a rest entry; "+S" is a 25 min strength block after the run in the same visit.

| Days | Lift | Run (event or Just cardio) | Sprint | Both, separate days |
|---|---|---|---|---|
| 1 | FB | E+S | Sprint A + plyos + lower lifts | the primary's 1-day plan |
| 2 | FB A, FB B | E+S, L+S (First 5K: two ladder days) | Sprint A + plyos + lower lifts, R, Sprint B + Nordic + upper lifts | primary day, secondary day |
| 3 | FB A, B, C | E+S, Q+S, L, R | Sprint A + plyos, R, Lower lifts + Nordic, Sprint B, R | 2 primary, 1 secondary |
| 4 | U, L, U, L, R | E+S, Q, E+S, L, R | Sprint A + plyos, Lower lifts, R, Sprint B, Lower lifts + Nordic | 2 and 2 |
| 5 | U, L, U, L, FB, R | E+S, Q, E, Q2 or E+S, L, R | as 4, plus Tempo | 3 primary, 2 secondary |
| 6 | U, L ×3 (Muscle: PPL ×2), R | E+S, Q, E, Q2, E+S, L, R | as 5, plus Upper lifts | 3 and 3 |

Both, same visit: the primary's rotation with the other goal's block appended to each day the budget allows (lift first on lift-primary days, run first on run-primary days). Strength blocks on run plans ride on easy or quality days, never on the long run; the user can set them to 0 or 1 on Your plan.

### 2.2 Branch questions

| Branch | Screen | Question | Default | Grade | Changes |
|---|---|---|---|---|---|
| Lift (Strength, Muscle) | Your lifts | One row per pattern: Squat, Hinge, Horizontal press, Overhead press, Pull. Each row is an `ExposedDropdownMenu` of main-capable lifts the equipment allows | Back squat, trap-bar deadlift, bench, overhead press, barbell row (or the first available) | Preference (choice improves affect; load is the exception) | Which lifts are mains; every chosen main is placed on every split (D3) |
| Lift (General fitness) | none | Mains default; swaps happen in the app | | | |
| Lift, Under a year or more | Recent numbers | Per main: weight × reps, effort (Easy / Good / Hard / Max), 10 reps or fewer | Blank: that lift calibrates in its first session | Moderate (Epley at ≤10 reps to failure) | Opening loads |
| Run (event) | Your event | Middle distance: 800 m / 1500 m / Mile / 3000 m. Ultra: 50K / 50 mi / 100K / 100 mi. Date (optional); Goal: Just finish / a time; Recent race or time trial (distance, time, date, optional) | 1500 m; 50K; no date; Just finish | Practice (plan length, taper), moderate (Riegel to half marathon) | Event distance, phase, taper, interval aims, goal check |
| Run | Where you are | Runs a week now (0 to 7); longest run in the past 30 days (distance or minutes); for Just cardio fitness: activity Run / Walk / Bike / Row / Elliptical | 0; blank; Run | Moderate (the 110% single-run cap needs a 30-day longest; readiness gates for half and up) | Volume start, foundation phase, run cap seed, modality |
| Run (Ultra) | Your event adds Road / Trail and Marathon best | Road | Weak | Time-on-feet sessions |
| Run (Fitness test) | Your event | Test: 2-mile, 1.5-mile, Cooper 12-minute, PACER/HAMR; date; target score; recent result | 2-mile; no date | Moderate (intervals for test scores) | Interval aim, practice test timing, PlanGoal |
| Sprint | Your event | Timing method: Phone timer / Partner stopwatch / Phone video / Electronic gates / Not timing; Sprinted all-out in the past month: yes/no; Best time (optional) | Phone timer; no; blank | Moderate (hand timing bias), practice (intro phase) | Intro length, attempt comparison |
| Both | Which first | Lifting / Cardio; then the lift sub-goal and the run or sprint sub-goal with their branch screens (Your lifts, Your event, Where you are); then Together: Same visit / Separate days. The common screens are asked once | Lifting; Separate when days ≥ 4, else Same visit | Moderate (primary goes first; the explosive-strength cost was seen in same-session designs) | Day layout, order within a day |

Pushed to later in the app (each has a home): bodyweight (You screen, only when Health Connect calories or a bodyweight display needs it, #527), exercise swaps and exclusions (day editor), favorites, reminder time, "Lighter today", planned light week cadence, guideline-minutes display, sled loads, surface and start type (sprint attempt entry), heart-rate monitor.

**Plans the user builds.** "Build my own" on the Plans screen is the other way in, and it is first-class: the plan picks a "Defaults for" setting (Strength, Muscle or General fitness; Strength by default) and every lift the user adds gets a `SlotRule` from `SlotRule.defaults(role, goal, structure, reps)` (§3.2), with the role guessed from the structure and the entry (ramped or main-capable → MAIN, else ACCESSORY) and editable in Lift options along with reps, reps left, increment, rest and back-offs. Progression then adjusts the user's weights exactly as it does a generated plan's. A user-built plan has no recipe and no "Reset day to plan".

**Not fitting the minutes.** When a day cannot fit the session length after the §3.1 dose ladder, Your plan shows the day with "Over by N min" and two choices: drop a lift (the user picks which) or longer sessions (raises the minutes answer). Nothing is dropped until the user chooses.

### 2.3 Key screens

Goal and sub-goal are one `LazyColumn` of selectable `Card`s with a one-line description each; the sub-goal list replaces the goal list in place.

```
┌──────────────────────────────────────────┐
│ ←  Set up                        1 of 6  │
│                                          │
│ What are you training for?               │
│                                          │
│ ┌──────────────────────────────────────┐ │
│ │ ● Lift                               │ │
│ │   Strength, muscle, or fitness       │ │
│ ├──────────────────────────────────────┤ │
│ │ ○ Run                                │ │
│ │   5K to ultra, a test, or fitness    │ │
│ ├──────────────────────────────────────┤ │
│ │ ○ Sprint                             │ │
│ │   40 yards to 400 m                  │ │
│ ├──────────────────────────────────────┤ │
│ │ ○ Both                               │ │
│ │   Lifting and cardio in one plan     │ │
│ └──────────────────────────────────────┘ │
│                                          │
│ Why we ask                               │
│                                          │
│                              [  Next  ]  │
└──────────────────────────────────────────┘
```

Your week: two `FilterChip` rows.

```
┌──────────────────────────────────────────┐
│ ←  Set up                        3 of 6  │
│                                          │
│ Your week                                │
│                                          │
│ Days you can keep                        │
│  (1) (2) (3●) (4) (5) (6)                │
│                                          │
│ Minutes a session                        │
│  (30) (45) (60●) (75) (90)               │
│                                          │
│ Why we ask                               │
│                              [  Next  ]  │
└──────────────────────────────────────────┘
```

About your training: two chip groups, one single-select and one multi-select. The run branch adds its rows.

```
┌──────────────────────────────────────────┐
│ ←  Set up                        5 of 6  │
│                                          │
│ About your training                      │
│                                          │
│ Lifting                                  │
│  (New●) (Under a year) (A year or more)  │
│                                          │
│ Anything to work around                  │
│  (Pain now) (Injury in the past year)    │
│  (Bone or joint condition) (65 or older) │
│                                          │
│ Why we ask                               │
│                              [  Next  ]  │
└──────────────────────────────────────────┘
```

Your lifts (Strength and Muscle): one `ListItem` per pattern with a trailing dropdown.

```
┌──────────────────────────────────────────┐
│ ←  Set up                        6 of 7  │
│                                          │
│ Your lifts                               │
│                                          │
│ Squat             Barbell Back Squat  ▾  │
│ Hinge             Trap-Bar Deadlift   ▾  │
│ Horizontal press  Barbell Bench Press ▾  │
│ Overhead press    Overhead Press      ▾  │
│ Pull              Barbell Row         ▾  │
│                                          │
│ Each one is in the plan.                 │
│ Why we ask                               │
│                              [  Next  ]  │
└──────────────────────────────────────────┘
```

Your event (Run): list, date, goal, recent result.

```
┌──────────────────────────────────────────┐
│ ←  Set up                        6 of 8  │
│                                          │
│ Your event                               │
│                                          │
│ Half marathon                            │
│ Date                    [ Mar 14, 2027 ] │
│ Goal        (Just finish●) (A time)      │
│                                          │
│ Recent race or time trial (optional)     │
│  Distance [ 10 km ▾ ]  Time [ 52:07 ]    │
│  Date     [ Oct 24, 2026 ]               │
│                                          │
│ Why we ask                               │
│                              [  Next  ]  │
└──────────────────────────────────────────┘
```

Your plan: the rotation, the split chooser where days allow one, and the plan's one "Why this plan" disclosure.

```
┌──────────────────────────────────────────┐
│ ←  Set up                        7 of 7  │
│                                          │
│ Your plan                                │
│ 4-day upper/lower · Strength             │
│ Split                  Upper/Lower   ▾   │
│                                          │
│ A  Lower     Squat 275 · 5 lifts · 58 min│
│ B  Upper     Bench 185 · 6 lifts · 57 min│
│ C  Lower     Deadlift 315 · 5 · 57 min   │
│ D  Upper     Press 115 · 6 · 55 min      │
│ —  Rest                                  │
│                                          │
│ Why this plan                            │
│                             [  Start  ]  │
└──────────────────────────────────────────┘
```

The set page gains an effort row above Log. The default is Good, so one tap still logs a set. A missed rep is #530's decrement beside Log, not an effort word.

```
│  TOP   185 lb × 5                        │
│  (Easy) (Good●) (Hard) (Max)            │
│  [ − ] [     Log 185 lb × 5     ]        │
```

## 3. Generator modules in `:domain`

Two deep modules, both in new packages, both pure Kotlin. Everything else is either a small shared type or an existing module kept.

### 3.1 `domain:planner/Planner`: answers to a plan

```kotlin
object Planner {
    /** Deterministic for (answers, known, today). Never reads the clock or the library of custom exercises. */
    fun plan(answers: SetupAnswers, known: KnownLoads = KnownLoads.NONE, todayEpochDay: Long): PlanDraft

    /** One day regenerated from a v2 recipe, with its opening rows, for Reset day to plan. Null for a day id the recipe does not produce. */
    fun day(answers: SetupAnswers, dayId: String, known: KnownLoads, todayEpochDay: Long): DayDraft?
}

/** The one seeding function. Every entry point that needs rows for a ruled slot calls it: creation, Reset day to plan,
 *  Plan mode's restore, swaps, added lifts, superset partners (row-aligned to the live main track), Today's preview,
 *  the watch's blank rows and PrescriptionDraft's default weight. Loads: known.lastWorking for this exercise through
 *  Estimate (null rir reads as 2), else the equipment floor with calibrating on. Never a formula. The same function
 *  seeds converted and user-built plans; there is no other seeding path. */
object SlotSeeding {
    fun rows(slot: ProgramExercise, rule: SlotRule, known: KnownLoads, steps: LoadSteps, unit: WeightUnit): List<LoggedSet>
}

data class PlanDraft(
    val name: String,
    val program: Program,                    // days with ProgramExercise.rule set, cardio blocks, REST entries
    val goal: PlanGoal?,                     // event, test or sprint goal; null for lift-only plans
    val working: Map<SlotKey, List<LoggedSet>>, // opening rows per slot, written at creation
    val autoApply: Boolean,                  // true for every new plan
    val deloadEveryCycles: Int?,             // 6 for A year or more, else null
    val reasons: List<Reason>,               // keys the app turns into the "Why this plan" sheet
)

data class KnownLoads(
    val lastWorking: Map<String, LoggedSet>,         // exercise id to its last working set in any plan
    val longestRecent: Map<CardioFamily, RecentMaxima>, // per family, over the last 30 days: the largest finished session total in metres and, independently, the largest in seconds, each summed over WORK and RECOVERY sets
)
```

Interface facts a caller must know: `program` is a valid `Program` for today's repository, mappers, Today, watch and backup; new per-slot facts ride on `ProgramExercise.rule: SlotRule?` and per-day cardio state on `ProgramDay.cardioRule: CardioRule?`; `working` must be written in the same transaction as the program (loads come from the user's numbers, history or a floor, never a formula); the recipe stored on the plan is `answers` encoded as v2; `goal` goes to `plan.goalJson` and is the live goal from then on, the recipe's date is never read again. The planner reads `known.longestRecent` for the first run cap; after that, history drives it (§3.2).

Invariants, each a test, and the order they give way in when a session is too short (minutes always win):

1. Estimated minutes per day ≤ the session length, or the day carries `Reason.OVER_BUDGET` and waits for the user's choice on Your plan (a preview state; a saved plan always fits). Estimate: 5 min warm-up, then per set 45 s plus its rest; a superset counts once; a cardio block counts its planned time. The property test asserts fits-or-OVER_BUDGET.
2. Every chosen main is a `MAIN` slot at least once per rotation, on every split.
3. When days ≥ 2, every rotation has at least two strength days that together cover every major muscle group, unless the user set strength days to 0 on a run or sprint plan.
4. Each major muscle group gets at least two exposures per rotation when days ≥ 2 (one exposure at 1 day).
5. Weekly sets per muscle, summed over the rotation's strength days (a compound credits its secondary muscles 0.5), sit inside the goal's band in §3.1.1. A rotation with any `Reason.SHORT_SESSION` day may fall under its floors; the cap of 20 never relaxes.
6. No slot needs equipment the user lacks or an excluded exercise; a pattern nothing can fill is dropped and the day keeps at least one exercise.
7. No target below 2 RIR in a New lifter's first six exposures, and never below 3 RIR on the 65+ path.
8. No planned cardio session breaks the §3.2 Run cap row (WORK plus RECOVERY totals against `longestRecent`, the ladder exemption, the floor at the largest total).
9. Mostly easy cardio: at most one quality session in a week of three or fewer sessions, two at four or more and only for Some or Racing.

Reduced-dose ladder when 1 fails, applied per day (each step applies in order until the day fits, each adds its `Reason`; the 65+ balance block is a WHO recommendation, not an accessory, and no step removes it): 1 drop accessories to one core slot of 2 sets; 2 drop SECONDARY exposures; 3 mains to `STRAIGHT` 2 sets at the slot's working load (the full scheme's TOP load); 4 mains to one ramp at 80% ×3 plus one working set at the working load; 5 MAIN rest 120 s; 6 drop the core slot. When the day still does not fit, the planner stops and marks the day `Reason.OVER_BUDGET(seconds)`; Your plan shows "Over by N min" and the user chooses to drop a lift or lengthen sessions (§2.2). No lift is dropped silently. Ramp and back-off sets always rest their own buckets (RAMP 90 s, BACKOFF 120 s), whatever the slot's role (§3.2.1). Volume bands (invariant 5) are checked only on days the ladder did not touch; a day carrying `Reason.SHORT_SESSION` may sit under its band. One hard set per lift is a defensible floor (one set beats none; the minimum effective dose is one hard set two to three times a week).

Where a cardio rule gives a range (rounds, reps of an interval, distance per rep), the planner starts at the low end and adds one unit (a round or rep first, then 100 m of distance) every two finished quality sessions not rated above their band (§3.1.2: an interval session counts on any word, a tempo session on Easy or Good); lift rep ranges follow the §3.2 Range row instead. Boundaries throughout: a step is coarse when step ÷ load > 0.10; an increment is capped at 10% of the load inclusive; Easy means rir ≥ 4.

#### 3.1.1 Lift rules

| Rule | Strength | Muscle | General fitness | 65+ modifier | Grade |
|---|---|---|---|---|---|
| Split by days | 1 FB; 2 FB A/B; 3 FB A/B/C; 4 U/L; 5 U/L/U/L/FB; 6 U/L ×3 | Same, 6 → PPL ×2 | FB at every count | Follows branch | Preference (frequency neutral at equal volume); offered on Your plan |
| Main scheme, A year or more | `RAMP_TOP_BACKOFF`, scheme ramps 60% ×5 and 80% ×3, TOP ×5 at 2 RIR, 2 back-offs at 90% ×5 | `STRAIGHT` 3 × 6–10 at 1–2 RIR | `STRAIGHT` 2 × 8–15 at 2–3 RIR | `STRAIGHT` 2 × 6–10 at 3 RIR | Strong (≥80% 1RM for strength; load-agnostic for size near failure) |
| Main scheme, New or Under a year | `STRAIGHT` 3 × 5 at 3 RIR for six exposures, then 2 | `STRAIGHT` 3 × 8–12 at 2–3 RIR, then 1–2 | As general | As 65+ | Practice (first weeks achievable; affect predicts adherence) |
| Second exposure of a main | `SECONDARY` role: a variant or the same lift, 2 × 5 at 3 RIR | Same lift or variant, 2 × 8–12 | none | none | Moderate (mains 2 to 3 times a week for strength) |
| Accessories | 3 × 8–12 at 2 RIR | 3 × 10–15, isolation at 1 RIR | 2 × 10–15 at 2 RIR | 2 × 8–12 at 3 RIR | Moderate |
| Weekly sets per muscle (band) | 6 to 12 | 10 to 14 (New, Under a year), 12 to 18 (A year or more), cap 20 | 4 to 8 | 6 to 10 | Strong for shape, moderate for the numbers |
| Rest by role and goal (`RestPolicy.defaultSeconds(role, goal)`, seconds) | MAIN 180, SECONDARY 120, ACCESSORY 90, ISOLATION and CORE 60 | MAIN 120, SECONDARY 120, ACCESSORY 90, ISOLATION and CORE 60 | MAIN 90, ACCESSORY 90, ISOLATION 60 | MAIN 120, ACCESSORY 90, others 60 | Moderate to weak (preference past 90 s) |
| Order | Main first | Main first | Main first | Balance block (2 × 30 s single-leg stance, 2 × 20 m carry) first; the dose ladder never removes it | Strong (target lift early); strong (balance work for 65+, WHO 2020) |
| Extras | none | none | Optional easy finisher (activity from the Both or Just cardio answer; off if none) | Fast-concentric variant allowed once technique holds (later swap) | |

Day skeletons reuse today's pattern lists (spec §6.3) with two changes: the Overhead press row is a real `V_PUSH` main slot, and the Pull row's choice decides whether the pull main is horizontal or vertical. Isolation rotates as today. Core on every day stays.

Opening loads (`SlotSeeding.rows`), in order: the user's recent number → `Estimate`; else `known.lastWorking` for that exercise → `Estimate` with its reps and rir (null rir reads as 2), so a 225 × 5 history opens a 3 × 10 slot at the matching load, not at 225; else the floor with `calibrating = true`: barbell or trap bar at the bar (45 lb / 20 kg), dumbbells 10 lb / 5 kg a hand, machine or cable 20 lb / 10 kg, kettlebell 18 lb / 8 kg, bodyweight entries use their rep target.

`Estimate` (`domain:progression/Estimate`): with reps r, reported RIR k and n = r + k ≤ 10, e1RM = w × (1 + n/30) (Epley); the load for a target of t reps at RIR j is e1RM / (1 + (t + j)/30), rounded to the lift's step. When n > 10 the entered weight opens the lift with `calibrating = true`.

Increment per lift (`incrementPct` on the rule, editable in Lift options): New and Under a year 5% lower body, 2.5% upper and small; A year or more 2.5% lower, 1.25% upper; rounded to the nearest equipment step, never less than one step, never more than 10%. The step comes from the recipe's `LoadSteps` and the exercise's equipment at read time (barbell step, dumbbell step, stack step; kettlebells use the dumbbell step), not from the rule. When a step is coarse (step ÷ load > 0.10: a 10 lb dumbbell with a 5 lb step, a 20 lb stack with a 10 lb step), the slot progresses reps first: a Fixed target becomes a Range(t, t + 4) and the step is allowed only at the top of the range. Bodyweight slots add one rep per success to a cap of 20, then the plan offers the library's weighted pair as a swap (`Reason.ADD_WEIGHT`).

#### 3.1.2 Run rules

Backbone for every event: easy sessions plus the quality sessions below, every session's WORK total capped at 110% of the 30-day longest, the taper in §3.2, and two 25 min strength blocks a week (`SECONDARY` role: squat or trap-bar deadlift, a single-leg lift, calf, Nordic, core) placed as the table in §2.1 shows, which the user can set to 0 or 1 on Your plan.

Readiness gates (moderate, observational; Fokkema 2020, Videbæk 2015): a plan never refuses an event, it starts where the log says, and moves through the phases below in order, skipping any whose gate the log already meets. The Plan screen states the phase and, when the date is too close for the remaining phases, says so (`Reason.DATE_TOO_CLOSE`) and still generates.

| Phase | Structure | Gate to leave it |
|---|---|---|
| Foundation 1 (ladder) | The run/walk ladder, 3 sessions a week, exempt from the run cap (the ladder is its own guardrail) | Level 12 finished (30 min continuous), which seeds the cap |
| Foundation 2 (base) | The 5K to 10K structure; the long run grows 5% a cycle in the block's progress measure (distance for distance ends, time for time ends, A16) under the cap | Half: a finished run of 10 km or 60 min. Marathon and ultra: the same, then the half build |
| Half build | §3.1.2 half row | Marathon: a finished 21 km run or a 30 km week. Half: the taper |
| Marathon build | §3.1.2 marathon row | Ultra: a finished run of 30 km or 3 h, or a logged marathon. Marathon: the taper |
| Ultra build | §3.1.2 ultra row | The taper |

Every gate has a time form, so a runner who logs time only (no GPS yet, and no manual distance at Finish) still moves through the phases; the half-build gate for the marathon is a 21 km or 2 h run, or a 30 km or 3 h week.

With Running: Some or Racing and a longest run on record, the plan starts at the first phase whose gate that run does not meet. Without any longest run and no ladder (Some or Racing), there is no cap until the first finished session, which then seeds it.

| Sub-goal | Sessions a week (from days) | Session mix | Long run target | Quality | Grade |
|---|---|---|---|---|---|
| First 5K (Running: New) | 3 | Run/walk ladder, all easy | Not separate | None for 8 to 12 weeks | Moderate (novice injury and quit rates) |
| 5K to 10K | 3 to 5 | Easy ×(n−1), 1 quality; 2 quality at ≥4 for Some or Racing | 25 to 30% of weekly volume, up to 60 to 90 min | Threshold (20 to 30 min, or 3 to 4 × 8 to 10 min) alternating with VO2max (5 to 6 × 3 to 4 min hard, equal easy) | Moderate (distribution), preference (reps) |
| Half marathon | 4 | 3 easy, 1 quality | Build to 18 to 21 km or 2 h | Threshold; VO2max optional | Moderate (observational) |
| Marathon | 4 to 6 | Easy, 1 quality, long | Build to 28 to 32 km or 3 h; marathon-pace segments inside long runs in the last 6 to 8 weeks | Threshold | Moderate (observational) |
| Ultra (50K, 50 mi, 100K, 100 mi) | 4 to 6 | Easy, 0 to 1 quality, long; back-to-back long weekends optional (editable) | 50K and 50 mi: 60 to 80% of race distance (50K: 30 to 40 km, capped at 3.5 h); 100K and 100 mi: 4 to 6 h time-on-feet; hiking for trail | Threshold or hills | Weak |
| Middle distance (800 m, 1500 m, mile, 3000 m) | 3 to 5 | Easy, strides 1 to 2, 1 race-pace interval, 1 threshold | 60 to 75 min | 1500 m, mile, 3000 m: 6 to 8 × 400 m at race pace, 2 min jog. 800 m: 3 to 4 × 300 to 500 m at race pace with full recovery in the last 6 weeks, 6 × 400 m before that | Weak (elite practice), moderate for mostly easy |
| Fitness test | 3 to 4 | 1 interval, 1 tempo, 1 to 2 easy | Not central | 4 to 6 × 400 to 800 m at 5% faster than goal pace; tempo 15 to 25 min; practice test 2 to 3 weeks out; retest every 4 to 6 weeks; PACER adds 20 m shuttle repeats | Moderate (intervals, military data) |
| Just cardio fitness | days | Easy ×(n−1), 1 interval after 4 to 8 consistent weeks | none | 4 to 6 × 3 to 4 min hard | Strong that both work, moderate for the edge |

Run intensity is prescribed in three words, each a talk-test band with an RPE (CR-10) range behind it that the user never sees or types: Easy (full sentences, RPE 3 to 4, below the first ventilatory threshold), Good (short phrases, RPE 5 to 7, steady and tempo work), Hard (a few words, RPE 8 to 10, intervals and time trials). Pace aims appear only when a goal time or recent result exists. At Finish a cardio session takes one of the same three words, stored as `cardio_session.effort` (EASY, GOOD, HARD; nothing is preselected, and null = not reported, which reads as the session's own band); the word drives the ladder and sprint progression. A quality session counts toward progression unless it was rated above its band: an interval session counts on any word, a tempo session on Easy or Good. The run/walk ladder (editable table, practice): levels of run/walk seconds × rounds, 1: 60/120 ×8, 2: 90/120 ×8, 3: 120/90 ×7, 4: 180/90 ×6, 5: 240/60 ×6, 6: 300/60 ×5, 7: 480/60 ×3, 8: 600/60 ×2, 9: 900/60 ×2, 10: 20 min continuous, 11: 25 min, 12: 30 min. Ladder sessions are exempt from the run cap; advancing one level per finished session rated Easy is the ladder's own guardrail, and the level 12 run is the first cap anchor. Good repeats the level rather than advancing: the ladder's sessions belong in the easy band, where the talk test tracks the first ventilatory threshold (talk test review 2022), novice programs advance only when the step felt manageable (Relph 2023; the first-5K default in the evidence review), and how a session feels while it is moderate is what predicts coming back (Rhodes & Kates 2015). A past running injury starts one level lower. The current level, the quality-session alternation, the intro weeks left and the sprint volume live in `ProgramDay.cardioRule: CardioRule` (`program_day.cardioRuleJson`), the cardio analog of `SlotRule`.

Run days are `ProgramDayKind.CARDIO` days inside the rotation (letters, never `C1`/`C2`), with an optional strength block after the run as §2.1's table shows. Rest entries: one after the long run; one between the two quality sessions when a week has two; for sprint plans one between any two sprint days.

#### 3.1.3 Sprint rules

| Rule | Default | Grade |
|---|---|---|
| Sessions | Two sprint days at least one rest entry apart; two lower-body strength days (squat or trap-bar deadlift, hinge, single-leg, 3 to 5 × 3 to 6 at 2 RIR) each opening with 40 to 80 plyometric contacts (pogos and skips, building to bounds over 6 to 10 weeks); Nordic 2 × 4 to 6 on one lifting day; optional tempo day | Moderate-strong (strength transfer), moderate (plyometrics), moderate-contested (Nordic) |
| 40 yd to 100 m | Day 1 acceleration: 4 to 8 × 10 to 30 m from a 2- or 3-point start, rest 1 min per 10 m, 100 to 200 m total to start. Day 2 max velocity: 3 to 5 × 10 to 20 m flying after a 20 to 30 m build-up, rest 4 to 6 min | Weak (coaching consensus) |
| 200 to 400 m | Keep one acceleration or max-velocity day; the other is sprint endurance 3 to 4 × 100 to 150 m at >95% with 8 to 12 min rest, or 60 to 80 m series; add tempo 100 to 300 m at 60 to 70% | Weak |
| Warm-up | Every sprint day opens with a 10 min build-up block before the first maximal rep | Consensus |
| Intro | No all-out sprint in the past month, or a hamstring strain in 12 months: the first three sprint weeks run the low end of every range | Practice |
| Progression | Add one rep, then distance within the range, every two finished sessions whatever their word (the sprint band is Hard, so Hard is the expected rating; a session with an unfinished WORK set or a Pain flag does not count); intensity stays maximal; weekly max-speed meters rise at most 15% (editable); a 14-day gap resets to intro volume. State lives in the day's `CardioRule` | Practice |
| Timing | Time trial every fifth cycle at the end of a light week; attempts store timing method, start type and surface; compare like with like; hand-timed differences under 0.1 s read as "same" | Moderate (hand timing bias), inference (noise floor) |
| Phone timer | On a sprint WORK rep the Log button reads "Start on the beep": a 3-2-1 countdown, a beep, the clock runs, one tap stops it. The time is stored in hundredths with its own method, `PHONE`, and compares only with other `PHONE` runs of the same distance and setup (start protocol); charts and records split by distance, method and start protocol, and the record reads "Phone-timed best". No conversion between methods; the 0.1 s hand-timing noise threshold does not apply to `PHONE`. Typed entry with a method (`HAND` partner stopwatch, `VIDEO`, `GATES`) stays. No watch part. Copy under the result: "Approximate beep-to-tap time; compare only with your phone-timed runs using the same setup." | Council decision (2026-11-08); moderate that methods differ by a fixed bias, so like compares only with like |

Sprint reps are cardio blocks: `WARMUP` time, `WORK` distance sets flagged hard with `RECOVERY` time between, so #536's rounds editor covers them. Each timed rep writes `cardio_set_result.centis` together with its `timingMethod` and `startProtocol`, immutable once written, so a result can never be re-read under another method; a time trial's best rep also becomes a `PlanGoal.Sprint` attempt with the same three facts.

#### 3.1.4 Both

| Case | Layout | Grade |
|---|---|---|
| Lift primary, separate days | Lift days as the lift branch; cardio days from the run sub-goal, run days placed after upper days where the week allows | Weak-contested (leg interference in trained lifters) |
| Lift primary, same visit | Lift days with the cardio block after the lifts (the existing finisher position) | Moderate (primary first) |
| Cardio primary | Run plan as §3.1.2 with two 25 min strength blocks; same visit puts the strength block after the run and the day opens on the cardio page (#533) | Moderate for benefit; inference for the dose |
| Sprint plus lifting, 4 days | Sprint + plyos; lower strength; rest; sprint; lower strength + Nordic | Moderate (low interference), weak (order) |

No cardio cap anywhere (no minutes-per-week threshold is supported).

#### 3.1.5 What the planner absorbs or retires

| Today | Fate |
|---|---|
| `ProgramGenerator`, `SplitDefaults`, `CardioFirstPlanner`, `CardioPlanner` | Deleted in PR 6 with the old wizard, their last caller. `templateFor` goes with them (§4.6) |
| `CardioIntervals` | Stays. It is a production dependency of `CardioBlocks.fromLegacy` (`CardioBlocks.kt:20`) and of the watch (`WearApp.kt:150, 257`, `DialState.kt:613`); it goes only in a later PR, named when the watch's cardio suggestion and the legacy cardio migration stop calling it |
| `WizardAnswers`, `CardioFirst`, `AnchorScheme`, `DeadliftVariant`, `SplitTemplate`, `LiftEntry`, `CardioPrefs`, `CardioPlacement` | Deleted in PR 6 with the old wizard. `CardioMode` stays: the retained `CardioBlocks` (`legacyModeOf`, `activityOf`), `CardioExecution`, `HistoryRepository` and `CardioRecordMapper` still read it, and it goes only with the PR that migrates those callers, like `CardioIntervals`. A small `V1RecipeDto` in `data:serialization` reads three fields of an old recipe during conversion (§4.4): `equipment` and `goalsLb` (carried into `plan.settingsJson`) and `cardioMode`/`fiveKGoal` (the existing legacy cardio migration). Nothing else is read from it |
| `CardioBlocks.fromLegacy/legacyModeOf/activityOf` | Stay in `:domain` for the cardio migration and CSV import, still calling `CardioIntervals` for the shipped vectors; `CardioIntervalsTest` keeps pinning them |
| `GoalCalculator`, `StrengthStandards`, `LifterConfig`, the `GoalSource.Std/FracOfStd` ratio values | Deleted in PR 6, the PR that removes their last callers (the old wizard, `WizardAnswers.config`, `ProgramGenerator`), together with `PinnedVerificationTest` and the spec §4 constants (owner decision); PR 1 moves `round5` and the PROTOTYPE constants into `Scheme` so `SetSeeder`, `SetEditor` and `GeneratedLoad` stop reading `StrengthStandards` first. The library keeps a `mainCapable` marker on the entries that are `Std` today; conversion and Your lifts read it. `DaySeeds.plan/previewMain/swap` call `SlotSeeding.rows` for every slot; `PerformedDay.of` takes rules plus `KnownLoads` instead of `WizardAnswers`; `PerformedExercise.goal` is the plan's `goalsLb` entry or null, so the Day header's target reads the working rows and the watch `goalLabel` shows the user goal or nothing; `PrescriptionDraft` defaults to the slot's working weight or the floor. `GoalSource.Flat/Reps/Time` stay as the library's tracking-type source |
| `SetSeeder.shape`, `GeneratedLoad`, `PlateMath` | Kept; `shape` takes a `Scheme`. `Scheme.PROTOTYPE` (ramps 55/70/80/90 × 5,5,5,3, one back-off at 75% × 8) is the scheme converted plans keep; `Scheme.STRENGTH` (§3.1.1) is the generated default |
| `PlanRepository.createAuthoredPlan`, add lift, swap | Every slot they create gets `SlotRule.defaults(role, goal, structure, reps)` and rows from `SlotSeeding.rows`; the plan's "Defaults for" goal lives in `plan.settingsJson`. From PR 2a until PR 6 the old wizard's `createGeneratedPlan` runs `convertPlans` on the plan it just inserted, so no plan ever exists without rules |
| `ExerciseLibrary`, `availableWith` | Kept; `availableWith(equipment, excluded)` adds the exclusion set. Every entry gains `primary: Set<Muscle>` and `secondary: Set<Muscle>` (`Muscle`: CHEST, FRONT_DELT, SIDE_DELT, REAR_DELT, LATS, UPPER_BACK, BICEPS, TRICEPS, QUADS, HAMSTRINGS, GLUTES, CALVES, CORE), the one source for set counting; the overhead press is FRONT_DELT primary and TRICEPS secondary, never chest. New entries: pogo hop, A-skip, bound, box jump, single-leg stance (timed), sit-to-stand |
| `Rotation`, `Program.rotationDays` | Kept. The `C1`/`C2` name rule stays for converted plans that still hold those extras; new plans never mint those ids, so new CARDIO days rotate |
| `PlanGoal`, `GoalProgress` | Adapted: `Timed.underSeconds` becomes nullable ("just finish"), `PlanGoal.Sprint` added |

### 3.2 `domain:progression/Progression`: the log to next targets

```kotlin
object Progression {
    data class Exposure(val planned: List<LoggedSet>, val actual: List<LoggedSet>, val epochDay: Long, val roughDay: Boolean)
    data class Context(val unit: WeightUnit, val equipment: Set<Equipment>, val todayEpochDay: Long, val light: Boolean)

    /** Rows a run opens with: layoff scaling and the light cycle applied as a view. Nothing is written back.
     *  [lastTrainedEpochDay] is the exercise's last session anywhere in the plan; [expectedRecurrenceDays] comes from the plan. */
    fun atStart(rule: SlotRule, working: List<LoggedSet>, lastTrainedEpochDay: Long?, expectedRecurrenceDays: Int, ctx: Context): Opening

    /** While a slot is calibrating: the weight to pre-fill on the next pending set. */
    fun nextSet(rule: SlotRule, logged: List<LoggedSet>, pending: LoggedSet, ctx: Context): LoggedSet

    /** After Finish: next time's working rows, the updated rule, and the one change with its reason. */
    fun afterSession(rule: SlotRule, working: List<LoggedSet>, exposure: Exposure, ctx: Context): Outcome

    fun cardioAtStart(block: CardioBlock, rule: CardioRule?, recent: CardioHistory, goal: PlanGoal?, ctx: Context): CardioBlock
    fun cardioAfterSession(block: CardioBlock, rule: CardioRule?, results: List<CardioSetResult>, effort: CardioEffort?, recent: CardioHistory, goal: PlanGoal?, ctx: Context): CardioOutcome
}

data class Outcome(val rows: List<LoggedSet>, val rule: SlotRule, val change: Change, val reason: Reason)
sealed interface Change { Hold; Increase(from, to); Decrease(from, to); RepsTo(n); Calibrated(lb); KeptLowered(lb); Restart(factor); LightCycle }
```

Interface facts: `working` is the slot's `exercise_log` rows and the only home of the load; `rule.state` is the only home of streaks and the calibrating flag: Finish writes engine state and shared success credit, and accepting calibration applies its proposed flag; `atStart` writes nothing and returns `Opening(rows, layoff: LayoffKind, ruleOverride: SlotRule?)`, which the run stores in its plan JSON (`SessionPlanJson.layoff`, additive) so that `Exposure.layoff` carries it back; `afterSession` is called once per slot at Finish from `TrackerRepository.progress`, the single write point, and the data layer writes `rows` and `rule` together, and the prior state into `workout_session.progressionUndoJson` so the receipt's Undo survives process death; a Finish also clears any older undo record on the same plan, so only the latest Finish is undoable. The undo record holds everything that Finish wrote: every slot's prior rows and rule, the day's prior `CardioRule`, and the plan's prior `lightCycleRemaining` and `cyclesCompleted`, so a reactive or planned light cycle fired by that Finish is undone with it (Undo restores all of it and clears the column; the column is cleared when the day's next run starts). Next time mode per slot: `keepToPlan` (Plan) wins, then `rule.nextTime` (Automatic or Ask) when set, then the plan's `autoApply`. Automatic applies `Change`; Ask offers all load, rep, calibration and range-reset changes, preserving stored rows and the target until acceptance; Plan writes nothing and the slot's rows reset to the plan, as today. A watch-logged set has `rir == null`, which reads as on target.

```kotlin
data class SlotRule(
    val role: SlotRole,                 // MAIN, SECONDARY, ACCESSORY, ISOLATION, CORE, BALANCE, PLYO
    val target: RepTarget,              // Fixed(reps) or Range(lo, hi, current)
    val rir: Int,                       // target reps in reserve on working sets
    val incrementPct: Double,
    val everyExposures: Int,            // 1..3 successes between increases
    val dropPct: Double = 0.05,         // 0 = misses never lower
    val scheme: Scheme,                 // ramps and back-offs for ramped structures
    val restSeconds: Int? = null,       // set only by a user edit in Lift options
    val nextTime: NextTimeMode? = null, // AUTO or ASK; null follows the plan
    val state: SlotState,               // calibrating, successStreak, repMissStreak, effortMissStreak, resetEpochDays (last two), exposures
) {
    companion object {
        /** Evidence defaults for a slot the user adds by hand or that conversion creates. [role] is the caller's guess
         *  (ramped or main-capable → MAIN, else ACCESSORY); [structure] picks Scheme.PROTOTYPE for ramped slots; [reps]
         *  is the first working row's count, or null for the goal's default. Rir and rest follow the §3.1.1 table for
         *  [goal]; increments by body half at the A-year-or-more values; everyExposures 1; dropPct 0.05. */
        fun defaults(role: SlotRole, goal: LiftGoal, structure: SetStructure, reps: Int?): SlotRule
    }
}
```

The lift's step is not stored: `ctx` carries the plan's `LoadSteps` and the step follows the exercise's equipment.

| Rule | Behavior | Grade |
|---|---|---|
| Effort words | Easy = 4 (four or more), Good = 2, Hard = 1, Max = 0; a missed rep is a logged rep count below target (#530's decrement); numeric 0 to 5 when the user sets Effort scale to Reps left; null = on target. A cardio session takes one of three words, Easy / Good / Hard, each an RPE band the user never sees (`cardio_session.effort`, §3.1.2) | Moderate (beginners can use RIR; underprediction about 1 rep). Moderate for RPE and the talk test |
| Success | Every working set reps ≥ target and rir ≥ max(0, rule.rir − 1). A working set below its planned load outside the carried load blocks success and counts as a rep miss; a ramped lift's back-offs regenerated from its newly carried TOP belong to that anchor. At rule.rir 2, Hard passes and Max holds; at rule.rir 3, Hard holds | Moderate (tolerance of one rep) |
| Easy session | Every working set rir ≥ 4: the increase is twice the rounded single increment, capped at 10% | Practice |
| Fixed reps, success | After `everyExposures` successes in a row: load + increment (nearest step, ≥ one step, ≤ 10%) | Weak (double progression), practice (mechanics), moderate (percentage asymmetry) |
| Range, success | Current target below hi: next target = min(hi, lowest working reps + 1), load held. At hi with every set ≥ hi: load + increment and target back to lo | Weak |
| Miss | A rep miss is any working set short of reps; an effort miss is every rep made with some set at rir < rule.rir − 1. Either resets the success streak and holds the load. Two rep misses in a row at the same load (a "stall"): load × (1 − dropPct), nearest step down; with a coarse step (step ÷ load > 0.10), target reps − 1 instead (floor 3). Two effort misses in a row: hold and `everyExposures` + 1 (max 3), streak cleared; effort alone never lowers a load. A second stall within 42 days: `everyExposures` + 1 (max 3) | Practice; moderate that an occasional miss is harmless |
| Rough day | `exposure.roughDay` (receipt checkbox): streaks untouched, rows held | Practice |
| Max | Never add load after any working set at rir 0, whatever the target; a range target may still gain a rep | Practice, from ACSM's "failure unnecessary" |
| Carried load | A completed TOP is the anchor and regenerates ramps and back-offs; a missing TOP keeps the prior anchor. Straight sets adopt one load only when a strict majority of planned WORK rows completed there, matched by row identity; ties hold. RAMP and unplanned EXTRA rows never vote. A zero-load or timed row is never lowered. A newly adopted lower anchor clears streaks and earns no increase that session; Ask offers it | Owner decision; moderate (self-selected loads work when effort is anchored) |
| Cadence | New and Under a year: 1. A year or more: 2. Stalls and double effort misses move 1 → 2 → 3, never past 3; four consecutive successes at a slowed cadence move it back one step, never below the plan's base cadence: the experience answer's value on a generated plan, 1 on a user-built or converted plan | Moderate (progress slows with training age) |
| Calibrating | While `calibrating`: a set at rir ≥ 4 (Easy) → next pending set × 1.25, rounded up to the step and at least two steps; a set at rir ≤ 3 with n = reps + rir ≤ 10 → `Estimate` gives the load for the slot's target and the slot is calibrated at it; a set at rir ≤ 3 with n > 10 (a rep-range slot) anchors directly: calibrated at that load when reps ≥ target, else at load × 0.9 to the step; remaining sets take the calibrated load. Still Easy at the end: working load = last set × 1.25, still calibrating. The weight field stays editable, so a lifter who knows the number types it | Practice (first weight from a ramp, never a 1RM) |
| Layoff (per exercise, across the plan) | gap = days since the exercise's last session in any slot or day of this plan; expected = ceil(7 × training entries in the rotation ÷ (days a week × times the exercise appears per rotation)); layoff = gap − expected. ≤ 3 normal; 4 to 7 hold this exposure; 8 to 14 × 0.95; 15 to 42 × 0.85 and working sets × 2/3 (round up) for one exposure; > 42 × 0.75 and calibrating on. On the 65+ path, or for New with under six months in the log, × 0.75 and calibrating when layoff > 14. Applied at start as a view (`Opening.layoff` = NONE, HOLD, SCALED(factor) or RECALIBRATE); the restart rows become the exposure's plan. After the session: HOLD → rows and streaks unchanged; SCALED and on target (success at the scaled load) → next time reopens at the pre-layoff working load, no increase, streaks unchanged (return within one or two sessions when RIR is on target); SCALED and a rep miss → the scaled load becomes the working load, streaks cleared; SCALED and an effort-only miss → the scaled load becomes the working load, success streak cleared, `effortMissStreak` 1; RECALIBRATE → `rule.state.calibrating` becomes true in `afterSession` and the calibration rules apply from this exposure's sets | Moderate-strong (detraining), practice (table) |
| Light cycle | `plan.lightCycleRemaining > 0`: working sets × 0.6 (round up, ≥ 1), loads × 0.9 to the step, ramps unchanged; progression holds; the counter falls by one per finished training day. Set to the rotation's training-entry count when triggered. Reactive triggers, only when the plan's `autoApply` is on: a stall on two or more lifts within one rotation; Rough day on two consecutive sessions. Planned trigger, on any plan with `deloadEveryCycles` set, Ask mode included (the light rows are a view, so no load is written): at a rotation wrap, `cyclesCompleted > 0 && cyclesCompleted % deloadEveryCycles == 0`. No planned light cycle for New | Preference (structure), weak (outcomes) |
| Cardio measure | Distance or time + 5%, pace 1% opt-in, work sets only (A16 as today) | Practice (A16) |
| Run cap | A session total is the sum over WORK and RECOVERY sets: in metres (`metresAtEnd` when present, else `metres`) when every such set has a distance, and always in seconds. A planned session is compared in metres when all its WORK and RECOVERY ends are distances and the history total has metres, otherwise in seconds (a planned distance set converts at the block's pace aim, else the family's most recent pace). The planned total never exceeds 110% of `longestRecent`'s maximum in the comparison unit (the distance maximum for metres, the time maximum for seconds, each its own session); the limit rounds down to 100 m or 1 min and never below that maximum, so a repeat always fits. A planned distance set with no pace aim and no family pace is compared in metres only, and when the history has no metres that session is uncapped. Intervals shrink by dropping rounds, never by shortening a rep. Ladder sessions are exempt (§3.1.2). No history: the setup answer stands in until the first finished same-family session or 30 days after creation, whichever comes first; after that, history only. Novice weekly distance rises at most 30% over two weeks | Moderate (single-run cap), moderate (two-week growth) |
| Run/walk ladder | Session finished and rated Easy (or not reported): next level. Good: repeat, however many times. Hard, or Pain now flagged: one level down. The Finish effort row has no preselected word | Practice; moderate that advancing on an easy-feeling session serves adherence (§3.1.2) |
| Counts as finished | One rule for records and progression: a set is finished when its planned end was reached. A time end's reading is `metresAtEnd`, a distance end's is `secondsAtEnd`; records and comparisons use those readings, phone, watch and hand-logged sessions alike (a hand-logged session has one reading, which is both). Totals (`elapsedSeconds`, `metres`) hold the overrun. Rows without at-end fields (before v25) keep today's rules in each reader | Owner decision (#526) |
| Phase and taper | From the live goal date: taper 10 days for ≤ 10K and tests (× 0.6), 14 days for the half (× 0.7, then × 0.5 in the final week), 21 days for marathon and ultra (× 0.8, × 0.6, × 0.5); WORK totals scale, hard flags and pace aims stay; the long run stops growing at the event's target | Moderate-strong (cut volume, keep intensity) |
| Goal check | Riegel T2 = T1 × (D2/D1)^1.06 up to the half; marathon prediction × 1.05. Computed in seconds as doubles and rounded to the nearest whole second once, at the end. The Plan screen states the prediction when the goal is faster | Moderate (to the half), practice (margin) |
| Sprint | §3.1.3 progression; a time trial attempt compares only with attempts of the same timing method, distance and start protocol; the phone timer's method is `PHONE`, and the 0.1 s "same" threshold applies to `HAND` only | Practice |

Editable conventions and where: per lift in Lift options: increment %, target reps or range, RIR, rest (writes `rule.restSeconds`), back-off count and %, Next time (Automatic / Ask / Plan, writing `rule.nextTime` or `keepToPlan`). Per plan in Plan settings: Next time default (Automatic / Ask), Effort scale (Words / Reps left), Defaults for (Strength / Muscle / General fitness, user-built and converted plans), Light week every N cycles (off, 4 to 8), Misses lower by (0 to 10%), Run cap (on by default), Strength days on run plans (0 to 2). Per day: "Lighter today" applies `LightWeek` to this run only.

#### 3.2.1 What progression absorbs or retires

| Today | Fate |
|---|---|
| `CarryForward.next/offer/offerForSession/workingIndices/withWorkingWeight` | Absorbed. `cleared`, `workingIndices` and `withWorkingWeight` stay as helpers in `domain:progression` |
| `WeightStepper.increment` as the rule | Retired as a rule; stays as the UI stepper step. The lift's step comes from the recipe's `LoadSteps` and the exercise's equipment at read time |
| `CardioProgression` (`offer`, `fullSuccess`, `nextTime`, `increased`, measures) | Absorbed into `cardioAfterSession`; `CardioBests.counts` and `GoalProgress` call the one "counts as finished" rule and read the at-target values |
| `DaySeeds.plan/previewMain/swap`, `SlotSeeds`, `TrackerRepository.setKeepToPlan` restore (:475), `resetDayToTemplate`, add lift, superset partner | All call `SlotSeeding.rows` (§3.1); `SlotSeeds` and the formula path are deleted. Covered by repository tests on the phone path and `SetEditApplier` tests on the watch path |
| `RestPolicy` | Gains `defaultSeconds(role, goal)` (the §3.1.1 table) beside the kind buckets, and the role-to-bucket map MAIN → TOP, SECONDARY → BACKOFF, ACCESSORY and PLYO → WORK, ISOLATION, CORE and BALANCE → LIGHT. `effectiveRestSeconds(kind, tracking, overrides, role?, goal?, slotOverride?)` stays the single resolver with this precedence: `slotOverride` (a user's Lift options edit) > the user's bucket override (bucket from the role when present, else from the kind) > `defaultSeconds(role, goal)` > `defaultSeconds(bucket)`. RAMP and BACKOFF sets always use their own buckets (90 s, 120 s), whatever the role; the role-and-goal table covers TOP, WORK and EXTRA. User-built and converted plans take their goal from `plan.settingsJson` ("Defaults for", Strength unless the user changes it), and the Strength column with those two exceptions equals today's buckets (TOP 180, WORK 90, LIGHT 60), so a converted plan rests as it did. The watch reads stamped numbers, so its contract does not change |
| `SetEditor` cascade | Kept; reads `rule.scheme` (ramp percentages, back-off count and percent). On `Scheme.PROTOTYPE` it reproduces today's cascade numbers (case 16) |
| `DayScreenBuilder.nextTime`, `ReceiptNextTime`, `SessionFinisher` | Callers switch to `Outcome`. The receipt prints the absolute weight and the change (#529) and offers Undo or "Back to {planned}" |

Deletion test: removing `Planner` scatters split, slot, load and cardio rules across the wizard, the preview, Reset day and History; removing `Progression` scatters success, miss, gap, light and cap rules across Finish, the receipt, the chip and records. Both earn their keep. One adapter each is enough: the seam is between the pure module and the repository, which is where the tests already cross.

### 3.3 Seams left open

Two follow-ups are not designed here; this is only where they will plug in.

- **Meet.** A Lift sub-goal with a date. It is a `Goal.Lift(kind = MEET)` card on Sub-goal, a `PlanGoal.Meet(date, lifts)` beside the running goals, and a build and taper driven by `Progression.atStart` from the live goal date exactly as the running taper is (§3.2 Phase and taper reads a date and a scaling table; the lift version scales sets and raises load toward the chosen lifts' openers). Nothing in this document branches on "is this a meet plan"; the date machinery is already goal-agnostic.
- **Dated programs.** A program that prescribes week by week (5/3/1, a 12-week peak) is a writer of `SlotRule`s on a schedule: it rewrites each slot's `scheme`, `target` and load at each week boundary and sets `nextTime = PLAN`-like behavior for its duration. `Progression` reads whatever rule is current and never needs to know a week exists. The one addition it will need is a plan-level week index; nothing here is placed where that column would have to move.

## 4. Data model and migrations

### 4.1 Room v25 (migration 24 → 25)

PR #539 took Room v24 and backup v26, so this design's one migration is 24 → 25 and its backup is v27.

| Table | Column | Meaning |
|---|---|---|
| `program_exercise` | `ruleJson TEXT NULL` | `SlotRule`; the conversion in §4.4 writes one on every existing slot inside the migration's release, and every new slot is born with one, so it is never null in practice |
| `program_day` | `cardioRuleJson TEXT NULL` | `CardioRule`: ladder level, quality alternation, intro weeks left, sprint volume; null on lift-only and converted days |
| `session_set` | `rir INTEGER NULL` | Reported effort; null = not reported |
| `cardio_session` | `effort TEXT NULL` | The session's word, EASY, GOOD or HARD; null = not reported |
| `cardio_set_result` | `centis INTEGER NULL`, `timingMethod TEXT NULL`, `startProtocol TEXT NULL` | A sprint rep's time in hundredths with the method it was taken by (PHONE, HAND, VIDEO, GATES) and its start protocol (for example THREE_POINT, BLOCKS, STANDING, FLYING), written together and never rewritten; backup v27 carries all three, and the restore test keeps a PHONE three-point rep and a HAND standing-start rep in separate series |
| `plan` | `settingsJson TEXT NULL` | `PlanSettings`: equipment set, `LoadSteps`, "Defaults for" goal, `goalsLb`. Written by the wizard, by Build my own, and by conversion from a v1 recipe. The home these facts need once plans are not all generated |
| `workout_session` | `roughDay INTEGER NOT NULL DEFAULT 0`, `progressionUndoJson TEXT NULL` | The receipt's "Rough day" flag; the prior rows and rules for Undo, cleared on Undo or at the day's next Start |
| `plan` | `autoApply INTEGER NOT NULL DEFAULT 0` | The plan's Next time default, Automatic (1) or Ask (0), below `keepToPlan` and `rule.nextTime`; existing plans keep today's chip behavior, new plans insert 1 |
| `plan` | `lightCycleRemaining INTEGER NOT NULL DEFAULT 0`, `deloadEveryCycles INTEGER NULL`, `cyclesCompleted INTEGER NOT NULL DEFAULT 0` | Light cycle state and cadence |
| `cardio_set_result` | `metresAtEnd INTEGER NULL`, `secondsAtEnd INTEGER NULL` | #526: the reading when the planned end was reached, written by the phone and watch executors and by the past-session form; `elapsedSeconds` and `metres` stay the totals, so the overrun is the difference |

Done criteria for the migration and conversion: a v24 database with an open run, a parked (Plan-mode) slot, a cardio-first plan and a never-opened day upgrades with the run still open, the parked rows intact, a rule on every slot, the never-opened slot's rows seeded from history or the floor, cardio blocks unchanged, and every `workout_session`, `session_set` and `cardio_session` row byte-identical to before.

JSON payloads: `SetDto.rir: Int? = null` (additive; `exercise_log`, `run_track`, `plannedJson` all gain it with no rewrite). `PlanRecipeDto` becomes a v2 shape with a `"v": 2` field (`SetupAnswersDto`: goal, days, minutes, experience, workarounds, mains, recent numbers, event answers; equipment and steps move to `settingsJson`); a recipe without `v` is v1, read once by conversion through `V1RecipeDto` and then set to null. `PlanGoalJson` adds the `sprint` variant and lets `timed.underSeconds` be absent. `GoalProgress.met` is false without a time. `SessionPlanJson.layoff` (additive) records the opening's layoff kind. No new `SetKind`. The wire gains two additive nullable fields, `WatchCardioSetResult.metresAtEnd` and `secondsAtEnd` (`domain:sync/WatchCardio.kt:36`), mapped both ways and carried through the durable queue, so a watch-run set keeps its at-target reading; an old watch sends nulls. `WatchSet.restAfterSeconds` is still stamped phone-side from the resolver.

GPS later: a future `cardio_track(cardioSessionId, …)` table keyed to `cardio_session.id` needs nothing from this migration; `DistanceSource.GPS` already exists; `metresAtEnd` is the value a tracker would fill at the planned time.

### 4.2 DataStore

`effort_scale` (WORDS, REPS_LEFT; default WORDS). The profile keys stay; in PR 6 `readGlobalLifter` starts returning `LifterProfile(bodyweightLb: Double?, age: Int?)`; `hasLifterAnswers` is replaced by null-ness. The `level` key stays until PR 6, which deletes `LifterConfig`, `GlobalLifter.level`, the You screen's Level section and the `PlansViewModel` and `BackupMapping` readers together. The You row reads "Not set" until the user enters a bodyweight (#527).

### 4.3 Backup v27

Carries every column above except `progressionUndoJson` (Undo is not offered after a restore), plus `effortScale`, `settingsJson`, and recipe v2 inside `recipeJson`. Documents before v27 restore history exactly as today and their plans as they were, then the §4.4 conversion runs on them. Tests: a v26 document restores and converts (every slot ruled, an edited goal still shows from `settingsJson`, a never-opened slot seeded from history or the floor, History unchanged), and a v27 document round-trips every new field. Ship the PlanGoal changes (§3.1.5) in the same release as v27; if a release intervenes, bump to v28.

### 4.4 Existing users: one conversion, no legacy engine

Workout history is never touched: History reads logged sets, and deleting or rewriting them is irreversible. Plans are converted once, at the v25 upgrade and after any restore of a document older than v27, by `PlanRepository.convertPlans` (the shape of `ensurePlanSettingsMigrated`): every existing plan becomes one of the user's own plans under the new engine, and the user may delete it.

Per slot, the rule is derived the boring way with `SlotRule.defaults(role, goal = Strength, structure, reps)`: role MAIN when the structure is ramped or the entry is main-capable (today's `GoalSource.Std` lifts, so a novice plan's straight-set squat keeps main rest, N8), else ACCESSORY; a Fixed target from the slot's first working row (the TOP for a ramped slot), else the goal's default; rir 2; increments 2.5% lower body and 1.25% upper, one step minimum; `everyExposures` 1; `dropPct` 0.05; `Scheme.PROTOTYPE` on ramped slots, so existing rows and the cascade keep their numbers; no rest override. Slots with stored rows keep them. Slots never opened get rows from `SlotSeeding.rows` (history for that exercise, else the equipment floor with calibrating on); the bodyweight formula is gone and nothing recomputes an opened slot. Per plan: `autoApply` 0 (Ask, so the chip stays until the user switches), `settingsJson` from the v1 recipe's `equipment` (plus default `LoadSteps` for the unit) and `goalsLb` with "Defaults for" Strength, `recipeJson` set to null (no Reset day to plan), cardio blocks and Rest entries untouched, `cardioRuleJson` null (cardio progression runs as A16 with the run cap; no ladder or sprint state). Reset day to template disappears with the old generator. The calculated goal line and "Goal met" disappear (D12); `goalsLb` entries the user typed still show.

`convertPlans` is idempotent (it skips ruled slots) and also runs on every plan `createGeneratedPlan` or `createAuthoredPlan` inserts from PR 2a on, so between PR 2a and PR 6 the old wizard's plans get rules and rows the same way and no plan ever exists with a null `ruleJson`. Converted plans behave like any user-built plan from then on: layoff scaling, light cycles on Automatic, lowered weights kept, rest by role under the Strength column (TOP 180, BACKOFF 120, WORK 90, LIGHT 60, which is what those plans rest today).

### 4.5 Issues filed separately

| Issue | How it fits |
|---|---|
| #527 profile as real data | §4.2 is the same change; this design never writes a profile |
| #528 lift note per session | Shipped as #539 (Room v24, backup v26); this design's migration follows it as 24 → 25 |
| #529 receipt next-time copy | The receipt row prints `Outcome.change` with the absolute weight; the NOW fix can land first on `ReceiptNextTime` |
| #530 miss in two taps | The decrement is the miss; a set logged below target is a miss for progression whatever its effort word |
| #531 cardio pace, #532 cardio trend | Read `metresAtEnd`/`secondsAtEnd` when present, totals otherwise; the trend uses the one "counts as finished" rule |
| #533 cardio day opens on cardio | Run-primary same-visit days rely on it |
| #534 Target heading, #535 swap search fall-through | Untouched; #535's fall-through list also respects the plan's exclusions |
| #536 interval rounds | Run/walk ladders and sprint sessions are rounds of WORK and RECOVERY sets |

### 4.6 The inventory's open decisions

1. **Loads at creation.** Yes: `PlanDraft.working` is written with the program, and every later entry point that needs rows (Plan mode restore, Reset day to plan, swap, add, partner, preview, watch blank rows, conversion of a never-opened slot) calls `SlotSeeding.rows`. `DaySeeds.plan` keeps its "seed slots with no stored log" semantics with one seeding function behind it; the formula is deleted.
2. **Per-slot rules.** One JSON column, `ruleJson`, on every slot (conversion writes the existing ones); `cardioRuleJson` is its cardio twin on the day.
3. **`templateFor`.** Deleted with the old generator; converted plans have no recipe and no Reset day. v2 recipes get Reset day to plan through `Planner.day`, which returns the day and its rows. History's main-lift membership is `rule.role == MAIN` everywhere.
4. **Profile.** Nullable, as §4.2; `LifterConfig` is gone.
5. **A14 and A13.** A14 is superseded (automatic by default with Undo; two consecutive misses lower by 5%, editable to 0). A13's "no stored plan kind, rotation-based" stands: the goal is a planner input in the recipe and a live `PlanGoal`, and screens derive what they need; "Defaults for" in `settingsJson` is a rest-and-rule default, not a kind.

## 5. Spec and PLAN amendments

**PLAN.md A19, "Setup by goal, generator from the log" (this document normative).** Supersedes spec §4 (goal math and seeding) and §6; replaces A14 and the recent-lifts A15; absorbs A16 and the cardio-first paragraph of A13; extends A18 (nullable time, sprint goal, date drives taper). A13's "§11 numbers do not move" is retired: §11.1's formula numbers go with the formula, and §11.2 below is the contract. Existing plans convert once (§4.4); workout history is kept.

**Spec §4** is replaced by §3.2's rules and the `Scheme` note; its constants are deleted. **Spec §6** is replaced by §2 and §3. **Spec §11.1** is retired: `PinnedVerificationTest` and the `GoalCalculator`/`StrengthStandards` tests are deleted in PR 6, and that same PR lands PLAN.md A19, the spec §4, §6 and §11 rewrites and the CLAUDE.md Verification paragraph (pointing at §11.2), so no review is ever bound by pinned numbers whose tests are gone. The one number that survives is the cascade's shape, re-pinned as case 16 on `Scheme.PROTOTYPE`.

**Spec §11.2 (the pinned cases).** Reference lifter R: Strength, 4 days, 60 min, full gym at 5 lb steps, A year or more, no workarounds, mains squat / trap-bar deadlift / bench / overhead press / barbell row, recent numbers squat 275 × 5 Good, bench 185 × 5 Good, trap-bar deadlift 315 × 5 Good, press 115 × 5 Good, row blank.

| # | Case | Expected |
|---|---|---|
| 1 | R's program | Days A Lower (squat MAIN, deadlift SECONDARY), B Upper (bench MAIN, row MAIN, press SECONDARY), C Lower (deadlift MAIN, squat SECONDARY), D Upper (press MAIN, bench SECONDARY, row SECONDARY), one REST after C; every chosen main is MAIN exactly once; estimated minutes ≤ 60 on every day |
| 2 | R's squat opening rows | RAMP 165 × 5, RAMP 220 × 3, TOP 275 × 5, BACKOFF 250 × 5, BACKOFF 250 × 5 (90% of 275 = 247.5 → 250) |
| 3 | R's bench opening rows | RAMP 110 × 5, RAMP 150 × 3, TOP 185 × 5, BACKOFF 165 × 5 ×2 |
| 4 | Estimate | 185 × 8 Good (n = 10): e1RM 246.67; 3 × 5 at 2 RIR opens at 200. 135 × 12 Good: n > 10, opens at 135 calibrating |
| 5 | Increase, trained | Squat 275 TOP, 5/5/5 Good twice (`everyExposures` 2): 2.5% = 6.9 → 5 → 280. Bench 185: 1.25% = 2.3 → one step → 190 |
| 6 | Increase, new | Squat 95 straight 3 × 5 Good: 5% = 4.75 → 5 → 100 after one exposure. Bench 65: 2.5% = 1.6 → 70 |
| 7 | Easy doubles | Squat 275 with every working set at rir 4: twice the rounded single increment, 2 × 5 = 10 → 285 (the 10% cap, 27.5, is not reached) |
| 8 | Miss then drop | Bench `STRAIGHT` 3 × 5 at 185 (the New-path shape): 5/5/3 → hold (missStreak 1); next 5/4/4 → 175 (× 0.95 = 175.75 → 175), streak 0 |
| 9 | Rough day | The same bench: 5/3/3 with `roughDay` → hold, missStreak unchanged |
| 10 | Lowered kept | The New-path bench, `STRAIGHT` 3 × 5 at 185, cadence 1: working sets logged 175/175/175 Good → `KeptLowered(175)`, streaks cleared, no increase this session; next opens 175; the next Good exposure → 180. On R's bench (cadence 2) the second success after the lowered session reaches 180 |
| 11 | Range | DB row 60, Range(8, 12, current 8): 10/9/8 Good → target 9, load 60. 12/12/12 at target 12 → 65 (DB step 5), target 8 |
| 12 | Calibrating | Squat from the bar, target 5 at 2 RIR: 45 × 5 Easy → next 60 (× 1.25 = 56.25, up to the step and at least two steps); 60 Easy → 75; 75 × 5 Good → n = 7, e1RM 92.5, calibrated 75, remaining sets 75. The same 75 × 5 Max → n = 5, e1RM 87.5 → 87.5 / 1.2333 = 70.9 → calibrated 70 |
| 13 | Layoff | R's squat appears twice per 4-entry rotation at 4 days a week: expected recurrence ceil(7 × 4 / (4 × 2)) = 4 days. Gap 4 → layoff 0, normal; gap 7 → 3, normal (no reduction, progression runs); gap 9 → 5, hold; gap 16 → 12 → opens 260 (× 0.95 = 261.25, `SCALED`); 5/5/5 Good at 260 → next time 275 again, no increase, streaks unchanged; a rep miss at 260 instead → working load 260, streaks cleared; 5/5/5 with a Max set at 260 → working load 260, success streak cleared, `effortMissStreak` 1. Gap 30 → 26 → 235 (× 0.85 = 233.75) with 2 working sets; gap 60 → 56 → opens 205 (× 0.75 = 206.25, `RECALIBRATE`) and `calibrating` turns on in `afterSession`, not at Start |
| 14 | Light cycle | Fixture: case 2's squat rows. Working sets TOP 275 + BACKOFF 250 ×2 (three) → × 0.6 = 1.8 → 2 sets: TOP 250 ×5 (247.5 → 250), one BACKOFF 225 ×5; ramps unchanged; progression holds (PR 1). On R's plan `lightCycleRemaining` starts at 4 and falls to 0 after four finished days (PR 2b) |
| 15 | Rest by role and goal | Straight 3 × 5 squat, role MAIN, Strength: 180 s; the same slot in a Muscle plan: 120 s; a curl (ISOLATION): 60 s; a user TOP-bucket override of 150 reaches the Strength squat; a Lift options rest of 150 on the Muscle squat beats a bucket override of 100 |
| 16 | Cascade with scheme | Editing R's squat TOP to 285: ramps 170 (171 → 170) and 230 (228 → 230), back-offs 255 (256.5 → 255). On `Scheme.PROTOTYPE` (a converted plan's ramped slot) a TOP set to 245 gives ramps 135/170/195/220 and back-off 185, today's cascade numbers kept as scheme math |
| 17 | Run cap | Longest finished same-family session in 30 days 8,050 m → limit 8,855 → 8,800 m. A planned 10,000 m long run opens at 8,800 m; 7,500 m + 5% = 7,875 m is under the limit and stays 7,875 m; a planned 10 × 1,000 m interval day opens as 8 × 1,000 m. The setup answer seeds the limit until the first finished run, then history alone |
| 18 | Counts as finished | A 15:00 set reading 3,470 m at 15:00 and stopped at 15:04 with 3,485 m: finished, record and comparison 3,470 m, overrun 4 s and 15 m; a 1.5 mi set reaching 2,414 m at 12:30 and stopped at 12:41: finished, record 12:30; a set stopped at 14:50: not finished; a pre-v25 row with no at-end fields follows today's rule in each reader |
| 19 | Taper | Half marathon, 12 days out, 18,000 m long run → 12,600 m; 5 days out → 9,000 m; hard flags and aims unchanged |
| 20 | Riegel | 2^1.06 = 2.084931. 5K in 25:00 → 10K 1,500 × 2.084931 = 3,127.4 → 3,127 s = 52:07; half 1:50:00 → marathon 6,600 × 2.084931 = 13,760.5 → 13,761 s = 3:49:21; with the margin 14,448.6 → 14,449 s = 4:00:49. One rounding, to the nearest second, at the end |
| 21 | Test aim | 2 mi under 16:00 → 400 m interval aim 1:53 (119.3 s × 0.95 = 113.3 → 113) |
| 22 | Set counting | Fixture: an Upper day with bench 3 (CHEST; FRONT_DELT, TRICEPS), OHP 3 (FRONT_DELT; TRICEPS), barbell row 3 (UPPER_BACK, LATS; BICEPS, REAR_DELT). Credits: chest 3; front delts 1.5 + 3 = 4.5; triceps 1.5 + 1.5 = 3; upper back 3; lats 3; biceps 1.5; rear delts 1.5. Invariant 5 is a property test over Muscle × days 2 to 6 × minutes 45 to 90 × every equipment preset, on rotation totals: in a rotation with no `Reason.SHORT_SESSION` day, each major muscle inside its band; in any rotation, none above 20 |
| 23 | Older adult | Any branch with 65+: no target below 3 RIR; a balance block opens each lifting day. On a 2-day full-body plan with the squat once per rotation, expected recurrence is 7: gap 10 → layoff 3, normal; gap 25 → layoff 18 → × 0.75 and calibrating |
| 24 | Rotation | `RotationTest` unchanged; a run plan E+S, Q+S, L, R visits in order and R resolves to E+S |
| 25 | Short sessions | Strength, 1 day × 30 min, five mains, A year or more: ladder steps 1 to 6; one full-body day of five MAIN slots, each one ramp at 80% × 3 (RAMP bucket, 90 s) and one working set × 5 at 120 s, no core; estimate 5 × ((45 + 90) + (45 + 120)) + 300 = 1,800 s; `Reason.SHORT_SESSION`. Strength, 2 days × 30 min: day A (squat, bench, row) stops at step 4: three mains at one ramp × 3 (90 s) plus one working set × 5 (180 s) and one core slot of 2 sets (60 s); estimate 3 × (135 + 225) + 2 × 105 + 300 = 1,590 s. Day B (deadlift, press) stops at step 3: `STRAIGHT` 2 × 5 at the working load, 180 s, plus the core slot; estimate 2 × 450 + 210 + 300 = 1,410 s |
| 26 | Coarse step | DB lateral raise 10 lb, dumbbell step 5 (50% of the load): reps first, Fixed 12 becomes Range(12, 16); 16/16/16 Good → 15 lb, target 12. Pull-up (REPS) target 8: each success adds a rep; at 20 the plan offers Weighted Pull-Up (`Reason.ADD_WEIGHT`) |
| 27 | History load | `lastWorking` squat 225 × 5 with no rir (reads as 2): e1RM 277.5; a Muscle slot of 3 × 10 at 2 RIR opens at 277.5 / (1 + 12/30) = 198.2 → 200 |
| 28 | Conversion | A pre-A19 generated plan: its squat slot (`RAMP_TOP_BACKOFF`, stored rows 130/165/190/210/235/175) converts to role MAIN, Fixed 5, rir 2, `Scheme.PROTOTYPE`, rows unchanged; a straight `hack_squat` slot never opened converts to ACCESSORY, Fixed 10, rows from that exercise's history or else 3 × 20 lb calibrating; the plan is Ask mode with `settingsJson` holding the recipe's equipment and `goalsLb` and `recipeJson` null; its cardio blocks and Rest entries are unchanged; every history row is byte-identical. A v26 backup restores and converts to the same state |
| 29 | Event identity | 800 m: the last six weeks open at the low end, 3 × 300 m at 800 m pace with full recovery, then 4 × 300 m, then 4 × 400 m, one unit every two finished interval sessions whatever their word (the interval band is Hard); 6 × 400 m at race pace before those weeks. 1500 m: 6 × 400 m at race pace, 2 min jog. 50K: long run target 30,000 m rising to 40,000 m, capped at 3.5 h |
| 30 | Readiness | Running New, marathon 30 weeks out, 0 runs a week, no longest: Foundation 1 at ladder level 1 (60/120 × 8), no cap. Level 12 finished as a 5,000 m run anchors the cap at 5,500 m and opens Foundation 2; the long run grows 5% a cycle under the cap, so the half gate (a finished 10,000 m run) comes after 15 increases (5,000 × 1.05^15 = 10,394.6 → 10,395; 1.05^14 gives 9,900); then the half build until a 21 km run or a 30 km week; then the marathon build. With the date 8 weeks out the plan still generates and shows `Reason.DATE_TOO_CLOSE` |
| 31 | Undo | Finish writes every slot's prior rows and rule, the day's prior `CardioRule`, and the plan's prior `lightCycleRemaining` and `cyclesCompleted` to `progressionUndoJson`; Undo after a process restart restores all of it, so a reactive light cycle fired by that Finish is undone too, and clears the column; the day's next Start clears it unread |
| 32 | Effort misses | The New-path bench at rule.rir 3: 5/5/5 with one set Hard (rir 1) in two consecutive sessions → load holds 185, `everyExposures` 1 → 2, streak cleared. R's bench at rule.rir 2: two consecutive sessions each with a Max set and every rep made → holds 185, `everyExposures` 2 → 3. Two consecutive rep misses (5/5/3 then 5/4/4) → 175 |
| 33 | Direct calibration | A Muscle DB row, target 10 at 2 RIR, calibrating, 40 lb typed: 40 × 10 Good → n = 12 → calibrated at 40. 40 × 9 Good (n = 11, short of 10) → 40 × 0.9 = 36 → 35 |
| 34 | Ladder and cap | Twelve finished sessions rated Easy advance the ladder 1 → 12 with no cap applied at any level; a Good session repeats its level (three Good sessions in a row still repeat), a Hard session drops one; the level 12 run of 5,000 m becomes the first anchor (limit 5,500 m); a repeat 5,000 m run always fits because the limit never falls below the largest total |
| 35 | Planned light in Ask mode | An Ask-mode plan with `deloadEveryCycles` 6 fires the light cycle at its sixth wrap (`cyclesCompleted` 6), shows light rows, writes no load, and offers no chip during the light days; the same plan never fires a reactive light cycle |
| 36 | Over budget | Strength, 65+, 1 day × 30 min, five mains, A year or more. The balance block (2 × 30 s stance, 2 × 20 m carry, 60 s rest, 4 × 105 = 420 s) is a WHO recommendation, not an accessory, so no ladder step removes it. After step 6: 5 × ((45 + 90) + (45 + 120)) + 420 + 300 = 2,220 s, so the planner marks `OVER_BUDGET(420)` and Your plan reads "Over by 7 min" (seconds rounded up to the next minute) with the five lifts intact. Longer sessions (45 min) refits at step 1: mains 5 × 2 × (45 + 120) = 1,650 + core 210 + balance 420 + 300 = 2,580 ≤ 2,700. Drop a lift (the row) leaves 4 × 300 + 420 + 300 = 1,920, "Over by 2 min", and the choice shows again; a second drop gives 3 × 300 + 420 + 300 = 1,620 and the day fits |
| 37 | Sprint timer | A 30 m rep from a 3-point start stopped by the phone timer at 4.52 s stores `centis` 452, `timingMethod` PHONE and `startProtocol` THREE_POINT in one write that nothing later edits; a typed 4.30 with gates, a partner's 4.40 stopwatch (`HAND`) and a `PHONE` 40 m rep are three other series and never compare with it; the best `PHONE` 30 m 3-point rep of a time-trial session becomes the `PlanGoal.Sprint` attempt and the record reads "Phone-timed best 4.52"; a later `PHONE` 4.45 on the same setup is a new best (no 0.1 s threshold), while a `HAND` 4.45 against a `HAND` 4.52 reads "same" |
| 38 | User-built plan | Build my own, Defaults for Strength, a DB curl added as 3 × 8 at 30 lb: `SlotRule.defaults` gives ACCESSORY, rir 2, increment 1.25% (upper body) → 0.375 → one step 5, which is coarse (5 ÷ 30 > 0.10), so the target becomes Range(8, 12, current 8); 12/12/12 Good with the target at 8 → target 12, load held at 30 (§3.2 Range row); 12/12/12 Good at target 12 → 35 lb, target 8. A bench added as `RAMP_TOP_BACKOFF` 185 × 5 gets MAIN, Fixed 5, `Scheme.PROTOTYPE` rows 100/130/150/165 × 5,5,5,3, TOP 185, B/O 140 × 8 (`GeneratedLoad.round` to the nearest 5: 0.55 × 185 = 101.75 → 100; 0.70 → 129.5 → 130; 0.80 → 148 → 150; 0.90 → 166.5 → 165; 0.75 → 138.75 → 140) |

Tests that change: `PinnedVerificationTest`, `GoalCalculatorTest`, `StrengthStandardsTest` and every test that builds a `LifterConfig` are deleted or rewritten against rules (the inventory counts about 40 files constructing defaults and about 80 hard-coding 235 or its ramp numbers; PR 6 owns that sweep, in the same PR as the deletions and the A19 docs). `ProgramGeneratorTest` (45), `ProgramGeneratorAnchorsTest` (6), `CardioFirstPlannerTest`, `SplitDefaultsTest`, `WizardStateBuilderTest`, `WizardViewModelWiringTest`, `WizardLiftFieldsTest`, `WizardOperationStatesTest`, `WizardRestoreWiringTest`, `SetupStateBuilderTest` goal rows, `PlanScreenBuilderTest` goal rows, `JournalBuilderTest` goal line: deleted and replaced by `PlannerTest`, `PlannerRunTest`, `PlannerSprintTest`, `SetupFlowTest`. `CarryForwardTest` → `ProgressionTest`; `CardioProgressionTest` → `ProgressionCardioTest`; `CardioBestsTest` uses the shared rule. `CardioIntervalsTest` and `CardioBlocksTest` move to `:data` as `LegacyCardioVectorsTest`. `RestPolicyTest` adds role and goal cases. `SetEditingTest`, `SetEditTest`, `BodyweightTest` re-pin the same cascade numbers on `Scheme.PROTOTYPE` (case 16). `PlanRecipeJsonTest` covers v2 and `V1RecipeDto`'s three fields; `PlanConversionTest` covers case 28; `PlanSettingsTest` and backup v9 to v27 tests extend with the §4.3 restore-and-convert cases. `DaySeedsTest`, `PerformedDayTest` and `SetEditApplierTest` gain ruled-slot cases for every seeding entry point. CSV: `HistoryCsvWriter` adds a `rir` column (integer 0 to 5 or blank) for strength sets; the existing `rpe` column is untouched, since cardio import reuses it (`HistoryCsvReader:218`); the reader accepts files with or without `rir`; `HistoryCsvRoundTripTest` covers both.

## 6. Copy

American English, sentence case, one disclosure per screen, no advice beyond the fact stated.

**Why we ask** (one sheet per screen):

- Goal: "The goal sets the shape: how heavy, how many sets, what the cardio is for. It doesn't set a number to hit. Logging and the next target do that."
- Sub-goal (Lift): "Heavier loads build strength (ACSM 2026). Size comes from enough sets near your limit at any load. Fitness needs two strength days and easy cardio (WHO 2020)."
- Sub-goal (Run): "Events differ in how far the long run goes, what the hard sessions are, and how long the taper is. Most training stays easy for all of them."
- Your week: "Two strength days covering the major muscles is the floor in WHO 2020 and ACSM 2026. Past that, how the week is spread barely changes results, so pick what you can keep. Sessions are cut to fit the minutes."
- Equipment: "The smallest weight you can add decides whether a lift moves by load or by reps first."
- About your training: "Experience sets the starting effort and how fast loads move. The other answers keep certain lifts, loads and tests out of the plan. Nothing here is stored as a profile."
- Your lifts: "Choosing your own lifts makes training feel better (Oliveira 2015), and in a trial of older adults self-chosen loads built as much strength as assigned ones. Each one you pick is in the plan."
- Recent numbers: "A set of ten or fewer with how many reps you had left gives a working estimate (Epley). Leave a lift blank and its first session finds the weight."
- Your event: "The date sets the build and a taper of one to three weeks that cuts volume and keeps intensity (Bosquet 2007). A recent result predicts pace up to the half marathon (Riegel)."
- Where you are: "No single run goes more than 10% past your longest in the last 30 days (Frandsen 2025). This answer stands in until there's a log."
- Your event (Sprint): "Hand times run about a quarter second faster than electronic ones, so results compare only with the same method."
- Which first: "Doing the main goal first in a session protects it (Eddens 2018). Lifting and cardio in one session cost some explosive strength; sessions three hours apart showed no such cost (Schumann 2022)."
- Your plan ("Why this plan"): the plan's `Reason` list, each one line, for example "Squat and deadlift twice a week: strength gains favor two or more exposures (ACSM 2026)." · "Sets stop 2 reps short: training to failure isn't necessary (ACSM 2026)." · "Rest after C: the two heaviest days are kept apart." · "Long run 8.8 km: 10% past your longest recent run." · "One hard set per lift: the session is 30 minutes. One set beats none (ACSM 2026)."

**Receipt** ("Why" disclosure, one per receipt): "Bench 190 lb next time (+5): all sets made with reps left." · "Squat 250 lb next time: 16 days since the last squat." · "Chest press 20 lb next time: you lowered it today." · "Press 175 lb next time (−10): two misses in a row." · "Light cycle, 3 days left."

**Labels**: Effort: Easy, Good, Hard, Max. Cardio finish: "How was it?" with Easy, Good, Hard, none preselected. Cardio bands on a set: Easy (full sentences), Good (short phrases), Hard (a few words); no numbers anywhere. Sprint rep button: "Start on the beep", then "Stop"; under a phone-timed result: "Approximate beep-to-tap time; compare only with your phone-timed runs using the same setup."; the record label: "Phone-timed best". Lift options: Next time (Automatic, Ask, Plan), Reps, Reps left, Increase, Rest, Back-offs. Plan settings: Next time (Automatic, Ask), Effort scale (Words, Reps left), Defaults for (Strength, Muscle, General fitness), Light week, Misses lower by, Run cap, Strength days. Your plan over budget: "Over by 5 min", "Drop a lift", "Longer sessions". Plan screen phase line: Foundation, Build, Taper. Receipt: Rough day (checkbox), Back to 185 lb, Undo. Today: "C — Rest", "Lighter today".

## 7. Evidence used

Figures the design depends on and that the review marks **[verify]**. Only these need the primary source checked before the copy in §6 ships; the rest of the design's numbers are either read in full by the review or labeled practice.

| Figure | Used in | Primary source to read |
|---|---|---|
| Epley and Brzycki formulas; individual error ≥ 10% | `Estimate`, "Recent numbers" disclosure | Original Epley 1985; Brzycki 1993 |
| NSCA 2-for-2 increments (2.5 to 5 lb upper, 5 to 10 lower) | Sanity bound on §3.1.1 increments | NSCA Essentials, program design chapter |
| ACSM 2009 2 to 10% increase when target exceeded by one or two reps | Increment cap | ACSM 2009 full text |
| Frandsen 2025 110% single-run cap and hazard ratios | Run cap, "Where you are" disclosure | BJSM paper, DOI and CIs |
| Bosquet 2007 taper "8 to 14 days" and "about 3%" | Taper lengths, "Your event" disclosure | Bosquet et al. 2007 MSSE |
| Bosquet 2013 no loss up to 28 days; older g 0.76 vs younger 0.31 | Gap table rows 2 to 4 and the 65+ rule | Bosquet et al. 2013 |
| Hand timing 0.22 to 0.26 s faster | Sprint attempt comparison, disclosure | Hetzler; Mayhew |
| Alcaraz 2018 ≥ 160 m per session, ≥ 6 weeks | Sprint block length | Alcaraz et al. 2018 |
| van Dyk 2019 RR 0.49; Impellizzeri 2021 RR 0.59 | Nordic default and its "likely helpful" copy | Both full texts |
| Behan 2022 48 reps over six weeks | Nordic dose 2 × 4 to 6 | Behan et al. 2022 |
| Pelland plateau points (18 to 20 sets, 2 to 3 per exercise) | Muscle volume band cap 20 | Pelland et al. 2026 Sports Med |
| Conti 2026 median and 52-week wording | Not in copy; motivates the short flow | Paper's Results |
| Lally 18 to 254 days; one missed day | Not in copy; motivates rotation copy | Lally 2010 |
| Vickers & Vertosick marathon margin | The × 1.05 marathon margin (practice; replace with their mileage-adjusted model if the paper gives one) | Vickers & Vertosick 2016 |

Used without a verify mark (read in full by the review): ACSM 2026 (two days, ≥ 80% 1RM for strength, 2 to 3 RIR, frequency neutral, periodization optional, failure unnecessary, no adverse-event rise), WHO 2020 floors, Halperin 2022 RIR accuracy, Grgic 2022 failure, Singer 2024 rest, Moesgaard 2022 undulation, Hickmott 2022 and Graham & Cleather 2021 autoregulation, Latella 2022 lift asymmetry, Steele 2023 decelerating progress, Buist 2008 and Nielsen 2014 on progression, Videbæk 2015 and Relph 2023 novice rates, Rosenblat 2025 and Campos 2022 distribution, Wen 2019 intervals, Fokkema 2020 and Doherty 2020 marathon volumes, Haugen 2019 sprint categories, Schumann 2022 and Eddens 2018 concurrent training, Blagrove 2018 runners' strength, Kaushal & Rhodes 2015 habit, Coleman 2024 and Rogerson 2024 deloads, Hwang 2017 and Halonen 2024 breaks, Oliveira 2015 (affect) and PMC11653500 (equal strength gains with self-selected loads, older adults), Androulakis-Korakakis 2020 minimum effective dose (one hard set, two to three times a week; the floor behind the short-session ladder), Tanaka 2001 (recorded, unused).

## 8. PR breakdown

Each PR is bounded for a Sol build and a council review. Tier per CLAUDE.md. "Migration" marks a Room or backup version change.

| # | PR | Tier | Done when |
|---|---|---|---|
| 1 | **Progression in `:domain`.** `domain:progression/` with `Progression`, `SlotRule`, `CardioRule`, `Scheme`, `Effort`, `Estimate`, `RunCap`, `Taper`, `Riegel`, `LightWeek`, `FinishedRule`, `SlotSeeding`; `LoggedSet.rir`; `ProgramExercise.rule`, `ProgramDay.cardioRule`; `CardioSetResult.metresAtEnd/secondsAtEnd`; `SetSeeder.shape(scheme)`; `SetEditor` reads the scheme; `RestPolicy.defaultSeconds(role, goal)` and the resolver's precedence with the RAMP and BACKOFF exceptions; `Reason`, `LayoffKind`, `Opening`; `SlotRule.defaults`; `Scheme.PROTOTYPE` and `Scheme.STRENGTH`, with `round5` and today's RAMP_PCTS / BACKOFF / BACKOFF_REPS moved into `Scheme` so `SetSeeder`, `SetEditor` and `GeneratedLoad` no longer read `StrengthStandards` (the only caller change in this PR; `StrengthStandards` itself stays until PR 6) | Opus | §11.2 cases 4 to 21, 24, 26, 27, 32, 33, 34, 38 green; `CarryForwardTest` and `CardioProgressionTest` ported, old objects still present |
| 2a | **Persistence, conversion, and rules on every new slot.** Migration: Room 24 → 25 (every §4.1 column), backup v27, DataStore `effort_scale`, recipe v2 codec and `V1RecipeDto`, `PlanGoalJson` sprint variant and nullable time, the library's `mainCapable` marker; `PlanRepository.convertPlans` at upgrade, after a pre-v27 restore, and on every plan `createGeneratedPlan` or `createAuthoredPlan` inserts (§4.4); and every path that creates a slot (`createAuthoredPlan`, `TrackerRepository.addExercise` :444-455, plan and today-only swaps, superset partner adds, and `TrackerRepository.resetDayToTemplate` :594-616, which runs `convertPlans` on the reset day) writes `SlotRule.defaults` and `SlotSeeding.rows` in the same transaction, so from this build on no slot ever exists without a rule. `replaceProgram` (:348) has no production caller, only tests | Opus | The §4.1 upgrade criteria; case 28; the §4.3 restore-and-convert cases; a v27 round-trip; History rows byte-identical before and after; a plan created by the old wizard on this build has a rule and rows on every slot; create an authored plan, then add a lift, then swap it: every slot has a rule and rows at each step; reset a generated day to its template: every slot has a rule |
| 2b | **Integration.** `TrackerRepository.progress` calls `afterSession` and writes the undo record; `atStart` at run open with the per-exercise layoff stored in the run's plan JSON; `WatchCardioSetResult.metresAtEnd/secondsAtEnd` with mappings and durable-queue round trips; `cardioAtStart/AfterSession` with history and the session word; `CardioBests` and `GoalProgress` on `FinishedRule` and at-target values; executors and the past-session form write at-target readings; `DaySeeds`, `PerformedDay.of`, `PrescriptionDraft`, Plan-mode restore and Reset on `SlotSeeding` (creation, add, swap and partner paths already are, from 2a); rest by role and goal from `settingsJson`; lowered weight kept; `CarryForward` and `CardioProgression` deleted (their last callers migrate here). The formula path (`GoalCalculator`, `StrengthStandards`, `LifterConfig`, `SlotSeeds`) stays compiled and is unreachable for plan slots, since every slot now has a rule; it goes in PR 6 with the old wizard | Opus | Cases 31, 35 and 38; a watch-run 15:00 set arrives on the phone with its 15:00 reading; a converted straight-set squat (main-capable entry) rests 180 s and a converted straight curl 90 s; a lowered 175 opens at 175; a converted plan still shows the chip; a swapped-in lift opens from history or the floor, calibrating; `SetEditApplier` tests pass; no seeding path (`DaySeeds`, `SlotSeeding`, swap, partner, preview, watch rows, `PrescriptionDraft`) references `GoalCalculator`; `JournalBuilder:81` and `SetupStateBuilder.goalPreview` still do, and move to `settingsJson.goalsLb` in PR 6 |
| 2c | **Effort and progression UI.** The effort row (Easy / Good / Hard / Max) on the set page beside #530's decrement; the cardio Finish effort row (Easy / Good / Hard); `Effort scale` setting; Lift options (Next time Automatic / Ask / Plan, Rest, Increase, Reps, Reps left, Back-offs); Plan settings (Next time default, Defaults for, Light week, Misses lower by, Run cap); the receipt prints the change with Undo, "Back to", "Rough day" and its one "Why" | Sonnet (exact contract) | Logging 5/5/4 with the decrement stores rir 0 on the short set; a cardio Finish stores `effort` GOOD; Undo on the receipt restores after a process restart; a kg user sees kg steps everywhere; a converted plan's lift shows its derived rule in Lift options |
| 3 | **Planner, lift branches.** `domain:planner/` with `SetupAnswers`, `Planner.plan/day`; Strength, Muscle, General fitness, 65+ modifier; the reduced-dose ladder; `SessionBudget`; `availableWith(equipment, excluded)`; `Muscle` and the primary/secondary fields on every library entry, plus the plyometric and balance entries (Sonnet sub-task from a fixed table reviewed before entry) | Opus | Cases 1 to 3, 15, 16, 22, 23, 25, 36 green; invariants 1 to 7 as property tests over days 1 to 6 × minutes 30 to 90 × every equipment preset × experience; every combination either fits through the ladder with its Reasons or carries `OVER_BUDGET` with every chosen main still present |
| 4 | **Planner, run, test, both.** Event identity, event sessions, the readiness phase table and its gates, run/walk ladder, run cap seed and expiry, phases and taper, hybrid layouts and the §2.1 entries table, REST placement | Opus | Cases 17, 19, 20, 21, 24, 29, 30; invariants 8 and 9; a half marathon plan at 4 days × 60 min lists 3 easy, 1 quality, the long run under the cap and 2 strength blocks on easy or quality days |
| 5 | **Planner, sprint.** Sprint sessions as cardio blocks, intro phase, plyometric progression, Nordic, `PlanGoal.Sprint` with attempts and timing method, `CardioRule` sprint state, `cardio_set_result.centis` in the result model | Opus | A 100 m plan at 2, 3 and 4 days matches the §2.1 table; attempts compare only within a timing method; a 14-day gap resets volume; case 37's comparison rules |
| 6 | **Setup screens, lift.** Goal (Lift card only until PR 7), Sub-goal, Your week, Equipment, About your training, Your lifts, Recent numbers, Your plan with "Why" and the over-budget choice (drop a lift, longer sessions); new `SavedStateHandle` keys; the crash-safe create transaction (program, rules, cardio rules, working rows, recipe v2, `settingsJson`, `autoApply`, goal, under the restore lock, reusing a half-created plan on retry); History mains from role; History's goal line (`JournalBuilder:81`) and `SetupStateBuilder.goalPreview` read `settingsJson.goalsLb` only; Reset day to plan for recipe plans; the `level` DataStore key, `GlobalLifter.level`, the You screen's Level section and the `PlansViewModel` and `BackupMapping` readers removed; the old wizard removed and, with it, its last dependents deleted: `ProgramGenerator`, `SplitDefaults`, `CardioFirstPlanner`, `CardioPlanner`, `WizardAnswers` and its enums, `GoalCalculator`, `StrengthStandards`, `LifterConfig`, `SlotSeeds`, `PinnedVerificationTest` and the `LifterConfig` test sweep; the docs that must not outlive them land here too: PLAN.md A19, spec §4, §6 and §11 rewritten to §11.2, and the CLAUDE.md Verification paragraph pointing at §11.2 | Opus | Playtest selectors updated; no file in the repo references `GoalCalculator`, `LifterConfig` or `ProgramGenerator`; CLAUDE.md's Verification names §11.2 cases, not 235 and its ramps; a New lifter at 3 days × 45 min with dumbbells gets three full-body days of 2 × 8–15 at the dumbbell floor, calibrating, and the first Good set fixes the load; case 36's prompt appears and nothing is dropped until chosen; a process kill between the plan insert and the working-row write leaves no half plan on relaunch |
| 7 | **Setup screens, run, sprint, both, and the sprint timer.** Run, Sprint and Both cards appear; Your event, Where you are, sprint event, Which first and Together; goal to `goalJson`; Plan screen shows the goal date, phase line and Riegel check; the beep-start phone timer on sprint WORK reps (`AudioTrack` or `ToneGenerator` beep, `SystemClock.elapsedRealtime` for the stop, no new permission); cardio-first steps removed | Sonnet | p5's runner gets 3 runs and two 25 min strength blocks on easy or quality days; a 2-mile test plan writes a `Timed` goal with aims; a New marathoner with no runs starts on the ladder; case 37 end to end on the phone |
| 8 | **Cleanup and docs.** Move `Rotation` to `planner/` and delete the empty `generator/` package (`CardioIntervals` and `CardioBlocks` move with it, since the watch and `fromLegacy` still call them; `V1RecipeDto` stays in `:data`); CSV `rir` column and reader; DESIGN.md history row; `docs/briefs/README.md`; `docs/index.md` | Sonnet | `:domain` has no `generator/` package; CI green; CSV round-trip with and without `rir`; the briefs index names A19 |

PRs 1, 3, 4, 5 are pure `:domain` and can be reviewed on their tests alone. PR 2a is the one migration; 2b and 2c follow it. PR 6 depends on 2c and 3; PR 7 on 4, 5 and 6. Nothing is deleted before the PR that migrates its last caller: `CarryForward` and `CardioProgression` in 2b, the old wizard, the formula, the `level` key and their tests in 6; `CardioIntervals` stays until the watch's cardio suggestion and `CardioBlocks.fromLegacy` stop calling it, in a PR not in this list. Every slot created on any build from 2a on has a rule, because `convertPlans` runs on plan insert and every slot-creating path writes `SlotRule.defaults`. Between PR 6 and PR 7 the Goal screen offers Lift only; nothing is flagged, the cards are simply absent. The Meet branch and dated programs (§3.3) come after PR 8 as their own designs.

## 9. Owner decisions and open questions

Decided 2026-11-08 (the numbers are the questions as the owner saw them):

1. Automatic by default, Ask mode kept for converted plans. Yes.
2. Two consecutive rep misses lower the load 5%, editable 0 to 10%. Yes.
3. Calculated goals retire; only typed goals show. Yes.
4. Reset day to template disappears with the old generator. Yes; the owner will generate new plans and delete old ones.
5 and 11. Changed: no legacy contracts at all. The formula, `LifterConfig`, §11.1 and their tests go; existing plans convert once into the user's own plans (§4.4); history is kept.
6. Audience: competitive lifters are in scope through a Meet branch right after the core (§3.3 seam). User-built plans are first-class now; dated programs are a follow-up (§3.3 seam).
7. Effort words Easy / Good / Hard / Max, Good default, reps left as a setting. Cardio takes one of three words at Finish, Easy / Good / Hard, mapped internally to RPE bands; run intensity is prescribed in the same three words with the talk test (round 2 answer to Q16).
8. An in-app beep-start phone timer for sprints, compared like with like; typed entry with a method stays; no watch part. The "stored as hand-timed" part is superseded by 18: the method is `PHONE`.
9. Two 25 min strength blocks on run plans by default. Yes.
10. Planned light week every 6 cycles for A year or more; reactive triggers on Automatic. Yes.
12. Changed: a plan that does not fit is shown with "Over by N min" and the user chooses to drop a lift or lengthen sessions; nothing is dropped silently.
13. Ladder sessions exempt from the run cap. Yes.
14. Muscle credits from an owner-reviewed table; overhead press is front delts and triceps. Yes.

Decided in round 2 (on revision 4's questions):

15. Conversion defaults as designed (rir 2, 2.5% lower and 1.25% upper, cadence 1, drop 5%, "Defaults for" Strength). Yes.
16. Changed: cardio effort is three words, Easy / Good / Hard, never a number; each maps internally to an RPE band (Easy 3 to 4, Good 5 to 7, Hard 8 to 10). The ladder advances on Easy, repeats on Good, drops on Hard (§3.1.2 gives the evidence for Good repeating; revision 6 removed the two-repeats drop).
17. Dose-ladder steps 1 to 6 stay automatic with Reasons; only removing a main lift asks. Yes.
18. Decided by the council at the owner's request: a separate method `PHONE`, compared only with `PHONE` runs of the same distance and start protocol, records read "Phone-timed best", no conversion between methods, no 0.1 s threshold for `PHONE` (§3.1.3).

No questions are open.

## 10. Revision 1 (council round 1, 2026-11-08)

Ledger ids and what changed. Section numbers refer to this document.

- C1 (grace vs #526): grace removed. "Counts as finished" = planned end reached; records and progression read `metresAtEnd`/`secondsAtEnd`, totals hold the overrun, pre-v24 rows keep today's rules (§3.2 table, §4.1, case 18); old Q11 dropped.
- C2 (gap clock): layoff is per exercise across the plan against its expected recurrence (`ceil(7 × entries ÷ (days × appearances))`); the 65+/New row only when layoff > 14; `atStart` takes `expectedRecurrenceDays`; cases 13 and 23 recomputed and pin consecutive normal sessions with no reduction (§3.2, §5).
- C3 (feasibility): invariants reordered with minutes first and a reduced-dose ladder with Reasons (§3.1); case 25 pins 1 day × 30 min and 2 days × 30 min; new Q12.
- C4 (PR size): PR 2 split into 2a persistence, 2b integration, 2c UI; the Room upgrade and restore criteria are spelled out (§4.1, §4.3); PR 6 moved to Opus with the crash-safe create as a done criterion; Run/Sprint/Both cards absent until PR 7 (§8).
- C5 (copy): Your lifts, Which first and the failure reason rewritten (§6); the self-selected-load cite is the older-adult trial (§7).
- C6 (CSV): a `rir` column for strength sets, `rpe` untouched, reader accepts both, round-trip test (§5, PR 8).
- C7 (case 20): one rounding to the nearest second at the end; 3:49:21 (§3.2 Goal check, case 20).
- Sol S3: `LegacyRecipeDto` keeps every v1 field and decodes to today's `LifterConfig` with emphasis and goalsLb; restore tests added (§3.1.5, §4.3, §4.4).
- Sol S4 and Opus S3: `SlotSeeding.rows` is the one seeding function for creation, `Planner.day` (now returns rows), Plan-mode restore, Reset, swap, add, partner, preview, watch blank rows and `PrescriptionDraft`; `PerformedDay.of` takes a `SeedingInput`; v2 header and watch label defined; assigned to PR 2b (§3.1, §3.1.5, §3.2.1, §4.6).
- Sol S5: reps-first fallback when one step exceeds 10% of the load, bodyweight rep cap with the weighted-pair offer; case 26 (§3.1.1).
- Sol S6: cap on the session's WORK total, limit rounded down, intervals drop rounds, setup seed expires on the first session or 30 days, `cardioAtStart` takes history; case 17 recomputed (§3.1 invariant 8, §3.2).
- Sol S8: `cardio_session.effort` and `program_day.cardioRuleJson` (`CardioRule`) in Room v24 and backup v26, with restore tests (§3.1.2, §3.2, §4.1, §4.3).
- Sol S9: Middle distance picks 800 / 1500 / mile / 3000; Ultra picks 50K / 50 mi / 100K / 100 mi; case 29 (§2.2, §3.1.2).
- Sol S10: readiness gates and a foundation phase that never blocks setup; case 30 (§3.1.2).
- Sol S11: `ensureRulesWritten` writes legacy main membership from `ProgramGenerator.mainLiftIds` before PR 8 deletes it; the post-PR-8 fallback and its accepted edge are stated; case 28 (§4.4).
- Sol S12: `rule.nextTime` (AUTO / ASK) with precedence `keepToPlan` > `rule.nextTime` > `plan.autoApply` (§3.2, §4.1).
- Opus S5: effort words Easy / Right / Hard / Grinder (4 / 2 / 1 / 0); a miss is #530's decrement; grinder rule unconditional; Easy doubling at rir ≥ 4; light cycle from stalls or Rough day, never per-set effort; Muscle isolation targets 1 RIR (§2.3, §3.1.1, §3.2).
- Opus S6: calibration uses `Estimate` on any set with rir ≤ 3, × 1.25 and at least two steps on Easy, the weight field stays editable; Recent numbers offered to Under a year; case 12 recomputed (§2.2, §3.2).
- Opus S7: `lastWorking` goes through `Estimate` with null rir = 2; case 27 (§3.1.1).
- Opus S8: `workout_session.progressionUndoJson`, cleared on Undo or the day's next Start, not in the backup; case 31 (§3.2, §4.1, §4.3).
- Opus S9: `rule.restarts` false on legacy slots and reactive light cycles only on Automatic plans; new Q11 (§3.2, §4.4).
- Opus S10: `RestPolicy.defaultSeconds(role, goal)` with the resolver's precedence; `restSeconds` only for user edits; `stepLb` removed from the rule and derived from `LoadSteps` plus the exercise's equipment; case 15 extended (§3.1.1, §3.2, §3.2.1).
- Opus S11: rotation-entries-by-days table per branch, run strength placement fixed as "+S" on easy or quality days, sprint layouts at 1 to 6 days, Both's screen list (§2.1, §2.2, §3.1.2).
- Opus S15: planned trigger fires only at a wrap with `cyclesCompleted > 0`; `lightCycleRemaining` starts at the rotation's training-entry count; stall defined as a load drop after two consecutive misses (§3.2, case 14).
- Opus S16: case 7 made explicit (285); case 8 and 9 on a straight 3 × 5 fixture; case 22 replaced by a counting fixture plus a property test; taper fixed at 10 days for ≤ 10K; §2.1's 65+ line now reads "3 RIR or more"; the preview mockup shows loads, not "3×5".
- Open item 12 (#534, #535): resolved from the issue bodies; both untouched (§1, §4.5).

Not applied: none. Owner calls created rather than decided: Q11 (legacy plans excluded from restarts and light cycles) and Q12 (the ladder's last step drops a main).

## 11. Revision 2 (council round 2, 2026-11-08)

- R2-1: ramp sets always rest the RAMP bucket (§3.1 ladder, §3.2.1 RestPolicy row); the ladder is per day, step 1 names a 2-set core slot, step 3 states the STRAIGHT load; case 25 recomputed through the ladder: 1 day 1,800 s, 2 days A 1,590 s (step 4) and B 1,410 s (step 3) (§5).
- R2-2: `KnownLoads.longestRecent` is a `SessionTotal(seconds, metres?)` over WORK and RECOVERY sets; the cap compares in metres when both sides have distances, else in seconds; the limit never falls below the largest total (§3.1 KnownLoads, §3.2 Run cap).
- R2-3: `data:serialization/LegacyMains.of(recipe)`, a frozen copy of the anchor rule, feeds `ensureRulesWritten` at upgrade and at every restore on any build; the structure fallback and its "accepted edge" are gone (§4.4, §4.6, case 28, PR 2b, PR 8).
- R2-4: PR 3 adds `Muscle` with primary and secondary sets on every library entry as the one source for set counting; OHP is front delts and triceps, not chest; case 22 recomputed; invariant 5 holds only on days without `Reason.SHORT_SESSION` (§3.1, §3.1.5, case 22, PR 3, new Q14).
- R2-5: rep misses and effort misses split; effort misses hold and slow cadence, never lower a load; `SlotState` carries both streaks; case 32 (§3.2 Miss row, §3.2 SlotRule, §5).
- R2-6: the undo record holds every slot's prior rows and rule, the day's prior `CardioRule`, and the plan's prior `lightCycleRemaining` and `cyclesCompleted`; case 31 rewritten (§3.2 interface facts, §5).
- R2-7: switching a pre-A19 plan to Automatic rewrites `restarts` and `dropPct` once; the planned light trigger runs on any plan with `deloadEveryCycles`, Ask included, reactive triggers stay on Automatic; v1 plans use the Strength rest column, which equals today's buckets; Q11 restated; case 35 (§3.2 Light cycle, §3.2.1, §4.4, §9).
- R2-8: `SlotRule.legacy` flag; every seeding entry point dispatches `legacy` → `GoalCalculator` before the ruled branch; `SlotSeeding`'s contract says so; case 28 pins an unopened legacy day after `ensureRulesWritten` (§3.1, §3.1.5, §3.2 SlotRule, §3.2.1, §4.4).
- R2-9: ladder sessions are exempt from the run cap; level 12 is the first anchor; the limit never falls below the largest total; case 34 pins all twelve levels and the anchor; new Q13 (§3.1.2, §3.2 Run cap, §5, §9).
- R2-10: `Opening(rows, layoff, ruleOverride)`, `SessionPlanJson.layoff`, `Exposure.layoff`; after-session outcomes for HOLD, SCALED on target (back to the working load), SCALED with a rep miss, RECALIBRATE (state changes only in `afterSession`); case 13 extended (§3.2 interface facts and Layoff row, §4.1, §5).
- R2-11: `WatchCardioSetResult.metresAtEnd/secondsAtEnd` as additive nullable wire fields with mappings and durable-queue round trips in PR 2b; the "no wire change" claim removed (§4.1, PR 2b).
- R2-12: direct calibration when n > 10: calibrated at the set's load when reps ≥ target, else × 0.9; case 33 (§3.2 Calibrating, §5).
- R2-13: readiness phase table (Foundation 1 ladder → Foundation 2 base → half build → marathon build → ultra build) with gates; case 30 extended to the first gate, 15 increases from 5,000 m (§3.1.2, §5, PR 4).
- R2-14: `Reason`, `LayoffKind` and `Opening` move to PR 1; case 14 names its fixture and splits the PR 1 and PR 2b pins (§5, §8).
- R2-15: "trained lifters" dropped from the Schumann line (§6).
- R2-16: case 10 names the fixture and cadence; a lowered exposure clears streaks and takes no increase (§5).
- R2-17: one boundary sentence: a step is coarse when step ÷ load > 0.10, increments are capped at 10% inclusive, Easy is rir ≥ 4; ranges start at the low end and add one unit every two finished Right or Easy quality sessions, reps before distance; case 29 recomputed (§3.1, §3.1.1, §3.2 Miss and Calibrating rows, §5).

Not applied: none. Owner calls created rather than decided: Q13 (ladder exempt from the cap) and Q14 (muscle credits, OHP not chest).

## 12. Revision 3 (council round 3, the cap, 2026-11-08)

Owner decision: these ten fixes close the council loop; no further round.

- O3-1: invariant 8 now points at the §3.2 Run cap row (WORK plus RECOVERY, ladder exemption, floor) (§3.1).
- O3-2: every phase gate has a time form (10 km or 60 min; 21 km or 2 h, or a 30 km or 3 h week; 30 km or 3 h, or a logged marathon); the long run grows in the block's progress measure (§3.1.2 phase table and the sentence under it).
- O3-3: case 33's second set is 40 × 9 Right (n = 11, reps short) → 35, which exercises the × 0.9 branch (§5).
- O3-4: cadence never passes 3, and four consecutive successes at a slowed cadence step it back one, never below the experience default (§3.2 Cadence row).
- O3-5: BACKOFF sets keep the BACKOFF bucket (120 s) like RAMP keeps 90 s; the role table covers TOP, WORK and EXTRA; the "equals today's buckets" claim names the two exceptions (§3.1 ladder sentence, §3.2.1 RestPolicy row). Case 25's estimates already used 120 s back-offs and are unchanged.
- S3-1: `KnownLoads.longestRecent` holds independent distance and time maxima per family (`RecentMaxima`); the cap compares against the maximum in its own unit (§3.1 KnownLoads, §3.2 Run cap row).
- S3-2: bands are rotation totals; a rotation with a `SHORT_SESSION` day may fall under its floors and the cap of 20 never relaxes; case 22's property contract restated (§3.1 invariant 5, §5).
- S3-3: SCALED plus an effort-only miss: the scaled load becomes the working load, success streak cleared, `effortMissStreak` 1; pinned in case 13 (§3.2 Layoff row, §5).
- S3-4: the ultra gate is a finished 30 km or 3 h run, or a logged marathon, reachable from the marathon build (§3.1.2 phase table).
- S3-5: the low-end range rule is scoped to cardio with its units (a round or rep, then 100 m); lift ranges follow the §3.2 Range row (§3.1).

Nits taken as one-line fixes: run cap with no pace (uncapped when the history has no metres, §3.2 Run cap row); a Finish clears older undo records on the plan (§3.2 interface facts); `LegacyMains` freezes its own `subRank` copy (§4.4); case 30 rounds to 10,395 (§5). Invariant 5 wording, SCALED plus effort miss and the range-rule body text are covered by S3-2, S3-3 and S3-5.

### Follow-ups for PR specs

- Streak resets: a matrix of which events (success, rep miss, effort miss, Rough day, lowered-kept, each layoff kind, calibration, light cycle) reset or leave `successStreak`, `repMissStreak` and `effortMissStreak`, for the PR 1 spec. This document states each in prose; the spec should pin the matrix.

## 13. Revision 4 (owner decisions, 2026-11-08)

Each decision and where it landed.

- **5 and 11, no legacy contracts.** `SlotRule.legacy` and `restarts` removed (§3.2); `LegacyMains`, the legacy seeding dispatch and `GoalCalculator`/`StrengthStandards`/`LifterConfig` deleted (§3.1 `SlotSeeding` contract, §3.1.5, §3.2.1, §4.2); spec §4 and §11.1 retired with `PinnedVerificationTest` (§5); `Scheme.LEGACY` renamed `Scheme.PROTOTYPE` and kept only as the scheme converted plans use, with the cascade numbers re-pinned as case 16; §4.4 rewritten as a one-time conversion that keeps history byte-identical; case 28 became the conversion case; `V1RecipeDto` reads three fields of an old recipe (§3.1.5, §4.1); PRs 2a and 2b reshaped around conversion and the formula sweep (§8); Q5 and Q11 marked changed (§9).
- **Room 24 → 25, backup v27** after PR #539 (§4.1, §4.3, §3.2 and case 18 "before v25", §8).
- **6, audience and seams.** §1 audience and non-goals rewritten; new §3.3 names the Meet and dated-program seams without designing them; §8 footnote places them after PR 8.
- **6, user-built plans first-class.** §2.2 "Plans the user builds"; `SlotRule.defaults(role, goal, structure, reps)` (§3.2); `plan.settingsJson` with "Defaults for" (§4.1, §3.2.1 rest row); `createAuthoredPlan` row (§3.1.5); case 38; PR 2b and 2c.
- **7, effort words** Easy / Good / Hard / Max throughout §2 to §9 (the revision records in §10 to §12 keep the words they were written with).
- **7b, cardio RPE.** Session RPE 0 to 10 at Finish (`cardio_session.rpe`), run intensity as RPE bands with the talk test, ladder and sprint thresholds restated (§3.1.2, §3.1.3, §3.2 Effort words and Run/walk ladder rows, `cardioAfterSession(rpe)`, §4.1, §6 labels, cases 29 and 34, PR 2c).
- **8, sprint timer.** Phone timer row in §3.1.3, `cardio_set_result.centis` (§4.1), timing-method options (§2.2), case 37, PR 5 and PR 7, §6 labels.
- **12, no silent drop.** Ladder step 7 replaced by `Reason.OVER_BUDGET` and the Your plan choice (§3.1, §2.2); case 36; PR 3 and PR 6 done criteria; Q12 marked changed and Q17 added.
- **1, 2, 3, 4, 9, 10, 13, 14** recorded as decided in §9; no text elsewhere changed for them.

### Follow-ups for PR specs

- Streak resets: the matrix of which events reset or leave `successStreak`, `repMissStreak` and `effortMissStreak`, for the PR 1 spec.
- Conversion edge list for the PR 2a spec: superset partner tracks, parked (Plan-mode) slots, plans with only `C1`/`C2` extras, authored prescriptions on ramped slots (A13's set-structures amendment), and a never-opened slot whose exercise has history only in another unit.

## 14. Revision 5 (round 2 of owner answers, 2026-11-08)

- **Q15 yes, Q17 yes.** Marked decided in §9; no text elsewhere changed.
- **Q16 changed: three cardio words, no numbers.** Easy / Good / Hard at Finish, mapped internally to RPE bands (Easy 3 to 4, Good 5 to 7, Hard 8 to 10) chosen from the talk-test and three-zone evidence; `cardio_session.effort TEXT` replaces the `rpe` column (§4.1); `cardioAfterSession(effort: CardioEffort?)` (§3.2); run intensity prescribed in the same three words (§3.1.2); the ladder advances on Easy, repeats on Good, drops on Hard or two repeats, with the evidence for Good repeating stated beside the ladder (§3.1.2, §3.2 Run/walk ladder row); quality sessions count unless rated above their band, sprint sessions on Easy or Good (§3.1, §3.1.3); cases 29 and 34 recomputed; §1, §6 labels, PR 2b and 2c, and §9 item 7 updated.
- **Q18 decided by the council: `PHONE` method.** Phone-timed runs compare only with `PHONE` runs of the same distance and start protocol; records split by distance, method and protocol and read "Phone-timed best"; no conversion between methods; the 0.1 s threshold applies to `HAND` only; the result copy is "Approximate beep-to-tap time; compare only with your phone-timed runs using the same setup." (§3.1.3 Phone timer row, §3.2 Sprint row, §6 labels); case 37 recomputed.

## 15. Revision 6 (PR #541 council round 1, 2026-11-08)

- A1: case 38 recomputed: `GeneratedLoad.round` to the nearest 5 gives ramps 100/130/150/165 and B/O 140; the curl increment is 1.25% (upper body) → one step; at Range(8, 12, current 8) a 12/12/12 first moves the target to 12 and holds 30, the next 12/12/12 at target 12 reaches 35 (§5).
- A2: PR order re-cut so nothing is deleted before the PR that migrates its last caller: PR 1 moves `round5` and the PROTOTYPE constants into `Scheme` (its only caller change); PR 2a makes `convertPlans` idempotent and runs it on every inserted plan, so the old wizard's plans have rules until PR 6; PR 2b deletes only `CarryForward` and `CardioProgression` and leaves the formula compiled but unreachable; PR 6 removes the old wizard with `ProgramGenerator`, `SplitDefaults`, `CardioFirstPlanner`, `CardioPlanner`, `WizardAnswers`, `GoalCalculator`, `StrengthStandards`, `LifterConfig`, `SlotSeeds` and their tests; PR 8 keeps `CardioIntervals` and the fixture (§3.1.5 two rows, §4.4, §8 rows 1, 2a, 2b, 6, 8 and the footnote).
- A3: PLAN.md A19, the spec §4, §6 and §11 rewrites and the CLAUDE.md Verification paragraph land in PR 6, the PR that deletes `PinnedVerificationTest` (§5, §8 row 6).
- A4: sprint progression counts finished sessions on any word, since the sprint band is Hard; sessions ended early for form or a Pain flag do not count (§3.1.3 Progression row).
- A5: Good repeats however many times; only Hard or a Pain flag drops a level; the cardio Finish row has no preselected word (§3.1.2, §3.2 Run/walk ladder row, §6 labels, case 34).
- A6: case 36 rebuilt on a reachable fixture, the 65+ path with five mains and its balance block at 1 day × 30 min: "Over by 7 min" (seconds rounded up to the minute); longer sessions fit at step 1 (2,580 s); one dropped lift still reads "Over by 2 min" and a second drop fits (§5).
- A7: §9 item 8 marked superseded by 18 (`PHONE`).
- A8: the cadence floor is the plan's base cadence: the experience answer's value on a generated plan, 1 on a user-built or converted plan (§3.2 Cadence row).
- A9: `SlotRule.defaults` KDoc matches its four-argument signature, and the two `defaults(role, goal)` mentions name all four (§2.2, §3.1.5, §3.2); §3.3 moved after §3.2's deletion-test paragraph.
- A10: `cardio_set_result` stores `timingMethod` and `startProtocol` with `centis`, written once and never rewritten; backup v27 carries them; the restore test keeps a PHONE three-point rep and a HAND two-point rep apart (§3.1.3, §4.1, case 37).
- A11: conversion gives MAIN to ramped slots and to straight slots on main-capable entries (today's `GoalSource.Std` lifts, which keeps N8's fix), so the PR 2b assertion now reads "a converted straight-set squat rests 180 s and a converted straight curl 90 s" (§2.2, §3.1.5, §4.4, §8 row 2b).
- A12: invariant 1 admits an `OVER_BUDGET` preview day awaiting the user's choice, and the property test asserts fits-or-OVER_BUDGET (§3.1).
- A13: the inventory's machine-local path became a portable citation of the playtest kit repository (`docs/briefs/wizard-inventory-2026-11-08.md` line 5; the only edit to that file).

## 16. Revision 7 (PR #541 council round 2, 2026-11-08)

- B1: the §3.1.5 rows for `ProgramGenerator` and `WizardAnswers` now say PR 6, matching §8; `CardioIntervals` has its own row.
- B2: rule assignment for every slot-creating path (`createAuthoredPlan`, `TrackerRepository.addExercise`, plan and today-only swaps, superset partner adds) moves into PR 2a with `SlotRule.defaults` and `SlotSeeding.rows` in one transaction; 2a's done criteria pin "create an authored plan, then add a lift, then swap it"; 2b's scope and the §8 footnote restated (§8).
- B3: the `level` key, `GlobalLifter.level`, the You screen's Level section and the `PlansViewModel`/`BackupMapping` readers go in PR 6 with `LifterConfig`, not in 2a (§4.2, §8 rows 2a and 6).
- B4: 2b's acceptance narrowed to "no seeding path references `GoalCalculator`"; `JournalBuilder:81` and `SetupStateBuilder.goalPreview` move to `settingsJson.goalsLb` in PR 6 (§8 rows 2b and 6).
- B5: §9 item 16 marked: the two-repeats drop was removed in revision 6.
- B6: the 65+ balance block's exemption from the dose ladder is stated in the ladder paragraph and the §3.1.1 Order row (§3.1, §3.1.1).
- B7: `CardioIntervals` stays until `CardioBlocks.fromLegacy` and the watch (`WearApp`, `DialState`) stop calling it, in a PR outside this list; PR 8 only moves it with `CardioBlocks` out of `generator/`; `CardioIntervalsTest` keeps pinning the vectors (§3.1.5, §8 row 8 and footnote).
- B8: PR 2a adds the `mainCapable` marker; the restore test's second rep is a standing start, which is in the protocol list; the sprint exclusion reads "an unfinished WORK set or a Pain flag" (§8 row 2a, §4.1, §3.1.3).

## 17. Revision 8 (round 3 merge fixes)

- Opus R3 S1: `resetDayToTemplate` joins PR 2a's slot-creating list and its done criteria (§8 row 2a); `replaceProgram` noted as test-only.
- Sol R3 C1: `CardioMode` is no longer deleted in PR 6; it stays with the retained cardio adapters until the PR that migrates its callers (§3.1.5).
- Round 3 nits left for the PR specs: `readGlobalLifter`'s return type changes in PR 6 (§4.2); PR 8 names the destination package for `CardioIntervals`/`CardioBlocks` and updates watch imports; the §4.6 table cross-references PR 2a for add, swap and partner seeding.

## 18. Revision 9 (owner decision, 2026-11-09)

- Seeding source order for every `SlotSeeding` row (add, plan and today-only swaps, partners, Reset day, conversion): the weight the plan already opens the lift at on another slot (its stored MAIN row's TOP, else first WORK or EXTRA row; slots whose rule is calibrating skipped; the latest written row wins; a load prescription counts when no slot has a row); else the last working set through Estimate; else the equipment floor, calibrating. A plan load is copied as is only at equal working reps. At different reps, Estimate uses the source working reps and source rule's RIR (2 without a rule), then the target reps and RIR, rounded down to the unit's equipment step grid; a converted or equal-reps plan source leaves calibrating off. A source that Estimate cannot convert anchors its load rounded down, with calibrating on. Prescription-only load sources supply their prescribed reps. A loaded hold takes the plan's or the last working load as its added load. PR 2a-ii part two.


## 19. Revision 10 (PR 2b split and part one, 2026-11-09)

- PR 2b ships as four parts in one release with 2a: (1) Finish runs the engine for lifts, rest by role, records on `FinishedRule`; (2) openings: `atStart` layoff and light cycles, and superset-partner progression; (3) the seeding sweep off the formula; (4) cardio integration and the watch wire.
- Converted and old-wizard plans remain Ask; Build my own plans are Automatic. The old wizard stays Ask until PR 6.
- The next-time chip and the receipt read the change Finish recorded on the archived track (`PlannedTrackDto.offer`): the newest session of the day. Cross-day evidence never supplies a chip. An Automatic change shows as a selected chip that restores the old rows and target.
- Undo restores each row and rule only where it still holds what that Finish wrote. Accepting a chip during a run also writes the stored rows and any proposed target, so Discard preserves acceptance.

### Caller override (owner and council, 2026-11-09)

These decisions supersede the earlier Ask, carried-load, and success rules.

| Mode | Rows at Finish | Rule at Finish | Offer |
|---|---|---|---|
| Plan (`keepToPlan`) | Delete the log | Unchanged | None |
| Ask | Opening stored rows, cleared of results | Updated counters and cadence; target and calibration status held until acceptance | Every load, rep, calibration and range reset; acceptance applies rows and target together |
| Automatic | `Outcome.rows` | `Outcome.rule` | Applied load or rep change; calibration has no receipt delta line |

- R1 (Finish runs the engine): a completed TOP determines the carried load and regenerates its ramps and back-offs. Without a completed TOP, keep the prior anchor. Straight sets adopt one load only with a strict majority of the planned WORK rows done there, matched by row identity. RAMP and unplanned EXTRA rows do not vote. Ties hold. Bodyweight and timed rows planned at zero never count as lowered.
- A below-plan working set outside the carried load blocks success and gives a rep miss; regenerated back-offs belong to their TOP anchor. A newly adopted lower anchor clears the streaks and earns no increase in that session. In Ask mode it remains an offer until accepted.
- Effort-only misses hold the load and slow cadence; Max blocks a load increase. Only two consecutive rep misses at the same load trigger the stall drop. Effort input ships in a separate PR in this release; until then `rir` is null and reads as on target.
- A successful Finish also advances the success streak of every other non-Plan slot in the same plan for the same exercise and working-rep target, in the same transaction and undo record. It never writes another slot's load. A different rep target, a blocked session, a miss, or a newly adopted lower anchor earns no shared credit.
- For part two's layoff check, converted and user-built plans use their finished sessions in the 28 days before the lift's last session, divided by 4, with a floor of 1 day per week. This part does not implement the layoff opening.
- Offers contain immutable before/after rows and rule snapshots so a rep change, calibration or range reset can be accepted together with its load. No older archive is rewritten. Rep receipt lines have their own SavedStateHandle key; existing Parcelable layouts stay fixed.

## 20. Revision 11 (PR 2b part two-a, openings and light cycles, 2026-11-09)

- Part two splits: (a) openings and light cycles for MAIN tracks; (b) superset-partner rules and partner openings.
- The opening lives on the run track (`run_track.openingJson`, Room 26) from Start to Finish and is copied to the archived track (`PlannedTrackDto.opening`, the `SessionPlanJson.layoff` of §4.1). Only an explicit Start opens through `atStart`; a run started by a tick, a skip or a drop opens as stored, and superset, Plan-mode and unruled slots open as stored.
- Days a week for the layoff check: the plan's sessions on the 28 days ending on the exercise's last session, ÷ 4 rounded, at least 1. The 65+ row reads the lifter's age until PR 6's setup answer; New waits for PR 6.
- A finished training day (strength or cardio) counts the light cycle down; a wrap is the finish of the rotation's last training day that advances it. The reactive stall trigger counts distinct exercises with a stall in this Finish's rotation window; the Rough day trigger lands with 2c's checkbox. Undo restores both light counters by compare-and-swap.
- A track that opened with a layoff or the light view offers from the stored working load, so Hold, a scaled success and a light day record no offer.

## 21. Revision 12 (PR 2b part two-b, partner rules, 2026-11-09)

- A superset partner has its own rule in `program_exercise.partnerRuleJson` (Room 27, additive in backup v27), read through `SupersetPartner.rule`. Adding or swapping a partner seeds its rows and rule through `SlotSeeding`; conversion, a generated plan and Reset day write the rule only, its reps from the stored, then parked, partner rows, then the catalog. Removing or swapping the partner clears the rule; a main swap, a structure or prescription edit and Plan mode keep it.
- Finish judges a partner track exactly as a main slot and records its offer on the archived partner track. Cross-day credit runs between any two tracks of the plan on different days with the same exercise and working-rep target, main or partner. A partner stall counts toward the reactive light cycle. Undo records which column a rule came from.
- Both halves of a superset open through `atStart`. When either half opened, the partner is fitted to the main's opened row count (trimmed from the end, or padded with its own last row), never reseeded. A partner whose count changed without an opening of its own opens as a Hold, so Finish leaves its rows and streaks as they were.

## 22. Revision 13 (PR 2b part three-a, lazy seeds, 2026-11-09)

- Part three splits: (a) the data layer's lazy seeds; (b) the display and editor targets (`PerformedExercise.goal`, watch blank rows and label, Plan screen, day editor, `PrescriptionDraft`, receipt targets) and `DaySeeds` deleted; (c) openings before Start and tick-started openings.
- A track with no stored row seeds through `SlotSeeding` under its own rule (else the conversion rule), from `PlanRepository.seeds`; `observeDay` shows the seed without writing it. `ensureSeeded` and a pre-start edit write a seed's rows and its rule's calibrating flag together; a run start still writes none.
- Partner tracks count as the plan's working weight for the same lift. A restored or reopened track re-plans from its stored rows.

## 23. Revision 14 (PR 2b part three-b-i, display surfaces, 2026-11-09)

- A lift's goal is the plan's own goal for it (`goalsLb`) and nothing else; catalog rep and hold targets are no longer goals. A timed hold's load is its working row's; its load stepper follows the catalog or prescription.
- The watch's last-resort rows are authored-count blanks; the Plan screen counts the rows a slot's rule opens with; a receipt's Planned line is the rows the session opened with.
- The day's seeds re-read on any history write. Part 3b-ii moves the day editor and the prescription draft to the stored or seeded rows.

## 24. Revision 15 (PR 2b part three-b-ii, the day editor, 2026-11-09)

- The day editor reads each slot's next-time rows, stored or else seeded, and never a run's. Its summary counts those rows, and the Prescription page starts from the first working row; only an empty stored track falls back to the catalog's rep or hold target.
- No app or data code seeds or targets a plan slot from the formula. The old wizard, the setup goal preview and the journal's goals keep it until PR 6.

## 25. Revision 16 (PR 2b part three-c-i, today's draft, 2026-11-10)

- Before Start, Today's edits (values, sets, swaps, skips, on the phone or the watch) open a draft: the day's run row, not started, built like Start builds it. The saved plan changes only from the plan editor, Reset, the next-time chip and Finish.
- Start, or a done tick, starts the workout from the draft. Starting clears the day's Finish Undo and writes the day's missing seeds. The draft's opening is the one it was created with.
- Before any draft, the Day screen, the preview and the watch show the rows Start would open with, written nowhere.
- One draft or workout at a time. Discard drops a draft. The last Finish's Undo is refused while a draft of the same day is open.
- A fitted partner only counts the exposure (owner, 2026-11-09).

## 26. Revision 17 (PR 2b part three-c-ii, the draft UI, 2026-11-10)

- Before Start, the Day screen's top bar offers Start instead of Finish, and a "Today only" line links to the day's plan editor.
- A phone tick before Start opens a sheet asking to start. Start begins the workout and logs the set; Not yet writes nothing. A watch tick still starts the workout.
- Starting or editing another day while a draft is open asks to drop the draft, in one step. A started workout keeps the two-step Discard and start, and an edit on another day shows "Another workout is open".
- Today offers "Start day A" for a draft instead of "Resume day A".
- A set timer started before Start opens the same ready-to-start sheet; Start begins the workout and then the timer.

## 27. Revision 18 (PR 2b part four-a, readings at the end, 2026-11-10)

- PR 2b part four ships as three parts in the 2a/2b release: (a) readings at the end; (b) Finish judges cardio and `CardioProgression` goes; (c) openings through `cardioAtStart`.
- No executor reads distance while a set runs, so a set's readings at the end are the ones its totals prove (`FinishedRule.atEnd`), written by `logCardioSession`, a distance amendment and a one-set History edit. The wire gains no field: the watch never runs past an end, so the phone derives a wrist set's readings, old watches included. The fields come back with the distance tracker.
- A phone steady run stopped past its planned time reads its time at the end and no distance, so it is not a record, meets no distance aim and proves no timed goal. The totals keep the overrun.

## 28. Revision 19 (PR 2b part four-b-i, Finish judges cardio, 2026-11-10)

- Part four-b ships as two parts: (i) the cardio commit judges and `CardioProgression` goes; (ii) the after-run entry takes the distance at the target, written to `metresAtEnd`.
- `logCardioSession` judges a session only when it is its day's newest and ran exactly the day's stored block, on a day that is not kept to plan or on the ladder. An Automatic plan writes the next block to `cardioWorkingJson` whatever "Offer cardio increases" says; an Ask plan writes nothing and offers it when that switch is on. A capped block that drops rounds repeats. A capped or tapered session never changes the stored block.
- A phone steady run with a time end and no distance aim, stopped past its planned end, proves the planned seconds through `secondsAtEnd` and earns the increase: Automatic writes it and Ask offers it. The overrun still earns no record and meets no distance aim. When `secondsAtEnd` has no paired `metresAtEnd`, history pace uses elapsed seconds and total metres.
- The run cap reads the family's sessions completed in the 30 × 24 h before the judged one, and the pace of the newest one with a distance.
- Caller answer (2026-11-10): "Offer cardio increases" off hides the chip in both Automatic and Ask modes, matching strength. Automatic still progresses; receipt Undo arrives in 2c. The Day screen still hides the chip after today's session.

- A distance typed after the commit judges the session again in the same transaction. `cardio_session.increaseApplied` limits each session to one Automatic write, so an amendment after the chip undo never writes the increase back.

## 29. Revision 20 (PR 2b part four-b-ii, the reading at the target, 2026-11-11)

- The after-run entry takes an optional reading at a planned end the set passed: "Distance at 25:00" for a steady run stopped past its time, "Time at 1.5 mi" for a stopped distance set whose typed total passed its distance. It fills `metresAtEnd` or `secondsAtEnd`, the slot the GPS tracker will fill. Left blank, the set keeps its totals and is not a record.
- A supplied reading is never overwritten. A time at a distance end makes the set FINISHED, since its end was reached. Either reading is judged again like a typed distance.
- A steady run stopped past its time opens the entry even when its distance was typed during the run.
- The cardio receipt reads the stored rows, so it shows the same records as History.

## 30. Revision 21 (PR 2b part four-c, cardio openings, 2026-11-11)

- A cardio day opens through `cardioAtStart`: the taper from the plan's goal date, then the run cap against the family's sessions in the 30 × 24 h before now. A day kept to plan opens as written; a ladder day opens as written.
- Before Start the Day screen, the preview and the watch show the opening and write nothing. The phone run freezes it when the run is created, so an edit before Start lands in that run; a plan edit still clears an unstarted run. The wire is unchanged.
- A capped or tapered session never changes the stored block, so a capped day climbs back to its stored block as the history grows.

## 31. Revision 22 (the per-set effort row, 2026-11-11)

- After a set is logged on the phone, the rest panel shows Easy, Good, Hard and Max beside Undo for as long as Undo shows. One tap stores the word on that set; tapping it again clears it. Nothing is preselected, and an unrated set counts as on target. The row has the label Effort to its left.
- The Lift sheet shows a rated set's word, and the set editor offers the same row for any logged set.
- Logging a set short does not store an effort. The watch does not offer effort.

## 32. Revision 23 (2c-1)

Rough day is a reversible toggle over Finish's undo record. It restores the prior rows and rules by compare-and-swap, skips SCALED and RECALIBRATE break openings, and leaves Finish's own light-counter snapshot alone. Unticking restores Finish's result. Two consecutive rough strength sessions on an Automatic plan start a light week when none is running; the toggle records and reverses that counter change. This retires design case 9 ("hold, missStreak unchanged") and `Exposure.roughDay`.

The cardio commit keeps a persisted undo journal in Room 32, including the exact block writes and light-counter snapshots. Distance amendments preserve the original block snapshot. One Undo covers strength Finish and cardio logged during its run. Cardio logged with no strength run open has its own receipt and Undo; the day's next strength Start or cardio run creation clears earlier undo records. Undo shows for counter-only changes, too. Journals are omitted from v27 backups.

A death after the Finish commit recovers through the normal Finish path, using archived tracks (including dropped lifts and partners), archived live set counts, and the run's time window for cardio Undo identity. Receipt cardio rows include sessions completed on the session's date or during its window. The recovery path skips the live no-done-sets check and computes record baselines from captured tracks.

Applied changes show whatever the offer switches say. Each lift's own rule determines its mode. The receipt gives absolute next-time values, one Undo, Rough day, and one Why disclosure; cardio receipts and past-cardio entry include How was it?. Existing SavedStateHandle parcels retain their layouts; receipt identity and past-cardio effort have separate keys.

Section 8's first 2c criterion (a short set stores rir 0) is superseded by revision 22.


## 33. Revision 24 (2c-2)

Plan settings expose Automatic or Ask, Defaults for, Light week (Off or every 4 to 8 rotations), and Run cap on cardio plans. The owner's Q2 keeps the Offer switches on Ask plans only. Automatic plans show every next-time chip; an Automatic lift override also shows its strength chip. Switching modes retains the stored Offer values for Ask.

The owner's Q3 keeps Lift options to Next time, Rest, Reps left, and Increase. Back-offs, Misses lower by, and Strength days remain cut. Plan mode and Reset to plan require a prescription. A legacy unprescribed lift kept to plan offers Automatic and Ask with neither selected; either choice restores its parked rows. Rest reads the timer's resolver, including the user's bucket overrides. Settings exposes Words or Reps left, and numeric effort stores the selected rir.

Prescription and structure edits preserve Next time, Rest, Reps left, and Increase; a swap preserves Next time only. Prescription and structure edits carry `rir` and `incrementPct` even when they still hold the old default, so a General accessory that turns ramped keeps rir 2 rather than MAIN's 3; this is deliberate, since the rule records no edit flag.

Plan settings stay in optional JSON fields, with absent runCap meaning on. Room and backup versions stay unchanged. Existing SavedStateHandle parcels keep their layouts; the Lift options entry uses a separate key.


## 34. Revision 25 (2c-3)

Lift markers describe the captured opening, including its read-only preview before Start. Estimate takes precedence over Light week, Lighter today, and After a break. The set page and Lift sheet use the same marker for each half of a superset.

Today offers Lighter today above Start only with an eligible ruled lift and no draft, run, or light week. The choice opens through the existing light-week calculation. For non-recalibration light openings, Finish records LIGHT_CYCLE for completed work and leaves stored working rows and rules unchanged, including exposure counts. Counter-driven light weeks also leave exposure counts unchanged for these openings. A light RECALIBRATE opening retains the existing progression path: Automatic writes the 0.75× recalibration rows, sets calibrating, and counts the exposure; Ask keeps its stored rows and calibration state and counts the exposure.

Cardio overruns show two legs on the receipt and in History only when Room holds a measured or typed reading at the planned end: metresAtEnd for a time end, secondsAtEnd for a distance end. Derived readings never qualify. Receipt results reload through the receipt's cardio session identity; existing SavedStateHandle parcels keep their layouts.

The watch's prescribed LoadReps, Reps, and Hold labels follow working rows. RepsInTime retains the prescribed window. Room and backup versions stay unchanged.

## 35. Revision 26 (PR 3a, planner lift branches)

PR 3a builds Strength, Muscle and General fitness with the 65+ modifier as a pure domain planner. Nothing calls it yet; PR 6 wires it into setup and Reset day. Every chosen main appears once as MAIN, even when its day cannot fit. Defaults follow the five setup rows in order: squat, hinge, horizontal press, overhead press, pull. The pull row tries horizontal before vertical; defaults prefer the named design lift, then available weighted entries, then reps entries. Timed entries are skipped by default selection. A loaded pair target is skipped only when its equipment list equals its bodyweight pair's equipment list (dips, weighted push-up and weighted pull-ups). Goblet squat remains the pinned Dumbbells default because its equipment differs from sit-to-stand's. A timed chosen main falls back to the default main for that row. A caller drops a lift by omitting it from the chosen list.

Strength and Muscle use the same lift for its second exposure, on the next day that hosts its family. General fitness and the 65+ path have no second exposures. Full-body days fill knee, hip, horizontal press and pull groups, then isolation and core; a main or second exposure fills its group. Every day has one core slot of two sets before the dose ladder. Accessories follow catalog rank, excluding chosen mains, unavailable or excluded entries, main-capable entries, loaded pair targets and entries already on the day. When no other eligible pull fills a slot, it may reuse the chosen pull as ACCESSORY only when invariant 4 needs it: without the reuse, UPPER_BACK or LATS would have fewer than two exposures in the rotation. The fallback counts MAIN, SECONDARY and accessory exposures across the rotation and adds reuse only on a day that increases a deficient muscle's exposure count. Kettlebells has only `kb_row`: prohibiting this reuse while also forbidding second exposures for General and 65+ leaves back and biceps at one exposure, even at 90 minutes. Two existing exposures prevent the fallback.

Rules start with `SlotRule.defaults` and only raise RIR: New keeps at least 2, 65+ at least 3, and New or Under a year Strength and Muscle mains start at 3. The first-weeks RIR is all PR 3a writes; the change after six exposures needs a rule field and codec work in a follow-up. Increments use the default body-half values, doubled for New and Under a year. Cadence is two exposures for A year or more and one otherwise; the planned light cycle is every six rotations for A year or more and absent otherwise. Generated rules use `Scheme.STRENGTH` until ladder step 4 replaces the main's scheme.

Superset partners get their own isolation rule and no opening rows. `working` contains only each slot's main track. Recent numbers take precedence over known working loads; the existing `SlotSeeding.rows` writes rows and the rule's calibrating state after the ladder settles the shape. Plan reasons use the separate, re-derived `PlanReason` type; the persisted receipt `Reason` and its codec stay unchanged.

Rotations start Lower on U/L splits. With four or more training days, one Rest follows the third training entry, and every entry receives the next letter. The four-day reference rotation is A Lower, B Upper, C Lower, D Rest, E Upper. Muscle at six days is Push, Pull, Legs, Rest, Push, Pull, Legs. General fitness stays full-body at every count.

The session estimate uses five minutes of warm-up and 45 seconds plus the timer resolver's rest per set; a superset counts once. The ladder removes accessories, then second exposures, reduces mains to two straight sets, then one ramp and one working set, caps main rest at 120 seconds when higher, and finally removes core. It never removes a main or balance slot and never empties a day. It reports the last step needed as `ShortSession`; a day still over after step 6 also carries the exact excess seconds as `OverBudget`. Exposure and coverage floors give way on rotations with any `ShortSession` day.

The planner writes `restSeconds` for the 65+ main's 120 seconds, ladder step 5 and catalog doses. The stance rests 30 seconds, making the two-set stance plus two-set carry block 360 seconds. The older-adult five-main day at 30 minutes is 2,160 seconds and carries `OverBudget(360)`; 45 minutes fits at step 1 at 2,520 seconds. Dropping the row leaves 1,860 seconds and `OverBudget(60)`; dropping the press too fits at step 4 at 1,770 seconds. Ramp and back-off buckets keep their own rest.

Every equipment preset includes bodyweight. Full gym has all eleven equipment types; Home barbell adds barbell, rack, bench and pull-up bar; Dumbbells adds dumbbells and bench; Kettlebells adds kettlebells; Machines only adds machine, cable and bench; Bodyweight adds the pull-up bar. A muscle group no available entry credits is exempt from exposure floors. The owner muscle table supplies primary and secondary set credit, exercise doses and side flags. Plyometric and Balance are appended movement patterns; the app change is limited to their two labels and mapping checks.

Volume balancing is PR 3b: enforce the owner's floors and a cap of 20 sets on rotation totals, with no enforcement of band tops. PR 3a includes set counting but makes no volume-band assertion. Recipe dates, plan goal, name, auto-apply, split choice, the General fitness finisher, other Why reasons and exclusion storage stay with later PRs.

## 36. Revision 27 (PR 3b, weekly set floors)

The planner enforces the owner's weekly set floors and a cap of 20 for every muscle on rotation totals. Section 3.1.1's band tops are not enforced. Strength and General fitness floor QUADS, HAMSTRINGS, GLUTES, CHEST, LATS, UPPER_BACK and FRONT_DELT; Muscle adds BICEPS and TRICEPS. SIDE_DELT, REAR_DELT, CALVES and CORE have no floor. Strength's floor is 6, General fitness's 4, and Muscle's 10 for New and Under a year or 12 for A year or more. The 65+ modifier sets the goal's floors to 6. A muscle no available entry credits as primary has no floor.

Balancing runs after the ladder and before opening rows. The trim takes one set at a time from the ACCESSORY, ISOLATION or CORE slot with the most sets that credits an over-cap muscle as primary, latest day and last slot first, removing a slot at zero. It runs on every strength day because the cap never relaxes. CORE is in the trim because the 65+ carry and the core slot reach 24 sets over six days.

The fill runs on days the ladder left alone, muscle by muscle, one set at a time: first the eligible slot with the fewest sets, earliest day and earliest slot first, up to five sets a slot; then a one-set accessory slot of the muscle's pattern on the day with the most room, before its core slot. When nothing else fits, it may reuse a chosen main that is not main-capable on a day without it. No set may push a day past its minutes or a muscle past 20. A floor the minutes stop marks those days `ShortSession` at step 0.

Kettlebells for Muscle, A year or more, at two days has one row, already at five sets on both days. Its back stays at 10 sets with no reason, since longer sessions would not help.

The reference rotation's second Upper day now has three bench sets as its second exposure, so day E estimates 3,195 s.

## 37. Revision 28 (PR 4a, run, test and both at creation)

PR 4a adds the Run branch's eight sub-goals, field tests and Both layouts to the pure domain planner at creation. Lift remains the default, with its fitting, volume balance and seeding pipeline preserved. Setup carries the run answers and Both's primary and together choices. Just cardio fitness is the run default; middle distance starts at 1500 m, ultra at 50K, the field test at 2-mile, and an event with no time means Just finish. The 5K-to-10K choice starts at 5K; the half is 21,097 m. Both defaults to lifting first, separate days from four days and same visit below that. Runs a week now is deferred until a rule reads it.

The entries table governs every day count. Run days use rotation letters and CARDIO, with the strength block in exercises and the run in cardioBlock. The ladder takes the row for at most three training days. A second quality session appears only at five or six days for Some or Racing, when the structure supplies one. At five days with two quality sessions the row is E+S, Q, E+S, Q2, L, R; without Q2 it is E+S, Q, E, E+S, L, R. Six days uses E+S, Q, E, Q2 or E, E+S, L, R. The two-day row keeps L+S so both strength blocks can be placed. There is one Rest after the long entry, with an easy entry between quality sessions rather than a second Rest. These decisions supersede the conflicting quality-frequency and Rest prose above.

Ladder days all carry the existing run/walk block. Base uses threshold on Q and VO2max on Q2; half, marathon and ultra builds use threshold alone. Middle distance uses race pace then threshold. Tests use test intervals then tempo; their long entry is easy. Just cardio starts with easy sessions throughout. Range-based sessions start at the low end: threshold 20 minutes, VO2max five 3-minute reps with equal recovery, race pace six 400 m reps with 2-minute recovery, test intervals four 400 m reps with 2-minute recovery, and tempo 15 minutes. The 800 m within its last six weeks starts with three 300 m reps and 4-minute full recovery. Recovery sits between reps; the estimate's five-minute warm-up supplies the warm-up. Quality WORK sets carry hard; easy, long and ladder sets do not.

Long runs follow the anchor's distance form when it has metres or no anchor exists, otherwise its time form: base 10,000 m or 60 minutes, half build 18,000 m or 2 hours, marathon build 28,000 m or 3 hours, 50K build 30,000 m or 3.5 hours, and 50-mile build 48,280 m or 4 hours. The 100K and 100-mile builds use 4 hours, and middle distance uses 60 minutes, in time form only. The first cap reads known history for the activity family, otherwise the setup longest. Every non-ladder block passes through RunCap with the recent result's pace when present. A changed stored block reports RunCapped; ladder sessions remain exempt. The existing opening cap and taper still apply as views.

Easy runs fill the minutes after the five-minute warm-up and a reserved 25 minutes on a strength-block day, with a 20-minute floor. Their limit is 75% of the plan's stored long run by time, rounded down to a whole minute with a one-minute minimum; that limit wins over the 20-minute floor. A distance-form long run converts at the estimate's 0.36 seconds per metre. Thus a stored 13,200 m long run is 4,752 seconds and limits easy runs to 3,540 seconds, including at 90-minute sessions. Plans carrying no long run have no such limit. The run cap applies afterward. Long days are exempt from the minutes and carry no strength block except the table's two-day L+S exception. Other interval sessions drop rounds down to one when the minutes require it; reps and ladder blocks are never shortened for the minutes. An excess that remains reports OverBudget. The estimate adds every cardio set's planned seconds, using its time aim or 0.36 seconds per metre for a distance end without one; estimates without cardio stay unchanged.

The first requested zero, one or two strength flags receive the blocks. Each starts with the existing 65+ balance block when applicable, then a SECONDARY squat or hinge, an available single-leg accessory outside the chosen mains, alternating gastrocnemius and soleus calf work, Nordic or another knee-flexion accessory, and alternating core patterns. Every non-balance slot has two sets. Run plans use Strength rules; Both uses the lift sub-goal. Blocks use the existing fitting ladder and slot seeding. Coverage, exposure and weekly volume invariants apply to lift days; the run strength block is checked against its own contents.

Distance events start at the first unmet gate, whether the runner says New, Some or Racing. Without an anchor, New starts on the ladder and Some or Racing at base. Ladder's gate is 30 minutes or 5,000 m; base is 10 km or 60 minutes; half build is 21 km or 2 hours; marathon build is 30 km or 3 hours, or a logged marathon best for ultra. When all gates are met the final phase remains. Middle distance, tests and Just cardio have no readiness phase. Ladder starts at the highest run interval the anchor's seconds support, level one without time, and one lower for a past running injury with a floor of one. A 20-minute anchor starts at level ten. Minimum phase lengths are eight weeks for ladder, six for base and eight for each build, through the event's readiness phase. A marathon from ladder needs 30 weeks, including taper within the last build. A closer date reports DateTooClose and still generates. The caller supplies today when a date is present; the planner reads no clock.

Pace aims appear on race-pace and test reps alone. Race pace uses the goal time, otherwise an unrounded Riegel prediction, and rounds once for the rep; all rep aims have a one-second minimum. The timed field tests use 95% of goal pace; Cooper uses the score as metres in 720 seconds. PACER and HAMR have no rep aim yet. Timed events and the two timed tests produce Timed goals, and scored PACER or HAMR produces a Shuttle goal. Just finish, Cooper and Just cardio return no goal in 4a. The 86,400-second bound remains; PR 7 owns nullable timed goals and a Cooper form. An event goal faster than the recent result predicts reports GoalCheck through the existing Riegel goal check, including the marathon's 1.05 margin; ultras get no check.

Both separate days splits primary and secondary counts as 1+0, 1+1, 2+1, 2+2, 3+2 and 3+3, alternates primary first, keeps a Rest beside its preceding training entry, and re-letters days, working keys, reasons and session keys together. Run days have no strength blocks when separate lift days supply the strength. One day on separate days is the primary's own draft. Same visit, cardio first follows the run layout with strength blocks under the lift sub-goal. Same visit, lift first at 45 minutes or more reserves 20 minutes from the lift fitting and volume-balance budget, or the ladder session's own duration during ladder. Every lift training day receives its mapped run; ladder sessions repeat across the lift rotation when it has more than three days. A capped quality or long session is attached when it fits the full minutes; otherwise it becomes a 20-minute easy run. Long placement settles before the easy limit is calculated. At 30 minutes lifting keeps its full budget and runs attach only where they fit; easy time is the remaining whole minutes when at least 20 are available, then limited and capped. The final OverBudget uses the full minutes after the run is added. D15 also applies to a one-day, lift-first same-visit plan: it reserves run time at 45 minutes or more and uses the literal fit rule at 30 minutes. Lift days keep their kind and title. Every Both layout keeps the run goal and plan-level reasons, with a readiness phase only when it carries a run.

PR 4b owns progression after creation: additive CardioRule session kind, range position and two-session counter, long-run target and quality alternation, with the data codec; increases after every two finished quality sessions within their band; the 50K target rising from 30,000 to 40,000 m under 3.5 hours; log-driven single-run and weekly phase gates and replanning; date-driven 800 m changes and marathon-pace segments; practice tests, retests, strides, PACER shuttle repeats, Just cardio's later intervals and the novice 30% two-week growth rule. It also owns case 29's 3 × 300 m to 4 × 300 m to 4 × 400 m progression and case 30's ladder to base to half to marathon transition. A null CardioRule keeps today's progression behavior. The replan trigger still needs its own design and 4b may split again.

## 38. Revision 29 (PR 4b-i, run progression rules)

PR 4b-i adds run progression to the pure domain planner and the existing CardioRule JSON codec. The rule stores the session kind, range steps, a zero-or-one session credit, the alternate kind and the long-run target. RunSession moves to progression, stored by name, with SHARPENING appended for the 800 m's last six weeks. Ladder rules stay CardioRule(level). Existing ladder and empty rules keep their exact JSON, including {"ladderLevel":3} and {}; defaults are omitted. Invalid names and inconsistent state reject on restore. Room and backup versions stay unchanged. No app caller reads the new fields yet; the planner is wired in PR 6 and PR 7.

Creation and progression share one quality range table. Threshold grows from 20 to 30 minutes, tempo from 15 to 25 minutes, VO2max from five 3-minute reps to six 4-minute reps, race pace from six to eight 400 m reps, sharpening from three 300 m reps to four 500 m reps, and test intervals from four 400 m reps to six 800 m reps. Rounds fill first, then every rep grows by 100 m or 60 seconds. Recovery stays between reps: equal time for VO2max, two minutes for race pace and test intervals, four minutes for sharpening. Quality WORK sets stay hard. Creation starts at the table's low end and keeps PR 4a's blocks and fitting.

Every two counted quality sessions add one unit. A session counts only on the existing full-success rule: the exact block, every WORK set finished and every aim met at its reading. Intervals count on every effort word; threshold and tempo count on Easy, Good or no reported word. A session that does not count keeps its existing credit. At the top no credit accumulates. If the run cap would cut a step against the history and this session's maxima, the step waits with one credit and its stored block repeats. Step marks a range increase; Alternate marks a switch of kind.

A step grows from the stored block; checking it against the session minutes so a step past the minutes waits like a capped step is required in PR 4b-ii, which passes the minutes into CardioContext.

A lone base-phase threshold day alternates with VO2max after each counted session. A plan with its own VO2max day does not alternate. The two kinds share steps through the larger range top, each building at its own top when the shared position passes it. Aims scale from the stored first WORK set with withEndValue on each step; no race pace is stored. Thus case 29's 800 m at 2:00 moves from 3 × 300 m at 45 seconds to 4 × 300 m at 45, then 4 × 400 m at 60 and 4 × 500 m at 75. The 2-mile test's later rep aims follow per-step rounding rather than rounding once from the race pace.

Ruled easy runs repeat. Their length is set at creation by the minutes and the limit of 75% of the stored long run, then re-derived when a gate causes replanning. Just cardio fitness repeats its easy sessions until the later interval work lands. Every run day, including Both's final replacement sessions on lift-first days, receives its session's rule after the creation cap. Ladder days keep only their ladder level.

Long runs take the existing five-percent increase in their progress measure, stop at the event target without shrinking a stored end, then pass through the run cap. Base stops at 90 minutes, including a distance end converted at the recent pace. Half build stops at 21,000 m or, in time form, two hours; marathon build at 32,000 m or three hours. The 50K stops at 40,000 m and 3.5 hours, whichever comes first when pace is known. At 5:00/km, 30,000 m reaches 40,000 m after six increases; at 6:00/km it reaches its 35,000 m stop after four. Without a pace only the distance stop applies. The 50-mile target is 64,374 m in distance form or six hours in time form. The 100K and 100-mile targets are six hours in time form; middle distance stops at 75 minutes. Increase retains A16's capped flag, compared to the stopped block.

KnownLoads gains per-family longestWeek: the largest total in a seven-by-24-hour window among finished sessions in the last 30 days, metres and seconds each their own maximum. Each window ends at a finished session, includes that session and excludes a session exactly seven days back. Session totals reuse RunCap.maxima, including WORK and RECOVERY. A time-only session adds seconds without removing the other sessions' metres. The half-build gate also accepts a 30 km or three-hour week, including at creation. Other gates retain their single-run forms.

A plan built from the log starts at the first unmet gate. A later phase than the stored plan's is the passed gate and supplies the replacement draft; phases move forward only. Leaving the ladder is gated by LadderDone. Case 30 keeps the single creation-cap rule: the level-12 5,000 m run anchors the cap at 5,500 m, which opens the base long run. Thirteen rounded five-percent increases reach 10,371 m; twelve reach 9,877 m. Half build opens at 11,400 m and reaches 20,474 m after twelve increases, passing the two-hour gate at 7,370 seconds at 0.36 seconds per metre. Marathon build opens at 22,500 m and reaches its 32,000 m target after eight increases. The phase minimums still total 30 weeks from ladder to marathon readiness.

PR 4b-ii owns Finish and gate wiring: the log-driven replan trigger with a stored recipe and phase, writing block and rule together with undo, progressing every ladder day as one runner's level, clearing a rule on a day-editor block edit, and Ask-mode offers for Step, Alternate and LevelTo while credits count silently. PR 4b-iii owns date-driven transitions at 42 days for the 800 m, marathon-pace segments in the last six to eight weeks, practice tests two to three weeks out, retests every four to six weeks, Just cardio's interval after four to eight consistent weeks, strides, PACER shuttle repeats and the novice 30% two-week rule.
