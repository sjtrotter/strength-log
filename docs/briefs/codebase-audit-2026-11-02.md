# Architecture audit: exercise.log (2026-11-02)

Tracking issue: #418 (one child issue per candidate, #404-#414).

Read-only audit. Paths are relative to the repo root. `DVM` = `app/.../ui/day/DayViewModel.kt`, `TR` = `data/.../data/TrackerRepository.kt`. Line numbers are as of 305b4fea.

## 1. Map

```
:domain (3.0k, pure)  generator, seeding (SetSeeder/SetEditor/CarryForward), standards, records, sync wire DTOs
:data   (5.7k)        Room + DataStore; TrackerRepository (2,460 lines) is the only module callers use
:transfer (3.7k)      backup codec, CSV, Health Connect
:app   (21.8k)        Compose + VMs; also hosts sync/ (watch projection + delta appliers) and rest/
:wear   (8.0k)        dial UI, PendingEdits queue, DataLayer client
```

Flow: wizard -> `ProgramGenerator` -> plan/program rows. Day screen: `DVM` combines `programFor`/`slotsFor`/`dayTracksFlow`, seeds working values (`exercise_log`) or run tracks, `WorkoutStart` opens a `workout_run`, ticks go through `TR.updateSets` -> `applyToRun`, Finish -> `TR.finishRun` (archive to `workout_session`/`session_set`, `CarryForward`, rotation advance) -> receipt -> History (`LogViewModel`). Watch: `TodaySnapshotSource` -> `WatchSnapshotBuilder` -> Data Layer -> `DialState`; watch deltas -> `StrengthLogWearListenerService` -> `SetEditApplier`/`CardioDeltaApplier` -> `TR.applyToRun`.

The pure modules are mostly deep and well tested (`SetSeeder`, `PersonalBests`, `PendingEdits`, `DialState.dialUiState`). Depth breaks down at two places: `TR` (131 public members) and `DVM` (85 public members, 56 `savedState[...]` writes). Rules that belong to neither have leaked into `ui/day` and get re-derived by `sync/`.

## 2. Deepening candidates (ranked)

### 1. One module owns seeding of runs and working values
- **Cluster.** The "prescription, else goal seed; partner aligned to the live main row count" rule is written five times: `DayScreenBuilder.kt:143-171` (seedPlan), `:124-131` (previewMainSets), `TR:598-614` (setPrescription), `TR:1519-1531` and `:1550-1556` (dropSupersetHalf), `TR:1684-1691` (swapInRun). "Seed into the open run, else seedIfEmpty" is duplicated in `DVM:1869-1878` and `SetEditApplier.kt:198-211`.
- **Interface today.** Every caller builds the `existing` map, fetches config and catalog, calls `DayScreenBuilder`, maps `SeedWrite` to `RunSeed`, and passes `seeds` into `startRun`, `applyToRun`, `applyExerciseSwap` and `seedRunTracksIfMissing`. You can see this at `SetEditApplier.kt:97-103,175-182`, `DVM:1385-1389,1749,1770-1790`, `WorkoutStart.kt:48`, `DayPreparation.kt:17-27`, `PreviewViewModel.kt:53` and `WatchSnapshotBuilder.kt:94-99`. It also means `sync/` imports `ui.day` (`SetEditApplier.kt:18`, `WatchSnapshotBuilder.kt:11,34-35`).
- **Proposal.** Put a pure `SlotSeeds` in :domain (`forDay`, `forSwap`). The repository computes seeds itself inside the transaction, as `dropSupersetHalf` already does, and the `seeds` parameters disappear from its interface. Add one `ensureSeeded(planId, dayId)`.
- **Deletion test.** If you delete `seedPlan`, the complexity comes back in 7 callers, so it earns its keep, but it sits at the wrong seam. If you delete the `seeds` parameters, the complexity vanishes from callers.
- **Leverage and locality.** One seed rule. Watch, phone and preview cannot diverge.
- **Tests.** Pure `SlotSeeds` tests, plus the existing in-memory Room run tests.
- **Size and risk.** M, medium. This touches the path that produces the pinned §11 ramp (130/165/190/210). It moves callers, not math, but every §11 test must pass unchanged. There is no schema change.

