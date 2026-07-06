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
   the lifter's living record. GOALs change only via Setup inputs.
3. **Top set is the anchor.** Main lifts use a Madcow-style ramp → top set →
   back-off. Changing the top-set weight recalculates the ramp and back-off.
   Editing any other set changes only that set.
4. **Maintenance math.** Targets are derived from bodyweight-ratio strength
   standards, then deliberately scaled *below* max-effort territory and
   age-adjusted. "Own it, don't grind it."
5. **Every tap is cheap.** Steppers, not keyboards. Checkmarks, not forms.
   Cards collapse when done.
6. **Lift first, cardio after.** Cardio is planned around the lifting, never
   before it, with intensity matched to that day's leg fatigue.
7. **Local-first.** Room + DataStore. No network permission needed in v1.

---

## 2. Feature inventory (v1)

| Area | Feature |
|---|---|
| Onboarding | Setup wizard: goal emphasis → days/week → split (sane default) → anchors → cardio prefs → bodyweight/age/level |
| Program | Generated editable program stored locally; per-day edit (swap/add/remove exercises) with ranked same-pattern substitutions |
| Workout | Day screen: ramped mains with cascade, per-set weight+reps steppers, add/remove sets, supersets with paired sub-rows, per-set checkmarks, collapsible cards, optional cardio finisher card |
| Rotation | Auto-suggested next day, manual override, DONE advances cycle |
| Setup page | Bodyweight / age / level anytime; live GOAL preview; re-run wizard |
| Wear | Glanceable per-set logging synced to phone |

Deliberately **not** in v1: history/charts, cloud sync, accounts, plate calculator,
rest timers, RPE, social anything. (Section 10.)

---

## 3. Domain layer — core types

Pure Kotlin. No Android imports anywhere in `:domain`.

```kotlin
enum class ExperienceLevel { NOVICE, INTERMEDIATE, ADVANCED }
enum class GoalEmphasis { STRENGTH, BALANCED, PHYSIQUE }
enum class StandardLift { SQUAT, BENCH, DEADLIFT, OHP, INCLINE, ROW }

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

data class LifterConfig(
  val bodyweightLb: Int = 235,
  val age: Int = 40,
  val level: ExperienceLevel = ExperienceLevel.INTERMEDIATE,
  val emphasis: GoalEmphasis = GoalEmphasis.BALANCED,
)

data class CardioPrefs(
  val mode: CardioMode = CardioMode.OUTDOOR_RUN,   // OUTDOOR_RUN, TREADMILL, LOW_IMPACT, NONE
  val placement: CardioPlacement = CardioPlacement.FINISHERS, // FINISHERS, SEPARATE_DAYS, BOTH, NONE
  val fiveKGoal: Boolean = true,      // loose target, shapes suggestion copy
)

data class CardioSuggestion(val label: String, val detail: String, val hard: Boolean)
```

---

## 4. Domain layer — constants & goal math

Calibrated in the prototype. **Do not change without re-running Section 11 numbers.**

