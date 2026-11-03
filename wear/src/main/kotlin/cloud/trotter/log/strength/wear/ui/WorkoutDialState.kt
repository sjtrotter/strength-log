package cloud.trotter.log.strength.wear.ui

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.mapSaver
import cloud.trotter.log.strength.domain.sync.ExerciseSwapDelta
import cloud.trotter.log.strength.domain.sync.SetEditDelta
import cloud.trotter.log.strength.domain.sync.WatchSnapshot

internal const val NO_REST = -1
private const val NO_SELECTION = -1

internal data class DeviceTime(val wallMillis: Long, val elapsedMillis: Long, val bootCount: Int)

/** Local ownership and workout progress survive process death; glances do not. */
internal data class WorkoutDialState(
    val planId: Long?,
    val dayId: String,
    val runId: String?,
    val face: DialFace,
    val lifting: Boolean = false,
    val startedAtWallMillis: Long = 0L,
    val startedAtElapsedMillis: Long = 0L,
    val restDeadlineMillis: Long = 0L,
    val restTotalSeconds: Int = 0,
    val restBetweenExercises: Boolean = false,
    val restedSeconds: Int = NO_REST,
    val sessionStartedAtMillis: Long = 0L,
    val sessionEndedAtMillis: Long = 0L,
    val selectedExercise: Int = NO_SELECTION,
    val tickMemory: TickMemory = TickMemory.EMPTY,
    val peek: PeekState? = null,
    val browseDay: Int? = null,
    val swapPreview: SwapPreview? = null,
    val swapExerciseIndex: Int? = null,
) {
    fun exerciseIndex(inputs: WorkoutInputs): Int = inputs.timedRep?.owner?.programExerciseId?.let { id ->
        inputs.snapshot.day.exercises.indexOfFirst { it.programExerciseId == id }.takeIf { it >= 0 }
    } ?: ExerciseSelection.resolve(inputs.snapshot, selectedExercise.takeIf { it >= 0 })

    fun swapPreviewFor(exerciseIndex: Int): SwapPreview? = swapPreview.takeIf { swapExerciseIndex == exerciseIndex }

    private fun clearRest() = copy(restDeadlineMillis = 0L, restTotalSeconds = 0, restBetweenExercises = false)

    private fun startRest(seconds: Int, betweenExercises: Boolean, now: DeviceTime) = copy(
        restTotalSeconds = seconds,
        restBetweenExercises = betweenExercises,
        restDeadlineMillis = RestTimer.deadlineFrom(now.elapsedMillis, seconds),
    )

    fun reduce(event: WorkoutEvent, now: DeviceTime, inputs: WorkoutInputs): WorkoutTransition {
        val snap = inputs.snapshot
        val index = exerciseIndex(inputs)
        val exercise = snap.day.exercises.getOrNull(index)
        val round = exercise?.let(::currentRoundIndex) ?: 0
        val attempt = inputs.timedRep
        val current = if (swapExerciseIndex == index) this else copy(swapPreview = null, swapExerciseIndex = index)
        val effects = mutableListOf<WorkoutEffect>()
        val next = when (event) {
            WorkoutEvent.StartSet -> {
                if (exercise?.repWindowSeconds?.let { it > 0 } == true) {
                    val started = startTimedRep(snap, index, inputs.cardioRunning, attempt,
                        now.wallMillis, now.elapsedMillis, now.bootCount)
                        ?: return WorkoutTransition(current)
                    effects += WorkoutEffect.TimedRepChanged(started)
                    if (sessionStartedAtMillis == 0L) effects += WorkoutEffect.SessionStarted(started.startedAtWallMillis)
                    effects += WorkoutEffect.Haptic(DialHaptics.Cue.START)
                    current.clearRest().copy(lifting = false, startedAtWallMillis = 0L, startedAtElapsedMillis = 0L,
                        restedSeconds = NO_REST,
                        sessionStartedAtMillis = sessionStartedAtMillis.takeIf { it != 0L } ?: started.startedAtWallMillis)
                } else {
                    if (inputs.cardioRunning || attempt != null) return WorkoutTransition(current)
                    if (sessionStartedAtMillis == 0L) effects += WorkoutEffect.SessionStarted(now.wallMillis)
                    effects += WorkoutEffect.Haptic(DialHaptics.Cue.START)
                    current.copy(lifting = true, restedSeconds = NO_REST, startedAtWallMillis = now.wallMillis,
                        startedAtElapsedMillis = now.elapsedMillis,
                        sessionStartedAtMillis = sessionStartedAtMillis.takeIf { it != 0L } ?: now.wallMillis)
                }
            }
            WorkoutEvent.Tick -> {
                val ex = exercise ?: return WorkoutTransition(current)
                val set = ex.sets.getOrNull(round) ?: return WorkoutTransition(current)
                if (set.done) return WorkoutTransition(current)
                // Cancel the ending hold's goal buzz before sending its tick or starting a rest.
                effects += WorkoutEffect.DisarmTimer
                val timedDelta = attempt?.let { buildTimedRepDelta(it, snap, now.wallMillis) }
                if (attempt != null && timedDelta == null) return WorkoutTransition(current, effects)
                effects += WorkoutEffect.SendEdit(timedDelta ?: buildTickDelta(
                    dayId = snap.day.dayId, programExerciseId = ex.programExerciseId, setIndex = round,
                    startedAtMillis = startedAtWallMillis, completedAtMillis = now.wallMillis, snapshot = snap))
                effects += WorkoutEffect.Haptic(DialHaptics.Cue.CONFIRM_TICK)
                effects += WorkoutEffect.TimedRepChanged(null)
                val start = attempt?.startedAtWallMillis ?: startedAtWallMillis
                var completed = current.copy(
                    tickMemory = tickMemory.record(ex.programExerciseId, round,
                        if (start > 0L) ((now.wallMillis - start) / 1000L).toInt() else 0),
                    lifting = false, startedAtWallMillis = 0L, startedAtElapsedMillis = 0L, restedSeconds = NO_REST,
                    sessionStartedAtMillis = sessionStartedAtMillis.takeIf { it != 0L } ?: now.wallMillis,
                    sessionEndedAtMillis = now.wallMillis,
                )
                val flags = ex.sets.mapIndexed { i, s -> i == round || s.done }
                val othersDone = snap.day.exercises.all { other ->
                    other.programExerciseId == ex.programExerciseId || other.sets.all { it.done }
                }
                val advance = decideStreamAdvance(flags, flags.all { it } && othersDone)
                if (advance == StreamAdvance.DayDone && snap.cardio?.loggedStartedAt == null) {
                    completed = completed.copy(face = DialFace.OVERVIEW)
                }
                when {
                    RestTimer.shouldRest(advance, set.restAfterSeconds) ->
                        completed.startRest(set.restAfterSeconds, false, now)
                    RestTimer.shouldRestAfterExercise(advance, set.restAfterSeconds) ->
                        completed.startRest(set.restAfterSeconds, true, now)
                    else -> completed
                }
            }
            is WorkoutEvent.StartRest -> current.startRest(event.seconds, event.betweenExercises, now)
            WorkoutEvent.ClearRest -> current.clearRest()
            WorkoutEvent.SkipRest -> {
                effects += WorkoutEffect.DisarmTimer
                current.clearRest().copy(restedSeconds = NO_REST)
            }
            is WorkoutEvent.RestExpired -> {
                if (restDeadlineMillis != event.deadlineMillis || restDeadlineMillis <= 0L ||
                    !RestTimer.isExpired(restDeadlineMillis, now.elapsedMillis)) return WorkoutTransition(current)
                current.clearRest().copy(restedSeconds = if (restBetweenExercises) NO_REST else restTotalSeconds)
            }
            WorkoutEvent.ResumeRest -> {
                if (restDeadlineMillis > 0L && attempt == null && !inputs.cardioRunning) {
                    effects += WorkoutEffect.ArmRest(restDeadlineMillis, restTotalSeconds)
                }
                current
            }
            WorkoutEvent.ResumeTimedHold -> {
                val goal = exercise?.let { holdGoalSeconds(it, round) } ?: 0
                if (attempt == null && !inputs.cardioRunning && lifting && goal > 0 && startedAtElapsedMillis > 0L) {
                    effects += WorkoutEffect.ArmTimedHold(RestTimer.deadlineFrom(startedAtElapsedMillis, goal), goal)
                }
                current
            }
            is WorkoutEvent.Undo -> {
                val ex = snap.day.exercises.getOrNull(event.target.exerciseIndex) ?: return WorkoutTransition(current)
                effects += WorkoutEffect.DisarmTimer
                effects += WorkoutEffect.SendEdit(buildUndoDelta(snap.day.dayId, ex.programExerciseId,
                    event.target.roundIndex, now.wallMillis, snap))
                effects += WorkoutEffect.Haptic(DialHaptics.Cue.UNDO)
                current.clearRest().copy(restedSeconds = NO_REST, lifting = false,
                    startedAtWallMillis = 0L, startedAtElapsedMillis = 0L,
                    tickMemory = tickMemory.forget(ex.programExerciseId, event.target.roundIndex),
                    selectedExercise = event.target.exerciseIndex)
            }
            WorkoutEvent.ConfirmSwap -> {
                val ex = exercise ?: return WorkoutTransition(current)
                val alternate = SwapPicker.resolve(current.swapPreview?.alternateIndex, ex.alternates.size)
                    ?: return WorkoutTransition(current)
                effects += WorkoutEffect.SendSwap(buildSwapDelta(snap, ex.programExerciseId, ex.alternates[alternate], now.wallMillis))
                effects += WorkoutEffect.Haptic(DialHaptics.Cue.CONFIRM_TICK)
                current.copy(swapPreview = null, tickMemory = tickMemory.forgetExercise(ex.programExerciseId))
            }
            is WorkoutEvent.TurnCrown -> {
                var movement = 0
                val turned = when (event.crown) {
                    DialCrown.SELECT_EXERCISE -> {
                        val selected = ExerciseSelection.move(index, event.detents, snap.day.exercises.size)
                        movement = selected - index
                        current.copy(selectedExercise = selected)
                    }
                    DialCrown.PEEK -> {
                        val from = peek?.roundIndex ?: round
                        val nextPeek = PeekScrub.turn(peek, round, event.detents, exercise?.sets?.size ?: 0, now.elapsedMillis)
                        movement = (nextPeek?.roundIndex ?: from) - from
                        current.copy(peek = nextPeek)
                    }
                    DialCrown.SELECT_ALTERNATE -> {
                        val from = current.swapPreview?.alternateIndex ?: -1
                        val preview = SwapPicker.turn(current.swapPreview, event.detents,
                            exercise?.alternates?.size ?: 0, now.elapsedMillis)
                        movement = (preview?.alternateIndex ?: -1) - from
                        current.copy(swapPreview = preview)
                    }
                    DialCrown.REP_COUNT -> {
                        if (attempt != null) {
                            val counted = attempt.turn(event.detents)
                            effects += WorkoutEffect.TimedRepChanged(counted)
                            movement = counted.count - attempt.count
                        }
                        current
                    }
                    DialCrown.CARDIO_SET -> {
                        movement = event.cardioMovement
                        current
                    }
                    DialCrown.NONE -> current
                }
                repeat(kotlin.math.abs(movement)) { effects += WorkoutEffect.Haptic(DialHaptics.Cue.ROTARY_DETENT) }
                if (movement != event.detents) effects += WorkoutEffect.Haptic(DialHaptics.Cue.BOUNDARY)
                turned
            }
            is WorkoutEvent.ChangeRepCount -> {
                if (attempt != null) effects += WorkoutEffect.TimedRepChanged(attempt.turn(event.detents))
                current
            }
            is WorkoutEvent.Swipe -> when (event.swipe) {
                DialSwipe.NEXT_EXERCISE -> current.copy(selectedExercise = ExerciseSelection.next(index,
                    snap.day.exercises.map { ex -> ex.sets.any { !it.done } }))
                DialSwipe.BROWSE_DAY -> current.copy(browseDay = CycleBrowse.next(browseDay, snap))
                DialSwipe.NONE -> current
            }
            is WorkoutEvent.ShowFace -> current.copy(face = event.face)
            WorkoutEvent.StartCardio -> {
                effects += WorkoutEffect.StartCardio
                current.copy(face = DialFace.WORKOUT)
            }
            WorkoutEvent.StopCardio -> {
                effects += WorkoutEffect.StopCardio
                current
            }
            is WorkoutEvent.StopTimedRep -> {
                if (attempt != null && attempt.matchesRunningAttempt(event.pressed) && attempt.reconcile(snap) != null &&
                    !RestTimer.isExpired(attempt.deadline(now.elapsedMillis, now.wallMillis, now.bootCount), now.elapsedMillis)) {
                    effects += WorkoutEffect.DisarmTimer
                    effects += WorkoutEffect.TimedRepChanged(null)
                    effects += WorkoutEffect.Haptic(DialHaptics.Cue.START)
                    current.copy(face = DialFace.WORKOUT)
                } else current
            }
            is WorkoutEvent.ReconcileSelection -> if (ExerciseSelection.shouldForget(snap,
                selectedExercise.takeIf { it >= 0 }, event.pendingExercises)) current.copy(selectedExercise = NO_SELECTION) else current
            WorkoutEvent.ExerciseChanged -> current
            WorkoutEvent.PeekTimeout -> if (PeekScrub.expired(peek, now.elapsedMillis)) current.copy(peek = null) else current
            WorkoutEvent.SwapTimeout -> if (SwapPicker.expired(current.swapPreview, now.elapsedMillis)) current.copy(swapPreview = null) else current
        }
        return WorkoutTransition(next, effects)
    }
}

