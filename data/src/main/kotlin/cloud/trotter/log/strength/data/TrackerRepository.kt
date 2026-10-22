package cloud.trotter.log.strength.data

import androidx.room.withTransaction
import cloud.trotter.log.strength.data.catalog.ExerciseCatalog
import cloud.trotter.log.strength.data.checkmark.CheckmarkReset
import cloud.trotter.log.strength.data.db.StrengthDatabase
import cloud.trotter.log.strength.data.db.dao.CustomExerciseDao
import cloud.trotter.log.strength.data.db.dao.ProgramDao
import cloud.trotter.log.strength.data.db.dao.SessionDao
import cloud.trotter.log.strength.data.db.dao.SessionSummaryRow
import cloud.trotter.log.strength.data.db.dao.SessionTonnageRow
import cloud.trotter.log.strength.data.db.dao.TopSetRow
import cloud.trotter.log.strength.data.db.entity.CustomExerciseEntity
import cloud.trotter.log.strength.data.db.entity.CardioSessionEntity
import cloud.trotter.log.strength.data.db.entity.CardioSetResultEntity
import cloud.trotter.log.strength.data.db.entity.ExerciseLogEntity
import cloud.trotter.log.strength.data.db.entity.PlanEntity
import cloud.trotter.log.strength.data.db.entity.PlanOrigin
import cloud.trotter.log.strength.data.db.entity.ProgramDayEntity
import cloud.trotter.log.strength.data.db.entity.ProgramExerciseEntity
import cloud.trotter.log.strength.data.db.entity.RestoreMarkerEntity
import cloud.trotter.log.strength.data.db.entity.SessionSetEntity
import cloud.trotter.log.strength.data.db.entity.Slot
import cloud.trotter.log.strength.data.db.entity.WorkoutSessionEntity
import cloud.trotter.log.strength.data.mapping.toDomain
import cloud.trotter.log.strength.data.mapping.toDomainOrNull
import cloud.trotter.log.strength.data.mapping.toEntity
import cloud.trotter.log.strength.data.mapping.toEntry
import cloud.trotter.log.strength.data.migration.reinterpretRepsAsSeconds
import cloud.trotter.log.strength.data.prefs.RestoreInterruption
import cloud.trotter.log.strength.data.prefs.RestoreJournal
import cloud.trotter.log.strength.data.prefs.SettingsStore
import cloud.trotter.log.strength.data.serialization.CardioBlockJson
import cloud.trotter.log.strength.data.serialization.PrescriptionJson
import cloud.trotter.log.strength.data.serialization.PlanRecipeJson
import cloud.trotter.log.strength.data.serialization.SetJson
import cloud.trotter.log.strength.domain.generator.Rotation
import cloud.trotter.log.strength.domain.generator.ProgramGenerator
import cloud.trotter.log.strength.domain.generator.WizardAnswers
import cloud.trotter.log.strength.domain.library.GoalSource
import cloud.trotter.log.strength.domain.library.ExerciseLibrary
import cloud.trotter.log.strength.domain.library.TrackingType
import cloud.trotter.log.strength.domain.model.CardioBlock
import cloud.trotter.log.strength.domain.model.CardioActivity
import cloud.trotter.log.strength.domain.model.CardioSetResult
import cloud.trotter.log.strength.domain.model.CardioPrefs
import cloud.trotter.log.strength.domain.model.CardioMode
import cloud.trotter.log.strength.domain.model.Equipment
import cloud.trotter.log.strength.domain.model.LifterConfig
import cloud.trotter.log.strength.domain.model.LoggedSet
import cloud.trotter.log.strength.domain.model.MovementPattern
import cloud.trotter.log.strength.domain.model.Prescription
import cloud.trotter.log.strength.domain.model.PrescribedTarget
import cloud.trotter.log.strength.domain.model.Program
import cloud.trotter.log.strength.domain.model.ProgramExercise
import cloud.trotter.log.strength.domain.model.SetKind
import cloud.trotter.log.strength.domain.standards.RestCategory
import cloud.trotter.log.strength.domain.standards.RestSettings
import cloud.trotter.log.strength.domain.units.WeightUnit
import java.io.IOException
import java.time.Clock
import java.time.LocalDate
import java.util.UUID
import kotlinx.coroutines.NonCancellable
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
import kotlinx.coroutines.withContext

/**
 * The single data-layer entry point (spec §7 surface, extended by PLAN.md
 * A1/A4/A6). Every read is a [Flow] off Room/DataStore; every mutation is a
 * suspend call that commits immediately, so no working truth ever lives only in
 * memory. Cross-table mutations run in one Room transaction.
 * Program and live-log reads and day/position writes act on the selected plan;
 * writes addressed by slot id act on the slot's own plan.
 *
 * "Today" for the daily checkmark reset is the device-local date via [clock]
 * (injectable for tests).
 *
 * `open` only to allow a recording subclass in tests to assert the ordering of
 * cross-store mutations (e.g. wizard finish writes the program before the
 * completion flag); the public surface is unchanged.
 */
