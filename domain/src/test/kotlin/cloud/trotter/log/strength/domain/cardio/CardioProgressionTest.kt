package cloud.trotter.log.strength.domain.cardio

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
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class CardioProgressionTest {
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
    ) = CardioSetResult(spec, status, elapsed, metres, metres?.let { DistanceSource.MANUAL })

    private fun finished(block: CardioBlock) = block.sets.map { result(it) }

    private fun next(block: CardioBlock, measure: ProgressMeasure, percent: Int) =
        CardioProgression.nextTime(block, block.activity, finished(block), measure, percent)

    @Test
    fun `distance plus five percent of a 3 mi run`() {
        val opening = block(distance(4828))
        val offered = block(distance(5069))
        assertEquals(offered, CardioProgression.increased(opening, ProgressMeasure.DISTANCE, 5))
        assertEquals(CardioNextTime.Offer(offered, opening, false, CardioIncrease.Distance(241)),
            next(opening, ProgressMeasure.DISTANCE, 5))
    }

    @Test
    fun `time plus five percent of a 20 minute run`() {
        val opening = block(time(1200))
        val offered = block(time(1260))
        assertEquals(offered, CardioProgression.increased(opening, ProgressMeasure.TIME, 5))
        assertEquals(CardioNextTime.Offer(offered, opening, false, CardioIncrease.Time(60)),
            next(opening, ProgressMeasure.TIME, 5))
    }

    @Test
    fun `pace one percent faster rounds to the second`() {
        val opening = block(distance(1600, 480))
        val offered = block(distance(1600, 475))
        assertEquals(offered, CardioProgression.increased(opening, ProgressMeasure.PACE, 1))
        assertEquals(CardioNextTime.Offer(offered, opening, false, CardioIncrease.Faster(5)),
            next(opening, ProgressMeasure.PACE, 1))
        assertEquals(block(distance(400, 104)),
            CardioProgression.increased(block(distance(400, 105)), ProgressMeasure.PACE, 1))
    }

    @Test
    fun `minimum one unit`() {
        assertEquals(block(time(9)), CardioProgression.increased(block(time(8)), ProgressMeasure.TIME, 5))
        assertEquals(block(distance(11)), CardioProgression.increased(block(distance(10)), ProgressMeasure.DISTANCE, 5))
    }

    @Test
    fun `distance keeps the pace of a time aim`() {
        assertEquals(block(distance(420, 110)),
            CardioProgression.increased(block(distance(400, 105)), ProgressMeasure.DISTANCE, 5))
    }

    @Test
    fun `distance keeps the pace of a time end`() {
        assertEquals(block(time(1890, 5069)),
            CardioProgression.increased(block(time(1800, 4828)), ProgressMeasure.DISTANCE, 5))
    }

    @Test
    fun `time keeps the pace of a distance aim`() {
        assertEquals(block(time(1890, 5069)),
            CardioProgression.increased(block(time(1800, 4828)), ProgressMeasure.TIME, 5))
    }

    @Test
    fun `pace with a time end holds the end and increases the distance aim`() {
        val opening = block(time(1800, 4828))
        val offered = block(time(1800, 4876))
        assertEquals(offered, CardioProgression.increased(opening, ProgressMeasure.PACE, 1))
        assertEquals(CardioNextTime.Offer(offered, opening, false, CardioIncrease.Distance(48)),
            next(opening, ProgressMeasure.PACE, 1))
    }

    @Test
    fun `intervals keep their rounds and their easy sets`() {
        val opening = intervals()
        val increased = CardioProgression.increased(opening, ProgressMeasure.TIME, 5)
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
            val increased = CardioProgression.increased(opening, measure, 5)
            for (index in listOf(0, 2, 3)) assertSame(opening.sets[index], increased.sets[index])
            assertEquals(opening.sets.size, increased.sets.size)
        }
    }

    @Test
    fun `out of range leaves the block unchanged`() {
        val opening = block(distance(199_000))
        assertSame(opening, CardioProgression.increased(opening, ProgressMeasure.DISTANCE, 5))
        assertNull(CardioProgression.offer(opening, finished(opening), ProgressMeasure.DISTANCE, 5))
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
            assertSame(opening, CardioProgression.increased(opening, measure, 5))
            assertNull(CardioProgression.offer(opening, finished(opening), measure, 5))
        }
    }

    @Test
    fun `pace preservation does not overflow integer multiplication`() {
        assertEquals(block(distance(200_000, 86_400)),
            CardioProgression.increased(block(distance(160_000, 69_120)), ProgressMeasure.DISTANCE, 25))
        assertEquals(block(time(86_400, 200_000)),
            CardioProgression.increased(block(time(69_120, 160_000)), ProgressMeasure.TIME, 25))
    }

    @Test
    fun `percent is clamped`() {
        for ((opening, measure) in listOf(block(time(1200)) to ProgressMeasure.TIME,
            block(distance(400)) to ProgressMeasure.DISTANCE, block(distance(1600, 480)) to ProgressMeasure.PACE)) {
            assertEquals(CardioProgression.increased(opening, measure, 1), CardioProgression.increased(opening, measure, 0))
            assertEquals(CardioProgression.increased(opening, measure, 25), CardioProgression.increased(opening, measure, 90))
        }
        assertEquals(block(time(1212)), CardioProgression.increased(block(time(1200)), ProgressMeasure.TIME, 0))
        assertEquals(block(time(1500)), CardioProgression.increased(block(time(1200)), ProgressMeasure.TIME, 90))
    }

    @Test
    fun `default percentages are five for distance and time and one for pace`() {
        assertEquals(1, CardioProgression.MIN_PERCENT)
        assertEquals(25, CardioProgression.MAX_PERCENT)
        assertEquals(5, CardioProgression.defaultPercent(ProgressMeasure.DISTANCE))
        assertEquals(5, CardioProgression.defaultPercent(ProgressMeasure.TIME))
        assertEquals(1, CardioProgression.defaultPercent(ProgressMeasure.PACE))
    }

    @Test
    fun `available measures require support in every work set`() {
        assertEquals(setOf(ProgressMeasure.TIME), CardioProgression.available(block(time(1800))))
        assertEquals(setOf(ProgressMeasure.TIME, ProgressMeasure.DISTANCE, ProgressMeasure.PACE),
            CardioProgression.available(block(time(1800, 4828))))
        assertEquals(setOf(ProgressMeasure.DISTANCE), CardioProgression.available(block(distance(4828))))
        assertEquals(setOf(ProgressMeasure.DISTANCE, ProgressMeasure.PACE),
            CardioProgression.available(block(distance(400, 105))))
        assertEquals(emptySet(), CardioProgression.available(block(time(300, role = CardioRole.WARMUP))))
        assertEquals(setOf(ProgressMeasure.TIME), CardioProgression.available(block(time(120, 400), time(120))))
        assertEquals(emptySet(), CardioProgression.available(block(time(120), distance(400))))
        assertEquals(setOf(ProgressMeasure.DISTANCE, ProgressMeasure.PACE),
            CardioProgression.available(block(time(120, 400), distance(400, 105))))
    }

    @Test
    fun `default measure follows the end and never opts into pace`() {
        assertEquals(ProgressMeasure.DISTANCE, CardioProgression.defaultMeasure(block(distance(4828))))
        assertEquals(ProgressMeasure.TIME, CardioProgression.defaultMeasure(block(time(1800))))
        assertEquals(ProgressMeasure.TIME, CardioProgression.defaultMeasure(intervals()))
        assertEquals(ProgressMeasure.TIME, CardioProgression.defaultMeasure(block(time(1800, 4828))))
        assertEquals(ProgressMeasure.DISTANCE, CardioProgression.defaultMeasure(block(distance(400, 105))))
        assertEquals(ProgressMeasure.DISTANCE, CardioProgression.defaultMeasure(block(time(120, 400), distance(400, 105))))
        assertNull(CardioProgression.defaultMeasure(block(time(300, role = CardioRole.WARMUP))))
        assertNull(CardioProgression.defaultMeasure(block(time(120), distance(400))))
    }

    @Test
    fun `resolve keeps a supported choice and falls back after an edit`() {
        assertEquals(ProgressMeasure.TIME, CardioProgression.resolve(block(time(1800)), ProgressMeasure.PACE))
        assertEquals(ProgressMeasure.PACE, CardioProgression.resolve(block(time(1800, 4828)), ProgressMeasure.PACE))
        assertEquals(ProgressMeasure.DISTANCE, CardioProgression.resolve(block(distance(400)), null))
        assertNull(CardioProgression.resolve(block(time(300, role = CardioRole.WARMUP)), ProgressMeasure.TIME))
    }

    @Test
    fun `unsupported measures leave the block unchanged and offer nothing`() {
        val opening = block(time(1800))
        for (measure in listOf(ProgressMeasure.DISTANCE, ProgressMeasure.PACE)) {
            assertSame(opening, CardioProgression.increased(opening, measure, 5))
            assertNull(CardioProgression.offer(opening, finished(opening), measure, 5))
        }
    }

    @Test
    fun `open ended time requires the planned duration and finished status`() {
        val opening = block(time(1800))
        val spec = opening.sets.single()
        assertFalse(CardioProgression.fullSuccess(opening, listOf(result(spec, elapsed = 1795))))
        assertTrue(CardioProgression.fullSuccess(opening, listOf(result(spec, elapsed = 1800))))
        assertTrue(CardioProgression.fullSuccess(opening, listOf(result(spec, elapsed = 2400))))
        assertFalse(CardioProgression.fullSuccess(opening,
            listOf(result(spec, CardioSetStatus.SHORTENED, elapsed = 1800))))
    }

    @Test
    fun `distance aim requires a recorded distance meeting the aim`() {
        val opening = block(time(1800, 4828))
        val spec = opening.sets.single()
        assertFalse(CardioProgression.fullSuccess(opening, listOf(result(spec, metres = null))))
        assertFalse(CardioProgression.fullSuccess(opening, listOf(result(spec, metres = 4800))))
        assertTrue(CardioProgression.fullSuccess(opening, listOf(result(spec, metres = 4828))))
        assertTrue(CardioProgression.fullSuccess(opening, listOf(result(spec, metres = 5000))))
    }

    @Test
    fun `time aim requires finishing by the aim`() {
        val opening = block(distance(400, 105))
        val spec = opening.sets.single()
        assertFalse(CardioProgression.fullSuccess(opening, listOf(result(spec, elapsed = 106, metres = 400))))
        assertTrue(CardioProgression.fullSuccess(opening, listOf(result(spec, elapsed = 105, metres = 400))))
        assertTrue(CardioProgression.fullSuccess(opening, listOf(result(spec, elapsed = 104, metres = 400))))
    }

    @Test
    fun `finished status meets deadline and distance ends without rechecking actuals`() {
        val opening = block(time(120), distance(400))
        assertTrue(CardioProgression.fullSuccess(opening, opening.sets.map { result(it, elapsed = 0, metres = null) }))
    }

    @Test
    fun `interval success judges every work set and ignores skipped warmup`() {
        val opening = intervals()
        val results = finished(opening)
        assertTrue(CardioProgression.fullSuccess(opening, results))
        assertFalse(CardioProgression.fullSuccess(opening, results.mapIndexed { index, result ->
            if (index == 3) result.copy(status = CardioSetStatus.SHORTENED) else result
        }))
        assertTrue(CardioProgression.fullSuccess(opening, results.mapIndexed { index, result ->
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
        assertTrue(CardioProgression.fullSuccess(opening, results))
        val warmup = block(time(300, role = CardioRole.WARMUP))
        assertFalse(CardioProgression.fullSuccess(warmup, finished(warmup)))
    }

    @Test
    fun `success requires the exact block size specs and order`() {
        val opening = intervals()
        val results = finished(opening)
        assertFalse(CardioProgression.fullSuccess(opening, results.dropLast(1)))
        assertFalse(CardioProgression.fullSuccess(opening, results + result(time(120))))
        assertFalse(CardioProgression.fullSuccess(opening, results.mapIndexed { index, result ->
            if (index == 1) result.copy(spec = time(121)) else result
        }))
        assertFalse(CardioProgression.fullSuccess(opening, results.reversed()))
    }

    @Test
    fun `offer after a miss is null and nothing drops`() {
        val opening = block(time(1800))
        val results = listOf(result(opening.sets.single(), elapsed = 1795))
        assertNull(CardioProgression.offer(opening, results, ProgressMeasure.TIME, 5))
        assertEquals(block(time(1800)), opening)
    }

    @Test
    fun `next time recognizes the opening and the applied offer from the same results`() {
        val opening = block(distance(4828))
        val offered = block(distance(5069))
        val results = finished(opening)
        val pending = CardioProgression.nextTime(opening, opening.activity, results, ProgressMeasure.DISTANCE, 5)
        val applied = CardioProgression.nextTime(offered, opening.activity, results, ProgressMeasure.DISTANCE, 5)
        assertEquals(CardioNextTime.Offer(offered, opening, false, CardioIncrease.Distance(241)), pending)
        assertEquals(assertIs<CardioNextTime.Offer>(pending).copy(applied = true), applied)
    }

    @Test
    fun `next time repeats the opening after a miss`() {
        val opening = block(time(1800))
        val results = listOf(result(opening.sets.single(), CardioSetStatus.SHORTENED))
        assertEquals(CardioNextTime.Repeat,
            CardioProgression.nextTime(opening, opening.activity, results, ProgressMeasure.TIME, 5))
        assertNull(CardioProgression.nextTime(block(time(1900)), opening.activity, results, ProgressMeasure.TIME, 5))
    }

    @Test
    fun `next time hides offers after edits or an activity change`() {
        val opening = block(distance(4828))
        val results = finished(opening)
        assertNull(CardioProgression.nextTime(block(distance(5000)), opening.activity, results, ProgressMeasure.DISTANCE, 5))
        assertNull(CardioProgression.nextTime(opening, CardioActivity.TRAIL_RUN, results, ProgressMeasure.DISTANCE, 5))
        assertNull(CardioProgression.nextTime(opening, null, results, ProgressMeasure.DISTANCE, 5))
    }

    @Test
    fun `next time needs a measure and nonempty history`() {
        val opening = block(distance(4828))
        assertNull(CardioProgression.nextTime(opening, opening.activity, finished(opening), null, 5))
        assertNull(CardioProgression.nextTime(opening, opening.activity, emptyList(), ProgressMeasure.DISTANCE, 5))
        assertNull(CardioProgression.nextTime(opening, opening.activity, null, ProgressMeasure.DISTANCE, 5))
    }

    @Test
    fun `next time reports the first work change after warmup`() {
        val opening = block(time(300, role = CardioRole.WARMUP), time(120), time(240))
        val next = assertIs<CardioNextTime.Offer>(next(opening, ProgressMeasure.TIME, 5))
        assertEquals(CardioIncrease.Time(6), next.change)
        assertEquals(block(time(300, role = CardioRole.WARMUP), time(126), time(252)), next.offered)
    }

    @Test
    fun `next time repeats when success cannot produce a supported increase`() {
        val limit = block(distance(199_000))
        assertEquals(CardioNextTime.Repeat, next(limit, ProgressMeasure.DISTANCE, 5))
        assertEquals(CardioNextTime.Repeat, next(block(time(1800)), ProgressMeasure.PACE, 1))
    }
}
