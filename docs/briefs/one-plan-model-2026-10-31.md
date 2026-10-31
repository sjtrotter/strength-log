# One plan model (#342) and per-exercise supersets (#343): design, 2026-10-31

Step 3 of milestone M9 (`product-council-2026-10-31.md`). It builds on workout runs
(`workout-runs-2026-10-31.md`), which shipped in v0.5.0.

- **Designer:** Claude Opus, standing in for Fable.
- **Status:** revision 4, approved after three review rounds (Astra 9/10, Claude 8/10). The ledgers are at the end.
- **Line numbers:** from main at `bdfe1627`. Room is at v14 and the backup format at 13.

## 1. What the owner decided

- Origin is provenance. Every capability works in every plan.
- Each slot either remembers what the lifter did or keeps to plan. Remembered slots get "Reset
  to plan".
- Cardio has one typed form. The prose finisher and the `CardioDay` side channel go.
- Plan settings live in the plan and show only where they apply.
- Hand-built standard lifts seed from goal math, not 0 lb × 10.
- Session notes move into Room.
- Supersets work in every plan. Each exercise in a round is logged and confirmed on its own,
  and rest starts after the round's last exercise.
- The §11 goal, ramp and cascade numbers stay pinned.

## 2. The current model

**Origin.** `PlanOrigin { GENERATED, AUTHORED }` (`PlanEntity.kt:28`) is stored as
`plan.origin`. Code branches on it in these places:

- **Rotation** (`Rotation.kt:12-13`): authored plans rotate over every day, generated plans over
  `strengthDays`. Authored days are always `STRENGTH` kind (`addDay`,
  `TrackerRepository.kt:482-494`), so the two rules give the same answer today.
- **Exercise floor** (`PlanRules.kt:6-8`): 3 per generated day, 1 per authored day, 0 on an
  authored day with cardio (`TrackerRepository.kt:745-751`, `DayEditLogic.kt:42-43`).
- **Settings:**
  - `answersForPlan` (`:188-190`) decodes the recipe only for generated plans;
  - `setConfig`, `setCardioPrefs` and `setWizardAnswers` (`:237`, `:250`, `:318`) drop writes to
    an authored plan;
  - Setup still shows Emphasis and Cardio for every plan (`SetupScreen.kt:112-113`), so an
    authored plan's edits vanish.
- **Day and slot operations that only authored plans allow:**
  - `addDay`, `renameDay`, `removeDay` (`:482`, `:496`, `:506`);
  - `setCardioBlock` (`:529`) and `setPrescription` (`:543`);
  - the matching UI gates (`DayViewModel.kt:1244`, `:1277`, `:1285`; `DayEditSheet.kt:318`,
    `:383`; `PlanViewModel.kt:42`, `:55`, `:71`; `PlanScreen.kt:71`, `:133`).
- **Add exercise** (`:717-735`): an authored add gets 3 sets and a prescription from the
  catalog goal, and standard lifts get `LoadReps(0.0, 10)` (`:731`). A generated add inserts the
  slot as given and seeds from goal math.
- **Superset partner:** `setSupersetPartner` refuses any slot with a prescription (`:789`), and
  the UI hides the button on authored plans (`DayViewModel.kt:1748`, `DayEditSheet.kt:391`).
  Every authored slot has a prescription, so authored plans can't have supersets.
- **Swap** matches by slot id in authored plans and by position otherwise
  (`DayViewModel.kt:1314`).

**Next time.** Finish calls `carryForward` (`:1568-1582`). A slot with a stored prescription has
its working values deleted, so it reseeds from the prescription. Any other slot gets
`CarryForward.next(planned, actual)`. No per-slot flag exists; "has a prescription" stands in
for "keep to plan".

**Cardio** has three forms:

1. A prose `CardioSuggestion` (`Types.kt:95-110`) from `CardioPlanner` (`CardioPlanner.kt:15-42`),
   stored in `program_day.cardioJson`.
2. Interval steps that `CardioIntervals.plan` derives from the prose. The watch's legacy timer
   runs them (`WearApp.kt:144-160`, `:257`).
3. A typed `CardioBlock` (`Cardio.kt:58-62`). Authored days store it in
   `program_day.cardioBlockJson`. Generated days are adapted at read time by `effectiveCardio`
   (`Cardio.kt:66-72`), which uses the plan's cardio mode to pick the activity.

