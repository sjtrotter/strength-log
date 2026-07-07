package io.github.sjtrotter.strengthlog.ui.theme

import androidx.compose.ui.graphics.Color
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DayAccentTest {

    @Test
    fun `indices 0-3 map to the four spec accents in order`() {
        assertEquals(Color(0xFFC1440E), dayAccent(0)) // A
        assertEquals(Color(0xFF2D5A3D), dayAccent(1)) // B
        assertEquals(Color(0xFFB8860B), dayAccent(2)) // C
        assertEquals(Color(0xFF1F4E5F), dayAccent(3)) // D
    }

    @Test
    fun `day 5 cycles back to A's colors`() {
        assertEquals(dayAccent(0), dayAccent(4))
        assertEquals(onDayAccent(0), onDayAccent(4))
    }

    @Test
    fun `every accent and on-color pairing meets WCAG AA`() {
        for (dayIndex in 0..3) {
            val ratio = contrastRatio(dayAccent(dayIndex), onDayAccent(dayIndex))
            assertTrue(
                ratio >= 4.5,
                "Day index $dayIndex accent/on-color contrast is $ratio, below WCAG AA's 4.5:1",
            )
        }
    }

    @Test
    fun `error and on-error pairing is the recolored crimson and meets WCAG AA`() {
        // Design-pass recolor (docs/design-handoff/tokens/colors.css): Error moved
        // off the M3 default 0xFFB3261E to a cooler crimson so it never reads as
        // Day A's terracotta. TextPrimary on it is ~4.84:1 — pin both the exact
        // hex and the contrast floor so neither regresses silently.
        assertEquals(Color(0xFFC2334D), Error)
        val ratio = contrastRatio(Error, TextPrimary)
        assertTrue(ratio >= 4.5, "Error/on-error contrast is $ratio, below WCAG AA's 4.5:1")
    }

    // WCAG 2.x relative luminance + contrast ratio (w3.org/TR/WCAG21/#dfn-contrast-ratio).
    private fun contrastRatio(a: Color, b: Color): Double {
        val la = relativeLuminance(a)
        val lb = relativeLuminance(b)
        return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)
    }

    private fun relativeLuminance(color: Color): Double {
        fun linear(channel: Float): Double {
            val c = channel.toDouble()
            return if (c <= 0.03928) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * linear(color.red) + 0.7152 * linear(color.green) + 0.0722 * linear(color.blue)
    }
}
