package cloud.trotter.log.strength.domain.seeding

import cloud.trotter.log.strength.domain.progression.WorkingRows
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

    /** Seeds must describe each track's current prescription; only empty tracks use them. */
    fun alignPaired(
        main: List<LoggedSet>,
        partner: List<LoggedSet>,
        seededMain: List<LoggedSet>,
        seededPartner: List<LoggedSet>,
    ): Pair<List<LoggedSet>, List<LoggedSet>> {
        val count = maxOf(main.size, partner.size)
        fun pad(current: List<LoggedSet>, seeded: List<LoggedSet>): List<LoggedSet> =
            (0 until count).map { index ->
                current.getOrNull(index) ?: WorkingRows.cleared(listOf(
                    current.lastOrNull() ?: seeded.getOrNull(index) ?: seeded.last(),
                )).single()
            }
        return pad(main, seededMain) to pad(partner, seededPartner)
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
        fun replan(current: List<LoggedSet>, seeded: List<LoggedSet>, keepExisting: Boolean = false): List<LoggedSet> = buildList {
            for (index in kept) {
                val c = current.getOrNull(index)
                val s = seeded.getOrNull(index)
                add(when {
                    c != null && (keepExisting || c.done) -> c
                    s != null && !keepExisting -> s.copy(plannedIndex = c?.plannedIndex, watchEditedAt = c?.watchEditedAt)
                    c != null -> c
                    else -> WorkingRows.cleared(listOf(
                        if (current.isEmpty()) s ?: seeded.last() else last(),
                    )).single()
                })
            }
        }
        return replan(main, seededMain) to replan(partner, seededPartner, keepExisting = true)
    }
}
