package cloud.trotter.log.strength.domain.progression

import cloud.trotter.log.strength.domain.model.CardioActivity
import cloud.trotter.log.strength.domain.model.CardioBlock
import cloud.trotter.log.strength.domain.model.CardioRole
import cloud.trotter.log.strength.domain.model.CardioSetResult
import cloud.trotter.log.strength.domain.model.CardioSetSpec
import cloud.trotter.log.strength.domain.model.CardioSetStatus
import cloud.trotter.log.strength.domain.model.DistanceSource
import cloud.trotter.log.strength.domain.model.Measure
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame

class RunCapTest {
    private fun time(seconds: Int, aim: Int? = null, role: CardioRole = CardioRole.WORK) =
        CardioSetSpec(role, Measure.Time(seconds), aim?.let { Measure.Distance(it) })
    private fun distance(metres: Int, aim: Int? = null, role: CardioRole = CardioRole.WORK) =
        CardioSetSpec(role, Measure.Distance(metres), aim?.let { Measure.Time(it) })
    private fun block(vararg sets: CardioSetSpec) = CardioBlock(CardioActivity.ROAD_RUN, sets.toList())
    private fun result(
        spec: CardioSetSpec,
        status: CardioSetStatus = CardioSetStatus.FINISHED,
        elapsed: Int = 0,
        metres: Int? = null,
        metresAtEnd: Int? = null,
        secondsAtEnd: Int? = null,
    ) = CardioSetResult(spec, status, elapsed, metres, metres?.let { DistanceSource.MANUAL }, metresAtEnd, secondsAtEnd)

    private fun intervals(work: CardioSetSpec, recovery: CardioSetSpec, rounds: Int = 10) =
        CardioBlock(CardioActivity.ROAD_RUN, buildList {
            add(time(600, role = CardioRole.WARMUP))
            repeat(rounds) { index ->
                if (index > 0) add(recovery)
                add(work)
            }
        })

    @Test
    fun `case 17 limit`() {
        for ((maximum, grid, expected) in listOf(
            Triple(8050, 100, 8800), Triple(5000, 100, 5500), Triple(2640, 60, 2880), Triple(1800, 60, 1980),
            Triple(360, 60, 396), Triple(400, 100, 440), Triple(50, 100, 55),
        )) assertEquals(expected, RunCap.limit(maximum, grid))
    }

    @Test
    fun `case 17 long run`() {
        val anchor = RecentMaxima(8050, 2900)
        assertEquals(block(distance(8800)), RunCap.capped(block(distance(10000)), anchor, null))
        val opening = block(time(600, role = CardioRole.WARMUP), distance(10000), time(300, role = CardioRole.COOLDOWN))
        val capped = RunCap.capped(opening, anchor, null)
        assertEquals(opening.copy(sets = listOf(opening.sets[0], distance(8800), opening.sets[2])), capped)
        assertSame(opening.sets[0], capped.sets[0])
        assertSame(opening.sets[2], capped.sets[2])
    }

    @Test
    fun `case 17 under the limit`() {
        val opening = block(distance(7875))
        assertSame(opening, RunCap.capped(opening, RecentMaxima(8050, 2900), null))
    }

    @Test
    fun `case 17 intervals drop rounds`() {
        val opening = intervals(distance(1000).copy(hard = true), distance(100, role = CardioRole.RECOVERY))
        val capped = RunCap.capped(opening, RecentMaxima(8050, 2900), null)
        assertEquals(opening.copy(sets = opening.sets.take(16)), capped)
        assertEquals(8, capped.sets.count { it.role == CardioRole.WORK })
        assertEquals(7, capped.sets.count { it.role == CardioRole.RECOVERY })
        capped.sets.forEachIndexed { index, set -> assertSame(opening.sets[index], set) }
    }

    @Test
    fun `seconds comparison includes recovery and fits an inclusive limit`() {
        val opening = intervals(distance(1000, 240).copy(hard = true), time(90, role = CardioRole.RECOVERY))
        val capped = RunCap.capped(opening, RecentMaxima(8050, 2640), null)
        assertEquals(opening.copy(sets = opening.sets.take(18)), capped)
        assertEquals(9, capped.sets.count { it.role == CardioRole.WORK })
        assertEquals(8, capped.sets.count { it.role == CardioRole.RECOVERY })
    }

    @Test
    fun `distance converts at the family pace`() {
        assertEquals(block(distance(8100)), RunCap.capped(block(distance(10000)), RecentMaxima(null, 2700), 0.36))
    }

    @Test
    fun `time end with a distance aim holds its pace`() {
        assertEquals(block(time(2880, 8000)), RunCap.capped(block(time(3600, 10000)), RecentMaxima(8050, 2640), null))
    }

    @Test
    fun `no comparable unit or no anchor leaves the block uncapped`() {
        val opening = block(distance(10000))
        assertSame(opening, RunCap.capped(opening, RecentMaxima(null, 2700), null))
        val mixed = block(distance(1000), time(90, role = CardioRole.RECOVERY), distance(1000))
        assertSame(mixed, RunCap.capped(mixed, RecentMaxima(8050, 2640), null))
        assertSame(opening, RunCap.capped(opening, null, 0.36))
    }

