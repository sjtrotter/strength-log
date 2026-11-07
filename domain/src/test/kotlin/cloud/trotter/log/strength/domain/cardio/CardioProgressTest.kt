package cloud.trotter.log.strength.domain.cardio

import cloud.trotter.log.strength.domain.model.CardioActivity
import cloud.trotter.log.strength.domain.model.CardioBlock
import cloud.trotter.log.strength.domain.model.CardioProgress
import cloud.trotter.log.strength.domain.model.CardioRole
import cloud.trotter.log.strength.domain.model.CardioSetSpec
import cloud.trotter.log.strength.domain.model.Measure
import cloud.trotter.log.strength.domain.model.ProgramDay
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CardioProgressTest {
    private val opening = CardioBlock(CardioActivity.ROAD_RUN,
        listOf(CardioSetSpec(CardioRole.WORK, Measure.Time(1200))))
    private val working = opening.copy(sets = listOf(CardioSetSpec(CardioRole.WORK, Measure.Time(1260))))
    private val day = ProgramDay("A", "Run", "", emptyList(), cardioBlock = opening,
        cardioProgress = CardioProgress(working = working))

    @Test fun `Progress runs working values and Plan parks them`() {
        assertEquals(working, day.cardioToRun)
        val parked = day.copy(cardioProgress = day.cardioProgress.copy(keepToPlan = true))
        assertEquals(opening, parked.cardioToRun)
        assertEquals(working, parked.cardioProgress.working)
        assertEquals(working, parked.copy(cardioProgress = parked.cardioProgress.copy(keepToPlan = false)).cardioToRun)
    }

    @Test fun `working values require the same activity and ordered roles`() {
        val incompatible = listOf(
            working.copy(activity = CardioActivity.ROWER),
            working.copy(sets = working.sets + CardioSetSpec(CardioRole.RECOVERY, Measure.Time(60))),
            working.copy(sets = listOf(CardioSetSpec(CardioRole.WARMUP, Measure.Time(1260)))),
        )
        incompatible.forEach {
            assertEquals(opening, day.copy(cardioProgress = CardioProgress(working = it)).cardioToRun)
        }
        assertEquals(opening, day.copy(cardioProgress = CardioProgress()).cardioToRun)
        assertNull(day.copy(cardioBlock = null).cardioToRun)
    }
}
