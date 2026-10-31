package cloud.trotter.log.strength.domain.seeding

import cloud.trotter.log.strength.domain.model.LoggedSet
import cloud.trotter.log.strength.domain.model.SetKind

/** Working values retained after a run closes. */
object CarryForward {
    /** Results removed: done, both timestamps, plannedIndex, watchEditedAt. Other values kept. */
    fun cleared(sets: List<LoggedSet>): List<LoggedSet> = sets.map {
        it.copy(done = false, startedAtMillis = null, completedAtMillis = null, plannedIndex = null, watchEditedAt = null)
    }

    /** Next time's working values from a finished run's [planned] and [actual] rows. */
    fun next(planned: List<LoggedSet>, actual: List<LoggedSet>): List<LoggedSet> {
        val cleared = cleared(actual)
        val plannedTop = planned.firstOrNull { it.kind == SetKind.TOP }
        val actualTopIndex = cleared.indexOfFirst { it.kind == SetKind.TOP }
        return if (plannedTop != null && actualTopIndex >= 0 && plannedTop.weightLb != cleared[actualTopIndex].weightLb) {
            SetEditor.editWeight(cleared, actualTopIndex, cleared[actualTopIndex].weightLb)
        } else cleared
    }
}
