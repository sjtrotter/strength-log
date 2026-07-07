package io.github.sjtrotter.strengthlog.data

import androidx.room.withTransaction
import io.github.sjtrotter.strengthlog.data.catalog.ExerciseCatalog
import io.github.sjtrotter.strengthlog.data.checkmark.CheckmarkReset
import io.github.sjtrotter.strengthlog.data.db.StrengthDatabase
import io.github.sjtrotter.strengthlog.data.db.dao.CustomExerciseDao
import io.github.sjtrotter.strengthlog.data.db.dao.ProgramDao
import io.github.sjtrotter.strengthlog.data.db.dao.SessionDao
import io.github.sjtrotter.strengthlog.data.db.entity.CustomExerciseEntity
import io.github.sjtrotter.strengthlog.data.db.entity.ExerciseLogEntity
import io.github.sjtrotter.strengthlog.data.db.entity.ProgramDayEntity
import io.github.sjtrotter.strengthlog.data.db.entity.ProgramExerciseEntity
import io.github.sjtrotter.strengthlog.data.db.entity.SessionSetEntity
import io.github.sjtrotter.strengthlog.data.db.entity.Slot
import io.github.sjtrotter.strengthlog.data.db.entity.WorkoutSessionEntity
import io.github.sjtrotter.strengthlog.data.mapping.toDomain
import io.github.sjtrotter.strengthlog.data.mapping.toEntity
import io.github.sjtrotter.strengthlog.data.mapping.toEntry
import io.github.sjtrotter.strengthlog.data.prefs.SettingsStore
import io.github.sjtrotter.strengthlog.data.serialization.SetJson
import io.github.sjtrotter.strengthlog.domain.generator.ProgramGenerator
import io.github.sjtrotter.strengthlog.domain.generator.Rotation
import io.github.sjtrotter.strengthlog.domain.generator.WizardAnswers
import io.github.sjtrotter.strengthlog.domain.model.CardioPrefs
import io.github.sjtrotter.strengthlog.domain.model.Equipment
import io.github.sjtrotter.strengthlog.domain.model.LifterConfig
import io.github.sjtrotter.strengthlog.domain.model.LoggedSet
import io.github.sjtrotter.strengthlog.domain.model.MovementPattern
import io.github.sjtrotter.strengthlog.domain.model.Program
import io.github.sjtrotter.strengthlog.domain.model.ProgramExercise
import io.github.sjtrotter.strengthlog.domain.units.WeightUnit
import java.time.Clock
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * The single data-layer entry point (spec §7 surface, extended by PLAN.md
 * A1/A4/A6). Every read is a [Flow] off Room/DataStore; every mutation is a
 * suspend call that commits immediately, so no working truth ever lives only in
 * memory. Cross-table mutations run in one Room transaction.
 *
 * "Today" for the daily checkmark reset is the device-local date via [clock]
 * (injectable for tests).
 */
