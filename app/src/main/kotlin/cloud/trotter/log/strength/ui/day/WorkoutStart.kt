package cloud.trotter.log.strength.ui.day

import androidx.lifecycle.SavedStateHandle
import cloud.trotter.log.strength.data.OpenRun
import cloud.trotter.log.strength.data.RunSeed
import cloud.trotter.log.strength.data.RunStartResult
import cloud.trotter.log.strength.data.TrackerRepository
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
) {
    private val lock = Mutex()
    private val pending = savedState.getStateFlow<DaySelection?>("start_target", null)
    private val conflictId = savedState.getStateFlow<String?>("start_conflict", null)
    private val confirming = savedState.getStateFlow("start_confirming", false)
    private val destinations = Channel<DaySelection>(Channel.BUFFERED)
    val navigation = destinations.receiveAsFlow()
    val conflict = combine(repo.openRunFlow, pending, conflictId, confirming) { open, target, id, confirm ->
        open?.takeIf { target != null && it.id == id }?.let { WorkoutConflict(it, confirm) }
    }.stateIn(scope, SharingStarted.WhileSubscribed(5_000L), null)

    fun start(target: DaySelection) {
        scope.launch { lock.withLock { startNow(target) } }
    }

    private suspend fun startNow(target: DaySelection) {
        val planId = target.planId ?: return
        val slots = repo.slotsFor(planId, target.dayId).first()
        val seeds = repo.daySeeds(planId, target.dayId, slots).map { RunSeed(it.programExerciseId, it.slot, it.sets) }
        when (val result = repo.startRun(planId, target.dayId, seeds)) {
            is RunStartResult.OtherRunOpen -> {
                savedState["start_target"] = target
                savedState["start_conflict"] = result.runId
                savedState["start_confirming"] = false
            }
            is RunStartResult.Started, is RunStartResult.AlreadyOpen, RunStartResult.NoStrengthSlots -> {
                dismiss()
                destinations.send(target)
            }
        }
    }

    fun dismiss() {
        savedState["start_target"] = null
        savedState["start_conflict"] = null
        savedState["start_confirming"] = false
    }

    fun confirmDiscard() { savedState["start_confirming"] = true }

    fun resume() {
        val run = conflict.value?.run ?: return
        val dayId = run.dayId ?: return
        dismiss()
        scope.launch { destinations.send(DaySelection(run.planId, dayId)) }
    }

    fun discardAndStart() {
        if (!confirming.value) return
        val target = pending.value ?: return
        val runId = conflictId.value ?: return
        scope.launch { lock.withLock {
            // Never discard a replacement run if this dialog became stale.
            if (repo.openRunFlow.first()?.id == runId) repo.discardRun(runId)
            startNow(target)
        } }
    }
}
