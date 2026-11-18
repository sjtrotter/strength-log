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
| Foundation 1 (ladder) | The run/walk ladder, at most 3 spread sessions per rotation, two counted Easy finishes per level; at most 3 counted finishes in 7 local days and a busy-fortnight level-up guard; blocks exempt from the run cap | The second counted Easy at level 12 (30 min continuous), passing cap and guard, seeds the cap |
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
| Progression | Add a fixed unit to the day's WORK total per counted pair of sessions, at most once per day per rotation (10 m acceleration or max velocity, 20 m sprint endurance, 100 m tempo). An unfinished WORK set or Pain does not count; Hard tempo does not count. Planned maximal meters rise by at most max(15%, 20 m) per rotation. More than 14 days without a maximal sprint triggers an intro restart. State lives in the day's `CardioRule`; revision 32 defines trials and the plan-wide layoff scan | Practice |
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
| Run/walk ladder | Session finished and rated Easy: a credit, then the next level. Good, no word or unfinished: repeat and keep the credit. Hard or Pain: one level down, floor 1, credit cleared. Dated progress counts at most 3 Easy finishes in 7 local days, refuses backdated Easy finishes before the newest stored ladder date, and holds a level-up or completion for 7 days after the last level-up while any 7 days in the last 14 hold more than 3 runs. The Finish effort row has no preselected word | Practice; no shown injury or drop-out benefit |
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
| 30 | Readiness | New marathoner, 30 weeks out, no longest: LADDER level 1, 60 s run/90 s walk ×10, no cap. Twenty-four Easy finishes, two per level, reach level 12's run and then BASE with anchor 5,000 m, limit 5,500. At +5% each cycle the base gate follows increase 15: increase 14 is 9,900 m and 15 is 10,395; timed equivalents 3,566/3,744 s. HALF_BUILD then opens 11,400 m (target 21,000); MARATHON_BUILD opens 22,500 (target 32,000). A date only eight weeks out still generates and reports DateTooClose. |
| 31 | Undo | Finish writes every slot's prior rows and rule, the day's prior `CardioRule`, and the plan's prior `lightCycleRemaining` and `cyclesCompleted` to `progressionUndoJson`; Undo after a process restart restores all of it, so a reactive light cycle fired by that Finish is undone too, and clears the column; the day's next Start clears it unread |
| 32 | Effort misses | The New-path bench at rule.rir 3: 5/5/5 with one set Hard (rir 1) in two consecutive sessions → load holds 185, `everyExposures` 1 → 2, streak cleared. R's bench at rule.rir 2: two consecutive sessions each with a Max set and every rep made → holds 185, `everyExposures` 2 → 3. Two consecutive rep misses (5/5/3 then 5/4/4) → 175 |
| 33 | Direct calibration | A Muscle DB row, target 10 at 2 RIR, calibrating, 40 lb typed: 40 × 10 Good → n = 12 → calibrated at 40. 40 × 9 Good (n = 11, short of 10) → 40 × 0.9 = 36 → 35 |
| 34 | Ladder and cap | Twenty-four Easy finishes, two per level, advance ladder 1→12, then finish the ladder, with no cap applied to any ladder block: the first Easy finish at a level earns a credit and repeats, the second moves up. Good repeats (including three consecutive Good finishes); a finish with no word repeats, at level 12 too (audit 2026-11-13 row 3); Good, no word and unfinished keep a credit; Hard or Pain drops one level and clears the credit, and at level 1 repeats with it cleared. With dates, an Easy finish counts only while fewer than three counted Easy finishes fall in the seven local days ending today and it is not dated before the newest counted one; a level-up waits a week after the last one while any seven consecutive days of the last fourteen hold more than three runs; a waiting finish repeats and keeps its credit. A finished 5,000 m run becomes the first anchor, limit 5,500. A repeated 5,000 m run always fits; the limit never falls below the largest total. |
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
- About your training (Run and Both): "Experience sets where lifting and running start. A runner whose longest recent run is under 30 minutes and under 5 km starts on a run/walk ladder, as does a New runner who leaves it blank; Racing runners skip it. A past running injury starts it one level lower. Nothing here is stored as a profile."
- Your lifts: "Choosing your own lifts makes training feel better (Oliveira 2015), and in a trial of older adults self-chosen loads built as much strength as assigned ones. Each one you pick is in the plan."
- Recent numbers: "A set of ten or fewer with how many reps you had left gives a working estimate (Epley). Leave a lift blank and its first session finds the weight."
- Your event: "The date sets the build and a taper of one to three weeks that cuts volume and keeps intensity (Bosquet 2007). A recent result predicts pace up to the half marathon (Riegel)."
- Your event (Fitness test with an interval aim): "Interval reps aim slightly faster than the target pace. A date tapers the last ten days."
- Your event (Fitness test without an interval aim, including PACER and HAMR): "A date tapers the last ten days."
- Where you are: "Runs past the run/walk ladder stay within 10% of your longest session in the last 30 days, a limit from runners (Frandsen 2025). This answer stands in for 30 days, or until there's a log."
- Your event (Sprint): "Hand times run about a quarter second faster than electronic ones, so results compare only with the same method."
- Which first: "Doing the main goal first in a session protects it (Eddens 2018). Lifting and cardio in one session cost some explosive strength; sessions three hours apart showed no such cost (Schumann 2022)."
- Your plan ("Why this plan"): the plan's `Reason` list, each one line, for example "Squat and deadlift twice a week: strength gains favor two or more exposures (ACSM 2026)." · "Sets stop 2 reps short: training to failure isn't necessary (ACSM 2026)." · "Rest after C: the two heaviest days are kept apart." · "Long run 8.8 km: 10% past your longest recent run." · "One hard set per lift: the session is 30 minutes. One set beats none (ACSM 2026)."

**Receipt** ("Why" disclosure, one per receipt): "Bench 190 lb next time (+5): all sets made with reps left." · "Squat 250 lb next time: 16 days since the last squat." · "Chest press 20 lb next time: you lowered it today." · "Press 175 lb next time (−10): two misses in a row." · "Light cycle, 3 days left."

Together choice bodies: Same visit, "Lifting and running in the same visits"; Separate days, "Lifting days and running days".

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

Distance events start at the first unmet gate, whether the runner says New, Some or Racing. Without an anchor, New starts on the ladder and Some or Racing at base. Ladder's gate is 30 minutes or 5,000 m; base is 10 km or 60 minutes; half build is 21 km or 2 hours; marathon build is 30 km or 3 hours, or a logged marathon best for ultra. When all gates are met the final phase remains. Middle distance, tests and Just cardio have no readiness phase. Ladder starts at the highest level whose total WORK seconds the longest finished running time covers, level one without running time, and one lower for a past running injury with a floor of one. A 20-minute runner starts at level eight. Ladder weeks derive from two Easy finishes per remaining level divided by the draft's ladder sessions per rotation, rounded up; zero sessions makes readiness unreachable and reports DateTooClose for any date. Minimum phase lengths are six weeks for base and eight for each build, through the event's readiness phase. A marathon from ladder level 1 at three sessions a rotation needs 8 + 6 + 8 + 8 = 30 weeks, including taper within the last build. A closer date reports DateTooClose and still generates. The caller supplies today when a date is present; the planner reads no clock.

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

## 39. Revision 30 (PR 5a, sprint times and the sprint goal)

PR 5 splits into three parts: 5a owns the timing model, sprint goal and case 37's comparisons; 5b owns sprint plans at creation; 5c owns sprint progression. PR 5a adds an optional SprintTime to CardioSetResult, holding hundredths, timing method and start protocol together. It maps to the existing centis, timingMethod and startProtocol columns. Room and backup v27 stay unchanged, with no new stored JSON fields. Untimed rows keep their existing behavior. The phone timer and typed sprint entry remain PR 7.

Stored timing names now use TimingMethod (PHONE, HAND, VIDEO, GATES) and StartProtocol (THREE_POINT, TWO_POINT, BLOCKS, STANDING, FLYING). TWO_POINT joins §4.1's examples to cover §3.1.3's two-point acceleration starts. New constants go at the end. These enums replace the any-upper-case-name check everywhere: partial timing triples, unknown names and times outside 1 through 360,000 hundredths reject on restore; an invalid Room row is omitted at the read boundary.

A timed FINISHED WORK rep with a distance end belongs to the series of that distance, timing method and start protocol. There is no conversion between methods. Surface waits for its own field and design line. Equal hundredths read SAME for every method; only HAND also reads differences under 0.1 s as SAME. A standing best changes only when FASTER: HAND 4.52 followed by 4.45 leaves 4.52, then 4.41 replaces it. PHONE 4.45 replaces PHONE 4.52. Attempts keep their raw hundredths.

A time-trial session yields one attempt per series at the sprint goal's metres, taking the lowest hundredths and the first rep on a tie. Attempts follow the order their series first appear. The goal's best uses the newest attempt's series, with the last listed attempt winning a timestamp tie, and folds that series oldest first using the same standing-best rule. The sprint goal's optional target has no setup question or met rule yet. Timed sprint reps leave the general cardio records; untimed sets still count as before. Sprint goals have no taper. The Plan screen keeps hiding a sprint goal until PR 7 supplies its row and copy.

PR 5b follows §2.1's Sprint entries table for every day count, including plyometric placement. Every contact counts toward the 40-to-80 cap: the opening is pogo 3 × 15 plus A-skip 1 × 20, totaling 65; progression grows toward 80 and introduces bounds, then box jumps. Intro starts at every range's low end. Without intro, acceleration starts at the first rounds-first, 10 m-unit step reaching 100 m of WORK: 8 × 20 m, totaling 160 m. Five and six days append Tempo and Upper lifts to Sprint A + plyos, Lower lifts, Rest, Sprint B, Lower lifts + Nordic.

PR 5b also defines max-velocity WORK as the flying 10 to 20 m, excluding the 20 to 30 m build-up from sets. Recovery starts at the low end: acceleration 60 s per 10 m, max velocity 240 s, sprint endurance 480 s. Tempo opens at 6 × 100 m with 60 s walks, not hard, in the Good band, growing toward 300 m reps. The 40 yd event stores 37 m; PR 7 displays 40 yd. Both with a sprint sub-goal uses the sprint layout and the lift sub-goal's rules and mains on lift days; it asks neither Which first nor Together.

PR 5c owns two-session steps, the 14-day gap, the weekly 15% cap, plyometric contacts, intro countdown and time trials. The cap compares the sum of the plan's weekly sprint WORK metres before and after a step; a step past 15% waits with its credit. Intro lasts three finished sessions per sprint day, one per rotation, counted in CardioRule. A gap of 14 days is measured per sprint day from its last finished session: Start shows the intro block as a view, and the next Finish writes steps = 0 with intro restarted. Every fifth rotation's Sprint A is the time trial, whether or not the plan has a light week.

## 40. Revision 31 (PR 5b, sprint plans at creation)

PR 5b adds the Sprint branch and Both with a sprint sub-goal to the pure domain planner. Both follows the sprint layout with the lift sub-goal's rules and chosen mains, and asks neither Which first nor Together. Every day count follows §2.1's Sprint column, including its rest entries and plyometric placement. Five and six days append Tempo and Upper lifts. The draft carries the sprint event goal, no run phase, and a session entry for every day with a cardio block. The 40 yd event stores 37 m.

Sprint sessions are RunSession kinds stored by name: acceleration, max velocity, sprint endurance and sprint tempo. Run tempo stays separate. Rounds fill first, then acceleration grows from 4 to 8 × 10 to 30 m, max velocity from 3 to 5 × 10 to 20 m, and sprint endurance from 3 to 4 × 100 to 150 m, in 10 m units. Acceleration rests 60 seconds per 10 m; max velocity starts at 240 seconds and sprint endurance at 480 seconds. Max-velocity WORK measures the flying distance alone. Tempo starts at 6 × 100 m with 60-second walks, grows in 100 m units toward 300 m reps, and is not hard, in the Good band.

Every maximal sprint block opens with WARMUP Time(600), followed by hard distance WORK and timed RECOVERY between reps, without aims, on Track run. The estimate counts this ten-minute build-up as the day's warm-up and omits its separate five minutes when a block opens with WARMUP. Tempo has no build-up block and keeps the estimate's five minutes. Planner-made lift and run days have no opening WARMUP set, so their estimates stay unchanged. The lead accepted this estimate rule on November 13, 2026.

For 200 and 400 m, Sprint A is max velocity and Sprint B is sprint endurance opening at 3 × 100 m with 480-second rests. The 60-to-80 m series is not built. Tempo remains at five and six days for every event; the table adds no tempo day at one through four. The lead accepted this event mapping on November 13, 2026.

No all-out sprint in the past month, or a hamstring strain in twelve months, starts every range at its low end. Without intro, acceleration starts at the first rounds-first step reaching 100 m of WORK: 8 × 20 m, totaling 160 m. Every other session starts at step zero. Sprint A alone opens with pogo 3 × 15 and A-skip 1 × 20, totaling 65 contacts, at Easy (at least 4 RIR) with 60-second rests. Excluded entries are omitted. The fitting ladder treats plyometrics as accessories and never empties a day.

The lead accepted Strength rules for Sprint lift slots on November 13, 2026; Both uses the lift sub-goal's rules. The first Lower day takes the squat main, the second the hinge main, with the other lower row as fallback, then a hinge accessory and a single-leg accessory. Mains use the existing lift rules: Strength's 3 × 5 or the experienced ramped TOP × 5, with New mains at 3 RIR. Upper days hold the resolved mains of rows two through four. No core or calf slot is added. The table's Nordic day adds 2 × 4 to 6; when Nordic is unavailable, the first other available knee-flexion accessory keeps its own rep target at two sets. The 65+ balance block comes before plyometrics, lifts and Nordic on lift days.

The lead also accepted these lift contents and invariant boundaries on November 13, 2026. Budget, availability and effort invariants 1, 6 and 7 apply, as does invariant 5's cap of 20. Invariant 2 applies to the rows the layout hosts. Exposure and coverage floors in invariants 3 and 4 and invariant 5's floors do not apply: the table has no upper day at three through five days. Both with sprint therefore places chosen upper mains only at two and six days, and only one lower main at one through three days. Fitting and seeding reuse the existing helpers, and reasons come only from fitting.

Sprint sessions are exempt from the run cap at creation, as the lead accepted on November 13, 2026. Standing recovery must not cause a short logged jog to cut sprint rounds. The weekly 15% sprint WORK guardrail belongs to PR 5c. Sprint blocks are not shortened to fit minutes; the lift fitting ladder runs on days with exercises, and any remaining excess is reported.

The lead accepted deferring timing method and best time until PR 7's timer on November 13, 2026. SprintAnswers holds only the event, sprinted all-out in the past month, and hamstring strain. A best time without a start protocol cannot form a SprintAttempt. Intro changes sprint ranges alone; plyometrics open at 65 contacts either way.

The lead accepted leaving Progression.kt unchanged in PR 5b on November 13, 2026. PR 5c owns two-session steps, the fourteen-day gap, the weekly 15%, contacts growth, intro countdown and time trials. It also owns the sprint exemption from the cap at open and replacement of run stepping for sprint days, preserving their build-up. Until then, cardioAtStart still caps any non-ladder block and cardioAfterSession would route a sprint rule through the run range path. No production caller writes a sprint rule before PR 7.

## 41. Revision 32 (PR 5c-i, sprint progression)

The lead accepted the redesigned sprint progression rules on November 14, 2026, after two council reviews. Sprint days share their range table and block builder with creation. Each step adds a fixed unit to the day's total WORK meters: acceleration 10 m (40 to 240 m), max velocity 10 m (30 to 100 m), sprint endurance 20 m (300 to 600 m), tempo 100 m (600 to 1,800 m). Total = low end + steps × unit. The rep count is total ÷ shortest rep, clamped to the rep range; distribute the total in 10 m grains (tempo: 100 m), longer reps first. Sprint endurance goes from 130/130/120 at 380 m to 4 × 100 m at 400 m. Tempo keeps six reps and lengthens one rep at a time. Maximal WORK stays hard, recovery stays between reps, and maximal blocks keep their ten-minute build-up.

Intro is three counted sessions per sprint day, stored as a countdown in CardioRule, including tempo. Every WORK set must finish under the existing full-success rule; an unfinished WORK set or Pain does not count. Hard does not count for tempo's Good band; any effort word counts for maximal work. The session that ends intro earns no credit. Thereafter a counted session earns a credit if the day has none; a counted session holding credit adds one step and clears the credit. At its range top the day holds. Each sprint day, including tempo, stores sprintStepCycle, the rotation of its last step. If it already stepped in the current rotation, it keeps the credit for a later rotation. Repeating A nine times before B, running days out of order, and skipping days do not create another step. There is no fairness wait or runtime cap check on progression. Steps depend on rotations wrapping, as light cycles do: once a day has stepped, a user who never completes a rotation cannot progress further.

The planned maximal meters are the sum of every acceleration, max-velocity and sprint-endurance day's stored WORK total, excluding tempo. The planned total can rise by at most **max(15% of the planned rotation, 20 m)** between rotations, by construction: one step per maximal day adds at most 10 m in a one-day plan, 20 m in a short-event two-day plan, or 30 m in a 200/400 m two-day plan. Days three through six add no maximal day. There is no percentage check at Finish, split fallback or wait bookkeeping. Skips, trials and tempo substitutes never lower stored steps. A layoff restart is the only path that lowers a step. Non-intro acceleration opens at the first step totaling at least 160 m (8 × 20 m, step 12); intro stays at its low end when its countdown ends. Every sprint day remains exempt from the run cap.

**The 20 m floor is a lead decision, practice only, departing from audit decision 5's pure 15% limit.** At low totals 15% cannot admit one 10 m unit, leaving beginners unable to progress. The floor permits 40 → 50 m (25%), 30 → 40 m (33%) and 70 → 90 m (29%); only 30 → 40 m exceeds the audit's suggested approximate 30% ceiling. At 134 m and above the rise stays within 15%. Neither 15% nor 20 m is a tested sprint cap, and no injury-prevention claim is made. Duhig 2016 measured actual high-speed running against each elite Australian football player's two-year session average; this planned total is a product proxy for that measure. Malone 2017 studied 37 elite Gaelic footballers: higher chronic loads (≥ 4,750 AU) and 90 to 120 m per week at max velocity had a lower-risk association that was not significant (p = .055); the lower-load group showed increased risk. Neither study establishes that progressing a beginner is safer than holding them. Haugen 2019's session ranges are coaching recommendations, not validated minimum doses. The 10 m grid is practical to mark; 20 m sprint-endurance steps make the top attainable within a year of counted sessions. Tempo uses its existing 100 m grid.

