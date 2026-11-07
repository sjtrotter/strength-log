package cloud.trotter.log.strength.data

import androidx.room.withTransaction
import cloud.trotter.log.strength.data.db.StrengthDatabase
import cloud.trotter.log.strength.data.db.dao.PlanDao
import cloud.trotter.log.strength.data.db.dao.ProgramDao
import cloud.trotter.log.strength.data.db.dao.SessionDao
import cloud.trotter.log.strength.data.db.entity.PlanEntity
import cloud.trotter.log.strength.data.db.entity.PlanOrigin
import cloud.trotter.log.strength.data.db.entity.ProgramDayEntity
import cloud.trotter.log.strength.data.db.entity.ProgramExerciseEntity
import cloud.trotter.log.strength.data.mapping.toDomain
import cloud.trotter.log.strength.data.mapping.toEntity
import cloud.trotter.log.strength.data.prefs.SettingsStore
import cloud.trotter.log.strength.data.serialization.LegacyCardio
import cloud.trotter.log.strength.data.serialization.CardioBlockJson
import cloud.trotter.log.strength.data.serialization.PlanRecipeJson
import cloud.trotter.log.strength.domain.generator.WizardAnswers
import cloud.trotter.log.strength.domain.model.LifterConfig
import cloud.trotter.log.strength.domain.model.GoalEmphasis
import cloud.trotter.log.strength.domain.model.Program
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

    suspend fun setOfferStrengthIncreases(planId: Long, on: Boolean) = planDao.updateOfferStrengthIncreases(planId, on)

    suspend fun setOfferCardioIncreases(planId: Long, on: Boolean) = planDao.updateOfferCardioIncreases(planId, on)

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
                val nextDay = program.rotationDays.firstOrNull()?.id
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
     *  Also converts legacy cardio to typed blocks. */
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
            if (legacyCardioConverted) return@withLock
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

    internal suspend fun invalidateLegacyCardioConversion() {
        planSettingsMigrationMutex.withLock { legacyCardioConverted = false }
    }
}

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
