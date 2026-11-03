package cloud.trotter.log.strength.ui.day

import cloud.trotter.log.strength.data.prefs.UndoTarget
import cloud.trotter.log.strength.domain.model.LoggedSet

internal object WorkoutUndo {
    fun matches(row: LoggedSet, target: UndoTarget): Boolean {
        val after = target.after
        return row.done && row.weightLb == after.weightLb && row.reps == after.reps && row.seconds == after.seconds &&
            row.completedAtMillis == target.loggedAtMillis
    }

    fun revert(current: List<LoggedSet>, target: UndoTarget, before: LoggedSet): List<LoggedSet>? {
        val row = current.getOrNull(target.index) ?: return null
        if (!matches(row, target)) return null
        return current.mapIndexed { index, set ->
            if (index != target.index) set else before.copy(
                done = false, startedAtMillis = null, completedAtMillis = null,
                // Replayed watch ticks must stay stale after Undo.
                watchEditedAt = listOfNotNull(before.watchEditedAt, set.watchEditedAt).maxOrNull(),
            )
        }
    }
}
