package cloud.trotter.log.strength.ui.log

import cloud.trotter.log.strength.data.catalog.ExerciseCatalog
import cloud.trotter.log.strength.data.db.dao.SessionSummaryRow
import cloud.trotter.log.strength.data.db.dao.SessionTonnageRow
import cloud.trotter.log.strength.data.db.dao.TopSetRow
import cloud.trotter.log.strength.data.db.entity.CardioSessionEntity
import cloud.trotter.log.strength.domain.library.GoalSource
import cloud.trotter.log.strength.domain.model.LifterConfig
import cloud.trotter.log.strength.domain.model.LoggedSet
import cloud.trotter.log.strength.domain.model.PrescribedTarget
import cloud.trotter.log.strength.domain.model.Program
import cloud.trotter.log.strength.domain.model.SetKind
import cloud.trotter.log.strength.domain.records.PersonalBests
import cloud.trotter.log.strength.domain.standards.GoalCalculator
import cloud.trotter.log.strength.domain.standards.GoalTarget
import cloud.trotter.log.strength.domain.units.WeightStepper
import cloud.trotter.log.strength.domain.units.WeightUnit
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters
import java.util.Locale

/**
 * The journal's pure derivations (docs/briefs/journal.md §3): trajectory series
 * and their new-high markers, weekly tonnage buckets including the empty weeks,
 * and the month grid. Android-free (java.time is plain JDK) so every rule is a
 * JVM unit test, exactly like [LogScreenBuilder] next door.
 *
 * GOALs are only ever *read* here — [GoalCalculator] stays the SSOT and nothing
 * in the journal recomputes or advances a target.
 */
object JournalBuilder {

    /** Weeks of tonnage the volume section shows, including the current one. */
    const val VOLUME_WEEKS = 12

