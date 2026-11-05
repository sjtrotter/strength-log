package cloud.trotter.log.strength.ui.day

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "en-rUS")
class RestAnnouncementTest {
    private val resources = ApplicationProvider.getApplicationContext<Context>().resources

    @Test fun formats45Seconds() {
        assertEquals("Rest 45 seconds", formatRestAnnouncement(resources, 45))
    }

    @Test fun formats60Seconds() {
        assertEquals("Rest 1 minute", formatRestAnnouncement(resources, 60))
    }

    @Test fun formats90Seconds() {
        assertEquals("Rest 1 minute 30 seconds", formatRestAnnouncement(resources, 90))
    }

    @Test fun formats120Seconds() {
        assertEquals("Rest 2 minutes", formatRestAnnouncement(resources, 120))
    }

    @Test fun formats150Seconds() {
        assertEquals("Rest 2 minutes 30 seconds", formatRestAnnouncement(resources, 150))
    }
}