```kotlin
object StrengthStandards {
  // Estimated 1RM as multiple of bodyweight, by level. ROW uses ~0.75 of BENCH ratio.
  val ratios: Map<ExperienceLevel, Map<StandardLift, Double>> = mapOf(
    ExperienceLevel.NOVICE to mapOf(
      StandardLift.SQUAT to 1.25, StandardLift.BENCH to 1.0,
      StandardLift.DEADLIFT to 1.5, StandardLift.OHP to 0.6,
      StandardLift.INCLINE to 0.85, StandardLift.ROW to 0.75),
    ExperienceLevel.INTERMEDIATE to mapOf(
      StandardLift.SQUAT to 1.5, StandardLift.BENCH to 1.25,
      StandardLift.DEADLIFT to 2.0, StandardLift.OHP to 0.8,
      StandardLift.INCLINE to 1.05, StandardLift.ROW to 0.95),
    ExperienceLevel.ADVANCED to mapOf(
      StandardLift.SQUAT to 2.0, StandardLift.BENCH to 1.75,
      StandardLift.DEADLIFT to 2.5, StandardLift.OHP to 1.1,
      StandardLift.INCLINE to 1.45, StandardLift.ROW to 1.3),
  )

  // Per-lift correction so calculated working weights are realistic:
  // trap-bar DL trimmed vs straight-bar 2.0x standard; DB incline trimmed vs barbell.
  val liftTune: Map<StandardLift, Double> = mapOf(
    StandardLift.SQUAT to 1.0, StandardLift.BENCH to 1.0,
    StandardLift.DEADLIFT to 0.82, StandardLift.OHP to 1.0,
    StandardLift.INCLINE to 0.9, StandardLift.ROW to 1.0)

  // Maintenance factor = working top set as a fraction of estimated 1RM.
  // Sits deliberately BELOW rep-max tables (5RM is ~87% 1RM; we want comfort).
  fun maintenanceFactor(e: GoalEmphasis) = when (e) {
    GoalEmphasis.STRENGTH -> 0.75   // top set of 3
    GoalEmphasis.BALANCED -> 0.68   // top set of 5  (prototype default)
    GoalEmphasis.PHYSIQUE -> 0.60   // top set of 8
  }
  fun topSetReps(e: GoalEmphasis) = when (e) {
    GoalEmphasis.STRENGTH -> 3; GoalEmphasis.BALANCED -> 5; GoalEmphasis.PHYSIQUE -> 8
  }

  const val BACKOFF = 0.75            // back-off weight as fraction of top set
  const val BACKOFF_REPS = 8
  val RAMP_PCTS = listOf(0.55, 0.70, 0.80, 0.90)  // last ramp done as a lighter triple

  // Age de-rating: ~5% per decade after 40 (research). Continuous form that
  // reproduces the prototype's 0.97 at age 40 exactly:
  fun ageDerate(age: Int): Double =
    (1.0 - 0.005 * maxOf(0, age - 34)).coerceIn(0.80, 1.00)
}

object GoalCalculator {
  fun round5(w: Double) = maxOf(5.0, Math.round(w / 5.0) * 5.0)

  fun goalForMain(std: StandardLift, perHand: Boolean, cfg: LifterConfig): Double {
    val ratio = StrengthStandards.ratios[cfg.level]!![std]!!
    val tune = StrengthStandards.liftTune[std] ?: 1.0
    val oneRm = cfg.bodyweightLb * ratio * tune * StrengthStandards.ageDerate(cfg.age)
    var top = oneRm * StrengthStandards.maintenanceFactor(cfg.emphasis)
    if (perHand) top /= 2.0
    return round5(top)
  }

  /** Accessory GOAL: library flatGoal, or fraction of a named main's goal. */
  fun goalFor(pe: ProgramExercise, day: ProgramDay, cfg: LifterConfig): Double { /* resolve via ExerciseLibrary; see Section 5 */ TODO() }
}
```

### Set seeding (mains get the FULL working sequence as loggable rows)

```kotlin
object SetSeeder {
  fun seed(pe: ProgramExercise, goal: Double, cfg: LifterConfig): List<LoggedSet> {
    val lib = ExerciseLibrary.get(pe.exerciseId)
    if (pe.isMain) {
      val topReps = StrengthStandards.topSetReps(cfg.emphasis)
      val ramps = StrengthStandards.RAMP_PCTS.mapIndexed { i, p ->
        LoggedSet(GoalCalculator.round5(goal * p),
          if (i == StrengthStandards.RAMP_PCTS.lastIndex) 3 else 5, SetKind.RAMP)
      }
      return ramps +
        LoggedSet(goal, topReps, SetKind.TOP) +
        LoggedSet(GoalCalculator.round5(goal * StrengthStandards.BACKOFF),
                  StrengthStandards.BACKOFF_REPS, SetKind.BACKOFF)
    }
    return List(pe.targetSets) { LoggedSet(goal, 10, SetKind.WORK) }
  }
  // Superset partner track: same count as primary, seeded from the partner
  // exercise's library flatGoal, all SetKind.WORK.
}
```

### The cascade rule (prototype-proven, keep exactly)

Editing a set's **weight** on a main lift's **TOP** row re-derives every RAMP row
(from `RAMP_PCTS`, in order) and the BACKOFF row (×0.75), rounding to 5. It does
**not** touch reps, EXTRA/WORK rows, or the superset track. Editing any non-TOP
row changes only that row (manual tweaks stick). Editing reps never cascades.

