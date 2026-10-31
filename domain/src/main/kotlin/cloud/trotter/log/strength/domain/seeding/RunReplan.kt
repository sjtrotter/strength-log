package cloud.trotter.log.strength.domain.seeding

import cloud.trotter.log.strength.domain.model.LoggedSet

/** Re-plans a run track after a prescription edit: done rows stay, the rest follow [seeded]. */
object RunReplan {
    /** Preserves done rows and the replaced rows' planned identities and watch stamps. */
    fun represcribe(current: List<LoggedSet>, seeded: List<LoggedSet>): List<LoggedSet> =
        (0 until maxOf(current.size, seeded.size)).mapNotNull { index ->
            val c = current.getOrNull(index)
            val s = seeded.getOrNull(index)
            when {
                c?.done == true -> c
                s != null -> s.copy(plannedIndex = c?.plannedIndex, watchEditedAt = c?.watchEditedAt)
                else -> null
            }
        }
}
