package cloud.trotter.log.strength.widget

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Build
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
    val lightAccent = DayAccentColors.deepHex(accentIndex).toInt()
    val darkAccent = DayAccentColors.brightHex(accentIndex).toInt()
    views.setViewVisibility(R.id.widget_day_letter, if (dayLetter == null) View.GONE else View.VISIBLE)
    if (dayLetter != null) {
        views.setTextViewText(R.id.widget_day_letter, dayLetter)
        views.setInt(R.id.widget_day_letter, "setBackgroundColor", DayAccentColors.hex(accentIndex).toInt())
        views.setTextColor(R.id.widget_day_letter, DayAccentColors.onAccentHex(accentIndex).toInt())
    }

    views.setViewVisibility(R.id.widget_day_line, if (dayLine == null) View.GONE else View.VISIBLE)
    if (dayLine != null) {
        views.setTextViewText(R.id.widget_day_line, dayLine)
        views.setThemeTextColor(context, R.id.widget_day_line, R.color.widget_text_primary)
    }

    views.setTextViewText(R.id.widget_stat_line, when (val line = statLine) {
        WidgetStatLine.NothingOnToday -> context.getString(R.string.nothing_on_today)
        WidgetStatLine.SetUpProgram -> context.getString(R.string.widget_no_program)
        is WidgetStatLine.Data -> line.value
    })
    views.setThemeTextColor(context, R.id.widget_stat_line, when (state) {
        TodayWidgetState.NO_PROGRAM, TodayWidgetState.NOTHING_ON_TODAY -> R.color.widget_text_secondary
        TodayWidgetState.DONE -> R.color.widget_done
        else -> R.color.widget_text_primary
    })

    // The bar earns its 3dp only while there is a partial day to show.
    val showProgress = state == TodayWidgetState.IN_PROGRESS
    views.setViewVisibility(R.id.widget_progress, if (showProgress) View.VISIBLE else View.GONE)
    if (showProgress) {
        views.setInt(R.id.widget_progress_fill, "setImageLevel", CLIP_MAX * setsDone / totalSets)
        views.setThemeColor(context, R.id.widget_progress_fill, "setColorFilter", lightAccent, darkAccent)
    }

    views.setOnClickPendingIntent(R.id.widget_root, openApp(context))
    return views
}

/** API 31 hosts choose the color at apply time, including after a system theme change. */
private fun RemoteViews.setThemeTextColor(context: Context, viewId: Int, colorId: Int) {
    fun color(mode: Int): Int {
        val config = Configuration(context.resources.configuration)
        config.uiMode = (config.uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or mode
        return context.createConfigurationContext(config).getColor(colorId)
    }
    setThemeColor(context, viewId, "setTextColor", color(Configuration.UI_MODE_NIGHT_NO), color(Configuration.UI_MODE_NIGHT_YES))
}

private fun RemoteViews.setThemeColor(context: Context, viewId: Int, method: String, light: Int, dark: Int) {
    if (Build.VERSION.SDK_INT >= 31) setColorInt(viewId, method, light, dark)
    else {
        val night = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        setInt(viewId, method, if (night) dark else light)
    }
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
