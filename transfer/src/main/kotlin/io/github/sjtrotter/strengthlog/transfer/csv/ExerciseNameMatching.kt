package io.github.sjtrotter.strengthlog.transfer.csv

/**
 * The one normalization CSV exercise-name matching uses, shared by
 * [HistoryCsvReader] (matching a row's name against the catalog) and
 * [CsvHistoryImporter] (matching a confirmed row back to the custom exercise
 * it created) so the two stay in lockstep. "Case/whitespace-insensitive exact
 * match" (PLAN.md A2) means trimmed, internal whitespace collapsed, and
 * lower-cased — nothing fuzzier than that; a genuinely different name is left
 * for the user to resolve, never guessed.
 */
internal fun normalizeExerciseName(name: String): String =
    name.trim().replace(Regex("\\s+"), " ").lowercase()
