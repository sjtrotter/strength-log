package cloud.trotter.log.strength.domain.generator

import cloud.trotter.log.strength.domain.model.CardioSuggestion
import kotlin.test.Test
import kotlin.test.assertEquals

class CardioIntervalsTest {

    private val easy = CardioSuggestion(label = "Easy Zone 2", detail = "", hard = false)
    private val hard = CardioSuggestion(label = "Hard cardio — intervals", detail = "", hard = true)

    // These are the §11-class pinned vectors. A diff changing them is wrong unless the spec changes.
    @Test
    fun `plans have the pinned vectors and totals`() {
        val easyPlan = CardioIntervals.plan(easy, fiveK = false)
        val hardPlan = CardioIntervals.plan(hard, fiveK = false)
        val hardFiveKPlan = CardioIntervals.plan(hard, fiveK = true)

        assertEquals(listOf(1500), easyPlan.steps.map { it.seconds })
        assertEquals(listOf(1200), hardPlan.steps.map { it.seconds })
        assertEquals(
            listOf(300, 120, 120, 120, 120, 120, 120, 120, 120, 120, 120),
            hardFiveKPlan.steps.map { it.seconds },
        )
        assertEquals(1500, easyPlan.totalSeconds)
        assertEquals(1200, hardPlan.totalSeconds)
        assertEquals(1500, hardFiveKPlan.totalSeconds)
    }

    @Test
    fun `step hard flags are pinned`() {
        assertEquals(listOf(false), CardioIntervals.plan(easy, fiveK = false).steps.map { it.hard })
        assertEquals(listOf(true), CardioIntervals.plan(hard, fiveK = false).steps.map { it.hard })
        assertEquals(
            listOf(false, true, false, true, false, true, false, true, false, true, false),
            CardioIntervals.plan(hard, fiveK = true).steps.map { it.hard },
        )
    }

    @Test
    fun `every plan's ordered labels are pinned`() {
        assertEquals(listOf("EASY"), CardioIntervals.plan(easy, fiveK = false).steps.map { it.label })
        assertEquals(listOf("TEMPO"), CardioIntervals.plan(hard, fiveK = false).steps.map { it.label })
        assertEquals(
            listOf(
                "WARM-UP",
                "HARD", "EASY", "HARD", "EASY", "HARD",
                "EASY", "HARD", "EASY", "HARD", "EASY",
            ),
            CardioIntervals.plan(hard, fiveK = true).steps.map { it.label },
        )
    }
}
