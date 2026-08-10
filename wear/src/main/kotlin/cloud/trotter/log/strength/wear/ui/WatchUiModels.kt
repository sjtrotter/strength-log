package cloud.trotter.log.strength.wear.ui

import cloud.trotter.log.strength.domain.library.TrackingType
import cloud.trotter.log.strength.domain.standards.SetFormatter
import cloud.trotter.log.strength.domain.sync.WatchExercise
import cloud.trotter.log.strength.domain.sync.WatchSet
import cloud.trotter.log.strength.domain.sync.WatchSnapshot
import cloud.trotter.log.strength.domain.units.SecondsStepper
import cloud.trotter.log.strength.domain.units.WeightStepper
import cloud.trotter.log.strength.domain.units.WeightUnit

/**
 * Pure-Kotlin mapping from the wire [WatchSnapshot] to what the wear screens
 * render — kept out of any `@Composable` so it's JVM-testable without
 * Robolectric (the screens do no shaping of their own, only layout).
 */

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
fun WatchExercise.toStreamUiState(
    unit: WeightUnit,
    dayId: String,
    accentIndex: Int,
    copy: DialCopy,
): ExerciseStreamUiState {
    val labels = kindLabels(sets, copy)
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
private fun kindLabels(sets: List<WatchSet>, copy: DialCopy): List<String> {
    var ramp = 0
    return sets.mapIndexed { index, s ->
        when (s.kind) {
            "RAMP" -> copy.rampLabel(++ramp)
            "TOP" -> copy.topLabel
            "BACKOFF" -> copy.backoffLabel
            else -> "${index + 1}"
        }
    }
}

/** [WatchSnapshot.unit] ("lb"/"kg") parsed to the domain enum; defaults to LB on anything else. */
fun watchUnit(unit: String): WeightUnit = WeightUnit.entries.firstOrNull {
    it.name.equals(unit, ignoreCase = true)
} ?: WeightUnit.LB
