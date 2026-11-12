package cloud.trotter.log.strength.run

import android.app.Application
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import cloud.trotter.log.strength.MainActivity
import cloud.trotter.log.strength.domain.model.CardioActivity
import cloud.trotter.log.strength.domain.units.DistanceUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class RunNotificationTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val notification = RunNotification(context)

    @Test fun contentIntentResumesTheLauncherTask() {
        val notice = RunNotice(CardioActivity.ROAD_RUN, 1000, GpsLive(GpsLiveStatus.FINDING, null, null))
        val pending = notification.build(notice, DistanceUnit.MI).contentIntent
        val intent = shadowOf(pending).savedIntent

        assertEquals(MainActivity::class.java.name, intent.component?.className)
        assertEquals(Intent.ACTION_MAIN, intent.action)
        assertTrue(intent.hasCategory(Intent.CATEGORY_LAUNCHER))
        assertTrue(intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
        assertTrue(intent.flags and Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED != 0)
        assertEquals(0, shadowOf(pending).requestCode)
        assertTrue(shadowOf(pending).flags and PendingIntent.FLAG_IMMUTABLE != 0)
    }

    @Test fun missingNoticeShowsFindingGpsWithoutAChronometer() {
        val built = notification.build(null, DistanceUnit.MI)

        assertEquals("Finding GPS", built.extras.getString(Notification.EXTRA_TITLE))
        assertEquals("Finding GPS", built.extras.getString(Notification.EXTRA_TEXT))
        assertFalse(built.extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER))
    }
    @Test fun initialNotificationFindsGpsUntilTheDistanceSettingIsKnown() {
        val notice = RunNotice(CardioActivity.ROAD_RUN, 1000, GpsLive(GpsLiveStatus.LIVE, 1996, 3.0))
        assertEquals("Finding GPS", notification.build(notice, null).extras.getString(Notification.EXTRA_TEXT))
        assertEquals("1.24 mi", notification.build(notice, DistanceUnit.MI).extras.getString(Notification.EXTRA_TEXT))
    }

}
