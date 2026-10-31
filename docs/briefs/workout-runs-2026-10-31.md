# Workout runs (#341): design, 2026-10-31 (revision 2)

Step 2 of milestone M9 (`product-council-2026-10-31.md`).

- **Designer:** Claude Opus, standing in for Fable, which was over its usage limit.
- **Status:** revised after review rounds 1 and 2; the ledgers are at the end.
- **Shipping:** PRs 2 to 4 ship in one release.
- **Line numbers:** from main at `7bf302b3`.

## 1. The current model

**Live sets.**

- `exercise_log` (`ProgramEntities.kt:77-91`) is keyed by
  `(planId, dayId, programExerciseId, slot)` and holds `setsJson`, `checkDate` and `updatedAt`.
- One row is both next time's values and today's ticks.
- The plan is `program_day` (`:29-46`), `program_exercise` (`:48-66`) and `plan`
  (`PlanEntity.kt:6-19`, with `suggestedDayId`).
- The session start is a DataStore stamp (`SettingsStore.kt:362-386`).

**Finish** is `advanceDay` (`TrackerRepository.kt:1256-1322`). It:

- uses the selected plan (`:1258`);
- copies the raw `setsJson` into `session_set` (`:1286-1309`);
- blanks `checkDate` (`:1311`);
- deletes prescribed logs (`:1312-1314`);
- advances the rotation (`:1316`).

Finish isn't idempotent.

**Confirmed bug.** `clearChecksForDay` (`ProgramDao.kt:156`) blanks only the date, and `:1291`
archives the raw flags. So an untouched track is archived with an earlier run's ticks. The same
happens to a track left alone after Clear.

**CheckmarkReset** (`CheckmarkReset.kt:33-42`) hides stale flags on read, at
`TrackerRepository.kt:1625-1631`.

**The wire.**

- The snapshot carries only the selected plan's suggested day (`TodaySnapshotSource.kt:41-51`).
- Deltas address `(dayId, programExerciseId, slot, setIndex, planId)` (`SetEditDelta.kt:20-51`).
- `SyncCodec` (`:24`) omits fields that hold their default value, and nothing reads
  `schemaVersion`.
- The applier reads and writes in separate calls, and dedupes through DataStore markers
  (`SetEditApplier.kt:80-125`). Its comment at `:45-46` admits #296.

**Storage versions.** The backup is v12 (`BackupDocument.kt:18`), and `LiveLogBackup` carries
`checkDate` (`:209-218`). Room is v12 (`StrengthDatabase.kt:38`).

**Docs that contradict the code.**

- `PLAN.md:224` (A13) claims deltas carry the run.
- `:124` (A6) and `:34` (A1) describe the date reset and the live log that this design retires.

## 2. The target model

`exercise_log` stays as the plan's working values, the remembered sets for next time, minus
`checkDate`. A run owns today's rows.

### New tables (Room v13)

**`workout_run`** holds open runs only, at most one at a time.

| Column | Notes |
| --- | --- |
| `id TEXT PK` | a UUID |
| `planId INTEGER NULL` | nullable for Start empty |
| `dayId TEXT NULL` | nullable for Start empty |
| `dayTitle`, `planName` | |
| `startedAt` | |

**`run_track`**

| Column | Notes |
| --- | --- |
| `id INTEGER PK` | |
| `runId`, `position` | |
| `programExerciseId NULL`, `slot`, `exerciseId` | |
| `retired INTEGER NOT NULL DEFAULT 0` | |
| `plannedJson` | the rows captured at Start; never changes |
| `setsJson` | the actuals |
| `updatedAt` | |

It has an index on `(runId)` and a unique index on `(runId, programExerciseId, slot, exerciseId)`.

### Changed tables

- **`program_day`** gains two columns.
  - `nextRunId TEXT NOT NULL`: the day's next run identity. It is minted when the day is
    created and again at every close. Each day has its own, so two days never claim one ID.
  - `closedAt INTEGER NULL`: when the day's last Finish or Discard happened.
- **`workout_session`** gains `runId TEXT NULL` with a unique index, and `plannedJson TEXT NULL`
  holding the session's tracks as `[{position, exerciseId, slot, retired, sets}]`.
