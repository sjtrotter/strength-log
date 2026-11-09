# Setup wizard and plan generator: inventory before the rebuild

Date: 2026-11-08. Base: main at `c30109f5` (plus the docs-only `9d4e7757`). Read-only pass; nothing was built or run.

This maps what the setup wizard and plan generator do today, what reads their output, and where a replacement would sit. It is input for the Fable design pass. It is not a spec. `STRENGTH_TRACKER_SPEC.md` §6 and `docs/PLAN.md` A13–A18 stay authoritative until a design doc amends them. The evidence review is `docs/briefs/wizard-evidence-2026-11-08/report.md`. The tester findings are in the playtest kit repository (`strength-log-playtest-kit`), `results/r4-synthesis.md`.

**Reference format.** `domain:` is `domain/src/main/kotlin/cloud/trotter/log/strength/domain/`, `data:` is `data/src/main/kotlin/cloud/trotter/log/strength/data/`, `app:` is `app/src/main/kotlin/cloud/trotter/log/strength/`, `transfer:` is `transfer/src/main/kotlin/cloud/trotter/log/strength/transfer/`, and `domain-test:` is `domain/src/test/kotlin/cloud/trotter/log/strength/domain/`. `strings.xml` is `app/src/main/res/values/strings.xml`.

**Verdict column.** *Replace* means the rebuild owns it. *Keep* means it should survive unchanged. *Adapt* means it survives but its inputs or rules change.

---

## 0. The picture in one paragraph

The wizard (`app:ui/wizard/`) collects a `WizardAnswers` draft in `SavedStateHandle`. On the last step it calls `ProgramGenerator.generate(answers)` (`domain:generator/`), which returns a `Program`. A `Program` is days of exercise slots: an exercise id, a set structure, a set count, an optional superset partner, and an optional prescription. Days can also carry a cardio block. `PlanRepository.createGeneratedPlan` writes that program to Room, and the answers go into `plan.recipeJson`. The global lifter (bodyweight, age, level, unit) goes to DataStore. **The generator writes no loads, except for novice mains and lifts the user entered.** Loads are computed lazily: the first time a day is viewed or started, `DaySeeds`/`SlotSeeds` run `GoalCalculator`, a bodyweight-ratio formula, against the *current* global lifter and write `exercise_log` working values. Those values are "seeded once, then persist." After each workout, `CarryForward.next` rewrites the working values. `CarryForward.offer` computes a "+N" increase that the lifter must accept with a chip. The recipe is re-read later, too. Reset day to template regenerates a day from it, History's main-lift list re-runs the generator skeleton, the Plan screen shows formula goals from it, and the slot-swap list and the watch's alternates filter by its equipment.

---

## 1. Wizard flow

### 1.1 Files

| File | Role | Verdict |
|---|---|---|
| `app:ui/wizard/WizardStateBuilder.kt` | Step order per branch (`steps`, :29-43), split fallback (:51-54), preview rows (:56-70), recent-lifts rows (:110-114) | Replace |
| `app:ui/wizard/WizardModels.kt` | `WizardStep` enum (:25-40), `WizardUiState` incl. `canContinue` gate (:78-79), `WizardActions` | Replace |
| `app:ui/wizard/WizardViewModel.kt` | One `SavedStateHandle` key per answer (:81-120), `currentAnswers()` (:184-218), navigation (:278-300), `finish()` (:431-467), plan naming (:469-487), first-run restore (:507-555), prefill (`applyAnswers`, :574-597) | Replace; the finish write order, restore lock and first-run latch are worth keeping |
| `app:ui/wizard/WizardScreen.kt` | One composable per step (:197-776), footer (:780) | Replace |
| `app:ui/wizard/DeviceWeightUnitProvider.kt` | First-run lb/kg default from the device locale | Keep |
| `domain:generator/WizardAnswers.kt` | `WizardAnswers`, `CardioFirst`, `AnchorScheme`, `DeadliftVariant`, `SplitTemplate`, `LiftEntry` | Replace, but old recipes must still decode (§4.5) |
| `app:ui/AppNavHost.kt:122, 174-175, 268, 340, 813` | `wizard` route. It is the start destination while `wizardComplete` is false, and Plans "New plan" opens it | Adapt |

### 1.2 Step order by branch

`WizardStateBuilder.steps` (:29-43) owns the order, and it is recomputed from the draft on every Next and Back.

| # | Lifting first (`cardioFirst == null`) | Cardio first, "Also lift? No" | Cardio first, 2 or 3 lift days |
|---|---|---|---|
| 1 | FOCUS | FOCUS | FOCUS |
| 2 | EMPHASIS | CARDIO_ACTIVITY | CARDIO_ACTIVITY |
| 3 | DAYS_PER_WEEK | CARDIO_START | CARDIO_START |
| 4 | SPLIT | CARDIO_SESSIONS | CARDIO_SESSIONS |
| 5 | ANCHORS | ALSO_LIFT | ALSO_LIFT |
| 6 | CARDIO | ROTATION | EMPHASIS |
| 7 | ABOUT_YOU | | SPLIT (days = lift days) |
| 8 | EQUIPMENT | | ANCHORS |
| 9 | LIFTS | | ABOUT_YOU |
| 10 | ROTATION | | EQUIPMENT |
| 11 | | | LIFTS |
| 12 | | | ROTATION |

There is no other branch. "Hybrid" today means either lifting with run finishers or separate cardio days, or cardio first with 2 or 3 lift days. Running has no event, sprint, race date or volume questions. The plan's cardio goal (A18, `PlanGoal`) is set later on the Plan screen, not in setup.

### 1.3 Every question, its default, and where the answer goes