The generator's standalone cardio days are `CardioDay` (`ProgramGenerator.kt:38-50`, `:426-445`),
outside `Program`. The wizard turns them into `CARDIO`-kind `program_day` rows with prose
(`WizardViewModel.kt:386-398`), and `resetDayToTemplate` can't regenerate them.

**Session notes** are a JSON map in DataStore (`SettingsStore.kt:99-101`, `:195-199`,
`:336-342`), backed up inside `settings` (`BackupDocument.kt:141`). Deleting a session leaves its
note behind (`TrackerRepository.kt:1199`).

**Supersets.** One `program_exercise` row with `supersetExerciseId` has two tracks, `MAIN` and
`SS`, row-aligned. A round tick flips `done` on both (`DayScreenBuilder.kt:193-202`) through
`updateSetsPaired` (`TrackerRepository.kt:977-990`). Rest starts on any checked tick
(`DayViewModel.kt:925-942`). Round counts read MAIN alone: `Slot.isRound` for Today, the receipt and the share
card, `DayProgress` for the widget and the watch, and the watch's dial reads. A watch
`done` delta on either track is copied onto the other (`SetEditApplier.kt:100-106`).

## 3. The target model

### Remember or keep

A new column, `program_exercise.keepToPlan` (Boolean, default false), is the slot's choice. It
covers both tracks of a superset slot.

- **The plan for a slot** is its stored prescription when it has one, and goal math otherwise.
  This is what `DayScreenBuilder.seedPlan` already computes, so seeding doesn't change.
- **Finish** (`carryForward`):
  - A kept slot's working values are deleted, so the next run seeds from the plan.
  - A remembered slot gets `CarryForward.next`, as now, with one exception. A `setPrescription`
    that switched between timed and rep-shaped rows mid-run leaves the open track as it was
    (`track.canReplan` is false). When a prescribed MAIN track with planned rows no longer
    matches its prescription's shape, the working values of both the slot's tracks are deleted
    instead, so the new prescription takes effect next time. A track with no planned rows is
    never checked.
  - The test is `slot.keepToPlan`, not `prescriptionJson != null`.
- **Defaults:**
  - The migration sets `keepToPlan = (prescriptionJson IS NOT NULL)`. Every existing slot
    behaves exactly as it does today.
  - A new slot remembers. That covers generated slots, slots added to any plan, and slots the
    lifter builds with the prescription editor. Setting a prescription doesn't change the flag.
- **Switching to "Plan"** deletes the slot's working values on both tracks at once, whether or
  not a run is open. The open run's tracks are separate rows and don't change. The working
  values reseed from the plan, so a Discard afterward also leaves the plan in place.
- **Reset to plan** (remembered slots only) deletes the slot's working values on both tracks,
  so the next run seeds from the plan. While a run on that day is open, the action is hidden.
  Finish would overwrite a reset made mid-run, and mid-run fixes are made on the sets
  themselves.
- **UI.** The slot's edit sheet gets one control labeled "Next time" with two options, "Last
  results" and "Plan". "Reset to plan" sits below it when "Last results" is chosen. Copy goes
  through `prose-whip` and `docs/design/copy-style.md`.
- **Backup.** Format 14 carries `keepToPlan` on each slot. Restoring an older file derives it
  from the prescription, the same rule as the migration.
- **Wire.** The watch doesn't need the flag. `WatchExercise.prescribed` keeps its meaning,
  "this slot has a stored prescription".

### One kind of plan

Origin stays in the table, in backups and in the UI's "where it came from" text. Behavior
doesn't branch on it, with one exception that really is about provenance. "Reset to template"
regenerates a day from the generator recipe, so it's offered only on a generated plan's day that
the generator would produce (`resetDayToTemplate` finds it in the regenerated program). If the
lifter removes generated day B and adds a new day, the new day gets the letter B, and Reset to
template would regenerate over it. That is accepted; the action already asks to confirm.

- **Rotation** is `strengthDays` for every plan, falling back to every day when a plan has no
  strength day. `Rotation.nextFor` loses its `authored` parameter. This matches today's answers
  for both origins (§2), and it keeps spec §6.4: standalone cardio days stay out of the
  rotation. `removeDay` moves a suggestion off the removed day with the same rule, so removing
  suggested B from A, B, C1, C2 suggests the next strength day, not C1.
- **The exercise floor** is 1 for every day, or 0 when the day has a cardio block. This is a
  spec amendment to §8.3's floor of 3 for generated days; `docs/PLAN.md` records it.
  `minExercisesPerDay` takes the cardio fact, not the origin.
