package cloud.trotter.log.strength.ui.day

import cloud.trotter.log.strength.domain.theme.KeepScreenOn
import android.os.Parcelable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.res.stringResource
import cloud.trotter.log.strength.R
import cloud.trotter.log.strength.data.LastSource
import cloud.trotter.log.strength.data.RunSetCounts
import cloud.trotter.log.strength.data.SpacingReason
import cloud.trotter.log.strength.data.db.entity.Slot
import cloud.trotter.log.strength.data.db.entity.TestResultEntity
import cloud.trotter.log.strength.domain.library.TrackingType
import cloud.trotter.log.strength.domain.library.eachSideOf
import cloud.trotter.log.strength.domain.standards.HoldClock
import cloud.trotter.log.strength.domain.standards.TestEvent
import cloud.trotter.log.strength.domain.standards.TestRef
import cloud.trotter.log.strength.domain.routine.Move
import cloud.trotter.log.strength.domain.model.Equipment
import cloud.trotter.log.strength.domain.model.LoggedSet
import cloud.trotter.log.strength.domain.model.Sides
import cloud.trotter.log.strength.domain.model.Measure
import cloud.trotter.log.strength.domain.model.SetKind
import cloud.trotter.log.strength.domain.progression.EffortScale
import cloud.trotter.log.strength.domain.progression.LiftGoal
import cloud.trotter.log.strength.domain.progression.SlotRule
import cloud.trotter.log.strength.domain.progression.SprintDose
import cloud.trotter.log.strength.domain.standards.PhoneRest
import cloud.trotter.log.strength.domain.units.DistanceUnit
import cloud.trotter.log.strength.domain.units.WeightUnit
import cloud.trotter.log.strength.sync.RemoteTick
import kotlinx.parcelize.Parcelize

@Parcelize
@Immutable
data class DaySelection(val planId: Long?, val dayId: String) : Parcelable

/** Completed work kept after a run track leaves the plan. */
@Immutable
data class RetiredTrackLine(val name: String, val doneCount: Int)

/** A phone tick held while the ready-to-start sheet asks; replayed by index on Start. */
@Parcelize
data class PendingStartTick(
    val dayId: String, val programExerciseId: Long, val slot: String, val index: Int,
    val actionLog: Boolean, val timedRep: Boolean,
    val startTimer: Boolean = false,
) : Parcelable

/**
 * Immutable render model for the whole day screen (UDF: the ViewModel's single output).
 *
 * [Immutable] is load-bearing, not decoration. Without it the Compose compiler
 * reads `List<ExerciseCardState>` and a `:domain` value type it can't see the
 * stability of, calls the whole class unstable, and falls back to *identity*
 * comparison — so a rebuilt state that differs in one set's tick recomposes
 * every card on screen. Every model in this file is built once by
 * [DayScreenBuilder]/[DayViewModel] and never mutated, which is exactly what the
 * annotation promises; keep it that way (no `MutableList` here, ever).
 */