@OptIn(ExperimentalCoroutinesApi::class)
open class TrackerRepository(
    private val db: StrengthDatabase,
    private val programDao: ProgramDao,
    private val sessionDao: SessionDao,
    private val customExerciseDao: CustomExerciseDao,
    private val settings: SettingsStore,
    private val clock: Clock = Clock.systemDefaultZone(),
) {

    /** Clock-backed compatibility reading for non-app composition and tests. */
    fun currentDate(): LocalDate = LocalDate.now(clock)

    /** Taken off [db] rather than injected like the other DAOs on purpose: the
     *  restore marker is this class's own crash bookkeeping (#172) — never data
     *  a caller supplies, reads, or would substitute — and threading it through
     *  every construction site would claim otherwise. */
    private val restoreMarkerDao = db.restoreMarkerDao()
    private val cardioSessionDao = db.cardioSessionDao()
    private val cardioSetResultDao = db.cardioSetResultDao()
    private val planDao = db.planDao()
    private val planSettingsMigrationMutex = Mutex()

    /** Serializes plan selection and archive changes across Room and DataStore. */
    private val planSelectionLock = Mutex()

    // --- config & preferences ------------------------------------------------

    val plansFlow: Flow<List<PlanEntity>> = planDao.observePlans()

    /** Distinct, because DataStore emits on every key's write and each emission
     *  here restarts every plan-scoped Room query downstream. */
    val selectedPlanIdFlow: Flow<Long?> =
        combine(settings.selectedPlanIdFlow, plansFlow) { storedId, plans ->
            plans.firstOrNull { it.id == storedId && !it.archived }?.id
                ?: plans.firstOrNull { !it.archived }?.id
        }.distinctUntilChanged()

    val planSummariesFlow: Flow<List<PlanSummary>> = combine(
        plansFlow,
        settings.selectedPlanIdFlow,
        programDao.observeAllDays(),
        sessionDao.lastSessionByPlan(),
    ) { plans, storedId, days, sessions ->
        val selectedId = plans.firstOrNull { it.id == storedId && !it.archived }?.id
            ?: plans.firstOrNull { !it.archived }?.id
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
                lastSessionAt = lastByPlan[plan.id],
            )
        }
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

    private fun answersForPlan(plan: PlanEntity?, global: GlobalLifter): WizardAnswers =
        plan?.takeIf { it.origin == PlanOrigin.GENERATED.name }?.recipeJson?.let { PlanRecipeJson.decode(it, global) }
            ?: WizardAnswers(config = global.toConfig())

    val wizardAnswersFlow: Flow<WizardAnswers> = flow {
        ensurePlanSettingsMigrated()
        emitAll(planAnswersFlow)
    }
    val configFlow: Flow<LifterConfig> = flow {
        ensurePlanSettingsMigrated()
        emitAll(planAnswersFlow.map { it.config }.distinctUntilChanged())
    }
    val cardioPrefsFlow: Flow<CardioPrefs> = flow {
        ensurePlanSettingsMigrated()
        emitAll(planAnswersFlow.map { it.cardio }.distinctUntilChanged())
    }
    val unitFlow: Flow<WeightUnit> = settings.unitFlow
    val restSettingsFlow: Flow<RestSettings> = settings.restSettingsFlow
    val phoneRestTimerEnabledFlow = settings.phoneRestTimerEnabledFlow
    val phoneRestFlow = settings.phoneRestFlow
    val phoneRestNotificationAskedFlow = settings.phoneRestNotificationAskedFlow
    /** The keep-screen-on preference (#125). Collected once, by the activity, so
     *  the wake follows the app rather than whichever screen happens to be
     *  composed — see `MainActivity`. */
    val keepScreenOnFlow: Flow<Boolean> = settings.keepScreenOnFlow
    val topSetHelperSeenFlow: Flow<Boolean> = settings.topSetHelperSeenFlow
    val supersetHelperSeenFlow: Flow<Boolean> = settings.supersetHelperSeenFlow
    val themePreferenceFlow = settings.themePreferenceFlow
    val wizardCompleteFlow: Flow<Boolean> = settings.wizardCompleteFlow

    /** Whether the one-shot Health Connect backfill has run (#159) — the Log
     *  screen's offer hides itself once it has. */
    val healthBackfillDoneFlow: Flow<Boolean> = settings.healthBackfillDoneFlow

    /** The in-progress session's start stamp (session-start capture), or `null`
     *  between an advance/clear and the next performed tick. A stamp whose stored
     *  calendar date isn't today reads as `null`: it belonged to a session
     *  abandoned on an earlier day (ticked, never advanced), and must never be
     *  inherited by today's — the same cross-day staleness rule [CheckmarkReset]
     *  applies to checkmarks, off the one injectable [clock] (SSOT). */
    val sessionStartedAtFlow: Flow<Long?> = settings.sessionStartRawFlow.map { stamp ->
        stamp?.takeIf { it.date == CheckmarkReset.today(clock) }?.startedAtMillis
    }

    fun sessionStartedAtFlow(today: Flow<LocalDate>): Flow<Long?> =
        combine(settings.sessionStartRawFlow, today) { stamp, date ->
            stamp?.takeIf { it.date == date.toString() }?.startedAtMillis
        }

    // Block bodies, not expression form: DataStore's Preferences (SettingsStore's
    // setter return type) must not leak into this public surface via inference —
    // it is a :data-internal detail and consumers don't compile against DataStore.
    /** Updates the global lifter and the selected plan's emphasis. A GENERATED
     *  plan without a recipe starts from [WizardAnswers] defaults with the global
     *  lifter on its first settings write. AUTHORED plans ignore recipe writes
     *  because they have no generator; their settings flows read defaults. */
    suspend fun setConfig(config: LifterConfig) {
        ensurePlanSettingsMigrated()
        val planId = selectedPlanId()
        settings.setGlobalLifter(GlobalLifter.of(config))
        if (planId == null) return
        db.withTransaction {
            val plan = planDao.plan(planId) ?: return@withTransaction
            if (plan.origin != PlanOrigin.GENERATED.name) return@withTransaction
            val answers = plan.recipeJson?.let { PlanRecipeJson.decode(it, GlobalLifter.of(config)) }
                ?: WizardAnswers(config = GlobalLifter.of(config).toConfig())
            planDao.updateRecipe(planId, PlanRecipeJson.encode(answers.copy(config = config)))
        }
    }

    suspend fun setCardioPrefs(prefs: CardioPrefs) {
        ensurePlanSettingsMigrated()
        val planId = selectedPlanId() ?: return
        val global = settings.globalLifterFlow.first()
        db.withTransaction {
            val plan = planDao.plan(planId) ?: return@withTransaction
            if (plan.origin != PlanOrigin.GENERATED.name) return@withTransaction
            val answers = plan.recipeJson?.let { PlanRecipeJson.decode(it, global) }
                ?: WizardAnswers(config = global.toConfig())
            planDao.updateRecipe(planId, PlanRecipeJson.encode(answers.copy(cardio = prefs)))
        }
    }

    suspend fun setUnit(unit: WeightUnit) {
        settings.setUnit(unit)
    }

    /** Flips the keep-screen-on preference (the day screen's bottom-bar switch). */
    suspend fun setKeepScreenOn(on: Boolean) {
        settings.setKeepScreenOn(on)
    }

    suspend fun markTopSetHelperSeen() {
        settings.markTopSetHelperSeen()
    }

    suspend fun markSupersetHelperSeen() {
        settings.markSupersetHelperSeen()
    }

    suspend fun setThemePreference(theme: cloud.trotter.log.strength.domain.theme.ThemePreference) {
        settings.setThemePreference(theme)
    }

    /** Records that the one-shot Health Connect backfill published the history
     *  that predated the grant (#159). Only ever written after a backfill that
     *  reported every session published. */
    suspend fun setHealthBackfillDone() {
        settings.setHealthBackfillDone()
    }

    /** Flips the master rest-timer gate (Setup's "Rest timer on watch" toggle). */
    suspend fun setRestTimerEnabled(enabled: Boolean) {
        settings.setRestTimerEnabled(enabled)
    }

    suspend fun setPhoneRestTimerEnabled(enabled: Boolean) = settings.setPhoneRestTimerEnabled(enabled)
    suspend fun setPhoneRest(rest: cloud.trotter.log.strength.domain.standards.PhoneRest?) = settings.setPhoneRest(rest)
    suspend fun markPhoneRestNotificationAsked() = settings.markPhoneRestNotificationAsked()

    /** Writes one per-category rest override from the Setup editor; clamping to
     *  [cloud.trotter.log.strength.domain.standards.RestPolicy]'s bounds is
     *  [SettingsStore]'s job (SSOT). */
    suspend fun setRestOverride(category: RestCategory, seconds: Int) {
        settings.setRestOverride(category, seconds)
    }

    /** The Setup "RESET DEFAULTS" affordance: reverts every rest category to its
     *  RestPolicy default. */
    suspend fun clearRestOverrides() {
        settings.clearRestOverrides()
    }

    open suspend fun setWizardComplete(complete: Boolean) {
        settings.setWizardComplete(complete)
    }

    /** Persists the wizard inputs so a single day can later be regenerated
     *  ([resetDayToTemplate]) from the same recipe. */
    open suspend fun setWizardAnswers(answers: WizardAnswers) {
        ensurePlanSettingsMigrated()
        settings.setGlobalLifter(GlobalLifter.of(answers.config))
        val planId = ensureSelectedPlan()
        db.withTransaction {
            if (planDao.plan(planId)?.origin == PlanOrigin.GENERATED.name) {
                planDao.updateRecipe(planId, PlanRecipeJson.encode(answers))
            }
        }
    }

    // --- exercise catalog (code + custom overlay) ----------------------------

    val catalogFlow: Flow<ExerciseCatalog> =
        customExerciseDao.observeAll().map { rows -> ExerciseCatalog(rows.map { it.toEntry() }) }

    /**
     * [tracking] selects which of [goalStartLb]/[targetReps]/[targetSeconds] is
     * the live GOAL (mirrors [cloud.trotter.log.strength.data.mapping.toEntry]):
     * WEIGHTED reads [goalStartLb] as the flat starting weight; REPS reads
     * [targetReps]; TIMED reads [targetSeconds] plus [goalStartLb] as any
     * optional added load. Defaulted so every pre-tracking-types call site
     * (weighted-only) keeps compiling unchanged.
     */
    suspend fun addCustomExercise(
        name: String,
        pattern: MovementPattern,
        equipment: List<Equipment>,
        perHand: Boolean,
        goalStartLb: Double,
        tracking: TrackingType = TrackingType.WEIGHTED,
        targetReps: Int? = null,
        targetSeconds: Int? = null,
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
                tracking = tracking.name,
                targetReps = targetReps,
                targetSeconds = targetSeconds,
            ),
        )
        return id
    }

    suspend fun removeCustomExercise(id: String) = customExerciseDao.delete(id)

    // --- program -------------------------------------------------------------

    private suspend fun selectedPlanId(): Long? = selectedPlanIdFlow.first()

    private suspend fun ensureSelectedPlan(): Long = selectedPlanId() ?: run {
        val position = (planDao.allPlans().maxOfOrNull { it.position } ?: -1) + 1
        val id = planDao.insertPlan(
            PlanEntity(
                id = 0,
                name = PlanEntity.LEGACY_NAME,
                origin = PlanOrigin.GENERATED.name,
                position = position,
                createdAt = clock.millis(),
            ),
        )
        settings.setSelectedPlanId(id)
        id
    }

    val programFlow: Flow<Program> =
        selectedPlanIdFlow.flatMapLatest { planId ->
            if (planId == null) {
                flowOf(Program(emptyList()))
            } else {
                programFor(planId)
            }
        }

    /** A plan's program, independent of selection. */
    fun programFor(planId: Long): Flow<Program> = combine(
        programDao.observeDays(planId),
        programDao.observeExercises(planId),
        ::assemble,
    )

    /** Selects an existing active plan without changing any plan's contents. */
    suspend fun selectPlan(id: Long) {
        planSelectionLock.withLock {
            if (planDao.plan(id)?.archived == false) settings.setSelectedPlanId(id)
        }
    }

    /** Commits a generated plan or fills the selected settings-only plan,
     * returning its id without writing DataStore. */
    open suspend fun createGeneratedPlan(name: String, answers: WizardAnswers, program: Program): Long {
        return planSelectionLock.withLock {
            val storedId = settings.selectedPlanIdFlow.first()
            db.withTransaction {
                val plans = planDao.allPlans()
                val selected = plans.firstOrNull { it.id == storedId && !it.archived }
                    ?: plans.firstOrNull { !it.archived }
                val recipe = PlanRecipeJson.encode(answers)
                val nextDay = program.strengthDays.firstOrNull()?.id
                val planId = if (selected != null && programDao.daysForPlan(selected.id).isEmpty()) {
                    planDao.updatePlan(selected.copy(
                        name = name,
                        origin = PlanOrigin.GENERATED.name,
                        recipeJson = recipe,
                        suggestedDayId = nextDay,
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
                    ))
                }
                program.days.forEachIndexed { index, day ->
                    programDao.upsertDay(day.toEntity(planId).copy(position = index))
                    day.exercises.forEachIndexed { position, exercise ->
                        programDao.insertExercise(exercise.toEntity(planId, day.id, position))
                    }
                }
                planId
            }
        }
    }

    /** Idempotently writes the global lifter and selects an existing active plan
     * under the selection lock. */
    open suspend fun publishCreatedPlan(id: Long, answers: WizardAnswers) {
        planSelectionLock.withLock {
            settings.setGlobalLifter(GlobalLifter.of(answers.config))
            if (planDao.plan(id)?.archived == false) settings.setSelectedPlanId(id)
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
        programDao.upsertDay(ProgramDayEntity(id, "A", 0, dayTitle.trim(), "", null))
        id
    }

    suspend fun publishCreatedAuthoredPlan(id: Long) {
        planSelectionLock.withLock {
            val plan = planDao.plan(id)
            if (plan?.origin == PlanOrigin.AUTHORED.name && !plan.archived) settings.setSelectedPlanId(id)
        }
    }

    suspend fun addDay(planId: Long, title: String): String? = db.withTransaction {
        if (planDao.plan(planId)?.origin != PlanOrigin.AUTHORED.name) return@withTransaction null
        val trimmed = title.trim()
        if (trimmed.length !in 1..30) return@withTransaction null
        val days = programDao.daysForPlan(planId)
        if (days.size >= 7) return@withTransaction null
        val letter = ('A'..'G').firstOrNull { candidate -> days.none { it.dayId == candidate.toString() } }
            ?.toString() ?: return@withTransaction null
        programDao.upsertDay(ProgramDayEntity(
            planId, letter, (days.maxOfOrNull { it.position } ?: -1) + 1, trimmed, "", null,
        ))
        letter
    }

    suspend fun renameDay(planId: Long, dayId: String, title: String) {
        db.withTransaction {
            if (planDao.plan(planId)?.origin != PlanOrigin.AUTHORED.name) return@withTransaction
            val trimmed = title.trim()
            if (trimmed.length !in 1..30) return@withTransaction
            val day = programDao.day(planId, dayId) ?: return@withTransaction
            programDao.upsertDay(day.copy(title = trimmed))
        }
    }

    suspend fun removeDay(planId: Long, dayId: String): Boolean = db.withTransaction {
        val plan = planDao.plan(planId) ?: return@withTransaction false
        if (plan.origin != PlanOrigin.AUTHORED.name) return@withTransaction false
        val days = programDao.daysForPlan(planId)
        val removed = days.firstOrNull { it.dayId == dayId } ?: return@withTransaction false
        if (days.size <= 1) return@withTransaction false
        programDao.deleteLogsForDay(planId, dayId)
        programDao.deleteExercisesForDay(planId, dayId)
        programDao.deleteDay(planId, dayId)
        if (plan.suggestedDayId == dayId) {
            val remaining = days.filter { it.dayId != dayId }
            val next = remaining.firstOrNull { it.position > removed.position } ?: remaining.first()
            planDao.updateSuggestedDay(planId, next.dayId)
        }
        true
    }

    /** Authored cardio is stored on its owning day; null removes it if an exercise remains. */
    suspend fun setCardioBlock(planId: Long, dayId: String, block: CardioBlock?): Boolean =
        db.withTransaction {
            if (planDao.plan(planId)?.origin != PlanOrigin.AUTHORED.name) return@withTransaction false
            val day = programDao.day(planId, dayId) ?: return@withTransaction false
            if (block == null && programDao.exercisesForDay(planId, dayId).isEmpty()) return@withTransaction false
            // encode reconstructs the domain objects, enforcing all their requires.
            val json = block?.let(CardioBlockJson::encode)
            programDao.upsertDay(day.copy(cardioBlockJson = json))
            true
        }

    /** Sets belong to the slot, never the prescription JSON. Null keeps its current count. */
    suspend fun setPrescription(programExerciseId: Long, prescription: Prescription, sets: Int? = null) {
        db.withTransaction {
            val row = programDao.exercise(programExerciseId) ?: return@withTransaction
            if (planDao.plan(row.planId)?.origin != PlanOrigin.AUTHORED.name) return@withTransaction
            val count = sets ?: row.targetSets
            require(count in 1..20)
            programDao.setPrescription(row.id, PrescriptionJson.encode(prescription), count)
            programDao.deleteLogsForExercise(row.planId, row.dayId, row.id)
        }
    }

    /** Slot-owned ordering, independent of whichever plan is selected now. */
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
            createdAt = clock.millis(),
        ))
        programDao.upsertDays(programDao.daysForPlan(id).map { it.copy(planId = copyId) })
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
        val selectedId = active.firstOrNull { it.id == storedId }?.id ?: active.firstOrNull()?.id
        if (id == selectedId || active.size <= 1 || active.none { it.id == id }) return false
        planDao.updateArchived(id, true)
        return true
    }

    /** Restores an archived plan without selecting it or changing its contents. */
    suspend fun restorePlan(id: Long) {
        planSelectionLock.withLock {
            db.withTransaction { planDao.updateArchived(id, false) }
        }
    }

    /** Full replace with the wizard's generated program (spec §7). Also points the
     *  rotation at the first day. Destructive for the selected plan only: its old
     *  program and live logs are cleared (history in `workout_session` is untouched). */
    open suspend fun replaceProgram(program: Program) {
        ensurePlanSettingsMigrated()
        val planId = ensureSelectedPlan()
        db.withTransaction {
            programDao.deleteLogsForPlan(planId)
            programDao.deleteExercisesForPlan(planId)
            programDao.deleteDaysForPlan(planId)
            program.days.forEachIndexed { index, day ->
                programDao.upsertDay(day.toEntity(planId).copy(position = index))
                day.exercises.forEachIndexed { pos, pe ->
                    programDao.insertExercise(pe.toEntity(planId, day.id, pos))
                }
            }
            program.strengthDays.firstOrNull()?.let { planDao.updateSuggestedDay(planId, it.id) }
        }
    }

    /** Swaps the exercise in a slot (spec §8.3). Keeps the slot's stable id but
     *  clears its live log so the new exercise reseeds from its own GOAL; prior
     *  performance survives in `workout_session` history.
     *  The selected plan locates the slot, and the slot names its plan. */
    suspend fun swapExercise(dayId: String, position: Int, newExerciseId: String) {
        val planId = selectedPlanId() ?: return
        db.withTransaction {
            val row = programDao.exerciseAt(planId, dayId, position) ?: return@withTransaction
            swapInPlan(row.planId, row.dayId, row.id, newExerciseId)
        }
    }

    /** The same §8.3 swap, naming the slot by its stable row id instead of its
     *  position — which is how the watch knows it (`programExerciseId` is the only
     *  slot identity on the wire). One mutation body, two ways to address it.
     *  The slot names its plan, and a missing slot or mismatched day is a no-op. */
    suspend fun swapExerciseById(dayId: String, programExerciseId: Long, newExerciseId: String) {
        db.withTransaction {
            val row = programDao.exercise(programExerciseId) ?: return@withTransaction
            if (row.dayId != dayId) return@withTransaction
            swapInPlan(row.planId, row.dayId, row.id, newExerciseId)
        }
    }

    private suspend fun swapInPlan(
        planId: Long,
        dayId: String,
        programExerciseId: Long,
        newExerciseId: String,
    ) {
        programDao.setExerciseId(programExerciseId, newExerciseId)
        programDao.deleteLogsForExercise(planId, dayId, programExerciseId)
    }

    /** Appends an exercise to the end of a day (spec §8.3). */
    suspend fun addExercise(dayId: String, exercise: ProgramExercise) {
        val planId = selectedPlanId() ?: return
        db.withTransaction {
            val position = programDao.maxPosition(planId, dayId) + 1
            val authored = planDao.plan(planId)?.origin == PlanOrigin.AUTHORED.name
            val slot = if (authored) {
                if (programDao.day(planId, dayId) == null) return@withTransaction
                val entry = ExerciseCatalog(customExerciseDao.getAll().map { it.toEntry() }).find(exercise.exerciseId)
                    ?: return@withTransaction
                val target = when (val goal = entry.goal) {
                    is GoalSource.Reps -> PrescribedTarget.Reps(goal.targetReps)
                    is GoalSource.Time -> PrescribedTarget.Hold(goal.targetSeconds, goal.addedWeightLb)
                    is GoalSource.Flat -> PrescribedTarget.LoadReps(goal.weightLb, 10)
                    is GoalSource.Std, is GoalSource.FracOfStd -> PrescribedTarget.LoadReps(0.0, 10)
                }
                exercise.copy(targetSets = 3, prescription = Prescription(target))
            } else exercise
            programDao.insertExercise(slot.toEntity(planId, dayId, position))
        }
    }

    /** Removes a slot and its live log (spec §8.3), enforcing its own plan's
     *  day floor in the same transaction. Selection changes cannot retarget it. */
    suspend fun removeExercise(programExerciseId: Long): Boolean =
        db.withTransaction {
            val row = programDao.exercise(programExerciseId) ?: return@withTransaction false
            val plan = planDao.plan(row.planId) ?: return@withTransaction false
            val origin = PlanOrigin.entries.firstOrNull { it.name == plan.origin } ?: PlanOrigin.GENERATED
            val count = programDao.exercisesForDay(row.planId, row.dayId).size
            val hasCardio = origin == PlanOrigin.AUTHORED &&
                CardioBlockJson.decode(
                    programDao.day(row.planId, row.dayId)?.cardioBlockJson,
                ) != null
            val floor = if (hasCardio) 0 else minExercisesPerDay(origin)
            if (count <= floor) return@withTransaction false
            programDao.deleteLogsForExercise(row.planId, row.dayId, row.id)
            programDao.deleteExercise(row.id)
            true
        }

    suspend fun setExerciseNote(programExerciseId: Long, text: String) {
        programDao.setExerciseNote(programExerciseId, text.trim().take(120))
    }

    // TODO(schema): Store this on workout_session when the coordinated Room
    // schema bump lands; DataStore is the migration-free local-first bridge.
    val sessionNotesFlow: Flow<Map<Long, String>> = settings.sessionNotesFlow

    fun sessionNoteFlow(sessionId: Long): Flow<String> = settings.sessionNoteFlow(sessionId)

    suspend fun setSessionNote(sessionId: Long, text: String) {
        settings.setSessionNote(sessionId, text)
    }

    /**
     * Attaches [partnerExerciseId] to the slot at [position] as its superset
     * partner (#93) — the same call covers adding a partner to a plain slot and
     * swapping an existing one. Prescribed slots refuse either change.
     *
     * Only the SS track is cleared, so the new partner reseeds from its own GOAL
     * on the next observation while the slot's MAIN track — the lifter's living
     * record, possibly with weeks of edits and extra sets on it — survives
     * untouched. Deliberately NOT [ProgramDao.deleteLogsForExercise], which would
     * nuke both tracks.
     */
    suspend fun setSupersetPartner(dayId: String, position: Int, partnerExerciseId: String) {
        val planId = selectedPlanId() ?: return
        db.withTransaction {
            val row = programDao.exerciseAt(planId, dayId, position) ?: return@withTransaction
            if (row.toDomain().prescription != null) return@withTransaction
            programDao.setSupersetExerciseId(row.id, partnerExerciseId)
            programDao.deleteLogForSlot(planId, dayId, row.id, Slot.SS)
        }
    }

    /** Drops the slot's superset partner and its SS track (#93), leaving the MAIN
     *  track alone for the same reason [setSupersetPartner] does. */
    suspend fun removeSupersetPartner(dayId: String, position: Int) {
        val planId = selectedPlanId() ?: return
        db.withTransaction {
            val row = programDao.exerciseAt(planId, dayId, position) ?: return@withTransaction
            programDao.setSupersetExerciseId(row.id, null)
            programDao.deleteLogForSlot(planId, dayId, row.id, Slot.SS)
        }
    }

    /** Regenerates one day from the stored wizard answers (spec §8.3), leaving the
     *  other days untouched. No-op if answers regenerate no such day. */
    suspend fun resetDayToTemplate(dayId: String) {
        ensurePlanSettingsMigrated()
        val planId = selectedPlanId() ?: return
        val answers = wizardAnswersFlow.first()
        val regenerated = ProgramGenerator.generate(answers).program
        val newDay = regenerated.days.firstOrNull { it.id == dayId } ?: return
        val position = programDao.day(planId, dayId)?.position
            ?: regenerated.days.indexOfFirst { it.id == dayId }
        db.withTransaction {
            programDao.deleteLogsForDay(planId, dayId)
            programDao.deleteExercisesForDay(planId, dayId)
            programDao.upsertDay(newDay.toEntity(planId).copy(position = position))
            newDay.exercises.forEachIndexed { pos, pe ->
                programDao.insertExercise(pe.toEntity(planId, dayId, pos))
            }
        }
    }

    // --- one-shot legacy fixups ----------------------------------------------

    /**
     * One-shot reps→seconds carry for live logs of entries reclassified to TIMED
     * (tracking-types P3, Decision 5). Before the update those exercises (plank,
     * hollow hold, weighted plank, suitcase carry) were tracked with the only
     * field the UI offered — reps — so a timed hold was recorded there; this moves
     * it into `seconds` and zeroes reps for exactly those slots. It never touches
     * any other exercise and never deletes a row. Prescribed main tracks keep
     * their authored meaning even when the catalog classifies them as TIMED.
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

    /** Moves pre-#268 plan preferences to Room before atomically retiring their keys.
     *  Recipe-less generated plans make a retry after the Room commit harmless. */
    suspend fun runPlanSettingsFixupIfNeeded() {
        ensurePlanSettingsMigrated()
    }

    /** A half-done restore's settings are not this device's to copy; reconcile owns them. */
    private suspend fun ensurePlanSettingsMigrated() {
        planSettingsMigrationMutex.withLock {
            if (restoreMarkerDao.nonce() != null) return@withLock
            if (settings.planSettingsFixupDoneFlow.first()) return@withLock
            val legacy = settings.legacyAnswersFlow.first()
            val suggestedDay = settings.legacySuggestedDayFlow.first()
            if (legacy != null) {
                val storedId = settings.selectedPlanIdFlow.first()
                db.withTransaction {
                    val recipe = PlanRecipeJson.encode(legacy)
                    val plans = planDao.allPlans()
                    plans.filter { it.origin == PlanOrigin.GENERATED.name && it.recipeJson == null }
                        .forEach { planDao.updateRecipe(it.id, recipe) }
                    val selected = plans.firstOrNull { it.id == storedId && !it.archived }
                        ?: plans.firstOrNull { !it.archived }
                    if (selected != null && selected.suggestedDayId == null) {
                        planDao.updateSuggestedDay(selected.id, suggestedDay)
                    }
                }
            }
            settings.removeLegacyPlanKeys()
        }
    }

    // --- live logs -----------------------------------------------------------

    /**
     * The day's exercise slots with their stable row ids, in program order. The UI
     * needs the id to key and seed each slot's log ([logFlow]/[updateSets]); the
     * pure-domain [Program] from [programFlow] deliberately doesn't carry it.
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

    /** The day's live logs with the daily checkmark reset applied (spec §7). */
    fun logFlow(dayId: String): Flow<List<LoggedSlot>> =
        selectedPlanIdFlow.flatMapLatest { planId ->
            if (planId == null) flowOf(emptyList())
            else programDao.observeLogs(planId, dayId).map { rows ->
                val today = CheckmarkReset.today(clock)
                rows.map { it.toLoggedSlot(today) }
            }
        }

    /** Reprojects on a civil-day tick even when Room has not emitted. */
    fun logFlow(dayId: String, today: Flow<LocalDate>): Flow<List<LoggedSlot>> =
        selectedPlanIdFlow.flatMapLatest { planId ->
            if (planId == null) flowOf(emptyList())
            else logsFor(planId, dayId, today)
        }

    /** A plan's live logs with the same civil-day reset as [logFlow]. */
    fun logsFor(planId: Long, dayId: String, today: Flow<LocalDate>): Flow<List<LoggedSlot>> =
        combine(programDao.observeLogs(planId, dayId), today) { rows, date ->
            rows.map { it.toLoggedSlot(date.toString()) }
        }

    /** Persists a slot's set track immediately (spec §7). The write stamps today's
     *  date, so the `done` flags it carries are "today's" checks.
     *  The slot names its plan, and a missing slot or mismatched day is a no-op. */
    suspend fun updateSets(dayId: String, programExerciseId: Long, slot: String, sets: List<LoggedSet>) {
        db.withTransaction {
            val row = programDao.exercise(programExerciseId) ?: return@withTransaction
            if (row.dayId != dayId) return@withTransaction
            programDao.upsertLog(logEntity(row.planId, row.dayId, row.id, slot, sets))
        }
    }

    /**
     * Seeds a track **only if it is still unseeded when the write happens**, and
     * reports whether it did. The one way a seed reaches the database.
     * The slot names its plan, and a missing slot or mismatched day returns false.
     *
     * Seeding is decided from a read ("this slot has no track") and acted on later,
     * and there are now two seeders — the day ViewModel's lazy pass and the watch
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
    suspend fun seedIfEmpty(
        dayId: String,
        programExerciseId: Long,
        slot: String,
        sets: List<LoggedSet>,
    ): Boolean {
        return db.withTransaction {
            val row = programDao.exercise(programExerciseId) ?: return@withTransaction false
            if (row.dayId != dayId) return@withTransaction false
            if (programDao.logForSlot(row.planId, row.dayId, row.id, slot) != null) return@withTransaction false
            programDao.upsertLog(logEntity(row.planId, row.dayId, row.id, slot, sets))
            true
        }
    }

    /**
     * Persists a superset slot's two tracks in one transaction (spec §4/§8.2:
     * rounds stay aligned row-for-row). Paired mutations must never be two
     * separate writes — process death between them would misalign the tracks
     * permanently, and the misalignment would flow into A1 session history.
     * The slot names its plan, and a missing slot or mismatched day is a no-op.
     */
    suspend fun updateSetsPaired(
        dayId: String,
        programExerciseId: Long,
        mainSets: List<LoggedSet>,
        ssSets: List<LoggedSet>,
    ) {
        db.withTransaction {
            val row = programDao.exercise(programExerciseId) ?: return@withTransaction
            if (row.dayId != dayId) return@withTransaction
            programDao.upsertLog(logEntity(row.planId, row.dayId, row.id, Slot.MAIN, mainSets))
            programDao.upsertLog(logEntity(row.planId, row.dayId, row.id, Slot.SS, ssSets))
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
            checkDate = CheckmarkReset.today(clock),
            updatedAt = clock.millis(),
        )

    /**
     * Clears today's checkmarks for one day without advancing the rotation (spec
     * §8.2 footer "clear today's checkmarks"). Invalidates each log's checkDate so
     * the daily-reset rule surfaces every set as unchecked; weights and reps stay.
     * Also clears the session-start stamp (restart semantics, session-start
     * capture): the next tick starts timing a fresh session.
     */
    suspend fun clearChecks(dayId: String) {
        val planId = selectedPlanId() ?: return
        programDao.clearChecksForDay(planId, dayId)
        settings.clearSessionStartedAt()
    }

    /**
     * Stamps "now" as the in-progress session's start — a no-op if already
     * stamped *today* since the last advance/clear (session-start capture); a
     * stamp left over from a previous calendar day is overwritten, not kept.
     * Ticking a set is performing, not planning, so this is called from the day
     * screen's first done=true tick and from the watch delta applier's first
     * done=true apply; both go through this one helper so "session start" means
     * the same thing regardless of which device performed the first set. "Today"
     * is [CheckmarkReset]'s device-local date off [clock] — the one date basis
     * the daily reset already uses.
     */
    suspend fun stampSessionStartIfUnset() =
        settings.stampSessionStartIfUnset(clock.millis(), CheckmarkReset.today(clock))

    // --- rotation & session history ------------------------------------------

    val suggestedDayFlow: Flow<String?> = flow {
        ensurePlanSettingsMigrated()
        emitAll(selectedPlanStateFlow.map { it?.suggestedDayId }.distinctUntilChanged())
    }

    val sessionsFlow: Flow<List<WorkoutSessionEntity>> = sessionDao.observeSessions()

    /**
     * Append-only cardio history, newest completion first. Labels are valid only
     * when non-blank and at most [CARDIO_LABEL_MAX_LENGTH] characters. A mode is
     * persisted as [CardioMode.name]; readers must use [decodedCardioMode], whose
     * nullable decode keeps an unknown future name readable without pretending
     * it is a known activity mode.
     */
    val cardioSessionsFlow: Flow<List<CardioSessionEntity>> = cardioSessionDao.observeSessions()

    val selectedPlanCardioSessionsFlow: Flow<List<CardioSessionEntity>> =
        combine(selectedPlanIdFlow, cardioSessionsFlow) { planId, sessions ->
            sessions.filter { planId != null && it.planId == planId }
        }

    /** Latest workout or cardio completion; [PlanEntity.LEGACY_ID] also counts null-plan history. */
    val selectedPlanLastSessionAtFlow: Flow<Long?> =
        selectedPlanIdFlow.flatMapLatest { planId ->
            if (planId == null) flowOf(null) else lastSessionAtFor(planId)
        }.distinctUntilChanged()

    /** A fixed plan's latest workout or cardio completion, including legacy history. */
    fun lastSessionAtFor(planId: Long): Flow<Long?> =
        combine(sessionsFlow, cardioSessionsFlow) { sessions, cardio ->
            listOfNotNull(
                sessions.filter { belongsToPlan(it.planId, planId) }.maxOfOrNull { it.completedAt },
                cardio.filter { belongsToPlan(it.planId, planId) }.maxOfOrNull { it.completedAt },
            ).maxOrNull()
        }.distinctUntilChanged()

    private fun belongsToPlan(sessionPlanId: Long?, planId: Long?): Boolean =
        planId != null &&
            (sessionPlanId == planId || (planId == PlanEntity.LEGACY_ID && sessionPlanId == null))

    /**
     * Commits one completed cardio mutation immediately. The transaction is
     * deliberately explicit even though C1 writes one row: this is the single
     * write boundary future paired effects must join, never a UI-held staging
     * value. Labels are non-blank and capped at 80 characters; modes are stored
     * by enum name and decoded with [decodedCardioMode] on read. An explicit
     * plan id is preserved and its current name copied when the plan exists;
     * only a session without a plan uses the selected plan.
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
        val ownerId = session.planId ?: selectedPlanId()
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
            // A late or second-device completion still logs, but cannot move a newer cursor.
            if (finishDay && plan != null && stamped.dayId != null && plan.suggestedDayId == stamped.dayId) {
                advanceRotation(plan, stamped.dayId)
            }
            id
        }
    }

    /** Room only: both the phone commit and strength DONE use the originating plan's rotation. */
    private suspend fun advanceRotation(plan: PlanEntity, dayId: String) {
        val program = assemble(programDao.daysForPlan(plan.id), programDao.exercisesForPlan(plan.id))
        Rotation.nextFor(program, dayId, authored = plan.origin == PlanOrigin.AUTHORED.name)?.let {
            planDao.updateSuggestedDay(plan.id, it)
        }
    }

    suspend fun amendCardioSetDistance(sessionId: Long, position: Int, metres: Int?) {
        require(metres == null || metres > 0)
        db.withTransaction {
            require(cardioSetResultDao.forSession(sessionId).any { it.position == position })
            cardioSetResultDao.amendDistance(sessionId, position, metres, metres?.let { "MANUAL" })
        }
    }

    suspend fun cardioSession(id: Long): CardioSessionEntity? = cardioSessionDao.byId(id)

    val cardioSetResultsFlow: Flow<Map<Long, List<CardioSetResult>>> =
        cardioSetResultDao.observeAll().map { rows ->
            rows.groupBy { it.cardioSessionId }.mapValues { (_, sets) -> sets.mapNotNull { it.toDomainOrNull() } }
        }.distinctUntilChanged()

    suspend fun cardioSetsFor(sessionId: Long): List<CardioSetResult> =
        cardioSetResultDao.forSession(sessionId).mapNotNull { it.toDomainOrNull() }

    /** Exact rows for undo, including original ids, order and stored specs. */
    data class DeletedCardioSession(
        val session: CardioSessionEntity,
        val sets: List<CardioSetResultEntity>,
    )

    suspend fun deleteCardioSession(sessionId: Long): DeletedCardioSession? = db.withTransaction {
        val session = cardioSessionDao.byId(sessionId) ?: return@withTransaction null
        val sets = cardioSetResultDao.forSession(sessionId)
        cardioSetResultDao.deleteForSession(sessionId)
        cardioSessionDao.deleteById(sessionId)
        DeletedCardioSession(session, sets)
    }

    suspend fun restoreCardioSession(deleted: DeletedCardioSession) {
        db.withTransaction {
            cardioSessionDao.insert(deleted.session)
            if (deleted.sets.isNotEmpty()) cardioSetResultDao.insertAll(deleted.sets)
        }
    }

    /** The Log screen's list (#14): every session newest-first, with its total
     *  set count pre-aggregated (no per-row query as history grows). */
    val sessionSummariesFlow: Flow<List<SessionSummaryRow>> = sessionDao.observeSessionSummaries()

    /** Selected plan's workout history, including pre-plans history for the migrated plan. */
    val selectedPlanSessionSummariesFlow: Flow<List<SessionSummaryRow>> =
        selectedPlanIdFlow.flatMapLatest { planId ->
            if (planId == null) flowOf(emptyList()) else sessionSummariesFor(planId)
        }

    /** A fixed plan's workout history, including pre-plans history for the migrated plan. */
    fun sessionSummariesFor(planId: Long): Flow<List<SessionSummaryRow>> =
        sessionSummariesFlow.map { sessions ->
            sessions.filter { belongsToPlan(it.session.planId, planId) }
        }

    /** Every session's heaviest completed TOP set per lift, oldest first — the
     *  journal's trajectory series and the cascade ceremony's before-picture. */
    val topSetHistoryFlow: Flow<List<TopSetRow>> = sessionDao.observeTopSets(SetKind.TOP.name)

    /** Completed tonnage per session, oldest first — the journal's weekly bars. */
    val sessionTonnageFlow: Flow<List<SessionTonnageRow>> = sessionDao.observeSessionTonnage()

    /** One session's sets, fetched on demand when the Log screen expands a row
     *  (#14) — not part of [sessionSummariesFlow] because most rows stay collapsed. */
    suspend fun sessionSets(sessionId: Long): List<SessionSetEntity> = sessionDao.setsForSession(sessionId)

    /** One session header by id (#17): the Health Connect publish path pairs this
     *  with [sessionSets] to build the exported record. */
    suspend fun session(sessionId: Long): WorkoutSessionEntity? = sessionDao.sessionById(sessionId)

    /** Exact rows removed by [deleteSession], retained briefly by the Log screen
     *  so undo can restore their primary keys and denormalized history verbatim. */
    data class DeletedSession(
        val session: WorkoutSessionEntity,
        val sets: List<SessionSetEntity>,
    )

    /**
     * Corrects one archived set immediately. History edits only change recorded
     * ACTUALs; they never retroactively fire or undo a GOAL cascade.
     */
    suspend fun updateSessionSet(set: SessionSetEntity) {
        require(sessionDao.updateSet(set) == 1) { "Unknown session set ${set.id}" }
    }

    /** Deletes history only; in particular, the DataStore rotation pointer is untouched. */
    suspend fun deleteSession(sessionId: Long): DeletedSession? = db.withTransaction {
        val session = sessionDao.sessionById(sessionId) ?: return@withTransaction null
        val sets = sessionDao.setsForSession(sessionId)
        sessionDao.deleteSetsForSession(sessionId)
        sessionDao.deleteSessionById(sessionId)
        DeletedSession(session, sets)
    }

    /** Restores a just-deleted session exactly, including every original row id. */
    suspend fun restoreSession(deleted: DeletedSession) {
        db.withTransaction {
            sessionDao.insertSessions(listOf(deleted.session))
            if (deleted.sets.isNotEmpty()) sessionDao.insertSets(deleted.sets)
        }
    }

    /**
     * Batches the A1 "last time" chip for a whole day into one query (#14):
     * [exerciseIds]' most recent completed performance, keyed by exercise id. An
     * id with no history is simply absent from the result.
     *
     * `open` for the same reason the wizard writes are: a test double slows this
     * read down to pin what the day screen shows while it waits (#127).
     */
    open suspend fun lastPerformed(exerciseIds: List<String>): Map<String, LastPerformed> {
        if (exerciseIds.isEmpty()) return emptyMap()
        return sessionDao.lastPerformedRows(exerciseIds).toLastPerformedByExercise()
    }

    /**
     * Batches the day-card "Best" chip for a whole day into one query
     * (performance-profile.md Phase 1): [exerciseIds]' all-time heaviest
     * completed performance, keyed by exercise id. An id with no history is
     * simply absent from the result. Purely derived from session history —
     * never stored, never touches GOAL math.
     */
    suspend fun personalRecords(exerciseIds: List<String>): Map<String, PersonalRecord> {
        if (exerciseIds.isEmpty()) return emptyMap()
        return sessionDao.personalRecordRows(exerciseIds).toPersonalRecordsByExercise()
    }

    /**
     * "DONE — advance" (spec §7, PLAN.md A1): appends a session record
     * for the completed day (denormalizing exercise names so history survives
     * later edits/deletions), clears that day's checkmarks, and advances the
     * rotation pointer through authored days, or strength days for generated plans (§6.4).
     *
     * Authored live logs are deleted after archiving so the next run reseeds from its prescription.
     *
     * Takes the completed day id because the user may have completed a manually
     * overridden day, not the suggested one; the spec's bare `advanceDay()` can't
     * express that.
     *
     * Returns the id of the session row it just appended, so the caller can hand
     * it to a [SessionPublisher] (#17, D7 trigger point) without re-querying for
     * "the latest session" and racing a second completion.
     *
     * Session-start stamp: the in-progress-session start lives in DataStore, not
     * Room ([stampSessionStartIfUnset]), so consuming it here spans two stores
     * with no shared transaction. The stamp is read *before* the Room write and
     * cleared *after* it commits — a crash before the read changes nothing; a
     * crash between the read and the commit leaves the stamp for a retry to
     * consume; a crash after commit but before the clear leaves a stale stamp
     * behind. None of those tears the just-written session or set rows. A stale
     * stamp is contained on two fronts: the date-scoped read ([sessionStartedAtFlow])
     * drops it outright once the calendar day turns over, and even within the
     * same day both `:transfer` exports (HC calories, CSV Duration) refuse a
     * span outside a sane 5 min–6 h window — so the worst case is a session
     * whose `startedAt` is simply absent, never a garbage duration.
     */
    suspend fun advanceDay(completedDayId: String, today: LocalDate? = null): Long {
        ensurePlanSettingsMigrated()
        val planId = selectedPlanId() ?: return 0
        val plan = planDao.plan(planId) ?: return 0
        val bodyweight = configFlow.first().bodyweightLb
        // Date-scoped read (see [sessionStartedAtFlow]): a stamp from a day the
        // user ticked but never advanced reads as null here, so the completed
        // session records no start rather than inheriting the abandoned one.
        val sessionStartedAt = if (today == null) {
            sessionStartedAtFlow.first()
        } else {
            settings.sessionStartRawFlow.first()
                ?.takeIf { it.date == today.toString() }
                ?.startedAtMillis
        }
        val catalog = ExerciseCatalog(customExerciseDao.getAll().map { it.toEntry() })
        var newSessionId = 0L
        db.withTransaction {
            val days = programDao.daysForPlan(plan.id)
            val exercises = programDao.exercisesForPlan(plan.id)
            val dayTitle = days.firstOrNull { it.dayId == completedDayId }?.title ?: completedDayId
            val slotsById = exercises.filter { it.dayId == completedDayId }.associateBy { it.id }
            val logs = programDao.logsForDay(plan.id, completedDayId)

            val sessionId = sessionDao.insertSession(
                WorkoutSessionEntity(
                    id = 0,
                    dayId = completedDayId,
                    dayTitle = dayTitle,
                    startedAt = sessionStartedAt,
                    completedAt = clock.millis(),
                    bodyweightLb = bodyweight,
                    planId = plan.id,
                    planName = plan.name,
                ),
            )
            newSessionId = sessionId
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
                        seconds = s.seconds,
                        startedAtMillis = s.startedAtMillis,
                        completedAtMillis = s.completedAtMillis,
                    )
                }
            }
            if (sessionSets.isNotEmpty()) sessionDao.insertSets(sessionSets)

            programDao.clearChecksForDay(plan.id, completedDayId)
            slotsById.values.filter { it.toDomain().prescription != null }.forEach {
                programDao.deleteLogsForExercise(plan.id, completedDayId, it.id)
            }

            advanceRotation(plan, completedDayId)
        }
        // Consumed: cleared only once the session row above is durably committed
        // (see the crash-ordering note on this method).
        settings.clearSessionStartedAt()
        return newSessionId
    }

    // --- full backup (A2) ----------------------------------------------------

    /**
     * Reads everything the user owns into one [FullSnapshot] for the A2 backup.
     * Every list comes from a query with an explicit `ORDER BY`, so the output is
     * deterministic — two exports of the same state are byte-identical, keeping
     * diffs and round-trip tests honest.
     */
    suspend fun exportSnapshot(): FullSnapshot {
        ensurePlanSettingsMigrated()
        // DataStore and Room have independent commits. Read preferences first;
        // never collect a DataStore-backed flow while holding a Room transaction.
        val global = settings.globalLifterFlow.first()
        val unit = settings.unitFlow.first()
        val wizardComplete = settings.wizardCompleteFlow.first()
        val selectedPlanId = settings.selectedPlanIdFlow.first()
        val restSettings = settings.restSettingsFlow.first()
        val keepScreenOn = settings.keepScreenOnFlow.first()
        val topSetHelperSeen = settings.topSetHelperSeenFlow.first()
        val supersetHelperSeen = settings.supersetHelperSeenFlow.first()
        val sessionNotes = settings.sessionNotesFlow.first()
        val themePreference = settings.themePreferenceFlow.first()
        return db.withTransaction {
            // Use this same plan list for the legacy settings projection, too.
            // Separate reads could otherwise straddle a restore's Room commit.
            val plans = planDao.allPlans()
            val selectedPlan = plans.firstOrNull { it.id == selectedPlanId && !it.archived }
                ?: plans.firstOrNull { !it.archived }
            FullSnapshot(
                answers = answersForPlan(selectedPlan, global),
                answersCarryRecipe = false,
                unit = unit,
                wizardComplete = wizardComplete,
                suggestedDay = selectedPlan?.suggestedDayId,
                selectedPlanId = selectedPlanId,
                restSettings = restSettings,
                keepScreenOn = keepScreenOn,
                topSetHelperSeen = topSetHelperSeen,
                supersetHelperSeen = supersetHelperSeen,
                sessionNotes = sessionNotes,
                themePreference = themePreference,
                plans = plans,
                customExercises = customExerciseDao.allOrdered(),
                days = programDao.allDays(),
                exercises = programDao.allExercises(),
                logs = programDao.allLogs(),
                sessions = sessionDao.allSessions(),
                sessionSets = sessionDao.allSessionSets(),
                cardioSessions = cardioSessionDao.all(),
                cardioSetResults = cardioSetResultDao.all(),
            )
        }
    }

    /**
     * Replaces the device's entire state with [snapshot] (A2 restore). The caller
     * (`:transfer`) has already validated the backup end-to-end; this method does
     * no validation and performs an unconditional destructive replace.
     *
     * Two independent stores, no shared transaction. Room and DataStore each
     * commit atomically on their own; nothing spans both. So rather than claim an
     * atomicity it can't have, this method makes the pair *recoverable* (#172):
     *
     *  1. [journal] stages the settings half under a fresh nonce, before
     *     anything is destroyed.
     *  2. The destructive Room transaction writes that same nonce into
     *     `restore_marker` — *inside* the transaction, so the marker exists if
     *     and only if the data half committed. That is the whole point: a flag
     *     written after the transaction can be lost in the gap between two
     *     durable commits, and losing it would discard the only copy of the
     *     settings half.
     *  3. The settings write lands, then the journal and the marker both clear.
     *
     * Interrupted anywhere, the next launch reads the marker, matches it against
     * the staged nonce, and either finishes the settings half or discards a
     * payload whose transaction never committed ([reconcilePendingRestore]).
     * The interim state is not benign and is not treated as such — restored
     * training data paired with the old device's config means every derived GOAL
     * is wrong until the replay happens.
     *
     * Room goes before the settings write for the reason it always did: the
     * only cross-store pointer left is `selectedPlanId` pointing at a plan,
     * and the reverse order could publish a pointer into a program that does
     * not exist yet. The tail is [NonCancellable]
     * so a cancelled caller still *finishes* here instead of deferring to the next
     * launch — belt to the marker's braces, not the correctness argument. The
     * transaction itself stays cancellable: rolling it back leaves the device
     * untouched, which is a clean outcome.
     *
     * Failures are reported by phase ([RestoreInterruption]) rather than as the
     * raw [IOException] the UI would report as a problem reading the file.
     */
    suspend fun importSnapshot(snapshot: FullSnapshot, journal: RestoreJournal) {
        val nonce = UUID.randomUUID().toString()
        val legacyPlanCreated = snapshot.plans.isEmpty() && snapshot.answersCarryRecipe
        val restoredSelectedPlanId = snapshot.selectedPlanId
            ?: if (legacyPlanCreated) PlanEntity.LEGACY_ID else null
        val stagedSnapshot = snapshot.copy(selectedPlanId = restoredSelectedPlanId)
        try {
            journal.stage(stagedSnapshot, nonce)
        } catch (e: IOException) {
            throw RestoreInterruption.NotStarted(e)
        }
        db.withTransaction {
            programDao.deleteAllLogs()
            programDao.deleteAllExercises()
            programDao.deleteAllDays()
            planDao.deleteAllPlans()
            sessionDao.deleteAllSessionSets()
            sessionDao.deleteAllSessions()
            cardioSetResultDao.deleteAll()
            cardioSessionDao.deleteAll()
            customExerciseDao.deleteAll()

            if (snapshot.plans.isNotEmpty()) {
                planDao.insertPlans(snapshot.plans)
            } else if (legacyPlanCreated) {
                planDao.insertPlan(
                    PlanEntity(
                        id = PlanEntity.LEGACY_ID,
                        name = PlanEntity.LEGACY_NAME,
                        origin = PlanOrigin.GENERATED.name,
                        position = 0,
                        createdAt = clock.millis(),
                    ),
                )
            }
            if (snapshot.answersCarryRecipe && snapshot.plans.none { it.recipeJson != null }) {
                val recipe = PlanRecipeJson.encode(snapshot.answers)
                val plans = planDao.allPlans()
                plans.filter { it.origin == PlanOrigin.GENERATED.name && it.recipeJson == null }
                    .forEach { planDao.updateRecipe(it.id, recipe) }
                val selectedId = plans.firstOrNull { it.id == restoredSelectedPlanId && !it.archived }?.id
                    ?: plans.firstOrNull { !it.archived }?.id
                if (selectedId != null && planDao.plan(selectedId)?.suggestedDayId == null) {
                    planDao.updateSuggestedDay(selectedId, snapshot.suggestedDay)
                }
            }
            customExerciseDao.upsertAll(snapshot.customExercises)
            programDao.upsertDays(snapshot.days)
            programDao.insertExercises(snapshot.exercises)
            programDao.insertLogs(snapshot.logs)
            sessionDao.insertSessions(snapshot.sessions)
            sessionDao.insertSets(snapshot.sessionSets)
            cardioSessionDao.insertAll(snapshot.cardioSessions)
            cardioSetResultDao.insertAll(snapshot.cardioSetResults)
            restoreMarkerDao.put(RestoreMarkerEntity(nonce = nonce))
        }
        withContext(NonCancellable) {
            try {
                settings.restore(
                    global = GlobalLifter.of(snapshot.answers.config),
                    unit = snapshot.unit,
                    wizardComplete = snapshot.wizardComplete,
                    selectedPlanId = restoredSelectedPlanId,
                    restSettings = snapshot.restSettings,
                    keepScreenOn = snapshot.keepScreenOn,
                    topSetHelperSeen = snapshot.topSetHelperSeen,
                    supersetHelperSeen = snapshot.supersetHelperSeen,
                    sessionNotes = snapshot.sessionNotes,
                    themePreference = snapshot.themePreference,
                )
            } catch (e: IOException) {
                throw RestoreInterruption.SettingsPending(e)
            }
            clearRestoreBookkeeping(journal)
        }
    }

    /**
     * Finishes an interrupted restore at startup, returning whether it replayed
     * one. Old-format journals overwrite every generated plan's recipe and the
     * selected plan's rotation when staged: the restore replaced Room, so any
     * existing settings came from interim edits. The caller must prevent overlap
     * with a live restore (`:transfer` owns that lock).
     */
    suspend fun reconcilePendingRestore(journal: RestoreJournal): Boolean {
        val committed = restoreMarkerDao.nonce()
        val replayed = journal.reconcile(committed) { answers, suggestedDay, storedId ->
            db.withTransaction {
                val recipe = PlanRecipeJson.encode(answers)
                val plans = planDao.allPlans()
                if (plans.isEmpty()) {
                    planDao.insertPlan(
                        PlanEntity(
                            id = PlanEntity.LEGACY_ID,
                            name = PlanEntity.LEGACY_NAME,
                            origin = PlanOrigin.GENERATED.name,
                            position = 0,
                            createdAt = clock.millis(),
                            recipeJson = recipe,
                            suggestedDayId = suggestedDay,
                        ),
                    )
                    return@withTransaction PlanEntity.LEGACY_ID
                }
                plans.filter { it.origin == PlanOrigin.GENERATED.name }
                    .forEach { planDao.updateRecipe(it.id, recipe) }
                val selected = plans.firstOrNull { it.id == storedId && !it.archived }
                    ?: plans.firstOrNull { !it.archived }
                if (selected != null && suggestedDay != null) {
                    planDao.updateSuggestedDay(selected.id, suggestedDay)
                }
                selected?.id
            }
        }
        if (committed != null) restoreMarkerDao.clear()
        ensurePlanSettingsMigrated()
        return replayed
    }

    /** Drops both halves of the restore bookkeeping. A failure here means the
     *  restore itself fully landed and only the paperwork is outstanding — the
     *  leftover pair replays the same values idempotently next launch — so it is
     *  reported as that, not as a failed restore. */
    private suspend fun clearRestoreBookkeeping(journal: RestoreJournal) {
        try {
            journal.clear()
            restoreMarkerDao.clear()
        } catch (e: IOException) {
            throw RestoreInterruption.CleanupPending(e)
        } catch (e: RuntimeException) {
            // Room surfaces storage failures as SQLiteException, not IOException;
            // a cleanup failure is the same success-with-a-footnote either way.
            if (e is kotlinx.coroutines.CancellationException) throw e
            throw RestoreInterruption.CleanupPending(e)
        }
    }

    // --- CSV history export/import (#16) --------------------------------------

    /**
     * Every session + set for CSV history export (#16), in the same
     * deterministic order as [exportSnapshot]'s equivalent fields. Read-only —
     * `:transfer` builds the CSV text from this instead of touching Room.
     */
    suspend fun exportSessionHistory(): SessionHistorySnapshot = SessionHistorySnapshot(
        unit = settings.unitFlow.first(),
        sessions = sessionDao.allSessions(),
        sessionSets = sessionDao.allSessionSets(),
        cardioSessions = cardioSessionDao.all(),
        cardioSets = cardioSetResultDao.all().groupBy { it.cardioSessionId }.mapNotNull { (id, rows) ->
            val sets = rows.mapNotNull { it.toDomainOrNull() }
            if (sets.size == rows.size) id to sets else null
        }.toMap(),
    )

    /**
     * Appends CSV-imported history in one transaction (#16, D9's one staging
     * transaction rule). Additive only: unlike [importSnapshot]'s full
     * destructive replace, the program and live logs are untouched.
     * [newCustomExercises] are upserted first so every imported set's
     * `exerciseId` already resolves by the time its row lands; each
     * [ImportedSession] then gets a freshly generated session id stamped onto
     * its own sets before they're inserted, exactly as [advanceDay] links a
     * completed day's sets to the session it just created.
     *
     * The caller has already validated the file and the user has already
     * confirmed the exercise-name matches (`:transfer`'s preview/confirm
     * model) — this method performs no validation of its own and always
     * commits what it's given. That includes a null
     * [WorkoutSessionEntity.bodyweightLb]: a CSV has no bodyweight column, so an
     * imported session legitimately records none, and every reader downstream
     * (backup, Health Connect, the Log screen) is required to handle it (#171).
     */
    suspend fun importSessionHistory(
        sessions: List<ImportedSession>,
        newCustomExercises: List<CustomExerciseEntity>,
        cardioSessions: List<ImportedCardioSession> = emptyList(),
    ) {
        db.withTransaction {
            if (newCustomExercises.isNotEmpty()) customExerciseDao.upsertAll(newCustomExercises)
            sessions.forEach { imported ->
                val sessionId = sessionDao.insertSession(imported.session.copy(id = 0))
                if (imported.sets.isNotEmpty()) {
                    sessionDao.insertSets(imported.sets.map { it.copy(id = 0, sessionId = sessionId) })
                }
            }
            cardioSessions.forEach { imported ->
                val sessionId = cardioSessionDao.insert(imported.session.copy(id = 0))
                if (imported.sets.isNotEmpty()) {
                    cardioSetResultDao.insertAll(imported.sets.mapIndexed { position, set ->
                        set.toEntity(sessionId, position)
                    })
                }
            }
        }
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

    companion object {
        const val CARDIO_LABEL_MAX_LENGTH = 80
    }
}

/** Unknown stored enum names stay unknown: callers may render [mode], while typed consumers skip it. */
fun CardioSessionEntity.decodedCardioMode(): CardioMode? =
    CardioMode.entries.find { it.name == mode }
