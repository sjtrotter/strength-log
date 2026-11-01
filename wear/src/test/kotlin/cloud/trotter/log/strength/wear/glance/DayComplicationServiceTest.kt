package cloud.trotter.log.strength.wear.glance

import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.NoDataComplicationData
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import cloud.trotter.log.strength.domain.sync.WatchDay
import cloud.trotter.log.strength.domain.sync.WatchSnapshot
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