@Immutable
data class DayUiState(
    val hasProgram: Boolean = false,
    /** True only while the program is still being read. `hasProgram = false`
     *  with this false is the *answer* "there is no program" — a state the
     *  lifter has to act on — and the screen says so instead of preparing
     *  forever. */
    val loading: Boolean = false,
    val isProgramDay: Boolean = true,
    val selectedPageKey: String? = null,
    val openSheet: OpenSheet? = null,
    val swapUndo: WorkoutSwapUndo? = null,
    val pendingPlanSwap: PendingPlanSwap? = null,
    val viewDayId: String? = null,
    val planId: Long? = null,
    val runId: String? = null,
    val runOpen: Boolean = false,
    val runSetCounts: RunSetCounts? = null,
    val openRunElsewhere: String? = null,
    /** Completed tracks displaced by plan edits during this run. */
    val retiredTracks: List<RetiredTrackLine> = emptyList(),
    /** Done rounds on retired MAIN tracks, excluding superset partner duplicates. */
    val retiredRoundDoneCount: Int = 0,
    val dayIndex: Int = 0,
    val dayTitle: String = "",
    val emphasisLine: String = "",
    val unit: WeightUnit = WeightUnit.LB,
    /** The day DONE advances to (rotation successor of the viewed day). */
    val nextDayId: String? = null,
    val exercises: List<ExerciseCardState> = emptyList(),
    val skippedLifts: List<SkippedLift> = emptyList(),
    val skippedPartners: Map<Long, SkippedLift> = emptyMap(),
    val cardio: CardioCardState? = null,
    val cardioLeads: Boolean = false,
    val timedRep: TimedRepState? = null,
    val hold: HoldState? = null,
    val executionBusy: Boolean = false,
    val executionBlockedReason: WorkoutBlockedReason? = null,
    val cardioRunning: Boolean = false,
    val keepScreenOn: KeepScreenOn = KeepScreenOn.WHILE_TIMER,
    val rest: RestUiState? = null,
    val watchStatus: WatchStatus? = null,
    val remoteTick: RemoteTick? = null,
    val workout: WorkoutState? = null,
    /** True while the viewed day has lifts and no started workout: no row, or this day's draft. */
    val beforeStart: Boolean = false,
    val startSheet: Boolean = false,
    val effortScale: EffortScale = EffortScale.WORDS,
    val warmUpMoves: List<Move> = emptyList(),
    val coolDownMoves: List<Move> = emptyList(),
    val warmUp: RoutineSheetState? = null,
    val coolDown: RoutineSheetState? = null,
) {
    val timedRepRunning: Boolean = timedRep?.event?.awaitingCount == false
    val executionKeepsScreenOn: Boolean =
        keepScreenOn.holds(workoutOpen = false, timerRunning = timedRepRunning || hold != null || cardioRunning)

    val doneSets: Int = runSetCounts?.done
        ?: (exercises.sumOf { card ->
            card.rows.count { it.done } + card.rows.count { it.partner?.done == true }
        } + retiredRoundDoneCount)

    val canFinish: Boolean = exercises.any { card ->
        card.rows.any { it.done || it.partner?.done == true }
    } || retiredTracks.any { it.doneCount > 0 } || cardio?.loggedSeconds != null

    val finishBlockedReason: WorkoutBlockedReason? get() =
        if (canFinish) null else WorkoutBlockedReason.NO_LOGGED_SETS

    val cardioStartBlockedReason: WorkoutBlockedReason? get() = when {
        cardioStartEnabled -> null
        executionBlockedReason != null -> executionBlockedReason
        timedRepRunning || hold != null || cardio?.run?.running == true -> WorkoutBlockedReason.TIMER_RUNNING
        cardio?.run?.saveFailed == true -> WorkoutBlockedReason.CARDIO_NOT_SAVED
        cardio?.run?.complete == true -> WorkoutBlockedReason.CARDIO_SAVING
        else -> null
    }

    val totalSets: Int = runSetCounts?.total ?: exercises.sumOf { card ->
        card.rows.size + card.rows.count { it.partner != null }
    }

    private val exercisePages: List<WorkoutPage> = exercises.map { card ->
        WorkoutPage(card.programExerciseId, card.title, card.rows.count { it.done }, card.rows.size, card.allDone)
    }
    private val cardioPages: List<WorkoutPage> = listOfNotNull(cardio?.let {
        WorkoutPage(null, it.label, if (it.loggedSeconds != null) 1 else 0, 1, it.loggedSeconds != null)
    })
    val pages: List<WorkoutPage> = if (cardioLeads) cardioPages + exercisePages else exercisePages + cardioPages
    val currentPage: Int = DayScreenBuilder.currentPage(pages.map { it.key }, selectedPageKey, exercises,
        cardioLeads, cardio?.loggedSeconds != null)

    val workoutCard: ExerciseCardState? get() = exercises.firstOrNull {
        "exercise:${it.programExerciseId}" == (workout?.shown as? WorkoutSelection.Shown.Group)?.key
    }

    val workoutNextPage: WorkoutPage? get() {
        val key = WorkoutSelection.next(
            pages.map { WorkoutSelection.Group(it.key, it.allDone) },
            (workout?.shown as? WorkoutSelection.Shown.Group)?.key,
        )
        return pages.firstOrNull { it.key == key }
    }

    val workoutAllLogged: Boolean get() = workout?.shown == WorkoutSelection.Shown.AllLogged ||
        workout?.completedView == true && workoutNextPage == null

    internal val cardioStartEnabled: Boolean get() =
        !executionBusy || cardio?.run?.let { run -> !run.running && !run.complete } == true

    val workoutClockChip: ClockChip?
        @Composable get() {
            val shown = workout?.shown as? WorkoutSelection.Shown.Group
            if (workout == null) return null
            timedRep?.let { timed ->
                val event = timed.event
                val key = "exercise:${event.programExerciseId}"
                if (shown?.key == key) return@let
                val title = exercises.firstOrNull { it.programExerciseId == event.programExerciseId }?.title ?: return null
                return ClockChip(key, if (event.awaitingCount) {
                    stringResource(R.string.workout_clock_chip_enter_reps, title)
                } else stringResource(R.string.workout_clock_chip, title, formatCardioTime(timed.remainingSeconds)))
            }
            hold?.let { held ->
                val key = "exercise:${held.event.programExerciseId}"
                if (shown?.key == key) return@let
                val title = exercises.firstOrNull { it.programExerciseId == held.event.programExerciseId }?.title ?: return null
                return ClockChip(key, stringResource(R.string.workout_clock_chip, title, formatCardioTime(held.heldSeconds)))
            }
            val cardio = cardio ?: return null
            val run = cardio.run?.takeIf { it.running } ?: return null
            if (shown?.key == "cardio") return null
            val end = run.block.sets.getOrNull(run.position)?.end
            val seconds = if (end is Measure.Time && !run.block.openEnded) cardio.stepSecondsLeft else cardio.elapsedSeconds
            return ClockChip("cardio", stringResource(R.string.workout_clock_chip,
                cardioActivityName(run.block.activity), formatCardioTime(seconds)))
        }
}

