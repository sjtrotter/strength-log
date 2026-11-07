package cloud.trotter.log.strength.ui.wizard

import android.content.Context
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import cloud.trotter.log.strength.R
import cloud.trotter.log.strength.data.BackupRepository
import cloud.trotter.log.strength.data.PlanRepository
import cloud.trotter.log.strength.data.prefs.RestoreInterruption
import cloud.trotter.log.strength.data.prefs.SettingsStore
import cloud.trotter.log.strength.di.ApplicationScope
import cloud.trotter.log.strength.domain.generator.AnchorScheme
import cloud.trotter.log.strength.domain.generator.CardioFirst
import cloud.trotter.log.strength.domain.generator.DeadliftVariant
import cloud.trotter.log.strength.domain.generator.LiftEntry
import cloud.trotter.log.strength.domain.generator.ProgramGenerator
import cloud.trotter.log.strength.domain.generator.SplitTemplate
import cloud.trotter.log.strength.domain.generator.WizardAnswers
import cloud.trotter.log.strength.domain.model.CardioActivity
import cloud.trotter.log.strength.domain.model.CardioFamily
import cloud.trotter.log.strength.domain.model.CardioMode
import cloud.trotter.log.strength.domain.model.CardioPlacement
import cloud.trotter.log.strength.domain.model.CardioPrefs
import cloud.trotter.log.strength.domain.model.Equipment
import cloud.trotter.log.strength.domain.model.ExperienceLevel
import cloud.trotter.log.strength.domain.model.GoalEmphasis
import cloud.trotter.log.strength.domain.model.LifterConfig
import cloud.trotter.log.strength.domain.model.Measure
import cloud.trotter.log.strength.domain.model.Program
import cloud.trotter.log.strength.domain.standards.StrengthStandards
import cloud.trotter.log.strength.domain.units.Bodyweight
import cloud.trotter.log.strength.domain.units.DistanceUnit
import cloud.trotter.log.strength.domain.units.WeightUnit
import cloud.trotter.log.strength.transfer.backup.BackupError
import cloud.trotter.log.strength.transfer.backup.BackupService
import cloud.trotter.log.strength.ui.backup.TransferErrorMessages
import cloud.trotter.log.strength.ui.text.UiText
import java.io.IOException
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Setup wizard ViewModel (spec §6.1, PLAN.md A4). Every field of the
 * in-progress [WizardAnswers] draft lives in [SavedStateHandle] as an
 * individual key, so rotation/process death mid-wizard loses nothing (data
 * principle: no unsaved truth in a bare ViewModel field).
 *
 * [Finish][finish] creates and selects a new plan from the preview through
 * [PlanRepository.createGeneratedPlan] and [PlanRepository.publishCreatedPlan];
 * it never replaces an existing program.
 * New-plan entry seeds the draft from the selected plan's stored answers.
 *
 * [Restore from backup][restoreFromBackup] is the other way out of the wizard,
 * offered on the first step of a genuine first run only — see [Keys.FIRST_RUN].
 */
