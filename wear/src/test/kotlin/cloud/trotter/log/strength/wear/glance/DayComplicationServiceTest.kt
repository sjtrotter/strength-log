package cloud.trotter.log.strength.wear.glance

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.NoDataComplicationData
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import cloud.trotter.log.strength.domain.sync.WatchDay
import cloud.trotter.log.strength.domain.sync.WatchSnapshot
import cloud.trotter.log.strength.domain.glance.GlanceLines
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DayComplicationServiceTest {
    @Test fun `localized glance resources match the shared sentence case lines`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val copy = dayGlanceCopy(context)
        val glance = DayGlance(true, "A", "Squat focus", 0, 4, 0, 18)
        assertEquals(GlanceLines.dayLine("A", "Squat focus"), glance.titleLine(copy))
        for (done in listOf(0, 2, 18)) {
            assertEquals(GlanceLines.statLine(4, done, 18), glance.copy(doneSets = done).setLine(copy))
        }
    }

    @Test fun `blank-day requests return no data for both advertised types`() = runTest {
        val snapshot = WatchSnapshot(
            revision = 1, suggestedDayId = "", unit = "lb",
            day = WatchDay("", "", 0, emptyList()),
        )
        val service = object : DayComplicationService() {
            override suspend fun readSnapshot() = snapshot
        }

        for (type in listOf(ComplicationType.SHORT_TEXT, ComplicationType.RANGED_VALUE)) {
            val response = service.onComplicationRequest(ComplicationRequest(1, type, false))
            assertTrue(response is NoDataComplicationData)
            assertEquals(ComplicationType.NO_DATA, response?.type)
        }
    }
}
