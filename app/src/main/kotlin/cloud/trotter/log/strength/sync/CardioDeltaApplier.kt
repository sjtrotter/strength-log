package cloud.trotter.log.strength.sync

import cloud.trotter.log.strength.data.TrackerRepository
import cloud.trotter.log.strength.data.db.entity.CardioSessionEntity
import cloud.trotter.log.strength.domain.model.CardioMode
import cloud.trotter.log.strength.domain.sync.CardioDelta
import cloud.trotter.log.strength.transfer.health.SessionPublisher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Phone-side single-write consumer for completed wrist cardio sessions. */
class CardioDeltaApplier(
    private val repo: TrackerRepository,
    private val markers: AppliedEditMarkers,
    private val sessionPublisher: SessionPublisher,
) {
    enum class Outcome { APPLIED, STALE, INVALID }
    private val lock = Mutex()

    suspend fun apply(delta: CardioDelta): Outcome = lock.withLock {
        if (delta.dayId.isBlank() || delta.label.isBlank() || delta.stamp <= 0L ||
            delta.startedAt < 0L || delta.completedAt <= delta.startedAt ||
            delta.seconds < 0 || delta.stepsCompleted < 0 ||
            CardioMode.entries.none { it.name == delta.mode && it != CardioMode.NONE }
        ) return Outcome.INVALID
        if (delta.stamp <= markers.lastApplied(MARKER_KEY)) return Outcome.STALE

        val duplicate = repo.cardioSessionsFlow.first().any {
            it.dayId == delta.dayId && it.startedAt == delta.startedAt
        }
        if (!duplicate) {
            val id = repo.logCardioSession(
                CardioSessionEntity(
                    dayId = delta.dayId, mode = delta.mode, hard = delta.hard,
                    label = delta.label, startedAt = delta.startedAt,
                    completedAt = delta.completedAt, seconds = delta.seconds,
                    stepsCompleted = delta.stepsCompleted,
                ),
            )
            sessionPublisher.publishCardio(id)
        }
        markers.markApplied(MARKER_KEY, delta.stamp)
        Outcome.APPLIED
    }

    private companion object { const val MARKER_KEY = "cardio:event" }
}