- **`session_set`** gains `trackPosition INTEGER NULL` and `plannedIndex INTEGER NULL`.
- **`exercise_log`** is rebuilt without `checkDate`.

### New `LoggedSet` and `SetDto` fields

Both are nullable, additive JSON.

- `plannedIndex`: the planned row this actual answers. Start sets `plannedIndex = i` on every
  row, `addSet` leaves it null, and `CarryForward` clears both new fields.
- `watchEditedAt`: the newest watch stamp applied to the row. It replaces the DataStore
  set-edit markers, and it commits with the edit.

### Layers

| Layer | Where it lives |
| --- | --- |
| Prescription | `program_exercise`, plus the goal math |
| Working values | `exercise_log` |
| Captured prescription and actuals | `run_track`, archived to `workout_session.plannedJson` and `session_set` |

With no run open, every edit changes the working values. With a run open, edits change the run.

### The gate

`applyToRun(runId, planId, dayId, trackKey, transform, isDoneTick)` runs as one Room transaction:

1. If `runId` is the open run and the plan and day match, apply.
2. If `runId` equals `program_day(planId, dayId).nextRunId`:
   - an edit that isn't a done tick changes that day's working values, whether or not a run is
     open on another day;
   - a done tick starts the run and then applies, but only when no run is open;
   - otherwise, reject.
3. Otherwise, reject.

- The read, the check and the write all happen inside that transaction.
- The phone passes the `runId` its screen showed, so a delayed phone tick can't consume the
  next run's ID.
- Tracks are matched on the unique key. A delta with no `exerciseId` uses the slot's
  non-retired track.

### Finish

`finishRun(runId)` runs as one transaction:

1. If a session with this `runId` exists, return its ID.
2. If the run isn't open, return null.
3. Otherwise:
   - Insert the session, with its `runId` and `plannedJson`.
   - Insert the rows: every row of a non-retired track, and only the done rows of a retired
     track. Each row carries `trackPosition` and `plannedIndex`.
   - Carry forward, advance the rotation, set `closedAt`, and mint a new `nextRunId` on the
     day whose `nextRunId == run.id`.
   - Delete the run.

**No detached runs.** An edit that removes the open run's day closes the run in the same
transaction: `removeDay`, `replaceProgram` or the wizard's save (`:443`). The run is archived
as a session if it has done rows, and dropped otherwise, with no carry forward and no rotation
advance. So no run can outlive its day. A re-added letter (`addDay` `:491` takes the lowest
free letter) always gets a fresh `nextRunId`, and nothing can attach to it by accident.

Bodyweight is read before the transaction. The DataStore stamp goes away.

### Carry forward

`CarryForward.next(planned, actual)` lives in `:domain`.

- **Generated slots:** clear done flags and timestamps. If the TOP weight differs from the
  planned TOP, re-derive RAMP and BACKOFF from the final TOP. That is the pinned cascade, so
  the result is the same as today.
- **Authored slots:** delete the working values. That is today's policy; step 3 adds the
  per-slot remember-or-keep flag.
- Only a slot whose current exercise matches the track's exercise carries forward.

### The cascade

`SetEditor.editWeight` (`:20-36`) skips done rows, but still advances `rampSeen` past them. The
pinned §11 values involve no done rows.

### Mid-run plan edits

Each edit changes the plan, and the run as follows:

- **Swap:** the old track gets `retired=1`, and a new track is seeded from the new exercise.
  Swapping back reopens the earlier track through the unique key.
- **Prescription edit:** undone rows are re-seeded and keep their `plannedIndex`; done rows
  stay. A change of tracking type applies to future runs only.
- **Add an exercise:** a track is appended.
- **Remove an exercise:** the track is retired if it has done rows, and deleted otherwise.
- **Partner change:** the same rules, applied to the superset track.

`resetDayToTemplate` keeps the day's `nextRunId` and `closedAt`, and applies the remove and add
rules above to the run, slot by slot. `duplicatePlan` (`:585`) mints new IDs.

## 3. Lifecycle

- **Start:** only a done tick starts a run, on either device, through the gate.
  - The caller passes the seeds (`seedPlan` is in `:app`; the applier already calls it).
  - A slot that has no working values is seeded into the run.
  - A missing track is seeded the first time the day is shown. That covers runs created by
    the migration.
