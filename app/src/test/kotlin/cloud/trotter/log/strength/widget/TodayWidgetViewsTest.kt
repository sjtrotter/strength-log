package cloud.trotter.log.strength.widget

import android.app.Application
import android.content.Context
import android.content.res.Configuration
import android.graphics.PorterDuffColorFilter
import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import cloud.trotter.log.strength.R
import cloud.trotter.log.strength.domain.theme.DayAccentColors
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31], application = Application::class)
class TodayWidgetViewsTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private fun themed(night: Boolean): Context {
        val config = Configuration(context.resources.configuration)
        config.uiMode = (config.uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
            if (night) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
        return context.createConfigurationContext(config)
    }

    @Test fun oneRemoteViewsUsesHostThemeForTextBackgroundAndDerivedProgressAccent() {
        val content = TodayWidgetContent("Day A, Squat focus", WidgetStatLine.Data("2 of 18 sets"),
            TodayWidgetState.IN_PROGRESS, 0, 2, 18, "A")
        val remote = content.toRemoteViews(themed(false))
        for (night in listOf(false, true)) {
            val host = themed(night)
            val view = remote.apply(host, null)
            val day = view.findViewById<TextView>(R.id.widget_day_line)
            val stat = view.findViewById<TextView>(R.id.widget_stat_line)
            val letter = view.findViewById<TextView>(R.id.widget_day_letter)
            val progress = view.findViewById<ImageView>(R.id.widget_progress_fill)
            assertEquals("Day A, Squat focus", day.text.toString())
            assertEquals("2 of 18 sets", stat.text.toString())
            assertEquals("A", letter.text.toString())
            assertEquals(View.VISIBLE, letter.visibility)
            assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_NO, letter.importantForAccessibility)
            assertEquals(DayAccentColors.onAccentHex(0).toInt(), letter.currentTextColor)
            assertEquals(host.getColor(R.color.widget_text_primary), day.currentTextColor)
            assertEquals(host.getColor(R.color.widget_text_primary), stat.currentTextColor)
            val background = view.background as GradientDrawable
            assertEquals(host.getColor(R.color.widget_background), background.color!!.defaultColor)
            assertEquals(if (night) DayAccentColors.brightHex(0).toInt() else DayAccentColors.deepHex(0).toInt(),
                shadowOf(progress.colorFilter as PorterDuffColorFilter).color)
            assertEquals(10_000 * 2 / 18, progress.drawable.level)
        }
    }

    @Test @Config(sdk = [30]) fun olderHostsUseTheCurrentResourceTheme() {
        val content = TodayWidgetContent("Day B, Upper", WidgetStatLine.Data("1 of 4 sets"),
            TodayWidgetState.IN_PROGRESS, 1, 1, 4, "B")
        val remote = content.toRemoteViews(themed(false))
        for (night in listOf(false, true)) {
            val host = themed(night)
            val view = remote.apply(host, null)
            assertEquals(host.getColor(R.color.widget_text_primary), view.findViewById<TextView>(R.id.widget_day_line).currentTextColor)
            assertEquals(host.getColor(R.color.widget_text_secondary), view.findViewById<TextView>(R.id.widget_stat_line).currentTextColor)
        }
    }

    @Test fun emptyWidgetUsesSentenceCaseAndHidesTheDayBlock() {
        val view = todayWidgetContent(null).toRemoteViews(context).apply(context, null)
        assertEquals("Set up a plan", view.findViewById<TextView>(R.id.widget_stat_line).text.toString())
        assertEquals(View.GONE, view.findViewById<View>(R.id.widget_day_letter).visibility)
        assertEquals(View.GONE, view.findViewById<View>(R.id.widget_day_line).visibility)
        assertEquals(View.GONE, view.findViewById<View>(R.id.widget_progress).visibility)
    }

    @Test fun pickerPreviewShowsASampleDay() {
        val view = LayoutInflater.from(context).inflate(R.layout.widget_today_preview, null)
        assertEquals("A", view.findViewById<TextView>(R.id.widget_day_letter).text.toString())
        assertEquals("Day A, Squat focus", view.findViewById<TextView>(R.id.widget_day_line).text.toString())
        assertEquals("2 of 18 sets", view.findViewById<TextView>(R.id.widget_stat_line).text.toString())
        assertEquals(View.VISIBLE, view.findViewById<View>(R.id.widget_progress).visibility)
    }
}
