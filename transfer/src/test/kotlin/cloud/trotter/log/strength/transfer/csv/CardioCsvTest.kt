package cloud.trotter.log.strength.transfer.csv

import cloud.trotter.log.strength.data.SessionHistorySnapshot
import cloud.trotter.log.strength.data.catalog.ExerciseCatalog
import cloud.trotter.log.strength.data.db.entity.CardioSessionEntity
import cloud.trotter.log.strength.domain.units.WeightUnit
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CardioCsvTest {
    @Test
    fun `cardio round-trip routes before exercise matching`() {
        val source = cardio("Easy Zone 2")
        val csv = HistoryCsvWriter.export(
            emptyList(), emptyList(), WeightUnit.LB, ZoneOffset.UTC, listOf(source),
        )
        val row = Csv.parse(csv)[1]
        assertEquals(CARDIO_MARKER, row[12])

        val preview = HistoryCsvReader.preview(csv, ExerciseCatalog(emptyList()), ZoneOffset.UTC)
        assertTrue(preview.sessions.isEmpty())
        assertTrue(preview.unmatchedNames.isEmpty())
        assertEquals("Easy Zone 2", preview.cardioSessions.single().label)
        assertEquals(90, preview.cardioSessions.single().seconds)
    }

    @Test
    fun `formula and apostrophe cardio labels cross only the csv neutralization boundary`() {
        listOf("=1+1", "'=Already literal").forEach { label ->
            val csv = HistoryCsvWriter.export(
                emptyList(), emptyList(), WeightUnit.LB, ZoneOffset.UTC, listOf(cardio(label)),
            )
            val encodedLabel = Csv.parse(csv)[1][3]
            assertTrue(encodedLabel.startsWith("'"))
            assertEquals(
                label,
                HistoryCsvReader.preview(csv, ExerciseCatalog(emptyList()), ZoneOffset.UTC)
                    .cardioSessions.single().label,
            )
        }
    }

    @Test
    fun `re-importing an export skips cardio already present despite millisecond drift`() {
        val held = cardio("Easy Zone 2").copy(completedAt = 91_437, startedAt = 1_437)
        val csv = HistoryCsvWriter.export(
            emptyList(), emptyList(), WeightUnit.LB, ZoneOffset.UTC, listOf(held),
        )
        val preview = HistoryCsvReader.preview(csv, ExerciseCatalog(emptyList()), ZoneOffset.UTC)
        val existing = SessionHistorySnapshot(
            WeightUnit.LB, emptyList(), emptyList(), cardioSessions = listOf(held),
        )

        assertTrue(CsvHistoryImporter.commit(preview, existingHistory = existing).cardioSessions.isEmpty())
        assertEquals(
            1,
            CsvHistoryImporter.commit(preview, existingHistory = null).cardioSessions.size,
        )
    }

    private fun cardio(label: String) = CardioSessionEntity(
        id = 1, dayId = "A", mode = "OUTDOOR_RUN", hard = false, label = label,
        startedAt = 1_000, completedAt = 91_000, seconds = 90, stepsCompleted = 1,
    )
}
