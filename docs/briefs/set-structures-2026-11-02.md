# Design: set structures per lift (#415)

Owner decisions (2026-11-02): pyramid later (PR 4, a follow-up); a written load x reps on a ramped structure is the top set and cascades; hand-added big barbell lifts (catalog `Std` goal) default to ramp/top/back-off, everything else to straight sets. Wizard main lifts stay ramp/top/back-off, and existing plans migrate unchanged (main lift to ramp/top/back-off, the rest to straight).

Line numbers are as of `121a9a22` (main, after #426). Issue #415; before the receipt redesign #416. Reads: CLAUDE.md, DESIGN.md, spec §4/§11/§12, PLAN A13, `docs/briefs/run-writes-2026-11-02.md`, `personal-bests-2026-11-02.md`.

## 0. Decisions in one screen

- One enum on the slot, `SetStructure { STRAIGHT, RAMP_TOP_BACKOFF, RAMP_TOP }`, replaces `ProgramExercise.isMain`. Three fixed shapes, no percentages or counts to tune. The pyramid is defined below but not shipped until the owner says so (owner question 1).
- No separate warm-up switch. Spec §12: "the ramp IS the main lift's warm-up." Ramps are part of the shape, so the shape already answers the warm-up question. `hasWarmupHint` and `repSchemeLabel` are dead display data (nothing renders them) and go.
- The structure applies to a weighted target from either source: the catalog goal or an authored `load × reps`. Reps, holds and reps-in-time always seed straight sets. This amends PLAN A13's "authored slots never cascade" to "straight-set prescriptions never cascade" (owner question 2).
- Every shape is a sequence of the existing `SetKind`s, so seeding is the only domain rule that changes. Cascade, carry-forward, labels, rest, records, History, receipt and the watch wire key on `SetKind` and do not change. §11 numbers are untouched.
- Room 17→18 rebuilds `program_exercise` (minSdk 26 has no `DROP COLUMN`); backup v16. The watch wire does not change.
- The choice lives on the slot options page of the Edit day sheet, as three radio rows under "Set structure", shown only for weighted targets. It is a plan fact; there is no per-workout structure (add or remove a set in the run covers the one-off).

## 1. The model

### 1.1 The type

```kotlin
// domain/model/Types.kt, beside SetKind
/** How a slot's sets are laid out. Stored by name; new constants go at the end. */
enum class SetStructure {
    STRAIGHT,          // targetSets × WORK at one weight
    RAMP_TOP_BACKOFF,  // 4 RAMP, TOP, 1 BACKOFF (spec §4, pinned)
    RAMP_TOP;          // 4 RAMP, TOP

    val hasTopSet: Boolean get() = this != STRAIGHT
}

data class ProgramExercise(
    val exerciseId: String,
    val structure: SetStructure = SetStructure.STRAIGHT,
    val targetSets: Int = 3,
    val superset: SupersetPartner? = null,
    val note: String = "",
    val prescription: Prescription? = null,
    val keepToPlan: Boolean = false,
)
```

`isMain` (Types.kt:50), `repSchemeLabel` (:52) and `hasWarmupHint` (:53) are removed. Old data maps through one rule, `SetStructure.fromLegacy(isMain)`: a main lift becomes `RAMP_TOP_BACKOFF`, everything else `STRAIGHT`. The one visible change: a main lift that carried a written `load × reps` ran as straight sets of that load; it now ramps around that load as its top set (owner decision 2). Keeping it straight would break workout-only swaps, where the substitute drops the prescription and must still ramp. The mapper bridge (PR 1), the Room migration (PR 2) and the backup v15 read (PR 2) all apply it.

### 1.2 What each shape seeds (squat, GOAL 235, BALANCED)

The "top" of a slot is one (weight, reps) pair: the prescribed `LoadReps` when the slot has one, else `(GoalCalculator.goalFor(entry), StrengthStandards.topSetReps(emphasis))`. The shape is laid out around it; `round5` throughout, as today.

| Structure | Rows | Squat at GOAL 235 | Prescribed 225 × 5 |
|---|---|---|---|
| `STRAIGHT` | `targetSets` × WORK | 235 × 10, ×3 (today's non-main seed, `ACCESSORY_REPS`) | 225 × 5, ×3 (today's prescribed seed) |
| `RAMP_TOP_BACKOFF` | R R R R TOP B/O | 130×5, 165×5, 190×5, 210×3, **235×5**, 175×8 (spec §11) | 125×5, 160×5, 180×5, 205×3, **225×5**, 170×8 |
| `RAMP_TOP` | R R R R TOP | 130×5, 165×5, 190×5, 210×3, **235×5** | 125×5, 160×5, 180×5, 205×3, **225×5** |

Rules that follow:

- `targetSets` keeps today's meaning: the row count of a straight-set slot. Ramped shapes ignore it (they do today: `SetSeeder.seed` at SetSeeder.kt:44-63 never reads it for a main). It stays stored because straight slots need it and the generator's `targetSets = 6` on anchors is harmless.
- Reps, holds and reps-in-time (goal `GoalTarget.Reps`/`Time`, prescription `Reps`/`Hold`/`RepsInTime`) seed straight rows whatever the stored structure, exactly as SetSeeder.kt:35-42 and :18-25 do now. The structure is still stored on the slot so a swap back to a weighted lift honors it. The editor hides the choice for these slots.
- Superset partners stay all-WORK at the main's row count (`SlotSeeds.partner`, SlotSeeds.kt:23-24). Unchanged.
- No parameters earn a place. Back-off count, back-off percentage and ramp count are fixed by `StrengthStandards` (RAMP_PCTS, BACKOFF, BACKOFF_REPS at StrengthStandards.kt:53-55). A lifter who wants a second back-off adds a set in the run; `CarryForward.cleared` keeps EXTRA rows as working values (CarryForwardTest `extra rows remain working values`).

### 1.3 Shapes not shipped, and why

- **Pyramid** (ramps up, top, graded sets down): 130×5, 165×5, 190×5, 210×3, 235×5, 190×8, 165×8, with the descent reusing RAMP_PCTS[2], [1] and BACKOFF_REPS, so no new constant. It is the one shape whose BACKOFF rows are not all 75%, so `SetEditor.editWeight` (SetEditor.kt:48-64, which gives every BACKOFF row `× BACKOFF`) would need the shape passed in, and so would `CarryForward.next` and both edit callers. The owner said "maybe"; the three shapes he named are free downstream. Ship it only if he wants it (question 1), as its own PR (§5, PR 4).
- **Top set + back-offs without ramps**, **reverse pyramid**: not in the owner's list. Each is one enum constant and one `when` branch later; the column is TEXT by name, so adding one needs no migration.

### 1.4 Relation to the rest of the model

- `Prescription` (Prescription.kt:4-18) is unchanged. A prescription supplies the top; the structure supplies the layout. `PrescriptionDraft.sets` (PrescriptionDraft.kt:17) still writes `targetSets`.
- `hasWarmupHint`: delete. Written by `ProgramGenerator.withWarmupHint` (ProgramGenerator.kt:386-399), copied into `ExerciseCardState.hasWarmupHint` (DayScreenModels.kt:283, DayViewModel.kt:2247) and read by no composable, string or watch surface. The one test that asserts it (`ProgramGeneratorTest.kt:348`) goes with it. If the accessory feeler set ever comes back, it is a structure constant, not a flag.
- `repSchemeLabel`: delete. Set at ProgramGenerator.kt:341, 346, 353 and DayViewModel.kt:1759, rendered nowhere. The generator's `"ramp → top → back-off"` is the second copy of the fact the issue names.
- Tracking types: WEIGHTED is the only type that ramps (the generator already requires it for mains, ProgramGenerator.kt:334-336, and `TrackingTypeTest.kt:84` pins it). Seeding decides by the resolved target, not by the entry, so a weighted entry with a `Hold` prescription seeds straight holds, as today.

## 2. Progression per structure

`CarryForward.next` (CarryForward.kt:14-21) is unchanged: if the planned TOP weight differs from the finished run's TOP weight, re-derive from the actual top with `SetEditor.editWeight`, else keep the cleared actuals.

| Structure | At Finish | Example |
|---|---|---|
| `STRAIGHT` | No TOP row: the cleared actuals carry forward as they were done (weights and reps). Same as every accessory today. | 235×10, 235×12, 235×10 → next time 235×10, 235×12, 235×10 |
| `RAMP_TOP_BACKOFF` | TOP-keyed cascade, pinned. | top edited to 245 → 135/170/195/220/**245**/185 (§11) |
| `RAMP_TOP` | Same rule; there is no BACKOFF row to re-derive. | top edited to 245 → 135/170/195/220/**245** |

`keepToPlan` ("Next time: Plan", TrackerRepository.kt:741-747) is orthogonal and unchanged: a kept-to-plan slot drops its working values at Finish and reseeds from the goal or prescription, so a ramped prescription with "Plan" reramps from its prescribed top every session, and with "Last results" it carries the lifter's top like a generated main.

Generator default: anchors `RAMP_TOP_BACKOFF`, every other slot `STRAIGHT` (ProgramGenerator.kt:333-355), the same program as today. Manual add in the editor: `SetStructure.defaultFor(entry)` = `RAMP_TOP_BACKOFF` for a WEIGHTED `GoalSource.Std` entry, else `STRAIGHT` (owner question 3). Today a manually added squat seeds 3 × 235 × 10; the goal is calibrated as a top set, so that default is a wart this fixes with knowledge the catalog already holds.

## 3. Consumers and their change

Verified at `121a9a22`. "Unchanged" means the code keys on `SetKind` and every shape is a sequence of existing kinds.

| Area | Where | Change |
|---|---|---|
| Model | `domain/model/Types.kt:48-58` | `structure` replaces `isMain`; drop `repSchemeLabel`, `hasWarmupHint` |
| Seeding | `domain/seeding/SlotSeeds.kt:16-24`, `SetSeeder.kt:18-25, 35-63` | One function: resolve the top (prescription `LoadReps` or goal `Weight`), then `when (structure)`. Non-weighted targets stay straight. `SetSeeder.seed(pe, goal, cfg)` becomes `shape(structure, topLb, topReps, targetSets)`; `seedPrescribed` folds into it. `SlotSeeds` keeps its three entry points |
| Cascade | `domain/seeding/SetEditor.kt:48-64` | Unchanged. RAMP rows take RAMP_PCTS in order; a `RAMP_TOP` track has exactly four. KDoc "a main lift's TOP row" → "a TOP row" |
| Carry-forward | `domain/seeding/CarryForward.kt:14-21` | Unchanged (§2) |
| Labels | `domain/model/SetLabels.kt`, `DayScreenBuilder.kt:113-123`, `wear/.../WatchUiModels.kt:137-147` | Unchanged: R1..R4, TOP, B/O, ordinals from kinds |
| Rest | `domain/standards/RestPolicy.kt:45-54`; callers `DayViewModel.kt:1170`, `DayScreenModels.kt:419`, `WatchSnapshotBuilder.kt:268-270` | Unchanged. Reword the KDoc at :41-43 ("seeded only on main lifts" → "only by ramped structures") |
| Records | `domain/records/PersonalBests.kt:20-21` | Unchanged: RAMP never counts; TOP, BACKOFF, WORK, EXTRA count. Straight sets all count; a ramp-only lift's top counts |
| Receipt | `ui/day/CascadeCeremony.kt:44`; `SessionDao.kt:190` `observeTopSets(TOP)`; `TrackerRepository.kt:1192` | Unchanged for this issue. #416 draws one row shape per structure later and reads `structure` from the slot |
| History trajectory | `ui/log/JournalBuilder.kt:68` | `!pe.isMain \|\| pe.prescription != null` → `!pe.structure.hasTopSet`; `goalLb` from the prescribed `LoadReps.lb` when present, else the entry's `GoalTarget.Weight`, skip otherwise. #345 widens History anyway |
| Set screen | `ui/day/WorkoutSetScreen.kt:442`, `DayScreenBuilder.kt:184` (`topSetComparison`), `LiftSheet.kt:92` | Unchanged (kind-driven) |
| Set edit sheet | `ui/day/SetEditSheet.kt:143-145`, `strings.xml:528` | Helper copy by the track's kinds: with a BACKOFF row "Warm-ups and back-off follow this weight." (today), without one "Warm-ups follow this weight." |
| Day card state | `ui/day/DayScreenModels.kt:281, 283`, `DayViewModel.kt:2245, 2247` | Delete `isMain` and `hasWarmupHint` (never read) |
| Manual add | `ui/day/DayViewModel.kt:1753-1760` | `ProgramExercise(exerciseId, structure = SetStructure.defaultFor(entry))` |
| Wizard preview | `ui/wizard/WizardScreen.kt:519` | `firstOrNull { it.structure.hasTopSet }` |
| Plan screen | `ui/plan/PlanScreenBuilder.kt:47` | Unchanged: set counts come from `DaySeeds.previewMain`, so a ramp-only slot shows 5 |
| Generator | `domain/generator/ProgramGenerator.kt:333-355, 386-399` and the `withWarmupHint(...)` calls in each day builder | `main()` sets `RAMP_TOP_BACKOFF`; drop labels and the warm-up wrapper |
| Repository: prescriptions | `data/TrackerRepository.kt:498-548` `setPrescription` | The open-run replan seeds through the structure (`SlotSeeds.main` on the row with the new prescription and count) instead of `SlotSeeds.prescribed` |
| Repository: new | `data/TrackerRepository.kt`, `ProgramDao.kt` | `setStructure(programExerciseId, structure)`: write the column; if this day's run is open, `RunReplan.represcribe(current, seeded)` on the MAIN track (done rows stay, SS aligned as `setPrescription` does); `deleteLogsForExercise` so working values reseed. Mirrors `setPrescription` |
| Swaps | `data/DaySeeds.kt:51-69`, `TrackerRepository.kt:1539-1600`, `:666-676` | Unchanged. The structure rides on the slot's `ProgramExercise`, so a plan swap or a run-only swap seeds the substitute in the slot's structure from the substitute's goal; a REPS/TIMED substitute seeds straight by the tracking rule |
| Room | `data/db/entity/ProgramEntities.kt:63-66`, `Mappers.kt:24-27, 41-44`, `Migrations.kt` (new 17→18), `StrengthDatabase.kt:43`, `data/schemas/.../18.json` | Rebuild `program_exercise` with `structure TEXT NOT NULL` in place of `isMain`, `repSchemeLabel`, `hasWarmupHint` (the 12→13 pattern at Migrations.kt:170-195, sqlite_sequence kept). `structure = CASE isMain WHEN 1 THEN 'RAMP_TOP_BACKOFF' ELSE 'STRAIGHT' END` via the INSERT ... SELECT (the `fromLegacy` rule). Older migrations that read `isMain` (:176-184, :321-322) stay: they run on old schemas |
| Backup | `transfer/.../BackupDocument.kt:23, 197-208`, `BackupMapping.kt:109-116, 255-262`, `BackupCodec.kt:~105` | v16. `ProgramExerciseBackup` gains `structure: String? = null`; `isMain` becomes `Boolean? = null` and is read only (v≤15: `structure ?: if (isMain == true) RTB else STRAIGHT`; unknown names → STRAIGHT, never a throw); drop `repSchemeLabel`, `hasWarmupHint` (`ignoreUnknownKeys = true` at BackupCodec.kt:53 reads old documents). Add the v16 branch to the decoder `when` |
| Watch | `domain/sync/WatchSnapshot.kt:232-251`, `WatchSnapshotBuilder.kt:224-273` | No wire change. The watch sees `WatchSet.kind` and `restAfterSeconds`, both computed on the phone; `WatchCycleExercise.setCount` follows the seed. The watch never learns the structure and does not need to; the pyramid would also be existing kinds |
| Glance | `domain/glance/` | No `SetKind` use; unchanged |
| Tests (rename only) | `TrackingTypeTest.kt:84`, `ProgramGeneratorTest.kt:135, 174-175, 211, 348`, `ProgramGeneratorAnchorsTest.kt:57`, `CarryForwardTest.kt:13`, `SetEditingTest.kt:16`, plus ~40 app/data/transfer tests and fixtures that construct `ProgramExercise(isMain = …)` or `ExerciseCardState(isMain = …)` | `isMain = true` → `structure = SetStructure.RAMP_TOP_BACKOFF`; delete the warm-up-hint assertion |

## 4. The plan-editor UI

**Where.** The slot options page of the Edit day sheet (`DayEditSheet.kt:381-458`, `SlotOptionsScreen`), which already holds "Next time", Swap exercise, Prescription, Move and Superset for one slot. Between "Next time" and the buttons, a `titleSmall` "Set structure" and three M3 `ListItem`s with a leading `RadioButton` (the M3 radio-list pattern; three segments cannot hold these labels on a 360 dp phone, and a dropdown hides the descriptions). Shown only when the slot's target is weighted: the entry tracks WEIGHTED and the prescription, if any, is `LoadReps`.

Copy, sentence case, caps only for set notation (HANDOFF rule 4):

| Option | Headline | Supporting line |
|---|---|---|
| `STRAIGHT` | Straight sets | Every set at one weight. |
| `RAMP_TOP` | Ramp to a top set | Four warm-ups, then the top set. |
| `RAMP_TOP_BACKOFF` | Ramp, top set, back-off | Four warm-ups, the top set, one lighter set of 8. |

"Warm-ups" is the word the set edit sheet already uses for ramps (strings.xml:528).

**Prescription page** (`DayEditSheet.kt:461-553`): the "Sets" stepper shows only for a `STRAIGHT` slot; ramped shapes fix their row count. Everything else stays.

**Slot row** (`DayEditSheet.kt:353-375`): no change. The pattern line stays; the structure is one tap away and the Plan screen's set count already reflects it.

**Default for a new exercise.** `SetStructure.defaultFor(entry)` (§2): a Std-sourced weighted lift ramps, everything else is straight. Question 3.

**One workout only?** No. The structure is a plan fact like a prescription. Changing it while the day's run is open edits the plan and replans the undone rows of the open run, as a prescription edit does today (TrackerRepository.kt:506-544). A one-off ("skip the back-off today") is remove-set or add-set in the run, both already there and both carried forward only through "Last results". A `confirm` is not needed: the change is reversible and the done rows survive.

**State and wiring.** `DayEditSlotState` (DayEditModels.kt:41-60) gains `structure: SetStructure` and `weightedTarget: Boolean`; `DayEditActions` gains `onStructureChange: (slotId: Long, SetStructure) -> Unit`; `DayViewModel` calls `repo.setStructure` inside `mutate { }` like `setKeepToPlan`.

## 5. PR steps

Each PR is mergeable alone with green CI. The guard for every PR: `PinnedVerificationTest` (`squat seeded sequence`, `bench seeded sequence`, `cascade squat TOP to 245`), `SlotSeedsTest` (`main lift preserves the pinned squat sequence`), `CarryForwardTest`, `SetEditTest`, `TrackingSeedingTest`, `RestPolicyTest`, `PersonalBestsTest`, `SetLabelsTest`, the generator tests, `DaySeedsTest`, `TrackerRepositoryRunTest`/`RunEditsTest`, `BackupRoundTripTest`, and the store screenshots must render byte-identical.

**PR 1: the domain model (Opus).** `SetStructure`; `ProgramExercise.structure` replaces `isMain`; `repSchemeLabel` and `hasWarmupHint` leave the domain model and the generator; seeding by structure in `SlotSeeds`/`SetSeeder` with the `RAMP_TOP` branch present but unreachable from any UI; `SetStructure.defaultFor`; `JournalBuilder.kt:68`, `WizardScreen.kt:519`, the card-state fields and the test renames. `Mappers.kt` bridges the unchanged entity: `toDomain` reads `isMain` → RTB/STRAIGHT, `toEntity` writes `isMain = structure.hasTopSet`, `repSchemeLabel = ""`, `hasWarmupHint = false`. The design doc lands in `docs/briefs/set-structures-2026-11-02.md` and PLAN A13 gets its amendment line.
Must not change: every seeded sequence and cascade number; the generated program (same exercises, same row shapes, same set counts per day); rest buckets; labels. New tests: `SlotSeedsTest` for `RAMP_TOP` (five rows, no BACKOFF, same ramps and top), for a prescribed `LoadReps` under each shape (the 225 column above), for `Reps`/`Hold` under a ramped structure staying straight; `CarryForwardTest` for `RAMP_TOP` 235→245.

**PR 2: storage (Opus; Room migration).** Room 17→18 rebuild; entity, mappers and the exported schema; backup v16 with the legacy `isMain` read.
Must not change: any row's exercise, position, note, prescription, `keepToPlan`, superset; `sqlite_sequence`; the decoded program of every fixture backup in `transfer/src/test/resources/backup/`. New tests: `Migration17To18Test` (isMain 1 → RTB, 0 → STRAIGHT, columns gone, sequence kept, the 16→17 test's pattern); backup v15 document decodes with the right structures and a v16 round-trip; an unknown structure name decodes to STRAIGHT.

**PR 3: repository and plan editor (Sonnet for UI, Opus for the repository method).** `ProgramDao.setStructure`, `TrackerRepository.setStructure` with the open-run replan, `setPrescription` seeding through the structure; the radio list, the conditional "Sets" stepper, the set edit sheet helper copy, the manual-add default. This is the PR that makes `RAMP_TOP` reachable.
Must not change: `setPrescription` behavior for straight slots (its existing repository tests); done rows across a structure change; the SS track alignment. New tests: repository test that a structure change on an open run keeps done rows and reshapes the rest (`RunReplan.represcribe` pinned), and that it deletes the slot's working values; a straight → RTB change on a prescribed 225 × 5 slot yields the 225 column; `DayViewModel` wiring test for `onStructureChange`; the editor renders the list only for weighted targets (a hold slot shows none); a screenshot of the options page.

**PR 4 (only on owner yes): pyramid.** `PYRAMID` constant; `SetEditor.editWeight(sets, index, newWeight, shape)` where the shape is the per-kind percentage ladder (RTB and RAMP_TOP pass the ladder that reproduces today's rule exactly); `CarryForward.next` and both edit callers (`DayViewModel.kt:968-976`, `sync/SetEditApplier`) pass the slot's structure. Must not change: the §11 cascade with the default shape; `SetEditTest` `row edit delegates top weight cascade and keeps completed ramps`.

## 6. Owner questions

1. **Pyramid now, later, or not at all?** Recommend later (PR 4) or never: it is the one shape that needs a shape-aware cascade; the three you named cost nothing downstream. If yes, the shape is 130×5, 165×5, 190×5, 210×3, 235×5, 190×8, 165×8 for the reference squat.
2. **A ramped structure on an authored `load × reps` uses that load as the top set and cascades off it** (amends PLAN A13's "authored slots never cascade" to "straight-set prescriptions never cascade"). Recommend yes; it is what "squat 225 × 5, ramp/top/back-off" means.
3. **Default for an exercise added by hand:** ramp/top/back-off for the big barbell lifts (catalog `Std` goal) and straight sets for everything else, or straight for all? Recommend the first: today a hand-added squat seeds 3 × 235 × 10 against a goal calibrated as a top set.
