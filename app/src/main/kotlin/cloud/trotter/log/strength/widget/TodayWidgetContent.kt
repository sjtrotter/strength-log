package cloud.trotter.log.strength.widget

import cloud.trotter.log.strength.domain.glance.DayProgress
import cloud.trotter.log.strength.domain.glance.GlanceLines
import cloud.trotter.log.strength.domain.sync.WatchSnapshot

/** Which of the widget's four faces to paint; drives colour, not text. */
enum class TodayWidgetState {
    /** A day is queued but untouched — the widget advertises the work ahead. */
    BEFORE,
    IN_PROGRESS,
    DONE,

    /** No program yet (or a day with no exercises): one tertiary line, never blank. */
    NO_PROGRAM,
}

/**
 * Everything the RemoteViews layer needs, already resolved. Pure data — the
 * assembly in [TodayWidgetViews] reads these fields and does no deriving of its
 * own.
 */
data class TodayWidgetContent(
    /** Null only in [TodayWidgetState.NO_PROGRAM], where the line is hidden. */
    val dayLine: String?,
    val statLine: WidgetStatLine,
    val state: TodayWidgetState,
    /** Day index for [cloud.trotter.log.strength.domain.theme.DayAccentColors]; 0 when there's no day. */
    val accentIndex: Int,
    val setsDone: Int,
    val totalSets: Int,
)

sealed interface WidgetStatLine {
    data object SetUpProgram : WidgetStatLine
    data class Data(val value: String) : WidgetStatLine
}

/**
 * Projects the shared today-state ([cloud.trotter.log.strength.sync.TodaySnapshotSource])
 * into the widget's two lines (glance-surfaces.md §1). The counts are the same
 * ones the watch's day list shows: one round of the main track is one set, and a
 * superset partner rides inside that round rather than adding to it.
 *
 * Line 1 pairs the day letter with the day *title* — the emphasis line the day
 * screen prints underneath it is a five-clause sentence that can't survive one
 * widget row.
 */
fun todayWidgetContent(snapshot: WatchSnapshot?): TodayWidgetContent {
    val day = snapshot?.day
    if (day == null || day.exercises.isEmpty()) {
        return TodayWidgetContent(
            dayLine = null,
            statLine = WidgetStatLine.SetUpProgram,
            state = TodayWidgetState.NO_PROGRAM,
            accentIndex = 0,
            setsDone = 0,
            totalSets = 0,
        )
    }

    val progress = DayProgress.of(day)
    val total = progress.total
    val done = progress.done
    val state = when {
        total > 0 && done == total -> TodayWidgetState.DONE
        done == 0 -> TodayWidgetState.BEFORE
        else -> TodayWidgetState.IN_PROGRESS
    }

    return TodayWidgetContent(
        dayLine = GlanceLines.dayLine(day.dayId, day.title),
        statLine = WidgetStatLine.Data(GlanceLines.statLine(day.exercises.size, done, total)),
        state = state,
        accentIndex = day.accentIndex,
        setsDone = done,
        totalSets = total,
    )
}
