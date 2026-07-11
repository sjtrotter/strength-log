package io.github.sjtrotter.strengthlog.ui.day

import io.github.sjtrotter.strengthlog.domain.library.TrackingType
import io.github.sjtrotter.strengthlog.domain.model.CardioSuggestion
import io.github.sjtrotter.strengthlog.domain.units.WeightUnit

/** Immutable render model for the whole day screen (UDF: the ViewModel's single output). */
data class DayUiState(
    val hasProgram: Boolean = false,
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
    val cardio: CardioSuggestion? = null,
    val keepScreenOn: Boolean = false,
) {
    /** True when the viewed day isn't the suggested-next one (spec §8.2 override note). */
    val isOverride: Boolean
        get() = suggestedDayId != null && viewDayId != null && viewDayId != suggestedDayId
}

/** One entry in the day tab strip. */
data class DayTab(
    val dayId: String,
    val dayIndex: Int,
    val isSuggested: Boolean,
    val isSelected: Boolean,
)

/** One exercise card (spec §8.2). */
data class ExerciseCardState(
    val programExerciseId: Long,
    /** The slot's stable position in the day (spec §8.3) — the key
     *  [DayEditActions.onSwap]/[TrackerRepository.swapExercise][
     *  io.github.sjtrotter.strengthlog.data.TrackerRepository.swapExercise]
     *  needs, which the ADD/REMOVE WEIGHT pill ([weightSwap]) reuses verbatim. */
    val position: Int,
    val title: String,
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
 * by the existing slot swap ([io.github.sjtrotter.strengthlog.data.TrackerRepository.swapExercise]),
 * never a new mutation path. [isRemove] picks the pill's label/copy; the
 * affordance itself is exactly "swap this slot to [targetExerciseId]".
 */
data class WeightSwapAffordance(
    val targetExerciseId: String,
    val targetName: String,
    val isRemove: Boolean,
)
