# Cardio promotion — design brief (issue #154)

Owner (2026-08-07): "cardio is a second-class citizen… maybe we should promote
it — make the sets/workouts executable as the weight training is, along with
the logging." Owner (2026-08-10): full build before Play; this brief first.

Today cardio is a suggestion: `CardioPlanner` (spec §6.4/§12) writes a card and
an intent line, and nothing ever happens to it. Promotion means a cardio block
can be **started, executed against its plan, logged into history, seen in the
journal, and written to Health Connect** — with the same GOAL-vs-ACTUAL honesty
the lifts have. Lift-first stays law: nothing here reorders a day.

## 1. What stays true

- **CardioPlanner stays the SSOT for the plan.** Its two suggestions (easy
  Zone 2, hard intervals) become *executable* plans, not different plans.
- **Cardio never gates the day.** DONE, rounds, the receipt, the day-progress
  counter, and the cascade all remain lifts-only. A finisher is a finisher:
  skipping it is normal, logging it is extra credit. (`DayProgress` and every
  §11 number are untouched.)
- **No new brand of chrome.** Execution lives inside the existing CardioCard on
  the day screen, in the app's authored language — no new screen, no wizardry.

## 2. Domain: the plan becomes steps

New pure types in `:domain` (`generator/CardioIntervals.kt`):

```kotlin
data class CardioStep(val label: String, val seconds: Int, val hard: Boolean)
data class CardioPlan(val steps: List<CardioStep>) {
    val totalSeconds: Int get() = steps.sumOf { it.seconds }
}
```

`CardioIntervals.plan(suggestion: CardioSuggestion, fiveK: Boolean): CardioPlan`
derives steps from the same rules that wrote the prose:

- Easy Zone 2 → one step: `EASY · 25:00` (the 20–30 min band pins its middle;
  the lifter ends early or late freely — ACTUAL is what's logged).
- Hard, no 5k → `WARM-UP 5:00`, `TEMPO 20:00`.
- Hard, 5k → `WARM-UP 5:00`, then 5 × (`HARD 2:00`, `EASY 2:00`) — the prose
  says 4–6; the plan pins 5 and the lifter stops early or repeats freely.

**Pinned vectors (§11 addendum, new `CardioIntervalsTest`):** easy = [1500];
hard = [300, 1200]; hard+5k = [300, 120, 120, 120, 120, 120, 120, 120, 120,
120, 120] (total 1500). These are the cardio analog of the squat GOAL numbers:
a diff that changes them is wrong unless the spec changes first.

## 3. Data: a logged cardio session

New Room entity (DB v6, additive migration):

```kotlin
@Entity(tableName = "cardio_session")
data class CardioSessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val dayId: String?,          // null for a standalone cardio day entry
    val mode: String,            // CardioMode.name at log time
    val hard: Boolean,
    val label: String,           // the plan's label at log time ("Easy Zone 2")
    val startedAt: Long,
    val completedAt: Long,
    val seconds: Int,            // ACTUAL elapsed, not the plan's total
    val stepsCompleted: Int,     // how many plan steps fully elapsed
)
```

Deliberate absences: no distance, no pace, no HR — this app logs what the
lifter did against the plan it wrote, not what a sports watch measures. If that
ever changes it's a new brief. `label`/`mode` are copied at log time (the plan
can change later; history is immutable — same rule as `exerciseName` on set
rows).

- **Backup schema v7**: `cardioSessions: List<CardioSessionBackup> = []`
  (defaulted — old backups restore; new backups into old apps are already
  version-rejected).