class TrackerRepository(
    private val db: StrengthDatabase,
    private val programDao: ProgramDao,
    private val sessionDao: SessionDao,
    private val customExerciseDao: CustomExerciseDao,
    private val settings: SettingsStore,
    private val clock: Clock = Clock.systemDefaultZone(),
) {

    // --- config & preferences ------------------------------------------------

    val configFlow: Flow<LifterConfig> = settings.configFlow
    val cardioPrefsFlow: Flow<CardioPrefs> = settings.cardioPrefsFlow
    val unitFlow: Flow<WeightUnit> = settings.unitFlow
    val wizardCompleteFlow: Flow<Boolean> = settings.wizardCompleteFlow
    val wizardAnswersFlow: Flow<WizardAnswers> = settings.wizardAnswersFlow

    suspend fun setConfig(config: LifterConfig) = settings.setConfig(config)
    suspend fun setCardioPrefs(prefs: CardioPrefs) = settings.setCardioPrefs(prefs)
    suspend fun setUnit(unit: WeightUnit) = settings.setUnit(unit)
    suspend fun setWizardComplete(complete: Boolean) = settings.setWizardComplete(complete)

    /** Persists the wizard inputs so a single day can later be regenerated
     *  ([resetDayToTemplate]) and the setup screen can re-run the wizard. */
    suspend fun setWizardAnswers(answers: WizardAnswers) = settings.setWizardAnswers(answers)

    // --- exercise catalog (code + custom overlay) ----------------------------

    val catalogFlow: Flow<ExerciseCatalog> =
        customExerciseDao.observeAll().map { rows -> ExerciseCatalog(rows.map { it.toEntry() }) }

    suspend fun addCustomExercise(
        name: String,
        pattern: MovementPattern,
        equipment: List<Equipment>,
        perHand: Boolean,
        goalStartLb: Double,
    ): String {
        val id = ExerciseCatalog.CUSTOM_ID_PREFIX + UUID.randomUUID().toString().replace("-", "")
        customExerciseDao.upsert(
            CustomExerciseEntity(
                id = id,
                name = name.trim(),
                pattern = pattern.name,
                equipmentCsv = equipment.joinToString(",") { it.name },
                perHand = perHand,
                goalStartLb = goalStartLb,
            ),
        )
        return id
    }

    suspend fun removeCustomExercise(id: String) = customExerciseDao.delete(id)

    // --- program -------------------------------------------------------------

    val programFlow: Flow<Program> =
        combine(programDao.observeDays(), programDao.observeExercises(), ::assemble)

    /** Full replace with the wizard's generated program (spec §7). Also points the
     *  rotation at the first day. Destructive: old program and live logs are
     *  cleared (history in `workout_session` is untouched). */
    suspend fun replaceProgram(program: Program) {
        db.withTransaction {
            programDao.deleteAllLogs()
            programDao.deleteAllExercises()
            programDao.deleteAllDays()
            program.days.forEachIndexed { index, day ->
                programDao.upsertDay(day.toEntity().copy(position = index))
                day.exercises.forEachIndexed { pos, pe ->
                    programDao.insertExercise(pe.toEntity(day.id, pos))
                }
            }
        }
        program.days.firstOrNull()?.let { settings.setSuggestedDay(it.id) }
    }

    /** Swaps the exercise in a slot (spec §8.3). Keeps the slot's stable id but
     *  clears its live log so the new exercise reseeds from its own GOAL; prior
     *  performance survives in `workout_session` history. */
    suspend fun swapExercise(dayId: String, position: Int, newExerciseId: String) {
        db.withTransaction {
            val row = programDao.exerciseAt(dayId, position) ?: return@withTransaction
            programDao.setExerciseId(row.id, newExerciseId)
            programDao.deleteLogsForExercise(dayId, row.id)
        }
    }

    /** Appends an exercise to the end of a day (spec §8.3). */
    suspend fun addExercise(dayId: String, exercise: ProgramExercise) {
        db.withTransaction {
            val position = programDao.maxPosition(dayId) + 1
            programDao.insertExercise(exercise.toEntity(dayId, position))
        }
    }

    /** Removes the exercise at [position] and its live log (spec §8.3). The
     *  min-exercises-per-day rule is a UI concern and is not enforced here. */
    suspend fun removeExercise(dayId: String, position: Int) {
        db.withTransaction {
            val row = programDao.exerciseAt(dayId, position) ?: return@withTransaction
            programDao.deleteLogsForExercise(dayId, row.id)
            programDao.deleteExercise(row.id)
        }
    }

    /** Regenerates one day from the stored wizard answers (spec §8.3), leaving the
     *  other days untouched. No-op if answers regenerate no such day. */
    suspend fun resetDayToTemplate(dayId: String) {
        val answers = settings.wizardAnswersFlow.first()
        val regenerated = ProgramGenerator.generate(answers).program
        val newDay = regenerated.days.firstOrNull { it.id == dayId } ?: return
        val position = programDao.day(dayId)?.position
            ?: regenerated.days.indexOfFirst { it.id == dayId }
        db.withTransaction {
            programDao.deleteLogsForDay(dayId)
            programDao.deleteExercisesForDay(dayId)
            programDao.upsertDay(newDay.toEntity().copy(position = position))
            newDay.exercises.forEachIndexed { pos, pe ->
                programDao.insertExercise(pe.toEntity(dayId, pos))
            }
        }
    }

    // --- live logs -----------------------------------------------------------

    /**
     * The day's exercise slots with their stable row ids, in program order. The UI
     * needs the id to key and seed each slot's log ([logFlow]/[updateSets]); the
     * pure-domain [Program] from [programFlow] deliberately doesn't carry it.
     */
    fun daySlotsFlow(dayId: String): Flow<List<ProgramSlot>> =
        programDao.observeExercisesForDay(dayId).map { rows ->
            rows.map { ProgramSlot(it.id, it.position, it.toDomain()) }
        }

    /** The day's live logs with the daily checkmark reset applied (spec §7). */
    fun logFlow(dayId: String): Flow<List<LoggedSlot>> =
        programDao.observeLogs(dayId).map { rows ->
            val today = CheckmarkReset.today(clock)
            rows.map { it.toLoggedSlot(today) }
        }

    /** Persists a slot's set track immediately (spec §7). The write stamps today's
     *  date, so the `done` flags it carries are "today's" checks. */
    suspend fun updateSets(dayId: String, programExerciseId: Long, slot: String, sets: List<LoggedSet>) {
        programDao.upsertLog(
            ExerciseLogEntity(
                dayId = dayId,
                programExerciseId = programExerciseId,
                slot = slot,
                setsJson = SetJson.encodeSets(sets),
                checkDate = CheckmarkReset.today(clock),
                updatedAt = clock.millis(),
            ),
        )
    }

    /**
     * Clears today's checkmarks for one day without advancing the rotation (spec
     * §8.2 footer "clear today's checkmarks"). Invalidates each log's checkDate so
     * the daily-reset rule surfaces every set as unchecked; weights and reps stay.
     */
    suspend fun clearChecks(dayId: String) = programDao.clearChecksForDay(dayId)

    // --- rotation & session history ------------------------------------------

    val suggestedDayFlow: Flow<String?> = settings.suggestedDayFlow

    val sessionsFlow: Flow<List<WorkoutSessionEntity>> = sessionDao.observeSessions()

    /**
     * "DONE — advance" (spec §7, PLAN.md A1): appends an immutable session record
     * for the completed day (denormalizing exercise names so history survives
     * later edits/deletions), clears that day's checkmarks, and advances the
     * rotation pointer to the following day.
     *
     * Takes the completed day id because the user may have completed a manually
     * overridden day, not the suggested one; the spec's bare `advanceDay()` can't
     * express that.
     */
    suspend fun advanceDay(completedDayId: String) {
        val bodyweight = settings.configFlow.first().bodyweightLb
        val catalog = ExerciseCatalog(customExerciseDao.getAll().map { it.toEntry() })
        var next: String? = null
        db.withTransaction {
            val days = programDao.allDays()
            val exercises = programDao.allExercises()
            val dayTitle = days.firstOrNull { it.dayId == completedDayId }?.title ?: completedDayId
            val slotsById = exercises.filter { it.dayId == completedDayId }.associateBy { it.id }
            val logs = programDao.logsForDay(completedDayId)

            val sessionId = sessionDao.insertSession(
                WorkoutSessionEntity(
                    id = 0,
                    dayId = completedDayId,
                    dayTitle = dayTitle,
                    startedAt = null,
                    completedAt = clock.millis(),
                    bodyweightLb = bodyweight,
                ),
            )
            val sessionSets = logs.flatMap { log ->
                val pe = slotsById[log.programExerciseId] ?: return@flatMap emptyList()
                val exerciseId = if (log.slot == Slot.SS) pe.supersetExerciseId else pe.exerciseId
                if (exerciseId == null) return@flatMap emptyList()
                val name = catalog.find(exerciseId)?.name ?: exerciseId
                SetJson.decodeSets(log.setsJson).mapIndexed { index, s ->
                    SessionSetEntity(
                        id = 0,
                        sessionId = sessionId,
                        exerciseId = exerciseId,
                        exerciseName = name,
                        slot = log.slot,
                        setIndex = index,
                        kind = s.kind.name,
                        weightLb = s.weightLb,
                        reps = s.reps,
                        done = s.done,
                    )
                }
            }
            if (sessionSets.isNotEmpty()) sessionDao.insertSets(sessionSets)

            programDao.clearChecksForDay(completedDayId)

            val program = assemble(days, exercises)
            if (program.days.any { it.id == completedDayId }) {
                next = Rotation.next(program, completedDayId)
            }
        }
        next?.let { settings.setSuggestedDay(it) }
    }

    // --- helpers -------------------------------------------------------------

    private fun assemble(
        days: List<ProgramDayEntity>,
        exercises: List<ProgramExerciseEntity>,
    ): Program {
        val byDay = exercises.groupBy { it.dayId }
        return Program(
            days.sortedBy { it.position }.map { day -> day.toDomain(byDay[day.dayId].orEmpty()) },
        )
    }

    private fun ExerciseLogEntity.toLoggedSlot(today: String): LoggedSlot {
        val stored = SetJson.decodeSets(setsJson)
        return LoggedSlot(
            programExerciseId = programExerciseId,
            slot = slot,
            sets = CheckmarkReset.applyResetIfStale(stored, checkDate, today),
            checkDate = checkDate,
        )
    }
}
