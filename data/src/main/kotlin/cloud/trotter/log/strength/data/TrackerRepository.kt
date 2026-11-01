package cloud.trotter.log.strength.data

import android.database.sqlite.SQLiteException
import androidx.room.withTransaction
import cloud.trotter.log.strength.data.catalog.ExerciseCatalog
import cloud.trotter.log.strength.data.run.LegacyLiveLogSplit
import cloud.trotter.log.strength.data.run.SplitInput
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
import cloud.trotter.log.strength.data.db.entity.RunTrackEntity
import cloud.trotter.log.strength.data.db.entity.WorkoutRunEntity
import cloud.trotter.log.strength.data.db.entity.WorkoutSessionEntity
import cloud.trotter.log.strength.data.mapping.toDomain
import cloud.trotter.log.strength.data.mapping.toDomainOrNull
import cloud.trotter.log.strength.data.mapping.toEntity
import cloud.trotter.log.strength.data.mapping.toEntry
import cloud.trotter.log.strength.data.migration.reinterpretRepsAsSeconds
import cloud.trotter.log.strength.data.prefs.RestoreInterruption
import cloud.trotter.log.strength.data.prefs.RestoreJournal
import cloud.trotter.log.strength.data.prefs.WorkoutRecord
import cloud.trotter.log.strength.data.prefs.SettingsStore
import cloud.trotter.log.strength.data.serialization.LegacyCardio
import cloud.trotter.log.strength.data.serialization.CardioBlockJson
import cloud.trotter.log.strength.data.serialization.PrescriptionJson
import cloud.trotter.log.strength.data.serialization.PlanRecipeJson
import cloud.trotter.log.strength.data.serialization.PlannedTrackDto
import cloud.trotter.log.strength.data.serialization.SessionPlanJson
import cloud.trotter.log.strength.data.serialization.SetDto
import cloud.trotter.log.strength.data.serialization.SetJson
import cloud.trotter.log.strength.domain.seeding.RunReplan
import cloud.trotter.log.strength.domain.seeding.SetSeeder
import cloud.trotter.log.strength.domain.seeding.CarryForward
import cloud.trotter.log.strength.domain.generator.Rotation
import cloud.trotter.log.strength.domain.generator.ProgramGenerator
import cloud.trotter.log.strength.domain.generator.WizardAnswers
import cloud.trotter.log.strength.domain.library.ExerciseLibrary
import cloud.trotter.log.strength.domain.library.TrackingType
import cloud.trotter.log.strength.domain.library.tracking
import cloud.trotter.log.strength.domain.model.CardioBlock
import cloud.trotter.log.strength.domain.model.CardioActivity
import cloud.trotter.log.strength.domain.model.CardioSetResult
import cloud.trotter.log.strength.domain.model.CardioMode
import cloud.trotter.log.strength.domain.model.Equipment
import cloud.trotter.log.strength.domain.model.LifterConfig
import cloud.trotter.log.strength.domain.model.LoggedSet
import cloud.trotter.log.strength.domain.model.MovementPattern
import cloud.trotter.log.strength.domain.model.Prescription
import cloud.trotter.log.strength.domain.model.Program
import cloud.trotter.log.strength.domain.model.ProgramDayKind
import cloud.trotter.log.strength.domain.model.ProgramExercise
import cloud.trotter.log.strength.domain.model.SetKind
import cloud.trotter.log.strength.domain.standards.GoalCalculator
import cloud.trotter.log.strength.domain.standards.RestCategory
import cloud.trotter.log.strength.domain.standards.RestSettings
import cloud.trotter.log.strength.domain.units.WeightUnit
import java.io.IOException
import java.time.Clock
import java.time.Instant
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
    private val mintRunId: () -> String = { UUID.randomUUID().toString() },
) {

    /** Clock-backed compatibility reading for non-app composition and tests. */
    fun currentDate(): LocalDate = LocalDate.now(clock)

    /** Taken off [db] rather than injected like the other DAOs on purpose: the
     *  restore marker is this class's own crash bookkeeping — never data a
     *  caller supplies, reads, or would substitute — and threading it through
     *  every construction site would claim otherwise. */
    private val restoreMarkerDao = db.restoreMarkerDao()
    private val cardioSessionDao = db.cardioSessionDao()
    private val cardioSetResultDao = db.cardioSetResultDao()
    private val planDao = db.planDao()
    private val runDao = db.runDao()
    private val planSettingsMigrationMutex = Mutex()
    private var legacyCardioConverted = false
    private val sessionNotesMigrationMutex = Mutex()

    /** Serializes plan selection and archive changes across Room and DataStore. */
    private val planSelectionLock = Mutex()

    // --- config & preferences ------------------------------------------------

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

    private fun answersForPlan(plan: PlanEntity?, global: GlobalLifter): WizardAnswers =
        plan?.recipeJson?.let { PlanRecipeJson.decode(it, global) }
            ?: WizardAnswers(config = global.toConfig())

    val wizardAnswersFlow: Flow<WizardAnswers> = flow {
        ensurePlanSettingsMigrated()
        emitAll(planAnswersFlow)
    }
    val configFlow: Flow<LifterConfig> = flow {
        ensurePlanSettingsMigrated()
        emitAll(planAnswersFlow.map { it.config }.distinctUntilChanged())
    }
    val unitFlow: Flow<WeightUnit> = settings.unitFlow
    val restSettingsFlow: Flow<RestSettings> = settings.restSettingsFlow
    val phoneRestTimerEnabledFlow = settings.phoneRestTimerEnabledFlow
    val workoutRecordFlow = settings.workoutRecordFlow
    val phoneRestFlow = settings.phoneRestFlow
    val phoneRestNotificationAskedFlow = settings.phoneRestNotificationAskedFlow
    /** The keep-screen-on preference. Collected once, by the activity, so
     *  the wake follows the app rather than whichever screen happens to be
     *  composed — see `MainActivity`. */
    val keepScreenOnFlow: Flow<Boolean> = settings.keepScreenOnFlow
    val topSetHelperSeenFlow: Flow<Boolean> = settings.topSetHelperSeenFlow
    val supersetHelperSeenFlow: Flow<Boolean> = settings.supersetHelperSeenFlow
    val themePreferenceFlow = settings.themePreferenceFlow
    val wizardCompleteFlow: Flow<Boolean> = settings.wizardCompleteFlow

    /** Whether the one-shot Health Connect backfill has run — the Log
     *  screen's offer hides itself once it has. */
    val healthBackfillDoneFlow: Flow<Boolean> = settings.healthBackfillDoneFlow

    // Block bodies, not expression form: DataStore's Preferences (SettingsStore's
    // setter return type) must not leak into this public surface via inference —
    // it is a :data-internal detail and consumers don't compile against DataStore.
    suspend fun setConfig(config: LifterConfig) {
        ensurePlanSettingsMigrated()
        val planId = selectedPlanId()
        settings.setGlobalLifter(GlobalLifter.of(config))
        if (planId == null) return
        db.withTransaction {
            val plan = planDao.plan(planId) ?: return@withTransaction
            val answers = plan.recipeJson?.let { PlanRecipeJson.decode(it, GlobalLifter.of(config)) }
                ?: WizardAnswers(config = GlobalLifter.of(config).toConfig())
            planDao.updateRecipe(planId, PlanRecipeJson.encode(answers.copy(config = config)))
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
     *  that predated the grant. Only ever written after a backfill that
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
    suspend fun setWorkoutRecord(record: WorkoutRecord?) = settings.setWorkoutRecord(record)
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
            planDao.updateRecipe(planId, PlanRecipeJson.encode(answers))
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
    open suspend fun createGeneratedPlan(name: String, answers: WizardAnswers, program: Program): Long {
        return planSelectionLock.withLock {
            val storedId = settings.selectedPlanIdFlow.first()
            db.withTransaction {
                val plans = planDao.allPlans()
                val selected = preferredPlan(plans, storedId)
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
                    programDao.upsertDay(day.toEntity(planId).copy(position = index).freshRunId())
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
        ensurePlanSettingsMigrated()
        planSelectionLock.withLock {
            val active = planDao.plan(id)?.archived == false
            if (active) planDao.updateOnToday(id, true)
            settings.setGlobalLifter(GlobalLifter.of(answers.config))
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
        programDao.upsertDay(ProgramDayEntity(id, "A", 0, dayTitle.trim(), "", null).freshRunId())
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
        ).freshRunId())
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
        val bodyweight = configFlow.first().bodyweightLb
        val catalog = ExerciseCatalog(customExerciseDao.getAll().map { it.toEntry() })
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
        ensurePlanSettingsMigrated()
        val global = settings.globalLifterFlow.first()
        db.withTransaction {
            val row = programDao.exercise(programExerciseId) ?: return@withTransaction
            val count = sets ?: row.targetSets
            require(count in 1..20)
            programDao.setPrescription(row.id, PrescriptionJson.encode(prescription), count)
            val run = runDao.openRun()
            if (run?.planId == row.planId && run.dayId == row.dayId) {
                val tracks = runDao.tracks(run.id).filter {
                    it.programExerciseId == row.id && !it.retired
                }
                val main = tracks.firstOrNull { it.slot == Slot.MAIN && it.exerciseId == row.exerciseId }
                if (main != null && main.canReplan(prescription.tracking == TrackingType.TIMED)) {
                    val current = SetJson.decodeSets(main.setsJson)
                    val seeded = SetSeeder.seedPrescribed(count, prescription)
                    val partner = tracks.firstOrNull { it.slot == Slot.SS }
                    val mainRows = if (partner == null) RunReplan.represcribe(current, seeded) else if (
                        partner.exerciseId != row.exerciseIdFor(Slot.SS)
                    ) {
                        val partnerRows = SetJson.decodeSets(partner.setsJson)
                        val alignedSeed = RunReplan.alignPaired(
                            seeded, partnerRows, seeded, partnerRows.ifEmpty { seeded },
                        ).first
                        RunReplan.represcribe(current, alignedSeed)
                    } else {
                        val catalog = ExerciseCatalog(customExerciseDao.getAll().map { it.toEntry() })
                        val entry = catalog.find(partner.exerciseId)
                        if (entry == null) RunReplan.represcribe(current, seeded) else {
                            val config = answersForPlan(planDao.plan(row.planId), global).config
                            val partnerSeed = SetSeeder.seedPartner(count, GoalCalculator.targetFor(entry, config))
                            val paired = RunReplan.represcribePaired(
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
            }
            programDao.deleteLogsForExercise(row.planId, row.dayId, row.id)
        }
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
        programDao.upsertDays(programDao.daysForPlan(id).map { it.copy(planId = copyId).freshRunId() })
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

    /** Full replace with the wizard's generated program (spec §7). Also points the
     *  rotation at the first day. Destructive for the selected plan only: its old
     *  program and live logs are cleared (history in `workout_session` is untouched). */
    open suspend fun replaceProgram(program: Program) {
        ensurePlanSettingsMigrated()
        val planId = ensureSelectedPlan()
        val bodyweight = configFlow.first().bodyweightLb
        val catalog = ExerciseCatalog(customExerciseDao.getAll().map { it.toEntry() })
        db.withTransaction {
            closeRemovedRun(planId, programDao.daysForPlan(planId).map { it.dayId }.toSet(), bodyweight, catalog)
            programDao.deleteLogsForPlan(planId)
            programDao.deleteExercisesForPlan(planId)
            programDao.deleteDaysForPlan(planId)
            program.days.forEachIndexed { index, day ->
                programDao.upsertDay(day.toEntity(planId).copy(position = index).freshRunId())
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
        val catalog = catalogFlow.first()
        return db.withTransaction {
            val row = programDao.exercise(programExerciseId) ?: return@withTransaction false
            if (row.dayId != dayId) return@withTransaction false
            if (newExerciseId == row.exerciseId) {
                val run = runDao.openRun()?.takeIf { it.planId == row.planId && it.dayId == dayId }
                    ?: return@withTransaction true
                val config = answersForPlan(planDao.plan(row.planId), global).config
                return@withTransaction swapInRun(run.id, row.id, Slot.MAIN, newExerciseId, null, config, catalog)
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
        retireActiveTrack(planId, dayId, programExerciseId, Slot.MAIN)
        programDao.setExerciseId(programExerciseId, newExerciseId)
        programDao.deleteLogsForExercise(planId, dayId, programExerciseId)
    }

    /** The open run's active track for this slot: retired when it holds done work, dropped otherwise. */
    private suspend fun retireActiveTrack(planId: Long, dayId: String, programExerciseId: Long, slot: String) {
        val run = runDao.openRun()?.takeIf { it.planId == planId && it.dayId == dayId } ?: return
        val track = runDao.tracks(run.id).firstOrNull {
            it.programExerciseId == programExerciseId && it.slot == slot && !it.retired
        } ?: return
        if (SetJson.decodeSets(track.setsJson).any { it.done }) {
            runDao.updateTrack(track.copy(retired = true, updatedAt = clock.millis()))
        } else {
            runDao.deleteTrack(track.id)
        }
    }

    /** Appends an exercise to the end of a day (spec §8.3). */
    suspend fun addExercise(dayId: String, exercise: ProgramExercise, targetPlanId: Long? = null) {
        val planId = targetPlanId ?: selectedPlanId() ?: return
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

    val sessionNotesFlow: Flow<Map<Long, String>> = flow {
        ensureSessionNotesMoved()
        emitAll(sessionDao.observeSessions().map { sessions ->
            sessions.filter { it.note.isNotBlank() }.associate { it.id to it.note }
        })
    }

    suspend fun setSessionNote(sessionId: Long, text: String) {
        ensureSessionNotesMoved()
        sessionDao.setNote(sessionId, text.trim().take(120))
    }

    suspend fun ensureSessionNotesMoved() {
        sessionNotesMigrationMutex.withLock {
            if (restoreMarkerDao.nonce() != null) return@withLock
            val notes = settings.legacySessionNotesFlow.first()
            if (notes.isEmpty()) return@withLock
            db.withTransaction {
                notes.forEach { (id, note) -> sessionDao.setNote(id, note.trim().take(120)) }
            }
            settings.removeLegacySessionNotes()
        }
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
            retireActiveTrack(planId, dayId, row.id, Slot.SS)
            programDao.setSupersetExerciseId(row.id, null)
            programDao.deleteLogForSlot(planId, dayId, row.id, Slot.SS)
        }
    }

    /** Regenerates one day from the stored wizard answers (spec §8.3), leaving the
     *  other days untouched. No-op if answers regenerate no such day. */
    suspend fun resetDayToTemplate(dayId: String, targetPlanId: Long? = null) {
        ensurePlanSettingsMigrated()
        val planId = targetPlanId ?: selectedPlanId() ?: return
        if (planDao.plan(planId)?.origin != PlanOrigin.GENERATED.name) return
        val answers = wizardAnswersFor(planId).first()
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
            programDao.upsertDay(if (existing == null) replacement.freshRunId() else replacement.copy(
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

    /** Moves pre-plan preferences to Room before atomically retiring their keys.
     *  Also converts legacy cardio to typed blocks.
     *  Recipe-less generated plans make a retry after the Room commit harmless. */
    suspend fun runPlanSettingsFixupIfNeeded() {
        ensurePlanSettingsMigrated()
    }

    /** A half-done restore's settings are not this device's to copy; reconcile owns them.
     *  Also converts legacy cardio to typed blocks. */
    private suspend fun ensurePlanSettingsMigrated(migrateOnToday: Boolean = true) {
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

    // --- live logs -----------------------------------------------------------

    /**
     * The day's exercise slots with their stable row ids, in program order. The UI
     * needs the id to key and seed each slot's log ([dayTracksFlow]/[updateSets]); the
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

    fun dayTracksFlow(planId: Long, dayId: String): Flow<DayTracks> = combine(
        openRunFlow, programDao.observeDays(planId), programDao.observeLogs(planId, dayId),
        programDao.observeExercisesForDay(planId, dayId),
    ) { open, days, logs, slots ->
        if (open?.planId == planId && open.dayId == dayId) {
            DayTracks(open.id, true, open.tracks.filter { !it.retired }.mapNotNull { track ->
                track.programExerciseId?.let { LoggedSlot(it, track.slot, track.sets, track.exerciseId) }
            }, retired = open.tracks.filter { it.retired })
        } else {
            DayTracks(days.firstOrNull { it.dayId == dayId }?.nextRunId?.takeIf(String::isNotBlank),
                false, logs.map { log -> log.toLoggedSlot().copy(
                    exerciseId = slots.firstOrNull { it.id == log.programExerciseId }?.exerciseIdFor(log.slot),
                ) })
        }
    }

    suspend fun updateSets(
        runId: String, dayId: String, programExerciseId: Long, slot: String, sets: List<LoggedSet>,
    ): RunWriteResult {
        val planId = planIdOfSlot(programExerciseId) ?: return RunWriteResult.Rejected
        return applyToRun(runId, planId, dayId, listOf(TrackEdit(programExerciseId, slot, replacesAll = true) { current -> sets.keepingWatchStamps(current) }),
            seeds = listOf(RunSeed(programExerciseId, slot, CarryForward.cleared(sets))))
    }

    /**
     * Seeds a track **only if it is still unseeded when the write happens**, and
     * reports whether it did. The one way a seed reaches the database.
     * The slot names its plan, and a missing slot or mismatched day returns false.
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

    suspend fun updateSetsPaired(
        runId: String,
        dayId: String,
        programExerciseId: Long,
        main: List<LoggedSet>,
        ss: List<LoggedSet>,
    ): RunWriteResult {
        val planId = planIdOfSlot(programExerciseId) ?: return RunWriteResult.Rejected
        return applyToRun(runId, planId, dayId, listOf(
            TrackEdit(programExerciseId, Slot.MAIN, replacesAll = true) { current -> main.keepingWatchStamps(current) },
            TrackEdit(programExerciseId, Slot.SS, replacesAll = true) { current -> ss.keepingWatchStamps(current) },
        ), seeds = listOf(RunSeed(programExerciseId, Slot.MAIN, CarryForward.cleared(main)),
            RunSeed(programExerciseId, Slot.SS, CarryForward.cleared(ss))))
    }

    private fun List<LoggedSet>.keepingWatchStamps(current: List<LoggedSet>): List<LoggedSet> =
        mapIndexed { index, set ->
            val stamp = listOfNotNull(set.watchEditedAt, current.getOrNull(index)?.watchEditedAt).maxOrNull()
            set.copy(watchEditedAt = stamp)
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
        Rotation.nextFor(program, dayId)?.let {
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

    /** The Log screen's list: every session newest-first, with its total
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

    /** One session's sets, fetched on demand when the Log screen expands a row — not
     *  part of [sessionSummariesFlow] because most rows stay collapsed. */
    suspend fun sessionSets(sessionId: Long): List<SessionSetEntity> = sessionDao.setsForSession(sessionId)

    /** One session header by id: the Health Connect publish path pairs this
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
    suspend fun deleteSession(sessionId: Long): DeletedSession? {
        ensureSessionNotesMoved()
        return db.withTransaction {
            val session = sessionDao.sessionById(sessionId) ?: return@withTransaction null
            val sets = sessionDao.setsForSession(sessionId)
            sessionDao.deleteSetsForSession(sessionId)
            sessionDao.deleteSessionById(sessionId)
            DeletedSession(session, sets)
        }
    }

    /** Restores a just-deleted session exactly, including every original row id. */
    suspend fun restoreSession(deleted: DeletedSession) {
        db.withTransaction {
            sessionDao.insertSessions(listOf(deleted.session))
            if (deleted.sets.isNotEmpty()) sessionDao.insertSets(deleted.sets)
        }
    }

    /** Most recent completed-session date per exercise, for the Lift sheet. */
    suspend fun lastPerformedDates(exerciseIds: List<String>): Map<String, Long> =
        if (exerciseIds.isEmpty()) emptyMap() else sessionDao.lastPerformedDates(exerciseIds)
            .associate { it.exerciseId to it.completedAt }

    /**
     * Batches the A1 "last time" chip for a whole day into one query:
     * [exerciseIds]' most recent completed performance, keyed by exercise id. An
     * id with no history is simply absent from the result.
     *
     * `open` for the same reason the wizard writes are: a test double slows this
     * read down to pin what the day screen shows while it waits.
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

    private fun ProgramDayEntity.freshRunId(): ProgramDayEntity = copy(nextRunId = mintRunId(), closedAt = null)

    /** The open workout and its tracks, independent of plan selection. */
    val openRunFlow: Flow<OpenRun?> = runDao.observeOpenRun().flatMapLatest { run ->
        if (run == null) flowOf(null) else runDao.observeTracks(run.id).map { tracks ->
            OpenRun(run.id, run.planId, run.dayId, run.dayTitle, run.planName, run.startedAt, tracks.map { it.toRunTrack() })
        }
    }

    val todayTargetFlow: Flow<TodayTarget?> = flow {
        ensurePlanSettingsMigrated()
        emitAll(combine(
            settings.selectedPlanIdFlow, openRunFlow, plansFlow, restoreMarkerDao.observeNonce(),
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
        SetJson.decodeSets(plannedJson), SetJson.decodeSets(setsJson),
    )

    suspend fun startRun(planId: Long, dayId: String, seeds: List<RunSeed>): RunStartResult = db.withTransaction {
        val open = runDao.openRun()
        if (open != null) {
            return@withTransaction if (open.planId == planId && open.dayId == dayId) {
                RunStartResult.AlreadyOpen(open.id)
            } else RunStartResult.OtherRunOpen(open.id, open.planId, open.dayId)
        }
        val day = programDao.day(planId, dayId) ?: return@withTransaction RunStartResult.NoStrengthSlots
        if (programDao.exercisesForDay(planId, dayId).isEmpty()) return@withTransaction RunStartResult.NoStrengthSlots
        startRun(day, initialRunTracks(day, seeds))
        RunStartResult.Started(day.nextRunId)
    }

    // Watch swaps must not start a new identity if Finish wins during validation.
    private suspend fun startRunIfNext(
        runId: String, planId: Long, dayId: String, seeds: List<RunSeed>,
    ): RunStartResult? = db.withTransaction {
        val day = programDao.day(planId, dayId) ?: return@withTransaction null
        if (day.nextRunId != runId) return@withTransaction null
        startRun(planId, dayId, seeds)
    }

    /** Identity and no-op checks share the mutation transaction, including preview swaps. */
    suspend fun applyExerciseSwap(
        planId: Long,
        dayId: String,
        programExerciseId: Long,
        newExerciseId: String,
        runId: String?,
        seed: RunSeed?,
        startSeeds: List<RunSeed>,
        allowChange: Boolean,
    ): ExerciseSwapResult {
        val config = wizardAnswersFor(planId).first().config
        val catalog = catalogFlow.first()
        return db.withTransaction {
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
                    val initialSeed = seed ?: return@withTransaction ExerciseSwapResult.REJECTED
                    if (initialSeed.programExerciseId != row.id || initialSeed.slot != Slot.MAIN) {
                        return@withTransaction ExerciseSwapResult.REJECTED
                    }
                    if (startRunIfNext(targetRunId, planId, dayId, startSeeds) !is RunStartResult.Started) {
                        return@withTransaction ExerciseSwapResult.REJECTED
                    }
                }
                swapInRun(targetRunId, programExerciseId, Slot.MAIN, newExerciseId, seed, config, catalog)
            }
            if (applied) ExerciseSwapResult.CHANGED else ExerciseSwapResult.REJECTED
        }
    }

    suspend fun swapInRun(
        runId: String,
        programExerciseId: Long,
        slot: String,
        newExerciseId: String,
        seed: RunSeed?,
        expectedExerciseId: String? = null,
    ): Boolean {
        val planId = planIdOfSlot(programExerciseId) ?: return false
        val config = wizardAnswersFor(planId).first().config
        val catalog = catalogFlow.first()
        return swapInRun(runId, programExerciseId, slot, newExerciseId, seed, config, catalog, expectedExerciseId)
    }

    private suspend fun swapInRun(
        runId: String,
        programExerciseId: Long,
        slot: String,
        newExerciseId: String,
        seed: RunSeed?,
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
            val active = tracks.firstOrNull {
                it.programExerciseId == programExerciseId && it.slot == slot && !it.retired
            }
            if (expectedExerciseId != null && active?.exerciseId != expectedExerciseId) return@withTransaction false
            if (active?.exerciseId == newExerciseId) return@withTransaction true
            val retired = tracks.firstOrNull {
                it.programExerciseId == programExerciseId && it.slot == slot && it.exerciseId == newExerciseId && it.retired
            }
            val validSeed = seed?.takeIf { it.programExerciseId == programExerciseId && it.slot == slot }
            var replacement = retired?.copy(retired = false, updatedAt = clock.millis()) ?: run {
                val base = validSeed?.sets ?: return@withTransaction false
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
                    else validSeed?.sets.orEmpty()
                val programSlot = ProgramSlot(row.id, row.position, row.toDomain())
                val performed = performedExercise(programSlot, partner.exerciseId, catalog, otherSlot)
                val otherSeed = if (otherSlot == Slot.SS) {
                    performed.goal(config)?.let { SetSeeder.seedPartner(count, it) }
                } else {
                    performed.prescription?.let { SetSeeder.seedPrescribed(row.targetSets, it) }
                        ?: performed.goal(config)?.let {
                            SetSeeder.seed(programSlot.exercise.copy(exerciseId = performed.exerciseId, prescription = null), it, config)
                        }
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
        seeds: List<RunSeed> = emptyList(),
    ): RunWriteResult = db.withTransaction {
        if (runId.isBlank() || edits.isEmpty()) return@withTransaction RunWriteResult.Rejected
        val day = programDao.day(planId, dayId) ?: return@withTransaction RunWriteResult.Rejected
        val open = runDao.openRun()
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
            val base = working[key] ?: workingBase(day, slot, edit.slot, seeds)
                ?: return@withTransaction RunWriteResult.Rejected
            val result = edit.transform(base) ?: return@withTransaction RunWriteResult.Unchanged
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
            edited.copy(id = inserted.first { it.sameKey(edited) }.id,
                setsJson = SetJson.encodeSets(SetJson.decodeSets(edited.setsJson).mapIndexed { i, set -> set.copy(plannedIndex = i) }))
        })
        RunWriteResult.InRun(runId, true)
    }

    /** The bounded path for a delta that carries no run: an old watch. */
    suspend fun applyToOpenRun(
        planId: Long, dayId: String, editedAtMillis: Long, edits: List<TrackEdit>,
    ): RunWriteResult = db.withTransaction {
        val open = runDao.openRun() ?: return@withTransaction RunWriteResult.Rejected
        val day = programDao.day(planId, dayId) ?: return@withTransaction RunWriteResult.Rejected
        if (open.planId != planId || open.dayId != dayId || edits.isEmpty() ||
            editedAtMillis <= (day.closedAt ?: Long.MIN_VALUE)) return@withTransaction RunWriteResult.Rejected
        val resolved = resolveRunEdits(open.id, day, runDao.tracks(open.id), edits, emptyList())
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
        seeds: List<RunSeed>,
        reopenRetired: Boolean = false,
    ): ResolvedRunEdits {
        val resolved = tracks.toMutableList()
        val changed = linkedSetOf<Int>()
        for (edit in edits) {
            val seed = seeds.firstOrNull { it.programExerciseId == edit.programExerciseId && it.slot == edit.slot }
            var reopened = false
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
                        resolved[retiredIndex] = reopenTrack(resolved[retiredIndex], seed)
                        index = retiredIndex
                        reopened = true
                    }
                }
            }
            if (index < 0) {
                if (seed == null) return ResolvedRunEdits(failure = RunWriteResult.Rejected)
                val slot = programDao.exercise(seed.programExerciseId)
                    ?.takeIf { it.planId == day.planId && it.dayId == day.dayId }
                    ?: return ResolvedRunEdits(failure = RunWriteResult.Rejected)
                val exerciseId = slot.exerciseIdFor(seed.slot)
                    ?: return ResolvedRunEdits(failure = RunWriteResult.Rejected)
                if (edit.exerciseId != null && edit.exerciseId != exerciseId) {
                    return ResolvedRunEdits(failure = RunWriteResult.Rejected)
                }
                val candidate = runTrack(runId, slot, seed.slot, exerciseId, CarryForward.cleared(seed.sets))
                if (resolved.any { it.sameKey(candidate) }) return ResolvedRunEdits(failure = RunWriteResult.Rejected)
                resolved.add(candidate)
                index = resolved.lastIndex
            }
            val track = resolved[index]
            val current = SetJson.decodeSets(track.setsJson)
            val result = edit.transform(current)
                ?: return ResolvedRunEdits(failure = RunWriteResult.Unchanged)
            if (reopened && edit.replacesAll && !result.keepsDoneRowsOf(current)) {
                return ResolvedRunEdits(failure = RunWriteResult.Rejected)
            }
            resolved[index] = track.copy(setsJson = SetJson.encodeSets(result), updatedAt = clock.millis())
            changed.add(index)
        }
        return ResolvedRunEdits(changed.map { resolved[it] })
    }

    /**
     * A whole-list write that reopens a retired track was built from the screen's empty or
     * stale rows, so it may only add to the reopened rows: every done row stays done, at its index, with
     * its values.
     */
    private fun List<LoggedSet>.keepsDoneRowsOf(reopened: List<LoggedSet>): Boolean =
        reopened.withIndex().filter { it.value.done }.all { (i, row) ->
            val kept = getOrNull(i) ?: return@all false
            kept.done && kept.kind == row.kind && kept.weightLb == row.weightLb &&
                kept.reps == row.reps && kept.seconds == row.seconds
        }

    private fun RunTrackEntity.sameKey(other: RunTrackEntity): Boolean =
        runId == other.runId && programExerciseId == other.programExerciseId && slot == other.slot && exerciseId == other.exerciseId

    private fun RunTrackEntity.canReplan(isTimed: Boolean): Boolean {
        val captured = SetJson.decodeSets(plannedJson).firstOrNull() ?: return false
        return (captured.seconds > 0) == isTimed
    }

    private fun reopenTrack(track: RunTrackEntity, seed: RunSeed?): RunTrackEntity = track.copy(
        retired = false,
        setsJson = seed?.takeIf { track.canReplan((it.sets.firstOrNull()?.seconds ?: 0) > 0) }?.let {
            SetJson.encodeSets(RunReplan.represcribe(SetJson.decodeSets(track.setsJson), it.sets))
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
        seeds: List<RunSeed>,
    ): List<LoggedSet>? {
        val stored = programDao.logForSlot(day.planId, day.dayId, slot.id, slotName)
        val sets = stored?.let { SetJson.decodeSets(it.setsJson) }
            ?: seeds.firstOrNull { it.programExerciseId == slot.id && it.slot == slotName }?.sets
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

    private suspend fun initialRunTracks(day: ProgramDayEntity, seeds: List<RunSeed>): List<RunTrackEntity> {
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

    /** Adds available seeds to the open run without replacing captured tracks.
     * Swapping back preserves done rows and re-plans undone rows from the current seed. */
    suspend fun seedRunTracksIfMissing(runId: String, seeds: List<RunSeed>) {
        db.withTransaction {
            val run = runDao.openRun()?.takeIf { it.id == runId } ?: return@withTransaction
            val planId = run.planId ?: return@withTransaction
            val dayId = run.dayId ?: return@withTransaction
            val existing = runDao.tracks(runId).toMutableList()
            for (seed in seeds) {
                if (existing.any { it.programExerciseId == seed.programExerciseId && it.slot == seed.slot && !it.retired }) continue
                val slot = programDao.exercise(seed.programExerciseId)
                    ?.takeIf { it.planId == planId && it.dayId == dayId } ?: continue
                val exerciseId = slot.exerciseIdFor(seed.slot) ?: continue
                val track = runTrack(runId, slot, seed.slot, exerciseId, CarryForward.cleared(seed.sets))
                val retiredIndex = existing.indexOfFirst { it.retired && it.sameKey(track) }
                if (retiredIndex >= 0) {
                    val reopened = reopenTrack(existing[retiredIndex], seed)
                    runDao.updateTrack(reopened)
                    existing[retiredIndex] = reopened
                } else {
                    runDao.insertTrack(track)
                    existing.add(track)
                }
            }
        }
    }

    /** Archives a run once, carries forward its working values, and advances its day. */
    suspend fun finishRun(runId: String): Long? {
        val bodyweight = configFlow.first().bodyweightLb
        val catalog = ExerciseCatalog(customExerciseDao.getAll().map { it.toEntry() })
        return db.withTransaction {
            sessionDao.sessionByRunId(runId)?.let { return@withTransaction it.id }
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
            sessionId
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
            if (track.retired) continue
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

    // --- full backup (A2) ----------------------------------------------------

    /**
     * Reads everything the user owns into one [FullSnapshot] for the A2 backup.
     * Every list comes from a query with an explicit `ORDER BY`, so the output is
     * deterministic — two exports of the same state are byte-identical, making
     * diffs and round-trip tests reliable.
     */
    suspend fun exportSnapshot(): FullSnapshot {
        ensurePlanSettingsMigrated()
        ensureSessionNotesMoved()
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
        val themePreference = settings.themePreferenceFlow.first()
        return db.withTransaction {
            // Use this same plan list for the legacy settings projection, too.
            // Separate reads could otherwise straddle a restore's Room commit.
            val plans = planDao.allPlans()
            val selectedPlan = preferredPlan(plans, selectedPlanId)
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
                runs = runDao.allRuns(),
                runTracks = runDao.allTracks(),
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
     * atomicity it can't have, this method makes the pair *recoverable*:
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
     * payload whose transaction never committed ([reconcilePendingRestore]). The
     * interim state is not benign and is not treated as such — restored training
     * data paired with the device's pre-restore config means every derived GOAL
     * is wrong until the replay happens.
     *
     * Room goes before the settings write because the only cross-store pointer is
     * `selectedPlanId` pointing at a plan, and the reverse order could publish a
     * pointer into a program that does not exist yet. The tail is [NonCancellable]
     * so a cancelled caller still *finishes* here instead of deferring to the next
     * launch — belt to the marker's braces, not the correctness argument. The
     * transaction itself stays cancellable: rolling it back leaves the device
     * untouched, which is a clean outcome.
     *
     * Failures are reported by phase ([RestoreInterruption]) rather than as the
     * raw [IOException] the UI would report as a problem reading the file.
     */
    suspend fun importSnapshot(snapshot: FullSnapshot, journal: RestoreJournal) {
        try {
            ensureSessionNotesMoved()
        } catch (e: IOException) {
            throw RestoreInterruption.NotStarted(e)
        }
        val days = snapshot.days.map { if (it.nextRunId.isBlank()) it.freshRunId() else it }
        val split = snapshot.legacyLogs.takeIf { it.isNotEmpty() }?.let { legacy ->
            val customNames = snapshot.customExercises.associate { it.id to it.name }
            LegacyLiveLogSplit.split(SplitInput(
                logs = legacy, slots = snapshot.exercises, days = days,
                planNames = snapshot.plans.associate { it.id to it.name }.ifEmpty {
                    mapOf(PlanEntity.LEGACY_ID to PlanEntity.LEGACY_NAME)
                },
                exerciseName = { ExerciseLibrary.find(it)?.name ?: customNames[it] ?: it },
                today = LocalDate.now(clock).toString(), now = clock.millis(),
                openRun = snapshot.runs.firstOrNull()?.let { it.planId to it.dayId }, mint = mintRunId,
            ))
        }
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
            runDao.deleteAllTracks()
            runDao.deleteAllRuns()
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
                        onToday = true,
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
                val selectedId = preferredPlan(plans, restoredSelectedPlanId)?.id
                if (selectedId != null && planDao.plan(selectedId)?.suggestedDayId == null) {
                    planDao.updateSuggestedDay(selectedId, snapshot.suggestedDay)
                }
            }
            customExerciseDao.upsertAll(snapshot.customExercises)
            programDao.upsertDays(days)
            programDao.insertExercises(snapshot.exercises)
            programDao.insertLogs(snapshot.logs + split?.logs.orEmpty())
            sessionDao.insertSessions(snapshot.sessions)
            sessionDao.insertSets(snapshot.sessionSets)
            cardioSessionDao.insertAll(snapshot.cardioSessions)
            cardioSetResultDao.insertAll(snapshot.cardioSetResults)
            runDao.insertRuns(snapshot.runs)
            runDao.insertTracks(snapshot.runTracks)
            split?.run?.let { runDao.insertRun(it) }
            split?.tracks?.let { runDao.insertTracks(it) }
            split?.archived?.forEach { archived ->
                val id = sessionDao.insertSession(archived.session)
                sessionDao.insertSets(archived.sets.map { it.copy(sessionId = id) })
            }
            split?.closes?.forEach { close ->
                val day = programDao.day(close.planId, close.dayId) ?: return@forEach
                programDao.upsertDay(day.copy(nextRunId = close.nextRunId, closedAt = close.closedAt))
            }
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
                    themePreference = snapshot.themePreference,
                    onTodayFixupDone = true,
                )
            } catch (e: IOException) {
                throw RestoreInterruption.SettingsPending(e)
            }
            planSettingsMigrationMutex.withLock { legacyCardioConverted = false }
            clearRestoreBookkeeping(journal)
            try {
                ensurePlanSettingsMigrated()
            } catch (e: IOException) {
                throw RestoreInterruption.CleanupPending(e)
            } catch (e: SQLiteException) {
                throw RestoreInterruption.CleanupPending(e)
            }
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
        val replayed = journal.reconcile(committed, applyLegacySessionNotes = { notes ->
            db.withTransaction {
                notes.forEach { (id, note) -> sessionDao.setNote(id, note.trim().take(120)) }
            }
        }) { answers, suggestedDay, storedId ->
            db.withTransaction {
                val recipe = PlanRecipeJson.encode(answers)
                val plans = planDao.allPlans()
                if (plans.isEmpty()) {
                    planDao.insertPlan(
                        PlanEntity(
                            id = PlanEntity.LEGACY_ID,
                            name = PlanEntity.LEGACY_NAME,
                            onToday = true,
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
                val selected = preferredPlan(plans, storedId)
                if (selected != null && suggestedDay != null) {
                    planDao.updateSuggestedDay(selected.id, suggestedDay)
                }
                selected?.id
            }
        }
        planSettingsMigrationMutex.withLock { legacyCardioConverted = false }
        if (committed != null) restoreMarkerDao.clear()
        ensurePlanSettingsMigrated(migrateOnToday = false)
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

    // --- CSV history export/import --------------------------------------------

    /**
     * Every session + set for CSV history export, in the same deterministic
     * order as [exportSnapshot]'s equivalent fields. Read-only — `:transfer`
     * builds the CSV text from this instead of touching Room.
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
     * Appends CSV-imported history in one transaction
     * (docs/briefs/m4-transfer.md, "#16 CSV"), per D9's one staging
     * transaction rule. Additive only: unlike [importSnapshot]'s full
     * destructive replace, the program and live logs are untouched.
     * [newCustomExercises] are upserted first so every imported set's
     * `exerciseId` already resolves by the time its row lands; each
     * [ImportedSession] then gets a freshly generated session id stamped onto
     * its own sets before they're inserted, exactly as [finishRun] links a
     * completed day's sets to the session it just created.
     *
     * The caller has already validated the file and the user has already
     * confirmed the exercise-name matches (`:transfer`'s preview/confirm model)
     * — this method performs no validation of its own and always commits what
     * it's given. That includes a null [WorkoutSessionEntity.bodyweightLb]: a
     * CSV has no bodyweight column, so an imported session legitimately records
     * none, and every reader downstream (backup, Health Connect, the Log screen)
     * is required to handle it.
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

    private fun ExerciseLogEntity.toLoggedSlot(): LoggedSlot =
        LoggedSlot(programExerciseId, slot, SetJson.decodeSets(setsJson))

    companion object {
        const val CARDIO_LABEL_MAX_LENGTH = 80
    }
}

/** Unknown stored enum names stay unknown: callers may render [mode], while typed consumers skip it. */
fun CardioSessionEntity.decodedCardioMode(): CardioMode? =
    CardioMode.entries.find { it.name == mode }
