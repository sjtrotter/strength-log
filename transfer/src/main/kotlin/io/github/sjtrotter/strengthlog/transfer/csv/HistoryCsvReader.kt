package io.github.sjtrotter.strengthlog.transfer.csv

import io.github.sjtrotter.strengthlog.data.catalog.ExerciseCatalog
import io.github.sjtrotter.strengthlog.domain.units.WeightUnit
import io.github.sjtrotter.strengthlog.transfer.backup.BackupCodec
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

/**
 * Parses a Strong/Hevy-shaped CSV into the pure [CsvImportPreview] (issue
 * #16). Validation order mirrors [BackupCodec]: size cap, then CSV
 * well-formedness, then header (column presence + weight-unit resolvability),
 * then each row, then exercise-name matching — cheapest and most fundamental
 * checks first, so a bad file fails fast and *before* anything is grouped
 * into sessions.
 *
 * The reader only knows the header spellings in [HISTORY_FIELD_ALIASES] and
 * maps by column *name*, not position — a file with the same columns in a
 * different order, or extra columns this app ignores (Duration, RPE, Notes,
 * ...), parses identically.
 */
object HistoryCsvReader {

    private const val BYTE_ORDER_MARK = "\uFEFF"

    private val DATE_FORMATS = listOf(
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"),
        DateTimeFormatter.ISO_LOCAL_DATE_TIME,
    )

