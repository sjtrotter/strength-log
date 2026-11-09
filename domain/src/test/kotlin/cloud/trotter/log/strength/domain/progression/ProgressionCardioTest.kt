package cloud.trotter.log.strength.domain.progression

import cloud.trotter.log.strength.domain.generator.CardioBlocks
import cloud.trotter.log.strength.domain.model.CardioActivity
import cloud.trotter.log.strength.domain.model.CardioBlock
import cloud.trotter.log.strength.domain.model.CardioRole
import cloud.trotter.log.strength.domain.model.CardioSetResult
import cloud.trotter.log.strength.domain.model.CardioSetSpec
import cloud.trotter.log.strength.domain.model.CardioSetStatus
import cloud.trotter.log.strength.domain.model.CardioSuggestion
import cloud.trotter.log.strength.domain.model.DistanceSource
import cloud.trotter.log.strength.domain.model.Measure
import cloud.trotter.log.strength.domain.model.ProgressMeasure
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ProgressionCardioTest {
    private fun time(seconds: Int, aim: Int? = null, role: CardioRole = CardioRole.WORK) =
        CardioSetSpec(role, Measure.Time(seconds), aim?.let { Measure.Distance(it) })

    private fun distance(metres: Int, aim: Int? = null, role: CardioRole = CardioRole.WORK) =
        CardioSetSpec(role, Measure.Distance(metres), aim?.let { Measure.Time(it) })

    private fun block(vararg sets: CardioSetSpec) = CardioBlock(CardioActivity.ROAD_RUN, sets.toList())

    private fun intervals() = CardioBlocks.fromLegacy(
        CardioSuggestion("", "", hard = true), fiveK = true, activity = CardioActivity.ROAD_RUN,
    )

    private fun result(
        spec: CardioSetSpec,
        status: CardioSetStatus = CardioSetStatus.FINISHED,
        elapsed: Int = ((spec.end as? Measure.Time) ?: (spec.aim as? Measure.Time))?.seconds ?: 0,
        metres: Int? = ((spec.end as? Measure.Distance) ?: (spec.aim as? Measure.Distance))?.metres,
        metresAtEnd: Int? = null,
        secondsAtEnd: Int? = null,
    ) = CardioSetResult(spec, status, elapsed, metres, metres?.let { DistanceSource.MANUAL }, metresAtEnd, secondsAtEnd)

    private fun finished(block: CardioBlock) = block.sets.map { result(it) }

    @Test
    fun `distance plus five percent of a 3 mi run`() {
        val opening = block(distance(4828))
        val offered = block(distance(5069))
        assertEquals(offered, CardioMeasures.increased(opening, ProgressMeasure.DISTANCE, 5))
    }

    @Test
    fun `time plus five percent of a 20 minute run`() {
        val opening = block(time(1200))
        val offered = block(time(1260))
        assertEquals(offered, CardioMeasures.increased(opening, ProgressMeasure.TIME, 5))
    }

    @Test
    fun `pace one percent faster rounds to the second`() {
        val opening = block(distance(1600, 480))
        val offered = block(distance(1600, 475))
        assertEquals(offered, CardioMeasures.increased(opening, ProgressMeasure.PACE, 1))
        assertEquals(block(distance(400, 104)),
            CardioMeasures.increased(block(distance(400, 105)), ProgressMeasure.PACE, 1))
    }

    @Test
    fun `minimum one unit`() {
        assertEquals(block(time(9)), CardioMeasures.increased(block(time(8)), ProgressMeasure.TIME, 5))
        assertEquals(block(distance(11)), CardioMeasures.increased(block(distance(10)), ProgressMeasure.DISTANCE, 5))
    }

    @Test
    fun `distance keeps the pace of a time aim`() {
        assertEquals(block(distance(420, 110)),
            CardioMeasures.increased(block(distance(400, 105)), ProgressMeasure.DISTANCE, 5))
    }

    @Test
    fun `distance keeps the pace of a time end`() {
        assertEquals(block(time(1890, 5069)),
            CardioMeasures.increased(block(time(1800, 4828)), ProgressMeasure.DISTANCE, 5))
    }

    @Test
    fun `time keeps the pace of a distance aim`() {
        assertEquals(block(time(1890, 5069)),
            CardioMeasures.increased(block(time(1800, 4828)), ProgressMeasure.TIME, 5))
    }

    @Test
    fun `pace with a time end holds the end and increases the distance aim`() {
        val opening = block(time(1800, 4828))
        val offered = block(time(1800, 4876))
        assertEquals(offered, CardioMeasures.increased(opening, ProgressMeasure.PACE, 1))
    }

    @Test
    fun `intervals keep their rounds and their easy sets`() {
        val opening = intervals()
        val increased = CardioMeasures.increased(opening, ProgressMeasure.TIME, 5)
        assertEquals(11, increased.sets.size)
        assertEquals(Measure.Time(300), increased.sets.first().end)
        assertEquals(opening.activity, increased.activity)
        assertEquals(opening.sets.map { it.role }, increased.sets.map { it.role })
        assertEquals(opening.sets.map { it.hard }, increased.sets.map { it.hard })
        assertEquals(List(5) { Measure.Time(126) }, increased.sets.filter { it.role == CardioRole.WORK }.map { it.end })
        assertEquals(List(5) { Measure.Time(120) }, increased.sets.filter { it.role == CardioRole.RECOVERY }.map { it.end })
        opening.sets.indices.filter { opening.sets[it].role != CardioRole.WORK }.forEach {
            assertSame(opening.sets[it], increased.sets[it])
        }
    }

    @Test
    fun `warmup recovery and cooldown targets and aims never change`() {
        val opening = block(time(300, 500, CardioRole.WARMUP), distance(400, 105),
            distance(100, 60, CardioRole.RECOVERY), time(300, 500, CardioRole.COOLDOWN))
        for (measure in listOf(ProgressMeasure.DISTANCE, ProgressMeasure.PACE)) {
            val increased = CardioMeasures.increased(opening, measure, 5)
            for (index in listOf(0, 2, 3)) assertSame(opening.sets[index], increased.sets[index])
            assertEquals(opening.sets.size, increased.sets.size)
        }
    }

    @Test
    fun `out of range leaves the block unchanged`() {
        val opening = block(distance(199_000))
        assertSame(opening, CardioMeasures.increased(opening, ProgressMeasure.DISTANCE, 5))
    }

    @Test
    fun `range failure in any work end or aim rejects the entire increase`() {
        val cases = listOf(
            block(time(120), time(86_400)) to ProgressMeasure.TIME,
            block(distance(400, 105), distance(100, 86_400)) to ProgressMeasure.DISTANCE,
            block(time(120, 400), time(120, 200_000)) to ProgressMeasure.TIME,
            block(distance(400, 105), distance(400, 1)) to ProgressMeasure.PACE,
            block(time(120, 400), time(120, 200_000)) to ProgressMeasure.PACE,
        )
        for ((opening, measure) in cases) {
            assertSame(opening, CardioMeasures.increased(opening, measure, 5))
        }
    }

    @Test
    fun `pace preservation does not overflow integer multiplication`() {
        assertEquals(block(distance(200_000, 86_400)),
            CardioMeasures.increased(block(distance(160_000, 69_120)), ProgressMeasure.DISTANCE, 25))
        assertEquals(block(time(86_400, 200_000)),
            CardioMeasures.increased(block(time(69_120, 160_000)), ProgressMeasure.TIME, 25))
    }

    @Test
    fun `percent is clamped`() {
        for ((opening, measure) in listOf(block(time(1200)) to ProgressMeasure.TIME,
            block(distance(400)) to ProgressMeasure.DISTANCE, block(distance(1600, 480)) to ProgressMeasure.PACE)) {
            assertEquals(CardioMeasures.increased(opening, measure, 1), CardioMeasures.increased(opening, measure, 0))
            assertEquals(CardioMeasures.increased(opening, measure, 25), CardioMeasures.increased(opening, measure, 90))
        }
        assertEquals(block(time(1212)), CardioMeasures.increased(block(time(1200)), ProgressMeasure.TIME, 0))
        assertEquals(block(time(1500)), CardioMeasures.increased(block(time(1200)), ProgressMeasure.TIME, 90))
    }

    @Test
    fun `default percentages are five for distance and time and one for pace`() {
        assertEquals(1, CardioMeasures.MIN_PERCENT)
        assertEquals(25, CardioMeasures.MAX_PERCENT)
        assertEquals(5, CardioMeasures.defaultPercent(ProgressMeasure.DISTANCE))
        assertEquals(5, CardioMeasures.defaultPercent(ProgressMeasure.TIME))
        assertEquals(1, CardioMeasures.defaultPercent(ProgressMeasure.PACE))
    }

    @Test
    fun `available measures require support in every work set`() {
        assertEquals(setOf(ProgressMeasure.TIME), CardioMeasures.available(block(time(1800))))
        assertEquals(setOf(ProgressMeasure.TIME, ProgressMeasure.DISTANCE, ProgressMeasure.PACE),
            CardioMeasures.available(block(time(1800, 4828))))
        assertEquals(setOf(ProgressMeasure.DISTANCE), CardioMeasures.available(block(distance(4828))))
        assertEquals(setOf(ProgressMeasure.DISTANCE, ProgressMeasure.PACE),
            CardioMeasures.available(block(distance(400, 105))))
        assertEquals(emptySet(), CardioMeasures.available(block(time(300, role = CardioRole.WARMUP))))
        assertEquals(setOf(ProgressMeasure.TIME), CardioMeasures.available(block(time(120, 400), time(120))))
        assertEquals(emptySet(), CardioMeasures.available(block(time(120), distance(400))))
        assertEquals(setOf(ProgressMeasure.DISTANCE, ProgressMeasure.PACE),
            CardioMeasures.available(block(time(120, 400), distance(400, 105))))
    }

    @Test
    fun `default measure follows the end and never opts into pace`() {
        assertEquals(ProgressMeasure.DISTANCE, CardioMeasures.defaultMeasure(block(distance(4828))))
        assertEquals(ProgressMeasure.TIME, CardioMeasures.defaultMeasure(block(time(1800))))
        assertEquals(ProgressMeasure.TIME, CardioMeasures.defaultMeasure(intervals()))
        assertEquals(ProgressMeasure.TIME, CardioMeasures.defaultMeasure(block(time(1800, 4828))))
        assertEquals(ProgressMeasure.DISTANCE, CardioMeasures.defaultMeasure(block(distance(400, 105))))
        assertEquals(ProgressMeasure.DISTANCE, CardioMeasures.defaultMeasure(block(time(120, 400), distance(400, 105))))
        assertNull(CardioMeasures.defaultMeasure(block(time(300, role = CardioRole.WARMUP))))
        assertNull(CardioMeasures.defaultMeasure(block(time(120), distance(400))))
    }

    @Test
    fun `resolve keeps a supported choice and falls back after an edit`() {
        assertEquals(ProgressMeasure.TIME, CardioMeasures.resolve(block(time(1800)), ProgressMeasure.PACE))
        assertEquals(ProgressMeasure.PACE, CardioMeasures.resolve(block(time(1800, 4828)), ProgressMeasure.PACE))
        assertEquals(ProgressMeasure.DISTANCE, CardioMeasures.resolve(block(distance(400)), null))
        assertNull(CardioMeasures.resolve(block(time(300, role = CardioRole.WARMUP)), ProgressMeasure.TIME))
    }

    @Test
    fun `unsupported measures leave the block unchanged and offer nothing`() {
        val opening = block(time(1800))
        for (measure in listOf(ProgressMeasure.DISTANCE, ProgressMeasure.PACE)) {
            assertSame(opening, CardioMeasures.increased(opening, measure, 5))
        }
    }

    @Test
    fun `open ended time requires the planned duration and finished status`() {
        val opening = block(time(1800))
        val spec = opening.sets.single()
        assertFalse(CardioMeasures.fullSuccess(opening, listOf(result(spec, elapsed = 1795))))
        assertTrue(CardioMeasures.fullSuccess(opening, listOf(result(spec, elapsed = 1800))))
        assertTrue(CardioMeasures.fullSuccess(opening, listOf(result(spec, elapsed = 2400))))
        assertFalse(CardioMeasures.fullSuccess(opening,
            listOf(result(spec, CardioSetStatus.SHORTENED, elapsed = 1800))))
    }

    @Test
    fun `distance aim requires a recorded distance meeting the aim`() {
        val opening = block(time(1800, 4828))
        val spec = opening.sets.single()
        assertFalse(CardioMeasures.fullSuccess(opening, listOf(result(spec, metres = null))))
        assertFalse(CardioMeasures.fullSuccess(opening, listOf(result(spec, metres = 4800))))
        assertTrue(CardioMeasures.fullSuccess(opening, listOf(result(spec, metres = 4828))))
        assertTrue(CardioMeasures.fullSuccess(opening, listOf(result(spec, metres = 5000))))
    }

    @Test
    fun `time aim requires finishing by the aim`() {
        val opening = block(distance(400, 105))
        val spec = opening.sets.single()
        assertFalse(CardioMeasures.fullSuccess(opening, listOf(result(spec, elapsed = 106, metres = 400))))
        assertTrue(CardioMeasures.fullSuccess(opening, listOf(result(spec, elapsed = 105, metres = 400))))
        assertTrue(CardioMeasures.fullSuccess(opening, listOf(result(spec, elapsed = 104, metres = 400))))
    }

    @Test
    fun `finished status meets deadline and distance ends without rechecking actuals`() {
        val opening = block(time(120), distance(400))
        assertTrue(CardioMeasures.fullSuccess(opening, opening.sets.map { result(it, elapsed = 0, metres = null) }))
    }

    @Test
    fun `interval success judges every work set and ignores skipped warmup`() {
        val opening = intervals()
        val results = finished(opening)
        assertTrue(CardioMeasures.fullSuccess(opening, results))
        assertFalse(CardioMeasures.fullSuccess(opening, results.mapIndexed { index, result ->
            if (index == 3) result.copy(status = CardioSetStatus.SHORTENED) else result
        }))
        assertTrue(CardioMeasures.fullSuccess(opening, results.mapIndexed { index, result ->
            if (index == 0) result.copy(status = CardioSetStatus.SKIPPED, elapsedSeconds = 0) else result
        }))
    }

    @Test
    fun `nonwork status end and aim are not judged`() {
        val opening = block(time(300, 500, CardioRole.WARMUP), time(120),
            distance(100, 60, CardioRole.RECOVERY), time(300, 500, CardioRole.COOLDOWN))
        val results = opening.sets.map {
            if (it.role == CardioRole.WORK) result(it)
            else result(it, CardioSetStatus.SKIPPED, elapsed = 1000, metres = null)
        }
        assertTrue(CardioMeasures.fullSuccess(opening, results))
        val warmup = block(time(300, role = CardioRole.WARMUP))
        assertFalse(CardioMeasures.fullSuccess(warmup, finished(warmup)))
    }

    @Test
    fun `success requires the exact block size specs and order`() {
        val opening = intervals()
        val results = finished(opening)
        assertFalse(CardioMeasures.fullSuccess(opening, results.dropLast(1)))
        assertFalse(CardioMeasures.fullSuccess(opening, results + result(time(120))))
        assertFalse(CardioMeasures.fullSuccess(opening, results.mapIndexed { index, result ->
            if (index == 1) result.copy(spec = time(121)) else result
        }))
        assertFalse(CardioMeasures.fullSuccess(opening, results.reversed()))
    }

    private fun finish(
        opening: CardioBlock,
        measure: ProgressMeasure? = ProgressMeasure.TIME,
        percent: Int = 5,
        results: List<CardioSetResult> = finished(opening),
        recent: CardioHistory = CardioHistory(),
        runCap: Boolean = false,
        rule: CardioRule? = null,
    ) = Progression.cardioAfterSession(opening, rule, results, CardioEffort.GOOD, recent,
        Progression.CardioContext(1000, measure, percent, runCap))

    @Test
    fun `Finish increases distance five percent on a 3 mi run`() {
        assertEquals(CardioOutcome(block(distance(5069)), null, CardioChange.Increase(ProgressMeasure.DISTANCE, false)),
            finish(block(distance(4828)), ProgressMeasure.DISTANCE))
    }

    @Test
    fun `Finish increases time five percent on a 20 minute run`() {
        assertEquals(CardioOutcome(block(time(1260)), null, CardioChange.Increase(ProgressMeasure.TIME, false)),
            finish(block(time(1200))))
    }

    @Test
    fun `Finish increases pace one percent with a distance end`() {
        assertEquals(CardioOutcome(block(distance(1600, 475)), null, CardioChange.Increase(ProgressMeasure.PACE, false)),
            finish(block(distance(1600, 480)), ProgressMeasure.PACE, 1))
    }

    @Test
    fun `Finish increases pace one percent with a time end`() {
        assertEquals(CardioOutcome(block(time(1800, 4876)), null, CardioChange.Increase(ProgressMeasure.PACE, false)),
            finish(block(time(1800, 4828)), ProgressMeasure.PACE, 1))
    }

    @Test
    fun `next time reports the first work change after warmup`() {
        val opening = block(time(300, role = CardioRole.WARMUP), time(120), time(240))
        assertEquals(CardioOutcome(block(time(300, role = CardioRole.WARMUP), time(126), time(252)), null,
            CardioChange.Increase(ProgressMeasure.TIME, false)), finish(opening))
    }

    @Test
    fun `Finish repeats when distance is out of range`() {
        val opening = block(distance(199_000))
        assertEquals(CardioOutcome(opening, null, CardioChange.Repeat), finish(opening, ProgressMeasure.DISTANCE))
    }

    @Test
    fun `Finish repeats after a range failure in any work end or aim`() {
        val cases = listOf(
            block(time(120), time(86_400)) to ProgressMeasure.TIME,
            block(distance(400, 105), distance(100, 86_400)) to ProgressMeasure.DISTANCE,
            block(time(120, 400), time(120, 200_000)) to ProgressMeasure.TIME,
            block(distance(400, 105), distance(400, 1)) to ProgressMeasure.PACE,
            block(time(120, 400), time(120, 200_000)) to ProgressMeasure.PACE,
        )
        for ((opening, measure) in cases) {
            assertEquals(CardioOutcome(opening, null, CardioChange.Repeat), finish(opening, measure))
        }
    }

    @Test
    fun `Finish repeats when the measure is unsupported`() {
        val opening = block(time(1800))
        for (measure in listOf(ProgressMeasure.DISTANCE, ProgressMeasure.PACE)) {
            assertEquals(CardioOutcome(opening, null, CardioChange.Repeat), finish(opening, measure))
        }
    }

    @Test
    fun `offer after a miss is null and nothing drops`() {
        val opening = block(time(1800))
        assertEquals(CardioOutcome(opening, null, CardioChange.Repeat),
            finish(opening, results = listOf(result(opening.sets.single(), elapsed = 1795))))
    }

    @Test
    fun `next time repeats the opening after a miss`() {
        val opening = block(time(1800))
        assertEquals(CardioOutcome(opening, null, CardioChange.Repeat),
            finish(opening, results = listOf(result(opening.sets.single(), CardioSetStatus.SHORTENED))))
    }

    @Test
    fun `next time needs a measure and nonempty history`() {
        val opening = block(distance(4828))
        assertEquals(CardioOutcome(opening, null, CardioChange.Repeat), finish(opening, measure = null))
        assertEquals(CardioOutcome(opening, null, CardioChange.Repeat),
            finish(opening, ProgressMeasure.DISTANCE, results = emptyList()))
    }

    @Test
    fun `next time repeats when success cannot produce a supported increase`() {
        val limit = block(distance(199_000))
        assertEquals(CardioOutcome(limit, null, CardioChange.Repeat), finish(limit, ProgressMeasure.DISTANCE))
        val opening = block(time(1800))
        assertEquals(CardioOutcome(opening, null, CardioChange.Repeat), finish(opening, ProgressMeasure.PACE, 1))
    }

    @Test
    fun `case 18 no grace window at Finish`() {
        val opening = block(time(900, 3480))
        val row = result(opening.sets.single(), elapsed = 904, metres = 3485, metresAtEnd = 3470, secondsAtEnd = 900)
        assertEquals(CardioOutcome(opening, null, CardioChange.Repeat), finish(opening, results = listOf(row)))
        assertEquals(CardioOutcome(block(time(945, 3654)), null, CardioChange.Increase(ProgressMeasure.TIME, false)),
            finish(opening, results = listOf(row.copy(metresAtEnd = null, secondsAtEnd = null))))
    }

    @Test
    fun `case 18 distance end meets its time aim at the reading`() {
        val opening = block(distance(400, 105))
        val row = result(opening.sets.single(), elapsed = 110, metresAtEnd = 400, secondsAtEnd = 104)
        assertEquals(CardioOutcome(block(distance(420, 110)), null, CardioChange.Increase(ProgressMeasure.DISTANCE, false)),
            finish(opening, ProgressMeasure.DISTANCE, results = listOf(row)))
        assertEquals(CardioOutcome(opening, null, CardioChange.Repeat),
            finish(opening, ProgressMeasure.DISTANCE, results = listOf(row.copy(secondsAtEnd = 106))))
    }

    @Test
    fun `case 18 open ended v25 run counts after reaching its end with overrun`() {
        val opening = block(time(1800))
        val row = result(opening.sets.single(), elapsed = 1830, secondsAtEnd = 1800)
        assertEquals(CardioOutcome(block(time(1890)), null, CardioChange.Increase(ProgressMeasure.TIME, false)),
            finish(opening, results = listOf(row)))
    }

    @Test
    fun `case 17 at Finish under the limit`() {
        assertEquals(CardioOutcome(block(distance(7875)), null, CardioChange.Increase(ProgressMeasure.DISTANCE, false)),
            finish(block(distance(7500)), ProgressMeasure.DISTANCE,
                recent = CardioHistory(longest = RecentMaxima(8050, 2900)), runCap = true))
    }

    @Test
    fun `case 17 capped at Finish unless the cap is off`() {
        val opening = block(distance(8000))
        val recent = CardioHistory(longest = RecentMaxima(8050, 2900))
        assertEquals(CardioOutcome(block(distance(8800)), null, CardioChange.Increase(ProgressMeasure.DISTANCE, true)),
            finish(opening, ProgressMeasure.DISTANCE, 25, recent = recent, runCap = true))
        assertEquals(CardioOutcome(block(distance(10000)), null, CardioChange.Increase(ProgressMeasure.DISTANCE, false)),
            finish(opening, ProgressMeasure.DISTANCE, 25, recent = recent))
    }

    @Test
    fun `case 17 the first finished session ends the seed`() {
        assertEquals(CardioOutcome(block(distance(8800)), null, CardioChange.Increase(ProgressMeasure.DISTANCE, true)),
            finish(block(distance(8000)), ProgressMeasure.DISTANCE, 25,
                recent = CardioHistory(seed = RecentMaxima(20000, null), planCreatedEpochDay = 990), runCap = true))
    }

    @Test
    fun `case 17 at run open the cap uses history or the unexpired seed`() {
        val recent = CardioHistory(longest = RecentMaxima(8050, 2900))
        val ctx = Progression.CardioContext(1000, ProgressMeasure.DISTANCE, 5)
        val intervals = CardioBlock(CardioActivity.ROAD_RUN, buildList {
            add(time(600, role = CardioRole.WARMUP))
            repeat(10) { index ->
                if (index > 0) add(distance(100, role = CardioRole.RECOVERY))
                add(distance(1000).copy(hard = true))
            }
        })
        val eightRounds = intervals.copy(sets = intervals.sets.take(16))
        for ((opening, expected) in listOf(block(distance(10000)) to block(distance(8800)), intervals to eightRounds)) {
            assertEquals(expected, Progression.cardioAtStart(opening, null, recent, null, ctx))
            assertSame(opening, Progression.cardioAtStart(opening, null, recent, null, ctx.copy(runCap = false)))
        }
        val seed = CardioHistory(seed = RecentMaxima(8050, null), planCreatedEpochDay = 100)
        val opening = block(distance(10000))
        assertEquals(block(distance(8800)), Progression.cardioAtStart(opening, null, seed, null, ctx.copy(todayEpochDay = 110)))
        assertSame(opening, Progression.cardioAtStart(opening, null, seed, null, ctx.copy(todayEpochDay = 130)))
    }

    @Test
    fun `a rule without a ladder level comes back unchanged`() {
        val rule = CardioRule(null)
        val opening = block(time(1800))
        val increased = finish(opening, rule = rule)
        assertSame(rule, increased.rule)
        assertEquals(CardioChange.Increase(ProgressMeasure.TIME, false), increased.change)
        val repeated = finish(opening, measure = null, rule = rule)
        assertSame(rule, repeated.rule)
        assertEquals(CardioChange.Repeat, repeated.change)
    }

    @Test
    fun `case 17 Finish repeats when grid rounding removes the whole increase`() {
        val opening = block(distance(400))
        assertEquals(CardioOutcome(opening, null, CardioChange.Repeat),
            finish(opening, ProgressMeasure.DISTANCE, 25, runCap = true))
    }

}
