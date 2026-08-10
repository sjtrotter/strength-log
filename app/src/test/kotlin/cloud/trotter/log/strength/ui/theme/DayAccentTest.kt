package cloud.trotter.log.strength.ui.theme

import androidx.compose.ui.graphics.Color
import cloud.trotter.log.strength.domain.theme.DayAccentColors
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class DayAccentTest {

    @Test
    fun `light palette is pinned to the authored warm-paper tokens`() {
        assertEquals(Color(0xFFF1EFEA), LightBackground)
        assertEquals(Color(0xFFFAF9F6), LightSurface)
        assertEquals(Color(0xFFE9E6E0), LightSurface2)
        assertEquals(Color(0xFFE1DDD5), LightSurface3)
        assertEquals(Color(0xFFD8D4CC), LightBorder)
        assertEquals(Color(0xFFC4BFB5), LightBorderStrong)
        assertEquals(Color(0xFF1B1B1E), LightTextPrimary)
        assertEquals(Color(0xFF5C5C64), LightTextSecondary)
        assertEquals(Color(0xFF8B8B92), LightTextFaint)
    }

    @Test
    fun `indices 0-6 map to the seven day accents in order`() {
        // Extended from the spec's 4 to 7 (wear-companion §8.5 amendment); the
        // hexes are SSOT in :domain DayAccentColors and the phone now reads all
        // seven from there rather than a truncated local copy.
        assertEquals(Color(0xFFC1440E), dayAccent(0)) // A
        assertEquals(Color(0xFF2D5A3D), dayAccent(1)) // B
        assertEquals(Color(0xFFB8860B), dayAccent(2)) // C
        assertEquals(Color(0xFF1F4E5F), dayAccent(3)) // D
        assertEquals(Color(0xFF3C4E78), dayAccent(4)) // E
        assertEquals(Color(0xFF8B4356), dayAccent(5)) // F
        assertEquals(Color(0xFF6B6A2C), dayAccent(6)) // G
    }

    @Test
    fun `days E-G are distinct accents, not a cycle back to A-D`() {
        // Regression guard for the old modulo-4 cycle: day index 4/5/6 must be
        // their own colors (matching the watch), not A/B/C again.
        assertNotEquals(dayAccent(0), dayAccent(4))
        assertNotEquals(dayAccent(1), dayAccent(5))
        assertNotEquals(dayAccent(2), dayAccent(6))
    }

    @Test
    fun `day 8 cycles back to A's colors`() {
        assertEquals(dayAccent(0), dayAccent(7))
        assertEquals(onDayAccent(0, true), onDayAccent(7, true))
    }

    @Test
    fun `every accent and on-color pairing meets WCAG AA`() {
        for (isDark in listOf(true, false)) {
            for (dayIndex in 0..6) {
                val ratio = contrastRatio(dayAccent(dayIndex), onDayAccent(dayIndex, isDark))
                assertTrue(
                    ratio >= 4.5,
                    "Day index $dayIndex accent/on-color contrast is $ratio in ${if (isDark) "dark" else "light"}, below WCAG AA's 4.5:1",
                )
            }
        }
    }

    @Test
    fun `light done and error foregrounds clear AA on warm paper`() {
        assertEquals(Color(0xFF37774E), LightDone)
        assertEquals(Color(0xFFC2334D), LightError)
        assertTrue(contrastRatio(LightDone, LightBackground) >= 4.5)
        assertTrue(contrastRatio(LightError, LightBackground) >= 4.5)
    }

    @Test
    fun `every bright accent is legible as a mark on the near-black background`() {
        // docs/briefs/journal.md §0: the raw accents are identity fills and four
        // of the seven sit near 2:1 on Background — unusable for a chart line or
        // the cascade scrim's numeral. The lift toward TextPrimary clears AA text
        // contrast (well past the 3:1 a graphic mark needs). Measured from the
        // phone's own colors even though the rule is now in :domain — this floor
        // is what the phone promises its own screens.
        for (dayIndex in 0..6) {
            val ratio = contrastRatio(accentBright(dayIndex), DarkBackground)
            assertTrue(
                ratio >= 4.5,
                "Day index $dayIndex bright accent contrast on Background is $ratio, below WCAG AA's 4.5:1",
            )
        }
    }

    @Test
    fun `the phone brightens with the shared rule, not one of its own`() {
        // #150: the phone and the watch each derived their own bright variant, so
        // the same day rendered two colors. Both read DayAccentColors now, and this
        // is the guard against the phone quietly growing a second rule again.
        for (dayIndex in 0..6) {
            assertEquals(Color(DayAccentColors.brightHex(dayIndex)), accentBright(dayIndex))
        }
    }

    @Test
    fun `a bright accent is still its own day's hue, not a shared grey`() {
        val bright = (0..6).map { accentBright(it) }
        assertEquals(bright.size, bright.distinct().size)
        for (dayIndex in 0..6) assertNotEquals(dayAccent(dayIndex), accentBright(dayIndex))
        assertEquals(accentBright(0), accentBright(7), "brightening cycles with the accent it derives from")
    }

    @Test
    fun `error and on-error pairing is the recolored crimson and meets WCAG AA`() {
        // Design-pass recolor (docs/design-handoff/tokens/colors.css): Error moved
        // off the M3 default 0xFFB3261E to a cooler crimson so it never reads as
        // Day A's terracotta. TextPrimary on it is ~4.84:1 — pin both the exact
        // hex and the contrast floor so neither regresses silently.
        assertEquals(Color(0xFFC2334D), DarkError)
        val ratio = contrastRatio(DarkError, DarkTextPrimary)
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
