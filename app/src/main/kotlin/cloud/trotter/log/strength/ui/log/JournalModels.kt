package cloud.trotter.log.strength.ui.log

import cloud.trotter.log.strength.domain.units.WeightUnit
import java.time.LocalDate

/** Derived History sections. Dates and totals retain their values for localized presentation. */
data class JournalUiState(
    val trajectories: List<TrajectoryCard> = emptyList(),
    val volume: VolumeChart? = null,
    val calendar: CalendarMonth? = null,
)

/**
 * One main lift's top-set story (§1.1). [points] is one entry per session that
 * trained the lift, oldest first; [plotted] is false with fewer than two points,
 * where the card explains the missing trend and labels the latest result.
 *
 * [axisMin]/[axisMax] bracket both the points and [goalValue] with a little
 * headroom, so a goal above every lift still has its reference line on screen.
 * [goalLabel] and [latestLabel] are numeric weights in [unit], without surrounding copy.
 */
data class TrajectoryCard(
    val exerciseId: String,
    val exerciseName: String,
    val dayIndex: Int,
    val points: List<TrajectoryPoint>,
    val goalValue: Float,
    val goalLabel: String,
    val goalMet: Boolean,
    val latestLabel: String,
    val unit: WeightUnit,
    val axisMin: Float,
    val axisMax: Float,
) {
    val plotted: Boolean get() = points.size >= 2
}

/** One session's top set. [newHigh] marks an all-time high — the only points
 *  that get a marker, because they are the engine's visible click. */
data class TrajectoryPoint(val value: Float, val newHigh: Boolean, val date: LocalDate)

/** Up to twelve ISO weeks of volume, newest first. */
data class VolumeChart(val weeks: List<VolumeWeek>)

/** [trained] distinguishes verified empty weeks; a null [total] means unavailable data. */
data class VolumeWeek(
    val startDate: LocalDate,
    val total: Double?,
    val unit: WeightUnit,
    val trained: Boolean,
)

/** One month of the training calendar (§1.3), always a whole month of cells. */
data class CalendarMonth(
    val title: String,
    val monthOffset: Int,
    val canPageBack: Boolean,
    val canPageForward: Boolean,
    /** Empty leading cells before the 1st, so the grid starts on the right weekday. */
    val leadingBlanks: Int,
    val days: List<CalendarDay>,
)

/**
 * One calendar cell. A trained day carries [dayLetter] (identity is the letter;
 * the accent is flavor) and the [sessionId] a tap scrolls to; an untrained day
 * has neither and shows only its faint numeral. [label] is the spoken form,
 * since neither visible form states the month.
 */
data class CalendarDay(
    val dayOfMonth: Int,
    /** The cell's whole spoken sentence (TalkBack), built by [JournalBuilder.calendar]. */
    val label: String,
    val dayLetter: String? = null,
    val dayIndex: Int = 0,
    val sessionId: Long? = null,
    val moreSessions: Boolean = false,
    val isToday: Boolean = false,
)
