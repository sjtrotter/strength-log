package cloud.trotter.log.strength.ui.day

import androidx.lifecycle.SavedStateHandle
import cloud.trotter.log.strength.data.OpenRun
import cloud.trotter.log.strength.data.RunStartResult
import cloud.trotter.log.strength.data.RunWriteResult
import cloud.trotter.log.strength.data.TrackerRepository
import cloud.trotter.log.strength.data.db.entity.Slot
import cloud.trotter.log.strength.rest.PhoneRestController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class WorkoutConflict(val run: OpenRun, val confirming: Boolean)

/** Shared explicit Start policy. The repository arbitrates races with other starters. */
class WorkoutStart(
    private val repo: TrackerRepository,
    private val savedState: SavedStateHandle,
    private val scope: CoroutineScope,
    private val phoneRest: PhoneRestController,
) {
    private val lock = Mutex()
    private val pending = savedState.getStateFlow<DaySelection?>("start_target", null)
    private val conflictId = savedState.getStateFlow<String?>("start_conflict", null)
    private val confirming = savedState.getStateFlow("start_confirming", false)
    private val dropOnly = savedState.getStateFlow("start_drop_only", false)
    private val destinations = Channel<DaySelection>(Channel.BUFFERED)
    val navigation = destinations.receiveAsFlow()
    val conflict = combine(repo.openRunFlow, pending, conflictId, confirming) { open, target, id, confirm ->
        open?.takeIf { target != null && it.id == id }?.let { WorkoutConflict(it, confirm) }
    }.stateIn(scope, SharingStarted.WhileSubscribed(5_000L), null)

    init {
        scope.launch {
            combine(repo.openRunFlow, conflictId) { open, id -> open to id }.collect { (_, id) ->
                if (id != null) lock.withLock {
                    val open = repo.openRunFlow.first()
                    if (conflictId.value == id) {
                        if (open?.id != id) dismiss()
                        else if (!open.draft) savedState["start_drop_only"] = false
                    }
                }
            }
        }
    }

    fun refuseEdit(target: DaySelection, runId: String) {
        savedState["start_target"] = target
        savedState["start_conflict"] = runId
        savedState["start_confirming"] = false
        savedState["start_drop_only"] = true
    }

    fun start(target: DaySelection) {
        scope.launch { lock.withLock { startNow(target) } }
    }

    fun startWithWorkingWeight(target: DaySelection, programExerciseId: Long, weightLb: Double) {
        scope.launch { lock.withLock {
            savedState["start_offer_exercise"] = programExerciseId
            savedState["start_offer_weight_lb"] = weightLb
            startNow(target)
        } }
    }

    private suspend fun startNow(target: DaySelection) {
        val planId = target.planId ?: return
        savedState["start_drop_only"] = false
        when (val result = repo.startRun(planId, target.dayId)) {
            is RunStartResult.OtherRunOpen -> {
                savedState["start_target"] = target
                savedState["start_conflict"] = result.runId
                savedState["start_confirming"] = false
            }
            is RunStartResult.Started, is RunStartResult.AlreadyOpen, RunStartResult.NoStrengthSlots -> {
                val runId = when (result) {
                    is RunStartResult.Started -> result.runId
                    is RunStartResult.AlreadyOpen -> result.runId
                    else -> null
                }
                val exerciseId = savedState.get<Long>("start_offer_exercise")
                val weightLb = savedState.get<Double>("start_offer_weight_lb")
                if (runId != null && exerciseId != null && weightLb != null &&
                    repo.setWorkingWeight(runId, planId, target.dayId, exerciseId, Slot.MAIN, weightLb) == RunWriteResult.Rejected) {
                    val open = repo.openRunFlow.first()
                    if (open != null && (open.planId != planId || open.dayId != target.dayId)) {
                        startNow(target)
                        return
                    }
                }
                dismiss()
                destinations.send(target)
            }
        }
    }

    fun dismiss() {
        savedState["start_drop_only"] = false
        savedState["start_target"] = null
        savedState["start_conflict"] = null
        savedState["start_confirming"] = false
        savedState["start_offer_exercise"] = null
        savedState["start_offer_weight_lb"] = null
    }

    fun confirmDiscard() { savedState["start_confirming"] = true }

    fun resume() {
        val run = conflict.value?.run ?: return
        val dayId = run.dayId ?: return
        dismiss()
        scope.launch { destinations.send(DaySelection(run.planId, dayId)) }
    }

    fun discardAndStart() {
        scope.launch { lock.withLock {
            val target = pending.value ?: return@withLock
            val runId = conflictId.value ?: return@withLock
            val open = repo.openRunFlow.first()
            if (open?.id == runId && !open.draft) savedState["start_drop_only"] = false
            if (!confirming.value && !(open?.id == runId && open.draft)) return@withLock
            // Drop cannot delete a draft promoted by a watch tick after the read above.
            if (open?.id == runId) {
                if (!repo.discardRun(runId, draftOnly = !confirming.value)) return@withLock
                phoneRest.skip()
            }
            if (dropOnly.value) dismiss() else startNow(target)
        } }
    }
}
