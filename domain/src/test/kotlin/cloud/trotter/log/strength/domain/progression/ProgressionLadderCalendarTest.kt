package cloud.trotter.log.strength.domain.progression

import cloud.trotter.log.strength.domain.model.CardioActivity
import cloud.trotter.log.strength.domain.model.CardioBlock
import cloud.trotter.log.strength.domain.model.CardioRole
import cloud.trotter.log.strength.domain.model.CardioSetResult
import cloud.trotter.log.strength.domain.model.CardioSetStatus
import cloud.trotter.log.strength.domain.model.Measure
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ProgressionLadderCalendarTest {
    private data class Run(val day: Long, val seconds: Int)
    private data class Climb(val runs: List<Run>, val doneSession: Int, val doneDay: Long)
    private fun results(block: CardioBlock) = block.sets.map {
        CardioSetResult(it, CardioSetStatus.FINISHED, (it.end as Measure.Time).seconds)
    }
    private fun work(block: CardioBlock) = block.sets.filter { it.role == CardioRole.WORK }
        .sumOf { (it.end as Measure.Time).seconds }

    private fun climb(sessions: List<Pair<Long, CardioEffort>>): Climb {
        var rule = CardioRule(1)
        var block = RunWalkLadder.block(1, CardioActivity.ROAD_RUN)
        val runs = mutableListOf<Run>()
        var doneSession: Int? = null
        var doneDay: Long? = null
        for ((day, effort) in sessions) {
            runs += Run(day, if (doneSession == null) work(block) else 1800)
            if (doneSession == null) {
                val ctx = Progression.CardioContext(day, null, 0,
                    ladder = LadderContext(day, RunWalkLadder.busy(runs.map { it.day }, day)))
                val next = Progression.cardioAfterSession(block, rule, results(block), effort, CardioHistory(), ctx)
                rule = assertNotNull(next.rule)
                block = next.block
                if (next.change == CardioChange.LadderDone) {
                    doneSession = runs.size
                    doneDay = day
                }
            } else if (runs.size == doneSession + 2) break
        }
        return Climb(runs, assertNotNull(doneSession), assertNotNull(doneDay))
    }

    /** Equal-count adjacent weeks, including zero-padded boundaries outside the observed sessions. */
    private fun worst(runs: List<Run>): Pair<Double, Long> {
        var worst = 0.0
        var ending = runs.first().day
        for (t in runs.first().day..runs.last().day + 13) {
            val before = runs.filter { it.day in t - 13..t - 7 }
            val after = runs.filter { it.day in t - 6..t }
            if (before.isEmpty() || before.size != after.size) continue
            val rise = 100.0 * (after.sumOf { it.seconds }.toDouble() / before.sumOf { it.seconds } - 1)
            if (rise > worst) {
                worst = rise
                ending = t
            }
        }
        return worst to ending
    }

    private fun check(sessions: List<Pair<Long, CardioEffort>>, percent: Double, ending: Long, doneSession: Int, doneDay: Long) {
        val climb = climb(sessions)
        val (rise, day) = worst(climb.runs)
        assertTrue(rise <= 20.0)
        assertEquals(percent, rise, 0.0001)
        assertEquals(ending, day)
        assertEquals(doneSession, climb.doneSession)
        assertEquals(doneDay, climb.doneDay)
        assertEquals(doneSession + 2, climb.runs.size)
    }

    @Test fun `daily running with one Hard rises at most a fifth`() {
        check((6L..205L).mapIndexed { index, day -> day to if (index == 10) CardioEffort.HARD else CardioEffort.EASY },
            12.5, 70, 86, 91)
    }

    @Test fun `three runs a calendar week on drifting days rise at most a fifth`() {
        val days = listOf(4L, 5, 6, 9, 10, 12, 14, 18, 20, 22, 24, 27, 29, 30, 31) +
            (5L..39L).flatMap { week -> listOf(0L, 2L, 4L).map { week * 7 + it } }
        check(days.map { it to CardioEffort.EASY }, 19.1489, 67, 31, 70)
    }

    @Test fun `four runs a week on fixed weekdays rise at most a fifth`() {
        val days = (0L until 80L).flatMap { week -> (0L..3L).map { week * 7 + it } }
        check(days.map { it to CardioEffort.EASY }, 12.5, 57, 46, 78)
    }

    @Test fun `three fixed weekdays meet the proven bound`() {
        val days = (0L until 80L).flatMap { week -> listOf(0L, 2L, 4L).map { week * 7 + it } }
        check(days.map { it to CardioEffort.EASY }, 19.1489, 51, 24, 53)
    }

    @Test fun `a composition table`() {
        var rule = CardioRule(9, credits = 1)
        var block = RunWalkLadder.block(9, CardioActivity.ROAD_RUN)
        val seconds = buildList<Int> {
            for (effort in List(5) { CardioEffort.EASY } + CardioEffort.GOOD) {
                add(work(block))
                val next = Progression.cardioAfterSession(block, rule, results(block), effort, CardioHistory(),
                    Progression.CardioContext(0, null, 0))
                rule = assertNotNull(next.rule)
                block = next.block
            }
        }
        assertEquals(listOf(1350, 1440, 1440, 1620, 1620, 1800), seconds)
        val rise = 100.0 * (seconds.takeLast(3).sum().toDouble() / seconds.take(3).sum() - 1)
        assertTrue(rise <= 20.0)
        assertEquals(19.1489, rise, 0.0001)
    }
}
