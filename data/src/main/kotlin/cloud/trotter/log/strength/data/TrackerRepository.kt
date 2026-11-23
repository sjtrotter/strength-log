package cloud.trotter.log.strength.data

import cloud.trotter.log.strength.data.mapping.withSides
import androidx.room.withTransaction
import cloud.trotter.log.strength.data.serialization.SetupRecipeJson
import cloud.trotter.log.strength.data.serialization.setupAnswers
import cloud.trotter.log.strength.domain.planner.RunReplan
import cloud.trotter.log.strength.domain.progression.CardioHistory
import cloud.trotter.log.strength.domain.planner.Planner
import cloud.trotter.log.strength.domain.planner.PlanBranch
import cloud.trotter.log.strength.data.catalog.ExerciseCatalog
import cloud.trotter.log.strength.data.db.StrengthDatabase
import cloud.trotter.log.strength.data.db.dao.CustomExerciseDao
import cloud.trotter.log.strength.data.db.dao.ProgramDao
import cloud.trotter.log.strength.data.db.dao.SessionDao
import cloud.trotter.log.strength.data.db.entity.CardioRouteEntity
import cloud.trotter.log.strength.data.db.entity.CardioSessionEntity
import cloud.trotter.log.strength.data.db.entity.ExerciseLogEntity
import cloud.trotter.log.strength.data.db.entity.PlanEntity
import cloud.trotter.log.strength.data.db.entity.ProgramDayEntity
import cloud.trotter.log.strength.data.db.entity.ProgramExerciseEntity
import cloud.trotter.log.strength.data.db.entity.RunTrackEntity
import cloud.trotter.log.strength.data.db.entity.SessionSetEntity
import cloud.trotter.log.strength.data.db.entity.Slot
import cloud.trotter.log.strength.data.db.entity.WorkoutRunEntity
import cloud.trotter.log.strength.data.db.entity.WorkoutSessionEntity
import cloud.trotter.log.strength.data.mapping.toDomain
import cloud.trotter.log.strength.data.mapping.toDomainOrNull
import cloud.trotter.log.strength.data.mapping.toEntity
import cloud.trotter.log.strength.data.migration.reinterpretRepsAsSeconds
import cloud.trotter.log.strength.data.prefs.SettingsStore
import cloud.trotter.log.strength.data.serialization.planSettings
import cloud.trotter.log.strength.data.serialization.CardioResultDto
import cloud.trotter.log.strength.data.serialization.UndoReplanOfferDto
import cloud.trotter.log.strength.data.serialization.PlanGoalJson
import cloud.trotter.log.strength.domain.model.PlanGoal
import cloud.trotter.log.strength.data.serialization.CardioGoalUndoDto
import cloud.trotter.log.strength.data.serialization.CardioUndoDto
import cloud.trotter.log.strength.data.serialization.CardioUndoJson
import cloud.trotter.log.strength.data.serialization.CardioRuleJson
import cloud.trotter.log.strength.data.serialization.CardioRuleUndoDto
import cloud.trotter.log.strength.data.serialization.CardioDayUndoDto
import cloud.trotter.log.strength.data.serialization.CardioBlockUndoDto
import cloud.trotter.log.strength.data.serialization.ReasonCode
import cloud.trotter.log.strength.domain.progression.CardioEffort
import cloud.trotter.log.strength.data.serialization.CardioRouteJson
import cloud.trotter.log.strength.data.serialization.CardioBlockJson
import cloud.trotter.log.strength.data.serialization.OfferDto
import cloud.trotter.log.strength.data.serialization.OpeningDto
import cloud.trotter.log.strength.data.serialization.OpeningJson
import cloud.trotter.log.strength.data.serialization.ParkedLogsJson
import cloud.trotter.log.strength.data.serialization.ParkedTrackDto
import cloud.trotter.log.strength.data.serialization.PlannedTrackDto
import cloud.trotter.log.strength.data.serialization.PrescriptionJson
import cloud.trotter.log.strength.data.serialization.ProgressionUndoDto
import cloud.trotter.log.strength.data.serialization.ProgressionUndoJson
import cloud.trotter.log.strength.data.serialization.SessionPlanJson
import cloud.trotter.log.strength.data.serialization.SetDto
import cloud.trotter.log.strength.data.serialization.SetJson
import cloud.trotter.log.strength.data.serialization.SlotRuleJson
import cloud.trotter.log.strength.data.serialization.UndoLogDto
import cloud.trotter.log.strength.data.serialization.UndoReplanDto
import cloud.trotter.log.strength.data.serialization.UndoReplanDayDto
import cloud.trotter.log.strength.data.serialization.UndoDayColumnsDto
import cloud.trotter.log.strength.data.serialization.UndoPlanDto
import cloud.trotter.log.strength.data.serialization.UndoRowDto
import cloud.trotter.log.strength.data.serialization.UndoRuleDto
import cloud.trotter.log.strength.domain.model.Rotation
import cloud.trotter.log.strength.domain.library.ExerciseLibrary
import cloud.trotter.log.strength.domain.library.TrackingType
import cloud.trotter.log.strength.domain.library.tracking
import cloud.trotter.log.strength.domain.model.CardioActivity
import cloud.trotter.log.strength.domain.model.CardioBlock
import cloud.trotter.log.strength.domain.model.CardioFamily
import cloud.trotter.log.strength.domain.model.CardioRole
import cloud.trotter.log.strength.domain.model.CardioMode
import cloud.trotter.log.strength.domain.model.CardioSetResult
import cloud.trotter.log.strength.domain.model.DistanceSource
import cloud.trotter.log.strength.domain.model.LoggedSet
import cloud.trotter.log.strength.domain.model.NOTE_MAX_LENGTH
import cloud.trotter.log.strength.domain.model.Prescription
import cloud.trotter.log.strength.domain.model.PrescribedTarget
import cloud.trotter.log.strength.domain.model.Program
import cloud.trotter.log.strength.domain.model.ProgramDay
import cloud.trotter.log.strength.domain.model.ProgramDayKind
import cloud.trotter.log.strength.domain.model.ProgramExercise
import cloud.trotter.log.strength.domain.model.ProgressMeasure
import cloud.trotter.log.strength.domain.model.SetStructure
import cloud.trotter.log.strength.domain.progression.CardioChange
import cloud.trotter.log.strength.domain.progression.CardioMeasures
import cloud.trotter.log.strength.domain.progression.CardioRule
import cloud.trotter.log.strength.domain.progression.RunWalkLadder
import cloud.trotter.log.strength.domain.progression.SprintDose
import cloud.trotter.log.strength.domain.progression.sprintPlannedBlock
import cloud.trotter.log.strength.domain.records.SprintComparison
import cloud.trotter.log.strength.domain.progression.sprintMaximalMetres
import cloud.trotter.log.strength.domain.progression.CardioOutcome
import cloud.trotter.log.strength.domain.progression.Change
import cloud.trotter.log.strength.domain.progression.FinishedRule
import cloud.trotter.log.strength.domain.progression.LayoffKind
import cloud.trotter.log.strength.domain.progression.LightCycle
import cloud.trotter.log.strength.domain.progression.LightWeek
import cloud.trotter.log.strength.domain.progression.LoadFollowing
import cloud.trotter.log.strength.domain.progression.LoadSource
import cloud.trotter.log.strength.domain.progression.Estimate
import cloud.trotter.log.strength.domain.progression.LoadSteps
import cloud.trotter.log.strength.domain.progression.NextTimeMode
import cloud.trotter.log.strength.domain.progression.Outcome
import cloud.trotter.log.strength.domain.planner.LiftExperience
import cloud.trotter.log.strength.domain.progression.Progression
import cloud.trotter.log.strength.domain.progression.Reason
import cloud.trotter.log.strength.domain.progression.SlotRule
import cloud.trotter.log.strength.domain.progression.SharedProgression
import cloud.trotter.log.strength.domain.progression.automatic
import cloud.trotter.log.strength.domain.progression.SlotSeeding
import cloud.trotter.log.strength.domain.progression.WorkingRows
import cloud.trotter.log.strength.domain.seeding.TrackReseed
import cloud.trotter.log.strength.domain.seeding.SetEditor
import cloud.trotter.log.strength.domain.units.WeightUnit
import java.time.Clock
import java.time.ZoneId
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
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

    /** The zone every local sprint and ladder date uses; Start and Finish read the same one. */
    val zone: ZoneId get() = clock.zone

    private val restoreMarkerDao = db.restoreMarkerDao()
    private val cardioSessionDao = db.cardioSessionDao()
    private val cardioRouteDao = db.cardioRouteDao()
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
        val letter = ('A'..'Z').firstOrNull { candidate -> days.none { it.dayId == candidate.toString() } }
            ?.toString() ?: return@withTransaction null
        programDao.upsertDay(ProgramDayEntity(
            planId, letter, (days.maxOfOrNull { it.position } ?: -1) + 1, trimmed, "", null,
        ).freshRunId(mintRunId))
        letter
    }

    suspend fun addRestDay(planId: Long, title: String): String? = db.withTransaction {
        if (planDao.plan(planId) == null) return@withTransaction null
        val trimmed = title.trim()
        if (trimmed.length !in 1..30) return@withTransaction null
        val days = programDao.daysForPlan(planId)
        val letter = ('A'..'Z').firstOrNull { candidate -> days.none { it.dayId == candidate.toString() } }
            ?.toString() ?: return@withTransaction null
        val day = ProgramDayEntity(
            planId, letter, (days.maxOfOrNull { it.position } ?: -1) + 1, trimmed, "", null,
            kind = ProgramDayKind.REST.name,
        )
        if (assemble(days + day, emptyList()).rotationDays.none { it.kind != ProgramDayKind.REST }) {
            return@withTransaction null
        }
        programDao.upsertDay(day.freshRunId(mintRunId))
        letter
    }

    suspend fun moveDay(planId: Long, dayId: String, up: Boolean) {
        db.withTransaction {
            val days = programDao.daysForPlan(planId).sortedBy { it.position }
            val index = days.indexOfFirst { it.dayId == dayId }
            if (index < 0) return@withTransaction
            val neighbor = days.getOrNull(index + if (up) -1 else 1) ?: return@withTransaction
            val day = days[index]
            programDao.upsertDay(day.copy(position = neighbor.position))
            programDao.upsertDay(neighbor.copy(position = day.position))
        }
    }

    suspend fun takeRest(planId: Long, restDayId: String): Boolean = db.withTransaction {
        val plan = planDao.plan(planId) ?: return@withTransaction false
        if (plan.suggestedDayId != restDayId) return@withTransaction false
        val days = programDao.daysForPlan(planId)
        if (days.none { it.dayId == restDayId && it.kind == ProgramDayKind.REST.name }) return@withTransaction false
        planDao.updateSuggestedDay(planId, Rotation.nextFor(assemble(days, emptyList()), restDayId))
        true
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
        val bodyweight = settings.lifterProfileFlow.first().bodyweightLb
        val catalog = this.catalog.snapshot()
        return db.withTransaction {
            val plan = planDao.plan(planId) ?: return@withTransaction false
            val days = programDao.daysForPlan(planId)
            val removed = days.firstOrNull { it.dayId == dayId } ?: return@withTransaction false
            val remaining = days.filter { it.dayId != dayId }
            val trainingDays = assemble(remaining, emptyList()).rotationDays.filter { it.kind != ProgramDayKind.REST }
            if (trainingDays.isEmpty()) return@withTransaction false
            closeRemovedRun(planId, setOf(dayId), bodyweight, catalog)
            programDao.deleteLogsForDay(planId, dayId)
            programDao.deleteExercisesForDay(planId, dayId)
            programDao.deleteDay(planId, dayId)
            if (plan.suggestedDayId == dayId) {
                val rotationIds = trainingDays.map { it.id }.toSet()
                val rotation = remaining.filter { it.dayId in rotationIds }
                val next = rotation.firstOrNull { it.position > removed.position } ?: rotation.firstOrNull()
                planDao.updateSuggestedDay(planId, next?.dayId)
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
            val rule = CardioRuleJson.decode(day.cardioRuleJson)
            programDao.upsertDay(day.copy(cardioBlockJson = json, cardioJson = null, cardioWorkingJson = null,
                cardioRuleJson = day.cardioRuleJson.takeUnless { rule != null && rule.session?.sprint != true }))
            true
        }

    suspend fun setCardioKeepToPlan(planId: Long, dayId: String, keep: Boolean) {
        db.withTransaction {
            programDao.setCardioKeepToPlan(planId, dayId, keep)
            if (!keep) {
                val day = programDao.day(planId, dayId) ?: return@withTransaction
                val level = CardioRuleJson.decode(day.cardioRuleJson)?.ladderLevel ?: return@withTransaction
                val stored = day.toDomain(emptyList()).cardioToRun ?: return@withTransaction
                val block = CardioBlockJson.encode(RunWalkLadder.block(level, stored.activity))
                if (block != day.cardioBlockJson) {
                    programDao.setCardioState(planId, dayId, block, null, day.cardioRuleJson)
                }
            }
        }
    }

    suspend fun setCardioProgression(planId: Long, dayId: String, measure: ProgressMeasure, percent: Int) {
        programDao.setCardioProgression(planId, dayId, measure.name,
            percent.coerceIn(CardioMeasures.MIN_PERCENT, CardioMeasures.MAX_PERCENT))
    }

    suspend fun setCardioWorking(planId: Long, dayId: String, block: CardioBlock): Boolean = db.withTransaction {
        val day = programDao.day(planId, dayId) ?: return@withTransaction false
        val plan = CardioBlockJson.decode(day.cardioBlockJson) ?: return@withTransaction false
        if (day.cardioRuleJson != null || day.cardioKeepToPlan || block.activity != plan.activity ||
            block.sets.map { it.role } != plan.sets.map { it.role }) return@withTransaction false
        programDao.setCardioWorking(planId, dayId, if (block.sets == plan.sets) null else CardioBlockJson.encode(block))
        true
    }

    /** Sets belong to the slot, never the prescription JSON. Null keeps its current count.
     * A structure, when given, is written in the same transaction.
     * A change between timed and rep-shaped rows applies to future runs only. */
    suspend fun setPrescription(
        programExerciseId: Long,
        prescription: Prescription,
        sets: Int? = null,
        structure: SetStructure? = null,
    ) {
        plans.ensurePlanSettingsMigrated()
        val unit = settings.unitFlow.first()
        db.withTransaction {
            val row = programDao.exercise(programExerciseId) ?: return@withTransaction
            val count = sets ?: row.targetSets
            require(count in 1..20)
            val stored = PrescriptionJson.decode(row.prescriptionJson)
            val kept = if (prescription.test == null && stored?.test != null &&
                stored.target::class == prescription.target::class) prescription.copy(test = stored.test) else prescription
            val json = PrescriptionJson.encode(kept)
            if (structure != null && structure.name != row.structure) {
                programDao.setStructure(row.id, structure.name)
            }
            val updated = row.copy(prescriptionJson = json, targetSets = count, structure = structure?.name ?: row.structure)
            programDao.setPrescription(row.id, json, count)
            val catalog = this.catalog.snapshot()
            val rule = plans.defaultRule(updated, catalog).preservingSettings(SlotRuleJson.decode(row.ruleJson))
            replanSlot(updated.copy(ruleJson = SlotRuleJson.encode(rule)),
                unit, catalog)
            programDao.deleteLogsForExercise(row.planId, row.dayId, row.id)
            programDao.setRule(row.id, SlotRuleJson.encode(rule))
        }
    }

    suspend fun setStructure(programExerciseId: Long, structure: SetStructure) {
        plans.ensurePlanSettingsMigrated()
        val unit = settings.unitFlow.first()
        db.withTransaction {
            val row = programDao.exercise(programExerciseId) ?: return@withTransaction
            // Picking the current structure again must not replan rows or drop remembered values.
            if (row.structure == structure.name) return@withTransaction
            val updated = row.copy(structure = structure.name)
            programDao.setStructure(row.id, structure.name)
            val catalog = this.catalog.snapshot()
            val rule = plans.defaultRule(updated, catalog).preservingSettings(SlotRuleJson.decode(row.ruleJson))
            replanSlot(updated.copy(ruleJson = SlotRuleJson.encode(rule)),
                unit, catalog)
            programDao.deleteLogsForExercise(row.planId, row.dayId, row.id)
            programDao.setRule(row.id, SlotRuleJson.encode(rule))
        }
    }

    private suspend fun replanSlot(row: ProgramExerciseEntity, unit: WeightUnit, catalog: ExerciseCatalog) {
        val run = runDao.openRun() ?: return
        if (run.planId != row.planId || run.dayId != row.dayId) return
        val tracks = runDao.tracks(run.id).filter { it.programExerciseId == row.id }
        val slot = ProgramSlot(row.id, row.position, row.toDomain())
        val exercise = slot.exercise
        val entry = catalog.find(row.exerciseId)
        val isTimed = (exercise.prescription?.tracking ?: entry?.tracking) == TrackingType.TIMED
        val seeded = plans.seed(row.planId, slot, Slot.MAIN, 0, unit, catalog)?.rows ?: return
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
                Slot.SS -> plans.seed(row.planId, slot, Slot.SS, partnerMainSeed?.size ?: continue, unit, catalog)?.rows
                    ?: continue
                else -> continue
            }
            val trackIsTimed = if (track.slot == Slot.MAIN) isTimed
                else catalog.find(track.exerciseId)?.tracking == TrackingType.TIMED
            if (!track.canReplan(trackIsTimed)) continue
            runDao.updateTrack(track.copy(
                setsJson = SetJson.encodeSets(TrackReseed.represcribe(SetJson.decodeSets(track.setsJson), trackSeed)),
                updatedAt = clock.millis(),
            ))
        }
        val main = activeMain?.takeIf { it.exerciseId == row.exerciseId } ?: return
        if (!main.canReplan(isTimed)) return
        val current = SetJson.decodeSets(main.setsJson)
        val partner = tracks.firstOrNull { !it.retired && it.slot == Slot.SS }
        val mainRows = if (partner == null) TrackReseed.represcribe(current, seeded) else if (
            partner.exerciseId != row.exerciseIdFor(Slot.SS)
        ) {
            val partnerRows = SetJson.decodeSets(partner.setsJson)
            val partnerSeed = if (partnerRows.isEmpty()) {
                plans.runSwapRows(row, Slot.SS, partner.exerciseId, maxOf(current.size, seeded.size), unit, catalog) ?: return
            } else emptyList()
            val alignedSeed = TrackReseed.alignPaired(
                seeded, partnerRows, seeded, partnerSeed,
            ).first
            val rows = TrackReseed.represcribe(current, alignedSeed)
            if (rows.size > partnerRows.size) {
                runDao.updateTrack(partner.copy(
                    setsJson = SetJson.encodeSets(TrackReseed.alignPaired(rows, partnerRows, rows, partnerSeed).second),
                    updatedAt = clock.millis(),
                ))
            }
            rows
        } else {
            val partnerEntry = catalog.find(partner.exerciseId)
            if (partnerEntry == null) TrackReseed.represcribe(current, seeded) else {
                val partnerSeed = requireNotNull(plans.seed(row.planId, slot, Slot.SS, seeded.size, unit, catalog)).rows
                val paired = TrackReseed.represcribePaired(
                    current, SetJson.decodeSets(partner.setsJson), seeded, partnerSeed,
                )
                runDao.updateTrack(partner.copy(
                    setsJson = SetJson.encodeSets(paired.second), updatedAt = clock.millis(),
                ))
                paired.first
            }
        }
        runDao.updateTrack(main.copy(
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

    /** Replaces the selected plan's program and points the rotation at its first training day; its old program
     *  and live logs are cleared, history is untouched. No production caller: tests use it to seed a program. */
    suspend fun replaceProgram(program: Program) {
        plans.ensurePlanSettingsMigrated()
        val planId = plans.ensureSelectedPlan()
        val bodyweight = settings.lifterProfileFlow.first().bodyweightLb
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
            program.rotationDays.firstOrNull()?.let {
                planDao.updateSuggestedDay(planId, Rotation.trainingDayFrom(program, it.id))
            }
        }
    }

    /** The slot names its plan; a missing slot or mismatched day returns false. */
    suspend fun swapExerciseById(
        dayId: String,
        programExerciseId: Long,
        newExerciseId: String,
    ): Boolean {
        val unit = settings.unitFlow.first()
        return db.withTransaction {
            val catalog = this.catalog.snapshot()
            val row = programDao.exercise(programExerciseId) ?: return@withTransaction false
            if (row.dayId != dayId) return@withTransaction false
            if (newExerciseId == row.exerciseId) {
                clearSlotSkipState(row.planId, dayId, row.id)
                val run = runDao.openRun()?.takeIf { it.planId == row.planId && it.dayId == dayId }
                    ?: return@withTransaction true
                return@withTransaction swapInRun(run.id, row.id, Slot.MAIN, newExerciseId, unit, catalog)
            }
            swapInPlan(row.planId, row.dayId, row.id, newExerciseId, unit, catalog)
            true
        }
    }

    private suspend fun swapInPlan(
        planId: Long,
        dayId: String,
        programExerciseId: Long,
        newExerciseId: String,
        unit: WeightUnit,
        catalog: ExerciseCatalog,
    ) {
        val row = programDao.exercise(programExerciseId) ?: return
        clearSlotSkipState(planId, dayId, programExerciseId)
        retireActiveTrack(planId, dayId, programExerciseId, Slot.MAIN)
        val swapped = row.swappedTo(newExerciseId)
        programDao.replaceExercise(row.id, newExerciseId, swapped.structure, swapped.targetSets)
        programDao.deleteLogsForExercise(planId, dayId, programExerciseId)
        val replacement = programDao.exercise(programExerciseId) ?: return
        plans.ruleSlot(replacement, unit, catalog)
        val replacementRule = SlotRuleJson.decode(programDao.exercise(row.id)?.ruleJson)
        if (replacementRule != null) programDao.setRule(row.id,
            SlotRuleJson.encode(replacementRule.copy(nextTime = SlotRuleJson.decode(row.ruleJson)?.nextTime)))
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
        val unit = settings.unitFlow.first()
        val catalog = this.catalog.snapshot()
        db.withTransaction {
            val position = programDao.maxPosition(planId, dayId) + 1
            if (programDao.day(planId, dayId) == null) return@withTransaction
            val id = programDao.insertExercise(exercise.toEntity(planId, dayId, position))
            val row = programDao.exercise(id) ?: return@withTransaction
            plans.ruleSlot(row, unit, catalog)
            if (exercise.superset != null) plans.rulePartner(row, liveMainRows(row), unit, catalog)
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
        db.withTransaction {
            val row = programDao.exercise(programExerciseId) ?: return@withTransaction
            val run = runDao.openRun()?.takeIf { it.planId == row.planId && it.dayId == row.dayId }
            val track = run?.let { runDao.tracks(it.id) }?.firstOrNull {
                it.programExerciseId == row.id && it.slot == Slot.MAIN && !it.retired
            }
            val note = text.trim().take(NOTE_MAX_LENGTH)
            val now = clock.millis()
            if (track != null && track.exerciseId != row.exerciseId) {
                runDao.updateTrack(track.copy(note = note, updatedAt = now))
            } else {
                programDao.setExerciseNote(row.id, note, now)
            }
        }
    }

    suspend fun setKeepToPlan(programExerciseId: Long, keep: Boolean): Boolean =
        db.withTransaction {
            val row = programDao.exercise(programExerciseId) ?: return@withTransaction false
            if (keep && PrescriptionJson.decode(row.prescriptionJson) == null) return@withTransaction false
            if (row.keepToPlan == keep) return@withTransaction true
            programDao.setKeepToPlan(row.id, keep)
            if (keep) {
                parkWorkingValues(row)
                deleteSlotWorkingValues(row)
            } else {
                restoreParkedValues(row)
            }
            true
        }

    private suspend fun parkWorkingValues(row: ProgramExerciseEntity) {
        val tracks = listOf(Slot.MAIN, Slot.SS).mapNotNull { slot ->
            val exerciseId = row.exerciseIdFor(slot) ?: return@mapNotNull null
            val log = programDao.logForSlot(row.planId, row.dayId, row.id, slot) ?: return@mapNotNull null
            ParkedTrackDto(slot, exerciseId, log.setsJson)
        }
        if (tracks.isNotEmpty()) programDao.setParkedLogs(row.id, ParkedLogsJson.encode(tracks))
    }

    private suspend fun restoreParkedValues(row: ProgramExerciseEntity) {
        val parked = row.parkedLogsJson ?: return
        val catalog = this.catalog.snapshot()
        val prescription = PrescriptionJson.decode(row.prescriptionJson)
        val tracks = ParkedLogsJson.decode(parked)
        for (track in tracks) {
            if (track.exerciseId != row.exerciseIdFor(track.slot)) continue
            val sets = SetJson.decodeSets(track.setsJson)
            val first = sets.firstOrNull() ?: continue
            val tracking = if (track.slot == Slot.MAIN) {
                prescription?.tracking ?: catalog.find(track.exerciseId)?.tracking
            } else {
                catalog.find(track.exerciseId)?.tracking
            }
            // Unknown tracking means the catalog row is unreadable, so restore the stored shape as is.
            if (tracking != null && (first.seconds > 0) != (tracking == TrackingType.TIMED)) continue
            programDao.upsertLog(logEntity(row.planId, row.dayId, row.id, track.slot, sets))
        }
        programDao.setParkedLogs(row.id, null)
    }

    suspend fun resetSlotToPlan(programExerciseId: Long): Boolean = db.withTransaction {
        val row = programDao.exercise(programExerciseId) ?: return@withTransaction false
        if (row.keepToPlan || PrescriptionJson.decode(row.prescriptionJson) == null) return@withTransaction false
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
     * Only the SS track and the partner's rule are cleared and rewritten, the rows at the live MAIN count.
     * MAIN working values and the slot's rule survive the edit.
     */
    suspend fun setSupersetPartner(dayId: String, programExerciseId: Long, partnerExerciseId: String) {
        val unit = settings.unitFlow.first()
        val catalog = this.catalog.snapshot()
        db.withTransaction {
            val row = programDao.exercise(programExerciseId) ?: return@withTransaction
            if (row.dayId != dayId) return@withTransaction
            val planId = row.planId
            clearSlotSkipState(planId, dayId, row.id)
            retireActiveTrack(planId, dayId, row.id, Slot.SS)
            programDao.setSupersetExerciseId(row.id, partnerExerciseId)
            programDao.deleteLogForSlot(planId, dayId, row.id, Slot.SS)
            val partnered = row.copy(supersetExerciseId = partnerExerciseId)
            plans.rulePartner(partnered, liveMainRows(partnered),
                unit, catalog)
            if (row.supersetExerciseId != null) {
                val replacementRule = SlotRuleJson.decode(programDao.exercise(row.id)?.partnerRuleJson)
                if (replacementRule != null) programDao.setPartnerRule(row.id,
                    SlotRuleJson.encode(replacementRule.copy(nextTime = SlotRuleJson.decode(row.partnerRuleJson)?.nextTime)))
            }
        }
    }

    /** Drops the slot's superset partner, its SS track and the partner's rule, leaving the MAIN
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

    /** Rebuilds one day of a setup plan through [Planner.day]; its rows seed lazily.
     *  Plans without a v2 recipe have no reset (design §4.6.3); other days are untouched;
     *  a day the recipe does not produce is left alone. */
    suspend fun resetDayToTemplate(dayId: String, targetPlanId: Long? = null) {
        plans.ensurePlanSettingsMigrated()
        val planId = targetPlanId ?: plans.selectedPlanId() ?: return
        val plan = planDao.plan(planId) ?: return
        val day = plan.resetDay(dayId, settings.unitFlow.first()) ?: return
        db.withTransaction {
            val existing = programDao.day(planId, dayId) ?: return@withTransaction
            for (slot in programDao.exercisesForDay(planId, dayId)) {
                retireActiveTrack(planId, dayId, slot.id, Slot.MAIN)
                retireActiveTrack(planId, dayId, slot.id, Slot.SS)
            }
            programDao.deleteLogsForDay(planId, dayId)
            programDao.deleteExercisesForDay(planId, dayId)
            programDao.upsertDay(day.toEntity(planId).copy(position = existing.position,
                nextRunId = existing.nextRunId, closedAt = existing.closedAt))
            day.exercises.forEachIndexed { pos, exercise ->
                programDao.insertExercise(exercise.toEntity(planId, dayId, pos))
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

    /** A setup plan's own 65+ answer wins over the profile age (#592 may change the tie-break; it lives here). */
    private fun olderAdult(plan: PlanEntity?, profile: LifterProfile): Boolean =
        plan?.recipeJson?.let(SetupRecipeJson::olderAdult)
            ?: (profile.age?.let { it >= Progression.OLDER_ADULT_AGE } == true)

    // --- live logs -----------------------------------------------------------

    /** Pure read: seeding and writes remain the caller's responsibility. A seed also reads other days' rows,
     *  which re-emit through this day's `exercise_log` query because Room invalidates by table, and history,
     *  which [SessionDao.observeHistoryChanges] re-emits.
     *  Before Start it also shows each track's opening ([initialRunTracks]), still without writing. */
    fun observeDay(planId: Long, dayId: String): Flow<PerformedDay> = combine(
        plans.programFor(planId), plans.slotsFor(planId, dayId), dayTracksFlow(planId, dayId),
        plans.planSettingsFor(planId), combine(catalog.catalogFlow, sessionDao.observeHistoryChanges(), settings.unitFlow,
            combine(settings.lifterProfileFlow, planDao.observePlan(planId)) { profile, plan ->
                olderAdult(plan, profile)
            }.distinctUntilChanged(),
        ) { catalog, _, unit, olderAdult ->
            Triple(catalog, unit, olderAdult)
        },
    ) { program, slots, tracks, planSettings, (catalog, unit, olderAdult) ->
        val seeds = plans.seeds(planId, slots.forRun(tracks.droppedPartners, tracks.skippedLifts),
            tracks.tracks.associate { (it.programExerciseId to it.slot) to it.sets.size }, unit, catalog)
        val day = if (tracks.runOpen) null else programDao.day(planId, dayId)
        val openings = day?.let {
            initialRunTracks(it, seeds, unit, catalog, olderAdult)
                .filter { track -> track.openingJson != null }
                .mapNotNull { track -> track.programExerciseId?.let { id ->
                    (id to track.slot) to track
                } }.toMap()
        }.orEmpty()
        PerformedDay.of(planId, dayId, program, slots, tracks, planSettings, catalog, seeds.mapValues { it.value.rows },
            openings.mapValues { SetJson.decodeSets(it.value.plannedJson) },
            openings.mapValues { OpeningJson.decode(it.value.openingJson) })
    }

    /** Each of the day's slots' next-time MAIN rows: stored (`exercise_log`, whatever run is open), else its seed
     *  ([PlanRepository.seed]). An unknown, unprescribed slot has no entry, as in [PlanRepository.seeds]. Pure read;
     *  re-emits on the same writes as [observeDay]. */
    fun observePlanRows(planId: Long, dayId: String): Flow<Map<Long, List<LoggedSet>>> = combine(
        plans.slotsFor(planId, dayId), programDao.observeLogs(planId, dayId), settings.unitFlow,
        combine(catalog.catalogFlow, sessionDao.observeHistoryChanges()) { catalog, _ -> catalog },
    ) { slots, logs, unit, catalog ->
        val stored = logs.filter { it.slot == Slot.MAIN }
            .associate { it.programExerciseId to SetJson.decodeSets(it.setsJson) }
        slots.mapNotNull { slot ->
            val exercise = slot.exercise
            if (catalog.find(exercise.exerciseId) == null && exercise.prescription == null) return@mapNotNull null
            val rows = stored[slot.programExerciseId]
                ?: plans.seed(planId, slot, Slot.MAIN, 0, unit, catalog)?.rows
            rows?.let { slot.programExerciseId to it }
        }.toMap()
    }

    fun dayTracksFlow(planId: Long, dayId: String): Flow<DayTracks> = combine(
        openRunFlow, programDao.observeDays(planId), programDao.observeLogs(planId, dayId),
        programDao.observeExercisesForDay(planId, dayId),
    ) { open, days, logs, slots ->
        if (open?.planId == planId && open.dayId == dayId) {
            DayTracks(open.id, true, open.tracks.filter { !it.retired }.mapNotNull { track ->
                track.programExerciseId?.let { LoggedSlot(it, track.slot, track.sets, track.exerciseId, track.note, track.opening, track.planned) }
            }, retired = open.tracks.filter { it.retired && (!it.dropped || it.sets.any { set -> set.done }) },
                droppedPartners = open.tracks.mapNotNull { it.programExerciseId }.filterTo(mutableSetOf()) { id ->
                    open.isPartnerDropped(id)
                }, skippedLifts = open.tracks.mapNotNull { it.programExerciseId }.filterTo(mutableSetOf()) { id ->
                    open.isLiftSkipped(id)
                }, draft = open.draft)
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
        val unit = settings.unitFlow.first()
        db.withTransaction {
            val day = programDao.day(planId, dayId) ?: return@withTransaction
            val catalog = this.catalog.snapshot()
            val run = runDao.openRun()?.takeIf { it.planId == planId && it.dayId == dayId }
            if (run == null) {
                keepSeeds(day, seedPlan(day, null, unit, catalog))
            } else {
                seedRunTracks(day, runDao.tracks(run.id), unit, catalog)
            }
        }
    }

    /** Writes missing stored rows with their seed rule on ruled columns outside Plan mode (spec W2b-3a decision 3).
     *  Room only; runs in the caller's transaction. */
    private suspend fun keepSeeds(day: ProgramDayEntity, seeds: Map<Pair<Long, String>, SlotSeeding.Seeded>) {
        val undoAfter = ProgressionUndoJson.decode(sessionDao.lastSessionForDay(day.planId, day.dayId)?.progressionUndoJson)
            ?.rules.orEmpty().associate { (it.programExerciseId to it.slot) to it.afterJson }
        for ((key, seeded) in seeds) {
            val (id, name) = key
            if (programDao.logForSlot(day.planId, day.dayId, id, name) != null) continue
            val slot = programDao.exercise(id) ?: continue
            programDao.upsertLog(logEntity(day.planId, day.dayId, id, name, seeded.rows))
            val storedRule = slot.ruleJsonFor(name)
            if (!slot.keepToPlan && storedRule != null && storedRule != undoAfter[key]) {
                val encoded = SlotRuleJson.encode(seeded.rule)
                if (encoded != storedRule) setRuleFor(id, name, encoded)
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
     * A note is trimmed and capped at [NOTE_MAX_LENGTH].
     * A new session never starts with [CardioSessionEntity.increaseApplied].
     * A set without a reading source or GPS total gets the readings its totals prove ([FinishedRule.atEnd]).
     * A session that counts is judged in the same transaction ([CardioJudge]); an Automatic plan takes its next block.
     */
    suspend fun logCardioSession(
        session: CardioSessionEntity,
        sets: List<CardioSetResult> = emptyList(),
        finishDay: Boolean = false,
        route: String? = null,
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
        val unit = settings.unitFlow.first()
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
            if (plan != null) {
                sessionDao.clearProgressionUndoBefore(plan.id, stamped.startedAt)
                cardioSessionDao.clearProgressionUndoBefore(plan.id, stamped.startedAt)
            }
            val id = cardioSessionDao.insert(stamped.copy(id = 0, note = stamped.note.trim().take(NOTE_MAX_LENGTH), increaseApplied = false))
            if (sets.isNotEmpty()) cardioSetResultDao.insertAll(sets.mapIndexed { position, set ->
                // A GPS row carries what its track read; an unread reading stays unknown.
                (if (set.readingSource != null || set.distanceSource == DistanceSource.GPS) set else FinishedRule.atEnd(set)).toEntity(id, position)
            })
            if (route != null && CardioRouteJson.decode(route) != null) {
                cardioRouteDao.insert(CardioRouteEntity(id, route))
            }
            if (sets.isNotEmpty()) cardioJudgment(id, first = true, unit = unit)?.let {
                applyCardio(it)
                appendSprintAttempts(it)
            }
            if (finishDay && plan != null && stamped.dayId != null) {
                val counters = afterDay(plan, stamped.dayId, reactive = false, finishedAt = stamped.completedAt, unit = unit)
                counters?.let {
                    val entry = requireNotNull(cardioSessionDao.byId(id))
                    val undo = CardioUndoJson.decode(entry.progressionUndoJson) ?: CardioUndoDto()
                    cardioSessionDao.update(entry.copy(progressionUndoJson = CardioUndoJson.encode(undo.copy(plan = counters))))
                }
                if (counters?.replan == null) advanceRotation(plan, stamped.dayId)
            }
            id
        }
    }

    private class CardioJudgment(val sessionId: Long, val planId: Long, val dayId: String, val automatic: Boolean,
                                 val stored: CardioBlock, val outcome: CardioOutcome,
                                 val preWrite: CardioRule? = null, val cycle: Int? = null,
                                 val runDays: List<ProgramDayEntity>? = null, val choice: Boolean? = null) {
        val ladderDrop: Boolean get() {
            val level = preWrite?.ladderLevel ?: return false
            return (outcome.change as? CardioChange.LevelTo)?.level?.let { it < level } == true
        }
    }

    /** [sessionId]'s judgment when it counts (decision 3), read from Room only; null otherwise.
     *  Ruled amendments require their recorded pre-write state and rotation; run replay also checks every day
     *  written by that Finish. A Finish that re-planned stores a later effort or distance only; Undo first, then re-rate. */
    private suspend fun cardioJudgment(sessionId: Long, first: Boolean, unit: WeightUnit, choice: Boolean? = null): CardioJudgment? {
        val session = cardioSessionDao.byId(sessionId) ?: return null
        val planId = session.planId ?: return null
        val dayId = session.dayId ?: return null
        val plan = planDao.plan(planId) ?: return null
        val entity = programDao.day(planId, dayId) ?: return null
        if (session.increaseApplied) return null
        if (cardioSessionDao.newestForDay(planId, dayId)?.id != sessionId) return null
        // A re-plan can keep the judged day's columns, so the column gates alone would replay it; Undo comes first.
        if (!first && CardioUndoJson.decode(session.progressionUndoJson)?.plan?.replan != null) return null
        val activity = CardioActivity.entries.firstOrNull { it.name == session.activity } ?: return null
        val names = CardioFamily.of(activity).activities.map { it.name }
        val sessions = cardioSessionDao.forActivities(names)
        val sets = cardioSetResultDao.forActivities(names).groupBy { it.cardioSessionId }
            .mapValues { (_, rows) -> rows.mapNotNull { it.toDomainOrNull() } }
        val day = entity.toDomain(emptyList())
        val stored = day.cardioToRun ?: return null
        if (day.cardioRule?.session?.sprint == true) {
            val undo = CardioUndoJson.decode(session.progressionUndoJson)
            val recorded = undo?.rule
            if (!first && (recorded == null || entity.cardioRuleJson != recorded.after)) return null
            val preWrite = if (first) requireNotNull(day.cardioRule) else CardioRuleJson.decode(recorded?.before) ?: return null
            val cycle = if (first) plan.cyclesCompleted else requireNotNull(recorded).cycle
            val sprint = cardioPlan(plan, programDao.daysForPlan(planId), cycle, unit, dayId to preWrite)
            val judged = CardioJudge.judge(day.copy(cardioRule = preWrite), session, sessions, sets,
                plan.planSettings.runCap, sprint) ?: return null
            return CardioJudgment(sessionId, plan.id, dayId, plan.autoApply, stored, judged.outcome, preWrite, cycle)
        }
        if (day.cardioRule != null) {
            val undo = CardioUndoJson.decode(session.progressionUndoJson)
            val recorded = undo?.rule
            val currentDays = programDao.daysForPlan(planId)
            val records = if (first) emptyList<CardioDayUndoDto>() else undo?.days.orEmpty()
            if (!first && (recorded == null || entity.cardioRuleJson != recorded.after ||
                    records.any { record -> !record.matches(currentDays.firstOrNull { it.dayId == record.dayId }) })) return null
            val preWrite = if (first) requireNotNull(day.cardioRule) else CardioRuleJson.decode(recorded?.before) ?: return null
            val cycle = if (first) plan.cyclesCompleted else requireNotNull(recorded).cycle
            val preWriteDays = currentDays.map { current ->
                val record = records.firstOrNull { it.dayId == current.dayId }
                val restored = if (record == null) current else current.copy(cardioBlockJson = record.blockBefore,
                    cardioWorkingJson = record.workingBefore, cardioRuleJson = record.ruleBefore)
                if (current.dayId == dayId && !first) restored.copy(cardioRuleJson = requireNotNull(recorded).before) else restored
            }
            val inputs = cardioPlan(plan, preWriteDays, cycle, unit)
            val program = inputs.program
            if (preWrite.ladderLevel != null && program.days.any {
                    it.id != dayId && it.cardioRule?.ladderLevel != null && it.cardioRule != preWrite
                }) return null
            val preWriteDay = program.days.first { it.id == dayId }
            val preWriteStored = preWriteDay.cardioToRun ?: return null
            val judged = CardioJudge.judge(preWriteDay, session, sessions, sets, plan.planSettings.runCap, inputs) ?: return null
            if (judged.opening != preWriteStored) return null
            return CardioJudgment(sessionId, planId, dayId, choice ?: undo?.result?.choice ?: plan.autoApply,
                preWriteStored, judged.outcome, preWrite, cycle, preWriteDays, choice ?: undo?.result?.choice)
        }
        val judged = CardioJudge.judge(day, session, sessions, sets, plan.planSettings.runCap) ?: return null
        if (judged.opening != stored) return null
        return CardioJudgment(sessionId, plan.id, dayId, plan.autoApply, stored, judged.outcome)
    }

    /** Room-only plan inputs, with the judgment's days, rotation and optional pre-write sprint rule. */
    private suspend fun cardioPlan(plan: PlanEntity, days: List<ProgramDayEntity>, cycle: Int, unit: WeightUnit,
                                   override: Pair<String, CardioRule>? = null): CardioPlan {
        val program = assemble(days, programDao.exercisesForPlan(plan.id)).let { program ->
            if (override == null) program else program.copy(days = program.days.map {
                if (it.id == override.first) it.copy(cardioRule = override.second) else it
            })
        }
        return CardioPlan.of(plan.copy(cyclesCompleted = cycle), program, unit, zone)
    }

    /** Rule-less increases write only on Automatic; sprint state always writes. Ruled run replay replaces
     *  the previous Finish write, with Ask retaining only Repeat credit changes. */
    private suspend fun applyCardio(judgment: CardioJudgment) {
        if (judgment.runDays != null) {
            applyRuledRun(judgment, judgment.runDays)
            return
        }
        if (judgment.preWrite != null) {
            val session = requireNotNull(cardioSessionDao.byId(judgment.sessionId))
            val results = cardioSetResultDao.forSession(session.id).mapNotNull { it.toDomainOrNull() }
            val cycle = requireNotNull(judgment.cycle)
            var next = requireNotNull(judgment.outcome.rule)
            if (sprintMaximalMetres(results) > 0) next = next.copy(maximalCycle = cycle)
            val after = CardioRuleJson.encode(next)
            if (programDao.day(judgment.planId, judgment.dayId)?.cardioRuleJson != after) {
                programDao.setCardioRule(judgment.planId, judgment.dayId, after)
            }
            val undo = CardioUndoJson.decode(session.progressionUndoJson) ?: CardioUndoDto()
            cardioSessionDao.update(session.copy(increaseApplied = judgment.outcome.change == CardioChange.Step,
                progressionUndoJson = CardioUndoJson.encode(undo.copy(rule = CardioRuleUndoDto(
                    undo.rule?.before ?: CardioRuleJson.encode(judgment.preWrite), after, cycle), result = runResult(judgment)))))
            return
        }
        if (!judgment.automatic || judgment.outcome.change !is CardioChange.Increase || judgment.outcome.block == judgment.stored) return
        val before = programDao.day(judgment.planId, judgment.dayId)?.cardioWorkingJson
        if (!setCardioWorking(judgment.planId, judgment.dayId, judgment.outcome.block)) return
        val after = programDao.day(judgment.planId, judgment.dayId)?.cardioWorkingJson
        val session = requireNotNull(cardioSessionDao.byId(judgment.sessionId))
        val undo = CardioUndoJson.decode(session.progressionUndoJson) ?: CardioUndoDto()
        cardioSessionDao.update(session.copy(increaseApplied = true, progressionUndoJson = CardioUndoJson.encode(
            undo.copy(block = CardioBlockUndoDto(if (undo.block != null) undo.block.before else before, after)),
        )))
    }

    private suspend fun appendSprintAttempts(judgment: CardioJudgment) {
        if (judgment.outcome.dose != SprintDose.TRIAL) return
        val plan = planDao.plan(judgment.planId) ?: return
        val goal = PlanGoalJson.decode(plan.goalJson) as? PlanGoal.Sprint ?: return
        val first = programDao.daysForPlan(plan.id).firstOrNull {
            sprintPlannedBlock(CardioRuleJson.decode(it.cardioRuleJson), CardioActivity.TRACK_RUN)
                ?.sets?.any { spec -> spec.role == CardioRole.WORK && spec.hard } == true
        } ?: return
        if (first.dayId != judgment.dayId) return
        val session = requireNotNull(cardioSessionDao.byId(judgment.sessionId))
        val results = cardioSetResultDao.forSession(session.id).mapNotNull { it.toDomainOrNull() }
        val attempts = SprintComparison.attempts(goal, results, session.completedAt)
        if (attempts.isEmpty()) return
        val before = requireNotNull(plan.goalJson)
        val after = PlanGoalJson.encode(goal.copy(attempts = goal.attempts + attempts))
        planDao.updateGoal(plan.id, after)
        val undo = CardioUndoJson.decode(session.progressionUndoJson) ?: CardioUndoDto()
        cardioSessionDao.update(session.copy(progressionUndoJson = CardioUndoJson.encode(
            undo.copy(goal = CardioGoalUndoDto(before, after)))))
    }

    private val CardioDayUndoDto.changed: Boolean
        get() = blockBefore != blockAfter || workingBefore != null || ruleBefore != ruleAfter

    private fun CardioDayUndoDto.matches(day: ProgramDayEntity?): Boolean = day != null &&
        day.cardioBlockJson == blockAfter && day.cardioWorkingJson == null && day.cardioRuleJson == ruleAfter

    private suspend fun applyRuledRun(judgment: CardioJudgment, beforeDays: List<ProgramDayEntity>) {
        val session = requireNotNull(cardioSessionDao.byId(judgment.sessionId))
        val undo = CardioUndoJson.decode(session.progressionUndoJson) ?: CardioUndoDto()
        val touched = undo.days.map { it.dayId }.toMutableSet()
        for (record in undo.days) {
            val before = beforeDays.first { it.dayId == record.dayId }
            programDao.setCardioState(judgment.planId, before.dayId, before.cardioBlockJson,
                before.cardioWorkingJson, before.cardioRuleJson)
        }
        val judgedDay = beforeDays.first { it.dayId == judgment.dayId }
        suspend fun write(day: ProgramDayEntity, block: CardioBlock, rule: CardioRule) {
            val blockJson = CardioBlockJson.encode(block)
            val ruleJson = CardioRuleJson.encode(rule)
            if (day.cardioBlockJson != blockJson || day.cardioRuleJson != ruleJson || day.cardioWorkingJson != null) {
                programDao.setCardioState(judgment.planId, day.dayId, blockJson, null, ruleJson)
                touched += day.dayId
            }
        }
        if (judgment.automatic || judgment.outcome.change == CardioChange.Repeat) {
            val rule = requireNotNull(judgment.outcome.rule)
            write(judgedDay, judgment.outcome.block, rule)
            if (judgment.preWrite?.ladderLevel != null && rule != judgment.preWrite) {
                for (day in beforeDays) {
                    if (day.dayId == judgment.dayId) continue
                    val domain = day.toDomain(emptyList())
                    if (domain.cardioRule?.ladderLevel == null) continue
                    val stored = domain.cardioToRun ?: continue
                    val block = if (day.cardioKeepToPlan) stored
                        else RunWalkLadder.block(rule.ladderLevel ?: RunWalkLadder.TOP, stored.activity)
                    write(day, block, rule)
                }
            }
        } else if (judgment.ladderDrop) {
            val cleared = requireNotNull(judgment.preWrite).copy(credits = 0)
            write(judgedDay, judgment.stored, cleared)
            for (day in beforeDays) {
                if (day.dayId == judgment.dayId) continue
                val domain = day.toDomain(emptyList())
                if (domain.cardioRule?.ladderLevel == null) continue
                write(day, domain.cardioToRun ?: continue, cleared)
            }
        }
        val records = beforeDays.filter { it.dayId in touched }.map { before ->
            val after = requireNotNull(programDao.day(judgment.planId, before.dayId))
            CardioDayUndoDto(before.dayId, before.cardioBlockJson, after.cardioBlockJson,
                before.cardioWorkingJson, before.cardioRuleJson, after.cardioRuleJson)
        }
        val afterRule = requireNotNull(programDao.day(judgment.planId, judgment.dayId)?.cardioRuleJson)
        cardioSessionDao.update(session.copy(progressionUndoJson = CardioUndoJson.encode(undo.copy(
            rule = CardioRuleUndoDto(undo.rule?.before ?: requireNotNull(judgedDay.cardioRuleJson), afterRule,
                requireNotNull(judgment.cycle)), days = records, result = runResult(judgment)))))
    }

    private fun runResult(judgment: CardioJudgment): CardioResultDto {
        val change = judgment.outcome.change
        val name = when (change) {
            CardioChange.Repeat -> "REPEAT"
            is CardioChange.Increase -> "INCREASE"
            is CardioChange.LevelTo -> "LEVEL_TO"
            CardioChange.LadderDone -> "LADDER_DONE"
            CardioChange.Step -> "STEP"
            is CardioChange.Alternate -> "ALTERNATE"
            CardioChange.Restart -> "RESTART"
        }
        val sprint = judgment.outcome.dose != null
        val before = judgment.preWrite
        val after = judgment.outcome.rule
        val credit = before != null && after != null && after.credits > before.credits
        val introLeft = if (sprint && before != null && after != null && after.intro < before.intro) after.intro else null
        return CardioResultDto(name, (change as? CardioChange.LevelTo)?.level,
            (change as? CardioChange.Alternate)?.to?.name, (change as? CardioChange.Increase)?.capped == true,
            judgment.preWrite?.ladderLevel, judgment.outcome.hold?.name,
            credit && (sprint || (change == CardioChange.Repeat && before?.ladderLevel != null)),
            !sprint && !judgment.automatic && change != CardioChange.Repeat, judgment.choice,
            CardioBlockJson.encode(judgment.outcome.block), judgment.outcome.dose?.name, introLeft)
    }

    /** Replays the newest ruled run Finish with an explicit chip choice, retaining its original Undo. */
    suspend fun setRunOfferApplied(cardioSessionId: Long, applied: Boolean): Boolean {
        val unit = settings.unitFlow.first()
        return db.withTransaction {
            val session = cardioSessionDao.byId(cardioSessionId) ?: return@withTransaction false
            if (CardioUndoJson.decode(session.progressionUndoJson)?.result == null) return@withTransaction false
            val judgment = cardioJudgment(cardioSessionId, first = false, unit = unit, choice = applied)
                ?.takeIf { it.runDays != null } ?: return@withTransaction false
            applyCardio(judgment)
            true
        }
    }

    suspend fun setCardioEffort(sessionId: Long, effort: CardioEffort?) {
        val unit = settings.unitFlow.first()
        db.withTransaction {
            val session = cardioSessionDao.byId(sessionId) ?: return@withTransaction
            cardioSessionDao.update(session.copy(effort = effort?.name))
            cardioJudgment(sessionId, first = false, unit = unit)?.let { applyCardio(it) }
        }
    }

    suspend fun clearFinishUndoForDay(planId: Long, dayId: String) = db.withTransaction {
        sessionDao.clearProgressionUndoForDay(planId, dayId)
        cardioSessionDao.clearProgressionUndoForDay(planId, dayId)
    }

    /** Amends a set's typed distance and judges the session again: a rule-less Automatic increase writes at most
     *  once per session, while the day still holds its opening. Ruled runs replay their original state;
     *  sprint replay uses the undo rule until a Step or invalidation.
     *  Working-value undo keeps the session's increaseApplied flag.
     *  A reading at the end is written with the total and judged with it.
     *  A Finish that re-planned stores a later effort or distance only; Undo first, then re-rate. */
    suspend fun amendCardioSetDistance(sessionId: Long, position: Int, metres: Int?, readingAtEnd: Int? = null) {
        val unit = settings.unitFlow.first()
        db.withTransaction {
            history.amendCardioSetDistance(sessionId, position, metres, readingAtEnd)
            cardioJudgment(sessionId, first = false, unit = unit)?.let { applyCardio(it) }
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
            OpenRun(run.id, run.planId, run.dayId, run.dayTitle, run.planName, run.startedAt, tracks.map { it.toRunTrack() }, run.draft)
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
        SetJson.decodeSets(plannedJson), SetJson.decodeSets(setsJson), dropped, note, OpeningJson.decode(openingJson),
    )

    suspend fun startRun(planId: Long, dayId: String, lighter: Boolean = false): RunStartResult {
        plans.ensurePlanSettingsMigrated()
        val profile = settings.lifterProfileFlow.first()
        val unit = settings.unitFlow.first()
        return db.withTransaction {
            val plan = planDao.plan(planId)
            val olderAdult = olderAdult(plan, profile)
            val catalog = this.catalog.snapshot()
            startRun(planId, dayId, unit, catalog, olderAdult, lighter)
        }
    }

    /** Runs inside a transaction and takes unit and catalog from the caller. */
    private suspend fun startRun(
        planId: Long, dayId: String, unit: WeightUnit, catalog: ExerciseCatalog, olderAdult: Boolean, lighter: Boolean = false,
    ): RunStartResult {
        val open = runDao.openRun()
        if (open != null) {
            if (open.planId != planId || open.dayId != dayId) {
                return RunStartResult.OtherRunOpen(open.id, open.planId, open.dayId)
            }
            if (!open.draft) return RunStartResult.AlreadyOpen(open.id)
            val day = programDao.day(planId, dayId) ?: return RunStartResult.NoStrengthSlots
            promote(open.id, day, unit, catalog)
            return RunStartResult.Started(open.id)
        }
        val day = programDao.day(planId, dayId) ?: return RunStartResult.NoStrengthSlots
        if (programDao.exercisesForDay(planId, dayId).isEmpty()) return RunStartResult.NoStrengthSlots
        val seeds = seedPlan(day, null, unit, catalog)
        insertStarted(day, seeds, initialRunTracks(day, seeds, unit, catalog, olderAdult, lighter))
        return RunStartResult.Started(day.nextRunId)
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
        val profile = settings.lifterProfileFlow.first()
        val unit = settings.unitFlow.first()
        return db.withTransaction {
            val plan = planDao.plan(planId)
            val olderAdult = olderAdult(plan, profile)
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
                swapInPlan(planId, dayId, programExerciseId, newExerciseId, unit, catalog)
                true
            } else {
                val targetRunId = runId ?: ownRun?.id ?: return@withTransaction ExerciseSwapResult.ALREADY_THERE
                swapInDraftOrRun(targetRunId, programExerciseId, Slot.MAIN, newExerciseId, unit, catalog, olderAdult)
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
        val profile = settings.lifterProfileFlow.first()
        val unit = settings.unitFlow.first()
        return db.withTransaction {
            val plan = planDao.plan(planId)
            val olderAdult = olderAdult(plan, profile)
            val catalog = this.catalog.snapshot()
            val day = programDao.day(planId, dayId) ?: return@withTransaction RunWriteResult.Rejected
            val row = programDao.exercise(programExerciseId)
                ?.takeIf { it.planId == planId && it.dayId == dayId }
                ?: return@withTransaction RunWriteResult.Rejected
            val open = runDao.openRun()
            if (open != null && (open.planId != planId || open.dayId != dayId)) return@withTransaction RunWriteResult.Rejected
            val runId = open?.id ?: day.nextRunId
            val tracks = if (open == null) {
                initialRunTracks(day, seedPlan(day, null, unit, catalog), unit, catalog, olderAdult)
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
                if (open == null) insertDraft(day, tracks)
                val capturedMain = runDao.tracks(runId).single { it.programExerciseId == row.id && it.slot == Slot.MAIN && !it.retired }
                runDao.updateTrack(capturedMain.copy(retired = true, dropped = true, updatedAt = clock.millis()))
                return@withTransaction RunWriteResult.InRun(runId, started = false)
            }
            if (keep == Slot.SS && swapSeed(row, Slot.MAIN, partner.exerciseId, tracks, unit, catalog) == null) {
                return@withTransaction RunWriteResult.Rejected
            }
            if (open == null) insertDraft(day, tracks)
            val capturedPartner = runDao.tracks(runId).first { it.programExerciseId == row.id && it.slot == Slot.SS && !it.retired }
            runDao.updateTrack(capturedPartner.copy(retired = true, dropped = true, updatedAt = clock.millis()))
            if (keep == Slot.SS) {
                val capturedMain = runDao.tracks(runId).single { it.programExerciseId == row.id && it.slot == Slot.MAIN && !it.retired }
                check(swapInRun(runId, row.id, Slot.MAIN, partner.exerciseId, unit, catalog,
                    expectedExerciseId = main.exerciseId))
                // The track key cannot hold a second MAIN with the same exercise.
                if (main.exerciseId != partner.exerciseId) {
                    runDao.updateTrack(capturedMain.copy(retired = true, dropped = true, updatedAt = clock.millis()))
                }
            }
            RunWriteResult.InRun(runId, started = false)
        }
    }

    suspend fun restoreSkippedLift(runId: String, programExerciseId: Long): RunWriteResult {
        plans.ensurePlanSettingsMigrated()
        val unit = settings.unitFlow.first()
        return db.withTransaction {
            val run = runDao.openRun()?.takeIf { it.id == runId } ?: return@withTransaction RunWriteResult.Rejected
            val row = programDao.exercise(programExerciseId)
                ?.takeIf { it.planId == run.planId && it.dayId == run.dayId }
                ?: return@withTransaction RunWriteResult.Rejected
            val day = programDao.day(row.planId, row.dayId) ?: return@withTransaction RunWriteResult.Rejected
            if (!runDao.tracks(runId).slotSkipped(row.id, Slot.MAIN)) return@withTransaction RunWriteResult.Rejected
            val catalog = this.catalog.snapshot()
            clearSlotSkipState(row.planId, row.dayId, row.id)
            seedRunTracks(day, runDao.tracks(runId), unit, catalog, restoringLift = row.id)
            RunWriteResult.InRun(runId, started = false)
        }
    }

    /** Restores a single captured half while its remaining half still matches. */
    suspend fun restoreSkipped(runId: String, programExerciseId: Long, slot: String): RunWriteResult {
        if (slot != Slot.MAIN && slot != Slot.SS) return RunWriteResult.Rejected
        plans.ensurePlanSettingsMigrated()
        val unit = settings.unitFlow.first()
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
            val aligned = alignRunTracks(row, restoredMain, partner, unit, catalog.snapshot())
                ?: return@withTransaction RunWriteResult.Rejected
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
        val profile = settings.lifterProfileFlow.first()
        val unit = settings.unitFlow.first()
        return db.withTransaction {
            val row = programDao.exercise(programExerciseId) ?: return@withTransaction false
            val plan = planDao.plan(row.planId)
            val olderAdult = olderAdult(plan, profile)
            val catalog = this.catalog.snapshot()
            swapInDraftOrRun(runId, programExerciseId, slot, newExerciseId, unit, catalog, olderAdult, expectedExerciseId)
        }
    }

    /** [swapInRun] on the open row, or on a new draft when [runId] is the day's next run and no row is open
     *  (owner decision 1). A refused swap leaves no draft behind. Runs inside a transaction. */
    private suspend fun swapInDraftOrRun(
        runId: String, programExerciseId: Long, slot: String, newExerciseId: String,
        unit: WeightUnit, catalog: ExerciseCatalog, olderAdult: Boolean, expectedExerciseId: String? = null,
    ): Boolean {
        val row = programDao.exercise(programExerciseId) ?: return false
        var inserted = false
        if (runDao.openRun() == null) {
            val day = programDao.day(row.planId, row.dayId)?.takeIf { it.nextRunId == runId } ?: return false
            if (swapSeed(row, slot, newExerciseId, emptyList(), unit, catalog) == null) return false
            insertDraft(day, initialRunTracks(day, seedPlan(day, null, unit, catalog), unit, catalog, olderAdult))
            inserted = true
        }
        val applied = swapInRun(runId, programExerciseId, slot, newExerciseId, unit, catalog, expectedExerciseId)
        if (!applied && inserted) {
            runDao.deleteTracks(runId)
            runDao.deleteRun(runId)
        }
        return applied
    }

    /** Runs inside a transaction and takes unit and catalog from the caller. */
    private suspend fun swapInRun(
        runId: String,
        programExerciseId: Long,
        slot: String,
        newExerciseId: String,
        unit: WeightUnit,
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
            var replacement = retired?.copy(retired = false, dropped = false, updatedAt = clock.millis()) ?: run {
                val base = swapSeed(row, slot, newExerciseId, tracks, unit, catalog) ?: return@withTransaction false
                runTrack(runId, row, slot, newExerciseId, WorkingRows.cleared(base))
            }
            val otherSlot = if (slot == Slot.MAIN) Slot.SS else Slot.MAIN
            val partner = tracks.firstOrNull {
                it.programExerciseId == programExerciseId && it.slot == otherSlot && !it.retired
            }
            var alignedPartner = partner
            if (partner != null) {
                val otherRows = SetJson.decodeSets(partner.setsJson)
                val aligned = alignRunTracks(row, replacement, partner, unit, catalog)
                    ?: return@withTransaction false
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

    private suspend fun alignRunTracks(
        row: ProgramExerciseEntity,
        first: RunTrackEntity,
        second: RunTrackEntity,
        unit: WeightUnit,
        catalog: ExerciseCatalog,
    ): Pair<List<LoggedSet>, List<LoggedSet>>? {
        val firstRows = SetJson.decodeSets(first.setsJson)
        val secondRows = SetJson.decodeSets(second.setsJson)
        val count = maxOf(firstRows.size, secondRows.size)
        suspend fun seed(track: RunTrackEntity, rows: List<LoggedSet>): List<LoggedSet> =
            if (rows.isNotEmpty() || count == 0) emptyList() else
                plans.runSwapRows(row, track.slot, track.exerciseId, count, unit, catalog).orEmpty()
        val firstSeed = seed(first, firstRows)
        val secondSeed = seed(second, secondRows)
        if (count > 0 && ((firstRows.isEmpty() && firstSeed.isEmpty()) ||
                (secondRows.isEmpty() && secondSeed.isEmpty()))) return null
        return TrackReseed.alignPaired(firstRows, secondRows, firstSeed, secondSeed)
    }

    /** Atomically edits the addressed run or draft, opening today's draft on the day's next run
     *  (owner decision 1). A done tick starts the workout unless `startOnTick` is false. */
    suspend fun applyToRun(
        runId: String,
        planId: Long,
        dayId: String,
        edits: List<TrackEdit>,
        startOnTick: Boolean = true,
    ): RunWriteResult {
        plans.ensurePlanSettingsMigrated()
        val profile = settings.lifterProfileFlow.first()
        val unit = settings.unitFlow.first()
        return db.withTransaction {
            if (runId.isBlank() || edits.isEmpty()) return@withTransaction RunWriteResult.Rejected
            val day = programDao.day(planId, dayId) ?: return@withTransaction RunWriteResult.Rejected
            val plan = planDao.plan(planId)
            val olderAdult = olderAdult(plan, profile)
            val catalog = this.catalog.snapshot()
            val open = runDao.openRun()
            if (open != null) {
                if (open.id != runId || open.planId != planId || open.dayId != dayId) return@withTransaction RunWriteResult.Rejected
                val tracks = runDao.tracks(runId)
                val resolved = resolveRunEdits(runId, day, tracks, edits,
                    seedPlan(day, tracks, unit, catalog), reopenRetired = true)
                if (resolved.failure != null) return@withTransaction resolved.failure
                if (open.draft && resolved.ticked && !startOnTick) return@withTransaction RunWriteResult.NeedsStart
                val starts = open.draft && resolved.ticked
                if (starts) promote(open.id, day, unit, catalog)
                writeRunTracks(resolved.tracks)
                return@withTransaction RunWriteResult.InRun(runId, starts)
            }
            if (day.nextRunId != runId) return@withTransaction RunWriteResult.Rejected
            val seeds = seedPlan(day, null, unit, catalog)
            val initial = initialRunTracks(day, seeds, unit, catalog, olderAdult)
            val resolved = resolveRunEdits(runId, day, initial, edits, seeds)
            if (resolved.failure != null) return@withTransaction resolved.failure
            if (resolved.tracks.all { edited -> initial.any { it.sameKey(edited) && it.setsJson == edited.setsJson } }) {
                return@withTransaction RunWriteResult.Unchanged
            }
            if (resolved.ticked && !startOnTick) return@withTransaction RunWriteResult.NeedsStart
            if (resolved.ticked) insertStarted(day, seeds, initial) else insertDraft(day, initial)
            val inserted = runDao.tracks(runId)
            writeRunTracks(resolved.tracks.map { edited ->
                // Phone edits can create tracks absent from the plan seed and initial insert.
                val existing = inserted.firstOrNull { it.sameKey(edited) }
                if (existing != null) edited.copy(id = existing.id) else edited.copy(id = runDao.insertTrack(edited),
                    setsJson = SetJson.encodeSets(SetJson.decodeSets(edited.setsJson).mapIndexed { i, set -> set.copy(plannedIndex = i) }))
            })
            RunWriteResult.InRun(runId, resolved.ticked)
        }
    }

    /**
     * Writes an accepted or restored working weight and any recorded target.
     * In a run, it becomes the opening weight and next time's working weight too.
     * Refused once a row is done.
     */
    suspend fun setWorkingWeight(
        runId: String,
        planId: Long,
        dayId: String,
        programExerciseId: Long,
        slot: String,
        weightLb: Double,
    ): RunWriteResult {
        val unit = settings.unitFlow.first()
        val catalog = this.catalog.snapshot()
        return db.withTransaction {
            val open = runDao.openRun()
            if (open?.id == runId) {
                if (open.planId != planId || open.dayId != dayId) return@withTransaction RunWriteResult.Rejected
                val track = runDao.tracks(runId).singleOrNull {
                    it.programExerciseId == programExerciseId && it.slot == slot && !it.retired
                } ?: return@withTransaction RunWriteResult.Rejected
                val rows = SetJson.decodeSets(track.setsJson)
                if (rows.any { it.done }) return@withTransaction RunWriteResult.Unchanged
                val equipment = catalog.find(track.exerciseId)?.equipment.orEmpty()
                val owner = programDao.exercise(programExerciseId)?.takeIf { it.exerciseIdFor(slot) == track.exerciseId }
                val acceptance = owner?.let { acceptedOfferRows(planId, dayId, it, slot, rows, weightLb, unit, catalog) }
                if (acceptance == OfferAcceptance.Stale) return@withTransaction RunWriteResult.Rejected
                val accepted = (acceptance as? OfferAcceptance.Applied)?.rows
                val next = (accepted ?: WorkingRows.withWorkingWeight(rows, weightLb, unit, equipment))
                    .mapIndexed { i, row -> row.copy(plannedIndex = i) }
                runDao.updateTrack(track.copy(
                    setsJson = SetJson.encodeSets(next),
                    plannedJson = SetJson.encodeSets(WorkingRows.cleared(next)),
                    openingJson = if (accepted != null) null else OpeningJson.decode(track.openingJson)
                        ?.takeIf { it.light }?.copy(layoff = OpeningDto.NONE, factor = null)?.let(OpeningJson::encode),
                    updatedAt = clock.millis(),
                ))
                if (owner != null) {
                    programDao.logForSlot(planId, dayId, programExerciseId, slot)?.let { stored ->
                        val storedRows = SetJson.decodeSets(stored.setsJson)
                        val writtenRows = accepted ?: WorkingRows.withWorkingWeight(storedRows, weightLb, unit, equipment)
                        programDao.upsertLog(logEntity(planId, dayId, programExerciseId, slot, writtenRows))
                        if (slot == Slot.MAIN) retie(owner, writtenRows, catalog)
                    }
                }
                return@withTransaction RunWriteResult.InRun(runId, false)
            }
            if (open == null && programDao.day(planId, dayId)?.nextRunId == runId) {
                val stored = programDao.logForSlot(planId, dayId, programExerciseId, slot)
                    ?: return@withTransaction RunWriteResult.Rejected
                val exercise = programDao.exercise(programExerciseId)?.exerciseIdFor(slot)
                val equipment = exercise?.let { catalog.find(it)?.equipment }.orEmpty()
                val rows = SetJson.decodeSets(stored.setsJson)
                val acceptance = programDao.exercise(programExerciseId)?.let { acceptedOfferRows(planId, dayId, it, slot, rows, weightLb, unit, catalog) }
                if (acceptance == OfferAcceptance.Stale) return@withTransaction RunWriteResult.Rejected
                val accepted = (acceptance as? OfferAcceptance.Applied)?.rows
                val writtenRows = accepted ?: WorkingRows.withWorkingWeight(rows, weightLb, unit, equipment)
                programDao.upsertLog(logEntity(planId, dayId, programExerciseId, slot, writtenRows))
                if (slot == Slot.MAIN) programDao.exercise(programExerciseId)?.let { retie(it, writtenRows, catalog) }
                return@withTransaction RunWriteResult.WorkingValues
            }
            RunWriteResult.Rejected
        }
    }

    private sealed interface OfferAcceptance {
        data object Stale : OfferAcceptance
        data class Applied(val rows: List<LoggedSet>) : OfferAcceptance
    }

    /** Reads only the newest archive; acceptance never rewrites its offer or an older session. */
    private suspend fun acceptedOfferRows(planId: Long, dayId: String, owner: ProgramExerciseEntity, name: String,
                                         rows: List<LoggedSet>, weightLb: Double, unit: WeightUnit, catalog: ExerciseCatalog): OfferAcceptance? {
        if (owner.keepToPlan) return null
        val exerciseId = owner.exerciseIdFor(name) ?: return null
        val session = sessionDao.lastSessionForDay(planId, dayId) ?: return null
        val offer = session.plannedJson?.let { SessionPlanJson.decode(it) }
            ?.lastOrNull { it.programExerciseId == owner.id && it.exerciseId == exerciseId && it.slot == name && !it.retired }?.offer ?: return null
        val before = offer.beforeSets?.map(SetDto::toDomain) ?: return null
        val after = offer.afterSets?.map(SetDto::toDomain) ?: return null
        val beforeRule = offer.beforeRuleJson?.let(SlotRuleJson::decode) ?: return null
        val afterRule = offer.afterRuleJson?.let(SlotRuleJson::decode) ?: return null
        val current = SlotRuleJson.decode(owner.ruleJsonFor(name)) ?: return null
        val apply = when {
            offer.fromLb == offer.toLb && weightLb == offer.toLb ->
                sameValues(rows, before) && current.target == beforeRule.target && current.state.calibrating == beforeRule.state.calibrating
            weightLb == offer.toLb -> true
            weightLb == offer.fromLb -> false
            else -> return null
        }
        val source = if (apply) before else after
        val target = if (apply) after else before
        val sourceRule = if (apply) beforeRule else afterRule
        val targetRule = if (apply) afterRule else beforeRule
        if (!sameValues(rows, source) || current.target != sourceRule.target) {
            return if (beforeRule.target != afterRule.target || beforeRule.state.calibrating != afterRule.state.calibrating) {
                OfferAcceptance.Stale
            } else null
        }
        setRuleFor(owner.id, name, SlotRuleJson.encode(current.copy(target = targetRule.target,
            state = current.state.copy(calibrating = targetRule.state.calibrating))))
        if (name == Slot.MAIN) {
            val siblings = shareAcceptance(owner, source, sourceRule, target, targetRule)
            val planSlots = programDao.exercisesForPlan(planId)
            val steps = plans.planSettings(planId).loadSteps ?: LoadSteps.defaults(unit)
            val queue = ArrayDeque(listOf(owner to target) + siblings.map { it to target })
            val written = queue.mapTo(mutableSetOf<Long>()) { it.first.id }
            while (queue.isNotEmpty()) {
                val (leader, leaderRows) = queue.removeFirst()
                val load = WorkingRows.workingWeight(leaderRows)?.takeIf { it > 0.0 } ?: continue
                for (write in followerWrites(planSlots, leader, load, written, unit, catalog, steps)) {
                    programDao.upsertLog(logEntity(write.slot.planId, write.slot.dayId, write.slot.id, Slot.MAIN, write.rows))
                    setRuleFor(write.slot.id, Slot.MAIN, write.encodedRule)
                    written.add(write.slot.id)
                    queue.addLast(write.slot to write.rows)
                }
            }
        }
        return OfferAcceptance.Applied(target)
    }

    private fun sameValues(a: List<LoggedSet>, b: List<LoggedSet>): Boolean =
        a.size == b.size && a.zip(b).all { (x, y) ->
            x.kind == y.kind && x.weightLb == y.weightLb && x.reps == y.reps && x.seconds == y.seconds
        }

    /** Only siblings still holding the offer's source rows and target follow acceptance, in the caller's transaction. */
    private suspend fun shareAcceptance(owner: ProgramExerciseEntity, source: List<LoggedSet>, sourceRule: SlotRule,
                                        target: List<LoggedSet>, targetRule: SlotRule): List<ProgramExerciseEntity> {
        val siblings = mutableListOf<ProgramExerciseEntity>()
        val planAutomatic = planDao.plan(owner.planId)?.autoApply == true
        val performed = owner.toDomain()
        for (other in programDao.exercisesForPlan(owner.planId)) {
            if (other.dayId == owner.dayId) continue
            val sibling = other.toDomain()
            val rule = sibling.rule ?: continue
            if (!SharedProgression.shares(performed, sibling, planAutomatic) || rule.target != sourceRule.target) continue
            val stored = programDao.logForSlot(other.planId, other.dayId, other.id, Slot.MAIN) ?: continue
            val rows = runCatching { SetJson.decodeSets(stored.setsJson) }.getOrNull() ?: continue
            if (!sameValues(rows, source)) continue
            val encoded = try {
                SlotRuleJson.encode(rule.copy(target = targetRule.target,
                    state = rule.state.copy(calibrating = targetRule.state.calibrating)))
            } catch (_: RuntimeException) { continue }
            programDao.upsertLog(logEntity(other.planId, other.dayId, other.id, Slot.MAIN, target))
            setRuleFor(other.id, Slot.MAIN, encoded)
            siblings.add(other)
        }
        return siblings
    }

    /** The bounded path for a delta that carries no run: an old watch. */
    suspend fun applyToOpenRun(
        planId: Long, dayId: String, editedAtMillis: Long, edits: List<TrackEdit>,
    ): RunWriteResult = db.withTransaction {
        val open = runDao.openRun()?.takeUnless { it.draft } ?: return@withTransaction RunWriteResult.Rejected
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
            val run = runDao.openRun()?.takeUnless { it.draft } ?: return@withTransaction
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
        val ticked: Boolean = false,
    )

    private suspend fun resolveRunEdits(
        runId: String,
        day: ProgramDayEntity,
        tracks: List<RunTrackEntity>,
        edits: List<TrackEdit>,
        seeds: Map<Pair<Long, String>, SlotSeeding.Seeded>,
        reopenRetired: Boolean = false,
    ): ResolvedRunEdits {
        val resolved = tracks.toMutableList()
        val changed = linkedSetOf<Int>()
        var ticked = false
        for (edit in edits) {
            if (tracks.slotSkipped(edit.programExerciseId, edit.slot)) {
                return ResolvedRunEdits(failure = RunWriteResult.Rejected)
            }
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
                if (slot != null && exerciseId != null && (edit.exerciseId == null || edit.exerciseId == exerciseId)) {
                    val retiredIndex = resolved.indexOfFirst {
                        it.programExerciseId == edit.programExerciseId && it.slot == edit.slot &&
                            it.exerciseId == exerciseId && it.retired
                    }
                    if (retiredIndex >= 0) {
                        resolved[retiredIndex] = reopenTrack(resolved[retiredIndex], workingBase(day, slot, edit.slot, seeds))
                        index = retiredIndex
                    }
                }
            }
            if (index < 0) {
                val slot = programDao.exercise(edit.programExerciseId)
                    ?.takeIf { it.planId == day.planId && it.dayId == day.dayId }
                    ?: return ResolvedRunEdits(failure = RunWriteResult.Rejected)
                // Unknown exercises have no plan seed; a phone edit can supply their first rows.
                val base = workingBase(day, slot, edit.slot, seeds) ?: if (edit.createsMissing) {
                    edit.transform(emptyList())?.takeIf { it.isNotEmpty() }?.also { created = it }?.let(WorkingRows::cleared)
                        ?: return ResolvedRunEdits(failure = RunWriteResult.Unchanged)
                } else return ResolvedRunEdits(failure = RunWriteResult.Rejected)
                val exerciseId = slot.exerciseIdFor(edit.slot)
                    ?: return ResolvedRunEdits(failure = RunWriteResult.Rejected)
                if (edit.exerciseId != null && edit.exerciseId != exerciseId) {
                    return ResolvedRunEdits(failure = RunWriteResult.Rejected)
                }
                val candidate = runTrack(runId, slot, edit.slot, exerciseId, WorkingRows.cleared(base))
                if (resolved.any { it.sameKey(candidate) }) return ResolvedRunEdits(failure = RunWriteResult.Rejected)
                resolved.add(candidate)
                index = resolved.lastIndex
            }
            val track = resolved[index]
            val current = SetJson.decodeSets(track.setsJson)
            val result = created ?: edit.transform(current)
                ?: return ResolvedRunEdits(failure = RunWriteResult.Unchanged)
            if (result.count { it.done } > current.count { it.done }) ticked = true
            resolved[index] = track.copy(setsJson = SetJson.encodeSets(result), updatedAt = clock.millis())
            changed.add(index)
        }
        return ResolvedRunEdits(changed.map { resolved[it] }, ticked = ticked)
    }

    private fun RunTrackEntity.sameKey(other: RunTrackEntity): Boolean =
        runId == other.runId && programExerciseId == other.programExerciseId && slot == other.slot && exerciseId == other.exerciseId

    private fun RunTrackEntity.canReplan(isTimed: Boolean): Boolean {
        val captured = SetJson.decodeSets(plannedJson).firstOrNull() ?: return false
        return (captured.seconds > 0) == isTimed
    }

    private fun reopenTrack(
        track: RunTrackEntity,
        base: List<LoggedSet>?,
    ): RunTrackEntity = track.copy(
        retired = false,
        setsJson = (if (track.openingJson != null) SetJson.decodeSets(track.plannedJson)
            else base)
            ?.takeIf { track.canReplan((it.firstOrNull()?.seconds ?: 0) > 0) }?.let {
                SetJson.encodeSets(TrackReseed.represcribe(SetJson.decodeSets(track.setsJson), it))
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

    private fun sourceSlot(planSlots: List<ProgramExerciseEntity>, dayId: String, exerciseId: String,
                           tracking: TrackingType): ProgramExerciseEntity? =
        planSlots.filter { it.dayId == dayId && it.exerciseId == exerciseId }.sortedBy { it.position }
            .firstOrNull { LoadFollowing.linkable(it.toDomain(), tracking) }

    private suspend fun currentLoad(slot: ProgramExerciseEntity): Double? =
        programDao.logForSlot(slot.planId, slot.dayId, slot.id, Slot.MAIN)
            ?.let { runCatching { SetJson.decodeSets(it.setsJson) }.getOrNull() }?.let(WorkingRows::workingWeight)?.takeIf { it > 0.0 }
            ?: (PrescriptionJson.decode(slot.prescriptionJson)?.target as? PrescribedTarget.LoadReps)?.lb?.takeIf { it > 0.0 }

    private fun trackingOf(catalog: ExerciseCatalog, exerciseId: String): TrackingType =
        catalog.find(exerciseId)?.tracking ?: TrackingType.WEIGHTED

    private class FollowerWrite(val slot: ProgramExerciseEntity, val rows: List<LoggedSet>, val encodedRule: String)

    private suspend fun followerWrites(planSlots: List<ProgramExerciseEntity>, source: ProgramExerciseEntity, loadLb: Double,
                                       skip: Set<Long>, unit: WeightUnit, catalog: ExerciseCatalog, steps: LoadSteps): List<FollowerWrite> {
        val tracking = trackingOf(catalog, source.exerciseId)
        if (sourceSlot(planSlots, source.dayId, source.exerciseId, tracking)?.id != source.id) return emptyList()
        val program = assemble(programDao.daysForPlan(source.planId), planSlots)
        val equipment = catalog.find(source.exerciseId)?.equipment.orEmpty()
        return planSlots.filter { it.id !in skip && it.dayId != source.dayId && it.exerciseId == source.exerciseId }.mapNotNull { other ->
            val follower = other.toDomain()
            val rule = follower.rule ?: return@mapNotNull null
            val link = rule.loadSource?.takeIf { it.dayId == source.dayId } ?: return@mapNotNull null
            if (LoadFollowing.followedDay(program, other.dayId, follower, tracking) == null || LoadFollowing.held(rule)) return@mapNotNull null
            val stored = programDao.logForSlot(other.planId, other.dayId, other.id, Slot.MAIN) ?: return@mapNotNull null
            val storedRows = runCatching { SetJson.decodeSets(stored.setsJson) }.getOrNull() ?: return@mapNotNull null
            val derived = steps.nearest(link.ratio * loadLb, equipment, unit)
            if (derived == WorkingRows.workingWeight(storedRows)) return@mapNotNull null
            val (rows, moved) = LoadFollowing.atLoad(WorkingRows.cleared(storedRows), rule, derived, unit, equipment)
            val encoded = try { SlotRuleJson.encode(moved.copy(state = moved.state.copy(calibrating = false))) }
                catch (_: RuntimeException) { return@mapNotNull null }
            FollowerWrite(other, rows, encoded)
        }
    }

    private suspend fun retie(slot: ProgramExerciseEntity, rows: List<LoggedSet>, catalog: ExerciseCatalog) {
        val rule = SlotRuleJson.decode(programDao.exercise(slot.id)?.ruleJson) ?: return
        val link = rule.loadSource ?: return
        val planSlots = programDao.exercisesForPlan(slot.planId)
        val tracking = trackingOf(catalog, slot.exerciseId)
        val program = assemble(programDao.daysForPlan(slot.planId), planSlots)
        if (LoadFollowing.followedDay(program, slot.dayId, slot.toDomain(), tracking) == null) return
        val source = sourceSlot(planSlots, link.dayId, slot.exerciseId, tracking) ?: return
        val own = WorkingRows.workingWeight(rows)?.takeIf { it > 0.0 } ?: return
        val sourceLoad = currentLoad(source) ?: return
        val ratio = own / sourceLoad
        if (ratio != link.ratio) setRuleFor(slot.id, Slot.MAIN, SlotRuleJson.encode(rule.copy(loadSource = link.copy(ratio = ratio))))
    }

    private fun ProgramExerciseEntity.ruleJsonFor(slot: String): String? = when (slot) {
        Slot.MAIN -> ruleJson
        Slot.SS -> partnerRuleJson
        else -> null
    }

    suspend fun editRule(programExerciseId: Long, slot: String, edit: RuleEdit) = db.withTransaction {
        require(slot == Slot.MAIN || slot == Slot.SS)
        if (edit is RuleEdit.LoadSource) require(slot == Slot.MAIN)
        val row = programDao.exercise(programExerciseId) ?: return@withTransaction
        val rule = SlotRuleJson.decode(row.ruleJsonFor(slot)) ?: return@withTransaction
        val updated = when (edit) {
            is RuleEdit.NextTime -> {
                val default = if (planDao.plan(row.planId)?.autoApply == true) NextTimeMode.AUTO else NextTimeMode.ASK
                rule.copy(nextTime = edit.mode?.takeUnless { it == default })
            }
            is RuleEdit.LoadSource -> rule.copy(loadSource = edit.dayId?.let { dayId ->
                val catalog = this.catalog.snapshot()
                val tracking = trackingOf(catalog, row.exerciseId)
                val planSlots = programDao.exercisesForPlan(row.planId)
                val program = assemble(programDao.daysForPlan(row.planId), planSlots)
                if (dayId !in LoadFollowing.eligibleSources(program, row.dayId, row.toDomain(), tracking)) return@withTransaction
                val source = sourceSlot(planSlots, dayId, row.exerciseId, tracking) ?: return@withTransaction
                val sourceRule = SlotRuleJson.decode(source.ruleJson) ?: return@withTransaction
                val own = currentLoad(row)
                val theirs = currentLoad(source)
                val ratio = if (own != null && theirs != null) own / theirs
                    else Estimate.loadRatio(rule.target.reps, rule.rir, sourceRule.target.reps, sourceRule.rir) ?: return@withTransaction
                LoadSource(dayId, ratio)
            })
            is RuleEdit.Rest -> rule.copy(restSeconds = edit.seconds)
            is RuleEdit.RepsLeft -> rule.copy(rir = edit.value)
            is RuleEdit.Increase -> rule.copy(incrementPct = edit.fraction)
        }
        setRuleFor(row.id, slot, SlotRuleJson.encode(updated))
    }

    private fun SlotRule.preservingSettings(old: SlotRule?): SlotRule = if (old == null) this else copy(
        nextTime = old.nextTime, restSeconds = old.restSeconds, rir = old.rir, incrementPct = old.incrementPct, loadSource = old.loadSource,
    )

    private suspend fun setRuleFor(programExerciseId: Long, slot: String, json: String) {
        when (slot) {
            Slot.MAIN -> programDao.setRule(programExerciseId, json)
            Slot.SS -> programDao.setPartnerRule(programExerciseId, json)
        }
    }

    private suspend fun workingBase(
        day: ProgramDayEntity,
        slot: ProgramExerciseEntity,
        slotName: String,
        seeds: Map<Pair<Long, String>, SlotSeeding.Seeded>,
    ): List<LoggedSet>? {
        val stored = programDao.logForSlot(day.planId, day.dayId, slot.id, slotName)
        val sets = stored?.let { SetJson.decodeSets(it.setsJson) }
            ?: seeds[slot.id to slotName]?.rows
        return sets?.map {
            it.copy(done = false, startedAtMillis = null, completedAtMillis = null, plannedIndex = null, sides = null)
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

    /** The tracks a run would open with now: each track's stored rows, else its seed, opened through [opened].
     *  Reads only Room; writes nothing. */
    private suspend fun initialRunTracks(day: ProgramDayEntity, seeds: Map<Pair<Long, String>, SlotSeeding.Seeded>,
                                         unit: WeightUnit, catalog: ExerciseCatalog, olderAdult: Boolean, lighter: Boolean = false): List<RunTrackEntity> {
        val tracks = mutableListOf<RunTrackEntity>()
        for (slot in programDao.exercisesForDay(day.planId, day.dayId)) {
            for (slotName in listOf(Slot.MAIN, Slot.SS)) {
                val exerciseId = slot.exerciseIdFor(slotName) ?: continue
                val base = workingBase(day, slot, slotName, seeds) ?: continue
                tracks.add(runTrack(day.nextRunId, slot, slotName, exerciseId, base))
            }
        }
        return opened(day, tracks, unit, catalog, olderAdult, lighter)
    }

    /** [tracks] with each eligible MAIN and SS track opened through [Progression.atStart] (spec W2b-2a decision 4
     *  as amended by W2b-3c decision 3: any start, stored or seeded; W2b-2b decision 8). Reads only Room. */
    private suspend fun opened(day: ProgramDayEntity, tracks: List<RunTrackEntity>, unit: WeightUnit,
                               catalog: ExerciseCatalog, olderAdult: Boolean, lighter: Boolean = false): List<RunTrackEntity> {
        val plan = planDao.plan(day.planId) ?: return tracks
        val program = assemble(programDao.daysForPlan(day.planId), programDao.exercisesForPlan(day.planId))
        val training = program.rotationDays.filter { it.kind == ProgramDayKind.STRENGTH }
        val steps = plans.planSettings(day.planId).loadSteps ?: LoadSteps.defaults(unit)
        val today = localEpochDay(clock.millis())
        val first = sessionDao.firstSessionAt()?.let(::localEpochDay)
        val newLifter = plan.recipeJson?.let(SetupRecipeJson::experience) == LiftExperience.NEW &&
            (first == null || today - first < Progression.NEW_LIFTER_LOG_DAYS)
        val light = plan.lightCycleRemaining > 0 || lighter
        fun startOfDay(epochDay: Long) = LocalDate.ofEpochDay(epochDay).atStartOfDay(clock.zone).toInstant().toEpochMilli()
        val result = tracks.map { track ->
            if (track.slot != Slot.MAIN && track.slot != Slot.SS) return@map track
            val row = track.programExerciseId?.let { programDao.exercise(it) } ?: return@map track
            if (row.keepToPlan || row.exerciseIdFor(track.slot) != track.exerciseId) return@map track
            val rule = SlotRuleJson.decode(row.ruleJsonFor(track.slot)) ?: return@map track
            val last = sessionDao.lastTrainedAt(day.planId, track.exerciseId)?.let(::localEpochDay)
            val daysPerWeek = if (last == null) 1 else maxOf(1,
                (sessionDao.sessionCount(day.planId, startOfDay(last - 27), startOfDay(last + 1)) + 2) / 4)
            val appearances = maxOf(1, training.count { d -> d.exercises.any {
                it.exerciseId == track.exerciseId || it.superset?.exerciseId == track.exerciseId
            } })
            val expected = Progression.expectedRecurrenceDays(maxOf(1, training.size), daysPerWeek, appearances)
            val ctx = Progression.Context(unit, catalog.find(track.exerciseId)?.equipment.orEmpty().toSet(), steps, today, light = light)
            val opening = try {
                Progression.atStart(rule, WorkingRows.cleared(SetJson.decodeSets(track.plannedJson)), last, expected, ctx, olderAdult = olderAdult, newLifter = newLifter)
            } catch (_: RuntimeException) {
                // A malformed row must never block Start; the track opens as stored.
                null
            } ?: return@map track
            val dto = OpeningDto.of(opening.layoff, light) ?: return@map track
            track.copy(plannedJson = SetJson.encodeSets(opening.rows),
                setsJson = SetJson.encodeSets(SetEditor.keepingIndexStamps(SetJson.decodeSets(track.setsJson),
                    opening.rows.mapIndexed { i, s -> s.copy(plannedIndex = i) })),
                openingJson = OpeningJson.encode(dto))
        }
        val openedSlots = result.filter { it.openingJson != null }.mapNotNull { it.programExerciseId }.toSet()
        return result.map { track ->
            if (track.slot != Slot.SS || track.programExerciseId !in openedSlots) return@map track
            val main = result.firstOrNull { it.programExerciseId == track.programExerciseId && it.slot == Slot.MAIN }
                ?: return@map track
            val count = SetJson.decodeSets(main.plannedJson).size
            val rows = SetJson.decodeSets(track.plannedJson)
            if (count == 0 || rows.isEmpty() || rows.size == count) return@map track
            val fit = fitted(rows, count)
            val before = tracks.first { it.sameKey(track) }
            track.copy(plannedJson = SetJson.encodeSets(fit),
                setsJson = SetJson.encodeSets(SetEditor.keepingIndexStamps(SetJson.decodeSets(before.setsJson),
                    fitted(SetJson.decodeSets(track.setsJson), count).mapIndexed { i, s -> s.copy(plannedIndex = i) })),
                openingJson = track.openingJson ?: OpeningJson.encode(OpeningDto(OpeningDto.HOLD)))
        }
    }

    /** [rows] trimmed from the end, or padded with copies of its last row, to [count]; empty rows stay empty. */
    private fun fitted(rows: List<LoggedSet>, count: Int): List<LoggedSet> =
        if (rows.isEmpty()) rows else rows.take(count) + List(maxOf(0, count - rows.size)) { rows.last() }

    private suspend fun insertDraft(day: ProgramDayEntity, tracks: List<RunTrackEntity>) {
        runDao.insertRun(WorkoutRunEntity(
            day.nextRunId, day.planId, day.dayId, day.title, planDao.plan(day.planId)?.name, clock.millis(), draft = true,
        ))
        runDao.insertTracks(tracks)
    }

    private suspend fun insertStarted(day: ProgramDayEntity, seeds: Map<Pair<Long, String>, SlotSeeding.Seeded>, tracks: List<RunTrackEntity>) {
        beginWorkout(day, seeds)
        runDao.insertRun(WorkoutRunEntity(
            day.nextRunId, day.planId, day.dayId, day.title, planDao.plan(day.planId)?.name, clock.millis(), draft = false,
        ))
        runDao.insertTracks(tracks)
    }

    private suspend fun promote(runId: String, day: ProgramDayEntity, unit: WeightUnit, catalog: ExerciseCatalog) {
        beginWorkout(day, seedPlan(day, null, unit, catalog))
        runDao.promote(runId, clock.millis())
    }

    /** What Start writes besides the run: the day's Undo is cleared first, so keepSeeds' undo guard
     *  never skips a seed's calibrating flag. */
    private suspend fun beginWorkout(day: ProgramDayEntity, seeds: Map<Pair<Long, String>, SlotSeeding.Seeded>) {
        sessionDao.clearProgressionUndoForDay(day.planId, day.dayId)
        cardioSessionDao.clearProgressionUndoForDay(day.planId, day.dayId)
        keepSeeds(day, seeds)
    }

    /** Runs inside a transaction and takes unit and catalog from the caller. */
    private suspend fun seedPlan(
        day: ProgramDayEntity,
        runTracks: List<RunTrackEntity>?,
        unit: WeightUnit,
        catalog: ExerciseCatalog,
    ): Map<Pair<Long, String>, SlotSeeding.Seeded> {
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
        return plans.seeds(day.planId, slots, existing, unit, catalog)
    }

    /** The slot's live MAIN row count: its open-run track's, else its stored MAIN row's; null with neither. */
    private suspend fun liveMainRows(row: ProgramExerciseEntity): Int? {
        val run = runDao.openRun()?.takeIf { it.planId == row.planId && it.dayId == row.dayId }
        return run?.let { runDao.tracks(it.id) }?.firstOrNull {
            it.programExerciseId == row.id && it.slot == Slot.MAIN && !it.retired
        }?.let { SetJson.decodeSets(it.setsJson).size }
            ?: programDao.logForSlot(row.planId, row.dayId, row.id, Slot.MAIN)?.let {
                runCatching { SetJson.decodeSets(it.setsJson).size }.getOrNull()
            }
    }

    /** Runs inside a transaction; reads only. */
    private suspend fun swapSeed(
        row: ProgramExerciseEntity, slot: String, newExerciseId: String, tracks: List<RunTrackEntity>,
        unit: WeightUnit, catalog: ExerciseCatalog,
    ): List<LoggedSet>? {
        val mainRows = tracks.firstOrNull { it.programExerciseId == row.id && it.slot == Slot.MAIN && !it.retired }
            ?.let { SetJson.decodeSets(it.setsJson).size } ?: liveMainRows(row) ?: row.targetSets
        return plans.runSwapRows(row, slot, newExerciseId, mainRows, unit, catalog)
    }

    /** Runs inside a transaction and takes unit and catalog from the caller. */
    private suspend fun seedRunTracks(
        day: ProgramDayEntity,
        tracks: List<RunTrackEntity>,
        unit: WeightUnit,
        catalog: ExerciseCatalog,
        restoringLift: Long? = null,
    ) {
        val runId = day.nextRunId
        val seeds = seedPlan(day, tracks, unit, catalog)
            .filterKeys { restoringLift == null || it.first == restoringLift }
        if (restoringLift == null) keepSeeds(day, seeds)
        val existing = tracks.toMutableList()
        val seededSlots = mutableSetOf<Long>()
        for ((key, sets) in seeds) {
            val slot = programDao.exercise(key.first) ?: continue
            val exerciseId = slot.exerciseIdFor(key.second) ?: continue
            val base = workingBase(day, slot, key.second, seeds) ?: sets.rows
            val track = runTrack(runId, slot, key.second, exerciseId, WorkingRows.cleared(base))
            val retiredIndex = existing.indexOfFirst { it.retired && it.sameKey(track) }
            if (retiredIndex >= 0) {
                val reopened = reopenTrack(existing[retiredIndex], workingBase(day, slot, key.second, seeds))
                runDao.updateTrack(reopened)
                existing[retiredIndex] = reopened
            } else {
                existing.add(track.copy(id = runDao.insertTrack(track)))
            }
            seededSlots.add(key.first)
        }
        for (id in seededSlots) {
            val main = existing.singleOrNull { it.programExerciseId == id && it.slot == Slot.MAIN && !it.retired } ?: continue
            val partner = existing.singleOrNull { it.programExerciseId == id && it.slot == Slot.SS && !it.retired } ?: continue
            val mainRows = SetJson.decodeSets(main.setsJson)
            val partnerRows = SetJson.decodeSets(partner.setsJson)
            if (mainRows.size == partnerRows.size) continue
            val row = programDao.exercise(id) ?: continue
            val aligned = alignRunTracks(row, main, partner, unit, catalog) ?: continue
            writeRunTracks(listOf(
                main.copy(setsJson = SetJson.encodeSets(aligned.first), updatedAt = clock.millis()),
                partner.copy(setsJson = SetJson.encodeSets(aligned.second), updatedAt = clock.millis()),
            ))
        }
    }

    /** Archives a run once, carries forward its working values, and advances its day. */
    suspend fun finishRun(runId: String): Long? = finishRunCapturing(runId)?.sessionId

    /** A finished run's session, captured tracks, and slot prescriptions, including on retry. */
    data class FinishedRun(val sessionId: Long, val tracks: List<RunTrack>, val slots: List<ProgramSlot>? = null)

    /** [finishRun], also returning the exact tracks the transaction archived, so a caller
     *  describing the session cannot disagree with it after a concurrent edit (the watch's
     *  applier does not share the phone's lock). */
    suspend fun finishRunCapturing(runId: String): FinishedRun? {
        plans.ensurePlanSettingsMigrated()
        val bodyweight = settings.lifterProfileFlow.first().bodyweightLb
        val unit = settings.unitFlow.first()
        val catalog = this.catalog.snapshot()
        return db.withTransaction {
            sessionDao.sessionByRunId(runId)?.let { session ->
                val slots = session.planId?.let { programDao.exercisesForDay(it, session.dayId) }.orEmpty()
                    .map { ProgramSlot(it.id, it.position, it.toDomain()) }
                val archived = ArchivedRun.of(session, sessionDao.setsForSession(session.id), slots)
                return@withTransaction FinishedRun(session.id, archived.tracks, archived.slots)
            }
            val run = runDao.openRun()?.takeIf { it.id == runId && !it.draft } ?: return@withTransaction null
            val tracks = runDao.tracks(runId)
            val now = clock.millis()
            val day = run.planId?.let { planId -> run.dayId?.let { programDao.day(planId, it) } }
            val slots = day?.let { programDao.exercisesForDay(it.planId, it.dayId) }
                .orEmpty().map { ProgramSlot(it.id, it.position, it.toDomain()) }
            val progressed = if (day != null && day.nextRunId == run.id) {
                progress(day, tracks, unit, catalog, now).also {
                    sessionDao.clearProgressionUndoBefore(day.planId, run.startedAt)
                    cardioSessionDao.clearProgressionUndoBefore(day.planId, run.startedAt)
                }
            } else null
            val sessionId = archiveRun(run, tracks, bodyweight, catalog, now,
                progressed?.offers.orEmpty(), progressed?.undoJson, progressed?.reasons.orEmpty())
            if (day != null && day.nextRunId == run.id) {
                if (progressed?.replanned != true) planDao.plan(day.planId)?.let { advanceRotation(it, day.dayId) }
                programDao.closeDay(day.planId, day.dayId, mintRunId(), now)
            }
            runDao.deleteTracks(runId)
            runDao.deleteRun(runId)
            FinishedRun(sessionId, tracks.map { it.toRunTrack() }, slots)
        }
    }

    private suspend fun archiveRun(
        run: WorkoutRunEntity,
        tracks: List<RunTrackEntity>,
        bodyweight: Double?,
        catalog: ExerciseCatalog,
        now: Long,
        offers: Map<Long, OfferDto> = emptyMap(),
        undoJson: String? = null,
        reasons: Map<Long, String> = emptyMap(),
    ): Long {
        val sessionId = sessionDao.insertSession(WorkoutSessionEntity(
            id = 0, dayId = run.dayId ?: "", dayTitle = run.dayTitle, startedAt = run.startedAt,
            completedAt = now, bodyweightLb = bodyweight, planId = run.planId,
            planName = run.planId?.let { planDao.plan(it)?.name } ?: run.planName, runId = run.id,
            progressionUndoJson = undoJson,
            plannedJson = SessionPlanJson.encode(tracks.map { track ->
                PlannedTrackDto(
                    track.position, track.exerciseId, track.slot, track.retired,
                    SetJson.decodeSets(track.plannedJson).map(SetDto::of),
                    liveSetCount = track.toRunTrack().setCounts().total,
                    note = snapshotNote(track, run.startedAt),
                    offer = offers[track.id],
                    programExerciseId = track.programExerciseId,
                    opening = OpeningJson.decode(track.openingJson),
                    reason = reasons[track.id], dropped = track.dropped,
                )
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
                    rir = set.rir,
                ).withSides(set.sides)
            }
        })
        return sessionId
    }

    private suspend fun snapshotNote(track: RunTrackEntity, startedAt: Long): String? {
        if (track.note.isNotBlank()) return track.note
        val slot = track.programExerciseId?.let { programDao.exercise(it) } ?: return null
        val updatedAt = slot.noteUpdatedAt ?: return null
        return slot.note.takeIf { it.isNotBlank() && updatedAt >= startedAt }
    }

    private class Progressed(val offers: Map<Long, OfferDto>, val undoJson: String?, val reasons: Map<Long, String>,
                             val replanned: Boolean)

    private class Prepared(val outcome: Outcome, val rule: SlotRule, val encodedRule: String, val offer: OfferDto?, val applied: Boolean)

    /** Runs inside Finish's transaction. All inputs come from Room or the caller's frozen settings. */
    private suspend fun progress(day: ProgramDayEntity, tracks: List<RunTrackEntity>, unit: WeightUnit,
                                 catalog: ExerciseCatalog, now: Long): Progressed {
        val logs = mutableListOf<UndoLogDto>()
        val rules = mutableListOf<UndoRuleDto>()
        val offers = mutableMapOf<Long, OfferDto>()
        val reasons = mutableMapOf<Long, String>()
        suspend fun writeLog(slot: ProgramExerciseEntity, name: String, rows: List<LoggedSet>?, from: Long? = null, followed: Boolean = false) {
            val before = programDao.logForSlot(day.planId, slot.dayId, slot.id, name)
            if (rows == null) {
                if (before == null) return
                programDao.deleteLogForSlot(day.planId, slot.dayId, slot.id, name)
            } else programDao.upsertLog(logEntity(day.planId, slot.dayId, slot.id, name, rows))
            val index = logs.indexOfFirst { it.programExerciseId == slot.id && it.slot == name }
            val prior = if (index >= 0) logs[index].before else before?.let { UndoRowDto(it.setsJson, it.updatedAt) }
            val record = UndoLogDto(slot.id, name, prior, rows?.let(SetJson::encodeSets),
                dayId = slot.dayId.takeIf { it != day.dayId }, sharedFrom = from, followed = followed)
            if (index >= 0) logs[index] = record else logs.add(record)
        }
        suspend fun writeRule(slot: ProgramExerciseEntity, name: String, stored: String, after: String, from: Long? = null) {
            if (after == stored) return
            setRuleFor(slot.id, name, after)
            val index = rules.indexOfFirst { it.programExerciseId == slot.id && it.slot == name }
            val record = UndoRuleDto(slot.id, if (index >= 0) rules[index].beforeJson else stored, after, name, sharedFrom = from)
            if (index >= 0) rules[index] = record else rules.add(record)
        }
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
        changedShape.forEach {
            writeLog(slots.getValue(it), Slot.MAIN, null)
            writeLog(slots.getValue(it), Slot.SS, null)
        }
        val plan = planDao.plan(day.planId)
        val planAutomatic = plan?.autoApply == true
        val planSlots = programDao.exercisesForPlan(day.planId)
        val otherDays = planSlots.filter { it.dayId != day.dayId }
        val written = mutableSetOf<Long>()
        val steps = plans.planSettings(day.planId).loadSteps ?: LoadSteps.defaults(unit)
        suspend fun followFrom(source: ProgramExerciseEntity, rows: List<LoggedSet>, origin: Long) {
            val load = WorkingRows.workingWeight(rows)?.takeIf { it > 0.0 } ?: return
            for (write in followerWrites(planSlots, source, load, written, unit, catalog, steps)) {
                if (write.slot.id in written) continue
                writeLog(write.slot, Slot.MAIN, write.rows, from = origin, followed = true)
                writeRule(write.slot, Slot.MAIN, requireNotNull(write.slot.ruleJson), write.encodedRule, from = origin)
                written.add(write.slot.id)
                followFrom(write.slot, write.rows, origin)
            }
        }
        // Match the stored pre-Finish definitions; the first performed source owns each sibling's copy.
        suspend fun shareProgression(source: ProgramExerciseEntity, rows: List<LoggedSet>, rule: SlotRule) {
            val performed = source.toDomain()
            for (other in otherDays) {
                if (other.id in written) continue
                val sibling = other.toDomain()
                val siblingRule = sibling.rule ?: continue
                if (!SharedProgression.shares(performed, sibling, planAutomatic)) continue
                val stored = programDao.logForSlot(day.planId, other.dayId, other.id, Slot.MAIN) ?: continue
                val storedRows = runCatching { SetJson.decodeSets(stored.setsJson) }.getOrNull() ?: continue
                if (storedRows.map { it.kind } != rows.map { it.kind }) continue
                val encoded = try { SlotRuleJson.encode(SharedProgression.copied(siblingRule, rule)) }
                    catch (_: RuntimeException) { continue }
                writeLog(other, Slot.MAIN, rows, from = source.id)
                writeRule(other, Slot.MAIN, requireNotNull(other.ruleJson), encoded, from = source.id)
                written.add(other.id)
                if (WorkingRows.workingWeight(rows) != WorkingRows.workingWeight(storedRows)) followFrom(other, rows, source.id)
            }
        }
        val today = localEpochDay(now)
        var stalled = false
        for (track in tracks) {
            if (track.retired && !track.dropped) continue
            val actual = SetJson.decodeSets(track.setsJson)
            if (track.dropped && actual.none { it.done }) continue
            val opening = OpeningJson.decode(track.openingJson)
            val window = if (track.slot == Slot.MAIN) repWindowOf(slots[track.programExerciseId]?.prescriptionJson) else null
            // A benchmark is a view of the slot (#646): its result lives in test_result; rows and rule are untouched.
            if (opening?.benchmark == true) {
                reasons[track.id] = ReasonCode.encode(Reason.Measured)
                continue
            }
            // Non-window light openings are views; recalibration must persist.
            if (window == null && opening?.light == true && opening.layoff != OpeningDto.RECALIBRATE) {
                val planned = SetJson.decodeSets(track.plannedJson)
                val reached = WorkingRows.workSetIndices(planned).any { i -> actual.any { it.plannedIndex == i && it.done } }
                reasons[track.id] = ReasonCode.encode(if (reached) Reason.LightCycle else Reason.NoSets)
                continue
            }
            val slot = slots[track.programExerciseId] ?: continue
            if (slot.exerciseIdFor(track.slot) != track.exerciseId) continue
            if (slot.id in changedShape) continue
            if (slot.keepToPlan) {
                writeLog(slot, track.slot, null)
                continue
            }
            val planned = SetJson.decodeSets(track.plannedJson)
            val stored = slot.ruleJsonFor(track.slot)
            val rule = stored?.let(SlotRuleJson::decode)
            val log = programDao.logForSlot(day.planId, day.dayId, slot.id, track.slot)
            val working = log?.let {
                runCatching { SetJson.decodeSets(it.setsJson) }.getOrNull()
            } ?: WorkingRows.cleared(planned)
            // Only fitting gives a partner's Hold another row count than its stored row; a row deleted mid-run reads as fitted.
            val fittedPartner = track.slot == Slot.SS && opening?.layoff == OpeningDto.HOLD && (log == null || planned.size != working.size)
            if (rule == null) {
                if (log != null || !fittedPartner) writeLog(slot, track.slot, if (opening != null) WorkingRows.cleared(working) else WorkingRows.carried(planned, actual))
                continue
            }
            // A fitted partner gives no evidence, calibrating or not: its rule counts the exposure and nothing else changes (spec W2b-2b decision 9; owner, 2026-11-09).
            if (fittedPartner) {
                if (WorkingRows.workSetIndices(planned).any { i -> actual.any { it.plannedIndex == i && it.done } }) {
                    writeRule(slot, track.slot, requireNotNull(stored),
                        SlotRuleJson.encode(rule.copy(state = rule.state.copy(exposures = rule.state.exposures + 1))))
                }
                continue
            }
            val layoff = opening?.let { runCatching { it.layoffKind() }.getOrNull() } ?: LayoffKind.None
            val entry = catalog.find(track.exerciseId)
            val automatic = rule.automatic(planAutomatic)
            val sourceLoad = if (track.slot == Slot.MAIN) {
                val tracking = trackingOf(catalog, slot.exerciseId)
                val program = assemble(programDao.daysForPlan(day.planId), planSlots)
                LoadFollowing.followedDay(program, slot.dayId, slot.toDomain(), tracking)?.let { sourceDay ->
                    sourceSlot(planSlots, sourceDay, slot.exerciseId, tracking)?.let { currentLoad(it) }
                }
            } else null
            // A malformed row must never block Finish; an opened track keeps its stored load.
            val prepared = try {
                val outcome = Progression.afterSession(rule, working, Progression.Exposure(planned, actual, today, layoff = layoff),
                    Progression.Context(unit, entry?.equipment.orEmpty().toSet(), steps, today, light = opening?.light == true, holdCeilingSeconds = entry?.holdCeilingSeconds, sourceLoadLb = sourceLoad), window)
                val applied = automatic || outcome.reason == Reason.Followed
                val writtenRule = if (applied) outcome.rule else outcome.rule.copy(target = rule.target,
                    state = outcome.rule.state.copy(calibrating = rule.state.calibrating))
                Prepared(outcome, writtenRule, SlotRuleJson.encode(writtenRule),
                    if (outcome.reason == Reason.Followed) null else offerOf(outcome, if (opening == null) planned else WorkingRows.cleared(working), working, rule), applied)
            } catch (_: RuntimeException) { null }
            if (prepared == null) {
                writeLog(slot, track.slot, if (opening != null) WorkingRows.cleared(working) else WorkingRows.carried(planned, actual))
                continue
            }
            val outcome = prepared.outcome
            reasons[track.id] = ReasonCode.encode(outcome.reason)
            val writtenRows = if (prepared.applied || outcome.reason == Reason.Measured) outcome.rows else WorkingRows.cleared(working)
            writeLog(slot, track.slot, writtenRows)
            writeRule(slot, track.slot, requireNotNull(stored), prepared.encodedRule)
            if (track.slot == Slot.MAIN) {
                written.add(slot.id)
                if (WorkingRows.workingWeight(writtenRows) != WorkingRows.workingWeight(working)) followFrom(slot, writtenRows, slot.id)
                shareProgression(slot, writtenRows, prepared.rule)
            }
            if (outcome.reason == Reason.Stall) stalled = true
            prepared.offer?.let { offers[track.id] = it }
        }
        val reactive = plan != null && plan.autoApply && plan.lightCycleRemaining == 0 && stalled &&
            stalledLifts(day.planId, today) >= 2
        val planUndo = plan?.let { afterDay(it, day.dayId, reactive, now, unit) }
        val undo = if (logs.isEmpty() && rules.isEmpty() && planUndo == null) null
            else ProgressionUndoJson.encode(ProgressionUndoDto(logs, rules, planUndo))
        return Progressed(offers, undo, reasons, planUndo?.replan != null)
    }

    private fun localEpochDay(millis: Long): Long =
        Instant.ofEpochMilli(millis).atZone(clock.zone).toLocalDate().toEpochDay()

    private fun trainingDays(program: Program): List<ProgramDay> =
        program.rotationDays.filter { it.kind != ProgramDayKind.REST }

    private suspend fun afterDay(plan: PlanEntity, dayId: String, reactive: Boolean, finishedAt: Long,
                                 unit: WeightUnit): UndoPlanDto? {
        val counters = lightAfterDay(plan, dayId, reactive) ?: return null
        if (counters.afterCycles <= counters.beforeCycles) return counters
        val current = planDao.plan(plan.id) ?: return counters
        val (next, recipe) = nextPhase(current, finishedAt, unit) ?: return counters
        return if (current.autoApply) {
            val old = assemble(programDao.daysForPlan(current.id), programDao.exercisesForPlan(current.id))
            val due = Rotation.upNextAfter(old, current.suggestedDayId, dayId)
            counters.copy(replan = writeReplan(current, next, recipe, due))
        } else counters.copy(replanOffer = UndoReplanOfferDto(next.phase?.name))
    }

    /** Called inside Finish's transaction, with settings frozen by its caller. */
    private suspend fun nextPhase(plan: PlanEntity, finishedAt: Long, unit: WeightUnit): Pair<RunReplan.Replanned, String>? {
        val planId = plan.id
        val answers = plan.setupAnswers(unit)?.takeIf {
            it.branch == PlanBranch.RUN || (it.branch == PlanBranch.BOTH && !it.both.sprint)
        } ?: return null
        val recipe = plan.recipeJson ?: return null
        val phase = SetupRecipeJson.phase(recipe) ?: return null
        val days = programDao.daysForPlan(planId)
        val program = assemble(days, programDao.exercisesForPlan(planId))
        val family = CardioFamily.of(answers.run.activity)
        val log = db.cardioLog(family, finishedAt)
        val history = CardioHistory.of(log, finishedAt)
        val known = CardioHistory.known(family, log, finishedAt)
        val week = CardioHistory.lastWeek(log, finishedAt) ?: return null
        val next = runCatching {
            RunReplan.next(program, answers, phase, known, localEpochDay(finishedAt), week, history.secondsPerMetre)
        }.getOrNull() ?: return null
        return next to recipe
    }

    /** Where a re-plan leaves the cursor: a runner due a rest keeps one, the new rotation's closing REST entry;
     *  everyone else starts at day A (lead, 2026-11-18). */
    private fun replanCursor(old: Program, due: String?, new: Program): String? {
        val dayA = Rotation.trainingDayFrom(new, new.rotationDays.first().id)
        val dueRest = old.days.firstOrNull { it.id == due }?.kind == ProgramDayKind.REST
        return if (dueRest) new.rotationDays.lastOrNull { it.kind == ProgramDayKind.REST }?.id ?: dayA else dayA
    }

    private suspend fun writeReplan(plan: PlanEntity, next: RunReplan.Replanned, recipe: String, due: String?): UndoReplanDto {
        val planId = plan.id
        val days = programDao.daysForPlan(planId)
        val old = assemble(days, programDao.exercisesForPlan(planId))
        val cursor = replanCursor(old, due, next.program)
        val recipeAfter = SetupRecipeJson.withPhase(recipe, next.phase)
        val records = next.program.days.mapIndexed { index, day ->
            val before = days.firstOrNull { it.dayId == day.id }
            val columns = day.toEntity(planId).copy(position = index)
            // A kept block retains its original serialized columns as well as its domain values.
            val after = if (before == null) columns.freshRunId(mintRunId)
                else if (before.cardioKeepToPlan) before.copy(position = index)
                else before.copy(kind = columns.kind, title = columns.title, position = index,
                    cardioBlockJson = columns.cardioBlockJson, cardioRuleJson = columns.cardioRuleJson,
                    cardioWorkingJson = columns.cardioWorkingJson)
            programDao.upsertDay(after)
            UndoReplanDayDto(day.id, before?.replanColumns(), after.replanColumns())
        }
        planDao.updatePlan(plan.copy(recipeJson = recipeAfter, suggestedDayId = cursor))
        return UndoReplanDto(recipe, recipeAfter, records, cursorBefore = due, cursorAfter = cursor)
    }

    /** Accepts the Finish's phase offer from its log date and includes the merge in that Finish's Undo. */
    suspend fun acceptReplanOffer(sessionId: Long?, cardioSessionId: Long?): ReplanAccept {
        val unit = settings.unitFlow.first()
        return db.withTransaction {
            val strength = sessionId?.let { sessionDao.sessionById(it) }
            val cardio = cardioSessionId?.let { cardioSessionDao.byId(it) }
            val strengthUndo = ProgressionUndoJson.decode(strength?.progressionUndoJson)
            val cardioUndo = CardioUndoJson.decode(cardio?.progressionUndoJson)
            val fromStrength = strengthUndo?.plan?.replanOffer != null
            val counter = if (fromStrength) strengthUndo?.plan else cardioUndo?.plan
            if (counter?.replanOffer == null) return@withTransaction ReplanAccept.Gone
            val planId = (if (fromStrength) strength?.planId else cardio?.planId) ?: return@withTransaction ReplanAccept.Gone
            if (runDao.openRun()?.planId == planId) return@withTransaction ReplanAccept.RunOpen
            val plan = planDao.plan(planId) ?: return@withTransaction ReplanAccept.Gone
            val finishedAt = if (fromStrength) requireNotNull(strength).completedAt else requireNotNull(cardio).completedAt
            val (next, recipe) = nextPhase(plan, finishedAt, unit) ?: return@withTransaction ReplanAccept.Waiting
            val updated = counter.copy(replan = writeReplan(plan, next, recipe, due = plan.suggestedDayId), replanOffer = null)
            if (fromStrength) {
                sessionDao.updateProgression(requireNotNull(strength).id,
                    ProgressionUndoJson.encode(requireNotNull(strengthUndo).copy(plan = updated)), strength.roughDay)
            } else {
                cardioSessionDao.update(requireNotNull(cardio).copy(progressionUndoJson =
                    CardioUndoJson.encode(requireNotNull(cardioUndo).copy(plan = updated))))
            }
            ReplanAccept.Moved
        }
    }

    private fun ProgramDayEntity.replanColumns() = UndoDayColumnsDto(kind, title, position,
        cardioBlockJson, cardioRuleJson, cardioWorkingJson)

    private fun ProgramDayEntity.withColumns(columns: UndoDayColumnsDto) = copy(kind = columns.kind,
        title = columns.title, position = columns.position, cardioBlockJson = columns.block,
        cardioRuleJson = columns.rule, cardioWorkingJson = columns.working)

    private suspend fun replanHeldByOpenRun(planId: Long, record: UndoReplanDto): Boolean =
        runDao.openRun()?.let { open ->
            open.planId == planId && record.days.any { it.before == null && it.dayId == open.dayId }
        } == true

    private suspend fun replanMatches(planId: Long, record: UndoReplanDto): Boolean {
        if (planDao.plan(planId)?.recipeJson != record.recipeAfter) return false
        if (replanHeldByOpenRun(planId, record)) return false
        return record.days.all { day ->
            programDao.day(planId, day.dayId)?.replanColumns() == day.after &&
                (day.before != null || programDao.exercisesForDay(planId, day.dayId).isEmpty())
        }
    }

    /** Restore before the Finish's cardio records, so their afters match again, including LadderDone siblings. */
    private suspend fun undoReplan(planId: Long, record: UndoReplanDto) {
        if (!replanMatches(planId, record)) return
        for (day in record.days) {
            if (day.before == null) programDao.deleteDay(planId, day.dayId)
            else programDao.upsertDay(requireNotNull(programDao.day(planId, day.dayId)).withColumns(day.before))
        }
        val plan = requireNotNull(planDao.plan(planId))
        val cursor = if (record.cursorAfter != null && plan.suggestedDayId == record.cursorAfter) record.cursorBefore
            else plan.suggestedDayId
        planDao.updatePlan(plan.copy(recipeJson = record.recipeBefore, suggestedDayId = cursor))
    }

    /** Counts a finished training day down and records a wrap (spec W2b-2a decisions 5 and 6); null when nothing
     *  changed or [dayId] is not a training day of the rotation. Reads the rotation before it advances. Room only. */
    private suspend fun lightAfterDay(plan: PlanEntity, dayId: String, reactive: Boolean): UndoPlanDto? {
        val program = assemble(programDao.daysForPlan(plan.id), programDao.exercisesForPlan(plan.id))
        val training = trainingDays(program)
        if (training.none { it.id == dayId }) return null
        val before = LightCycle(plan.lightCycleRemaining, plan.cyclesCompleted)
        val after = LightWeek.afterDay(before, training.size, Rotation.wraps(program, plan.suggestedDayId, dayId),
            plan.deloadEveryCycles, reactive)
        if (after == before) return null
        planDao.updateLightCycle(plan.id, after.remaining, after.cyclesCompleted)
        return UndoPlanDto(before.remaining, before.cyclesCompleted, after.remaining, after.cyclesCompleted)
    }

    /** Distinct exercises among the plan's ruled, non-Plan main and partner tracks with a stall in this Finish's rotation window:
     *  this Finish and the plan's previous (strength entries − 1) sessions. Call after this Finish's rule writes. */
    private suspend fun stalledLifts(planId: Long, today: Long): Int {
        val slots = programDao.exercisesForPlan(planId)
        val program = assemble(programDao.daysForPlan(planId), slots)
        val entries = maxOf(1, program.rotationDays.count { it.kind == ProgramDayKind.STRENGTH })
        val windowStart = (sessionDao.recentCompletedAt(planId, entries - 1).map(::localEpochDay) + today).min()
        return slots.filter { !it.keepToPlan }.flatMap { slot ->
            listOf(Slot.MAIN, Slot.SS).mapNotNull { name ->
                slot.exerciseIdFor(name)?.takeIf {
                    SlotRuleJson.decode(slot.ruleJsonFor(name))?.state?.resetEpochDays?.any { date -> date in windowStart..today } == true
                }
            }
        }.distinct().size
    }

    /** A slot's reps-in-time prescription; a malformed one reads as none so it never blocks Finish. */
    private fun repWindowOf(prescriptionJson: String?): PrescribedTarget.RepsInTime? =
        runCatching { PrescriptionJson.decode(prescriptionJson)?.target as? PrescribedTarget.RepsInTime }.getOrNull()

    private fun offerOf(outcome: Outcome, planned: List<LoggedSet>, working: List<LoggedSet>, rule: SlotRule): OfferDto? {
        val from = WorkingRows.workingWeight(planned) ?: return null
        val weights = when (val change = outcome.change) {
            is Change.Increase -> from to change.toLb
            is Change.Decrease -> from to change.toLb
            is Change.KeptLowered -> from to change.lb
            is Change.Calibrated -> from to change.lb
            is Change.RepsTo, is Change.SecondsTo -> from to (WorkingRows.workingWeight(outcome.rows) ?: from)
            else -> WorkingRows.workingWeight(outcome.rows)?.takeIf { it != from }?.let { from to it } ?: return null
        }
        return OfferDto(weights.first, weights.second, WorkingRows.cleared(working).map(SetDto::of),
            outcome.rows.map(SetDto::of), SlotRuleJson.encode(rule), SlotRuleJson.encode(outcome.rule))
    }

    private val CardioDayUndoDto.ladder: Boolean
        get() = CardioRuleJson.decode(ruleBefore)?.ladderLevel != null || CardioRuleJson.decode(ruleAfter)?.ladderLevel != null

    /** A ladder Finish restores its shared credit, dates and level together, or none of them after a later edit to a
     *  ladder day, including leaving Keep, which rewrites that day's block. */
    private suspend fun ladderRecordsMatch(planId: Long, dayId: String, undo: CardioUndoDto): Boolean {
        val days = undo.days.filter { it.ladder }
        val rule = undo.rule
        val ladderRule = CardioRuleJson.decode(rule?.before)?.ladderLevel != null ||
            CardioRuleJson.decode(rule?.after)?.ladderLevel != null
        if (days.isEmpty() && !ladderRule) return true
        return days.all { it.matches(programDao.day(planId, it.dayId)) } &&
            (rule == null || programDao.day(planId, dayId)?.cardioRuleJson == rule.after)
    }

    suspend fun undoFinish(sessionId: Long?, cardioSessionId: Long?): UndoResult = db.withTransaction {
        val session = sessionId?.let { sessionDao.sessionById(it) }
        val cardio = cardioSessionId?.let { cardioSessionDao.byId(it) }
        val planId = session?.planId ?: cardio?.planId ?: return@withTransaction UndoResult.Nothing
        val dayId = session?.dayId ?: cardio?.dayId ?: return@withTransaction UndoResult.Nothing
        if (dayOpen(planId, dayId)) return@withTransaction UndoResult.DayOpen
        if (session?.roughDay == true) return@withTransaction UndoResult.Nothing
        val strengthUndo = ProgressionUndoJson.decode(session?.progressionUndoJson)
        if (strengthUndo != null && touchesOpenDay(planId, strengthUndo)) return@withTransaction UndoResult.DayOpen
        val cardioUndo = CardioUndoJson.decode(cardio?.progressionUndoJson)
        if (strengthUndo == null && cardioUndo == null) return@withTransaction UndoResult.Nothing
        var replanKept = false
        strengthUndo?.plan?.replan?.let {
            replanKept = replanHeldByOpenRun(planId, it)
            undoReplan(planId, it)
        }
        if (cardio?.planId == planId && cardio.dayId == dayId) {
            cardioUndo?.plan?.replan?.let {
                replanKept = replanHeldByOpenRun(planId, it) || replanKept
                undoReplan(planId, it)
            }
        }
        var ladderKept = false
        if (strengthUndo != null) {
            swapStrength(planId, dayId, strengthUndo, reverse = false)
            strengthUndo.plan?.let { swapCounters(planId, it, reverse = false) }
            sessionDao.clearProgressionUndo(requireNotNull(session).id)
        }
        if (cardioUndo != null && cardio?.planId == planId && cardio.dayId == dayId) {
            val ladderMatches = ladderRecordsMatch(planId, dayId, cardioUndo)
            ladderKept = cardioUndo.days.any { it.ladder } && !ladderMatches
            cardioUndo.block?.let { block ->
                if (programDao.day(planId, dayId)?.cardioWorkingJson == block.after) {
                    programDao.setCardioWorking(planId, dayId, block.before)
                }
            }
            for (record in cardioUndo.days) {
                if ((!record.ladder || ladderMatches) && record.matches(programDao.day(planId, record.dayId))) {
                    programDao.setCardioState(planId, record.dayId, record.blockBefore, record.workingBefore, record.ruleBefore)
                }
            }
            cardioUndo.rule?.let { rule ->
                if (ladderMatches && programDao.day(planId, dayId)?.cardioRuleJson == rule.after) {
                    programDao.setCardioRule(planId, dayId, rule.before)
                }
            }
            cardioUndo.goal?.let { goal ->
                if (planDao.plan(planId)?.goalJson == goal.after) planDao.updateGoal(planId, goal.before)
            }
            cardioUndo.plan?.let { swapCounters(planId, it, reverse = false) }
            cardioSessionDao.update(cardio.copy(progressionUndoJson = null))
        }
        when {
            replanKept -> UndoResult.UndoneReplanKept
            ladderKept -> UndoResult.UndoneLadderKept
            else -> UndoResult.Undone
        }
    }

    private suspend fun dayOpen(planId: Long, dayId: String): Boolean =
        runDao.openRun()?.let { it.planId == planId && it.dayId == dayId } == true

    private suspend fun touchesOpenDay(planId: Long, undo: ProgressionUndoDto): Boolean =
        undo.logs.any { log -> log.dayId?.let { dayOpen(planId, it) } == true }

    private suspend fun swapCounters(planId: Long, counters: UndoPlanDto, reverse: Boolean) {
        val current = planDao.plan(planId) ?: return
        val expectedRemaining = if (reverse) counters.beforeRemaining else counters.afterRemaining
        val expectedCycles = if (reverse) counters.beforeCycles else counters.afterCycles
        if (current.lightCycleRemaining == expectedRemaining && current.cyclesCompleted == expectedCycles) {
            planDao.updateLightCycle(planId, if (reverse) counters.afterRemaining else counters.beforeRemaining,
                if (reverse) counters.afterCycles else counters.beforeCycles)
        }
    }

    private suspend fun swapStrength(planId: Long, dayId: String, undo: ProgressionUndoDto, reverse: Boolean,
                                     excluded: Set<Pair<Long?, String>> = emptySet()) {
        for (log in undo.logs) {
            if ((log.sharedFrom ?: log.programExerciseId) to log.slot in excluded) continue
            val logDay = log.dayId ?: dayId
            val current = programDao.logForSlot(planId, logDay, log.programExerciseId, log.slot)
            if (current?.setsJson != (if (reverse) log.before?.setsJson else log.afterSetsJson)) continue
            val target = if (reverse) log.afterSetsJson else log.before?.setsJson
            if (target != null) programDao.upsertLog(ExerciseLogEntity(planId, logDay, log.programExerciseId,
                log.slot, target, if (reverse) clock.millis() else log.before!!.updatedAt))
            else programDao.deleteLogForSlot(planId, logDay, log.programExerciseId, log.slot)
        }
        for (rule in undo.rules) {
            if ((rule.sharedFrom ?: rule.programExerciseId) to rule.slot in excluded) continue
            if (programDao.exercise(rule.programExerciseId)?.ruleJsonFor(rule.slot) == (if (reverse) rule.beforeJson else rule.afterJson)) {
                setRuleFor(rule.programExerciseId, rule.slot, if (reverse) rule.afterJson else rule.beforeJson)
            }
        }
    }

    suspend fun setRoughDay(sessionId: Long, rough: Boolean): UndoResult = db.withTransaction {
        val session = sessionDao.sessionById(sessionId) ?: return@withTransaction UndoResult.Nothing
        val planId = session.planId ?: return@withTransaction UndoResult.Nothing
        if (dayOpen(planId, session.dayId)) return@withTransaction UndoResult.DayOpen
        val undo = ProgressionUndoJson.decode(session.progressionUndoJson) ?: return@withTransaction UndoResult.Nothing
        if (touchesOpenDay(planId, undo)) return@withTransaction UndoResult.DayOpen
        if (session.roughDay == rough) return@withTransaction UndoResult.Nothing
        val excluded = session.plannedJson?.let(SessionPlanJson::decode).orEmpty().filter {
            it.opening?.layoff in listOf(OpeningDto.SCALED, OpeningDto.RECALIBRATE)
        }.map { it.programExerciseId to it.slot }.toSet()
        swapStrength(planId, session.dayId, undo, reverse = !rough, excluded = excluded)
        val plan = planDao.plan(planId)
        var roughLight: UndoPlanDto? = null
        if (rough && plan?.autoApply == true && plan.lightCycleRemaining == 0) {
            val previous = sessionDao.previousSessionForPlan(planId, session.completedAt, sessionId)
            if (previous?.roughDay == true) {
                val program = assemble(programDao.daysForPlan(planId), programDao.exercisesForPlan(planId))
                val days = trainingDays(program).size.coerceAtLeast(1)
                roughLight = UndoPlanDto(0, plan.cyclesCompleted, days, plan.cyclesCompleted)
                planDao.updateLightCycle(planId, days, plan.cyclesCompleted)
            }
        }
        if (!rough) undo.roughLight?.let { swapCounters(planId, it, reverse = false) }
        sessionDao.updateProgression(sessionId, ProgressionUndoJson.encode(undo.copy(roughLight = roughLight)), rough)
        UndoResult.Undone
    }

    suspend fun sessionByRunId(runId: String): WorkoutSessionEntity? = sessionDao.sessionByRunId(runId)

    suspend fun followedWrites(sessionId: Long): Map<Long, List<FollowedWrite>> {
        fun weight(json: String?): Double? =
            json?.let { runCatching { SetJson.decodeSets(it) }.getOrNull() }?.let(WorkingRows::workingWeight)
        return ProgressionUndoJson.decode(sessionDao.sessionById(sessionId)?.progressionUndoJson)?.logs.orEmpty()
            .mapNotNull { log ->
                val source = log.sharedFrom?.takeIf { log.followed } ?: return@mapNotNull null
                val day = log.dayId ?: return@mapNotNull null
                val to = weight(log.afterSetsJson) ?: return@mapNotNull null
                source to FollowedWrite(day, weight(log.before?.setsJson), to)
            }.groupBy({ it.first }, { it.second })
    }

    suspend fun finishUndoState(sessionId: Long?, cardioSessionId: Long?): FinishUndoState = db.withTransaction {
        val session = sessionId?.let { sessionDao.sessionById(it) }
        val cardio = cardioSessionId?.let { cardioSessionDao.byId(it) }
        val strength = ProgressionUndoJson.decode(session?.progressionUndoJson)
        val cardioUndo = CardioUndoJson.decode(cardio?.progressionUndoJson)
        suspend fun countersMatch(planId: Long?, undo: UndoPlanDto?): Boolean {
            if (undo == null || planId == null || undo.beforeRemaining == undo.afterRemaining) return false
            val plan = planDao.plan(planId) ?: return false
            return plan.lightCycleRemaining == undo.afterRemaining && plan.cyclesCompleted == undo.afterCycles
        }
        val logs = if (session?.planId != null) strength?.logs.orEmpty().any { log ->
            log.before?.setsJson != log.afterSetsJson &&
                programDao.logForSlot(session.planId, log.dayId ?: session.dayId, log.programExerciseId, log.slot)?.setsJson == log.afterSetsJson
        } else false
        val ruleChanges = strength?.rules.orEmpty().any { record ->
            val before = SlotRuleJson.decode(record.beforeJson)
            val after = SlotRuleJson.decode(record.afterJson)
            before != null && after != null && (before.target != after.target || before.state.calibrating != after.state.calibrating) &&
                programDao.exercise(record.programExerciseId)?.ruleJsonFor(record.slot) == record.afterJson
        }
        val block = cardioUndo?.block
        val cardioMatches = block != null && block.before != block.after && cardio?.planId != null && cardio.dayId != null &&
            programDao.day(cardio.planId, cardio.dayId)?.cardioWorkingJson == block.after
        val ladderMatches = if (cardioUndo != null && cardio?.planId != null && cardio.dayId != null) {
            ladderRecordsMatch(cardio.planId, cardio.dayId, cardioUndo)
        } else true
        val cardioRule = cardioUndo?.rule
        val cardioRuleMatches = ladderMatches && cardioRule != null && cardioRule.before != cardioRule.after &&
            cardio?.planId != null && cardio.dayId != null &&
            programDao.day(cardio.planId, cardio.dayId)?.cardioRuleJson == cardioRule.after
        val cardioDaysMatch = ladderMatches && cardio?.planId != null && cardioUndo?.days.orEmpty().any { record ->
            record.changed && record.matches(programDao.day(cardio.planId, record.dayId))
        }
        val replanRestorable = (strength?.plan?.replan?.let { record -> session?.planId?.let { replanMatches(it, record) } } == true) ||
            (cardioUndo?.plan?.replan?.let { record -> cardio?.planId?.let { replanMatches(it, record) } } == true)
        FinishUndoState(session?.roughDay == true, strength != null,
            replanRestorable || logs || ruleChanges || cardioMatches || cardioRuleMatches || cardioDaysMatch || countersMatch(session?.planId, strength?.plan) || countersMatch(cardio?.planId, cardioUndo?.plan),
            strength?.plan, strength?.roughLight, cardioUndo?.plan)
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

    /** Drops a draft or a run's actuals, preserving working values and plan edits, and closes the day's identity.
     * With [draftOnly], returns false without writing if the run has started or is no longer open. */
    suspend fun discardRun(runId: String, draftOnly: Boolean = false): Boolean = db.withTransaction {
        val run = runDao.openRun()?.takeIf { it.id == runId } ?: return@withTransaction false
        if (draftOnly && !run.draft) return@withTransaction false
        val day = run.planId?.let { planId -> run.dayId?.let { programDao.day(planId, it) } }
        if (day != null && day.nextRunId == run.id) {
            for (log in programDao.logsForDay(day.planId, day.dayId)) {
                programDao.upsertLog(log.copy(setsJson = SetJson.encodeSets(WorkingRows.cleared(SetJson.decodeSets(log.setsJson)))))
            }
            programDao.closeDay(day.planId, day.dayId, mintRunId(), clock.millis())
        }
        runDao.deleteTracks(runId)
        runDao.deleteRun(runId)
        true
    }

    private suspend fun closeRemovedRun(planId: Long, dayIds: Set<String>, bodyweight: Double?, catalog: ExerciseCatalog) {
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