- **Day operations** (add, rename, remove day; set cardio block; set prescription) work in every
  plan, and so does their UI, including the restored-draft gates (`DayViewModel.kt:346`, `:352`)
  and Reset to template's gate (`DayEditSheet.kt:328`). `addDay` picks the first free letter
  A–G, which can't collide with a generated `C1` or `C2`. Its cap of 7 counts strength days, so
  a six-day generated plan with two cardio days can still add one.
- **Add exercise** has one path: insert the slot as given, with no prescription, so standard
  lifts seed from goal math. The `LoadReps(0.0, 10)` default and its test go. Existing authored
  slots keep their stored prescriptions; the migration rewrites nobody's numbers.
- **Supersets** work on any slot, prescribed or not. The partner seeds from its own goal math
  (`seedPlan` already does this), and a kept slot resets both tracks at Finish.
  `setSupersetPartner` loses the prescription check and the UI loses both gates.
  `setPrescription` on a paired slot keeps the tracks row-aligned. In one transaction it
  builds one set of kept indices from both open tracks (index `i` stays if `i` is below the new
  count or either track is done at `i`), applies it to both, seeds added MAIN rows from the
  prescription and added SS rows with `seedPartner`, and clears both tracks' working values. If
  the MAIN track can't be replanned, neither track changes, and Finish's shape check clears both
  working values.
