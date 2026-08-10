package cloud.trotter.log.strength.transfer.health

import androidx.health.connect.client.records.ExerciseSessionRecord
import cloud.trotter.log.strength.data.db.entity.CardioSessionEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CardioRecordMapperTest {
    @Test fun `modes map to pinned exercise types`() {
        assertEquals(ExerciseSessionRecord.EXERCISE_TYPE_RUNNING, mapped("OUTDOOR_RUN")?.exerciseType)
        assertEquals(ExerciseSessionRecord.EXERCISE_TYPE_RUNNING_TREADMILL, mapped("TREADMILL")?.exerciseType)
        assertEquals(ExerciseSessionRecord.EXERCISE_TYPE_BIKING, mapped("LOW_IMPACT")?.exerciseType)
        assertNull(mapped("FUTURE_MODE"))
    }

    @Test fun `invalid windows and durations do not map`() {
        assertNull(CardioRecordMapper.toExerciseSession(cardio(start = 2_000, end = 2_000)))
        assertNull(CardioRecordMapper.toExerciseSession(cardio(start = 1_000, end = 60_999)))
        assertNull(CardioRecordMapper.toExerciseSession(cardio(start = 1_000, end = 86_401_001)))
    }

    @Test fun `client identity and version follow dedupe contract`() {
        val record = mapped("OUTDOOR_RUN")!!
        assertEquals("strengthlog-cardio-42", record.metadata.clientRecordId)
        assertEquals(0L, record.metadata.clientRecordVersion)
    }

    private fun mapped(mode: String) = CardioRecordMapper.toExerciseSession(cardio(mode = mode))
    private fun cardio(mode: String = "OUTDOOR_RUN", start: Long = 1_000, end: Long = 61_000) = CardioSessionEntity(
        id = 42, dayId = "A", mode = mode, hard = false, label = "Easy Zone 2",
        startedAt = start, completedAt = end, seconds = 60, stepsCompleted = 0,
    )
}
