package cloud.trotter.log.strength.wear.ui

import androidx.compose.runtime.saveable.mapSaver
import cloud.trotter.log.strength.domain.sync.SetEditDelta
import cloud.trotter.log.strength.domain.sync.WatchSnapshot

/** An attempt belongs to a plan, day, exercise track and round, never a display position. */
data class TimedRepOwner(
    val planId: Long?,
    val dayId: String,
    val programExerciseId: Long,
    val exerciseId: String,
    val slot: String,
    val setIndex: Int,
    val runId: String? = null,
)

enum class TimedRepPhase { RUNNING, COUNT }

/** Root-owned so ambient can remove the interactive dial without losing the attempt. */
data class TimedRepState(
    val owner: TimedRepOwner,
    val totalSeconds: Int,
    val startedAtWallMillis: Long,
    val startedAtElapsedMillis: Long,
    val bootCount: Int,
    val phase: TimedRepPhase = TimedRepPhase.RUNNING,
    val count: Int = 0,
) {
    fun deadline(nowElapsedMillis: Long, nowWallMillis: Long, nowBootCount: Int): Long =
        if (nowBootCount == bootCount) {
            RestTimer.deadlineFrom(startedAtElapsedMillis, totalSeconds)
        } else {
            nowElapsedMillis + (totalSeconds * 1_000L -
                (nowWallMillis - startedAtWallMillis).coerceAtLeast(0L)).coerceAtLeast(0L)
        }

    /** Re-entry after expiry advances silently; arming a past boundary must never buzz. */
    fun resume(
        snap: WatchSnapshot,
        nowElapsedMillis: Long,
        nowWallMillis: Long,
        nowBootCount: Int,
    ): TimedRepState? {
        val valid = reconcile(snap) ?: return null
        val expired = RestTimer.isExpired(deadline(nowElapsedMillis, nowWallMillis, nowBootCount), nowElapsedMillis)
        return if (phase == TimedRepPhase.RUNNING && expired) valid.timeUp() else valid
    }

    fun timeUp(): TimedRepState = copy(phase = TimedRepPhase.COUNT)

    fun matchesRunningAttempt(pressed: TimedRepState?): Boolean =
        phase == TimedRepPhase.RUNNING && this == pressed

    fun turn(detents: Int): TimedRepState = if (phase == TimedRepPhase.COUNT) {
        copy(count = (count.toLong() + detents).coerceIn(0L, 999L).toInt())
    } else this

    /** Validate before rendering, arming, or logging, including after a saved-state restore. */
    fun reconcile(snapshot: WatchSnapshot): TimedRepState? {
        if (owner.planId != snapshot.planId || owner.dayId != snapshot.day.dayId ||
            owner.runId != snapshot.runId) return null
        val exercise = snapshot.day.exercises.find { it.programExerciseId == owner.programExerciseId }
            ?: return null
        if (exercise.exerciseId != owner.exerciseId || exercise.slot != owner.slot ||
            exercise.repWindowSeconds != totalSeconds) return null
        if (owner.setIndex != currentRoundIndex(exercise)) return null
        if (exercise.sets.getOrNull(owner.setIndex)?.done != false) return null
        return this
    }
}

fun startTimedRep(
    snapshot: WatchSnapshot,
    exerciseIndex: Int,
    cardioRunning: Boolean,
    existing: TimedRepState?,
    nowWallMillis: Long,
    nowElapsedMillis: Long,
    bootCount: Int,
): TimedRepState? {
    if (cardioRunning || existing != null) return null
    val exercise = snapshot.day.exercises.getOrNull(exerciseIndex) ?: return null
    val index = currentRoundIndex(exercise)
    if (exercise.repWindowSeconds <= 0 || exercise.sets.getOrNull(index)?.done != false) return null
    return TimedRepState(
        TimedRepOwner(snapshot.planId, snapshot.day.dayId, exercise.programExerciseId,
            exercise.exerciseId, exercise.slot, index, snapshot.runId),
        exercise.repWindowSeconds, nowWallMillis, nowElapsedMillis, bootCount,
    )
}

fun buildTimedRepDelta(state: TimedRepState, snapshot: WatchSnapshot, completedAtMillis: Long): SetEditDelta? {
    if (state.phase != TimedRepPhase.COUNT || state.reconcile(snapshot) == null) return null
    return buildTickDelta(
        dayId = state.owner.dayId,
        programExerciseId = state.owner.programExerciseId,
        setIndex = state.owner.setIndex,
        startedAtMillis = state.startedAtWallMillis,
        completedAtMillis = completedAtMillis,
        snapshot = snapshot,
        reps = state.count,
    )
}

internal val timedRepStateSaver = mapSaver<TimedRepState?>(
    save = { state ->
        if (state == null) emptyMap() else mapOf(
            "planId" to state.owner.planId, "dayId" to state.owner.dayId,
            "exerciseId" to state.owner.programExerciseId, "slot" to state.owner.slot,
            "slotExerciseId" to state.owner.exerciseId,
            "setIndex" to state.owner.setIndex, "seconds" to state.totalSeconds,
            "wall" to state.startedAtWallMillis, "elapsed" to state.startedAtElapsedMillis,
            "boot" to state.bootCount, "phase" to state.phase.name, "count" to state.count,
            "runId" to state.owner.runId,
        )
    },
    restore = { saved ->
        // Older saved attempts lack the slot's content identity and cannot be validated.
        if (saved["slotExerciseId"] !is String) null else TimedRepState(
            TimedRepOwner(saved["planId"] as Long?, saved["dayId"] as String,
                saved["exerciseId"] as Long, saved["slotExerciseId"] as String,
                saved["slot"] as String, saved["setIndex"] as Int, saved["runId"] as String?),
            saved["seconds"] as Int, saved["wall"] as Long, saved["elapsed"] as Long,
            saved["boot"] as Int, TimedRepPhase.valueOf(saved["phase"] as String),
            (saved["count"] as Int).coerceIn(0, 999),
        )
    },
)
