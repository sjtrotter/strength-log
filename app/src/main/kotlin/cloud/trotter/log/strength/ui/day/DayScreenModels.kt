package cloud.trotter.log.strength.ui.day

import androidx.compose.runtime.Immutable
import cloud.trotter.log.strength.domain.library.TrackingType
import cloud.trotter.log.strength.domain.model.LoggedSet
import cloud.trotter.log.strength.domain.units.WeightUnit
import cloud.trotter.log.strength.domain.standards.PhoneRest
import cloud.trotter.log.strength.sync.RemoteTick

/**
 * Immutable render model for the whole day screen (UDF: the ViewModel's single output).
 *
 * [Immutable] is load-bearing, not decoration (#156). Without it the Compose
 * compiler reads `List<ExerciseCardState>` and a `:domain` value type it can't
 * see the stability of, calls the whole class unstable, and falls back to
 * *identity* comparison — so a rebuilt state that differs in one set's tick
 * recomposes every card on screen. Every model in this file is built once by
 * [DayScreenBuilder]/[DayViewModel] and never mutated, which is exactly what the
 * annotation promises; keep it that way (no `MutableList` here, ever).
 */
@Immutable
data class DayUiState(
    val hasProgram: Boolean = false,
    /** True only while the program is still being read (#127). `hasProgram =
     *  false` with this false is the *answer* "there is no program" — a state
     *  the lifter has to act on — and the screen says so instead of preparing
     *  forever. */
    val loading: Boolean = false,
    val tabs: List<DayTab> = emptyList(),
    val viewDayId: String? = null,
    val dayIndex: Int = 0,
    val dayTitle: String = "",
    val emphasisLine: String = "",
    val unit: WeightUnit = WeightUnit.LB,
    val suggestedDayId: String? = null,
    /** The day DONE advances to (rotation successor of the viewed day). */
    val nextDayId: String? = null,
    val exercises: List<ExerciseCardState> = emptyList(),
    val cardio: CardioCardState? = null,
    val keepScreenOn: Boolean = false,
    val showMainHelper: Boolean = true,
    val showSupersetHelper: Boolean = true,
    val rest: RestUiState? = null,
    val watchStatus: WatchStatus? = null,
    val remoteTick: RemoteTick? = null,
) {
    /** True when the viewed day isn't the suggested-next one (spec §8.2 override note). */
    val isOverride: Boolean
        get() = suggestedDayId != null && viewDayId != null && viewDayId != suggestedDayId

    /** Rounds ticked across the day's MAIN tracks — the same count the widget,
     *  the watch and Today show, since a superset partner rides inside its
     *  round rather than adding one (glance-surfaces.md §4.2).
     *
     *  Counted once here rather than on every read (#156): these two are read
     *  four times over in the header alone — the status line takes both, and
     *  the progress rule's fraction takes both again — so as `get()` accessors
     *  they walked every row of every card four times per composition of the
     *  chrome. They are derived from `exercises`, which cannot change under
     *  them, so once per state is the honest number of times to count. */
    val doneSets: Int = exercises.sumOf { card -> card.rows.count { it.done } }

    /** Rounds this day holds in total, counted the same way as [doneSets]. */
    val totalSets: Int = exercises.sumOf { it.rows.size }
}

@Immutable
data class RestUiState(val rest: PhoneRest, val remainingSeconds: Int, val remainingFraction: Float, val over: Boolean)

enum class WatchStatusKind { ACTIVE, SYNCING, OFFLINE_QUEUED }
data class WatchStatus(val kind: WatchStatusKind, val changeCount: Int = 0)

enum class DoneButtonState { ALL_DONE, PARTIAL, NOTHING_LOGGED }

/** One entry in the day tab strip. */
data class DayTab(
    val dayId: String,
    val dayIndex: Int,
    val isSuggested: Boolean,
    val isSelected: Boolean,
)

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
)

enum class CardioPhase { SUGGESTION, EXECUTING, OVERRUN }

/** One exercise card (spec §8.2). [Immutable] for the reason [DayUiState]
 *  carries it: this is the type the LazyColumn compares per item. */
