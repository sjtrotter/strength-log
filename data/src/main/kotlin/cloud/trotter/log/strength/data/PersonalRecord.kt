package cloud.trotter.log.strength.data

import cloud.trotter.log.strength.data.db.dao.PersonalRecordRow
import cloud.trotter.log.strength.domain.model.LoggedSet
import cloud.trotter.log.strength.domain.model.SetKind
import cloud.trotter.log.strength.domain.records.PersonalBests

/**
 * One exercise's all-time best under [PersonalBests], derived from session history
 * for the day-card "Best" chip. [achievedAtMillis] is the first session's
 * `completedAt` that reached this performance.
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
 * Reduces the oldest-first [cloud.trotter.log.strength.data.db.dao.SessionDao.personalRecordRows]
 * into records in first-seen exercise order. A strictly better set replaces a record,
 * so the record keeps its earliest date. The newest counting set's tracking type wins;
 * a lift retyped back to an earlier type keeps that type's older history.
 * Exercises without a counting set are absent; unknown set kinds are skipped.
 */
fun List<PersonalRecordRow>.toPersonalRecordsByExercise(): Map<String, PersonalRecord> {
    val result = LinkedHashMap<String, PersonalRecord>()
    for ((exerciseId, rows) in groupBy { it.exerciseId }) {
        val sets = mutableListOf<LoggedSet>()
        val knownRows = mutableListOf<PersonalRecordRow>()
        for (row in rows) {
            val kind = try {
                SetKind.valueOf(row.kind)
            } catch (_: IllegalArgumentException) {
                continue
            }
            sets += LoggedSet(row.weightLb, row.reps, kind, done = true, seconds = row.seconds)
            knownRows += row
        }
        PersonalBests.bestIndex(sets)?.let { index ->
            val row = knownRows[index]
            result[exerciseId] = PersonalRecord(exerciseId, row.weightLb, row.reps, row.completedAt, row.seconds)
        }
    }
    return result
}