| Step | Question (strings.xml) | Options | Default when untouched | Feeds | Notes |
|---|---|---|---|---|---|
| FOCUS | "What comes first?" (:977) | Lifting / Cardio | Lifting (`wizard_focus` false, VM :152) | `WizardAnswers.cardioFirst` null vs. non-null (VM :186-192) | First run only: "Have a backup? Restore it" (WizardScreen :202, :352) |
| CARDIO_ACTIVITY | "Activity" (:980) | Family: Run / Ride / Row / Walk (WizardScreen :209-214); Where: Road/Trail/Track/Treadmill for Run, Outdoor/Treadmill for Walk, Outdoor/Stationary for Ride | ROAD_RUN (VM :153) | `CardioFirst.activity` | ELLIPTICAL exists (`domain:model/Cardio.kt:3-6`, `CardioFamily` :70-80) but setup doesn't offer it. `planName` handles it anyway (VM :477) |
| CARDIO_START | "Comfortable session" (:992) | Time (minutes) or Distance (in mi/km); distance-unit chooser | Time, 1,800 s (VM :154-156); distance default 5,000 m; unit suggested from weight unit on first run (VM :255-257) | `CardioFirst.comfortable` (`Measure.Time` or `Measure.Distance`) | This is the only cardio fitness input |
| CARDIO_SESSIONS | "Sessions per rotation" (:994) + "Rest days" (:1033) | Sessions 1–6; rest days 0–2 | 3 sessions; **1 rest day** (VM :157-158) | `CardioFirst.sessions`, `.restDays` | Defaults disagree: the VM uses 1, `CardioFirst()` uses 0 (`domain:generator/WizardAnswers.kt:70`), and the recipe DTO uses 0 (`data:serialization/PlanRecipeJson.kt:35`) |
| ALSO_LIFT | "Also lift?" (:996) | No / 2 days / 3 days (`LIFT_DAY_CHOICES`, WizardAnswers.kt:78) | No (VM :159) | `CardioFirst.liftDays`; with lift days > 0 it also sets `daysPerWeek` (VM :324-328, :195) | Lift days skip DAYS_PER_WEEK and CARDIO |
| EMPHASIS | "What are you training for?" (:133) | Strength-leaning / Balanced strength + muscle / Physique-leaning | BALANCED | `LifterConfig.emphasis`. Stored in the recipe, not global | Drives top-set reps 3/5/8 and the 0.75/0.68/0.60 load factor (§2.6). The Plan screen can change it later (`data:PlanRepository.kt:141-154`) |
| DAYS_PER_WEEK | "How many days a week can you commit?" (:134) | 2–6 stepper | 4 (`WizardAnswers.kt:43`) | `daysPerWeek`; changing it resets the split if it is no longer offered (VM :339-344, StateBuilder :51-54) | Lifting branch only. 1 day is not allowed (`require` :58) |
| SPLIT | "Your split" (:135) | Suggested and Alternative from `SplitDefaults.optionsFor` (§2.2) | The suggested split for the day count | `split` | At 2 days there is no alternative |
| ANCHORS | "Main lifts" (:136) | Prototype (Squat/Bench/Deadlift/Incline DB), Big 4 (…/Row), 5/3/1-style (…/OHP); Deadlift: Trap-bar / Conventional / Sumo | PROTOTYPE, TRAP_BAR | `anchorScheme`, `deadliftVariant` | Narrowing notice only on FULL_BODY (WizardScreen :466-480). Splits silently drop anchors (§6, item 1) |
| CARDIO | "Cardio" (:137) | Mode: Outdoor run / Treadmill / Bike-elliptical / None; Placement: Finishers / Separate days / Both (NONE is hidden, :532); "Keep me 5k-ready" switch | OUTDOOR_RUN, FINISHERS, 5k on (`domain:model/Types.kt:158-162`) | `CardioPrefs` | Lifting branch only. Placement is hidden when mode is None |
| ABOUT_YOU | "About you" (:138) | Unit lb/kg; bodyweight; age; level Novice/Intermediate/Advanced | Unit: device default on first run, else the stored unit (VM :254). **Bodyweight and age start blank, and Next stays disabled until both are ≥ 1** (`canContinue`, Models :78-79; VM :282, :559-560). Level is pre-selected INTERMEDIATE | `LifterConfig.bodyweightLb/age/level/weightUnit`; prefilled only when `hasLifterAnswers` (VM :252, :591-592) | Skipped entirely on cardio-first with no lift days, which caused D2 |
| EQUIPMENT | "What equipment do you have?" (:139) | 11 checkboxes + Select all / Clear all | Everything (`WizardAnswers.kt:51`) | `equipment`. Filters slot picks and anchor substitutions, and later swap and watch alternatives | No plate, dumbbell-step or machine-step info |
| LIFTS | "Your recent lifts" (:140-141) | Weight + reps per main lift that the generator placed (`ProgramGenerator.mainLiftIds`, StateBuilder :110) | Blank; reps default to `topSetReps(emphasis)` (:113) | `WizardAnswers.lifts` → load × reps prescription on that main (`ProgramGenerator.kt:80-106`) | **Not stored in the recipe** (PLAN.md A15b), so a new-plan wizard never prefills them and Reset day to template forgets them. Only mains get rows; accessories are always formula |
| ROTATION | "Your rotation" (:202) | Preview cards per day: title, cardio summary, emphasis line, lifts with set counts and seeded working heading (WizardScreen :728-776) | — | Start → `finish()` | Empty program → "These answers don't produce any days" plus "Review answers" (:866-867) |

### 1.4 What Finish writes (`WizardViewModel.finish`, :431-467)

Order is deliberate (crash safety). All of it runs under `BackupService.withRestoreLock`.

1. `PlanRepository.createGeneratedPlan(name, answers, previewProgram)` (`data:PlanRepository.kt:284-321`) inserts a `plan` row (origin GENERATED, `recipeJson = PlanRecipeJson.encode(answers)`, `suggestedDayId` = first training day, `offerCardioIncreases = answers.cardioFirst != null`), every `program_day`, and every `program_exercise`. It writes no `exercise_log`. If the selected plan has no days, that plan is reused instead of inserting a new one.
2. `publishCreatedPlan(id, answers, writeLifter = ABOUT_YOU in steps)` (:326-334): sets `onToday`, writes the global lifter to DataStore only if About you was asked, and selects the plan.
3. `settings.setUnit`; `setDistanceUnit` only on cardio first; `setWizardComplete(true)` last.
4. The created plan id is kept in `SavedStateHandle` (`wizard_created_plan_id`), so a retry reuses it.

Plan name (VM :469-487): "N-day full body / upper/lower / push/pull/legs / PPL + upper/lower", or "Running / Cycling / Rowing / Walking" (+ " + lifting") (strings.xml :43-46, :999-1003).

### 1.5 What the wizard decides without asking

| Decision | Where | Value |
|---|---|---|
| Profile when About you is skipped | VM :205-206 → `LifterConfig()` (`Types.kt:136-140`); `GlobalLifter` defaults (`data:GlobalLifter.kt:7-12`); `readGlobalLifter` (`data:prefs/SettingsStore.kt:495-501`) | 235 lb, age 40, INTERMEDIATE. These show as "You" and get stamped on sessions (D2) |
| Main-lift sets and reps | `ProgramGenerator.main` (:428-437), `StrengthStandards` | RAMP_TOP_BACKOFF, 6 rows: 4 ramps + top × 3/5/8 + one back-off × 8 |
| Novice mains | `seedMains` (:80-106) | 3 straight sets at the empty bar (45 lb or 20 kg) for barbell lifts, else the formula load; reps = top-set reps |
| Accessory sets, reps and load | `accessory` (:439-440), `SetSeeder.ACCESSORY_REPS` (`domain:seeding/SetSeeder.kt:35`), `GoalCalculator` | 3 × 10 at a bodyweight-derived or flat load |
| Exercise choice | `pickAccessory`/`bestAccessory` (:464-475), `ExerciseLibrary.byPattern` (`domain:library/ExerciseLibrary.kt:284-285`) | Rank-1 available entry per pattern that is not `GoalSource.Std` |
| Supersets | `armsSuperset` (:443-453) | Biceps + triceps on full-body days where `i % 3 == 1` (B, E) and on every Upper day |
| Goals ("Goal 180", "Goal met") | `GoalCalculator.goalFor` (`domain:standards/GoalCalculator.kt:42-58`) | Formula unless edited on the Plan screen (`goalsLb`) |
| Increment | `WeightStepper.increment` (`domain:units/WeightStepper.kt:19-22`) | +5 lb (+2.5 at ≤ 20 lb) or +2.5 kg (+1.25 at ≤ 9 kg), same for every lift |
| Increase policy | `PlanEntity.offerStrengthIncreases` default true (`data:db/entity/PlanEntity.kt:21`); cardio default false, true for cardio first (`PlanRepository.kt:298, 310`) | Offer chip, never auto-apply |
| Rest | `RestPolicy.defaultSeconds` (`domain:standards/RestPolicy.kt:31-37`) | By set kind: ramp 90, top 180, back-off 120, work 90, light 60 |
| Finisher type | `CardioPlanner.finisher` (`domain:generator/CardioPlanner.kt:16-22`) | Hard on leg-light days, easy on leg-heavy days, always easy for low impact |
| Interval structure | `CardioIntervals.plan` (`domain:generator/CardioIntervals.kt:19-33`) | 5 min warm-up + 5 × (2 hard / 2 easy), or 20 min tempo, or 25 min easy |
| Treadmill means running | `CardioBlocks.activityOf` (`domain:generator/CardioBlocks.kt:38-43`) | TREADMILL → TREADMILL_RUN; LOW_IMPACT → STATIONARY_BIKE |
| Standalone cardio days | `standaloneCardio` (:483-503) | Exactly 2 ("C1", "C2"), easy 25 min + one core lift, outside the rotation |
| Cardio-first session mix | `CardioFirstPlanner.program` (`domain:generator/CardioFirstPlanner.kt:19-26`) | Fixed Easy/Intervals/Long sequences by session count |
| Long run | `longMeasure` (:61-64) | 1.5 × comfortable, rounded to the minute |
| Cardio-first intervals | :34 | Always the 5k interval block, whatever the activity (a rower or walk too) |
| Rest-day placement | :43-57 | After Long, then after the first Intervals (or the first Easy) |
| Lift days in cardio first | :27-30 | The lifting generator with `cardio = NONE`, split from SPLIT (2 days: full body only; 3: FB or PPL) |
| Day letters | `DAY_IDS` (:56), compaction (:120-128); cardio first letters everything in order (:58) | A, B, C…; C1/C2 for standalone cardio |

