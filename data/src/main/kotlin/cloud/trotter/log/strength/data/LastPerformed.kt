package cloud.trotter.log.strength.data

import cloud.trotter.log.strength.data.db.dao.LastPerformedRow
import cloud.trotter.log.strength.data.db.dao.LastPerformedSourceRow
import cloud.trotter.log.strength.data.db.entity.PlanEntity
import cloud.trotter.log.strength.data.db.entity.Slot

/** One exercise's most recent completed performance (PLAN.md A1 "last time"
 *  chip on the day screen's exercise cards). */
data class LastPerformed(val weightLb: Double, val reps: Int, val seconds: Int = 0)

/**
 * Reduces [cloud.trotter.log.strength.data.db.dao.SessionDao.lastPerformedRows]'
 * flat, newest-session-first result into one [LastPerformed] per exercise id —
 * the first row seen for an id is its most recent performance because the
 * query already orders by session recency (ties broken by heaviest weight
 * within that session). An id with no rows is simply absent (never
 * performed). Kept pure and Android-free so the reduction is unit-testable
 * without Room.
 */
fun List<LastPerformedRow>.toLastPerformedByExercise(): Map<String, LastPerformed> {
    val result = LinkedHashMap<String, LastPerformed>()
    for (row in this) {
        result.getOrPut(row.exerciseId) { LastPerformed(row.weightLb, row.reps, row.seconds) }
    }
    return result
}

/** The slot's own last performance, or the exercise's last performance anywhere, with its source. */
data class SlotLast(
    val last: LastPerformed,
    val sameSlot: Boolean,
    val planId: Long?,
    val planName: String?,
    val dayId: String,
)

internal fun belongsToPlan(sessionPlanId: Long?, planId: Long?): Boolean =
    planId != null &&
        (sessionPlanId == planId || (planId == PlanEntity.LEGACY_ID && sessionPlanId == null))

/** Rows arrive newest first, with the same performance tiebreaks as [toLastPerformedByExercise]. */
fun List<LastPerformedSourceRow>.toLastPerformedForSlots(planId: Long, dayId: String): Map<Pair<String, String>, SlotLast> =
    buildMap {
        for ((exerciseId, rows) in this@toLastPerformedForSlots.groupBy { it.exerciseId }) {
            for (slot in listOf(Slot.MAIN, Slot.SS)) {
                val own = rows.firstOrNull { it.slot == slot && belongsToPlan(it.planId, planId) && it.dayId == dayId }
                val row = own ?: rows.first()
                put(exerciseId to slot, SlotLast(
                    LastPerformed(row.weightLb, row.reps, row.seconds), own != null, row.planId, row.planName, row.dayId,
                ))
            }
        }
    }
