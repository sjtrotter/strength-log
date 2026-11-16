package cloud.trotter.log.strength.ui.wizard

import android.content.Context
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cloud.trotter.log.strength.R
import cloud.trotter.log.strength.data.PlanRepository
import cloud.trotter.log.strength.data.prefs.RestoreInterruption
import cloud.trotter.log.strength.data.prefs.SettingsStore
import cloud.trotter.log.strength.data.serialization.setupAnswers
import cloud.trotter.log.strength.di.ApplicationScope
import cloud.trotter.log.strength.domain.model.Equipment
import cloud.trotter.log.strength.domain.planner.EquipmentPreset
import cloud.trotter.log.strength.domain.planner.LiftExperience
import cloud.trotter.log.strength.domain.planner.Planner
import cloud.trotter.log.strength.domain.planner.SetupAnswers
import cloud.trotter.log.strength.domain.progression.Effort
import cloud.trotter.log.strength.domain.progression.LiftGoal
import cloud.trotter.log.strength.domain.units.WeightUnit
import cloud.trotter.log.strength.transfer.backup.BackupError
import cloud.trotter.log.strength.transfer.backup.BackupService
import cloud.trotter.log.strength.ui.backup.TransferErrorMessages
import cloud.trotter.log.strength.ui.text.UiText
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Keeps every setup answer and the current step in one saved JSON draft.
 *  Start reuses a matching plan under the restore lock and marks setup complete last. */