enum class WorkoutBlockedReason { NO_LOGGED_SETS, TIMER_RUNNING, CARDIO_NOT_SAVED, CARDIO_SAVING, CARDIO_IN_PROGRESS }

@Immutable
data class ClockChip(val key: String, val label: String)

data class WorkoutFocus(val programExerciseId: Long, val slot: String, val index: Int)

data class UndoOffer(
    val programExerciseId: Long,
    val slot: String,
    val index: Int,
    val title: String,
    val set: LoggedSet,
    val tracking: TrackingType,
    val unit: WeightUnit,
    val partnerSet: LoggedSet? = null,
)

data class WorkoutState(
    val shown: WorkoutSelection.Shown,
    val focus: WorkoutFocus?,
    val completedView: Boolean,
    val undo: UndoOffer?,
    val lastCompletionMillis: Long?,
    val review: WorkoutReview? = null,
)

/** The logged row on the card; effortSlots show an effort row, unrated lack a rating and hold the big button. */
data class WorkoutReview(val programExerciseId: Long, val index: Int, val effortSlots: List<String>, val unrated: List<String>)

/** The phone never asks for effort on warm-up ramps. */
internal fun showsEffort(kind: SetKind, tracking: TrackingType, seconds: Int = 0): Boolean =
    kind != SetKind.RAMP && (tracking != TrackingType.TIMED || seconds > 0)

internal fun EffortScale.forTracking(tracking: TrackingType): EffortScale =
    if (tracking == TrackingType.TIMED) EffortScale.WORDS else this

/** The hold timer's target for this set in seconds, or 0: only a TIMED main outside a superset has one. */
internal fun ExerciseCardState.holdTargetSeconds(index: Int): Int =
    if (tracking == TrackingType.TIMED && !isSuperset && timedRepDurationSeconds == null) {
        rows.getOrNull(index)?.let { row ->
            benchmark?.let { it.ceilingSeconds ?: HoldClock.PAST_TARGET_LIMIT_SECONDS } ?: row.seconds
        } ?: 0
    } else 0

/** Saved as one value; DayUiState exposes only an identity resolved against its day. */
@Parcelize
@Immutable
data class OpenSheet(
    val dayId: String,
    val kind: Kind,
    val programExerciseId: Long,
    val setIndex: Int,
    val cardioRunId: String? = null,
    val slot: String = Slot.MAIN,
) : Parcelable {
    enum class Kind { EDITOR, TIMED_REP_COUNT, CARDIO_DISTANCE, CARDIO_SETS, LIFT, DAY, SWAP }
}

@Parcelize
data class PendingPlanSwap(
    val dayId: String,
    val planId: Long,
    val programExerciseId: Long,
    val slot: String,
    val exerciseId: String,
    val title: String,
    val doneCount: Int,
) : Parcelable