### Add / remove sets

- "+ add set" appends `{ weight = lastRow.weight, reps = lastRow.reps, kind = EXTRA }`.
- If the exercise has a superset partner, add/remove apply to **both tracks at the
  same index** so rounds stay aligned row-for-row. Never allow removing below 1 set.

---

## 5. Exercise library & substitution engine

The library is a static, in-code catalog. Each entry: id, display name, pattern,
equipment list, `perHand` flag, and GOAL source (either a `std` StandardLift for
mains, a `fracOfStd` pair, or a `flatGoal` starting weight). `subRank` orders
substitution candidates *within a pattern* (1 = first suggestion).

**Substitution rule:** replacements for exercise X = all library entries with the
same `MovementPattern`, excluding X, sorted by `subRank`. (Equipment-profile
filtering is a v1.1 option — ship without it; the ranked list is enough.)
If a pattern has no alternatives (rare), fall back to the adjacent pattern noted
in the table.

| id | Name | Pattern | Equip | GOAL source | subRank | Notes |
|---|---|---|---|---|---|---|
| bb_back_squat | Barbell Back Squat | SQUAT_BILATERAL | BARBELL,RACK | std SQUAT | 1 | main-capable |
| hack_squat | Hack Squat | SQUAT_BILATERAL | MACHINE | flat 180 | 2 | closest leg-press sub |
| leg_press | Leg Press | SQUAT_BILATERAL | MACHINE | frac 1.4 × SQUAT | 3 | spine-unloaded volume |
| goblet_squat | Goblet Squat | SQUAT_BILATERAL | DUMBBELL | flat 70 | 4 | simplest fallback |
| front_squat | Front Squat | SQUAT_BILATERAL | BARBELL,RACK | frac 0.8 × SQUAT | 5 | quad bias |
| smith_squat | Smith Machine Squat | SQUAT_BILATERAL | MACHINE | frac 0.9 × SQUAT | 6 | |
| bss | Bulgarian Split Squat | SINGLE_LEG | DUMBBELL,BENCH | flat 35 (perHand) | 1 | |
| walking_lunge | Walking Lunge | SINGLE_LEG | DUMBBELL | flat 30 (perHand) | 2 | time-cheap BSS sub |
| reverse_lunge | Reverse Lunge | SINGLE_LEG | DUMBBELL | flat 30 (perHand) | 3 | knee-friendlier lunge |
| step_up | Step-Up | SINGLE_LEG | DUMBBELL,BENCH | flat 25 (perHand) | 4 | |
| trap_dl | Trap-Bar Deadlift | HINGE | TRAP_BAR | std DEADLIFT | 1 | main-capable; least lumbar strain |
| conv_dl | Conventional Deadlift | HINGE | BARBELL | std DEADLIFT ×1.0 tune | 2 | main-capable; most posterior chain, most back demand |
| sumo_dl | Sumo Deadlift | HINGE | BARBELL | std DEADLIFT | 3 | main-capable; quad-biased pull |
| rdl | Romanian Deadlift | HINGE | BARBELL | frac 0.72 × SQUAT | 4 | stretch-biased hamstrings |
| stiff_dl | Stiff-Leg Deadlift | HINGE | BARBELL | frac 0.65 × SQUAT | 5 | deeper stretch than RDL |
| good_morning | Good Morning | HINGE | BARBELL,RACK | frac 0.45 × SQUAT | 6 | |
| back_ext | Back Extension | HINGE | BODYWEIGHT | flat 0 | 7 | |
| seated_curl | Seated Leg Curl | KNEE_FLEXION | MACHINE | flat 90 | 1 | stretched-position curl (preferred) |
| lying_curl | Lying Leg Curl | KNEE_FLEXION | MACHINE | flat 90 | 2 | short-head/lower-ham bias |
| ball_curl | Swiss Ball Leg Curl | KNEE_FLEXION | BODYWEIGHT | flat 0 | 3 | no-machine fallback |
| nordic | Nordic Curl (assisted) | KNEE_FLEXION | BODYWEIGHT | flat 0 | 4 | eccentric emphasis |
| leg_ext | Leg Extension | KNEE_EXTENSION | MACHINE | flat 90 | 1 | optional; knee-health ROM work; fallback pattern → SQUAT_BILATERAL |
| bb_bench | Barbell Bench Press | H_PUSH | BARBELL,BENCH,RACK | std BENCH | 1 | main-capable |
| db_bench | DB Bench Press | H_PUSH | DUMBBELL,BENCH | frac 0.4 × BENCH (perHand) | 2 | |
| incline_db | Incline DB Press | H_PUSH | DUMBBELL,BENCH | std INCLINE (perHand) | 3 | main-capable; upper-chest bias |
| incline_bb | Incline Barbell Press | H_PUSH | BARBELL,BENCH,RACK | frac 0.8 × BENCH | 4 | |
| machine_chest | Machine Chest Press | H_PUSH | MACHINE | flat 100 | 5 | |
| pec_deck | Pec Deck / Cable Press | H_PUSH | MACHINE,CABLE | flat 100 | 6 | pump finisher |
| dips | Weighted Dip | H_PUSH | BODYWEIGHT | flat 0 | 7 | |
| ohp | Overhead Press (Barbell) | V_PUSH | BARBELL,RACK | std OHP | 1 | main-capable (5/3/1-style anchors) |
| db_shoulder | Seated DB Shoulder Press | V_PUSH | DUMBBELL,BENCH | flat 55 (perHand) | 2 | |
| machine_shoulder | Machine Shoulder Press | V_PUSH | MACHINE | flat 90 | 3 | |
| landmine_press | Landmine Press | V_PUSH | BARBELL | flat 60 | 4 | shoulder-friendly angle |
| bb_row | Barbell Row | H_PULL | BARBELL | std ROW | 1 | main-capable (Big-4 anchors) |
| cs_row | Chest-Supported Row | H_PULL | MACHINE,BENCH,DUMBBELL | frac 0.8 × BENCH | 2 | lower-back-free |
| cable_row | Seated Cable Row | H_PULL | CABLE | flat 120 | 3 | |
| db_row | One-Arm DB Row | H_PULL | DUMBBELL,BENCH | flat 60 (perHand) | 4 | |
| pullup | Pull-Up / Chin-Up | V_PULL | PULLUP_BAR | flat 0 | 1 | |
| lat_pd_wide | Lat Pulldown (wide) | V_PULL | CABLE | flat 130 | 2 | |
| lat_pd_neutral | Lat Pulldown (neutral) | V_PULL | CABLE | flat 125 | 3 | joint-friendly grip |
| assisted_pullup | Assisted Pull-Up | V_PULL | MACHINE | flat 0 | 4 | |
| cable_lateral | Cable Lateral Raise | SIDE_DELT | CABLE | flat 15 | 1 | constant tension |
| db_lateral | DB Lateral Raise | SIDE_DELT | DUMBBELL | flat 15 (perHand) | 2 | |
| face_pull | Face Pull | REAR_DELT | CABLE | flat 40 | 1 | shoulder health |
| reverse_pec | Reverse Pec Deck | REAR_DELT | MACHINE | flat 70 | 2 | |
| ez_curl | EZ-Bar Curl | BICEPS | EZ_BAR | flat 60 | 1 | |
| incline_curl | Incline DB Curl | BICEPS | DUMBBELL,BENCH | flat 30 (perHand) | 2 | stretch-biased |
| hammer_curl | Hammer Curl | BICEPS | DUMBBELL | flat 30 (perHand) | 3 | |
| cable_curl | Cable Curl | BICEPS | CABLE | flat 50 | 4 | |
| rope_pushdown | Rope Pushdown | TRICEPS | CABLE | flat 50 | 1 | |
| oh_tri_ext | Overhead Triceps Extension | TRICEPS | CABLE,DUMBBELL | flat 50 | 2 | long-head bias |
| skullcrusher | Skull Crusher | TRICEPS | EZ_BAR,BENCH | flat 55 | 3 | |
| standing_calf | Standing Calf Raise | CALF_GASTROC | MACHINE,BODYWEIGHT | flat 90 | 1 | knee straight |
| legpress_calf | Leg-Press Calf Raise | CALF_GASTROC | MACHINE | flat 180 | 2 | |
| seated_calf | Seated Calf Raise | CALF_SOLEUS | MACHINE | flat 90 | 1 | knee bent — different head |
| plank | Plank / Side Plank | CORE_ANTI_EXT | BODYWEIGHT | flat 0 | 1 | |
| ab_wheel | Ab Wheel Rollout | CORE_ANTI_EXT | BODYWEIGHT | flat 0 | 2 | |
| dead_bug | Dead Bug | CORE_ANTI_EXT | BODYWEIGHT | flat 0 | 3 | |
| pallof | Pallof Press | CORE_ANTI_ROT | CABLE | flat 25 | 1 | |
| suitcase_carry | Suitcase Carry | CORE_ANTI_ROT | DUMBBELL | flat 50 (perHand) | 2 | |
| cable_crunch | Cable Crunch | CORE_FLEX | CABLE | flat 90 | 1 | |
| hanging_raise | Hanging Leg Raise | CORE_FLEX | PULLUP_BAR | flat 0 | 2 | |

