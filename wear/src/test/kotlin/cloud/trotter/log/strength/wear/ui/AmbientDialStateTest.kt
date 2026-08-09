package cloud.trotter.log.strength.wear.ui

import androidx.compose.ui.graphics.Color
import cloud.trotter.log.strength.domain.sync.WatchDay
import cloud.trotter.log.strength.domain.sync.WatchExercise
import cloud.trotter.log.strength.domain.sync.WatchSet
import cloud.trotter.log.strength.domain.sync.WatchSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

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
        assertEquals(12f / 21f, state.dayProgress)
    }

    @Test
    fun `a running rest uses cadence safe copy and moves the time down a band`() {
        val state = ambientDialState(day, timeText = "10:42", restRemainingSeconds = 84)
        assertEquals("RESTING", state.centerText)
        assertEquals("10:42", state.bottomText)
        assertFalse(state.centerText.contains(':'))
        assertFalse(state.centerText.any(Char::isDigit))
    }

    @Test
    fun `burn in shift is deterministic bounded and changes across ticks`() {
        val cycle = (0..8).map(::ambientPixelOffset)
        assertEquals(ambientPixelOffset(3), ambientPixelOffset(3))
        assertTrue(cycle.toSet().size > 1)
        assertTrue(cycle.all { it.x in -2..2 && it.y in -2..2 })
        assertEquals(ambientPixelOffset(0), ambientPixelOffset(9))
    }

    @Test
    fun `low bit palette contains only black and white`() {
        val palette = ambientPalette(lowBit = true)
        assertEquals(Color.Black, palette.background)
        assertEquals(Color.White, palette.primary)
        assertEquals(palette.primary, palette.secondary)
    }

    @Test
    fun `an expired or unknown rest leaves the dial to the clock`() {
        assertEquals("10:42", ambientDialState(day, "10:42", restRemainingSeconds = 0).centerText)
    }

    @Test
    fun `a day with no program has nothing to count`() {
        val state = ambientDialState(snapshot(), timeText = "10:42")
        assertEquals("DAY A", state.topText)
        assertEquals(0f, state.dayProgress)
        assertEquals("10:42", state.centerText)
    }
}