### 1.6 Wizard state mechanics worth keeping

- Every answer is its own `SavedStateHandle` key (VM :81-120). A legacy step index is migrated (:126-131), so a rebuild that changes steps needs its own new key, per CLAUDE.md's Parcelable rule. `WizardLiftDraft` is `@Parcelize` (Models :43-44). Do not reorder it. Give a new shape a new key.
- A new-plan wizard prefills from the *selected* plan's recipe (`repo.wizardAnswersFlow`, VM :250-252). An answer the user already edited before init finishes is not overwritten (`EDITED_ANSWERS`, :562-572).
- First-run restore exists only on FOCUS and only when `wizardComplete` was false at entry (`FIRST_RUN`, :84-90, :507-555).

---

## 2. Plan generation

### 2.1 Entry points

| Function | File:line | Called by |
|---|---|---|
| `ProgramGenerator.generate(answers)` | `domain:generator/ProgramGenerator.kt:63-66` | Wizard preview and finish (VM :489); `templateFor`; `CardioFirstPlanner` for its lift days |
| `ProgramGenerator.mainLiftIds(answers)` | :69-73 | Wizard LIFTS rows; **History's main-lift list** (`app:ui/log/LogViewModel.kt:174`) |
| `ProgramGenerator.templateFor(answers, day)` | :133-140 | Reset day to template (`data:TrackerRepository.kt:577-602`); its availability check (`app:ui/day/DayEditor.kt:86-87`) |
| `ProgramGenerator.anchorIds/availableAnchorIds/activeAnchorIds` | :147-193 | Wizard anchor step; **Plan screen goal list** (`app:ui/setup/SetupStateBuilder.kt:22-35`) |
| `CardioFirstPlanner.program` | `domain:generator/CardioFirstPlanner.kt:18-59` | `generate` when `cardioFirst != null` |
| `CardioPlanner.finisher/standalone` | `domain:generator/CardioPlanner.kt:16-25` | Generator only |
| `CardioBlocks.fromLegacy/activityOf/legacyModeOf` | `domain:generator/CardioBlocks.kt:13-43` | Generator, **legacy cardio migration** (`data:serialization/LegacyCardio.kt:8-12`, `data:PlanRepository.kt:469`), History weekly distance (`app:ui/log/JournalBuilder.kt:213`), CSV import (`transfer:csv/HistoryCsvReader.kt:357`) |

### 2.2 Splits and day kinds (`domain:generator/SplitDefaults.kt`)

| Days | Default | Alternative | Day kinds |
|---|---|---|---|
| 2 | FULL_BODY | — | FB ×2 |
| 3 | FULL_BODY | PPL | FB ×3 / Push, Pull, Legs |
| 4 | FULL_BODY | UPPER_LOWER | FB ×4 / U, L, U, L |
| 5 | PPLUL | FULL_BODY | Push, Pull, Legs, Upper, Lower (fixed, :38-39) / FB ×5 |
| 6 | PPL | UPPER_LOWER | P, P, L ×2 / U, L ×3 |

`optionsFor` :15-22; `dayKinds` :29-40. A day nothing available can fill is dropped and the letters close up (`ProgramGenerator.kt:120-128`).

### 2.3 Anchors

`anchorIds` (:147-158): PROTOTYPE = `bb_back_squat, bb_bench, <dl>, incline_db`; BIG_4 = `…, bb_row`; FIVE_THREE_ONE = `…, ohp`. The deadlift is `trap_dl` / `conv_dl` / `sumo_dl`. An unavailable anchor becomes its pattern's best available weighted lift (:162-174). `activeAnchorIds` (:186-193) takes the first N anchors on FULL_BODY when N days ≤ anchor count. Its KDoc says every other split "assigns anchors by pattern match… so its whole anchor set stays active". That is not what happens (§6, item 1).

How anchors reach days:

- **Full body** (:218-275): the anchor at `i % used.size` is the day's main. With 4 days and 4 anchors, every anchor is placed.
- **Upper / Push** (:310, :360): `anchorFor(H_PUSH)`. This is always bench, because `incline_db` is H_PUSH too and comes later.
- **Lower / Legs** (:338-341, :409): hinge on even occurrences, squat on odd ones.
- **Pull** (:384): `anchorFor(H_PULL)`, which is `bb_row` in BIG_4. Otherwise `pickMain(H_PULL)` returns `bb_row` anyway (rank 1, `ExerciseLibrary.kt:157`).
- No split day asks for a V_PUSH main. OHP (`ExerciseLibrary.kt:143`, `Std(OHP)`) is skipped by the accessory picker because it is Std-sourced. The upper day's V_PUSH slot gets the next ranked non-Std press (:314).

### 2.4 Day skeletons

| Day | Slots in order (file:line) |
|---|---|
| Full body (:238-266) | anchor main; opposing compound (chest if the main is lower, else squat or single-leg); hinge-or-squat complement; pull (chest if the main is a pull); isolation rotating side delt → arms superset → calf (:279-284); core rotating anti-ext → anti-rot → flex (`CORE_ROTATION`, :57). Odd days flip incline/vertical pull/knee flexion/soleus/single-leg (:232-236). Emphasis line :286-298 |
| Upper (:311-319) | H_PUSH main; H_PULL; V_PUSH; V_PULL; side/rear delt alternating; arms superset; core |
| Lower (:342-349) | hinge or squat main; the other pattern; single-leg; knee flexion; calf alternating; core |
| Push (:361-368) | H_PUSH main; incline chest; V_PUSH; side delt; triceps; core |
| Pull (:385-391) | H_PULL main; V_PULL; rear delt; biceps; core |
| Legs (:411-417) | hinge or squat main; single-leg; knee flexion; calf; core |
| Cardio + Core (:483-503) | one core accessory + a standalone easy block; kind CARDIO; ids C1, C2 |
| Cardio first (CardioFirstPlanner :31-58) | Easy / Intervals / Long days (kind STRENGTH with no exercises and a cardio block), lift days interleaved after the run days, REST entries |

### 2.5 Slot fill

- `main(id)` (:428-437) must be WEIGHTED and yields `RAMP_TOP_BACKOFF`, `targetSets = 6`.
- `accessory(entry)` (:439-440) yields STRAIGHT, `targetSets = 3`, no prescription.
- `pickAccessory`/`bestAccessory` (:464-475): best-ranked available entry by `subRank`, excluding `GoalSource.Std`. `pickChest` biases flat versus incline by id set (:59, :468-472).
- `pickMain` (:456-459): best-ranked available WEIGHTED entry, used when no anchor fits.
- Equipment filtering is `ExerciseEntry.availableWith` (`domain:library/ExerciseEntry.kt`, last lines): every listed piece must be ticked.
- The library has 184 entries (`domain:library/ExerciseLibrary.kt`). Std-sourced, main-capable lifts: back squat, trap/conv/sumo deadlift, bench, incline DB, OHP, barbell row (:59-157).

### 2.6 Sets, reps and loads

The generator emits shape only. Loads come from `SlotSeeds.main` (`domain:seeding/SlotSeeds.kt:18-25`). If the slot has a prescription, the load is the prescription (`SetSeeder.seedPrescribed`, `SetSeeder.kt:21-33`). Otherwise it is `GoalCalculator.targetFor(entry, config)` → `SetSeeder.seed` (:45-75).

