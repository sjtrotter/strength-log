package cloud.trotter.log.strength.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import java.lang.reflect.Method
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The theme is only finished when nothing falls through it (issue #157,
 * Phase 1). Baseline Material is a purple scheme, a Roboto scale and a corner
 * set this app doesn't draw; every role left unspecified is that leaking into
 * the first stock component to read it. These tests walk the roles one by one
 * and name the ones still wearing Material's value.
 */
class ThemeCompletenessTest {

    private val baselineColors = darkColorScheme()
    private val baselineType = Typography()
    private val baselineShapes = Shapes()

    // Color is a value class, so each role's getter comes back as a raw long
    // under a mangled name. Walking the getters instead of a hand-written list
    // means a role added by a future Material release joins this test on its own.
    private val colorRoles: List<Method> =
        ColorScheme::class.java.declaredMethods
            .filter {
                !it.isSynthetic &&
                    it.parameterCount == 0 &&
                    it.returnType == java.lang.Long.TYPE &&
                    it.name.startsWith("get")
            }
            .sortedBy(Method::getName)

    private fun roleName(method: Method) =
        method.name.removePrefix("get").substringBefore('-').replaceFirstChar(Char::lowercaseChar)

    private fun read(method: Method, scheme: ColorScheme) = method.invoke(scheme) as Long

    @Test
    fun `the scheme this test walks is the whole scheme`() {
        // Material 3 1.4.0 has 48 color roles. When a release adds one this
        // number moves, and the leak test below starts covering it — the point
        // of counting rather than trusting the filter.
        assertEquals(48, colorRoles.size, "roles found: ${colorRoles.map(::roleName)}")
    }

    @Test
    fun `the deliberately-baseline list names real roles`() {
        val unknown = DeliberatelyBaseline - colorRoles.map(::roleName).toSet()
        assertTrue(unknown.isEmpty(), "not color roles: $unknown")
    }

    @Test
    fun `no color role is left at its baseline Material value`() {
        val leaked = colorRoles
            .filter { roleName(it) !in DeliberatelyBaseline }
            .filter { read(it, AppColorScheme) == read(it, baselineColors) }
        assertTrue(
            leaked.isEmpty(),
            "roles still on Material's baseline palette: ${leaked.map(::roleName)}",
        )
    }

    @Test
    fun `every color role is drawn from the app's own palette`() {
        // Differing from baseline isn't enough — a role could differ and still
        // be an off-brand one-off. The whole scheme has to come from Color.kt,
        // or from the accent-sunk-into-surface containers derived from it.
        val named = listOf(
            Background, Surface, Surface2, Surface3, Border, BorderStrong,
            TextPrimary, TextSecondary, TextFaint, Error, Done, FocusRing,
        ) + (0..6).flatMap { listOf(dayAccent(it), onDayAccent(it)) }
        val palette = (named + named.map(::containerOf)).map { it.value.toLong() }.toSet()

        val strangers = colorRoles.filter { read(it, AppColorScheme) !in palette }
        assertTrue(
            strangers.isEmpty(),
            "roles set to colors outside the app palette: ${strangers.map(::roleName)}",
        )
    }

    @Test
    fun `all fifteen typography roles are the app's, not Material's`() {
        val leaked = TypographyRoles.filter { (_, read) -> read(AppTypography) == read(baselineType) }
        assertTrue(
            leaked.isEmpty(),
            "type roles still on Material's baseline scale: ${leaked.map { it.first }}",
        )
    }

    @Test
    fun `typography follows the two-face rule`() {
        // Display, headline, title and label speak in the condensed face; body
        // is the plain sans, because paragraphs read badly condensed.
        TypographyRoles.forEach { (name, read) ->
            val expected = if (name.startsWith("body")) Sans else Condensed
            assertEquals(expected, read(AppTypography).fontFamily, "$name wears the wrong face")
        }
    }

    @Test
    fun `display roles keep tabular numerals`() {
        // In this app the display sizes are what numbers render at.
        listOf(
            AppTypography.displayLarge,
            AppTypography.displayMedium,
            AppTypography.displaySmall,
        ).forEach { assertEquals("tnum", it.fontFeatureSettings) }
    }

    @Test
    fun `the shape scale is the app's own radii`() {
        assertEquals(RoundedCornerShape(4.dp), AppShapes.extraSmall)
        assertEquals(RoundedCornerShape(8.dp), AppShapes.small)
        assertEquals(RoundedCornerShape(10.dp), AppShapes.medium)
        assertEquals(RoundedCornerShape(12.dp), AppShapes.large)
        // extraLarge stays at Material's 28 dp deliberately: dialogs and modal
        // sheets are the only things that read it and the app has no token of
        // its own at that size. See Shape.kt.
        assertEquals(RoundedCornerShape(28.dp), AppShapes.extraLarge)
    }

    @Test
    fun `the shape steps the app draws with are not Material's`() {
        // extraSmall and small agree with Material by coincidence (4 dp, 8 dp);
        // the two the app actually draws — compact chrome and cards — must not.
        assertTrue(AppShapes.medium != baselineShapes.medium, "medium leaked Material's 12 dp")
        assertTrue(AppShapes.large != baselineShapes.large, "large leaked Material's 16 dp")
    }

    private companion object {
        /**
         * Roles whose right value happens to be the one Material already ships,
         * exempted from the leak test the same way `extraLarge`'s 28 dp is
         * exempted from the shape one. Empty today, and it should stay that way
         * unless the alternative is worse.
         *
         * The case it exists for is `scrim`. Material's is pure `#000`; the app
         * sets `Background` instead, which is honest — that is the app's black —
         * but it does move the modal sheet's overlay by about 4/255 a channel.
         * If a future palette decides `#000` is genuinely the right scrim, the
         * answer is to name it here, not to invent an off-token near-black just
         * to keep this test quiet.
         */
        val DeliberatelyBaseline = emptySet<String>()

        val TypographyRoles: List<Pair<String, (Typography) -> TextStyle>> = listOf(
            "displayLarge" to { t: Typography -> t.displayLarge },
            "displayMedium" to { t: Typography -> t.displayMedium },
            "displaySmall" to { t: Typography -> t.displaySmall },
            "headlineLarge" to { t: Typography -> t.headlineLarge },
            "headlineMedium" to { t: Typography -> t.headlineMedium },
            "headlineSmall" to { t: Typography -> t.headlineSmall },
            "titleLarge" to { t: Typography -> t.titleLarge },
            "titleMedium" to { t: Typography -> t.titleMedium },
            "titleSmall" to { t: Typography -> t.titleSmall },
            "bodyLarge" to { t: Typography -> t.bodyLarge },
            "bodyMedium" to { t: Typography -> t.bodyMedium },
            "bodySmall" to { t: Typography -> t.bodySmall },
            "labelLarge" to { t: Typography -> t.labelLarge },
            "labelMedium" to { t: Typography -> t.labelMedium },
            "labelSmall" to { t: Typography -> t.labelSmall },
        )
    }
}
