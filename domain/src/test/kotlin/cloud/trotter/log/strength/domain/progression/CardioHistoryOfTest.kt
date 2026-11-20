package cloud.trotter.log.strength.domain.progression

import cloud.trotter.log.strength.domain.model.CardioActivity
import cloud.trotter.log.strength.domain.model.CardioFamily
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
    @Test fun `longest running metres counts finished WORK distance only`() {
        val work = result(time(900), 900, 2500)
        val recovery = result(CardioSetSpec(CardioRole.RECOVERY, Measure.Time(2700)), 2700, 3000)
        val finished = LoggedCardio(asOf - day, listOf(work, recovery))
        assertEquals(2500, CardioHistory.longestRunningMetres(listOf(finished), asOf))
        val skipped = listOf(
            finished.copy(results = listOf(work, result(time(100), 100))),
            finished.copy(results = listOf(work.copy(status = CardioSetStatus.SHORTENED, secondsAtEnd = null), recovery)),
            finished.copy(completedAt = asOf - 31 * day),
        )
        assertNull(CardioHistory.longestRunningMetres(skipped, asOf))
        assertEquals(2500, CardioHistory.longestRunningMetres(skipped + finished, asOf))
        assertEquals(4000, CardioHistory.longestRunningMetres(
            listOf(LoggedCardio(asOf - day, listOf(result(distance(4000), 1200)))), asOf))
    }

    @Test fun `known fills every run anchor`() {
        val work = result(time(900), 900, 2500)
        val recovery = result(CardioSetSpec(CardioRole.RECOVERY, Measure.Time(2700)), 2700, 3000)
        val sessions = listOf(LoggedCardio(asOf - 2 * day, listOf(work, recovery)),
            LoggedCardio(asOf - day, listOf(result(distance(3000), 1000, 3000))))
        val known = CardioHistory.known(CardioFamily.RUN, sessions, asOf)
        assertEquals(mapOf(CardioFamily.RUN to RecentMaxima(5500, 3600)), known.longestRecent)
        assertEquals(mapOf(CardioFamily.RUN to RecentMaxima(8500, 4600)), known.longestWeek)
        assertEquals(mapOf(CardioFamily.RUN to 1000), known.longestRunning)
        assertEquals(mapOf(CardioFamily.RUN to 3000), known.longestRunningMetres)
        assertEquals(KnownLoads.NONE, CardioHistory.known(CardioFamily.RUN, emptyList(), asOf))
    }

    @Test fun `the longest bout is one work set`() {
        val results = RunWalkLadder.block(8, CardioActivity.ROAD_RUN).sets.map {
            CardioSetResult(it, CardioSetStatus.FINISHED, (it.end as Measure.Time).seconds)
        }
        val log = listOf(LoggedCardio(asOf - day, results))
        assertEquals(RecentMaxima(null, 300), CardioHistory.longestBout(log, asOf))
        assertEquals(1200, CardioHistory.longestRunning(log, asOf))
    }

    @Test fun `a single run is one bout with its at end reading`() {
        val row = result(time(1500), 1530, 4100).copy(metresAtEnd = 4000)
        assertEquals(1500, row.secondsAtEnd)
        assertEquals(RecentMaxima(4000, 1500), CardioHistory.longestBout(
            listOf(LoggedCardio(asOf - day, listOf(row))), asOf))
    }

    @Test fun `bout seconds and meters are each their own maximum`() {
        val sessions = listOf(
            LoggedCardio(asOf - 2 * day, listOf(result(time(1300), 1300))),
            LoggedCardio(asOf - day, listOf(result(distance(3300), 900))),
        )
        assertEquals(RecentMaxima(3300, 1300), CardioHistory.longestBout(sessions, asOf))
    }

    @Test fun `back to back work sets are separate bouts`() {
        val session = LoggedCardio(asOf - day, List(2) { result(time(600), 600) })
        assertEquals(RecentMaxima(null, 600), CardioHistory.longestBout(listOf(session), asOf))
    }

    @Test fun `unfinished and old sessions give no bout`() {
        val unfinished = LoggedCardio(asOf - day, listOf(result(time(600), 500, status = CardioSetStatus.SHORTENED)))
        val old = LoggedCardio(asOf - 30 * day, listOf(result(time(600), 600)))
        assertNull(CardioHistory.longestBout(listOf(unfinished), asOf))
        assertNull(CardioHistory.longestBout(listOf(old), asOf))
        assertNull(CardioHistory.longestBout(listOf(unfinished, old), asOf))
    }

    @Test fun `known carries the longest bout`() {
        val results = RunWalkLadder.block(10, CardioActivity.ROAD_RUN).sets.map {
            CardioSetResult(it, CardioSetStatus.FINISHED, (it.end as Measure.Time).seconds)
        }
        val known = CardioHistory.known(CardioFamily.RUN, listOf(LoggedCardio(asOf - day, results)), asOf)
        assertEquals(mapOf(CardioFamily.RUN to RecentMaxima(null, 720)), known.longestBout)
    }

}
