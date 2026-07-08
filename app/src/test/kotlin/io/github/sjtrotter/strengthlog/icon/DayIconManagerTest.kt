package io.github.sjtrotter.strengthlog.icon

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DayIconManagerTest {

    // --- dayIndexForIcon: rotation-day letter -> 0-based icon index ------------

    @Test
    fun `letters A through G map to indices 0 through 6`() {
        ('A'..'G').forEachIndexed { index, letter ->
            assertEquals(index, dayIndexForIcon(letter.toString()))
        }
    }

    @Test
    fun `null id maps to null`() {
        assertNull(dayIndexForIcon(null))
    }

    @Test
    fun `a letter past G is out of icon range and maps to null`() {
        // The wizard caps programs at 6 days (WizardAnswers.daysPerWeek 2..6), so this
        // guards against a day id the shipped icon set doesn't cover, not a real program.
        assertNull(dayIndexForIcon("H"))
        assertNull(dayIndexForIcon("Z"))
    }

    @Test
    fun `a malformed id maps to null`() {
        assertNull(dayIndexForIcon(""))
        assertNull(dayIndexForIcon("AB"))
    }

    // --- aliasStatesFor: exactly one alias enabled per day index ----------------

    @Test
    fun `day index 0 enables only Launcher_DayA`() {
        val states = aliasStatesFor(0)

        assertEquals(7, states.size)
        assertEquals(
            listOf("Launcher_DayA", "Launcher_DayB", "Launcher_DayC", "Launcher_DayD",
                "Launcher_DayE", "Launcher_DayF", "Launcher_DayG"),
            states.map { it.alias },
        )
        assertEquals(listOf(true, false, false, false, false, false, false), states.map { it.enabled })
    }

    @Test
    fun `day index 2 enables only Launcher_DayC`() {
        val states = aliasStatesFor(2)

        assertEquals("Launcher_DayC", states.single { it.enabled }.alias)
        assertEquals(6, states.count { !it.enabled })
    }

    @Test
    fun `day index 6 enables only Launcher_DayG, the last alias`() {
        val states = aliasStatesFor(6)

        assertEquals("Launcher_DayG", states.single { it.enabled }.alias)
    }
}
