package cloud.trotter.log.strength.domain.progression

import cloud.trotter.log.strength.domain.model.CardioActivity
import cloud.trotter.log.strength.domain.model.CardioBlock
import cloud.trotter.log.strength.domain.model.CardioRole
import cloud.trotter.log.strength.domain.model.CardioSetResult
import cloud.trotter.log.strength.domain.model.CardioSetSpec
import cloud.trotter.log.strength.domain.model.CardioSetStatus
import cloud.trotter.log.strength.domain.model.DistanceSource
import cloud.trotter.log.strength.domain.model.Measure
import cloud.trotter.log.strength.domain.model.ProgressMeasure
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame

class ProgressionLadderTest {
    private fun time(seconds: Int, role: CardioRole = CardioRole.WORK) = CardioSetSpec(role, Measure.Time(seconds))
    private fun distance(metres: Int) = CardioSetSpec(CardioRole.WORK, Measure.Distance(metres))
    private fun block(vararg sets: CardioSetSpec) = CardioBlock(CardioActivity.ROAD_RUN, sets.toList())
    private fun ladder(level: Int) = RunWalkLadder.block(level, CardioActivity.ROAD_RUN)
    private fun finished(block: CardioBlock) = block.sets.map {
        CardioSetResult(it, CardioSetStatus.FINISHED, (it.end as Measure.Time).seconds)
    }
    private val tiny = CardioHistory(longest = RecentMaxima(100, 60))
    private val ctx = Progression.CardioContext(1000, ProgressMeasure.TIME, 5)
    private fun finish(
        level: Int,
        effort: CardioEffort? = CardioEffort.EASY,
        pain: Boolean = false,
        opening: CardioBlock = ladder(level),
        results: List<CardioSetResult> = finished(opening),
    ) = Progression.cardioAfterSession(opening, CardioRule(level), results, effort, tiny, ctx, pain)

    @Test
    fun `case 34 ladder table and rule bounds`() {
        assertEquals(12, RunWalkLadder.LEVELS.size)
        assertEquals(12, RunWalkLadder.TOP)
        assertEquals(block(*List(8) { listOf(time(60), time(120, CardioRole.RECOVERY)) }.flatten().toTypedArray()), ladder(1))
        assertEquals(block(*List(3) { listOf(time(480), time(60, CardioRole.RECOVERY)) }.flatten().toTypedArray()), ladder(7))
        assertEquals(block(time(1200)), ladder(10))
        assertEquals(block(time(1800)), ladder(12))
        assertFailsWith<IllegalArgumentException> { CardioRule(0) }
        assertFailsWith<IllegalArgumentException> { CardioRule(13) }
        assertFailsWith<IllegalArgumentException> { ladder(0) }
        assertFailsWith<IllegalArgumentException> { ladder(13) }
    }

    @Test
    fun `case 34 twelve Easy sessions climb the ladder with no cap`() {
        var rule = CardioRule(1)
        var opening = ladder(1)
        for (level in 1..11) {
            assertSame(opening, Progression.cardioAtStart(opening, rule, tiny, null, ctx))
            val next = Progression.cardioAfterSession(opening, rule, finished(opening), CardioEffort.EASY, tiny, ctx)
            assertEquals(CardioChange.LevelTo(level + 1), next.change)
            assertEquals(level + 1, next.rule?.ladderLevel)
            assertEquals(ladder(level + 1), next.block)
            rule = assertNotNull(next.rule)
            opening = next.block
        }
        assertSame(opening, Progression.cardioAtStart(opening, rule, tiny, null, ctx))
        val last = Progression.cardioAfterSession(opening, rule, finished(opening), CardioEffort.EASY, tiny, ctx)
        assertEquals(CardioChange.LadderDone, last.change)
        assertNull(assertNotNull(last.rule).ladderLevel)
        assertSame(opening, last.block)
    }

