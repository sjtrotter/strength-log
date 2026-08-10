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
  ending early or running long is normal — ACTUAL is what's logged).
- Hard, no 5k → one step: `TEMPO 20:00` (the prose says 20 min tempo; no invented warm-up).
- Hard, 5k → `WARM-UP 5:00`, then 5 × (`HARD 2:00`, `EASY 2:00`) — the prose
  says 4–6; the plan pins 5; stopping early is normal. C1 has no manual
  repeats — ACTUAL is total elapsed plus the fully-completed step prefix,
  which `stepsCompleted` records exactly.

**Pinned vectors (§11 addendum, new `CardioIntervalsTest`):** easy = [1500];
hard = [1200]; hard+5k = [300, 120, 120, 120, 120, 120, 120, 120, 120,
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

`dayId` is always the day the block was started from — C1 has no ad-hoc
cardio outside a generated day, so it is never null in practice; the column
stays nullable only so a future ad-hoc entry needs no migration.

Deliberate absences: no distance, no pace, no HR — this app logs what the
lifter did against the plan it wrote, not what a sports watch measures. If that
ever changes it's a new brief. `label`/`mode` are copied at log time (the plan
can change later; history is immutable — same rule as `exerciseName` on set
rows).

- **Backup schema v6** (current is v5): `cardioSessions:
  List<CardioSessionBackup> = []`, defaulted — v1–v5 documents decode with an
  empty list through the existing shared-decoder routing; newer-than-current
  stays loudly rejected.
- **CSV**: one file (per #175's decision), with an explicit discriminator:
  the writer emits `Set Type = cardio` in the existing optional column and the
  reader routes such rows to cardio BEFORE strength name-matching ever sees
  them (no custom-exercise prompts, no set rows). Labels pass the #175
  neutralization boundary like any free text. Dedupe identity, extending
  #216's normalization: `(completedAt at CSV second precision, normalized
  label, cardio marker)` — collisions skip the insert, same as strength.
  Round-trip pins include formula-leading and apostrophe labels.

## 4. Phone execution

The CardioCard (day screen, expanded) gains a START. Executing state is owned
by `DayViewModel` (survives rotation via the same discipline as everything
else: the running block's `startedAtElapsedMillis`, `stepIndex`, and
`startedAtWallMillis` in `SavedStateHandle`; process death mid-cardio resumes
from the restored stamps — elapsed derives from `elapsedRealtime`, same
contract as the wear rest deadline):

- Card shows the current step (`HARD · 1:23 left`), overall elapsed, and one
  quiet STOP. Step boundaries buzz via an exact `ELAPSED_REALTIME_WAKEUP`
  alarm for the NEXT boundary only — the #188 pattern with its identity-
  guarded single-alarm discipline (#207), phone twin. The lifecycle contract
  is stated, not implied: buzzes are guaranteed only while the app is
  foreground with the day screen live; backgrounding forfeits boundary buzzes
  (no foreground service in C1 — a non-goal on the record) and the elapsed
  math is simply correct again on return. Keep-screen-on applies while a
  block runs.
- Durable state is minimal and derived: `SavedStateHandle` holds only the
  plan identity and the wall + elapsedRealtime start anchors. Step index and
  elapsed are DERIVED from the anchors on every restore, never stored. Reboot
  is detected by anchor divergence (elapsed anchor younger than wall delta) —
  after a reboot the wall anchor alone drives elapsed. Restores re-arm only
  the next FUTURE boundary; missed buzzes are never replayed. A restore that
  lands past the plan's end enters OVERRUN (below) with elapsed still
  honest.
- The plan running out transitions to OVERRUN: the card keeps counting
  elapsed (Zone 2 runs long all the time) and only STOP logs. One insert,
  immediately, single transaction — write-on-every-mutation. Under 60 seconds
  elapsed, STOP discards instead of logging (a fat-finger START must not mint
  history; threshold pinned).
- The intent line on Today and the receipt do not change. The card collapses
  back to its suggestion form once logged, with a quiet `LOGGED · 24:12` line
  for the rest of the day (reads from history, no new state).

## 5. Journal + Health Connect

- **Log screen list**: cardio sessions interleave by `completedAt` — same
  card shape, `label` where the day title goes, `MODE · M:SS` where the set
  count goes, no expansion and no SHARE (both are strength constructs). The
  accent is the logged day's accent (`dayId` is always set in C1); the card's
  semantics announce "cardio session" with label and duration. The calendar's
  day-dot ("a session happened") now includes cardio. Trajectories and volume
  stay lifts-only by definition.
- **Validity**: `mode` decodes with an unknown-name fallback (label still
  renders; HC mapping skips), labels are non-blank and length-capped at the
  entity boundary, and formula-shaped labels are neutralized only at the CSV
  boundary (#175) — nowhere else.
- **HC**: `CardioRecordMapper` writes an `ExerciseSessionRecord` with client
  id `strengthlog-cardio-<id>`, version 0 — #194's dedupe contract verbatim.
  Mode mapping pinned: `OUTDOOR_RUN` and `TREADMILL` → RUNNING,
  `LOW_IMPACT` → BIKING (its prose verb is "ride"). The Room insert is the
  committed mutation; the HC publish follows, retryable/backfillable by the
  stable client id (#159's one-shot counts cardio too). Mapper guards:
  `completedAt > startedAt`, duration within [60s, 24h], else no record —
  never invented data.

## 6. Wrist role — phase C2, designed now, built second

The watch stays a *logger with a timer*, not a sports tracker:

- Cardio is a STATE within the existing workout face (a `DialScreen` entry),
  never a third face — the two-face contract of wear-dial-v3 holds. Entered
  from the overview once the day has a finisher (or after the last lift):
  center shows the current step's countdown, the clock ring drains per step —
  the rest ring's exact grammar — tap advances nothing (steps advance on
  time), and the 700ms authored hold = stop-and-log (the hold slot is free
  here: undo is never offered in the cardio state, so the grammar doesn't
  collide). Buzz at boundaries via `RestTimerController` (#188/#207)
  unchanged.
- The logged session travels as `CardioDelta(schemaVersion = 1)` — its own
  codec and message path, NOT a snapshot version bump (#199's wire is
  additive-with-defaults; nothing rejects). It rides the existing durable
  queue with the same stamp machinery ticks use: the stamp IS the stable
  event id, persisted until ack, deduplicated phone-side by that id, and the
  queue settles when an installed snapshot exposes the logged session — the
  exact reconcile contract edits already obey. Replay after reconnect is
  therefore idempotent by construction.
- Execution is device-local: starting a block on one device mirrors nothing
  live to the other; only completions travel, and completion identity dedupes
  them. No shared active-session protocol — a deliberate non-goal.
- `WatchSnapshot` gains `cardio: CardioSuggestion? = null` — additive,
  defaulted, no version change — so the dial can offer the block.
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

- Standalone days (`SEPARATE_DAYS`): the spec's "Cardio + Core" days keep
  their core lifts, so DONE and `DayProgress` stay lifts-only there too — no
  contradiction with §1. Logging the cardio block is a separate calendar fact
  (`cardioLogged`), never DONE. No day type exists whose completion is cardio.
- Editing/deleting logged cardio: none in C1 (matches lifts — history is
  append-only today). CSV re-import dedupe (#196) extends to cardio rows by
  the same identity triple.
