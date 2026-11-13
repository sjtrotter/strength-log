package cloud.trotter.log.strength.domain.records

import cloud.trotter.log.strength.domain.model.CardioRole
import cloud.trotter.log.strength.domain.model.CardioSetResult
import cloud.trotter.log.strength.domain.model.CardioSetSpec
import cloud.trotter.log.strength.domain.model.CardioSetStatus
import cloud.trotter.log.strength.domain.model.Measure
import cloud.trotter.log.strength.domain.model.PlanGoal
import cloud.trotter.log.strength.domain.model.SprintAttempt
import cloud.trotter.log.strength.domain.model.SprintTime
import cloud.trotter.log.strength.domain.model.StartProtocol
import cloud.trotter.log.strength.domain.model.TimingMethod
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class SprintComparisonTest {
    private fun rep(
        metres: Int,
        centis: Int,
        method: TimingMethod,
        start: StartProtocol = StartProtocol.THREE_POINT,
        status: CardioSetStatus = CardioSetStatus.FINISHED,
    ) = CardioSetResult(CardioSetSpec(CardioRole.WORK, Measure.Distance(metres), hard = true),
        status, (centis + 99) / 100, timing = SprintTime(centis, method, start))

    @Test fun `case 37 splits series by distance method and start protocol`() {
        val phone = rep(30, 452, TimingMethod.PHONE)
        val bests = SprintComparison.bests(listOf(phone, rep(30, 430, TimingMethod.GATES),
            rep(30, 440, TimingMethod.HAND), rep(40, 560, TimingMethod.PHONE),
            rep(30, 452, TimingMethod.PHONE, StartProtocol.STANDING)))

        assertEquals(5, bests.size)
        assertSame(phone, bests[SprintSeries(30, TimingMethod.PHONE, StartProtocol.THREE_POINT)])
    }

    @Test fun `only hand verdicts have a noise threshold and other setups reject`() {
        fun time(centis: Int, method: TimingMethod) = SprintTime(centis, method, StartProtocol.THREE_POINT)
        for ((method, centis, expected) in listOf(
            Triple(TimingMethod.PHONE, 445, SprintVerdict.FASTER),
            Triple(TimingMethod.HAND, 445, SprintVerdict.SAME),
            Triple(TimingMethod.HAND, 443, SprintVerdict.SAME),
            Triple(TimingMethod.HAND, 442, SprintVerdict.FASTER),
            Triple(TimingMethod.HAND, 461, SprintVerdict.SAME),
            Triple(TimingMethod.HAND, 462, SprintVerdict.SLOWER),
            Triple(TimingMethod.GATES, 451, SprintVerdict.FASTER),
            Triple(TimingMethod.VIDEO, 453, SprintVerdict.SLOWER),
        )) {
            assertEquals(expected, SprintComparison.verdict(time(centis, method), time(452, method)))
        }
        for (method in TimingMethod.entries) {
            assertEquals(SprintVerdict.SAME, SprintComparison.verdict(time(452, method), time(452, method)))
        }
        assertFailsWith<IllegalArgumentException> {
            SprintComparison.verdict(time(445, TimingMethod.PHONE), time(452, TimingMethod.HAND))
        }
        assertFailsWith<IllegalArgumentException> {
            SprintComparison.verdict(time(445, TimingMethod.PHONE),
                SprintTime(452, TimingMethod.PHONE, StartProtocol.STANDING))
        }
    }

    @Test fun `bests replace only a faster standing time`() {
        val hand = rep(30, 452, TimingMethod.HAND)
        val same = rep(30, 445, TimingMethod.HAND)
        val faster = rep(30, 441, TimingMethod.HAND)
        assertSame(hand, SprintComparison.bests(listOf(hand, same)).values.single())
        assertSame(faster, SprintComparison.bests(listOf(hand, same, faster)).values.single())
        val phone = rep(30, 445, TimingMethod.PHONE)
        assertSame(phone, SprintComparison.bests(listOf(rep(30, 452, TimingMethod.PHONE), phone)).values.single())
    }

    @Test fun `only finished timed work with a distance end belongs to a series`() {
        val rep = rep(30, 452, TimingMethod.PHONE)
        assertEquals(SprintSeries(30, TimingMethod.PHONE, StartProtocol.THREE_POINT), SprintComparison.series(rep))
        assertNull(SprintComparison.series(rep.copy(timing = null)))
        for (status in listOf(CardioSetStatus.SHORTENED, CardioSetStatus.SKIPPED)) {
            assertNull(SprintComparison.series(rep.copy(status = status)))
        }
        assertNull(SprintComparison.series(rep.copy(spec = rep.spec.copy(role = CardioRole.RECOVERY))))
        assertNull(SprintComparison.series(rep.copy(spec = rep.spec.copy(end = Measure.Time(10)))))
    }

    @Test fun `case 37 attempts take the fastest rep per goal series in first appearance order`() {
        val goal = PlanGoal.Sprint(30)
        val warmup = CardioSetResult(CardioSetSpec(CardioRole.WARMUP, Measure.Time(600)), CardioSetStatus.FINISHED, 600)
        val session = listOf(warmup, rep(30, 470, TimingMethod.PHONE), rep(30, 452, TimingMethod.PHONE),
            rep(30, 461, TimingMethod.PHONE), rep(40, 440, TimingMethod.PHONE))
        val phone = SprintAttempt(7_000, SprintTime(452, TimingMethod.PHONE, StartProtocol.THREE_POINT))
        assertEquals(listOf(phone), SprintComparison.attempts(goal, session, 7_000))
        val hand = rep(30, 440, TimingMethod.HAND)
        assertEquals(listOf(phone, SprintAttempt(7_000, requireNotNull(hand.timing))),
            SprintComparison.attempts(goal, session + hand, 7_000))
        assertEquals(emptyList<SprintAttempt>(), SprintComparison.attempts(goal,
            listOf(warmup, rep(40, 440, TimingMethod.PHONE)), 7_000))

        val first = rep(30, 445, TimingMethod.HAND)
        val tied = first.copy(elapsedSeconds = 6, timing = requireNotNull(first.timing).copy())
        assertSame(first.timing, SprintComparison.attempts(goal,
            listOf(rep(30, 452, TimingMethod.HAND), first, tied), 7_000).single().time)
    }

    @Test fun `cardio records skip timed reps`() {
        val timed = rep(30, 452, TimingMethod.PHONE)
        assertFalse(CardioBests.counts(timed))
        assertTrue(CardioBests.counts(timed.copy(timing = null)))
    }
}