    /** Reads [input] into a string, refusing anything over [maxBytes] before
     *  it's fully buffered (reuses the same ceiling backup import uses). */
    fun readCapped(input: InputStream, maxBytes: Long = BackupCodec.DEFAULT_MAX_BYTES): String {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(16 * 1024)
        var total = 0L
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            total += n
            if (total > maxBytes) throw CsvImportError.TooLarge(total, maxBytes)
            out.write(buf, 0, n)
        }
        return out.toString(Charsets.UTF_8.name())
    }

    /**
     * Builds the preview/confirm model from raw CSV [text]. [catalog] supplies
     * every name a row can match against (code library + the user's existing
     * custom exercises); [zone] resolves each row's local date/time to an
     * epoch millisecond (injectable so tests don't depend on the host's zone).
     */
    fun preview(
        text: String,
        catalog: ExerciseCatalog,
        zone: ZoneId = ZoneId.systemDefault(),
        maxBytes: Long = BackupCodec.DEFAULT_MAX_BYTES,
    ): CsvImportPreview {
        if (text.length.toLong() > maxBytes) throw CsvImportError.TooLarge(text.length.toLong(), maxBytes)

        // A UTF-8 BOM survives trim() (U+FEFF is not whitespace), so an Excel/
        // Windows re-save would otherwise make the first header ("Date") fail to
        // match and report a misleading MissingColumns. Strip it once, up front.
        val content = text.removePrefix(BYTE_ORDER_MARK)

        val parsed = try {
            Csv.parse(content)
        } catch (e: Csv.UnterminatedQuote) {
            throw CsvImportError.MalformedCsv(e.message ?: "ends inside a quoted field", e)
        }
        val rows = parsed.filterNot { row -> row.all { it.isBlank() } }
        if (rows.isEmpty()) throw CsvImportError.Empty()
        val header = rows.first()
        val dataRows = rows.drop(1)
        if (dataRows.isEmpty()) throw CsvImportError.Empty()

        val columns = mapColumns(header)
        val weightUnitResolver = resolveWeightUnit(header, columns)

        // Cardio / bodyweight rows (both weight and reps blank) aren't strength
        // sets we model, so parseRow returns null for them and they're dropped
        // here rather than rejecting the whole file.
        val parsedRows = dataRows.mapIndexedNotNull { index, fields ->
            parseRow(fields, line = index + 2, columns = columns, zone = zone, weightUnit = weightUnitResolver)
        }

        val sessions = groupIntoSessions(parsedRows)
        val unmatched = matchExerciseNames(sessions, catalog)
        val matchedSessions = resolveExerciseIds(sessions, catalog)

        return CsvImportPreview(sessions = matchedSessions, unmatchedNames = unmatched)
    }

    // --- header mapping --------------------------------------------------

    private fun mapColumns(header: List<String>): Map<HistoryField, Int> {
        val normalized = header.map { normalizeHeader(it) }
        val columns = mutableMapOf<HistoryField, Int>()
        for ((field, aliases) in HISTORY_FIELD_ALIASES) {
            val aliasKeys = aliases.map { normalizeHeader(it) }
            val index = normalized.indexOfFirst { it in aliasKeys }
            if (index >= 0) columns[field] = index
        }
        val required = listOf(
            HistoryField.DATE, HistoryField.WORKOUT_NAME, HistoryField.EXERCISE_NAME,
            HistoryField.WEIGHT, HistoryField.REPS,
        )
        val missing = required.filter { it !in columns }
        if (missing.isNotEmpty()) {
            throw CsvImportError.MissingColumns(missing.map { HISTORY_FIELD_ALIASES.getValue(it).first() })
        }
        return columns
    }

    /** How a row's weight unit is determined: the same unit for every row (a
     *  "Weight (kg)"-style header), or a per-row column to read. */
    private sealed interface WeightUnitStrategy {
        data class Fixed(val unit: WeightUnit) : WeightUnitStrategy
        data class PerRow(val column: Int) : WeightUnitStrategy
    }

    private fun resolveWeightUnit(header: List<String>, columns: Map<HistoryField, Int>): WeightUnitStrategy {
        val weightHeaderText = header[columns.getValue(HistoryField.WEIGHT)]
        val implied = WEIGHT_HEADER_IMPLIED_UNIT[normalizeHeader(weightHeaderText)]
        val unitColumn = columns[HistoryField.WEIGHT_UNIT]
        return when {
            unitColumn != null -> WeightUnitStrategy.PerRow(unitColumn)
            implied != null -> WeightUnitStrategy.Fixed(implied)
            else -> throw CsvImportError.AmbiguousWeightUnit(weightHeaderText)
        }
    }

    private fun parseWeightUnitToken(raw: String, line: Int): WeightUnit =
        when (raw.trim().lowercase()) {
            "lb", "lbs" -> WeightUnit.LB
            "kg", "kgs" -> WeightUnit.KG
            else -> throw CsvImportError.MalformedRow(line, "unknown weight unit '$raw'")
        }

    // --- row parsing -------------------------------------------------------

    private data class ParsedRow(
        val completedAt: Long,
        val dayTitle: String,
        val exerciseName: String,
        val setOrder: Int?,
        val weightLb: Double,
        val reps: Int,
        val seconds: Int,
    )

    /**
     * Parses one data row, or returns null for a cardio/bodyweight row we don't
     * model (both weight and reps blank) so the caller drops it without
     * rejecting the whole file. Every cell is read through [cell]/[rawCell]
     * ([List.getOrNull]) so a row truncated short of a column it needs surfaces
     * a typed [CsvImportError.MalformedRow], never a raw IndexOutOfBounds.
     *
     * Blank vs. bad are treated differently: a *blank* weight or reps defaults
     * (0.0 / 0) because a real Strong/Hevy export leaves them blank for
     * bodyweight/cardio work; a *present but malformed* value (non-numeric,
     * negative, or non-finite like Infinity/NaN/overflow) rejects the file.
     */
    private fun parseRow(
        fields: List<String>,
        line: Int,
        columns: Map<HistoryField, Int>,
        zone: ZoneId,
        weightUnit: WeightUnitStrategy,
    ): ParsedRow? {
        // A column that mapColumns proved is present, but whose cell this row is
        // too short to contain, is a ragged row — a typed error, not a crash.
        fun cell(field: HistoryField): String {
            val index = columns.getValue(field)
            return fields.getOrNull(index)?.trim()
                ?: throw CsvImportError.MalformedRow(line, "row is too short for column ${field.name}")
        }
        // An optional column's cell, absent-or-blank collapsing to "".
        fun rawCell(field: HistoryField): String =
            columns[field]?.let { fields.getOrNull(it)?.trim() }.orEmpty()

        val weightText = cell(HistoryField.WEIGHT)
        val repsText = cell(HistoryField.REPS)
        val secondsText = rawCell(HistoryField.SECONDS)
        // A row with a Distance is cardio (a run/ride), not a strength set — even
        // if it also carries Seconds. A TIMED hold has Seconds but no distance.
        val isCardio = rawCell(HistoryField.DISTANCE).isNotBlank()
        // Not a strength set we model — skip, don't reject the file. A weight or
        // reps keeps the row; a lone Seconds keeps it only when it isn't cardio.
        if (weightText.isBlank() && repsText.isBlank() && (secondsText.isBlank() || isCardio)) return null

        val dateText = cell(HistoryField.DATE)
        val completedAt = parseDate(dateText, zone)
            ?: throw CsvImportError.MalformedRow(line, "unparsable date '$dateText'")

        val dayTitle = cell(HistoryField.WORKOUT_NAME)
        if (dayTitle.isBlank()) throw CsvImportError.MalformedRow(line, "workout name is blank")

        val exerciseName = cell(HistoryField.EXERCISE_NAME)
        if (exerciseName.isBlank()) throw CsvImportError.MalformedRow(line, "exercise name is blank")

        val setOrder = rawCell(HistoryField.SET_ORDER).takeIf { it.isNotEmpty() }?.let { raw ->
            val order = raw.toIntOrNull() ?: throw CsvImportError.MalformedRow(line, "unparsable set order '$raw'")
            if (order < 1) throw CsvImportError.MalformedRow(line, "set order must be at least 1: '$raw'")
            order - 1
        }

        val weightLb = if (weightText.isBlank()) {
            0.0 // bodyweight row: reps present, no external load
        } else {
            val display = weightText.toDoubleOrNull()
                ?: throw CsvImportError.MalformedRow(line, "unparsable weight '$weightText'")
            if (!display.isFinite() || display < 0) {
                throw CsvImportError.MalformedRow(line, "weight out of range '$weightText'")
            }
            // Only a loaded row needs a unit; a blank-weight (bodyweight) row may
            // legitimately omit its Weight Unit cell.
            val unit = when (weightUnit) {
                is WeightUnitStrategy.Fixed -> weightUnit.unit
                is WeightUnitStrategy.PerRow -> {
                    val token = fields.getOrNull(weightUnit.column)?.trim()
                        ?: throw CsvImportError.MalformedRow(line, "row is too short for its Weight Unit column")
                    parseWeightUnitToken(token, line)
                }
            }
            unit.toLb(display)
        }

        val reps = if (repsText.isBlank()) {
            0 // cardio-adjacent or unlogged reps default to none
        } else {
            val parsed = repsText.toIntOrNull()
                ?: throw CsvImportError.MalformedRow(line, "unparsable reps '$repsText'")
            if (parsed < 0) throw CsvImportError.MalformedRow(line, "reps must not be negative: '$repsText'")
            parsed
        }

        val seconds = if (secondsText.isBlank()) {
            0 // no Seconds column, or a non-timed set — no hold to record
        } else {
            val parsed = secondsText.toIntOrNull()
                ?: throw CsvImportError.MalformedRow(line, "unparsable seconds '$secondsText'")
            if (parsed < 0) throw CsvImportError.MalformedRow(line, "seconds must not be negative: '$secondsText'")
            parsed
        }

        return ParsedRow(completedAt, dayTitle, exerciseName, setOrder, weightLb, reps, seconds)
    }

    private fun parseDate(text: String, zone: ZoneId): Long? {
        for (format in DATE_FORMATS) {
            try {
                return LocalDateTime.parse(text, format).atZone(zone).toInstant().toEpochMilli()
            } catch (e: DateTimeParseException) {
                // try the next format
            }
        }
        return null
    }

    // --- grouping & matching -------------------------------------------------

    private fun groupIntoSessions(rows: List<ParsedRow>): List<PreviewSession> {
        // Rows group by (completedAt, workout name). The date carries only
        // second precision (Strong's "yyyy-MM-dd HH:mm:ss") — deliberately, to
        // stay byte-compatible with Strong on re-export. The only ambiguity that
        // buys is two *distinct* workouts with the same title starting in the
        // same second, which merges them; that's negligible in practice and a
        // fair trade for interchange fidelity.
        val groups = LinkedHashMap<Pair<Long, String>, MutableList<ParsedRow>>()
        for (row in rows) {
            groups.getOrPut(row.completedAt to row.dayTitle) { mutableListOf() }.add(row)
        }
        return groups.map { (key, groupRows) ->
            val (completedAt, dayTitle) = key
            val perExerciseCounter = mutableMapOf<String, Int>()
            val sets = groupRows.map { row ->
                val setIndex = row.setOrder
                    ?: perExerciseCounter.merge(normalizeExerciseName(row.exerciseName), 1, Int::plus)!!.minus(1)
                PreviewSet(
                    exerciseName = row.exerciseName,
                    exerciseId = null, // resolved in resolveExerciseIds
                    setIndex = setIndex,
                    weightLb = row.weightLb,
                    reps = row.reps,
                    seconds = row.seconds,
                )
            }
            PreviewSession(dayTitle = dayTitle, completedAt = completedAt, sets = sets)
        }
    }

    private fun catalogLookup(catalog: ExerciseCatalog): Map<String, String> =
        catalog.entries.associate { normalizeExerciseName(it.name) to it.id }

    private fun matchExerciseNames(sessions: List<PreviewSession>, catalog: ExerciseCatalog): List<UnmatchedExerciseName> {
        val known = catalogLookup(catalog)
        val seen = LinkedHashMap<String, String>() // normalized -> first-seen display form
        for (session in sessions) {
            for (set in session.sets) {
                val key = normalizeExerciseName(set.exerciseName)
                if (key !in known) seen.putIfAbsent(key, set.exerciseName)
            }
        }
        return seen.values.map { name -> UnmatchedExerciseName(name, PatternGuesser.guess(name)) }
    }

    private fun resolveExerciseIds(sessions: List<PreviewSession>, catalog: ExerciseCatalog): List<PreviewSession> {
        val known = catalogLookup(catalog)
        return sessions.map { session ->
            session.copy(
                sets = session.sets.map { set ->
                    set.copy(exerciseId = known[normalizeExerciseName(set.exerciseName)])
                },
            )
        }
    }
}