**Main-lift goal** (`GoalCalculator.goalForMain`, `GoalCalculator.kt:25-36`):
`bodyweightLb × ratio[level][lift] × tune[lift] × ageDerate(age) × maintenanceFactor(emphasis)`, halved for per-hand lifts, rounded to 5 lb (`round5` :23) or snapped to the kg grid (`domain:units/GeneratedLoad.kt:8-13`).

| Constant | `domain:standards/StrengthStandards.kt` | Values |
|---|---|---|
| `ratios` | :13-29 | Novice SQ 1.25, BP 1.0, DL 1.5, OHP 0.6, INC 0.85, ROW 0.75; Intermediate 1.5/1.25/2.0/0.8/1.05/0.95; Advanced 2.0/1.75/2.5/1.1/1.45/1.3 |
| `liftTune` | :33-37 | DL 0.82 (trap bar; `conv_dl` overrides to 1.0), INC 0.9, else 1.0 |
| `maintenanceFactor` | :41-45 | STRENGTH 0.75, BALANCED 0.68, PHYSIQUE 0.60 |
| `topSetReps` | :47-51 | 3 / 5 / 8 |
| `BACKOFF`, `BACKOFF_REPS` | :53-54 | 0.75 × top, 8 reps, one set |
| `RAMP_PCTS` | :55 | 0.55, 0.70, 0.80, 0.90 at 5, 5, 5, 3 reps (`SetSeeder.kt:104`) |
| `ageDerate` | :59-60 | 1 − 0.005 × max(0, age − 34), clamped 0.80–1.00 |

**Accessory goal** (`GoalCalculator.goalFor`, :42-58): an edited goal (`cfg.goalsLb`) wins; else `FracOfStd` = fraction × main goal; `Flat` = fixed lb; REPS/TIMED entries carry their own target (`targetFor` :66-72). Accessory reps = 10 (`SetSeeder.kt:35`). A Std lift in a straight slot still does top-set reps (`SlotSeeds.kt:24`).

**Shapes** (`SetSeeder.shape`, :77-93): STRAIGHT = N × (load, reps, WORK); RAMP_TOP_BACKOFF = 4 ramps + TOP + 1 BACKOFF; RAMP_TOP = 4 ramps + TOP. Ramp and back-off loads round through `GeneratedLoad.ramp` (:15-18), which never drops below the bar for barbell lifts. `SetStructure` has only these three (`Types.kt:31-43`). Nothing can express N back-offs, a different back-off drop, singles, %TM, RPE/RIR targets or week-to-week waves.

**When loads are computed.** `TrackerRepository.ensureSeeded` (`data:TrackerRepository.kt:685-701`) and `seedPlan` (:1393-1419) call `DaySeeds.plan` (`data:DaySeeds.kt:25-46`). That writes `exercise_log` only for slots with no stored log. So the load depends on the global lifter *at first view*, not at setup, and changing bodyweight later never rewrites a seeded slot. Previews that never seed (Plan screen set counts, watch blank rows) use `DaySeeds.previewMain` (:49-52). A swapped-in exercise arrives as 3 straight sets (`DaySeeds.swap`, :55-75) and takes the plan's or history's working weight if one exists (`withHistoryWeight`, `TrackerRepository.kt:1441`; `planWorkingWeight` :1459). Otherwise it gets the formula load, which is the source of the "DB row 60×10" complaint.

### 2.7 Cardio generation

| Path | Rule | File:line |
|---|---|---|
| Lifting branch finisher | None if mode NONE, placement NONE or SEPARATE_DAYS. Hard = leg-light day and not low impact | `CardioPlanner.kt:16-22`; leg-heavy = FB main is squat/hinge/single-leg (`ProgramGenerator.kt:230, 273`), Lower/Legs always (:354, :422) |
| Hard block | 5k on: 5 min warm-up + 5 × (2 min HARD + 2 min EASY). 5k off: 20 min tempo | `CardioIntervals.kt:19-33`; roles mapped in `CardioBlocks.fromLegacy` (:19-33) |
| Easy block | One 25-minute WORK set (open-ended, `Cardio.kt:59`) | `CardioIntervals.kt:22` |
| Standalone | 2 Cardio + Core days for SEPARATE_DAYS/BOTH | `ProgramGenerator.kt:483-503` |
| Cardio first | Sessions → [E], [E,L], [E,I,L], [E,I,E,L], [E,I,E,E,L], [E,I,E,I,E,L]. Easy = comfortable measure; Long = 1.5×; Intervals = the 5k block. Lift day k follows run day k. Rest days after Long and after the first Intervals | `CardioFirstPlanner.kt:19-58` |

### 2.8 Rest and increments

The generator sets neither. Rest is `RestPolicy` by set kind and tracking type (§3.5), with global overrides only. Increment is `WeightStepper.increment` by weight and unit, never by lift (§3.1).

### 2.9 Pinned numbers (spec §11.1) and the tests that hold them

All of these run through `GoalCalculator`, `SetSeeder` and `SetEditor` directly, not through `ProgramGenerator`. A new planner that stops calling them leaves them green. Deleting or changing those three objects breaks them.

| Number | Test |
|---|---|
| ageDerate(40) = 0.97 | `domain-test:PinnedVerificationTest.kt:33-35` |
| Squat 235, bench 195, trap DL 255, incline DB 75/hand | :38-43 |
| Squat ramp 130/165/190/210 ×5,5,5,3, TOP 235×5, B/O 175×8 | :46-58 |
| Bench 105/135/155/175, TOP 195×5, B/O 145×8 | :61-73 |
| Cascade TOP → 245 gives 135/170/195/220, B/O 185 | :76-91; also `SetEditingTest.kt:76-90`, `seeding/SetEditTest.kt:64`, `units/BodyweightTest.kt:81` |
| RDL 170, leg press 330, CS row 155; db_bench 80/hand | :94-104 |
| Rotation next(A)=B … next(D)=A; §6.2 day counts and templates | `RotationTest.kt` (19 tests), `ProgramGeneratorTest.kt:155, 173, 195, 293` (45 tests in the file); `ProgramGeneratorAnchorsTest.kt` (6) |

**A trap.** `PinnedVerificationTest` builds `LifterConfig(bodyweightLb = 235, age = 40)` (:23) and relies on the *defaults* for level (INTERMEDIATE) and emphasis (BALANCED). Changing `LifterConfig`'s defaults, for example making profile fields nullable to fix D2, silently changes the pinned inputs. Spell them out in the test first. 40 test files construct `LifterConfig()` or `WizardAnswers()` with defaults, and about 80 hard-code 235 or its ramp numbers (data, app, transfer and wear tests included).

The shipped cardio vectors (PLAN.md A13, "do not move") are pinned in `domain-test:generator/CardioIntervalsTest.kt`, `CardioBlocksTest.kt`, and `ProgramGeneratorTest.kt:371` ("typed generated cardio matches shipped finishers…").

---

## 3. Progression after setup

### 3.1 Strength Next time (PLAN.md A14)

