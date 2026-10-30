package cloud.trotter.log.strength.wear.theme

import androidx.compose.ui.graphics.Color
import cloud.trotter.log.strength.domain.theme.DayAccentColors
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The watch's day colors are the phone's day colors. Both read the shared,
 * contrast-pinned brightening rule in `:domain`; the watch must not derive one of
 * its own.
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
