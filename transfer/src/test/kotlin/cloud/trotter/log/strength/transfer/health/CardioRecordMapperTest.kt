package cloud.trotter.log.strength.transfer.health

import androidx.health.connect.client.records.ExerciseSessionRecord
import cloud.trotter.log.strength.data.db.entity.CardioSessionEntity
import cloud.trotter.log.strength.domain.model.CardioActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CardioRecordMapperTest {
    @Test fun `plan title preserves fallback client identity and exercise type`() {
        val original = cardio()
        val withoutPlan = CardioRecordMapper.toExerciseSession(original)!!
        val withPlan = CardioRecordMapper.toExerciseSession(original.copy(planId = 2, planName = "Test prep"))!!

        assertEquals("Easy Zone 2", withoutPlan.title)
        assertEquals("Test prep · Easy Zone 2", withPlan.title)
        assertEquals("strengthlog-cardio-42", withPlan.metadata.clientRecordId)
        assertEquals(withoutPlan.metadata.clientRecordId, withPlan.metadata.clientRecordId)
        assertEquals(0L, withPlan.metadata.clientRecordVersion)
        assertEquals(withoutPlan.exerciseType, withPlan.exerciseType)
    }

    @Test fun `modes map to pinned exercise types`() {
        assertEquals(ExerciseSessionRecord.EXERCISE_TYPE_RUNNING, mapped("OUTDOOR_RUN")?.exerciseType)
        assertEquals(ExerciseSessionRecord.EXERCISE_TYPE_RUNNING_TREADMILL, mapped("TREADMILL")?.exerciseType)
        assertEquals(ExerciseSessionRecord.EXERCISE_TYPE_BIKING, mapped("LOW_IMPACT")?.exerciseType)
        assertNull(mapped("FUTURE_MODE"))
    }

    @Test fun `every activity overrides the legacy mode without changing client identity`() {
        val types = mapOf(
            CardioActivity.ROAD_RUN to ExerciseSessionRecord.EXERCISE_TYPE_RUNNING,
            CardioActivity.TRAIL_RUN to ExerciseSessionRecord.EXERCISE_TYPE_RUNNING,
            CardioActivity.TRACK_RUN to ExerciseSessionRecord.EXERCISE_TYPE_RUNNING,
            CardioActivity.TREADMILL_RUN to ExerciseSessionRecord.EXERCISE_TYPE_RUNNING_TREADMILL,
            CardioActivity.OUTDOOR_WALK to ExerciseSessionRecord.EXERCISE_TYPE_WALKING,
            CardioActivity.TREADMILL_WALK to ExerciseSessionRecord.EXERCISE_TYPE_WALKING,
            CardioActivity.OUTDOOR_RIDE to ExerciseSessionRecord.EXERCISE_TYPE_BIKING,
            CardioActivity.STATIONARY_BIKE to ExerciseSessionRecord.EXERCISE_TYPE_BIKING_STATIONARY,
            CardioActivity.ELLIPTICAL to ExerciseSessionRecord.EXERCISE_TYPE_ELLIPTICAL,
            CardioActivity.ROWER to ExerciseSessionRecord.EXERCISE_TYPE_ROWING_MACHINE,
        )
        assertEquals(CardioActivity.entries.toSet(), types.keys)
        val legacy = CardioRecordMapper.toExerciseSession(cardio())!!
        types.forEach { (activity, type) ->
            val record = CardioRecordMapper.toExerciseSession(cardio(mode = "NONE").copy(activity = activity.name))!!
            assertEquals(activity.name, type, record.exerciseType)
            assertEquals(legacy.metadata.clientRecordId, record.metadata.clientRecordId)
            assertEquals(legacy.metadata.clientRecordVersion, record.metadata.clientRecordVersion)
            assertTrue(record.segments.isEmpty())
            assertTrue(record.laps.isEmpty())
        }
        assertNull(CardioRecordMapper.toExerciseSession(cardio().copy(activity = "FUTURE")))
        assertNull(mapped("NONE"))
    }

    @Test fun `invalid windows and durations do not map`() {
        assertNull(CardioRecordMapper.toExerciseSession(cardio(start = 2_000, end = 2_000)))
        assertNull(CardioRecordMapper.toExerciseSession(cardio(start = 1_000, end = 60_999)))
        assertNull(CardioRecordMapper.toExerciseSession(cardio(start = 1_000, end = 86_401_001)))
    }

    @Test fun `boundary durations map inclusively`() {
        assertEquals(60_000L, span(cardio(start = 1_000, end = 61_000)))
        assertEquals(86_400_000L, span(cardio(start = 1_000, end = 86_401_000)))
    }

    private fun span(session: CardioSessionEntity): Long? =
        CardioRecordMapper.toExerciseSession(session)?.let { it.endTime.toEpochMilli() - it.startTime.toEpochMilli() }

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
