package cloud.trotter.log.strength.ui.today

import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TodayScreenBuilderTest {
    @Test fun noHistoryHasNoFact() {
        assertNull(TodayScreenBuilder.coachLine(null, ZoneId.of("UTC")))
    }
    @Test fun lastTrainedUsesAmericanDateAndCivilZone() {
        assertEquals("Last trained Oct 28", TodayScreenBuilder.coachLine(
            Instant.parse("2026-10-29T01:00:00Z"), ZoneId.of("America/Chicago"),
        ))
    }
}