### 2. One transactional write path for set edits
- **Cluster.** Phone edits read a track outside any transaction (`DVM:1992` trackFor), apply `SetEditor`, and write the whole list (`TR:1070-1076` with `replacesAll`). That path covers `DVM:991-1027`, `1129-1220` and `1229-1290`. Watch edits instead transform inside `applyToRun` (`SetEditApplier.kt:62-90`).
- **Interface today.** The two paths hold different mutexes (`DVM:160`, `SetEditApplier.kt:35`). The repository admits that whole-list writes come "from the screen's empty or stale rows" (`TR:1857-1861`) but guards only the reopen case (`TR:1849`).
- **Likely lost update.** Suppose a watch tick commits between the phone's read and its write. The phone overwrites `done`, and `keepingWatchStamps` (`TR:1125-1129`) keeps the watch stamp, so the re-sent delta is then judged STALE (`SetEditApplier.kt:80`). Confirm this with a failing test before fixing it.
- **Proposal.** Add `SetEdit` commands in :domain (weight, reps, seconds, tick, add, remove, restore) and apply them through `SetEditor` inside the transaction. Both phone and watch call `TR.edit(...)`.
- **Deletes.** `updateSets`, `updateSetsPaired`, `replacesAll`, `keepingWatchStamps`, and the test hooks `beforeWorkoutUndoWrite`/`afterWorkoutTickWrite` (`DVM:162-163`).
- **Size and risk.** M, medium. This is run semantics. There is no schema or wire change.

### 3. A read model for "the day as it will be performed"
- **Cluster.** One per-slot projection (performed exercise, goal, tracking, "show the load on a hold", stored-or-seeded sets) is computed in four places: `DVM:2243-2330` buildCard, with the load rule at `:2259-2264`; `WatchSnapshotBuilder.kt:155-237`, with the load rule at `:174-183`; `PreviewViewModel.kt:50-75`; and `PlanScreenBuilder.kt:43-50`. The watch then re-derives the rule a third time (`WatchUiModels.kt:85`). `WatchSnapshotBuilder` documents itself as a "mirror" of buildCard (`:39-43`).
- **Duplicate flow plumbing.** The same `programFor`/`wizardAnswersFor`/`catalog`/`unit`/`slots`/`tracks` combine is assembled in `DVM:187-200`, `TodaySnapshotSource.kt:64-88`, `PreviewViewModel.kt:41-49` and `PlanViewModel.kt:44`.
- **Proposal.** A :data `observeDay(planId, dayId): Flow<PerformedDay>`. Screens and the snapshot builder only format.
- **Deletion test.** Removing it brings complexity back in all four consumers, so the module would earn its keep.
- **Size and risk.** L, high on the watch wire. Values on `WatchSnapshot` must stay byte-identical, and `WatchSnapshotBuilderTest` is the guard. Keep `DVM`'s loading and blanking behavior (`DVM:241-249`). This depends on #1.

### 4. Cardio execution lives once, in :domain
- **Cluster.** The phone `CardioRun.kt:72-123` and the watch `CardioSetState.kt:46-111` implement the same state machine: elapsed time, missed-boundary reconcile, auto-start of RECOVERY/COOLDOWN, and the distance-entry rule. The watch version runs on stringly-typed wire types.
- **Boot-count arbitration.** There are four copies: `CardioRun.kt:72-77`, `DVM:2222-2231`, `CardioSetState.kt:49-61` and `wear/.../TimedRepState.kt:30-35`.
- **Session summary.** The mode, hard, label, seconds and steps are computed twice: `DVM:818-828` and `CardioSetState.kt:163-171`. The activity label is also written twice (`CardioPresentation.kt:38-39`, `CardioSetState.kt:196-197`).
- **Proposal.** A domain `CardioExecution` over `CardioBlock` with a `BootAnchor` value and an injected now (wall, elapsed, boot). It returns its next state and a `summary()`. The phone and watch types keep only UI fields (drafts, crown, options). That gives two adapters, so the seam is real.
- **Tests.** Pure JVM tests. They can replace much of `DayViewModelCardioExecutionTest` (1,469 lines, Robolectric).
- **Size and risk.** M, medium. The wire is unchanged (convert at `WatchCardio.kt:31`). Saved-state restore (the Parcelable `CardioRun`, `CardioSetState.kt:201-241`) must keep reading old bundles.