@Parcelize
data class WorkoutSwapUndo(
    val dayId: String,
    val runId: String,
    val programExerciseId: Long,
    val slot: String,
    val previousExerciseId: String,
    val exerciseId: String,
    val title: String,
    val skippedSlot: String? = null,
) : Parcelable

/** A distinct draft sentinel lets the sheet initialize once without replacing an explicit zero or blank. */
internal const val UNINITIALIZED_TIMED_REP_DRAFT = "-1"

/** One atomic saved event: anchors while running, then a count draft and sheet delivery state. */
@Parcelize
@Immutable
data class TimedRepEvent(
    val dayId: String,
    val programExerciseId: Long,
    val exerciseId: String,
    val setIndex: Int,
    val durationSeconds: Int,
    val wallStart: Long,
    val elapsedStart: Long,
    val bootCount: Int,
    val awaitingCount: Boolean = false,
    val draft: String = UNINITIALIZED_TIMED_REP_DRAFT,
    val sheetRequested: Boolean = false,
    val sheetDismissed: Boolean = false,
) : Parcelable {
    val count: Int? get() = draft.takeIf { it.isNotBlank() && it.all(Char::isDigit) }?.toIntOrNull()
}

@Immutable
data class TimedRepState(val event: TimedRepEvent, val remainingSeconds: Int) {
    val requestsSheet: Boolean = event.awaitingCount && !event.sheetRequested && !event.sheetDismissed
}

/** One hold in progress (#628): the running side's clock anchors and what each finished side held.
 *  Saved under its own key; never add, drop or reorder a field. */
@Parcelize
@Immutable
data class HoldEvent(
    val dayId: String,
    val programExerciseId: Long,
    val exerciseId: String,
    val setIndex: Int,
    val targetSeconds: Int,
    val sides: Int,
    val startedAtMillis: Long,
    val wallStart: Long,
    val elapsedStart: Long,
    val bootCount: Int,
    val running: Boolean = true,
    val heldSeconds: List<Int> = emptyList(),
    val endedAtMillis: Long = 0L,
) : Parcelable {
    val complete: Boolean get() = heldSeconds.size >= sides
    val sideNumber: Int get() = (heldSeconds.size + 1).coerceAtMost(sides)
    val loggedSeconds: Int get() = HoldClock.loggedSeconds(heldSeconds)
}

@Immutable
data class HoldState(val event: HoldEvent, val heldSeconds: Int) {
    val fraction: Float get() = HoldClock.fraction(heldSeconds, event.targetSeconds)
}

@Immutable
data class RestUiState(val rest: PhoneRest, val remainingSeconds: Int, val remainingFraction: Float, val over: Boolean)

enum class WatchStatusKind { ACTIVE, SYNCING, OFFLINE_QUEUED }
data class WatchStatus(val kind: WatchStatusKind, val changeCount: Int = 0)

@Immutable
data class SprintRepUi(val armed: Boolean, val beepElapsedMillis: Long?,
                       val protocol: cloud.trotter.log.strength.domain.model.StartProtocol,
                       val lastTime: cloud.trotter.log.strength.domain.model.SprintTime?)

@Immutable
data class CardioCardState(
    val label: String,
    val detail: String,
    val hard: Boolean,
    val phase: CardioPhase = CardioPhase.SUGGESTION,
    val currentStepLabel: String? = null,
    val stepSecondsLeft: Int = 0,
    val elapsedSeconds: Int = 0,
    /** Most recent logged session for this day, derived from Room history. */
    val loggedSeconds: Int? = null,
    val loggedSessionId: Long? = null,
    val loggedSets: List<cloud.trotter.log.strength.domain.model.CardioSetResult> = emptyList(),
    val block: cloud.trotter.log.strength.domain.model.CardioBlock? = null,
    val run: CardioRun? = null,
    val distanceRuns: List<CardioRun> = emptyList(),
    val canAddSet: Boolean = true,
    val finishesDay: Boolean = false,
    val runDistanceText: String = "",
    val distanceUnit: DistanceUnit = DistanceUnit.MI,
    val loggedActivity: cloud.trotter.log.strength.domain.model.CardioActivity? = null,
    val distanceInputs: List<CardioDistanceInput> = emptyList(),
    val readingInputs: List<CardioReadingInput> = emptyList(),
    val nextTime: CardioNextTime? = null,
    val canSwap: Boolean = false,
    val gps: CardioGpsState? = null,
    val gpsAsk: Boolean = false,
    val gpsSets: List<CardioGpsSet> = emptyList(),
    val sprint: SprintStartUi? = null,
    val sprintRep: SprintRepUi? = null,
)

