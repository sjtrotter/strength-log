package cloud.trotter.log.strength.domain.progression

import cloud.trotter.log.strength.domain.model.CardioRole
import cloud.trotter.log.strength.domain.model.CardioSetResult
import cloud.trotter.log.strength.domain.model.CardioSetSpec
import cloud.trotter.log.strength.domain.model.CardioSetStatus
import cloud.trotter.log.strength.domain.model.DistanceSource
import cloud.trotter.log.strength.domain.model.Measure
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CardioHistoryWeekTest {
    private val day = 86_400_000L
    private val asOf = 9 * day + 3_600_000
    private fun session(days: Int, metres: Int?, seconds: Int, status: CardioSetStatus = CardioSetStatus.FINISHED): LoggedCardio {
        val end = metres?.let { Measure.Distance(it) } ?: Measure.Time(seconds)
        val spec = CardioSetSpec(CardioRole.WORK, end)
        return LoggedCardio(days * day, listOf(FinishedRule.atEnd(CardioSetResult(
            spec, status, seconds, metres, metres?.let { DistanceSource.MANUAL },
        ))))
    }
    private fun sessions() = listOf(session(0, 8000, 2880), session(2, 10_000, 3600),
        session(5, 12_000, 4320), session(9, 9000, 3240))

    @Test fun `week maxima sum finished sessions and exclude the exact seven day boundary`() {
        assertEquals(7 * day, CardioHistory.WEEK_MILLIS)
        assertEquals(RecentMaxima(30_000, 10_800), CardioHistory.longestWeek(sessions(), asOf))
        assertEquals(RecentMaxima(30_000, 10_800),
            CardioHistory.longestWeek(sessions() + session(3, 50_000, 18_000, CardioSetStatus.SHORTENED), asOf))
    }

    @Test fun `time only sessions add seconds while distance totals remain independent`() {
        assertEquals(RecentMaxima(30_000, 11_800),
            CardioHistory.longestWeek(sessions() + session(4, null, 1000), asOf))
        assertEquals(RecentMaxima(null, 1000), CardioHistory.longestWeek(listOf(session(4, null, 1000)), asOf))
    }

    @Test fun `no recent finished session gives no week`() {
        assertNull(CardioHistory.longestWeek(sessions(), 40 * day))
        assertNull(CardioHistory.longestWeek(emptyList(), asOf))
        assertNull(CardioHistory.longestWeek(listOf(session(3, 5000, 1800, CardioSetStatus.SHORTENED)), asOf))
        assertNull(CardioHistory.longestWeek(listOf(session(40, 5000, 1800)), asOf))
        assertNull(CardioHistory.longestWeek(listOf(session(0, 5000, 1800)), 30 * day))
        assertEquals(RecentMaxima(5000, 1800),
            CardioHistory.longestWeek(listOf(session(0, 5000, 1800).copy(completedAt = 1)), 30 * day))
    }
}
