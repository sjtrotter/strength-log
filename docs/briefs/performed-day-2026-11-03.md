# Design: one read model for the day as it will be performed (#408)

Line numbers are as of `4121a15c` (main). `DVM` = `app/.../ui/day/DayViewModel.kt`, `WSB` = `app/.../sync/WatchSnapshotBuilder.kt`, `TSS` = `app/.../sync/TodaySnapshotSource.kt`, `PVM` = `app/.../ui/preview/PreviewViewModel.kt`, `PSB` = `app/.../ui/plan/PlanScreenBuilder.kt`, `TR` = `data/.../data/TrackerRepository.kt`.

## 1. Today

Since the audit, #422-#426 already unified most of the per-slot math: `performedExercise()` (`data/PerformedExercise.kt:22-36`) is the one swap rule, `DaySeeds` the one seed rule, `SlotSeeds` the one seeding math. What is still written more than once is the *assembly*: which flows feed the projection, how MAIN and SS tracks are paired, "stored or seeded", and the load rule.

| | Flow assembly | Slots | Performed / goal | Load rule | Sets |
|---|---|---|---|---|---|
| **Day screen** `DVM` | `contextFlow` 181-197: `programFor`, `wizardAnswersFor`, `unitFlow`, `catalogFlow` (+ a `configFlow` branch with no target). `committedDayFlow` 200-250: `dayHistoryKeys` → `ensureSeeded` (a write) → `combine(dayTracksFlow, openRunFlow)` | `forRun(dropped, skipped)` 218 | `performedExercise` 2185 (MAIN), 2199-2201 (SS); goal `?: Weight(0.0)` 2189 | Boolean `timedShowsWeight` 2192-2197; partner 2206 | `stored ?: emptyList()` 2198 (relies on the write above) |
| **Watch snapshot** `TSS` + `WSB` | TSS 67-85: `programFor`, `slotsFor`, `dayTracksFlow`, `openRunFlow`; `wizardAnswersFor`, `catalogFlow`, `unitFlow`, `restSettingsFlow` | `forRun` WSB 93 | WSB 166, 187-189; `goal(cfg)` 173; header via `DayScreenBuilder.headerTarget` 185 | Numeric `goalLb` 175-184 (lb on the wire) | `stored ?: DaySeeds.plan ?: DaySeeds.previewMain` 191-193; SS `stored ?: plan ?: empty` 194-196 |
| **Preview** `PVM` | 44-46: `programFor`, `wizardAnswersFor`, `catalogFlow`, `unitFlow` (`distinctUntilChanged`); 49 `dayHistoryKeys` (which itself combines `slotsFor` + `dayTracksFlow`); 52 `dayTracksFlow` **again** + `plansFlow` | **none** (no `forRun`) | 62-66 per track | none needed (`SetFormatter.summary`) | `stored ?: DaySeeds.plan.orEmpty()` 69 |
| **Plan screen** `PlanViewModel` 44-49 + `PSB` 46-52 | `plansFlow` → `programFor`, `wizardAnswersFor`, `catalogFlow`, `unitFlow` | synthetic `ProgramSlot(position)` from the *program*, no logs, no swaps | n/a | n/a | `DaySeeds.previewMain(...).size` |
| **Wear** `WatchUiModels.kt:88` | wire only | | | `TIMED && goal > 0` re-derived from the wire | |
| **History ids** `DayPreparation.kt:14-25` | used by DVM 210 and PVM 49-50 | | MAIN/SS pairing a 4th time | | |
| **Watch edit** `SetEditApplier.kt:56-62, 81, 96-101` | one-shot `first()` reads | `forRun` 57 | 81 | | `DaySeeds.plan` 100 (write path; out of #408's scope) |

### Where they differ

1. **Live divergence: the preview ignores the run's drops and skips.** `PVM:49-61` maps the raw `slots` from `dayHistoryKeys`; it never applies `forRun(tracks.droppedPartners, tracks.skippedLifts)`. Concrete case: a run is open on day A and the lifter skipped lift 2 (or dropped a partner). Plans → the plan → tap day A always opens the preview (`AppNavHost.kt:309`). The preview lists lift 2 with a *fresh seed* as its working value ("235 × 5"), because the skipped lift's retired tracks are not in `tracks.tracks` and `DaySeeds.plan` seeds whatever has no row. The day screen and the watch omit it. Small, reachable, wrong. The read model fixes it for free (PR 1).
2. **Unseedable slot (exercise id not in the catalog).** Reachable: `removeCustomExercise` (`TR:284`) with the id still on a slot, or a restored backup from a device that had the custom exercise. `ensureSeeded` cannot seed it (`DaySeeds.plan:31` skips), so the phone card has **0 rows**; the watch sends `targetSets` blank WORK rows (`previewMain`), and the widget counts them in its total (`DayProgress.of(snapshot)`); the preview shows no working value. `WatchSnapshotBuilderTest:260` pins the watch side ("same Today and snapshot preview count"), but its "Today" is `DaySeeds.previewMain` itself, not the day screen. Not fixed here: the wire must not change, and blank rows on the phone could not be ticked (`applyToRun`'s seed base is empty for such a slot, so the edit would be `Unchanged`). Owner question 1.
3. **Not divergences, left alone.** Day index: the phone indexes `strengthDays`, the watch its own `watchDays` filter (WSB 86-91); both are pinned, both are presentation. Unresolved-entry header: phone shows "0" via `Weight(0.0)`, the watch sends `""` and the wear falls back to "0" (`WatchUiModels.kt:96`); same pixels. The DVM seeding *before* observing while the others seed *in* the projection gives the same rows once the write has landed; the design keeps the write (binding constraint).

## 2. The target

### Types (`:data`, new file `data/PerformedDay.kt`)

```kotlin
/** One track of a slot as it will be performed: the exercise after any swap, its target,
 *  and the rows the lifter will see — stored when a row exists, else the seed. */
data class PerformedTrack(
    val performed: PerformedExercise,     // exerciseId, entry, prescription
    val goal: GoalTarget?,                // performed.goal(config); null for an unknown entry
    val stored: List<LoggedSet>?,         // the row, when one exists
    val seed: List<LoggedSet>?,           // DaySeeds.plan's rows when none does; null when unseedable
) {
    val tracking: TrackingType get() = performed.tracking
    val sets: List<LoggedSet> get() = stored ?: seed.orEmpty()
    /** The load the target carries, canonical lb (WSB:175-184 today): the wire's `goal`,
     *  the phone's load caption. 0 for reps-only targets. */
    val loadLb: Double
    /** The one load rule (DVM:2192-2197, WatchUiModels:88): a hold that carries load. */
    val showsLoad: Boolean get() = tracking == TrackingType.TIMED && loadLb > 0.0
}

data class PerformedSlot(val slot: ProgramSlot, val main: PerformedTrack, val partner: PerformedTrack?)

data class PerformedDay(
    val planId: Long,
    val dayId: String,
    val program: Program,
    val day: ProgramDay?,                 // null when dayId is not in the program
    val config: LifterConfig,
    val equipment: Set<Equipment>,
    val catalog: ExerciseCatalog,
    val tracks: DayTracks,                // runId, runOpen, retired, dropped, skipped: DVM reads all of them
    val slots: List<PerformedSlot>,       // after forRun(dropped, skipped), in position order
) {
    /** Distinct performed ids for the history fetch (replaces DayPreparation.performedDayExerciseIds). */
    val performedExerciseIds: List<String>

    companion object {
        fun of(planId: Long, dayId: String, program: Program, slots: List<ProgramSlot>,
               tracks: DayTracks, answers: WizardAnswers, catalog: ExerciseCatalog): PerformedDay
    }
}
```

`of` is the code that moves: `forRun`, `associateBy { id to slot }`, `DaySeeds.plan(workoutSlots, existing, config, catalog)`, then per slot `performedExercise(slot, stored[MAIN]?.exerciseId, catalog)` and, when `exercise.superset != null`, the SS twin. `seed` is `plan[key]` (null when unseedable). `loadLb` is WSB:175-184 verbatim. Nothing here is new math; `SlotSeeds`, `GoalCalculator`, `DaySeeds` are untouched, so the §11 numbers cannot move.

`showsLoad` is behavior-identical to the DVM's boolean everywhere it is read: `SetText.kt:24` reads it only under `TrackingType.TIMED`, `SetEditSheet.kt:184` under `WEIGHTED || timedWeight`, so the `LoadReps -> true` arm at DVM:2194 (a WEIGHTED track) was never observable. The partner has no prescription, so `partner.showsLoad` equals DVM:2206.

### The flow (`TrackerRepository`)

```kotlin
/** The day as it will be performed. Pure read: nothing is seeded or written here. */
fun observeDay(planId: Long, dayId: String): Flow<PerformedDay> = combine(
    programFor(planId), slotsFor(planId, dayId), dayTracksFlow(planId, dayId),
    wizardAnswersFor(planId), catalogFlow,
) { program, slots, tracks, answers, catalog -> PerformedDay.of(planId, dayId, program, slots, tracks, answers, catalog) }
```

- **Not in it:** the display unit, rest settings, keep-screen-on. #420 deliberately removed the repository's settings pass-throughs; those are formatting inputs and each consumer combines `settings.unitFlow` (etc.) itself, as now. `openRunFlow` is also not in it beyond what `DayTracks` already carries (the DVM wants the *other* day's open run for `openRunElsewhere`, TSS wants `startedAt`; both keep their own `openRunFlow` leg).
- **No `distinctUntilChanged` inside.** `ExerciseCatalog` is not a data class, so structural equality would be defeated whenever the catalog re-emits anyway; `wizardAnswersFor` is already distinct; and each consumer dedupes at its own level (DVM `contextFlow` and PVM are `distinctUntilChanged`, the watch publisher compares snapshot content). Adding one would be a layer that does nothing.
- **Cost.** Five legs, all Room (`dayTracksFlow` is itself four). Every write to a touched table re-emits; per emission the work is `DaySeeds.plan` over the day's slots (under ten) — microseconds. The DVM already carries this fan-in today; TSS and PVM lose a leg each.
- **Null and empty.** A `dayId` not in the program gives `day = null`, `slots` from whatever rows exist (normally none). The DVM maps that to `noDayState` as now; the watch builder returns `null` as now (WSB:90).

### What each consumer keeps

- **DayViewModel**: everything that is screen state. `buildCard(slot: PerformedSlot, unit, history)` formats: titles, `HeaderTarget`, labels, rows, chips, plate line, weight-swap affordance. `timedShowsWeight = slot.main.showsLoad`. `main = slot.main.sets` — after `ensureSeeded` has run, `stored` is present whenever `seed` is, so this equals today's `stored.orEmpty()` in every reachable frame (the write completes before `observeDay` is subscribed, so Room's first emission already holds the rows).
- **WatchSnapshotBuilder**: `build(day: PerformedDay, unit, revision, restSettings, loggedCardio, cardio acks, planName, runStartedAt, processedEditStamps)`. `buildExercise(slot: PerformedSlot, ...)`: `goal = slot.main.loadLb`, `tracking = slot.main.tracking.name.lowercase()`, `sets = slot.main.sets ... ?: DaySeeds.previewMain(...)`. That last fallback stays *in the builder*, one line, documented as the wire's authored-count placeholder for an unseedable slot (divergence 2); everything else is formatting. `alternatesFor` stays public (SetEditApplier). The KDoc stops calling itself a mirror.
- **PreviewViewModel**: `lifts = day.slots.map { ... }` formatting `working` from `track.sets.firstOrNull { it.kind != RAMP }` and names from `performed.entry?.name`. Deletes `PreviewContext`, its own `DaySeeds.plan`, its second `dayTracksFlow` subscription and its `performedExercise` calls.
- **`dayHistoryKeys`** (DayPreparation): becomes `combine(observeDay(planId, dayId).map { it.performedExerciseIds }.distinctUntilChanged(), sessionSummariesFor(planId)) { ids, _ -> ids }`; `performedDayExerciseIds` is deleted. Callers get ids directly instead of `(slots, performedIds)`.
- **PlanScreenBuilder: deliberately not a consumer.** Its per-day set count is a *plan* fact — the program's exercises with no slots, logs or swaps — and the Plan screen lists every day of the plan. Routing it through `observeDay` would mean one five-leg observation per day for a number that `DaySeeds.previewMain` already gives from the program alone. It stays as is; the `PlanViewModel` assembly (44-49) is three flows it genuinely needs. The audit's "done when" named it; I am saying plainly that part is not worth doing.
- **Wear** (`WatchUiModels.kt:88`): stays; the wire cannot carry a new field without a shape change. Its comment should name `PerformedTrack.showsLoad` as the rule it reproduces.
- **SetEditApplier**: out of #408. Its reads could become `repo.observeDay(planId, dayId).first()` later, but the run-swap override (`delta.exerciseId` replacing the stored id, SEA:79-81) means the performed lookup there is not the stored one; leave for a follow-up if anyone wants it.

## 3. PR steps

Each is mergeable alone with green CI. The watch moves last.

### PR 1 — `PerformedDay`, `observeDay`, the preview and the history keys

- Add `data/PerformedDay.kt` and `TR.observeDay`. Move the preview onto it; rewrite `dayHistoryKeys` on it and delete `performedDayExerciseIds` (DVM:210-213 adapts to receive ids; three lines).
- Fixes divergence 1 (preview honors `forRun`). This is a visible change and the right one; flagged under owner questions as a notice, not a decision.
- **Must not change:** `PreviewUiState` fields and strings; `DaySeeds`, `SlotSeeds`, `PerformedExercise`; no writes from the preview (`unopenedPreviewUsesTheWorkoutSeedWithoutWritingWorkingValues` pins this); DVM output.
- **Tests:** new `data/.../PerformedDayTest` pinning `of` against today's behavior — stored wins over seed, partner seeds row-aligned to a lived-in main (`DaySeedsTest` already covers the math; this pins the pairing), unseedable gives `seed == null` and `sets` empty, `forRun` drops and skips, `loadLb` for all seven target arms, `showsLoad` for the TIMED cases and false for WEIGHTED `LoadReps`, `performedExerciseIds` follows swapped partners. `PreviewViewModelTest` unchanged plus one new test: an open run with a skipped lift leaves it out of the preview. DVM suites run unchanged (they exercise `dayHistoryKeys`).

### PR 2 — the day screen

- `committedDayFlow`: `historyKeys.flatMapLatest { ids -> flow { ensureSeeded(planId, dayId); emit(fetchDayHistory(ids)) } }.flatMapLatest { history -> combine(repo.observeDay(planId, dayId), repo.openRunFlow) { day, open -> buildState(ctx, day, history, open) } }`, with the `.onStart { emit(loading) }` exactly where it is (DVM:241-249). `buildState` reads title, emphasis, cardio block, `nextDayId` and day index from `day.program`/`day.day`, not `ctx.program`, so the card list and the header come from one emission. `DayContext` drops `cfg` (its last reader is `buildCard`); it keeps `program` for `resolveDay`, `noDayState` and `validateCardioRun`, and `catalog` for the skipped/retired name lookups and `buildEditState`.
- `buildCard(slot: PerformedSlot, unit, history)`; delete the per-slot `performedExercise`, goal and load code (DVM:2185-2207).
- **Must not change:** `DayUiState`/`ExerciseCardState` shape and values; seeding before observing; the loading frame on day switch; `mutationLock` discipline; every write path (`tickSetLocked`, `trackFor`, swaps) — they are not touched.
- **Tests:** `DayViewModelDaySwitchTest` (`switchingDaysDropsTheOldDaysRowsBeforeTheNewDaysArrive`, `keepScreenOnDoesNotRestartTheDay`, pausing), `DayViewModelCascadeTest` (§11: 235 / 130-165-190-210 / cascade to 245), `DayViewModelLastPerformedTest`, `DayViewModelPersonalRecordTest`, `DayViewModelWorkoutTest`, `DayViewModelReceiptTest`, `DayViewModelTimedRepExecutionTest`, `DayViewModelCardioExecutionTest`, `ScrollPathStabilityTest`, `WorkoutSetRouteTest`. All unchanged. One new test if cheap: a TIMED hold with added load has `timedShowsWeight`, a loaded WEIGHTED lift does not (pins the `LoadReps` arm's irrelevance so nobody "restores" it).

### PR 3 — the watch snapshot

- `TSS`: `combine(repo.observeDay(plan.id, dayId), repo.openRunFlow, context(unit, rest, acks), repo.cardioSessionsFlow, today)`; the `Context` class loses `cfg`, `catalog`, `equipment`. `WSB.build(day: PerformedDay, ...)` and `buildExercise(slot: PerformedSlot, ...)` as in §2. `runId`, `retiredRoundsDone`, `droppedPartners`, `skippedLifts`, `planId`, `equipment` come from `day`; `planName`, `runStartedAt`, cardio and ack inputs stay parameters.
- Reword the WSB KDoc; point the wear comment at `PerformedTrack.showsLoad` (comment only, no wear code).
- **Must not change:** any value on `WatchSnapshot`, field by field; `MAX_ALTERNATES`; `alternatesFor` signature; the `previewMain` placeholder for unseedable slots; `WatchSnapshot` with `suggestedDayId = ""` on `NothingOnToday` (TSS:51-60, untouched).
- **Tests:** `WatchSnapshotBuilderTest` has 40 `build(` calls. Add one test-local helper `performedDay(program, dayId, slots, logs, dropped, skipped)` that calls `PerformedDay.of(...)` with `DayTracks(null, false, logs, droppedPartners = ..., skippedLifts = ...)`, and change only the call expressions. **Review rule for this PR: no assertion line in that file changes**; the diff is inputs only. `TodaySnapshotSourceRestTest` (end to end through Room, Hilt-free) runs **unchanged** and is the stronger guard, including `a rest-settings edit changes the projected snapshot content` and `a mid-run bench swap publishes two retired rounds`. `DayViewModelReceiptTest:289` adapts its one call. `WatchUiModelsTest` unchanged. The widget tests (`todayWidgetContent`) unchanged.

After PR 3 the issue's "done when" holds for the day screen, the preview and the watch; the plan screen is excluded on purpose (§2).

## 4. Risks and owner questions

**Risks**

- *Two program emissions in the DVM.* `ctx.program` (for day resolution) and `day.program` (for display) come from two `programFor` subscriptions and can be a frame apart during a plan edit. Mitigated by building all displayed facts from `day.program` and by the existing `ctx.copy(dayId = takeIf { it == effectiveDayId })` guard. Day-switch and edit tests cover the frame.
- *Weakening the wire guard while rewriting its inputs.* Handled by the "inputs only" review rule and by `TodaySnapshotSourceRestTest` not changing at all.
- *A `combine` leg that suspends first.* `programFor` and `wizardAnswersFor` run `ensurePlanSettingsMigrated()` before emitting; today's consumers already wait on them, so `observeDay`'s first emission is no later than the current pipelines'.
- *Catalog identity.* `ExerciseCatalog` compares by reference; a custom-exercise write re-runs every consumer, exactly as it does today. Not a regression, not fixed here.
- *Pinned numbers.* No seeding or goal code changes; `DaySeedsTest`, `SlotSeedsTest`, the §11 cascade tests are untouched and must stay green in each PR.

**Owner questions** (only where visible behavior is involved)

1. *Unseedable slot* (divergence 2): the phone shows a card with no rows, the watch and widget show the authored count as blank rows. This design leaves both as they are. Do you want them reconciled, and which way? Changing the watch alters wire values (a later, separate PR); changing the phone means blank placeholder rows that cannot be ticked until the slot is swapped.
2. *Preview with an open run* (divergence 1): PR 1 makes the preview drop skipped lifts and dropped partners, matching the day screen and the watch. I am treating this as a fix, not a decision; say so if you'd rather the preview keep showing the full plan.
