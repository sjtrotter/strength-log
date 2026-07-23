package io.github.sjtrotter.strengthlog.wear.ui

import io.github.sjtrotter.strengthlog.domain.library.TrackingType
import io.github.sjtrotter.strengthlog.domain.standards.SetFormatter
import io.github.sjtrotter.strengthlog.domain.sync.WatchExercise
import io.github.sjtrotter.strengthlog.domain.sync.WatchSet
import io.github.sjtrotter.strengthlog.domain.sync.WatchSnapshot
import io.github.sjtrotter.strengthlog.domain.units.SecondsStepper
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
    /** How this exercise is tracked — decides which control the stream renders and
     *  which field the crown edits (§3). */
    val tracking: TrackingType,
    /** True for a TIMED exercise whose goal carries load (weighted plank): the added
     *  load shows as a read-only caption. False for every other type. */
    val hasAddedLoad: Boolean,
    /** The read-only added-load caption ("+25") for a loaded TIMED hold; blank otherwise.
     *  Added load is a phone-side setup value — the watch never edits it (the phone drops
     *  weight deltas on TIMED tracks, design risk #2), so it is displayed, not stepped. */
    val addedLoadDisplay: String,
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
    val seconds: Int,
    val done: Boolean,
    /** The read-only hero numeral (redesign §1.2), pre-formatted per tracking type
     *  so the screen does layout only: the weight (WEIGHTED), "×r" (REPS), the hold
     *  (TIMED), or — for a superset round — the [SetFormatter] summary ("185×5")
     *  regardless of tracking, since a paired round always reads as one line. */
    val heroDisplay: String,
    /** The read-only secondary caption under the hero numeral: "× 5" reps for
     *  WEIGHTED, blank for REPS/TIMED/superset (their captions are either a static
     *  label the screen owns, or the exercise-level added-load caption, or the
     *  partner row). */
    val secondaryDisplay: String,
    /** Null when this exercise has no superset partner. */
    val partner: PartnerRowUiState? = null,
) {
    /** The TIMED hold formatted the same way the phone does ("45s" / "1:30"). */
    val secondsDisplay: String get() = SecondsStepper.format(seconds)
}

data class PartnerRowUiState(
    val weightDisplay: Double,
    val reps: Int,
    /** The partner round's [SetFormatter] summary ("50×12") — read-only. */
    val summaryDisplay: String,
)

/** [unit] converts the DTO's canonical lb into what the watch displays. */
fun WatchExercise.toStreamUiState(unit: WeightUnit, dayId: String, accentIndex: Int): ExerciseStreamUiState {
    val labels = kindLabels(sets)
    val track = watchTracking(tracking)
    val ssTrack = watchTracking(ssTracking)
    val isSuperset = supersetPartnerName != null
    // A TIMED goal carries its added load on the numeric [goal] (0 when none), so a
    // loaded hold is simply goal > 0 — same rule the phone's timedShowsWeight uses.
    val loaded = track == TrackingType.TIMED && goal > 0.0
    return ExerciseStreamUiState(
        programExerciseId = programExerciseId,
        dayId = dayId,
        accentIndex = accentIndex,
        name = name,
        // Use the phone's pre-formatted, per-type GOAL label; fall back to the weight
        // numeral only for a pre-P1.5 snapshot that never set it (keeps old wires safe).
        goalDisplay = goalLabel.ifBlank { WeightStepper.format(unit.fromLb(goal)) },
        tracking = track,
        hasAddedLoad = loaded,
        addedLoadDisplay = if (loaded) "+${WeightStepper.format(unit.fromLb(goal))}" else "",
        perHand = perHand,
        partnerName = supersetPartnerName,
        rounds = sets.mapIndexed { i, set ->
            val (hero, secondary) = when {
                isSuperset -> SetFormatter.summary(track, set.weightLb, set.reps, set.seconds, unit) to ""
                track == TrackingType.WEIGHTED -> WeightStepper.format(unit.fromLb(set.weightLb)) to "× ${set.reps}"
                track == TrackingType.REPS -> "×${set.reps}" to ""
                else -> SecondsStepper.format(set.seconds) to ""
            }
            RoundUiState(
                index = i,
                kindLabel = labels[i],
                weightDisplay = unit.fromLb(set.weightLb),
                reps = set.reps,
                seconds = set.seconds,
                done = set.done,
                heroDisplay = hero,
                secondaryDisplay = secondary,
                partner = ssSets.getOrNull(i)?.let {
                    PartnerRowUiState(
                        weightDisplay = unit.fromLb(it.weightLb),
                        reps = it.reps,
                        summaryDisplay = SetFormatter.summary(ssTrack, it.weightLb, it.reps, it.seconds, unit),
                    )
                },
            )
        },
    )
}

/** [WatchExercise.tracking] ("weighted"/"reps"/"timed") parsed to the domain enum;
 *  defaults to WEIGHTED on anything else so a stale/garbled wire degrades safely. */
fun watchTracking(tracking: String): TrackingType = TrackingType.entries.firstOrNull {
    it.name.equals(tracking, ignoreCase = true)
} ?: TrackingType.WEIGHTED

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
