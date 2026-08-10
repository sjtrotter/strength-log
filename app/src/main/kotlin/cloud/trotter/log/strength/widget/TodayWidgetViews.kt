package cloud.trotter.log.strength.widget

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.RemoteViews
import cloud.trotter.log.strength.MainActivity
import cloud.trotter.log.strength.R
import cloud.trotter.log.strength.domain.theme.DayAccentColors

/** A [android.graphics.drawable.ClipDrawable] is fully revealed at level 10000. */
private const val CLIP_MAX = 10_000

/**
 * Paints [TodayWidgetContent] onto the widget layout. Deliberately dumb: every
 * string and count is already decided, and the only lookup here is the day accent
 * — read from [DayAccentColors] so the widget, the day screen and the watch all
 * tint from one table.
 */
internal fun TodayWidgetContent.toRemoteViews(context: Context): RemoteViews {
    val views = RemoteViews(context.packageName, R.layout.widget_today)
    val accent = DayAccentColors.hex(accentIndex).toInt()

    views.setViewVisibility(R.id.widget_day_line, if (dayLine == null) View.GONE else View.VISIBLE)
    if (dayLine != null) {
        views.setTextViewText(R.id.widget_day_line, dayLine)
        views.setTextColor(R.id.widget_day_line, accent)
    }

    views.setTextViewText(R.id.widget_stat_line, when (val line = statLine) {
        WidgetStatLine.SetUpProgram -> context.getString(R.string.widget_no_program)
        is WidgetStatLine.Data -> line.value
    })
    views.setTextColor(
        R.id.widget_stat_line,
        context.getColor(
            when (state) {
                TodayWidgetState.NO_PROGRAM -> R.color.widget_text_secondary
                TodayWidgetState.DONE -> R.color.widget_done
                else -> R.color.widget_text_primary
            },
        ),
    )

    // The bar earns its 3dp only while there is a partial day to show.
    val showProgress = state == TodayWidgetState.IN_PROGRESS
    views.setViewVisibility(R.id.widget_progress, if (showProgress) View.VISIBLE else View.GONE)
    if (showProgress) {
        views.setInt(R.id.widget_progress_fill, "setImageLevel", CLIP_MAX * setsDone / totalSets)
        views.setInt(R.id.widget_progress_fill, "setColorFilter", accent)
    }

    views.setOnClickPendingIntent(R.id.widget_root, openApp(context))
    return views
}

/** ACTION_MAIN/LAUNCHER on an explicit intent: tapping the widget resumes the
 *  app's existing task exactly as tapping its icon would, rather than stacking a
 *  second MainActivity on top of whatever the user left open. */
private fun openApp(context: Context): PendingIntent {
    val intent = Intent(context, MainActivity::class.java).apply {
        action = Intent.ACTION_MAIN
        addCategory(Intent.CATEGORY_LAUNCHER)
        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
    }
    return PendingIntent.getActivity(
        context,
        0,
        intent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
}