@Immutable
data class ExerciseCardState(
    val programExerciseId: Long,
    /** The slot's stable position in the day (spec §8.3) — the key
     *  [DayEditActions.onSwap]/[TrackerRepository.swapExercise][
     *  cloud.trotter.log.strength.data.TrackerRepository.swapExercise]
     *  needs, which the ADD/REMOVE WEIGHT pill ([weightSwap]) reuses verbatim. */
    val position: Int,
    val title: String,
    val note: String = "",
    val isMain: Boolean,
    val isSuperset: Boolean,
    val hasWarmupHint: Boolean,
    /** GOAL number already formatted in the user's unit (read-only). */
    val goalDisplay: String,
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
    /** "185×8"-style chip (PLAN.md A1 bonus); null when never performed before. */
    val lastTimeDisplay: String? = null,
    /** "245×5"-style all-time-best chip (docs/briefs/performance-profile.md Phase
     *  1); null when there is no record, or when it equals [lastTimeDisplay] —
     *  showing the same number twice is noise, not signal. */
    val personalRecordDisplay: String? = null,
    /** "+5 LB FROM LAST" / "MATCHED" / "FIRST LOG" over the TOP row (issue
     *  #127); null when the card has no TOP set to compare — see
     *  [DayScreenBuilder.topSetComparison]. */
    val topSetComparison: String? = null,
    /** "Plates: 45 + 25 + 2.5 a side"-style line (issue #101), keyed to the
     *  first undone MAIN-slot set's weight; null for non-barbell exercises,
     *  finished cards, or an unloadable weight — see [DayScreenBuilder.plateLine]. */
    val plateLine: cloud.trotter.log.strength.ui.text.UiText.DayPlate? = null,
    val allDone: Boolean,
    val collapsed: Boolean,
    val collapsedSummary: String,
    val rows: List<SetRowState>,
    /** The ADD WEIGHT / REMOVE WEIGHT pill (§4.2); null when this slot's
     *  exercise has no weighted-pair link at all. */
    val weightSwap: WeightSwapAffordance? = null,
)

/**
 * One logged round. For a plain exercise [partner] is null. For a superset the
 * partner sub-row is aligned at the same index and there is a single done tick per
 * round (checking the round dims both rows) — spec §8.2.
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
    val isNext: Boolean = false,
    val justTickedRemotely: Boolean = false,
    val remoteTickEventId: Long = 0L,
)

/**
 * The row a × just took off a card, for as long as the undo offer stands
 * (#124). The screen reads [programExerciseId] and [index] to draw the offer in
 * the slot the row left; [DayViewModel] reads the rest to put the row back
 * exactly as it was, rather than appending a fresh copy.
 *
 * Deliberately not persisted (same reasoning as [CascadeCeremony]): a
 * five-second courtesy is not user data, and an offer that came back from the
 * dead after process death would be a worse bug than the one it fixes.
 */
data class RemovedSet(
    val programExerciseId: Long,
    /** Where the row sat, and where undo puts it back. */
    val index: Int,
    /** The day it was removed from — undo after a day switch must not write
     *  the row into whatever day the user moved to. */
    val dayId: String,
    val main: LoggedSet,
    /** The superset partner's row for the same round, when there was one. */
    val partner: LoggedSet? = null,
)

/** The superset partner's independent weight/reps for one round (no own tick). */
data class PartnerRowState(
    val weightDisplay: Double,
    val reps: Int,
    /** TIMED tracks only; 0 (ignored) for WEIGHTED/REPS (§2.2). */
    val seconds: Int = 0,
)

/**
 * The ADD WEIGHT / REMOVE WEIGHT card affordance (§4.2): a labeled pill backed
 * by the existing slot swap ([cloud.trotter.log.strength.data.TrackerRepository.swapExercise]),
 * never a new mutation path. [isRemove] picks the pill's label/copy; the
 * affordance itself is exactly "swap this slot to [targetExerciseId]".
 */
data class WeightSwapAffordance(
    val targetExerciseId: String,
    val targetName: String,
    val isRemove: Boolean,
)
