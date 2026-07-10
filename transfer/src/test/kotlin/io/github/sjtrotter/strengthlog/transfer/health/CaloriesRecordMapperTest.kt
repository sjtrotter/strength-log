package io.github.sjtrotter.strengthlog.transfer.health

import io.github.sjtrotter.strengthlog.data.db.entity.WorkoutSessionEntity
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The pure session → [androidx.health.connect.client.records.ActiveCaloriesBurnedRecord]
 * mapping (session-start-calories brief). Runs under Robolectric only because
 * the androidx.health record types are Android artifacts — no provider or
 * emulator involved (D10), same shape as [SessionRecordMapperTest].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CaloriesRecordMapperTest {

    private val zone = ZoneId.of("America/New_York")

    private fun session(startedAt: Long?, completedAt: Long, bodyweightLb: Int = 200) = WorkoutSessionEntity(
        id = 7, dayId = "A", dayTitle = "Lower", startedAt = startedAt, completedAt = completedAt, bodyweightLb = bodyweightLb,
    )

    @Test
    fun `no recorded start yields no record`() {
        assertNull(CaloriesRecordMapper.toActiveCalories(session(startedAt = null, completedAt = 10_000L), zone))
    }

    @Test
    fun `a duration under the 5-minute floor yields no record`() {
        val start = 0L
        val end = start + 4L * 60_000L // 4 minutes
        assertNull(CaloriesRecordMapper.toActiveCalories(session(start, end), zone))
    }

    @Test
    fun `a duration over the 6-hour ceiling yields no record`() {
        val start = 0L
        val end = start + 6L * 3_600_000L + 1 // 6 hours and 1 ms
        assertNull(CaloriesRecordMapper.toActiveCalories(session(start, end), zone))
    }

    @Test
    fun `exactly 5 minutes and exactly 6 hours both pass the sanity window`() {
        val start = 0L
        assertTrue(CaloriesRecordMapper.toActiveCalories(session(start, start + 5L * 60_000L), zone) != null)
        assertTrue(CaloriesRecordMapper.toActiveCalories(session(start, start + 6L * 3_600_000L), zone) != null)
    }

    @Test
    fun `a stale stamp that outlives its session (negative duration) yields no record`() {
        val completedAt = 10_000L
        assertNull(CaloriesRecordMapper.toActiveCalories(session(startedAt = completedAt + 1, completedAt = completedAt), zone))
    }

    @Test
    fun `kcal is MET times bodyweight in kg times duration in hours`() {
        val start = 0L
        val end = start + 3_600_000L // exactly 1 hour
        val record = CaloriesRecordMapper.toActiveCalories(session(start, end, bodyweightLb = 200), zone)!!
        // 200 lb ~= 90.7185 kg; 5.0 MET * 90.7185 kg * 1 h ~= 453.59 kcal.
        assertEquals(453.59, record.energy.inKilocalories, 0.5)
    }

    @Test
    fun `the record window is the session's raw start and end, never a synthesized one`() {
        val start = 1_000_000L
        val end = start + 3_600_000L
        val record = CaloriesRecordMapper.toActiveCalories(session(start, end), zone)!!
        assertEquals(start, record.startTime.toEpochMilli())
        assertEquals(end, record.endTime.toEpochMilli())
    }

    @Test
    fun `clientRecordId is stable per session and distinct from the exercise record's`() {
        assertEquals("strengthlog-calories-42", CaloriesRecordMapper.clientRecordId(42L))
        assertEquals("strengthlog-calories-42", CaloriesRecordMapper.clientRecordId(42L)) // repeatable
        assertNotEquals(CaloriesRecordMapper.clientRecordId(42L), SessionRecordMapper.clientRecordId(42L))
    }
}
