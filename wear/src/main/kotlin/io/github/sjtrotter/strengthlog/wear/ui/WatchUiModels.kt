package io.github.sjtrotter.strengthlog.wear.ui

import io.github.sjtrotter.strengthlog.domain.sync.WatchExercise
import io.github.sjtrotter.strengthlog.domain.sync.WatchSet
import io.github.sjtrotter.strengthlog.domain.sync.WatchSnapshot
import io.github.sjtrotter.strengthlog.domain.units.WeightStepper
import io.github.sjtrotter.strengthlog.domain.units.WeightUnit

/**
 * Pure-Kotlin mapping from the wire [WatchSnapshot] to what the wear screens
 * render — kept out of any `@Composable` so it's JVM-testable without
 * Robolectric (the screens do no shaping of their own, only layout).
 */

data class DayListUiState(
    val dayId: String,
    val accentIndex: Int,
    /** The day's real focus/label ([WatchSnapshot]'s `day.emphasisLine`), or
     *  null when the phone didn't compute one — never hardcoded filler text. */
    val subtitle: String?,
    val rows: List<DayListRow>,
)

data class DayListRow(
    val programExerciseId: Long,
    val name: String,
    /** Non-null only for a superset's main row — the `↳ Partner` sub-line. */
    val partnerName: String?,
    val doneCount: Int,
    val totalCount: Int,
    /** Where tapping this row opens the stream — the first undone round, or
     *  the last round when every round is already done (digest §1.1). */
    val firstUndoneIndex: Int,
) {
    val allDone: Boolean get() = totalCount > 0 && doneCount == totalCount
}

fun WatchSnapshot.toDayListUiState(): DayListUiState = DayListUiState(
    dayId = day.dayId,
    accentIndex = day.accentIndex,
    subtitle = day.emphasisLine.takeIf { it.isNotBlank() },
    rows = day.exercises.map { it.toDayListRow() },
)

private fun WatchExercise.toDayListRow(): DayListRow {
    val firstUndone = sets.indexOfFirst { !it.done }
    return DayListRow(
        programExerciseId = programExerciseId,
        name = name,
        partnerName = supersetPartnerName,
        doneCount = sets.count { it.done },
        totalCount = sets.size,
        firstUndoneIndex = if (firstUndone >= 0) firstUndone else (sets.size - 1).coerceAtLeast(0),
    )
}

/**
 * The one row — the first not-yet-complete exercise — that gets the "up next"
 * treatment (design digest §0 `listRows`). Null when every row is done.
 */
fun upNextIndex(rows: List<DayListRow>): Int? = rows.indexOfFirst { !it.allDone }.takeIf { it >= 0 }

data class ExerciseStreamUiState(
    val programExerciseId: Long,
    val dayId: String,
    val accentIndex: Int,
    val name: String,
    val goalDisplay: String,
    val perHand: Boolean,
    val partnerName: String?,
    val rounds: List<RoundUiState>,
) {
    val isSuperset: Boolean get() = partnerName != null
}

data class RoundUiState(
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
fun WatchExercise.toStreamUiState(unit: WeightUnit, dayId: String, accentIndex: Int): ExerciseStreamUiState {
    val labels = kindLabels(sets)
    return ExerciseStreamUiState(
        programExerciseId = programExerciseId,
        dayId = dayId,
        accentIndex = accentIndex,
        name = name,
        goalDisplay = WeightStepper.format(unit.fromLb(goal)),
        perHand = perHand,
        partnerName = supersetPartnerName,
        rounds = sets.mapIndexed { i, set ->
            RoundUiState(
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

/** Per-round kind labels: R1…, TOP, B/O, or a plain 1-based number — mirrors the phone's DayScreenBuilder. */
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

data class DayDoneUiState(
    val dayId: String,
    val accentIndex: Int,
    val roundsLogged: Int,
)

fun WatchSnapshot.toDayDoneUiState(): DayDoneUiState = DayDoneUiState(
    dayId = day.dayId,
    accentIndex = day.accentIndex,
    roundsLogged = day.exercises.sumOf { it.sets.size },
)

/** [WatchSnapshot.unit] ("lb"/"kg") parsed to the domain enum; defaults to LB on anything else. */
fun watchUnit(unit: String): WeightUnit = WeightUnit.entries.firstOrNull {
    it.name.equals(unit, ignoreCase = true)
} ?: WeightUnit.LB
