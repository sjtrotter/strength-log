package cloud.trotter.log.strength.ui.log

import cloud.trotter.log.strength.data.catalog.ExerciseCatalog
import cloud.trotter.log.strength.data.db.dao.SessionSummaryRow
import cloud.trotter.log.strength.data.db.dao.SessionTonnageRow
import cloud.trotter.log.strength.data.db.dao.TopSetRow
import cloud.trotter.log.strength.domain.model.LifterConfig
import cloud.trotter.log.strength.domain.model.Program
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
import kotlin.math.roundToLong

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

    /** Weeks of tonnage the volume chart shows, including the current one. */
    const val VOLUME_WEEKS = 12

    // Locale.US, not the device default: these are caps display tokens next to
    // English section headers, and Locale.ROOT abbreviates "MMMM" to "Jul".
    private val MONTH_TITLE = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.US)
    private val CELL_DATE = DateTimeFormatter.ofPattern("MMMM d", Locale.US)
    private val CAPTION_DATE = DateTimeFormatter.ofPattern("MMM d", Locale.US)

    /** One ramped main lift and its read-only GOAL — the input to [trajectories]. */
    data class MainLift(
        val exerciseId: String,
        val name: String,
        val dayIndex: Int,
        val goalLb: Double,
    )

    /**
     * The program's ramped mains in day order, de-duplicated by exercise (a lift
     * anchored on two days keeps its first day's accent). [dayIndex] is the day's
     * position in [Program.days] — the same key `dayAccent` takes, so a lift's
     * line matches the tab it lives under.
     *
     * Only weight-target mains qualify: a REPS/TIMED slot has no top-set weight
     * to plot, and the generator already refuses to make one a main.
     */
    fun mainLifts(program: Program, catalog: ExerciseCatalog, cfg: LifterConfig): List<MainLift> {
        val lifts = LinkedHashMap<String, MainLift>()
        program.days.forEachIndexed { dayIndex, day ->
            for (pe in day.exercises) {
                if (!pe.isMain || pe.exerciseId in lifts) continue
                val entry = catalog.find(pe.exerciseId) ?: continue
                val goal = GoalCalculator.targetFor(entry, cfg) as? GoalTarget.Weight ?: continue
                lifts[pe.exerciseId] = MainLift(pe.exerciseId, entry.name, dayIndex, goal.lb)
            }
        }
        return lifts.values.toList()
    }

    /**
     * One card per lift in [mains] that has been trained at least once, from the
     * flat [topSets] history (oldest first). A point is a [TrajectoryPoint.newHigh]
     * when it beats every top set before it — the first one included, since it is
     * the all-time high the moment it lands. (The cascade ceremony uses a stricter
     * rule: see `CascadeCeremony`, which needs a *previous* high to beat.)
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
        var high = Double.NEGATIVE_INFINITY
        val points = rows.map { row ->
            val newHigh = row.topWeightLb > high
            if (newHigh) high = row.topWeightLb
            TrajectoryPoint(unit.fromLb(row.topWeightLb).toFloat(), newHigh)
        }
        val goal = unit.fromLb(lift.goalLb).toFloat()
        val values = points.map { it.value }
        val since = CAPTION_DATE.format(Instant.ofEpochMilli(rows.first().completedAt).atZone(zone))
        val sessionWord = if (rows.size == 1) "SESSION" else "SESSIONS"
        return TrajectoryCard(
            exerciseId = lift.exerciseId,
            exerciseName = lift.name,
            dayIndex = lift.dayIndex,
            points = points,
            goalValue = goal,
            goalLabel = "GOAL ${WeightStepper.format(unit.fromLb(lift.goalLb))}",
            goalMet = high >= lift.goalLb,
            latestLabel = WeightStepper.format(unit.fromLb(rows.last().topWeightLb)),
            caption = "${rows.size} $sessionWord · SINCE ${since.uppercase(Locale.US)}",
            axisMin = axisBound(values, goal, low = true),
            axisMax = axisBound(values, goal, low = false),
            gridlines = gridlines(values),
        )
    }

    /** Plot bounds over the points and the goal line, with 8% headroom so neither
     *  a marker nor the goal label sits flush against the card edge. A flat
     *  series (every session the same weight) still needs a non-zero span. */
    private fun axisBound(values: List<Float>, goal: Float, low: Boolean): Float {
        val min = minOf(values.min(), goal)
        val max = maxOf(values.max(), goal)
        val span = max - min
        val pad = if (span > 0f) span * AXIS_HEADROOM else maxOf(max * AXIS_HEADROOM, 1f)
        return if (low) min - pad else max + pad
    }

    /** At most two faint y-gridlines: the lightest and heaviest top set actually
     *  performed. A flat series collapses to one. */
    private fun gridlines(values: List<Float>): List<TrajectoryGridline> {
        val min = values.min()
        val max = values.max()
        val marks = if (min == max) listOf(min) else listOf(min, max)
        return marks.map { TrajectoryGridline(it, WeightStepper.format(it.toDouble())) }
    }

    /**
     * The last [VOLUME_WEEKS] ISO weeks ending with [today]'s, oldest first.
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

        val peak = totals.max()
        val peakIndex = totals.indexOfFirst { it == peak }
        val labelled = setOfNotNull(peakIndex, (VOLUME_WEEKS - 1).takeIf { trained[it] })
        return VolumeChart(
            bars = List(VOLUME_WEEKS) { i ->
                VolumeBar(
                    fraction = if (peak > 0.0) (totals[i] / peak).toFloat() else 0f,
                    trained = trained[i],
                    label = if (i in labelled && trained[i]) tonnageLabel(totals[i]) else null,
                )
            },
        )
    }

    /** Compact tonnage: "840", "12.4K", "124K". Bars carry at most two of these,
     *  so precision matters less than staying one glanceable token wide. */
    fun tonnageLabel(value: Double): String {
        if (value < 1_000) return value.roundToLong().toString()
        val thousands = value / 1_000
        return if (thousands >= 100) {
            "${thousands.roundToLong()}K"
        } else {
            String.format(Locale.ROOT, "%.1fK", thousands)
        }
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
    ): CalendarMonth? {
        if (sessions.isEmpty()) return null
        val offset = monthOffset.coerceAtMost(0)
        val month = YearMonth.from(today).plusMonths(offset.toLong())

        // Oldest first so "the first session of that day" is the letter shown.
        val byDate = sessions
            .sortedBy { it.session.completedAt }
            .groupBy { Instant.ofEpochMilli(it.session.completedAt).atZone(zone).toLocalDate() }
        val earliest = byDate.keys.min()

        val days = (1..month.lengthOfMonth()).map { dayOfMonth ->
            val date = month.atDay(dayOfMonth)
            val onDate = byDate[date]
            val first = onDate?.first()?.session
            // The drawn cell is only a letter or a numeral; its month, its today
            // state and its session count exist solely in this spoken label.
            val label = buildList {
                add(CELL_DATE.format(date))
                if (date == today) add("today")
                if (onDate == null) {
                    add("no session")
                } else {
                    add("day ${onDate.first().session.dayId}")
                    add(if (onDate.size == 1) "1 session" else "${onDate.size} sessions")
                }
            }.joinToString(", ")
            CalendarDay(
                dayOfMonth = dayOfMonth,
                label = label,
                dayLetter = first?.dayId,
                dayIndex = first?.let { LogScreenBuilder.dayIndex(it.dayId) } ?: 0,
                sessionId = first?.id,
                moreSessions = (onDate?.size ?: 0) > 1,
                isToday = date == today,
            )
        }
        return CalendarMonth(
            title = MONTH_TITLE.format(month).uppercase(Locale.US),
            monthOffset = offset,
            canPageBack = YearMonth.from(earliest) < month,
            canPageForward = offset < 0,
            leadingBlanks = month.atDay(1).dayOfWeek.value - DayOfWeek.MONDAY.value,
            days = days,
        )
    }

    private const val DAYS_PER_WEEK = 7
    private const val AXIS_HEADROOM = 0.08f
}
