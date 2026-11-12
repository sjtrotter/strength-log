package cloud.trotter.log.strength.transfer.csv

import cloud.trotter.log.strength.data.catalog.ExerciseCatalog
import cloud.trotter.log.strength.data.db.entity.SessionSetEntity
import cloud.trotter.log.strength.data.db.entity.Slot
import cloud.trotter.log.strength.data.db.entity.WorkoutSessionEntity
import cloud.trotter.log.strength.domain.model.SetKind
import cloud.trotter.log.strength.domain.model.PrescribedTarget
import cloud.trotter.log.strength.domain.model.Prescription
import cloud.trotter.log.strength.domain.seeding.SetSeeder
import cloud.trotter.log.strength.domain.units.WeightUnit
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

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
    fun `a completed two minute rep window round trips as 42 reps`() {
        val completed = SetSeeder.seedPrescribed(1, Prescription(PrescribedTarget.RepsInTime(120, 50)))
            .single().copy(reps = 42, done = true)
        val session = WorkoutSessionEntity(1, "A", "Rep window", null, 1_720_000_000_000L, 235.0)
        val row = set(1, "bb_back_squat", "Barbell Back Squat", completed.weightLb, completed.reps, completed.seconds)

        val csv = HistoryCsvWriter.export(listOf(session), listOf(row), WeightUnit.LB, zone)

        val cells = Csv.parse(csv)[1]
        assertEquals("", cells[HISTORY_CSV_HEADER.indexOf("Weight")])
        assertEquals("42", cells[HISTORY_CSV_HEADER.indexOf("Reps")])
        assertEquals("", cells[HISTORY_CSV_HEADER.indexOf("Seconds")])
        val preview = HistoryCsvReader.preview(csv, catalog, zone)
        val imported = CsvHistoryImporter.commit(preview, emptyMap()).sessions.single().sets.single()
        assertEquals(Triple(0.0, 42, 0), Triple(imported.weightLb, imported.reps, imported.seconds))
    }

    @Test
    fun `former plank name imports as plank without an unmatched name`() {
        for (name in listOf("Plank / Side Plank", "  plank / SIDE   plank  ")) {
            val csv = "Date,Workout Name,Exercise Name,Weight,Weight Unit,Reps,Seconds\n" +
                "2026-07-01 08:00:00,Day A,$name,,lb,,45\n"

            val preview = HistoryCsvReader.preview(csv, catalog, zone)

            assertTrue(preview.unmatchedNames.isEmpty())
            assertEquals("plank", preview.sessions.single().sets.single().exerciseId)
            val imported = CsvHistoryImporter.commit(preview, emptyMap()).sessions.single().sets.single()
            assertEquals("plank", imported.exerciseId)
            assertEquals(45, imported.seconds)
        }
    }

    @Test
    fun `a current catalog name wins over a former name`() {
        val custom = catalog.get("plank").copy(id = "custom_hold", name = "plank / side plank")
        val catalogWithCustom = ExerciseCatalog(listOf(custom))
        val csv = "Date,Workout Name,Exercise Name,Weight,Weight Unit,Reps,Seconds\n" +
            "2026-07-01 08:00:00,Day A,Plank / Side Plank,,lb,,45\n"

        val preview = HistoryCsvReader.preview(csv, catalogWithCustom, zone)

        assertTrue(preview.unmatchedNames.isEmpty())
        assertEquals("custom_hold", preview.sessions.single().sets.single().exerciseId)
    }

    @Test
    fun `timed and reps sets survive an export then import`() {
        val session = WorkoutSessionEntity(1, "A", "Day A", null, 1_720_000_000_000L, 235.0)
        val sets = listOf(
            set(1, "bb_back_squat", "Barbell Back Squat", 225.0, 5, 0), // WEIGHTED
            set(2, "pushup", "Push-Up", 0.0, 20, 0),                   // REPS
            set(3, "plank", "Plank", 0.0, 0, 45),        // TIMED
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
        assertEquals(listOf("", "", "45"), byName.getValue("Plank").let { listOf(it[weightIdx], it[repsIdx], it[secIdx]) })

        // Round-trip: re-import the exact CSV and confirm each set's values.
        val preview = HistoryCsvReader.preview(csv, catalog, zone)
        val plan = CsvHistoryImporter.commit(preview, emptyMap())
        val imported = plan.sessions.single().sets.associateBy { it.exerciseName }
        assertEquals(Triple(225.0, 5, 0), imported.getValue("Barbell Back Squat").let { Triple(it.weightLb, it.reps, it.seconds) })
        assertEquals(Triple(0.0, 20, 0), imported.getValue("Push-Up").let { Triple(it.weightLb, it.reps, it.seconds) })
        assertEquals(Triple(0.0, 0, 45), imported.getValue("Plank").let { Triple(it.weightLb, it.reps, it.seconds) })
    }
}
