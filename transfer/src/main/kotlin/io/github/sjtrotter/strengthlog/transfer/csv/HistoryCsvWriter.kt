package io.github.sjtrotter.strengthlog.transfer.csv

import io.github.sjtrotter.strengthlog.data.db.entity.SessionSetEntity
import io.github.sjtrotter.strengthlog.data.db.entity.WorkoutSessionEntity
import io.github.sjtrotter.strengthlog.domain.units.WeightUnit
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Renders session history as Strong-compatible CSV (PLAN.md A2/A5, issue
 * #16): one row per set, [HISTORY_CSV_HEADER]'s column layout. Pure and
 * Android-free — [io.github.sjtrotter.strengthlog.transfer.csv.CsvHistoryService]
 * supplies the data from [io.github.sjtrotter.strengthlog.data.TrackerRepository.exportSessionHistory].
 */
object HistoryCsvWriter {

    private val DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

    /**
     * [sessions] and [sessionSets] come straight off `ORDER BY id` queries
     * (`:data`'s deterministic read surface); this function re-sorts sessions
     * by (completedAt, id) and, within a session, keeps sets in `id` order —
     * `:data` always inserts a session's sets in exercise-position-then-
     * setIndex order (one contiguous batch per completed day, per slot, in
     * `setIndex` order), so ascending id already *is* that order. Two exports
     * of the same state are therefore byte-identical.
     */
    fun export(
        sessions: List<WorkoutSessionEntity>,
        sessionSets: List<SessionSetEntity>,
        unit: WeightUnit,
        zone: ZoneId = ZoneId.systemDefault(),
    ): String {
        val setsBySession = sessionSets.groupBy { it.sessionId }
        val orderedSessions = sessions.sortedWith(compareBy({ it.completedAt }, { it.id }))

        val rows = mutableListOf<List<String>>()
        rows.add(HISTORY_CSV_HEADER)
        for (session in orderedSessions) {
            val date = DATE_FORMAT.format(Instant.ofEpochMilli(session.completedAt).atZone(zone))
            val sets = setsBySession[session.id].orEmpty().sortedBy { it.id }
            for (set in sets) {
                rows.add(
                    listOf(
                        date,
                        session.dayTitle,
                        "", // Duration — not tracked
                        set.exerciseName,
                        (set.setIndex + 1).toString(),
                        formatWeight(unit.fromLb(set.weightLb)),
                        unit.name.lowercase(),
                        set.reps.toString(),
                        "", // Distance — not tracked
                        "", // Distance Unit — not tracked
                        "", // Seconds — not tracked
                        "", // Notes — not tracked
                        "", // Workout Notes — not tracked
                        "", // RPE — not tracked
                    ),
                )
            }
        }
        return rows.joinToString("\r\n", postfix = "\r\n") { Csv.writeRow(it) }
    }

    /** Whole numbers print without a trailing ".0" (Strong's own convention);
     *  fractional display weights (kg conversions) keep their precision. */
    private fun formatWeight(value: Double): String =
        if (value == Math.floor(value) && !value.isInfinite()) value.toLong().toString() else value.toString()
}
