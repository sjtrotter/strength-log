package cloud.trotter.log.strength.ui.theme

import androidx.compose.foundation.Indication
import androidx.compose.foundation.LocalIndication
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RippleConfiguration
import androidx.compose.material3.Shapes
import androidx.compose.material3.Text
import androidx.compose.material3.Typography
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.graphics.Color
import cloud.trotter.log.strength.domain.theme.DayAccentColors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import cloud.trotter.log.strength.domain.theme.ThemePreference

/**
 * `ThemeCompletenessTest` proves the three values are complete; this proves
 * [AppTheme] actually hands them to Material. A shape scale nobody passes to
 * `MaterialTheme` is a file, not a theme.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ThemeWiringTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun appThemeInstallsTheAppScheme() {
        var colors: ColorScheme? = null
        var type: Typography? = null
        var shapes: Shapes? = null
        composeTestRule.setContent {
            AppTheme(preference = ThemePreference.DARK) {
                colors = MaterialTheme.colorScheme
                type = MaterialTheme.typography
                shapes = MaterialTheme.shapes
            }
        }
        assertSame(DarkAppColorScheme, colors)
        assertEquals(AppTypography, type)
        assertEquals(AppShapes, shapes)
    }

    @Test
    fun appThemeInstallsEqualRippleValuesForWrapperAndMaterialPaths() {
        var indication: Indication? = null
        var configuration: RippleConfiguration? = null
        composeTestRule.setContent {
            AppTheme(preference = ThemePreference.DARK) {
                indication = LocalIndication.current
                configuration = LocalRippleConfiguration.current
            }
        }

        assertEquals(DarkAppColorScheme.onSurfaceVariant, configuration?.color)
        assertEquals(1f, configuration?.color?.alpha)
        // AppIndication is constructed from this opaque color and bounded flag;
        // Indication does not expose those constructor values for inspection.
        assertEquals(true, AppRippleBounded)
        assertEquals(AppRipplePressedAlpha, configuration?.rippleAlpha?.pressedAlpha)
        assertEquals(0.04f, configuration?.rippleAlpha?.hoveredAlpha)
        assertEquals(0.04f, configuration?.rippleAlpha?.draggedAlpha)
        // The bespoke inset ring remains the only focus treatment.
        assertEquals(0f, configuration?.rippleAlpha?.focusedAlpha)
    }

    @Test
    fun explicitLightThemeInstallsLightSchemeAndDarkContentRipple() {
        var colors: ColorScheme? = null
        var configuration: RippleConfiguration? = null
        composeTestRule.setContent {
            AppTheme(preference = ThemePreference.LIGHT) {
                colors = MaterialTheme.colorScheme
                configuration = LocalRippleConfiguration.current
            }
        }
        assertSame(LightAppColorScheme, colors)
        assertEquals(LightAppColorScheme.onSurfaceVariant, configuration?.color)
        assertEquals(AppRipplePressedAlpha, configuration?.rippleAlpha?.pressedAlpha)
        assertEquals(0.04f, configuration?.rippleAlpha?.hoveredAlpha)
        assertEquals(0.04f, configuration?.rippleAlpha?.draggedAlpha)
        assertEquals(0f, configuration?.rippleAlpha?.focusedAlpha)
    }

    @Test
    fun explicitDarkOverridesLightSystemAppearance() {
        var colors: ColorScheme? = null
        composeTestRule.setContent {
            AppTheme(preference = ThemePreference.DARK, systemInDarkTheme = false) {
                colors = MaterialTheme.colorScheme
            }
        }
        assertSame(DarkAppColorScheme, colors)
    }

    @Test
    fun explicitLightOverridesDarkSystemAppearance() {
        var colors: ColorScheme? = null
        composeTestRule.setContent {
            AppTheme(preference = ThemePreference.LIGHT, systemInDarkTheme = true) {
                colors = MaterialTheme.colorScheme
            }
        }
        assertSame(LightAppColorScheme, colors)
    }

    @Test
    fun systemPreferenceFollowsChangedSystemAppearance() {
        var systemInDarkTheme by mutableStateOf(false)
        var colors: ColorScheme? = null
        composeTestRule.setContent {
            AppTheme(preference = ThemePreference.SYSTEM, systemInDarkTheme = systemInDarkTheme) {
                colors = MaterialTheme.colorScheme
            }
        }

        composeTestRule.runOnIdle {
            assertSame(LightAppColorScheme, colors)
            systemInDarkTheme = true
        }
        composeTestRule.runOnIdle {
            assertSame(DarkAppColorScheme, colors)
        }
    }

    @Test
    fun accentEmphasisResolvesBrightForDarkAndDeepForLight() {
        var dark: Color? = null
        var light: Color? = null
        composeTestRule.setContent {
            AppTheme(preference = ThemePreference.DARK) { dark = accentEmphasis(2) }
            AppTheme(preference = ThemePreference.LIGHT) { light = accentEmphasis(2) }
        }
        assertEquals(Color(DayAccentColors.brightHex(2)), dark)
        assertEquals(Color(DayAccentColors.deepHex(2)), light)
    }

    /**
     * `AlertDialog` pours `headlineSmall` into its title slot, and all six of
     * the app's dialogs fill that slot with a bare `Text` — so completing
     * `headlineSmall` moved their titles off Roboto and onto the condensed
     * face. That is the intended end state (a Roboto title was the baseline
     * leak this migration exists to remove), and this pins it: a regression
     * back to the platform sans fails here rather than in a screenshot.
     */
    @Test
    fun unstyledDialogTitlesSpeakInTheAppsCondensedFace() {
        var titleStyle: TextStyle? = null
        composeTestRule.setContent {
            AppTheme {
                AlertDialog(
                    onDismissRequest = {},
                    title = {
                        titleStyle = LocalTextStyle.current
                        Text("Reset day to template?")
                    },
                    confirmButton = { Text("Reset") },
                )
            }
        }
        composeTestRule.onNodeWithText("Reset day to template?").assertExists()
        assertEquals(Condensed, titleStyle?.fontFamily)
        assertEquals(AppTypography.headlineSmall.fontSize, titleStyle?.fontSize)
        assertEquals(AppTypography.headlineSmall.lineHeight, titleStyle?.lineHeight)
        assertEquals(AppTypography.headlineSmall.fontWeight, titleStyle?.fontWeight)
    }
}