| Piece | File:line | Behavior |
|---|---|---|
| Carry at Finish | `CarryForward.next` (`domain:seeding/CarryForward.kt:22-39`), called from `TrackerRepository.carryForward` (:1608-1641) | Keeps the plan's shape and opening weight. If the TOP was logged at a different weight, the whole planned list comes back (:30-31). RAMP/BACKOFF rows take the logged weight. WORK/EXTRA rows keep the planned weight, so **a lowered weight never carries** (N12). Keep-to-plan slots delete working values. A prescription whose shape changed during the run drops its working values (:1615-1624) |
| Offer | `CarryForward.offer`/`offerForSession` (:53-98) | Full success (every working row done, at or above the planned weight, reps ≥ planned; :84-88) → opening + `WeightStepper.increment` (:89-93), floored at the bar. Or the heaviest done working set above the opening with full reps (:94-96). The larger of the two wins, never both. Reads the newest qualifying session from **any day in the plan** (`HistoryRepository.progressionSessions`, `data:HistoryRepository.kt:302-316`). Timed rows never get offers (:69) |
| Working rows | `workingIndices` (:42-47) | The TOP of a ramped lift, else every WORK/EXTRA row |
| Chip state | `DayScreenBuilder.nextTime` (`app:ui/day/DayScreenBuilder.kt:170-195`) | Null when offers are off, keep-to-plan is on, or any row is done. `Repeat` when there is no offer and the current weight equals the opening. `Offer(applied)` when the current weight equals the offer. Label "+5 lb" (`offerLabel` :214-215) |
| Accept | `DayViewModel.setWorkingWeight` (`app:ui/day/DayViewModel.kt:1193-1198`) → `TrackerRepository.setWorkingWeight` (:1181-1220) → `CarryForward.withWorkingWeight` (:101-112) | A TOP cascades once; WORK/EXTRA rows take the weight directly. Writes the open run's tracks or the stored working values |
| Switches | `PlanEntity.offerStrengthIncreases` (default on), `ProgramExercise.keepToPlan` (Progress / Plan) | Plan screen and Lift options. Disclosure "Planned reps; increases need your approval." (strings.xml:688, `DayEditSheet.kt:590`) |
| Receipt | `SessionFinisher` (`app:ui/day/SessionFinisher.kt:158-187`) → `ReceiptRowItem.kt:270-272`, `ReceiptText.kt:138-143` | "Next time: +5 lb" / "Repeat" with no absolute weight (N1). `ReceiptNextTime` already carries `offerLb` and `openingLb` |

There is no miss rule, no "two misses, drop 10", no deload, no per-lift increment, no auto-apply, no reps-first (double) progression, and no RIR feedback. "Misses never lower" is a deliberate owner rule (A14).

### 3.2 Cascade (spec §4)

`SetEditor.editWeight` (`domain:seeding/SetEditor.kt:57-79`): editing a TOP re-derives each not-done RAMP from `RAMP_PCTS` and the BACKOFF from `× 0.75`, rounded via `GeneratedLoad.ramp`. Reps and seconds never cascade (:82-87). A non-TOP edit touches only its own row. `addSet` appends an EXTRA copy (:90-93). Authored prescriptions on STRAIGHT never cascade. A ramped authored load × reps cascades around that load (PLAN.md A13 set-structures amendment). The watch path reaches the same code through `SetEditApplier` (`app:sync/SetEditApplier.kt:67, 104`).

### 3.3 Cardio progression (PLAN.md A16)

`domain:cardio/CardioProgression.kt`: measure DISTANCE/TIME (5%) or PACE (1%, opt-in) (`defaultPercent` :35-38, `available` :41-49, `defaultMeasure` :52-61). `increased` changes WORK sets only and keeps pace by scaling the other measure (:68-110). `fullSuccess` (:113-126) requires an exact block match, every WORK set FINISHED, an open-ended timed set at `elapsed >= end` (:119), and aims met. `offer` (:129-130) and `nextTime` (:133-165) mirror strength: a chip, misses repeat. Per-day state lives in `program_day.cardioWorkingJson / cardioKeepToPlan / cardioProgressBy / cardioIncreasePercent`. The plan switch is `offerCardioIncreases`. App wiring is `DayScreenBuilder.cardioNextTime` (:197-207) and the Next time page (`DayEditSheet.kt:408-469`). Nothing caps long-run growth, controls weekly volume, or adapts to a race date.

### 3.4 Rotation

`domain:generator/Rotation.kt`: `upNextAfter` (:12-20) advances from the finished day unless a different day was suggested (out-of-order finishes leave the suggestion alone), and finishing the training day after a suggested Rest skips past it. `trainingDayFrom` (:22-30) and `nextFor` (:32-36) wrap. `Program.rotationDays` excludes CARDIO days with ids "C1"/"C2" by name (`Types.kt:113-115`), so the rotation is coupled to the generator's id scheme. `Program.dayToShow` (:118-125) resolves Rest. Callers: `TrackerRepository.advanceRotation` (:771-777) at Finish and cardio commit, `takeRest` (:147-154), `DayViewModel` (:646, :1921), `createGeneratedPlan` (initial day). There is no calendar or weekday model (r4 Part 4, item 12).

### 3.5 Rest policy

`RestPolicy.categoryFor` (`RestPolicy.kt:44-53`) keys on `SetKind` + `TrackingType`: RAMP, TOP and BACKOFF have their own buckets, WEIGHTED WORK → WORK (90 s, "accessory"; Setup label `SetupScreen.kt:360`), REPS/TIMED WORK and every EXTRA → LIGHT (60 s). Overrides are global per category (`RestSettings`, :82-85; DataStore `rest_*_seconds`, `SettingsStore.kt:82-87`). `effectiveRestSeconds` (:65-72) is the only resolver. Readers: the watch snapshot (`app:sync/WatchSnapshotBuilder.kt:223-225`, `WatchSet.restAfterSeconds`), the phone rest timer (`DayViewModel.kt:1416`), the workout rest hint (`DayScreenModels.kt:441-457`), and Setup (`SetupStateBuilder.restCategoryRows`). A straight-set main has no role to key on, because `ProgramExercise` stores none (`Types.kt:66-74`).

### 3.6 Records

| Record | File:line | Rule |
|---|---|---|
| Lift PRs | `domain:records/PersonalBests.kt:20-69` | Done, non-RAMP sets; WEIGHTED ranked by weight then reps. Used by receipt, History, share card, Best chip |
| Cardio PRs | `domain:records/CardioBests.kt:19-60` | Per (activity, planned end). A distance end always counts. **A time end counts only when `elapsedSeconds == end.seconds`** (:23), which disagrees with `CardioProgression.fullSuccess`'s `>=` (D7a/N4) |
| Plan goal progress | `domain:records/GoalProgress.kt:18-31` | `PlanGoal.Timed` (fastest single finished WORK set covering the distance) or `Shuttle` (hand-entered attempts). Goal model `domain:model/PlanGoal.kt:4-36`, stored in `plan.goalJson` (A18) |
| Lift goals in History | `app:ui/log/JournalBuilder.kt:67-88, 136` | Formula `GoalCalculator.targetFor`; "Goal met" = max top ≥ goal (D12) |

---

## 4. Data model the generator writes

### 4.1 Room (`data:db/StrengthDatabase.kt:43`, version 23, schemas exported)

| Entity | Written by generator? | Fields that carry generator output |
|---|---|---|
| `plan` (`data:db/entity/PlanEntity.kt:8-34`) | Yes | `origin` GENERATED/AUTHORED; `recipeJson` (PlanRecipeDto, §4.2); `suggestedDayId`; `onToday`; `offerStrengthIncreases` (default 1); `offerCardioIncreases` (default 0, 1 for cardio first); `goalJson` (A18, not set by the wizard) |
| `program_day` (`data:db/entity/ProgramEntities.kt:36-59`) | Yes | `planId+dayId` PK, `position`, `title`, `emphasisLine`, `kind` STRENGTH/CARDIO/REST, `cardioBlockJson`, `nextRunId` (minted at create, `PlanRepository.kt:314`); later state `cardioWorkingJson`, `cardioKeepToPlan`, `cardioProgressBy`, `cardioIncreasePercent`, `closedAt`; legacy `cardioJson` |
| `program_exercise` (:66-80) | Yes | `exerciseId`, `structure` (SetStructure name), `targetSets`, `supersetExerciseId`, `note`, `prescriptionJson` (only for novices or entered lifts), `keepToPlan`, `parkedLogsJson`. **No role, increment, rule, rest or rep range** |
| `exercise_log` (:88-96) | No: seeded lazily (§2.6), rewritten at Finish | `setsJson` = next time's rows per (plan, day, slot, track) |
| `workout_run`, `run_track` (`data:db/entity/RunEntities.kt:10-36`) | No | `plannedJson` = rows at start (the receipt's and History's "planned"), `setsJson` = actuals |
| `workout_session`, `session_set` (`data:db/entity/SessionEntities.kt`) | No | `bodyweightLb` (nullable, :24; stamped from config at Finish, D2), `plannedJson` (SessionPlanJson with per-track `note`), `kind` by name |
| `cardio_session`, `cardio_set_result` | No | Results keyed by spec JSON |
| `custom_exercise` | No | Tracking type and targets; default reps 10 (`CustomExerciseViewModel.kt:217`) |

