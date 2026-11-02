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
 * so the record keeps its earliest date; a set of another tracking type starts it over.
 * Exercises without a counting set are absent; unknown set kinds are skipped.
 */
fun List<PersonalRecordRow>.toPersonalRecordsByExercise(): Map<String, PersonalRecord> {
    val result = LinkedHashMap<String, PersonalRecord>()
    for ((exerciseId, rows) in groupBy { it.exerciseId }) {
        var standing: LoggedSet? = null
        var bestRow: PersonalRecordRow? = null
        for (row in rows) {
            val kind = try {
                SetKind.valueOf(row.kind)
            } catch (_: IllegalArgumentException) {
                continue
            }
            val candidate = LoggedSet(row.weightLb, row.reps, kind, done = true, seconds = row.seconds)
            if (!PersonalBests.counts(candidate)) continue
            // A change of tracking type (a custom exercise retyped) starts the record over in the newer type.
            val current = standing
            if (current != null && PersonalBests.tracking(candidate) == PersonalBests.tracking(current) &&
                PersonalBests.compare(candidate, current) <= 0
            ) continue
            standing = candidate
            bestRow = row
        }
        bestRow?.let { row ->
            result[exerciseId] = PersonalRecord(exerciseId, row.weightLb, row.reps, row.completedAt, row.seconds)
        }
    }
    return result
}
