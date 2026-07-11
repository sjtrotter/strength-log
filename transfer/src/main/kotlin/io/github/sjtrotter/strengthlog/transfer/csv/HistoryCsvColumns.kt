package io.github.sjtrotter.strengthlog.transfer.csv

import io.github.sjtrotter.strengthlog.domain.units.WeightUnit

/**
 * Strong's column layout (PLAN.md A2/A5, issue #16) — the header this app's
 * own export always writes, and the de facto interchange format Hevy/
 * FitNotes/spreadsheets also target. Duration, Distance, Distance Unit,
 * Notes, Workout Notes and RPE are always emitted empty: this app doesn't
 * track them, but the headers stay present for round-trip compatibility with
 * tools that read a fixed Strong-shaped column set. The `Seconds` column is
 * written for TIMED holds/carries and read back on import (tracking types).
 */
val HISTORY_CSV_HEADER: List<String> = listOf(
    "Date",
    "Workout Name",
    "Duration",
    "Exercise Name",
    "Set Order",
    "Weight",
    "Weight Unit",
    "Reps",
    "Distance",
    "Distance Unit",
    "Seconds",
    "Notes",
    "Workout Notes",
    "RPE",
)

/**
 * The facts the importer actually reads out of a row; every other Strong/Hevy
 * column (Duration, RPE, Notes, ...) is accepted in the header but ignored,
 * matching what this app tracks (PLAN.md A1). SECONDS carries a TIMED hold/carry.
 */
internal enum class HistoryField { DATE, WORKOUT_NAME, EXERCISE_NAME, SET_ORDER, WEIGHT, WEIGHT_UNIT, REPS, SECONDS, DISTANCE }

/** Header spellings recognized for each field, matched case/whitespace-
 *  insensitively so the mapping is header-driven, not positional — a Hevy
 *  file with columns in a different order maps identically. */
internal val HISTORY_FIELD_ALIASES: Map<HistoryField, List<String>> = mapOf(
    HistoryField.DATE to listOf("Date", "Start Time"),
    HistoryField.WORKOUT_NAME to listOf("Workout Name", "Title"),
    HistoryField.EXERCISE_NAME to listOf("Exercise Name", "Exercise Title"),
    HistoryField.SET_ORDER to listOf("Set Order", "Set Index"),
    HistoryField.WEIGHT to listOf("Weight", "Weight (kg)", "Weight (lb)", "Weight Kg", "Weight Lb"),
    HistoryField.WEIGHT_UNIT to listOf("Weight Unit"),
    HistoryField.REPS to listOf("Reps"),
    HistoryField.SECONDS to listOf("Seconds"),
    // Read only to tell a cardio row (has a distance) apart from a TIMED hold
    // (seconds, no distance); the value itself isn't imported.
    HistoryField.DISTANCE to listOf("Distance"),
)

/**
 * The unit a [HistoryField.WEIGHT] header spelling implies on its own, for
 * files (Hevy-style) that fold the unit into the weight column name instead
 * of carrying a separate `Weight Unit` column. `null` means the column name
 * doesn't say — a separate `Weight Unit` column is required in that case.
 */
internal val WEIGHT_HEADER_IMPLIED_UNIT: Map<String, WeightUnit> = mapOf(
    normalizeHeader("Weight (kg)") to WeightUnit.KG,
    normalizeHeader("Weight Kg") to WeightUnit.KG,
    normalizeHeader("Weight (lb)") to WeightUnit.LB,
    normalizeHeader("Weight Lb") to WeightUnit.LB,
)

/** Case/whitespace-insensitive header comparison key. */
internal fun normalizeHeader(header: String): String = header.trim().lowercase()