### 5. Day identity rules: accent index and which day is shown
- **Accent index.** There are four rules:
  - position among strength days (`DVM:2040`, `TodayViewModel.kt:56,63`, `PreviewViewModel.kt:58`, `PlanScreenBuilder.kt:43`)
  - position among all days (`DVM:1494`, used by the receipt)
  - position among watch days (`WatchSnapshotBuilder.kt:84-88`)
  - the dayId letter (`LogScreenBuilder.kt:39-42`, used by History, the share card and the journal)

  Day ids are the first unused letter (`TR:530`), so after a remove and add the letter and the position disagree.
- **Next day to show.** Written twice: `TodayViewModel.kt:52-53` and `DVM:2009-2014`.
- **Proposal.** `Program.accentIndex(dayId)` and `Program.dayToShow(suggested)` in :domain. History can only compute the letter rule, which is why it is the default. This is an owner call.
- **Size and risk.** S, low. Colors visibly change on some screens. `accentIndex` changes in value on the wire, not in shape.

### 6. The phone rest timer as one module
- **Cluster.** Start is inside the tick (`DVM:1193-1209`). Adjust and skip are in `PhoneRestActions.kt:15-36`: an `object` with a process-global mutex, and `repo` and `runtime` passed on every call. Expiry is written twice, with different clear timing (`DVM:289-323`, `PhoneRestRuntime.kt:158-176`).
- **Related duplication.** The undo window `loggedAtMillis + 10_000L` appears three times (`DVM:344,1943,1968`), and the undo row-match predicate twice (`DVM:1096-1100`, `:1938-1941`).
- **Proposal.** A singleton `PhoneRest` with `state`, `start`, `adjust`, `skip` and `expire(now)` that owns the lock. `RestRuntime` keeps its real two-adapter seam.
- **Size and risk.** S-M, low. The DataStore keys are unchanged.

### 7. Watch workout logic out of the composable
- **Cluster.** `WearApp.kt:461-948` holds `startSet`, `tick`, `startRest` and `clearRest` as local functions that mutate `rememberSaveable` fields. The file reads the clock directly 41 times.
- **Proposal.** Turn `WorkoutDialState` (`WearApp.kt:949`, a field bag today) into a reducer: an event plus the device time in, a new state plus effects (edits to send, haptic cue, rest) out. `DialState.dialUiState` (`DialState.kt:157`, with 1,052 lines of tests) is the precedent.
- **Size and risk.** M, low. It is watch-only with no wire change, but it needs the owner to check it on the wrist.