data class SprintStartUi(val dose: SprintDose, val shortenedReps: Int?, val daysSince: Long?, val spacingDays: Int,
                         val reason: SpacingReason, val nineTenths: Boolean)

enum class GpsShown { READY, FINDING, LIVE, NO_SIGNAL, PARTIAL, LOCATION_OFF }
@Immutable
data class CardioGpsState(val shown: GpsShown, val metres: Int? = null, val metresPerSecond: Double? = null)

enum class CardioPhase { SUGGESTION, EXECUTING }

@Immutable
sealed interface HeaderTarget {
    val display: String
    /** [eachSideOf] for this slot. */
    val eachSide: Boolean

    data class Goal(override val display: String, override val eachSide: Boolean = false) : HeaderTarget

    data class Prescribed(
        override val display: String,
        val repAim: Int? = null,
        override val eachSide: Boolean = false,
        val resistance: String = "",
    ) : HeaderTarget
}

/** Derived every build; accepting an offer writes the working rows. */
@Immutable
sealed interface NextTime {
    data object Repeat : NextTime
    data class Offer(val label: String, val applied: Boolean, val offerLb: Double, val openingLb: Double, val reps: Int? = null, val calibration: Boolean = false, val seconds: Int? = null) : NextTime
}

/** One exercise card (spec §8.2). [Immutable] for the reason [DayUiState]
 *  carries it: this is the type the set screen compares between updates. */
@Immutable
data class ExerciseCardState(
    val programExerciseId: Long,
    /** The slot's stored position for UI callbacks; removal can leave gaps. */
    val position: Int,
    val title: String,
    val note: String = "",
    val isSuperset: Boolean,
    /** Generated GOAL or authored target, already formatted in the user's unit. */
    val headerTarget: HeaderTarget,
    val perHand: Boolean,
    /** How the main track is logged and rendered (§2.1) — routes [SetRowState]
     *  through the matching stepper set. */
    val tracking: TrackingType = TrackingType.WEIGHTED,
    /** TIMED-only: whether the main track carries an optional added-load
     *  stepper (the entry's GOAL declares `addedWeightLb > 0`, §3). Always
     *  false for WEIGHTED/REPS. */
    val timedShowsWeight: Boolean = false,
    /** The superset partner's own tracking type; null for a plain exercise. A
     *  partner can track differently from the main (an accessory pairing a
     *  weighted main with a TIMED partner is valid — only mains are WEIGHTED). */
    val partnerTracking: TrackingType? = null,
    /** TIMED-only, mirrors [timedShowsWeight] for the superset partner track. */
    val partnerTimedShowsWeight: Boolean = false,
    /** "185 × 8"-style chip (PLAN.md A1 bonus); null when never performed before. */
    val lastTimeDisplay: String? = null,
    val lastTimeDate: String? = null,
    /** The day [lastTimeDisplay] came from when it is not this day (#629). */
    val lastTimeSource: LastSource? = null,
    val follows: String? = null,
    val partnerHeaderTarget: HeaderTarget? = null,
    val partnerLastTimeDisplay: String? = null,
    val partnerLastTimeDate: String? = null,
    val partnerLastTimeSource: LastSource? = null,
    val partnerSetLabels: List<String> = emptyList(),
    /** "245×5"-style all-time-best chip (docs/briefs/performance-profile.md Phase
     *  1); null when there is no record, or when it equals [lastTimeDisplay] —
     *  showing the same number twice is noise, not signal. */
    val personalRecordDisplay: String? = null,
    /** "+5 LB FROM LAST" / "MATCHED" / "FIRST LOG" over the TOP row; null
     *  when the card has no TOP set to compare — see
     *  [DayScreenBuilder.topSetComparison]. */
    val topSetComparison: String? = null,
    val allDone: Boolean,
    val rows: List<SetRowState>,
    val focusIndex: Int = DayScreenBuilder.focusIndex(rows),
    val summaryLine: String = "",
    val partnerTitle: String? = null,
    val partnerExerciseId: String? = null,
    val equipment: List<Equipment> = emptyList(),
    /** The Add weight / Remove weight menu (§4.2); null when this slot's
     *  exercise has no weighted-pair link at all. */
    val weightSwap: WeightSwapAffordance? = null,
    val exerciseId: String = "",
    val timedRepDurationSeconds: Int? = null,
    val timedRepAim: Int? = null,
    val marker: LiftMarker? = null,
    val partnerMarker: LiftMarker? = null,
    val workingHeading: String? = null,
    val partnerWorkingHeading: String? = null,
    val nextTime: NextTime? = null,
    val partnerNextTime: NextTime? = null,
    val rule: SlotRule? = null,
    val liftGoal: LiftGoal = LiftGoal.STRENGTH,
    val benchmark: BenchmarkUi? = null,
    val prescriptionTest: BenchmarkLink? = null,
    /** [cloud.trotter.log.strength.domain.library.ExerciseEntry.pressureCaution] of the performed exercise. */
    val holdPressureCaution: Boolean = false,
)