- **Resume:** `openRunFlow` decides what the day screen, the widget and the snapshot show. It
  takes precedence over `suggestedDayId` and over the selected plan.
- **Process death:** all state is in Room.
- **Midnight:** nothing is dated.
- **Finish twice:** the second call returns the first session.
- **Discard** replaces "clear today's checks." It drops the run's actuals, sets `closedAt` and
  mints a new ID. Plan edits made during the run, such as swaps and prescription changes,
  stay.
- **Watch Finish offline** is step 6. The one field that can't be added later is in this
  design: a run ID in every snapshot, including previews, so the watch can start a run
  offline. Everything else step 6 needs is additive:
  - the finish message and its path;
  - an ack list of closed run IDs;
  - merge rules using the per-set `watchEditedAt`;
  - a capability field before the watch offers Finish.
- **Start empty:** the run has a null plan and day, and nothing carries forward.
  `workout_session.dayId` is NOT NULL, so step 4 decides how to handle it.
- **Plan switch with a run open:** the run stays open, and Resume shows it. A tick for another
  day is rejected, because only one run can be open.
- **Deleting the day of an open run:** the run closes in the same transaction (see "No detached
  runs"). A plan can't be deleted, only archived (`:602-609`).
- **Cardio** stays outside runs. Its guarded rotation advance (`:1085`) can move the pointer
  while a run is open, and the run doesn't depend on the pointer.

## 4. The wire

**Snapshot** gains:

- `runId: String? = null`, the open run's ID, or the day's `nextRunId` when previewing;
- `runStartedAt: Long? = null`.

**Deltas:**

- `SetEditDelta` gains `runId` and `exerciseId`.
- `ExerciseSwapDelta` gains `runId`.

**Versioning:**

- `schemaVersion` stays at 1 and is never read. A field's presence is the capability
  (`runId != null`); a missing field decodes to its default, so it is unambiguous.
- Tests decode real old payloads.

**A new watch** goes through the gate.

**An old watch** sends a null `runId`.

- Its delta applies only to an open run on that plan and day, and only when
  `editedAtMillis > closedAt` for the day.
- **An old watch never starts a run.** The lifter starts on the phone, with one tick, and the
  old watch logs into that run from then on.
- So it can't revive a finished or discarded run, whatever its clock says.
- A slot ID from a deleted day fails validation.

**The watch queue** drops a queued delta when the snapshot shows the same plan and day under a
different `runId`. The day's ID changes only when its run closes, so an edit is settled only
by what happened to its own run. Dial state is keyed by `runId`.

**#296:**

- Closed for updated watches.
- For old watches, a late tick can only land in a run that the phone has already started
  since the tick's own run closed. It is bounded by `closedAt`, apart from a few seconds of
  clock skew around Finish.

## 5. Migration

**Room 12 to 13** runs with "today" injectable for tests.

1. Normalize every row with its own `checkDate` (`applyResetIfStale`).
2. Mint `nextRunId` for every day.
3. Find the candidates: `(plan, day)` pairs that have done rows dated today.
   - The pair with the latest `completedAtMillis` (falling back to `updatedAt`) becomes the
     open run.
   - **Its run ID is that day's `nextRunId`,** so it is attached. A test asserts this.
   - Its `plannedJson` is the same rows with done flags and timestamps cleared, and with
     `plannedIndex = i`.
4. Every other candidate is archived as a session, with no rotation advance and no carry
   forward. Its day gets `closedAt` set to the migration time and a fresh `nextRunId`. That
   keeps the work; the lifter can delete it from history.
5. Clear every flag and timestamp left in `exercise_log`, and rebuild the table.
6. A one-time startup step swaps in the DataStore stamp as the run's start time if it matches,
   then deletes the stamp keys.

**Backup v13** carries:

- `runs`, with their tracks;
- `ProgramDayBackup.nextRunId` and `closedAt`, so a restored open run stays attached;
- `SessionBackup.runId` and `plannedJson`;
- the new session-set fields.

**v12 and earlier** decode `checkDate` and go through the same split, including the attached
run ID. Restoring on the same day
gives an open run; on a later day, cleared values.

## 6. PR breakdown

### PR 1. Archive what the screen shows

- **Change:** `:1291` archives rows with `checkDate == ''` with their done flags and timestamps
  cleared. Those are exactly the rows that a Finish or Clear left behind. Date-stale rows keep
  today's behavior until PR 2, so a session that crosses midnight still archives the sets
  ticked before it.
- **Fixes:** stale ticks archived as done.
- **Tests:**
  - an untouched slot after a previous Finish;
  - Clear, then tick, then Finish;
  - a set ticked at 23:40 and Finish at 00:20 still archives it as done.
- Ships alone.

### PR 2. Workout runs in Room

- **Changes:**
  - the v13 tables and the migration;
  - the gate;
  - `finishRun` and `discardRun`;
  - the cascade skipping done rows, and `CarryForward`;
  - dedupe inside the row;
  - backup v13;
  - the day screen, widget and snapshot read the run;
  - `CheckmarkReset` and the DataStore stamp deleted;
  - PLAN A1 and A6 amended.
- Old-shape deltas go through the bounded null-run path.
- Plan edits leave the run untouched. Nothing is lost.
- **Fixes:**
  - the midnight reset;
  - Finish replay;
  - process death;
  - the cascade;
  - swaps and prescription edits deleting performed sets;
  - the authored-reseed mechanism.
- **Tests:**
  - `Migration12To13Test`: normalization, the split, surplus days, IDs;
  - `TrackerRepositoryRunTest`: Finish twice, past midnight, Discard, `removeDay` or a wizard
    save with a run open, letter reuse, duplicate mints, preview edits to another day with a
    run open;
  - `CarryForwardTest`;
  - `SetEditingTest`, with `PinnedVerificationTest` unchanged;
  - `BackupV13Test`;
  - `BackupV12Test`, restored the same day and a later day;
  - `PersistenceProcessDeathTest`.

### PR 3. Mid-run edits reach the run

- **Changes:** swap, prescription edit, add, remove and partner change; the day screen shows
  retired tracks.
- **Tests:**
  - `TrackerRepositoryRunEditsTest`;
  - a watch swap mid-run, in `SetEditApplierTest`.

### PR 4. Run identity on the wire

- **Changes:** the snapshot and delta fields, `PendingEdits`, dial state, and PLAN A13
  corrected.
- **Closes:** #296.
- **Tests:**
  - `SetEditApplierRunTest`:
    - a late tick after Finish;
    - a one-day plan run twice;
    - a tick on `nextRunId` starts the run;
    - a weight edit while previewing changes the working values;
    - an old-watch tick bounded by `closedAt`;
    - an old-watch done tick with no run open is rejected.
  - `PendingEditsTest`;
  - `SyncCodecTest`, with old payloads.

## 7. Risks and owner decisions

**Risks.**

- **PR #295** shipped a run token and then removed it, because it was mutable and
  clock-dependent. This design answers each point:
  - the IDs never change and are minted in a transaction;
  - the gate's check and write happen in one transaction;
  - a delta is settled only by its own run.
- **PR 2 is large.**
- **The migration rewrites JSON in Kotlin.**

**Owner decisions.**

1. **A prescription edit during a workout** re-plans today's remaining sets (recommended).
   Changing the tracking type always applies to future workouts only.
2. **A run left open** never closes on its own (recommended).

## Review round 1 ledger

Two seats reviewed: the Claude seat scored 6/10 and Sol scored 5/10, and both asked for changes.

### Claude seat

| ID | Outcome | Fix or reason |
| --- | --- | --- |
| C1 | Agree | `nextRunId` lives on `program_day`, and the gate matches plan and day |
| C2 | Agree | Old-watch ticks are bounded by `closedAt` |
| C3 | Agree | One Start rule: a done tick only |
| C4 | Agree | The done-skip and `CarryForward` move to PR 2, which has the baseline |
| C5 | Agree | PR 1 archives the reset view, and the migration normalizes first |
| C6 | Agree | Missing tracks are seeded on first view, and copied rows are cleared |
| C7 | Agree | A `retired` column. Attachment through `nextRunId` replaces refusing those edits |
| C8 | Agree | `schemaVersion` is never bumped; capability is a field's presence |
| C9 | Partly agree | Per-set `watchEditedAt` replaces the markers. Merge rules wait for step 6 |
| C10 | Agree | `applyToRun` takes a transform |
| C11 | Agree | `run_track.prescriptionJson` is cut |
| C12 | Declined | Another plan's queued deltas predate #341 (`PendingEdits.kt:66`) |
| C13 | Agree | One release, and PR 2 refuses nothing |
| C14 | Agree | Owner decisions 1, 4 and 5 are dropped |
| C15 | Agree | Cardio stays outside runs; its advance doesn't touch the run |

### Sol

| ID | Outcome | Fix or reason |
| --- | --- | --- |
| S1 | Agree | Each track is normalized by its own `checkDate`; planned rows are cleared |
| S2 | Agree | Surplus days in progress are archived as sessions. Restore tests cover the same day and a later day |
| S3 | Agree | Deltas carry `exerciseId`; `trackId` is cut |
| S4 | Declined for now | Belongs to step 6, and doesn't force a schema choice |
| S5 | Agree | A stored `retired` flag. A detached run is detected through `nextRunId`. Backups carry both. Plans are never deleted (`:602-609`) |
| S6 | Agree, narrowed | `plannedJson` on the session, plus `plannedIndex` on each row |
| S7 | Agree | Normalize before archiving in PR 1; the cascade change goes in PR 2 |
| S8 | Agree | The gate separates a run-starting done tick from working-value edits |
| S9 | Partly agree | The dedupe stamp now commits with the row. Merge rules wait for step 6 |
| S10 | Agree | Writes carry the expected `runId`, and `duplicatePlan` mints new IDs |
| S11 | Agree | One release; the atomic gate ships in PR 2 |
| S12 | Agree | Same fix as C8, with tests on real old payloads |
| S13 | Agree | Bounded by `closedAt`; the #296 claim is qualified |
| S14 | Agree | A tracking-type change applies to future workouts only |
| S15 | Agree | Discard drops the actuals and keeps plan edits; the doc now says so plainly |

## Review round 2 ledger

Two seats reviewed: the Claude seat scored 7/10 and Sol scored 7/10, and both asked for changes.
The lead applied these fixes.

### Claude seat

| ID | Outcome | Fix or reason |
| --- | --- | --- |
| C7, N1 | Agree | No detached runs. An edit that removes the open run's day closes the run in the same transaction, and `resetDayToTemplate` applies the remove and add rules |
| N2 | Agree | The migration mints IDs first; the open run's ID is its day's `nextRunId`, and a test checks it. The v12 restore does the same |
| N3 | Agree | PR 1 resets only rows with `checkDate == ''`. A set ticked before midnight is kept, and a test checks it |
| N4 | Agree | Start sets `plannedIndex = i`, and `CarryForward` clears both new fields |
| N5 | Agree | A non-done edit on a day's `nextRunId` changes working values even with a run open elsewhere |

### Sol

| ID | Outcome | Fix or reason |
| --- | --- | --- |
| S3 | Declined to step 6 | Ticks made offline on a preview, followed by a swap on the phone before Start, need the offline-watch merge rules |
| S6 | Declined | #345 reports sets done versus prescribed, and the planned rows give that. Timed windows, each-side flags and resistance stay in the prescription on `program_exercise`. An untouched track that was removed mid-run was taken out of today's plan on purpose |
| S13 | Agree | An old watch never starts a run. It logs only into a run the phone started after the day's `closedAt` |
| N1 | Agree | Same fix as Claude N2 |
| N2 | Agree | Surplus days archived by the migration get `closedAt` and a fresh `nextRunId` |
| N3 | Agree | No longer reachable: with no detached runs, Discard always matches its day |

## Review round 3

Both seats approved.

| Seat | Round 1 | Round 2 | Round 3 |
| --- | --- | --- | --- |
| Claude Opus | 6/10 | 7/10 | 8/10, approve |
| GPT Sol | 5/10 | 7/10 | 9/10, approve |

The Claude seat added one nit, which the PR 2 build spec covers.

- The wizard's save at `:443` closes the open run only on the branch that rewrites that plan.
- The branch that creates a new plan removes no day.
