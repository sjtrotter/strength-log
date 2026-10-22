package cloud.trotter.log.strength.sync

import cloud.trotter.log.strength.data.TrackerRepository
import cloud.trotter.log.strength.data.db.entity.CardioSessionEntity
import cloud.trotter.log.strength.domain.model.CardioActivity
import cloud.trotter.log.strength.domain.model.CardioMode
import cloud.trotter.log.strength.domain.sync.CardioDelta
import cloud.trotter.log.strength.transfer.health.SessionPublisher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Phone-side single-write consumer for completed wrist cardio sessions. */
class CardioDeltaApplier(
    private val repo: TrackerRepository,
    private val ackLog: CardioAckLog,
    private val sessionPublisher: SessionPublisher,
) {
    enum class Outcome { APPLIED, DUPLICATE, INVALID }
    private val lock = Mutex()

    /**
     * Idempotence is CONTENT identity (dayId + startedAt — the same key the
     * phone's own STOP uses), never stamp order: fire-and-forget sends carry no
     * ordering contract, so an older stamp arriving late must still insert.
     * The ack log records each processed stamp and preserves the highest stamp
     * for old watches. Every outcome records its stamp, INVALID included: a
     * delta this phone can never apply must be acked and dropped, or the watch
     * re-sends it forever. Recording happens after repository work returns,
     * outside any Room transaction.
     * Its explicit plan survives a phone plan switch; a null plan keeps the old
     * selected-plan behavior.
     */
    suspend fun apply(delta: CardioDelta): Outcome = lock.withLock {
        val sets = runCatching { delta.sets.map { it.toDomain() } }.getOrNull()
        if (sets == null || (delta.activity != null && CardioActivity.entries.none { it.name == delta.activity }) ||
            delta.dayId.isBlank() || delta.label.isBlank() || delta.label.length > TrackerRepository.CARDIO_LABEL_MAX_LENGTH || delta.stamp <= 0L ||
            delta.startedAt < 0L || delta.completedAt <= delta.startedAt ||
            delta.seconds < 0 || delta.stepsCompleted < 0 ||
            CardioMode.entries.none { it.name == delta.mode && it != CardioMode.NONE }
        ) {
            ackLog.record(delta.stamp)
            return Outcome.INVALID
        }

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
                    planId = delta.planId, activity = delta.activity,
                ), sets, finishDay = delta.finishesDay,
            )
            sessionPublisher.publishCardio(id)
        }
        ackLog.record(delta.stamp)
        if (duplicate) Outcome.DUPLICATE else Outcome.APPLIED
    }

    companion object { const val MARKER_KEY = "cardio:event" }
}