- **CSV**: cardio goes in the existing history export as rows with
  `Exercise Name = label`, weight/reps empty, a `Seconds` value — the format
  Strong/Hevy readers ignore gracefully; our reader maps them back by the
  label+empty-weight shape. (One file, per #175's one-format decision.)

## 4. Phone execution

The CardioCard (day screen, expanded) gains a START. Executing state is owned
by `DayViewModel` (survives rotation via the same discipline as everything
else: the running block's `startedAtElapsedMillis`, `stepIndex`, and
`startedAtWallMillis` in `SavedStateHandle`; process death mid-cardio resumes
from the restored stamps — elapsed derives from `elapsedRealtime`, same
contract as the wear rest deadline):

- Card shows the current step (`HARD · 1:23 left`), overall elapsed, and one
  quiet STOP. Step boundaries buzz (Vibrator, one shot) via an exact
  `ELAPSED_REALTIME_WAKEUP` alarm scheduled for the NEXT boundary only —
  the `RestTimerController` pattern (#188), phone-side twin, same
  foreground-contract KDoc. The keep-screen-on preference applies while a
  block runs (it already exists on the day screen).
- STOP (or the plan running out) logs the session: one insert, immediately, in
  a single transaction — write-on-every-mutation, as ever. Under 60 seconds of
  elapsed time, STOP discards instead of logging (a fat-finger START must not
  mint history; threshold pinned in a test).
- The intent line on Today and the receipt do not change. The card collapses
  back to its suggestion form once logged, with a quiet `LOGGED · 24:12` line
  for the rest of the day (reads from history, no new state).

## 5. Journal + Health Connect

- **Log screen list**: cardio sessions interleave by `completedAt` with
  strength sessions — same card shape, `label` where the day title goes,
  `MODE · M:SS` where the set count goes, no expansion (there are no rows). The
  calendar's day-dot definition ("a session happened") now includes cardio.
  Trajectories and volume are lifts-only by definition and do not change.
- **HC**: `CardioRecordMapper` writes an `ExerciseSessionRecord`
  (`EXERCISE_TYPE_RUNNING` / `_BIKING` by mode; treadmill maps to running)
  with client id `strengthlog-cardio-<id>`, version 0 — the #194 dedupe
  contract verbatim. Calories: same bodyweight-honest rule as lifts — no
  bodyweight, no calories record. Backfill (#159's one-shot) counts cardio
  sessions too once this ships.

## 6. Wrist role — phase C2, designed now, built second

The watch stays a *logger with a timer*, not a sports tracker:

- After the last lift (or from the overview when the day has a finisher), the
  dial offers the cardio block as one more face: center shows the current
  step's countdown (the clock-ring drains per step — the rest ring's exact
  visual grammar), tap advances nothing (steps advance on time), long-press =
  stop-and-log. Buzz at boundaries via `RestTimerController` — the machinery
  from #188/#207 unchanged.
- The logged session travels as a new `CardioDelta` over the wire (schema
  bump; epoch/revision rules from #199 apply; `applyDelta` gains one overload,
  the #208 overlay covers it for free).
- The `WatchSnapshot` gains the day's `cardio: CardioSuggestion?` it already
  computes phone-side (WatchSnapshotBuilder), so the dial can offer it.
- Health Services stays out (deviation 5, #167) — the timer is elapsed-time
  arithmetic, not sensors. Revisit trigger unchanged.

## 7. Build order (each a PR through the standard loop)

1. **C0 — domain**: `CardioStep/CardioPlan/CardioIntervals` + pinned vectors.
2. **C1a — data**: entity, DB v6 migration, DAO, repository writes/reads,
   backup v7, CSV round-trip.
3. **C1b — phone execution**: DayViewModel state machine + CardioCard
   execution UI + boundary alarms + logging + LOGGED line.
4. **C1c — journal + HC**: list interleave, calendar dots, mapper, backfill
   inclusion.
5. **C2a — wire**: snapshot cardio field, `CardioDelta`, overlay coverage.
6. **C2b — dial**: the cardio face + stop-and-log + buzz.

C0–C1c ship Play-ready cardio; C2 completes the owner's "executable as the
weight training is" on the wrist. §11 pinned numbers are never touched; the
new cardio vectors join them.

## 8. Open questions (answered here, overridable by the owner)

- Standalone cardio days (`SEPARATE_DAYS` placement): C1 treats them as a day
  whose only block is cardio — the day screen shows the one card; DONE is the
  cardio log itself for that day type. (Smallest honest reading; revisit if it
  feels wrong on wrist.)
- Editing/deleting logged cardio: none in C1 (matches lifts — history is
  append-only today). CSV re-import dedupe (#196) extends to cardio rows by
  the same identity triple.
