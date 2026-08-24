package cloud.trotter.log.strength.ui.today

import cloud.trotter.log.strength.data.catalog.ExerciseCatalog
import cloud.trotter.log.strength.data.db.dao.SessionSummaryRow
import cloud.trotter.log.strength.data.db.dao.TopSetRow
import cloud.trotter.log.strength.domain.units.WeightStepper
import cloud.trotter.log.strength.domain.units.WeightUnit
import cloud.trotter.log.strength.time.CivilTime
import cloud.trotter.log.strength.ui.log.LogScreenBuilder
import cloud.trotter.log.strength.ui.log.JournalBuilder
import cloud.trotter.log.strength.ui.text.TodayActionKind
import cloud.trotter.log.strength.ui.text.UiText
import java.time.DayOfWeek
import java.time.Instant
import java.time.YearMonth
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters

/**
 * The pure decision logic behind the Today screen (#121): the statement's
 * overline, the one action's label, the rotation rail and the last-session
 * strip. Kept free of Android and coroutines so every rule is unit-testable on
 * the JVM; [TodayViewModel] only wires these to repository flows.
 *
 * Anything the glance surfaces already say lives in
 * [cloud.trotter.log.strength.domain.glance.GlanceLines], not here — Today
 * prints the same day and stat lines as the widget and the watch.
 */
object TodayScreenBuilder {

    /** Exactly one derived journal fact, in editorial priority order. */
    fun lifeLine(
        mains: List<JournalBuilder.MainLift>,
        sessions: List<SessionSummaryRow>,
        topSets: List<TopSetRow>,
        strengthDays: Int,
        now: CivilTime,
    ): UiText? {
        val goalFact = mains.mapNotNull { lift ->
            val count = topSets.asSequence()
                .filter { it.exerciseId == lift.exerciseId }
                .toList()
                .takeLastWhile { it.topWeightLb >= lift.goalLb }
                .size
            count.takeIf { it > 0 }?.let { Triple(lift.name, count, mains.indexOf(lift)) }
        }.maxWithOrNull(compareBy<Triple<String, Int, Int>> { it.second }.thenBy { -it.third })
        if (goalFact != null) return UiText.TodayGoalLife(goalFact.first, goalFact.second)

        val dated = sessions.map { row ->
            row to Instant.ofEpochMilli(row.session.completedAt).atZone(now.zone).toLocalDate()
        }
        val weekStart = now.date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        val thisWeek = dated.count { (_, date) -> date >= weekStart && date <= now.date }
        val lastDate = dated.firstOrNull()?.second
        if (thisWeek > 0 && lastDate != null) {
            return UiText.TodayWeekLife(thisWeek, strengthDays, daysBetween(lastDate, now.date))
        }
        if (lastDate != null) {
            val month = YearMonth.from(now.date)
            val thisMonth = dated.count { (_, date) -> YearMonth.from(date) == month }
            return UiText.TodayHistoryLife(daysBetween(lastDate, now.date), thisMonth)
        }
        return null
    }

    private fun daysBetween(from: java.time.LocalDate, to: java.time.LocalDate): Int =
        ChronoUnit.DAYS.between(from, to).coerceAtLeast(0).toInt()

    fun standaloneCardioLine(hasCardioDays: Boolean): String? =
        "CARDIO + CORE · 25 MIN · LOG".takeIf { hasCardioDays }

    /**
     * The statement's overline. Reads the same three phases the day itself has,
     * with the `>=` (not `==`) completion test GlanceLines documents: an
     * over-count is better read as a finished day than as an impossible ratio.
     */
    fun overline(doneSets: Int, totalSets: Int): String = when {
        totalSets > 0 && doneSets >= totalSets -> "READY TO FINISH"
        doneSets > 0 -> "IN PROGRESS"
        else -> "NEXT IN ROTATION"
    }

    /** "4 OF 18 SETS" — the progress phrase Today's action label and the day
     *  screen's status line both print, so the two never drift apart. */
    fun setsPhrase(doneSets: Int, totalSets: Int): String = "$doneSets OF $totalSets SETS"

    /** The one dominant action's label, in the same three phases as [overline]. */
    fun actionLabel(dayId: String, doneSets: Int, totalSets: Int): UiText.TodayAction = UiText.TodayAction(
        kind = when {
            totalSets > 0 && doneSets >= totalSets -> TodayActionKind.FINISH
            doneSets > 0 -> TodayActionKind.CONTINUE
            else -> TodayActionKind.START
        }, dayId = dayId, done = doneSets, total = totalSets,
    )

    /** The mode overline owns "EASY"/"HARD"; planner labels remain reusable by Day. */
    fun cardioIntentLine(hard: Boolean, label: String): UiText.TodayCardio = UiText.TodayCardio(hard, label)

    /** The rotation rail: every day of the cycle in order, with [nextDayId] marked. */
    fun rotationMarks(days: List<String>, nextDayId: String): List<RotationMark> =
        days.mapIndexed { index, id -> RotationMark(id, index, isNext = id == nextDayId) }

    /**
     * The heaviest TOP set of one session, named — `null` when [rows] is empty.
     * The caller filters [rows] to a single session; ties keep the first row.
     */
    fun topLiftDisplay(rows: List<TopSetRow>, catalog: ExerciseCatalog, unit: WeightUnit): String? {
        val row = rows.maxByOrNull { it.topWeightLb } ?: return null
        val name = catalog.find(row.exerciseId)?.name ?: row.exerciseId
        return "$name ${WeightStepper.format(unit.fromLb(row.topWeightLb))}"
    }

    /**
     * The quiet last-session strip — "Jul 30, 2026 · 18 sets · Back Squat 245".
     * `null` with no history at all, and the strip is then *absent* from the
     * screen rather than showing placeholder copy. [sessions] arrives
     * newest-first ([cloud.trotter.log.strength.data.TrackerRepository.sessionSummariesFlow]'s
     * contract); the top-lift part drops out silently when that session logged
     * no TOP set.
     */
    fun lastSessionLine(
        sessions: List<SessionSummaryRow>,
        topSets: List<TopSetRow>,
        catalog: ExerciseCatalog,
        unit: WeightUnit,
    ): String? {
        val row = sessions.firstOrNull() ?: return null
        return listOfNotNull(
            LogScreenBuilder.dateDisplay(row.session.completedAt),
            "${row.setCount} sets",
            topLiftDisplay(topSets.filter { it.sessionId == row.session.id }, catalog, unit),
        ).joinToString(" · ")
    }
}