### 4.2 JSON payloads in columns

- **Recipe** (`data:serialization/PlanRecipeJson.kt:14-36`): `daysPerWeek, split, anchorScheme, deadliftVariant, emphasis, cardioMode, cardioPlacement, fiveKGoal, equipment, goalsLb, cardioFirst{activity, seconds|metres, sessions, liftDays, restDays}`. It does not hold bodyweight, age, level or unit (those are global) or the recent lifts. `decode` (:77-112) merges in the global lifter. `validate` (:62-75) runs on backup import (`transfer:backup/BackupCodec.kt:456`). The decoder ignores unknown keys (`SetJson`, `data:serialization/SetSerialization.kt:72`), so new fields are additive on read.
- Prescription (`PrescriptionJson.kt`): `loadReps | reps | hold | repsInTime`, `eachSide`, `resistance`.
- Sets (`SetSerialization.kt`): `LoggedSet` incl. `kind` by name, `plannedIndex`, timestamps.
- Cardio block, parked logs, plan goal, session plan: `CardioBlockJson`, `ParkedLogsJson`, `PlanGoalJson`, `SessionPlanJson`.

### 4.3 DataStore (`data:prefs/SettingsStore.kt`)

| Key | Line | Meaning |
|---|---|---|
| `bodyweight_tenths_lb` (legacy `bodyweight`), `age`, `level` | :44-47 | Global lifter. Absent → `LifterConfig()` defaults on read (:495-501) |
| (derived) `hasLifterAnswersFlow` | :39-41 | True if bodyweight or age was ever written. Already in backups (`hasLifterAnswers`) |
| `weight_unit`, `distance_unit` | :66-67 | Units |
| `wizard_complete`, `selected_plan_id` | :64-65 | Routing and Today plan |
| `rest_timer_enabled`, `rest_{ramp,top,backoff,work,light}_seconds` | :82-87 | Rest |
| Legacy plan keys `emphasis, cardio_mode, cardio_placement, five_k, days_per_week, split, anchor_scheme, deadlift_variant, equipment, suggested_day` | :49-61 | Pre-plans recipe. Migrated once into `plan.recipeJson` (`PlanRepository.ensurePlanSettingsMigrated`, :433-480), then removed |

### 4.4 Backup (`transfer:backup/BackupDocument.kt:33`, schema v25)

Plans carry `recipeJson`, `suggestedDay`, `onToday`, both offer switches and `goalJson`. Settings carry the lifter (`bodyweightLb`, `bodyweightLbPrecise`, `age`, `level`, `hasLifterAnswers`). v1–v8 documents carry the recipe fields in settings (`BackupMapping.toSnapshot`, `transfer:backup/BackupMapping.kt:208-230`). A v<23 document whose recipe has `cardioFirst` is rejected (`BackupCodec.kt:96-106`). Restoring a legacy single-program backup builds a recipe from `snapshot.answers` (`data:BackupRepository.kt:200-204`).

### 4.5 What a migration would touch

| Change the design might make | Touches |
|---|---|
| New recipe shape (goal type, event, date, session length, plate steps, exclusions) | `PlanRecipeDto` + `encode/validate/decode`; keep decoding every old recipe, because `templateFor` and History's `mainLiftIds` regenerate from it; a backup schema bump if validation must reject old readers; `PlanRecipeJsonTest`, `PlanSettingsTest`, Backup v9–v25 tests |
| Per-slot role / increment / rule / rest / rep range / RIR / back-off scheme | New `program_exercise` columns or one JSON column (Room v24 migration, Opus tier), `ProgramExercise` domain fields, `Mappers.kt`, the backup `ProgramExerciseBackup` + version bump, the watch snapshot if the watch must show it (additive fields; `schemaVersion` stays 1 by convention, PLAN.md A13) |
| New `SetKind` (e.g. multiple back-offs labeled, AMRAP) | Stored by name in `exercise_log`, `run_track`, `session_set`, the backup, and the wire (`WatchSet.kind`). An old watch must tolerate the new value. `RestPolicy.categoryFor`, `SetLabels`, `PersonalBests.counts`, `CarryForward.workingIndices` all branch on kind |
| Nullable profile (D2) | `LifterConfig` / `GlobalLifter` / `readGlobalLifter` defaults; `GoalCalculator` requires a bodyweight; `finishRunCapturing` and `closeRemovedRun` stamp `bodyweightLb`; the pinned test's implicit defaults (§2.9) |
| Block / dated weeks (p3) | No current home: the rotation has no week index and nothing stores a block position. This needs a new plan-level state column |
| Lazy seeding moves to setup | `DaySeeds.plan` "seeded once" semantics, `ensureSeeded`, and every reader of `exercise_log`. Writing working values at create time is allowed by the existing schema (no migration) |

---

## 5. Coupling: who reads generator output or `LifterConfig`