@HiltViewModel
class WizardViewModel @Inject constructor(
    private val repo: PlanRepository,
    private val savedState: SavedStateHandle,
    @ApplicationContext private val context: Context,
    private val backupService: BackupService,
    @ApplicationScope private val appScope: CoroutineScope,
    private val deviceWeightUnitProvider: DeviceWeightUnitProvider,
    private val settings: SettingsStore,
) : ViewModel() {

    private object Keys {
        const val INITIALIZED = "wizard_initialized"

        /** Whether this wizard was entered with nothing on the device yet
         *  (latched from `wizardComplete` in [init], never re-read). It gates
         *  the restore-from-backup entry: when creating another plan the device already
         *  holds a program and a history, and Data/Backup owns import there
         *  behind a confirm-overwrite dialog — a second, unguarded destructive
         *  path off a setup screen is exactly the accident worth designing out. */
        const val FIRST_RUN = "wizard_first_run"
        const val LEGACY_STEP = "wizard_step"
        const val STEP = "wizard_step_index_v2"
        const val DAYS = "wizard_days"
        const val SPLIT = "wizard_split"
        const val ANCHOR_SCHEME = "wizard_anchor_scheme"
        const val DEADLIFT = "wizard_deadlift"
        const val CARDIO_MODE = "wizard_cardio_mode"
        const val CARDIO_PLACEMENT = "wizard_cardio_placement"
        const val FIVE_K = "wizard_five_k"
        const val BODYWEIGHT = "wizard_bodyweight_lb"
        const val LEGACY_BODYWEIGHT = "wizard_bodyweight"
        const val EDITED_ANSWERS = "wizard_edited_answers"
        const val AGE = "wizard_age"
        const val LEVEL = "wizard_level"
        const val EMPHASIS = "wizard_emphasis"
        const val EQUIPMENT = "wizard_equipment"
        const val LIFTS = "wizard_lifts"
        const val FOCUS = "wizard_focus"
        const val CF_ACTIVITY = "wizard_cf_activity"
        const val CF_BY_TIME = "wizard_cf_by_time"
        const val CF_SECONDS = "wizard_cf_seconds"
        const val CF_METRES = "wizard_cf_metres"
        const val CF_SESSIONS = "wizard_cf_sessions"
        const val CF_REST_DAYS = "wizard_cf_rest_days"
        const val CF_LIFT_DAYS = "wizard_cf_lift_days"
        const val DISTANCE_UNIT = "wizard_distance_unit"
        const val UNIT = "wizard_unit"
        /** Retained for this wizard's lifetime so completion retries reuse its plan. */
        const val CREATED_PLAN_ID = "wizard_created_plan_id"
    }

    private val defaults = WizardAnswers()

    private val initialized = savedState.getStateFlow(Keys.INITIALIZED, false)
    // Legacy indices describe the lifting flow before FOCUS was added.
    private val stepIndex: StateFlow<Int> = savedState.getStateFlow(
        Keys.STEP,
        savedState.get<Int>(Keys.LEGACY_STEP)?.let {
            it.coerceIn(0, WizardStateBuilder.steps(defaults).lastIndex - 1) + 1
        } ?: 0,
    )
    private val daysPerWeek: StateFlow<Int> = savedState.getStateFlow(Keys.DAYS, defaults.daysPerWeek)
    private val split: StateFlow<String> = savedState.getStateFlow(Keys.SPLIT, defaults.split.name)
    private val anchorScheme: StateFlow<String> =
        savedState.getStateFlow(Keys.ANCHOR_SCHEME, defaults.anchorScheme.name)
    private val deadliftVariant: StateFlow<String> =
        savedState.getStateFlow(Keys.DEADLIFT, defaults.deadliftVariant.name)
    private val cardioMode: StateFlow<String> = savedState.getStateFlow(Keys.CARDIO_MODE, defaults.cardio.mode.name)
    private val cardioPlacement: StateFlow<String> =
        savedState.getStateFlow(Keys.CARDIO_PLACEMENT, defaults.cardio.placement.name)
    private val fiveK: StateFlow<Boolean> = savedState.getStateFlow(Keys.FIVE_K, defaults.cardio.fiveKGoal)
    private val bodyweight: StateFlow<Double?> = savedState.getStateFlow(
        Keys.BODYWEIGHT, savedState.get<Int>(Keys.LEGACY_BODYWEIGHT)?.toDouble(),
    )
    private val age: StateFlow<Int?> = savedState.getStateFlow(Keys.AGE, null)
    private val level: StateFlow<String> = savedState.getStateFlow(Keys.LEVEL, defaults.config.level.name)
    private val emphasis: StateFlow<String> = savedState.getStateFlow(Keys.EMPHASIS, defaults.config.emphasis.name)
    private val equipment: StateFlow<List<String>> =
        savedState.getStateFlow(Keys.EQUIPMENT, defaults.equipment.map { it.name })
    private val lifts: StateFlow<List<WizardLiftDraft>> = savedState.getStateFlow(Keys.LIFTS, emptyList())
    private val unit: StateFlow<String> = savedState.getStateFlow(Keys.UNIT, WeightUnit.LB.name)
    private val focus = savedState.getStateFlow(Keys.FOCUS, false)
    private val cfActivity = savedState.getStateFlow(Keys.CF_ACTIVITY, CardioActivity.ROAD_RUN.name)
    private val cfByTime = savedState.getStateFlow(Keys.CF_BY_TIME, true)
    private val cfSeconds = savedState.getStateFlow(Keys.CF_SECONDS, 1_800)
    private val cfMetres = savedState.getStateFlow(Keys.CF_METRES, 5_000)
    private val cfSessions = savedState.getStateFlow(Keys.CF_SESSIONS, 3)
    private val cfRestDays = savedState.getStateFlow(Keys.CF_REST_DAYS, 1)
    private val cfLiftDays = savedState.getStateFlow(Keys.CF_LIFT_DAYS, 0)
    private val distanceUnit = savedState.getStateFlow(Keys.DISTANCE_UNIT, DistanceUnit.MI.name)
    private val cardioFirstChanges = combine(listOf<Flow<Any?>>(
        focus, cfActivity, cfByTime, cfSeconds, cfMetres, cfSessions, cfRestDays, cfLiftDays, distanceUnit,
    )) { Unit }
    private val previewProgram = MutableStateFlow<Program?>(null)

    private val isComplete = MutableStateFlow(false)
    private val finishProgress = MutableStateFlow(FinishProgress())

    private data class FinishProgress(val inFlight: Boolean = false, val error: UiText? = null)

    private val firstRun: StateFlow<Boolean> = savedState.getStateFlow(Keys.FIRST_RUN, false)

    /** In-flight/failed state of a restore. Not in [SavedStateHandle] on purpose:
     *  it is progress, not truth. The import outlives this ViewModel (it runs on
     *  the app scope) and any half of it that survives process death is finished
     *  by the startup reconciliation, so nothing here is worth persisting — a
     *  user who comes back to a blank wizard just picks the file again. */
    private val restoreProgress = MutableStateFlow(RestoreProgress())

    private data class RestoreProgress(val inFlight: Boolean = false, val error: cloud.trotter.log.strength.ui.text.UiText? = null)

    /** Synchronous snapshot for both rendering and finish: combined groups can
     *  still carry older answers after restore has cleared its in-flight flag. */
    private fun currentAnswers(): WizardAnswers {
        val currentEmphasis = enumOf(emphasis.value, defaults.config.emphasis)
        val cardioFirst = if (focus.value) CardioFirst(
            activity = enumOf(cfActivity.value, CardioActivity.ROAD_RUN),
            comfortable = if (cfByTime.value) Measure.Time(cfSeconds.value) else Measure.Distance(cfMetres.value),
            sessions = cfSessions.value,
            restDays = cfRestDays.value,
            liftDays = cfLiftDays.value,
        ) else null
        return WizardAnswers(
            cardioFirst = cardioFirst,
            daysPerWeek = cardioFirst?.liftDays?.takeIf { it > 0 } ?: daysPerWeek.value,
            split = enumOf(split.value, defaults.split),
            anchorScheme = enumOf(anchorScheme.value, defaults.anchorScheme),
            deadliftVariant = enumOf(deadliftVariant.value, defaults.deadliftVariant),
            cardio = CardioPrefs(
                mode = enumOf(cardioMode.value, defaults.cardio.mode),
                placement = enumOf(cardioPlacement.value, defaults.cardio.placement),
                fiveKGoal = fiveK.value,
            ),
            config = LifterConfig(
                bodyweightLb = bodyweight.value ?: defaults.config.bodyweightLb,
                age = age.value ?: defaults.config.age,
                level = enumOf(level.value, defaults.config.level),
                emphasis = currentEmphasis,
                weightUnit = enumOf(unit.value, WeightUnit.LB),
            ),
            equipment = equipment.value.mapNotNull { name -> Equipment.entries.firstOrNull { it.name == name } }.toSet(),
            lifts = lifts.value.mapNotNull { d ->
                val lb = d.weightLb?.takeIf { it > 0.0 && it.isFinite() } ?: return@mapNotNull null
                val reps = (d.reps ?: StrengthStandards.topSetReps(currentEmphasis)).takeIf { it in 1..99 } ?: return@mapNotNull null
                d.exerciseId to LiftEntry(lb, reps)
            }.toMap(),
        )
    }

    val uiState: StateFlow<WizardUiState> =
        combine(listOf<Flow<Any?>>(
            stepIndex, daysPerWeek, split, anchorScheme, deadliftVariant,
            cardioMode, cardioPlacement, fiveK, bodyweight, age, level, emphasis,
            equipment, lifts, unit, isComplete, firstRun, restoreProgress,
            previewProgram, finishProgress, initialized, cardioFirstChanges,
        )) {
            val restore = restoreProgress.value
            val finish = finishProgress.value
            WizardStateBuilder.buildUiState(
                stepIndex = stepIndex.value,
                answers = currentAnswers(),
                isComplete = isComplete.value,
                restore = WizardRestoreState(offered = firstRun.value, inFlight = restore.inFlight, error = restore.error),
                unit = enumOf(unit.value, WeightUnit.LB),
                distanceUnit = enumOf(distanceUnit.value, DistanceUnit.MI),
                cardioStartByTime = cfByTime.value,
                previewProgram = previewProgram.value,
                finishError = finish.error,
                finishInFlight = finish.inFlight,
                initialized = initialized.value,
                bodyweightLb = bodyweight.value,
                age = age.value,
                liftDrafts = lifts.value,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), WizardUiState(initialized = initialized.value))

    init {
        if (savedState.get<Boolean>(Keys.INITIALIZED) != true) {
            viewModelScope.launch {
                val answers = repo.wizardAnswersFlow.first()
                val isFirstRun = !settings.wizardCompleteFlow.first()
                applyAnswers(answers, prefillAboutYou = settings.hasLifterAnswersFlow.first())
                savedState[Keys.FIRST_RUN] = isFirstRun
                applyAnswer(Keys.UNIT, if (isFirstRun) deviceWeightUnitProvider.defaultUnit().name else settings.unitFlow.first().name)
                applyAnswer(Keys.DISTANCE_UNIT, if (isFirstRun) {
                    DistanceUnit.suggestedFor(deviceWeightUnitProvider.defaultUnit()).name
                } else settings.distanceUnitFlow.first().name)
                if (currentStep() == WizardStep.ROTATION) {
                    previewProgram.value = generatePreview(currentAnswers())
                }
                savedState[Keys.INITIALIZED] = true
            }
        } else if (currentStep() == WizardStep.ROTATION) {
            previewProgram.value = generatePreview(currentAnswers())
        }
    }

    // --- navigation ------------------------------------------------------------

    // onNext/onBack read stepIndex.value (the raw SavedStateHandle-backed flow),
    // never uiState.value: uiState is combine()d asynchronously, so two calls
    // made back-to-back (before the dispatcher processes the first) would both
    // see the same stale step and collapse into a single advance — the same
    // lost-update shape DayViewModel's mutationLock guards against for set
    // edits. SavedStateHandle's own StateFlow has no such lag: a set is visible
    // to the very next read.

    fun onNext() {
        if (!initialized.value) return
        val steps = WizardStateBuilder.steps(currentAnswers())
        val current = stepIndex.value.coerceIn(steps.indices)
        if (steps[current] == WizardStep.ABOUT_YOU && !hasValidAboutYouAnswers()) return
        if (steps[current] == WizardStep.ROTATION) {
            finish()
        } else {
            if (steps[current + 1] == WizardStep.ROTATION) {
                previewProgram.value = generatePreview(currentAnswers())
            }
            savedState[Keys.STEP] = current + 1
        }
    }

    fun onBack() {
        val steps = WizardStateBuilder.steps(currentAnswers())
        val current = stepIndex.value.coerceIn(steps.indices)
        if (current > 0) {
            if (steps[current] == WizardStep.ROTATION) previewProgram.value = null
            savedState[Keys.STEP] = current - 1
        }
    }

    private fun currentStep(): WizardStep = WizardStateBuilder.steps(currentAnswers()).let { steps ->
        steps[stepIndex.value.coerceIn(steps.indices)]
    }

    // --- field setters -----------------------------------------------------------

    fun setFocus(cardio: Boolean) = setAnswer(Keys.FOCUS, cardio)

    fun setCardioFamily(family: CardioFamily) = setCardioActivity(family.activities.first())

    fun setCardioActivity(activity: CardioActivity) = setAnswer(Keys.CF_ACTIVITY, activity.name)

    fun setCardioStartByTime(byTime: Boolean) = setAnswer(Keys.CF_BY_TIME, byTime)

    fun setCardioStartSeconds(seconds: Int) = setAnswer(Keys.CF_SECONDS, seconds.coerceIn(1, 86_400))

    fun setCardioStartMetres(metres: Int) = setAnswer(Keys.CF_METRES, metres.coerceIn(1, 200_000))

    fun setRestDays(days: Int) = setAnswer(Keys.CF_REST_DAYS, days.coerceIn(0, 2))

    fun setCardioSessions(sessions: Int) = setAnswer(Keys.CF_SESSIONS, sessions.coerceIn(1, 6))

    fun setLiftDays(days: Int) {
        require(days in CardioFirst.LIFT_DAY_CHOICES)
        setAnswer(Keys.CF_LIFT_DAYS, days)
        if (days > 0) setDaysPerWeek(days)
    }

    fun setDistanceUnit(value: DistanceUnit) {
        require(value != DistanceUnit.M)
        setAnswer(Keys.DISTANCE_UNIT, value.name)
    }

    fun setEmphasis(value: GoalEmphasis) {
        setAnswer(Keys.EMPHASIS, value.name)
    }

    fun setDaysPerWeek(value: Int) {
        val clamped = value.coerceIn(2, 6)
        setAnswer(Keys.DAYS, clamped)
        val currentSplit = enumOf(split.value, defaults.split)
        setAnswer(Keys.SPLIT, WizardStateBuilder.splitForDays(currentSplit, clamped).name)
    }

    fun setSplit(value: SplitTemplate) {
        setAnswer(Keys.SPLIT, value.name)
    }

    fun setAnchorScheme(value: AnchorScheme) {
        setAnswer(Keys.ANCHOR_SCHEME, value.name)
    }

    fun setDeadliftVariant(value: DeadliftVariant) {
        setAnswer(Keys.DEADLIFT, value.name)
    }

    fun setCardioMode(value: CardioMode) {
        setAnswer(Keys.CARDIO_MODE, value.name)
    }

    fun setCardioPlacement(value: CardioPlacement) {
        setAnswer(Keys.CARDIO_PLACEMENT, value.name)
    }

    fun setFiveK(value: Boolean) {
        setAnswer(Keys.FIVE_K, value)
    }

    fun setBodyweight(value: Double?) {
        setAnswer(Keys.BODYWEIGHT, value?.takeIf { it.isFinite() && it >= 1.0 }?.let(Bodyweight::round))
    }

    fun setUnit(value: WeightUnit) {
        setAnswer(Keys.UNIT, value.name)
    }

    fun setAge(value: Int?) {
        setAnswer(Keys.AGE, value?.takeIf { it >= 1 })
    }

    fun setLevel(value: ExperienceLevel) {
        setAnswer(Keys.LEVEL, value.name)
    }

    fun toggleEquipment(value: Equipment) {
        val current = equipment.value
        setAnswer(Keys.EQUIPMENT, if (value.name in current) current - value.name else current + value.name)
    }

    fun setLiftWeight(exerciseId: String, weightLb: Double?) {
        val draft = lifts.value.firstOrNull { it.exerciseId == exerciseId } ?: WizardLiftDraft(exerciseId, null, null)
        val updated = draft.copy(weightLb = weightLb?.takeIf { it.isFinite() && it > 0.0 })
        setAnswer(Keys.LIFTS, ArrayList(lifts.value.filterNot { it.exerciseId == exerciseId } + updated))
    }

    fun setLiftReps(exerciseId: String, reps: Int?) {
        val draft = lifts.value.firstOrNull { it.exerciseId == exerciseId } ?: WizardLiftDraft(exerciseId, null, null)
        val updated = draft.copy(reps = reps)
        setAnswer(Keys.LIFTS, ArrayList(lifts.value.filterNot { it.exerciseId == exerciseId } + updated))
    }

    // --- finish --------------------------------------------------------------

    /**
     * Creates a new plan from the answers and preview and persists the selected unit,
     * and only then marks the wizard complete (spec §6, D3: the only program
     * creator).
     *
     * Write order is crash-safety, not cosmetics. `wizardComplete=true` is what
     * routes the app to the day screen (D1); if it were set before
     * [PlanRepository.createGeneratedPlan], a process death in the gap would
     * strand the app on an empty program. Setting it last means a crash before it
     * simply re-runs the wizard (the draft survives in [SavedStateHandle]). Mirrors
     * [BackupRepository.importSnapshot]'s own write-before-flag ordering.
     *
     * A first-run wizard that finds `wizardComplete` already true steps aside
     * instead of generating. The app opens on the wizard whenever that flag
     * reads false, so an interrupted restore whose settings half is still
     * pending lands us here over a *restored* program — and generating would
     * select a different plan. [firstRun] is latched at entry, so this can only
     * fire when the flag flipped underneath us; a new-plan wizard on a complete
     * setup never takes this branch.
     *
     * [BackupService.withRestoreLock] finishes any pending recovery before that
     * check and keeps it and the writes it authorizes in one critical section.
     * If recovery fails, the wizard stays open with an error so the lifter can retry.
     * Retries reuse a remembered plan only while it has days, then idempotently
     * publish it, the unit and completion.
     */
    private fun finish() {
        if (WizardStep.ABOUT_YOU in WizardStateBuilder.steps(currentAnswers()) && !hasValidAboutYouAnswers()) return
        if (finishProgress.value.inFlight || isComplete.value || previewProgram.value?.days.isNullOrEmpty()) return
        finishProgress.value = FinishProgress(inFlight = true)
        viewModelScope.launch {
            try {
                backupService.withRestoreLock {
                    if (firstRun.value && settings.wizardCompleteFlow.first()) return@withRestoreLock
                    val answers = currentAnswers()
                    val rememberedId = savedState.get<Long>(Keys.CREATED_PLAN_ID)
                    val planId = if (rememberedId != null && repo.planHasDays(rememberedId)) {
                        rememberedId
                    } else {
                        val createdId = repo.createGeneratedPlan(
                            planName(answers),
                            answers,
                            previewProgram.value ?: generatePreview(answers),
                        )
                        savedState[Keys.CREATED_PLAN_ID] = createdId
                        createdId
                    }
                    repo.publishCreatedPlan(planId, answers, writeLifter = WizardStep.ABOUT_YOU in WizardStateBuilder.steps(answers))
                    settings.setUnit(enumOf(unit.value, WeightUnit.LB))
                    if (answers.cardioFirst != null) settings.setDistanceUnit(enumOf(distanceUnit.value, DistanceUnit.MI))
                    settings.setWizardComplete(true)
                }
                // Either branch leaves the device set up, so the wizard leaves.
                isComplete.value = true
            } catch (e: RestoreInterruption) {
                finishProgress.value = finishProgress.value.copy(error = TransferErrorMessages.of(e))
            } catch (e: IOException) {
                finishProgress.value = finishProgress.value.copy(error = UiText.FileAccessFailure(e.message))
            } finally {
                finishProgress.value = finishProgress.value.copy(inFlight = false)
            }
        }
    }

    private fun planName(answers: WizardAnswers): String {
        val cardio = answers.cardioFirst
        if (cardio != null) {
            val name = context.getString(when (CardioFamily.of(cardio.activity)) {
                CardioFamily.RUN -> R.string.plan_name_running
                CardioFamily.RIDE -> R.string.plan_name_cycling
                CardioFamily.ROW -> R.string.plan_name_rowing
                CardioFamily.WALK -> R.string.plan_name_walking
                CardioFamily.ELLIPTICAL -> R.string.cardio_elliptical
            })
            return if (cardio.liftDays > 0) context.getString(R.string.plan_name_with_lifting, name) else name
        }
        return context.getString(when (answers.split) {
            SplitTemplate.FULL_BODY -> R.string.plan_name_full_body
            SplitTemplate.UPPER_LOWER -> R.string.plan_name_upper_lower
            SplitTemplate.PPL -> R.string.plan_name_ppl
            SplitTemplate.PPLUL -> R.string.plan_name_pplul
        }, answers.daysPerWeek)
    }

    private fun generatePreview(answers: WizardAnswers): Program = ProgramGenerator.generate(answers)

    // --- restore from backup (first run only) ----------------------------------

    /**
     * Restores the picked backup instead of answering the wizard. No confirm
     * dialog: this is only reachable on a first run (see [Keys.FIRST_RUN]), so
     * there is nothing on the device to overwrite. [BackupService] validates the
     * whole file before it writes a byte, so a bad file leaves a fresh install
     * fresh and only puts a message on the screen.
     *
     * Where it lands is the *restored document's* call, not ours. A backup taken
     * mid-first-run carries `wizardComplete = false` and no generated program —
     * leaving for the day screen there would strand it on "Preparing your
     * program…", the same trap [finish]'s write order avoids. So on that one
     * document we stay in the wizard, re-seeded with whatever answers the backup
     * did carry, and the lifter finishes setup from there.
     */
    fun restoreFromBackup(uri: Uri) {
        if (!firstRun.value || restoreProgress.value.inFlight) return
        restoreProgress.value = RestoreProgress(inFlight = true)
        viewModelScope.launch {
            try {
                // A note to carry down the success path: CleanupPending means the
                // restore fully landed and only its bookkeeping is outstanding,
                // so it must not divert us into the failure branches below.
                var note: cloud.trotter.log.strength.ui.text.UiText? = null
                try {
                    // App-scoped for the same reason the Data/Backup screen's
                    // restore is: this ViewModel dies with the wizard, and the
                    // import must not be cut between its two halves.
                    appScope.async(Dispatchers.IO) {
                        context.contentResolver.openInputStream(uri)?.use { backupService.importFrom(it) }
                            ?: throw IOException("no input stream for $uri")
                    }.await()
                } catch (e: RestoreInterruption.CleanupPending) {
                    note = TransferErrorMessages.of(e)
                }
                // Discard the retry id only once Room has been replaced.
                savedState.remove<Long>(Keys.CREATED_PLAN_ID)
                applyAnswers(repo.wizardAnswersFlow.first(), prefillAboutYou = settings.hasLifterAnswersFlow.first())
                applyAnswer(Keys.DISTANCE_UNIT, settings.distanceUnitFlow.first().name)
                if (settings.wizardCompleteFlow.first()) {
                    // Leaving for the day screen; a leftover cleanup note goes
                    // with it. Nothing is owed the user — the app finishes the
                    // tidying itself at the next launch.
                    isComplete.value = true
                } else {
                    restoreProgress.value = RestoreProgress(error = note)
                }
            } catch (e: BackupError) {
                restoreProgress.value = RestoreProgress(error = TransferErrorMessages.of(e))
            } catch (e: RestoreInterruption) {
                when (e) {
                    is RestoreInterruption.SettingsPending,
                    is RestoreInterruption.CleanupPending -> savedState.remove<Long>(Keys.CREATED_PLAN_ID)
                    is RestoreInterruption.NotStarted -> Unit
                }
                restoreProgress.value = RestoreProgress(error = TransferErrorMessages.of(e))
            } catch (e: IOException) {
                restoreProgress.value = RestoreProgress(error = cloud.trotter.log.strength.ui.text.UiText.FileAccessFailure(e.message))
            } catch (e: SecurityException) {
                // A revoked/expired SAF grant surfaces here, not as a crash.
                restoreProgress.value = RestoreProgress(error = cloud.trotter.log.strength.ui.text.UiText.FilePermissionLost)
            }
        }
    }

    // --- helpers ---------------------------------------------------------------

    private fun hasValidAboutYouAnswers(): Boolean =
        bodyweight.value?.let { it.isFinite() && it >= 1.0 } == true && (age.value ?: 0) >= 1

    private fun <T> setAnswer(key: String, value: T) {
        if (!initialized.value) {
            val edited = savedState.get<List<String>>(Keys.EDITED_ANSWERS).orEmpty()
            if (key !in edited) savedState[Keys.EDITED_ANSWERS] = edited + key
        }
        savedState[key] = value
    }

    private fun <T> applyAnswer(key: String, value: T) {
        if (key !in savedState.get<List<String>>(Keys.EDITED_ANSWERS).orEmpty()) savedState[key] = value
    }

    private fun applyAnswers(answers: WizardAnswers, prefillAboutYou: Boolean) {
        val cardio = answers.cardioFirst ?: CardioFirst()
        applyAnswer(Keys.FOCUS, answers.cardioFirst != null)
        applyAnswer(Keys.CF_ACTIVITY, cardio.activity.name)
        applyAnswer(Keys.CF_BY_TIME, cardio.comfortable is Measure.Time)
        applyAnswer(Keys.CF_SECONDS, (cardio.comfortable as? Measure.Time)?.seconds ?: 1_800)
        applyAnswer(Keys.CF_METRES, (cardio.comfortable as? Measure.Distance)?.metres ?: 5_000)
        applyAnswer(Keys.CF_SESSIONS, cardio.sessions)
        applyAnswer(Keys.CF_REST_DAYS, answers.cardioFirst?.restDays ?: 1)
        applyAnswer(Keys.CF_LIFT_DAYS, cardio.liftDays)
        applyAnswer(Keys.DAYS, answers.daysPerWeek)
        applyAnswer(Keys.SPLIT, answers.split.name)
        applyAnswer(Keys.ANCHOR_SCHEME, answers.anchorScheme.name)
        applyAnswer(Keys.DEADLIFT, answers.deadliftVariant.name)
        applyAnswer(Keys.CARDIO_MODE, answers.cardio.mode.name)
        applyAnswer(Keys.CARDIO_PLACEMENT, answers.cardio.placement.name)
        applyAnswer(Keys.FIVE_K, answers.cardio.fiveKGoal)
        applyAnswer(Keys.BODYWEIGHT, answers.config.bodyweightLb.takeIf { prefillAboutYou })
        applyAnswer(Keys.AGE, answers.config.age.takeIf { prefillAboutYou })
        applyAnswer(Keys.LEVEL, answers.config.level.name)
        applyAnswer(Keys.EMPHASIS, answers.config.emphasis.name)
        applyAnswer(Keys.UNIT, answers.config.weightUnit.name)
        applyAnswer(Keys.EQUIPMENT, answers.equipment.map { it.name })
    }

    private inline fun <reified E : Enum<E>> enumOf(name: String, default: E): E =
        enumValues<E>().firstOrNull { it.name == name } ?: default

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
