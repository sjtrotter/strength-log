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

    fun represcribePaired(
        main: List<LoggedSet>,
        partner: List<LoggedSet>,
        seededMain: List<LoggedSet>,
        seededPartner: List<LoggedSet>,
    ): Pair<List<LoggedSet>, List<LoggedSet>> {
        require(seededMain.isNotEmpty() && seededMain.size == seededPartner.size)
        val kept = (0 until maxOf(main.size, partner.size, seededMain.size)).filter { index ->
            index < seededMain.size || main.getOrNull(index)?.done == true || partner.getOrNull(index)?.done == true
        }
        fun replan(current: List<LoggedSet>, seeded: List<LoggedSet>, keepExisting: Boolean = false): List<LoggedSet> = kept.map { index ->
            val c = current.getOrNull(index)
            val s = seeded.getOrNull(index)
            when {
                c != null && (keepExisting || c.done) -> c
                s != null -> s.copy(plannedIndex = c?.plannedIndex, watchEditedAt = c?.watchEditedAt)
                else -> c ?: seeded.last().copy(plannedIndex = null, watchEditedAt = null)
            }
        }
        return replan(main, seededMain) to replan(partner, seededPartner, keepExisting = true)
    }
}