internal data class WorkoutInputs(
    val snapshot: WatchSnapshot,
    val timedRep: TimedRepState? = null,
    val cardioRunning: Boolean = false,
)

internal sealed interface WorkoutEvent {
    data object StartSet : WorkoutEvent
    data object Tick : WorkoutEvent
    data class StartRest(val seconds: Int, val betweenExercises: Boolean) : WorkoutEvent
    data object ClearRest : WorkoutEvent
    data object SkipRest : WorkoutEvent
    data class RestExpired(val deadlineMillis: Long) : WorkoutEvent
    data object ResumeRest : WorkoutEvent
    data object ResumeTimedHold : WorkoutEvent
    data class Undo(val target: UndoTarget) : WorkoutEvent
    data object ConfirmSwap : WorkoutEvent
    data class TurnCrown(val crown: DialCrown, val detents: Int, val cardioMovement: Int = 0) : WorkoutEvent
    data class ChangeRepCount(val detents: Int) : WorkoutEvent
    data class Swipe(val swipe: DialSwipe) : WorkoutEvent
    data class ShowFace(val face: DialFace) : WorkoutEvent
    data object StartCardio : WorkoutEvent
    data object StopCardio : WorkoutEvent
    data class StopTimedRep(val pressed: TimedRepState?) : WorkoutEvent
    data class ReconcileSelection(val pendingExercises: Set<Long>) : WorkoutEvent
    data object ExerciseChanged : WorkoutEvent
    data object PeekTimeout : WorkoutEvent
    data object SwapTimeout : WorkoutEvent
}

