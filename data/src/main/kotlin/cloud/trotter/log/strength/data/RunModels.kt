package cloud.trotter.log.strength.data

import cloud.trotter.log.strength.data.db.entity.PlanEntity
import cloud.trotter.log.strength.domain.model.LoggedSet

/** Initial working values for a slot with no stored log. */
data class RunSeed(val programExerciseId: Long, val slot: String, val sets: List<LoggedSet>)

/** An edit addressed to a captured exercise or the current non-retired slot. */
data class TrackEdit(
    val programExerciseId: Long,
    val slot: String,
    val exerciseId: String? = null,
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
)

data class TodayTarget(val plan: PlanEntity, val dayId: String?)