- **Swap** matches by slot id in every plan.
- **Settings.** Every plan has settings. A plan without a recipe reads defaults plus the global
  lifter, as an authored plan does now. The first settings write creates the recipe, as it does
  now for a generated plan without one. `answersForPlan`, `setConfig` and `setWizardAnswers`
  lose their origin checks. The backup codec stops rejecting an authored plan with a recipe
  (`BackupCodec.kt:305`).
  - Bodyweight, age and level stay global (`GlobalLifter`). They describe the person, not a
    plan.
  - Emphasis stays in the plan. It feeds goal math (`GoalCalculator.kt:32`,
    `SetSeeder.kt:48`), which now seeds hand-built standard lifts too, so Setup shows it for
    every plan.
  - Equipment stays in the recipe and is out of scope here (#339).

### Cardio has one typed form

- **One conversion rule.** A `data` function turns a prose `cardioJson` into a block with
  `CardioBlocks.fromLegacy`, using the plan's answers exactly as `answersForPlan` resolves them:
  the recipe when there is one, and the `WizardAnswers` defaults (outdoor run, 5K on) when there
  isn't. A mode of `NONE` gives no block. This is the block `effectiveCardio` shows today, so an
  open cardio run's `sourceBlockJson` (`DayViewModel.kt:1554`) still matches.
- **Converting stored days.** A one-shot repository fixup runs after
  `ensurePlanSettingsMigrated`, so legacy DataStore answers are already in the recipes. For each
  day with `cardioJson` and no `cardioBlockJson`, it writes the block, or nothing when the mode
  is `NONE`, and clears `cardioJson`. A day that already has a block keeps it. The fixup runs
  inside the same gate as `ensurePlanSettingsMigrated`. `selectedPlanFlow` already waits for
  that gate; `programFlow`, `programFor` (`TrackerRepository.kt:385-398`), the snapshot source
  and `exportSnapshot` are made to wait for it too, so no reader or export sees a day before its
  conversion. The fixup is skipped under the restore marker, as the
  plan-settings fixup is (`TrackerRepository.kt:880`). No Room bump: the column stays, written
  by nothing, and a later schema bump drops it.
- **Restore.** Every older backup format carries prose cardio, including pre-v9 files whose
  answers live in `settings` and authored plans without a recipe. Restore resolves each
  plan's answers first, then converts its days inside the restore transaction with the same
  rule. Format 15 writes no `cardioJson`.
- **The generator.** `ProgramDay.cardio`, `CardioDay` and `GeneratedProgram.cardioDays` go. The
  generator returns one `Program` whose standalone cardio days are `CARDIO`-kind `ProgramDay`s
  with a `cardioBlock` and the core exercise. `CardioPlanner` returns `CardioBlock`s, built
  through `fromLegacy` internally so the pinned interval vectors (`CardioIntervalsTest`) hold. A
  cardio day's `emphasisLine` keeps the text it has today. `resetDayToTemplate` can now
  regenerate `C1` and `C2`.
- **Readers.** `effectiveCardio` goes; readers use `day.cardioBlock`. The phone's prose detail
  (`DayViewModel.kt:1460`, `TodayViewModel.kt:177`) becomes the block's existing summary text.
- **Settings.** Cardio preferences now feed only the generator. Setup stops showing them in this
  PR, so no control is left that changes nothing. The wizard still asks. A day's cardio is
  edited on that day with the cardio block editor, which this PR opens to generated plans.
- **The watch.**
  - For every day with a block, the phone fills `WatchSnapshot.cardio` as the carrier of the
    already-logged facts (`loggedStartedAt`, `loggedSeconds`). Its label is the block summary,
    its detail is empty, `hard` is true when any set is hard, and `mode` is null.
  - A null mode suppresses the watch's legacy interval offer (`DialState.kt:200`), and
    `CardioSetState` reads the logged facts (`CardioSetState.kt:45`, `:190`). So the v0.5.0
    watch runs typed cardio alone and still sees that cardio was logged. Hand-built cardio days
    start receiving the logged facts too, which stops the watch offering them again after
    they're logged.
  - The watch's legacy timer code is deleted with the dial in step 6. `CardioSuggestion` stays
    as that wire type until then.
- **History.** `cardio_session` rows with a mode and no sets keep reading in the Log, CSV and
  Health Connect, as now.

### Session notes in Room

- `workout_session.note` (TEXT NOT NULL DEFAULT ''), in Room v15.
- A one-shot fixup copies the DataStore map into the rows and then clears the key. This
  mirrors `ensurePlanSettingsMigrated`. Notes for sessions that no longer exist are dropped.
- `sessionNotesFlow`, `setSessionNote` and the Log and receipt callers read and write the
  column. Deleting a session deletes its note with it.
- The backup carries `note` on each session (format 14). The reader still accepts
  `settings.sessionNotes` from older files and maps it onto the sessions. Restore writes the
  column, clears the DataStore key, and doesn't run the fixup.

### Supersets, logged per exercise

The tracks stay row-aligned: adding, removing and restoring a set stay paired. Only `done` and
its timing become per track. The phone logs per exercise now; the watch keeps its round tick,
unchanged, until the step 6 rebuild.

- **Phone.** Each round shows a tick per exercise. A tick writes its own track through
  `updateSets` with that track's slot; it never touches the partner. Unticking is per track too.
- **Rest** starts when a tick completes a round: a plain set, or the second of the pair, with
  both active tracks done at that index. The first tick of a pair starts no rest.
- **Finish** is enabled when any track, active or retired, has a done row
  (`DayViewModel.kt:1162-1165`, `DayScreen.kt:348`). It no longer needs MAIN to be done.
- **Round counts don't change.** Today, the widget's `DayProgress`, `retiredRoundsDone`, the
  receipt, the share card and the watch keep counting MAIN done rows, as now. A round with MAIN
  done and SS skipped counts; a round with only SS done doesn't. Every count stays the same on
  every device and in history, including history edited in the Log. Steps 4 and 6 redesign the
  screens that show these counts and can define them again there.
- **Watch deltas.** A watch `done = true` delta marks every row of that round done, MAIN and SS,
  and never changes the timing of a row that is already done, whether the delta addresses it or
  it is the partner. A `done = false` delta clears both, as now. This is a change to
  `SetEditApplier.kt:100-106` and `stamped`, and it covers queued v0.5.0 ticks too.
- **What the watch can't do yet.** Its dial, crown, swipe and overview read MAIN alone. A round
  the phone half-ticked with MAIN done looks done on the watch, and the lifter finishes it on the
  phone. A round with only SS done is still offered, and the watch tick completes it with SS's
  timing kept. Per-exercise logging on the watch is step 6.
- **Wire, Room and backup.** No changes.

## 4. Must not change

- The pinned §11 tests (`PinnedVerificationTest`) and `CardioIntervalsTest`.
- A slot's behavior across the upgrade and across a restore of an older backup: every existing
  slot keeps or remembers exactly as it does on v0.5.0, and no stored prescription or working
  value is rewritten.
- A generated plan's rotation, and the steps, durations and activity of every cardio day,
  generated or hand-built, as shown on v0.5.0.
- Run behavior from step 2: the gate, Finish idempotency, Discard, the old-watch path, and
  mid-run edits replanning the open run.
- Old backups (every format up to 13) restore, including session notes and prose cardio.
- History rows from before this change read, count and export as they do now.

## 5. PR breakdown

Each PR is shippable alone.

### PR 1. Remember or keep, and session notes in Room (Room v15, backup 14)

- `keepToPlan`, its migration and backup field; `carryForward` reads it, including the shape
  check.
- "Next time" and "Reset to plan" in the slot edit sheet; switching to Plan clears working
  values at once.
- `workout_session.note`, the DataStore move, the restore path, and the backup field.
- Tests:
  - the migration and an older-backup restore both set the flag from the prescription;
  - a kept slot without a prescription reseeds from goal math after Finish;
  - a remembered prescribed slot carries forward;
  - a remembered slot whose prescription changed shape mid-run reseeds from the new
    prescription after Finish, and so does its partner;
  - switching to Plan mid-run, then Discard, leaves the next run seeded from the plan;
  - Reset deletes both tracks and is refused while the day's run is open;
  - notes survive the move, a restore of a v13 file and a v14 round trip, and the fixup
    doesn't run after a restore;
  - deleting a session drops its note.

### PR 2. Typed cardio only (backup 15)

- The conversion rule, the fixup, the restore path, the generator, `CardioPlanner` and the
  wizard emitting blocks; `effectiveCardio` and `CardioDay` go; the ack-only snapshot `cardio`.
- Setup's cardio section goes, and the cardio block editor opens to generated plans.
- Tests:
  - the conversion gives the block `effectiveCardio` gave for each mode, with 5K on and off;
  - a plan with no recipe converts with the defaults; `NONE` gives no block; an existing block
    is kept;
  - restores of a v8 file (answers in settings) and a v13 file convert their cardio;
  - `programFor` and `exportSnapshot` called before the fixup has run return converted days;
  - `resetDayToTemplate` regenerates `C1`;
  - a watch snapshot for a cardio day has `cardio.mode == null` and the logged facts.

### PR 3. One kind of plan (no schema change)

- Rotation and `removeDay`'s suggestion, the floor, the day cap, day operations and their
  draft gates, add exercise, supersets on any slot with `setPrescription` keeping pairs aligned,
  swap by id, settings for every plan, and the `docs/PLAN.md` amendment.
- Tests:
  - rotation answers match the old ones for both origins;
  - removing a suggested generated day skips the cardio days;
  - a generated plan can add a day, a cardio block and a prescription;
  - an authored plan can pair a superset, and a set-count change on a paired prescribed slot
    mid-run keeps both tracks the same length when only SS has a done row past the new count;
  - an authored add of a standard lift seeds from goal math;
  - an authored plan's emphasis write sticks and changes its goals.

### PR 4. Supersets logged per exercise (no schema change)

- The phone's per-track tick; rest after the round; Finish on any done row; the watch-delta
  timing rule. No watch code changes, and no count changes.
- Tests:
  - ticking MAIN alone starts no rest and leaves SS undone; ticking SS then starts rest;
  - unticking one exercise leaves the other done;
  - Finish is enabled with only an SS row done;
  - Today, `DayProgress` and the receipt give the same counts as v0.5.0 for the same rows;
  - a watch tick on a round with SS done keeps SS's timing; a queued tick on a done MAIN keeps
    MAIN's timing; a watch untick clears both.

## 6. Risks and owner decisions

- **The watch logs supersets per exercise in step 6, not now.** This is a sequencing call. The
  dial is replaced in step 6, so per-exercise logging on it would be built twice. Until then the
  phone logs per exercise and the watch logs whole rounds. A round the phone half-ticked with
  MAIN done can only be finished on the phone.
- **The generated floor of 3 goes.** A generated day can be cut to one exercise, like an
  authored day.
- **Cardio preferences leave Setup.** Changing the run type on a generated plan becomes a
  per-day edit, or a new plan from the wizard. Today, changing the mode in Setup changes every
  generated cardio day at once.
- **Existing hand-built standard lifts keep their 0 lb × 10 prescriptions.** Only new adds
  change. The lifter can switch such a slot to "Last results" and edit the weights once.
- **A legacy wrist cardio timer running when the phone updates is lost.** The v0.5.0 watch
  discards a legacy timer whose snapshot loses its mode (`WearApp.kt:155`). This needs a phone
  update in the middle of a watch-timed cardio set; it is accepted, not handled.

## Review round 1 ledger

Seats: Astra (gpt-6-astra, xhigh) 5/10, CHANGES; Claude (Opus subagent) 6/10, CHANGES.

| ID | Finding | Outcome |
| --- | --- | --- |
| A1, C1, C2 | "No recipe, no cardio" was wrong; the migration ran before legacy answers reach recipes; older backups unhandled | Fixed: one rule mirroring `answersForPlan`, run as a post-settings fixup and on restore; no Room bump |
| A2, C4 | `keepToPlan` missing from the backup | Fixed: format 14 field, derived for older files |
| A3 | A remembered slot carries old-shaped actuals past a prescription shape change | Fixed: shape check in `carryForward` |
| A4 | A paired prescribed slot misaligns when its set count changes mid-run | Fixed: `setPrescription` replans SS with MAIN |
| A5, C7 | The watch reads MAIN alone in more places than named | Fixed: one round rule, every reader listed |
| A6, C6 | `isRound` is a slot predicate; the widget, `retiredRoundsDone`, history pairing and retirement were undefined | Fixed: a `:domain` round rule with retirement and legacy history rules |
| A7 | Finish needs a full round | Fixed: any done row |
| A8 | The watch copy overwrites a confirmed exercise's timing | Fixed: copy only into an undone partner |
| A9, C8 | Setup's cardio controls would be dead after PR 2 | Fixed: moved to PR 2, with the editor |
| A10 | `removeDay` could suggest a cardio day | Fixed: same rule as rotation |
| A11, C3 | Legacy watch timers at upgrade; the ack carrier's content unstated | Carrier specified; the in-flight timer loss accepted and listed in §6 |
| C5 | Switching to Plan takes effect one Finish late | Fixed: clears working values when no run is open |
| C9 | The 7-day cap counts cardio days | Fixed: counts strength days |
| C10 | Draft and Reset-to-template gates missing | Fixed: listed in PR 3 |
| C11 | Import default is speculative; cardio day `emphasisLine` unstated | Fixed: dropped; `emphasisLine` keeps its text |
| C12 | Restore still writes notes to DataStore | Fixed: restore writes the column and skips the fixup |
| C13 | Reset to template over a re-added day letter | Accepted and stated |

## Review round 2 ledger

Seats: Astra 7/10, CHANGES; Claude 7/10, CHANGES. Round 2 showed the watch half of the superset
design growing a new requirement with each fix (partner identities on SS deltas, crown, swipe,
timed-rep starts). Revision 3 drops it: the watch is unchanged until step 6, and the phone owns
per-exercise logging.

| ID | Finding | Outcome |
| --- | --- | --- |
| CN1, N4 | Retirement made rounds undone or double-counted; `retiredRoundsDone` excludes SS | Fixed: sides merge active and retired tracks; required sides are the active ones; removed slots keep the MAIN-only count |
| CN2, N4 | History rows can't be paired | Fixed: Finish stores `roundsDone`; older sessions keep the MAIN count |
| CN3, N2 | Replanning tracks separately misaligns them | Fixed: one kept-index set for both; paired cleanup at Finish |
| CN4, N7 | The copy rule broke unticks and left the addressed row's timing unprotected | Fixed: a watch tick never retimes a done row; untick clears both |
| N5, N6 | Watch navigation, timed-rep starts and SS delta identity | Removed: the watch sends no SS deltas and its code doesn't change |
| CN7 | The v0.5.0 watch can't finish a phone half-ticked round | Stated in §3 and §6 |
| N1, CN5 | Readers and exports could run before the cardio fixup; `NONE` days kept prose | Fixed: program flows and export wait; restore converts in its transaction; `NONE` clears |
| N3 | Switching to Plan mid-run, then Discard | Fixed: the switch clears working values at once |
| CN6 | The shape check would delete deliberately empty tracks | Fixed: prescribed MAIN tracks with planned rows only |

## Review round 3 ledger

Seats: Astra 7/10, CHANGES; Claude 7/10, CHANGES. Every open finding was about counting
superset rounds: a plain swap's done and total (CR1), a negative snapshot adjustment (R1), a
stored count going stale when history is edited in the Log (R2), and the backup path for that
count (R3, CR2). Each revision of the round rule opened a new case. Revision 4 removes the rule:
counts stay MAIN-based everywhere, as on v0.5.0, and the per-exercise change keeps only the
tick, rest, Finish and the watch-delta timing rule.

| ID | Finding | Outcome |
| --- | --- | --- |
| CR1, R1, CN1, N4 | The round rule changed plain-swap counts and broke snapshot totals | Removed: no count changes |
| R2, R3, CR2, CN2 | `roundsDone` goes stale on history edits and is missing from the backup | Removed: no stored count |
| CR3 | PR 1 bullet contradicted §3 on switching to Plan mid-run | Fixed |
| CR4 | `programFlow` didn't wait for the cardio fixup | Fixed: the fixup runs inside the settings gate |
