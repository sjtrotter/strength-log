package io.github.sjtrotter.strengthlog.transfer.csv

import io.github.sjtrotter.strengthlog.data.catalog.ExerciseCatalog
import io.github.sjtrotter.strengthlog.domain.model.MovementPattern
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Parses the checked-in Strong/Hevy fixtures (`src/test/resources/csv`) plus
 * hand-built edge cases: header-driven (not positional) column mapping,
 * per-column-implied vs. companion-column weight units, inferred Set Order,
 * exercise-name matching, and every typed rejection path.
 */
class HistoryCsvReaderTest {

    private val zone = ZoneId.of("UTC")
    private val catalog = ExerciseCatalog.CODE_ONLY

    private fun fixture(name: String): String =
        checkNotNull(javaClass.classLoader?.getResourceAsStream("csv/$name")) { "missing fixture $name" }
            .use { it.readBytes().toString(Charsets.UTF_8) }

    @Test
    fun `parses the Strong fixture into two sessions`() {
        val preview = HistoryCsvReader.preview(fixture("strong_export.csv"), catalog, zone)

        assertEquals(2, preview.sessions.size)
        val dayA = preview.sessions.first { it.dayTitle == "Day A" }
        assertEquals(3, dayA.sets.size)
        assertEquals(listOf(0, 1), dayA.sets.filter { it.exerciseName == "Barbell Back Squat" }.map { it.setIndex })
        assertEquals("bb_back_squat", dayA.sets.first { it.exerciseName == "Barbell Back Squat" }.exerciseId)
        assertEquals(225.0, dayA.sets.first().weightLb, 0.001)
    }

    @Test
    fun `an exercise name with a comma round-trips through the fixture's quoting`() {
        val preview = HistoryCsvReader.preview(fixture("strong_export.csv"), catalog, zone)
        val dayB = preview.sessions.first { it.dayTitle == "Day B" }
        assertEquals("Squat, Low Bar", dayB.sets.first().exerciseName)
    }

    @Test
    fun `an unmatched Strong exercise name gets a pattern suggestion`() {
        val preview = HistoryCsvReader.preview(fixture("strong_export.csv"), catalog, zone)
        assertEquals(listOf("Squat, Low Bar"), preview.unmatchedNames.map { it.name })
        assertEquals(MovementPattern.SQUAT_BILATERAL, preview.unmatchedNames.first().suggestedPattern)
        assertTrue(preview.sessions.first { it.dayTitle == "Day B" }.sets.all { it.exerciseId == null })
    }

    @Test
    fun `parses the Hevy fixture despite reordered header-driven and unit-embedded columns`() {
        val preview = HistoryCsvReader.preview(fixture("hevy_export.csv"), catalog, zone)

        assertEquals(2, preview.sessions.size)
        val dayA = preview.sessions.first { it.dayTitle == "Day A" }
        assertEquals(3, dayA.sets.size)
        // Set Order column is absent in the Hevy fixture: two "Barbell Back
        // Squat" rows must still get inferred, distinct, 0-based indices.
        assertEquals(listOf(0, 1), dayA.sets.filter { it.exerciseName == "Barbell Back Squat" }.map { it.setIndex }.sorted())
        // Weight (kg) header carries no separate Weight Unit column.
        assertEquals(225.0, dayA.sets.first { it.exerciseName == "Barbell Back Squat" }.weightLb, 0.5)
    }

    @Test
    fun `an unmatched Hevy exercise name gets a pattern suggestion`() {
        val preview = HistoryCsvReader.preview(fixture("hevy_export.csv"), catalog, zone)
        assertEquals(listOf("Sled Leg Press Machine"), preview.unmatchedNames.map { it.name })
    }

    // --- header-driven mapping -------------------------------------------------

    @Test
    fun `column order does not matter`() {
        val text = "Reps,Date,Exercise Name,Workout Name,Weight,Weight Unit\n5,2026-07-01 08:00:00,Barbell Back Squat,Day A,225,lb\n"
        val preview = HistoryCsvReader.preview(text, catalog, zone)
        assertEquals(1, preview.sessions.size)
        assertEquals("bb_back_squat", preview.sessions.first().sets.first().exerciseId)
    }

    @Test
    fun `exercise name matching ignores case and extra whitespace`() {
        val text = "Date,Workout Name,Exercise Name,Weight,Weight Unit,Reps\n" +
            "2026-07-01 08:00:00,Day A,  barbell   BACK squat ,225,lb,5\n"
        val preview = HistoryCsvReader.preview(text, catalog, zone)
        assertTrue(preview.isFullyMatched)
        assertEquals("bb_back_squat", preview.sessions.first().sets.first().exerciseId)
    }

    // --- rejection paths --------------------------------------------------------

    @Test
    fun `a missing required column is rejected`() {
        val text = "Date,Workout Name,Weight,Weight Unit,Reps\n2026-07-01 08:00:00,Day A,225,lb,5\n"
        val e = assertFailsWith<CsvImportError.MissingColumns> { HistoryCsvReader.preview(text, catalog, zone) }
        assertTrue(e.missing.contains("Exercise Name"))
    }

    @Test
    fun `a weight column with no resolvable unit is rejected`() {
        val text = "Date,Workout Name,Exercise Name,Weight,Reps\n2026-07-01 08:00:00,Day A,Barbell Back Squat,225,5\n"
        val e = assertFailsWith<CsvImportError.AmbiguousWeightUnit> { HistoryCsvReader.preview(text, catalog, zone) }
        assertEquals("Weight", e.header)
    }