| Surface | Reads | File:line | Breaks if… |
|---|---|---|---|
| Day screen, card heading, "Target" | Seeded rows, `workingHeading` | `DayViewModel.kt:2176-2186`, `DayScreenBuilder.kt:148-168`, `LiftSheet.kt:63-68` | Row kinds or working-row rules change |
| Day screen Next time chip | `CarryForward`, `offerStrengthIncreases`, `keepToPlan` | `DayViewModel.kt:2183-2186`, `DayScreenBuilder.kt:170-195` | Progression moves out of `CarryForward` |
| Seeding everywhere (Today preview, Plan set counts, watch blank rows, swap seeds, superset partner) | `SlotSeeds`, `DaySeeds`, `GoalCalculator`, `config` | `data:DaySeeds.kt`; `data:PerformedDay.kt:60-75`; `TrackerRepository.kt:258-330, 685-701, 1393-1500`; `PlanScreenBuilder.kt:54-80`; `WatchSnapshotBuilder.kt:150` | Load source changes |
| Day editor | Recipe equipment for swap ranking, `templateFor` availability, `SlotSeeds` for structure labels, `PrescriptionDraft` formula defaults | `DayEditor.kt:69, 78, 86-87, 406-409`; `DayEditSheet.kt:466-469`; `PrescriptionDraft.kt:48-78` | `templateFor` or recipe equipment goes away |
| Reset day to template | Regenerates from the recipe | `TrackerRepository.kt:577-602` | The generator stops being deterministic per recipe, or the old generator is deleted |
| Session finish and receipt | Config for target rows, `SlotSeeds`, `nextTime` | `SessionFinisher.kt:125, 143-187`; `ReceiptBuilder.kt:84-91`; `ReceiptRowItem.kt:270-272`; `ReceiptText.kt:138-143` | Planned-target or offer model changes |
| Finish write | `config.bodyweightLb` stamped on the session; `CarryForward.next`; rotation advance | `TrackerRepository.kt:1551-1572, 1574-1606, 1608-1641`; also `removeDay` :165-166, `replaceProgram` :346-349, `closeRemovedRun` :1672-1680 | D2 fix; progression change |
| Lift note snapshot | `program_exercise.note` copied into every session | `TrackerRepository.kt:1590`; `LogViewModel.kt:565-571` | N6 (not wizard, but the same column) |
| History trajectories and goals | `ProgramGenerator.mainLiftIds(recipe)` for main-lift membership, `GoalCalculator` for goal lines and "Goal met" | `LogViewModel.kt:168-175`; `JournalBuilder.kt:67-88, 136` | The generator is replaced (membership comes from re-running the old skeleton), or formula goals are removed |
| History bodyweight | `session.bodyweightLb` ("BW 235"); Health Connect weight prompt vs `config.bodyweightLb` | `LogViewModel.kt:207-211, 497-503, 552` | Nullable profile |
| Plans list "You" row | `configFlow` printed as fact | `app:ui/plan/PlansViewModel.kt:48-59` | D2 fix |
| You screen | `configFlow`; edits via `updateLifter` | `app:ui/you/YouViewModel.kt:27-41` | Nullable profile |
| Plan screen | Formula goal list from `availableAnchorIds`, emphasis picker (generated plans only), goal edits into the recipe, offer switches, cardio goal | `PlanScreenBuilder.kt:49-50`; `SetupStateBuilder.kt:22-35`; `PlanViewModel.kt:142-173`; `PlanRepository.kt:141-172` | Goals or emphasis leave the recipe |
| Today | `Program.dayToShow`, `cardioOnly` = kind CARDIO or no exercises + a block, rest rows | `app:ui/today/TodayViewModel.kt:62-72` | New day kinds or a calendar model |
| Rotation `C1`/`C2` exclusion | Day ids by name | `domain:model/Types.kt:113-115` | Standalone cardio ids change |
| Watch | `WatchSnapshot`: set kinds, `restAfterSeconds`, goal label, alternates filtered by recipe equipment; phone applies watch edits through `SetEditor`, `DaySeeds`, config | `WatchSnapshotBuilder.kt:130-225`; `SetEditApplier.kt:67, 104, 165-169`; `domain:sync/WatchSnapshot.kt:134-251`; wear renders kinds via `SetLabels` (`wear/.../wear/ui/WatchUiModels.kt`) | New kinds or roles; rest by role must be stamped phone-side, since the watch only counts down |
| Health Connect calories | `session.bodyweightLb × 5 MET × hours` | `transfer:health/CaloriesRecordMapper.kt:50-63` | Bodyweight stays a default (exports a made-up number) |
| Backup | Recipe, offer switches, lifter, `hasLifterAnswers` | §4.4 | Recipe or entity fields added |
| CSV export | Sets only; RPE column written blank | `transfer:csv/HistoryCsvWriter.kt:107-117` | RPE/RIR becomes tracked (p3) |
| CSV import | `CardioBlocks.legacyModeOf` | `transfer:csv/HistoryCsvReader.kt:357` | `CardioBlocks` is deleted |
| Legacy cardio migration | `CardioBlocks.fromLegacy` + recipe mode and 5k flag | `data:serialization/LegacyCardio.kt:8-12`; `PlanRepository.kt:469` | `CardioBlocks`/`CardioIntervals` deleted, or the recipe drops `cardioMode`/`fiveKGoal` |
| Playtest harness | Drives the wizard by on-screen text | `app/src/test/.../playtest/Selectors.kt`, `playtest/pilot/wizard-dialog-capture.json`, kit personas | Any copy or step change; personas need bodyweight and age (r4 Part 5, item 4) |
| App tests | `WizardStateBuilderTest`, `WizardViewModelWiringTest`, `WizardLiftFieldsTest`, `WizardOperationStatesTest`, `WizardRestoreWiringTest`, `SetupStateBuilderTest`, `PlanScreenBuilderTest`, `JournalBuilderTest`; data `PlanRecipeJsonTest`, `PlanSettingsTest`, `DaySeedsTest` | `app/src/test`, `data/src/test` | Rewrite with the wizard |

---

## 6. Known defects to design out (r4 synthesis Part 4) mapped to code

| # | Input | Cause in code | Notes for the design |
|---|---|---|---|
| 1 | Honor the anchor choice on every split (D3) | Upper/Push take `anchorFor(H_PUSH)` = bench (`ProgramGenerator.kt:310, 360`). No split asks for a V_PUSH main, and `pickAccessory` skips Std lifts, so OHP never appears (:314, :474-475). The incline DB anchor (PROTOTYPE) collides with bench on H_PUSH, and the row (BIG_4) appears only on PPL Pull days. On UPPER_LOWER the fourth anchor of every scheme is dropped. `activeAnchorIds` KDoc claims the opposite (:176-185). The notice is FULL_BODY-only (`WizardScreen.kt:466-480`). LIFTS asks only for placed mains (`WizardStateBuilder.kt:110`) | Make placement total, or say per split what was dropped. One SSOT for "which lifts are mains" also feeds History (`LogViewModel.kt:174`) |
| 2 | Ask before guessing loads (p1 chest press 100, hack squat 180; p4 every accessory "calculated") | `GoalCalculator.goalFor` bodyweight ratios (`GoalCalculator.kt:42-58`, `StrengthStandards.kt:13-60`), applied lazily by `SlotSeeds.main` (`SlotSeeds.kt:18-25`). LIFTS covers mains only. Swap seeding uses the same formula when there is no history (`DaySeeds.swap`, `TrackerRepository.kt:1423-1456`) | The evidence review recommends rep target + RIR from the log. Keep `GoalCalculator` alive for §11 and for old plans |
| 3 | Progression rules per lift: increment by lift, auto vs offer, what success means, the "+20" after a missed last set (p1) | One increment for all lifts (`WeightStepper.kt:19-22`, used at `CarryForward.kt:90`). Offer-only (`offerStrengthIncreases`, `DayScreenBuilder.kt:179`). Success = every working row at reps (:84-88). `heavier` (:94-96) offers the heaviest full-rep set even after a later miss | Needs a stored per-slot rule (§4.5). A14's "misses never lower" is an owner rule; changing it needs an explicit owner call |
| 4 | Misses, drops, deloads; a lowered weight never carries (N12) | `CarryForward.next` keeps planned WORK weights and reverts a changed TOP (:22-39). No miss counter, no deload state, no week index | Needs history-derived state (consecutive misses) or a stored counter. The "28-day evidence review" after production (PLAN.md A13) is the owner's earlier stance against automatic deloads |
| 5 | Set schemes: top + N back-offs at −X; singles with back-offs | `SetStructure` has 3 values (`Types.kt:31-43`); one back-off at 0.75 × 8 (`StrengthStandards.kt:53-54`, `SetSeeder.kt:87-91`); the cascade hard-codes the same constants (`SetEditor.kt:71-75`) | Parameterize the shape per slot. The §11 numbers are the RAMP_TOP_BACKOFF default and must keep reproducing |
| 6 | Rest by role, not set kind (D5) | `RestPolicy.categoryFor` (`RestPolicy.kt:44-53`); WEIGHTED WORK = 90 s "accessory". Novice mains are always STRAIGHT (`ProgramGenerator.kt:94-97`), so every novice squat rests 90 s. No role is stored (`Types.kt:66-74`) | A stored slot role (or a per-slot rest) feeds `effectiveRestSeconds`. Phone and watch both read the resolver, so one change covers both |
| 7 | Cardio modalities and goals: rower, walking as its own activity, run types, pace targets, 8×30/90, session length | Lifting-branch `CardioMode` has 4 values (`Types.kt:154`); Treadmill → TREADMILL_RUN (`CardioBlocks.kt:38-43`); fixed interval steps (`CardioIntervals.kt:19-33`); cardio-first intervals are the 5k block for any activity (`CardioFirstPlanner.kt:34`); no session-length question anywhere | `CardioActivity` (10 values) and typed `CardioSetSpec` (role, end, aim, hard) already express all of these, so the gap is in generation, not the model. `PlanGoal.Timed` (A18) already holds "distance under time by date" |
| 8 | Runner first with supporting strength; ask bodyweight only when needed (D2) | Lift days reuse the barbell generator (`CardioFirstPlanner.kt:27-30`). ABOUT_YOU skipped → defaults 235/40/intermediate (`WizardViewModel.kt:205-206`), shown on Plans (`PlansViewModel.kt:48-59`), stamped on sessions (`TrackerRepository.kt:1552, 1559`), and exported as calories (`CaloriesRecordMapper.kt:55-63`). `hasLifterAnswersFlow` exists but only gates wizard prefill (`SettingsStore.kt:39-41`) | `WorkoutSessionEntity.bodyweightLb` is already nullable and readers handle null |
| 9 | Bring your own program (dated four-week block, %TM, RPE targets) | Authored plans exist (`PlanRepository.createAuthoredPlan`, :337) but have no week dimension; `Prescription` has no %TM or RPE (`domain:model/Prescription.kt:4-18`); rotation has no block position | Decide import-or-generate. Either way it needs a week index on the plan |
| 10 | Goals from what the user wants, or none (D12) | History goal line and "Goal met" from `GoalCalculator.targetFor` (`JournalBuilder.kt:81, 136`); the Plan screen lists formula goals (`SetupStateBuilder.kt:22-35`) | `goalsLb` in the recipe is the existing override path. A null goal already renders (no line) |
| 11 | Experience level vs. real numbers; ask only what changes the plan | Level is asked even after real lifts are entered (`WizardScreen.kt:599-612`), and Next is disabled until bodyweight and age are set (`WizardModels.kt:78-79`). Level changes ratios (`StrengthStandards.kt:13-29`) and turns on novice straight sets (`ProgramGenerator.kt:86`); age only feeds `ageDerate` | Bodyweight and age are pure formula inputs (and calories). If the formula leaves the seeding path, neither needs asking for lifting |
| 12 | Schedule model: weekdays, AM run + PM lift, Thursday kept after a skipped Tuesday | Rotation only (`Rotation.kt`); "No stored plan kind… alternating plans by weekday is the lifter switching" (PLAN.md A13). Same-day pairs: two plans on Today (`onToday`) or a finisher on one day | A calendar model is a cross-cutting change: Today, the watch `suggestedDayId`, `advanceRotation`, Rest entries |
| 13 | Exercise selection fit (machines only, gym-specific); swap default load | Rank-1 per pattern within equipment (`ProgramGenerator.kt:464-475`); equipment is all-or-nothing per entry (`availableWith`); "seated row" became Chest-Supported Row because of rank; the swap load comes from the formula (item 2) | Exclusions and favorites have no storage today |