A maximal day restarts when the plan went **more than 14 days** without a maximal sprint at any point since that day last ran. Use one predicate on consecutive gaps in [the day's last maximal date, all later maximal dates in the plan, today]; if the day has never run, start at the plan's first maximal date. Fourteen days keeps the stored block; fifteen restarts. A maximal date is a session with at least one finished WORK rep of a maximal block: normal blocks, restarts and trials count, including Pain; all-skipped sessions, tempo and B's tempo substitute do not. B checks layoff before trial substitution. Start shows the low-end block as a view; Finish writes steps = 0, credits = 0 and intro = 3, and keeps the last-step and trial rotation markers. A counted restarting session is intro session one, leaving intro = 2. Tempo never resets. The threshold is practice; detraining studies do not settle it. PR 7's restart receipt must explain: “More than two weeks since your last sprint session”.

Every fifth rotation's Sprint A opens a time trial only when intro is over, A has not already run a trial that rotation, no layoff restart applies, and the trial rotation's maximal meters fit planned + max(15%, 20 m). Count both reps for short events plus every maximal day still running its normal block. At 200/400 m, no trial is offered if B already finished a maximal block this rotation, even off-suggestion or without earning progression credit. A runs its normal block and waits for the next trial rotation. B runs the low-end tempo block only when A's sprintTrialCycle equals cyclesCompleted, meaning A ran the trial. Its stored maximal step, intro and credit stay unchanged. Skipping A, finishing intro on a trial rotation, or restarting leaves B normal. Repeating A after its trial opens A's normal stored block. At 40 yd, 60 m and 100 m, B always runs its normal block. An over-bound trial leaves both normal blocks in place. One-day 200/400 m plans never get a trial: their max-velocity total tops out at 100 m, and even 200 m exceeds 100 + 20 m. The lead accepts, as practice, 200/400 m trials while training reps are 100 to 130 m: the trial rotation is lighter in volume, and no study points either way.

Trials keep a ten-minute build-up, then two hard WORK reps at 40 yd (37 m) or 60 m, with 240 or 360 seconds between them, or one rep at 100, 200 or 400 m. Finish writes sprintTrialCycle = cyclesCompleted on A and preserves its steps, credit and intro; a trial earns no progression credit. The stored-step block returns afterward. PR 7 owns writing the attempt; case 37 takes the session's best rep.

Plyometric contacts already grow through the bodyweight lift rules: pogo 3 × 15 gains one rep per counted session up to 20, while A-skip 1 × 20 holds. Contacts at session starts are 65, 68, 71, 74, 77, 80 and then 80. PR 5c-i pins this behavior without changing lift progression. Bounds and box jumps are exercise changes on Finish and belong to PR 5c-ii. A-skip at the cap reports Reason.AddWeight; PR 7's receipt copy must not tell the user to add weight to a drill.

RunSession.quality keeps the six run range kinds: threshold, VO2max, race pace, test intervals, tempo and sharpening. The sprint property identifies the four sprint kinds. Timed goal bests skip a WORK set carrying a sprint time, matching general cardio records; untimed sets retain their existing behavior. CardioRule's intro is omitted from JSON at zero; sprintStepCycle and sprintTrialCycle are optional and omitted when null. v0.11 rules keep their exact encoding. SprintContext requires the plan including prior Finish updates, maximal session dates by plan day, IDs of maximal days already run this rotation, today, cyclesCompleted before the rotation wraps, and the goal. Finish wiring remains PR 4b-ii's, and production sprint setup remains PR 7's.

Sprint steps change meaning versus main: acceleration formerly counted added rounds then whole-block lengthening (8 × 20 m was step 5, now step 12); sprint endurance and tempo also change. This is safe because no production caller writes a sprint rule before PR 7. Run ranges, the run/walk ladder and null-rule progression retain their existing meanings.

**Audit changes (2026-11-14, redesign after council rounds 1 and 2).** The lead's practice decision replaces audit row 5's pure 15% cap with max(15%, 20 m) of the planned rotation, enforced through fixed units and one step per day per rotation, with a runtime check only for trial admission. Row 8's threshold becomes more than 14 days, applied across the plan since the day last ran; there is no fascicle rationale for that number. Row 9 keeps best-of-two 40 yd and 60 m trials with 240/360-second recovery, one rep at 100 to 400 m. See [decisions.md rows 5, 8 and 9](wizard-evidence-audit-2026-11-13/decisions.md). These are practice rules, without injury-prevention claims.

## 42. Revision 33 (evidence audit, 2026-11-13)

Calibration jumps and the Easy-session double are practice (safety-first), following rows 1 and 2 of the [evidence audit decisions](wizard-evidence-audit-2026-11-13/decisions.md). Both read Easy as exactly 4 reps left, even when a higher reserve was reported.

While calibrating, an Easy set estimates e1RM as load × (1 + (reps + 4) / 30). The next load is bounded by `Estimate.loadFor` for the slot's target reps and RIR, rounded down to the equipment step, with a one-step increase as the floor. If reps + 4 exceeds ten, no estimate is trusted and the next load is one step higher. The floor can exceed the Epley bound on coarse equipment. The first non-Easy anchor still concludes calibration through the existing estimate or direct-load path.

An Easy session doubles the rounded single increment only when every working set was Easy and the session's heaviest working set, read at 4 RIR, supports the doubled load at the slot's target RIR. Predicted RIR is `30 × (e1RM / doubledLoad − 1) − targetReps`. A missing estimate or a prediction below the slot's RIR uses the single increment with `Reason.AllMade`; `Reason.EasySession` applies only when the double is allowed. Coarse steps and the existing 10% cap keep their behavior. Case 7's 275 × 5 TOP estimates 357.5 lb; at 285 × 5, predicted RIR is 2.63, so the double remains allowed for its 2-RIR target.

Case 12 is re-pinned at its fixture's target of 5 reps and 2 RIR, using a 45 lb bar and 5 lb steps: 45 × 5 Easy estimates 58.5 lb and bounds the next load at 58.5 / (1 + 7/30) = 47.43 lb, so the one-step floor gives 50. Then 50 × 5 Easy estimates 65 lb, bound 52.70, next 55; 55 × 5 Easy estimates 71.5 lb, bound 57.97, next 60. A 60 × 5 Good set estimates 74 lb and calibrates to 74 / (1 + 7/30) = 60 lb. A 60 × 5 Max set instead estimates 70 lb, giving 56.76 lb and calibrating to the nearest step, 55 lb.

## 43. Revision 34 (lift audit fixes, 2026-11-14)

This revision implements [audit 2026-11-13, rows 10, 11 and 13](wizard-evidence-audit-2026-11-13/decisions.md), superseding the session estimates and older-adult restart rules above. The following is also the implementation report. Values were derived by reading the planner and its fixtures; no builds, Gradle commands or tests were run. CI must verify these expectations.

**Session estimate (row 13, G02/W05).** Each lift takes 15 seconds of setup plus reps × 3 seconds. The 3 seconds is evidence-informed by Wilk 2021's 2–5 seconds per repetition at volitional tempo; the setup allowance is practice because transitions are unmeasured. Only the repetition time doubles for an entry or prescription marked `eachSide`. A timed set takes its planned seconds, doubled for `eachSide`, plus 15 seconds of setup. A superset includes both lifts' work once per pair, with one rest from the existing resolver. A rep range uses its current target; ramps and back-offs use their scheme's own reps. Unruled weighted slots without a rep target use 10 reps. The five-minute warm-up and cardio block's planned time remain; a block's own opening WARMUP replaces the five-minute allowance.

Session minutes cover the workout; the optional cool-down (#574) is extra time, outside the estimate (audit W05). There is no cool-down allowance or `SetupAnswers` switch. Counting 240 seconds caused the dose ladder to cut whole blocks over a few seconds: the four-day 60-minute reference B reached 3606 seconds and lost pullups, laterals and its arm superset; the 30-minute plank-only Both fixture lost its run. Excluding the optional cool-down keeps those blocks and states what the minutes cover. See the [session timing and warm-up audit notes](wizard-evidence-audit-2026-11-13/notes/concurrent-timing-warmup.md). Work and rest both derive their set counts from `setKinds(slot)`, including a ramped prescription whose `targetSets` differs from its scheme.

**65+ carry (row 10, O09).** The balance block opens the farmer's carry at 2 × 20 seconds, at the shared `SlotSeeding` dumbbell floor: 10 lb per hand, or 5 kg per hand stored in pounds. This opening is practice. Timed holds do not support load calibration: seeding preserves their rule, `nextSet` skips timed rows, and `usesLoadCalibration` rejects them. The carry therefore opens without calibrating. The catalog remains `time(40, 50.0)`, `perHand = true`, `eachSide = false`; per hand describes the load, not two timed sides. The catalog default is also used by ordinary timed seeding and authored slots, so changing it would affect non-65+ users. Only the 65+ balance prescription overrides it.

**65+ layoff (row 11, O10).** Measure extra days after expected recurrence. Under 16 slot exposures, 65+ keeps the prior rule: past 14 extra days, ×0.75 and load recalibration when supported. From 16 exposures, the adult bands apply: 0–3 normal, 4–7 hold, 8–14 ×0.95, 15–42 ×0.85 with ceil(2/3) working sets, and past 42 ×0.75 with recalibration. Sixteen exposures approximates eight weeks at twice a week; that threshold, factors and set reduction are practice informed by Lemmer 2000 and Yang 2022's history-dependent findings. New lifters below 65 retain recalibration past 14 days regardless of exposure count. `atStart` now takes separate `olderAdult` and `newLifter` flags; the live repository call passes its existing age flag as `olderAdult`. No stored state or Room behavior changes. See [O10 and the older-adult evidence](wizard-evidence-audit-2026-11-13/report.md).

### Changed pinned expectations and arithmetic

All session changes below cite audit 2026-11-13, row 13 in their tests. Seconds include the last set's rest, as before. The unchanged planner ladder and volume rules determine all shape changes.

Arithmetic key (work plus rest unless stated otherwise):

- Session base `B = 300`; Rest = 0. An opening cardio WARMUP replaces B.
- Ramped Strength main `R = (15+5×3+90) + (15+3×3+90) + (15+5×3+180) + 2×(15+5×3+120) = 744`.
- Five-rep main set `M = 15+5×3+180 = 210`; secondary set `S = 15+5×3+120 = 150`.
- Eight-rep accessory `A = 15+8×3+90 = 129`; catalog six-rep pullup `U = 15+6×3+90 = 123`.
- Eight-rep isolation/core `I = 15+8×3+60 = 99`; arm pair `P = 2×(15+8×3)+60 = 138`.
- Plank `H = 15+45+60 = 120`; short ramp `Q = 15+3×3+90 = 114`.
- Older main set `O = 15+6×3+120 = 153`; short older main `Q+O = 267`.
- Balance block `V = 2×(15+30×2+30) + 2×(15+20+60) = 400`; `B+V = 700`.
- Sprint plyometrics `Y = 3×(15+15×3+60) + (15+20×3+60) = 495`; four-rep Nordic set `N = 15+4×3+60 = 87`.

The first value is before this PR, the middle value is the reviewed round-1 head with cool-down, and the last is the corrected expectation. Repeated values identify expectations that did not change again.

| Test and pinned value | Before PR → round-1 head → final | Final arithmetic and shape |
|---|---|---|
| PlannerTest case 1, A/B/C/E seconds | [2700, 3525, 2700, 3195] → [2805, 2526, 2763, 3354] → [2565, 3366, 2523, 3114] | A=B+R+2S+3A+6I+2H; B=B+2R+2S+3U+5I+3P; C=B+R+2S+3A+8I; E=B+R+5S+3U+3I+3P+2H. |
| PlannerTest case 1, reasons | [] → [ShortSession(B, 1)] → [] | B=3366 ≤ 3600; pullups, laterals and arms stay. The 3606-second cliff disappears. |
| PlannerTest case 1, weekly triceps | 11.5 → 8.5 → 11.5 | Bench 6/2 + press 5/2 + both arm partners 2×3 = 11.5. |
| DoseLadderTest case 25, one day | 1800 → 1860 → 1620; excess none → 60 → none | B+5×(Q+150)=1620. With core, 1620+2H=1860 > 1800; step 6 still drops core. |
| DoseLadderTest case 25, two days | [1590, 1410] → [1752, 1578] → [1800, 1338]; steps [4,3] → [4,3] → [3,3] | A=B+6M+2H=1800; B=B+4M+2I=1338. A now fits two straight sets per main at step 3, before the short ramp. |
| DoseLadderTest case 36, five mains at 30 minutes | 2160 → 2275 → 2035; excess 360 → 475 → 235; rounded excess minutes 6 → 8 → 4 | B+V+5×267=2035; 2035−1800=235; ceil(235/60)=4. Step 6 and main shapes stay. |
| DoseLadderTest case 36, five mains at 45 minutes | 2520 → 2515 → 2470; step 1 → 4 → 1 | Step 1=B+V+10O+2H=2470 ≤ 2700. Two straight sets per main and core stay; the short-ramp change disappears. |
| DoseLadderTest case 36, four mains at 30 minutes | 1860 → 2008 → 1768; excess 60 → 208 → none | B+V+4×267=1768; with core, 2008 > 1800. Step 6 fits with no excess. |
| DoseLadderTest case 36, three mains at 30 minutes | 1770 → 1741 → 1741; step 4 → 6 → 4 | Step 3=B+V+6O+2H=1858 > 1800; step 4=B+V+3×267+2H=1741. Core returns. |
| DoseLadderTest, General rest through step 5 | 1800-second fixture → same → 1650-second reserved budget | Step 4 with core=B+5×(Q+129)+2H=1755; step 5 preserves 90-second main rest; step 6=B+5×243=1515 fits. A 150-second reserve keeps the test exercising step 5 after removing the cool-down. |
| DoseLadderTest, two 75-minute days | [3960, 4215] → [3909, 4236] → [3669, 3996] | A=B+3R+4S+3I+2H; B=B+2R+8S+3P+6I. Four curls still land on B: after bench/row growth B=3600 < A=3669. |
| VolumeBalanceTest, six-day reference A/B/C/D/E/F/G | [2640, 3105, 2565, 0, 3105, 2010, 3105] → [2763, 3249, 2634, 0, 3291, 2148, 3249] → [2523, 3009, 2394, 0, 3051, 1908, 3009] | A=B+R+5A+6I+2H; B/G=B+R+2S+3A+3U+5I+3P; C=B+R+2S+2A+8I; E=3009−2I+2H; F=B+2S+4A+8I. Trim shapes stay. |
| VolumeBalanceTest, six older days A/B/C/D/E/F/G | [2160, 2430, 2055, 0, 2325, 1995, 2325] → [2398, 2680, 2257, 0, 2602, 2209, 2581] → [2158, 2440, 2017, 0, 2362, 1969, 2341] | A=700+2O+4A+4I+2H; B=700+2O+4A+2U+4I+2P; C=700+2O+4A+5I; E=2440−2I+H; F=700+6A+5I; G=2440−I. Core trimming and two-set carries stay. |
| VolumeBalanceTest, 60-minute fill seconds and reasons | [3510, 3600] → [3567, 3585] → [3525, 3570]; [A0,B0] → [A0,B0] → [] | A starts B+9M+4S+3I+2H=3327. B starts B+6M+6S+3P+2I=3072; bench and row each grow by S, reaching 3372. A takes two curls: 3327+2I=3525; the third would reach 3624. B takes two: 3372+2I=3570. All floors fit. Reset B's reasons also become empty. |
| VolumeBalanceTest, 60-minute B row sets | 3 → 2 → 3 | B after chest fill=3072+S=3222; row growth=3222+S=3372 ≤ 3600. |
| VolumeBalanceTest, 60-minute added slots/role/tail | B seated_curl / ISOLATION / [seated_curl,pallof] → B pullup / ACCESSORY / [pullup,pallof] → A and B seated_curl / ISOLATION, B tail [seated_curl,pallof] | Curl sets on B: 1 → 1 pullup → 2 curls; A gains two curls. I=99 and both final totals fit. |
| VolumeBalanceTest, 60-minute hamstring credit | 3.5 → 2.5 → 6.5 | Trap main 3/2 + trap secondary 2/2 + four curls = 6.5. |
| VolumeBalanceTest, 75-minute fill | [3510, 3915] → expected [3567, 4008], CI [3963, 3612] → [3723, 3372] | A=3327+4I=3723; B=3372. The four-curl assertion moves B → A. Host order, not day labeling, was wrong in the previous expectation. |
| SetCountTest, prototype squat estimate | 1230 → 1398 → 1158 | B+3×(15+5×3+90)+(15+3×3+90)+(15+10×3+180)+(15+8×3+120)=1158. Unruled prototype uses ten top reps. |
| SetCountTest, superset difference | 0 → +135 → +135 | Three partner sets × (15+10×3)=135; one rest per pair. Single curl 705 → 945 → 705; pair 705 → 1080 → 840. |
| SetCountTest, unknown-slot estimate | 705 → 945 → 705 | B+3×(15+10×3+90)=705. |
| PlannerRunTest, half at four 60-minute days A/B/C | [3330, 1500, 3330] → [3282, 1500, 3240] → same | A=B+1800+2×(150+129+99+93+120); C replaces plank 120 with pallof 99. Nordic uses six catalog reps: 15+6×3+60=93. |
| PlannerRunTest D14, A/C seconds | [5070, 5070] → [5022, 4980] → same | A=B+3540+1182; C=B+3540+1140. Cardio and strength shapes stay. |
| PlannerBothTest D15, 30-minute plank-only lift and run | lift 510/run present → lift 780/no run → lift 540/run present | B+2H=540; run floor((1800−540)/60)×60=1260; combined=1800. Same EASY session returns; the cool-down-off comparison is removed with the switch. |
| SessionBudgetTest, strength with attached run | new 1770 → 1530 | B+(15+5×3)+1200=1530. Pure cardio stays B+1200=1500; its own opening 600-second WARMUP gives 600+1200=1800. Cool-down delta and switch tests are replaced. |
| PlannerSprintTest, 100 m at two days A/B/C | [2699, 0, 3326] → [2693, 0, 3155] → same | A=794+Y+3M+6A; C=1091+9M+2N. Cardio days and shapes stay. |
| PlannerSprintTest, 100 m at three days A/B/C/D/E | [1214, 0, 1995, 1091, 0] → [1289, 0, 2118, 1091, 0] → [1289, 0, 1878, 1091, 0] | A=794+Y; C=B+3M+6A+2N. Shapes stay. |
| PlannerSprintTest, 100 m at four days A/B/C/D/E | [1214, 1785, 0, 1091, 1995] → [1289, 1944, 0, 1091, 2118] → [1289, 1704, 0, 1091, 1878] | A=794+Y; B=B+3M+6A; E=1704+2N. Shapes stay. |
| PlannerSprintTest, 200/400 m at two days, 75-minute reference | [2996, 0, 3903] → [2990, 0, 3732] → same | A=1091+Y+3M+6A; C=1668+9M+2N. |
| PlannerSprintTest, 200/400 m at two days, 60-minute fitted | [2996, 0, 3018] → [2990, 0, 3558] → same; C step 3 → 1 → 1; sets [2,2,2] → [3,3,3] → same | Step 1 drops Nordic: 1668+9M=3558 ≤ 3600. All three upper mains keep three sets. |
| PlannerSprintTest, five/six days upper G | 2325 → 2430 → 2190 | B+9M=2190. Shape stays; pure tempo remains B+516=816. |
| PlannerSprintTest, recent acceleration with plyometrics | 1918 → 1993 → same | 1498+Y=1993. Shape stays. |
| PlannerSprintTest, 30-minute one-day fitted A | 1469 → 1424 → same | 794+3M=1424 after step 1. Shape stays. |
| PlannerSprintTest, recent 30-minute A excess | 118 → 193 → same | 1498+Y−1800=193. Steps A6/C1 stay; C full=1878 > 1800, after step 1=B+3M=930 (was 1170). |
| ProgressionOlderLayoffTest case 23, trained older opening (row 11) | Recalibrate / 205 lb / calibrating → Scaled(0.85) / 235 lb / no override → same | Fixture uses the trained boundary at 16 exposures: 1025−1000−7=18 extra days; 275×0.85=233.75 → nearest 5 lb=235, versus 275×0.75=206.25 → 205. ceil(3×2/3)=2 working sets rather than 3. |

**Volume-fill failure trace.** On the reviewed head, the unfilled novice days were A=3567 and B=3312. Chest growth took B to 3462; lat growth took it to 3612. A was then shorter, so the four curls landed on A: 3567+4×99=3963, leaving B=3612. The old [3567,4008] expectation assigned the same 396 seconds of curls to B without following the host sort. Removing the cool-down gives the pre-fill pair [3327,3072]; after chest and lat growth it is [3327,3372]. The 75-minute result is [3723,3372]. At 60 minutes the third curl on A is blocked, so fill adds and grows a second curl slot on B, giving [3525,3570]. The old step-zero fixture now reaches every floor; a separate 3500-second reserved-budget fixture pins [3426,3471] and step-zero reasons on both blocked days, then only B when A is ineligible.

**Remaining shape changes relative to before the PR.** The two-day 30-minute Strength A stops at step 3 with two straight sets per main instead of step 4's short ramp/top. The novice 60-minute volume-fill fixture has two curls on each day instead of one curl on B; B's row retains its original three sets; at 75 minutes the four curls move from B to A. The fitted 200/400 m upper day stops at step 1 with three sets per main instead of step 3 with two. The 65+ carry opens at 2×20 seconds at the dumbbell floor, and trained older layoffs use the history-aware bands above. The reference B accessory loss, the Both run loss, the older 45-minute short ramps and the older three-main core loss all disappear. No ladder, volume-fill or cardio-layout rule was changed to force a shape.

Unchanged pins were reviewed by reading their fixtures: the remaining planner tests, Run/RunProgress, Both, Sprint/SprintProgress, and their invariant grids. Pure cardio estimates and fitted interval blocks retain their planned seconds. The kettlebell volume tests retain their pinned slot shapes; no planner rule was changed to preserve an earlier shape. The existing low-exposure planner case 23 and older-layoff boundary test retain recalibration past 14 days. Spec §11 goal, prototype seed and TOP-cascade code and expectations were left intact.

### Tests added

`SessionBudgetTest`:

- `five and twelve reps include setup and rep time`: work 15+5×3 = 30 and 15+12×3 = 51 seconds.
- `eachSide doubles eight reps but not setup`: 15+8×3×2 = 63 seconds.
- `a thirty second eachSide hold doubles time but not setup`: 15+30×2 = 75 seconds.
- `a superset counts both ten rep lifts once and one rest`: 300+45+45+60 = 450 seconds.
- `a range uses its current target and ramps and backoffs use their own reps`: range current 11 gives 15+11×3 = 48; ramped fixture totals 300+120+114+30+2×150 = 864.
- `strength and cardio include planned time and an opening warmup replaces the allowance`: strength with attached run 300+30+1200=1530, pure cardio 300+1200=1500, own warm-up 600+1200=1800.
- `ramped prescriptions count the scheme sets when targetSets differs`: one `targetSets` but five scheme sets; prescribed reps 300+5×30+420=870, prescribed per-side holds 300+5×75+420=1095, catalog holds 300+5×60+420=1020.

`VolumeBalanceTest`:

- `fill stops at the reserved budget and reports step zero on only the blocked days`: 3500-second budget; 3327+99=3426 and 3372+99=3471; another 99 seconds fits neither day. Repeating with A ineligible reports only B.

`PlannerTest`:

- `older carries open at the dumbbell floor per hand in either unit`: 2 × 20 seconds, 10 lb / 5 kg per hand, no calibration; catalog stays 40 seconds / 50 lb per hand.

`ProgressionOlderLayoffTest`:

- `fifteen versus sixteen older exposures at twenty days past recurrence`: gap 27−7 = 20; at 15 exposures, 275×0.75 → 205 lb, 3 working sets and recalibration; at 16, 275×0.85 → 235 lb, ceil(3×2/3) = 2 working sets, no override.
- `trained older layoff bands use the recurrence clock through forty three days`: boundaries 3/4/7/8/14/15/42/43; at 43, 275×0.75 → 205 lb with recalibration at either 15 or 16 exposures.
- `New lifters below sixty five keep recalibration regardless of exposures`: at both 15 and 16 exposures, 20 extra days still uses 275×0.75 → 205 lb and recalibration.

### Files changed

- `domain/src/main/kotlin/cloud/trotter/log/strength/domain/planner/`: `SessionBudget.kt`, `SetupAnswers.kt`, `DayLayout.kt`, `DoseLadder.kt`, `VolumeBalance.kt`, `BothLayout.kt`, `RunLayout.kt`, `SprintLayout.kt`.
- `domain/src/main/kotlin/cloud/trotter/log/strength/domain/progression/`: `Progression.kt`, `SlotSeeding.kt` (floor shared within the module).
- `data/src/main/kotlin/cloud/trotter/log/strength/data/TrackerRepository.kt`: one named argument follows the separated age flag; no transaction, schema or storage changes.
- `domain/src/test/kotlin/cloud/trotter/log/strength/domain/planner/`: new `SessionBudgetTest.kt`; updated `PlannerTest.kt`, `DoseLadderTest.kt`, `VolumeBalanceTest.kt`, `SetCountTest.kt`, `PlannerRunTest.kt`, `PlannerBothTest.kt`, `PlannerSprintTest.kt`.
- `domain/src/test/kotlin/cloud/trotter/log/strength/domain/progression/ProgressionOlderLayoffTest.kt`.
- `docs/briefs/wizard-design-2026-11-08.md`: this revision and report.

### Beyond the spec

No additional behavior changes. To preserve existing coverage after the 60-minute fill fixture stopped being blocked, the step-zero assertions use a separate reserved-budget fixture. The General rest fixture reserves 150 seconds so it still reaches step 5. The live caller's named-argument update remains required to distinguish age from New status; no Room, backup, app or wire changes were made. No network calls, subagents, builds or tests were used. Compilation and test execution remain for CI under the task's hard rules. The known `AppCrashesTest > mainLooperFailureFailsTheStep` playtest ClassCastException is unchanged and remains a follow-up. No files were staged, committed or pushed.

## 44. Revision 35 (every set is a card, 2026-11-15)

The effort row moved from the rest band into the set's card (#583). A set
that shows an effort row must be rated before End rest or Next set; ramps, timed holds and watch
sets are never asked. Null still counts as on target at Finish (a set left by picking another lift,
or logged on the watch).


## 45. Revision 36 (PR 6a, the data the new setup writes)

Lift setup offers balance-only entries to a lifter 65 or older with fewer than three lifting days, following audit 2026-11-13 decision 12 (WHO 2020). The default leaves the rotation alone and adds one BalanceDaysOffered reason. Accepting the offer stores balanceDays and adds entries after the first lifting day: A Full Body, B Balance, C Balance for one lifting day; A Full Body, B Balance, C Full Body for two. Letters, working keys, sessions and day reasons move together. Each Balance entry contains the available, non-excluded balance block, seeded through SlotSeeding. The step follows fitting and volume balance, leaving the lifting days intact. An empty block, three or more lifting days, or a lifter under 65 gets neither reason. Run, Sprint and Both keep their current layouts until PR 7. Copy must never imply this block meets the fall-prevention dose.

Your lifts lists every available, non-excluded, non-timed candidate the planner accepts for each of its five main rows, excluding loaded pairs whose equipment equals their bodyweight pair's. The default leads each row, then weighted choices precede reps choices in pattern and catalog order. This is wider than mainCapable because that flag identifies eight barbell-centric entries and would omit choices for dumbbell, kettlebell, machine and bodyweight users. Default mains are unchanged.

Recipe v2 stores the Lift branch, lift goal, days, minutes, experience, olderAdult, optional mains, recent numbers with effort, excluded ids and balanceDays. Equipment and load steps live in settingsJson; unit comes from the app setting. None of those three facts is in the recipe. This build refuses to encode other branches so it cannot drop their answers. Reads return null for an unsupported or invalid v2 recipe; backup validation rejects it.

A v1 plan keeps equipment, typed lift goals and emphasis in its recipe until conversion in 6c. A v2 plan or a plan without a recipe keeps equipment and typed lift goals in settingsJson. Missing equipment means all equipment. Defaults for on a setup plan is its lift sub-goal, with that plan's load steps. Emphasis edits ignore v2 recipes, and their Plan screen hides Emphasis while keeping Goals visible.

Creation holds the plan selection lock and writes the GENERATED plan, every day, slot and partner rule, and every opening MAIN exercise_log in one Room transaction. A crash rolls it all back. A selected plan without days is filled in place. Inside the transaction the planner first runs without known loads to find the exact exercise ids, then runs with their last working sets from history; shape does not depend on loads. Recent numbers take precedence. Preview uses the same two passes without writes. Publishing selects an active plan and puts it on Today without writing a lifter profile. This build supplies the repository path without a production caller or new setup screen.

Reset day to plan dispatches by recipe. V2 rebuilds title, kind, cardio, slots and rules through Planner.day, retiring the day's active tracks and deleting its live rows first. It writes no replacement rows: lazy seeding uses the plan's working load elsewhere, then history, then the equipment floor, and Start persists missing seeds with their calibrating flags. An unreadable recipe, unknown day, Rest entry or planner exception changes nothing. V1 keeps its template path. The button and confirmation now use Reset day to plan for both versions.

History mains are known weighted slots with role MAIN in day order, one card per exercise. A LoadReps prescription also permits a weighted trajectory regardless of catalog tracking. A main without a typed goal keeps its card with no goal line. The generated Plan screen uses the same membership, shows typed weights or Not set, and opens an empty goal field for an untyped lift. Remove goal appears only for a stored goal. Calculated goals and Use calculated leave these readers; the domain formula remains pinned for legacy callers.

Backup v28 carries recipe v2 and the new settings equipment and goals, validating all three strictly. Older documents carrying those payloads are inconsistent; v27 otherwise keeps its decoder. Older builds reject v28 with their existing newer-version message. Releases continue between PR 6 and PR 7; a later shape change requires another version bump.


## 46. Revision 37 (PR 6b, lift setup screens)

Setup at the existing `wizard` route replaces the old wizard UI. Its canonical steps are Goal, Sub-goal, Your week, Equipment, About your training, Your lifts, Recent numbers and Your plan. Goal offers Lift alone until PR 7. Strength and Muscle include Your lifts when any main row has choices. Every trained lifter with a weighted current main gets Recent numbers, including General fitness. New lifters skip Recent numbers. A removed step falls back to the last preceding step still in the draft's list. Next stays enabled after initialization; Start waits for a ready, nonempty preview. The top arrow and system Back step backward, and a later entry can leave from Goal. Restore and creation hold Back until their writes finish.

One versioned JSON value under `setup_draft` holds every answer and the step by name. Unknown fields are ignored, missing fields default, and unreadable versions start again. This lets PR 7 add answers without changing a saved Parcelable. Three separate latches hold initialization, first-run entry and the created plan id. The new ViewModel reads no old `wizard_*` draft keys. Preview and operation progress are transient; a restored draft on Your plan previews again.

The default draft maps to `SetupAnswers()` exactly. Sub-goal maps to the lift goal; days and minutes map directly. Equipment is the exact selected set. A preset or checkbox change resets chosen mains to null, so unavailable choices cannot survive gear changes. Units keep the three smallest-step option indices and convert the options to pounds. About asks lifting experience and 65 or older, with no profile write. Your lifts replaces only the selected main's row. Recent numbers keep complete current-main entries with finite positive weights no greater than 2000 lb and 1 to 10 reps; New ignores them while retaining typed drafts. Later entries prefill only a readable v2 selected-plan recipe, otherwise defaults in the app unit; first run uses the device unit.

Your plan previews the same two planner passes Start writes. Each rotation row shows its letter, stored title, first main's short name and working load when weighted, lift count and estimated minutes. Rest has just its letter and title. Over-budget rows state the excess minutes. An over-budget card offers a main to drop and the next longer session option; nothing is dropped until chosen, and Start stays enabled while a day is over. A 65+ lifter on fewer than three lifting days gets an explicit Add offer for the remaining balance days, following audit 2026-11-13 decision 12 (WHO 2020). Accepting adds the short Balance entries; Remove returns to the unaccepted preview. Declining leaves the lifting days alone.

Why this plan lists short-session ladder facts, over-budget facts, accepted or offered balance days, reps left when every rule carries them, calibration when any rule carries it, a planned lighter week when present, and estimated minutes last. The older examples of twice-weekly squat and deadlift and Rest after C have no planner reason and are not built. No copy implies the balance block meets the fall-prevention dose, calls an estimate your max, predicts strength lost after a layoff, calls lighter weeks required, or claims evidence for practice values such as set floors, cadence, increments, back-offs or warm-up doses. Effort stays Easy, Good, Hard and Max without rep counts, per owner decision L30.

Start sets its double-tap guard synchronously, takes the existing restore lock and lets startup reconciliation finish. A completed restore underneath a first run makes Start step aside. Otherwise it reuses a remembered plan with days or creates one transactionally through `createPlannedPlan`, remembers the id immediately, publishes selection and Today, writes the unit and writes completion last. A retry or recreation reuses that id. A landed mid-first-run import clears the id and reseeds at Goal from the restored device. The first-run-only restore picker and app-scope import keep their existing error mapping. Setup plans' own 65+ answer also drives workout layoff openings, ahead of profile age; 6c keeps this rule.

| Mock | Built | Reason |
|---|---|---|
| Easy, Right, Hard, Grinder | Easy, Good, Hard, Max | Owner decision 7 and L30 |
| Run, Sprint and Both cards | Lift alone | Owner, until PR 7 |
| Work-around chips | A 65 or older checkbox row | Lead answer 3; no planner rules read the other answers |
| Split dropdown | None | Lead answer 4; the planner derives layout |
| Split description subtitle | Plan name, such as Strength, 4 days | The planner exposes no split name |
| Placeholder sub-goal descriptions | The approved strings; General fitness says nothing about cardio | The Lift plan has no cardio |
| Recent numbers for Strength and Muscle | Every trained lifter with a weighted main | Design §2.2 and the mock |
| No balance offer | An explicit offer card | Owner answer 2, 2026-11-15 |

The cardio-first setup is gone until PR 7; runners and cardio-first users can Build my own. The legacy generator, formula and repository fixture methods remain for their callers until 6c and 6d. No Room, DataStore or backup shape changes; backup stays v28.


## 47. Revision 38 (PR 6c-i, old plans convert)

A plan's equipment, typed lift goals and old wizard mains now have one home, settingsJson. A recipe is setup v2 or null after the fixups gate. A v1 recipe still waiting behind the gate reads as no setup recipe: settings facts, no Reset day, and the profile age for the 65+ fallback. The global lifter, its Level and the You screen stay unchanged until 6c-ii. Config reads the global lifter alone; every unit reader uses the same weight_unit preference through unitFlow, read before a Room transaction.

History and the Plan screen's goal rows count a known weighted slot when its rule role is MAIN or its exercise id is in PlanSettings.legacyMains. Conversion records ids of slots whose stored structure has a top set or whose prescription target is LoadReps: the two main shapes the old wizard wrote, including cardio-first lift days. It never changes a role. Changing substituted novice mains from ACCESSORY to MAIN would change Strength rest from 90 to 180 seconds, and Muscle or General fitness rest from 90 to 120 seconds; TOP overrides would also replace WORK overrides. Stored membership preserves those rests, increments, RIR, cadence, chips and progression while letting the generator leave in 6d.

For a plan as the old wizard created it, History membership is unchanged. Two user edits can differ from 6a: a novice main swapped away and back lost its prescription on the first swap, so it loses its card; a LoadReps target typed on a non-main slot of an old plan gains a card and a goal row. History points and typed goals otherwise read the same data.

The gate runs legacy preferences, on-Today membership, v1 recipes, legacy cardio, then slot rule conversion. V1 precedes legacy cardio so each old plan's remaining legacy days use that plan's cardio mode and 5K flag. Recipe-less days use the old wizard defaults, outdoor running with the 5K goal on. Legacy preferences apply their plan facts directly to generated plans without recipes before their keys and done flag retire in one DataStore edit. No DataStore read happens inside a Room transaction.

Every non-null recipe that is not v2 converts in one Room transaction, deferred while a restore marker is pending. applyV1Recipe rereads the plan in that transaction. Equipment comes from the readable recipe; recipe goals are overlaid with settings goals, so a goal typed while restore was pending survives. legacyMains comes from the stored slots. An unreadable old recipe keeps equipment and goals as stored, still records mains, uses old cardio defaults and becomes null. Applying the same recipe twice yields the same settings. A process kill rolls the whole pass back, and another gate call retries the untouched recipes.

Only plan.settingsJson, plan.recipeJson and, for days with legacy cardio, program_day.cardioBlockJson and cardioJson change. Settings write only when different; recipeJson becomes null; existing typed cardio blocks win and cardioJson clears. No other plan column, slot, rule, partner rule, working row, run, track or session row changes. Defaults for, loadSteps and runCap stay as stored. Missing Defaults for already means Strength and null loadSteps already derives the current unit's steps; writing today's steps would freeze them across a later unit change.

Setup v2 and Build my own plans are skipped. Duplicating a plan copies its settings, including legacyMains. Restoring v9 through v29 inserts plans as written and rearms the gate, which converts any v1 recipes. A v1 through v8 backup maps its settings block to FullSnapshot.legacyRecipe; import applies it after inserting program rows, within the restore transaction. Old-format interrupted-restore journals apply their staged plan facts through the same function to generated plans without v2 recipes, preserving setup plans. Every restore rearms the in-memory conversion flags.

Emphasis leaves the Plan screen because it only edited the old generator's template reset. Old plans have no Reset day. Setup plans keep Reset day to plan through Planner.day with lazy row seeding; other days remain untouched. createGeneratedPlan, publishCreatedPlan, setWizardAnswers and PlanRecipeJson.encode remain test fixtures until 6d. The two fixtures that write v1 recipes rearm fixups after their writes without nesting the selection and migration mutexes.

Backup v29 adds settingsJson legacyMains and validates non-blank ids strictly. An older-version document carrying a nonempty legacyMains is inconsistent, since an older build would silently lose those History cards. V1 recipes remain valid from v9 onward and convert on restore. Releases continue between builds, so 6c-ii's profile shape needs v30 rather than reusing v29. No Room version, DataStore key, SavedStateHandle shape or watch wire changes in 6c-i.


## 48. Revision 39 (PR 6c-ii, the profile)

LifterProfile holds two optional numbers: bodyweight in lb and age. Null means not entered; there are no defaults standing in for answers. Unit stays in SettingsStore.unitFlow. Bodyweight and age share one source of truth in SettingsStore.lifterProfileFlow, with setLifterProfile and updateLifterProfile for writes. PlanRepository no longer wraps profile reads or writes. The remaining old wizard fixtures write only bodyweight and age.

A profile reads the finer bodyweight key first, then the legacy whole-pound key, and reads age when present. Every write removes the legacy bodyweight key and the retired level key; clearing bodyweight removes both bodyweight keys, and clearing age removes its key. Restore clears all restorable preferences before writing the profile. A device that never edits its profile can keep an unread level value until its next write. No new DataStore key is needed.

You shows empty fields for missing numbers and has no Level section. The Plans You row reads Not set until a bodyweight exists, then bodyweight alone or bodyweight with age. Finished sessions stamp the nullable bodyweight directly. Health Connect offers its comparison prompt only when the profile has a bodyweight; its copy no longer promises goals. Profile age 65 or more enables the older-adult restart rule only for plans whose setup did not ask. A v2 setup plan's own olderAdult answer wins, including false.

A setup plan with experience New also takes the New restart rule while the lifting log is under six months old: no finished lifting session, or fewer than Progression.NEW_LIFTER_LOG_DAYS = 182 local days since the earliest completedAt. Every lifting session counts across plans, including CSV imports and sessions without sets; cardio sessions do not. Past fourteen days off, this rule scales by 0.75 and recalibrates. Plans without a v2 recipe never take it because they never asked about experience. The recipe's olderAdult and experience fields are read independently and tolerantly by SetupRecipeJson, so an unreadable answer never blocks Start. The shared opening function computes New from Room for both the Day preview and Start.

Backup v30 carries nullable bodyweightLb and age on every document, plus the precise nullable bodyweight. It never exports level or hasLifterAnswers. Both legacy fields remain declared for older-document compatibility, but nothing maps level. Before v30, hasLifterAnswers false maps to an empty profile; otherwise the precise bodyweight wins over whole pounds and age restores as written. Those older documents must contain both numbers. V30 maps the nullable numbers directly; present values retain validation. Older builds refuse v30 as a newer version. Each released document shape has its own version, so v29 is not reused.

This does not prefill setup's 65 or older answer from the profile or write it back (#592). A future prefill can read profile age against Progression.OLDER_ADULT_AGE. Write-back would require setup to ask age as a number: a checkbox cannot supply an age. The source tie-break lives only in TrackerRepository.olderAdult. Moving You to Settings would keep the same profile and Not set rule. No Room schema, SavedStateHandle shape or watch wire changes.


## 49. Revision 40 (PR 6d-i, old plans as data)

PR #597 froze the old wizard's main-lift rule as `V1Mains` in `data:serialization`. Its inputs are the old recipe's days per week, split, anchor scheme, deadlift variant and equipment set. Five ranked historical lists cover bilateral squat, hinge, horizontal push, horizontal pull and vertical push. These lists preserve the old availability, substitution, anchor and day-order rules independently of later catalog edits. Conversion intersects that membership with the plan's actual slots, also preserving slots with a top-set structure or a LoadReps prescription. Roles, rest and working rows stay intact. A CI proof compared the frozen rule with the live generator for all 368,640 inputs before deletion: five day counts × four splits × three anchors × three deadlifts × 2,048 equipment sets. Literal frozen-mains tests remain the contract after that proof retires.

The three old enum answers now have data-owned twins, `V1Split`, `V1Anchors` and `V1Deadlift`, with the same stored names. V1 recipe decoding, legacy preferences and pre-v9 backup mapping read those twins without keeping the domain wizard alive. The old cardio-first lift-day choices are a frozen validation set in the reader.

Old-plan test fixtures hold literal programs, recipe text, equipment, typed goals and main membership. They represent both kinds of old plan: pre-2a programs without rules, converted at launch, and plans created from 2a through 6b, already ruled but still carrying a v1 recipe. Shared writers insert the first kind directly and produce the second by running fixups before restoring its v1 recipe. The merged proofs compared every fixture with the live generator, including recipe bytes and main membership, and compared the fixture writer's plan, days, slots and logs with the old create path before and after fixups. Those proofs ran in CI while both paths still existed. The surviving fixture-recipe test ties the literal fixture mains to `V1Mains`. `publishCreatedPlan` and `setWizardAnswers` were removed; the old create and recipe encoder stayed only for the proofs until 6d-ii.


## 50. Revision 41 (PR 6d-ii, the formula is gone)

The old wizard's `ProgramGenerator`, `SplitDefaults`, `CardioFirstPlanner`, `CardioPlanner` and `WizardAnswers` files are deleted, along with `GoalCalculator`, `StrengthStandards` and `SlotSeeds`. Their formula types, creation-only repository method, recipe encoder, tests and the 6d-i generator proofs go with them. Old backup conversion retains the frozen `V1Mains` rule and literal fixture checks. New setup still creates through `Planner`; openings and progression still come from the log through `SlotSeeding` and `Progression`.

`GoalSource` now has three variants: `Weighted`, `Reps` and `Time`. This departs from §3.1.5's wording that Flat stays: once the formula reader goes, no code reads a weighted catalog entry's starting number. Keeping Flat would keep dead numbers in the catalog. Std, FracOfStd and Flat therefore fold into Weighted, which says how the entry is logged. Reps and Time retain their targets because conversion, the planner and opening rows use them. Catalog identity, equipment, ranks, main capability, muscles, doses and tracking types stay unchanged.

`GoalTarget` is one data class, a typed weight in pounds and its per-hand flag: the plan's optional `goalsLb` value. Its formatter still prints the bare converted number; phone and watch keep their expected goal strings. Reps and timed targets live in prescriptions and their existing formatters. `SetSeeder` retains `seedPrescribed`, `shape` and private `rampsAndTop`; the unused seed and partner overloads and accessory-rep constant are removed. Surviving edit and display tests use literal rows or the PROTOTYPE shape, preserving their numbers.

`CardioMode` stays for stored modes, watch deltas, history and legacy decoding. `CardioIntervals`, `CardioBlocks` and `CardioSuggestion` stay for legacy cardio conversion and the watch's shipped suggestions. `Rotation` stays for the planner, repositories and Today; PR 8 moves it and the two cardio files out of the generator package. `TrackerRepository.replaceProgram` stays because repository tests use it to seed programs; it has no production caller and is not a formula path. Replacing that fixture API is a follow-up.

PLAN.md A19 and spec §3, §4, §6 and §11 land with the deletion. The current contract is spec §11.2's table of cases and named tests; §11.1's profile formula pins are retired. CLAUDE.md Verification and DESIGN.md name §11.2. The table follows CI assertions where older design prose differs, including the calibration sequence, current session estimates and exposure-aware older layoffs. The cascade's completed ramps keep their weights.

Custom weighted exercises still store and back up `goalStartLb` exactly as typed. Their catalog mapping now makes explicit that no weighted opening reads it: since PR 2b, a never-opened custom lift opens at the equipment floor and calibrates. A separate owner decision should either use that typed number as a calibrating opening, like Recent numbers (recommended), or remove Starting weight from the form. This PR changes no field, Room schema, backup schema, DataStore key, SavedStateHandle shape or watch code; backup schema stays 30.


## 51. Revision 42 (run audit fixes, 2026-11-16)

This revision implements [audit 2026-11-13, rows 3, 4 and 15](wizard-evidence-audit-2026-11-13/decisions.md). It supersedes revision 29's “Ruled easy runs repeat,” §3.2's ladder row “(or not reported),” and §3.2's Phase and taper factors. The taper affects current users through `CardioJudge.opening`. Ladder progression and ruled easy-run growth remain planner-only until 4b-ii wires Finish; PR 7 will expose run plans in setup. No builds, Gradle commands or tests were run; CI must verify the domain, data and app unit tests.

**Unrated ladders repeat (row 3, R43; practice, weak).** A ladder day advances only after full success rated Easy. Good or no word repeats, including at level 12; Hard or Pain drops one, with a floor of level 1. A runner who never rates stays at the same level. The audit found no trial of per-session, effort-gated advancement. Rating-free advancement could traverse the table in 12 sessions, versus Couch-to-5K's 27; Relph 2023's drop-outs called progression too quick. Videbæk 2015's novice injury rate is context, not proof that this rule prevents injury. The audit's suggestion of two sessions per level is not part of the decision and is not implemented. PR 7's Finish effort row for ladder days needs copy explaining that the rating moves the runner up, with no injury claim; no copy changes land here.

**Easy runs grow to their time stop (row 4, R11; practice guided by weak evidence).** A CARDIO-kind EASY day with `EasyRunContext` grows through A16 on full success, at most once per rotation. The default is TIME at five percent, with the existing integer rounding. Growth stops at the day's minutes ceiling, then uses the same run cap and longest anchor including this session as the long run. A stored end already at or above the stop holds; it never shrinks. A missing context or an edited non-Time WORK end repeats. `Increase.capped` reports clipping by the run cap, not by the session stop. Only an actual increase records the rotation.

Weekly volume tracks performance in Doherty 2020 and Fokkema 2020 (moderate, observational); neither sets this growth rule. Nielsen 2014's greater-than-30-percent weekly distance rise had HR 1.59 (0.96–2.66) for distance-related injuries and a null overall result. Damsted 2019's early 20–60-percent rise versus under 20 percent had risk difference 22.6 percent (0.9–44.3), absent by day 56. These weak findings guide the practice limits; the five-percent step is A16's existing practice value (R09). No limit is called safe or injury-preventing.

`easyRunSeconds(minutes, strength, long)` exposes R10's unchanged formula: max(20 minutes, minutes minus 5 minutes of warm-up minus 25 minutes when a strength block is present), limited to 75 percent of the long run rounded down to a minute. No source sets that minimum, reserve or ratio; they are practice. Creation passes the capped stored long run. Finish must pass the day's minutes, whether it carries a strength block, and the current long run, so the 75-percent ceiling follows long-run growth (R10 fixed through R11). For example, a 1980-second long run allows floor(1485/60)×60 = 1440 seconds; a 2079-second long run allows floor(1559/60)×60 = 1500 seconds.

Two lead defaults are explicit practice. First, Hard or Pain holds easy-run growth even on full success: Hard is above the easy band, and Pain blocks the other ruled progressions. Second, a lift-first, same-visit Both lift day's attached run receives no `EasyRunContext` and repeats. D15's run uses the time left after fitting lifts; growth would squeeze that work. 4b-ii should pass the context only to CARDIO-kind easy days in run plans, cardio-first Both and separate-days Both. `CardioJudge.judge` currently passes the opening as the block; during a taper it is scaled, so growing from it would store a shorter block. The long run already has this issue. 4b-ii must grow from the stored block or skip growth during a taper. Neither wiring nor taper-write protection is built here.

Each easy day takes at most one configured step per rotation, then the 110-percent run cap applies. At the plan's stated frequency a rotation is a week. The supplied, already-run 2100-plan simulation reports at most 5.04 percent easy-plus-long growth per rotation, including New runners, and 21.6 percent growth over four rotations. After 52 rotations, OLD leaves 895 plans with an easy day below its stop and NEW leaves zero. Just cardio, Some, three 60-minute days with a 15-minute anchor starts at 960/960/960 seconds; OLD repeats forever, while NEW reaches 1800/1800/3300 at increases 13/13/26. This PR adds a 52-rotation invariant over the requested goals, frequencies, minutes, experience and time anchors; it has not been run locally. The separate history-based novice two-week 30-percent rule (R52, exploratory) stays planned for 4b-iii.

`CardioRule.sprintStepCycle` is now `stepCycle`, shared by sprint days and easy runs. Sprint behavior is unchanged. The JSON DTO property and key remain exactly `sprintStepCycle` from v0.11. EASY with cycle 3 decodes and re-encodes byte for byte; THRESHOLD with cycle 0 still rejects. No Room, backup, SavedStateHandle or watch wire shape changes.

**Deeper tapers (row 15, R32/R33; weak).** Up to 10K and Shuttle use ×0.5 in the existing ten-day band. The half uses ×0.6 at 14–8 days and ×0.45 at 7–0 days; longer distances use ×0.75 at 21–15 days, ×0.55 at 14–8 and ×0.4 at 7–0. Average cuts are 50 percent, (40+55)/2 = 47.5 percent and (25+45+60)/3 = 43.3 percent. Bosquet 2007 and Wang 2023 support the 41–60-percent reduction band weakly for these users; the half split is practice with no half-specific trial. Whether Bosquet's percentage means the taper mean or final week is unverified; these factors assume the mean. Lengths (R31), WORK-only scaling, hard flags, pace and intensity (R34), date boundaries, and `Taper.applied` rounding remain unchanged.

### Changed expectations and arithmetic

Every changed behavioral expectation cites `audit 2026-11-13, row 3` or `row 15` in its test. Round retention remains ceil(n×factor−1e-9); a single end uses Math.round(end×factor).

| Test and value | Old → new | Arithmetic or reason |
|---|---|---|
| TaperTest case 19 half marathon, days 988/995/986/993 | 0.7/0.5/0.7/0.5 → 0.6/0.45/0.6/0.45 | Goal day 1000 minus each day = 12/5/14/7 days out; row 15. |
| Same, applied single ends and literal factors | (0.7,12600)/(0.5,9000) → (0.6,10800)/(0.45,8100) | 18000×0.6 = 10800; 18000×0.45 = 8100; row 15. |
| TaperTest case 19 through cardioAtStart | 12600/9000 → 10800/8100 | Same products at 12/5 days out; row 15. |
| TaperTest case 19 hard flags and aims stay unchanged | (0.7,5)/(0.5,3) → (0.6,4)/(0.45,3) | ceil(6×0.6)=4 and ceil(6×0.45)=3; first block keeps 8 rather than 10 total sets, with 4 rather than 5 WORK reps; second stays at 6 total sets and 3 WORK reps; row 15. |
| TaperTest case 19 marathon bands at 980/990/997 | 0.8/0.6/0.5 → 0.75/0.55/0.4 | 20/10/3 days out; row 15. |
| Same, 5K and PACER at day 990 | 0.6/0.6 → 0.5/0.5 | 10 days out; row 15. |
| TaperTest upper distance boundaries, 10K/10001 m/half | 0.6/0.7/0.7 → 0.5/0.6/0.6 | 10/14/14 days out; distance edges unchanged; row 15. |
| Same, longer distance at 0/7/8/14/15/21 days out | 0.5/0.5/0.6/0.6/0.8/0.8 → 0.4/0.4/0.55/0.55/0.75/0.75 | Existing date edges select the deeper factors; row 15. |
| TaperTest case 19 taper precedes cap, cap off | 12600 → 10800 | 18000×0.6=10800; cap-on 8800 stays because 10800>8800; row 15. |
| PlannerRunTest case 19 stored long run openings | 12600/9000 → 10800/8100 | TODAY is 12 days out; TODAY+7 is 5; same 18000 products; row 15. |
| CardioOpeningTest case 19, road first/road last/rower | 12600/9000/12600 → 10800/8100/10800 | Half at TODAY+12 or TODAY+5; same 18000 products; row 15. |
| PreStartOpeningTest case 19, preview/watch | 42:00/Time(2520) → 36:00/Time(2160) | November 21 minus November 9 = 12 days; 3600×0.6=2160=36 minutes; row 15. |
| DayViewModelCardioExecutionTest aTaperedOpeningShowsBeforeStart, block/detail | Time(2520)/42:00 → Time(2160)/36:00 | Today+12; 3600×0.6=2160=36 minutes; row 15. |
| ProgressionLadderTest renamed not-reported test, level 4 | LevelTo(5), block/rule level 5 → Repeat, original block/rule level 4 | No word gives 4+0=4; row 3. |
| Same, added level-12 no-word coverage | LadderDone with cleared level → Repeat with original block/rule level 12 | No word gives 12+0=12; row 3. |
| ProgressionLadderTest repeats preserve rounds, last assertion | finish(12, null) → finish(12) with default Easy; LadderDone unchanged | Only finished Easy completes level 12; the null case moves to the renamed test; row 3. |
| ProgressionRunRangeTest name | ruled easy repeats → ruled easy repeats without an easy run context | Repeat expectation stays unchanged; context is opt-in. |
| Sprint and JSON fixtures naming the step marker | sprintStepCycle → stepCycle in Kotlin only | Cycle values remain 0, 1, 3, 4 and 5 as applicable; invalid −1 and invalid session checks remain. JSON bytes keep sprintStepCycle. |

The requested scan of all three modules' tests for `Taper`, `byEpochDay`, `dateEpochDay` and ladder/null-effort cases found no additional behavioral expectation changes beyond the spec's list. Literal-factor tests `single set holds its pace` and `round scaling tolerates floating point edges and keeps trailing easy sets` remain unchanged. Both cap-order checks remain 8800: limit(8050,100)=8800, and 10800 exceeds it. Ladder openings still bypass taper/cap, sprint goals still have no taper, and out-of-band dates still return null. Other dated fixtures test storage, goal validation, date UI, phase or goal reasons rather than tapered openings; their expectations stay unchanged. The repository Finish fixture with a ladder rule still returns no judgment because the live guard is unchanged.

### Files, coverage and scope

Production files: domain `Progression.kt`, new `EasyRunContext.kt`, `CardioRule.kt`, `SprintRules.kt`, `Taper.kt`, planner `RunSessions.kt` and `RunLayout.kt`; data `serialization/CardioRuleJson.kt` only. Tests: domain `ProgressionEasyRunTest.kt` (new), `ProgressionLadderTest.kt`, `ProgressionRunRangeTest.kt`, `ProgressionSprintTest.kt`, `TaperTest.kt`, `PlannerRunTest.kt`, `PlannerRunProgressTest.kt`, `PlannerRunInvariantsTest.kt`, `PlannerSprintProgressTest.kt`; data `CardioOpeningTest.kt` and `CardioRuleJsonTest.kt`; app `PreStartOpeningTest.kt` and `DayViewModelCardioExecutionTest.kt`. Documentation: this revision and spec §11.2 cases 19 and 34.

Coverage adds the six requested easy-run unit cases, the current-long-run stop example, the Just cardio growth sequence, the 52-rotation grid, a creation-stop invariant, a never-rated runner and EASY JSON round-trip coverage. It also pins the specified edited non-Time fallback and checks Good/no-word easy-run growth, missed aims, cap-off growth, a larger history anchor and negative EASY cycles within that scope. The only changed session shape in existing expectations is case 19's first six-round taper, five kept rounds → four. There are no additional product changes beyond spec A. The strings.xml scan found none of these taper numbers or copy, so no resources changed. CI results remain pending; no network, build, compilation or test command was used.

## 52. Revision 43 (sprint and hybrid audit fixes, 2026-11-16)

This revision implements [audit 2026-11-13, decision rows 6, 7 and 14](wizard-evidence-audit-2026-11-13/decisions.md), after revision 42 (spec A, #600, 47ad0182). It supersedes §2.1's Sprint three-day cell, §3.1.3's “8 to 12 min rest,” revision 31's “sprint endurance 480 seconds” and “opening at 3 × 100 m with 480-second rests,” revision 32's “sprint endurance 20 m (300 to 600 m),” and revision 28's “Both defaults to ... separate days from four days.” No STRENGTH_TRACKER_SPEC.md §11.2 case covers these layouts, ranges or defaults; none changes. No app copy changes.

**Three-day sprint row (row 6, S02; precaution, weak).** The decision is “3-day sprint row: A + plyos, Lower, R, B + Nordic, R (no max-velocity day right after Nordic + heavy legs).” The new row puts Lower after Sprint A and a rest entry before Sprint B; Nordic follows Sprint B's block. Within a rotation, a maximal sprint never directly follows Lower or knee-flexion work. The other rows stay as authored. S23's order stays sprints first, then lifts, then Nordic. Balance remains only on lower/upper days; a 65+ three-day sprinter still has one balance day.

The decision labels this a “Precaution: the recovery studies used 3 to 5 times the app's Nordic dose.” Alonso-Fernandez 2024 (PMID 34748450; 24 men) found high-speed horizontal force in a 30 m sprint reduced for up to 48 hours after 4 × 10 Nordics and advised at least 72 hours before demanding running. Coratella 2025 (PMID 40280182; 13 trained men) found eccentric strength reduced through day 3 after a first 24-rep bout. Haugen 2019 Table 3 (PMC6872694) places strength the day after sprinting. The app opens at 2 × 4–6, or 8–12 reps, a third to a fifth of those trial doses. The grade is weak (small trials), consistent with practice; the schedule is a precaution, with no claim that it prevents injury. See [the sprint recovery notes](wizard-evidence-audit-2026-11-13/notes/sprint-plyo-nordic.md).

**Sprint endurance (row 7, S10; practice).** The decision is “Sprint-endurance rest at least 600 s, flat; the intro opens at 2 × 100 m.” Haugen 2019's untrained example is “2–3 × 100-m sprints with 10-min recovery”; 480 seconds is the elite Table 2 floor. The decision keeps recovery flat, with no scaling by rep time. The shared range is 2–4 reps of 100–150 m, adding 20 m of total WORK per step: 200, 220, ... 600 m, top (600−200)/20=20. The first six shapes are 100/100, 110/110, 120/120, 130/130, 140/140, 100/100/100. Every RECOVERY is Time(600).

The lead keeps a per-session non-intro opening: acceleration 160 m at step 12; sprint endurance 300 m at step 5 (3 × 100 m). Other sessions start at step 0. Intro opens and stays at the low end when its countdown ends: sprint endurance is now 2 × 100 m. The unchanged layoff restart also opens step 0, so returning users restart at 2 × 100 m instead of 3 × 100 m. No production path creates a sprint or Both plan, or writes a sprint rule, before PR 7; no wire migration is needed for changed step meanings.

Revision 32's planned rise ≤ max(15%,20 m) still holds by construction: two-day 200/400 m plans add at most 30 m from a minimum 230 m, 30/230=13.0%. The reachable-state enumeration remains in the test suite. Section 6 of spec B supplies the previously run simulator oracle; it was not rerun here. Non-intro 400 m 16-rotation blocks and planned meters remain; 52-rotation trial counts are 8/10 for 100 m and 8/10 for 400 m, intro/non-intro. Without trials C reaches 600 m at rotation 44/31. The existing bound, restart, trial-admission and layoff rules remain.

Each endurance recovery adds 120 seconds. The ten-minute build-up replaces the separate five-minute warm-up; at 0.36 s/m, intro se0=600+2×36+600=1272 s and non-intro se5=600+3×36+2×600=1908 s, formerly 1668 s for 3 × 100 m on 480 s rests. At 30 minutes a non-intro endurance block alone exceeds 1800 seconds by 108; the planner reports OverBudget. Revision 31 still forbids shortening sprint blocks. Lift fitting can remove Nordic or reduce main sets; it cannot make the sprint itself fit.

**Hybrid strength floor and default (row 14, C04 strong; C03 weak-contested, three-day threshold practice).** The decision is “No layout leaves a lift-primary plan with fewer than two strength days a week; trained Strength users default to separate days when days allow.” WHO 2020 and ACSM (Garber 2011) advise two or more strength days (strong); Blagrove 2018 describes one session a week as maintenance. Schumann 2022 (PMC8891239; 43 studies) found no maximal-strength or size cost of concurrent training, but explosive strength fell within the same session (≤20 min apart, SMD −0.31), not at ≥3 hours. Petré 2021 (PMC8053170, not re-read in the audit) found a lower-body 1RM penalty for trained lifters in same-session designs (ES −0.35). The C03 grade is weak-contested. See [concurrent timing notes](wizard-evidence-audit-2026-11-13/notes/concurrent-timing-warmup.md).

SetupAnswers now resolves Together from all relevant answers. Lift first at two days always uses SAME_VISIT, overriding even explicit SEPARATE_DAYS: one lift + one run becomes two strength days with attached runs. PR 7 must not offer Separate days there. Other explicit answers stay authoritative. With no explicit answer, days ≥4 stay separate; lift first, Strength, A year or more at three days now defaults to separate (two lift + one run, since ceil(3/2)=2). The three-day threshold is practice. General/Muscle, less experienced lifters and cardio-first users keep their defaults. At one day the primary side's one-day layout remains. Cardio-first same visit retains three CARDIO days plus Rest at three days, with strength blocks on two days; the three STRENGTH-day same-visit shape applies to lift first.

### Changed expectations and arithmetic

Every changed existing expectation has an `audit 2026-11-13, row N` comment. Main set=15+5×3+180=210 s; accessory set=129 s; Nordic set=15+4×3+60=87 s. The following ledger includes fixture and shape changes; unchanged values shown beside them are retained deliberately.

| Test or fixture | Old → new | Arithmetic and decision row |
|---|---|---|
| PlannerSprintTest `se0`; new `se5` | 3 × 100 m, 480 s rests → `se0` 2 × 100 m, 600 s; `se5` 3 × 100 m, 600 s | Row 7: intro WORK 200 m; non-intro WORK 200+5×20=300 m. |
| Three-day 100 m kinds and Lower title | CARDIO, REST, STRENGTH, CARDIO, REST; C Lower → CARDIO, STRENGTH, REST, CARDIO, REST; B Lower | Row 6: A + plyos, Lower, R, B + Nordic, R; three training days. |
| Same, lift placement | C squat/rdl/bss/Nordic; D empty → B squat/rdl/bss; D Nordic | Row 6: three Lower lifts move C→B; one Nordic slot moves C→D. Sessions remain A acceleration and D max velocity. |
| Same, seconds | [1289,0,1878,1091,0] → [1289,1704,0,1265,0] | Row 6: A=794+495=1289; B=300+3×210+6×129=1704; D=1091+2×87=1265. |
| `the other events keep the table`, 200/400 m opening block | `se0` 3 × 100 m at 480 s → 2 × 100 m at 600 s | Row 7: 600+72+600=1272; two-day C=1272+9×210+2×87=3336≤4500. Intro rule remains 3. |
| Two-day 200/400 m 60-minute ladder test name and fixture | Intro, step one → non-intro, step three; `sprintedRecently` false → true | Row 7: use the retained 300 m opening at step 5 to test the larger recovery budget. |
| Same, 75-minute reference seconds | [2990,0,3732] → [2990,0,3972] | Row 7: A=1091+495+3×210+6×129=2990; C=1908+9×210+2×87=3972. No reference reasons. |
| Same, 60-minute reason and seconds | ShortSession(C,1), [2990,0,3558] → ShortSession(C,3), [2990,0,3168] | Row 7: step 1 drops Nordic, 3972−174=3798>3600; step 2 has no secondary slots to remove; step 3=1908+6×210=3168≤3600. A and rest B stay unchanged. |
| Same, C sets | [3,3,3] → [2,2,2] | Row 7: three mains, two straight sets each, six total. Bench/ohp/row, roles, cardio block/rule and sessions remain. Added intro coverage keeps all lifts with no reasons at C=1272+1890+174=3336. |
| `the intro opens every range at its low end`, non-intro 200 m D | Previously unasserted → `se5`, CardioRule SPRINT_ENDURANCE steps=5 | Row 7: 200+5×20=300 m, three 100 m reps. A stays max velocity step 0. |
| `plyometrics and Nordic open at the owner doses`, Nordic/fallback lookup | C → D | Row 6: two Nordic sets remain Range(4,6,4); two seated-curl fallback sets remain Range(8,12,8). Selection reads knee-flexion candidates on D. |
| Thirty-minute recent three-day reasons | ShortSession(A,6), OverBudget(A,193), ShortSession(C,1) → ShortSession(A,6), OverBudget(A,193) | Row 6: A=1498+495=1993, excess 193; B=1704 and D=1265≤1800, so Lower no longer needs fitting. |
| `sprint ranges and blocks`, tops | [20,7,15,12] → [20,7,20,12] | Row 7: sprint-endurance top=(4×150−2×100)/20=20. |
| Same, sprint-endurance block and planned seconds | 3 × 100 m at step 0; [794,1498,1091,1668,516] → 2 × 100 m at step 0; [794,1498,1091,1272,516] | Row 7: 600+2×36+600=1272; added step-5 `se5` check is 600+3×36+2×600=1908. |
| PlannerSprintInvariantsTest three-day kinds and rule steps | cardio/rest/strength/cardio/rest → cardio/strength/rest/cardio/rest; non-intro endurance 0 → 5 | Rows 6/7: same three-day order; 200+5×20=300 m. Intro 0, acceleration 12 and other steps 0 remain; session lists remain. |
| PlannerSprintProgressTest 400 m 16-rotation C steps | [0,0,1,1,2,2,2,3,3,4,4,4,5,5,6,6] → [5,5,6,6,7,7,7,8,8,9,9,9,10,10,11,11] | Row 7: add five steps to preserve 300 m and every later non-intro total. A steps, blocks and planned meters remain. |
| Both-top and out-of-order progression tests, C top | 15 → 20 | Row 7: (600−200)/20=20; A top remains 7. |
| Both-top test, first C 600 m rotation | Intro 34 → 44; non-intro 31 → 31 | Row 7: five extra intro steps × two rotations=10; A still reaches 100 m at 18/15. |
| Both-top test, trial simulation length and final C step | 52 rotations and C=15 → 60 rotations and C=20 | Row 7: 200 m intro is at 580 m (step 19) at 52 rotations and reaches 600 at 56; 60 covers both events. A remains 7. |
| 52-rotation trial counts, 400 m intro | 9 → 8 | Row 7: 400≤planned×1.15 needs planned≥348 m; intro endurance starts 100 m lower. 100 m intro/non-intro and 400 m non-intro remain 8/10/10. |
| Intro ending on trial rotation five, C normal WORK | 300 → 200 m | Row 7: intro stays at low end, 2×100=200 m. |
| Return on trial rotation twenty, C reset WORK | 300 → 200 m | Row 7: reset step 0=2×100=200 m. A reset 30 m and two restarts remain. |
| Pending B restart after A intro, trial-rotation C reset WORK | 300 → 200 m | Row 7: A 30+C 200=230≤planned 330; rotation-0 C=300, planned=330, zero trials and two restarts remain. |
| ProgressionSprintTest 380→400 m fixture | steps=4 → steps=9 | Row 7: 200+9×20=380; next 400. Reps stay 130/130/120 → 100/100/100/100. |
| Existing restart and tempo-substitution fixtures | steps=4 retained; normal endurance block 380 → 280 m through shared builder | Row 7: 200+4×20=280, reps 140/140. Restart low block becomes 200 m; tempo substitute remains 600 m. No fixture re-pin. |
| PlannerBothTest General/New defaults | BothAnswers.together(1/3/4) → SetupAnswers.together() for 1/3/4 days | Row 14: unchanged results same/same/separate; four days split 2+2. |
| PlannerRunInvariantsTest Both policy checks and 8208-plan grid | BothAnswers.together(days) → SetupAnswers.together(); lift-first explicit separate at two days now uses same visit | Row 14: 1+1 split → two strength days. Grid count stays 8208. |

Both 100 m progression pins, skip-A at trial rotation ten (C normal 380 m), repeat-A after its trial (A normal 70 m and B tempo), long-trial admission at 350 but not 340 planned meters, B-before-A/off-suggestion trial blocking and plan-wide layoff checks remain unchanged. Non-intro long-event planned meters still equal 330+10×A steps when C is at its opening. Shared sprintBlock expectations follow the new range without copying its arithmetic into a second builder. No additional existing expectation beyond spec B §4 was re-pinned.

### Coverage and files changed

New named tests: PlannerSprintTest `200 and 400 m intro at two days fit sixty minutes without shortening`; ProgressionSprintTest `sprint endurance rests ten minutes between reps` and `sprint endurance crosses 280 to 300 meters`; PlannerBothTest `trained Strength lifting first defaults to separate days at three days`, `lifting first never splits two days into one strength day`, and `Both defaults and the strength floor hold over 324 plans`. The 324 cases are 3 lift goals × 3 lift experiences × 6 day answers × 2 primary sides × 3 Together answers (null/same/separate), using FIVE_TO_TEN_K, SOME and known(8050,2900). Existing sprint invariant grids gain the within-rotation S02 check and S10 opening/rest checks. The 380→400 transition stays covered at step 9; all 21 endurance steps get recovery coverage.

Production files under domain: planner/SprintLayout.kt, planner/SetupAnswers.kt, planner/BothLayout.kt, progression/RunRange.kt. Tests under domain: planner/PlannerSprintTest.kt, planner/PlannerSprintInvariantsTest.kt, planner/PlannerSprintProgressTest.kt, planner/PlannerBothTest.kt, planner/PlannerRunInvariantsTest.kt, progression/ProgressionSprintTest.kt. Documentation: this revision. Domain stays pure Kotlin; no data, app, Room, backup, wire or resources change. No network, compilation, builds, Gradle, tests or subagents were used; CI verification of :domain unit tests remains pending.

### Out of scope and PR 7 follow-up

1. The rotation wrap remains an owner question: two-day 200/400 m Sprint B + Nordic still precedes next rotation's max-velocity Sprint A without a rest entry; four-day 200/400 m Lower + Nordic does too. The audit kept S01 as practice and flagged the wrap. Row 6 changes only the three-day row; the S02 invariant deliberately stops at the rotation boundary.
2. Cardio-first separate days at two to three days and Both with a sprint sub-goal at one and three days still have one lifting day. Row 14 covers lift-first run hybrids; these are owner questions. PR 7 copy must not imply two strength days for them.
3. A New runner on Both, Strength, A year or more, at three days now gets one ladder session a week on the separate-days default; the four-day separate default already gave two. Recorded without another rule change.

These rules carry their stated evidence grades or practice labels. No copy claims that Nordic halves hamstring injuries or that any limit prevents injury.


## 53. Revision 44 (sprint week A: layouts, ladder flags, Both floor, careful intro, 2026-11-16)

This revision implements spec A of the approved [sprint week design, revision 4](sprint-week-2026-11-17/design.md): §2's layouts and Both floor, §3.2's spacing functions, §3.4's fitting flags and full-dose minutes, and §5's careful intro. [Research and evidence grades](sprint-week-2026-11-17/research.md) are copied with the design. The simulator sources and results remain outside the repository; design §3.4 summarizes the budget results and §4.3 summarizes the allowance simulator. Neither was rerun for this implementation. The weekly allowance, structural credit rule and Finish replay land in revision 45 (spec B).

**High/low rotations [P].** The rotations are A1 R; HA R HB R; HA U R HB R; HA U R HB U R; HA U R HB U T; and HA U R HB U T U. Maximal IDs remain A/C at two days and A/D at three through six; tempo remains F. The cardio block leads, followed by the existing 65+ balance block, plyometrics, one lower MAIN, lower accessories, upper MAINs and knee flexion last. Upper entries without resolved upper MAINs become Rest with the same ID, before adding balance. The old "Lower" title no longer occurs on sprint layouts.

Both high days carry practice plyometric doses: A has 3×15 pogo contacts + 20 A-skip contacts = 65; B has 2×15 + 20 = 50. The 40–80-contact window, 65/50 split, Nordic 2×4–6 dose and all these placement choices are practice. The max-velocity day carries the hinge MAIN and Nordic or its knee-flexion fallback. The other high day carries the squat MAIN and one single-leg accessory. A1 retains the squat, hinge accessory and single-leg prescription, followed by Rest, and has no knee-flexion slot. All lift doses still come from `liftSlot`.

High/low as one continuous visit follows Francis [P]; Johnston 2016/2017 tested same-day sessions two hours apart [weak], not one continuous session. Haugen's separate-day examples are practice. Sprint-first order and grouping eccentric hamstring work after max velocity are practice choices informed by acute fatigue/recovery findings [weak]. Thomas 2018 used 50 heavy squat reps and 450 maximal sprint meters; Alonso-Fernandez used 40 Nordics, above this plan's 8–12. These data do not establish this layout's injury effects. The rotation guarantees concern entry order, including the wrap, and do not enforce a user's calendar. Tempo remains revision 31's one day at five and six days [P; owner Q1]; no new tempo appears at three or four days.

**Both floor and fallback [P].** Both + sprint requires a resolved squat or hinge MAIN for non-careful users, or a resolved squat MAIN for the careful group. It checks equipment, exclusions and TIMED resolution through `resolveMains`, rather than reading the raw picks. From two days up, at least two entries retain a MAIN after fitting. WHO's two-or-more-strength-days recommendation is strong; this gate, fallback and definition of a lifting day are practice choices. A hinge day always falls back to a squat MAIN. A squat day falls back to a hinge MAIN only for non-careful Both. Sprint and the careful group keep the single-leg accessory without that fallback. The non-careful Both exception can therefore have two heavy hinges per rotation; it does not carry a one-hinge promise. A one-day Both hinge fallback skips its hinge accessory so it has one hinge slot, not two. One day cannot satisfy a two-day floor.

**Spacing advice [P].** `spacingDays` returns three calendar days during the first weeks or for the careful group, otherwise two. `daysSince` returns the calendar-day difference, or null without an earlier maximal date. `sprintFirstWeeks` activates for a maximal day's remaining intro, an actual Nordic slot below three exposures, or a New lifter's maximal-day squat/hinge MAIN below three exposures. The three-exposure cutoff and two/three-day advice are practice informed by weak recovery studies; the app cannot measure hours. PR 7 supplies the Start copy and calls these functions.

**Fitting and minutes [P].** Sprint keeps plyometric slots and every knee-flexion fallback at step 1; Both keeps knee flexion there. Steps 2–6 stay unchanged. Step 7 removes plyometrics only when its flag is on; step 8 removes knee flexion only when its flag is on. Disabled steps keep their numbers and change nothing. Exhaustion reports step 6 with both flags off, otherwise step 8, plus every excess second. The never-empty rule remains. The shared minutes answers are 30/45/60/75/90. `Planner.fullDoseMinutes` returns the smallest answer at or above the chosen minutes without a ladder step of 1 or more and without OverBudget; step-0 weekly floor stops do not count. It returns null if even 90 minutes trims. No current reference sprint grid does: the supplied budget results report zero trims among 96 plans per branch at 90 minutes. PR 7 owns the new reason and Longer-sessions copy.

**Careful group.** `fortyOrOlder || olderAdult || hamstringStrain` always opens with the intro, including after recent all-out sprinting. Green 2020 grades older age and previous hamstring strain as risk factors strong-moderate, mostly from team sports. The age-40 threshold, 65+ grouping, 12-month strain window, first-three-exposure cutoff and every dose/cutoff here are practice, not thresholds established by Green. Careful plans write six counted intro sessions on every sprint day, including tempo; other intro plans retain three. Layoff restarts use six when `SprintContext.careful` is true and three by default. A successful restart counts the first session (6→5); pain leaves six. `CardioRule` now accepts 0..6. Existing callers retain `careful = false` until spec B/PR 7 wiring. PR 7 owns the "about nine-tenths effort" copy; no effort cap is added here.

**Compatibility and supersession.** This supersedes §2.1's Sprint column; §3.1.3's Sessions and Intro rows (two high days, plyos on both, hinge + Nordic on max velocity, careful six-session intro); §3.1.4's sprint row; revision 31's upper-placement sentence; and revision 43's out-of-scope items 1 and 2 for sprint layouts. The wrap now ends on Rest, tempo, or an Upper entry without lower-body lifts, and Both + sprint has two or more lifting days from two days up. Run-hybrid behavior is unchanged. Sprint progression, ranges, trials, restarts with default context, and the per-rotation bound remain; revision 45 adds the weekly allowance and Finish replay. No production path creates these sprint plans before PR 7, so there is no migration. No Room, backup, SavedStateHandle, Parcelable or watch wire change; existing v0.11 CardioRule JSON bytes stay intact. No copy or strings.xml change. STRENGTH_TRACKER_SPEC.md §11.2 is unchanged: none of its cases covers sprint layouts, fitting, contacts or intro length. Cases 25 and 36 still use the six-step ladder with both flags off.

### Changed expectations and arithmetic

Each changed existing expectation has a `// sprint week design §N: ...` comment. Main set=15+5×3+180=210 s; accessory=15+8×3+90=129 s; Nordic=15+4×3+60=87 s; pogo=120 s; A-skip=135 s. A's plyos=3×120+135=495 s; B's=2×120+135=375 s. Cardio constants are acc0=794, acc5=1498, maxv0=1091, se0=1272, se5=1908 and tempo0=516 (816 including its separate warm-up).

| Test | Old → new | Source |
|---|---|---|
| `PlannerSprintTest > a 100 m plan at two days matches the table` | IDs A,B,C → A,B,C,D; kinds [CARDIO,REST,CARDIO] → [CARDIO,REST,CARDIO,REST]. A ids [pogo_hop,a_skip,bb_back_squat,rdl,bss] → [pogo_hop,a_skip,bb_back_squat,bss,bb_bench], roles → [PLYO,PLYO,MAIN,ACCESSORY,MAIN]. C ids [bb_bench,ohp,bb_row,nordic] → [pogo_hop,a_skip,trap_dl,ohp,bb_row,nordic], roles [PLYO,PLYO,MAIN,MAIN,MAIN,ISOLATION], plyo sets [2,1]. Seconds [2693,0,3155] → [2936,0,3530,0]. Sessions, blocks, rules, no reasons unchanged | §2.2; A=794+495+3×210+3×129+3×210=2936; C=1091+375+9×210+2×87=3530 (budget_results "Reference values", 2 d 100 m intro 60) |
| `... at three days matches the table` | B "Lower" [bb_back_squat,rdl,bss] → "Upper" [bb_bench,ohp,bb_row]; A [pogo_hop,a_skip] → [pogo_hop,a_skip,bb_back_squat,bss]; D [nordic] → [pogo_hop,a_skip,trap_dl,nordic]. Seconds [1289,1704,0,1265,0] → [2306,2190,0,2270,0]. Kinds unchanged | A=794+495+630+387=2306; B=300+9×210=2190; D=1091+375+630+174=2270 (budget_results 3 d 100 m intro 60) |
| `... at four days matches the table` | IDs A-E → A-F; kinds → [CARDIO,STRENGTH,REST,CARDIO,STRENGTH,REST]; B and E [bb_bench,ohp,bb_row] (were B Lower, E Lower + Nordic). Seconds [1289,1704,0,1091,1878] → [2306,2190,0,2270,2190,0] | budget_results 4 d 100 m intro 60 |
| `the other events keep the table` | Lift lists compared within each event group: the reference is `sprint(M100)` for 40 yd/60 m/100 m and `sprint(M200)` for 200/400 m (the hinge day swaps sides). Kinds compared against M100 for every event. The 200/400 comment "two-day C=1272+9*210+2*87=3336" → two-day A=3020, C=3924. Still no reasons and every day ≤ 4500 at 75 min | §2.2; A=1091+495+630+630+174=3020; C=1272+375+630+387+630+630=3924 (budget_results 2 d 200/400 intro 75) |
| `200 and 400 m non-intro at two days fit sixty minutes at ladder step three` | Reference minutes 75 → 90; reference seconds [2990,0,3972] → [3020,0,4560,0]. 60 min: reasons stay [ShortSession(C,3)]; seconds [2990,0,3168] → [3020,0,3543,0]. Unchanged days: A, B → A, B, D. C ids [bb_bench,ohp,bb_row] → [pogo_hop,a_skip,bb_back_squat,ohp,bb_row], roles [PLYO,PLYO,MAIN,MAIN,MAIN], sets [2,2,2] → [2,1,2,2,2] | C full=1908+375+630+387+630+630=4560 (>4500, so 75 min trims); step 1 drops BSS: 4173; step 3: 1908+375+3×2×210=3543 (budget_results 2 d 200/400 non-intro 60/90) |
| `200 and 400 m intro at two days fit sixty minutes without shortening` | Rename to `200 and 400 m intro at two days drop only the single-leg accessory at sixty minutes`. Reasons [] → [ShortSession(C,1)]; seconds [2990,0,3336] → [3020,0,3537,0]; C ids → [pogo_hop,a_skip,bb_back_squat,ohp,bb_row], sets [3,3,3,2] → [2,1,3,3,3]; C block se0 unchanged | C full 3924 > 3600; step 1 drops BSS: 1272+375+3×630=3537 (budget_results 2 d 200/400 intro 60) |
| `five and six days append tempo then upper lifts` | `four.program.days == five.program.days.take(5)` → `four.program.days.take(5) == five.program.days.take(5)` (four days now has a trailing rest F). F tempo 816, G Upper 2190, `five.take(6) == six.take(6)` unchanged | §2.2 rotations |
| `the intro opens every range at its low end` | A seconds 1993 → 3010. Strain fixture `CardioRule(null, ACCELERATION, intro = 3)` → `intro = 6` (a hamstring strain is careful) | A=1498+495+630+387=3010 (budget_results 3 d 100 m non-intro 60); §5 careful = strain or 65+ or 40+ |
| `plyometrics and Nordic open at the owner doses` | Read A's plyos as `exercises.filter { rule.role == PLYO }` (A now holds lifts). Nordic and seated-curl fallback stay `D.exercises.last()` | §2.1 |
| `thirty minutes fits through the ladder or reports the excess` | One day: reasons [ShortSession(A,1)] → [ShortSession(A,3)], ids [bb_back_squat] → [pogo_hop,a_skip,bb_back_squat], seconds 1424 → 1709; program IDs [A,B]. Recent three-day: [ShortSession(A,6), OverBudget(A,193)] → [ShortSession(A,7), ShortSession(B,3), ShortSession(D,7)]; A ids [pogo_hop,a_skip] → [bb_back_squat] at 1762; add D ids [trap_dl,nordic] at 1529 and B 1560 | One day: step 1 keeps the plyos and the squat: 794+495+630=1919 > 1800; step 3: 794+495+2×210=1709. Three-day A: 3010 → step 1 2623 → step 3 2413 → step 4 2317 → step 5 2257 → step 7 1498+264=1762; D: 2270 → step 3 2060 → step 4 1964 → step 5 1904 → step 7 1091+264+174=1529; B: 300+6×210=1560 (budget_results "3 d 100 m non-intro 30", "1 d 100 m intro 30") |
| `Both with a sprint sub-goal builds the sprint layout with the lift rules` | `day(muscle, "B").exercises.first()` → A's first MAIN slot (bb_back_squat, 3 sets, Range(8,12,8), rir 3 unchanged). `day(muscle, "E").exercises.first()` → D's MAIN is conv_dl. `none { bb_bench }` → bench is the MAIN of B and E at four days; six days B, E and G each hold [bb_bench] | §2.2 Both rows; §2.3 Muscle New lower main 3 × 8-12 at 3 RIR |
| `ProgressionSprintTest > rules reject sprint markers on run days and invalid sprint state` | `CardioRule(null, ACCELERATION, intro = 4)` → `intro = 7` | §5: `intro in 0..6` |
| `PlannerSprintInvariantsTest` `check` | Rewritten per section 7.2: sizes [1,3,5,5,6,7] → [2,4,5,6,6,7]; kinds and sessions per §2.2; rule intro `if (sprintIntro) (if (careful) 6 else 3) else 0` and steps from `sprintIntro` (the 65+ half of the 1800 grid is now careful: intro 6, steps 0); `previous.title != "Lower"` → G1/G2; plyo placement `dayIndex == 0` → maximal days; upper-main check `(days == 2 && C) || (days == 6 && G)` → section 7.2 | §2.2, §3.1, §5 |

The two-day 30-minute Reset fixture now derives ShortSession(A,7), ShortSession(C,8) and OverBudget(C,83); its assertions stay generic and unchanged. A=794+2×264=1322 after step 7; C=1091+3×264=1883 after step 8, excess 1883−1800=83. `sprint ranges and blocks` and every `PlannerSprintProgressTest` assertion remain unchanged. No additional existing test expectation beyond spec A §6 was moved. CI remains pending; this is a source review against the supplied simulator output, not a test-run claim.

### Files and coverage

Production:
- `domain/src/main/kotlin/cloud/trotter/log/strength/domain/planner/SprintLayout.kt`
- `domain/src/main/kotlin/cloud/trotter/log/strength/domain/planner/DoseLadder.kt`
- `domain/src/main/kotlin/cloud/trotter/log/strength/domain/planner/Planner.kt`
- `domain/src/main/kotlin/cloud/trotter/log/strength/domain/planner/SetupAnswers.kt`
- `domain/src/main/kotlin/cloud/trotter/log/strength/domain/planner/SprintAnswers.kt`
- `domain/src/main/kotlin/cloud/trotter/log/strength/domain/progression/SprintRules.kt`
- `domain/src/main/kotlin/cloud/trotter/log/strength/domain/progression/CardioRule.kt`
- `app/src/main/kotlin/cloud/trotter/log/strength/ui/wizard/SetupFlowStateBuilder.kt` (one line)

Tests:
- `domain/src/test/kotlin/cloud/trotter/log/strength/domain/planner/PlannerSprintTest.kt`
- `domain/src/test/kotlin/cloud/trotter/log/strength/domain/planner/PlannerSprintInvariantsTest.kt`
- `domain/src/test/kotlin/cloud/trotter/log/strength/domain/planner/DoseLadderTest.kt`
- `domain/src/test/kotlin/cloud/trotter/log/strength/domain/planner/PlannerTest.kt`
- `domain/src/test/kotlin/cloud/trotter/log/strength/domain/progression/ProgressionSprintTest.kt` (one invalid-bound fixture)
- `domain/src/test/kotlin/cloud/trotter/log/strength/domain/progression/SprintRulesTest.kt` (new)
- `data/src/test/kotlin/cloud/trotter/log/strength/data/CardioRuleJsonTest.kt`

Documentation:
- `docs/briefs/sprint-week-2026-11-17/design.md` (byte-for-byte copy)
- `docs/briefs/sprint-week-2026-11-17/research.md` (byte-for-byte copy)
- `docs/briefs/wizard-design-2026-11-08.md` (revision 44)

New tests cover the full prescription at 90 minutes, Both's resolved lower-main gate and one-day fallback, the flag grid and fixed step numbers, full-dose minutes (four pins plus a 768-case property grid), spacing and first-weeks tiers, careful restart and intro-bound validation, and intro-six JSON bytes. The 1800 + 360 + 648 fitted-plan grids keep their sizes and add cyclic G1/G2, slot ordering G3, max-velocity knee-flexion placement, upper-row placement and the Both floor.

### Judgment calls and remaining work

The public `sprintFirstWeeks(program, newLifter)` helper centralizes design §3.2's caller-derived tiers. This is the judgment call identified by spec A: it uses maximal-day intro, an actual `nordic` slot under three exposures, or a New lifter's high-day squat/hinge MAIN under three exposures. A seated-curl fallback does not activate the Nordic tier. The careful tier remains the separate argument to `spacingDays`.

The resolved-mains gate fixture uses default mains with every bilateral squat candidate excluded. A user-picked excluded squat already fails `resolveMains` with "Unavailable main" before reaching the Both gate, so it cannot exercise design §8.3's originally described gate failure. This clarification is specified in spec A §7.1; production validation order stays unchanged.

No additional product behavior was added. Coverage also checks two versus three Nordic exposures, `SprintContext.of` forwarding `careful`, and an older user's empty Upper entry becoming Rest without a balance-only day; these exercise the requested boundaries. The Nordic-only step-7 no-op is checked through the observable step-8 stop at a 2056-second budget and unchanged retained slots, without adding a ladder inspection API.

CI results are pending. No compilation, build, Gradle command, test, simulator, network access or subagent was run. The requested CI-green criterion cannot be verified locally under the hard rules. No staging, commit or push was performed.

## 54. Revision 45 (sprint week B: the weekly allowance and the Finish replay, 2026-11-17)

This revision implements [sprint week design revision 4, §4.2–§4.3](sprint-week-2026-11-17/design.md), including its revision 3–4 tables and the accepted Opus replay drift nit. It supersedes §3.1.3's Progression row and revision 32's “Finish wiring remains PR 4b-ii's” for sprint days. The design and research copy that arrived with revision 44 stays unchanged. No STRENGTH_TRACKER_SPEC.md §11.2 case covers the sprint allowance or progression; none changes, including case 31's undo/light-counter fixture. No app copy changes.

**Weekly sprint allowance [P].** The ledger sums the prescribed distance ends of FINISHED hard WORK sets, dated by the local start of the session. GPS totals do not change those meters. Pain sessions count toward exposure; all-skipped sessions, tempo and tempo substitutes do not. The rolling window includes today and the preceding six local dates. With `planned` equal to the sum of every maximal day's current rule-derived block, and `largest` its largest block, the normal cap is `planned + largest`. While a trial remains in the window, retain `planned + max(largest, trialMetres)`. Trial meters include both reps at 37 and 60 m. `weekLeft` is cap minus the prior ledger and can be negative. This is a product limit on exposure relative to the plan [P], informed only by weak team-sport associations (Duhig 2016, Malone 2017). It is not a validated threshold; copy never calls it safe.

Trial admission retains the rotation's rise bound, `rise ≤ max(15%, 20 m)`, and adds two ledger-backed checks: A has not yet completed a maximal session in this rotation, and the trial fits `planned + max(largest, trialMetres) − recent`. A declined trial writes no trial marker. A shortened maximal session consumes A's first-session opportunity; a tempo substitute does not. Without a ledger, the previous admission and progression pins remain unchanged.

**One decision at Start and Finish.** The order is layoff restart, B's tempo after A's completed long trial, admitted trial, then normal. Fit a restart's step-0 maximal block to the allowance; if it does not fit, substitute step-0 tempo. Restarts cannot shorten: acceleration/max velocity/endurance step 0 already have their minimum 4/3/2 reps. For normal maximal work, drop the last WORK set and its preceding RECOVERY until the block fits, stopping at the range's minimum rep count. If that minimum cannot fit, use step-0 tempo (6 × 100 m). Tempo is not maximal and is uncapped. Trial, shortened and substitute outcomes repeat without credit or intro countdown; trial records its rotation. A substitute for a restart writes no restart state.

**Structural credit.** Normal and restart decisions count only after full success without Pain or Hard good-band work, with the ordered WORK set ends actually run equal to those of the decision's own block: the rule's full normal block, or step 0 for a restart. An edited-down normal block earns nothing. Redistributing 8 × 20 m into 4 × 40 m also earns nothing despite equal total meters. An edited-down restart writes steps 0, credits 0 and the full 3-session intro (6 for the careful group), without counting session one. A shortened decision edited back up still earns nothing. This same normal path includes tempo day F, a small extension of the design's maximal-day wording.

**Finish replay and storage.** The replay runs on the real Finish path in `CardioJudge.judge`, called by `TrackerRepository.cardioJudgment` after rows are inserted and when effort or distance is amended. It anchors to Start's local date in `clock.zone` and the family's prior sessions ordered strictly before this session by `(completedAt, id)`; only this plan's identified days enter the sprint ledger. Using `floorDiv(completedAt, 86_400_000)` would select the UTC Finish day: a US evening run is already tomorrow there, and a run across local midnight would lose its Start window. The judged day's stored rule is read before the first write. Amendments use its recorded pre-write rule and rotation count, even if Finish wrapped the rotation.

The existing `increaseApplied` and `newestForDay` gates remain first. A sprint amendment also requires the session's undo rule record and an exact match between its recorded `after` JSON and the day's current rule JSON. Without that record, replay against an already-updated rule could duplicate credit. Later plan sessions, clear-undo, and Undo revoke that replay record. Other days' stored rules can drift through a plan edit or Reset day between Finish and amendment. The structural rule limits a normal/shortened flip to denying credit: a shortened block cannot gain credit when replay now calls for the full block. A plan edit can also flip trial admission and write or drop a rotation's `sprintTrialCycle`; that residual is accepted. Other days' Start values are not snapshotted.

Sprint judgments require an activity in the existing `CardioFamily.RUN` group. A non-running Swap, including a stationary bike with the same prescription, remains a logged cardio session without sprint credit or a sprint rule write, matching a swapped run day. Sprint writes bypass the `opening != cardioToRun` guard and the `held` override, leaving run behavior intact. They write the rule through a query on the existing JSON column and leave both block columns untouched. `sprintMaximalCycle` stores the rotation of each maximal day's last positive-exposure session, including off-suggestion sessions. The domain preserves this marker; Finish writes it. This per-day marker is a judgment call beyond the design: the cursor does not identify off-suggestion runs, session rows have no rotation index, and later sessions clear undo JSON. `CardioUndoDto.rule` records before/after JSON and the pre-wrap rotation on every sprint judgment, including unchanged ones. Older undo rows decode with `rule = null`. Undo restores the rule only while `after` still matches; a changed matching rule makes Finish Undo available. `FullSnapshot` holds raw `CardioSessionEntity` rows, including `progressionUndoJson`; the portable backup mapping omits that field, correcting the design's “Backup carries it as text” statement.

Sprint state writes on both Automatic and Ask plans: allowance, intro and rotation markers cannot depend on a display preference, and sprint rules have no Ask offer. A Step sets `increaseApplied` and is final like a run increase; credits, intro countdown, restart, trial and maximal markers remain revisable while undo exists. These Ask and Step choices are explicit additions to the design. The unit needed to decode setup answers is read before the transaction; the judgment reads only Room. Sprint recipes remain PR 7's work, so the careful recipe input is false until those answers can be stored.

**Guarantees and limits.** G-a: every admitted maximal dose fits the cap in force for its decision; cuts occur only when the full prescription cannot fit, and substitution only when its minimum reps cannot fit. G-b: the pre-decision window is within the current cap except for two named cases: a restart lowering the plan while recent post-break work remains (130 m against 110 m, B on day 24), and a manual override above the decision (410 m against 380 m, B on day 11). The next decisions shorten or substitute as the window clears. The plan never prescribes maximal exposure over the cap in force; actual exposure can exceed the current cap for up to seven days after a restart or an edit. The allowance does not enforce calendar spacing, lifting frequency, or work outside the app. A one-day plan run Monday and Thursday still progresses twice as fast in calendar time as one rotation a week (§4.3).

### Changed expectations and arithmetic

No existing expectation changes. The new fixtures pin the dense cap at 370 m after A steps 12→13: A 170 + C 30 + largest 170; its prior window is 350, leaving 20 < C's minimum 30. At the original 350 m cap, prior 265 leaves 85 and permits 4 × 20 = 80 m; prior 280 leaves 70 and substitutes. The midnight fixture's correct window is 220, leaving 130 and prescribing 6 × 20 = 120 m; the naive next-day window including that run is 180 and would permit 160 m, which still earns no structural credit. These are new assertions, not replacements for old numbers.

### Coverage and files changed

New `SprintAllowanceTest` fixtures cover dense decisions, minimum rep counts, restart fitting, structural edits, declined trials, trial allowance retention, local-date replay, both G-b exceptions and history derivation. The calendar tests cover all five events, intro on/off, 52 rotations, drift/slip at two through six days, dense/every-48-hours/nine at one through six days, and true Monday/Thursday at one day. They assert G-a/G-b, no needless cuts or substitutes, tempo only after a completed trial, and no ordinary-calendar cuts or declined trials. The supplied trace's non-intro one-day top rotations are 19 for 37/60/100 m and 14 for 200/400 m, counted from one.

New Robolectric `SprintFinishTest` covers midnight and evening replay, trial/restart/shortened writes, amendment from the original rule, tempo credit reversal, Step finality, Undo, absent plan inputs, Ask state writes, invalidated undo, distance replay, unchanged substitute records, pre-wrap cycle replay, old undo decoding and a stationary-bike Swap that earns no Step or sprint rule write. The structural edit coverage also rejects 4 × 40 m in place of 8 × 20 m. `CardioRuleJsonTest` adds maximal-marker round trips and rejection; `ProgressionSprintTest` adds decision ordering and marker validation. All existing test expectations remain intact. The supplied simulator and trace results informed the allowance and replay fixtures. This revision adds the weekly allowance, structural credit checks and Finish replay across domain and data; app wiring remains PR 7's work.

Production files: domain progression `SprintRules.kt`, `CardioRule.kt`; data `CardioJudge.kt`, `TrackerRepository.kt`, `db/dao/ProgramDao.kt`, serialization `CardioRuleJson.kt`, `ProgressionUndoJson.kt`. Test files: domain planner `SprintAllowanceTest.kt`, progression `ProgressionSprintTest.kt`; data `SprintFinishTest.kt`, `CardioRuleJsonTest.kt`. Documentation: this revision. No Room column, entity, migration, schema, backup format, SavedStateHandle, Parcelable, watch wire, resources or :app changes. PR 7 still owns app call sites, rule-derived surfaces, allowance and receipt copy, sprint recipe answers, keep-to-plan policy and any Ask Step offer.

## 55. Revision 46 (PR 4b-ii-a, run rules at Finish, 2026-11-17)

This revision wires run rules into Finish, the first part of PR 4b-ii. Part two owns stored phases, phase gates and the re-plan. It applies §3.1.2, §3.2 and audit decisions 3, 4 and 15. It supersedes revision 29's “Ask-mode offers for Step, Alternate and LevelTo”: PR 7 owns those offers; Ask holds changes other than Repeat credits meanwhile. The run/walk ladder pace and table stay unchanged.

**What is live.** Production creates cardio rules only through Planner, whose sole production writer, `createPlannedPlan`, receives Lift setup answers. Setup still offers Lift alone. Old cardio-first plans, converted plans and user-built cardio days have no run or sprint rules. Live users see nothing change: their openings, Finish writes, held guard, increase flag, chip and receipt retain their existing behavior. No app production code or copy changes. Tests can now create real Run and non-sprint Both plans through the repository, but PR 7 exposes those branches to users.

**Plan-block writes and one level per runner [P].** A ruled run day writes its plan block (`cardioBlockJson`) and rule together, leaving its working block null. A working block cannot carry an added round or a ladder level because `cardioToRun` rejects changed roles. Rule-less days keep their Automatic-only working-block path. A ladder Easy Finish advances every eligible ladder day one level; Good or no word repeats; Hard drops every eligible ladder day one level, with floor 1. The domain's Pain drop uses the same level-change outcome. Siblings keep their own activity; days kept to plan are excluded. Completing level 12 clears every eligible ladder rule's level and leaves its level-12 block. These writes implement §3.1.2's one level per finished session, not one level per day in the plan.

**Replay instead of finality [P].** Every ruled run judgment, even an unchanged one, records the judged day's original rule and pre-wrap rotation. It also records before and after block/rule columns and the previous working column for each day Finish wrote. The newest-session and increaseApplied gates remain first. An amendment requires a matching rule-after record and matching after columns for every recorded day, including the null working column. It judges the recorded befores, restores the previous write, and applies the latest result in the same transaction. Repeated rating edits replace the result rather than stacking levels or credits. The original befores and rotation remain the Undo basis. Undo restores each day's three columns only while its afters still match, then performs the existing rule swap; Finish Undo recognizes matching day records only when a block, working block or rule differs from its before. A replay back to the original state keeps its replay basis without offering Undo for unchanged days.

Run judgments keep `increaseApplied` false. A ladder rating is often entered or corrected after the commit; making an Easy advance final would preserve a mistaken novice advance after a correction to Good or Hard (audit decision 3). Sprint Step finality, allowance, replay, structural credit, Ask writes and recorded cycles remain unchanged. Sprint and run plan inputs now include the plan's exercises. The 21-local-day layoff fixture verifies that sprint Start and Finish agree on the step-0 restart, with the first completed session leaving intro 2.

**Ask [P].** Automatic applies the judged block and rule. Ask writes credits only on Repeat; Step, Alternate, Increase, LevelTo and LadderDone hold both block and rule until PR 7 supplies the offer. LadderDone holds even though its level-12 block equals the stored block, and ladder siblings also hold. Every judgment still records its replay basis. The chip does not pass plan inputs and therefore offers nothing on ruled run days; the receipt remains unchanged.

**Session minutes [P].** One planned-seconds calculation now lives in progression, shared by the planner's estimates and Finish. Distance sets without a time aim still use 0.36 s/m (6:00 per km, practice). `cardioBudget` subtracts lifts and the external 300-second warm-up from the chosen minutes; a block with its own leading WARMUP needs no external allowance. A growing range step, restored round or sprint step that exceeds that budget waits with its credit. A block that does not grow still fits. An alternation whose baseline does not fit takes the largest fitting step at or below its shared steps, with floor 0. If even step 0 does not fit, it holds today's kind with no pending credit. Long runs remain exempt, easy runs use `easyRunSeconds`, and ladder blocks are never shortened for minutes. Null budgets preserve all existing domain expectations. No source specifies growth against session minutes; this is a practice implementation of the chosen-minutes invariant. Trial, restart, tempo substitution and sprint allowance decisions keep their current paths. Sprint recipes remain unavailable, so their Finish minutes check has no production setup input until PR 7.

Full range rebuilds retain the stored sets before the first WORK/RECOVERY and after the last, preserving warm-ups and cool-downs. A ruled EASY session gets an `EasyRunContext` only on a CARDIO day with readable setup answers: its stop uses session minutes, actual strength-block presence and the current first LONG day; its cycle is recorded before Finish wraps it. A lift-first same-visit STRENGTH day repeats. The existing `opening != stored` guard remains for every non-sprint run judgment, including replay, so a tapered or capped opening earns no progression (#600).

**Editor and Reset [P].** Saving or removing a run block clears its run rule in the same day write; later progression then follows the existing rule-less A16 path and run cap. Sprint rules survive edits because their allowance, trial and restart guards depend on them; PR 7 owns the sprint editor policy. Ruled days reject working-block writes. Reset day returns for non-Lift recipes: rebuilding a run day from creation answers needs the stored phase, today and the log; phase storage is part two, and PR 7 owns Reset's policy.

**Recipe v2.** Optional Run and Both DTOs round-trip run goal, experience, event/test, date, target, recent result, longest run, marathon best, activity, injury and strength-block count, plus Both's order and Together answers. The codec requires exactly the objects appropriate to the branch, paired recent fields, at most one longest field, named enums and all domain answer checks. Sprint and Both-with-sprint encoding still refuse: PR 7 adds sprint answers; storing them now would lose them. Default null fields are omitted, so Lift recipe bytes stay identical. Backup stays version 30: no production build writes Run or Both recipes before PR 7, which bumps the backup version with its writer. Undo's additive days list defaults empty and is not encoded when empty; portable backups do not include Undo.

### Item reconciliation at 7455c807

| Earlier council item | State at #605 | This part |
|---|---|---|
| Range steps and alternation respect minutes | Missing | Steps wait with credit; alternation fits down or holds without credit |
| Full step keeps WARMUP/COOLDOWN | Missing | Stored leading/trailing sets retained |
| Editor clears run rule | Missing | Atomic clear; sprint rule retained |
| Ladder judgment, run rule writes, round-role guard, shared ladder level | Missing | Plan inputs, plan-block writes and sibling replay |
| Phase storage and v2 recipe | Lift-only recipe; phase missing | Run/Both codec here; phase and re-plan in part two |
| Sprint context, local dates, cycles, held exemption, repeat writes, Start/Finish inputs | Built by #605 | Retained; add 21-day agreement test |
| Sprint-step minutes check | Missing | Optional budget at Step |
| Judge KDoc excludes ladder | Stale after #605 | Ruled run inputs and repository guard documented |
| #600 taper/opening guard | Present on non-sprint days | Kept for ruled run days and replay |
| #600 easy stop with distance aim | Pace scaling already present | Pin 1750/5000 → 1800/5142 |
| R11 EasyRunContext | Optional, never passed | CARDIO easy days with setup now receive it |

### Changed expectations and arithmetic

`encode refuses other branches` in `SetupRecipeJsonTest` becomes `encode refuses sprint answers until PR 7`, with an inline `// 4b-ii-a:` comment. RUN and default BOTH encoding: throw → encode successfully; SPRINT: throw → throw; Both-with-sprint remains refused. The codec now stores the Run and Both answers rather than dropping them. Round 1 changes `an alternation past the session minutes waits on the current kind` to `an alternation below the next kind minimum holds without pending credit`: credits 1 → 0 at a 1500-second budget, because VO2MAX step 0 needs 5×180 + 4×180 = 1620 seconds. It holds without a pending step rather than repeating with a ready credit. No §11.2 value changes. Case 34 adds the two repository ladder test references; case 31 stays unchanged.

New boundary assertions include 4-round VO2MAX 1260 → 1620 seconds (wait at 1500), threshold 1320 → 1380 (wait at 1350), alternation 1200 → 1620 (hold without credit at 1500), and a stored 300+1200+300 block stepping to 300+1260+300. A threshold day at shared step 5 switches to VO2MAX step 1 at a 2000-second budget: six 180-second reps plus five 180-second recoveries need 1980 seconds; step 2 needs 6×240 + 5×240 = 2640. Ten-session loops pin both the deliberate hold at 1500 and continued switches after fitting down at 2000. The easy aim pin derives round(1750×0.05)=88, 1750+88=1838, round(5000×1838/1750)=5251, then round(5251×1800/1838)=5142. Easy Finish 960+48=1008 stays within the 1020-second run cap. Creation fits a 30-minute VO2MAX day to 1260+300=1560; its restored fifth round needs 1620+300=1920 and waits. A 60-minute quality day steps to six rounds at 1980 seconds. A half long run stored at 11000 m opens at 11000×0.6=6600 m twelve days out and earns no write.

A16 cannot add rounds on a rule-less day. Its held-guard fixture therefore uses the existing capped interval shape that removes a round, while a ruled VO2MAX fixture proves that adding a round passes through. No production rule or expected number changes for that fixture clarification.

### Coverage and files changed

New plain JVM `CardioJudgeRunTest` covers plan-input requirements, easy-context gating, the rule-less held guard, added rounds and the budget left after lifts. New Robolectric `RunFinishTest` covers shared ladder advances/drops/completion, unrated replay, quality steps and Undo, minutes waits, Ask credits, taper protection, easy growth once per rotation, editor clearing, working-block refusal and non-Lift Reset refusal. Additional boundaries cover sibling invalidation, guarded Undo, kept days, sibling activities and old Undo bytes. Domain tests cover budget edges, null budgets, non-growing switches, kept warm-ups, sprint Step waits, easy aim scaling and the budget identity. Recipe tests cover Run/Both round trips, strict validation and unchanged Lift bytes. The existing rule-less cardio, sprint, lift recipe, undo and light-cycle expectations are retained; CI must verify them.

Production files: domain progression `CardioMeasures.kt`, `Progression.kt`, `SprintRules.kt`; domain planner `SessionBudget.kt`, `RunSessions.kt`, `BothLayout.kt`, `SetupAnswers.kt`, `Planner.kt`; data `CardioJudge.kt`, `TrackerRepository.kt`, `db/dao/ProgramDao.kt`, serialization `ProgressionUndoJson.kt`, `SetupRecipeJson.kt`. Tests: domain `ProgressionRunRangeTest.kt`, `ProgressionSprintTest.kt`, `ProgressionEasyRunTest.kt`, `SessionBudgetTest.kt`, plus import-only changes in `PlannerRunInvariantsTest.kt`, `PlannerRunProgressTest.kt`, `PlannerSprintTest.kt`; data `CardioJudgeRunTest.kt` (new), `RunFinishTest.kt` (new), `SprintFinishTest.kt`, `SetupRecipeJsonTest.kt`. Documentation: this revision and `STRENGTH_TRACKER_SPEC.md` §11.2 case 34.

No Room version, entity, column or migration changes; no backup version, DataStore key, SavedStateHandle, Parcelable, watch wire, resource or app production changes. No network, compilation, build, test, Gradle, emulator or subagent was used. CI verification remains pending.

## 56. Revision 47 (run/walk ladder: two Easy per level, the weekly cap, a re-stepped table, 2026-11-17)

This revision implements the owner's 2026-11-17 decisions against main 0b0042a4 (#606), before PR 4b-ii part B. The [design, Revision 4](run-ladder-2026-11-17/design.md) and [research and grades](run-ladder-2026-11-17/research.md) are preserved here; only the design's §10.1 busy example is corrected. No production path creates ladder days before PR 7: setup answers do not set a Run branch, Reset returns for non-Lift recipes, and a hand-edited backup's ladder level decodes without credit or dates. Live users see nothing change. No migration.

**Owner 2026-11-17 [P, practice].** Two counted Easy sessions finish each level; level 1 has ten minutes of running; at most three Easy finishes per rolling seven local days count toward progress. These are owner choices, not tested injury or drop-out interventions. Research §4 places 24 finishes inside published beginner programs' 22 to 37 sessions. The ten-minute first dose comes from GRONORUN week 1, with Couch-to-5K's 90-second walk (research §5). R46's three sessions a week is practice. Weak observational evidence plus practice motivates the about-20-percent target; the 10-percent-rule RCT found no injury benefit, and no outcome study tests this table, cap or guard.

**Table [P].** Each round is a WORK run followed by a RECOVERY walk when present, including the final round. Levels 11 and 12 are continuous. Running time never falls and rises 6.7 to 12.5 percent per level. Total time on foot stays between 22.5 and 30 minutes; the 12-to-27-minute unbroken-bout jump remains practice.

| Level | Run seconds | Walk seconds | Rounds | WORK seconds | On-foot seconds |
|---|---|---|---|---|---|
| 1 | 60 | 90 | 10 | 600 | 1500 |
| 2 | 75 | 90 | 9 | 675 | 1485 |
| 3 | 105 | 90 | 7 | 735 | 1365 |
| 4 | 135 | 90 | 6 | 810 | 1350 |
| 5 | 180 | 90 | 5 | 900 | 1350 |
| 6 | 200 | 90 | 5 | 1000 | 1450 |
| 7 | 270 | 90 | 4 | 1080 | 1440 |
| 8 | 300 | 90 | 4 | 1200 | 1560 |
| 9 | 450 | 90 | 3 | 1350 | 1620 |
| 10 | 720 | 90 | 2 | 1440 | 1620 |
| 11 | 1620 | 0 | 1 | 1620 | 1620 |
| 12 | 1800 | 0 | 1 | 1800 | 1800 |

**Credit, cap and guard [P].** The first finished Easy earns a credit and repeats; the second raises the level or finishes level 12. Good, no word and unfinished sessions preserve credit. Hard or Pain drops one level, floor 1, and clears credit while keeping dates. A counted Easy appends Start's local date to the last three counted dates, with duplicates allowed. The weekly window includes today minus six through today. A backdated Easy before either the newest counted date or last level-up repeats. A level-up or LadderDone waits seven days after the previous level-up while some seven consecutive local days in the last fourteen hold more than three family runs, this session included. All prior family sessions count toward busy, regardless of rating, completion, plan or sets. A wait keeps credit and uses no cap slot. Production always supplies this context; pure tests can omit it to exercise two-per-level alone. `RunWalkLadder.busy` in pure domain is shared by the judge and calendar tests. The cap limits progress, not running.

Proven, in the chronological model, while no 7 days hold more than 3 runs (+12.5 / +12.5 / +19.15 percent for windows of 1 / 2 / 3 runs); searched, not proven, above that (worst found +19.15 percent over all pairs, +18.75 percent over pairs holding 4 or more runs); kept blocks and backdated entries are outside the model; weeks with more runs than the week before are frequency, which no ladder rule bounds.

**Corrected busy example [P].** Runs on d − 13, d − 12, d − 11, d − 10 and d are busy. Every other day from d − 13 is also busy: d − 13..d − 7 holds four. Mon/Wed/Fri at d − 11, d − 9, d − 7, d − 4, d − 2, d is not busy; every third day is not busy. This corrects the copied design's §10.1 example; the guard itself is unchanged from Revision 4.

**One runner, Keep and Undo [P].** Every ladder day holds the runner's rule, including kept days. An applied rule change writes the shared level, credit and dates to all of them; ordinary siblings take their own activity's level block, and kept days keep their stored block. LadderDone writes a fresh `CardioRule(null)` everywhere, including kept days. Leaving Keep synchronizes that day's block to the current rule in the same transaction, including on Ask, without adding an Undo record. A ladder judgment fails closed when another ladder day's pre-write rule disagrees. Ladder Undo restores all ladder day records and the judged rule together only when every recorded after still matches; otherwise it restores none of the ladder and proceeds with the rest, returns `Undone`, and clears the record. A refused later finish stays refused. Replay retains part A's newest-of-day and matching-afters gates and judges recorded befores on the original local Start date. This supersedes revision 46's “Siblings keep their own activity; days kept to plan are excluded”: kept days now take the rule, while holding the block.

**Ask [P].** A Repeat credit change writes to every ladder day. Cap, guard, backdate, Good, no word and unfinished write nothing when the rule is unchanged. A held level-up writes nothing, keeps credit 1 and retries at the next Easy. A held drop at level 2 or above clears credit on every ladder day while holding level and block; Hard at level 1 applies its Repeat credit clear. LadderDone writes nothing until PR 7's offer. Part A's Ask LadderDone test covers that hold; the stored phase half belongs to part B. Accepted offers will write the held outcome; part B re-plans Automatic plans only.

**Planner starts and weeks [P].** The start reads `KnownLoads.longestRunning`, falling back to the setup time seed. `CardioHistory.longestRunning` uses the shared `RunCap.finished` rule and the last 30 days, summing WORK secondsAtEnd or elapsedSeconds in each finished session, excluding RECOVERY. No production caller fills it here; part B supplies it for re-planning, PR 7 for creation. The cap's WORK-plus-RECOVERY anchor stays unchanged. Starts are 59/60 → 1, 900 → 5, 1200 → 8, 1799 → 11; injury subtracts one with floor 1, so 1200 → 7. A completed level-1 session has 600 running seconds but 1500 on foot: the former starts level 1, the latter would start level 10. Ladder weeks are ceil(2 × remaining levels / actual ladder sessions per rotation): level 1 at 3/2/1 sessions gives 8/12/24 weeks, level 8 at 3 gives 4. Zero ladder sessions means unreachable and DateTooClose for any date. Other phases keep six/eight/eight weeks. Case 30 remains 8 + 6 + 8 + 8 = 30 weeks.

**Both spread [P].** Lift-first same-visit plans attach at most three ladder sessions. Chosen rotation positions include Rest gaps and maximize the smallest cyclic gap, minimize how many gaps equal that minimum, then choose the earliest set. Three lift days use A/B/C; four, five and six use A/C/E with gaps 2/2/1, 2/2/2 and 2/2/3. Every lift day still reserves the full 1500-second level-1 ladder block, including days without cardio; accepted practice. A 60-minute session leaves 2100 seconds for lifting.

**Supersession.** This edits §3.2's ladder row to “a credit, then the next level,” the Foundation 1 row, revision 28's ladder start paragraph (20-minute runner → level 8, derived weeks), and the case 30 and 34 rows here and in `STRENGTH_TRACKER_SPEC.md` §11.2. The audit's R41, R42 and R46 rows remain historical; their ladder dose, one-session step and fixed duration are superseded by this revision. Revision 42's decision to leave two-per-level out is superseded. The level-12 anchor, cap math, base increases and later phase openings in case 30 do not change.

### Changed expectations and arithmetic

Each changed expectation has a `// ladder 2026-11-17:` explanation in its existing test.

| Test or fixture | Old → new | Reason |
|---|---|---|
| ProgressionLadderTest table | L1 8 × (60,120) → 10 × (60,90); L7 3 × (480,60) → 4 × (270,90); L10 1200 continuous → 2 × (720,90) | Re-stepped table; WORK 480/1440/1200 → 600/1080/1440 |
| Case 34 climb and completion | 12 Easy, each advancing → 24, credit then advance at each level, fresh empty rule on completion | 2 × 12; block identity retained on first finish and completion |
| Trail level change and completion calls | Credit 0 → 1 before Easy | Second Easy changes level or finishes |
| RunPhaseTest start | RecentMaxima input → WORK seconds; 1200 → L10 becomes L8; injury L9 → L7 | Four times 300 = 1200; subtract one for injury; distance-only/null stays L1 |
| RunPhaseTest remaining weeks | Fixed LADDER 8 → passed ladderWeeks 8; results 30/14/0/8/0 unchanged | Derived length at three ladder sessions |
| PlannerRunTest readiness | Rule 10 and 1200 continuous → rule 8 and 4 × (300,90); injury rule 9 → 7 | Running-time start |
| PlannerRunProgressTest case 30 | 12 loop iterations → 24; every finish advances → even index Repeat, odd index LevelTo(index / 2 + 2), index 23 LadderDone | Two Easy per level, pure context remains cap-free |
| PlannerBothTest D15 | Reserve leaves 2160 → 2100; six runs → three on A/C/E; every lift has cardio → only those three | 3600 − 10 × (60 + 90) = 2100; rotation gaps |
| CardioJudgeRunTest plan inputs | Rule 4 → credited rule 4 before LevelTo(5); date-free resulting rule → counted [1000], raised 1000 | Start at 86,400,000,000 ms is local UTC day 1000; new production context, not predicted by the design |
| RunFinishTest first Easy and Undo | One Easy → L2 → first credit at L1, second Easy on d+2 → L2, Undo → first credit at L1 | d = 20774; dates [d] then [d,d+2], raised d+2 |
| RunFinishTest unrated replay and repeated replay | Easy → rule 2 → credited rule 1 with [d]; Good still → original rule 1 | Replay uses original befores; no stacked credit |
| RunFinishTest Hard | Rule 3 → rule 2 → credited, dated rule 3 → rule 2 with dates retained | Hard clears only credit and drops one |
| RunFinishTest completion fixtures | Credit-free rule 12 → credit 1, counted [d−2], raised d−9; final rule remains empty | Second Easy completes and clears dates |
| RunFinishTest Ask completion | Every re-rating writes nothing → Hard clears shared credit at level 12; Good/Easy restore original credited before | Ask holds the level change; replay replaces prior write |
| RunFinishTest sibling returned to Progress | Kept B rule/block 11 → rule 12 with block 11, un-keep block 12; no Ask write → first Easy credit shared, next completion held | One runner includes kept days; duplicate date [d,d] allowed |
| RunFinishTest changed sibling Undo | A/C restored separately → no ladder restore; rule 2 after one Easy → credited rule 1 | All ladder afters must match; edited B survives |
| RunFinishTest kept activity | B rule 1 → shared dated rule 2; C date-free rule 2 → dated rule 2, TRAIL block retained | Keep freezes only block; level-up needs initial credit |

New tests pin the dated cap and boundary, busy/non-busy guard and seventh day, held completion, backdating, retained drop dates, rule bounds, exact JSON and invalid input, the five calendar/composition cases, WORK-only history, derived weeks, unreachable dated plans, spread days, local-midnight judgment including unrated/empty-set prior runs, fourth Easy refusal, late ratings, dated Undo, Ask credit clearing, kept-day resume and completion, partial Undo, divergence and the shared-rule invariant through Keep, replay, Undo and editing.

### Coverage and files changed

Production: domain progression `RunWalkLadder.kt`, `CardioRule.kt`, `Progression.kt`, new `LadderContext.kt`, `SlotSeeding.kt`, `CardioHistory.kt`, `RunCap.kt`; domain planner `RunPhase.kt`, `RunLayout.kt`, `BothLayout.kt`; data `CardioJudge.kt`, `TrackerRepository.kt`, serialization `CardioRuleJson.kt`. Tests: domain `ProgressionLadderTest.kt`, new `ProgressionLadderCalendarTest.kt`, `RunPhaseTest.kt`, `PlannerRunTest.kt`, `PlannerRunProgressTest.kt`, `PlannerBothTest.kt`; data `CardioJudgeRunTest.kt`, `RunFinishTest.kt`, `CardioRuleJsonTest.kt`. Docs: this revision, the two linked brief copies, and spec §11.2. The test scan found no further moved expectations in domain, data or transfer: other cases read the table directly, test unchanged openings or carry no Easy credit. The strings.xml scan found no ladder string, so no copy change.

No Room version, entity, column, backup version, DataStore key, SavedStateHandle, Parcelable, watch wire, resource or app production changes. Default fields stay omitted, preserving `{"ladderLevel":3}` byte for byte; new keys validate through the existing backup codec. No network, builds, compilation, Gradle, tests or subagents were used. CI verification of domain, data, transfer, app unit tests and the build remains pending.

### PR 7 inputs, not built here

1. **Required Keep and leaving Keep copy [P].** Explain that Keep holds this day's dose while the plan moves on, a hard session here never changes the plan because kept days are never judged, and leaving Keep moves the day to the current level. Draft: “Keep this run as it is. Your other days keep moving, and a hard run here won't change the plan.” Leaving: “This day moves up to your current level.” No injury claim. These user-chosen dose changes fall outside the bound: B kept at L1 while A/C reach L9 gives 600 → 1350 seconds, +125 percent; leaving Keep at L10 or L12 on Mon/Wed/Fri gives +24.1 or +28.6 percent.
2. **Finish effort row [P].** Explain that the rating moves the runner up, two Easy runs finish a level, and Good or no word repeats. No injury claim (audit row 3).
3. **Ask offers [P].** Offer held level-ups, held drops (only credit clear written), and held LadderDone. Acceptance writes the held outcome. Part B re-plans Automatic plans only; the offer owns re-plan copy.
4. **Cap and guard [P].** Limit progress, not running. Draft: “Up to three Easy runs a week count toward the next level.” A busy fortnight holds the next step a week. R46, three ladder days in Run/Both and R52's novice two-week rule in 4b-iii own exposure.
5. **Backdated entries [P].** Easy entered before the newest counted date does not count; the history entry sheet may explain it.
6. **No ladder session [P].** Both on one separate day with lifting first, or together at 30 minutes when the 25-minute ladder does not fit, reports DateTooClose for any date. Offer longer sessions or separate days.
7. **Creation inputs [P].** Fill `KnownLoads.longestRunning` wherever `longestRecent` is filled, including creation's LADDER gate. No interval level of this ladder reaches 1800 seconds on foot; a runner's own long run/walk could.
8. **Backup schema bump.** Older imports are strict: their `CardioRule.init` rejects ladder credits, so a backup carrying those credits fails import, although `ignoreUnknownKeys` ignores the two new keys. Moot until PR 7 creates ladder plans; bump with its writer.
9. **Fail closed [P].** A ladder forked outside the app by a hand-edited backup stalls; PR 7 may add repair.
10. **Watch.** Blocks reach the watch through the existing block path. No wire change; any watch copy is a follow-up.

## 57. Revision 48 (PR 4b-ii-b, phase gates and the re-plan, 2026-11-17)

**1.1 When [P].** Re-planning runs inside the Finish that wraps the rotation, immediately after `lightAfterDay` increases `cyclesCompleted`, on either the cardio commit or the strength Finish path. The next phase begins a new rotation; quality days do not move mid-rotation, and a mistaken distance can be corrected before the wrap. The new cursor is the first training day. Finish skips its ordinary cursor advance when it re-plans.

**1.2 The gates [P; ladder end follows Relph 2023].** Recipe v2 stores `runPhase` at creation and changes it only on a re-plan. Only Automatic Run and Both plans without a sprint sub-goal re-plan. Ask keeps its phase until PR 7's offer, which owns acceptance and copy. Leaving LADDER requires every rule to have cleared its `ladderLevel`, including kept days: LadderDone, rather than a longest-run threshold, is the exit gate. A run/walk outside the ladder can reach 30 minutes on foot without 30 minutes continuously; `RunCap.maxima` includes RECOVERY. LadderDone needs a credit at level 12 and a counted Easy finish passing the weekly cap and level-up guard. The credited fixture uses `credits = 1`, `ladderCounted = [d − 2]`, `ladderRaised = d − 9`; its sessions are on d, d + 1 and d + 2. No seven consecutive days of its last fourteen hold more than three runs, and nine days since the last raise also clears the guard.

Every other gate uses §3.1.2's table, with the family's 30-day longest, longest week and longest WORK running time from the log at the wrap. Gate forms are practice (audit R37/R38), superseding the table introduction's moderate label for the numerical gates. `Planner.plan(..., atLeast = storedPhase)` cannot move back; the ladder exit uses a BASE floor. A later draft phase supplies the next program, skipping gates already met. A wrap without a finished family run in the preceding 7 × 24 hours waits. Lift, converted, old cardio-first and unphased recipes keep the existing Finish behavior. Setup offers Lift alone until PR 7, so this changes no live plan.

**1.3 What the plan writes [P].** Merge the draft in its day-id order. Existing days keep their exercises, slot rules and working rows; strength blocks are never rebuilt, refitted or moved. New days take the draft's cardio with no slots, keeping the stored strength-block count. Kept days retain their entire stored prescription, including a former ladder day with `CardioRule(null)`. A STRENGTH day takes only the new cardio block and rule. Other surviving run days take the draft's kind, title, capped block and rule. Unsupported kind changes or missing stored ids refuse the re-plan. Quality sessions restart at the low end. The long run opens under creation's single-session run cap, including case 30's 11,400 m opening from 10,371 m.

An EASY Time end is the least of the draft end, `easyRunSeconds` for the actual retained strength block and new long run, and the same day's previous easy length, falling back to the longest stored EASY day. Easy runs never lengthen at a re-plan. A merged CARDIO day whose retained slots differ from the draft drops interval rounds until its minutes fit; a single WORK set stays whole, as creation's OverBudget behavior requires.

**1.4 The weekly guard and waiting [P, guided by weak evidence].** Planned seconds include every rotation day's `cardioToRun`, kept days included. Distance sets without time aims use the family's recent pace, else 0.36 seconds per meter. The allowance is floor(1.2 × min(stored planned seconds, finished seconds in the 7 × 24 hours ending at the wrap)). In day order, shorten easy runs by 60 seconds each pass to a 600-second floor, checking after each cut; then remove interval rounds to one; then shorten the long run by the remaining excess, rounding down to 60 seconds or 100 m. The long-run floor is the larger of the stored LONG end and that week's longest finished run, converting units at the same pace. Kept days are never shortened. If these floors still exceed the allowance, return null and write no phase, recipe or program change. Retry at a later wrap. Sparse runners keep the stored program; after LadderDone, their level-12 blocks grow under A16 within the run cap.

Audit row 4's novice weekly practice guides the 20-percent allowance. Damsted 2019 compared early rises of 20–60 percent with under 20 percent; Nielsen 2014's greater-than-30-percent rise over two weeks had HR 1.59 (0.96–2.66). These weak findings do not establish a safe threshold. R52's ordinary-week novice two-week rule remains in 4b-iii. At the six-day New marathon ladder exit the simulation goes from 5,220 seconds run to 8,940 planned without the guard (+71.3 percent), or 6,240 with it (+19.5 percent); the easy runs then grow under R11.

**1.5 What the user sees [P].** No receipt line, reason or toast is added; PR 7 reads `plan.replan` from the Finish undo record for copy. Today opens the new rotation's first training day, and Plan lists its new cardio titles. Deferral shows nothing. Later deletion or correction of the gate run cannot move a plan backward; Undo can.

**1.6 Undo, idempotency, crash safety and ratings [P].** Finish and re-plan share the Room transaction and undo record. The phase advances with the program, so another wrap at the same phase does not re-plan; a deferred wrap writes nothing and retries later. Undo restores every prior day, deletes inserted days and restores the old recipe only when the recipe and every recorded day column still match the re-plan's afters, and inserted days have no exercises. Otherwise it leaves the re-plan and undoes the rest of Finish as before. Restore the program before part A's cardio records or the light counters so a wrapping LadderDone can restore all its shared ladder state. Undo does not move the cursor; day A exists in every program. `finishUndoState` recognizes a matching re-plan as restorable. Editing the wrapping session's effort or distance does not replay through the re-plan: part A's matching-afters gate refuses it. Undo first, then re-rate.

### Simulation and findings

The supplied `fix/ladder/replan_newladder.py` ports the new table, two Easy credits per level across ladder days, running-time ladder starts, the run cap and minutes waits, R11, quality alternation, the gates, easy carry and deferral. It models time-form runners, every attended session finished and Easy, one rotation per week with consecutive entries: 5K to 10K, half, marathon and 50K × New, Some and Racing × one to six days × 30 to 90 minutes × no anchor or 600–7,200 seconds, 2,880 plans for 104 rotations. Three ladder sessions never occupy fewer than seven days, so the ladder cap and guard do not bind. The Kotlin Run/Both invariant is separate and awaits CI.

| Variant | Re-plans | Worst re-planned week over week run | Base → half | Ladder → base |
|---|---|---|---|---|
| Draft replaces run days | 3,390 | +74.7 percent | +14.2 percent | +74.7 percent |
| Slots kept, easy carry | 3,384 | +74.7 percent | +8.3 percent | +74.7 percent |
| Carry, guard and deferral | 3,422 | +20.0 percent | +8.3 percent | +20.0 percent |

The guarded grid has zero plans above 1.2 × the week run and zero openings above the single-session cap. Half → marathon is at most +8.6 percent, marathon → ultra +9.0 percent. The guard shortens easy runs in 280 plans, all five- or six-day ladder exits (140 each; `fix/ladder-spec/bind_where.txt`). A New marathoner at three 60-minute days reaches base at rotation 8, half build at 22 and marathon build at 35, superseding the old one-per-level ladder's 4, 17 and 30. Guard factors 1.1/1.2/1.3 give maximum rises of 10.0/20.0/30.0 percent.

| Sparse runners, 35 percent skipped at random | Re-plans | Deferred wraps | Worst re-planned week over week run |
|---|---|---|---|
| Deferral | 3,470 | 1,249 | +20.0 percent |
| Without deferral | 3,486 | — | +308.8 percent |

No New runner remains in the ladder after 104 rotations in either sparse variant. Outside ladder and re-plan rotations the worst ordinary rotation rise remains +30.7 percent (5K to 10K, New, three days, 75 minutes, 1,800-second anchor), from the lone base threshold day's VO2max alternation; its two-rotation mean rises at most 25.3 percent. That 4b-i finding is unchanged. The ladder now takes eight weeks at three sessions rather than four.

### Supersession, files and expectations

This supersedes revision 29's draft replacement: the draft is merged. It supersedes revision 28's and §3.1.2's re-derived easy-run length at a gate: easy runs never lengthen on a re-plan. The phase now lives in recipe v2, superseding the former “Stored nowhere” description. Revision 47's deferral, Automatic-only phase changes and credited LadderDone fixture are binding.

Production files: `RunPhase.kt`, `Planner.kt`, `RunLayout.kt`, `BothLayout.kt`, new planner `RunReplan.kt`, `CardioMeasures.kt`, `CardioHistory.kt`, `TrackerRepository.kt`, `PlanRepository.kt`, `SetupRecipeJson.kt` and `ProgressionUndoJson.kt`. Tests: new planner `RunReplanTest` and data `RunReplanFinishTest`; additions to `PlannerRunInvariantsTest`, `PlannerRunTest`, `CardioHistoryWeekTest`, `SetupRecipeJsonTest` and `PlannedPlanTest`. `STRENGTH_TRACKER_SPEC.md` §11.2 case 30 adds the re-plan test without changing a number.

| Existing expectation or fixture | Old → new | Reason |
|---|---|---|
| SetupRecipeJsonTest run and both answers round trip | Absent phase → explicit HALF_BUILD in both fixtures; decoded answers unchanged | A phased goal requires its stored phase |
| SetupRecipeJsonTest run recipes validate strictly | Absent phase → explicit HALF_BUILD in the valid fixture; malformed payloads still reject | Validate run fields on an otherwise valid phased recipe |

Each fixture change has a `// 4b-ii-b:` comment. All creation numbers, ladder expectations, case 31 and Lift recipe bytes remain unchanged. The second-wrap idempotency fixture uses unfinished results in its second rotation to isolate re-planning from ordinary A16/R11 and quality progression. Extra coverage exercises a strength Finish wrapping a Both plan's run gate and undoing it. No Room schema, backup, DataStore, SavedStateHandle, Parcelable, watch wire, resource or app production change. No network, compilation, Gradle, builds, Kotlin tests or subagents were used; CI still owns domain, data and app unit tests and the build.

The existing planner returns a null phase when a Both layout supplies no run sessions (for example, lifting first on one separate day). Under this PR's strict recipe contract, `createPlannedPlan` cannot encode such phased-goal answers with that null draft phase. Their planner output remains unchanged; PR 7 needs to handle the no-run setup choice before offering it. The re-plan invariant checks only stored drafts with a phase and verifies that skipped drafts have no run sessions.

## 58. Revision 49 (PR 7a, run plans: the model, 2026-11-17)

**1.1 Entry [P, owner decision; lead adjudication 2026-11-17].** A brand-new runner starts on the run/walk ladder. New and Some runners use one entry gate for every run goal on foot: 1,800 running seconds or 5,000 running meters. A family's finished log supplies the gate; without a log, the typed longest run supplies it. With neither, New enters the ladder and Some starts past it. “Always” is read with this gate: case 29's New marathoner with a typed 6,000 m longest still starts at BASE. Middle distance, Fitness test and Just cardio fitness now have a ladder-only path for runners on foot; after the ladder their own plan has no phase. Walk, ride, row and elliptical Just cardio answers have no ladder.

The running-only LADDER gate also applies below a BASE floor. A run/walk log now stops at BASE where it previously reached HALF_BUILD; this is conservative.

A Some runner with a 900-second running anchor on Just cardio fitness now starts at level 5: five 180-second runs with walks. Revision 42's continuous easy-growth worked example survives as a stationary rider's example, with its anchor in the RIDE family. Its 960-second opening, subsequent 1,008 through 1,800 seconds and 26 increases on the day without strength remain unchanged. A synthetic `known(null, 900)` fixture supplies no WORK running time or typed longest, so its on-foot ladder start is level 1; a typed 900-second longest starts at level 5.

**1.2 Racing [P, practice; lead round 2].** Racing runners never start on the ladder, on any goal. The ladder's evidence concerns novices: Couch-to-5K and GRONORUN practice, Relph 2023 and Videbæk 2015, as recorded in [the ladder research](run-ladder-2026-11-17/research.md). “30 min continuous” is a 5K-readiness gate, not a measure of a middle-distance or test racer. A Racing runner on a phased goal starts at the first unmet gate from BASE; without an anchor that is BASE. A Racing runner on a goal without phases gets that goal's plan with no phase.

A Racing 800 m runner with a typed 1,200-second longest, four days and 60 minutes gets A EASY, B RACE_PACE, C EASY, D LONG, E REST. D's 3,600-second template caps to 1,320 = floor(1,200 × 1.1 / 60) × 60. A and C are 960 = floor(0.75 × 1,320 / 60) × 60, below the 1,800-second minutes allowance and the cap. B keeps six hard 400 m reps, five 120-second recoveries and no aim: without a pace, its distance work cannot compare with a time anchor.

**1.3 Typed distance [P, practice].** A typed distance with no time supplies floor(meters × 0.24) running seconds for the ladder start. The 4:00 per km estimate is chosen fast so it stays at or below most New or Some runners' likely running time; the ladder corrects upward after two counted Easy runs a level. The 0.36 s/m planning estimate was considered and not reused: it is slow by design so sessions fit their minutes, and would place runners faster than 6:00 per km above their running time. A typed 3 mi, 4,828 m, supplies floor(1,158.72) = 1,158 seconds: level 7 covers 1,080, while level 8 needs 1,200. Injury lowers that to level 6. A logged 900-second WORK time wins and starts level 5. The conversion cannot change the gate: distances below 5,000 m convert to at most 1,199 seconds, and distances at or above it already pass the distance arm.

**1.4 The default and the evidence [P, conservative practice].** The one-gate default for New and Some runners is conservative practice. Research does not show that it is safer than growing continuous easy runs or better for adherence; no study in the ladder research tests either comparison. Its Gaps section also records the untested transition from interval bouts to continuous running. This default trades continuity for walk-break bouts: 15 continuous minutes become level 5's five 3-minute runs with walks. Later, level 10's two 720-second bouts become level 11's 1,620-second continuous run. The 900-second example keeps its per-session running time at entry; weekly running time depends on session count, at most three ladder days. Each level's running time rises at most 12.5 percent. These are product and practice choices, with no claimed injury or adherence benefit.

**1.5 Running-only anchors [P, revision 47 input 7 completed].** The ladder gate reads WORK seconds and WORK meters, excluding RECOVERY. `CardioHistory.longestRunningMetres` counts a finished session only when every WORK set has a distance, using the same per-set distance reading as `RunCap.maxima`. The run cap and later phase gates keep their existing WORK-plus-RECOVERY anchors. A 3,600-second, 5,500 m run/walk with only 900 seconds and 2,500 m of WORK now enters level 5; a continuous 5K in 1,700 seconds passes the gate by distance. Setup and the re-plan share a Room-only log loader and `CardioHistory.known`, supplying the longest session, longest week, WORK seconds and WORK meters. History takes precedence over the typed seed. A logged continuous 2,400-second run with no distance starts at BASE and opens the long run at 2,640 seconds; the continuous 5K fixture opens at 5,500 m.

**1.6 Exit and recipes [P].** LadderDone still clears every ladder rule before a wrap can leave the ladder. A ladder-only path uses the BASE floor to request its goal's own plan, returns a null phase and removes `runPhase`. Revision 48's merge, kept days, easy carry, 20-percent weekly guard and waiting remain unchanged. The three-day Just cardio exit with three 1,800-second runs opens at A 1,800, B 1,800 and C 1,980 seconds: strength days allow 3,600 − 300 − 1,500 = 1,800; C's 3,300-second allowance caps to 1,980. Total 5,580 is below floor(1.2 × 5,400) = 6,480.

A recipe may omit its phase, including on a phased goal when a Both layout has no run session. Encoding a present phase requires the runner's path; Racing LADDER is refused. Validation still accepts a pre-7a Racing recipe carrying LADDER on a phased goal, so it decodes and can re-plan past the ladder. Removing a phase preserves every other field, including unknown fields from a newer writer. Existing encoded recipes retain their bytes; Lift recipe bytes remain pinned.

**1.7 Goals, Undo and Reset [P].** A Just-finish event or timed field test retains its distance and optional date as `PlanGoal.Timed` with a null target time. It has no target to beat, displays its distance and best alone, and tapers from its event and date like a timed goal. Cooper and Just cardio fitness still have no goal; PACER and HAMR still require a score for a Shuttle goal. The current editor maps a null time to its existing blank field and keeps Save disabled until a valid time is entered; PR 7c owns saving a blank time. The Parcelable fields do not change.

Undo leaves the re-plan intact while an open run belongs to a day it inserted. The guard lives in `replanMatches`, so receipt restorability agrees; the remaining Finish undo, including matching light counters, proceeds. Effort or distance amended after a re-plan stores the amendment only: Undo first, then re-rate. Reset day to plan is offered only on generated Lift setup plans; rebuilding a ruled Run, Sprint or Both day would discard its rule state. The repository's refusal remains.

**1.8 Dormancy [P].** Lift setup remains the only production plan writer and is untouched. Until PR 7c opens setup and bumps backup to v31, no production path writes a Run or Both recipe, LADDER on a goal without phases, a phase-free recipe on a phased goal, an untimed goal or a ladder credit. Repository tests can create these through `createPlannedPlan`. Backup remains schema 30. No Room, DataStore, SavedStateHandle key, Parcelable field, watch wire, `CardioRuleJson` bytes or string resource changes.

### Expectations and arithmetic

| Existing expectation or fixture | Old → new | Reason |
|---|---|---|
| SetupRecipeJsonTest run recipes carry their phase | Missing phase throws and fails validation → encodes, validates and decodes, `phase` null; invalid payload count 4 → 3 | A null phase stores nothing to re-plan; off-path phases still reject |
| PlannerBothTest lift-first one-day Separate draft | Goal null → untimed run goal | A one-day lift-only layout retains the run goal on the plan |
| PlannerRunTest half at four sixty minute days | Goal null → `Timed(ROAD_RUN, 21097, null, null)` | Just finish keeps its event and date |
| PlannerRunTest readiness selects the first unmet single run gate | New/no-anchor unphased fixtures with null → Some/no-anchor fixtures with null; New covered separately at LADDER | One entry gate; no-anchor Some still starts past it |
| PlannerRunTest short middle-distance anchors | New → Racing fixtures; 960 and the two positive capped easy runs stay | 1,200 × 1.1 = 1,320; floor(0.75 × 1,320 / 60) × 60 = 960. At a 60-second anchor cap = 66 and easy floor = 60 |
| PlannerRunTest small race and test interval aims | New → Some in four no-anchor fixtures; every aim stays 1 second | New now enters LADDER; Some preserves the interval subject and the one-second minimum |
| PlannerRunProgressTest Just cardio easy growth | ROAD_RUN with RUN 900-second anchor → STATIONARY_BIKE with RIDE anchor | On foot the synthetic fixture starts level 1; the rider retains 960, 1008, 1058, 1111, 1167, 1225, 1286, 1350, 1418, 1489, 1563, 1641, 1723, 1800 and 26 increases |
| RunFinishTest Just cardio easy growth | ROAD_RUN blocks → STATIONARY_BIKE blocks | On foot the typed 900-second fixture starts level 5; rider keeps 960 → 1008 → 1008 and cycle 0 |
| PlanGoalSprintJsonTest no-time timed projection | Null → `Timed(ROAD_RUN, 5000, null)` | Just finish keeps its event; sprint assertions stay |
| PlannerRunInvariantsTest re-plan grid | Goal phase pairs → runner path pairs plus ladder-only exits; 2,700 → 2,880 raw transitions | Remove 4 Racing ladder pairs × 5 layouts × 6 days × 3 minutes = 360; add 3 ladder-only goals × 2 experiences × 5 layouts × 6 days × 3 minutes = 540 |

The run and Both creation grid counts remain 5,130 and 8,208: 19 variants × 6 days × 5 minute choices × 3 experiences × 3 anchors, and 19 × 6 × 3 × 3 × 2 histories × 4 layouts. The easy-growth grid still has 486 inputs and 378 eligible starts: 36 New/Some Just cardio 900-second starts now skip as LADDER, while 36 Racing 5K-to-10K/half 900-second starts newly run at BASE. Its invariants are unchanged; no runtime result is claimed. PlannerBothTest's field-test grid already uses Some and Racing with either no anchor or a gate-clearing log, so its prescriptions and counts stay unchanged.

No §11.2 number changes. Case 17's cap, case 19's taper bands, case 20's Riegel predictions, case 21's test aims, case 24's rotation, case 29's pinned forms and 6,000 m BASE fixture, case 30's level 1 and 24 Easy finishes with the BASE floor, and case 34 remain pinned. Every changed existing expectation or fixture has a `// PR 7a:` comment. New coverage pins entry and exit, running-only history, log precedence, legacy Racing recipe reads, phase removal, untimed goals, Both layouts with no run session, the open-run Undo guard and Lift-only Reset.

### Supersession and files

This supersedes revision 28's “Middle distance, tests and Just cardio have no readiness phase” for runners on foot, and its “Just finish, Cooper and Just cardio return no goal” for Just finish. It supersedes revision 48's requirement that a phased goal store a phase. Revision 47 input 7 is complete. The owner decision and running-only gate are product rules [P]; the Racing exemption and typed-distance pace are practice [P].

Production: domain model `PlanGoal.kt`; planner `RunAnswers.kt`, `RunPhase.kt`, `RunLayout.kt`, `BothLayout.kt`, `Planner.kt`, `RunReplan.kt`; progression `SlotSeeding.kt`, `RunCap.kt`, `CardioHistory.kt`, `GoalPace.kt`; records `GoalProgress.kt`; data new `CardioLog.kt`, `PlanRepository.kt`, `TrackerRepository.kt`, serialization `PlanGoalJson.kt`, `SetupRecipeJson.kt`; app `ui/plan/PlanCardioGoal.kt`, `PlanGoalDraft.kt`, `ui/day/DayEditLogic.kt`, `DayEditor.kt`.

Tests: domain new `planner/PlannerEntryLadderTest.kt`, planner `PlannerBothTest.kt`, `PlannerRunTest.kt`, `PlannerRunProgressTest.kt`, `PlannerRunInvariantsTest.kt`, `RunPhaseTest.kt`, `RunReplanTest.kt`, progression `CardioHistoryOfTest.kt`, `TaperTest.kt`, records `GoalProgressTest.kt`; data `SetupRecipeJsonTest.kt`, `PlannedPlanTest.kt`, `RunReplanFinishTest.kt`, `RunFinishTest.kt`, `PlanGoalJsonTest.kt`, `PlanGoalSprintJsonTest.kt`; app `ui/plan/PlanCardioGoalScreenTest.kt`, `ui/day/DayEditLogicTest.kt`. Docs: this revision.

No network, compilation, Gradle, builds, tests or subagents were used.

## 59. Revision 50 (PR 7b, run results, Keep copy and Ask offers, 2026-11-17)

### Finish results and offers

A ruled run Finish stores its judged result inside the existing cardio undo JSON. The chip and receipt use the public `runResult()` decoder. They do not re-judge the current rule: after a first Easy has written credit, re-judging would describe the next Easy's level-up instead of the Finish that earned the credit. Older records default to no result; unreadable records return null. Rule-less and sprint record bytes stay unchanged. The two ruled judgment branches share one private Room-only `cardioPlan` builder; sprint inputs and behavior stay the same.

Ladder Easy repeats name BACKDATED, WEEK_CAP or BUSY only when those rules held them. Credit-earning repeats, Good, no answer and unfinished sessions carry no hold. The receipt explains earned credit, level-up, drop, completion and each hold in its Why sheet. Its next-time line names the level, Alternate kind or resulting block, with held changes marked offered. Sprint receipts and chips remain empty. Sprint receipts omit “Next time” until PR 7d (#611 follow-up).

Run change offers live on Today's and the preview's next-time chips. Accept and decline replay the recorded pre-write days through the same guard and Undo as an effort edit. The explicit choice persists in the result and overrides the plan mode during later effort or distance edits. Ask's ordinary write policy remains Repeat credit and a held drop's credit clear. Accepted ladder changes update every ladder rule while kept days retain their block.

At a wrap, Automatic still computes and merges the next phase in Finish. Ask records a plan-level offer and advances the cursor without merging. The receipt shows the offered phase (or the goal's own plan after a ladder-only path) and one accept button. Acceptance computes again from the log as of that Finish, refuses while a run on that plan is open or the guard waits, and stores the merge in that Finish's Undo. A missed offer is checked again at the next wrap. Applied re-plans also appear on the receipt.

Undo reports `UndoneLadderKept` when changed ladder days prevent the shared ladder restore; other Finish restoration proceeds. The existing single Undo button remains the workout's reversal action.

### Keep and Plan copy

The ladder Next time page shows both prescribed Keep and leaving-Keep lines under Plan. Progress hides both; ladder days hide Progress by because its measure does not govern the ladder. The receipt shows the prescribed effort note under How was it on ladder days. Exact resource copy is in `strings.xml`, including the weekly-cap explanation.

The Plan screen shows the stored Run or non-sprint Both phase, with Taper taking precedence in its date band. A timed goal through marathon distance with a recent race shows the existing Riegel goal-check prediction, recent distance and recent time in minutes:seconds. Case 20 remains 3,127 seconds from a 1,500-second 5K to 10K. Ultra goals show no prediction.

### Changed expectations

| File and existing test | Old → new | Reason |
|---|---|---|
| ProgressionLadderTest `the cap counts three Easy finishes in seven days` | Fourth Easy outcome hold null → WEEK_CAP | Names the hold; block, rule and change unchanged |
| ProgressionLadderTest `a full cap keeps the credit` | Outcome hold null → WEEK_CAP | Names the hold only |
| ProgressionLadderTest `the cap window is local days` | Full-window outcome hold null → WEEK_CAP | Names the hold only |
| ProgressionLadderTest `the guard holds a second level-up within a week for a busy runner` | Outcome hold null → BUSY | Names the hold only |
| ProgressionLadderTest `the guard holds LadderDone` | Outcome hold null → BUSY | Names the hold only |
| ProgressionLadderTest `a backdated Easy is a Repeat` | Outcome hold null → BACKDATED | Names the hold only |
| RunFinishTest `a changed ladder sibling prevents replay and survives Undo` | Undone → UndoneLadderKept | Shared ladder afters no longer match |
| RunFinishTest `a partial ladder Undo restores nothing on the ladder` | Undone → UndoneLadderKept | Changed sibling prevents shared restore |
| RunReplanFinishTest `undo keeps a re-plan while a run is open on a day it inserted` | Undone → UndoneLadderKept | Open inserted day keeps the re-plan, so the recorded ladder afters cannot match |

Every changed expectation above carries its `// PR 7b:` comment. PlannerBothTest's existing one-day Separate comment gains the missing `// PR 7a:` prefix; its expectation does not change here. No existing encoded-byte assertion changes: the older rule-less undo byte assertion remains exact. New coverage pins the new result bytes. No §11.2 numbers change.

### Supersession and files

This supersedes revision 46's Ask hold “until PR 7 supplies the offer” and unchanged receipt for ruled runs; revision 47's held LadderDone “until PR 7's offer”; and revision 48 §1.2's Ask phase hold until that offer and §1.5's “No receipt line”. The result, offer and copy additions do not change progression, readiness, weekly guards, Automatic re-planning or sprint rules.

Production: domain progression `CardioOutcome.kt`, `Progression.kt`; data `TrackerRepository.kt`, `FinishUndoState.kt`, serialization `ProgressionUndoJson.kt`; app day `DayScreenBuilder.kt`, `CardioNextTimeChip.kt`, `DayViewModel.kt`, `SessionReceiptScreen.kt`, `SessionFinisher.kt`, `DayScreen.kt`, `DayEditSheet.kt`, `DayEditor.kt`, `DayEditModels.kt`, receipt `ReceiptProgress.kt`, `ReceiptCardioLines.kt`; preview `PreviewViewModel.kt`; plan `PlanScreenBuilder.kt`, `PlanModels.kt`, `PlanScreen.kt`, `PlanCardioGoal.kt`, `PlanViewModel.kt`; `AppNavHost.kt`, `res/values/strings.xml`.

Tests: domain `ProgressionLadderTest.kt` and the revision 49 comment correction in `PlannerBothTest.kt`; data `RunFinishTest.kt`, `RunReplanFinishTest.kt`; app `DayScreenBuilderTest.kt`, `DayViewModelCardioExecutionTest.kt`, `ReceiptScreenTest.kt`, `PlanScreenBuilderTest.kt`, `DayEditSheetTest.kt`, `PlanScreenTest.kt`, `WorkoutSetScreenTest.kt`. Large-font fixtures cover the receipt, Why sheet, Keep page, Plan phase and prediction, and ladder chip, including the new button and chip's 48 dp target.

No network, compilation, Gradle, builds, tests or subagents were used. CI and the ladder receipt and set-screen render artifacts must be checked before merging. Room, backup schema 30, DataStore, SavedStateHandle, Parcelables, setup recipe bytes, cardio rule bytes and watch wire stay unchanged.


## 60. Revision 51 (PR 7c, Run and Both setup, 2026-11-18)

### Setup and saved answers

Goal offers Lift, Run and Both, with Lift selected by default. The Lift step list, answer defaults, fitting and balance choices, Start and restore latches, and five-Next pilot stay unchanged. Run walks Goal, Running goal, Your week, Equipment, About your training, optional Your event, Where you are and Your plan. Both adds Which first and Lifting goal before Running goal, keeps the existing conditional Your lifts and Recent numbers, and adds Together before Your plan where it is a real choice. Just cardio fitness skips Your event.

Together is offered at three through six days in either order and at two days running first. Hidden Together answers map to Same visit at one day and at two days lifting first. The saved draft keeps the user's Separate days choice so it returns when the step is offered again. Restores and prefills use the same mapping; a null offered choice uses SetupAnswers.together's default.

SetupDraft keeps version 1 and the existing setup_draft key, with defaulted Run and Both fields appended to its JSON model. No Parcelable, Room schema, DataStore schema, watch message or saved-state key changes. Decoding checks strengthBlocks in 0..2; stale run inputs remain readable. Answer mapping drops events outside the goal, invalid time/count/recent/longest fields, inapplicable marathon bests, surfaces outside the goal, and dates the goal or test cannot use. PACER and HAMR need a target before a date; Cooper's date stays hidden. Run uses default strength-block mains. Both maps two blocks and lets the planner choose their layout. Prefill carries every Run and Both field back.

After a downgrade, an older build drops a draft saved on a new step and resets setup without crashing because it cannot decode the step name. Play never downgrades.

Your event uses the goal's event choices, Ultra's Road or Trail and optional marathon best, target time/distance/count by test, and Just finish or A time for events. A date uses the M3 picker. Recent distance and time are optional. Where you are asks longest minutes or distance, with activity choices for Just cardio fitness. About adds running experience and the past-year running-injury answer. These screens use the exact PR 7c resource copy and M3 choices and NumberField.

Just finish stores null goalSeconds. A time stores INVALID_GOAL_TIME until the field holds a valid positive time; blank, malformed and zero times keep Next disabled on Your event. This supersedes §2's always-enabled Next for that explicit choice. Answer mapping still drops stale invalid values. Moving to or from Fitness test clears goalSeconds; changing between event goals keeps it.

The §6 copy list applies to Run and Both. The test Why sheet uses the interval-aim text only when testAim would return a value: a timed test with a target time or Cooper with a target distance. Tests without an aim use setup_flow_why_test_taper, "A date tapers the last ten days." PACER and HAMR always use that text.

### Preview, goal times and receipts

Your plan carries each stored cardio block into its day row, using the receipt's blockValue formatter. A cardio row with zero lifts shows only minutes in its trailing content. Run exposes zero, one or two strength blocks. No run session shows NoRun(minutes), with Longer sessions when available and Separate days only when Together is offered and the mapped answer is not already Separate days. Start remains enabled. NoRun replaces DateTooClose when no run fits; RunCapped, GoalCheck and LADDER otherwise follow the planner, and Minutes remains the last Why line.

Run names use the running goal; Both names use “Lifting and” plus that goal. The longest plural name is 39 characters. ClockText.duration formats m:ss below an hour and h:mm:ss from an hour; ClockText.parse accepts both. Setup and the Plan goal editor share them. Plan target, best, clock gaps and both prediction times use duration. The set-screen parser and displays keep their existing format. Just finish saves a Timed goal with null underSeconds, retaining event and date; A time stores INVALID_TIME until valid, and PlanGoalDraft.goal rejects every negative underSeconds. The PlanGoalDraft Parcelable is unchanged.

Setup goal times and the marathon best, and the Plan goal editor's time, use NumberField's minimum of distance in meters divided by 10 m/s, rounded down, with a minimum of one second. The Plan editor derives it from the draft's meters, keeping one second at zero meters. Two-part input remains m:ss: a Marathon goal of 3:30 is 210 seconds, shows the range error and remains invalid; 3:30:00 is accepted, as is an 800 m goal of 1:41.

Re-plan acceptance reports Moved, RunOpen, Gone or Waiting. Undo names UndoneReplanKept when an open workout sits on an inserted day and prevents its removal; that cause precedes the resulting ladder mismatch. Other restoration, including counters, proceeds. The three corresponding receipt messages distinguish an open workout, an ended offer and a kept phase.

Backup v31 carries Run and Both recipe v2, credited/count-dated ladder rules and untimed Timed goals. A raw-payload check rejects those shapes before v31 with “requires schema v31”, before legacy shaping can discard them. v1 through v30 otherwise retain their decoder, and v30 stays explicitly covered. Existing recipe and cardio-rule encoders are unchanged.

### Pilots, render gate and departures

run-to-today taps Run, six Next buttons, Start and expects Start day A. Its default plan is LADDER: A, B and C have CardioRule(1), D is Rest. both-to-today taps Both, eight Next buttons, verifies Lifting and running, then Next and Start. The core CI list includes Run; all includes both pilots. The original wizard-to-today and every other existing pilot are unchanged.

Setup render fixtures s23 through s51 cover each new surface at 1x and 2x, plus the event Why sheet. Progression fixtures p18-receipt-ladder through p25-plan-goal-hours cover the ladder receipt, its Why sheet, weekly hold, Ask re-plan, Keep, the Level 2 chip at both scales and hour goal prediction. Additional 2x receipt, Why and Keep captures meet the lead's entry gate. Existing s01 through s22 fixture calls remain intact; Goal's new choices necessarily appear in its existing screenshots as well. CI already publishes both fixture classes in set-screen-preview. The artifacts must be inspected before merge.

Departures from the 2026-11-08 mock: Runs a week is not asked because it has no planner input; the work-around chips other than running injury have no planner rule and are not asked; Cooper's date is hidden under owner decision 2. Sprint and Both with sprint remain deferred.

### Changed expectations and files

| Existing test | Old → new | Reason |
|---|---|---|
| RunReplanFinishTest, an Ask plan offers the re-plan at the wrap | true → ReplanAccept.Moved | The accepted merge was written |
| RunReplanFinishTest, a ladder only Ask plan offers the goal's own plan | true → ReplanAccept.Moved | The accepted merge was written |
| RunReplanFinishTest, accepting a re-plan refuses when it cannot move, open workout | false → ReplanAccept.RunOpen | The workout blocks acceptance |
| Same test, cleared record | false → ReplanAccept.Gone | No offer remains on the record |
| RunReplanFinishTest, a re-plan guard that now waits keeps the offer | false → ReplanAccept.Waiting | nextPhase cannot move |
| RunReplanFinishTest, a strength Finish carries an Ask re-plan offer | true → ReplanAccept.Moved | The accepted strength-record merge was written |
| RunReplanFinishTest, undo keeps a re-plan while a run is open on a day it inserted | UndoneLadderKept → UndoneReplanKept | The inserted open day is the cause |
| PlanCardioGoalScreenTest, an untimed goal shows its distance and best without a gap | draft.goal() null → the same untimed goal | Blank time saves Just finish |
| PlanViewModelTest, invalid cardio goal drafts cannot save and cancel leaves storage alone | Blank time 0 treated as invalid → negative time −1 treated as invalid; a new test saves 0 with its date | Blank time saves Just finish |
| SetupFlowScreenTest, Goal and restore | Run/Both absent → present; Sprint absent unchanged | The requested Goal choices supersede the old Lift-only assertion |
| SetupFlowScreenTest, large font keeps every step usable | Eight enum entries on a Lift fixture → thirteen entries with a Both fixture for the five new steps | Preserve its every-step subject and the preceding-step fallback |

Every changed assertion has its PR 7c comment. BackupV30Test now explicitly sets version 30 on its partial-profile fixture, keeping its existing 30 expectation and v30 subject. No planner/progression number or pinned §11.2 case changes. The default Lift plan expectation is unchanged.

Production: app wizard SetupDraft, SetupFlowModels, SetupFlowStateBuilder, SetupFlowViewModel, SetupFlowScreen, SetupFlowSteps, SetupFlowPlan, new SetupFlowRun and SetupFlowBoth; AppNavHost callback wiring; plan PlanGoalDraft and PlanCardioGoal; day ReceiptCardioLines, ReceiptProgress, SessionFinisher and SessionReceiptScreen; strings.xml. Domain: units/ClockText. Data: TrackerRepository and FinishUndoState. Transfer: BackupDocument and BackupCodec. Pilots: run-to-today.json, both-to-today.json and playtest.yml. Docs: this revision and DESIGN.md.

Tests: SetupDraftJsonTest, SetupFlowStateBuilderTest, SetupFlowViewModelTest, SetupFlowScreenTest and shared SetupFlowFixtures; SetupFlowPreviewShotsTest and ProgressionPreviewShotsTest; PlanCardioGoalScreenTest, PlanViewModelTest and ReceiptScreenTest; ClockTextTest; PlannedPlanTest and RunReplanFinishTest; BackupV30Test and new BackupV31Test.

No network, compilation, Gradle, builds, tests or subagents were used. CI results, pilot execution and generated render artifacts remain pending.


## 61. Revision 52 (PR 7d, sprint days ready, 2026-11-18)

### Start inputs and the public decision

Every opening receives the stored program, display unit and TrackerRepository.zone. The pure CardioPlan.of builder reads the stored rotation, goal and setup answers after the repository applies a replay's pre-write override. The day state, new cardio runs, preview and watch projection use it. Start dates its decision in the repository clock's zone, the same zone Finish uses for the frozen session's local Start date. The watch receives the decided block through the existing cardioOpening field.

SprintDose and SprintDecision are public. sprintDecision exposes the existing restart, B tempo, admitted trial, normal, shortened and substitute decision without changing its order or allowance. Every sprint CardioOutcome names that decision's dose; other outcomes retain null. sprintPlannedBlock exposes the build-up and current range before trials and allowance. The latest maximal date shares SprintContext.ofHistory's own-day, positive-maximal-meter filter; tempo and empty sessions do not become maximal dates.

### Set screen and rest rows

Before Start, with nothing logged today, the sprint card carries its dose, shortened WORK count, local gap, spacing tier, reason and intro-effort flag. Copy sits where the cardio chip would sit, in bodyMedium and onSurfaceVariant. A shortened dose first shows the approved allowance plural; a substitute first shows its approved tempo-substitution line. A maximal dose then shows the required Start note only when the known gap is below the spacing tier and the dose is not a substitute. Gap zero uses today, one uses yesterday, and larger gaps use the days-ago plural. A gap of three shows no note: the largest tier is three and the comparison is strict.

The three-day tier uses the reason in this order: 40-or-older or older-adult answer, hamstring strain, first weeks, then For now. The two-day tier always says For now. First weeks includes the existing intro, Nordic and New-lifter exposure rules. The careful group's maximal intro adds the nine-tenths effort line last. Tempo plan days have none of this advice. Start remains available. Preview and watch carry the block without this copy.

restBeforeSprint reads rotationDays in order, wrapping the last entry to the first. A REST row immediately before a maximal sprint day says “Rest before your next sprint day.” Today shows it under the plan name; Plan replaces its generic rest supporting text. At 100 m, two-day rotations mark B and D, three-day rotations C and E, four-day rotations C and F, and five- and six-day rotations only C. Tempo is not a maximal next day.

### Receipts, Plan and editing

Every sprint judgment, including replay and Ask, writes result inside progressionUndoJson. It records change, dose, whether credits rose, a reduced intro count when present, held=false and the outcome's planned next block. The existing RunResult reader handles ruled runs and sprints. Sprint receipts show the planned block as Next time, with the existing spoken same-value template. Why precedence is Restart, trial, shortened, substitute, B tempo, Step, completed intro, remaining intro plural, first credit, then no line. Repeat outcomes with a sprint explanation expose the existing Why action; otherwise these records would have text but no reachable sheet.

Plan rows count sprintPlannedBlock at the current steps rather than creation-time block columns. The sprint editor shows its planned block and “Sprint sets follow the plan.” It hides Edit cardio, Next time including Keep, and Remove cardio. Today-only rounds edits remain available and retain the structural-credit guard. Opening and judging ignore a sprint day's stale keepToPlan flag so it cannot bypass allowance.

The four design calls are retained: no sprint chip because Ask also writes sprint progression; copy belongs on the set screen before Start; the sprint goal row and its editing wait for PR 7e's timer and attempts; and TrackerRepository.zone owns local sprint dates. A sprint goal also hides Add goal so its unavailable editor cannot replace it with a timed goal. CardioRun, saved state, Room, backup v31, recipe bytes, rule bytes and the watch wire are unchanged. Sprint setup remains closed until 7f.

### Fitness-test target

NumberField's optional onDraftEdit reports raw text and the resolved number on each edit. Only the fitness-test target uses SetupTime's invalid marker: blank stays null, while nonblank malformed or below-floor text becomes INVALID_GOAL_TIME. For the 2-mile test, “16” and “1:00” wait, blank permits no target, and “16:00” stores 960 seconds. The event's existing A time behavior and all other setup behavior remain unchanged.

### Changed expectations

| Existing test and assertion | Old → new |
|---|---|
| ProgressionSprintTest: two counted sessions add one total unit whatever the maximal effort word, assertion 1 | `dose` implicit null → `SprintDose.NORMAL`; block, rule and change unchanged |
| ProgressionSprintTest: an unfinished WORK set or Pain does not count and Hard tempo keeps its credit, assertion 1 | `dose` implicit null → `SprintDose.NORMAL`; block, rule and change unchanged |
| ProgressionSprintTest: an unfinished WORK set or Pain does not count and Hard tempo keeps its credit, assertion 2 | `dose` implicit null → `SprintDose.NORMAL`; block, rule and change unchanged |
| ProgressionSprintTest: intro ends after three counted sessions without earning credit, assertion 1 | `dose` implicit null → `SprintDose.NORMAL`; block, rule and change unchanged |
| ProgressionSprintTest: restart precedes trial and B tempo substitution and counts intro session one, assertion 1 | `dose` implicit null → `SprintDose.RESTART`; block, rule and change unchanged |
| ProgressionSprintTest: admitted trial records its rotation and preserves step credit and intro, assertion 1 | `dose` implicit null → `SprintDose.TRIAL`; block, rule and change unchanged |
| ProgressionSprintTest: tempo never restarts and a trial substitute leaves B rule untouched, assertion 1 | `dose` implicit null → `SprintDose.TEMPO`; block, rule and change unchanged |
| ProgressionSprintTest: range tops hold and sprint days bypass the run cap and require context, assertion 1 | `dose` implicit null → `SprintDose.NORMAL`; block, rule and change unchanged |
| ProgressionSprintTest: a sprint step past the session minutes waits with its credit, assertion 1 | `dose` implicit null → `SprintDose.NORMAL`; block, rule and change unchanged |
| ProgressionSprintTest: a sprint step past the session minutes waits with its credit, assertion 2 | `dose` implicit null → `SprintDose.NORMAL`; block, rule and change unchanged |
| SprintAllowanceTest: an edited-down restart writes the restart state without counting, assertion 1 | `dose` implicit null → `SprintDose.RESTART`; block, rule and change unchanged |
| SprintRulesTest: a careful restart writes the six session intro, assertion 1 | `dose` implicit null → `SprintDose.RESTART`; block, rule and change unchanged |
| SprintRulesTest: a careful restart writes the six session intro, assertion 2 | `dose` implicit null → `SprintDose.RESTART`; block, rule and change unchanged |
| PlannerSprintProgressTest: trial preserves A stored step and credit for the next normal session | `dose` implicit null → `SprintDose.TRIAL`; stored block, rule, trial cycle 4 and change unchanged |
| DayViewModelCardioExecutionTest: aCappedOpeningShowsBeforeStartWritesNothingAndFreezesAtStart | Opening call without program/unit/zone → call with all three; 3480 seconds → 3480 seconds |
| DayViewModelCardioExecutionTest: anAddedSetBeforeStartKeepsTheFrozenOpening | Opening call without program/unit/zone → call with all three; stored 3600-second block → same stored block |

Every changed expectation has its PR 7d comment. SprintRulesTest's two careful-restart comparisons and PlannerSprintProgressTest's trial-preserves-state comparison also need the new dose even though §11 named only ProgressionSprintTest, SprintAllowanceTest and SprintFinishTest. All numeric progression and allowance expectations, including all existing SprintFinishTest assertions and pinned §11.2 cases, remain unchanged. No existing sprint undo byte expectation existed to replace; new coverage pins result bytes for a normal first-credit Finish. Run result bytes keep omitting the new default-null fields.

### Files and verification

Production: domain progression SprintRules.kt and CardioOutcome.kt; data CardioJudge.kt, TrackerRepository.kt and serialization/ProgressionUndoJson.kt; app day DayScreenBuilder.kt, DayScreenModels.kt, DayViewModel.kt, WorkoutSetScreen.kt, DayEditSheet.kt, DayEditor.kt and DayEditModels.kt; receipt ReceiptCardioLines.kt and ReceiptProgress.kt; preview PreviewViewModel.kt; sync TodaySnapshotSource.kt; plan PlanScreenBuilder.kt, PlanModels.kt and PlanScreen.kt; today TodayViewModel.kt, TodayModels.kt and TodayScreen.kt; wizard SetupFlowRun.kt; components NumberField.kt; res/values/strings.xml. ReceiptProgress.kt is the one production addition to the proposed file list, needed for the Why action.

Tests: SprintRulesTest, ProgressionSprintTest, SprintAllowanceTest, PlannerSprintProgressTest, new CardioJudgeSprintStartTest, SprintFinishTest, DayViewModelCardioExecutionTest, DayScreenBuilderTest, WorkoutSetScreenTest, ReceiptScreenTest, TodayScreenTest, PlanScreenBuilderTest, PlanScreenTest and DayEditSheetTest; the fitness-test regression in SetupFlowScreenTest; shared SprintUiFixtures; ProgressionPreviewShotsTest and SetupFlowPreviewShotsTest. The four requested fontScale=2 tests cover the Start note and allowance, sprint receipt, rest row and editor. Render fixtures p26 through p45 cover every sprint surface at both scales; p46 and p47 cover the invalid 2-mile target with Next disabled. Existing CI already includes both fixture classes in set-screen-preview.

The existing recipe encoder rejects sprint answers, including createPlannedPlan's call, and its bytes are frozen until 7f. Repository and ViewModel sprint tests therefore use the existing authored-plan plus generated-program fixture rather than opening sprint setup or changing the recipe. Plain JVM tests supply careful answers directly to CardioPlan.

This supersedes revision 45's “app wiring remains PR 7's work” and revision 50's loss of sprint receipt Next time. It changes no sprint progression, trial admission, allowance, markers or Undo rule. No network, compilation, Gradle, builds, tests or subagents were used. Source inspection, diff whitespace validation and resource XML parsing completed; CI, pilots and generated PNG inspection remain pending.

## 62. Revision 53 (PR 7e, the sprint timer, typed times and the sprint goal, 2026-11-18)

Sprint setup remains closed until 7f. This implements owner decision 8 and Q18: phone beep-to-tap timing is its own PHONE series, compared only at the same distance and start protocol. Typed times retain their method. The record label is “Phone-timed best.” There is no watch timing.

### Six design calls

1. The beep follows Start on the beep by five seconds. `SPRINT_BEEP_DELAY_MILLIS = 5_000L` is a practice choice that leaves time to set the phone hand at the start line and settle. The measured time starts at the scheduled beep instant, including after process restoration. Phone times include the tone's start latency, a known small slow bias covered by “Approximate” in the phone note.
2. Enter time finishes the current rep. Its labeled Time (s) field uses seconds and hundredths, or m:ss.hh from a minute. Hand stopwatch is the initial method; Electronic gates, Phone video and Phone timer are explicit choices. Save requires a positive time within `SprintTime.MAX_CENTIS`.
3. Start type defaults to the run's chosen protocol, then the newest timed result on the same plan day, then Standing. Three-point, Two-point, Blocks, Standing and Flying use the stored enum names. The next recovery shows only the preceding work rep's time and method; a phone result carries the exact phone note.
4. Only the first judgment of an admitted trial on the plan's first maximal sprint day appends goal attempts. Admission comes from 7d's `outcome.dose == TRIAL`; this adds no judgment or progression rule. Every finished timed work rep still writes its own timing columns, including ordinary sessions. `SprintComparison.attempts` supplies the best per series in first-appearance order.
5. Sprint goals have a separate row and dialog. The row shows event distance, optional target, the newest series' standing best, its start type and the optional date. The dialog edits target and date or removes the goal; it cannot change distance or kind. The generic save refuses to replace a stored sprint goal. `PlanRepository.setGoal` preserves stored sprint attempts at the same distance.
6. Backup remains v31. The timing columns have been in backup since v27 and sprint goals and attempts since 5a. Room, DataStore, recipe JSON, goal JSON and watch shapes do not change. Sprint recipe answers and backup v32 remain in 7f.

### Saved state and atomic Finish

The new keys are `day_sprint_timer` (`SprintTimerState`), `day_sprint_times` (`List<CardioSprintTime>`), `day_sprint_protocol` (protocol name), `day_sprint_time_draft` (`SprintTimeDraft`) and `plan_sprint_goal_draft` (`SprintGoalDraft`). Existing keys and Parcelables, including `CardioRun` and `PlanGoalDraft`, retain their field order and types. Half-typed rep and goal text survives restoration through the new drafts. A timer from another boot or more than ten minutes past its beep clears without storing a time. A restored future deadline can beep; a passed deadline never beeps again. ToneGenerator uses STREAM_ALARM at volume 100, creates one 200 ms beep and releases the generator afterward, with no permission change.

Finish passes the run's timed readings through the new `resultSets(gps, times)` overload; the old overload remains unchanged. The last matching time for a finished work position wins. Timing columns, sprint rule writes and admitted trial attempts share the cardio transaction. A goal-write failure rolls them all back. Only that first commit appends attempts; re-rating and distance replay retain them without appending again.

`CardioUndoDto.goal` is an optional default-null `CardioGoalUndoDto(before, after)` inside `progressionUndoJson`. Undo restores the exact previous goal JSON only while the current JSON still equals `after`. A later goal edit is preserved. Non-trial undo encodings continue to omit the new field.

The History single-set edit first tries `toDomainOrNull()`, then decodes a copy with only the three timing columns cleared. Updates copy the stored entity, preserving unreadable timing bytes. The amendment, readings, history flows and bests paths already use `toDomainOrNull()`; the remaining `SetDto::toDomain` call decodes strength sets and cannot see a cardio timing row. Backup validation stays strict.

### Changed expectations

| Existing test | Old → new | Reason |
|---|---|---|
| PlanScreenBuilderTest, a sprint goal shows no goal row before PR 7 | Hidden sprint goal with `goal == null` → separate `PlanSprintGoalUi(Sprint(100), null)` while generic `goal` remains null | The sprint row and its editor land together; the test is renamed “a sprint goal shows its own goal row” |
| PlanScreenTest, a sprint goal cannot open the timed goal editor | Hidden row; navigate to Light week → visible “100 m” and “No attempt yet”; no Add goal while the sprint goal exists | The sprint goal now has its own surface. Boolean fixture true/false becomes sprint UI/null; clearing it still shows Add goal |

Both expectation updates carry their PR 7e comments. No existing numeric expectation changes, including SprintFinishTest, SprintAllowanceTest and pinned §11.2 case 37. Case 37 gains only the end-to-end test name. There are no unpredicted numerical re-pins.

### Files and verification

Production: domain `units/ClockText.kt`; data `TrackerRepository.kt`, `serialization/ProgressionUndoJson.kt`, `HistoryRepository.kt` and `PlanRepository.kt`; app day `SprintTimer.kt`, `SprintTimeSheet.kt`, `CardioRun.kt`, `DayViewModel.kt`, `DayScreenModels.kt`, `WorkoutSetScreen.kt` and `DayScreen.kt`; app `ui/AppNavHost.kt`, `di/TimeModule.kt`, plan `PlanScreenBuilder.kt`, `PlanModels.kt`, `PlanScreen.kt`, `PlanCardioGoal.kt`, `PlanViewModel.kt`, `PlanGoalDraft.kt` and `res/values/strings.xml`. Docs: this revision and the case 37 test list in `STRENGTH_TRACKER_SPEC.md`. This is the specified 23 production and documentation files.

Tests: new `DayViewModelSprintTimerTest`, `SprintTimeSheetTest` and `CardioRunSprintTimeTest`; additions to `ClockTextTest`, `HistoryRepositoryCardioEditTest`, `PlanGoalTest`, `WorkoutSetScreenTest`, `PlanScreenBuilderTest`, `PlanScreenTest`, `PlanViewModelTest` and `PlanCardioGoalScreenTest`. Shared `SprintUiFixtures` and `ProgressionPreviewShotsTest` define p48 through p61, the seven new surfaces at 1x and 2x. The four requested largest-font tests cover timer controls, typed entry, goal row and goal editor. `PlaytestModules.kt` adds only the no-op SprintBeeper binding required because its test module replaces TimeModule; pilot scenarios and expectations are unchanged. Workout callbacks extend the existing `DayActions` type; this checkout has no `WorkoutSetActions` type.

As revision 52 records, the frozen recipe encoder rejects Sprint answers, including through `createPlannedPlan`. The new repository/ViewModel fixture therefore follows SprintFinishTest's authored-plan plus generated sprint-program setup. It does not open sprint setup or alter recipe encoding. The referenced `fix/pr7/plan.md` is absent from this checkout; the implementation follows the supplied 7e brief and repository guidance. The held-marker setup time-field follow-up remains in 7f's SetupFlowRun.kt work.

No network, Gradle, compilation, builds, tests or subagents were used. Static source review, XML parsing, preserved-shape inspection and diff whitespace checks are the local verification. CI on domain, data, transfer and app, the build, pilots and p48–p61 PNG generation and inspection remain pending.


## 63. Revision 54 (PR 7f, Sprint and Both + sprint setup, 2026-11-18)

Sprint and Both with a sprint sub-goal are open in setup. Sprint asks Goal, Which sprint, Your week, Equipment, About your training, Recent numbers when a trained lifter has a weighted main, and Your plan. Both + sprint also asks Lifting goal and, for Strength or Muscle with available choices, Your lifts. It asks no Which first, Your event, Where you are or Together. Lift, Run and Both with a run retain their steps and mapping.

### Design calls

1. Which first stays before Both's cardio goal. Selecting a sprint event removes Which first and Together from the step list. Mapping normalizes cardioFirst to false and together to null.
2. Sprint fitting explanations compare each fitted day to its 90-minute day with KnownLoads.NONE. Absent slots use the library's short names; a kept slot with fewer targetSets contributes “extra sets.” The comparison includes cumulative cuts, plyometrics and Nordic fallbacks. Step 0 retains the floor explanation.
3. Longer sessions goes to Planner.fullDoseMinutes on a shortened sprint plan, with “The full plan needs N minutes.” A null result sets 90 and omits that line. Lift and Run keep the next minutes option.
4. Split-day advice appears once in Why this plan. The suggested week, spacing tier and high-day order appear above the day rows.
5. Backup v32 carries Sprint and Both + sprint recipes. Older builds reject v32; older documents carrying either sprint recipe shape are inconsistent.

### Answers and floor

About retains lifting experience and 65 or older, then asks about all-out sprinting in the past month, a hamstring strain in the past 12 months, and 40 or older. The Running row is absent. These answers reach SprintAnswers and make the careful group live. They remain setup answers rather than a profile. The setup draft remains version 1 under setup_draft, with new fields taking defaults.

Planner.sprintLowerMainMissing exposes the same resolved-main check used by sprintDraft. Both + sprint needs a squat or hinge; the careful group needs a squat. Your lifts shows the owner-bound error and prevents Next. Preview checks before invoking the planner, shows NeedsLowerMain, disables Start and offers Review answers to Your lifts when present, otherwise Equipment. The drop list excludes a main whose removal would break the floor; the mutation also keeps that floor.

### Suggested week and fitting

| Days | Weekdays |
|---|---|
| 1 | Mon |
| 2 | Mon, Thu |
| 3 | Mon, Tue, Thu |
| 4 | Mon, Tue, Thu, Fri |
| 5 | Mon, Tue, Thu, Fri, Sat |
| 6 | Mon, Tue, Thu, Fri, Sat, Sun |

The rotation supplies Sprint, Upper and Tempo kinds. An Upper entry that becomes Rest is omitted without moving later entries to earlier weekdays. The three-day spacing tier reads sprintFirstWeeks or careful. Jumps and Nordic flags read fitted slots. Rest entries immediately before a maximal sprint use the existing rest-before-sprint label.

The one-day 100 m intro fixture at 30 minutes keeps pogo, A-skip and squat at ShortSession(A, 3). Compared with 90 minutes, its dropped names are [RDL, Bulgarians], plus extra sets. Three-day 100 m non-intro at 30 minutes has ShortSession(D, 7): D drops [Pogo Hop, A-Skip], plus extra sets, and keeps the Nordic. Two-day non-intro 100 m offers 75 minutes; 200 m offers 90. The split-day line remains in the disclosure, and Minutes remains last.

### Distance display and fixes

The lead amendment supersedes the shared-formatter proposal. SprintEvent owns yardsFor and label; 40 yd remains stored as 37 m. Only sprint reps, sprint receipts, the Plan sprint goal and editor, sprint plan names and setup event rows use that label. DistanceUnit, typed distances and History keep their formatting. The Plan goal's special case and sprint_event_40yd resource are removed.

The proposed half-marathon 1:30:00 fixture stays valid on switching to Marathon: the unchanged floor is floor(42195 / 10) = 4219 seconds, or 1:10:19. The regression checks that case remains enabled, then uses 1:00:00 (3600 seconds, above the half floor of 2109 and below the marathon floor) to exercise the invalid state. No floor rule changes.

NumberField accepts an explicit error marker. A time cleared after a floor change stays blank, shows the existing range error and keeps Next disabled. The armed ticker delays by the smaller of 250 ms and the time remaining before the beep, then uses 16 ms ticks. The armed-rep test also asserts that End rep is absent.

### Storage and expectations

Recipe v2 adds a sprint object and Both's default-false sprint flag. Sprint plans store no run object or phase. Existing Lift, Run and Both-with-run recipe bytes remain unchanged. Backup exports use v32 and retain v31 decoding. Room, DataStore, saved-state keys, Parcelables, cardio rule and goal JSON, progression and the watch wire are unchanged.

| Existing expectation | Old → new |
|---|---|
| SetupRecipeJsonTest, sprint encoding | Sprint and Both + sprint refused → round trip, including the careful answers |
| SetupFlowScreenTest, first-run Goal | Sprint absent → fourth goal visible |
| SetupFlowScreenTest, valid 800 m time switched to 1500 m | Blank invalid target without error → blank invalid target with range error |
| SetupFlowScreenTest, invalid 1500 m time switched to 800 m | Blank invalid target without error → blank invalid target with range error |
| WorkoutSetScreenTest, armed rep | Countdown and Stop asserted → End rep absence also asserted |
| Export checks using CURRENT_SCHEMA_VERSION | 31 → 32; explicit v31 round trips remain 31 |

Each edited existing expectation carries a PR 7f comment. There are no numeric re-pins, and no formatting expectation for a non-sprint 37 m distance changes. Existing setup render definitions s01 through s51 and the three existing setup pilots are unchanged; Goal renders necessarily gain the new entry. The Plan sprint editor receives its distance as presentation input while its saved Parcelable stays unchanged.

### Files and verification

Production: domain planner Planner, SprintAnswers and SprintLayout; data serialization SetupRecipeJson; transfer BackupDocument and BackupCodec; app wizard SetupDraft, SetupFlowModels, SetupFlowStateBuilder, SetupFlowViewModel, SetupFlowScreen, SetupFlowSteps, SetupFlowRun, SetupFlowLifts, SetupFlowPlan and new SetupFlowSprint; NumberField; WorkoutSetScreen; receipt ReceiptCardioLines, ReceiptText and ReceiptRowItem; SessionReceiptScreen; PlanCardioGoal, PlanScreen and AppNavHost; strings.xml. DistanceUnit has no production change. Docs: this revision and DESIGN.md. Pilots: sprint-to-today, both-sprint-to-today and playtest.yml.

Tests add sprint draft, steps, normalization, prefill, floor, fitting, full-dose, week, high-day, plan-name, recipe and backup coverage, a careful answer reaching Start, and non-sprint 37 m regressions. SetupFlowPreviewShotsTest defines s52 through s68; ProgressionPreviewShotsTest defines p62 through p65. Seven fontScale 2 cases cover the new setup surfaces. The source review includes resource XML, pilot JSON, test names, imports and diff whitespace. No network, builds, compilation, Gradle, tests or subagents were used. CI, pilots, PNG generation and lead inspection remain pending.

PR 7 is complete in scope with this PR. PR 8 carries SetupFlowRestoreTest's 10-second CI waits; remaining #610–#614 copy and SSOT nits (shared running-history filters, RunPhase fallbacks, reset eligibility, held-replan messages, Alternate's source, Ask acceptance and Undo cursor, duplicate Plan time invalidation, resolved-event fallback and time-floor documentation, parser semantics and timer lifecycle); accepted watch rebuild and nine-tenths cleanup notes; per-muscle balancing for Both + sprint Muscle; and the watch. Sprint timing method and best time in setup remain deferred under revision 31.
