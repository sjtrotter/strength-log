package cloud.trotter.log.strength.transfer.health

import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.metadata.Metadata
import cloud.trotter.log.strength.data.decodedCardioMode
import cloud.trotter.log.strength.data.db.entity.CardioSessionEntity
import cloud.trotter.log.strength.domain.model.CardioMode
import java.time.Instant
import java.time.ZoneId

object CardioRecordMapper {
    fun clientRecordId(id: Long): String = "strengthlog-cardio-$id"

    fun toExerciseSession(
        session: CardioSessionEntity,
        zone: ZoneId = ZoneId.systemDefault(),
    ): ExerciseSessionRecord? {
        if (session.completedAt <= session.startedAt) return null
        val duration = session.completedAt - session.startedAt
        if (duration !in 60_000L..86_400_000L) return null
        val type = when (session.decodedCardioMode()) {
            CardioMode.OUTDOOR_RUN -> ExerciseSessionRecord.EXERCISE_TYPE_RUNNING
            CardioMode.TREADMILL -> ExerciseSessionRecord.EXERCISE_TYPE_RUNNING_TREADMILL
            CardioMode.LOW_IMPACT -> ExerciseSessionRecord.EXERCISE_TYPE_BIKING
            CardioMode.NONE, null -> return null
        }
        val start = Instant.ofEpochMilli(session.startedAt)
        val end = Instant.ofEpochMilli(session.completedAt)
        return ExerciseSessionRecord(
            startTime = start,
            startZoneOffset = zone.rules.getOffset(start),
            endTime = end,
            endZoneOffset = zone.rules.getOffset(end),
            exerciseType = type,
            title = session.label,
            metadata = Metadata.manualEntry(clientRecordId(session.id)),
        )
    }
}
