package cloud.trotter.log.strength.wear.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Density
import cloud.trotter.log.strength.wear.ui.DialGeometry
import cloud.trotter.log.strength.wear.ui.DialTextRole
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Acceptance 1 of dial v2: nothing on the dial renders below 12sp. The v1 scale
 * missed that by a factor of two because the brief's 384px canvas maps to
 * *physical* pixels on a Pixel Watch — so the case that decides the scale is a
 * 192dp face at density 2.0, and it is a test rather than a guideline.
 */
class DialTypeTest {

    private val pixelWatch = Density(density = 2f)
    private val referenceScale = DialGeometry.scale(384f)

    @Test
    fun `every step the lifter reads renders at 12sp or more on a 192dp face`() {
        // CYCLE_LABEL is exempt: an owner-waived verdict from the on-wrist round
        // (issue #152). The cycle ring's colour is the identification and the
        // word only names it, so the floor that governs everything the lifter
        // actually *reads* does not apply to it.
        val type = dialTypography(referenceScale, pixelWatch)
        DialTextRole.entries.filter { it != DialTextRole.CYCLE_LABEL }.forEach { role ->
            val sp = type.style(role).fontSize.value
            assertTrue(sp >= 12f, "$role renders at ${sp}sp")
        }
    }

    @Test
    fun `each step lands on its sp step, not between two`() {
        val type = dialTypography(referenceScale, pixelWatch)
        assertEquals(36f, type.numeralLarge.fontSize.value, TOLERANCE)
        assertEquals(30f, type.numeral.fontSize.value, TOLERANCE)
        assertEquals(18f, type.discLabel.fontSize.value, TOLERANCE)
        assertEquals(15f, type.discLabelSmall.fontSize.value, TOLERANCE)
        assertEquals(13f, type.band.fontSize.value, TOLERANCE)
        assertEquals(12f, type.bandSecondary.fontSize.value, TOLERANCE)
        assertEquals(9f, type.cycleLabel.fontSize.value, TOLERANCE)
    }

    @Test
    fun `type grows with the face, so a bigger watch never shrinks below the floor`() {
        val big = dialTypography(DialGeometry.scale(454f), pixelWatch)
        val reference = dialTypography(referenceScale, pixelWatch)
        DialTextRole.entries.forEach { role ->
            assertTrue(
                big.style(role).fontSize.value > reference.style(role).fontSize.value,
                "$role did not scale up",
            )
        }
    }

    @Test
    fun `the scale is pinned to physical size, so the system font scale cannot move it`() {
        val cranked = Density(density = 2f, fontScale = 1.3f)
        val type = dialTypography(referenceScale, cranked)
        assertEquals(26f, with(cranked) { type.band.fontSize.toPx() }, TOLERANCE)
        assertEquals(72f, with(cranked) { type.numeralLarge.fontSize.toPx() }, TOLERANCE)
    }

    @Test
    fun `a curved band is the same type as a straight one`() {
        val type = dialTypography(referenceScale, pixelWatch)
        listOf(DialTextRole.BAND, DialTextRole.BAND_SECONDARY).forEach { role ->
            val straight = type.style(role)
            val curved = type.curved(role, Color.Red)
            assertEquals(straight.fontFamily, curved.fontFamily, "$role changed face on the arc")
            assertEquals(straight.fontSize, curved.fontSize, "$role changed size on the arc")
            assertEquals(straight.fontWeight, curved.fontWeight, "$role changed weight on the arc")
            assertEquals(straight.letterSpacing, curved.letterSpacing, "$role lost its tracking on the arc")
            assertEquals(Color.Red, curved.color)
        }
    }

    @Test
    fun `a band row still fits the annulus the wider cycle ring leaves it`() {
        // The cycle ring took 14 reference px off the band annulus (v3 §1). A band
        // is one line of caps, so what has to fit is its line box: 36px of annulus
        // against a 13sp row on a density-2 face.
        val type = dialTypography(referenceScale, pixelWatch)
        val row = with(pixelWatch) { type.curved(DialTextRole.BAND, Color.Red).lineHeight.toPx() }
        assertTrue(row <= DialGeometry.bandArc(384f).thicknessPx, "the band row (${row}px) overflows its annulus")
    }

    @Test
    fun `a cycle label's line box fits inside the cycle ring's own stroke`() {
        // This is why CYCLE_LABEL is 18 reference px and not 24 (BAND_SECONDARY):
        // the ring's stroke is 22 reference px and 22 / 1.2 (the band line-height
        // factor) is 18.33, so 18 is the largest whole step that still fits.
        val type = dialTypography(referenceScale, pixelWatch)
        val row = with(pixelWatch) { type.curved(DialTextRole.CYCLE_LABEL, Color.Red).lineHeight.toPx() }
        assertTrue(
            row <= DialGeometry.cycleRing(384f).strokePx,
            "the cycle label row (${row}px) overflows the ring stroke",
        )
    }

    @Test
    fun `a curved style carries no em units, which the arc renderer cannot convert`() {
        // The curved renderer px-converts every dimension it draws with, and a
        // TextUnit only survives that as Sp — an em lineHeight crashed the watch
        // on launch ("Only Sp can convert to Px", 2026-07-31).
        val type = dialTypography(referenceScale, pixelWatch)
        DialTextRole.entries.forEach { role ->
            val curved = type.curved(role, Color.Red)
            assertTrue(!curved.lineHeight.isEm, "$role's curved lineHeight is em")
            assertTrue(!curved.letterSpacing.isEm, "$role's curved letterSpacing is em")
            assertTrue(!curved.fontSize.isEm, "$role's curved fontSize is em")
        }
    }

    private companion object {
        const val TOLERANCE = 0.01f
    }
}
