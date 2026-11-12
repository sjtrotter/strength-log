package cloud.trotter.log.strength.data

import android.database.sqlite.SQLiteException
import androidx.room.withTransaction
import cloud.trotter.log.strength.data.catalog.ExerciseCatalog
import cloud.trotter.log.strength.data.db.entity.Slot
import cloud.trotter.log.strength.data.serialization.ParkedLogsJson
import cloud.trotter.log.strength.data.serialization.SetJson
import cloud.trotter.log.strength.data.serialization.SlotRuleJson
import cloud.trotter.log.strength.data.db.StrengthDatabase
import cloud.trotter.log.strength.data.db.dao.PlanDao
import cloud.trotter.log.strength.data.db.dao.ProgramDao
import cloud.trotter.log.strength.data.db.dao.SessionDao
import cloud.trotter.log.strength.data.db.entity.PlanEntity
import cloud.trotter.log.strength.data.db.entity.PlanOrigin
import cloud.trotter.log.strength.data.db.entity.ProgramDayEntity
import cloud.trotter.log.strength.data.db.entity.ProgramExerciseEntity
import cloud.trotter.log.strength.data.db.entity.ExerciseLogEntity
import cloud.trotter.log.strength.data.mapping.toDomain
import cloud.trotter.log.strength.data.mapping.toEntity
import cloud.trotter.log.strength.data.prefs.SettingsStore
import cloud.trotter.log.strength.data.serialization.LegacyCardio
import cloud.trotter.log.strength.data.serialization.CardioBlockJson
import cloud.trotter.log.strength.data.serialization.PlanGoalJson
import cloud.trotter.log.strength.domain.model.PlanGoal
import cloud.trotter.log.strength.domain.model.PlanGoalDate
import cloud.trotter.log.strength.domain.model.ShuttleAttempt
import cloud.trotter.log.strength.data.serialization.PlanRecipeJson
import cloud.trotter.log.strength.data.serialization.PlanSettings
import cloud.trotter.log.strength.data.serialization.PlanSettingsJson
import cloud.trotter.log.strength.domain.progression.LiftGoal
import cloud.trotter.log.strength.data.serialization.planSettings
import cloud.trotter.log.strength.domain.generator.Rotation
import cloud.trotter.log.strength.domain.generator.WizardAnswers
import cloud.trotter.log.strength.domain.model.LifterConfig
import cloud.trotter.log.strength.domain.model.GoalEmphasis
import cloud.trotter.log.strength.domain.model.Program
import cloud.trotter.log.strength.domain.model.ProgramExercise
import cloud.trotter.log.strength.domain.model.PrescribedTarget
import cloud.trotter.log.strength.domain.model.SetStructure
import cloud.trotter.log.strength.domain.library.ExerciseEntry
import cloud.trotter.log.strength.domain.progression.Effort
import cloud.trotter.log.strength.domain.progression.WorkingRows
import cloud.trotter.log.strength.domain.model.LoggedSet
import cloud.trotter.log.strength.domain.model.SetKind
import cloud.trotter.log.strength.domain.progression.KnownLoads
import cloud.trotter.log.strength.domain.progression.LoadSteps
import cloud.trotter.log.strength.domain.progression.SlotRule
import cloud.trotter.log.strength.domain.progression.SlotSeeding
import cloud.trotter.log.strength.domain.units.WeightUnit
import java.time.Clock
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

