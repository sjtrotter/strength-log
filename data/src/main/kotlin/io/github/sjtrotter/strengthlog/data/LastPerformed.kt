package io.github.sjtrotter.strengthlog.data

import io.github.sjtrotter.strengthlog.data.db.dao.LastPerformedRow

/** One exercise's most recent completed performance (PLAN.md A1 "last time"
 *  chip on the day screen's exercise cards, issue #14). */
data class LastPerformed(val weightLb: Double, val reps: Int)

/**
 * Reduces [io.github.sjtrotter.strengthlog.data.db.dao.SessionDao.lastPerformedRows]'
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
        result.getOrPut(row.exerciseId) { LastPerformed(row.weightLb, row.reps) }
    }
    return result
}
