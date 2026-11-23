package cloud.trotter.log.strength.data

import cloud.trotter.log.strength.data.db.dao.LastPerformedRow
import cloud.trotter.log.strength.data.db.dao.LastPerformedSourceRow
import cloud.trotter.log.strength.data.db.entity.PlanEntity
import cloud.trotter.log.strength.domain.model.LoggedSet
import cloud.trotter.log.strength.domain.model.SetKind

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

internal fun belongsToPlan(sessionPlanId: Long?, planId: Long?): Boolean =
    planId != null &&
        (sessionPlanId == planId || (planId == PlanEntity.LEGACY_ID && sessionPlanId == null))

/**
 * Reduces [cloud.trotter.log.strength.data.db.dao.SessionDao.lastPerformedSourceRows]'
 * flat, newest-session-first result into the newest session's working sets per
 * exercise id. The first row picks the session; its non-RAMP rows are returned
 * in slot and set order. An id with no rows is simply absent.
 */
fun List<LastPerformedSourceRow>.toLastSetsByExercise(): Map<String, List<LoggedSet>> =
    groupBy { it.exerciseId }.mapValues { (_, rows) ->
        val sessionId = rows.first().sessionId
        rows.filter { it.sessionId == sessionId && it.kind != SetKind.RAMP.name }
            .sortedWith(compareBy({ it.slot }, { it.setIndex })).map { it.toLoggedSet() }
    }

/** [planName] is null on the plan's own days and when blank. */
data class LastSource(val dayId: String, val planName: String?)

/** Uses the newest session, as [toLastSetsByExercise] does, unless it ran on the shown day of this plan. */
fun List<LastPerformedSourceRow>.toLastSourcesElsewhere(planId: Long, dayId: String): Map<String, LastSource> =
    groupBy { it.exerciseId }.mapNotNull { (exerciseId, rows) ->
        val row = rows.first()
        val samePlan = belongsToPlan(row.planId, planId)
        if (samePlan && row.dayId == dayId) null
        else exerciseId to LastSource(row.dayId, row.planName?.takeIf { !samePlan && it.isNotBlank() })
    }.toMap()

/** What every "Last" line reads, per exercise id: its newest session from any day or plan (#641, #645). [sources] holds
 *  only exercises whose newest session ran somewhere other than the shown day of this plan. */
data class LastTimes(
    val performed: Map<String, LastPerformed> = emptyMap(),
    val sets: Map<String, List<LoggedSet>> = emptyMap(),
    val dates: Map<String, Long> = emptyMap(),
    val sources: Map<String, LastSource> = emptyMap(),
)

private fun LastPerformedSourceRow.toLoggedSet(): LoggedSet = LoggedSet(
    weightLb, reps, SetKind.entries.firstOrNull { it.name == kind } ?: SetKind.WORK,
    done = true, seconds = seconds,
)