@OptIn(ExperimentalCoroutinesApi::class)
class PlanRepository(
    private val db: StrengthDatabase,
    private val settings: SettingsStore,
    private val clock: Clock = Clock.systemDefaultZone(),
    private val mintRunId: () -> String = { UUID.randomUUID().toString() },
    private val programDao: ProgramDao = db.programDao(),
    private val sessionDao: SessionDao = db.sessionDao(),
    private val planDao: PlanDao = db.planDao(),
) {
    private val restoreMarkerDao = db.restoreMarkerDao()
    private val customExerciseDao = db.customExerciseDao()
    private var slotsRuled = false
    private val planSettingsMigrationMutex = Mutex()
    private var legacyCardioConverted = false
    private val planSelectionLock = Mutex()

    val plansFlow: Flow<List<PlanEntity>> = planDao.observePlans()

    /** Distinct, because DataStore emits on every key's write and each emission
     *  here restarts every plan-scoped Room query downstream. */
    val selectedPlanIdFlow: Flow<Long?> =
        combine(settings.selectedPlanIdFlow, plansFlow) { storedId, plans ->
            preferredPlan(plans, storedId)?.id
        }.distinctUntilChanged()

    val planSummariesFlow: Flow<List<PlanSummary>> = flow {
        ensurePlanSettingsMigrated()
        emitAll(combine(
            plansFlow,
            settings.selectedPlanIdFlow,
            programDao.observeAllDays(),
            sessionDao.lastSessionByPlan(),
        ) { plans, storedId, days, sessions ->
            val selectedId = preferredPlan(plans, storedId)?.id
            val daysByPlan = days.groupBy { it.planId }
            val lastByPlan = sessions.associate { it.planId to it.completedAt }
            plans.map { plan ->
                PlanSummary(
                    id = plan.id,
                    name = plan.name,
                    nextDayId = plan.suggestedDayId,
                    nextDayTitle = daysByPlan[plan.id]?.firstOrNull { it.dayId == plan.suggestedDayId }?.title,
                    selected = plan.id == selectedId,
                    archived = plan.archived,
                    onToday = plan.onToday,
                    lastSessionAt = lastByPlan[plan.id],
                )
            }
        })
    }

    /** Plans on Today, after the same settings gate as the display target. */
    val todayPlans: Flow<List<PlanEntity>> = flow {
        ensurePlanSettingsMigrated()
        emitAll(combine(plansFlow, settings.selectedPlanIdFlow) { plans, storedId ->
            val preferredId = preferredPlan(plans, storedId)?.id
            plans.filter { !it.archived && it.onToday }
                .sortedWith(compareBy<PlanEntity> { it.id != preferredId }.thenBy { it.position })
        }.distinctUntilChanged())
    }

    private val selectedPlanStateFlow: Flow<PlanEntity?> =
        combine(selectedPlanIdFlow, plansFlow) { id, plans ->
            plans.firstOrNull { it.id == id }
        }.distinctUntilChanged()

    /** Selected plan identity and rotation, observed together after legacy fixups. */
    val selectedPlanFlow: Flow<PlanEntity?> = flow {
        ensurePlanSettingsMigrated()
        emitAll(selectedPlanStateFlow)
    }

    private val planAnswersFlow: Flow<WizardAnswers> = combine(
        settings.globalLifterFlow,
        selectedPlanStateFlow,
    ) { global, plan -> answersForPlan(plan, global) }.distinctUntilChanged()

    /** A plan's configuration and equipment, including global lifter preferences. */
    fun wizardAnswersFor(planId: Long): Flow<WizardAnswers> = flow {
        ensurePlanSettingsMigrated()
        emitAll(combine(settings.globalLifterFlow, plansFlow) { global, plans ->
            answersForPlan(plans.firstOrNull { it.id == planId }, global)
        }.distinctUntilChanged())
    }

    val wizardAnswersFlow: Flow<WizardAnswers> = flow {
        ensurePlanSettingsMigrated()
        emitAll(planAnswersFlow)
    }
    val configFlow: Flow<LifterConfig> = flow {
        ensurePlanSettingsMigrated()
        emitAll(planAnswersFlow.map { it.config }.distinctUntilChanged())
    }

    /** Lifter edits write the global lifter alone; plan settings stay in the recipe. */
    suspend fun updateLifter(transform: (GlobalLifter) -> GlobalLifter) {
        ensurePlanSettingsMigrated()
        settings.updateGlobalLifter(transform)
    }

    suspend fun setPlanEmphasis(planId: Long, emphasis: GoalEmphasis) {
        ensurePlanSettingsMigrated()
        // Read DataStore before the transaction: suspending on it inside one can deadlock.
        val global = settings.globalLifterFlow.first()
        db.withTransaction {
            val plan = planDao.plan(planId) ?: return@withTransaction
            val recipe = plan.recipeJson ?: return@withTransaction
            val answers = PlanRecipeJson.decode(recipe, global)
            planDao.updateRecipe(
                planId,
                PlanRecipeJson.encode(answers.copy(config = answers.config.copy(emphasis = emphasis))),
            )
        }
    }

    suspend fun setPlanGoal(planId: Long, exerciseId: String, goalLb: Double?) {
        require(goalLb == null || (goalLb.isFinite() && goalLb > 0.0 && goalLb <= PlanRecipeJson.MAX_GOAL_LB))
        ensurePlanSettingsMigrated()
        // Read DataStore before the transaction: suspending on it inside one can deadlock.
        val global = settings.globalLifterFlow.first()
        db.withTransaction {
            val plan = planDao.plan(planId) ?: return@withTransaction
            val recipe = plan.recipeJson ?: return@withTransaction
            val answers = PlanRecipeJson.decode(recipe, global)
            val goals = if (goalLb == null) answers.config.goalsLb - exerciseId
                else answers.config.goalsLb + (exerciseId to goalLb)
            planDao.updateRecipe(
                planId,
                PlanRecipeJson.encode(answers.copy(config = answers.config.copy(goalsLb = goals))),
            )
        }
    }

    /** Persists the wizard inputs so a single day can later be regenerated
     *  ([TrackerRepository.resetDayToTemplate]) from the same recipe. */
    suspend fun setWizardAnswers(answers: WizardAnswers) {
        ensurePlanSettingsMigrated()
        settings.setGlobalLifter(GlobalLifter.of(answers.config))
        val planId = ensureSelectedPlan()
        db.withTransaction {
            planDao.updateRecipe(planId, PlanRecipeJson.encode(answers))
        }
    }

    internal suspend fun selectedPlanId(): Long? = selectedPlanIdFlow.first()

    internal suspend fun ensureSelectedPlan(): Long = selectedPlanId() ?: run {
        val position = (planDao.allPlans().maxOfOrNull { it.position } ?: -1) + 1
        val id = planDao.insertPlan(
            PlanEntity(
                id = 0,
                name = PlanEntity.LEGACY_NAME,
                onToday = true,
                origin = PlanOrigin.GENERATED.name,
                position = position,
                createdAt = clock.millis(),
            ),
        )
        settings.setSelectedPlanId(id)
        id
    }

    val programFlow: Flow<Program> = flow {
        ensurePlanSettingsMigrated()
        emitAll(selectedPlanIdFlow.flatMapLatest { planId ->
            if (planId == null) {
                flowOf(Program(emptyList()))
            } else {
                programFor(planId)
            }
        })
    }

    /** A plan's program, independent of selection. */
    fun programFor(planId: Long): Flow<Program> = flow {
        ensurePlanSettingsMigrated()
        emitAll(combine(
            programDao.observeDays(planId),
            programDao.observeExercises(planId),
            ::assemble,
        ))
    }

    /** Selects an existing active plan without changing any plan's contents. */
    suspend fun selectPlan(id: Long) {
        planSelectionLock.withLock {
            ensurePlanSettingsMigrated()
            if (planDao.plan(id)?.archived == false) {
                planDao.updateOnToday(id, true)
                settings.setSelectedPlanId(id)
            }
        }
    }

    suspend fun useAlongside(id: Long) = setOnToday(id, true)

    suspend fun pause(id: Long) = setOnToday(id, false)

    suspend fun setAutoApply(planId: Long, on: Boolean) = planDao.updateAutoApply(planId, on)

    suspend fun setLightWeek(planId: Long, cycles: Int?) {
        require(cycles == null || cycles in 4..8)
        planDao.updateDeloadEvery(planId, cycles)
    }

    suspend fun setDefaultsFor(planId: Long, goal: LiftGoal) = editSettings(planId) { it.copy(defaultsFor = goal) }

    suspend fun setRunCap(planId: Long, on: Boolean) = editSettings(planId) { it.copy(runCap = on) }

    private suspend fun editSettings(planId: Long, edit: (PlanSettings) -> PlanSettings) = db.withTransaction {
        val plan = planDao.plan(planId) ?: return@withTransaction
        planDao.updateSettings(planId, PlanSettingsJson.encode(edit(plan.planSettings)))
    }

    suspend fun setOfferStrengthIncreases(planId: Long, on: Boolean) = planDao.updateOfferStrengthIncreases(planId, on)

    suspend fun setOfferCardioIncreases(planId: Long, on: Boolean) = planDao.updateOfferCardioIncreases(planId, on)

    suspend fun setGoal(planId: Long, goal: PlanGoal?) = db.withTransaction {
        require(PlanGoalDate.isValid(goal?.byEpochDay))
        val stored = PlanGoalJson.decode(planDao.plan(planId)?.goalJson)
        val next = if (goal is PlanGoal.Shuttle) {
            val attempts = (stored as? PlanGoal.Shuttle)?.takeIf {
                it.protocol.trim().equals(goal.protocol.trim(), ignoreCase = true)
            }?.attempts.orEmpty()
            goal.copy(protocol = goal.protocol.trim(), attempts = attempts)
        } else goal
        planDao.updateGoal(planId, next?.let(PlanGoalJson::encode))
    }

    suspend fun logShuttleAttempt(planId: Long, shuttles: Int) = db.withTransaction {
        val goal = PlanGoalJson.decode(planDao.plan(planId)?.goalJson) as? PlanGoal.Shuttle
            ?: return@withTransaction
        val next = goal.copy(attempts = goal.attempts + ShuttleAttempt(clock.millis(), shuttles))
        planDao.updateGoal(planId, PlanGoalJson.encode(next))
    }

    private suspend fun setOnToday(id: Long, onToday: Boolean) {
        planSelectionLock.withLock {
            ensurePlanSettingsMigrated()
            if (planDao.plan(id)?.archived == false) planDao.updateOnToday(id, onToday)
        }
    }

    suspend fun useOnly(id: Long) {
        planSelectionLock.withLock {
            ensurePlanSettingsMigrated()
            val changed = db.withTransaction {
                if (planDao.plan(id)?.archived != false) return@withTransaction false
                planDao.useOnly(id)
                true
            }
            if (changed) settings.setSelectedPlanId(id)
        }
    }

    /** Commits a generated plan or fills the selected settings-only plan,
     * returning its id without writing DataStore.
     * Removes no day: the rewrite branch fills only a plan with no days, so no run can be open on it. */
    suspend fun createGeneratedPlan(name: String, answers: WizardAnswers, program: Program): Long {
        return planSelectionLock.withLock {
            val storedId = settings.selectedPlanIdFlow.first()
            db.withTransaction {
                val plans = planDao.allPlans()
                val selected = preferredPlan(plans, storedId)
                val recipe = PlanRecipeJson.encode(answers)
                val nextDay = program.rotationDays.firstOrNull()?.let { Rotation.trainingDayFrom(program, it.id) }
                val planId = if (selected != null && programDao.daysForPlan(selected.id).isEmpty()) {
                    planDao.updatePlan(selected.copy(
                        name = name,
                        origin = PlanOrigin.GENERATED.name,
                        recipeJson = recipe,
                        suggestedDayId = nextDay,
                        offerCardioIncreases = answers.cardioFirst != null,
                    ))
                    selected.id
                } else {
                    planDao.insertPlan(PlanEntity(
                        id = 0,
                        name = name,
                        origin = PlanOrigin.GENERATED.name,
                        position = (planDao.maxPosition() ?: -1) + 1,
                        createdAt = clock.millis(),
                        recipeJson = recipe,
                        suggestedDayId = nextDay,
                        offerCardioIncreases = answers.cardioFirst != null,
                    ))
                }
                program.days.forEachIndexed { index, day ->
                    programDao.upsertDay(day.toEntity(planId).copy(position = index).freshRunId(mintRunId))
                    day.exercises.forEachIndexed { position, exercise ->
                        programDao.insertExercise(exercise.toEntity(planId, day.id, position))
                    }
                }
                val catalog = readableCatalog(customExerciseDao.getAll())
                programDao.exercisesForPlan(planId).filter { it.ruleJson == null }
                    .forEach { ruleSlot(it, answers.config.weightUnit, catalog) }
                programDao.exercisesForPlan(planId).filter { it.supersetExerciseId != null && it.partnerRuleJson == null }
                    .forEach { rulePartner(it, null, answers.config.weightUnit, catalog) }
                planId
            }
        }
    }

    /** Idempotently selects an existing active plan under the selection lock,
     * optionally writing the global lifter when setup collected those answers. */
    suspend fun publishCreatedPlan(id: Long, answers: WizardAnswers, writeLifter: Boolean = true) {
        ensurePlanSettingsMigrated()
        planSelectionLock.withLock {
            val active = planDao.plan(id)?.archived == false
            if (active) planDao.updateOnToday(id, true)
            if (writeLifter) settings.setGlobalLifter(GlobalLifter.of(answers.config))
            if (active) settings.setSelectedPlanId(id)
        }
    }

    /** Creates the program before publishing selection; no global lifter write. */
    suspend fun createAuthoredPlan(name: String, dayTitle: String): Long = db.withTransaction {
        require(name.trim().length in 1..40)
        require(dayTitle.trim().length in 1..30)
        val id = planDao.insertPlan(PlanEntity(
            id = 0,
            name = name.trim(),
            origin = PlanOrigin.AUTHORED.name,
            position = (planDao.maxPosition() ?: -1) + 1,
            createdAt = clock.millis(),
            suggestedDayId = "A",
            autoApply = true,
        ))
        programDao.upsertDay(ProgramDayEntity(id, "A", 0, dayTitle.trim(), "", null).freshRunId(mintRunId))
        id
    }

    suspend fun publishCreatedAuthoredPlan(id: Long) {
        ensurePlanSettingsMigrated()
        planSelectionLock.withLock {
            val plan = planDao.plan(id)
            if (plan?.origin == PlanOrigin.AUTHORED.name && !plan.archived) {
                planDao.updateOnToday(id, true)
                settings.setSelectedPlanId(id)
            }
        }
    }

    /** Whether the remembered plan still exists with a non-empty program. */
    suspend fun planHasDays(id: Long): Boolean = db.withTransaction {
        planDao.plan(id) != null && programDao.daysForPlan(id).isNotEmpty()
    }

    /** Renames a plan when the trimmed name is between 1 and 40 characters. */
    suspend fun renamePlan(id: Long, name: String) {
        db.withTransaction {
            val trimmed = name.trim()
            if (trimmed.length in 1..40) planDao.updateName(id, trimmed)
        }
    }

    /** Copies a plan and its live logs immediately after the source, without selecting it. */
    suspend fun duplicatePlan(id: Long, name: String): Long = db.withTransaction {
        val source = requireNotNull(planDao.plan(id))
        planDao.shiftAfter(source.position)
        val copyId = planDao.insertPlan(source.copy(
            id = 0,
            name = name,
            position = source.position + 1,
            onToday = false,
            createdAt = clock.millis(),
        ))
        programDao.upsertDays(programDao.daysForPlan(id).map { it.copy(planId = copyId).freshRunId(mintRunId) })
        val newIds = programDao.exercisesForPlan(id).associate { exercise ->
            exercise.id to programDao.insertExercise(exercise.copy(id = 0, planId = copyId))
        }
        programDao.insertLogs(programDao.logsForPlan(id).map { log ->
            log.copy(planId = copyId, programExerciseId = newIds.getValue(log.programExerciseId))
        })
        copyId
    }

    /** Archives an unselected plan only while another active plan remains. */
    suspend fun archivePlan(id: Long): Boolean = planSelectionLock.withLock {
        // Read before the transaction: a DataStore wait must never hold one open.
        val storedId = settings.selectedPlanIdFlow.first()
        db.withTransaction { archiveUnlessSelected(id, storedId) }
    }

    private suspend fun archiveUnlessSelected(id: Long, storedId: Long?): Boolean {
        val plans = planDao.allPlans()
        val active = plans.filterNot { it.archived }
        val selectedId = preferredPlan(plans, storedId)?.id
        if (id == selectedId || active.size <= 1 || active.none { it.id == id }) return false
        planDao.updateArchived(id, true)
        planDao.updateOnToday(id, false)
        return true
    }

    /** Restores an archived plan without selecting it or changing its contents. */
    suspend fun restorePlan(id: Long) {
        planSelectionLock.withLock {
            db.withTransaction {
                planDao.updateArchived(id, false)
                planDao.updateOnToday(id, false)
            }
        }
    }

    /** Moves pre-plan preferences to Room before atomically retiring their keys.
     *  Also converts legacy cardio to typed blocks.
     *  Recipe-less generated plans make a retry after the Room commit harmless. */
    suspend fun runPlanSettingsFixupIfNeeded() {
        ensurePlanSettingsMigrated()
    }

    /** A half-done restore's settings are not this device's to copy; reconcile owns them.
     *  Also converts legacy cardio to typed blocks.
     *  Last, it gives existing slots their rules ([convertPlans]). */
    internal suspend fun ensurePlanSettingsMigrated(migrateOnToday: Boolean = true) {
        planSettingsMigrationMutex.withLock {
            if (restoreMarkerDao.nonce() != null) return@withLock
            if (!settings.planSettingsFixupDoneFlow.first()) {
                val legacy = settings.legacyAnswersFlow.first()
                val suggestedDay = settings.legacySuggestedDayFlow.first()
                if (legacy != null) {
                    val storedId = settings.selectedPlanIdFlow.first()
                    db.withTransaction {
                        val recipe = PlanRecipeJson.encode(legacy)
                        val plans = planDao.allPlans()
                        plans.filter { it.origin == PlanOrigin.GENERATED.name && it.recipeJson == null }
                            .forEach { planDao.updateRecipe(it.id, recipe) }
                        val selected = preferredPlan(plans, storedId)
                        if (selected != null && selected.suggestedDayId == null) {
                            planDao.updateSuggestedDay(selected.id, suggestedDay)
                        }
                    }
                }
                settings.removeLegacyPlanKeys()
            }
            if (migrateOnToday && !settings.onTodayFixupDoneFlow.first()) {
                val storedId = settings.selectedPlanIdFlow.first()
                val migrated = db.withTransaction {
                    if (restoreMarkerDao.nonce() != null) return@withTransaction false
                    preferredPlan(planDao.allPlans(), storedId)?.let { planDao.updateOnToday(it.id, true) }
                    true
                }
                if (migrated) settings.setOnTodayFixupDone()
            }
            if (!legacyCardioConverted) {
                val global = settings.globalLifterFlow.first()
                legacyCardioConverted = db.withTransaction {
                    if (restoreMarkerDao.nonce() != null) return@withTransaction false
                    val plans = planDao.allPlans().associateBy { it.id }
                    for (day in programDao.daysWithLegacyCardio()) {
                        val blockJson = day.cardioBlockJson ?: LegacyCardio.toBlock(
                            requireNotNull(day.cardioJson), answersForPlan(plans[day.planId], global),
                        )?.let(CardioBlockJson::encode)
                        programDao.upsertDay(day.copy(cardioBlockJson = blockJson, cardioJson = null))
                    }
                    true
                }
                if (!legacyCardioConverted) return@withLock
            }
            if (!slotsRuled) {
                slotsRuled = try {
                    convertPlans()
                } catch (_: SQLiteException) {
                    // Keep plans readable after a rolled-back conversion; the next gate call retries.
                    false
                }
            }
        }
    }

    /**
     * The day's exercise slots with their stable row ids, in program order. The UI
     * needs the id to key and seed each slot's log ([TrackerRepository.dayTracksFlow]/[TrackerRepository.applyToRun]); the
     * pure-domain [Program] from [programFlow] doesn't carry it.
     */
    fun daySlotsFlow(dayId: String): Flow<List<ProgramSlot>> =
        selectedPlanIdFlow.flatMapLatest { planId ->
            if (planId == null) flowOf(emptyList())
            else slotsFor(planId, dayId)
        }

    /** A plan's day slots, independent of selection; ids are global across plans. */
    fun slotsFor(planId: Long, dayId: String): Flow<List<ProgramSlot>> =
        programDao.observeExercisesForDay(planId, dayId).map { rows ->
            rows.map { ProgramSlot(it.id, it.position, it.toDomain()) }
        }

    /** Resolves legacy watch messages whose slot is their only plan identity. */
    suspend fun planIdOfSlot(programExerciseId: Long): Long? = programDao.exercise(programExerciseId)?.planId

    val suggestedDayFlow: Flow<String?> = flow {
        ensurePlanSettingsMigrated()
        emitAll(selectedPlanStateFlow.map { it?.suggestedDayId }.distinctUntilChanged())
    }

    /** Writes [slot]'s rule in the caller's transaction (design §4.4). A slot with a stored MAIN row takes its rule
     *  from those rows and a kept-to-plan slot from its parked MAIN rows. Any other slot gets the defaults and,
     *  written beside them, opening rows from [SlotSeeding] ([opening]), unless its exercise is unknown and it has no
     *  prescription. The caller reads [unit] before opening its transaction. */
    internal suspend fun ruleSlot(slot: ProgramExerciseEntity, unit: WeightUnit, catalog: ExerciseCatalog) {
        val entry = catalog.find(slot.exerciseId)
        val planSettings = planSettings(slot.planId)
        val stored = programDao.logForSlot(slot.planId, slot.dayId, slot.id, Slot.MAIN)
        val rows = when {
            stored != null -> runCatching { SetJson.decodeSets(stored.setsJson) }.getOrNull()
            slot.keepToPlan -> parkedRows(slot, Slot.MAIN, slot.exerciseId)
            else -> null
        }
        val rule = PlanConversion.rule(slot.toDomain(), rows, entry, planSettings.defaultsFor)
        val seeded = if (stored == null && !slot.keepToPlan && (entry != null || slot.toDomain().prescription != null)) {
            opening(slot.planId, slot.id, slot.toDomain(), rule, unit, entry)
        } else null
        programDao.setRule(slot.id, SlotRuleJson.encode(seeded?.rule ?: rule))
        if (seeded != null) {
            programDao.upsertLog(ExerciseLogEntity(slot.planId, slot.dayId, slot.id, Slot.MAIN,
                SetJson.encodeSets(seeded.rows), clock.millis()))
        }
    }

    /** [SlotSeeding.rows] in the caller's transaction: the plan's working load, else history through Estimate,
     *  else the equipment floor with calibrating on. Reads only Room. */
    private suspend fun opening(planId: Long, slotId: Long, exercise: ProgramExercise, rule: SlotRule,
                                unit: WeightUnit, entry: ExerciseEntry?): SlotSeeding.Seeded {
        val id = exercise.exerciseId
        val last = sessionDao.lastWorkingSet(id)?.let { id to LoggedSet(it.weightLb, it.reps, SetKind.WORK, rir = it.rir) }
        val planned = planWorkingRow(planId, slotId, id)
        return SlotSeeding.rows(exercise, rule, KnownLoads(listOfNotNull(last).toMap()),
            planSettings(planId).loadSteps ?: LoadSteps.defaults(unit), unit, entry, planned?.weightLb,
            planned?.reps ?: rule.target.reps, planned?.rir ?: Effort.GOOD.rir)
    }

    /** The plan's main or partner working row on another slot, carrying its reps and source rule's RIR for conversion.
     *  Calibrating tracks are skipped; the latest stored row wins, with prescription-only mains ranked last.
     *  A stored row that offers no working load never falls back to its prescription. */
    private suspend fun planWorkingRow(planId: Long, exceptSlotId: Long, exerciseId: String): LoggedSet? =
        programDao.exercisesForPlan(planId).filter { it.id != exceptSlotId }.flatMap { source ->
            listOf(Slot.MAIN, Slot.SS).mapNotNull { name ->
                val matches = if (name == Slot.MAIN) source.exerciseId == exerciseId else source.supersetExerciseId == exerciseId
                if (!matches) return@mapNotNull null
                val rule = SlotRuleJson.decode(if (name == Slot.MAIN) source.ruleJson else source.partnerRuleJson)
                if (rule?.state?.calibrating == true) return@mapNotNull null
                val stored = programDao.logForSlot(planId, source.dayId, source.id, name)
                val working = if (stored != null) {
                    val rows = runCatching { SetJson.decodeSets(stored.setsJson) }.getOrNull() ?: return@mapNotNull null
                    val index = WorkingRows.workingIndices(rows).firstOrNull() ?: return@mapNotNull null
                    rows[index].copy(rir = rule?.rir ?: Effort.GOOD.rir)
                } else {
                    if (name != Slot.MAIN) return@mapNotNull null
                    when (val target = source.toDomain().prescription?.target) {
                        is PrescribedTarget.LoadReps -> LoggedSet(target.lb, target.reps, SetKind.WORK,
                            rir = rule?.rir ?: Effort.GOOD.rir)
                        is PrescribedTarget.Hold -> LoggedSet(target.addedLb, 0, SetKind.WORK)
                        else -> return@mapNotNull null
                    }
                }
                working.takeIf { it.weightLb > 0.0 }?.let { it to (stored?.updatedAt ?: Long.MIN_VALUE) }
            }
        }.maxByOrNull { it.second }?.first

    /** Rows a today-only swap opens with; reads only, in the caller's transaction. A track's own exercise first
     *  takes its stored rows. A MAIN substitute uses [swappedTo]'s shape; an SS substitute uses a partner rule. */
    internal suspend fun runSwapRows(row: ProgramExerciseEntity, slot: String, exerciseId: String, mainRows: Int,
                                     unit: WeightUnit, catalog: ExerciseCatalog): List<LoggedSet>? {
        val own = exerciseId == when (slot) {
            Slot.MAIN -> row.exerciseId
            Slot.SS -> row.supersetExerciseId
            else -> return null
        }
        if (own) {
            programDao.logForSlot(row.planId, row.dayId, row.id, slot)?.let { stored ->
                runCatching { SetJson.decodeSets(stored.setsJson) }.getOrNull()?.let { return it }
            }
        }
        val entry = catalog.find(exerciseId)
        if (slot == Slot.SS) return entry?.let {
            val rule = SlotRuleJson.decode(row.partnerRuleJson).takeIf { own }
                ?: PlanConversion.partnerRule(it, planSettings(row.planId).defaultsFor)
            partnerSeed(row.planId, row.id, exerciseId, mainRows, rule, unit, it)
        }?.rows
        val shaped = if (own) row else row.swappedTo(exerciseId)
        if (entry == null && shaped.toDomain().prescription == null) return null
        val rule = (if (own) SlotRuleJson.decode(row.ruleJson) else null)
            ?: PlanConversion.rule(shaped.toDomain(), null, entry, planSettings(row.planId).defaultsFor)
        return opening(row.planId, row.id, shaped.toDomain(), rule, unit, entry).rows
    }

    /** Writes [row]'s partner rule in the caller's transaction (spec W2b-2b decision 2). With [mainRows], a slot that
     *  is not kept to plan and whose partner the catalog knows also gets SS rows from [SlotSeeding] at that count,
     *  and the rule SlotSeeding returns. Otherwise only the rule, its reps from the stored SS rows, else the parked
     *  ones. A slot without a partner is left alone. */
    internal suspend fun rulePartner(row: ProgramExerciseEntity, mainRows: Int?, unit: WeightUnit, catalog: ExerciseCatalog) {
        val partner = row.supersetExerciseId ?: return
        val entry = catalog.find(partner)
        val seeded = if (mainRows != null && !row.keepToPlan && entry != null) {
            partnerSeed(row.planId, row.id, partner, mainRows,
                PlanConversion.partnerRule(entry, planSettings(row.planId).defaultsFor), unit, entry)
        } else null
        if (seeded != null) {
            programDao.upsertLog(ExerciseLogEntity(row.planId, row.dayId, row.id, Slot.SS,
                SetJson.encodeSets(seeded.rows), clock.millis()))
        }
        programDao.setPartnerRule(row.id, SlotRuleJson.encode(seeded?.rule ?:
            PlanConversion.partnerRule(entry, planSettings(row.planId).defaultsFor, storedPartnerRows(row))))
    }

    private suspend fun storedPartnerRows(row: ProgramExerciseEntity): List<LoggedSet>? =
        programDao.logForSlot(row.planId, row.dayId, row.id, Slot.SS)?.let {
            runCatching { SetJson.decodeSets(it.setsJson) }.getOrNull()
        } ?: if (row.keepToPlan) parkedRows(row, Slot.SS, row.supersetExerciseId) else null

    /** [mainRows] straight sets of [exerciseId] under [rule], from [opening]. */
    private suspend fun partnerSeed(planId: Long, slotId: Long, exerciseId: String, mainRows: Int, rule: SlotRule,
                                    unit: WeightUnit, entry: ExerciseEntry): SlotSeeding.Seeded =
        opening(planId, slotId, ProgramExercise(exerciseId, SetStructure.STRAIGHT, mainRows), rule, unit, entry)

    /** Opening rows and rule for [slot]'s [track] when it has no stored row (design §3.1; spec W2b-3a decision 1).
     *  Uses the track's stored rule, else the conversion rule; partners have [mainRows] rows. Reads only Room. */
    internal suspend fun seed(planId: Long, slot: ProgramSlot, track: String, mainRows: Int, unit: WeightUnit,
                              catalog: ExerciseCatalog): SlotSeeding.Seeded? {
        val exercise = slot.exercise
        val goal = planSettings(planId).defaultsFor
        return when (track) {
            Slot.MAIN -> {
                val entry = catalog.find(exercise.exerciseId)
                if (entry == null && exercise.prescription == null) return null
                opening(planId, slot.programExerciseId, exercise,
                    exercise.rule ?: PlanConversion.rule(exercise, null, entry, goal), unit, entry)
            }
            Slot.SS -> {
                val partner = exercise.superset ?: return null
                val entry = catalog.find(partner.exerciseId) ?: return null
                partnerSeed(planId, slot.programExerciseId, partner.exerciseId, mainRows,
                    partner.rule ?: PlanConversion.partnerRule(entry, goal), unit, entry)
            }
            else -> null
        }
    }

    /** [seed] for missing tracks; an unknown, unprescribed main seeds neither track. Partners use the live main
     *  count, else the main seed's count. A fully stored day needs no Room queries on each display emission. */
    internal suspend fun seeds(planId: Long, slots: List<ProgramSlot>, existing: Map<Pair<Long, String>, Int>,
                               unit: WeightUnit, catalog: ExerciseCatalog): Map<Pair<Long, String>, SlotSeeding.Seeded> {
        val seeds = linkedMapOf<Pair<Long, String>, SlotSeeding.Seeded>()
        for (slot in slots) {
            val exercise = slot.exercise
            if (catalog.find(exercise.exerciseId) == null && exercise.prescription == null) continue
            val main = slot.programExerciseId to Slot.MAIN
            val partner = slot.programExerciseId to Slot.SS
            val needsPartner = exercise.superset != null && partner !in existing
            val mainSeed = if (main !in existing) {
                seed(planId, slot, Slot.MAIN, 0, unit, catalog) ?: continue
            } else null
            mainSeed?.let { seeds[main] = it }
            if (needsPartner) {
                val count = existing[main] ?: mainSeed?.rows?.size ?: continue
                seed(planId, slot, Slot.SS, count, unit, catalog)?.let { seeds[partner] = it }
            }
        }
        return seeds
    }

    /** The rule for [slot] with no rows to read: what a structure change or a prescription edit, which delete the
     *  slot's working rows, leave it with. */
    internal suspend fun defaultRule(slot: ProgramExerciseEntity, catalog: ExerciseCatalog): SlotRule =
        PlanConversion.rule(slot.toDomain(), null, catalog.find(slot.exerciseId), planSettings(slot.planId).defaultsFor)

    internal suspend fun planSettings(planId: Long): PlanSettings =
        planDao.plan(planId)?.planSettings ?: PlanSettings()

    private fun parkedRows(slot: ProgramExerciseEntity, name: String, exerciseId: String?): List<LoggedSet>? = runCatching {
        slot.parkedLogsJson?.let(ParkedLogsJson::decode)?.firstOrNull {
            it.slot == name && it.exerciseId == exerciseId
        }?.setsJson?.let(SetJson::decodeSets)
    }.getOrNull()

    /** Gives every unruled slot its rule and a never-opened one its opening rows ([ruleSlot]), and every unruled
     *  partner its rule ([rulePartner]), all in one transaction. Ruled slots are skipped, so running it again changes
     *  nothing. False while a restore is pending. */
    internal suspend fun convertPlans(): Boolean {
        // DataStore is read before the transaction: suspending on it inside one can deadlock.
        val unit = settings.unitFlow.first()
        return db.withTransaction {
            if (restoreMarkerDao.nonce() != null) return@withTransaction false
            val catalog = readableCatalog(customExerciseDao.getAll())
            for (slot in programDao.unruledExercises()) ruleSlot(slot, unit, catalog)
            for (slot in programDao.unruledPartners()) rulePartner(slot, null, unit, catalog)
            true
        }
    }

    internal suspend fun invalidatePlanFixups() {
        planSettingsMigrationMutex.withLock {
            legacyCardioConverted = false
            slotsRuled = false
        }
    }
}

/** The slot as a different exercise arrives in it: straight sets, no prescription, and a ramp's sets become three. */
internal fun ProgramExerciseEntity.swappedTo(exerciseId: String): ProgramExerciseEntity = copy(
    exerciseId = exerciseId, structure = SetStructure.STRAIGHT.name, prescriptionJson = null,
    targetSets = if (toDomain().structure.hasTopSet) 3 else targetSets,
)

internal fun answersForPlan(plan: PlanEntity?, global: GlobalLifter): WizardAnswers =
    plan?.recipeJson?.let { PlanRecipeJson.decode(it, global) }
        ?: WizardAnswers(config = global.toConfig())

internal fun assemble(
    days: List<ProgramDayEntity>,
    exercises: List<ProgramExerciseEntity>,
): Program {
    val byDay = exercises.groupBy { it.dayId }
    return Program(
        days.sortedBy { it.position }.map { day -> day.toDomain(byDay[day.dayId].orEmpty()) },
    )
}

internal fun ProgramDayEntity.freshRunId(mintRunId: () -> String): ProgramDayEntity =
    copy(nextRunId = mintRunId(), closedAt = null)