### 8. Split TrackerRepository by the seams it already has
- **Cluster.** It has 131 public members, and about 28 of them pass straight through to `SettingsStore` (`TR:218-235`, `261-312`).
- **Cohesive modules inside.** Plans and selection (`137-210`, `371-720`), program editing (`524-946`), runs (`1381-2110`, absorbing #1 and #2), history (`1149-1380`), restore (`2112-2440`) and legacy fixups (`859-880`, `953-1030`, `1775-1790`).
- **Testing past the interface.** The class is `open` only so that tests can subclass it (`TR:101-106`; `DayViewModelWiringTest.kt:111`, `DayViewModelDaySwitchTest.kt:201`, `WizardViewModelWiringTest.kt:132`).
- **Proposal.** Separate modules that share `StrengthDatabase` as an internal seam for cross-table transactions.
- **Size and risk.** L, in 3-4 PRs. Restore is high risk (backup format, multi-table transactions), so do it last.

### 9. Split DayViewModel after 1-6
- **Cluster.** Plan editing (`DVM:1556-1870`) already has its own state (`dayEditState`, `DVM:470-476`), which `AppNavHost.kt:451` collects separately.
- **Proposal.** Move it into a `DayEditViewModel`. The receipt follows. What stays is day selection, the workout pager and sheets.
- **Size and risk.** M, low.

### 10. Small SSOT fixes (one PR)
- **Personal bests disagree on mixed tracking types.** The `PersonalRecord.kt:29-55` reducer lets a different tracking type replace the standing best, while `PersonalBests.best` keeps the first type (`PersonalBests.kt:36-45`). So the Best chip and the share card can disagree. Route both through `PersonalBests`.
- **`m:ss` formatting** is written five times: `CardioPresentation.kt:77`, `DayScreenBuilder.kt:41`, `LogScreenBuilder.kt:66`, `ShareCardContent.kt:110-115` and `wear/.../CardioSetState.kt:199`.
- **"Owns all the gear"** is written twice: `ProgramGenerator.kt:384-385` and `DayEditLogic.kt:35-36`.
- **Ramp and set-kind label counting** happens three times: `DayScreenBuilder.kt:203-213`, the `DVM:2276-2282` rampIndex counter, and `WatchUiModels.kt:134-144`.

Size S, low risk.

## 3. Delete outright

- **Settings pass-throughs.** Delete the roughly 28 `SettingsStore` pass-throughs on `TR` (`218-235`, `261-312`). Callers inject `SettingsStore`. First stop its setters returning `Preferences`, which is the leak the comment at `TR:236-238` works around.
- **`TR.currentDate()`** (`TR:117`) is used only by tests.
- **`DayScreenBuilder.SeedWrite`** (`:117-121`) duplicates `RunSeed` (`RunModels.kt:8`). Deleting it removes 9 `.map { RunSeed(...) }` conversions.
- **The 1-arg and 2-arg `WatchTrackerClient.sendCardio` overloads** (`WatchTrackerClient.kt:69-81`, `DataLayerWatchClient.kt:136-139`). Only the 3-arg form is called (`WearApp.kt:241`).
- **`FakeWatchClient`** (140 lines in wear `main`) is referenced only from tests. Move it to `src/test`.
- **`PhoneRestTimer.skip()`** (`PhoneRestTimer.kt:37`) returns `null` and nothing else.

## 4. Suggested order (each step is about one PR)

1. Do the deletions in section 3, and the small fixes in #10 (the PB fix needs its own test).
2. #2 step A: add the failing race test in `TrackerRepositoryRunEditsTest`.
3. #1: `SlotSeeds` in :domain, with `DayScreenBuilder` and the repository calling it. No behavior change; the §11 suite must be green.
4. #1 continued: the repository computes seeds itself, and the `seeds` parameters and `ensureSeeded` duplicates go. This unblocks #2 and #3.
5. #2: the `SetEdit` commands and a single write path. Delete the `DVM` test hooks.
6. #5: accent and next-day rules, after the owner picks the accent rule.
7. #6: `PhoneRest` module.
8. #4: domain `CardioExecution` and `BootAnchor`. The phone adopts it first, then the watch.
9. #3: `observeDay` read model. The phone day screen and preview adopt it first; `WatchSnapshotBuilder` adopts it last, behind its existing test.
10. #9: `DayEditViewModel`, then the receipt.
11. #7: watch dial reducer.
12. #8: the repository split in this order: plans, history, runs, then restore last.

## Maybe

- **Set display dispatch on `TrackingType`.** It repeats in `SetText.kt:15-26`, `WorkoutSetScreen.kt:797-803`, `ShareCardContent.kt:101-105`, `WatchUiModels.kt:100-104` and `DayScreenBuilder.kt:37-56`. Some copies use localized strings, so one formatter may not fit.
- **The legacy watch cardio path** (`CardioState.kt`, `CardioPlan`-based) next to `CardioSetState`. It can be retired only if no supported phone build still sends a block-less cardio day.
- **`AppNavHost.kt` (822 lines).** Not reviewed in depth.
