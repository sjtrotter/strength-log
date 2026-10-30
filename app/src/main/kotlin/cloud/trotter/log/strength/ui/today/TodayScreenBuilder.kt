package cloud.trotter.log.strength.ui.today

import cloud.trotter.log.strength.data.db.dao.SessionSummaryRow
import cloud.trotter.log.strength.data.db.dao.TopSetRow
import cloud.trotter.log.strength.time.CivilTime
import cloud.trotter.log.strength.ui.log.JournalBuilder
import cloud.trotter.log.strength.ui.text.TodayActionKind
import cloud.trotter.log.strength.ui.text.TodayCoachKind
import cloud.trotter.log.strength.ui.text.UiText
import java.time.DayOfWeek
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters

/**
 * Pure decision logic behind the Today screen, kept free of Android and
 * coroutines so every rule is unit-testable on the JVM.
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

    fun standaloneCardioLine(): String = "Log cardio + core, 25 min"

    /**
     * "Last trained 4 Aug, 10 weeks ago." — one fact; null with no history.
     * Weeks are rounded ((days + 3) / 7) once days ≥ 14.
     */
    fun coachLine(lastCompletedAt: Instant?, now: Instant, zone: ZoneId): UiText? {
        if (lastCompletedAt == null) return null
        val completedDate = lastCompletedAt.atZone(zone).toLocalDate()
        val today = now.atZone(zone).toLocalDate()
        val days = ChronoUnit.DAYS.between(completedDate, today).coerceAtLeast(0).toInt()
        val date = completedDate.format(DateTimeFormatter.ofPattern("d MMM"))
        return when (days) {
            0 -> UiText.TodayCoach(TodayCoachKind.TODAY)
            1 -> UiText.TodayCoach(TodayCoachKind.YESTERDAY)
            in 2..13 -> UiText.TodayCoach(TodayCoachKind.DAYS, date, days)
            else -> UiText.TodayCoach(TodayCoachKind.WEEKS, date, (days + 3) / 7)
        }
    }

    /** "4 of 21 sets logged." while a day is in progress. */
    fun progressLine(doneSets: Int, totalSets: Int): UiText.TodayProgress =
        UiText.TodayProgress(doneSets, totalSets)

    /** The one dominant action's label. */
    fun actionLabel(dayId: String, doneSets: Int, totalSets: Int): UiText.TodayAction = UiText.TodayAction(
        kind = when {
            totalSets > 0 && doneSets >= totalSets -> TodayActionKind.FINISH
            doneSets > 0 -> TodayActionKind.CONTINUE
            else -> TodayActionKind.START
        }, dayId = dayId, done = doneSets, total = totalSets,
    )

}
