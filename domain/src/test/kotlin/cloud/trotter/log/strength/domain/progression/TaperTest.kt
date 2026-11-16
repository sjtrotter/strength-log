package cloud.trotter.log.strength.domain.progression

import cloud.trotter.log.strength.domain.model.CardioActivity
import cloud.trotter.log.strength.domain.model.CardioBlock
import cloud.trotter.log.strength.domain.model.CardioRole
import cloud.trotter.log.strength.domain.model.CardioSetSpec
import cloud.trotter.log.strength.domain.model.Measure
import cloud.trotter.log.strength.domain.model.PlanGoal
import cloud.trotter.log.strength.domain.model.ProgressMeasure
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

class TaperTest {
    private fun time(seconds: Int, role: CardioRole = CardioRole.WORK) = CardioSetSpec(role, Measure.Time(seconds))
    private fun distance(metres: Int, aim: Int? = null) =
        CardioSetSpec(CardioRole.WORK, Measure.Distance(metres), aim?.let { Measure.Time(it) })
    private fun block(vararg sets: CardioSetSpec) = CardioBlock(CardioActivity.ROAD_RUN, sets.toList())
    private val half = PlanGoal.Timed(CardioActivity.ROAD_RUN, 21_097, 7200, byEpochDay = 1000)

    @Test
    fun `a sprint goal has no taper`() {
        assertNull(Taper.factor(PlanGoal.Sprint(100, byEpochDay = 20_010), 20_005))
    }

    @Test
    fun `case 19 half marathon`() {
        // audit 2026-11-13, row 15: 1000 minus 988, 995, 986, 993 = 12, 5, 14, 7 days; half factors 0.6, 0.45, 0.6, 0.45.
        for ((day, expected) in listOf(988L to 0.6, 995L to 0.45, 986L to 0.6, 993L to 0.45)) {
            assertEquals(expected, Taper.factor(half, day))
        }
        assertNull(Taper.factor(half, 985))
        assertNull(Taper.factor(half, 1001))
        // audit 2026-11-13, row 15: 18000 × 0.6 = 10800.
        assertEquals(block(distance(10800)), Taper.applied(block(distance(18000)), 0.6))
        // audit 2026-11-13, row 15: 18000 × 0.45 = 8100.
        assertEquals(block(distance(8100)), Taper.applied(block(distance(18000)), 0.45))
    }

    @Test
    fun `case 19 through cardioAtStart`() {
        val opening = block(distance(18000))
        val ctx = Progression.CardioContext(988, ProgressMeasure.DISTANCE, 5)
        // audit 2026-11-13, row 15: 12 days out: 18000 × 0.6 = 10800.
        assertEquals(block(distance(10800)), Progression.cardioAtStart(opening, null, CardioHistory(), half, ctx))
        // audit 2026-11-13, row 15: 5 days out: 18000 × 0.45 = 8100.
        assertEquals(block(distance(8100)),
            Progression.cardioAtStart(opening, null, CardioHistory(), half, ctx.copy(todayEpochDay = 995)))
    }

    @Test
    fun `case 19 hard flags and aims stay unchanged`() {
        val opening = CardioBlock(CardioActivity.ROAD_RUN, buildList {
            add(time(600, CardioRole.WARMUP))
            repeat(6) { index ->
                if (index > 0) add(time(90, CardioRole.RECOVERY))
                add(distance(400, 105).copy(hard = true))
            }
        })
        // audit 2026-11-13, row 15: ceil(6 × 0.6) = 4; ceil(6 × 0.45) = 3.
        for ((factor, count) in listOf(0.6 to 4, 0.45 to 3)) {
            val tapered = Taper.applied(opening, factor)
            assertEquals(opening.copy(sets = opening.sets.take(2 * count)), tapered)
            tapered.sets.forEachIndexed { index, set -> assertSame(opening.sets[index], set) }
            assertEquals(List(count) { distance(400, 105).copy(hard = true) }, tapered.sets.filter { it.role == CardioRole.WORK })
        }
    }

    @Test
    fun `single set holds its pace`() {
        assertEquals(block(distance(3500, 1050)), Taper.applied(block(distance(5000, 1500)), 0.7))
    }