Weight-stepper increment: 2.5 lb when the working weight is ≤ 20 lb (light
isolation), otherwise 5 lb. Reps stepper is always ±1.

---

## 6. Setup wizard & program generator

### 6.1 Wizard questions (in order, all with defaults so "next-next-next" works)

1. **What are you training for?** → GoalEmphasis
   (Strength-leaning / **Balanced strength + muscle** [default] / Physique-leaning)
2. **How many days a week can you realistically commit?** → 2–6 (default **4**)
3. **Split** — pre-selected sane default from the table below; user may override.
4. **Main-lift anchors** — default per table; alternatives offered:
   - *Prototype default:* Squat / Bench / Trap-Bar DL / Incline DB (maintenance-friendly, joint-kind)
   - *Big-4 (StrongLifts lineage):* Squat / Bench / Deadlift / Barbell Row
   - *5/3/1-style:* Squat / Bench / Deadlift / OHP
   For 2–3-day splits only the first N anchors are used in rotation.
   Deadlift anchor lets the user pick trap-bar (default), conventional, or sumo.
5. **Cardio** → mode (Outdoor run [default] / Treadmill / Bike-elliptical / None),
   placement (Finishers after lifting [default] / Separate days / Both / None),
   optional "keep me 5k-ready" toggle (default on).
