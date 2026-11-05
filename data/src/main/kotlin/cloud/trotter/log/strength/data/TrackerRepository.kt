package cloud.trotter.log.strength.data

import androidx.room.withTransaction
import cloud.trotter.log.strength.data.catalog.ExerciseCatalog
import cloud.trotter.log.strength.data.db.StrengthDatabase
import cloud.trotter.log.strength.data.db.dao.CustomExerciseDao
import cloud.trotter.log.strength.data.db.dao.ProgramDao
import cloud.trotter.log.strength.data.db.dao.SessionDao
import cloud.trotter.log.strength.data.db.entity.CardioSessionEntity
import cloud.trotter.log.strength.data.db.entity.ExerciseLogEntity
import cloud.trotter.log.strength.data.db.entity.PlanEntity
import cloud.trotter.log.strength.data.db.entity.PlanOrigin
import cloud.trotter.log.strength.data.db.entity.ProgramDayEntity
import cloud.trotter.log.strength.data.db.entity.ProgramExerciseEntity
import cloud.trotter.log.strength.data.db.entity.SessionSetEntity
import cloud.trotter.log.strength.data.db.entity.Slot
import cloud.trotter.log.strength.data.db.entity.RunTrackEntity
import cloud.trotter.log.strength.data.db.entity.WorkoutRunEntity
import cloud.trotter.log.strength.data.db.entity.WorkoutSessionEntity
import cloud.trotter.log.strength.data.mapping.toDomain
import cloud.trotter.log.strength.data.mapping.toEntity
import cloud.trotter.log.strength.data.migration.reinterpretRepsAsSeconds
import cloud.trotter.log.strength.data.prefs.SettingsStore
import cloud.trotter.log.strength.data.serialization.CardioBlockJson
import cloud.trotter.log.strength.data.serialization.PrescriptionJson
import cloud.trotter.log.strength.data.serialization.PlannedTrackDto
import cloud.trotter.log.strength.data.serialization.SessionPlanJson
import cloud.trotter.log.strength.data.serialization.SetDto
import cloud.trotter.log.strength.data.serialization.SetJson
import cloud.trotter.log.strength.domain.seeding.RunReplan
import cloud.trotter.log.strength.domain.seeding.SlotSeeds
import cloud.trotter.log.strength.domain.seeding.CarryForward
import cloud.trotter.log.strength.domain.generator.Rotation
import cloud.trotter.log.strength.domain.generator.ProgramGenerator
import cloud.trotter.log.strength.domain.library.ExerciseLibrary
import cloud.trotter.log.strength.domain.library.TrackingType
import cloud.trotter.log.strength.domain.library.tracking
import cloud.trotter.log.strength.domain.model.CardioBlock
import cloud.trotter.log.strength.domain.model.CardioActivity
import cloud.trotter.log.strength.domain.model.CardioSetResult
import cloud.trotter.log.strength.domain.model.CardioMode
import cloud.trotter.log.strength.domain.model.LifterConfig
import cloud.trotter.log.strength.domain.model.LoggedSet
import cloud.trotter.log.strength.domain.model.Prescription
import cloud.trotter.log.strength.domain.model.Program
import cloud.trotter.log.strength.domain.model.ProgramDayKind
import cloud.trotter.log.strength.domain.model.ProgramExercise
import cloud.trotter.log.strength.domain.model.SetStructure
import java.time.Clock
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/**
 * Program and workout access (spec §7 surface, extended by PLAN.md
 * A1/A4/A6). Every read is a [Flow] off Room/DataStore; every mutation is a
 * suspend call that commits immediately, so no working truth ever lives only in
 * memory. Cross-table mutations run in one Room transaction.
 * Program and live-log reads and day/position writes act on the selected plan;
 * writes addressed by slot id act on the slot's own plan.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TrackerRepository(
    private val db: StrengthDatabase,
    private val programDao: ProgramDao,
    private val sessionDao: SessionDao,
    customExerciseDao: CustomExerciseDao,
    private val settings: SettingsStore,
    private val clock: Clock = Clock.systemDefaultZone(),
    private val mintRunId: () -> String = { UUID.randomUUID().toString() },
    val plans: PlanRepository = PlanRepository(db, settings, clock, mintRunId, programDao, sessionDao),
    val catalog: CatalogRepository = CatalogRepository(customExerciseDao),
    val history: HistoryRepository = HistoryRepository(db, settings, plans, sessionDao, customExerciseDao),
) {

    private val restoreMarkerDao = db.restoreMarkerDao()
    private val cardioSessionDao = db.cardioSessionDao()
    private val cardioSetResultDao = db.cardioSetResultDao()
    private val planDao = db.planDao()
    private val runDao = db.runDao()

    // --- program -------------------------------------------------------------

    suspend fun addDay(planId: Long, title: String): String? = db.withTransaction {
        if (planDao.plan(planId) == null) return@withTransaction null
        val trimmed = title.trim()
        if (trimmed.length !in 1..30) return@withTransaction null
        val days = programDao.daysForPlan(planId)
        if (days.count { it.kind == ProgramDayKind.STRENGTH.name } >= 7) return@withTransaction null
        val letter = ('A'..'G').firstOrNull { candidate -> days.none { it.dayId == candidate.toString() } }
            ?.toString() ?: return@withTransaction null
        programDao.upsertDay(ProgramDayEntity(
            planId, letter, (days.maxOfOrNull { it.position } ?: -1) + 1, trimmed, "", null,
        ).freshRunId(mintRunId))
        letter
    }

    suspend fun renameDay(planId: Long, dayId: String, title: String) {
        db.withTransaction {
            val trimmed = title.trim()
            if (trimmed.length !in 1..30) return@withTransaction
            val day = programDao.day(planId, dayId) ?: return@withTransaction
            programDao.upsertDay(day.copy(title = trimmed))
        }
    }

    suspend fun removeDay(planId: Long, dayId: String): Boolean {
        val bodyweight = plans.configFlow.first().bodyweightLb
        val catalog = this.catalog.snapshot()
        return db.withTransaction {
            val plan = planDao.plan(planId) ?: return@withTransaction false
            val days = programDao.daysForPlan(planId)
            val removed = days.firstOrNull { it.dayId == dayId } ?: return@withTransaction false
            if (days.size <= 1) return@withTransaction false
            closeRemovedRun(planId, setOf(dayId), bodyweight, catalog)
            programDao.deleteLogsForDay(planId, dayId)
            programDao.deleteExercisesForDay(planId, dayId)
            programDao.deleteDay(planId, dayId)
            if (plan.suggestedDayId == dayId) {
                val remaining = days.filter { it.dayId != dayId }
                val rotation = remaining.filter { it.kind == ProgramDayKind.STRENGTH.name }.ifEmpty { remaining }
                val next = rotation.firstOrNull { it.position > removed.position } ?: rotation.first()
                planDao.updateSuggestedDay(planId, next.dayId)
            }
            true
        }
    }

    /** Cardio is stored on its owning day; null removes it if an exercise remains. */
    suspend fun setCardioBlock(planId: Long, dayId: String, block: CardioBlock?): Boolean =
        db.withTransaction {
            val day = programDao.day(planId, dayId) ?: return@withTransaction false
            if (block == null && programDao.exercisesForDay(planId, dayId).isEmpty()) return@withTransaction false
            // encode reconstructs the domain objects, enforcing all their requires.
            val json = block?.let(CardioBlockJson::encode)
            programDao.upsertDay(day.copy(cardioBlockJson = json, cardioJson = null))
            true
        }

    /** Sets belong to the slot, never the prescription JSON. Null keeps its current count.
     * A change between timed and rep-shaped rows applies to future runs only. */
    suspend fun setPrescription(programExerciseId: Long, prescription: Prescription, sets: Int? = null) {
        plans.ensurePlanSettingsMigrated()
        val global = settings.globalLifterFlow.first()
        db.withTransaction {
            val row = programDao.exercise(programExerciseId) ?: return@withTransaction
            val count = sets ?: row.targetSets
            require(count in 1..20)
            val json = PrescriptionJson.encode(prescription)
            val updated = row.copy(prescriptionJson = json, targetSets = count)
            programDao.setPrescription(row.id, json, count)
            replanSlot(updated, answersForPlan(planDao.plan(row.planId), global).config)
            programDao.deleteLogsForExercise(row.planId, row.dayId, row.id)
        }
    }

    suspend fun setStructure(programExerciseId: Long, structure: SetStructure) {
        plans.ensurePlanSettingsMigrated()
        val global = settings.globalLifterFlow.first()
        db.withTransaction {
            val row = programDao.exercise(programExerciseId) ?: return@withTransaction
            // Picking the current structure again must not replan rows or drop remembered values.
            if (row.structure == structure.name) return@withTransaction
            val updated = row.copy(structure = structure.name)
            programDao.setStructure(row.id, structure.name)
            replanSlot(updated, answersForPlan(planDao.plan(row.planId), global).config)
            programDao.deleteLogsForExercise(row.planId, row.dayId, row.id)
        }
    }

    private suspend fun replanSlot(row: ProgramExerciseEntity, config: LifterConfig) {
        fun plannedJson(rows: List<LoggedSet>, seed: List<LoggedSet>): String = SetJson.encodeSets(
            CarryForward.cleared(rows.mapIndexed { index, set -> seed.getOrNull(index) ?: set }),
        )

        val run = runDao.openRun() ?: return
        if (run.planId != row.planId || run.dayId != row.dayId) return
        val tracks = runDao.tracks(run.id).filter { it.programExerciseId == row.id }
        val catalog = this.catalog.snapshot()
        val exercise = row.toDomain()
        val entry = catalog.find(row.exerciseId)
        val isTimed = (exercise.prescription?.tracking ?: entry?.tracking) == TrackingType.TIMED
        val seeded = SlotSeeds.main(exercise, entry, config) ?: return
        val activeMain = tracks.firstOrNull { !it.retired && it.slot == Slot.MAIN }
        val partnerMain = if (activeMain == null) {
            tracks.firstOrNull { it.retired && it.slot == Slot.MAIN && it.exerciseId == row.exerciseId }
        } else {
            tracks.firstOrNull { it.slot == Slot.MAIN && it.dropped } ?: activeMain
        }
        val partnerMainSeed = seeded.takeIf {
            partnerMain?.exerciseId == row.exerciseId && partnerMain.canReplan(isTimed)
        }
        for (track in tracks.filter { it.retired && it.exerciseId == row.exerciseIdFor(it.slot) }) {
            val trackSeed = when (track.slot) {
                Slot.MAIN -> seeded
                Slot.SS -> SlotSeeds.partner(catalog.find(track.exerciseId), partnerMainSeed?.size ?: continue, config)
                    ?: continue
                else -> continue
            }
            val trackIsTimed = if (track.slot == Slot.MAIN) isTimed
                else catalog.find(track.exerciseId)?.tracking == TrackingType.TIMED
            if (!track.canReplan(trackIsTimed)) continue
            val rows = RunReplan.represcribe(SetJson.decodeSets(track.setsJson), trackSeed)
            runDao.updateTrack(track.copy(
                plannedJson = plannedJson(rows, trackSeed),
                setsJson = SetJson.encodeSets(rows),
                updatedAt = clock.millis(),
            ))
        }
        val main = activeMain?.takeIf { it.exerciseId == row.exerciseId } ?: return
        if (!main.canReplan(isTimed)) return
        val current = SetJson.decodeSets(main.setsJson)
        val partner = tracks.firstOrNull { !it.retired && it.slot == Slot.SS }
        val mainRows = if (partner == null) RunReplan.represcribe(current, seeded) else if (
            partner.exerciseId != row.exerciseIdFor(Slot.SS)
        ) {
            val partnerRows = SetJson.decodeSets(partner.setsJson)
            val alignedSeed = RunReplan.alignPaired(
                seeded, partnerRows, seeded, partnerRows.ifEmpty { seeded },
            ).first
            val rows = RunReplan.represcribe(current, alignedSeed)
            // A substituted partner keeps its rows; when the main track grows, it grows from its own target.
            if (rows.size > partnerRows.size) {
                val partnerSeed = SlotSeeds.partner(catalog.find(partner.exerciseId), rows.size, config)
                    ?: partnerRows.ifEmpty { rows }
                val aligned = RunReplan.alignPaired(rows, partnerRows, rows, partnerSeed).second
                runDao.updateTrack(partner.copy(
                    plannedJson = plannedJson(aligned, partnerSeed),
                    setsJson = SetJson.encodeSets(aligned),
                    updatedAt = clock.millis(),
                ))
            }
            rows
        } else {
            val partnerEntry = catalog.find(partner.exerciseId)
            if (partnerEntry == null) RunReplan.represcribe(current, seeded) else {
                val partnerSeed = requireNotNull(SlotSeeds.partner(partnerEntry, seeded.size, config))
                val paired = RunReplan.represcribePaired(
                    current, SetJson.decodeSets(partner.setsJson), seeded, partnerSeed,
                )
                runDao.updateTrack(partner.copy(
                    plannedJson = plannedJson(paired.second, partnerSeed),
                    setsJson = SetJson.encodeSets(paired.second), updatedAt = clock.millis(),
                ))
                paired.first
            }
        }
        runDao.updateTrack(main.copy(
            plannedJson = plannedJson(mainRows, seeded),
            setsJson = SetJson.encodeSets(mainRows), updatedAt = clock.millis(),
        ))
    }

    /** Slot-owned ordering, independent of whichever plan is selected. */
    suspend fun moveExercise(programExerciseId: Long, up: Boolean) {
        db.withTransaction {
            val row = programDao.exercise(programExerciseId) ?: return@withTransaction
            val slots = programDao.exercisesForDay(row.planId, row.dayId)
            val index = slots.indexOfFirst { it.id == row.id }
            val neighbour = slots.getOrNull(index + if (up) -1 else 1) ?: return@withTransaction
            programDao.setPosition(row.id, neighbour.position)
            programDao.setPosition(neighbour.id, row.position)
        }
    }

    /** Full replace with the wizard's generated program (spec §7). Also points the
     *  rotation at the first day. Destructive for the selected plan only: its old
     *  program and live logs are cleared (history in `workout_session` is untouched). */
    suspend fun replaceProgram(program: Program) {
        plans.ensurePlanSettingsMigrated()
        val planId = plans.ensureSelectedPlan()
        val bodyweight = plans.configFlow.first().bodyweightLb
        val catalog = this.catalog.snapshot()
        db.withTransaction {
            closeRemovedRun(planId, programDao.daysForPlan(planId).map { it.dayId }.toSet(), bodyweight, catalog)
            programDao.deleteLogsForPlan(planId)
            programDao.deleteExercisesForPlan(planId)
            programDao.deleteDaysForPlan(planId)
            program.days.forEachIndexed { index, day ->
                programDao.upsertDay(day.toEntity(planId).copy(position = index).freshRunId(mintRunId))
                day.exercises.forEachIndexed { pos, pe ->
                    programDao.insertExercise(pe.toEntity(planId, day.id, pos))
                }
            }
            program.strengthDays.firstOrNull()?.let { planDao.updateSuggestedDay(planId, it.id) }
        }
    }

    /** The slot names its plan; a missing slot or mismatched day returns false. */
    suspend fun swapExerciseById(
        dayId: String,
        programExerciseId: Long,
        newExerciseId: String,
    ): Boolean {
        val global = settings.globalLifterFlow.first()
        return db.withTransaction {
            val catalog = this.catalog.snapshot()
            val row = programDao.exercise(programExerciseId) ?: return@withTransaction false
            if (row.dayId != dayId) return@withTransaction false
            if (newExerciseId == row.exerciseId) {
                clearSlotSkipState(row.planId, dayId, row.id)
                val run = runDao.openRun()?.takeIf { it.planId == row.planId && it.dayId == dayId }
                    ?: return@withTransaction true
                val config = answersForPlan(planDao.plan(row.planId), global).config
                return@withTransaction swapInRun(run.id, row.id, Slot.MAIN, newExerciseId, config, catalog)
            }
            swapInPlan(row.planId, row.dayId, row.id, newExerciseId)
            true
        }
    }

    private suspend fun swapInPlan(
        planId: Long,
        dayId: String,
        programExerciseId: Long,
        newExerciseId: String,
    ) {
        clearSlotSkipState(planId, dayId, programExerciseId)
        retireActiveTrack(planId, dayId, programExerciseId, Slot.MAIN)
        programDao.setExerciseId(programExerciseId, newExerciseId)
        programDao.deleteLogsForExercise(planId, dayId, programExerciseId)
    }

    /** The open run's active track for this slot: retired when it holds done work, deleted otherwise. */
    private suspend fun retireActiveTrack(planId: Long, dayId: String, programExerciseId: Long, slot: String) {
        val run = runDao.openRun()?.takeIf { it.planId == planId && it.dayId == dayId } ?: return
        val track = runDao.tracks(run.id).firstOrNull {
            it.programExerciseId == programExerciseId && it.slot == slot && !it.retired
        } ?: return
        retireTrack(track)
    }

    private suspend fun retireTrack(track: RunTrackEntity) {
        if (SetJson.decodeSets(track.setsJson).any { it.done }) {
            runDao.updateTrack(track.copy(retired = true, updatedAt = clock.millis()))
        } else {
            runDao.deleteTrack(track.id)
        }
    }

    /** Plan edits and whole-lift Bring back supersede this slot's skip state. */
    private suspend fun clearSlotSkipState(planId: Long, dayId: String, programExerciseId: Long) {
        val run = runDao.openRun()?.takeIf { it.planId == planId && it.dayId == dayId } ?: return
        val tracks = runDao.tracks(run.id).filter { it.programExerciseId == programExerciseId }
        val partner = tracks.firstOrNull { it.slot == Slot.SS && it.dropped }
        for (track in tracks) {
            if (track.dropped || (partner != null && track.slot == Slot.MAIN && track.exerciseId == partner.exerciseId)) {
                retireTrack(track.copy(dropped = false))
            }
        }
    }

    /** Appends an exercise to the end of a day (spec §8.3). */
    suspend fun addExercise(dayId: String, exercise: ProgramExercise, targetPlanId: Long? = null) {
        val planId = targetPlanId ?: plans.selectedPlanId() ?: return
        db.withTransaction {
            val position = programDao.maxPosition(planId, dayId) + 1
            if (programDao.day(planId, dayId) == null) return@withTransaction
            programDao.insertExercise(exercise.toEntity(planId, dayId, position))
        }
    }

    /** Removes a slot and its live log (spec §8.3), enforcing its own plan's
     *  day floor in the same transaction. Selection changes cannot retarget it. */
    suspend fun removeExercise(programExerciseId: Long): Boolean =
        db.withTransaction {
            val row = programDao.exercise(programExerciseId) ?: return@withTransaction false
            val count = programDao.exercisesForDay(row.planId, row.dayId).size
            val hasCardio = CardioBlockJson.decode(
                programDao.day(row.planId, row.dayId)?.cardioBlockJson,
            ) != null
            val floor = minExercisesPerDay(hasCardio)
            if (count <= floor) return@withTransaction false
            clearSlotSkipState(row.planId, row.dayId, row.id)
            programDao.deleteLogsForExercise(row.planId, row.dayId, row.id)
            retireActiveTrack(row.planId, row.dayId, row.id, Slot.MAIN)
            retireActiveTrack(row.planId, row.dayId, row.id, Slot.SS)
            programDao.deleteExercise(row.id)
            true
        }

    suspend fun setExerciseNote(programExerciseId: Long, text: String) {
        programDao.setExerciseNote(programExerciseId, text.trim().take(120))
    }

    suspend fun setKeepToPlan(programExerciseId: Long, keep: Boolean) {
        db.withTransaction {
            val row = programDao.exercise(programExerciseId) ?: return@withTransaction
            programDao.setKeepToPlan(row.id, keep)
            if (keep) deleteSlotWorkingValues(row)
        }
    }

    suspend fun resetSlotToPlan(programExerciseId: Long): Boolean = db.withTransaction {
        val row = programDao.exercise(programExerciseId) ?: return@withTransaction false
        if (row.keepToPlan) return@withTransaction false
        val run = runDao.openRun()
        if (run?.planId == row.planId && run.dayId == row.dayId) return@withTransaction false
        deleteSlotWorkingValues(row)
        true
    }

    private suspend fun deleteSlotWorkingValues(row: ProgramExerciseEntity) {
        programDao.deleteLogForSlot(row.planId, row.dayId, row.id, Slot.MAIN)
        programDao.deleteLogForSlot(row.planId, row.dayId, row.id, Slot.SS)
    }

    /**
     * Attaches [partnerExerciseId] to [programExerciseId] as its superset
     * partner — the same call covers adding a partner to a plain slot and
     * swapping an existing one.
     *
     * Only the SS track is cleared, so the new partner reseeds from its own GOAL
     * on the next observation while the slot's MAIN track — the lifter's living
     * record, possibly with weeks of edits and extra sets on it — survives
     * untouched. NOT [ProgramDao.deleteLogsForExercise], which would nuke both
     * tracks.
     */
    suspend fun setSupersetPartner(dayId: String, programExerciseId: Long, partnerExerciseId: String) {
        db.withTransaction {
            val row = programDao.exercise(programExerciseId) ?: return@withTransaction
            if (row.dayId != dayId) return@withTransaction
            val planId = row.planId
            clearSlotSkipState(planId, dayId, row.id)
            retireActiveTrack(planId, dayId, row.id, Slot.SS)
            programDao.setSupersetExerciseId(row.id, partnerExerciseId)
            programDao.deleteLogForSlot(planId, dayId, row.id, Slot.SS)
        }
    }

    /** Drops the slot's superset partner and its SS track, leaving the MAIN
     *  track alone for the same reason [setSupersetPartner] does. */
    suspend fun removeSupersetPartner(dayId: String, programExerciseId: Long) {
        db.withTransaction {
            val row = programDao.exercise(programExerciseId) ?: return@withTransaction
            if (row.dayId != dayId) return@withTransaction
            val planId = row.planId
            clearSlotSkipState(planId, dayId, row.id)
            retireActiveTrack(planId, dayId, row.id, Slot.SS)
            programDao.setSupersetExerciseId(row.id, null)
            programDao.deleteLogForSlot(planId, dayId, row.id, Slot.SS)
        }
    }

    /** Regenerates one day from the stored wizard answers (spec §8.3), leaving the
     *  other days untouched. No-op if answers regenerate no such day. */
    suspend fun resetDayToTemplate(dayId: String, targetPlanId: Long? = null) {
        plans.ensurePlanSettingsMigrated()
        val planId = targetPlanId ?: plans.selectedPlanId() ?: return
        if (planDao.plan(planId)?.origin != PlanOrigin.GENERATED.name) return
        val answers = plans.wizardAnswersFor(planId).first()
        val regenerated = ProgramGenerator.generate(answers)
        val newDay = regenerated.days.firstOrNull { it.id == dayId } ?: return
        db.withTransaction {
            val existing = programDao.day(planId, dayId)
            val position = existing?.position ?: regenerated.days.indexOfFirst { it.id == dayId }
            for (slot in programDao.exercisesForDay(planId, dayId)) {
                retireActiveTrack(planId, dayId, slot.id, Slot.MAIN)
                retireActiveTrack(planId, dayId, slot.id, Slot.SS)
            }
            programDao.deleteLogsForDay(planId, dayId)
            programDao.deleteExercisesForDay(planId, dayId)
            val replacement = newDay.toEntity(planId).copy(position = position)
            programDao.upsertDay(if (existing == null) replacement.freshRunId(mintRunId) else replacement.copy(
                nextRunId = existing.nextRunId, closedAt = existing.closedAt,
            ))
            newDay.exercises.forEachIndexed { pos, pe ->
                programDao.insertExercise(pe.toEntity(planId, dayId, pos))
            }
        }
    }

    // --- one-shot legacy fixups ----------------------------------------------

    /**
     * One-shot reps→seconds carry for legacy live logs of entries reclassified to
     * TIMED (plank, hollow hold, weighted plank, suitcase carry). Legacy timed
     * holds store their duration in reps; this moves it into `seconds` and zeroes
     * reps for exactly those slots. It never touches any other exercise and never
     * deletes a row. Prescribed main tracks keep their authored meaning even when
     * the catalog classifies them as TIMED.
     *
     * One-shot on two fronts: a DataStore flag short-circuits it after the first
     * run, and the underlying [reinterpretRepsAsSeconds] only moves an *unfixed*
     * set (seconds 0, reps > 0), so even a second run — e.g. after the flag is
     * cleared by a restore — leaves already-migrated holds untouched. Runs in one
     * transaction so a crash mid-fixup leaves every slot either fully old or fully
     * carried, never half. Called once at app startup.
     */
    suspend fun runLegacyTimedFixupIfNeeded() {
        if (settings.legacyTimedFixupDoneFlow.first()) return
        db.withTransaction {
            val slotsById = programDao.allExercises().associateBy { it.id }
            for (log in programDao.allLogs()) {
                val pe = slotsById[log.programExerciseId] ?: continue
                if (log.slot == Slot.MAIN && pe.toDomain().prescription != null) continue
                val exerciseId = if (log.slot == Slot.SS) pe.supersetExerciseId else pe.exerciseId
                if (exerciseId == null || exerciseId !in ExerciseLibrary.RECLASSIFIED_TO_TIMED_IDS) continue
                val original = SetJson.decodeSets(log.setsJson)
                val fixed = original.map { it.reinterpretRepsAsSeconds() }
                if (fixed != original) {
                    programDao.upsertLog(log.copy(setsJson = SetJson.encodeSets(fixed)))
                }
            }
        }
        settings.setLegacyTimedFixupDone()
    }

    // --- live logs -----------------------------------------------------------

    /** Pure read: seeding and writes remain the caller's responsibility. */
    fun observeDay(planId: Long, dayId: String): Flow<PerformedDay> = combine(
        plans.programFor(planId), plans.slotsFor(planId, dayId), dayTracksFlow(planId, dayId),
        plans.wizardAnswersFor(planId), catalog.catalogFlow,
    ) { program, slots, tracks, answers, catalog ->
        PerformedDay.of(planId, dayId, program, slots, tracks, answers, catalog)
    }

    fun dayTracksFlow(planId: Long, dayId: String): Flow<DayTracks> = combine(
        openRunFlow, programDao.observeDays(planId), programDao.observeLogs(planId, dayId),
        programDao.observeExercisesForDay(planId, dayId),
    ) { open, days, logs, slots ->
        if (open?.planId == planId && open.dayId == dayId) {
            DayTracks(open.id, true, open.tracks.filter { !it.retired }.mapNotNull { track ->
                track.programExerciseId?.let { LoggedSlot(it, track.slot, track.sets, track.exerciseId) }
            }, retired = open.tracks.filter { it.retired && (!it.dropped || it.sets.any { set -> set.done }) },
                droppedPartners = open.tracks.mapNotNull { it.programExerciseId }.filterTo(mutableSetOf()) { id ->
                    open.isPartnerDropped(id)
                }, skippedLifts = open.tracks.mapNotNull { it.programExerciseId }.filterTo(mutableSetOf()) { id ->
                    open.isLiftSkipped(id)
                })
        } else {
            DayTracks(days.firstOrNull { it.dayId == dayId }?.nextRunId?.takeIf(String::isNotBlank),
                false, logs.map { log -> log.toLoggedSlot().copy(
                    exerciseId = slots.firstOrNull { it.id == log.programExerciseId }?.exerciseIdFor(log.slot),
                ) })
        }
    }

    /**
     * Seeds the day's missing tracks **only if they are still unseeded when the write happens**.
     * The plan and day identify the working values or their open run.
     *
     * Seeding is decided from a read ("this slot has no track") and acted on later,
     * and there are two seeders — the day ViewModel's lazy pass and the watch
     * applier's eager one after a swap. Between one of them reading an empty track
     * and writing to it, the other can seed *and the lifter can log a set against
     * the result*; an unconditional upsert would then overwrite real logged work
     * with a fresh seed. Re-checking inside the same transaction makes the losing
     * caller a no-op instead: whoever seeds first wins, and nothing that has been
     * logged since can be clobbered by a decision taken before it existed.
     *
     * "Unseeded" is the absence of a row, the same fact the seed plan keys on — an
     * empty stored track is a deliberate state and must not be re-seeded either.
     */
    suspend fun ensureSeeded(planId: Long, dayId: String) {
        plans.ensurePlanSettingsMigrated()
        val global = settings.globalLifterFlow.first()
        db.withTransaction {
            val day = programDao.day(planId, dayId) ?: return@withTransaction
            val config = answersForPlan(planDao.plan(planId), global).config
            val catalog = this.catalog.snapshot()
            val run = runDao.openRun()?.takeIf { it.planId == planId && it.dayId == dayId }
            if (run == null) {
                seedPlan(day, null, config, catalog).forEach { (key, sets) ->
                    programDao.upsertLog(logEntity(planId, dayId, key.first, key.second, sets))
                }
            } else {
                seedRunTracks(day, runDao.tracks(run.id), config, catalog)
            }
        }
    }

    private fun logEntity(
        planId: Long,
        dayId: String,
        programExerciseId: Long,
        slot: String,
        sets: List<LoggedSet>,
    ): ExerciseLogEntity =
        ExerciseLogEntity(
            planId = planId,
            dayId = dayId,
            programExerciseId = programExerciseId,
            slot = slot,
            setsJson = SetJson.encodeSets(sets),
            updatedAt = clock.millis(),
        )

    // --- rotation & session completion --------------------------------------

    /**
     * Commits the cardio session, its set results and any rotation advance in
     * one transaction. Paired effects must share this boundary; no mutation is
     * staged in UI state. Labels
     * are non-blank and capped at 80 characters; modes are stored by enum name
     * and decoded with [decodedCardioMode] on read. An explicit plan id is
     * preserved and its current name copied when the plan exists. Sessions
     * without a plan cannot advance a rotation.
     */
    suspend fun logCardioSession(
        session: CardioSessionEntity,
        sets: List<CardioSetResult> = emptyList(),
        finishDay: Boolean = false,
    ): Long {
        require(session.label.isNotBlank()) { "cardio label must not be blank" }
        require(session.label.length <= CARDIO_LABEL_MAX_LENGTH) {
            "cardio label must be at most $CARDIO_LABEL_MAX_LENGTH characters"
        }
        require(CardioMode.entries.any { it.name == session.mode }) {
            "cardio mode must be stored as CardioMode.name"
        }
        require(session.activity == null || CardioActivity.entries.any { it.name == session.activity }) {
            "cardio activity must be stored as CardioActivity.name"
        }
        val ownerId = session.planId
        return db.withTransaction {
            // Read the cursor in the same transaction as the log and guarded advance.
            val plan = ownerId?.let { planDao.plan(it) }
            val stamped = if (session.planId == null || plan != null) {
                session.copy(planId = plan?.id, planName = plan?.name)
            } else {
                session
            }
            // The phone can retry after process death between commit and saved-state acknowledgement.
            if (sets.isNotEmpty()) {
                cardioSessionDao.forRun(stamped.dayId, stamped.startedAt)?.let { return@withTransaction it.id }
            }
            val id = cardioSessionDao.insert(stamped.copy(id = 0))
            if (sets.isNotEmpty()) cardioSetResultDao.insertAll(sets.mapIndexed { position, set ->
                set.toEntity(id, position)
            })
            if (finishDay && plan != null && stamped.dayId != null) {
                advanceRotation(plan, stamped.dayId)
            }
            id
        }
    }

    /** Room only: both the phone commit and strength DONE use the originating plan's rotation. */
    private suspend fun advanceRotation(plan: PlanEntity, dayId: String) {
        val program = assemble(programDao.daysForPlan(plan.id), programDao.exercisesForPlan(plan.id))
        val nextDayId = Rotation.upNextAfter(program, plan.suggestedDayId, dayId)
        if (nextDayId != plan.suggestedDayId) {
            planDao.updateSuggestedDay(plan.id, nextDayId)
        }
    }

    /** The open workout and its tracks, independent of plan selection. */
    val openRunFlow: Flow<OpenRun?> = runDao.observeOpenRun().flatMapLatest { run ->
        if (run == null) flowOf(null) else runDao.observeTracks(run.id).map { tracks ->
            OpenRun(run.id, run.planId, run.dayId, run.dayTitle, run.planName, run.startedAt, tracks.map { it.toRunTrack() })
        }
    }

    val todayTargetFlow: Flow<TodayTarget?> = flow {
        plans.ensurePlanSettingsMigrated()
        emitAll(combine(
            settings.selectedPlanIdFlow, openRunFlow, plans.plansFlow, restoreMarkerDao.observeNonce(),
        ) { storedId, open, plans, restoreNonce ->
            // The plans can emit before the marker flow after an import commits; read it directly too.
            if (restoreNonce != null || restoreMarkerDao.nonce() != null) return@combine null
            val running = open?.let { run -> plans.firstOrNull { it.id == run.planId } }
            val preferred = preferredPlan(plans, storedId)?.takeIf { it.onToday }
            val plan = running ?: preferred ?: plans.firstOrNull { !it.archived && it.onToday }
            when {
                plan != null -> TodayTarget.Day(plan, if (running != null) open?.dayId else plan.suggestedDayId)
                plans.isEmpty() -> TodayTarget.NoPlans
                else -> TodayTarget.NothingOnToday
            }
        }.distinctUntilChanged())
    }

    private fun RunTrackEntity.toRunTrack(): RunTrack = RunTrack(
        id, position, programExerciseId, slot, exerciseId, retired,
        SetJson.decodeSets(plannedJson), SetJson.decodeSets(setsJson), dropped,
    )

    suspend fun startRun(planId: Long, dayId: String): RunStartResult {
        plans.ensurePlanSettingsMigrated()
        val global = settings.globalLifterFlow.first()
        return db.withTransaction {
            val config = answersForPlan(planDao.plan(planId), global).config
            val catalog = this.catalog.snapshot()
            startRun(planId, dayId, config, catalog)
        }
    }

    /** Runs inside a transaction and takes config and catalog from the caller. */
    private suspend fun startRun(
        planId: Long, dayId: String, config: LifterConfig, catalog: ExerciseCatalog,
    ): RunStartResult {
        val open = runDao.openRun()
        if (open != null) {
            return if (open.planId == planId && open.dayId == dayId) {
                RunStartResult.AlreadyOpen(open.id)
            } else RunStartResult.OtherRunOpen(open.id, open.planId, open.dayId)
        }
        val day = programDao.day(planId, dayId) ?: return RunStartResult.NoStrengthSlots
        if (programDao.exercisesForDay(planId, dayId).isEmpty()) return RunStartResult.NoStrengthSlots
        startRun(day, initialRunTracks(day, seedPlan(day, null, config, catalog)))
        return RunStartResult.Started(day.nextRunId)
    }

    /** Runs inside a transaction and takes config and catalog from the caller. */
    private suspend fun startRunIfNext(
        runId: String, planId: Long, dayId: String, config: LifterConfig, catalog: ExerciseCatalog,
    ): RunStartResult? {
        val day = programDao.day(planId, dayId) ?: return null
        // Watch swaps must not start a new identity if Finish wins during validation.
        if (day.nextRunId != runId) return null
        return startRun(planId, dayId, config, catalog)
    }

    /** Identity and no-op checks share the mutation transaction, including preview swaps. */
    suspend fun applyExerciseSwap(
        planId: Long,
        dayId: String,
        programExerciseId: Long,
        newExerciseId: String,
        runId: String?,
        allowChange: Boolean,
    ): ExerciseSwapResult {
        plans.ensurePlanSettingsMigrated()
        val global = settings.globalLifterFlow.first()
        return db.withTransaction {
            val config = answersForPlan(planDao.plan(planId), global).config
            val catalog = this.catalog.snapshot()
            val row = programDao.exercise(programExerciseId)
                ?.takeIf { it.planId == planId && it.dayId == dayId }
                ?: return@withTransaction ExerciseSwapResult.REJECTED
            val open = runDao.openRun()
            val ownRun = open?.takeIf { it.planId == planId && it.dayId == dayId }
            if (runId != null && (open != null && ownRun?.id != runId ||
                    (open == null && programDao.day(planId, dayId)?.nextRunId != runId))) {
                return@withTransaction ExerciseSwapResult.REJECTED
            }
            val active = ownRun?.let { run -> runDao.tracks(run.id).firstOrNull {
                it.programExerciseId == row.id && it.slot == Slot.MAIN && !it.retired
            } }
            if ((active?.exerciseId ?: row.exerciseId) == newExerciseId) {
                return@withTransaction ExerciseSwapResult.ALREADY_THERE
            }
            if (!allowChange) return@withTransaction ExerciseSwapResult.REJECTED
            val applied = if (runId == null && newExerciseId != row.exerciseId) {
                swapInPlan(planId, dayId, programExerciseId, newExerciseId)
                true
            } else {
                val targetRunId = runId ?: ownRun?.id ?: return@withTransaction ExerciseSwapResult.ALREADY_THERE
                if (ownRun == null) {
                    if (swapSeed(row, Slot.MAIN, newExerciseId, emptyList(), config, catalog) == null) {
                        return@withTransaction ExerciseSwapResult.REJECTED
                    }
                    if (startRunIfNext(targetRunId, planId, dayId, config, catalog) !is RunStartResult.Started) {
                        return@withTransaction ExerciseSwapResult.REJECTED
                    }
                }
                swapInRun(targetRunId, programExerciseId, Slot.MAIN, newExerciseId, config, catalog)
            }
            if (applied) ExerciseSwapResult.CHANGED else ExerciseSwapResult.REJECTED
        }
    }

    private fun List<RunTrackEntity>.slotSkipped(programExerciseId: Long, slot: String): Boolean =
        isSlotSkipped(programExerciseId, slot, RunTrackEntity::programExerciseId, RunTrackEntity::slot,
            RunTrackEntity::dropped, RunTrackEntity::retired)

    suspend fun skipLift(
        planId: Long, dayId: String, programExerciseId: Long, slot: String,
        expectedMainId: String, expectedPartnerId: String?,
    ): RunWriteResult {
        if (slot != Slot.MAIN && slot != Slot.SS) return RunWriteResult.Rejected
        if (slot == Slot.SS && expectedPartnerId == null) return RunWriteResult.Rejected
        return dropSupersetHalf(planId, dayId, programExerciseId,
            if (slot == Slot.SS) Slot.MAIN else Slot.SS, expectedMainId, expectedPartnerId)
    }

    suspend fun dropSupersetHalf(
        planId: Long,
        dayId: String,
        programExerciseId: Long,
        keep: String,
        expectedMainId: String,
        expectedPartnerId: String?,
    ): RunWriteResult {
        if (keep != Slot.MAIN && keep != Slot.SS) return RunWriteResult.Rejected
        plans.ensurePlanSettingsMigrated()
        val global = settings.globalLifterFlow.first()
        return db.withTransaction {
            val config = answersForPlan(planDao.plan(planId), global).config
            val catalog = this.catalog.snapshot()
            val day = programDao.day(planId, dayId) ?: return@withTransaction RunWriteResult.Rejected
            val row = programDao.exercise(programExerciseId)
                ?.takeIf { it.planId == planId && it.dayId == dayId }
                ?: return@withTransaction RunWriteResult.Rejected
            val open = runDao.openRun()
            if (open != null && (open.planId != planId || open.dayId != dayId)) return@withTransaction RunWriteResult.Rejected
            val runId = open?.id ?: day.nextRunId
            val tracks = if (open == null) {
                initialRunTracks(day, seedPlan(day, null, config, catalog))
            } else runDao.tracks(runId)
            val main = tracks.firstOrNull { it.programExerciseId == row.id && it.slot == Slot.MAIN && !it.retired }
                ?: return@withTransaction RunWriteResult.Rejected
            val partner = tracks.firstOrNull { it.programExerciseId == row.id && it.slot == Slot.SS && !it.retired }
            if (main.exerciseId != expectedMainId || partner?.exerciseId != expectedPartnerId) {
                return@withTransaction RunWriteResult.Rejected
            }
            if (partner == null) {
                if (keep != Slot.SS) return@withTransaction RunWriteResult.Rejected
                if (programDao.exercisesForDay(planId, dayId).none {
                        it.id != row.id && !tracks.slotSkipped(it.id, Slot.MAIN)
                    }) return@withTransaction RunWriteResult.Rejected
                if (open == null) startRun(day, tracks)
                val capturedMain = runDao.tracks(runId).single { it.programExerciseId == row.id && it.slot == Slot.MAIN && !it.retired }
                runDao.updateTrack(capturedMain.copy(retired = true, dropped = true, updatedAt = clock.millis()))
                return@withTransaction RunWriteResult.InRun(runId, started = open == null)
            }
            if (keep == Slot.SS && swapSeed(row, Slot.MAIN, partner.exerciseId, tracks, config, catalog) == null) {
                return@withTransaction RunWriteResult.Rejected
            }
            if (open == null) startRun(day, tracks)
            val capturedPartner = runDao.tracks(runId).first { it.programExerciseId == row.id && it.slot == Slot.SS && !it.retired }
            runDao.updateTrack(capturedPartner.copy(retired = true, dropped = true, updatedAt = clock.millis()))
            if (keep == Slot.SS) {
                val capturedMain = runDao.tracks(runId).single { it.programExerciseId == row.id && it.slot == Slot.MAIN && !it.retired }
                check(swapInRun(runId, row.id, Slot.MAIN, partner.exerciseId, config, catalog,
                    expectedExerciseId = main.exerciseId))
                // The track key cannot hold a second MAIN with the same exercise.
                if (main.exerciseId != partner.exerciseId) {
                    runDao.updateTrack(capturedMain.copy(retired = true, dropped = true, updatedAt = clock.millis()))
                }
            }
            RunWriteResult.InRun(runId, started = open == null)
        }
    }

    suspend fun restoreSkippedLift(runId: String, programExerciseId: Long): RunWriteResult {
        plans.ensurePlanSettingsMigrated()
        val global = settings.globalLifterFlow.first()
        return db.withTransaction {
            val run = runDao.openRun()?.takeIf { it.id == runId } ?: return@withTransaction RunWriteResult.Rejected
            val row = programDao.exercise(programExerciseId)
                ?.takeIf { it.planId == run.planId && it.dayId == run.dayId }
                ?: return@withTransaction RunWriteResult.Rejected
            val day = programDao.day(row.planId, row.dayId) ?: return@withTransaction RunWriteResult.Rejected
            if (!runDao.tracks(runId).slotSkipped(row.id, Slot.MAIN)) return@withTransaction RunWriteResult.Rejected
            val config = answersForPlan(planDao.plan(row.planId), global).config
            val catalog = this.catalog.snapshot()
            clearSlotSkipState(row.planId, row.dayId, row.id)
            seedRunTracks(day, runDao.tracks(runId), config, catalog, restoringLift = row.id)
            RunWriteResult.InRun(runId, started = false)
        }
    }

    /** Restores a single captured half while its remaining half still matches. */
    suspend fun restoreSkipped(runId: String, programExerciseId: Long, slot: String): RunWriteResult {
        if (slot != Slot.MAIN && slot != Slot.SS) return RunWriteResult.Rejected
        return db.withTransaction {
            val run = runDao.openRun()?.takeIf { it.id == runId } ?: return@withTransaction RunWriteResult.Rejected
            val row = programDao.exercise(programExerciseId)
                ?.takeIf { it.planId == run.planId && it.dayId == run.dayId }
                ?: return@withTransaction RunWriteResult.Rejected
            val tracks = runDao.tracks(runId)
            val main = tracks.singleOrNull { it.programExerciseId == row.id && it.slot == Slot.MAIN && !it.retired }
                ?: return@withTransaction RunWriteResult.Rejected
            val droppedMain = tracks.singleOrNull { it.programExerciseId == row.id && it.slot == Slot.MAIN && it.dropped }
            val partner = tracks.singleOrNull { it.programExerciseId == row.id && it.slot == Slot.SS && it.dropped && it.retired }
            if (partner == null || row.supersetExerciseId == null || tracks.any {
                    it.programExerciseId == row.id && it.slot == Slot.SS && !it.retired
                }) return@withTransaction RunWriteResult.Rejected
            val restoredMain = if (slot == Slot.MAIN) {
                if (main.exerciseId != partner.exerciseId) return@withTransaction RunWriteResult.Rejected
                droppedMain ?: main.takeIf { row.exerciseId == partner.exerciseId }
                    ?: return@withTransaction RunWriteResult.Rejected
            } else {
                if (droppedMain != null) return@withTransaction RunWriteResult.Rejected
                main
            }
            val mainRows = SetJson.decodeSets(restoredMain.setsJson)
            val partnerRows = SetJson.decodeSets(partner.setsJson)
            val mainSeed = SetJson.decodeSets(restoredMain.plannedJson)
            val partnerSeed = SetJson.decodeSets(partner.plannedJson)
            val count = maxOf(mainRows.size, partnerRows.size)
            if ((mainRows.size < count && mainSeed.isEmpty()) ||
                (partnerRows.size < count && partnerSeed.isEmpty())) return@withTransaction RunWriteResult.Rejected
            val aligned = RunReplan.alignPaired(mainRows, partnerRows, mainSeed, partnerSeed)
            if (main.id != restoredMain.id) {
                runDao.updateTrack(main.copy(retired = true, dropped = false, updatedAt = clock.millis()))
            }
            writeRunTracks(listOf(
                restoredMain.copy(retired = false, dropped = false, setsJson = SetJson.encodeSets(aligned.first), updatedAt = clock.millis()),
                partner.copy(retired = false, dropped = false, setsJson = SetJson.encodeSets(aligned.second), updatedAt = clock.millis()),
            ))
            RunWriteResult.InRun(runId, started = false)
        }
    }

    suspend fun swapInRun(
        runId: String,
        programExerciseId: Long,
        slot: String,
        newExerciseId: String,
        expectedExerciseId: String? = null,
    ): Boolean {
        plans.ensurePlanSettingsMigrated()
        val global = settings.globalLifterFlow.first()
        return db.withTransaction {
            val row = programDao.exercise(programExerciseId) ?: return@withTransaction false
            val config = answersForPlan(planDao.plan(row.planId), global).config
            val catalog = this.catalog.snapshot()
            swapInRun(runId, programExerciseId, slot, newExerciseId, config, catalog, expectedExerciseId)
        }
    }

    /** Runs inside a transaction and takes config and catalog from the caller. */
    private suspend fun swapInRun(
        runId: String,
        programExerciseId: Long,
        slot: String,
        newExerciseId: String,
        config: LifterConfig,
        catalog: ExerciseCatalog,
        expectedExerciseId: String? = null,
    ): Boolean {
        return db.withTransaction {
            val run = runDao.openRun()?.takeIf { it.id == runId } ?: return@withTransaction false
            val row = programDao.exercise(programExerciseId)
                ?.takeIf { it.planId == run.planId && it.dayId == run.dayId } ?: return@withTransaction false
            if (row.exerciseIdFor(slot) == null) return@withTransaction false
            val tracks = runDao.tracks(runId)
            if (tracks.slotSkipped(programExerciseId, slot)) return@withTransaction false
            val active = tracks.firstOrNull {
                it.programExerciseId == programExerciseId && it.slot == slot && !it.retired
            }
            if (expectedExerciseId != null && active?.exerciseId != expectedExerciseId) return@withTransaction false
            if (active?.exerciseId == newExerciseId) return@withTransaction true
            val retired = tracks.firstOrNull {
                it.programExerciseId == programExerciseId && it.slot == slot && it.exerciseId == newExerciseId && it.retired
            }
            val seed = swapSeed(row, slot, newExerciseId, tracks, config, catalog)
            var replacement = retired?.copy(retired = false, dropped = false, updatedAt = clock.millis()) ?: run {
                val base = seed ?: return@withTransaction false
                runTrack(runId, row, slot, newExerciseId, CarryForward.cleared(base))
            }
            val otherSlot = if (slot == Slot.MAIN) Slot.SS else Slot.MAIN
            val partner = tracks.firstOrNull {
                it.programExerciseId == programExerciseId && it.slot == otherSlot && !it.retired
            }
            var alignedPartner = partner
            if (partner != null) {
                val swappedRows = SetJson.decodeSets(replacement.setsJson)
                val otherRows = SetJson.decodeSets(partner.setsJson)
                val count = maxOf(swappedRows.size, otherRows.size)
                val swappedSeed = if (retired != null) SetJson.decodeSets(retired.plannedJson)
                    else seed.orEmpty()
                val programSlot = ProgramSlot(row.id, row.position, row.toDomain())
                val performed = performedExercise(programSlot, partner.exerciseId, catalog, otherSlot)
                val otherSeed = if (otherSlot == Slot.SS) {
                    SlotSeeds.partner(performed.entry, count, config)
                } else {
                    SlotSeeds.main(programSlot.exercise.copy(
                        exerciseId = performed.exerciseId, prescription = performed.prescription,
                    ), performed.entry, config)
                }?.takeIf { it.isNotEmpty() } ?: SetJson.decodeSets(partner.plannedJson)
                if ((swappedRows.size < count && swappedSeed.isEmpty()) ||
                    (otherRows.size < count && otherSeed.isEmpty())) return@withTransaction false
                val aligned = RunReplan.alignPaired(swappedRows, otherRows, swappedSeed, otherSeed)
                replacement = replacement.copy(setsJson = SetJson.encodeSets(aligned.first))
                if (aligned.second != otherRows) {
                    alignedPartner = partner.copy(setsJson = SetJson.encodeSets(aligned.second), updatedAt = clock.millis())
                }
            }
            active?.let { runDao.updateTrack(it.copy(retired = true, updatedAt = clock.millis())) }
            writeRunTracks(listOfNotNull(replacement, alignedPartner?.takeIf { it != partner }))
            true
        }
    }

    /**
     * Atomically edits working values or starts and edits the addressed run.
     * A preview edit naming another exercise was made before a swap.
     */
    suspend fun applyToRun(
        runId: String,
        planId: Long,
        dayId: String,
        edits: List<TrackEdit>,
    ): RunWriteResult {
        plans.ensurePlanSettingsMigrated()
        val global = settings.globalLifterFlow.first()
        return db.withTransaction {
            if (runId.isBlank() || edits.isEmpty()) return@withTransaction RunWriteResult.Rejected
            val day = programDao.day(planId, dayId) ?: return@withTransaction RunWriteResult.Rejected
            val config = answersForPlan(planDao.plan(planId), global).config
            val catalog = this.catalog.snapshot()
            val open = runDao.openRun()
            val tracks = open?.takeIf { it.planId == planId && it.dayId == dayId }?.let { runDao.tracks(it.id) }
            val seeds = seedPlan(day, tracks, config, catalog)
            if (open?.id == runId) {
                if (open.planId != planId || open.dayId != dayId) return@withTransaction RunWriteResult.Rejected
                val resolved = resolveRunEdits(runId, day, runDao.tracks(runId), edits, seeds, reopenRetired = true)
                if (resolved.failure != null) return@withTransaction resolved.failure
                writeRunTracks(resolved.tracks)
                return@withTransaction RunWriteResult.InRun(runId, false)
            }
            if (day.nextRunId != runId) return@withTransaction RunWriteResult.Rejected
            val slots = programDao.exercisesForDay(planId, dayId).associateBy { it.id }
            val working = linkedMapOf<Pair<Long, String>, List<LoggedSet>>()
            var doneTick = false
            for (edit in edits) {
                val slot = slots[edit.programExerciseId] ?: return@withTransaction RunWriteResult.Rejected
                if (slot.exerciseIdFor(edit.slot) == null) return@withTransaction RunWriteResult.Rejected
                if (edit.exerciseId != null && edit.exerciseId != slot.exerciseIdFor(edit.slot)) return@withTransaction RunWriteResult.Rejected
                val key = edit.programExerciseId to edit.slot
                val stored = working[key] ?: workingBase(day, slot, edit.slot, seeds)
                val base = stored ?: if (edit.createsMissing) emptyList() else return@withTransaction RunWriteResult.Rejected
                val result = edit.transform(base) ?: return@withTransaction RunWriteResult.Unchanged
                if (stored == null && result.isEmpty()) return@withTransaction RunWriteResult.Unchanged
                if (result.count { it.done } > base.count { it.done }) doneTick = true
                working[key] = result
            }
            if (!doneTick) {
                working.forEach { (key, result) ->
                    programDao.upsertLog(logEntity(planId, dayId, key.first, key.second, result))
                }
                return@withTransaction RunWriteResult.WorkingValues
            }
            if (open != null) return@withTransaction RunWriteResult.Rejected
            val initial = initialRunTracks(day, seeds)
            val resolved = resolveRunEdits(runId, day, initial, edits, seeds)
            if (resolved.failure != null) return@withTransaction resolved.failure
            startRun(day, initial)
            val inserted = runDao.tracks(runId)
            writeRunTracks(resolved.tracks.map { edited ->
                // Phone edits can create tracks absent from the plan seed and initial insert.
                edited.copy(id = inserted.firstOrNull { it.sameKey(edited) }?.id ?: runDao.insertTrack(edited),
                    setsJson = SetJson.encodeSets(SetJson.decodeSets(edited.setsJson).mapIndexed { i, set -> set.copy(plannedIndex = i) }))
            })
            RunWriteResult.InRun(runId, true)
        }
    }

    /** The bounded path for a delta that carries no run: an old watch. */
    suspend fun applyToOpenRun(
        planId: Long, dayId: String, editedAtMillis: Long, edits: List<TrackEdit>,
    ): RunWriteResult = db.withTransaction {
        val open = runDao.openRun() ?: return@withTransaction RunWriteResult.Rejected
        val day = programDao.day(planId, dayId) ?: return@withTransaction RunWriteResult.Rejected
        if (open.planId != planId || open.dayId != dayId || edits.isEmpty() ||
            editedAtMillis <= (day.closedAt ?: Long.MIN_VALUE)) return@withTransaction RunWriteResult.Rejected
        val resolved = resolveRunEdits(open.id, day, runDao.tracks(open.id), edits, emptyMap())
        if (resolved.failure != null) return@withTransaction resolved.failure
        writeRunTracks(resolved.tracks)
        RunWriteResult.InRun(open.id, false)
    }

    suspend fun adoptLegacySessionStartIfNeeded() {
        val stamp = settings.takeLegacySessionStart() ?: return
        db.withTransaction {
            val run = runDao.openRun() ?: return@withTransaction
            if ((stamp.planId == null || stamp.planId == run.planId) &&
                stamp.date == Instant.ofEpochMilli(run.startedAt).atZone(clock.zone).toLocalDate().toString() &&
                stamp.startedAtMillis < run.startedAt) {
                runDao.updateStartedAt(run.id, stamp.startedAtMillis)
            }
        }
    }

    private data class ResolvedRunEdits(
        val tracks: List<RunTrackEntity> = emptyList(),
        val failure: RunWriteResult? = null,
    )

    private suspend fun resolveRunEdits(
        runId: String,
        day: ProgramDayEntity,
        tracks: List<RunTrackEntity>,
        edits: List<TrackEdit>,
        seeds: Map<Pair<Long, String>, List<LoggedSet>>,
        reopenRetired: Boolean = false,
    ): ResolvedRunEdits {
        val resolved = tracks.toMutableList()
        val changed = linkedSetOf<Int>()
        for (edit in edits) {
            if (tracks.slotSkipped(edit.programExerciseId, edit.slot)) {
                return ResolvedRunEdits(failure = RunWriteResult.Rejected)
            }
            val seed = seeds[edit.programExerciseId to edit.slot]
            var created: List<LoggedSet>? = null
            var index = resolved.indexOfFirst {
                it.programExerciseId == edit.programExerciseId && it.slot == edit.slot &&
                    (if (edit.exerciseId != null) it.exerciseId == edit.exerciseId else !it.retired)
            }
            if (reopenRetired && resolved.none {
                    it.programExerciseId == edit.programExerciseId && it.slot == edit.slot && !it.retired
                }) {
                val slot = programDao.exercise(edit.programExerciseId)
                    ?.takeIf { it.planId == day.planId && it.dayId == day.dayId }
                val exerciseId = slot?.exerciseIdFor(edit.slot)
                if (exerciseId != null && (edit.exerciseId == null || edit.exerciseId == exerciseId)) {
                    val retiredIndex = resolved.indexOfFirst {
                        it.programExerciseId == edit.programExerciseId && it.slot == edit.slot &&
                            it.exerciseId == exerciseId && it.retired
                    }
                    if (retiredIndex >= 0) {
                        resolved[retiredIndex] = reopenTrack(resolved[retiredIndex], seeds)
                        index = retiredIndex
                    }
                }
            }
            if (index < 0) {
                // Unknown exercises have no plan seed; a phone edit can supply their first rows.
                val base = seed ?: if (edit.createsMissing) {
                    edit.transform(emptyList())?.takeIf { it.isNotEmpty() }?.also { created = it }?.let(CarryForward::cleared)
                        ?: return ResolvedRunEdits(failure = RunWriteResult.Unchanged)
                } else return ResolvedRunEdits(failure = RunWriteResult.Rejected)
                val slot = programDao.exercise(edit.programExerciseId)
                    ?.takeIf { it.planId == day.planId && it.dayId == day.dayId }
                    ?: return ResolvedRunEdits(failure = RunWriteResult.Rejected)
                val exerciseId = slot.exerciseIdFor(edit.slot)
                    ?: return ResolvedRunEdits(failure = RunWriteResult.Rejected)
                if (edit.exerciseId != null && edit.exerciseId != exerciseId) {
                    return ResolvedRunEdits(failure = RunWriteResult.Rejected)
                }
                val candidate = runTrack(runId, slot, edit.slot, exerciseId, CarryForward.cleared(base))
                if (resolved.any { it.sameKey(candidate) }) return ResolvedRunEdits(failure = RunWriteResult.Rejected)
                resolved.add(candidate)
                index = resolved.lastIndex
            }
            val track = resolved[index]
            val current = SetJson.decodeSets(track.setsJson)
            val result = created ?: edit.transform(current)
                ?: return ResolvedRunEdits(failure = RunWriteResult.Unchanged)
            resolved[index] = track.copy(setsJson = SetJson.encodeSets(result), updatedAt = clock.millis())
            changed.add(index)
        }
        return ResolvedRunEdits(changed.map { resolved[it] })
    }

    private fun RunTrackEntity.sameKey(other: RunTrackEntity): Boolean =
        runId == other.runId && programExerciseId == other.programExerciseId && slot == other.slot && exerciseId == other.exerciseId

    private fun RunTrackEntity.canReplan(isTimed: Boolean): Boolean {
        val captured = SetJson.decodeSets(plannedJson).firstOrNull() ?: return false
        return (captured.seconds > 0) == isTimed
    }

    private fun reopenTrack(
        track: RunTrackEntity,
        seeds: Map<Pair<Long, String>, List<LoggedSet>>,
    ): RunTrackEntity = track.copy(
        retired = false,
        setsJson = track.programExerciseId?.let { seeds[it to track.slot] }
            ?.takeIf { track.canReplan((it.firstOrNull()?.seconds ?: 0) > 0) }?.let {
                SetJson.encodeSets(RunReplan.represcribe(SetJson.decodeSets(track.setsJson), it))
            } ?: track.setsJson,
        updatedAt = clock.millis(),
    )

    private suspend fun writeRunTracks(tracks: List<RunTrackEntity>) {
        tracks.forEach { if (it.id == 0L) runDao.insertTrack(it) else runDao.updateTrack(it) }
    }

    private fun ProgramExerciseEntity.exerciseIdFor(slot: String): String? = when (slot) {
        Slot.MAIN -> exerciseId
        Slot.SS -> supersetExerciseId
        else -> null
    }

    private suspend fun workingBase(
        day: ProgramDayEntity,
        slot: ProgramExerciseEntity,
        slotName: String,
        seeds: Map<Pair<Long, String>, List<LoggedSet>>,
    ): List<LoggedSet>? {
        val stored = programDao.logForSlot(day.planId, day.dayId, slot.id, slotName)
        val sets = stored?.let { SetJson.decodeSets(it.setsJson) }
            ?: seeds[slot.id to slotName]
        return sets?.map {
            it.copy(done = false, startedAtMillis = null, completedAtMillis = null, plannedIndex = null)
        }
    }

    private fun runTrack(
        runId: String,
        slot: ProgramExerciseEntity,
        slotName: String,
        exerciseId: String,
        base: List<LoggedSet>,
    ): RunTrackEntity = RunTrackEntity(
        0, runId, slot.position, slot.id, slotName, exerciseId, false,
        SetJson.encodeSets(base), SetJson.encodeSets(base.mapIndexed { i, s -> s.copy(plannedIndex = i) }), clock.millis(),
    )

    private suspend fun initialRunTracks(day: ProgramDayEntity, seeds: Map<Pair<Long, String>, List<LoggedSet>>): List<RunTrackEntity> {
        val tracks = mutableListOf<RunTrackEntity>()
        for (slot in programDao.exercisesForDay(day.planId, day.dayId)) {
            for (slotName in listOf(Slot.MAIN, Slot.SS)) {
                val exerciseId = slot.exerciseIdFor(slotName) ?: continue
                val base = workingBase(day, slot, slotName, seeds) ?: continue
                tracks.add(runTrack(day.nextRunId, slot, slotName, exerciseId, base))
            }
        }
        return tracks
    }

    private suspend fun startRun(day: ProgramDayEntity, tracks: List<RunTrackEntity>) {
        runDao.insertRun(WorkoutRunEntity(
            day.nextRunId, day.planId, day.dayId, day.title, planDao.plan(day.planId)?.name, clock.millis(),
        ))
        runDao.insertTracks(tracks)
    }

    /** Runs inside a transaction and takes config and catalog from the caller. */
    private suspend fun seedPlan(
        day: ProgramDayEntity,
        runTracks: List<RunTrackEntity>?,
        config: LifterConfig,
        catalog: ExerciseCatalog,
    ): Map<Pair<Long, String>, List<LoggedSet>> {
        val rows = programDao.exercisesForDay(day.planId, day.dayId)
        val tracks = runTracks.orEmpty()
        val dropped = rows.filter { row ->
            tracks.isPartnerDropped(row.id, RunTrackEntity::programExerciseId, RunTrackEntity::slot, RunTrackEntity::dropped)
        }.mapTo(mutableSetOf()) { it.id }
        val skipped = rows.filter { tracks.slotSkipped(it.id, Slot.MAIN) }.mapTo(mutableSetOf()) { it.id }
        val slots = rows.map { ProgramSlot(it.id, it.position, it.toDomain()) }.forRun(dropped, skipped)
        val existing = if (runTracks != null) {
            runTracks.filterNot { it.retired }.mapNotNull { track ->
                track.programExerciseId?.let { (it to track.slot) to SetJson.decodeSets(track.setsJson).size }
            }.toMap()
        } else {
            rows.flatMap { row ->
                listOf(Slot.MAIN, Slot.SS).mapNotNull { slot ->
                    programDao.logForSlot(day.planId, day.dayId, row.id, slot)?.let {
                        (row.id to slot) to SetJson.decodeSets(it.setsJson).size
                    }
                }
            }.toMap()
        }
        return DaySeeds.plan(slots, existing, config, catalog)
    }

    /** Runs inside a transaction and takes config and catalog from the caller. */
    private fun swapSeed(
        row: ProgramExerciseEntity,
        slot: String,
        newExerciseId: String,
        tracks: List<RunTrackEntity>,
        config: LifterConfig,
        catalog: ExerciseCatalog,
    ): List<LoggedSet>? = DaySeeds.swap(
        ProgramSlot(row.id, row.position, row.toDomain()), slot, newExerciseId,
        tracks.filterNot { it.retired }.mapNotNull { track ->
            track.programExerciseId?.let { (it to track.slot) to SetJson.decodeSets(track.setsJson).size }
        }.toMap(), config, catalog,
    )

    /** Runs inside a transaction and takes config and catalog from the caller. */
    private suspend fun seedRunTracks(
        day: ProgramDayEntity,
        tracks: List<RunTrackEntity>,
        config: LifterConfig,
        catalog: ExerciseCatalog,
        restoringLift: Long? = null,
    ) {
        val runId = day.nextRunId
        val seeds = seedPlan(day, tracks, config, catalog)
            .filterKeys { restoringLift == null || it.first == restoringLift }
        val existing = tracks.toMutableList()
        val seededSlots = mutableSetOf<Long>()
        for ((key, sets) in seeds) {
            val slot = programDao.exercise(key.first) ?: continue
            val exerciseId = slot.exerciseIdFor(key.second) ?: continue
            val track = runTrack(runId, slot, key.second, exerciseId, CarryForward.cleared(sets))
            val retiredIndex = existing.indexOfFirst { it.retired && it.sameKey(track) }
            if (retiredIndex >= 0) {
                val reopened = reopenTrack(existing[retiredIndex], seeds)
                runDao.updateTrack(reopened)
                existing[retiredIndex] = reopened
            } else {
                existing.add(track.copy(id = runDao.insertTrack(track)))
            }
            if (restoringLift != null) seededSlots.add(key.first)
        }
        for (id in seededSlots) {
            val main = existing.singleOrNull { it.programExerciseId == id && it.slot == Slot.MAIN && !it.retired } ?: continue
            val partner = existing.singleOrNull { it.programExerciseId == id && it.slot == Slot.SS && !it.retired } ?: continue
            val mainRows = SetJson.decodeSets(main.setsJson)
            val partnerRows = SetJson.decodeSets(partner.setsJson)
            if (mainRows.size == partnerRows.size) continue
            val mainSeed = seeds[id to Slot.MAIN] ?: SetJson.decodeSets(main.plannedJson)
            val partnerSeed = seeds[id to Slot.SS] ?: SetJson.decodeSets(partner.plannedJson)
            if (mainSeed.isEmpty() || partnerSeed.isEmpty()) continue
            val aligned = RunReplan.alignPaired(mainRows, partnerRows, mainSeed, partnerSeed)
            writeRunTracks(listOf(
                main.copy(setsJson = SetJson.encodeSets(aligned.first), updatedAt = clock.millis()),
                partner.copy(setsJson = SetJson.encodeSets(aligned.second), updatedAt = clock.millis()),
            ))
        }
    }

    /** Archives a run once, carries forward its working values, and advances its day. */
    suspend fun finishRun(runId: String): Long? = finishRunCapturing(runId)?.sessionId

    /** A finished run's session and the tracks it archived; [tracks] is null when an
     *  earlier finish already archived the run. */
    data class FinishedRun(val sessionId: Long, val tracks: List<RunTrack>?)

    /** [finishRun], also returning the exact tracks the transaction archived, so a caller
     *  describing the session cannot disagree with it after a concurrent edit (the watch's
     *  applier does not share the phone's lock). */
    suspend fun finishRunCapturing(runId: String): FinishedRun? {
        val bodyweight = plans.configFlow.first().bodyweightLb
        val catalog = this.catalog.snapshot()
        return db.withTransaction {
            sessionDao.sessionByRunId(runId)?.let { return@withTransaction FinishedRun(it.id, null) }
            val run = runDao.openRun()?.takeIf { it.id == runId } ?: return@withTransaction null
            val tracks = runDao.tracks(runId)
            val now = clock.millis()
            val sessionId = archiveRun(run, tracks, bodyweight, catalog, now)
            val day = run.planId?.let { planId -> run.dayId?.let { programDao.day(planId, it) } }
            if (day != null && day.nextRunId == run.id) {
                carryForward(day, tracks)
                planDao.plan(day.planId)?.let { advanceRotation(it, day.dayId) }
                programDao.closeDay(day.planId, day.dayId, mintRunId(), now)
            }
            runDao.deleteTracks(runId)
            runDao.deleteRun(runId)
            FinishedRun(sessionId, tracks.map { it.toRunTrack() })
        }
    }

    private suspend fun archiveRun(
        run: WorkoutRunEntity,
        tracks: List<RunTrackEntity>,
        bodyweight: Int,
        catalog: ExerciseCatalog,
        now: Long,
    ): Long {
        val sessionId = sessionDao.insertSession(WorkoutSessionEntity(
            id = 0, dayId = run.dayId ?: "", dayTitle = run.dayTitle, startedAt = run.startedAt,
            completedAt = now, bodyweightLb = bodyweight, planId = run.planId,
            planName = run.planId?.let { planDao.plan(it)?.name } ?: run.planName, runId = run.id,
            plannedJson = SessionPlanJson.encode(tracks.map {
                PlannedTrackDto(it.position, it.exerciseId, it.slot, it.retired, SetJson.decodeSets(it.plannedJson).map(SetDto::of))
            }),
        ))
        sessionDao.insertSets(tracks.flatMap { track ->
            SetJson.decodeSets(track.setsJson).mapIndexedNotNull { index, set ->
                if (track.retired && !set.done) null else SessionSetEntity(
                    id = 0, sessionId = sessionId, exerciseId = track.exerciseId,
                    exerciseName = catalog.find(track.exerciseId)?.name ?: track.exerciseId,
                    slot = track.slot, setIndex = index, kind = set.kind.name, weightLb = set.weightLb,
                    reps = set.reps, done = set.done, seconds = set.seconds, startedAtMillis = set.startedAtMillis,
                    completedAtMillis = set.completedAtMillis, trackPosition = track.position, plannedIndex = set.plannedIndex,
                )
            }
        })
        return sessionId
    }

    private suspend fun carryForward(day: ProgramDayEntity, tracks: List<RunTrackEntity>) {
        val slots = programDao.exercisesForDay(day.planId, day.dayId).associateBy { it.id }
        val changedShape = slots.values.filter { slot ->
            if (slot.keepToPlan) return@filter false
            val prescription = PrescriptionJson.decode(slot.prescriptionJson) ?: return@filter false
            val main = tracks.firstOrNull {
                it.programExerciseId == slot.id && it.slot == Slot.MAIN && it.exerciseId == slot.exerciseId
            } ?: return@filter false
            SetJson.decodeSets(main.plannedJson).isNotEmpty() &&
                !main.canReplan(prescription.tracking == TrackingType.TIMED)
        }.map { it.id }.toSet()
        changedShape.forEach { deleteSlotWorkingValues(slots.getValue(it)) }
        for (track in tracks) {
            if (track.retired || track.dropped) continue
            val slot = slots[track.programExerciseId] ?: continue
            if (slot.exerciseIdFor(track.slot) != track.exerciseId) continue
            if (slot.id in changedShape) continue
            if (slot.keepToPlan) {
                programDao.deleteLogForSlot(day.planId, day.dayId, slot.id, track.slot)
            } else {
                programDao.upsertLog(logEntity(day.planId, day.dayId, slot.id, track.slot, CarryForward.next(
                    SetJson.decodeSets(track.plannedJson), SetJson.decodeSets(track.setsJson),
                )))
            }
        }
    }

    /** Unticks the open workout's rows, including retired tracks, without changing their values. */
    suspend fun clearRunChecks(runId: String): Boolean = db.withTransaction {
        if (runDao.openRun()?.id != runId) return@withTransaction false
        runDao.tracks(runId).forEach { track ->
            runDao.updateTrack(track.copy(
                setsJson = SetJson.encodeSets(SetJson.decodeSets(track.setsJson).map {
                    it.copy(done = false, startedAtMillis = null, completedAtMillis = null)
                }),
                updatedAt = clock.millis(),
            ))
        }
        true
    }

    /** Drops actuals, preserving working values and plan edits, and closes the day's identity. */
    suspend fun discardRun(runId: String): Boolean = db.withTransaction {
        val run = runDao.openRun()?.takeIf { it.id == runId } ?: return@withTransaction false
        val day = run.planId?.let { planId -> run.dayId?.let { programDao.day(planId, it) } }
        if (day != null && day.nextRunId == run.id) {
            for (log in programDao.logsForDay(day.planId, day.dayId)) {
                programDao.upsertLog(log.copy(setsJson = SetJson.encodeSets(CarryForward.cleared(SetJson.decodeSets(log.setsJson)))))
            }
            programDao.closeDay(day.planId, day.dayId, mintRunId(), clock.millis())
        }
        runDao.deleteTracks(runId)
        runDao.deleteRun(runId)
        true
    }

    private suspend fun closeRemovedRun(planId: Long, dayIds: Set<String>, bodyweight: Int, catalog: ExerciseCatalog) {
        val run = runDao.openRun()?.takeIf { it.planId == planId && it.dayId in dayIds } ?: return
        val tracks = runDao.tracks(run.id)
        if (tracks.any { track -> SetJson.decodeSets(track.setsJson).any { it.done } }) {
            archiveRun(run, tracks, bodyweight, catalog, clock.millis())
        }
        runDao.deleteTracks(run.id)
        runDao.deleteRun(run.id)
    }

    // --- helpers -------------------------------------------------------------

    private fun ExerciseLogEntity.toLoggedSlot(): LoggedSlot =
        LoggedSlot(programExerciseId, slot, SetJson.decodeSets(setsJson))

    companion object {
        const val CARDIO_LABEL_MAX_LENGTH = 80
    }
}
