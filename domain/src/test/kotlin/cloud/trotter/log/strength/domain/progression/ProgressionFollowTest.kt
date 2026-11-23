package cloud.trotter.log.strength.domain.progression

import cloud.trotter.log.strength.domain.model.Equipment
import cloud.trotter.log.strength.domain.model.LoggedSet
import cloud.trotter.log.strength.domain.model.SetKind
import cloud.trotter.log.strength.domain.units.WeightUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ProgressionFollowTest {
    private val bar = setOf(Equipment.BARBELL, Equipment.BENCH, Equipment.RACK)
    private val db = setOf(Equipment.DUMBBELL, Equipment.BENCH)
    private fun ctx(source: Double? = null, equipment: Set<Equipment> = bar) =
        Progression.Context(WeightUnit.LB, equipment, LoadSteps.defaults(WeightUnit.LB), 130, sourceLoadLb = source)
    private fun rule(rir: Int = 3, target: RepTarget = RepTarget.Fixed(5)) =
        SlotRule(SlotRole.MAIN, target, rir, 0.025, 1, scheme = Scheme.STRENGTH)
    private fun follower(ratio: Double = 170.0 / 185.0) = rule().copy(loadSource = LoadSource("A", ratio))
    private fun work(weight: Double, reps: Int = 5, count: Int = 3) = List(count) { LoggedSet(weight, reps, SetKind.WORK) }
    private fun actual(rows: List<LoggedSet>, reps: List<Int>? = null, weight: Double? = null) = rows.mapIndexed { i, row ->
        row.copy(done = true, plannedIndex = i, reps = reps?.get(i) ?: row.reps, weightLb = weight ?: row.weightLb)
    }
    private fun finish(rule: SlotRule, rows: List<LoggedSet>, done: List<LoggedSet> = actual(rows), context: Progression.Context) =
        Progression.afterSession(rule, rows, Progression.Exposure(rows, done, 130), context)
    private fun streaks(out: Outcome, success: Int, rep: Int, effort: Int) = assertEquals(listOf(success, rep, effort),
        listOf(out.rule.state.successStreak, out.rule.state.repMissStreak, out.rule.state.effortMissStreak))

    @Test fun `case 46 a follower takes the ratio of its source and holds through a miss`() {
        val first = finish(follower(), work(170.0), context = ctx(190.0))
        assertEquals(work(175.0), first.rows)
        assertEquals(Change.Increase(170.0, 175.0), first.change)
        assertEquals(Reason.Followed, first.reason)
        assertEquals(RepTarget.Fixed(5), first.rule.target)
        streaks(first, 1, 0, 0)
        val miss = finish(first.rule, first.rows, actual(first.rows, listOf(5, 5, 4)), ctx(190.0))
        assertEquals(work(175.0), miss.rows)
        assertEquals(Change.Hold, miss.change)
        assertEquals(Reason.RepMiss, miss.reason)
        streaks(miss, 0, 1, 0)
        val second = finish(miss.rule, miss.rows, actual(miss.rows, listOf(5, 5, 4)), ctx(195.0))
        assertEquals(work(175.0), second.rows)
        assertEquals(Reason.RepMiss, second.reason)
        streaks(second, 0, 2, 0)
        assertEquals(1, second.rule.everyExposures)
        assertTrue(second.rule.state.resetEpochDays.isEmpty())
        val success = finish(second.rule, second.rows, context = ctx(195.0))
        assertEquals(work(180.0), success.rows)
        assertEquals(Change.Increase(175.0, 180.0), success.change)
        assertEquals(Reason.Followed, success.reason)
        streaks(success, 1, 0, 0)
    }

    @Test fun `case 44 R's secondary follows its main at Estimate's ratio`() {
        val rule = follower(37.0 / 38.0).copy(role = SlotRole.SECONDARY)
        val rows = work(270.0, count = 2)
        val held = finish(rule, rows, context = ctx(275.0))
        assertEquals(rows, held.rows)
        assertEquals(Change.Hold, held.change)
        assertEquals(Reason.Followed, held.reason)
        val moved = finish(rule, rows, context = ctx(280.0))
        assertEquals(work(275.0, count = 2), moved.rows)
        assertEquals(Change.Increase(270.0, 275.0), moved.change)
        assertEquals(37.0 / 38.0, Estimate.loadRatio(5, 3, 5, 2))
        assertEquals(270.0, LoadSteps.defaults(WeightUnit.LB).nearest(275.0 * (37.0 / 38.0), bar, WeightUnit.LB))
    }

    @Test fun `a changed working load re-forms the ratio`() {
        val rows = work(170.0)
        val lower = finish(follower(), rows, actual(rows, weight = 160.0), ctx(190.0))
        assertEquals(Reason.Lowered, lower.reason)
        assertEquals(Change.KeptLowered(160.0), lower.change)
        assertEquals(work(160.0), lower.rows)
        assertEquals(LoadSource("A", 160.0 / 190.0), lower.rule.loadSource)
        val higher = finish(follower(), rows, actual(rows, weight = 180.0), ctx(190.0))
        assertEquals(Reason.Followed, higher.reason)
        assertEquals(Change.Hold, higher.change)
        assertEquals(work(180.0), higher.rows)
        assertEquals(LoadSource("A", 180.0 / 190.0), higher.rule.loadSource)
    }

    @Test fun `a follower keeps a fixed target on a coarse step`() {
        val input = follower(0.5).copy(target = RepTarget.Fixed(10), rir = 2)
        val out = finish(input, work(40.0, 10), context = ctx(90.0, db))
        assertEquals(RepTarget.Fixed(10), out.rule.target)
        assertEquals(work(45.0, 10), out.rows)
        assertEquals(Reason.Followed, out.reason)
    }

    @Test fun `a Range follower moves its reps and keeps its ratio`() {
        val input = follower(0.6).copy(target = RepTarget.Range(8, 12, 8), rir = 2)
        val rows = work(60.0, 8)
        val first = finish(input, rows, actual(rows, listOf(10, 9, 8)), ctx(100.0, db))
        assertEquals(work(60.0, 9), first.rows)
        assertEquals(RepTarget.Range(8, 12, 9), first.rule.target)
        assertEquals(Change.RepsTo(9), first.change)
        assertEquals(Reason.Followed, first.reason)
        assertEquals(input.loadSource, first.rule.loadSource)
        val raised = finish(first.rule, first.rows, context = ctx(105.0, db))
        assertEquals(work(65.0, 8), raised.rows)
        assertEquals(RepTarget.Range(8, 12, 8), raised.rule.target)
        assertEquals(Change.Increase(60.0, 65.0), raised.change)
        assertEquals(input.loadSource, raised.rule.loadSource)
        val ceiling = finish(input.copy(target = RepTarget.Range(8, 12, 12)), work(60.0, 12), context = ctx(100.0, db))
        assertEquals(work(60.0, 12), ceiling.rows)
        assertEquals(RepTarget.Range(8, 12, 12), ceiling.rule.target)
        assertEquals(Change.Hold, ceiling.change)
        assertEquals(Reason.Followed, ceiling.reason)
        val miss = finish(first.rule, first.rows, actual(first.rows, listOf(8, 8, 7)), ctx(100.0, db))
        assertEquals(first.rows, miss.rows)
        assertEquals(first.rule.target, miss.rule.target)
        assertEquals(Reason.RepMiss, miss.reason)
    }

    @Test fun `without a source load a linked slot progresses on its own`() {
        val out = finish(follower(), work(170.0), context = ctx())
        val own = finish(rule(), work(170.0), context = ctx())
        assertEquals(own.copy(rule = own.rule.copy(loadSource = follower().loadSource)), out)
        assertEquals(work(175.0), out.rows)
        assertEquals(Reason.AllMade, out.reason)
    }
}
