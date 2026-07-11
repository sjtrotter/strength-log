package io.github.sjtrotter.strengthlog.data

import io.github.sjtrotter.strengthlog.data.db.dao.PersonalRecordRow

/**
 * One exercise's all-time-best completed performance — the day-card "Best"
 * chip (performance-profile.md Phase 1: derived from session history, never
 * stored). [achievedAtMillis] is the session's `completedAt` the record was
 * first set at, kept for a future profile surface even though Phase 1 only
 * renders the weight/reps.
 */
data class PersonalRecord(
    val exerciseId: String,
    val weightLb: Double,
    val reps: Int,
    val achievedAtMillis: Long,
    /** Best hold for a TIMED exercise; 0 for WEIGHTED/REPS. The formatter reads
     *  it per the exercise's tracking type so a timed best renders as its hold. */
    val seconds: Int = 0,
)

/**
 * Reduces [io.github.sjtrotter.strengthlog.data.db.dao.SessionDao.personalRecordRows]'
 * flat result into one [PersonalRecord] per exercise id — the first row seen
 * for an id is its record because the query already orders by heaviest weight
 * (ties broken by more reps, then by which was achieved earliest). An id with
 * no rows is simply absent (never performed). Kept pure and Android-free so
 * the reduction is unit-testable without Room, mirroring
 * [toLastPerformedByExercise].
 */
fun List<PersonalRecordRow>.toPersonalRecordsByExercise(): Map<String, PersonalRecord> {
    val result = LinkedHashMap<String, PersonalRecord>()
    for (row in this) {
        result.getOrPut(row.exerciseId) {
            PersonalRecord(row.exerciseId, row.weightLb, row.reps, row.completedAt, row.seconds)
        }
    }
    return result
}
