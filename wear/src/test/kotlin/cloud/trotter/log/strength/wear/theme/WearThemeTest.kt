package cloud.trotter.log.strength.wear.theme

import androidx.compose.ui.graphics.Color
import cloud.trotter.log.strength.domain.theme.DayAccentColors
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The watch's day colors are the phone's day colors. Not "the same hexes brightened
 * however each surface felt like" — that was #150, where the watch lifted HSL
 * lightness by a flat 0.15 and left day D at 3.68:1 on the background while the
 * phone's contrast-pinned lift cleared AA. The rule now lives in `:domain` and
 * these are the assertions that stop the watch re-deriving one of its own.
 */
class WearThemeTest {

    @Test
    fun `every accent, on-accent and bright variant comes from the shared table`() {
        for (accentIndex in 0..6) {
            assertEquals(Color(DayAccentColors.hex(accentIndex)), dayAccent(accentIndex))
            assertEquals(Color(DayAccentColors.onAccentHex(accentIndex)), onDayAccent(accentIndex))
            assertEquals(Color(DayAccentColors.brightHex(accentIndex)), accentBright(accentIndex))
        }
    }

    @Test
    fun `a bright accent still carries its day's hue and cycles with it`() {
        val bright = (0..6).map { accentBright(it) }
        assertEquals(bright.size, bright.distinct().size, "two days share a bright accent")
        assertEquals(accentBright(0), accentBright(7))
    }

    @Test
    fun `the near-black surfaces are the same near-black the phone paints`() {
        // The bright accents are pinned against this background in :domain; if the
        // watch's Background drifts, that contrast floor stops meaning anything here.
        assertEquals(Color(0xFF0D0D0F), Background)
        assertEquals(Color(0xFFF2F2F0), TextPrimary)
    }
}
