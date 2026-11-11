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

class CardioHistoryOfTest {
    private val day = 86_400_000L
    private val asOf = 100 * day
    private fun time(seconds: Int) = CardioSetSpec(CardioRole.WORK, Measure.Time(seconds))
    private fun distance(metres: Int) = CardioSetSpec(CardioRole.WORK, Measure.Distance(metres))
    private fun result(spec: CardioSetSpec, elapsed: Int, metres: Int? = null,
                       status: CardioSetStatus = CardioSetStatus.FINISHED) = FinishedRule.atEnd(
        CardioSetResult(spec, status, elapsed, metres, metres?.let { DistanceSource.MANUAL }),
    )

    @Test fun `longest reads the last 30 days and pace the newest session with a distance`() {
        val sessions = listOf(
            LoggedCardio(asOf - 40 * day, listOf(result(distance(9000), 3000, 9000))),
            LoggedCardio(asOf - 29 * day, listOf(result(distance(6000), 1800, 6000))),
            LoggedCardio(asOf - 2 * day, listOf(result(time(1500), 1500))),
        )
        val history = CardioHistory.of(sessions, asOf)
        assertEquals(RecentMaxima(6000, 1800), history.longest)
        assertEquals(1800.0 / 6000, history.secondsPerMetre)
    }

    @Test fun `a session exactly 30 days back is outside the window`() {
        val session = LoggedCardio(asOf - 30 * day, listOf(result(distance(5000), 1500, 5000)))
        val history = CardioHistory.of(listOf(session), asOf)
        assertNull(history.longest)
        assertEquals(0.3, history.secondsPerMetre)
        assertEquals(RecentMaxima(5000, 1500),
            CardioHistory.of(listOf(session.copy(completedAt = session.completedAt + 1)), asOf).longest)
    }

    @Test fun `an overrun without distance at the end uses totals for pace`() {
        val row = result(time(1200), 1300, 5000)
        assertEquals(1200, row.secondsAtEnd)
        assertNull(row.metresAtEnd)
        val history = CardioHistory.of(listOf(LoggedCardio(asOf - day, listOf(row))), asOf)
        // The cap keeps the proved 1200 s; pace pairs 5000 total metres with 1300 total seconds.
        assertEquals(RecentMaxima(5000, 1200), history.longest)
        assertEquals(1300.0 / 5000, history.secondsPerMetre)
        assertEquals(0.26, history.secondsPerMetre)
    }

    @Test fun `pace keeps paired readings at the end when the totals overrun`() {
        val row = result(time(1200), 1300, 5000).copy(metresAtEnd = 4500)
        val history = CardioHistory.of(listOf(LoggedCardio(asOf - day, listOf(row))), asOf)
        assertEquals(RecentMaxima(4500, 1200), history.longest)
        assertEquals(1200.0 / 4500, history.secondsPerMetre)
    }

    @Test fun `no finished session gives no history`() {
        val session = LoggedCardio(asOf - day,
            listOf(result(distance(5000), 1200, 4000, CardioSetStatus.SHORTENED)))
        assertEquals(CardioHistory(), CardioHistory.of(listOf(session), asOf))
        assertEquals(CardioHistory(), CardioHistory.of(emptyList(), asOf))
    }
}
