# Design: one seeding owner, one edit path (#407 step 2 + #406)

Issues: #407 (step 2) and #406, under the audit tracking issue #418. Designed 2026-11-02; three PRs in §4.

Line numbers are as of `2cc8d2a2` (main, after #422). `TR` = `data/.../data/TrackerRepository.kt`, `DVM` = `app/.../ui/day/DayViewModel.kt`, `SEA` = `app/.../sync/SetEditApplier.kt`.

## 1. Write paths today

| Caller | Repository call | Transaction? | What "seeds" means |
| --- | --- | --- | --- |
| `DVM.changeWeight/Reps/Seconds` (994-1029), `tickSetLocked` (1132-1171), `addSet/removeSet/undoRemoveSet` (1232-1312) | `updateSets` (TR:986-992) / `updateSetsPaired` (TR:1026-1039) → `applyToRun(replacesAll = true)` | Read `trackFor` (DVM:1992) outside; the whole-list write inside | The screen's rows, as `RunSeed(…, CarryForward.cleared(sets))`: the base for a track that has no row yet |
| `DVM.undoLastLog` (1084-1118) | `applyToRun` with a precondition transform | Inside (test hook `beforeWorkoutUndoWrite` at 1096) | none |
| `SEA.applyEdit` (43-122) | `applyToRun(…, edits, seeds)` or `applyToOpenRun` | Transform inside; `dayTracks`, `slots`, `catalog`, `seedPlan` read outside (56-62, 96-101) | Plan seed from `DayScreenBuilder.seedPlan` |
| `SEA.applySwap` (150-191) | `applyExerciseSwap(…, seed, startSeeds, …)` (TR:1352) | Inside; seeds computed outside | Plan seed + swap seed |
| `SEA.seedSwappedSlot` (195-208), `DVM.ensureSeeded`/`seedWorkingValues` (1869-1886) | `seedRunTracksIfMissing` (TR:1858) or `seedIfEmpty` (TR:1011), one transaction each | "Run open?" decided outside | Plan seed via `DayPreparation.daySeeds` (18-28) |
| `WorkoutStart.startNow` (46-50) | `startRun(planId, dayId, seeds)` (TR:1329) | Inside; seeds outside | Plan seed |
| `DVM.swapWorkoutSlot` (1765-1803), `undoWorkoutSwap` (1372-1396) | `startRun(seeds)` then `swapInRun(…, seed)` (TR:1543) | Inside; seeds outside | Plan seed + `DayScreenBuilder.swapSeed` |
| `DVM.restoreSkippedInRun` (1739-1752) | `restoreSkippedLift` then `seedRunTracksIfMissing(alignPairs = true)` | Two transactions | Plan seed |
| `TR.dropSupersetHalf` (1435-1447) | itself | Inside, seeds computed from the DAO | Plan seed. **This is the model** for everything below |
| Read-side: `PreviewViewModel:55`, `WatchSnapshotBuilder:95-100, 193`, `PlanScreenBuilder:47` | `DayScreenBuilder.seedPlan` / `previewMainSets` | n/a (projection) | Plan seed for display |

The race (#406): `DVM.trackFor` reads outside the transaction, the write replaces the whole list, and `keepingWatchStamps` (TR:1041-1045) keeps the newer watch stamp on a row whose `done` the phone just reverted. The applier's replay then hits `row.watchEditedAt >= delta.editedAtMillis` (SEA:76) and is judged STALE. `keepsDoneRowsOf` (TR:1764-1783) is a partial guard for the same class of bug and goes with it.

## 2. Target interface

### `:data` — one pure day-level seed rule

```kotlin
/** data/DaySeeds.kt. Pure. SlotSeeds math, applied across a day's slots. */
object DaySeeds {
    /** Rows for every (slot, track) key absent from [existing]. A partner seeds at the live MAIN row count. */
    fun plan(slots: List<ProgramSlot>, existing: Map<Pair<Long, String>, Int>,
             config: LifterConfig, catalog: ExerciseCatalog): Map<Pair<Long, String>, List<LoggedSet>>
    /** Today's DayScreenBuilder.previewMainSets: the seed, else blank WORK rows at the authored count. */
    fun previewMain(slot: ProgramSlot, config: LifterConfig, catalog: ExerciseCatalog): List<LoggedSet>
}
```

This is `DayScreenBuilder.seedPlan` (143-160) moved verbatim, returning a map instead of `List<RunSeed>`: the read-side callers (preview, watch snapshot) look seeds up by key, while the repository writers convert the map back to an ordered `List<RunSeed>` until PR 2 removes those parameters. It lives in `:data`, not `:domain`, because `ProgramSlot` and `ExerciseCatalog` are `:data` types. `swapSeed` becomes a private repository function (nobody else needs it after step 2).

### `TrackerRepository` — public surface after both issues

```kotlin
suspend fun ensureSeeded(planId: Long, dayId: String)                       // replaces seedIfEmpty + seedRunTracksIfMissing
suspend fun startRun(planId: Long, dayId: String): RunStartResult
suspend fun applyToRun(runId: String, planId: Long, dayId: String, edits: List<TrackEdit>): RunWriteResult
suspend fun applyToOpenRun(planId: Long, dayId: String, editedAtMillis: Long, edits: List<TrackEdit>): RunWriteResult   // unchanged
suspend fun swapInRun(runId: String, programExerciseId: Long, slot: String, newExerciseId: String,
                      expectedExerciseId: String? = null): Boolean
suspend fun applyExerciseSwap(planId: Long, dayId: String, programExerciseId: Long, newExerciseId: String,
                              runId: String?, allowChange: Boolean): ExerciseSwapResult
suspend fun restoreSkippedLift(runId: String, programExerciseId: Long): RunWriteResult   // now reseeds + aligns inside
data class TrackEdit(programExerciseId: Long, slot: String, exerciseId: String? = null,
                     transform: (List<LoggedSet>) -> List<LoggedSet>?)   // replacesAll gone
```

`applyToRun` keeps its name: it is already the in-transaction command path the watch and `undoLastLog` use; the issue's `TR.edit(...)` is this method. Private plumbing: `seedPlan(day, runTracks, config, catalog)` builds `ProgramSlot`s from `exercisesForDay`, applies `forRun(dropped, skipped)` using the entity-level `isPartnerDropped`/`isSlotSkipped` (RunModels:87-105), computes `existing` from non-retired run tracks when the day's run is open or from `logForSlot` otherwise, and returns `DaySeeds.plan(...)`. `workingBase`, `initialRunTracks`, `resolveRunEdits`, `reopenTrack` take that map instead of `List<RunSeed>`; their logic is otherwise unchanged.

**Where config and catalog come from.** Lifter config is DataStore, so every public entry reads `val global = settings.globalLifterFlow.first()` *before* `db.withTransaction` and derives `answersForPlan(planDao.plan(planId), global).config` inside (the pattern at TR:498-501 and 645-660). Catalog is Room: `ExerciseCatalog(customExerciseDao.getAll().map { it.toEntry() })` inside the transaction (TR:526). Every private helper that can run inside another method's transaction takes `config` and `catalog` as parameters and never reads DataStore. `startRunIfNext` (TR:1343) is the trap: `applyExerciseSwap` calls it from inside its own transaction, so it must take the config the caller already read, not call public `startRun`.

### `:domain` — one row-edit rule

```kotlin
/** domain/seeding/SetEdit.kt. One row's edit from the phone or the watch; null leaves a value alone. */
data class SetEdit(val weightLb: Double? = null, val reps: Int? = null, val seconds: Int? = null,
                   val done: Boolean? = null, val startedAtMillis: Long? = null,
                   val completedAtMillis: Long? = null, val watchEditedAt: Long? = null)

object SetEditor { fun edit(sets: List<LoggedSet>, index: Int, edit: SetEdit): List<LoggedSet> }
```

`edit` = `editWeight` (cascade) / `editReps` / `editSeconds` for the value fields, then the stamp rule that today lives only in `SEA.stamped` (223-240) plus the `done` line (SEA:91): `done == false` clears both stamps; `done == true` on an already-done row fills only missing stamps; otherwise a given stamp overrides. `watchEditedAt` is written when given. The phone's tick (DVM:1156-1170) is a special case, with one difference to keep: the phone supplies `completedAtMillis = loggedAt` only when the row it reads inside the transaction goes from not done to done. A re-tick of an already-done row with a null `completedAtMillis` keeps it null on the phone (DVM:1141), while the watch's fill-missing rule fills it. So the phone builds `SetEdit(done = checked, reps = count, seconds = 0 if counted, completedAtMillis = loggedAt if the current row is not done)` from the transaction's row, and the watch keeps fill-missing; a test pins each. The issue's seven-command sealed hierarchy is more than needed: the repository already has a command primitive (`TrackEdit.transform`), and the only rule written twice is the row edit.

Add/remove/insert stay `SetEditor.addSet/removeSet/insertSet`; the paired variants (SetEditor:97-117) go because pairing is now the transaction: two `TrackEdit`s in one `applyToRun`, and the MAIN transform returning `null` when it refuses (`size <= 1`) leaves the whole group unwritten, which `seeded edit tracks commit only after every transform accepts` (RunTest:714) already pins.

### Callers afterwards

- **DayViewModel.** Value edits: `applyToRun(runId, planId, day, listOf(TrackEdit(id, slot) { rows -> if (index !in rows.indices) null else SetEditor.edit(rows, index, SetEdit(weightLb = lb)) }))`. Tick: same with the `SetEdit` above. Add/remove/undo-remove: one or two `TrackEdit`s. `trackFor` survives as a read for UI decisions only (the removed-set offer that must be pushed before the write, DVM:1267-1278; the TOP-set helper; the timed-count check), never as a write base. `ensureSeeded`/`seedWorkingValues` collapse to `repo.ensureSeeded(planId, dayId)` (the three `seedWorkingValues` callers at 1443, 1833, 1842 run with no open run on that day, so the single method is correct for them). `swapWorkoutSlot`: `startRun(planId, day)` then `swapInRun(runId, id, half, new)`. `undoWorkoutSwap`: `swapInRun(…, expectedExerciseId)`. `restoreSkippedInRun`: `restoreSkippedLift` alone. `undoLastLog`'s transform becomes a named pure function (`WorkoutUndo.revert(current, target, before): List<LoggedSet>?`) so its precondition is unit-tested without a hook.
- **SetEditApplier.** Drop the seeds block (96-101); build `SetEdit` from `delta.guardedFor(tracking)`; keep the STALE / INVALID precheck in the lambda so the `declined` side channel still works; `applyToRun(runId, planId, dayId, edits)`. The paired-tick decision (103-108) reads `DaySeeds.plan` for "is the other track seedable" so an unseedable partner still means a MAIN-only tick. `applySwap` passes no seeds; `seedSwappedSlot` becomes `ensureSeeded(planId, dayId)`. `sync/` no longer imports `ui.day` for seeding (the `activityLabel`/`ExercisePicker` imports are out of scope).
- **WatchSnapshotBuilder, PreviewViewModel, PlanScreenBuilder.** `DaySeeds.plan` / `DaySeeds.previewMain` in place of `DayScreenBuilder.seedPlan` / `previewMainSets`. Projection only; byte-identical output.
- **WorkoutStart.** `startRun(planId, dayId)`.

### Deleted

`RunSeed`; `TR.seedIfEmpty`, `seedRunTracksIfMissing`, the `seeds`/`seed`/`startSeeds` parameters on `startRun`, `startRunIfNext`, `applyToRun`, `applyExerciseSwap`, `swapInRun`; `DayScreenBuilder.seedPlan`, `swapSeed`, `previewMainSets` (moved); `DayPreparation.daySeeds`; `SEA.seedSwappedSlot`, `SEA.stamped`; `DVM.ensureSeeded`/`seedWorkingValues` bodies, the inline tick-row mapping; `TR.updateSets`, `updateSetsPaired`, `TrackEdit.replacesAll`, `keepingWatchStamps`, `keepsDoneRowsOf`; `DVM.beforeWorkoutUndoWrite`, `afterWorkoutTickWrite`; `SetEditor.addSetPaired`, `removeSetPaired`, `insertSetPaired`.

## 3. The race test

Goes in `TrackerRepositoryRunEditsTest` as the first commit of the #406 PR. It needs no hook: the phone's read and write are two separate repository calls today, so the test *is* the interleaving.

```kotlin
@Test fun `a phone edit written from a stale read keeps a watch tick that landed in between`() = runTest {
    val plan = generated(); val slot = slot(plan)
    val runId = start(plan)                                   // row 0 done by the phone, run open
    // The phone's read (DayViewModel.trackFor) happens here.
    val phoneRows = SetJson.decodeSets(db.runDao().tracks(runId).single().setsJson)
    // The watch ticks row 1 and commits, stamp 200 (what SetEditApplier's transform writes).
    assertEquals(RunWriteResult.InRun(runId, false), repo.applyToRun(runId, plan, "A", listOf(TrackEdit(slot, Slot.MAIN) { rows ->
        rows.mapIndexed { i, r -> if (i == 1) r.copy(done = true, completedAtMillis = 1_000L, watchEditedAt = 200L) else r }
    })))
    // The phone writes a RAMP-row weight edit on row 2 from its stale rows.
    repo.updateSets(runId, "A", slot, Slot.MAIN, SetEditor.editWeight(phoneRows, 2, 200.0))
    val after = SetJson.decodeSets(db.runDao().tracks(runId).single().setsJson)
    assertEquals(200.0, after[2].weightLb, 0.0)
    assertTrue(after[1].done)                                 // FAILS today: the tick is overwritten
    assertEquals(200L, after[1].watchEditedAt)                // passes today: the stamp survives, so a replay is STALE
}
```

On today's code the `done` assertion fails and the stamp assertion passes; together they are the lost update plus the poisoned stamp. After the fix the phone call becomes `applyToRun(..., TrackEdit(slot, Slot.MAIN) { SetEditor.edit(it, 2, SetEdit(weightLb = 200.0)) })` with no `phoneRows`, and the test documents the contract: an edit carries only its own change. Optional corollary in `SetEditApplierTest`: after the stale phone write, `applier.apply(sameTick)` returns STALE while the row reads undone; after the fix the row is done and the replay is STALE for the right reason.

## 4. PR steps

### PR 1 — `DaySeeds` in `:data` (pure move, no behavior change)

Move `seedPlan`/`previewMainSets` to `data/DaySeeds.kt` returning a map; switch `PreviewViewModel`, `WatchSnapshotBuilder`, `PlanScreenBuilder`, `SetEditApplier`, `DayPreparation.daySeeds`, `DVM.undoWorkoutSwap`/`swapWorkoutSlot` (they still map to `RunSeed` for the repository in this PR). Move `DayScreenBuilderTest` 76/586/601 to `DaySeedsTest` with the same assertions.

Must not change: §11 ramp and cascade numbers; `WatchSnapshotBuilderTest` (byte-identical snapshot); `PreviewViewModelTest`; `DayViewModelWiringTest:454`. Small, safe, and it already removes `sync/ → ui.day` for seeding.

### PR 2 — the repository seeds inside its own transactions (#407 step 2)

Add `ensureSeeded`, private `seedPlan`/`swapSeed`, the config-before/catalog-inside reads; remove `seeds` from `startRun`, `startRunIfNext` (takes config/catalog), `applyToRun`, `applyExerciseSwap`, `swapInRun`; fold reseed + `alignPairs` into `restoreSkippedLift`; delete `seedIfEmpty`, `seedRunTracksIfMissing`, `RunSeed`, `DayPreparation.daySeeds`, `SEA.seedSwappedSlot`. `updateSets`/`updateSetsPaired` stay as thin `applyToRun(replacesAll)` wrappers with no seed (a missing track now seeds from the plan and is then replaced; the one observable difference is `plannedJson` on a track the phone creates with a whole-list write, which only happens in tests).

Must not change, with the tests that pin each:
- Seeded once, then persists; whoever seeds first wins; an empty stored track is never reseeded: `TrackerRepositoryPlanScopeTest` 265, 276, 374; `TrackerRepositoryRunTest:695`; `seedIfEmpty`'s KDoc moves onto `ensureSeeded`.
- Reopened retired tracks keep done rows and follow a retired-time prescription edit: `RunEditsTest` 328, 381, 406, 432.
- Skips and drops: `RunEditsTest` 726-1100; `ProgramSlotForRunTest`; `SetEditApplierRunTest` 517, 531.
- Swap seeds (MAIN drops the prescription unless swapping back; SS at the live MAIN count; unknown substitute refused): `RunEditsTest` 542, 559, 585, 607; `SetEditApplierRunTest` 355, 483; `SetEditApplierTest` 126, 933; the `DaySeedsTest` swap cases from PR 1 are deleted here and the repository tests become the pin.
- Explicit Start uses working values before seeds: `RunTest:892`; preview edits before a run and first-tick capture: `RunTest` 295, 304, 325, 381; `SetEditApplierRunTest` 166, 214, 288, 303.
- Watch stale rejection, old-watch bound: `SetEditApplierTest` 228, 323; `RunTest:104`.

Test harness work is the bulk of this PR: about 200 call sites across `RunEditsTest` (65), `RunTest` (37), `PlanScopeTest` (29), `SetEditApplierTest` (21), `SetEditApplierRunTest` (11), `DayViewModelWorkoutTest` (10), `DayViewModelWiringTest` (9), plus `PersistenceProcessDeathTest` (androidTest, CI emulator). The `start()`/`seed()` helpers (`RunEditsTest:92-100`) become `applyToRun` with a tick transform and `ensureSeeded`; tests that need specific rows (`SetEditApplierTest.seedProgram` writes the §11 rows by hand) get one test-only helper that upserts through `db.programDao()`/`db.runDao()` so their numbers stay literal.

### PR 3 — one edit path (#406); carries the race test

Commit 1: the test in §3, pushed alone so CI shows it red. Commit 2+: `SetEdit` and `SetEditor.edit` in `:domain` with pure tests (phone tick, watch tick, untick, fill-missing, cascade); `SEA.applyEdit` builds `SetEdit`; DVM edits become `TrackEdit` transforms; `WorkoutUndo.revert` extracted; delete `updateSets`, `updateSetsPaired`, `replacesAll`, `keepingWatchStamps`, `keepsDoneRowsOf`, both hooks, the paired `SetEditor` functions, `SEA.stamped`.

Must not change:
- Phone writes preserve a newer watch stamp and the replay stays stale: `SetEditApplierTest:228` (rewritten against `applyToRun`; the assertion is the same).
- Every stamp rule: `SetEditApplierTest` 631-886 (timing, untick clears, fill-only-missing, partner round), `SetEditApplierRunTest:182`.
- Paired add/remove atomicity: `TrackerRepositoryPairedWriteTest` (rewritten as two `TrackEdit`s in one `applyToRun`); `RunTest:325`.
- Undo: `DayViewModelWorkoutTest` 570-615 (windows), 778, 830, 918, 950. The three hook tests: 800 and 854 become sequential (watch edit, then undo; same assertions); 887 is already covered by 950 and is deleted; the in-transaction precondition gets a pure `WorkoutUndo` test.
- `RunEditsTest:350` (`whole list writes before lazy seed cannot drop reopened done rows`) exists only because of whole-list writes. Rewrite as "a value edit before the lazy seed reopens the swapped-back track and applies on top of its done rows" (328 already covers the tick case).
- §11 numbers; `SetEditor.editWeight` untouched.

## 5. Risks and judgment calls

1. **DataStore inside a transaction.** The design adds a config read to `applyToRun`, `startRun`, `ensureSeeded`, `restoreSkippedLift`. All read before `withTransaction`; the nested path (`applyExerciseSwap → startRunIfNext → startRun`) must pass config down. A builder who "simplifies" by calling public `startRun` from inside will reproduce the `setPlanEmphasis` deadlock. Say it in the spec and in a KDoc on the private helpers.
2. **Hot-path cost.** One `globalLifterFlow.first()` plus one `planDao.plan` per tick. Milliseconds; acceptable. Do not cache config in the repository (SSOT).
3. **`ensureSeeded` widening for the legacy watch swap.** `seedSwappedSlot` seeded only the swapped slot; `ensureSeeded` seeds every missing track of the day. By "seeded once" this is invisible except on a day the phone has never opened, where it brings the day forward to the state the screen would create anyway. `SetEditApplierTest:126/933` assert only the swapped slot, so they stay green.
4. **The removed-set undo offer** is built from the pre-write rows (UI reads). If the watch changes that exact row between the read and the write, the offer restores the screen's version of the row. Today's behavior is identical; it is offer-only and bounded by the undo window. Not worth capturing inside the transform.
5. **Test churn** is the real size. PR 2 is M by production diff and L by test diff; it should stay one PR because an intermediate state with both seeding mechanisms is worse than a large mechanical diff. If the builder must split, split on tests only (migrate harness helpers first with the old API still present), never on production code.
6. **No owner question.** No spec number, schema, backup format, or wire shape changes; the watch's `SetEditDelta` is translated to `SetEdit` on the phone. One judgment call to flag, not ask: the TOP-set helper (`markTopSetHelperSeen`) marks from the row the user tapped rather than from a pre-read; the only difference is marking on a declined write, which cannot happen for a value edit on an in-range row.
