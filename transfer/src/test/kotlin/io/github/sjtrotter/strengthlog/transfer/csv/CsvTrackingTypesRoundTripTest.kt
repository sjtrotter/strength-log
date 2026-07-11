package io.github.sjtrotter.strengthlog.transfer.csv

import io.github.sjtrotter.strengthlog.data.catalog.ExerciseCatalog
import io.github.sjtrotter.strengthlog.data.db.entity.SessionSetEntity
import io.github.sjtrotter.strengthlog.data.db.entity.Slot
import io.github.sjtrotter.strengthlog.data.db.entity.WorkoutSessionEntity
import io.github.sjtrotter.strengthlog.domain.model.SetKind
import io.github.sjtrotter.strengthlog.domain.units.WeightUnit
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * CSV must carry the new tracking types through export and back: a TIMED hold
 * writes the Strong `Seconds` column with a blank Reps, a REPS set writes Reps
 * with a blank Weight, and a WEIGHTED set is unchanged — and importing that same
 * CSV reproduces each set's weight/reps/seconds. The `Seconds` column already
 * existed in the Strong layout, so the header never changes.
 */
class CsvTrackingTypesRoundTripTest {

    private val zone = ZoneId.of("UTC")
    private val catalog = ExerciseCatalog.CODE_ONLY

    private fun set(id: Long, exerciseId: String, name: String, weightLb: Double, reps: Int, seconds: Int) =
        SessionSetEntity(
            id = id,
            sessionId = 1,
            exerciseId = exerciseId,
            exerciseName = name,
            slot = Slot.MAIN,
            setIndex = 0,
            kind = SetKind.WORK.name,
            weightLb = weightLb,
            reps = reps,
            done = true,
            seconds = seconds,
        )

    @Test
    fun `timed and reps sets survive an export then import`() {
        val session = WorkoutSessionEntity(1, "A", "Day A", null, 1_720_000_000_000L, 235)
        val sets = listOf(
            set(1, "bb_back_squat", "Barbell Back Squat", 225.0, 5, 0), // WEIGHTED
            set(2, "pushup", "Push-Up", 0.0, 20, 0),                   // REPS
            set(3, "plank", "Plank / Side Plank", 0.0, 0, 45),        // TIMED
        )

        val csv = HistoryCsvWriter.export(listOf(session), sets, WeightUnit.LB, zone)

        // Column emission: the header is untouched, and each row fills only its type's cells.
        val rows = Csv.parse(csv)
        assertEquals(HISTORY_CSV_HEADER, rows[0])
        val weightIdx = HISTORY_CSV_HEADER.indexOf("Weight")
        val repsIdx = HISTORY_CSV_HEADER.indexOf("Reps")
        val secIdx = HISTORY_CSV_HEADER.indexOf("Seconds")
        val byName = rows.drop(1).associateBy { it[HISTORY_CSV_HEADER.indexOf("Exercise Name")] }
        assertEquals(listOf("225", "5", ""), byName.getValue("Barbell Back Squat").let { listOf(it[weightIdx], it[repsIdx], it[secIdx]) })
        assertEquals(listOf("", "20", ""), byName.getValue("Push-Up").let { listOf(it[weightIdx], it[repsIdx], it[secIdx]) })
        assertEquals(listOf("", "", "45"), byName.getValue("Plank / Side Plank").let { listOf(it[weightIdx], it[repsIdx], it[secIdx]) })

        // Round-trip: re-import the exact CSV and confirm each set's values.
        val preview = HistoryCsvReader.preview(csv, catalog, zone)
        val plan = CsvHistoryImporter.commit(preview, emptyMap())
        val imported = plan.sessions.single().sets.associateBy { it.exerciseName }
        assertEquals(Triple(225.0, 5, 0), imported.getValue("Barbell Back Squat").let { Triple(it.weightLb, it.reps, it.seconds) })
        assertEquals(Triple(0.0, 20, 0), imported.getValue("Push-Up").let { Triple(it.weightLb, it.reps, it.seconds) })
        assertEquals(Triple(0.0, 0, 45), imported.getValue("Plank / Side Plank").let { Triple(it.weightLb, it.reps, it.seconds) })
    }
}
