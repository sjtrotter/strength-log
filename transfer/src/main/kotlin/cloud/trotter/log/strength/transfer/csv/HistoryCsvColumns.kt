package cloud.trotter.log.strength.transfer.csv

import cloud.trotter.log.strength.domain.units.WeightUnit

/**
 * Strong's first 16 columns retain their names and positions through Plan.
 * Strength writes Seconds for timed sets; cardio writes one row per set with
 * Distance/Distance Unit carrying measured metres (`m`). Activity, Role, Target,
 * Aim, Hard, Status, Distance Source, Steps Completed, and Cardio Mode are
 * appended to preserve cardio prescriptions, results, and legacy summaries.
 * Workout Notes is always empty, and imports carry no plan.
 */
val HISTORY_CSV_HEADER: List<String> = listOf(
    "Date",
    "Workout Name",
    "Duration",
    "Exercise Name",
    "Set Order",
    "Set Type",
    "Weight",
    "Weight Unit",
    "Reps",
    "Distance",
    "Distance Unit",
    "Seconds",
    "Notes",
    "Workout Notes",
    "RPE",
    "Plan",
    "Activity",
    "Role",
    "Target",
    "Aim",
    "Hard",
    "Status",
    "Distance Source",
    "Steps Completed",
    "Cardio Mode",
)

/** Fields read by the header-driven strength and cardio import paths. */
internal enum class HistoryField {
    DATE, WORKOUT_NAME, DURATION, EXERCISE_NAME, SET_ORDER, WEIGHT, WEIGHT_UNIT,
    SET_TYPE, REPS, SECONDS, DISTANCE, DISTANCE_UNIT, NOTES, WORKOUT_NOTES, RPE, PLAN,
    ACTIVITY, ROLE, TARGET, AIM, HARD, STATUS, DISTANCE_SOURCE, STEPS_COMPLETED, CARDIO_MODE,
}

/** Header spellings recognized for each field, matched case/whitespace-
 *  insensitively so the mapping is header-driven, not positional — a Hevy
 *  file with columns in a different order maps identically. */
internal val HISTORY_FIELD_ALIASES: Map<HistoryField, List<String>> = mapOf(
    HistoryField.DATE to listOf("Date", "Start Time"),
    HistoryField.WORKOUT_NAME to listOf("Workout Name", "Title"),
    HistoryField.EXERCISE_NAME to listOf("Exercise Name", "Exercise Title"),
    HistoryField.SET_ORDER to listOf("Set Order", "Set Index"),
    HistoryField.SET_TYPE to listOf("Set Type", "set_type"),
    HistoryField.WEIGHT to listOf("Weight", "Weight (kg)", "Weight (lb)", "Weight Kg", "Weight Lb"),
    HistoryField.WEIGHT_UNIT to listOf("Weight Unit"),
    HistoryField.REPS to listOf("Reps"),
    HistoryField.SECONDS to listOf("Seconds"),
    HistoryField.DISTANCE to listOf("Distance"),
    HistoryField.DURATION to listOf("Duration"),
    HistoryField.DISTANCE_UNIT to listOf("Distance Unit"),
    HistoryField.NOTES to listOf("Notes"),
    HistoryField.WORKOUT_NOTES to listOf("Workout Notes"),
    HistoryField.RPE to listOf("RPE"),
    HistoryField.PLAN to listOf("Plan"),
    HistoryField.ACTIVITY to listOf("Activity"),
    HistoryField.ROLE to listOf("Role"),
    HistoryField.TARGET to listOf("Target"),
    HistoryField.AIM to listOf("Aim"),
    HistoryField.HARD to listOf("Hard"),
    HistoryField.STATUS to listOf("Status"),
    HistoryField.DISTANCE_SOURCE to listOf("Distance Source"),
    HistoryField.STEPS_COMPLETED to listOf("Steps Completed"),
    HistoryField.CARDIO_MODE to listOf("Cardio Mode"),
)

internal const val CARDIO_MARKER = "cardio"

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
