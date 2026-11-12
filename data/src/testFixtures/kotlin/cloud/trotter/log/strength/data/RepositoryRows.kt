package cloud.trotter.log.strength.data

import cloud.trotter.log.strength.data.db.StrengthDatabase
import cloud.trotter.log.strength.data.db.entity.ExerciseLogEntity
import cloud.trotter.log.strength.data.db.entity.RunTrackEntity
import cloud.trotter.log.strength.data.db.entity.Slot
import cloud.trotter.log.strength.data.prefs.SettingsStore
import cloud.trotter.log.strength.data.serialization.ParkedLogsJson
import cloud.trotter.log.strength.data.serialization.ParkedTrackDto
import cloud.trotter.log.strength.data.serialization.SetJson
import cloud.trotter.log.strength.data.serialization.SlotRuleJson
import cloud.trotter.log.strength.domain.model.LoggedSet
import cloud.trotter.log.strength.domain.model.MovementPattern
import cloud.trotter.log.strength.domain.model.SetStructure
import cloud.trotter.log.strength.domain.progression.LiftGoal
import cloud.trotter.log.strength.domain.progression.SlotRole
import cloud.trotter.log.strength.domain.progression.SlotRule

/** A rule for slots a test inserts straight into Room. Conversion skips ruled slots, so such a slot keeps today's
 *  lazy seeding: the state a structure change or a prescription edit leaves on this build. */
val FIXTURE_RULE_JSON: String = SlotRuleJson.encode(
    SlotRule.defaults(SlotRole.ACCESSORY, LiftGoal.STRENGTH, SetStructure.STRAIGHT, null, MovementPattern.CORE_FLEX),
)

/** Stores literal fixture rows without making them repository seed inputs. */
suspend fun StrengthDatabase.storeTestRows(
    dayId: String,
    programExerciseId: Long,
    slot: String,
    sets: List<LoggedSet>,
    runId: String? = null,
) {
    val exercise = requireNotNull(programDao().exercise(programExerciseId))
    val json = SetJson.encodeSets(sets)
    if (runId == null) {
        programDao().upsertLog(ExerciseLogEntity(
            exercise.planId, dayId, programExerciseId, slot, json, 0L,
        ))
        return
    }
    val current = runDao().tracks(runId).firstOrNull {
        it.programExerciseId == programExerciseId && it.slot == slot && !it.retired
    }
    if (current != null) {
        runDao().updateTrack(current.copy(setsJson = json))
    } else {
        val exerciseId = if (slot == Slot.SS) requireNotNull(exercise.supersetExerciseId) else exercise.exerciseId
        runDao().insertTrack(RunTrackEntity(
            id = 0, runId = runId, position = exercise.position,
            programExerciseId = programExerciseId, slot = slot, exerciseId = exerciseId,
            plannedJson = json, setsJson = json, updatedAt = 0L,
        ))
    }
}

suspend fun TrackerRepository.writeTestRows(
    runId: String,
    dayId: String,
    programExerciseId: Long,
    slot: String,
    sets: List<LoggedSet>,
): RunWriteResult {
    val planId = plans.planIdOfSlot(programExerciseId) ?: return RunWriteResult.Rejected
    return applyToRun(runId, planId, dayId, listOf(
        TrackEdit(programExerciseId, slot, createsMissing = true) { sets },
    ))
}

suspend fun TrackerRepository.writeTestRowsPaired(
    runId: String,
    dayId: String,
    programExerciseId: Long,
    main: List<LoggedSet>,
    ss: List<LoggedSet>,
): RunWriteResult {
    val planId = plans.planIdOfSlot(programExerciseId) ?: return RunWriteResult.Rejected
    return applyToRun(runId, planId, dayId, listOf(
        TrackEdit(programExerciseId, Slot.MAIN, createsMissing = true) { main },
        TrackEdit(programExerciseId, Slot.SS, createsMissing = true) { ss },
    ))
}

/** Replacing a program after migration requires a fresh conversion latch for the new fixture slots. */
suspend fun StrengthDatabase.convertTestPlans(settings: SettingsStore) {
    PlanRepository(this, settings).runPlanSettingsFixupIfNeeded()
}

/** Seeds persisted Plan mode from releases that allowed unprescribed lifts. Prescribed fixtures use the current writer. */
suspend fun TrackerRepository.keepToPlanFixture(db: StrengthDatabase, programExerciseId: Long) {
    if (setKeepToPlan(programExerciseId, true)) return
    val row = requireNotNull(db.programDao().exercise(programExerciseId))
    if (row.keepToPlan) return
    val parked = listOfNotNull(Slot.MAIN to row.exerciseId, row.supersetExerciseId?.let { Slot.SS to it })
        .mapNotNull { (slot, id) ->
            db.programDao().logForSlot(row.planId, row.dayId, row.id, slot)?.let { ParkedTrackDto(slot, id, it.setsJson) }
        }
    db.programDao().setKeepToPlan(row.id, true)
    if (parked.isNotEmpty()) db.programDao().setParkedLogs(row.id, ParkedLogsJson.encode(parked))
    db.programDao().deleteLogForSlot(row.planId, row.dayId, row.id, Slot.MAIN)
    db.programDao().deleteLogForSlot(row.planId, row.dayId, row.id, Slot.SS)
}
