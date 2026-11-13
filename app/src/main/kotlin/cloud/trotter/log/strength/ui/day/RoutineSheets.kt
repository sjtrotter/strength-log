package cloud.trotter.log.strength.ui.day

import android.os.Parcelable
import cloud.trotter.log.strength.data.prefs.RoutinePrefs
import cloud.trotter.log.strength.domain.planner.SessionBudget
import cloud.trotter.log.strength.domain.routine.Move
import cloud.trotter.log.strength.domain.routine.Routines
import kotlinx.parcelize.Parcelize

enum class RoutineKind { WARM_UP, COOL_DOWN }

/** Ticks and timer of one open routine sheet. [owner] is the run id on a lift day, else a cardio owner from the
 *  companion. [moves] are Move names frozen at the first interaction; empty means "use the day's current list". */
@Parcelize
data class RoutineProgress(val owner: String, val moves: List<String>, val checked: List<String>,
                           val timerStartedAt: Long?) : Parcelable {
    companion object {
        fun cardioStartOwner(planId: Long, dayId: String) = "cardio-start:$planId:$dayId"
        fun cardioOwner(cardioRunId: String) = "cardio:$cardioRunId"
    }
}

/** What the sheet draws. [runId] is set on lift days: closing records it in DataStore. [startsCardio] is the
 *  cardio-only warm-up, whose commit starts the cardio clock. */
data class RoutineSheetState(val owner: String, val moves: List<Move>, val checked: Set<Move>,
                             val timerSeconds: Int, val timerStartedAt: Long?, val runId: String?,
                             val startsCardio: Boolean = false)

internal object RoutineSheets {
    fun warmUp(state: DayUiState, prefs: RoutinePrefs, progress: RoutineProgress?): RoutineSheetState? {
        if (!prefs.warmUp || state.loading) return null
        val planId = state.planId
        val dayId = state.viewDayId
        if (planId != null && dayId != null && progress?.owner == RoutineProgress.cardioStartOwner(planId, dayId) &&
            state.exercises.isEmpty() && state.cardio != null && state.cardio.run == null) {
            return sheet(progress.owner, state.warmUpMoves, progress, null, warmUp = true, startsCardio = true)
        }
        val runId = state.runId ?: return null
        if (state.runOpen && !state.beforeStart && state.exercises.isNotEmpty() &&
            prefs.warmUpClosedRun != runId && state.doneSets == 0 && state.cardio?.loggedSeconds == null &&
            state.cardio?.run?.startedAt == null) {
            return sheet(runId, state.warmUpMoves, progress, runId, warmUp = true)
        }
        return null
    }

    fun coolDown(state: DayUiState, prefs: RoutinePrefs, progress: RoutineProgress?): RoutineSheetState? {
        if (!prefs.coolDown) return null
        if (progress?.owner?.startsWith("cardio:") == true) {
            return sheet(progress.owner, emptyList(), progress, null, warmUp = false)
        }
        val runId = state.runId ?: return null
        if (state.runOpen && !state.beforeStart && prefs.coolDownClosedRun != runId &&
            state.workoutAllLogged && state.canFinish) {
            return sheet(runId, state.coolDownMoves, progress, runId, warmUp = false)
        }
        return null
    }

    private fun sheet(owner: String, current: List<Move>, progress: RoutineProgress?, runId: String?,
                      warmUp: Boolean, startsCardio: Boolean = false): RoutineSheetState {
        val saved = progress?.takeIf { it.owner == owner }
        val moves = if (saved != null && saved.moves.isNotEmpty()) decode(saved.moves) else current
        return RoutineSheetState(owner, moves, saved?.let { decode(it.checked).toSet() }.orEmpty(),
            if (warmUp) SessionBudget.WARM_UP_SECONDS else Routines.seconds(moves), saved?.timerStartedAt,
            runId, startsCardio)
    }

    private fun decode(names: List<String>): List<Move> = names.mapNotNull { n -> Move.entries.firstOrNull { it.name == n } }
}
