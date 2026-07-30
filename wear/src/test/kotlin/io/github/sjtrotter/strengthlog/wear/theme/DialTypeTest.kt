package io.github.sjtrotter.strengthlog.wear.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Density
import io.github.sjtrotter.strengthlog.wear.ui.DialGeometry
import io.github.sjtrotter.strengthlog.wear.ui.DialTextRole
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
    fun `no step of the scale renders below 12sp on a 192dp face`() {
        val type = dialTypography(referenceScale, pixelWatch)
        DialTextRole.entries.forEach { role ->
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

    private companion object {
        const val TOLERANCE = 0.01f
    }
}
