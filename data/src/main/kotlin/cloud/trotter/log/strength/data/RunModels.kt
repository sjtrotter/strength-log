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
    val dropped: Boolean = false,
    val skippedAt: Long = 0L,
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
    val droppedPartners: Set<Long> = emptySet(),
    val skippedLifts: Set<Long> = emptySet(),
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

internal fun <T> Iterable<T>.isPartnerDropped(
    programExerciseId: Long,
    id: (T) -> Long?,
    slot: (T) -> String,
    dropped: (T) -> Boolean,
): Boolean = any { id(it) == programExerciseId && slot(it) == Slot.SS && dropped(it) }

fun OpenRun.isPartnerDropped(programExerciseId: Long): Boolean =
    tracks.isPartnerDropped(programExerciseId, RunTrack::programExerciseId, RunTrack::slot, RunTrack::dropped)

internal fun <T> Iterable<T>.isSlotSkipped(
    programExerciseId: Long,
    targetSlot: String,
    id: (T) -> Long?,
    slot: (T) -> String,
    dropped: (T) -> Boolean,
    retired: (T) -> Boolean,
): Boolean = any { id(it) == programExerciseId && slot(it) == targetSlot && dropped(it) } &&
    none { id(it) == programExerciseId && slot(it) == targetSlot && !retired(it) }

fun OpenRun.isSlotSkipped(programExerciseId: Long, slot: String): Boolean =
    tracks.isSlotSkipped(programExerciseId, slot, RunTrack::programExerciseId, RunTrack::slot, RunTrack::dropped, RunTrack::retired)

fun OpenRun.isLiftSkipped(programExerciseId: Long): Boolean = isSlotSkipped(programExerciseId, Slot.MAIN)

internal fun <T> Iterable<T>.latestSkippedMain(
    programExerciseId: Long,
    id: (T) -> Long?,
    slot: (T) -> String,
    dropped: (T) -> Boolean,
    updatedAt: (T) -> Long,
): T? = filter { id(it) == programExerciseId && slot(it) == Slot.MAIN && dropped(it) }.maxByOrNull(updatedAt)

fun OpenRun.skippedMainTrack(programExerciseId: Long): RunTrack? =
    tracks.latestSkippedMain(programExerciseId, RunTrack::programExerciseId, RunTrack::slot, RunTrack::dropped, RunTrack::skippedAt)
