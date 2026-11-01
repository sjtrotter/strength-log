package cloud.trotter.log.strength.data

import cloud.trotter.log.strength.data.db.entity.PlanEntity
import cloud.trotter.log.strength.data.db.entity.Slot
import cloud.trotter.log.strength.domain.model.LoggedSet

/** Initial working values for a slot with no stored log. */
data class RunSeed(val programExerciseId: Long, val slot: String, val sets: List<LoggedSet>)

enum class ExerciseSwapResult { CHANGED, ALREADY_THERE, REJECTED }

sealed interface RunStartResult {
    data class Started(val runId: String) : RunStartResult
    data class AlreadyOpen(val runId: String) : RunStartResult
    data class OtherRunOpen(val runId: String, val planId: Long?, val dayId: String?) : RunStartResult
    data object NoStrengthSlots : RunStartResult
}

/** An edit addressed to a captured exercise or the current non-retired slot. */
data class TrackEdit(
    val programExerciseId: Long,
    val slot: String,
    val exerciseId: String? = null,
    /** The transform replaces the whole list from the screen rather than editing rows by index. */
    val replacesAll: Boolean = false,
    /** Pure. Null leaves everything unwritten. */
    val transform: (List<LoggedSet>) -> List<LoggedSet>?,
)

/** Outcome of an atomic group of workout edits. */
sealed interface RunWriteResult {
    /** The edits committed to this run, optionally starting it. */
    data class InRun(val runId: String, val started: Boolean) : RunWriteResult
    /** The edits committed to the day's working values. */
    data object WorkingValues : RunWriteResult
    /** A transform declined the entire group of edits. */
    data object Unchanged : RunWriteResult
    /** The identity or track does not match an editable workout. */
    data object Rejected : RunWriteResult
}

/** One captured track and its actual rows. */
data class RunTrack(
    val id: Long,
    val position: Int,
    val programExerciseId: Long?,
    val slot: String,
    val exerciseId: String,
    val retired: Boolean,
    val planned: List<LoggedSet>,
    val sets: List<LoggedSet>,
)

/** The open workout, independent of the selected plan and rotation. */
data class OpenRun(
    val id: String,
    val planId: Long?,
    val dayId: String?,
    val dayTitle: String,
    val planName: String?,
    val startedAt: Long,
    val tracks: List<RunTrack>,
)

/** Active rows and retired work for a day's open run or working values. */
data class DayTracks(
    val runId: String?,
    val runOpen: Boolean,
    val tracks: List<LoggedSlot>,
    /** Tracks displaced by plan edits that still hold done work. */
    val retired: List<RunTrack> = emptyList(),
) {
    /** Done rounds on retired MAIN tracks, excluding superset partner duplicates. */
    val retiredRoundsDone: Int
        get() = retired.filter { Slot.isRound(it.slot) }.sumOf { track -> track.sets.count { it.done } }
}

sealed interface TodayTarget {
    data class Day(val plan: PlanEntity, val dayId: String?) : TodayTarget
    data object NoPlans : TodayTarget
    data object NothingOnToday : TodayTarget
}
