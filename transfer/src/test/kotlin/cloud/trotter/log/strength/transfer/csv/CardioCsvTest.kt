package cloud.trotter.log.strength.transfer.csv

import cloud.trotter.log.strength.data.SessionHistorySnapshot
import cloud.trotter.log.strength.data.catalog.ExerciseCatalog
import cloud.trotter.log.strength.data.db.entity.CardioSessionEntity
import cloud.trotter.log.strength.domain.units.WeightUnit
import cloud.trotter.log.strength.domain.generator.CardioBlocks
import cloud.trotter.log.strength.domain.model.CardioActivity
import cloud.trotter.log.strength.domain.model.CardioRole
import cloud.trotter.log.strength.domain.model.CardioSetResult
import cloud.trotter.log.strength.domain.model.CardioSetSpec
import cloud.trotter.log.strength.domain.model.CardioSetStatus
import cloud.trotter.log.strength.domain.model.CardioSuggestion
import cloud.trotter.log.strength.domain.model.DistanceSource
import cloud.trotter.log.strength.domain.model.Measure
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CardioCsvTest {
    @Test
    fun `cardio round-trip routes before exercise matching`() {
        val source = cardio("Easy Zone 2")
        val csv = HistoryCsvWriter.export(
            emptyList(), emptyList(), WeightUnit.LB, ZoneOffset.UTC, listOf(source),
        )
        val row = Csv.parse(csv)[1]
        assertEquals(CARDIO_MARKER, row[HISTORY_CSV_HEADER.indexOf("Set Type")])
        assertEquals("", row[HISTORY_CSV_HEADER.indexOf("Workout Notes")])

        val preview = HistoryCsvReader.preview(csv, ExerciseCatalog(emptyList()), ZoneOffset.UTC)
        assertTrue(preview.sessions.isEmpty())
        assertTrue(preview.unmatchedNames.isEmpty())
        assertEquals("Easy Zone 2", preview.cardioSessions.single().label)
        assertEquals(90, preview.cardioSessions.single().seconds)
    }

    @Test
    fun `cardio-only csv needs no strength columns or weight unit resolution`() {
        val rows = Csv.parse(HistoryCsvWriter.export(
            emptyList(), emptyList(), WeightUnit.LB, ZoneOffset.UTC, listOf(cardio("Tempo")),
        ))
        val omitted = setOf("Weight", "Weight Unit", "Reps")
        val keep = rows.first().indices.filter { rows.first()[it] !in omitted }
        val csv = rows.joinToString("\r\n", postfix = "\r\n") { row -> Csv.writeRow(keep.map { row[it] }) }

        assertEquals("Tempo", HistoryCsvReader.preview(csv, ExerciseCatalog(emptyList()), ZoneOffset.UTC)
            .cardioSessions.single().label)
    }

    @Test
    fun `foreign set types and workout notes cardio text stay strength`() {
        val header = HISTORY_CSV_HEADER
        fun strengthCsv(setType: String, workoutNotes: String = "") = Csv.writeRow(header) + "\r\n" +
            Csv.writeRow(header.map { column -> when (column) {
                "Date" -> "2026-08-10 10:00:00"
                "Workout Name" -> "Day A"
                "Exercise Name" -> "Barbell Back Squat"
                "Set Type" -> setType
                "Weight" -> "225"
                "Weight Unit" -> "lb"
                "Reps" -> "5"
                "Workout Notes" -> workoutNotes
                else -> ""
            } }) + "\r\n"

        listOf("Warm Up", "Failure", "normal", "Cardio", " cardio ").forEach { foreignType ->
            val preview = HistoryCsvReader.preview(strengthCsv(foreignType), ExerciseCatalog(emptyList()), ZoneOffset.UTC)
            assertTrue(preview.cardioSessions.isEmpty(), foreignType)
            assertEquals(1, preview.sessions.size)
        }
        assertEquals(1, HistoryCsvReader.preview(
            strengthCsv("Warm Up", workoutNotes = "cardio"), ExerciseCatalog(emptyList()), ZoneOffset.UTC,
        ).sessions.size)
    }

    @Test
    fun `cardio routing is header-driven after reordering`() {
        val rows = Csv.parse(HistoryCsvWriter.export(
            emptyList(), emptyList(), WeightUnit.LB, ZoneOffset.UTC, listOf(cardio("Intervals")),
        ))
        val order = rows.first().indices.reversed()
        val csv = rows.joinToString("\r\n", postfix = "\r\n") { row -> Csv.writeRow(order.map { row[it] }) }
        assertEquals("Intervals", HistoryCsvReader.preview(csv, ExerciseCatalog(emptyList()), ZoneOffset.UTC)
            .cardioSessions.single().label)
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

        assertTrue(CsvHistoryImporter.commit(preview, emptyMap(), existing).cardioSessions.isEmpty())
        assertEquals(
            1,
            CsvHistoryImporter.commit(preview, emptyMap(), existingHistory = null).cardioSessions.size,
        )
    }

    @Test
    fun `hard fiveK exports eleven honest set rows in position order`() {
        val block = CardioBlocks.fromLegacy(CardioSuggestion("Intervals", "hard", hard = true), true, CardioActivity.ROAD_RUN)
        val sets = block.sets.map { spec ->
            CardioSetResult(spec, CardioSetStatus.FINISHED, (spec.end as Measure.Time).seconds)
        }
        val rows = Csv.parse(exportSets(sets)).drop(1)
        assertEquals(11, rows.size)
        rows.forEachIndexed { index, row ->
            assertEquals(25, row.size)
            assertEquals("cardio", cell(row, "Set Type"))
            assertEquals((index + 1).toString(), cell(row, "Set Order"))
            assertEquals(if (index == 0) "5:00" else "2:00", cell(row, "Target"))
            assertEquals((sets[index].spec.role == CardioRole.WORK).toString(), cell(row, "Hard"))
            assertEquals(sets[index].spec.role.name, cell(row, "Role"))
            assertEquals("0:25:00", cell(row, "Duration"))
            listOf("Weight", "Weight Unit", "Reps", "Distance", "Distance Unit", "Notes", "Workout Notes",
                "RPE", "Steps Completed", "Cardio Mode").forEach { column -> assertEquals("", cell(row, column), column) }
        }
    }

    @Test
    fun `distance result and unknown shortened distance export separately from targets`() {
        val rows = Csv.parse(exportSets(mixedSets())).drop(1)
        val distance = rows[0]
        assertEquals("400", cell(distance, "Distance"))
        assertEquals("m", cell(distance, "Distance Unit"))
        assertEquals("400 m", cell(distance, "Target"))
        assertEquals("1:45", cell(distance, "Aim"))
        assertEquals("MANUAL", cell(distance, "Distance Source"))
        assertEquals("FINISHED", cell(distance, "Status"))
        assertEquals("SHORTENED", cell(rows[1], "Status"))
        listOf("Distance", "Distance Unit", "Distance Source").forEach { column -> assertEquals("", cell(rows[1], column)) }
    }

    @Test
    fun `set round trip preserves every fact and reimport adds nothing`() {
        val sets = mixedSets()
        val source = setSession(sets).copy(planId = 7, planName = "=Run", completedAt = 91_437)
        val csv = HistoryCsvWriter.export(emptyList(), emptyList(), WeightUnit.LB, ZoneOffset.UTC,
            listOf(source), mapOf(source.id to sets))
        val preview = preview(csv)
        assertTrue(preview.sessions.isEmpty())
        assertTrue(preview.unmatchedNames.isEmpty())
        val parsed = preview.cardioSessions.single()
        assertEquals(sets, parsed.sets)
        assertEquals("ROAD_RUN", parsed.activity)
        assertEquals(145, parsed.seconds)
        assertEquals("OUTDOOR_RUN", parsed.mode)
        assertTrue(parsed.hard)
        assertEquals(1, parsed.stepsCompleted)
        assertEquals(parsed.completedAt - 145_000, parsed.startedAt)
        val imported = CsvHistoryImporter.commit(preview, emptyMap()).cardioSessions.single()
        assertEquals(sets, imported.sets)
        assertEquals(parsed.activity, imported.session.activity)
        assertEquals(parsed.seconds, imported.session.seconds)
        assertEquals(parsed.mode, imported.session.mode)
        assertEquals(parsed.hard, imported.session.hard)
        assertEquals(parsed.stepsCompleted, imported.session.stepsCompleted)
        assertNull(imported.session.planId)
        assertNull(imported.session.planName)
        val existing = SessionHistorySnapshot(WeightUnit.LB, emptyList(), emptyList(),
            listOf(source), mapOf(source.id to sets))
        assertTrue(CsvHistoryImporter.commit(preview, emptyMap(), existing).cardioSessions.isEmpty())
        val committed = imported.session.copy(id = 42)
        val committedHistory = existing.copy(cardioSessions = listOf(committed), cardioSets = mapOf(42L to imported.sets))
        assertTrue(CsvHistoryImporter.commit(preview, emptyMap(), committedHistory).cardioSessions.isEmpty())
    }

    @Test
    fun `fingerprint distinguishes activity and every ordered set fact`() {
        val sets = mixedSets()
        val source = setSession(sets)
        val parsed = preview(exportSets(sets))
        val existing = SessionHistorySnapshot(WeightUnit.LB, emptyList(), emptyList(),
            listOf(source), mapOf(source.id to sets))
        val variants = listOf(
            existing.copy(cardioSessions = listOf(source.copy(activity = "TRAIL_RUN"))),
            existing.copy(cardioSets = mapOf(source.id to sets.reversed())),
        ) + listOf(
            sets[0].copy(spec = sets[0].spec.copy(role = CardioRole.RECOVERY)),
            sets[0].copy(spec = sets[0].spec.copy(end = Measure.Distance(500))),
            sets[0].copy(spec = sets[0].spec.copy(aim = Measure.Time(110))),
            sets[0].copy(spec = sets[0].spec.copy(hard = false)),
            sets[0].copy(status = CardioSetStatus.SHORTENED),
            sets[0].copy(elapsedSeconds = 99),
            sets[0].copy(metres = 399),
            sets[0].copy(distanceSource = DistanceSource.GPS),
        ).map { changed -> existing.copy(cardioSets = mapOf(source.id to listOf(changed, sets[1], sets[2]))) }
        variants.forEach { history -> assertEquals(1, CsvHistoryImporter.commit(parsed, emptyMap(), history).cardioSessions.size) }
    }

    @Test
    fun `legacy summary round trips through new columns and ignores old overloaded cells`() {
        val source = cardio("Legacy").copy(mode = "TREADMILL", hard = true, stepsCompleted = 7, activity = "TREADMILL_RUN")
        val csv = HistoryCsvWriter.export(emptyList(), emptyList(), WeightUnit.LB, ZoneOffset.UTC, listOf(source))
        val row = Csv.parse(csv)[1]
        listOf("Set Order", "Distance", "Distance Unit", "Notes", "RPE", "Role", "Target", "Aim", "Status",
            "Distance Source").forEach { column -> assertEquals("", cell(row, column), column) }
        assertEquals("true", cell(row, "Hard"))
        assertEquals("7", cell(row, "Steps Completed"))
        assertEquals("TREADMILL", cell(row, "Cardio Mode"))
        val altered = changeCell(changeCell(changeCell(csv, 1, "Notes", "ignored"), 1, "RPE", "bad"),
            1, "Distance Unit", "bad")
        val imported = CsvHistoryImporter.commit(preview(altered), emptyMap()).cardioSessions.single()
        assertEquals(source.copy(id = 0), imported.session)
        assertTrue(imported.sets.isEmpty())
    }

    @Test
    fun `sets without a known activity export and import as the summary row`() {
        listOf(null, "SWIM").forEach { activity ->
            val sets = setSession(mixedSets())
            val source = sets.copy(activity = activity, startedAt = sets.completedAt - sets.seconds * 1_000L)
            val csv = HistoryCsvWriter.export(emptyList(), emptyList(), WeightUnit.LB, ZoneOffset.UTC,
                listOf(source), mapOf(source.id to mixedSets()))
            val rows = Csv.parse(csv).drop(1).filter { row -> row.any { it.isNotBlank() } }
            assertEquals(1, rows.size, activity.toString())
            assertEquals("", cell(rows.single(), "Role"))
            val imported = CsvHistoryImporter.commit(preview(csv), emptyMap()).cardioSessions.single()
            assertEquals(source.copy(id = 0), imported.session)
            assertTrue(imported.sets.isEmpty())
            val stored = SessionHistorySnapshot(WeightUnit.LB, emptyList(), emptyList(),
                cardioSessions = listOf(source), cardioSets = mapOf(source.id to mixedSets()))
            assertTrue(CsvHistoryImporter.commit(preview(csv), emptyMap(), stored).cardioSessions.isEmpty())
        }
    }

    @Test
    fun `a previous version summary of a session stored with sets is already present`() {
        val sets = setSession(mixedSets())
        val stored = sets.copy(completedAt = 1_786_356_000_000, startedAt = 1_786_356_000_000 - sets.seconds * 1_000L)
        val csv = "Date,Workout Name,Duration,Exercise Name,Set Order,Set Type,Weight,Weight Unit,Reps," +
            "Distance,Distance Unit,Seconds,Notes,Workout Notes,RPE,Plan\r\n" +
            "2026-08-10 10:00:00,A,0:02:25,Sets,,cardio,,,,,${stored.hard},${stored.seconds}," +
            "${stored.mode},,${stored.stepsCompleted},\r\n"
        val history = SessionHistorySnapshot(WeightUnit.LB, emptyList(), emptyList(),
            cardioSessions = listOf(stored), cardioSets = mapOf(stored.id to mixedSets()))
        assertTrue(CsvHistoryImporter.commit(preview(csv), emptyMap(), history).cardioSessions.isEmpty())
    }

    @Test
    fun `summary activity and mode cells cross the formula neutralization boundary`() {
        val source = cardio("Odd").copy(activity = "=1+1", mode = "+SUM(A1)")
        val csv = HistoryCsvWriter.export(emptyList(), emptyList(), WeightUnit.LB, ZoneOffset.UTC, listOf(source))
        val row = Csv.parse(csv)[1]
        assertEquals("'=1+1", cell(row, "Activity"))
        assertEquals("'+SUM(A1)", cell(row, "Cardio Mode"))
        val imported = CsvHistoryImporter.commit(preview(csv), emptyMap()).cardioSessions.single().session
        assertEquals("=1+1", imported.activity)
        assertEquals("+SUM(A1)", imported.mode)
    }

    @Test
    fun `literal sixteen column previous export retains legacy interpretation`() {
        val csv = "Date,Workout Name,Duration,Exercise Name,Set Order,Set Type,Weight,Weight Unit,Reps," +
            "Distance,Distance Unit,Seconds,Notes,Workout Notes,RPE,Plan\r\n" +
            "2026-08-10 10:00:00,A,0:01:30,Legacy,,cardio,,,,,true,90,TREADMILL,,7,Old plan\r\n"
        val parsed = preview(csv).cardioSessions.single()
        assertEquals("A", parsed.dayId)
        assertEquals("Legacy", parsed.label)
        assertEquals("TREADMILL", parsed.mode)
        assertTrue(parsed.hard)
        assertEquals(7, parsed.stepsCompleted)
        assertEquals(90, parsed.seconds)
        assertEquals(parsed.completedAt - 90_000, parsed.startedAt)
        assertNull(parsed.activity)
        assertTrue(parsed.sets.isEmpty())
    }

    @Test
    fun `order one splits adjacent sessions with the same label date and plan`() {
        val sets = mixedSets()
        val first = setSession(sets)
        val secondSets = sets.take(1)
        val second = first.copy(id = 2, seconds = 100)
        val csv = HistoryCsvWriter.export(emptyList(), emptyList(), WeightUnit.LB, ZoneOffset.UTC,
            listOf(first, second), mapOf(first.id to sets, second.id to secondSets))
        val sessions = preview(csv).cardioSessions
        assertEquals(2, sessions.size)
        assertEquals(sets, sessions[0].sets)
        assertEquals(secondSets, sessions[1].sets)
    }

    @Test
    fun `gaps changed session cells activity and duration reject the offending row`() {
        val csv = exportSets(mixedSets())
        listOf("Set Order" to "3", "Date" to "2026-08-10 10:00:00", "Workout Name" to "B",
            "Exercise Name" to "Other", "Plan" to "Other", "Activity" to "TRAIL_RUN", "Duration" to "0:09:00")
            .forEach { (column, value) ->
                val error = assertFailsWith<CsvImportError.MalformedRow> { preview(changeCell(csv, 2, column, value)) }
                assertEquals(3, error.line, column)
            }
        assertEquals(2, assertFailsWith<CsvImportError.MalformedRow> {
            preview(changeCell(csv, 1, "Set Order", "2"))
        }.line)
    }

    @Test
    fun `invalid enums measures hard seconds and measured distance are malformed rows`() {
        val csv = exportSets(mixedSets())
        listOf("Role" to "UNKNOWN", "Role" to "work", "Status" to "unknown", "Distance Source" to "manual",
            "Activity" to "", "Activity" to "road_run", "Target" to "", "Target" to "0:00",
            "Target" to "200001 m", "Aim" to "500 m", "Hard" to "TRUE", "Seconds" to "-1",
            "Seconds" to "86401", "Distance" to "1.5", "Distance" to "-1", "Distance Unit" to "km",
            "Distance Source" to "", "Distance" to "", "Aim" to "999999999999999999999:00")
            .forEach { (column, value) ->
                val error = assertFailsWith<CsvImportError.MalformedRow>("$column=$value") {
                    preview(changeCell(csv, 1, column, value))
                }
                assertEquals(2, error.line)
            }
        val error = assertFailsWith<CsvImportError.MalformedRow> { preview(changeCell(csv, 1, "Aim", "500 m")) }
        assertEquals("aim must be a different kind of measure from end", error.detail)
    }

    @Test
    fun `set sessions allow zero and short elapsed totals but reject totals beyond a day`() {
        val spec = CardioSetSpec(CardioRole.WORK, Measure.Time(60))
        listOf(0, 30, 86_400).forEach { seconds ->
            val sets = listOf(CardioSetResult(spec, CardioSetStatus.SHORTENED, seconds))
            assertEquals(seconds, preview(exportSets(sets)).cardioSessions.single().seconds)
        }
        val sets = listOf(CardioSetResult(spec, CardioSetStatus.FINISHED, 86_400),
            CardioSetResult(spec, CardioSetStatus.FINISHED, 1))
        assertEquals(3, assertFailsWith<CsvImportError.MalformedRow> { preview(exportSets(sets)) }.line)
    }

    @Test
    fun `measure cells format minutes hours and metres with one parser`() {
        listOf(Measure.Time(1500) to "25:00", Measure.Time(3600) to "1:00:00",
            Measure.Time(3900) to "1:05:00", Measure.Distance(400) to "400 m").forEach { (measure, text) ->
            assertEquals(text, formatCardioMeasure(measure))
            assertEquals(measure, parseCardioMeasure(text))
        }
    }

    private fun cell(row: List<String>, column: String): String = row[HISTORY_CSV_HEADER.indexOf(column)]

    private fun changeCell(csv: String, rowIndex: Int, column: String, value: String): String =
        Csv.parse(csv).mapIndexed { index, row ->
            if (index == rowIndex) row.toMutableList().apply { this[HISTORY_CSV_HEADER.indexOf(column)] = value } else row
        }.joinToString("\r\n", postfix = "\r\n", transform = Csv::writeRow)

    private fun preview(csv: String): CsvImportPreview =
        HistoryCsvReader.preview(csv, ExerciseCatalog(emptyList()), ZoneOffset.UTC)

    private fun mixedSets(): List<CardioSetResult> = listOf(
        CardioSetResult(CardioSetSpec(CardioRole.WORK, Measure.Distance(400), Measure.Time(105), hard = true),
            CardioSetStatus.FINISHED, 100, 400, DistanceSource.MANUAL),
        CardioSetResult(CardioSetSpec(CardioRole.COOLDOWN, Measure.Time(60), hard = true), CardioSetStatus.SHORTENED, 45),
        CardioSetResult(CardioSetSpec(CardioRole.RECOVERY, Measure.Time(60)), CardioSetStatus.SKIPPED, 0),
    )

    private fun setSession(sets: List<CardioSetResult>): CardioSessionEntity = cardio("Sets").copy(
        activity = "ROAD_RUN", seconds = sets.sumOf { it.elapsedSeconds },
        hard = sets.any { it.spec.role == CardioRole.WORK && it.spec.hard },
        stepsCompleted = sets.count { it.status == CardioSetStatus.FINISHED },
    )

    private fun exportSets(sets: List<CardioSetResult>): String {
        val session = setSession(sets)
        return HistoryCsvWriter.export(emptyList(), emptyList(), WeightUnit.LB, ZoneOffset.UTC,
            listOf(session), mapOf(session.id to sets))
    }

    private fun cardio(label: String) = CardioSessionEntity(
        id = 1, dayId = "A", mode = "OUTDOOR_RUN", hard = false, label = label,
        startedAt = 1_000, completedAt = 91_000, seconds = 90, stepsCompleted = 1,
    )
}