Other WIZARD-tagged items in r4 that touch the same code:

- **D4/N1, receipt copy.** `ReceiptRowItem.kt:270-272` and `ReceiptText.kt:138-143` already have `offerLb` and `openingLb`. This is a NOW fix whatever the rebuild does.
- **D7a/N4, two success rules for one fact.** `CardioBests.kt:23` uses `==`, `CardioProgression.kt:119` uses `>=`. A new Progression module should own the single "did this time-ended set count" rule.
- **p5's "+0.35 km" with no reason.** `CardioProgression.defaultPercent` (:35-38).
- **p3's "7 of 33 sets".** Generated ramps count toward the total. Fewer generated rows fix it.

---

## 7. Seams: where a new planner and progression module sit

### 7.1 `PlanGenerator(answers) -> plan`

Put it in a new `domain:planner/` package (pure Kotlin, no Android). The output can still be `Program` so that `PlanRepository.createGeneratedPlan(name, recipe, program)` (:284-321), the Room mappers, Today, the watch and backup keep working unchanged, provided the new per-slot facts go on `ProgramExercise`/`ProgramDay` as additive fields.

| Today | Fate under a new planner |
|---|---|
| `ProgramGenerator`, `SplitDefaults`, `CardioFirstPlanner`, `CardioPlanner` | **Absorbed** for new plans. Keep a frozen copy (or keep these as-is, renamed "legacy") only for what old recipes need: `templateFor` on old plans and History's `mainLiftIds`. Alternatively, retire Reset day to template for old plans and store "generated main" as a slot role, which removes both regeneration callers |
| `WizardAnswers`, `CardioFirst`, `AnchorScheme`, `DeadliftVariant`, `SplitTemplate`, `LiftEntry`, `CardioPrefs`, `CardioMode`, `CardioPlacement` | **Retired** as inputs. They must still decode from old recipes and v1–v8 backups (`BackupMapping.kt:208-230`, `SettingsStore.legacyAnswersFlow` :138-149, `PlanRecipeJson.decode`) |
| `CardioIntervals`, `CardioBlocks` | `fromLegacy`/`legacyModeOf`/`activityOf` **stay** for migration and CSV. New plans author `CardioBlock` directly |
| `GoalCalculator`, `StrengthStandards` | **Keep** (pinned by §11 and used by old plans' lazy seeding). The new planner should not call them for new plans if loads come from user entries or a rep/RIR target |
| `SetSeeder.shape`, `GeneratedLoad`, `PlateMath` | **Keep**, and parameterize `shape` for N back-offs and drop percentages, with today's constants as the default |
| `ExerciseLibrary`, `ExerciseEntry.availableWith` | **Keep**. Add exclusion and favorite filters beside equipment |
| `Rotation` | **Keep** unless the design adopts weekdays. Remove the `C1`/`C2` name rule (`Types.kt:113-115`) by giving extras their own `ProgramDayKind` or a flag |
| `PlanGoal`, `GoalProgress` | **Adapt**. They are the natural home for event, race-date and test goals that the planner branches on |

### 7.2 `Progression(log) -> next targets`

Put it in a new `domain:progression/` package that takes a slot's rule plus its history (the same `CarryForward.Session` lists that `HistoryRepository.progressionSessions` already builds) and returns the next working rows and an offer or auto-change with a reason.

| Today | Fate |
|---|---|
| `CarryForward.next`, `offer`, `offerForSession`, `workingIndices`, `withWorkingWeight` (`domain:seeding/CarryForward.kt`) | **Absorbed.** `cleared`, `workingIndices` and `withWorkingWeight` are mechanics the run code uses heavily and can stay as helpers |
| `WeightStepper.increment` as the progression step | **Retired as a rule.** It stays as the stepper UI increment, and the per-lift increment moves into the slot rule |
| `CardioProgression` (`offer`, `fullSuccess`, `nextTime`, `increased`) | **Absorbed**, along with the one "counts as finished" rule shared with `CardioBests.counts` |
| `RestPolicy.categoryFor` | **Adapt** to key on slot role. `effectiveRestSeconds` stays the single resolver, so the watch contract is unchanged |
| `SetEditor` cascade | **Keep.** If back-off schemes become per slot, the cascade reads the slot's scheme instead of `StrengthStandards.BACKOFF`/`RAMP_PCTS`, and the defaults reproduce §11 |
| `DayScreenBuilder.nextTime`, the receipt `ReceiptNextTime`, `SessionFinisher` next-time assembly | **Callers.** They switch to the new module's result type. `TrackerRepository.carryForward` (:1608-1641) is the single write point at Finish |
| `PersonalBests`, `CardioBests.records`, `GoalProgress` | **Keep** as read-only records. They are not progression |

### 7.3 Things to settle before the design locks

1. Whether loads are written at plan creation (no migration, but it changes the "seeded once at first view" semantics in `DaySeeds.plan`) or stay lazy.
2. Where per-slot rules live: new columns versus one JSON column on `program_exercise`. Either way it is a Room v24 migration plus a backup version bump.
3. The fate of `templateFor`: keep the old generator frozen for old recipes, or retire Reset day to template for them.
4. The profile model: nullable bodyweight and age (D2), with `PinnedVerificationTest` made explicit first (§2.9).
5. Whether A14 ("misses never lower; increases need approval") and A13 ("plans stay generic and rotation-based; no stored plan kind") still stand. Both are owner decisions the evidence review pushes against.
