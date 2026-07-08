package io.github.sjtrotter.strengthlog.domain.theme

import kotlin.test.Test
import kotlin.test.assertEquals

/** SSOT pin — app's DayAccentTest and any :wear equivalent both derive from these. */
class DayAccentColorsTest {

    @Test
    fun `the four pinned day-accent hexes, in A-D order`() {
        assertEquals(0xFFC1440EL, DayAccentColors.hex(0))
        assertEquals(0xFF2D5A3DL, DayAccentColors.hex(1))
        assertEquals(0xFFB8860BL, DayAccentColors.hex(2))
        assertEquals(0xFF1F4E5FL, DayAccentColors.hex(3))
    }

    @Test
    fun `cycles back to A past the fourth day`() {
        assertEquals(DayAccentColors.hex(0), DayAccentColors.hex(4))
        assertEquals(DayAccentColors.hex(1), DayAccentColors.hex(5))
    }
}
