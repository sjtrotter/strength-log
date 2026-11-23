# Strength Tracker — Android + Wear OS Build Spec (v2)

A complete, self-contained handoff document for building a native Android strength
tracker with a Wear OS companion. It consolidates everything learned in a working
React prototype plus the research behind every design decision, so the implementer
should need **no web lookups** during the build.

> **For the implementer (Claude Code):**
> 1. Read Section 1 (product principles) and Section 12 (research appendix) first.
> 2. Build `:domain` (pure Kotlin, no Android deps) from Sections 3–7 and unit-test
>    it against the pinned verification numbers in Section 11 before any UI work.
> 3. Then `:data`, then the phone UI, then Wear.
> All behavioral rules in this doc were validated in a working prototype — replicate
> them exactly unless a section marks something as open.

---

## 1. Product vision & principles

**What it is:** a no-frills, get-out-of-your-way, cheap ($ one-time, no subscription,
no account, no ads, fully local) app that lets a lifter build a training plan through
a short wizard, then track it with minimal taps.

**Who it's for (reference user):** ~40-year-old, trains for *maintenance and health*,
not PRs. Schedule is unreliable — sessions get missed. Wants a good pump on weights
they can own, balanced development, and honest logging of what actually happened.

**Non-negotiable design principles (each is research-backed — see appendix):**

1. **Rotation, not calendar.** Workouts advance A→B→C→… on *completion*, never
   pinned to weekdays. Missing days shifts the plan; it never skips a muscle.
2. **GOAL vs ACTUAL.** Every exercise shows a calculated, read-only GOAL (target
   maintenance weight). The ACTUAL log is seeded from it once, then persists as
   the lifter's living record: next time keeps the plan's shape and opening
   weight, and an increase is offered, never applied (PLAN.md A14). GOALs change
   only via Setup inputs.
3. **Top set is the anchor.** Main lifts use a Madcow-style ramp → top set →
   back-off. Changing the top-set weight recalculates the ramp and back-off.
   Editing any other set changes only that set.
4. **Conservative loads.** A lift starts from Recent numbers or history, or the
   equipment floor while calibrating, and progresses conservatively from the log.
   Goals are what the lifter types.
5. **Every tap is cheap.** Steppers, not keyboards. Checkmarks, not forms.
   Cards collapse when done.
6. **Lift first, cardio after.** Cardio is planned around the lifting, never
   before it, with intensity matched to that day's leg fatigue.
7. **Local-first.** Room + DataStore. No network permission needed in v1.

---

## 2. Feature inventory (v1)

