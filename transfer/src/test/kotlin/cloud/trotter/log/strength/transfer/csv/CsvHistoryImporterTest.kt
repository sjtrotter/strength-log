package cloud.trotter.log.strength.transfer.csv

import cloud.trotter.log.strength.data.SessionHistorySnapshot
import cloud.trotter.log.strength.data.catalog.ExerciseCatalog
import cloud.trotter.log.strength.data.db.entity.CardioSessionEntity
import cloud.trotter.log.strength.data.db.entity.SessionSetEntity
import cloud.trotter.log.strength.data.db.entity.Slot
import cloud.trotter.log.strength.data.db.entity.WorkoutSessionEntity
import cloud.trotter.log.strength.domain.model.MovementPattern
import cloud.trotter.log.strength.domain.units.WeightUnit
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The commit half of the #16 preview/confirm model: building the entities
 *  [cloud.trotter.log.strength.data.TrackerRepository.importSessionHistory]
 *  writes, once the caller has supplied an approved pattern per unmatched
 *  name. No repository/Room involved — purely a mapping test. */
class CsvHistoryImporterTest {

    private val catalog = ExerciseCatalog.CODE_ONLY

    private val matchedRowText =
        "Date,Workout Name,Exercise Name,Set Order,Weight,Weight Unit,Reps\n" +
            "2026-07-01 08:00:00,Day A,Barbell Back Squat,1,225,lb,5\n" +
            "2026-07-01 08:00:00,Day A,Mystery Move,1,100,lb,10\n"

    @Test
    fun `own export matches existing done sets in id order with unchecked sets omitted`() {
        val zone = ZoneId.of("UTC")
        val session = WorkoutSessionEntity(1, "A", "Day A", null, 1_720_000_000_000L, 235)
        val first = SessionSetEntity(1, 1, "bb_back_squat", "Barbell Back Squat", Slot.MAIN, 0, "WORK", 225.0, 5, true)
        val sets = listOf(
            first.copy(id = 3, setIndex = 2, weightLb = 235.0, reps = 3),
            first.copy(id = 2, setIndex = 1, done = false),
            first,
        )
        val csv = HistoryCsvWriter.export(listOf(session), sets, WeightUnit.LB, zone)
        val preview = HistoryCsvReader.preview(csv, catalog, zone)
        val history = SessionHistorySnapshot(WeightUnit.LB, listOf(session), sets)

        val imported = CsvHistoryImporter.commit(preview, emptyMap(), history)

        assertTrue(imported.sessions.isEmpty())
        assertTrue(imported.newCustomExercises.isEmpty())
    }

    @Test
    fun `imports with and without Plan leave strength and cardio plan fields null`() {
        val zone = ZoneId.of("UTC")
        val session = WorkoutSessionEntity(1, "A", "Day A", null, 1_720_000_000_000L, 235, planId = 2, planName = "Test prep")
        val set = SessionSetEntity(1, 1, "bb_back_squat", "Barbell Back Squat", Slot.MAIN, 0, "WORK", 225.0, 5, true)
        val cardio = CardioSessionEntity(
            id = 2, dayId = "A", mode = "TREADMILL", hard = false, label = "Easy",
            startedAt = 1_720_000_000_000L, completedAt = 1_720_000_060_000L, seconds = 60,
            stepsCompleted = 1, planId = 2, planName = "Test prep",
        )
        val csv = HistoryCsvWriter.export(listOf(session), listOf(set), WeightUnit.LB, zone, listOf(cardio))
        val withoutPlan = Csv.parse(csv).joinToString("\r\n", postfix = "\r\n") { row ->
            Csv.writeRow(row.filterIndexed { index, _ -> index != HISTORY_CSV_HEADER.indexOf("Plan") })
        }

        for (text in listOf(csv, withoutPlan)) {
            val preview = HistoryCsvReader.preview(text, catalog, zone)
            val imported = CsvHistoryImporter.commit(preview, emptyMap())

            assertEquals(1, imported.sessions.size)
            assertEquals(1, imported.cardioSessions.size)
            assertNull(imported.sessions.single().session.planId)
            assertNull(imported.sessions.single().session.planName)
            assertNull(imported.cardioSessions.single().session.planId)
            assertNull(imported.cardioSessions.single().session.planName)
        }
    }

    @Test
    fun `commit without approving every unmatched name is rejected and nothing is built`() {
        val preview = HistoryCsvReader.preview(matchedRowText, catalog)
        assertFailsWith<CsvImportError.MissingApproval> { CsvHistoryImporter.commit(preview, emptyMap()) }
    }

    @Test
    fun `commit creates one custom exercise per approved unmatched name`() {
        val preview = HistoryCsvReader.preview(matchedRowText, catalog)
        val plan = CsvHistoryImporter.commit(preview, mapOf("Mystery Move" to MovementPattern.H_PULL))

        assertEquals(1, plan.newCustomExercises.size)
        val custom = plan.newCustomExercises.first()
        assertEquals("Mystery Move", custom.name)
        assertEquals(MovementPattern.H_PULL.name, custom.pattern)
        assertTrue(custom.id.startsWith(ExerciseCatalog.CUSTOM_ID_PREFIX))
    }

    @Test
    fun `approval matching is case and whitespace insensitive`() {
        val preview = HistoryCsvReader.preview(matchedRowText, catalog)
        val plan = CsvHistoryImporter.commit(preview, mapOf("  mystery   MOVE  " to MovementPattern.H_PULL))
        assertEquals(1, plan.newCustomExercises.size)
    }

    @Test
    fun `every set resolves to a real exerciseId, matched or newly created`() {
        val preview = HistoryCsvReader.preview(matchedRowText, catalog)
        val plan = CsvHistoryImporter.commit(preview, mapOf("Mystery Move" to MovementPattern.H_PULL))

        val allSets = plan.sessions.flatMap { it.sets }
        assertEquals(2, allSets.size)
        val customId = plan.newCustomExercises.single().id
        assertTrue(allSets.any { it.exerciseId == "bb_back_squat" })
        assertTrue(allSets.any { it.exerciseId == customId })
        assertTrue(allSets.all { it.done && it.slot == Slot.MAIN })
    }

    @Test
    fun `session rows carry no real bodyweight or program day`() {
        val preview = HistoryCsvReader.preview(matchedRowText, catalog)
        val plan = CsvHistoryImporter.commit(preview, mapOf("Mystery Move" to MovementPattern.H_PULL))

        val session = plan.sessions.single().session
        assertEquals("Day A", session.dayTitle)
        // Not 0 (#171): a fabricated bodyweight poisons the calorie estimate and
        // used to make every later full backup unrestorable.
        assertNull(session.bodyweightLb)
        assertEquals(0L, session.id)
    }

    @Test
    fun `a fully matched preview needs no approvals`() {
        val text = "Date,Workout Name,Exercise Name,Weight,Weight Unit,Reps\n" +
            "2026-07-01 08:00:00,Day A,Barbell Back Squat,225,lb,5\n"
        val preview = HistoryCsvReader.preview(text, catalog)
        val plan = CsvHistoryImporter.commit(preview, emptyMap())
        assertTrue(plan.newCustomExercises.isEmpty())
        assertEquals("bb_back_squat", plan.sessions.single().sets.single().exerciseId)
    }
}