    @Test
    fun `an unparsable date is rejected with its line number`() {
        val text = "Date,Workout Name,Exercise Name,Weight,Weight Unit,Reps\nnot-a-date,Day A,Barbell Back Squat,225,lb,5\n"
        val e = assertFailsWith<CsvImportError.MalformedRow> { HistoryCsvReader.preview(text, catalog, zone) }
        assertEquals(2, e.line)
    }

    @Test
    fun `an unparsable weight is rejected`() {
        val text = "Date,Workout Name,Exercise Name,Weight,Weight Unit,Reps\n2026-07-01 08:00:00,Day A,Barbell Back Squat,heavy,lb,5\n"
        assertFailsWith<CsvImportError.MalformedRow> { HistoryCsvReader.preview(text, catalog, zone) }
    }

    @Test
    fun `an unknown weight unit token is rejected`() {
        val text = "Date,Workout Name,Exercise Name,Weight,Weight Unit,Reps\n2026-07-01 08:00:00,Day A,Barbell Back Squat,225,stone,5\n"
        assertFailsWith<CsvImportError.MalformedRow> { HistoryCsvReader.preview(text, catalog, zone) }
    }

    @Test
    fun `a file with only a header is rejected as empty`() {
        val text = "Date,Workout Name,Exercise Name,Weight,Weight Unit,Reps\n"
        assertFailsWith<CsvImportError.Empty> { HistoryCsvReader.preview(text, catalog, zone) }
    }

    @Test
    fun `a completely empty file is rejected as empty`() {
        assertFailsWith<CsvImportError.Empty> { HistoryCsvReader.preview("", catalog, zone) }
    }

    @Test
    fun `an oversized file is rejected before parsing`() {
        val text = fixture("strong_export.csv")
        val e = assertFailsWith<CsvImportError.TooLarge> {
            HistoryCsvReader.preview(text, catalog, zone, maxBytes = 32)
        }
        assertEquals(32, e.maxBytes)
    }

    // --- untrusted-input hardening (PR #48 review) ------------------------------

    @Test
    fun `a row truncated before its Weight Unit cell is a typed error, not a crash`() {
        // 6-column header (Weight Unit last); the data row carries only 5 fields.
        val text = "Date,Workout Name,Exercise Name,Weight,Reps,Weight Unit\n" +
            "2026-07-01 08:00:00,Day A,Barbell Back Squat,225,5\n"
        val e = assertFailsWith<CsvImportError.MalformedRow> { HistoryCsvReader.preview(text, catalog, zone) }
        assertEquals(2, e.line)
    }

    @Test
    fun `a leading UTF-8 BOM is stripped so the first header still matches`() {
        val text = "\uFEFF" + fixture("strong_export.csv")
        val preview = HistoryCsvReader.preview(text, catalog, zone)
        assertEquals(2, preview.sessions.size)
    }

    @Test
    fun `a negative weight is rejected`() {
        val text = "Date,Workout Name,Exercise Name,Weight,Weight Unit,Reps\n" +
            "2026-07-01 08:00:00,Day A,Barbell Back Squat,-5,lb,5\n"
        assertFailsWith<CsvImportError.MalformedRow> { HistoryCsvReader.preview(text, catalog, zone) }
    }

    @Test
    fun `negative reps are rejected`() {
        val text = "Date,Workout Name,Exercise Name,Weight,Weight Unit,Reps\n" +
            "2026-07-01 08:00:00,Day A,Barbell Back Squat,225,lb,-3\n"
        assertFailsWith<CsvImportError.MalformedRow> { HistoryCsvReader.preview(text, catalog, zone) }
    }

    @Test
    fun `a non-finite weight is rejected`() {
        for (bad in listOf("Infinity", "NaN", "1e400")) {
            val text = "Date,Workout Name,Exercise Name,Weight,Weight Unit,Reps\n" +
                "2026-07-01 08:00:00,Day A,Barbell Back Squat,$bad,lb,5\n"
            assertFailsWith<CsvImportError.MalformedRow>("expected '$bad' to be rejected") {
                HistoryCsvReader.preview(text, catalog, zone)
            }
        }
    }

    @Test
    fun `cardio and bodyweight rows are skipped or zero-defaulted, not rejected`() {
        val preview = HistoryCsvReader.preview(fixture("strong_mixed.csv"), catalog, zone)

        assertTrue(preview.isFullyMatched)
        val dayA = preview.sessions.single { it.dayTitle == "Day A" }
        // The cardio row (blank weight AND reps) is dropped; the loaded squat and
        // the blank-weight bodyweight pull-up survive.
        assertEquals(2, dayA.sets.size)
        val pullup = dayA.sets.single { it.exerciseName == "Pull-Up / Chin-Up" }
        assertEquals(0.0, pullup.weightLb, 0.0) // bodyweight → weight defaults to 0
        assertEquals(8, pullup.reps)
        val squat = dayA.sets.single { it.exerciseName == "Barbell Back Squat" }
        assertEquals(225.0, squat.weightLb, 0.001)
    }

    @Test
    fun `an unterminated quoted field is surfaced as a typed malformed-CSV error`() {
        val text = "Date,Workout Name,Exercise Name,Weight,Weight Unit,Reps\n" +
            "2026-07-01 08:00:00,\"Day A,Barbell Back Squat,225,lb,5\n"
        assertFailsWith<CsvImportError.MalformedCsv> { HistoryCsvReader.preview(text, catalog, zone) }
    }
}