@Immutable
data class BenchmarkUi(val name: String, val windowSeconds: Int?, val ceilingSeconds: Int?)

data class BenchmarkLink(val ref: TestRef, val event: TestEvent)

/**
 * One logged round. For a plain exercise [partner] is null. For a superset the
 * partner sub-row is aligned at the same index; each track has its own done tick.
 */
data class SetRowState(
    val index: Int,
    val kindLabel: String,
    val isTop: Boolean,
    val weightDisplay: Double,
    val reps: Int,
    val done: Boolean,
    val partner: PartnerRowState? = null,
    /** TIMED tracks only; 0 (ignored) for WEIGHTED/REPS (§2.2). */
    val seconds: Int = 0,
    val justTickedRemotely: Boolean = false,
    val remoteTickEventId: Long = 0L,
    val kind: SetKind = SetKind.WORK,
    /** One-based among RAMP sets; zero for every other kind. */
    val rampIndex: Int = 0,
    val rir: Int? = null,
    /** The MAIN row's left and right (#644). */
    val sides: Sides? = null,
) {
    val complete: Boolean get() = done && partner?.done != false
    val nextSlot: String get() = if (done && partner?.done == false) Slot.SS else Slot.MAIN
}

/**
 * The removed round retained while the Snackbar offers Undo. [DayViewModel]
 * restores its tracks at [index], rather than appending a fresh copy.
 *
 * Deliberately not persisted: a five-second courtesy is not user data, and an
 * offer that came back after process death would restore a round to a workout
 * the lifter may already have left.
 */
data class RemovedSet(
    val programExerciseId: Long,
    /** Where the row sat, and where undo puts it back. */
    val index: Int,
    /** The day it was removed from — undo after a day switch must not write
     *  the row into whatever day the user moved to. */
    val dayId: String,
    val runId: String,
    val main: LoggedSet,
    /** The superset partner's row for the same round, when there was one. */
    val partner: LoggedSet? = null,
    val testResult: TestResultEntity? = null,
)

/** The superset partner's values and done tick for one round. */
data class PartnerRowState(
    val weightDisplay: Double,
    val reps: Int,
    val done: Boolean = false,
    /** TIMED tracks only; 0 (ignored) for WEIGHTED/REPS (§2.2). */
    val seconds: Int = 0,
    val rir: Int? = null,
)

/**
 * The Add weight / Remove weight menu affordance (§4.2), backed
 * by the existing slot swap ([cloud.trotter.log.strength.data.TrackerRepository.swapExerciseById]),
 * never a new mutation path. [isRemove] picks the pill's label/copy; the
 * affordance itself is exactly "swap this slot to [targetExerciseId]".
 */
data class WeightSwapAffordance(
    val targetExerciseId: String,
    val targetName: String,
    val isRemove: Boolean,
)

/** Stable workout group identity; cardio has no exercise id and uses its own string key. */
@Immutable
data class WorkoutPage(val exerciseId: Long?, val title: String, val done: Int, val total: Int, val allDone: Boolean) {
    val key: String = exerciseId?.let { "exercise:$it" } ?: "cardio"
}

data class SkippedLift(val programExerciseId: Long, val position: Int, val title: String, val slot: String = Slot.MAIN)
