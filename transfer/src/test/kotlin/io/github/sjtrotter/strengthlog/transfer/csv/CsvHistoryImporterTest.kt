package io.github.sjtrotter.strengthlog.transfer.csv

import io.github.sjtrotter.strengthlog.data.catalog.ExerciseCatalog
import io.github.sjtrotter.strengthlog.data.db.entity.Slot
import io.github.sjtrotter.strengthlog.domain.model.MovementPattern
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** The commit half of the #16 preview/confirm model: building the entities
 *  [io.github.sjtrotter.strengthlog.data.TrackerRepository.importSessionHistory]
 *  writes, once the caller has supplied an approved pattern per unmatched
 *  name. No repository/Room involved — purely a mapping test. */
class CsvHistoryImporterTest {

    private val catalog = ExerciseCatalog.CODE_ONLY

    private val matchedRowText =
        "Date,Workout Name,Exercise Name,Set Order,Weight,Weight Unit,Reps\n" +
            "2026-07-01 08:00:00,Day A,Barbell Back Squat,1,225,lb,5\n" +
            "2026-07-01 08:00:00,Day A,Mystery Move,1,100,lb,10\n"

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
        assertEquals(0, session.bodyweightLb)
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