    private val MONTH_TITLE = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.US)
    private val CELL_DATE = DateTimeFormatter.ofPattern("MMMM d", Locale.US)

    /** One main lift and its optional read-only weight GOAL — the input to [trajectories]. */
    data class MainLift(
        val exerciseId: String,
        val name: String,
        val dayIndex: Int,
        val goalLb: Double?,
    )

    /**
     * The program's main lifts in day order, de-duplicated by exercise (a lift
     * anchored on two days keeps its first day's accent).
     *
     * REPS/TIMED prescriptions have no top-set weight to plot.
     */
    fun mainLifts(program: Program, catalog: ExerciseCatalog, cfg: LifterConfig): List<MainLift> {
        val lifts = LinkedHashMap<String, MainLift>()
        program.days.forEach { day ->
            for (pe in day.exercises) {
                val entry = catalog.find(pe.exerciseId) ?: continue
                if ((!pe.structure.hasTopSet && entry.goal !is GoalSource.Std) || pe.exerciseId in lifts) continue
                val target = pe.prescription?.target
                if (target != null && target !is PrescribedTarget.LoadReps) continue
                val goalLb = (GoalCalculator.targetFor(entry, cfg) as? GoalTarget.Weight)?.lb
                if (target == null && goalLb == null) continue
                lifts[pe.exerciseId] = MainLift(pe.exerciseId, entry.name, Program.accentIndex(day.id), goalLb)
            }
        }
        return lifts.values.toList()
    }

    /**
     * One card per lift in [mains] that has been trained at least once, from the
     * flat [topSets] history (oldest first). A point is a [TrajectoryPoint.newHigh]
     * when it beats every top set before it — the first one included, since it is
     * the all-time high the moment it lands.
     */
    fun trajectories(
        mains: List<MainLift>,
        topSets: List<TopSetRow>,
        unit: WeightUnit,
        zone: ZoneId = ZoneId.systemDefault(),
    ): List<TrajectoryCard> {
        val byExercise = topSets.groupBy { it.exerciseId }
        return mains.mapNotNull { lift ->
            val rows = byExercise[lift.exerciseId].orEmpty()
            if (rows.isEmpty()) return@mapNotNull null
            card(lift, rows, unit, zone)
        }
    }

    private fun card(
        lift: MainLift,
        rows: List<TopSetRow>,
        unit: WeightUnit,
        zone: ZoneId,
    ): TrajectoryCard {
        var standing: LoggedSet? = null
        val points = rows.map { row ->
            val candidate = LoggedSet(row.topWeightLb, 0, SetKind.TOP, done = true)
            // The chart marks a lift's first session even though first times are not PRs.
            val newHigh = standing == null || PersonalBests.prKind(candidate, standing) != null
            if (newHigh) standing = candidate
            TrajectoryPoint(
                unit.fromLb(row.topWeightLb).toFloat(), newHigh,
                Instant.ofEpochMilli(row.completedAt).atZone(zone).toLocalDate(),
            )
        }
        val goalLb = lift.goalLb
        val goal = goalLb?.let { unit.fromLb(it).toFloat() }
        val values = points.map { it.value }
        return TrajectoryCard(
            exerciseId = lift.exerciseId,
            exerciseName = lift.name,
            dayIndex = lift.dayIndex,
            points = points,
            goalValue = goal,
            goalLabel = goalLb?.let { WeightStepper.format(unit.fromLb(it)) },
            goalMet = goalLb != null && rows.maxOf { it.topWeightLb } >= goalLb,
            latestLabel = WeightStepper.format(unit.fromLb(rows.last().topWeightLb)),
            unit = unit,
            axisMin = axisBound(values, goal, low = true),
            axisMax = axisBound(values, goal, low = false),
        )
    }

    /** Plot bounds over the points and the goal line, with 8% headroom so neither
     *  a marker nor the goal line sits flush against the plot edge. A flat
     *  series (every session the same weight) still needs a non-zero span. */
    private fun axisBound(values: List<Float>, goal: Float?, low: Boolean): Float {
        val min = minOf(values.min(), goal ?: values.min())
        val max = maxOf(values.max(), goal ?: values.max())
        val span = max - min
        val pad = if (span > 0f) span * AXIS_HEADROOM else maxOf(max * AXIS_HEADROOM, 1f)
        return if (low) min - pad else max + pad
    }

    /**
     * The last [VOLUME_WEEKS] ISO weeks ending with [today]'s, newest first.
     * Untrained weeks are present but empty — a missed week is part of the
     * rhythm, not a gap to close up. Returns null when nothing was ever
     * completed in the window, so the section simply doesn't render.
     */
    fun volume(
        tonnage: List<SessionTonnageRow>,
        unit: WeightUnit,
        today: LocalDate,
        zone: ZoneId = ZoneId.systemDefault(),
    ): VolumeChart? {
        val currentWeek = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        val firstWeek = currentWeek.minusWeeks((VOLUME_WEEKS - 1).toLong())
        val totals = DoubleArray(VOLUME_WEEKS)
        val trained = BooleanArray(VOLUME_WEEKS)
        for (row in tonnage) {
            val week = Instant.ofEpochMilli(row.completedAt).atZone(zone).toLocalDate()
                .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
            if (week < firstWeek || week > currentWeek) continue
            val index = (week.toEpochDay() - firstWeek.toEpochDay()).toInt() / DAYS_PER_WEEK
            totals[index] += unit.fromLb(row.tonnageLb)
            trained[index] = true
        }
        if (trained.none { it }) return null

        return VolumeChart(
            weeks = (VOLUME_WEEKS - 1 downTo 0).map { i ->
                VolumeWeek(
                    startDate = firstWeek.plusWeeks(i.toLong()),
                    total = totals[i],
                    unit = unit,
                    trained = trained[i],
                )
            },
        )
    }

    /**
     * The month grid [monthOffset] months before [today]'s month (0 = current,
     * never positive — there is nothing to see ahead of today). Returns null with
     * no history at all. Paging back stops at the month of the first session.
     *
     * Weeks start Monday, matching the ISO buckets [volume] uses.
     */
    fun calendar(
        sessions: List<SessionSummaryRow>,
        monthOffset: Int,
        today: LocalDate,
        zone: ZoneId = ZoneId.systemDefault(),
        cardioSessions: List<CardioSessionEntity> = emptyList(),
    ): CalendarMonth? {
        if (sessions.isEmpty() && cardioSessions.isEmpty()) return null
        val offset = monthOffset.coerceAtMost(0)
        val month = YearMonth.from(today).plusMonths(offset.toLong())

        // Oldest first so "the first session of that day" is the letter shown.
        val occurrences = sessions.map { Occurrence(it.session.completedAt, it.session.dayId, it.session.id) } +
            cardioSessions.map { Occurrence(it.completedAt, it.dayId.orEmpty(), null) }
        val byDate = occurrences.sortedBy { it.completedAt }
            .groupBy { Instant.ofEpochMilli(it.completedAt).atZone(zone).toLocalDate() }
        val earliest = byDate.keys.min()

        val days = (1..month.lengthOfMonth()).map { dayOfMonth ->
            val date = month.atDay(dayOfMonth)
            val onDate = byDate[date]
            val first = onDate?.first()
            // The spoken label adds the month, today state and session count.
            val label = buildList {
                add(CELL_DATE.format(date))
                if (date == today) add("today")
                if (onDate == null) {
                    add("no session")
                } else {
                    add("day ${onDate.first().dayId}")
                    add(if (onDate.size == 1) "1 session" else "${onDate.size} sessions")
                }
            }.joinToString(", ")
            CalendarDay(
                dayOfMonth = dayOfMonth,
                label = label,
                dayLetter = first?.dayId,
                dayIndex = first?.let { Program.accentIndex(it.dayId) } ?: 0,
                sessionId = first?.strengthId,
                moreSessions = (onDate?.size ?: 0) > 1,
                isToday = date == today,
            )
        }
        return CalendarMonth(
            title = MONTH_TITLE.format(month),
            monthOffset = offset,
            canPageBack = YearMonth.from(earliest) < month,
            canPageForward = offset < 0,
            leadingBlanks = month.atDay(1).dayOfWeek.value - DayOfWeek.MONDAY.value,
            days = days,
        )
    }

    private data class Occurrence(val completedAt: Long, val dayId: String, val strengthId: Long?)

    private const val DAYS_PER_WEEK = 7
    private const val AXIS_HEADROOM = 0.08f
}