    @Test
    fun `one rep left is never shortened`() {
        assertEquals(block(distance(1000)), RunCap.capped(block(distance(1000), distance(1000)), RecentMaxima(500, null), null))
    }

    @Test
    fun `case 17 maxima are independent`() {
        assertEquals(RecentMaxima(8050, 3000), RunCap.maxima(listOf(
            listOf(result(distance(8050), elapsed = 2900)),
            listOf(result(time(3000), elapsed = 3000, metres = 7000)),
            listOf(result(time(4000), CardioSetStatus.SHORTENED, elapsed = 3500, metres = 9000)),
        )))
        assertEquals(RecentMaxima(5000, 1500), RunCap.maxima(listOf(listOf(
            result(time(600, role = CardioRole.WARMUP), elapsed = 600), result(distance(5000), elapsed = 1500),
        ))))
        assertEquals(RecentMaxima(null, 568), RunCap.maxima(listOf(listOf(
            result(distance(1000), elapsed = 240), result(time(90, role = CardioRole.RECOVERY), elapsed = 90),
            result(distance(1000), elapsed = 238),
        ))))
        val overrun = result(time(900), elapsed = 904, metres = 3485, metresAtEnd = 3470, secondsAtEnd = 900)
        assertEquals(RecentMaxima(3470, 900), RunCap.maxima(listOf(listOf(overrun))))
        assertNull(RunCap.maxima(listOf(listOf(result(time(900), CardioSetStatus.SHORTENED)))))
    }

    @Test
    fun `case 17 setup answer seeds the limit until history exists`() {
        val seed = RecentMaxima(8050, null)
        val history = CardioHistory(seed = seed, planCreatedEpochDay = 100)
        assertSame(seed, RunCap.anchor(history, 110))
        assertNull(RunCap.anchor(history, 130))
        val longest = RecentMaxima(6000, 2100)
        assertSame(longest, RunCap.anchor(history.copy(longest = longest), 110))
        assertNull(RunCap.anchor(history.copy(planCreatedEpochDay = null), 110))
    }

    @Test
    fun `round removal preserves trailing recovery warmup and cooldown`() {
        val opening = block(time(600, role = CardioRole.WARMUP), distance(1000),
            time(90, role = CardioRole.RECOVERY), time(30, role = CardioRole.RECOVERY),
            time(60, role = CardioRole.COOLDOWN), distance(1000),
            time(90, role = CardioRole.RECOVERY), time(300, role = CardioRole.COOLDOWN))
        val expected = opening.copy(sets = listOf(0, 1, 4, 6, 7).map { opening.sets[it] })
        val capped = RunCap.capped(opening, RecentMaxima(null, 400), 0.24)
        assertEquals(expected, capped)
        expected.sets.forEachIndexed { index, kept -> assertSame(kept, capped.sets[index]) }
    }

    @Test
    fun `no work and a single work without a positive budget stay unchanged`() {
        val noWork = block(time(600, role = CardioRole.RECOVERY))
        assertSame(noWork, RunCap.capped(noWork, RecentMaxima(null, 60), null))
        val noBudget = block(time(120), time(600, role = CardioRole.RECOVERY))
        assertSame(noBudget, RunCap.capped(noBudget, RecentMaxima(null, 60), null))
        val belowGrid = block(distance(100))
        assertEquals(block(distance(55)), RunCap.capped(belowGrid, RecentMaxima(50, null), null))
    }

    @Test
    fun `maxima require finished work and use available readings on recovery`() {
        assertNull(RunCap.maxima(emptyList()))
        assertNull(RunCap.maxima(listOf(emptyList(), listOf(result(time(600, role = CardioRole.WARMUP))))))
        val short = result(distance(1000), CardioSetStatus.SHORTENED, metresAtEnd = 1000, secondsAtEnd = 240)
        assertNull(RunCap.maxima(listOf(listOf(short))))
        val work = result(distance(1000), elapsed = 240)
        val recovery = result(distance(100, role = CardioRole.RECOVERY), CardioSetStatus.SHORTENED, elapsed = 90)
        assertEquals(RecentMaxima(null, 330), RunCap.maxima(listOf(listOf(work, recovery))))
        assertEquals(RecentMaxima(1080, 300), RunCap.maxima(listOf(listOf(work,
            recovery.copy(metres = 85, distanceSource = DistanceSource.MANUAL, metresAtEnd = 80, secondsAtEnd = 60)))))
    }

    @Test
    fun `recent maxima merge each nullable unit and reject two missing units`() {
        assertEquals(RecentMaxima(8050, 3000), RecentMaxima(8050, 2900).max(RecentMaxima(7000, 3000)))
        assertEquals(RecentMaxima(5000, 1800), RecentMaxima(5000, null).max(RecentMaxima(null, 1800)))
        assertEquals(RecentMaxima(5000, null), RecentMaxima(4000, null).max(RecentMaxima(5000, null)))
        assertEquals(RecentMaxima(null, 1800), RecentMaxima(null, 1800).max(RecentMaxima(null, 900)))
        assertFailsWith<IllegalArgumentException> { RecentMaxima(null, null) }
    }
}
