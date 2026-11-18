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
import kotlin.test.assertTrue
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertNull

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
        credits: Int = 0,
        ladder: LadderContext? = null,
    ) = Progression.cardioAfterSession(opening, CardioRule(level, credits = credits), results, effort, tiny, ctx.copy(ladder = ladder), pain)

    @Test
    fun `case 34 ladder table and rule bounds`() {
        assertEquals(12, RunWalkLadder.LEVELS.size)
        assertEquals(12, RunWalkLadder.TOP)
        // ladder 2026-11-17: level 1 is 10 times 60 WORK and 90 RECOVERY, previously 8 times 60 and 120.
        assertEquals(block(*List(10) { listOf(time(60), time(90, CardioRole.RECOVERY)) }.flatten().toTypedArray()), ladder(1))
        // ladder 2026-11-17: level 7 is 4 times 270 and 90, previously 3 times 480 and 60.
        assertEquals(block(*List(4) { listOf(time(270), time(90, CardioRole.RECOVERY)) }.flatten().toTypedArray()), ladder(7))
        // ladder 2026-11-17: level 10 is 2 times 720 and 90, previously 1200 continuous.
        assertEquals(block(*List(2) { listOf(time(720), time(90, CardioRole.RECOVERY)) }.flatten().toTypedArray()), ladder(10))
        assertEquals(block(time(1800)), ladder(12))
        assertFailsWith<IllegalArgumentException> { CardioRule(0) }
        assertFailsWith<IllegalArgumentException> { CardioRule(13) }
        assertFailsWith<IllegalArgumentException> { ladder(0) }
        assertFailsWith<IllegalArgumentException> { ladder(13) }
    }

    @Test
    fun `case 34 twenty four Easy sessions climb two per level with no cap`() {
        var rule = CardioRule(1)
        var opening = ladder(1)
        for (level in 1..12) {
            assertSame(opening, Progression.cardioAtStart(opening, rule, tiny, null, ctx))
            val first = Progression.cardioAfterSession(opening, rule, finished(opening), CardioEffort.EASY, tiny, ctx)
            // ladder 2026-11-17: the first of two Easy finishes earns a credit and preserves the block.
            assertEquals(CardioOutcome(opening, CardioRule(level, credits = 1), CardioChange.Repeat), first)
            assertSame(opening, first.block)
            rule = assertNotNull(first.rule)
            assertSame(opening, Progression.cardioAtStart(opening, rule, tiny, null, ctx))
            val second = Progression.cardioAfterSession(opening, rule, finished(opening), CardioEffort.EASY, tiny, ctx)
            // ladder 2026-11-17: 2 times 12 = 24 finishes; only the second advances or clears all ladder state.
            if (level == 12) {
                assertEquals(CardioOutcome(opening, CardioRule(null), CardioChange.LadderDone), second)
                assertSame(opening, second.block)
            } else {
                assertEquals(CardioOutcome(ladder(level + 1), CardioRule(level + 1), CardioChange.LevelTo(level + 1)), second)
            }
            rule = assertNotNull(second.rule)
            opening = second.block
        }
    }

    @Test
    fun `case 34 not reported repeats the level`() {
        for (level in listOf(4, 12)) {
            val opening = ladder(level)
            val next = finish(level, effort = null, opening = opening)
            // audit 2026-11-13, row 3: no word gives 4 + 0 = 4 and 12 + 0 = 12, with no ladder completion.
            assertEquals(CardioOutcome(opening, CardioRule(level), CardioChange.Repeat), next)
            assertSame(opening, next.block)
            assertEquals(CardioOutcome(opening, CardioRule(level, credits = 1), CardioChange.Repeat),
                finish(level, effort = null, credits = 1, opening = opening))
        }
    }

    @Test
    fun `case 34 a never rated runner stays at the level`() {
        var opening = ladder(1)
        var rule = CardioRule(1)
        repeat(10) {
            val next = Progression.cardioAfterSession(opening, rule, finished(opening), null, tiny, ctx)
            assertEquals(CardioOutcome(opening, rule, CardioChange.Repeat), next)
            assertEquals(1, next.rule?.ladderLevel)
            assertSame(opening, next.block)
            opening = next.block
            rule = assertNotNull(next.rule)
        }
    }

    @Test
    fun `case 34 Good repeats however many times`() {
        val opening = ladder(5)
        repeat(3) {
            val next = finish(5, CardioEffort.GOOD, opening = opening)
            assertEquals(CardioOutcome(opening, CardioRule(5), CardioChange.Repeat), next)
            assertSame(opening, next.block)
            assertEquals(CardioOutcome(opening, CardioRule(5, credits = 1), CardioChange.Repeat),
                finish(5, CardioEffort.GOOD, credits = 1, opening = opening))
        }
    }

    @Test
    fun `case 34 Hard drops one with a floor at level one`() {
        assertEquals(CardioOutcome(ladder(4), CardioRule(4), CardioChange.LevelTo(4)), finish(5, CardioEffort.HARD))
        assertEquals(CardioOutcome(ladder(4), CardioRule(4), CardioChange.LevelTo(4)), finish(5, CardioEffort.HARD, credits = 1))
        assertEquals(CardioOutcome(ladder(1), CardioRule(1), CardioChange.Repeat), finish(1, CardioEffort.HARD, credits = 1))
        val opening = ladder(1)
        val next = finish(1, CardioEffort.HARD, opening = opening)
        assertEquals(CardioOutcome(opening, CardioRule(1), CardioChange.Repeat), next)
        assertSame(opening, next.block)
    }

    @Test
    fun `case 34 Pain drops one whatever the word`() {
        assertEquals(CardioOutcome(ladder(4), CardioRule(4), CardioChange.LevelTo(4)), finish(5, pain = true))
        assertEquals(CardioOutcome(ladder(4), CardioRule(4), CardioChange.LevelTo(4)), finish(5, pain = true, credits = 1))
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
        assertEquals(CardioOutcome(opening, CardioRule(5, credits = 1), CardioChange.Repeat),
            finish(5, credits = 1, opening = opening, results = results))
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
        // ladder 2026-11-17: the next level needs one stored credit before the Easy finish.
        assertEquals(RunWalkLadder.block(6, CardioActivity.TRAIL_RUN), finish(5, credits = 1, opening = trail).block)
        // audit 2026-11-13, row 3: finished Easy at 12 completes; no word now gives 12 + 0 = 12 above.
        // ladder 2026-11-17: completion also needs the first Easy credit.
        assertEquals(CardioChange.LadderDone, finish(12, credits = 1).change)
    }

    private val d = 20_000L
    private fun dated(rule: CardioRule, day: Long = d, busy: Boolean = false, effort: CardioEffort = CardioEffort.EASY): CardioOutcome {
        val opening = ladder(requireNotNull(rule.ladderLevel))
        return Progression.cardioAfterSession(opening, rule, finished(opening), effort, tiny,
            ctx.copy(ladder = LadderContext(day, busy)))
    }

    @Test fun `the cap counts three Easy finishes in seven days`() {
        val first = dated(CardioRule(1))
        assertEquals(CardioOutcome(ladder(1), CardioRule(1, credits = 1, ladderCounted = listOf(d)), CardioChange.Repeat), first)
        val second = dated(assertNotNull(first.rule), d + 1)
        assertEquals(CardioOutcome(ladder(2), CardioRule(2, ladderCounted = listOf(d, d + 1), ladderRaised = d + 1),
            CardioChange.LevelTo(2)), second)
        val third = dated(assertNotNull(second.rule), d + 2)
        val credited = CardioRule(2, credits = 1, ladderCounted = listOf(d, d + 1, d + 2), ladderRaised = d + 1)
        assertEquals(CardioOutcome(ladder(2), credited, CardioChange.Repeat), third)
        // PR 7b: the hold now names its reason
        assertEquals(CardioOutcome(ladder(2), credited, CardioChange.Repeat, hold = LadderHold.WEEK_CAP), dated(credited, d + 3))
        assertEquals(CardioOutcome(ladder(3), CardioRule(3, ladderCounted = listOf(d + 1, d + 2, d + 7), ladderRaised = d + 7),
            CardioChange.LevelTo(3)), dated(credited, d + 7))
    }

    @Test fun `a full cap keeps the credit`() {
        val rule = CardioRule(4, credits = 1, ladderCounted = listOf(d - 2, d - 1, d), ladderRaised = d - 1)
        // PR 7b: the hold now names its reason
        assertEquals(CardioOutcome(ladder(4), rule, CardioChange.Repeat, hold = LadderHold.WEEK_CAP), dated(rule))
    }

    @Test fun `the cap window is local days`() {
        val rule = CardioRule(3, ladderCounted = listOf(d - 7, d - 6, d - 5))
        assertEquals(CardioRule(3, credits = 1, ladderCounted = listOf(d - 6, d - 5, d)), dated(rule).rule)
        val full = CardioRule(3, ladderCounted = listOf(d - 6, d - 5, d - 4))
        // PR 7b: the hold now names its reason
        assertEquals(CardioOutcome(ladder(3), full, CardioChange.Repeat, hold = LadderHold.WEEK_CAP), dated(full))
    }

    @Test fun `the guard holds a second level-up within a week for a busy runner`() {
        val rule = CardioRule(5, credits = 1, ladderCounted = listOf(d - 3), ladderRaised = d - 3)
        // PR 7b: the hold now names its reason
        assertEquals(CardioOutcome(ladder(5), rule, CardioChange.Repeat, hold = LadderHold.BUSY), dated(rule, busy = true))
        assertEquals(CardioOutcome(ladder(6), CardioRule(6, ladderCounted = listOf(d - 3, d), ladderRaised = d),
            CardioChange.LevelTo(6)), dated(rule))
        val weekAgo = rule.copy(ladderCounted = listOf(d - 7), ladderRaised = d - 7)
        assertEquals(CardioOutcome(ladder(6), CardioRule(6, ladderCounted = listOf(d - 7, d), ladderRaised = d),
            CardioChange.LevelTo(6)), dated(weekAgo, busy = true))
    }

    @Test fun `the guard holds LadderDone`() {
        val rule = CardioRule(12, credits = 1, ladderCounted = listOf(d - 3), ladderRaised = d - 3)
        // PR 7b: the hold now names its reason
        assertEquals(CardioOutcome(ladder(12), rule, CardioChange.Repeat, hold = LadderHold.BUSY), dated(rule, busy = true))
        assertEquals(CardioOutcome(ladder(12), CardioRule(null), CardioChange.LadderDone), dated(rule, d + 4, busy = true))
    }

    @Test fun `a held level-up takes no cap slot`() {
        val rule = CardioRule(5, credits = 1, ladderCounted = listOf(d - 3), ladderRaised = d - 3)
        assertEquals(listOf(d - 3), dated(rule, busy = true).rule?.ladderCounted)
    }

    @Test fun `a backdated Easy is a Repeat`() {
        val rule = CardioRule(2, credits = 1, ladderCounted = listOf(10, 11, 12), ladderRaised = 11)
        // PR 7b: the hold now names its reason
        assertEquals(CardioOutcome(ladder(2), rule, CardioChange.Repeat, hold = LadderHold.BACKDATED), dated(rule, 9))
        assertEquals(CardioOutcome(ladder(1), CardioRule(1, ladderCounted = listOf(10, 11, 12), ladderRaised = 11),
            CardioChange.LevelTo(1)), dated(rule, 9, effort = CardioEffort.HARD))
    }

    @Test fun `a drop keeps the dates`() {
        val rule = CardioRule(5, credits = 1, ladderCounted = listOf(d - 2, d - 1), ladderRaised = d - 2)
        assertEquals(CardioOutcome(ladder(4), CardioRule(4, ladderCounted = listOf(d - 2, d - 1), ladderRaised = d - 2),
            CardioChange.LevelTo(4)), dated(rule, effort = CardioEffort.HARD))
    }

    @Test fun `LadderDone clears the ladder state`() {
        val rule = CardioRule(12, credits = 1, ladderCounted = listOf(d - 9), ladderRaised = d - 9)
        assertEquals(CardioRule(null), dated(rule).rule)
    }

    @Test fun `ladder rule bounds`() {
        assertEquals(1, CardioRule(3, credits = 1).credits)
        for (invalid in listOf<() -> CardioRule>(
            { CardioRule(3, ladderCounted = listOf(1, 2, 3, 4)) },
            { CardioRule(3, ladderCounted = listOf(2, 1)) },
            { CardioRule(null, ladderCounted = listOf(1)) },
            { CardioRule(null, ladderRaised = 1) },
            { CardioRule(3, credits = 2) },
            { CardioRule(null, credits = 1) },
        )) assertFailsWith<IllegalArgumentException> { invalid() }
    }

    @Test fun `busy counts any seven days of the last fourteen`() {
        assertTrue(RunWalkLadder.busy(listOf(d - 13, d - 12, d - 11, d - 10, d), d))
        assertTrue(RunWalkLadder.busy(listOf(d - 13, d - 11, d - 9, d - 7, d - 5, d - 3, d), d))
        assertFalse(RunWalkLadder.busy(listOf(d - 11, d - 9, d - 7, d - 4, d - 2, d), d))
        assertFalse(RunWalkLadder.busy(listOf(d - 12, d - 9, d - 6, d - 3, d), d))
        assertTrue(RunWalkLadder.busy(listOf(d - 6, d - 4, d - 2, d), d))
    }

    @Test fun `a held ladder Easy names why it did not count`() {
        assertEquals(LadderHold.BACKDATED, dated(CardioRule(2, ladderCounted = listOf(d + 1))).hold)
        assertEquals(LadderHold.WEEK_CAP, dated(CardioRule(2, ladderCounted = listOf(d - 2, d - 1, d))).hold)
        assertEquals(LadderHold.BUSY, dated(CardioRule(2, credits = 1, ladderRaised = d - 1), busy = true).hold)
        assertNull(dated(CardioRule(2)).hold)
        assertNull(finish(2, effort = CardioEffort.GOOD).hold)
        assertNull(finish(2, results = emptyList()).hold)
    }
}