6. **About you** → bodyweight (lb), age, experience level.

### 6.2 Split defaults by days/week

Rotation-based in all cases (advance on completion). Rest days are wherever life
puts them.

| Days | Default split (rotation) | Alternative offered | Rationale (see appendix) |
|---|---|---|---|
| 2 | Full-body A/B | — | Only way to hit everything 1–2×/wk |
| 3 | Full-body A/B/C | PPL (flagged: each muscle ~1×/wk — fine for maintenance, sub-optimal for growth) | FB gives ~2×/wk frequency at 3 days |
| 4 | **Full-body rotation, alternating lower/upper emphasis (prototype A/B/C/D)** | Upper/Lower ×2 | FB rotation is missed-day-proof; U/L is the classic 4-day |
| 5 | PPL + Upper/Lower (PPLUL) | FB rotation ×5 | Upper 3×/wk + legs 2×/wk balance |
| 6 | PPL ×2 | Upper/Lower ×3 | Each muscle 2×/wk at high volume |

### 6.3 Day templates (generator skeletons)

Every generated day slots exercises by pattern. The generator fills each slot with
the library's rank-1 entry unless the anchor choice dictates otherwise.

**Full-body day skeleton (used for 2/3/4-day FB splits):**
1 anchor main (rotates per day) + 1 opposing-region compound + 1 hinge-or-squat
complement + 1 pull + 1 isolation (delts/arms/calves, rotating) + 1 core
(pattern rotates ANTI_EXT → ANTI_ROT → FLEX across days).
Muscle-angle emphasis rotates across the cycle exactly as prototype Days A–D:
flat vs incline chest, horizontal vs vertical pull, hip-hinge vs knee-flexion
hamstrings, gastroc vs soleus calves, squat vs single-leg quads.

