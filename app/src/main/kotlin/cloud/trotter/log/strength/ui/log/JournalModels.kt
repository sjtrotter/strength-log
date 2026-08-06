package cloud.trotter.log.strength.ui.log

/**
 * The three derived sections that turn the Log screen into a journal
 * (docs/briefs/journal.md §1). Every field here is already display-ready —
 * strings formatted, weights converted, geometry normalized to 0..1 — so the
 * chart composables only place marks and never do arithmetic on domain values.
 *
 * A section is `null`/empty when history can't support it; nothing renders an
 * empty-state card (§4.1 — the session list's own empty state leads).
 */
data class JournalUiState(
    val trajectories: List<TrajectoryCard> = emptyList(),
    val volume: VolumeChart? = null,
    val calendar: CalendarMonth? = null,
)

/**
 * One main lift's top-set story (§1.1). [points] is one entry per session that
 * trained the lift, oldest first; [plotted] is false with fewer than two points,
 * where the card degrades to the current prescription plus the goal as text.
 *
 * [axisMin]/[axisMax] bracket both the points and [goalValue] with a little
 * headroom, so a goal above every lift still has its reference line on screen.
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
    val caption: String,
    val axisMin: Float,
    val axisMax: Float,
    val gridlines: List<TrajectoryGridline>,
) {
    val plotted: Boolean get() = points.size >= 2
}

/** One session's top set. [newHigh] marks an all-time high — the only points
 *  that get a marker, because they are the engine's visible click. */
data class TrajectoryPoint(val value: Float, val newHigh: Boolean)

/** A faint y-axis reference: the value line plus the numeral shown beside it. */
data class TrajectoryGridline(val value: Float, val label: String)

/** Twelve ISO weeks of tonnage, oldest first (§1.2). */
data class VolumeChart(val bars: List<VolumeBar>)

/**
 * One week's bar. [trained] false means no session that week — the slot stays
 * empty rather than drawing a zero-height bar, because the gap is the
 * information. [label] is set only on the tallest week and the current one.
 */
data class VolumeBar(
    val fraction: Float,
    val trained: Boolean,
    val label: String? = null,
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
 * has neither and shows only its faint numeral.
 */
data class CalendarDay(
    val dayOfMonth: Int,
    val dayLetter: String? = null,
    val dayIndex: Int = 0,
    val sessionId: Long? = null,
    val moreSessions: Boolean = false,
    val isToday: Boolean = false,
)
