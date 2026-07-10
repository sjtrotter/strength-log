package io.github.sjtrotter.strengthlog.domain.theme

import kotlin.test.Test
import kotlin.test.assertEquals

/** SSOT pin — app's DayAccentTest and any :wear equivalent both derive from these. */
class DayAccentColorsTest {

    @Test
    fun `the seven pinned day-accent hexes, in A-G order`() {
        assertEquals(0xFFC1440EL, DayAccentColors.hex(0)) // A
        assertEquals(0xFF2D5A3DL, DayAccentColors.hex(1)) // B
        assertEquals(0xFFB8860BL, DayAccentColors.hex(2)) // C
        assertEquals(0xFF1F4E5FL, DayAccentColors.hex(3)) // D
        assertEquals(0xFF3C4E78L, DayAccentColors.hex(4)) // E
        assertEquals(0xFF8B4356L, DayAccentColors.hex(5)) // F
        assertEquals(0xFF6B6A2CL, DayAccentColors.hex(6)) // G
    }

    @Test
    fun `cycles back to A past the seventh day`() {
        assertEquals(DayAccentColors.hex(0), DayAccentColors.hex(7))
        assertEquals(DayAccentColors.hex(1), DayAccentColors.hex(8))
    }

    @Test
    fun `only day C gets the dark on-accent, everyone else gets the light one`() {
        val darkText = 0xFF0D0D0FL
        val lightText = 0xFFF2F2F0L
        assertEquals(lightText, DayAccentColors.onAccentHex(0)) // A
        assertEquals(lightText, DayAccentColors.onAccentHex(1)) // B
        assertEquals(darkText, DayAccentColors.onAccentHex(2)) // C
        assertEquals(lightText, DayAccentColors.onAccentHex(3)) // D
        assertEquals(lightText, DayAccentColors.onAccentHex(4)) // E
        assertEquals(lightText, DayAccentColors.onAccentHex(5)) // F
        assertEquals(lightText, DayAccentColors.onAccentHex(6)) // G
    }

    @Test
    fun `on-accent lookup wraps at seven too`() {
        assertEquals(DayAccentColors.onAccentHex(2), DayAccentColors.onAccentHex(9)) // 9 mod 7 == 2 == C
    }
}
