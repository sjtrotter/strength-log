package io.github.sjtrotter.strengthlog.wear.ui

import io.github.sjtrotter.strengthlog.domain.sync.WatchExercise
import io.github.sjtrotter.strengthlog.domain.sync.WatchSet
import io.github.sjtrotter.strengthlog.domain.sync.WatchSnapshot
import io.github.sjtrotter.strengthlog.domain.units.WeightStepper
import io.github.sjtrotter.strengthlog.domain.units.WeightUnit

/**
 * Pure-Kotlin mapping from the wire [WatchSnapshot] to what the two Wear
 * screens render — kept out of any `@Composable` so it's JVM-testable without
 * Robolectric (the screens do no shaping of their own, only layout).
 */

data class DayListUiState(
    val dayTitle: String,
    val accentIndex: Int,
    val rows: List<DayListRow>,
)

data class DayListRow(
    val programExerciseId: Long,
    val name: String,
    val doneCount: Int,
    val totalCount: Int,
) {
    val allDone: Boolean get() = totalCount > 0 && doneCount == totalCount
}

fun WatchSnapshot.toDayListUiState(): DayListUiState = DayListUiState(
    dayTitle = day.title,
    accentIndex = day.accentIndex,
    rows = day.exercises.map { it.toDayListRow() },
)

private fun WatchExercise.toDayListRow() = DayListRow(
    programExerciseId = programExerciseId,
    name = name,
    doneCount = sets.count { it.done },
    totalCount = sets.size,
)

data class ExerciseDetailUiState(
    val programExerciseId: Long,
    val name: String,
    val goalDisplay: String,
    val perHand: Boolean,
    val accentIndex: Int,
    val partnerName: String?,
    val rows: List<SetRowUiState>,
)

data class SetRowUiState(
    val index: Int,
    val kindLabel: String,
    val weightDisplay: Double,
    val reps: Int,
    val done: Boolean,
    /** Null when this exercise has no superset partner. */
    val partner: PartnerRowUiState? = null,
)

data class PartnerRowUiState(val weightDisplay: Double, val reps: Int)

/** [unit] converts the DTO's canonical lb into what the watch displays. */
fun WatchExercise.toDetailUiState(unit: WeightUnit, accentIndex: Int): ExerciseDetailUiState {
    val labels = kindLabels(sets)
    return ExerciseDetailUiState(
        programExerciseId = programExerciseId,
        name = name,
        goalDisplay = WeightStepper.format(unit.fromLb(goal)),
        perHand = perHand,
        accentIndex = accentIndex,
        partnerName = supersetPartnerName,
        rows = sets.mapIndexed { i, set ->
            SetRowUiState(
                index = i,
                kindLabel = labels[i],
                weightDisplay = unit.fromLb(set.weightLb),
                reps = set.reps,
                done = set.done,
                partner = ssSets.getOrNull(i)?.let {
                    PartnerRowUiState(unit.fromLb(it.weightLb), it.reps)
                },
            )
        },
    )
}

/** Per-row kind labels: R1…, TOP, B/O, or a plain 1-based number — mirrors the phone's DayScreenBuilder. */
private fun kindLabels(sets: List<WatchSet>): List<String> {
    var ramp = 0
    return sets.mapIndexed { index, s ->
        when (s.kind) {
            "RAMP" -> "R${++ramp}"
            "TOP" -> "TOP"
            "BACKOFF" -> "B/O"
            else -> "${index + 1}"
        }
    }
}

/** [WatchSnapshot.unit] ("lb"/"kg") parsed to the domain enum; defaults to LB on anything else. */
fun watchUnit(unit: String): WeightUnit = WeightUnit.entries.firstOrNull {
    it.name.equals(unit, ignoreCase = true)
} ?: WeightUnit.LB
