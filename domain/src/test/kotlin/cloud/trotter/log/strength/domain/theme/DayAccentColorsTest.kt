package cloud.trotter.log.strength.domain.theme

import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

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

    @Test
    fun `the seven bright accents, in A-G order`() {
        // The one brightening rule's output, pinned so a change to the lift is a
        // deliberate edit to seven literals rather than a silent recolor of both
        // surfaces (#150).
        assertEquals(0xFFD2815DL, DayAccentColors.brightHex(0)) // A
        assertEquals(0xFF728F7CL, DayAccentColors.brightHex(1)) // B
        assertEquals(0xFFCCAC5BL, DayAccentColors.brightHex(2)) // C
        assertEquals(0xFF698792L, DayAccentColors.brightHex(3)) // D
        assertEquals(0xFF7C87A2L, DayAccentColors.brightHex(4)) // E
        assertEquals(0xFFAF808CL, DayAccentColors.brightHex(5)) // F
        assertEquals(0xFF9A9A71L, DayAccentColors.brightHex(6)) // G
    }

    @Test
    fun `every bright accent clears WCAG AA on the near-black background`() {
        // This floor is the whole reason the rule exists, and the reason it is one
        // rule: the watch's own lift left day D at 3.68:1 before #150.
        val background = 0xFF0D0D0FL
        for (dayIndex in 0..6) {
            val ratio = contrastRatio(DayAccentColors.brightHex(dayIndex), background)
            assertTrue(
                ratio >= 4.5,
                "Day index $dayIndex bright accent is $ratio on the background, below WCAG AA's 4.5:1",
            )
        }
    }

    @Test
    fun `the seven deep accents, in A-G order`() {
        assertEquals(0xFF8F3813L, DayAccentColors.deepHex(0)) // A
        assertEquals(0xFF284734L, DayAccentColors.deepHex(1)) // B
        assertEquals(0xFF896611L, DayAccentColors.deepHex(2)) // C
        assertEquals(0xFF1E3F4CL, DayAccentColors.deepHex(3)) // D
        assertEquals(0xFF323F5DL, DayAccentColors.deepHex(4)) // E
        assertEquals(0xFF693745L, DayAccentColors.deepHex(5)) // F
        assertEquals(0xFF535228L, DayAccentColors.deepHex(6)) // G
    }

    @Test
    fun `every deep accent clears WCAG AA on warm paper`() {
        val background = 0xFFF1EFEAL
        for (dayIndex in 0..6) {
            val ratio = contrastRatio(DayAccentColors.deepHex(dayIndex), background)
            assertTrue(
                ratio >= 4.5,
                "Day index $dayIndex deep accent is $ratio on warm paper, below WCAG AA's 4.5:1",
            )
        }
    }

    @Test
    fun `a deep accent stays distinct and cycles with its identity fill`() {
        val deep = (0..6).map { DayAccentColors.deepHex(it) }
        assertEquals(deep.size, deep.distinct().size)
        for (dayIndex in 0..6) assertNotEquals(DayAccentColors.hex(dayIndex), deep[dayIndex])
        assertEquals(DayAccentColors.deepHex(0), DayAccentColors.deepHex(7))
    }

    @Test
    fun `a bright accent is still its own day's hue, not a shared grey`() {
        val bright = (0..6).map { DayAccentColors.brightHex(it) }
        assertEquals(bright.size, bright.distinct().size)
        for (dayIndex in 0..6) assertNotEquals(DayAccentColors.hex(dayIndex), bright[dayIndex])
    }

    @Test
    fun `brightening cycles with the accent it derives from`() {
        assertEquals(DayAccentColors.brightHex(0), DayAccentColors.brightHex(7))
    }

    // WCAG 2.x relative luminance + contrast ratio (w3.org/TR/WCAG21/#dfn-contrast-ratio).
    private fun contrastRatio(a: Long, b: Long): Double {
        val la = relativeLuminance(a)
        val lb = relativeLuminance(b)
        return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)
    }

    private fun relativeLuminance(argb: Long): Double {
        fun linear(shift: Int): Double {
            val c = ((argb shr shift) and 0xFF).toDouble() / 255.0
            return if (c <= 0.03928) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * linear(16) + 0.7152 * linear(8) + 0.0722 * linear(0)
    }
}