    @Test
    fun `case 34 not reported reads as Easy`() {
        assertEquals(CardioOutcome(ladder(5), CardioRule(5), CardioChange.LevelTo(5)), finish(4, effort = null))
    }

    @Test
    fun `case 34 Good repeats however many times`() {
        val opening = ladder(5)
        repeat(3) {
            val next = finish(5, CardioEffort.GOOD, opening = opening)
            assertEquals(CardioOutcome(opening, CardioRule(5), CardioChange.Repeat), next)
            assertSame(opening, next.block)
        }
    }

    @Test
    fun `case 34 Hard drops one with a floor at level one`() {
        assertEquals(CardioOutcome(ladder(4), CardioRule(4), CardioChange.LevelTo(4)), finish(5, CardioEffort.HARD))
        val opening = ladder(1)
        val next = finish(1, CardioEffort.HARD, opening = opening)
        assertEquals(CardioOutcome(opening, CardioRule(1), CardioChange.Repeat), next)
        assertSame(opening, next.block)
    }

    @Test
    fun `case 34 Pain drops one whatever the word`() {
        assertEquals(CardioOutcome(ladder(4), CardioRule(4), CardioChange.LevelTo(4)), finish(5, pain = true))
    }

    @Test
    fun `case 34 unfinished repeats unless Hard drops it`() {
        val opening = ladder(5)
        val results = finished(opening).mapIndexed { index, row ->
            if (index == 0) row.copy(status = CardioSetStatus.SHORTENED) else row
        }
        val repeated = finish(5, opening = opening, results = results)
        assertEquals(CardioOutcome(opening, CardioRule(5), CardioChange.Repeat), repeated)
        assertSame(opening, repeated.block)
        assertEquals(CardioOutcome(ladder(4), CardioRule(4), CardioChange.LevelTo(4)),
            finish(5, CardioEffort.HARD, opening = opening, results = results))
    }

    @Test
    fun `case 34 level 12 run anchors the cap`() {
        val row = CardioSetResult(time(1800), CardioSetStatus.FINISHED, 1800, 5000, DistanceSource.MANUAL, 5000, 1800)
        assertEquals(RecentMaxima(5000, 1800), RunCap.maxima(listOf(listOf(row))))
        assertEquals(5500, RunCap.limit(5000, 100))
        val recent = CardioHistory(longest = RecentMaxima(5000, 1800))
        val rule = CardioRule(null)
        val repeatDistance = block(distance(5000))
        assertSame(repeatDistance, Progression.cardioAtStart(repeatDistance, rule, recent, null, ctx))
        assertEquals(block(distance(5500)), Progression.cardioAtStart(block(distance(6000)), rule, recent, null, ctx))
        val repeatTime = block(time(1800))
        assertSame(repeatTime, Progression.cardioAtStart(repeatTime, rule, recent, null, ctx))
        assertEquals(1980, RunCap.limit(1800, 60))
    }

    @Test
    fun `case 34 after the ladder A16 takes over`() {
        val opening = block(time(1800))
        val row = CardioSetResult(opening.sets.single(), CardioSetStatus.FINISHED, 1800, 5000, DistanceSource.MANUAL, 5000, 1800)
        assertEquals(CardioOutcome(block(time(1890)), CardioRule(null), CardioChange.Increase(ProgressMeasure.TIME, false)),
            Progression.cardioAfterSession(opening, CardioRule(null), listOf(row), CardioEffort.EASY, CardioHistory(), ctx))
    }

    @Test
    fun `case 34 repeats preserve rounds editor changes and activity on a level change`() {
        val edited = ladder(5).let { it.copy(sets = it.sets.dropLast(2)) }
        assertSame(edited, finish(5, CardioEffort.GOOD, opening = edited).block)
        val trail = ladder(5).copy(activity = CardioActivity.TRAIL_RUN)
        assertEquals(RunWalkLadder.block(6, CardioActivity.TRAIL_RUN), finish(5, opening = trail).block)
        assertEquals(CardioChange.LadderDone, finish(12, effort = null).change)
    }
}
