package cloud.trotter.log.strength.data.run

import cloud.trotter.log.strength.data.db.entity.ExerciseLogEntity
import cloud.trotter.log.strength.data.db.entity.ProgramDayEntity
import cloud.trotter.log.strength.data.db.entity.ProgramExerciseEntity
import cloud.trotter.log.strength.data.db.entity.RunTrackEntity
import cloud.trotter.log.strength.data.db.entity.SessionSetEntity
import cloud.trotter.log.strength.data.db.entity.Slot
import cloud.trotter.log.strength.data.db.entity.WorkoutRunEntity
import cloud.trotter.log.strength.data.db.entity.WorkoutSessionEntity
import cloud.trotter.log.strength.data.serialization.SetJson
import cloud.trotter.log.strength.domain.model.LoggedSet
import cloud.trotter.log.strength.domain.seeding.CarryForward

data class LegacyLog(
    val planId: Long, val dayId: String, val programExerciseId: Long, val slot: String,
    val sets: List<LoggedSet>, val checkDate: String, val updatedAt: Long,
)

data class SplitInput(
    val logs: List<LegacyLog>, val slots: List<ProgramExerciseEntity>, val days: List<ProgramDayEntity>,
    val planNames: Map<Long, String>, val exerciseName: (String) -> String, val today: String,
    val now: Long, val openRun: Pair<Long?, String?>?, val mint: () -> String,
)

data class ArchivedDay(val session: WorkoutSessionEntity, val sets: List<SessionSetEntity>)
data class DayClose(val planId: Long, val dayId: String, val nextRunId: String, val closedAt: Long)
data class SplitResult(
    val logs: List<ExerciseLogEntity>, val run: WorkoutRunEntity?, val tracks: List<RunTrackEntity>,
    val archived: List<ArchivedDay>, val closes: List<DayClose>,
)

object LegacyLiveLogSplit {
    fun split(input: SplitInput): SplitResult {
        val normalized = input.logs.map { log ->
            if (log.checkDate == input.today) log else log.copy(sets = CarryForward.cleared(log.sets))
        }
        val days = input.days.associateBy { it.planId to it.dayId }
        val slots = input.slots.associateBy { it.id }
        val candidates = normalized.groupBy { it.planId to it.dayId }.filter { (key, logs) ->
            key in days && key != input.openRun && logs.any { log -> log.sets.any { it.done } }
        }
        fun done(logs: List<LegacyLog>) = logs.flatMap { it.sets }.filter { it.done }
        fun start(logs: List<LegacyLog>): Long = done(logs).mapNotNull { it.startedAtMillis }.minOrNull()
            ?: done(logs).mapNotNull { it.completedAtMillis }.minOrNull()
            ?: logs.filter { log -> log.sets.any { it.done } }.minOf { it.updatedAt }
        fun exercise(log: LegacyLog): Pair<ProgramExerciseEntity, String>? {
            val slot = slots[log.programExerciseId]?.takeIf { it.planId == log.planId && it.dayId == log.dayId }
                ?: return null
            val id = when (log.slot) {
                Slot.MAIN -> slot.exerciseId
                Slot.SS -> slot.supersetExerciseId
                else -> null
            } ?: return null
            return slot to id
        }
        val winner = if (input.openRun != null) null else candidates.entries.maxWithOrNull(
            compareBy<Map.Entry<Pair<Long, String>, List<LegacyLog>>> {
                it.value.maxOf { log -> log.sets.filter { it.done }.maxOfOrNull { set ->
                    set.completedAtMillis ?: log.updatedAt
                } ?: Long.MIN_VALUE }
            }.thenBy { it.value.filter { log -> log.sets.any { it.done } }.maxOf { log -> log.updatedAt } },
        )?.key
        var run: WorkoutRunEntity? = null
        val tracks = mutableListOf<RunTrackEntity>()
        val archived = mutableListOf<ArchivedDay>()
        val closes = mutableListOf<DayClose>()
        candidates.forEach { (key, logs) ->
            val day = days.getValue(key)
            if (key == winner) {
                run = WorkoutRunEntity(day.nextRunId, day.planId, day.dayId, day.title,
                    input.planNames[day.planId], start(logs))
                logs.forEach logLoop@{ log ->
                    val (slot, id) = exercise(log) ?: return@logLoop
                    tracks.add(RunTrackEntity(
                        id = 0, runId = day.nextRunId, position = slot.position, programExerciseId = slot.id,
                        slot = log.slot, exerciseId = id,
                        plannedJson = SetJson.encodeSets(CarryForward.cleared(log.sets)),
                        setsJson = SetJson.encodeSets(log.sets.mapIndexed { i, set -> set.copy(plannedIndex = i) }),
                        updatedAt = log.updatedAt,
                    ))
                }
            } else {
                val session = WorkoutSessionEntity(
                    id = 0, dayId = day.dayId, dayTitle = day.title, startedAt = start(logs),
                    completedAt = done(logs).mapNotNull { it.completedAtMillis }.maxOrNull()
                        ?: logs.filter { log -> log.sets.any { it.done } }.maxOf { it.updatedAt },
                    bodyweightLb = null, planId = day.planId, planName = input.planNames[day.planId], runId = null,
                )
                val sets = logs.flatMap { log ->
                    val (_, id) = exercise(log) ?: return@flatMap emptyList()
                    log.sets.mapIndexed { i, set ->
                        SessionSetEntity(
                            id = 0, sessionId = 0, exerciseId = id, exerciseName = input.exerciseName(id),
                            slot = log.slot, setIndex = i, kind = set.kind.name, weightLb = set.weightLb,
                            reps = set.reps, done = set.done, seconds = set.seconds,
                            startedAtMillis = set.startedAtMillis, completedAtMillis = set.completedAtMillis,
                        )
                    }
                }
                archived.add(ArchivedDay(session, sets))
                closes.add(DayClose(day.planId, day.dayId, input.mint(), input.now))
            }
        }
        val working = normalized.map { log ->
            ExerciseLogEntity(log.planId, log.dayId, log.programExerciseId, log.slot,
                SetJson.encodeSets(CarryForward.cleared(log.sets)), log.updatedAt)
        }
        return SplitResult(working, run, tracks, archived, closes)
    }
}
