package cloud.trotter.log.strength.wear.ui

import cloud.trotter.log.strength.domain.sync.WatchDay
import cloud.trotter.log.strength.domain.sync.WatchExercise
import cloud.trotter.log.strength.domain.sync.WatchSet
import cloud.trotter.log.strength.domain.sync.WatchSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** What ambient mode says, and what it deliberately doesn't (brief §7). */
class AmbientDialStateTest {

    private fun exercise(id: Long, doneFlags: List<Boolean>) = WatchExercise(
        programExerciseId = id,
        slot = "main",
        name = "Ex$id",
        goal = 100.0,
        perHand = false,
        supersetPartnerName = null,
        sets = doneFlags.map { WatchSet(100.0, 5, "WORK", done = it) },
        ssSets = emptyList(),
    )

    private fun snapshot(vararg exercises: WatchExercise) = WatchSnapshot(
        revision = 1L,
        suggestedDayId = "A",
        day = WatchDay("A", "Day A", accentIndex = 0, exercises = exercises.toList()),
        unit = "lb",
    )

    private val day = snapshot(
        exercise(1L, List(12) { true }),
        exercise(2L, List(9) { false }),
    )

    @Test
    fun `at rest it is the day, the count and the time`() {
        val state = ambientDialState(day, timeText = "10:42")
        assertEquals("DAY A · 12/21", state.topText)
        assertEquals("10:42", state.centerText)
        assertNull(state.bottomText)
        assertNull(state.restFraction)
        assertEquals(12f / 21f, state.dayProgress)
    }

    @Test
    fun `a running rest takes the centre and moves the time down a band`() {
        val state = ambientDialState(day, timeText = "10:42", restRemainingSeconds = 84, restTotalSeconds = 150)
        assertEquals("1:24", state.centerText)
        assertEquals("10:42", state.bottomText)
        assertEquals(84f / 150f, state.restFraction!!)
    }

    @Test
    fun `an expired or unknown rest leaves the dial to the clock`() {
        assertEquals("10:42", ambientDialState(day, "10:42", restRemainingSeconds = 0, restTotalSeconds = 150).centerText)
        assertNull(ambientDialState(day, "10:42", restRemainingSeconds = 84, restTotalSeconds = 0).restFraction)
    }

    @Test
    fun `a day with no program has nothing to count`() {
        val state = ambientDialState(snapshot(), timeText = "10:42")
        assertEquals("DAY A", state.topText)
        assertEquals(0f, state.dayProgress)
        assertEquals("10:42", state.centerText)
    }
}
