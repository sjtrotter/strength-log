package io.github.sjtrotter.strengthlog.transfer.csv

import io.github.sjtrotter.strengthlog.data.db.entity.SessionSetEntity
import io.github.sjtrotter.strengthlog.data.db.entity.Slot
import io.github.sjtrotter.strengthlog.data.db.entity.WorkoutSessionEntity
import io.github.sjtrotter.strengthlog.domain.model.SetKind
import io.github.sjtrotter.strengthlog.domain.units.WeightUnit
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HistoryCsvWriterTest {

    private val zone = ZoneId.of("UTC")

    private fun set(sessionId: Long, id: Long, exerciseName: String, setIndex: Int, weightLb: Double, reps: Int) =
        SessionSetEntity(
            id = id,
            sessionId = sessionId,
            exerciseId = "bb_back_squat",
            exerciseName = exerciseName,
            slot = Slot.MAIN,
            setIndex = setIndex,
            kind = SetKind.TOP.name,
            weightLb = weightLb,
            reps = reps,
            done = true,
        )

    @Test
    fun `header matches Strong's column layout`() {
        val csv = HistoryCsvWriter.export(emptyList(), emptyList(), WeightUnit.LB, zone)
        val header = Csv.parse(csv).first()
        assertEquals(HISTORY_CSV_HEADER, header)
    }

    @Test
    fun `one row per set with Strong's column values`() {
        val session = WorkoutSessionEntity(1, "A", "Day A", null, 1_720_000_000_000L, 235)
        val sets = listOf(set(1, 1, "Barbell Back Squat", 0, 225.0, 5))

        val csv = HistoryCsvWriter.export(listOf(session), sets, WeightUnit.LB, zone)
        val rows = Csv.parse(csv)

        assertEquals(2, rows.size) // header + one set
        val row = rows[1]
        assertEquals("Day A", row[1])
        assertEquals("", row[2]) // Duration
        assertEquals("Barbell Back Squat", row[3])
        assertEquals("1", row[4]) // Set Order is 1-based
        assertEquals("225", row[5])
        assertEquals("lb", row[6])
        assertEquals("5", row[7])
        assertEquals("", row[8]) // Distance
        assertEquals("", row[11]) // Notes
        assertEquals("", row[13]) // RPE
    }

    @Test
    fun `weight is converted to the display unit and unit column reflects it`() {
        val session = WorkoutSessionEntity(1, "A", "Day A", null, 1_720_000_000_000L, 235)
        val sets = listOf(set(1, 1, "Barbell Back Squat", 0, 220.462262, 5)) // ~100 kg

        val csv = HistoryCsvWriter.export(listOf(session), sets, WeightUnit.KG, zone)
        val row = Csv.parse(csv)[1]

        assertEquals("kg", row[6])
        assertEquals(100.0, row[5].toDouble(), 0.001)
    }

    @Test
    fun `sessions are ordered by completedAt then id, sets by id within a session`() {
        val earlier = WorkoutSessionEntity(2, "A", "Second Inserted But Earlier", null, 1_000L, 235)
        val later = WorkoutSessionEntity(1, "A", "First Inserted But Later", null, 2_000L, 235)
        val sets = listOf(
            set(1, 20, "Second", 0, 100.0, 5),
            set(1, 10, "First", 0, 100.0, 5),
            set(2, 1, "Only", 0, 100.0, 5),
        )

        val csv = HistoryCsvWriter.export(listOf(later, earlier), sets, WeightUnit.LB, zone)
        val rows = Csv.parse(csv).drop(1)

        assertEquals(listOf("Second Inserted But Earlier", "First Inserted But Later", "First Inserted But Later"), rows.map { it[1] })
        assertEquals(listOf("Only", "First", "Second"), rows.map { it[3] })
    }

    @Test
    fun `exporting the same state twice is byte-identical`() {
        val session = WorkoutSessionEntity(1, "A", "Day, \"A\"", null, 1_720_000_000_000L, 235)
        val sets = listOf(set(1, 1, "Barbell Back Squat", 0, 225.0, 5))
        val first = HistoryCsvWriter.export(listOf(session), sets, WeightUnit.LB, zone)
        val second = HistoryCsvWriter.export(listOf(session), sets, WeightUnit.LB, zone)
        assertEquals(first, second)
        assertTrue(first.contains("\"Day, \"\"A\"\"\""), "workout name with comma/quote must be quoted")
    }
}
