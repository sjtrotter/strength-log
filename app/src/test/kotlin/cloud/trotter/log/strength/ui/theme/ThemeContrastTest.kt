package cloud.trotter.log.strength.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import kotlin.test.Test
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class ThemeContrastTest {

    private val schemes = listOf("Dark" to DarkAppColorScheme, "Light" to LightAppColorScheme)

    @Test
    fun `readable scheme roles meet WCAG AA text contrast`() {
        for ((name, scheme) in schemes) {
            val foregrounds = listOf(
                "onSurface" to scheme.onSurface,
                "onSurfaceVariant" to scheme.onSurfaceVariant,
                "primary" to scheme.primary,
                "error" to scheme.error,
            )
            for (foreground in foregrounds) {
                for (background in grounds(scheme)) {
                    assertContrast(name, foreground, background, 4.5)
                }
            }
            assertContrast(name, "onPrimary" to scheme.onPrimary, "primary" to scheme.primary, 4.5)
            assertContrast(name, "onError" to scheme.onError, "error" to scheme.error, 4.5)
            assertContrast(name, "onErrorContainer" to scheme.onErrorContainer, "errorContainer" to scheme.errorContainer, 4.5)
            assertContrast(name, "inverseOnSurface" to scheme.inverseOnSurface, "inverseSurface" to scheme.inverseSurface, 4.5)
            assertContrast(name, "inversePrimary" to scheme.inversePrimary, "inverseSurface" to scheme.inverseSurface, 4.5)
        }
    }

    @Test
    fun `outline meets WCAG boundary contrast on every ground`() {
        for ((name, scheme) in schemes) {
            for (background in grounds(scheme)) {
                assertContrast(name, "outline" to scheme.outline, background, 3.0)
            }
        }
    }

    @Test
    fun `scheme-wide primary is never a day accent`() {
        for ((name, scheme) in schemes) {
            for (dayIndex in 0..6) {
                assertNotEquals(dayAccent(dayIndex), scheme.primary, "$name primary equals dayAccent($dayIndex)")
            }
        }
    }

    private fun grounds(scheme: ColorScheme) = listOf(
        "background" to scheme.background,
        "surface" to scheme.surface,
        "surfaceContainerHigh" to scheme.surfaceContainerHigh,
        "surfaceContainerHighest" to scheme.surfaceContainerHighest,
    )

    private fun assertContrast(
        schemeName: String,
        foreground: Pair<String, Color>,
        background: Pair<String, Color>,
        minimum: Double,
    ) {
        val ratio = contrastRatio(foreground.second, background.second)
        assertTrue(
            ratio >= minimum,
            "$schemeName ${foreground.first} on ${background.first} contrast is $ratio:1, below $minimum:1",
        )
    }
}
