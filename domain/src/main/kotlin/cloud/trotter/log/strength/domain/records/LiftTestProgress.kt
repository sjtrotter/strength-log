package cloud.trotter.log.strength.domain.records

import cloud.trotter.log.strength.domain.model.LoggedSet
import cloud.trotter.log.strength.domain.model.SetKind

data class LiftSetRow(
    val sessionId: Long,
    val completedAt: Long,
    val exerciseId: String,
    val weightLb: Double,
    val reps: Int,
    val kind: SetKind,
    val done: Boolean,
) {
    val set: LoggedSet get() = LoggedSet(weightLb, reps, kind, done = done)
}

object LiftTestProgress {
    fun best(reps: Int, sets: List<LoggedSet>): Double? {
        require(reps in 1..10)
        return sets.filter { PersonalBests.counts(it) && it.weightLb > 0.0 && it.reps >= reps }
            .maxOfOrNull { it.weightLb }
    }
}