    @Test
    fun `case 19 marathon short race and shuttle bands follow the live date`() {
        val marathon = PlanGoal.Timed(CardioActivity.ROAD_RUN, 42_195, 14_400, 1000)
        // audit 2026-11-13, row 15: 1000 - 980 = 20 days, longer factor 0.75.
        assertEquals(0.75, Taper.factor(marathon, 980))
        // audit 2026-11-13, row 15: 1000 - 990 = 10 days, longer factor 0.55.
        assertEquals(0.55, Taper.factor(marathon, 990))
        // audit 2026-11-13, row 15: 1000 - 997 = 3 days, longer factor 0.4.
        assertEquals(0.4, Taper.factor(marathon, 997))
        assertNull(Taper.factor(marathon, 978))
        val fiveK = PlanGoal.Timed(CardioActivity.ROAD_RUN, 5000, 1500, 1000)
        // audit 2026-11-13, row 15: 1000 - 990 = 10 days, short-race factor 0.5.
        assertEquals(0.5, Taper.factor(fiveK, 990))
        assertNull(Taper.factor(fiveK, 989))
        // audit 2026-11-13, row 15: 1000 - 990 = 10 days, shuttle factor 0.5.
        assertEquals(0.5, Taper.factor(PlanGoal.Shuttle("PACER", 60, byEpochDay = 1000), 990))
        assertNull(Taper.factor(fiveK.copy(byEpochDay = null), 990))
        assertNull(Taper.factor(null, 990))
    }

    @Test
    fun `case 19 ladder day does not taper`() {
        val opening = RunWalkLadder.block(3, CardioActivity.ROAD_RUN)
        val goal = PlanGoal.Timed(CardioActivity.ROAD_RUN, 5000, 1500, 1000)
        assertSame(opening, Progression.cardioAtStart(opening, CardioRule(3), CardioHistory(), goal,
            Progression.CardioContext(995, ProgressMeasure.TIME, 5)))
    }

    @Test
    fun `round scaling tolerates floating point edges and keeps trailing easy sets`() {
        val opening = CardioBlock(CardioActivity.ROAD_RUN, buildList {
            add(time(600, CardioRole.WARMUP))
            repeat(10) {
                add(distance(400, 105).copy(hard = true))
                add(time(90, CardioRole.RECOVERY))
            }
            add(time(300, CardioRole.COOLDOWN))
        })
        val tapered = Taper.applied(opening, 0.7)
        val expected = opening.sets.take(14) + opening.sets.takeLast(2)
        assertEquals(opening.copy(sets = expected), tapered)
        expected.forEachIndexed { index, set -> assertSame(set, tapered.sets[index]) }
        assertEquals(1, Taper.applied(opening, 0.0).sets.count { it.role == CardioRole.WORK })
        val noWork = block(time(600, CardioRole.WARMUP))
        assertSame(noWork, Taper.applied(noWork, 0.5))
        assertEquals(block(distance(1, 1)), Taper.applied(block(distance(1, 1)), 0.5))
    }

    @Test
    fun `taper bands include each upper distance and date boundary`() {
        val tenK = PlanGoal.Timed(CardioActivity.ROAD_RUN, Taper.TEN_K_METRES, 3000, 1000)
        // audit 2026-11-13, row 15: 1000 - 990 = 10 days, 10000 metres uses 0.5.
        assertEquals(0.5, Taper.factor(tenK, 990))
        // audit 2026-11-13, row 15: 1000 - 986 = 14 days, 10001 metres uses 0.6.
        assertEquals(0.6, Taper.factor(tenK.copy(metres = 10001), 986))
        // audit 2026-11-13, row 15: 1000 - 986 = 14 days, 21100 metres uses 0.6.
        assertEquals(0.6, Taper.factor(half.copy(metres = Taper.HALF_METRES), 986))
        val longer = half.copy(metres = Taper.HALF_METRES + 1)
        // audit 2026-11-13, row 15: longer race at 0 and 7 days uses 0.4; 8 and 14 uses 0.55; 15 and 21 uses 0.75.
        for ((out, expected) in listOf(0L to 0.4, 7L to 0.4, 8L to 0.55, 14L to 0.55, 15L to 0.75, 21L to 0.75)) {
            assertEquals(expected, Taper.factor(longer, 1000 - out))
        }
        assertNull(Taper.factor(PlanGoal.Shuttle("PACER", 60, byEpochDay = 1000), 989))
    }

    @Test
    fun `case 19 taper precedes the run cap and applies with the cap off`() {
        val opening = block(distance(18000))
        val history = CardioHistory(longest = RecentMaxima(8050, 2900))
        val ctx = Progression.CardioContext(988, ProgressMeasure.DISTANCE, 5)
        assertEquals(block(distance(8800)), Progression.cardioAtStart(opening, null, history, half, ctx))
        // audit 2026-11-13, row 15: 18000 × 0.6 = 10800 with cap off; cap on still stops at 8800.
        assertEquals(block(distance(10800)),
            Progression.cardioAtStart(opening, null, history, half, ctx.copy(runCap = false)))
    }
}
