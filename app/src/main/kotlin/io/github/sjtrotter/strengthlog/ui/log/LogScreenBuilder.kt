package io.github.sjtrotter.strengthlog.ui.log

import io.github.sjtrotter.strengthlog.data.db.entity.SessionSetEntity
import io.github.sjtrotter.strengthlog.domain.model.SetKind
import io.github.sjtrotter.strengthlog.domain.standards.SetFormatter
import io.github.sjtrotter.strengthlog.domain.units.WeightStepper
import io.github.sjtrotter.strengthlog.domain.units.WeightUnit
import io.github.sjtrotter.strengthlog.ui.day.DayScreenBuilder
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * The pure History Log (#14) decision logic: list-row and expanded-session
 * formatting. Kept Android-free (java.time is plain JDK) so the whole
 * contract is unit-testable on the JVM, mirroring [DayScreenBuilder] — this
 * screen deliberately reuses [DayScreenBuilder.kindLabelsForKinds] rather than
 * re-deriving the R1/TOP/B/O labeling rule (SSOT).
 */
object LogScreenBuilder {

    private val DATE_FORMAT = DateTimeFormatter.ofPattern("MMM d, yyyy")

    /** "Jul 6, 2026" from a session's epoch-millis completion time, device-local. */
    fun dateDisplay(completedAtMillis: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        DATE_FORMAT.format(Instant.ofEpochMilli(completedAtMillis).atZone(zone))

    /**
     * 0-based day index from a program day id ("A".."Z", the day-generator
     * convention) — the same key the day screen's `dayAccent` uses, so a
     * history badge matches that day's live tab color. An unrecognized id
     * (never produced by this app) falls back to day A's.
     */
    fun dayIndex(dayId: String): Int {
        val letter = dayId.firstOrNull()?.uppercaseChar() ?: return 0
        return (letter - 'A').takeIf { it >= 0 } ?: 0
    }

    /** Bodyweight display in [unit], matching the day screen's own convention. */
    fun bodyweightDisplay(bodyweightLb: Int, unit: WeightUnit): String =
        WeightStepper.format(unit.fromLb(bodyweightLb.toDouble()))

    /**
     * Groups a session's flat [sets] by exercise id, preserving first-appearance
     * order. A superset's partner has its own `exerciseId`/name, so it lands in
     * its own group — this is "grouped by exercise", not by program slot.
     *
     * Each row formats by its logged VALUE ([SetFormatter.summaryOfValues]),
     * not by looking the exercise back up in the catalog: a `session_set` row
     * can predate a reclassification (design risk #3 — the P3 fixup only ever
     * touched live `exercise_log` rows, never history), so a legacy plank
     * logged as reps must still read as reps, never a manufactured "0s".
     */
    fun groupByExercise(sets: List<SessionSetEntity>, unit: WeightUnit): List<SessionExerciseGroup> {
        val byExercise = LinkedHashMap<String, MutableList<SessionSetEntity>>()
        for (set in sets) byExercise.getOrPut(set.exerciseId) { mutableListOf() }.add(set)

        return byExercise.values.map { group ->
            val kinds = group.map { runCatching { SetKind.valueOf(it.kind) }.getOrDefault(SetKind.WORK) }
            val labels = DayScreenBuilder.kindLabelsForKinds(kinds)
            SessionExerciseGroup(
                exerciseName = group.first().exerciseName,
                sets = group.mapIndexed { i, s ->
                    SessionSetSummary(labels[i], SetFormatter.summaryOfValues(s.weightLb, s.reps, s.seconds, unit))
                },
            )
        }
    }
}
