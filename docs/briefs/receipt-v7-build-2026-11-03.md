# Receipt v7: code design (#416)

Build design for the owner-approved mockup `docs/briefs/receipt-v7-2026-11-03/mockup.html`. The
visual and behavioral decisions are final and are not reopened here; this document says how the
code is shaped so a builder who cannot compile or run tests can build it exactly. Line numbers are
as of `e1887de2` (main, after #446). Reads: CLAUDE.md, DESIGN.md, `personal-bests-2026-11-02.md`
(steps 1 and 3 shipped in #402 and #444), `receipt-v4-2026-11-02/README.md`,
`set-structures-2026-11-02.md`, issues #416, #414, #445, #447.

Project rules that bind every PR: no INTERNET; `:domain` stays pure Kotlin; never read DataStore
inside a Room `withTransaction`; test gates never await inside a Room transaction; American English;
M3 components first, each bespoke piece stating in KDoc what M3 lacks; a Parcelable kept in
`SavedStateHandle` never gains, drops or reorders a field, so new state gets a new key.

## 0. Decisions in one screen

- One new frozen receipt type, `WorkoutReceipt`, under a new saved-state key `day_workout_receipt`.
  It carries structured rows (numbers, not copy); the screen formats them. The old `SessionReceipt`,
  `CascadeLift`, `CardioReceiptRecord` and `SavedCardioBest` classes stay in the code with their
  layouts frozen, because a bundle saved by the previous release is unparceled class-by-class at
  restore; the finisher removes the old key at startup and never reads it.
- Rows are built by one pure object, `ReceiptBuilder` (`:app`, `ui/day/receipt/`, plain JUnit, no
  Android imports beyond `@Parcelize` on the types). Record math stays in `:domain`
  (`PersonalBests`, `CardioBests`); `PersonalBests` gains `prKinds` so a row can carry two kinds.
- The Finish state machine is a saved `FinishAttempt` (finishing or failed) plus the receipt
  (shown). It lives in a new `SessionFinisher` class constructed by `DayViewModel` the way
  `DayEditor` is, which is the receipt's move out of the ViewModel that #416 and #414 ask for.
  Finish runs under `mutationLock` in its own `try`/`catch`, not through the bare `mutate`.
- The strength baseline is read with the current run excluded
  (`personalRecords(ids, excludingRunId)`), so a retry after the commit landed computes the same
  records as the first try, the shape #444 already uses for cardio.
- The screen is `Scaffold` + `TopAppBar` + `ListItem` rows + `Button`/`OutlinedButton` +
  `Snackbar`. Three bespoke pieces, each with a what-M3-lacks KDoc: the expandable row, the PR
  label, the ghost bar.
- Formatting follows the app's single formatters, so three mockup details change: holds read
  "75s, 70s" (`SecondsStepper` switches to m:ss at 90 s), added weight reads "25 × 8" like the
  workout screen (no "+"), cardio distance reads "5,210 m" as #444 pinned (km is DESIGN.md open
  question 12). Owner is told in §5; none changes a decision in the issue.

## 1. Data model

### 1.1 Frozen types

All in `app/src/main/kotlin/cloud/trotter/log/strength/ui/day/receipt/WorkoutReceipt.kt`. Every
class is `@Parcelize data class ... : Parcelable`. Enums (`WeightUnit`, `TrackingType`, `PrKind`,
`CardioActivity`, `CardioRole`, `CardioSetStatus`, `ReceiptRow.Shape`) parcel by name through
Parcelize; no custom parcelers. Weights are stored in lb and converted at display through the frozen
`unit`, as `SetFormatter.summary` does everywhere else.

```kotlin
@Parcelize
data class WorkoutReceipt(
    /** workout_session.id, or cardio_session.id when [cardio]. The live handle for share and note. */
    val sessionId: Long,
    val cardio: Boolean,
    val dayId: String,
    /** The completed day's accent, even after the rotation advances. */
    val dayIndex: Int,
    val dayTitle: String,
    val planName: String,
    val completedAt: Long,
    val minutes: Int?,
    val unit: WeightUnit,
    /** False when the standing bests could not be read; the screen says so and no row is a record. */
    val recordsChecked: Boolean,
    /** Two or more plans on Today and every one has a session completed on the civil day of [completedAt]. */
    val allPlansDone: Boolean,
    val groups: List<ReceiptGroup>,          // strength; empty for cardio
    val activity: CardioActivity?,           // cardio only
    val cardioRows: List<CardioReceiptRow>,  // cardio; empty for strength
    val note: String = "",
) : Parcelable

/** One lift, or one superset of two lifts. [rounds] is null for a single lift. */
@Parcelize
data class ReceiptGroup(val rounds: Int?, val rows: List<ReceiptRow>) : Parcelable

@Parcelize
data class ReceiptRow(
    val name: String,
    val tracking: TrackingType,
    val shape: Shape,
    /** Every done set, in logged order. Empty only for [Shape.SKIPPED]. */
    val sets: List<ReceiptSet>,
    /** Collapsed value lines, heaviest first. Indices into [sets]. One or two lines; see §1.5. */
    val lines: List<ValueLine>,
    /** When three or more weights were used: [lines] holds the heaviest set only, this is the rest. */
    val lighterSets: Int,
    /** The plan, shown as the last gray line of an open row. Null when the row never opens. */
    val planned: PlannedSets?,
    val record: RecordMark?,
) : Parcelable {
    enum class Shape {
        /** Ramped structure, a TOP set logged. Never opens. */
        TOP_SET,
        /** Ramped structure, no TOP set logged, something else was. Never opens. */
        HEAVIEST_NO_TOP,
        /** Straight sets, reps or holds. Opens. */
        STRAIGHT,
        /** No set logged, whether the lift was skipped in the run or never reached. */
        SKIPPED,
    }
}

@Parcelize data class ReceiptSet(val weightLb: Double, val reps: Int, val seconds: Int) : Parcelable

/** [weightLb] is null for REPS and TIMED rows (one line, every set). */
@Parcelize data class ValueLine(val weightLb: Double?, val setIndices: List<Int>) : Parcelable

@Parcelize data class PlannedSets(val count: Int, val weightLb: Double, val reps: Int, val seconds: Int) : Parcelable

/** [kind] is the label; [alsoKind] is spoken after it and never drawn. [setIndex] indexes [ReceiptRow.sets]. */
@Parcelize data class RecordMark(val kind: PrKind, val alsoKind: PrKind?, val setIndex: Int, val previous: ReceiptSet) : Parcelable

@Parcelize
data class CardioReceiptRow(
    val role: CardioRole,                 // WARMUP, WORK or COOLDOWN; RECOVERY folds into recoverySeconds
    val count: Int,                       // WORK intervals in this row; 1 for warm-up and cool-down
    val endSeconds: Int?,                 // the planned Measure.Time, WORK rows only
    val endMetres: Int?,                  // the planned Measure.Distance, WORK rows only
    val intervals: List<CardioInterval>,  // results in order
    val recoverySeconds: List<Int>,       // elapsed of the RECOVERY sets that followed this row's intervals
    val record: CardioRecordMark?,
) : Parcelable

@Parcelize data class CardioInterval(val status: CardioSetStatus, val elapsedSeconds: Int, val metres: Int?) : Parcelable

/** [previousSeconds] for FASTER, [previousMetres] for FARTHER. */
@Parcelize data class CardioRecordMark(val kind: PrKind, val intervalIndex: Int, val previousSeconds: Int?, val previousMetres: Int?) : Parcelable

@Parcelize
data class FinishAttempt(
    val runId: String,
    val planId: Long,
    val dayId: String,
    val dayIndex: Int,
    val dayTitle: String,
    val planName: String,
    val startedAt: Long?,
    val tappedAt: Long,
    val failed: Boolean = false,
) : Parcelable
```

Why rows carry numbers and not strings: the spoken sentence ("35 pounds: 10 and 10 reps") and the
visible line ("35 × 10, 10") are both derived from the same frozen numbers in the screen, in the
user's language, from `strings.xml`. Freezing copy would freeze one language and duplicate the
formatter.

### 1.2 Saved-state keys and the legacy bundle

| Key | Type | Written by | Cleared by |
|---|---|---|---|
| `day_finish` (`KEY_FINISH`) | `FinishAttempt?` | `SessionFinisher.start`, `retry` | success (receipt written), `backToWorkout`, the early returns in §2.3 |
| `day_workout_receipt` (`KEY_WORKOUT_RECEIPT`) | `WorkoutReceipt?` | finish success, `showCardio`, `setNote` | `dismissReceipt` |
| `day_cardio_standing_bests:<runId>` | `ArrayList<SavedCardioBest>` | unchanged (#444) | now cleared when the cardio receipt is shown, §2.7 |
| `day_session_receipt` (legacy) | `SessionReceipt?` | nobody | `SessionFinisher.init`, once |
| `day_cardio_receipt_records:<runId>` (legacy) | `ArrayList<CardioReceiptRecord>` | nobody | `SessionFinisher.init`, every key with the prefix |
| `day_deferred_cardio_receipt` | `CardioRun?` | unchanged | unchanged |

What happens to a bundle saved by the previous release: `SavedStateHandle.createHandle` reads every
key of the restored bundle, and `Bundle` unparcels each value through the class named in the
parcel. If `cloud.trotter.log.strength.ui.day.SessionReceipt` (or `CascadeLift`, which it
contains, or `CardioReceiptRecord`, or `SavedCardioBest`) were gone or reshaped, restoring a task
left open on the receipt across the update would throw `BadParcelableException` in the ViewModel
constructor. So:

- `SessionReceipt`, `CascadeLift`, `CardioReceiptRecord`, `SavedCardioBest` keep their package,
  name, fields and field order. `SessionReceipt` and `CascadeLift` move to
  `ui/day/LegacySessionReceipt.kt` with one KDoc: "Layout frozen. Exists only so a bundle written
  before receipt v7 can be read at restore. Removed by `SessionFinisher.init`. Delete after two
  releases that no longer write it." `SessionReceiptBuilder` and `CascadeCeremonyBuilder` are
  deleted (`CascadeCeremony.kt` keeps only the `CascadeLift` class, or `CascadeLift` moves into
  the legacy file; either way the class is unchanged). `CardioReceiptRecord.kt` keeps both classes
  and loses `CardioRun.receiptRecords`.
- `SessionFinisher.init` runs
  `savedState.remove<SessionReceipt>("day_session_receipt")` and removes every key starting with
  `day_cardio_receipt_records:`. A lifter who updates with a v6 receipt open in recents lands on the
  workout or Today with the session saved; the receipt view is the only thing lost.
- `MainCardioParcelRestoreTest` stays as it is. It hand-writes the legacy layout and proves the
  class still reads it.

### 1.3 Where rows are built

`app/src/main/kotlin/cloud/trotter/log/strength/ui/day/receipt/ReceiptBuilder.kt`, a pure
`object` with no Compose or Android imports, tested by `ReceiptBuilderTest` (plain JUnit, no
Robolectric), the same shape as `DayScreenBuilder` and `JournalBuilder`. Two entry points:

```kotlin
object ReceiptBuilder {
    fun strength(
        tracks: List<RunTrack>,                  // OpenRun.tracks, read before finishRun
        slots: List<ProgramSlot>,                // plans.slotsFor(planId, dayId).first(): structure and superset per programExerciseId
        names: (String) -> String,               // exerciseId -> catalog name, falling back to the id
        standing: Map<String, PersonalRecord>,   // historyRepository.personalRecords(ids, excludingRunId); empty when not checked
    ): List<ReceiptGroup>

    fun cardio(
        activity: CardioActivity,
        results: List<CardioSetResult>,                     // CardioRun.resultSets
        standing: Map<CardioRecordKey, CardioSetResult>,    // CardioBests.bests(activity, savedBests.map { it.toDomain() }); empty when not checked
    ): List<CardioReceiptRow>

    fun allPlansDone(
        plans: List<PlanEntity>, sessions: List<WorkoutSessionEntity>, cardio: List<CardioSessionEntity>,
        completedAt: Long, zone: ZoneId,
    ): Boolean
}
```

`RunTrack` (`data/RunModels.kt:40`) has `programExerciseId`, `position`, `slot`, `exerciseId`,
`retired`, `dropped`, `planned`, `sets`; it is the one input that carries both the plan rows and
the actuals. `DayTracks` does not carry `planned`, so the finisher reads `repo.openRunFlow.first()`
instead of `dayTracksFlow`.

### 1.4 Strength rows

Walk `slots` in `position` order. For each slot:

1. Tracks of this `programExerciseId` with `slot == Slot.MAIN`, ordered: retired tracks that hold a
   done set (in `position` order) first, then the live (non-retired) track. A retired track with no
   done set is dropped (a plan edit replaced it before anything happened). A live track with no done
   set, or a slot whose only MAIN track is `dropped` (the lift was skipped in the run,
   `OpenRun.isLiftSkipped`), becomes one `SKIPPED` row named for the track's `exerciseId`.
2. Superset: when the slot has an `SS` track that is live or holds a done set, the slot is one
   `ReceiptGroup(rounds = doneCount of the live MAIN track, rows = [main row, partner row])`. The
   screen draws gutters "A1" and "A2" from the position in `rows`. A dropped partner with nothing
   done makes the slot a plain lift. A retired MAIN track with done work inside a superset slot gets
   its own single-row group before the superset group.
3. Otherwise each row is its own `ReceiptGroup(rounds = null, rows = [row])`.
4. Tracks whose `programExerciseId` matches no slot (slot removed mid-run) are appended after the
   slots in `position` order with `structure = STRAIGHT` and no group.

One row from one track (`row(track, structure, name, standing)`):

- `done = track.sets.filter { it.done }`. Empty → `Shape.SKIPPED`, `sets = []`, `lines = []`,
  `planned = null`, `record = null`, `tracking` from the first planned row's values or WEIGHTED.
- `tracking = PersonalBests.tracking(best counting set)` when one exists, else
  `PersonalBests.tracking(done.first())`. Values decide, not the catalog, same as the records rule.
  Sets of another tracking type than the row's are kept in `sets` (they were done) but never form a
  line or a record.
- `sets = done.map { ReceiptSet(it.weightLb, it.reps, it.seconds) }`, logged order.
- Structure from the slot's `ProgramExercise.structure` (`SetStructure`). Ramped
  (`hasTopSet`): if any done set has `kind == TOP` → `TOP_SET`, `lines = [ValueLine(topWeight,
  [index of the top set])]` where the top set is the TOP set that ranks highest under
  `PersonalBests.compare` (two TOP rows after a ramp edit). Else → `HEAVIEST_NO_TOP`, the heaviest
  set is the done set (RAMP included) that ranks highest by `(weightLb, reps)`; `lines` holds it
  alone. Both shapes: `lighterSets = 0`, `planned = null`.
- `STRAIGHT` (structure STRAIGHT, or any non-WEIGHTED row):
  - WEIGHTED: group `sets` by `weightLb`, weights descending, each group's indices in logged order.
    One or two weights → one `ValueLine` per weight, `lighterSets = 0`. Three or more → one line
    with the single heaviest set (max by `(weightLb, reps)`), `lighterSets = sets.size - 1`.
  - REPS and TIMED: one `ValueLine(weightLb = null, setIndices = all of the row's tracking type)`.
  - `planned`: from `track.planned`, `PlannedSets(count = planned.size, weightLb, reps, seconds)`
    of the first planned row (straight seeds are uniform); null when `planned` is empty.
- Record: `best = PersonalBests.best(done as LoggedSet)` (RAMP never counts, TOP, BACKOFF, WORK,
  EXTRA do, a 0/0/0 set never does). `standingSet = standing[exerciseId]?.let {
  LoggedSet(it.weightLb, it.reps, SetKind.WORK, done = true, seconds = it.seconds) }`.
  `kinds = PersonalBests.prKinds(best, standingSet)` (§1.6). Non-empty →
  `RecordMark(kinds[0], kinds.getOrNull(1), setIndex = done.indexOf(best), previous =
  ReceiptSet(standing))`. `recordsChecked == false` means `standing` is empty, so no row can be a
  record; the gray line says why.

Tracking types in practice: weighted lifts and added-weight lifts (`weighted_pullup`,
`weighted_dip`, catalog `Flat` goals) are WEIGHTED and format as "25 × 8"; bodyweight is REPS
("12"); holds are TIMED ("75s", "1:30", "1:30 +25" via `SetFormatter.summary(TIMED, ...)`).

### 1.5 The value line

Visible text is produced in the screen from the frozen numbers, always through the existing
formatters: weights `WeightStepper.format(unit.fromLb(lb))`, holds `SecondsStepper.format(seconds)`
with added load through `SetFormatter.summary(TIMED, ...)`, reps as the integer. No unit suffix on
the row, as on the workout screen. The table below is the contract, including the mockup's edge
cases; the spoken column is §3.6.

| Case | Row data | Collapsed line(s) |
|---|---|---|
| Ramped, TOP logged, record | `TOP_SET`, record on the top set | "Top set" `245 × 5` (emphasis) then the old best `240 × 5` struck through in titleMedium; PR label |
| Ramped, TOP logged, no record | `TOP_SET` | "Top set" `245 × 5` plain |
| Ramped, TOP never logged | `HEAVIEST_NO_TOP` | "Heaviest" `210 × 3` then "top set not logged" in gray |
| Straight, one weight | `STRAIGHT`, 1 line | `35 × 10, 10, 8` |
| Two weights | 2 lines | `35 × 10, 10` / `30 × 8`; the record rep (`record.setIndex`) is emphasis-colored and underlined (2 dp, 4 dp offset) |
| Three or more weights | 1 line, `lighterSets = 3` | `25 × 12` then "and 3 lighter sets" in gray |
| Reps only | REPS | `12, 10, 9` |
| Added weight | WEIGHTED | `25 × 8, 8, 6` (the exercise name already says "Weighted") |
| Holds | TIMED | `75s, 70s`; `1:30 +25` for a loaded hold |
| Two records on one row | `record.kind` + `alsoKind` | label shows `kind` only; both spoken |
| Skipped | `SKIPPED` | name in onSurfaceVariant, "Skipped" on the right (bodyMedium), no chevron, 56 dp row |
| Fewer sets than planned | `STRAIGHT` | nothing extra; open row ends with "Planned 3 × 10 at 35" |
| Mixed tracking in one row (legacy data) | sets of the other type only in `sets` | not on the line; shown as plain set lines when open |
| Cardio distance left blank | `CardioInterval.metres == null` | "Distance not entered"; never "0 m"; no FARTHER record possible |
| Units | `WorkoutReceipt.unit` | none on the row; "pounds"/"kilograms" in every spoken line |

Open row (`STRAIGHT` only) lists every set: gutter ordinal (labelLarge, onSurfaceVariant), the set
via `SetFormatter.summary(tracking, w, r, s, unit, spaced = true)` in titleMedium (REPS drops the
leading "× ": show the bare count), the record set in emphasis color with "PR, up from 35 × 9"
(labelMedium, emphasis) after it, and the plan as the last gray line: WEIGHTED "Planned 3 × 10 at
35", REPS "Planned 3 × 10", TIMED "Planned 2 × 60s" (the hold formatted by `SecondsStepper`).

### 1.6 Records on a row: `PersonalBests.prKinds`

`domain/records/PersonalBests.kt` gains

```kotlin
/** Every dimension the candidate improved on, label first. Empty when it is not a PR. */
fun prKinds(candidate: LoggedSet, standing: LoggedSet?): List<PrKind> {
    if (!counts(candidate) || standing == null || tracking(candidate) != tracking(standing) ||
        compare(candidate, standing) <= 0) return emptyList()
    return when (tracking(candidate)) {
        WEIGHTED -> listOfNotNull(WEIGHT.takeIf { candidate.weightLb > standing.weightLb }, REPS.takeIf { candidate.reps > standing.reps })
        REPS -> listOf(REPS)
        TIMED -> listOfNotNull(HOLD.takeIf { candidate.seconds > standing.seconds }, WEIGHT.takeIf { candidate.weightLb > standing.weightLb })
    }
}
fun prKind(candidate: LoggedSet, standing: LoggedSet?): PrKind? = prKinds(candidate, standing).firstOrNull()
```

The first element equals today's `prKind` in every case (245×6 over 240×5 is WEIGHT first; 235×5 over
235×3 is REPS alone; 1:30 +25 over 1:15 is HOLD first), so `JournalBuilder.kt:109` is untouched and
`PersonalBestsTest` keeps passing. The label is the comparator's primary dimension; the mockup's
"first of Weight, Rep, Hold" agrees for weighted lifts and the hold case follows the comparator
(a longer loaded hold is a Hold PR that also mentions the weight).

### 1.7 Cardio rows

Walk `results` (one `CardioSetResult` per executed set; specs the lifter never reached are not
rows) in order:

- Consecutive `WARMUP` results → one row `role = WARMUP, count = 1, intervals = each result`. The
  collapsed value is the summed `elapsedSeconds` as `ClockText.minutesSeconds`; all skipped → the
  SKIPPED look ("Skipped" on the right). Same for `COOLDOWN`.
- `WORK` results → one row per distinct `spec.end`, in first-appearance order, `count =
  intervals.size`, `endSeconds`/`endMetres` from the end. Name: `count == 1` → the measure
  ("30:00" via `ClockText`, "5 km"-style is not used, distance via `R.string.cardio_metres` "%d m");
  `count > 1` → "6 × 400 m" (`receipt_cardio_work_count`).
- `RECOVERY` results append `elapsedSeconds` to the most recent WORK row's `recoverySeconds`; a
  recovery before any work is dropped.
- Records: `CardioBests.records(activity, results, standing)`; for each, find the WORK row whose
  end equals `record.key.end`, `intervalIndex` = first interval equal to `record.best` by
  `(status, elapsedSeconds, metres)`, previous from `record.previous`.

Collapsed WORK line: Distance end → label "Fastest" (`count > 1`) or "Time" (`count == 1`) and the
smallest `elapsedSeconds` among intervals that `CardioBests.counts` (FINISHED); none finished →
"No interval finished" in gray. Time end → label "Farthest" or "Distance" and the largest `metres`
among finished intervals with a distance, as "5,210 m" (`String.format(Locale.US, "%,d m")`, the
#444 formatter, moved from `CardioReceiptRecord.kt` to the screen); none → "Distance not entered".
A record puts the value in emphasis, the old best struck after it, and the label under the chevron.

A WORK row opens when `count > 1`: one line per interval, ordinal, then "1:41" (Distance end) or
"1,250 m" / "not entered" (Time end), SHORTENED appends ", shortened", SKIPPED shows "Skipped"; the
record interval is emphasis-colored with "PR, up from 1:42". Last gray line: "Recovery 1:30 each"
when every `recoverySeconds` is equal, else "Recovery 1:30, 2:00, 1:30"; omitted when empty.

### 1.8 Header facts

Strength: `dayTitle` from `program.days.first { it.id == dayId }.title`, `planName` from
`plans.plansFlow.first().first { it.id == planId }.name`, `completedAt` and `startedAt` from
`historyRepository.session(sessionId)`, `minutes = ((completedAt - startedAt) / 60_000.0)
.roundToInt().coerceAtLeast(1)` as today, null without a start. Cardio: `completedAt` from
`cardioSession(sessionId)`, `minutes = (seconds / 60.0).roundToInt().coerceAtLeast(1)`.

`allPlansDone`: `plans.filter { !it.archived && it.onToday }` has two or more entries and each
has a `WorkoutSessionEntity` or `CardioSessionEntity` with its `planId` whose `completedAt` falls
on the same `LocalDate` as the receipt's `completedAt` in `zone`. One plan on Today → false (the
owner's rule: the check line only with 2+ plans all done today).

## 2. The Finish state machine

### 2.1 States

| State | Saved state | Screen |
|---|---|---|
| finishing | `day_finish = FinishAttempt(failed = false)`, `day_workout_receipt = null` | header + exercise names, ghost bars after 260 ms, Done and Share disabled, Back swallowed, "Finishing workout" announced |
| failed | `FinishAttempt(failed = true)` | header without "done.", error message, Back to workout / Try again |
| shown | `day_finish = null`, `day_workout_receipt != null` | the receipt; Back = Done |
| none | both null | the workout |

Both keys are `SavedStateHandle` entries, so rotation and process death keep them. On restore with
`failed == false` the finisher re-runs the attempt (§2.4); the attempt is idempotent.

### 2.2 `SessionFinisher`

`app/src/main/kotlin/cloud/trotter/log/strength/ui/day/SessionFinisher.kt`, `internal class`,
constructed in `DayViewModel.init` next to `editor` (DayViewModel.kt:504-521) with the same style
of explicit dependencies:

```kotlin
internal class SessionFinisher(
    private val repo: TrackerRepository,
    private val historyRepository: HistoryRepository,
    private val plans: PlanRepository,
    private val catalog: CatalogRepository,
    private val settings: SettingsStore,
    private val sessionPublisher: SessionPublisher,
    private val shareCardService: ShareCardService,
    private val savedState: SavedStateHandle,
    private val scope: CoroutineScope,
    private val mutationLock: Mutex,
    private val clock: java.time.Clock,
    /** The pager's reaction to a committed day: phoneRest.skip(), clearTimedRep, view selection, pin, page, closeSheet (today's DayViewModel.kt:1474-1480). */
    private val afterCommit: suspend (dayId: String, nextDayId: String?) -> Unit,
    /** `executionActive`: a cardio run or timed rep owns the screen, so Done cannot leave yet. */
    private val executionActive: () -> Boolean,
) {
    val attempt: StateFlow<FinishAttempt?>        // savedState.getStateFlow(KEY_FINISH, null)
    val receipt: StateFlow<WorkoutReceipt?>       // savedState.getStateFlow(KEY_WORKOUT_RECEIPT, null)
    val noteSave: StateFlow<NoteSave?>            // bare MutableStateFlow; see §2.6
    val pendingShare: StateFlow<Intent?>          // moved from DayViewModel unchanged

    fun start(runId: String, planId: Long, dayId: String, dayIndex: Int, dayTitle: String)
    fun retry()
    fun backToWorkout()
    fun dismissReceipt(): Boolean
    fun setNote(text: String)
    fun share(); fun shareHandled()
    suspend fun showCardio(run: CardioRun, standing: Map<CardioRecordKey, CardioSetResult>?, session: CardioSessionEntity?)
}
```

`DayViewModel` keeps `completeDay()` as the entry: the synchronous checks at DayViewModel.kt:1453-1456
stay (current day, `runOpen`, `planId`, `runId`), then `finisher.start(runId, planId, day,
Program.accentIndex(day), dayState.value.dayTitle)`. `sessionReceipt`, `dismissSessionReceipt`,
`setReceiptNote`, `shareSession`, `shareHandled`, `pendingShare`, `sessionCardioRecords`,
`clearReceiptRecords`, `cardioRecordsKey` leave `DayViewModel`; `DayRoute` reads
`viewModel.finisher.receipt`, `.attempt`, `.noteSave`, `.pendingShare` and calls
`viewModel.finisher::retry`, `::backToWorkout`, `::setNote`, `::share`, `::shareHandled`, and
`if (viewModel.finisher.dismissReceipt()) onFinishSession()`, mirroring `viewModel.editor::...`.

`mutate` (DayViewModel.kt:1634) is not used by the finisher. Finish launches its own job:

```kotlin
private var job: Job? = null
private fun launch(block: suspend () -> Unit) {
    if (job?.isActive == true) return                // "Try again is ignored while a try is running"
    job = scope.launch {
        try { mutationLock.withLock { block() } }
        catch (c: CancellationException) { throw c }
        catch (_: Exception) { attempt.value?.let { savedState[KEY_FINISH] = it.copy(failed = true) } }
    }
}
```

The bare `mutate` launch would hand the exception to the default handler and crash; this catch is
how failure is detected, the same shape as `maybeLogCardio` (DayViewModel.kt:820-824). Reads of
DataStore (`settings.unitFlow.first()`) happen before `repo.finishRun`, never inside its
transaction; `finishRun` is the only transaction and the finisher never calls it from inside
another.

### 2.3 The finish body, in order

`start(...)`: launch →

1. `val run = repo.openRunFlow.first()?.takeIf { it.id == runId }`. Null → if
   `historyRepository` has a session for this run (new `sessionByRunId` pass-through to
   `SessionDao.sessionByRunId`, SessionDao.kt:81) the day already committed and the receipt is lost
   (as today; §5); either way `savedState[KEY_FINISH] = null; return`.
2. `val planName = plans.plansFlow.first().firstOrNull { it.id == planId }?.name.orEmpty()`.
   Write `savedState[KEY_FINISH] = FinishAttempt(runId, planId, dayId, dayIndex, dayTitle,
   planName, run.startedAt, tappedAt = clock.millis())`. From here the screen shows "finishing".
   (On `retry` and on the restore re-run the attempt already exists; steps 1-2 only re-read `run`.)
3. `if (run.tracks.none { t -> t.sets.any { it.done } }) { savedState[KEY_FINISH] = null; return }`
   (today's guard at DayViewModel.kt:1466-1468, now over `OpenRun`).
4. Reads: `slots = plans.slotsFor(planId, dayId).first()`, `catalog = catalog.catalogFlow.first()`,
   `unit = settings.unitFlow.first()`, `program = plans.programFor(planId).first()`.
5. Baseline, degradable: `val standing = try { historyRepository.personalRecords(ids,
   excludingRunId = runId) } catch (c: CancellationException) { throw c } catch (_: Exception) {
   null }` where `ids = run.tracks.map { it.exerciseId }.distinct()`.
   `recordsChecked = standing != null`.
6. `groups = ReceiptBuilder.strength(run.tracks, slots, { catalog.find(it)?.name ?: it },
   standing.orEmpty())`. Everything the receipt needs except the session row now exists in locals.
7. `val sessionId = repo.finishRun(runId) ?: run { savedState[KEY_FINISH] = null; return }`.
8. `val nextDayId = plans.plansFlow.first().firstOrNull { it.id == planId }?.suggestedDayId`;
   `afterCommit(dayId, nextDayId)`; `closeUndoWindow` is part of `afterCommit` (today's
   `closeUndoWindow()` at :1461 ran before the commit; after is equivalent, the run is closed).
9. `val session = historyRepository.session(sessionId)`;
   `allPlansDone = ReceiptBuilder.allPlansDone(plans.plansFlow.first(),
   historyRepository.sessionsFlow.first(), historyRepository.cardioSessionsFlow.first(),
   completedAt, ZoneId.systemDefault())`.
10. `savedState[KEY_WORKOUT_RECEIPT] = WorkoutReceipt(sessionId, cardio = false, dayId, dayIndex,
    dayTitle, planName, completedAt = session?.completedAt ?: clock.millis(), minutes, unit,
    recordsChecked, allPlansDone, groups, activity = null, cardioRows = emptyList())`;
    `savedState[KEY_FINISH] = null`; `scope.launch { sessionPublisher.publish(sessionId) }`.

Steps 1-6 can fail without touching the database; a failure there leaves the day open and the plan
where it was. Step 7 is the commit. A failure in 8-10 leaves a committed day with no receipt; the
catch marks the attempt failed and Try again reaches step 1's "session exists" branch, which clears
the attempt. This is the window the personal-bests brief already accepts; it is three primary-key
reads wide.

### 2.4 Failed, Try again, Back, restore

- Failed: `attempt.failed == true`. The screen shows the header (title without "done."), the
  error line, `OutlinedButton` "Back to workout", `Button` "Try again". System back and
  predictive back call `backToWorkout`.
- `retry()`: `attempt.value?.takeIf { it.failed } ?: return`; write `copy(failed = false)`; `launch`
  the body from step 1. Ignored while `job` is active.
- `backToWorkout()`: `savedState[KEY_FINISH] = null`. Nothing else was touched (the view selection
  and selected page are only cleared in `afterCommit`), so the pager shows the page it was on,
  which after a Finish tap is the last set or the all-logged page.
- Restore: `init { attempt.value?.takeIf { !it.failed }?.let { launch(body) } }` after the legacy
  key removal. The lifter asked for Finish and the process died; resuming is the honest outcome,
  and the body is idempotent: `finishRun` returns the existing session id for a closed run
  (TrackerRepository.kt:1307), and the baseline excludes the run by id, so a resume that finds the
  run still open produces the same receipt the first try would have.
- During finishing the screen's `BackHandler(enabled = true) {}` swallows back; Done is a disabled
  `Button`; Share is a disabled `IconButton`.

### 2.5 Records not checked

Only the baseline read degrades (step 5, and `standingBestsFor` for cardio in §2.7). Everything
else failing fails the finish. With `recordsChecked == false` the receipt renders normally with one
gray line under the header, "Records weren't checked for this workout.", and no row carries a
record because the builder saw an empty baseline. Zero records never appears as a result of a
failed read: the line is the only signal and it is always present in that case.

### 2.6 Note not saved

```kotlin
sealed interface NoteSave { data class Saved(val text: String) : NoteSave; data class Failed(val text: String) : NoteSave }
```

`setNote(text)`: `val id = receipt.value?.sessionId ?: return; val clean = text.trim().take(120)`;
launch (plain `scope.launch`, no lock: `setSessionNote` is one UPDATE) → try
`historyRepository.setSessionNote(id, clean)`, then `receipt.value?.takeIf { it.sessionId == id
}?.let { savedState[KEY_WORKOUT_RECEIPT] = it.copy(note = clean) }`, `_noteSave.value =
Saved(clean)`; catch → `_noteSave.value = Failed(clean)`. `noteSave` is a bare `MutableStateFlow`:
it survives rotation (the ViewModel does) and not process death, which is fine because the sheet
and its text are `rememberSaveable` and the snackbar is a courtesy; after process death the sheet
reopens with the text and the lifter taps Save again. The screen clears it (`noteSaveHandled()`)
when the sheet closes.

`NoteSheet` (`ui/components/NoteSheet.kt`) gains one optional parameter, `save: NoteSave? = null`.
With the default the behavior is unchanged (Log and exercise notes). When the caller passes a
`save` flow value the sheet owns closing: Save calls `onSave(text)` only; `LaunchedEffect(save)`:
`Saved` → `onDismiss()`; `Failed` → `snackbarHost.showSnackbar("Couldn't save the note.",
actionLabel = "Try again", duration = Indefinite)`, result `ActionPerformed` → `onSave(text)`
again. The `SnackbarHost` sits as the first child of the sheet's `Column`, above the field, inside
the `ModalBottomSheet`: M3's modal sheet is its own window, so a host in the screen's `Scaffold`
would be behind the scrim and untappable. This is the one place the build places the snackbar
inside the sheet rather than above it. It disappears with the sheet.

### 2.7 Cardio path changes (DayViewModel)

`finishCardioReceipt` (DayViewModel.kt:871-885) no longer computes records or removes the bests
key; it only sets `KEY_DEFERRED_CARDIO_RECEIPT` when none is pending (and clears the bests key of a
run it drops). `showDeferredCardioReceipt` (:890-911):

```kotlin
val standing = try { standingBestsFor(run) } catch (c: CancellationException) { throw c } catch (_: Exception) { null }
val session = historyRepository.cardioSession(sessionId)
finisher.showCardio(run, standing?.let { CardioBests.bests(run.block.activity, it.map(SavedCardioBest::toDomain)) }, session)
savedState.remove<ArrayList<SavedCardioBest>>(cardioBestsKey(run))
```

`showCardio` builds `WorkoutReceipt(sessionId, cardio = true, dayId, dayIndex, dayTitle, planName
= run.planName, completedAt = session?.completedAt ?: clock.millis(), minutes from
`session.seconds`, unit (read, unused by cardio rows but frozen for the header contract),
recordsChecked = standing != null, allPlansDone, groups = emptyList(), activity, cardioRows =
ReceiptBuilder.cardio(activity, run.resultSets, standing.orEmpty()))` and writes it under
`KEY_WORKOUT_RECEIPT`. `dayTitle` comes from `plans.programFor(run.planId).first().days
.firstOrNull { it.id == run.dayId }?.title ?: run.dayId`. `dismissReceipt()` keeps today's guard:
`if (executionActive() || receipt.value == null) return false`. The `cardio: CardioRun` field is
not carried on the new receipt; nothing reads it after build.

## 3. Compose structure

### 3.1 Files

| File | Holds |
|---|---|
| `ui/day/receipt/WorkoutReceipt.kt` | the frozen types (§1.1) |
| `ui/day/receipt/ReceiptBuilder.kt` | the pure builder (§1.3-1.8) |
| `ui/day/receipt/ReceiptText.kt` | `@Composable` formatting: visible strings and spoken sentences from a row (§3.6) |
| `ui/day/receipt/ReceiptRowItem.kt` | `ExpandableReceiptRow`, `SkippedReceiptRow`, `SupersetGroup`, `PrLabel`, `GhostBar`, `GhostRow` |
| `ui/day/SessionReceiptScreen.kt` | rewritten: `SessionReceiptScreen(receipt, ...)` and the shared `ReceiptScaffold` |
| `ui/day/FinishScreens.kt` | `FinishingScreen(attempt, state)`, `FinishFailedScreen(attempt, onRetry, onBack)` |
| `ui/day/SessionFinisher.kt` | §2 |
| `ui/day/LegacySessionReceipt.kt` | frozen `SessionReceipt`, `CascadeLift` (§1.2) |
| `ui/Transitions.kt:20` | `emphasizedDecelerate` becomes `internal` so the roll-in reuses it |

`DayScreen` (DayScreen.kt:46-68) takes `receipt: WorkoutReceipt?`, `finish: FinishAttempt?`,
`noteSave: NoteSave?`, `onRetryFinish`, `onBackToWorkout`, `onNoteSaveHandled` in place of
`sessionReceipt`; the early return becomes: receipt → `SessionReceiptScreen`; else attempt failed →
`FinishFailedScreen`; else attempt → `FinishingScreen`; else the workout. The finishing and failed
screens replace the workout the same way the receipt does: no workout nodes, back handlers or
auto-sheet effects underneath.

### 3.2 Layout, M3 components

`ReceiptScaffold(dayIndex, title, planLine, metaLine, shareEnabled, showShare, bottomBar, content)`:

- `Scaffold` with `TopAppBar(title = {}, actions = { if (showShare) IconButton(onClick, enabled =
  shareEnabled) { Icon(Share) } })`; `showShare = !receipt.cardio` (cardio has no share card; §5).
- Content: `Box(TopCenter) { Column(widthIn(max = ReadableWidth).fillMaxWidth()
  .verticalScroll()) }`, horizontal padding 16 dp (the mockup's), not today's 24.
- Header `Column(padding 0 16 16)`: title `displaySmall` onSurface; plan line `bodyLarge` +
  `FontWeight.Medium` onSurface; meta `bodyLarge` onSurfaceVariant. Receipt title
  `receipt_title_done` ("Squat focus, done."); finishing and failed use `dayTitle` alone. Meta:
  `receipt_meta_minutes(date, minutes)` or the date alone when `minutes == null`; date formatted
  `EEEE, MMM d` in `Locale.US` as today (SessionReceiptScreen.kt:94-97).
- "Records weren't checked" line: `bodyMedium` onSurfaceVariant, padding 16 horizontal, 8 bottom,
  between header and rows, only when `!recordsChecked`.
- Cardio activity line: `cardioActivityName(activity)` `bodyLarge` Medium, padding 16/8/4.
- Rows (§3.3), then `TextButton(onClick = open note) { "Add a note" / "Edit note" }` for strength
  only (today's rule), padding start 4 dp, and the saved note above it in `bodyMedium`
  onSurfaceVariant, 3 lines max, as today.
- `bottomBar`: `Surface(color = background) { Column { HorizontalDivider(color = outlineVariant);
  padding 16 horizontal 12 vertical; if (allPlansDone) Row(Icon(check_circle, tint = Done),
  Text("All plans done for today.", bodyLarge)) + 12 dp; Button(Done) } }`. Done: `Button`,
  `fillMaxWidth`, `heightIn(min = 48.dp)`, `ButtonDefaults.buttonColors(containerColor =
  dayAccent(dayIndex), contentColor = onDayAccent(dayIndex))`, `enabled = false` while finishing.
  Failed: `Row(spacedBy 8) { OutlinedButton("Back to workout", weight 1); Button("Try again",
  weight 1, day colors) }`.
- Back: `rememberBackGestureProgress(enabled = !noteOpen, onBack = onFinishSession)` and
  `backGesturePreview` stay as they are on the receipt (SessionReceiptScreen.kt:92, 99). Failed:
  `onBack = onBackToWorkout`. Finishing: `BackHandler(true) {}`.

Rows inside the content, strength: for each `ReceiptGroup`: `rounds == null` → the single row;
else `SupersetGroup(rounds) { rows with gutters "A1", "A2" }`. Row kinds: `SKIPPED` →
`SkippedReceiptRow`; `TOP_SET`/`HEAVIEST_NO_TOP` → `ExpandableReceiptRow(expandable = false)`;
`STRAIGHT` → `ExpandableReceiptRow(expandable = true)`. Cardio: WARMUP/COOLDOWN rows
`expandable = false`; WORK rows `expandable = count > 1`.

### 3.3 Bespoke pieces and their KDoc

Each KDoc must contain "Material 3" or "M3" and one of "lacks", "does not", "cannot", "no" so
`MaterialComponentConventionTest` accepts it; the sentences below satisfy that.

**`ExpandableReceiptRow`** (`ReceiptRowItem.kt`)

> A list row that opens to show its sets. Material 3 (1.4) has no expandable list item, so this
> is [ListItem] for the closed row plus [AnimatedVisibility] for the open part, with
> expanded/collapsed state in semantics. Migrate to material3 1.5's interactive list items once
> 1.5 is stable.

Anatomy: `Column { ListItem(...); AnimatedVisibility(open) { children } }`.
- `ListItem(headlineContent = Row { Text(name, bodyLarge, weight 1); if (expandable)
  Icon(expand_more, tint onSurfaceVariant, rotate 180 when open) }, leadingContent = gutter text
  (labelLarge onSurfaceVariant, 32 dp wide) when inside a superset, supportingContent = FlowRow {
  Column(value lines, spacedBy 2 dp, weight 1); PrLabel at end }, colors =
  ListItemDefaults.colors(containerColor = Color.Transparent))`. No `trailingContent`: the
  chevron rides on the name line and the label on the value line, which is the Opus layout, and
  at 200 % font `FlowRow` wraps the label under the numbers. Minimum height comes from `ListItem`
  (72 dp for two-line content).
- Value line (`Row(baseline, spacedBy 10 dp, wrap)`): optional label `bodyMedium`
  onSurfaceVariant ("Top set", "Heaviest", "Fastest"...), the figures `headlineSmall` onSurface,
  emphasis (`accentEmphasis(dayIndex)`) on the record set; the record rep additionally underlined
  (`TextDecoration.Underline`; Compose has no underline thickness control, the standard underline
  is accepted); the old best `titleMedium` onSurfaceVariant `TextDecoration.LineThrough`; "and 3
  lighter sets" / "top set not logged" `bodyMedium` onSurfaceVariant.
- The closed `ListItem` carries `Modifier.clearAndSetSemantics { contentDescription = spoken;
  if (expandable) { role = Role.Button; stateDescription = expanded/collapsed;
  onClick(label = expand/collapse) { toggle } } }` so TalkBack reads one sentence per row and the
  figures are never read piecemeal. The `clickable` modifier for touch is on the `ListItem` too;
  the item's `onClickLabel` matches. Open state: `rememberSaveable(rowKey)` where `rowKey` is the
  group index and row index, so rotation keeps rows open.
- Children (`Column(padding start 24 dp for the indent rule, end 16, bottom 8)` with a
  `VerticalDivider` on the left, `outlineVariant`): one `Row` per set (ordinal `labelLarge` 28 dp;
  value `titleMedium`, emphasis when it is the record set; `"PR, up from …"` `labelMedium`
  emphasis), each `Modifier.semantics { contentDescription = spoken set line }`; last the plan line
  `bodyMedium` onSurfaceVariant, 30 dp tall, with its spoken form as `contentDescription`.
- Chevron rotation `animateFloatAsState(if (open) 180f else 0f, tween(200, standard easing))`;
  `AnimatedVisibility(expandVertically + fadeIn / shrinkVertically + fadeOut)`. Under reduced
  motion (`!ValueAnimator.areAnimatorsEnabled()`) the children are an `if`, the chevron snaps.

**`SkippedReceiptRow`**: `ListItem(headlineContent = Text(name, bodyLarge, onSurfaceVariant),
trailingContent = Text("Skipped", bodyMedium, onSurfaceVariant))`, 56 dp, semantics "Face pull,
skipped." Plain M3; no KDoc needed.

**`SupersetGroup`**: `Text("Superset, 3 rounds", bodyLarge Medium, padding 12/16/2)` then a
`Row { VerticalDivider(outlineVariant); Column(rows) }` indented 24 dp. Plain M3 (`VerticalDivider`
instead of a hand-drawn 1 dp box, so the guardrail's thin-rule heuristic is not tripped).

**`PrLabel`**

> The record label on a receipt row: a non-interactive tonal [Surface] with words. Material 3's
> [Badge] holds counts and its chips are interactive; M3 has no non-interactive labeled tag. The
> one exception to DESIGN.md's "no badges".

`Surface(color = accentLabelContainer(dayIndex), contentColor = accentEmphasis(dayIndex), shape =
RoundedCornerShape(4.dp)) { Text(label, labelMedium, padding 6 horizontal 3 vertical,
softWrap = false) }`, `Modifier.clearAndSetSemantics {}` (the row's sentence names the record).
Label text by `PrKind`: WEIGHT "Weight PR", REPS "Rep PR", HOLD "Hold PR", FASTER "Faster PR",
FARTHER "Farther PR".

`ui/theme/Color.kt` gains the container rule next to `onDayAccent`:

```kotlin
/** PR-label container: the day accent sunk into the ground the receipt rows sit on, 15% over the
 *  near-black background and 10% over warm paper's surface. [accentEmphasis] text on it clears
 *  4.5:1 on every day in both expressions; `DayAccentTest` pins that. */
@Composable fun accentLabelContainer(dayIndex: Int): Color = accentLabelContainer(dayIndex, LocalAppPalette.current.isDark)
internal fun accentLabelContainer(dayIndex: Int, isDark: Boolean): Color =
    if (isDark) dayAccent(dayIndex).copy(alpha = 0.15f).compositeOver(DarkBackground)
    else dayAccent(dayIndex).copy(alpha = 0.10f).compositeOver(LightSurface)
```

Checked for this document with the WCAG formula the tests use: dark A 5.84, B 5.05, C 7.47,
D 4.72, E 4.99, F 5.31, G 5.97; light A 6.32, B 8.40, C 4.56, D 9.09, E 8.54, F 7.72, G 6.74.
All seven pass in both themes; the thinnest is day C light at 4.56.

**`GhostBar`** (and `GhostRow`)

> Placeholder bar for a value that is being written. Material 3 has no skeleton or placeholder
> component. The receipt already knows the exercise names, so a bar where the figures will be
> keeps the layout from jumping when they land. #447 decides whether other screens adopt it.

`Box(Modifier.height(22.dp).width(w).clip(RoundedCornerShape(4.dp)).background(Surface3))` with
alpha pulsing 1 → 0.55 → 1 over 1.6 s (`rememberInfiniteTransition`); under reduced motion the bar is static at alpha 1.
`GhostRow(name, gutter, width)`: a `ListItem` whose `headlineContent` is the name and whose
`supportingContent` is one `GhostBar`, widths cycling 150, 140, 110, 120, 130, 90 dp by row index
(a fixed sequence, so the frame is deterministic in tests).

### 3.4 `FinishingScreen` and `FinishFailedScreen`

`FinishingScreen(attempt, state: DayUiState)`: `ReceiptScaffold(title = dayTitle, planLine =
planName, metaLine = date(tappedAt) [+ minutes from startedAt], showShare = true, shareEnabled =
false, bottomBar = Done disabled)`. Content: `var revealed by remember { mutableStateOf(false) };
LaunchedEffect(Unit) { delay(LOADING_REVEAL_DELAY_MS); revealed = true }` (the constant from
`AuthoredStates.kt:69`); the header always; after `revealed`, one `GhostRow` per
`state.exercises` card (superset cards as a `SupersetGroup("Superset, N rounds")` with gutters,
N = `card.rows.size`), `state.skippedLifts` as `SkippedReceiptRow`. A zero-size `Box(Modifier
.semantics { liveRegion = LiveRegionMode.Polite; contentDescription = "Finishing workout" })` at
the top of the content announces once when the screen appears (the pattern at
WorkoutSetScreen.kt:422). `state.loading` → header only, no rows.

`FinishFailedScreen(attempt, onRetry, onBack)`: same scaffold, title without "done.", content
`Row(padding 8 16 16, spacedBy 12) { Icon(error, tint = MaterialTheme.colorScheme.error);
Column { Text("Couldn't finish the workout.", bodyLarge Medium, onSurface); Text("Every set you
logged is saved. Try again, or go back to the workout.", bodyMedium, onSurfaceVariant, top 4) } }`.
The icon is the only use of the error color; the text is normal.

### 3.5 Motion

- Records roll in once per receipt: on the record value `Text`, `graphicsLayer { translationY =
  roll.value * 0.6f * size.height; alpha = 1 - roll.value }` with `roll = remember(sessionId) {
  Animatable(if (animate) 1f else 0f) }` and `LaunchedEffect(sessionId) { if (animate) {
  delay(300); roll.animateTo(0f, tween(400, easing = emphasizedDecelerate)) } }`.
  `animate = !played && !reducedMotion` where `played` is `rememberSaveable(receipt.sessionId)`
  and set true in the same `LaunchedEffect` that fires the single `HapticFeedbackType.Confirm`
  when any row has a record (today's gating, SessionReceiptScreen.kt:73-88). Rotation, reopen and
  reduced motion start at the final frame.
- Chevron 200 ms, `AnimatedVisibility` defaults, ghost pulse 1.6 s; all off under reduced motion.

### 3.6 Semantics and what TalkBack says

`ReceiptText.kt` builds every sentence from resources and the frozen numbers. Helpers:

- `spokenWeight(lb, unit)`: `WeightStepper.format(unit.fromLb(lb))` + " " + `receipt_say_pounds`
  / `receipt_say_kilograms`.
- `spokenSet(set, tracking, unit)`: WEIGHTED `receipt_say_weight_for_reps` "%1$s for %2$d reps"
  ("245 pounds for 5 reps"); REPS `receipt_say_reps` "%1$d reps"; TIMED `spokenDuration(seconds)`
  plus, when `weightLb > 0`, `receipt_say_with_added` " with %1$s added".
- `spokenDuration(s)`: `receipt_say_minutes` plural ("%d minute"/"%d minutes") and
  `receipt_say_seconds` plural, joined by a space, omitting a zero part ("1 minute 15 seconds",
  "45 seconds", "10 minutes").
- `spokenList(items)`: "a", "a and b", "a, b and c" via `receipt_say_and` "%1$s and %2$s".
- `spokenRecord(kind)`: WEIGHT "Weight record", REPS "Rep record", HOLD "Hold record", FASTER
  "Faster record", FARTHER "Farther record"; two kinds → `spokenList`.

The closed row's `contentDescription`, sentences joined by a space:

| Row | Sentence |
|---|---|
| `TOP_SET` | "Barbell Back Squat. Top set, 245 pounds for 5 reps." + record: "Weight record, up from 240 pounds for 5 reps." |
| `HEAVIEST_NO_TOP` | "Romanian Deadlift. Top set not logged. Heaviest set, 210 pounds for 3 reps." |
| `STRAIGHT` WEIGHTED, 1-2 lines | "Dumbbell Curl. 35 pounds: 10 and 10 reps. 30 pounds: 8 reps." + record: "Rep record on set 1, up from 35 pounds for 9 reps." |
| `STRAIGHT` WEIGHTED, 3+ weights | "Dumbbell Lateral Raise. Heaviest set, 25 pounds for 12 reps, and 3 lighter sets." |
| `STRAIGHT` REPS | "Pull-up. 12, 10 and 9 reps." |
| `STRAIGHT` TIMED | "Plank. Holds of 1 minute 15 seconds and 1 minute 10 seconds." + "Hold record on set 1, up from 1 minute 10 seconds." |
| two records | "... Weight record and rep record, up from ..." |
| `SKIPPED` | "Face Pull, skipped." |
| cardio WARMUP / COOLDOWN | "Warm-up, 10 minutes." / "Cool-down, 5 minutes." |
| cardio WORK, distance end | "6 by 400 meters. Fastest, 1 minute 38 seconds." + "Faster record, up from 1 minute 42 seconds." |
| cardio WORK, time end | "30 minutes. Distance, 5,210 meters." + "Farther record, up from 4,980 meters." / "Distance not entered." |

Expanded/collapsed and "Double-tap to expand" come from `stateDescription` and the `onClick`
label (`log_expanded_state`, `log_collapsed_state`, `log_expand_label`, `log_collapse_label`,
already in strings.xml:289-292), not from the sentence. The spoken previous for a rep record is the
whole set ("35 pounds for 9 reps"), one rule for every kind; the mockup's shorter "up from 9" is
not used. "on set N" is used for holds too.

Open set line: "Set 1, 35 pounds for 10 reps, rep record, up from 35 pounds for 9 reps."; cardio
"Interval 2, 1 minute 38 seconds, faster record, up from 1 minute 42 seconds.", "Interval 3,
1 minute 20 seconds, shortened.", "Interval 4, skipped.". Plan line: `receipt_say_planned_weighted`
"Planned %1$d sets of %2$d reps at %3$s", `receipt_say_planned_reps` "Planned %1$d sets of %2$d
reps", `receipt_say_planned_holds` "Planned %1$d holds of %2$s". Recovery: "Recovery 1 minute
30 seconds each". The PR label, chevron and struck old best have no semantics of their own.

### 3.7 Strings

Added to `app/src/main/res/values/strings.xml` under `<!-- Session receipt -->`, verbatim:

| Key | Value |
|---|---|
| `receipt_title_done` | `%1$s, done.` |
| `receipt_meta_minutes` | `%1$s, %2$d min` |
| `receipt_top_set` | `Top set` |
| `receipt_heaviest` | `Heaviest` |
| `receipt_top_set_not_logged` | `top set not logged` |
| plurals `receipt_lighter_sets` | one `and %d lighter set`, other `and %d lighter sets` |
| `receipt_planned_at` | `Planned %1$d × %2$s at %3$s` |
| `receipt_planned` | `Planned %1$d × %2$s` |
| plurals `receipt_superset_rounds` | one `Superset, %d round`, other `Superset, %d rounds` |
| `receipt_pr_weight`, `receipt_pr_reps`, `receipt_pr_hold`, `receipt_pr_faster`, `receipt_pr_farther` | `Weight PR`, `Rep PR`, `Hold PR`, `Faster PR`, `Farther PR` |
| `receipt_set_pr_note` | `PR, up from %1$s` |
| `receipt_records_not_checked` | `Records weren't checked for this workout.` |
| `receipt_all_plans_done` | `All plans done for today.` |
| `receipt_finishing` | `Finishing workout` |
| `receipt_finish_failed_title` | `Couldn't finish the workout.` |
| `receipt_finish_failed_body` | `Every set you logged is saved. Try again, or go back to the workout.` |
| `receipt_try_again` | `Try again` |
| `receipt_back_to_workout` | `Back to workout` |
| `receipt_note_not_saved` | `Couldn't save the note.` |
| `receipt_cardio_work_count` | `%1$d × %2$s` |
| `receipt_cardio_fastest`, `receipt_cardio_time`, `receipt_cardio_farthest`, `receipt_cardio_distance` | `Fastest`, `Time`, `Farthest`, `Distance` |
| `receipt_cardio_distance_missing` | `Distance not entered` |
| `receipt_cardio_not_finished` | `No interval finished` |
| `receipt_cardio_shortened` | `%1$s, shortened` |
| `receipt_cardio_recovery_each` | `Recovery %1$s each` |
| `receipt_cardio_recovery` | `Recovery %1$s` |
| `receipt_say_pounds`, `receipt_say_kilograms`, `receipt_say_meters` | `pounds`, `kilograms`, `meters` |
| `receipt_say_weight_for_reps` | `%1$s for %2$d reps` |
| `receipt_say_reps` | `%1$d reps` |
| `receipt_say_weight_group` | `%1$s: %2$s reps.` |
| `receipt_say_with_added` | ` with %1$s added` |
| plurals `receipt_say_minutes` / `receipt_say_seconds` | `%d minute`/`%d minutes`, `%d second`/`%d seconds` |
| `receipt_say_and` | `%1$s and %2$s` |
| `receipt_say_top_set` | `Top set, %1$s.` |
| `receipt_say_top_set_not_logged` | `Top set not logged.` |
| `receipt_say_heaviest` | `Heaviest set, %1$s.` |
| `receipt_say_heaviest_lighter` | `Heaviest set, %1$s, %2$s.` (second arg the lighter-sets plural) |
| `receipt_say_holds` | `Holds of %1$s.` |
| `receipt_say_skipped` | `%1$s, skipped.` |
| `receipt_say_record_weight`, `_reps`, `_hold`, `_faster`, `_farther` | `Weight record`, `Rep record`, `Hold record`, `Faster record`, `Farther record` |
| `receipt_say_record_up_from` | `%1$s, up from %2$s.` |
| `receipt_say_record_on_set` | `%1$s on set %2$d, up from %3$s.` |
| `receipt_say_set` | `Set %1$d, %2$s` |
| `receipt_say_set_record` | `Set %1$d, %2$s, %3$s, up from %4$s.` |
| `receipt_say_planned_weighted`, `receipt_say_planned_reps`, `receipt_say_planned_holds` | `Planned %1$d sets of %2$d reps at %3$s`, `Planned %1$d sets of %2$d reps`, `Planned %1$d holds of %2$s` |
| `receipt_say_cardio_by` | `%1$d by %2$s` |
| `receipt_say_cardio_measure` | `%1$s.` |
| `receipt_say_cardio_value` | `%1$s, %2$s.` (label, value) |
| `receipt_say_cardio_distance_missing` | `Distance not entered.` |
| `receipt_say_interval` | `Interval %1$d, %2$s` |
| `receipt_say_interval_shortened` | `Interval %1$d, %2$s, shortened.` |
| `receipt_say_interval_skipped` | `Interval %1$d, skipped.` |
| `receipt_say_recovery_each` | `Recovery %1$s each` |

Reused: `receipt_done`, `receipt_share_button`, `note_add_action`, `note_edit_action`,
`day_skipped` ("Skipped"), `cardio_warmup`, `cardio_cooldown`, `cardio_metres`, `cardio_skipped`,
`log_expand_label`, `log_collapse_label`, `log_expanded_state`, `log_collapsed_state`.

Retired (delete with their last use in PR 3): `receipt_headline`, plurals `receipt_sets`,
`receipt_sets_partial`, `receipt_sets_in_minutes`, `receipt_new_top_set`,
`receipt_top_set_description`, `receipt_next`. `CardioSummary` (CardioPresentation.kt:87) and its
`cardio_outcomes`/`cardio_distance_*` strings stay: the workout page (WorkoutSetScreen.kt:340) and
History (LogScreen.kt:235) still call it; only the receipt's call goes.

## 4. PR split

Three PRs, each leaving main shippable with green CI. Common guard for all three (the "must not
change" floor): `PinnedVerificationTest` (`squat seeded sequence`, `cascade squat TOP to 245`),
`PersonalBestsTest`, `CardioBestsTest`, `PersonalRecordTest`, `JournalBuilderTest`,
`ShareCardContentBuilderTest`, `ThemeContrastTest`, `DayAccentTest`,
`MaterialComponentConventionTest`, `MainCardioParcelRestoreTest`, and the store screenshots render
(`StoreScreenshotsTest`, `ListingImagesTest`, `WorkoutSetScreenPreviewShotsTest` under
`-PstoreShots=true`, ci.yml:65; the receipt shot `06-receipt.png` changes only in PR 3).

### PR 1: types, builder, record rule, baseline query, label color (Opus-tier logic)

Contents: `WorkoutReceipt.kt` types; `ReceiptBuilder.kt`; `PersonalBests.prKinds` with `prKind`
delegating; `SessionDao.personalRecordRows(exerciseIds, excludedRunId)` and
`HistoryRepository.personalRecords(ids, excludingRunId: String? = null)`;
`HistoryRepository.sessionByRunId`; `accentLabelContainer` in `Color.kt`; `emphasizedDecelerate`
made `internal`; DESIGN.md error-color correction; this document. Nothing is wired to a screen yet
(the same shape as set-structures PR 1, whose `RAMP_TOP` branch shipped unreachable).

Must not change:
- The receipt the app shows stays v6, byte-identical; the test that proves it is
  `StoreScreenshotsTest.receipt` (06-receipt.png unchanged) and `SessionLifecycleScreenTest`.
- `PersonalBests.prKind` answers stay what they are for every `PersonalBestsTest` case; the test
  that proves it is `PersonalBestsTest` unchanged plus the new `prKinds` cases below.
- `personalRecords(ids)` with no exclusion returns what it did; `PersonalRecordTest` and the Best
  chip test in `DayScreenBuilderTest` prove it.
- Every scheme role contrast stays; `ThemeContrastTest`, `DayAccentTest` prove it.

File by file:
- `domain/.../records/PersonalBests.kt`: add `prKinds`; `prKind` = `prKinds(...).firstOrNull()`.
- `data/.../db/dao/SessionDao.kt:161-170`: add `AND (:excludedRunId IS NULL OR ws.runId IS NOT
  :excludedRunId)` to the WHERE; signature `personalRecordRows(exerciseIds: List<String>,
  excludedRunId: String?)`. Query projection only, no schema change, no migration (mirror of
  `CardioSetResultDao.finishedForActivity`, CardioSetResultDao.kt:17-24). Add
  `sessionByRunId` pass-through in `HistoryRepository` (SessionDao.kt:81 already has the query).
- `data/.../HistoryRepository.kt:249`: `personalRecords(exerciseIds, excludingRunId = null)`.
- `app/.../ui/day/receipt/WorkoutReceipt.kt`, `ReceiptBuilder.kt`: §1.
- `app/.../ui/theme/Color.kt`: `accentLabelContainer` beside `onDayAccent` (:92-104).
- `app/.../ui/Transitions.kt:20`: `private val` → `internal val emphasizedDecelerate`.
- `DESIGN.md` "Theme / Both surfaces": "Error is crimson `#C2334D` in both" → "Error is
  `#E5677D` on dark (6.07:1 as text on `#0D0D0F`, 5.64:1 on `#16161A`) and `#B02A43` on light
  (5.61:1 on `#F1EFEA`), crimson so an error never reads as Day A." In "Theme / Phone" delete the
  sentence "Related: Error `#C2334D` as text on `#0D0D0F` is 3.58:1; only on-error text was
  measured when it was chosen." In "Open questions" 4, delete "Error text on near-black needs a
  foreground variant or a rule against it." Add to "House rules / Both surfaces": "The PR label on
  the receipt is the one tonal tag: 15 % day accent over the background on dark, 10 % over the
  surface on light, emphasis text, pinned by `DayAccentTest`." Update the "Receipt" row of the
  Surfaces table: "Confirm the session saved, show every lift's work and any record".

Tests (new file `app/src/test/.../ui/day/receipt/ReceiptBuilderTest.kt`, plain JUnit; fixtures
build `RunTrack`s and `ProgramSlot`s directly). "Fails on old head" marks a case that cannot pass
before this PR (the code does not exist, so every builder case fails; the marks below are the
ones whose behavior differs from v6's `CascadeCeremonyBuilder`):

- Squat RTB track: planned 130/165/190/210 ×5/5/5/3, TOP 235×5, BACKOFF 175×8; done RAMP ×4,
  TOP 245×5, BACKOFF 185×8; standing squat 240×5 → one group, row `TOP_SET`, `lines = [ValueLine
  (245.0, [4])]`, `record = RecordMark(WEIGHT, null, 4, ReceiptSet(240.0, 5, 0))`,
  `planned = null`, `sets.size == 6`.
- Same, TOP 240×5 (ties standing) → `record == null`. Same, standing absent → `record == null`
  (first session is never a PR).
- RTB, TOP not done, RAMP 210×3 done last, BACKOFF 185×8 done, standing 240×5 →
  `HEAVIEST_NO_TOP`, line on the 210×3 set (index 3), no record.
- RTB, TOP 235×5 done, BACKOFF 185×8 done, standing 235×3 → `RecordMark(REPS, null, …)`
  (rep PR at equal weight; fails on old: the ceremony compared weight only).
- RTB, TOP 245×6, standing 240×5 → `kind WEIGHT, alsoKind REPS`.
- `RAMP_TOP` structure, TOP 245×5 done → `TOP_SET` with `planned == null` (never opens).
- STRAIGHT curl, done 35×10, 35×10, 30×8, planned 3 × 35×10, standing 35×9 → `STRAIGHT`,
  `lines = [ValueLine(35.0, [0, 1]), ValueLine(30.0, [2])]`, `lighterSets 0`, `planned =
  PlannedSets(3, 35.0, 10, 0)`, `record = RecordMark(REPS, null, 0, ReceiptSet(35.0, 9, 0))`
  (fails on old: WORK sets never earned a receipt row).
- STRAIGHT lateral raise 25×12, 20×10, 15×10, 10×12 → one line `(25.0, [0])`, `lighterSets = 3`.
- STRAIGHT 30×8 then 35×10, 35×10 (ascending) → lines ordered 35 then 30 (heaviest first).
- Pull-up REPS 12, 10, 9 (weight 0), standing ×10 → one line `(null, [0,1,2])`, tracking REPS,
  record `REPS` on set 0. Weighted pull-up (`weighted_pullup`, 25×8, 8, 6) → tracking WEIGHTED,
  one line `(25.0, [0,1,2])`.
- Plank TIMED 75 s, 70 s, standing 70 s → one line `(null, [0,1])`, record `HOLD` set 0,
  `planned = PlannedSets(2, 0.0, 0, 60)`. Loaded hold 90 s +25 over 75 s +0 → `HOLD` first,
  `alsoKind WEIGHT`.
- Mixed legacy: sets ×60 reps (0 lb) and 60 s hold on one track → tracking TIMED (last counting
  set), the reps set in `sets` but in no line, never compared.
- Superset slot bench + row, bench done 5,5,4 at 185, row 155×8, 155×8, 135×10, main track
  done 3 → `ReceiptGroup(rounds = 3, rows = [bench, row])`; row's lines `[(155,[0,1]),
  (135,[2])]`, `planned = PlannedSets(3, 155.0, 8, 0)`.
- Partner track dropped with nothing done → group `rounds == null`, one row.
- Skipped lift (MAIN track `dropped = true, retired = true`, no done) → `SKIPPED`, `sets` empty,
  name from the dropped track's exercise.
- Live track with nothing done (never reached) → `SKIPPED`.
- Retired track (swap mid-run) holding 2 done sets of bench, live track dumbbell press 3 done →
  two groups in that order; retired track with no done set → dropped.
- Track for a slot that no longer exists → appended last, STRAIGHT.
- `recordsChecked` scenario: empty baseline → no row has a record for the squat fixture above.
- Two-track same exercise (retired + live) → both compared to the same standing; both may carry a
  record (documented edge, pinned so nobody "fixes" it silently).
- KG display is a screen matter; the builder pins lb: standing 220.462 lb, top 231.485 lb →
  `record.previous.weightLb == 220.462`, `sets[top].weightLb == 231.485`.
- Cardio: TRACK_RUN; WARMUP 600 s; WORK 400 m FINISHED 101, 98, 104, 100, 99, 105 s with RECOVERY
  90 s after each; COOLDOWN 300 s; standing 400 m = 102 s → rows `[WARMUP(count 1, intervals 1,
  elapsed 600), WORK(count 6, endMetres 400, recoverySeconds = six 90s, record = (FASTER,
  intervalIndex 1, previousSeconds 102)), COOLDOWN(300)]`.
- ROAD_RUN WORK Time(1800) FINISHED metres 5210, standing 4980 → `WORK(count 1, endSeconds 1800,
  record (FARTHER, 0, previousMetres 4980))`; `metres = null` → no record; SHORTENED 5300 → no
  record and `intervals[0].status == SHORTENED`.
- Two WORK ends (400 m ×4 then 800 m ×2) → two WORK rows in that order, counts 4 and 2.
- Warm-up SKIPPED → WARMUP row with one SKIPPED interval. Recovery before any work → dropped.
- `allPlansDone`: two onToday plans, both with a session dated today → true; one plan only →
  false; two plans, one done yesterday → false; one strength + one cardio today → true; an
  archived onToday plan is ignored.
- `prKinds` (in `PersonalBestsTest`): 245×6 over 240×5 → [WEIGHT, REPS]; 245×4 over 240×5 →
  [WEIGHT]; 235×5 over 235×3 → [REPS]; 235×5 over 235×5 → []; ×12 over ×10 → [REPS]; 90 s +25
  over 75 s → [HOLD, WEIGHT]; 75 s +25 over 75 s +0 → [WEIGHT]; RAMP candidate → []; null
  standing → []. `prKind` equals `prKinds.firstOrNull()` for each.
- `personalRecordRows` exclusion (`data` Robolectric DAO test beside the existing one): two
  sessions for `bb_back_squat`, runIds "r1" (235×5) and "r2" (245×5); `excludedRunId = "r2"` →
  only the 235 row; `null` → both; a session with `runId = null` is never excluded.
- `DayAccentTest`: `every PR label container clears WCAG AA with the emphasis text` — for
  `isDark in [true, false]`, `dayIndex in 0..6`, `contrastRatio(accentLabelContainer(dayIndex,
  isDark), if (isDark) accentBright(dayIndex) else accentDeep(dayIndex)) >= 4.5`; and the exact
  rule pinned by value for day A dark (`Color(0xFF28150F)`) and day A light (`Color(0xFFF4E7DF)`),
  the mockup's `--tag` values.

Store screenshots: unchanged in PR 1.

### PR 2: the screen (Sonnet-tier UI to this contract)

Contents: `ReceiptText.kt`, `ReceiptRowItem.kt`, the rewritten `SessionReceiptScreen.kt` taking
`WorkoutReceipt`, `FinishScreens.kt`, the `NoteSheet` `save` parameter, every string in §3.7.
The old `SessionReceiptScreen(receipt: SessionReceipt, ...)` stays alive for one PR, renamed
`LegacySessionReceiptScreen` in `LegacySessionReceipt.kt` so `DayScreen` and the ViewModel are
untouched; nothing reaches the new screens yet except tests.

Must not change:
- What the app shows after Finish stays v6; `StoreScreenshotsTest.receipt` byte-identical.
- `NoteSheet` callers in Log and the exercise note keep closing on Save; `LogScreen`'s note test
  and `SessionLifecycleScreenTest.noteOpensOnTheReceiptAndReportsThroughTheSharedSheet` (against
  the legacy screen) prove it.
- `MaterialComponentConventionTest` stays green: every bespoke piece carries its KDoc.

File by file: as §3.1, plus `NoteSheet.kt:27-47` (new parameter, `SnackbarHost` as the first child
of the `Column`, `LaunchedEffect(save)`), `strings.xml` additions.

Tests (`app/src/test/.../ui/day/receipt/ReceiptScreenTest.kt`, Robolectric + Compose, the
`SessionLifecycleScreenTest` setup incl. the animator-scale helpers; fixture
`receiptFixture()` = mockup frame (a) as a `WorkoutReceipt`, built by hand, not by the builder):

- Header: "Squat focus, done.", "Four-day strength", "Tuesday, Nov 3, 58 min" exist (fix
  `completedAt` to 2026-11-03 local).
- Collapsed squat row: node with contentDescription "Barbell Back Squat. Top set, 245 pounds for
  5 reps. Weight record, up from 240 pounds for 5 reps." exists and has no click action; text
  "Weight PR" exists; "240 × 5" exists (unmerged).
- Curl row: contentDescription "Dumbbell Curl. 35 pounds: 10 and 10 reps. 30 pounds: 8 reps. Rep
  record on set 1, up from 35 pounds for 9 reps." has click action; before click
  `stateDescription == "Collapsed"`, "Planned 3 × 10 at 35" does not exist; after `performClick`
  `stateDescription == "Expanded"`, "Planned 3 × 10 at 35" exists, "PR, up from 35 × 9" exists,
  and a node with contentDescription "Set 1, 35 pounds for 10 reps, rep record, up from 35 pounds
  for 9 reps." exists.
- Lateral raise: "and 3 lighter sets" text; sentence ends "...and 3 lighter sets."
- Pull-up: "12, 10, 9"; plank: "75s, 70s", label "Hold PR", sentence "Plank. Holds of 1 minute
  15 seconds and 1 minute 10 seconds. Hold record on set 1, up from 1 minute 10 seconds."
- Superset: "Superset, 3 rounds" exists; "A1" and "A2" exist; "Barbell Row" sentence has two
  weight groups.
- Face pull: "Skipped" exists; contentDescription "Face Pull, skipped."; no click action.
- KG receipt (`unit = KG`, squat 231.485 over 220.462) → "105 × 5", "100 × 5", spoken
  "105 kilograms for 5 reps".
- Two records: `RecordMark(WEIGHT, REPS, …)` → label "Weight PR" only, sentence contains "Weight
  record and rep record".
- `recordsChecked = false` → "Records weren't checked for this workout." exists and no "PR" text.
- `allPlansDone = true` → "All plans done for today." exists; false → does not.
- Done click → `onFinishSession` once; system back → once; Share click → `onShare` once; cardio
  receipt → no Share node, no "Add a note".
- Cardio fixture (frame c): "Track run", "Warm-up" row "10:00", "6 × 400 m" with "Fastest",
  "1:38", "1:42", label "Faster PR", opens to "Recovery 1:30 each" and "PR, up from 1:42";
  "Cool-down" "5:00"; sentence "6 by 400 meters. Fastest, 1 minute 38 seconds. Faster record, up
  from 1 minute 42 seconds."; time-target fixture: "30:00", "Distance", "5,210 m", "4,980 m",
  "Farther PR"; `metres = null` → "Distance not entered", no label.
- Roll-in (use the normal test clock: in this repo `mainClock.autoAdvance = false` never applied a
  mutableState change and `StateRestorationTester` never recomposed under it): with animators on, after 100 ms the record value
  node exists but the row still reports; after 800 ms unchanged; haptic `Confirm` exactly once;
  `StateRestorationTester.emulateSavedInstanceStateRestore` → no second haptic, values present
  (port of `restoringDuringTheStrikeShowsTheEndStateWithoutAnotherHaptic`). Animator scale 0 →
  values present immediately.
- Open state survives restore: open the curl row, `emulateSavedInstanceStateRestore`, "Planned 3
  × 10 at 35" still exists.
- 200 % font (`LocalDensity(fontScale = 2f)` as `largeFontCanScrollToDone`): Done reachable by
  `performScrollTo`; "Weight PR" still displayed.
- `FinishingScreen`: before 260 ms no ghost node (tag `ghost`); after 300 ms seven ghost nodes
  for a seven-card day state (five single + one superset); Done is disabled (`assertIsNotEnabled`);
  Share disabled; a node with contentDescription "Finishing workout" exists; `pressBack` does not
  call any callback.
- `FinishFailedScreen`: "Squat focus" exists and "Squat focus, done." does not; "Couldn't finish
  the workout." and the body exist; "Try again" → `onRetry` once; "Back to workout" → `onBack`
  once; system back → `onBack`.
- `NoteSheet` with `save`: Save does not close the sheet; `save = Failed("x")` → "Couldn't save
  the note." and "Try again" exist inside the sheet, "Try again" → `onSave("x")` again; `save =
  Saved("x")` → `onDismiss` called. Default parameter → closes on Save as before.
- `WindowSizeTest.theReceiptCoversTheWindowButItsContentKeepsTheReadableCap` ported to the new
  screen with the new fixture (same assertions).

Store screenshots: unchanged in PR 2 (the legacy screen still renders 06-receipt.png).

### PR 3: the finisher, wiring, deletions, screenshot (Opus-tier)

Contents: `SessionFinisher.kt`; `DayViewModel` changes (§2.2, §2.7: construct the finisher,
`completeDay` delegates, remove the receipt members, cardio path through `showCardio`,
`afterCommit` lambda); `DayScreen`/`DayRoute`/`AppNavHost` wiring; delete
`LegacySessionReceiptScreen`, `SessionReceiptBuilder`, `CascadeCeremonyBuilder`,
`CardioRun.receiptRecords`, `formatRecordMetres` (moved), retired strings; keep the frozen
legacy classes; new `06-receipt.png`.

Must not change:
- `finishRun`'s effects (session row, sets, plan advance, carry-forward, pin) stay what they are;
  `TrackerRepositoryRunTest`, `DayViewModelReceiptTest` (`finishingCWhileBIsSuggestedReportsB…`,
  `finishingAPausedNonpreferredRunReportsItsOwnNextDay`, the standalone cardio case, the
  superset count cases rewritten to assert on `finisher.receipt`) prove it.
- Cardio logging, distance amendment, deferred receipts and the per-run baseline keys keep
  working; `DayViewModelCardioExecutionTest` cases rewritten to read
  `finisher.receipt.value?.cardioRows` instead of `sessionCardioRecords`, with the same expected
  records ("400 m" FASTER 98 s over 102 s; "30:00" FARTHER 5210 over 4980), prove it.
- A bundle written by v6 restores without a crash; `MainCardioParcelRestoreTest` (unchanged) and
  a new `DayViewModelReceiptTest.aLegacyReceiptBundleIsDroppedAtStartup` (handle seeded with
  `"day_session_receipt" to SessionReceipt(...)` and `"day_cardio_receipt_records:x" to
  arrayListOf(CardioReceiptRecord(...))`; new ViewModel → both keys absent, `finisher.receipt`
  null, no exception) prove it.
- Health Connect publish still fires once per finished session (`sessionPublisher.publish`
  counted in a fake), watch sync untouched (no file under `sync/` changes).
- The §11 pinned numbers: `PinnedVerificationTest`.

File by file:
- `DayViewModel.kt`: delete :416-450 (`sessionReceipt`, `sessionCardioRecords`,
  `cardioRecordsKey`, `clearCardioRecordState`'s records half, `clearReceiptRecords`), :453-461
  (`_pendingShare`), :1429-1438 (`setReceiptNote`), :1452-1505 (`completeDay` body → the
  delegation), :1507-1512 (`dismissSessionReceipt`), :1517-1528 (`shareSession`, `shareHandled`),
  `KEY_SESSION_RECEIPT`; edit :871-911 per §2.7; construct `finisher` in `init` after `editor`
  with `afterCommit = { day, nextDayId -> phoneRest.skip(); if (timedRepEvent.value?.dayId == day)
  clearTimedRep(); closeUndoWindow(); savedState[KEY_VIEW_SELECTION] = null; pinHeldFor =
  day.takeIf { nextDayId != day && effectiveDayId.value == day }; savedState[KEY_SELECTED_PAGE] =
  null; closeSheet() }` and `executionActive = { executionActive }`. `executionActive` stays a
  ViewModel property (cardio code uses it).
- `AppNavHost.kt:452, 458-473, 525, 556-560`: collect `finisher.receipt`, `.attempt`,
  `.noteSave`, `.pendingShare`; pass the new `DayScreen` parameters.
- `DayScreen.kt:46-68`: the three-way early return (§3.1).
- `StoreScreenshotsTest.receipt` (:236-258): the frame (a) fixture; `beforeCapture` asserts
  "Squat focus, done." and "Weight PR" displayed and "240 × 5" exists; render advances 2 s so the
  roll (300 + 400 ms) is finished. The dark store set keeps its count and names, so
  `scripts/check_store_shots.py` is unaffected; the CI artifact `store-screenshots` carries the
  new 06-receipt.png for the visual check. A light render is not a store shot; the contrast test
  covers the light label.
- Delete `CascadeCeremonyTest`, `SessionReceiptBuilderTest`; rewrite `DayViewModelCascadeTest`
  so "the moment" assertions read `finisher.receipt.value?.groups.flatMap { it.rows }.any { it
  .record != null }` (fires on a raised TOP, silent on ties, silent on a fresh route, never
  resurrected by a state re-emission, once per Finish).

Tests (`DayViewModelReceiptTest`, in-memory Room as today). Faults are injected through the
`SessionDao` delegate the test already builds (`object : SessionDao by db.sessionDao()`,
DayViewModelReceiptTest.kt:411-414), which both `TrackerRepository` and `repo.history` receive:
`sessionByRunId` is `finishRun`'s first statement inside its transaction
(TrackerRepository.kt:1307), so making it throw fails the finish before any write;
`personalRecordRows` throwing fails only the baseline; `setNote` throwing fails only the note.
A flag on the delegate (`var failSessionByRunId = false`) turns each fault on and off per test, and
a counter on the same methods stands in for "called once". `cardioSetResultDao` is created inside
`HistoryRepository` and cannot be substituted, so the cardio records-not-checked path is proven at
the builder level (an empty baseline yields no record) plus the `showCardio(standing = null)`
branch in a direct call, not through a failing DAO.

Cases:

- `completeDay` writes `day_finish` then `day_workout_receipt` and clears `day_finish`:
  after `advanceUntilIdle`, `handle["day_finish"] == null`, receipt non-null with `dayTitle ==
  "Lower"`, `planName` == the plan's name, `groups.single().rows.single().shape == TOP_SET`.
- A raised TOP (the `raiseTopSet` helper) → `record.kind == WEIGHT`, `previous.weightLb == 235.0`,
  `sets[record.setIndex].weightLb == 245.0` (fails on old: no such fields).
- Finish failure, uncommitted: `sessionByRunId` throwing → `handle["day_finish"].failed == true`,
  run still open (`repo.openRunFlow.first()?.id == runId`), suggested day unchanged, no session
  row, no receipt, no publish. Then `retry()` with the fault off → receipt present, exactly one
  session, one publish. `retry()` twice before `advanceUntilIdle` → `sessionByRunId` counted
  once.
- Baseline failure: `personalRecordRows` throws → receipt present, `recordsChecked == false`, no
  row has a record even with a raised TOP; everything else as the success case.
- Resume: handle seeded with `FinishAttempt(runId of an open run, failed = false)` → new
  ViewModel finishes it without a tap; receipt present; `finishRun` called once.
- Resume after commit: handle seeded with a non-failed attempt for a run already finished
  (session exists, run closed) → `day_finish` cleared, no receipt, no second session.
- `backToWorkout()` on a failed attempt → `day_finish` null, run still open, `KEY_SELECTED_PAGE`
  untouched (seed it before the tap and assert equal after).
- Parcel round trip (port of `theWholeReceiptSurvivesAParcelRoundTrip`): finish, set a note, write
  the handle to a `Parcel`, restore → `finisher.receipt` equal, `handle["day_workout_receipt"]`
  equal; restore does not read history (count `personalRecordRows` calls: unchanged by the
  restore; replaces the `observeTopSets` counter).
- Note: `setNote("  Felt steady  ")` → DB note "Felt steady", receipt note equal, `noteSave ==
  Saved("Felt steady")`; `sessionDao.setNote` throwing → `noteSave == Failed("Felt steady")`,
  receipt note unchanged.
- Legacy bundle dropped at startup (above).
- `allPlansDone`: two plans on Today, finish both today (one strength, one cardio) → second
  receipt `allPlansDone == true`, first `false`; a single plan → `false`.
- Cardio (`DayViewModelCardioExecutionTest`): the existing record cases produce `cardioRows[1]
  .record == CardioRecordMark(FASTER, 1, 102, null)` for the track run and `CardioRecordMark
  (FARTHER, 0, null, 4980)` for the road run; the standing-bests key is absent after the receipt
  shows; `finisher.showCardio(run, standing = null, session)` called directly → cardio receipt
  present, `recordsChecked == false`, no record.
- Screen wiring (`SessionLifecycleScreenTest`, now against `DayScreen` with the new parameters):
  receipt shown → "Finish" absent; `finish` non-null → "Finishing workout" node present, "Finish"
  absent; failed → "Try again" present.

## 5. Risks and owner questions

Risks:

- The frame between `finishRun` (step 7) and the receipt write (step 10) is three primary-key
  reads; a process death inside it loses the receipt but not the session, and the resume path
  clears the attempt. Same as today, named in the personal-bests brief. Not fixed here because the
  fix is a second builder input (session rows + `plannedJson`) for a window of milliseconds.
- `prKinds` changes `PersonalBests`, which the share card, Best chip and History read through
  `best`/`prKind`. The first element equals today's `prKind`; `PersonalBestsTest` guards the
  existing answers and the new cases pin the second element.
- The label container on day C in light is 4.56:1, two hundredths over the floor. The rule is a
  single constant per theme; if a later palette change drops it under, the test says so.
- Two rows for one exercise in one session (a swap away and back) may both carry a record against
  the same standing best. Pinned as the documented behavior rather than special-cased.
- The finishing frame draws exercise names from `DayUiState.exercises`, which is `WhileSubscribed`;
  on a cold restore it is the loading state for a moment, so the frame shows the header and the
  rows arrive with the state. The 260 ms reveal hides most of that.
- The builder cannot compile. Every string and every sentence is listed verbatim above, and the
  Compose tests in PR 2 assert the exact text, so a copy drift fails CI rather than ships.

Owner questions:

1. **Share and "Add a note" on the cardio receipt.** The mockup draws both on frames (c) and (e).
   Today's cardio receipt has neither (`cardioKeepsItsResultsWithoutShareOrNote`): `cardio_session`
   has no note column and the share card renders strength sessions only. This design keeps them
   off cardio. Adding them means a Room migration for the note and a cardio share card, which
   belong to their own issue. Confirm, or say which you want and it becomes a follow-up.
2. **Three mockup figures change under the app's formatters**, stated here so they are not read
   as bugs: holds under 90 s read "75s" not "1:15" (`SecondsStepper`, the rule the workout screen
   and History use); added-weight lifts read "25 × 8" not "+25 × 8" (the catalog tracks them as
   WEIGHTED and the name says "Weighted"); cardio distance reads "5,210 m" not "5.6 km" (#444's
   formatter; km and miles are DESIGN.md open question 12). No action needed unless you want one
   of those rules changed app-wide.