internal sealed interface WorkoutEffect {
    data class SendEdit(val delta: SetEditDelta) : WorkoutEffect
    data class SendSwap(val delta: ExerciseSwapDelta) : WorkoutEffect
    data class Haptic(val cue: DialHaptics.Cue) : WorkoutEffect
    data class TimedRepChanged(val state: TimedRepState?) : WorkoutEffect
    data class SessionStarted(val wallMillis: Long) : WorkoutEffect
    data object DisarmTimer : WorkoutEffect
    data class ArmRest(val deadlineMillis: Long, val totalSeconds: Int) : WorkoutEffect
    data class ArmTimedHold(val deadlineMillis: Long, val totalSeconds: Int) : WorkoutEffect
    data object StartCardio : WorkoutEffect
    data object StopCardio : WorkoutEffect
}

internal data class WorkoutTransition(
    val state: WorkoutDialState,
    val effects: List<WorkoutEffect> = emptyList(),
)

// rememberSaveable inputs reset a live plan/day/run switch, but do not validate a
// restored bundle. The first available snapshot must own the saved state too.
internal fun workoutDialStateSaver(planId: Long?, dayId: String, runId: String?) = mapSaver(
    save = { state: WorkoutDialState ->
        mapOf(
            "planId" to state.planId,
            "dayId" to state.dayId,
            "runId" to state.runId,
            "face" to state.face.name,
            "lifting" to state.lifting,
            "startedAtWallMillis" to state.startedAtWallMillis,
            "startedAtElapsedMillis" to state.startedAtElapsedMillis,
            "restDeadlineMillis" to state.restDeadlineMillis,
            "restTotalSeconds" to state.restTotalSeconds,
            "restBetweenExercises" to state.restBetweenExercises,
            "restedSeconds" to state.restedSeconds,
            "sessionStartedAtMillis" to state.sessionStartedAtMillis,
            "sessionEndedAtMillis" to state.sessionEndedAtMillis,
            "selectedExercise" to state.selectedExercise,
            "tickMemory" to state.tickMemory.encode(),
        )
    },
    restore = { saved ->
        if (saved["planId"] != planId || saved["dayId"] != dayId || saved["runId"] != runId) {
            null
        } else {
            WorkoutDialState(
                planId, dayId, runId, DialFace.valueOf(saved["face"] as String),
                lifting = saved["lifting"] as Boolean,
                startedAtWallMillis = saved["startedAtWallMillis"] as Long,
                startedAtElapsedMillis = saved["startedAtElapsedMillis"] as Long,
                restDeadlineMillis = saved["restDeadlineMillis"] as Long,
                restTotalSeconds = saved["restTotalSeconds"] as Int,
                restBetweenExercises = saved["restBetweenExercises"] as Boolean,
                restedSeconds = saved["restedSeconds"] as Int,
                sessionStartedAtMillis = saved["sessionStartedAtMillis"] as Long,
                sessionEndedAtMillis = saved["sessionEndedAtMillis"] as Long,
                selectedExercise = saved["selectedExercise"] as Int,
                tickMemory = TickMemory.decode(saved["tickMemory"] as String),
            )
        }
    },
)

// Keep the old map at the registry boundary; stateSaver would wrap it in MutableState.
internal fun workoutDialStateHolderSaver(planId: Long?, dayId: String, runId: String?): Saver<MutableState<WorkoutDialState>, Any> {
    val stateSaver = workoutDialStateSaver(planId, dayId, runId)
    return Saver(
        save = { holder -> with(stateSaver) { save(holder.value) } },
        restore = { value -> stateSaver.restore(value)?.let { mutableStateOf(it) } },
    )
}