| Area | Feature |
|---|---|
| Onboarding | Setup: Goal → Sub-goal → Your week → Equipment → About your training → Your lifts → Recent numbers → Your plan; see [design §2](docs/briefs/wizard-design-2026-11-08.md#2-the-flow) |
| Program | Generated editable program stored locally; per-day edit (swap/add/remove exercises) with ranked same-pattern substitutions |
| Workout | Day screen: ramped mains with cascade, per-set weight+reps steppers, add/remove sets, supersets with paired sub-rows, per-set checkmarks, collapsible cards, optional cardio finisher card |
| Rotation | Auto-suggested next day, manual override, DONE advances cycle |
| Setup page | Re-run the setup flow; Your plan previews the rotation before creation; see [design §2](docs/briefs/wizard-design-2026-11-08.md#2-the-flow) |
| Wear | Glanceable per-set logging synced to phone |

Deliberately **not** in v1: history/charts, cloud sync, accounts, plate calculator,
rest timers, RPE, social anything. (Section 10.)

---

## 3. Domain layer — core types

Pure Kotlin. No Android imports anywhere in `:domain`.

```kotlin

enum class MovementPattern {
  SQUAT_BILATERAL, SINGLE_LEG, HINGE, KNEE_FLEXION, KNEE_EXTENSION,
  H_PUSH, V_PUSH, H_PULL, V_PULL,
  SIDE_DELT, REAR_DELT, BICEPS, TRICEPS,
  CALF_GASTROC, CALF_SOLEUS,
  CORE_ANTI_EXT, CORE_ANTI_ROT, CORE_FLEX,
  CARDIO
}

enum class Equipment { BARBELL, TRAP_BAR, DUMBBELL, MACHINE, CABLE, BODYWEIGHT, BENCH, RACK, PULLUP_BAR, EZ_BAR }

enum class SetKind { RAMP, TOP, BACKOFF, WORK, EXTRA }

data class LoggedSet(
  val weightLb: Double,
  val reps: Int,
  val kind: SetKind,
  val done: Boolean = false,          // per-set checkmark, reset daily
)

data class SupersetPartner(val exerciseId: String)   // refers into ExerciseLibrary

/** One exercise slot inside a day of the user's (editable) program. */
data class ProgramExercise(
  val exerciseId: String,             // ExerciseLibrary key
  val isMain: Boolean = false,
  val targetSets: Int = 3,
  val repSchemeLabel: String = "",    // display only, e.g. "8-12"
  val hasWarmupHint: Boolean = false, // "+1 warm-up set" badge
  val superset: SupersetPartner? = null,
  val note: String = "",
)

data class ProgramDay(
  val id: String,                     // "A", "B", ...
  val title: String,
  val emphasisLine: String,           // muscle-angle emphasis, shown under title
  val exercises: List<ProgramExercise>,
  val cardio: CardioSuggestion?,      // null if user opted out of finishers
)

data class Program(val days: List<ProgramDay>)

data class CardioSuggestion(val label: String, val detail: String, val hard: Boolean)
```

---

## 4. Domain layer — set shapes and the cascade

Superseded by PLAN.md A19 for everything about where a load comes from: openings and seeding are design §3.1
(`SlotSeeding`) and progression is design §3.2; there is no goal formula. This section still pins how a ramped
slot's rows are shaped and edited.

### Set shapes

A ramped slot's rows are its `Scheme` around the TOP set (`SetSeeder.shape`):

- `Scheme.STRENGTH` (Strength plans, a year or more of lifting): ramps 60 % × 5 and 80 % × 3, the TOP, two
  back-offs at 90 % × 5.
- `Scheme.PROTOTYPE` (converted plans' ramped slots and hand-built ramped lifts): ramps 55/70/80/90 % × 5/5/5/3,
  the TOP, one back-off at 75 % × 8.

Ramp and back-off loads round to the lift's equipment grid with the bar as the floor (`GeneratedLoad`).

### The cascade rule (prototype-proven, keep exactly)

Editing a set's **weight** on a main lift's **TOP** row re-derives every RAMP and BACKOFF row from the slot's `Scheme`, rounding as
above; completed ramps keep their weights. It does
**not** touch reps, EXTRA/WORK rows, or the superset track. Editing any non-TOP
row changes only that row (manual tweaks stick). Editing reps never cascades. Pinned as §11.2 case 16.

### Add / remove sets

- "+ add set" appends `{ weight = lastRow.weight, reps = lastRow.reps, kind = EXTRA }`.
- If the exercise has a superset partner, add/remove apply to **both tracks at the
  same index** so rounds stay aligned row-for-row. Never allow removing below 1 set.

---

## 5. Exercise library & substitution engine

The library is a static, in-code catalog. Each entry: id, display name, pattern,
equipment list, `perHand` flag, and tracking type. Starting loads come from the
planner and the log (design §3, PLAN.md A19). `subRank` orders
substitution candidates *within a pattern* (1 = first suggestion).

**Substitution rule:** replacements for exercise X = all library entries with the
same `MovementPattern`, excluding X, sorted by `subRank`. (Equipment-profile
filtering is a v1.1 option — ship without it; the ranked list is enough.)
If a pattern has no alternatives (rare), fall back to the adjacent pattern noted
in the table.

| id | Name | Pattern | Equip | Tracking | subRank | Notes |
|---|---|---|---|---|---|---|
| bb_back_squat | Barbell Back Squat | SQUAT_BILATERAL | BARBELL,RACK | Weighted | 1 | main-capable |
| hack_squat | Hack Squat | SQUAT_BILATERAL | MACHINE | Weighted | 2 | closest leg-press sub |
| leg_press | Leg Press | SQUAT_BILATERAL | MACHINE | Weighted | 3 | spine-unloaded volume |
| goblet_squat | Goblet Squat | SQUAT_BILATERAL | DUMBBELL | Weighted | 4 | simplest fallback |
| front_squat | Front Squat | SQUAT_BILATERAL | BARBELL,RACK | Weighted | 5 | quad bias |
| smith_squat | Smith Machine Squat | SQUAT_BILATERAL | MACHINE | Weighted | 6 | |
| bss | Bulgarian Split Squat | SINGLE_LEG | DUMBBELL,BENCH | Weighted | 1 | |
| walking_lunge | Walking Lunge | SINGLE_LEG | DUMBBELL | Weighted | 2 | time-cheap BSS sub |
| reverse_lunge | Reverse Lunge | SINGLE_LEG | DUMBBELL | Weighted | 3 | knee-friendlier lunge |
| step_up | Step-Up | SINGLE_LEG | DUMBBELL,BENCH | Weighted | 4 | |
| trap_dl | Trap-Bar Deadlift | HINGE | TRAP_BAR | Weighted | 1 | main-capable; least lumbar strain |
| conv_dl | Conventional Deadlift | HINGE | BARBELL | Weighted | 2 | main-capable; most posterior chain, most back demand |
| sumo_dl | Sumo Deadlift | HINGE | BARBELL | Weighted | 3 | main-capable; quad-biased pull |
| rdl | Romanian Deadlift | HINGE | BARBELL | Weighted | 4 | stretch-biased hamstrings |
| stiff_dl | Stiff-Leg Deadlift | HINGE | BARBELL | Weighted | 5 | deeper stretch than RDL |
| good_morning | Good Morning | HINGE | BARBELL,RACK | Weighted | 6 | |
| back_ext | Back Extension | HINGE | BODYWEIGHT | Reps | 7 | |
| seated_curl | Seated Leg Curl | KNEE_FLEXION | MACHINE | Weighted | 1 | stretched-position curl (preferred) |
| lying_curl | Lying Leg Curl | KNEE_FLEXION | MACHINE | Weighted | 2 | short-head/lower-ham bias |
| ball_curl | Swiss Ball Leg Curl | KNEE_FLEXION | BODYWEIGHT | Reps | 3 | no-machine fallback |
| nordic | Nordic Curl (assisted) | KNEE_FLEXION | BODYWEIGHT | Reps | 4 | eccentric emphasis |
| leg_ext | Leg Extension | KNEE_EXTENSION | MACHINE | Weighted | 1 | optional; knee-health ROM work; fallback pattern → SQUAT_BILATERAL |
| bb_bench | Barbell Bench Press | H_PUSH | BARBELL,BENCH,RACK | Weighted | 1 | main-capable |
| db_bench | DB Bench Press | H_PUSH | DUMBBELL,BENCH | Weighted | 2 | |
| incline_db | Incline DB Press | H_PUSH | DUMBBELL,BENCH | Weighted | 3 | main-capable; upper-chest bias |
| incline_bb | Incline Barbell Press | H_PUSH | BARBELL,BENCH,RACK | Weighted | 4 | |
| machine_chest | Machine Chest Press | H_PUSH | MACHINE | Weighted | 5 | |
| pec_deck | Pec Deck / Cable Press | H_PUSH | MACHINE,CABLE | Weighted | 6 | pump finisher |
| dips | Weighted Dip | H_PUSH | BODYWEIGHT | Weighted | 7 | |
| ohp | Overhead Press (Barbell) | V_PUSH | BARBELL,RACK | Weighted | 1 | main-capable (5/3/1-style anchors) |
| db_shoulder | Seated DB Shoulder Press | V_PUSH | DUMBBELL,BENCH | Weighted | 2 | |
| machine_shoulder | Machine Shoulder Press | V_PUSH | MACHINE | Weighted | 3 | |
| landmine_press | Landmine Press | V_PUSH | BARBELL | Weighted | 4 | shoulder-friendly angle |
| bb_row | Barbell Row | H_PULL | BARBELL | Weighted | 1 | main-capable (Big-4 anchors) |
| cs_row | Chest-Supported Row | H_PULL | MACHINE,BENCH,DUMBBELL | Weighted | 2 | lower-back-free |
| cable_row | Seated Cable Row | H_PULL | CABLE | Weighted | 3 | |
| db_row | One-Arm DB Row | H_PULL | DUMBBELL,BENCH | Weighted | 4 | |
| pullup | Pull-Up / Chin-Up | V_PULL | PULLUP_BAR | Reps | 1 | |
| lat_pd_wide | Lat Pulldown (wide) | V_PULL | CABLE | Weighted | 2 | |
| lat_pd_neutral | Lat Pulldown (neutral) | V_PULL | CABLE | Weighted | 3 | joint-friendly grip |
| assisted_pullup | Assisted Pull-Up | V_PULL | MACHINE | Weighted | 4 | |
| cable_lateral | Cable Lateral Raise | SIDE_DELT | CABLE | Weighted | 1 | constant tension |
| db_lateral | DB Lateral Raise | SIDE_DELT | DUMBBELL | Weighted | 2 | |
| face_pull | Face Pull | REAR_DELT | CABLE | Weighted | 1 | shoulder health |
| reverse_pec | Reverse Pec Deck | REAR_DELT | MACHINE | Weighted | 2 | |
| ez_curl | EZ-Bar Curl | BICEPS | EZ_BAR | Weighted | 1 | |
| incline_curl | Incline DB Curl | BICEPS | DUMBBELL,BENCH | Weighted | 2 | stretch-biased |
| hammer_curl | Hammer Curl | BICEPS | DUMBBELL | Weighted | 3 | |
| cable_curl | Cable Curl | BICEPS | CABLE | Weighted | 4 | |
| rope_pushdown | Rope Pushdown | TRICEPS | CABLE | Weighted | 1 | |
| oh_tri_ext | Overhead Triceps Extension | TRICEPS | CABLE,DUMBBELL | Weighted | 2 | long-head bias |
| skullcrusher | Skull Crusher | TRICEPS | EZ_BAR,BENCH | Weighted | 3 | |
| standing_calf | Standing Calf Raise | CALF_GASTROC | MACHINE,BODYWEIGHT | Weighted | 1 | knee straight |
| legpress_calf | Leg-Press Calf Raise | CALF_GASTROC | MACHINE | Weighted | 2 | |
| seated_calf | Seated Calf Raise | CALF_SOLEUS | MACHINE | Weighted | 1 | knee bent — different head |
| plank | Plank | CORE_ANTI_EXT | BODYWEIGHT | Time | 1 | |
| ab_wheel | Ab Wheel Rollout | CORE_ANTI_EXT | BODYWEIGHT | Reps | 2 | |
| dead_bug | Dead Bug | CORE_ANTI_EXT | BODYWEIGHT | Reps | 3 | |
| high_plank | High Plank | CORE_ANTI_EXT | BODYWEIGHT | Time | 7 | |
| pallof | Pallof Press | CORE_ANTI_ROT | CABLE | Weighted | 1 | |
| suitcase_carry | Suitcase Carry | CORE_ANTI_ROT | DUMBBELL | Time | 2 | |
| side_plank | Side Plank | CORE_ANTI_ROT | BODYWEIGHT | Time | 8 | |
| cable_crunch | Cable Crunch | CORE_FLEX | CABLE | Weighted | 1 | |
| hanging_raise | Hanging Leg Raise | CORE_FLEX | PULLUP_BAR | Reps | 2 | |

Weight-stepper increment: 2.5 lb when the working weight is ≤ 20 lb (light
isolation), otherwise 5 lb. Reps stepper is always ±1.

---

## 6. Setup and the planner

Superseded by PLAN.md A19. The setup screens are design §2 (Goal, Sub-goal, Your week, Equipment, About your
training, Your lifts, Recent numbers, Your plan); the planner (`domain/planner`) owns plan construction and
`domain/progression` owns openings and next targets. The normative rules and revisions live in
`docs/briefs/wizard-design-2026-11-08.md`.

---

## 7. Data layer

The program is **user-editable**, so it lives in Room (not as a code constant).
The library stays in code.

```kotlin
@Entity(tableName = "program_day")           // one row per day, ordered
data class ProgramDayEntity(@PrimaryKey val dayId: String, val position: Int,
  val title: String, val emphasisLine: String, val cardioJson: String?)

@Entity(tableName = "program_exercise")      // ordered within a day
data class ProgramExerciseEntity(@PrimaryKey(autoGenerate = true) val id: Long,
  val dayId: String, val position: Int, val exerciseId: String,
  val isMain: Boolean, val targetSets: Int, val repSchemeLabel: String,
  val hasWarmupHint: Boolean, val supersetExerciseId: String?, val note: String)

@Entity(tableName = "exercise_log", primaryKeys = ["dayId","programExerciseId","slot"])
data class ExerciseLogEntity(
  val dayId: String, val programExerciseId: Long,
  val slot: String,                 // "main" or "ss" (superset partner track)
  val setsJson: String,             // kotlinx.serialization List<LoggedSet>
  val checkDate: String,            // yyyy-MM-dd the `done` flags belong to
  val updatedAt: Long)
```

**Checkmark semantics (prototype-proven):** `done` flags are valid only for
`checkDate == today`. On read, if the stored date isn't today, surface all sets
as unchecked (weights/reps persist; checks reset daily). "DONE — advance" also
clears today's checks for the completed day.

**DataStore (Preferences):** `bodyweight`, `age`, `level`, `emphasis`,
`cardio_mode`, `cardio_placement`, `five_k`, `suggested_day`, `wizard_complete`.
The `level`, `emphasis` and `cardio_placement` keys are superseded by PLAN.md A19.

**Persistence lessons from the prototype (hard-won):**
- Write on every mutation, commit immediately (Room transactions), never hold
  state only in memory. The prototype's original data-loss bug was writes that
  hadn't committed before a screen rotation / process death.
- Also flush in `onStop()` of the activity/lifecycle as belt-and-suspenders.
- (Artifact-only lesson, doesn't apply to Room: the web prototype had to debounce
  per-key writes to a rate-limited KV store. Room has no such limit.)

`TrackerRepository` surface: `configFlow`, `setConfig`, `programFlow`,
`replaceProgram(Program)` (wizard output; superseded by PLAN.md A19), `swapExercise(dayId, position, newId)`,
`addExercise`, `removeExercise`, `resetDayToTemplate(dayId)`,
`logFlow(dayId)`, `updateSets(dayId, peId, slot, sets)`, `suggestedDayFlow`,
`advanceDay()`, `overrideViewDay()` is UI-local (not persisted).

---

## 8. Phone UI (Jetpack Compose, MVVM, Hilt, single activity)

### 8.1 Screens & navigation
`WizardScreen` (first run / re-run; superseded by PLAN.md A19) → `DayScreen` (home) ⇄ `SetupScreen` (⚙ tab)
⇄ `DayEditSheet` (gear on DayScreen).

### 8.2 Day screen — behavioral contract (all prototype-proven; replicate exactly)

**Tabs:** ⚙ + one tab per day (A/B/C/…). Suggested-next day is marked (dot +
outline). Tapping another day is an override; a small line reads
"Viewing Day X (override). Suggested next: Day Y."

**Exercise card, expanded:**
- Header row: green ✓ chip when all sets done · MAIN badge (accent) for mains ·
  name — for supersets the title is **"SS: {Primary} + {Partner}"** · "+1 WARM-UP"
  badge when `hasWarmupHint` · GOAL label top-right: tiny "GOAL" over a large
  accent number (+"/hand" when perHand). GOAL is read-only, always.
- Mains show helper line: "change the TOP set → ramp & back-off recalculate."
- Set rows (one per LoggedSet): kind label (R1…, **TOP** highlighted with accent
  border/background, B/O, or plain number for WORK/EXTRA) · weight stepper
  (−/value/+) · reps stepper · per-set ✓ toggle · × remove. TOP-weight edits
  cascade per Section 4.
- **Superset rows:** beneath each primary row, an indented sub-row (↳ marker,
  slightly inset, dashed top border, muted background) with the partner's own
  independent weight and reps steppers. One ✓ per round — checking the primary
  row dims **both** rows (they're performed back-to-back as one round).
  "+ add set" adds an aligned row to both tracks; × removes from both.
- "+ add set" dashed button under the rows.

**Collapse behavior:**
- Auto-collapse a card when *all* its sets are checked. Left border turns green;
  ✓ chip shows by the name.
- Tapping the header toggles collapse manually at any time; manual choice
  overrides auto (stored in-memory only, reset when a session advances).
- Collapsed summary line: completed sets as `w×r` joined by " · " — **supersets
  use `w×r(ssW×ssR)` joined by " / "** (e.g. `60×12(50×15) / 60×11(50×14)`).
  If nothing is checked yet: `{n} sets · GOAL {g}`.
- Checkmarks are per-date, so every new calendar day starts fully expanded.

**Below the list:** optional cardio finisher card (collapsible, defaults closed,
day-appropriate suggestion per Section 6.4) → big accent
**"DONE — ADVANCE TO DAY {next}"** button (advances suggested pointer, resets
collapse overrides + today's checks) → footer with the program philosophy and a
"clear today's checkmarks" action.

### 8.3 Day edit sheet (the per-day gear)

Opened from a gear icon in the day header. Lists the day's exercises with:
- **Swap** → bottom sheet of ranked same-pattern substitutions from the library
  ("Best replacements" order = subRank). Confirming swaps the slot; the log for
  the old exercise is kept (keyed by programExerciseId) but the new exercise
  seeds fresh from its own GOAL.
- **Remove** (min 3 exercises per day enforced) and **Add** (pick pattern → pick
  exercise).
- **Reset day to template** escape hatch (regenerates that day from the wizard's
  stored answers).
Edits persist immediately to Room.

### 8.4 Setup screen
Bodyweight stepper, age stepper, level selector, emphasis selector, live preview
of the calculated main-lift GOALs, cardio prefs, and a "Re-run setup wizard"
button (destructive-confirm: replaces the program).

### 8.5 Visual design (keep the prototype feel)
Near-black background #0D0D0F, card surface #16161A, borders #2A2A30. Per-day
accents: A #C1440E, B #2D5A3D, C #B8860B, D #1F4E5F (cycle for >4 days).
Condensed display font (Oswald-class) for numbers/labels; clean sans for body.
Dark theme only in v1.

---

## 9. Wear OS companion

Watch = glanceable in-set logging; phone is source of truth.
- Separate `:wear` module sharing `:domain` (pure Kotlin compiles for Wear as-is).
- Shows suggested day's exercise list; per-exercise screen exposes GOAL and the
  set rows with rotary/± input for weight and reps and a done tick per set.
  Supersets render the partner sub-row beneath, same one-tick-per-round rule.
- Sync: Wearable Data Layer — `DataClient` for state snapshots, `MessageClient`
  for immediate set-edit deltas. Last-write-wins (single user).
- Use Horologist + Compose for Wear OS; do not reuse phone layouts.

---

## 10. Out of scope for v1
History/trend charts (maintenance app; the persisted per-set weights ARE the
"last time" reference) · cloud sync/accounts · plate calculator · rest timers ·
RPE · custom exercise creation (library is fixed) · light theme · tablet layouts ·
equipment-profile filtering of substitutions (v1.1 candidate).

---

## 11. Build order and pinned verification

### 11.1 Retired formula verification

PLAN.md A19 retires the bodyweight formula and its verification list. Historical design briefs retain that history;
no formula goal or opening derived from a profile is a current contract.

### 11.2 Pinned cases

These cases and the named tests are the contract. A number changes only when the same PR deliberately amends its
case and explains why. The tests win where earlier design prose differs. Normative design is
`docs/briefs/wizard-design-2026-11-08.md`, including its revisions.

R is `PlannerTest.referenceAnswers`: Strength, four days, 60 minutes, Full gym, pounds, A year or more, with mains
squat, trap-bar deadlift, bench, overhead press and barbell row. Recent Good sets are squat 275×5, bench 185×5,
trap-bar deadlift 315×5 and overhead press 115×5; row has no recent number. Session estimates use 15 s setup plus
reps × 3 s plus rest per set; only the rep or timed work doubles for each-side work, and a superset rests once.

| Case | Subject | Expected | Pinned by |
|---|---|---|---|
| 1 | R's program | R has A Lower (squat MAIN, deadlift SECONDARY), B Upper (bench and row MAIN, press SECONDARY), C Lower (deadlift MAIN, squat SECONDARY), D Rest, E Upper (press MAIN, bench and row SECONDARY). All five mains occur as MAIN exactly once. A/B/C/E estimates are 2,565/3,366/2,523/3,114 s, each ≤ 3,600 s, with no reasons; deload cadence 6. E bench has 3 sets and row 2. Weekly chest/front delts/triceps are 6/8/11.5 sets. | `PlannerTest > case 1 reference rotation keeps every main and second exposure with the audited session estimate` |
| 2 | R's squat opening rows | R's squat: RAMP 165×5, RAMP 220×3, TOP 275×5, BACKOFF 250×5 twice. Fixed 5, 2 RIR, cadence 2, increment 2.5%, STRENGTH, no calibration. Its secondary opens at 270×5 twice with 3 RIR. The row without a recent number opens at 45 lb calibrating. | `SchemeTest > case 2 Strength shapes R's squat`; `PlannerTest > case 2 recent squat seeds the Strength scheme and secondary effort` |
| 3 | R's bench opening rows | R's bench: RAMP 110×5, RAMP 150×3, TOP 185×5, BACKOFF 165×5 twice. | `SchemeTest > case 3 Strength shapes R's bench`; `PlannerTest > case 3 recent bench seeds its ramps top and backoffs` |
| 4 | Estimate | 185×8 Good, n=10: e1RM 246.66666666666666; 3×5 at 2 RIR opens at 200 lb. 135×12 Good exceeds the ten-rep estimate limit and opens at 135 lb calibrating. | `EstimateTest > case 4 Epley is trusted only through ten reps including reserve`; `SlotSeedingTest > case 4 opening uses the estimate or keeps an untrusted history load for calibration` |
| 5 | Increase, trained | Trained squat TOP 275 and bench TOP 185 hold after the first Good exposure (success streak 1); the second increases them to 280 and 190. Cadence 2; lower/upper increments 2.5%/1.25%, rounded to at least one equipment step. | `ProgressionTest > case 5 R's squat and bench increase after two successful exposures` |
| 6 | Increase, new | New squat straight 3×5 Good at 95 → 100 after one exposure (5%); bench 65 → 70 (2.5%, at least one step). | `ProgressionTest > case 6 New squat and bench advance by at least one step` |
| 7 | Easy doubles | All working sets Easy, read as 4 RIR: trained squat 275 → 285, twice the rounded 5 lb increment, within 10%. Its 357.5 lb estimate predicts 2.631578947368421 RIR at 285×5, above the target 2. New squat 95 → 100, a single step: the doubled 105 would leave 0.2857142857142857 RIR, below its target 3. An untrusted estimate also takes one increment. | `ProgressionTest > case 7 Easy doubles the increase within the ten percent cap`; `EstimateTest > rirAt predicts reserve for experienced and New squat doubles` |
| 8 | Miss then drop | New bench straight 3×5 at 185: 5/5/3 holds with rep-miss streak 1; then 5/4/4 lowers to 175 (5% drop), clears all streaks and records reset day 130. | `ProgressionTest > case 8 a first rep miss holds and a second lowers the New bench` |
| 9 | Rough day | The old hold-and-miss-streak case is retired by revision 23. Rough day reversibly swaps Finish's prior rows and rules using its undo record, excluding SCALED/RECALIBRATE restarts; unticking restores Finish's result. Finish counters stay intact; two consecutive rough strength days trigger a reversible light cycle only in Automatic. A receipt refusal reports an open day or a failed mutation. | `DayViewModelReceiptTest > post commit death recovers counts publishing and Undo through Finish`; `DayViewModelReceiptTest > Rough day refusals use Rough day mutation results`; `ReceiptProgressionTest > Rough day swaps rows and rules but leaves Finish counters alone`; `ReceiptProgressionTest > Rough day preserves break restart rows and reverts other lifts`; `ReceiptProgressionTest > two rough strength days start a rotation of light days only on Automatic`; `ReceiptProgressionTest > two rough strength days on Ask leave the light counter alone` |
| 10 | Lowered kept | New bench logged 175/175/175 Good instead of 185 becomes KeptLowered(175), clears streaks and opens at 175; the next Good exposure reaches 180. R's cadence-2 bench lowers to rows 105/140/175/160/160, holds on the first success and reaches 180 on the second. | `ProgressionTest > case 10 lowering straight bench becomes the next working load and clears streaks`; `ProgressionTest > case 10 lowering R's bench cascades and preserves its two exposure cadence` |
| 11 | Range | DB row 60, Range(8,12,current 8): 10/9/8 Good → current 9, load 60. At current 12, 12/12/12 → 65 lb and current 8. | `ProgressionTest > case 11 Range uses the lowest anchor reps then increases load at its ceiling` |
| 12 | Calibrating | Calibration at 5 reps, 2 RIR, 5 lb steps: 45×5 Easy → 50; 50 Easy → 55; 55 Easy → 60. A 60×5 Good set calibrates to 60; a Max set calibrates to 55. Finish keeps four 60×5 rows for the Good fixture and clears calibration and streaks. | `ProgressionTest > case 12 next set calibration climbs until the first non Easy anchor` |
| 13 | Layoff | Squat twice per four-entry training rotation at four days/week: recurrence ceil(7×4/(4×2))=4 days. Gaps 4/7 → extra 0/3, normal; 9 → extra 5, Hold; 16 → extra 12, SCALED 0.95, TOP 260. Success or incomplete work restores 275 with streaks unchanged; rep miss instead keeps 260 and clears streaks; effort miss keeps 260 with effort-miss streak 1. Gap 30 → extra 26, SCALED 0.85, TOP 235 with two working sets. Gap 60 → extra 56, RECALIBRATE at 205; Start's override is a view, Finish persists calibration. All-Easy then reaches 215 and stays calibrating. Light-cycle and backoff-only finishes preserve the recalibration opening; pre-Start phone/watch previews write nothing. | `ProgressionLayoffTest > case 13 expected recurrence uses the rotation appearances`; `ProgressionLayoffTest > case 13 a normal gap and three extra days run normally`; `ProgressionLayoffTest > case 13 layoff Hold keeps rows and all streaks`; `ProgressionLayoffTest > case 13 twelve extra days scale the squat to ninety five percent`; `ProgressionLayoffTest > case 13 scaled success or incomplete restores pre layoff rows and all streaks`; `ProgressionLayoffTest > case 13 scaled rep miss restarts and clears all streaks`; `ProgressionLayoffTest > case 13 scaled effort miss restarts with one effort miss`; `ProgressionLayoffTest > case 13 twenty six extra days scale to eighty five percent and keep two thirds of work sets`; `ProgressionLayoffTest > case 13 long layoff recalibrates as a view and all Easy keeps finding the load`; `ProgressionLayoffTest > case 13 light cycle preserves recalibration after a fifty six day gap`; `ProgressionLayoffTest > case 13 backoffs only after a fifty six day gap retain the recalibration opening load`; `ProgressionLayoffTest > case 13 pullups after a sixty day gap hold at eight reps without load calibration`; `ProgressionLayoffTest > case 13 timed planks after a sixty day gap keep seconds without load calibration`; `RunOpeningTest > case 13 a sixteen day gap opens the squat at ninety five percent`; `RunOpeningTest > case 13 a normal rotation gap opens as stored and a nine day gap holds`; `PreStartOpeningTest > case 13 shows 260 on the Day screen, the preview and the watch before Start and writes nothing`; `PreStartOpeningTest > case 13 decrement before Start uses the focused TOP on the opening`; `PreStartOpeningTest > case 13 Remove and Undo restore the opened TOP with its identity and watch stamp`; `PreStartOpeningTest > case 13 Remove and Undo restore a padded partner round aligned with MAIN`; `DraftRunTest > case 13 shows the opening before Start and writes nothing`; `DraftRunTest > case 13 removing the first ramp before Start keeps identities through Finish` |
| 14 | Light cycle | From case 2, three working sets ×0.6 rounds up to two: TOP 250×5 and one BACKOFF 225×5, ramps unchanged. Progression holds. A four-training-day light cycle counts 4→3→2→1→0 across finished days; Rest does not consume a training day. | `LightWeekTest > case 14 light rows keep ramps and sixty percent of work sets at ninety percent load`; `LightWeekTest > case 14 Finish restores working rows and preserves all streaks on a light day`; `LightCycleTest > case 14 a fired light cycle lasts one rotation`; `LightCycleFinishTest > case 14 a fired light cycle lasts four finished days on R's plan` |
| 15 | Rest by role and goal | Straight 3×5 squat MAIN: Strength rest 180 s, Muscle 120 s; ISOLATION curl 60 s. TOP bucket override 150 reaches the Strength squat; slot override 150 on the Muscle squat beats bucket override 100. New Strength mains have Fixed 5, 3 RIR and 3 straight sets. | `RestPolicyTest > case 15 role and goal defaults and override precedence`; `PlannerTest > case 15 new mains and isolation use the timer resolver and override precedence` |
| 16 | Cascade with scheme | STRENGTH TOP 275 edited to 285 gives ramps 170/230 and back-offs 255/255. PROTOTYPE rows start with ramps 130×5, 165×5, 190×5, 210×3, TOP 235×5 and back-off 175×8; TOP edited to 245 gives ramps 135/170/195/220 and back-off 185. Reps remain 5/5/5/3/5/8. Completed ramps retain their weights and percentage positions. | `SchemeTest > prototype equals today's default rows`; `SchemeTest > case 16 cascades follow the chosen scheme`; `PlannerTest > case 16 editing the seeded squat top cascades through the Strength scheme`; `SetEditingTest > top cascade keeps completed ramps and their percentage positions` |
| 17 | Run cap | Same-family 30-day maximum 8,050 m → 10% limit 8,855 → 8,800 m on the 100 m grid. Planned 10,000 opens at 8,800; 7,500+5%=7,875 stays below it. Ten 1,000 m intervals become eight work rounds with seven recoveries. Maxima are independent by unit. Setup seeds the cap only until history exists and expires by day 30; previews and Finish use the same cap. | `RunCapTest > case 17 limit`; `RunCapTest > case 17 long run`; `RunCapTest > case 17 under the limit`; `RunCapTest > case 17 intervals drop rounds`; `RunCapTest > case 17 maxima are independent`; `RunCapTest > case 17 setup answer seeds the limit until history exists`; `ProgressionCardioTest > case 17 at Finish under the limit`; `ProgressionCardioTest > case 17 capped at Finish unless the cap is off`; `ProgressionCardioTest > case 17 the first finished session ends the seed`; `ProgressionCardioTest > case 17 at run open the cap uses history or the unexpired seed`; `ProgressionCardioTest > case 17 Finish keeps the increase when grid rounding would remove it`; `ProgressionCardioTest > case 17 at run open grid rounding never shrinks below the anchor`; `ProgressionCardioTest > case 17 at run open small time sessions keep their increase`; `CardioOpeningTest > case 17 caps the stored long run by the family's last 30 days`; `CardioFinishTest > case 17 an 8050 m run in the window caps 10000 m to 8800 m`; `PlannerRunTest > case 17 history wins over the setup seed and caps the base long run`; `PreStartOpeningTest > case 17 shows the capped run on the preview and the watch before Start and writes nothing` |
| 18 | Counts as finished | A 900 s run with at-end 3,470 m/900 s, stopped at 904 s/3,485 m, is finished and compares 3,470 m: overrun 4 s/15 m. A 2,414 m end reached at 750 s and stopped at 761 s compares 750 s. Stopping at 890 s is unfinished. Pre-v25 null at-end fields retain each reader's legacy rule. No grace window: aim 3,480 m with at-end 3,470 repeats; its legacy-total counterpart increases to 945 s/3,654 m. A 400 m aim of 105 s succeeds at at-end 104 s despite total 110 s, increasing to 420 m/110 s; at-end 106 s repeats. | `FinishedRuleTest > case 18 time end with overrun`; `FinishedRuleTest > case 18 distance end`; `FinishedRuleTest > case 18 stopped early`; `FinishedRuleTest > case 18 pre-v25 rows keep today's rule in each reader`; `FinishedRuleTest > case 18 hand logged v25 row has one reading which is both`; `FinishedRuleTest > case 18 v25 row with at-end fields that did not finish`; `FinishedRuleTest > case 18 v25 time end reached with no distance known`; `FinishedRuleAtEndTest > case 18 a distance end reached at the tap reads its time`; `FinishedRuleAtEndTest > case 18 a steady run past its end keeps its time and no distance`; `ProgressionCardioTest > case 18 no grace window at Finish`; `ProgressionCardioTest > case 18 distance end meets its time aim at the reading`; `ProgressionCardioTest > case 18 open ended v25 run counts after reaching its end with overrun`; `CardioAtEndMappingTest > case 18 at-end readings round trip without sprint timing` |
| 19 | Taper | Half marathon long run 18,000 m: 12 days out → 10,800 (×0.6); 5 days out → 8,100 (×0.45). Factors follow audit 2026-11-13 row 15 (Bosquet 2007, Wang 2023; weak). Hard flags and aims stay unchanged. The live goal date controls taper, before the run cap; pre-Start phone/watch previews use the same opening. | `TaperTest > case 19 half marathon`; `TaperTest > case 19 through cardioAtStart`; `TaperTest > case 19 hard flags and aims stay unchanged`; `TaperTest > case 19 marathon short race and shuttle bands follow the live date`; `TaperTest > case 19 ladder day does not taper`; `TaperTest > case 19 taper precedes the run cap and applies with the cap off`; `PlannerRunTest > case 19 stored long run opens through the existing taper`; `CardioOpeningTest > case 19 tapers from the plan goal`; `PreStartOpeningTest > case 19 shows the tapered run on the preview and the watch` |
| 20 | Riegel | 2^1.06=2.084931 (tolerance 10⁻⁶). 5K in 1,500 s → 10K 3,127 s (52:07); half in 6,600 s → marathon 13,761 s (3:49:21), or 14,449 s (4:00:49) with margin. Round once at the end. Planner emits GoalCheck(3127) or GoalCheck(14449) when needed, never for an ultra. | `RiegelTest > case 20 goal checks round once and apply the marathon margin`; `PlannerRunTest > case 20 goal check rounds once and excludes ultras` |
| 21 | Test aim | 2 mi (3,219 m) under 960 s: 400 m test aim 113 s, 800 m 227 s. Planner has four hard 400 m work reps at 113 s and three 120 s recoveries. Cooper's 2,800 m/720 s yields a 98 s aim; PACER/HAMR carry shuttle target 60 without pace aims. A 1.5 mi test uses 2,414 m/960 s. | `GoalPaceTest > case 21 fitness test reps aim five percent faster than goal pace`; `PlannerRunTest > case 21 field tests carry aims and supported goal forms` |
| 22 | Set counting | Upper fixture: bench/OHP/row each 3 sets. Chest 3; front delts 4.5; triceps 3; upper back 3; lats 3; biceps 1.5; rear delts 1.5. Each muscle has one exposure. Major-muscle volume bands and the 20-set ceiling remain covered by planner invariant tests. | `SetCountTest > case 22 bench press and row count primary and half secondary sets` |
| 23 | Older adult | 65+: no rule below 3 RIR; balance comes first on lifting days (stance and carry, or stance alone with kettlebells), stance rest 30 s and MAIN rest 120 s; no SECONDARY role. Two lifting days with squat once give recurrence 7: gap 10 is normal. At gap 25 (18 extra days), fewer than 16 exposures recalibrates at ×0.75; 16 or more exposures scales ×0.85 to 235 with two working sets. At gap 27, exposure 15 gives 205 with three working sets and calibration; exposure 16 gives 235 with two and no override. Trained older bands recalibrate past 42 extra days; New lifters still recalibrate past 14 regardless of exposures. | `PlannerTest > case 23 older plans keep balance first and one squat track for layoff recurrence`; `ProgressionOlderLayoffTest > case 23 an older trained lifter's eighteen extra days scale with fewer sets`; `ProgressionOlderLayoffTest > fifteen versus sixteen older exposures at twenty days past recurrence`; `ProgressionOlderLayoffTest > trained older layoff bands use the recurrence clock through forty three days` |
| 24 | Rotation | Rotation preserves authored and old strength-plan order and excludes generated C1/C2 extras when training days exist. Run A Easy+strength, B Quality+strength, C Long, D Rest visits A→B→C→D; Rest resolves to A. The distance long run is 8,800 m, the timed fixture 3,180 s. | `RotationTest > generatedPlansKeepTheExistingStrengthRotation`; `RotationTest > mixedTrainingDaysVisitRunLiftRunAndWrap`; `RotationTest > liftingOnlyGeneratedPlanKeepsItsRotation`; `RunRotationTest > case 24 run days with strength and rest rotate through every entry`; `PlannerRunTest > case 24 run letters rotate through rest and keep the anchor unit` |
| 25 | Short sessions | Strength, trained, five mains, 1×30 min: step 6, no core; each main one 80%×3 ramp at 90 s rest plus one ×5 TOP at 120 s rest. Estimate 300+5×((15+3×3+90)+(15+5×3+120))=1,620 s. At 2×30 min both days stop at step 3 with two straight sets per main and a two-set core: A squat/bench/row 1,800 s; B deadlift/press 1,338 s. | `DoseLadderTest > case 25 one short day retains five mains at step six`; `DoseLadderTest > case 25 two short days stop at step three` |
| 26 | Coarse step | DB lateral 10 lb with a 5 lb step: Fixed 12 normalizes to Range(12,16); success moves current to 13. At current 16, 16/16/16 → 15 lb, current 12. Pullup 8→9, 19→20, then holds 20 and offers added weight. | `ProgressionTest > case 26 coarse dumbbells normalize to a Range and take one step at its ceiling`; `ProgressionTest > case 26 pullup adds reps through twenty then offers weight` |
| 27 | History load | History squat 225×5 without RIR reads as 2: e1RM 277.5. Muscle 3×10 at 2 RIR computes 198.21 (tolerance 0.01), opening at 200 lb without calibration. | `EstimateTest > case 27 history estimate adjusts for a different target`; `SlotSeedingTest > case 27 history without rir reads as two and adjusts to Muscle reps` |
| 28 | Conversion | Pre-A19 generated slots keep every stored row. Ramped squat gets MAIN, Fixed 5, 2 RIR, increment 2.5%, PROTOTYPE. Never-opened hack squat gets ACCESSORY, Fixed 10, 2 RIR, increment 2.5%; history supplies its estimate or it opens 3×10 at 20 lb calibrating. Ask mode, cardio, Rest, parked rows and history are preserved. Recipe equipment and typed goals move to settings, recipe clears; the conversion fixture retains squat goal 315 and legacyMains squat/bench/row. Frozen v1 mains also preserve edited old-plan membership on later restores. | `PlanConversionTest > case 28 converts the generated plan and keeps every row`; `PlanConversionTest > case 28 history is byte identical after upgrade and conversion`; `NeverOpenedSeedingTest > case 28 a never opened hack squat opens at twenty pounds calibrating`; `NeverOpenedSeedingTest > case 28 a never opened hack squat with history opens at the estimate`; `NeverOpenedSeedingTest > case 28 history in kg rounds to the kg step`; `SlotSeedingTest > case 28 never opened hack squat seeds three tens at twenty pounds calibrating`; `SlotRuleTest > case 28 conversion keeps ramped rules and supplies never opened accessory defaults`; `SlotRuleJsonTest > case 28 and a range with full state round trip exactly` |
| 29 | Event identity | 800 m, last six weeks, goal 120 s: 3×300 m at 45 s with 240 s recoveries → 4×300 → 4×400 at 60 s, one step per two finished Hard sessions. Earlier/undated: 6×400 at 60 s with 120 s recoveries. 1,500 m goal 270 s: 6×400 at 72 s; 5K in 1,200 predicts 89 s reps. 50K starts long at 30,000 m and rises 31,500→33,075→34,729→36,465→38,288→40,000, or stops at 35,000 at 0.36 s/m (3.5 h). Other ultra fixtures open at 11,880 s (100K cap), 48,280 m (50 mi), or 14,400 s for time-based 50/100 mi. | `PlannerRunTest > case 29 middle distance uses dated race reps and unrounded predictions`; `PlannerRunTest > case 29 ultra long forms start at the target and obey the cap`; `PlannerRunProgressTest > case 29 planner sharpening steps to four three hundred then four four hundred metre reps`; `ProgressionRunRangeTest > case 29 eight hundred metres steps every two finished hard sessions and scales aims`; `ProgressionRunRangeTest > case 29 fifty K long run stops at distance or three and a half hours without shrinking` |
| 30 | Readiness | New marathoner, 30 weeks out, no longest: LADDER level 1, 60 s run/90 s walk ×10, no cap. Twenty-four Easy finishes, two per level, reach level 12's run and then BASE with anchor 5,000 m, limit 5,500. At +5% each cycle the base gate follows increase 15: increase 14 is 9,900 m and 15 is 10,395; timed equivalents 3,566/3,744 s. HALF_BUILD then opens 11,400 m (target 21,000); MARATHON_BUILD opens 22,500 (target 32,000). A date only eight weeks out still generates and reports DateTooClose. | `PlannerRunTest > case 30 marathon from no longest starts on the untrimmed ladder`; `PlannerRunProgressTest > case 30 ladder base half and marathon progress through replanning from the log`; `RunPhaseTest > case 30 existing five percent increases reach the base gate on step fifteen`; `RunReplanTest > case 30 the half build opens at 11400 m through the re-plan` |
| 31 | Undo | Finish stores prior rows/rules, cardio rule and light counters in its undo journal. After restart Undo restores all of them and clears the journal; next Start clears it unread. The counter fixture reverses (remaining 2, cycles 6) to (0,5); another Undo restores the captured (1,6) alongside identical bench rows and rule. | `LightCycleFinishTest > case 31 undo after a restart restores the light counters a Finish fired` |
| 32 | Effort misses | New bench 185 at rule RIR 3: two 5/5/5 sessions with a Hard set hold 185 and slow cadence 1→2, streaks cleared. R's bench at RIR 2: two Max sessions with all reps made hold 185 and slow cadence 2→3. Two rep misses, 5/5/3 then 5/4/4, still lower to 175. | `ProgressionTest > case 32 two effort misses slow cadence and never lower the New bench`; `ProgressionTest > case 32 two Max sessions slow R's bench cadence to three`; `ProgressionTest > case 32 two rep misses still lower the load from a fresh rule` |
| 33 | Direct calibration | Calibrating Muscle DB row target 10 at 2 RIR: typed 40×10 Good anchors at 40. 40×9 Good misses target; 40×0.9=36 rounds to 35. Finish clears calibration and writes 35×10 rows. | `ProgressionTest > case 33 high rep calibration anchors directly and lowers a missed target` |
| 34 | Ladder and cap | Twenty-four Easy finishes, two per level, advance ladder 1→12, then finish the ladder, with no cap applied to any ladder block: the first Easy finish at a level earns a credit and repeats, the second moves up. Good repeats (including three consecutive Good finishes); a finish with no word repeats, at level 12 too (audit 2026-11-13 row 3); Good, no word and unfinished keep a credit; Hard or Pain drops one level and clears the credit, and at level 1 repeats with it cleared. With dates, an Easy finish counts only while fewer than three counted Easy finishes fall in the seven local days ending today and it is not dated before the newest counted one; a level-up waits a week after the last one while any seven consecutive days of the last fourteen hold more than three runs; a waiting finish repeats and keeps its credit. A finished 5,000 m run becomes the first anchor, limit 5,500. A repeated 5,000 m run always fits; the limit never falls below the largest total. | `ProgressionLadderTest > case 34 ladder table and rule bounds`; `ProgressionLadderTest > case 34 twenty four Easy sessions climb two per level with no cap`; `ProgressionLadderTest > case 34 not reported repeats the level`; `ProgressionLadderTest > case 34 Good repeats however many times`; `ProgressionLadderTest > case 34 Hard drops one with a floor at level one`; `ProgressionLadderTest > case 34 Pain drops one whatever the word`; `ProgressionLadderTest > case 34 unfinished repeats unless Hard drops it`; `ProgressionLadderTest > case 34 level 12 run anchors the cap`; `ProgressionLadderTest > case 34 after the ladder A16 takes over`; `ProgressionLadderTest > case 34 repeats preserve rounds editor changes and activity on a level change`; `RunFinishTest > two ladder Easy finishes move every ladder day up one level`; `RunFinishTest > an unrated ladder finish repeats until a rating replays it`; `ProgressionLadderTest > the cap counts three Easy finishes in seven days`; `ProgressionLadderTest > the guard holds a second level-up within a week for a busy runner`; `ProgressionLadderTest > the guard holds LadderDone`; `ProgressionLadderTest > a backdated Easy is a Repeat`; `ProgressionLadderCalendarTest > daily running with one Hard rises at most a fifth`; `ProgressionLadderCalendarTest > three runs a calendar week on drifting days rise at most a fifth`; `ProgressionLadderCalendarTest > three fixed weekdays meet the proven bound`; `RunFinishTest > a fourth Easy in a week repeats` |
| 35 | Planned light in Ask mode | Ask mode with deload cadence 6 fires on the sixth wrap: the two-day fixture moves counters (0,5)→(2,6)→(1,6)→(0,7). Light squat rows are ramps 165×5/220×3, TOP 250×5, BACKOFF 225×5; stored working rows stay intact and no increase is offered. Ask never fires a reactive light cycle. | `LightCycleTest > case 35 the planned trigger fires at every sixth wrap`; `LightCycleFinishTest > case 35 a planned light cycle fires at the sixth wrap in Ask and opens light rows without a load or an offer` |
| 36 | Over budget | 65+, trained, five mains, 1×30 min: balance 400 s stays; step 6 estimate 2,035 s, OverBudget 235 s, displayed 4 min, plus BalanceDaysOffered. Stance is 2×30 s (each side), carry 2×20 s. At 45 min, step 0 estimate 2,668 s fits with core. Dropping row leaves four mains, step 6, 1,768 s and no excess; three mains stop at step 4, 1,741 s with core. Setup drops nothing until chosen and allows creation with all five intact. | `DoseLadderTest > case 36 balance stays while an older day reports every excess second`; `SetupFlowStateBuilderTest > case 36 shows the prompt with every main kept`; `SetupFlowViewModelTest > case 36 waits for the lifter to choose` |
| 37 | Sprint timer | 30 m, PHONE, THREE_POINT, 4.52 s stores centis 452. GATES 4.30, HAND 4.40, PHONE 40 m, and a standing start belong to separate series (five in the fixture). Best rep per goal series becomes an attempt. PHONE 4.45 beats 4.52; HAND 4.45/4.43 versus 4.52 is SAME, 4.42 is FASTER; 4.61 SAME, 4.62 SLOWER. Gates/video use exact comparison; incompatible methods or starts reject comparison. | `SprintComparisonTest > case 37 splits series by distance method and start protocol`; `SprintComparisonTest > only hand verdicts have a noise threshold and other setups reject`; `SprintComparisonTest > case 37 attempts take the fastest rep per goal series in first appearance order`; `DayViewModelSprintTimerTest > case 37 phone and typed reps store their series and a trial adds attempts` |
| 38 | User-built plan | Build my own Strength DB curl 3×8 at 30: ACCESSORY, RIR 2, upper-body increment 1.25%, cadence 1, drop 5%. A 5 lb step is coarse, so 12/12/12 at current 8 → Range(8,12,current 12), load 30; the next 12/12/12 → 35 lb, current 8. Hand-added ramped bench 185×5 is MAIN, Fixed 5, PROTOTYPE: ramps 100×5/130×5/150×5/165×3, TOP 185×5, BACKOFF 140×8. | `Case38Test > case 38 hand added lifts store prescribed rows and rules and the curl moves reps before load`; `SlotRuleTest > case 38 rules for a hand added accessory and ramped main`; `SlotSeedingTest > case 38 rows keep the prototype scheme for a hand added ramped bench`; `ProgressionTest > case 38 curl reps reach the range ceiling before load increases` |
| 39 | Reps in time | Push-up, three 1:00 windows, aim 20, ACCESSORY Fixed 20 at RIR 2, cadence 1: rows seed 3 × 20 reps at 0 lb, and a window without an aim seeds 0. 20/20/20 → aim 21 (RepsTo 21, ALL_MADE). 20/18/20 → aim 20 (REP_MISS), and a second such miss still repeats 20, no stall. Aim 25: 25/25/25 → 26, rows stay 0 lb and three sets, no weight offer. Cadence 2: the first success holds 20 (STREAK), the second → 21. A Max window → 20 (EFFORT_MISS). No aim: 25/24/22 → MEASURED, rows 0 reps, target unchanged. | `ProgressionRepsInTimeTest > case 39 rows seed the rep aim`; `> case 39 every window at the aim raises the aim by one rep`; `> case 39 a short window repeats the aim and never stalls`; `> case 39 a window with no aim only measures`; `> case 39 the aim passes twenty reps with no weight offer or extra set`; `> case 39 the aim rises at the lift cadence`; `> case 39 a max effort window repeats the aim`; `FinishRepsInTimeTest > a rep window at its aim moves to the next aim and grants no shared credit` |

### 11.3 Delivery order

Build `:domain` first, then `:data`, then the phone UI. Repository verification covers Room and DataStore round trips
and process death. Verify the day behavior in §8.2 on the phone.

### 11.4 Watch persistence

Build `:wear` last; verify a watch set edit survives a phone app restart.

---

## 12. Research appendix (so the implementer never needs to search)

All paraphrased findings gathered during design; cite none in-app, they justify
defaults and copy.

**Frequency & splits.** Meta-analytic evidence (Schoenfeld et al., 2016) supports
training each muscle ≥2×/week over 1×/week at equal volume. Full-body handles
2–4 days/week well and is the most missed-day-tolerant. Upper/Lower is the
standard 4-day. PPL fits 6 days (each muscle 2×/wk) or 3 days (each 1×/wk —
acceptable for maintenance, sub-optimal for growth). PPLUL is the standard 5-day
hybrid: upper 3×/wk, legs 2×/wk. Volume and proximity to failure drive most
adaptation; split choice is mainly a scheduling instrument.

**Madcow / ramp structure.** Classic Madcow: 3-day full-body; volume day = 5 ramp
sets of 5 (top set last); heavy day = ramp to a top **triple**, then one back-off
set of 8 at ~75%. This app's hybrid (validated in prototype): 4 ramp sets
(55/70/80/90%, last ramp a lighter triple) → top set (reps by emphasis) →
back-off ×8 at 75%. Rows are a legitimate "big four" anchor (StrongLifts lineage
treats squat/bench/deadlift/row as the core barbell lifts); 5/3/1 instead anchors
on squat/bench/deadlift/OHP. Progressing an anchor ~once per week is the
sustainable intermediate cadence.

**Strength standards & age.** Typical 1RM-to-bodyweight ratios (intermediate male):
squat ~1.5×, bench ~1.25×, deadlift ~2.0×, OHP ~0.8×; advanced ≈ 2.0/1.75/2.5/1.1.
Standards assume ages 20–40; subtract ~5% per decade after 40 (hence ageDerate).
A 5-rep max is ~87% of 1RM — the maintenance factors (0.60–0.75) deliberately sit
below that so top sets are ownable, not grinding.

**Hamstrings (why leg curls exist).** Hamstrings cross hip and knee: hinges (RDL,
deadlifts) train hip extension; curls train knee flexion. The biceps femoris
short head crosses only the knee — hinges cannot train it; only knee-flexion work
does. EMG shows lying curls bias the lower/lateral hamstrings vs RDL's upper
region → do both patterns for complete development. Seated curls train the
hamstring at longer muscle length than lying (stretch-position work shows a
growth advantage), hence seated is subRank 1. Program rule: every leg-containing
day includes one hinge OR one knee-flexion move, and the cycle contains both.

**Leg extensions.** Optional. Value = quad isolation without spinal/hip load and
full-ROM knee work (knee health). Not required when squat/single-leg volume is
adequate.

**Deadlift variations.** Trap-bar: least lumbar strain and lowest mobility
demand; knee/hip ROM within ~2–6° of conventional; higher peak power; default
for non-competitors. Conventional: greatest glute/ham activation and largest ROM
(possible hypertrophy edge), highest back demand. Sumo: quad-biased (~+15% vastus
lateralis in phases), ~20–25% shorter ROM, upright torso. All three are valid;
choose by anatomy/comfort — the app exposes the choice on the deadlift anchor.

**Cardio interference & ordering.** Interference is real but minor at recreational
volumes (2–4 sessions/week each). Order asymmetry: lifting doesn't blunt endurance
adaptations, but cardio before lifting cuts lifting performance (up to ~10% volume
for hours). Therefore: always lift first; hard running goes on leg-fresh days;
easy Zone 2 after leg-heavy days aids recovery without adding leg fatigue.
Rushing between lifts to "keep heart rate up" sacrifices strength output — rest
2–3 min on mains; cardio is a separate block.

**Warm-ups.** The ramp IS the main lift's warm-up. Isolation accessories on
already-warm muscles need none. One light "feeler" set is worthwhile only for the
first accessory that loads a *new* movement pattern after the main — hence the
single `hasWarmupHint` per day.

**Core.** Postural/stabilizing musculature tolerates daily work; rotate the
pattern (anti-extension / anti-rotation / flexion) across days rather than
repeating one, and keep it submaximal. A short core piece can close every session.

**Muscle-angle rotation (the "calves principle").** Across a full cycle, hit each
region through complementary angles: gastroc (standing, knee straight) vs soleus
(seated, knee bent); flat vs incline pressing; horizontal-row thickness vs
vertical-pull width; hip-hinge vs knee-flexion hamstrings; bilateral squat vs
single-leg. The generator's emphasis lines make this visible per day.

**Substitution logic.** Movement pattern is the unit of exchange: a replacement
must load the same pattern (e.g., leg press → hack squat → goblet squat →
Bulgarian split squat). Machine↔free-weight swaps within a pattern preserve the
training effect for maintenance purposes.