@HiltViewModel
class SetupFlowViewModel @Inject constructor(
    private val repo: PlanRepository,
    private val savedState: SavedStateHandle,
    @ApplicationContext private val context: Context,
    private val backupService: BackupService,
    @ApplicationScope private val appScope: CoroutineScope,
    private val deviceWeightUnitProvider: DeviceWeightUnitProvider,
    private val settings: SettingsStore,
) : ViewModel() {
    private object Keys {
        const val DRAFT = "setup_draft"
        const val INITIALIZED = "setup_initialized"
        /** Latched at entry: later runs already hold plans and history, so Backup owns their destructive import
         *  behind its overwrite confirmation. Re-reading completion would also hide restore after a failed Start. */
        const val FIRST_RUN = "setup_first_run"
        const val CREATED_PLAN_ID = "setup_created_plan_id"
        const val CREATED_PLAN_UNIT = "setup_created_plan_unit"
    }

    private val initialized = savedState.getStateFlow(Keys.INITIALIZED, false)
    private val firstRun = savedState.getStateFlow(Keys.FIRST_RUN, false)
    private val draftFlow = savedState.getStateFlow<String?>(Keys.DRAFT, null).map { SetupDraftJson.decode(it) ?: SetupDraft() }
    private val previewTick = MutableStateFlow(0)
    private val preview = MutableStateFlow<PlanPreviewUi>(PlanPreviewUi.Loading)
    private var previewAnswers: SetupAnswers? = null
    private val restore = MutableStateFlow(SetupRestoreState())
    private data class FinishProgress(val inFlight: Boolean = false, val error: UiText? = null)
    private val finish = MutableStateFlow(FinishProgress())
    private val complete = MutableStateFlow(false)

    private fun current(): SetupDraft = SetupDraftJson.decode(savedState[Keys.DRAFT]) ?: SetupDraft()
    private fun blocked(): Boolean = !initialized.value || restore.value.inFlight || finish.value.inFlight

    private fun forgetCreatedPlan() {
        savedState.remove<Long>(Keys.CREATED_PLAN_ID)
        savedState.remove<String>(Keys.CREATED_PLAN_UNIT)
    }

    // Read the handle synchronously: combined UI flows can lag two mutations made in the same frame.
    private fun update(transform: (SetupDraft) -> SetupDraft) {
        if (blocked()) return
        val before = current()
        val after = transform(before)
        if (after == before) return
        if (SetupFlowStateBuilder.currentStep(after) != SetupStep.PLAN ||
            SetupFlowStateBuilder.answers(after) != SetupFlowStateBuilder.answers(before)) {
            previewAnswers = null
            preview.value = PlanPreviewUi.Loading
        }
        savedState[Keys.DRAFT] = SetupDraftJson.encode(after)
        previewTick.value += 1
    }

    val uiState: StateFlow<SetupFlowUiState> = combine(listOf<Flow<Any?>>(
        draftFlow, initialized, firstRun, preview, restore, finish, complete,
    )) {
        val draft = current()
        val plan = preview.value.let {
            if (it is PlanPreviewUi.Ready && previewAnswers != SetupFlowStateBuilder.answers(draft)) PlanPreviewUi.Loading else it
        }
        SetupFlowStateBuilder.build(draft, initialized.value, firstRun.value, plan,
            restore.value.copy(offered = firstRun.value), finish.value.inFlight, finish.value.error, complete.value)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SetupFlowUiState())

    init {
        if (!initialized.value || SetupDraftJson.decode(savedState[Keys.DRAFT]) == null) {
            savedState[Keys.INITIALIZED] = false
            viewModelScope.launch {
                val isFirstRun = !settings.wizardCompleteFlow.first()
                val draft = if (isFirstRun) SetupDraft(unit = deviceWeightUnitProvider.defaultUnit()) else prefill()
                savedState[Keys.FIRST_RUN] = isFirstRun
                savedState[Keys.DRAFT] = SetupDraftJson.encode(draft)
                savedState[Keys.INITIALIZED] = true
            }
        }
        viewModelScope.launch {
            combine(draftFlow, previewTick, initialized) { draft, tick, ready ->
                (if (ready && SetupFlowStateBuilder.currentStep(draft) == SetupStep.PLAN) SetupFlowStateBuilder.answers(draft) else null) to tick
            }.distinctUntilChanged().collectLatest { (answers, _) ->
                previewAnswers = null
                preview.value = PlanPreviewUi.Loading
                if (answers != null) {
                    try {
                        val result = SetupFlowStateBuilder.planPreview(repo.previewPlan(answers), answers)
                        previewAnswers = answers
                        preview.value = if (result is PlanPreviewUi.Ready) result.copy(name = planName(answers)) else result
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        preview.value = PlanPreviewUi.Failed
                    }
                }
            }
        }
    }

    private suspend fun prefill(): SetupDraft {
        val unit = settings.unitFlow.first()
        return repo.selectedPlanFlow.first()?.setupAnswers(unit)?.let(SetupFlowStateBuilder::draftFrom) ?: SetupDraft(unit = unit)
    }

    fun onNext() {
        if (blocked()) return
        val draft = current()
        val step = SetupFlowStateBuilder.currentStep(draft)
        if (step == SetupStep.PLAN) start() else update {
            val steps = SetupFlowStateBuilder.steps(it)
            it.copy(step = steps[steps.indexOf(SetupFlowStateBuilder.currentStep(it)) + 1])
        }
    }

    fun onBack() = update {
        val steps = SetupFlowStateBuilder.steps(it)
        it.copy(step = steps[(steps.indexOf(SetupFlowStateBuilder.currentStep(it)) - 1).coerceAtLeast(0)])
    }
    fun reviewAnswers() = update { it.copy(step = SetupStep.EQUIPMENT) }
    fun setSubGoal(value: LiftGoal) = update { it.copy(goal = value) }
    fun setDays(value: Int) = update { it.copy(days = value.coerceIn(1, 6)) }
    fun setMinutes(value: Int) = update { if (value in SetupFlowStateBuilder.minutesOptions) it.copy(minutes = value) else it }
    fun choosePreset(value: EquipmentPreset) = update { it.copy(equipment = value.equipment, mains = null) }
    fun chooseExactly() = update { it.copy(chooseExactly = true) }
    fun toggleEquipment(value: Equipment) = update {
        it.copy(equipment = if (value in it.equipment) it.equipment - value else it.equipment + value, mains = null)
    }
    fun setUnit(value: WeightUnit) = update { it.copy(unit = value) }
    fun setStepOption(row: StepRow, index: Int) = update {
        if (index !in SetupFlowStateBuilder.stepOptionsLb(row, it.unit).indices) it else when (row) {
            StepRow.BAR -> it.copy(barStep = index)
            StepRow.DUMBBELL -> it.copy(dumbbellStep = index)
            StepRow.STACK -> it.copy(stackStep = index)
        }
    }
    fun setExperience(value: LiftExperience) = update { it.copy(experience = value) }
    fun setOlderAdult(value: Boolean) = update { it.copy(olderAdult = value) }
    fun setMain(row: Int, id: String) = update {
        val choices = Planner.mainChoices(it.equipment).getOrNull(row).orEmpty()
        if (id !in choices) it else it.copy(mains = SetupFlowStateBuilder.currentMains(it).filterNot(choices::contains) + id)
    }
    fun setRecentWeight(id: String, lb: Double?) = update {
        it.copy(recent = it.recent + (id to (it.recent[id] ?: RecentDraft()).copy(weightLb = lb?.takeIf { value -> value.isFinite() && value > 0 })))
    }
    fun setRecentReps(id: String, reps: Int?) = update {
        it.copy(recent = it.recent + (id to (it.recent[id] ?: RecentDraft()).copy(reps = reps)))
    }
    fun setRecentEffort(id: String, effort: Effort) = update {
        it.copy(recent = it.recent + (id to (it.recent[id] ?: RecentDraft()).copy(effort = effort)))
    }
    fun setBalanceDays(value: Boolean) = update { it.copy(balanceDays = value) }
    fun dropLift(id: String) = update {
        val mains = SetupFlowStateBuilder.currentMains(it)
        if (mains.size < 2 || id !in mains) it else it.copy(mains = mains - id)
    }
    fun longerSessions() = update {
        it.copy(minutes = SetupFlowStateBuilder.minutesOptions.firstOrNull { minutes -> minutes > it.minutes } ?: it.minutes)
    }
    fun retryPreview() {
        if (blocked()) return
        previewAnswers = null
        preview.value = PlanPreviewUi.Loading
        previewTick.value += 1
    }

    /** Completion is last: a backup taken between writes must stay in setup, never open Today without its plan. */
    private fun start() {
        val draft = current()
        val answers = SetupFlowStateBuilder.answers(draft)
        if (blocked() || complete.value || SetupFlowStateBuilder.currentStep(draft) != SetupStep.PLAN ||
            preview.value !is PlanPreviewUi.Ready || previewAnswers != answers) return
        finish.value = FinishProgress(inFlight = true)
        viewModelScope.launch {
            try {
                backupService.withRestoreLock {
                    if (firstRun.value && settings.wizardCompleteFlow.first()) return@withRestoreLock
                    val remembered = savedState.get<Long>(Keys.CREATED_PLAN_ID)
                    val rememberedPlan = repo.plansFlow.first().firstOrNull { it.id == remembered }
                    // The decoder takes its unit from the caller; plan settings store only steps in lb.
                    val matches = rememberedPlan?.setupAnswers(answers.unit) == answers &&
                        savedState.get<String>(Keys.CREATED_PLAN_UNIT) == answers.unit.name
                    val id = if (rememberedPlan != null && matches &&
                        repo.planHasDays(rememberedPlan.id)) rememberedPlan.id else {
                        repo.createPlannedPlan(planName(answers), answers).also {
                            savedState[Keys.CREATED_PLAN_UNIT] = answers.unit.name
                            savedState[Keys.CREATED_PLAN_ID] = it
                        }
                    }
                    repo.publishPlannedPlan(id)
                    settings.setUnit(draft.unit)
                    settings.setWizardComplete(true)
                }
                complete.value = true
            } catch (e: RestoreInterruption) {
                finish.value = finish.value.copy(error = TransferErrorMessages.of(e))
            } catch (e: IOException) {
                finish.value = finish.value.copy(error = UiText.FileAccessFailure(e.message))
            } finally {
                finish.value = finish.value.copy(inFlight = false)
            }
        }
    }

    private fun planName(answers: SetupAnswers): String = context.resources.getQuantityString(
        R.plurals.setup_flow_plan_name, answers.days, context.getString(subGoalLabel(answers.goal)), answers.days,
    )

    /** No confirmation on first run: there is nothing to overwrite, and validation precedes all writes.
     *  A mid-first-run backup keeps setup open and re-seeds from the restored device at Goal. */
    fun restoreFromBackup(uri: Uri) {
        if (blocked() || !firstRun.value || complete.value) return
        restore.value = SetupRestoreState(inFlight = true)
        viewModelScope.launch {
            try {
                // CleanupPending means the restore landed; only bookkeeping is outstanding.
                var note: UiText? = null
                try {
                    // App-scoped: navigation may destroy this ViewModel between the import's two halves.
                    appScope.async(Dispatchers.IO) {
                        context.contentResolver.openInputStream(uri)?.use { backupService.importFrom(it) }
                            ?: throw IOException("no input stream for $uri")
                    }.await()
                } catch (e: RestoreInterruption.CleanupPending) {
                    note = TransferErrorMessages.of(e)
                }
                // Discard the retry id only once Room has been replaced.
                forgetCreatedPlan()
                if (settings.wizardCompleteFlow.first()) {
                    // Cleanup is retried by startup reconciliation after leaving setup.
                    complete.value = true
                } else {
                    savedState[Keys.DRAFT] = SetupDraftJson.encode(prefill())
                }
                restore.value = SetupRestoreState(error = note)
            } catch (e: BackupError) {
                restore.value = SetupRestoreState(error = TransferErrorMessages.of(e))
            } catch (e: RestoreInterruption) {
                when (e) {
                    is RestoreInterruption.SettingsPending, is RestoreInterruption.CleanupPending -> forgetCreatedPlan()
                    is RestoreInterruption.NotStarted -> Unit
                }
                restore.value = SetupRestoreState(error = TransferErrorMessages.of(e))
            } catch (e: IOException) {
                restore.value = SetupRestoreState(error = UiText.FileAccessFailure(e.message))
            } catch (_: SecurityException) {
                // SAF grants can expire or be revoked after picking the file.
                restore.value = SetupRestoreState(error = UiText.FilePermissionLost)
            }
        }
    }
}
