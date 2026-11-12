package cloud.trotter.log.strength.data

import cloud.trotter.log.strength.data.db.entity.SessionSetEntity
import cloud.trotter.log.strength.data.db.entity.WorkoutSessionEntity
import cloud.trotter.log.strength.data.serialization.SessionPlanJson
import cloud.trotter.log.strength.data.serialization.SetDto
import cloud.trotter.log.strength.domain.model.LoggedSet
import cloud.trotter.log.strength.domain.model.SetKind

/** The same Finish inputs after its run has been replaced by an archive. */
data class ArchivedRun(val tracks: List<RunTrack>, val slots: List<ProgramSlot>) {
    companion object {
        fun of(session: WorkoutSessionEntity, sets: List<SessionSetEntity>, slots: List<ProgramSlot>): ArchivedRun {
            val tracks = session.plannedJson?.let(SessionPlanJson::decode).orEmpty().map { dto ->
                val planned = dto.sets.map(SetDto::toDomain)
                val actual = sets.filter { it.trackPosition == dto.position && it.slot == dto.slot && it.exerciseId == dto.exerciseId }
                    .sortedBy { it.setIndex }.map { row ->
                        LoggedSet(row.weightLb, row.reps, SetKind.valueOf(row.kind), row.done, seconds = row.seconds,
                            startedAtMillis = row.startedAtMillis, completedAtMillis = row.completedAtMillis,
                            plannedIndex = row.plannedIndex, rir = row.rir)
                    }
                val count = dto.liveSetCount ?: actual.size
                val rows = if (dto.retired || dto.dropped) actual else actual + List((count - actual.size).coerceAtLeast(0)) { index ->
                    (planned.getOrNull(actual.size + index) ?: planned.lastOrNull() ?: LoggedSet(0.0, 0, SetKind.WORK)).copy(done = false)
                }
                RunTrack(dto.position.toLong(), dto.position, dto.programExerciseId, dto.slot, dto.exerciseId,
                    dto.retired, planned, rows, dropped = dto.dropped, note = dto.note.orEmpty())
            }
            return ArchivedRun(tracks, slots)
        }
    }
}