**Upper day skeleton:** H_PUSH main-or-heavy, H_PULL, V_PUSH, V_PULL,
SIDE_DELT or REAR_DELT, arms superset (BICEPS + TRICEPS), core.
**Lower day skeleton:** SQUAT_BILATERAL or HINGE main, the other pattern as
accessory, SINGLE_LEG, KNEE_FLEXION, one CALF (alternate heads), core.
**Push:** H_PUSH main, incline variant, V_PUSH, SIDE_DELT, TRICEPS, core.
**Pull:** H_PULL main (row anchor), V_PULL, REAR_DELT, BICEPS, core.
**Legs:** SQUAT or HINGE main (alternate across the two weekly legs days),
SINGLE_LEG, KNEE_FLEXION, CALF (alternate heads), core.

**Arms supersets** appear on upper/push-pull style days: primary BICEPS move
paired with a TRICEPS partner (see Section 8 for display/logging rules).

### 6.4 Cardio planner rules

- Never schedule cardio before lifting; finisher cards always render *below* the
  exercise list. Copy must remind: "lift first, then run."
- **Hard cardio (intervals / tempo)** only on days whose lifting is leg-light
  (upper, push, pull days; or the FB day whose main is a press). Suggestion when
  5k toggle on: "5 min easy, then 4–6 × 2 min hard / 2 min easy" or "20 min tempo".
- **Easy Zone 2 (20–30 min, conversational)** after leg-heavy days (squat/hinge
  mains), or as the default for LOW_IMPACT mode.
- Placement = SEPARATE_DAYS or BOTH → generator also emits standalone
  "Cardio + Core" day cards (Zone 2 + one core circuit) that live outside the
  strength rotation and never consume a rotation advance.
- Mode = NONE → no cardio cards anywhere.
- Core can appear **every day** (it recovers fast); rotate the pattern
  (anti-extension → anti-rotation → flexion) so no single pattern is hammered daily.

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

**Persistence lessons from the prototype (hard-won):**
- Write on every mutation, commit immediately (Room transactions), never hold
  state only in memory. The prototype's original data-loss bug was writes that
  hadn't committed before a screen rotation / process death.
- Also flush in `onStop()` of the activity/lifecycle as belt-and-suspenders.
- (Artifact-only lesson, doesn't apply to Room: the web prototype had to debounce
  per-key writes to a rate-limited KV store. Room has no such limit.)

`TrackerRepository` surface: `configFlow`, `setConfig`, `programFlow`,
`replaceProgram(Program)` (wizard output), `swapExercise(dayId, position, newId)`,
`addExercise`, `removeExercise`, `resetDayToTemplate(dayId)`,
`logFlow(dayId)`, `updateSets(dayId, peId, slot, sets)`, `suggestedDayFlow`,
`advanceDay()`, `overrideViewDay()` is UI-local (not persisted).

---

## 8. Phone UI (Jetpack Compose, MVVM, Hilt, single activity)

### 8.1 Screens & navigation
`WizardScreen` (first run / re-run) → `DayScreen` (home) ⇄ `SetupScreen` (⚙ tab)
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

## 11. Build order & pinned verification numbers

1. `:domain` — Sections 3–6. Unit tests MUST reproduce, for
   `LifterConfig(bodyweightLb=235, age=40, level=INTERMEDIATE, emphasis=BALANCED)`:
   - ageDerate(40) == 0.97
   - Squat GOAL 235 · Bench 195 · Trap-bar DL 255 · Incline DB 75/hand
   - Squat seeded sequence: R 130×5, R 165×5, R 190×5, R 210×3, TOP 235×5, B/O 175×8
   - Bench seeded sequence: R 105×5, R 135×5, R 155×5, R 175×3, TOP 195×5, B/O 145×8
   - Cascade: setting squat TOP to 245 yields ramps 135/170/195/220 and B/O 185
   - Accessory derivations: RDL 170 (0.72×squat), Leg Press 330 (1.4×squat),
     Chest-Supported Row 155 (0.8×bench)
   - Rotation: next(A)=B … next(D)=A; generator emits the day counts/templates
     of Section 6.2 for each days/week input.
2. `:data` — Room + DataStore + repository; serialization round-trip tests;
   process-death persistence test (kill app mid-edit, relaunch, state intact).
3. Phone UI — Wizard, Day screen (verify every behavior in 8.2 manually:
   cascade, superset alignment, collapse auto+manual, per-date check reset,
   override note, DONE advance), Day edit sheet, Setup.
4. `:wear` — last; verify a watch set-edit survives phone app restart.

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
