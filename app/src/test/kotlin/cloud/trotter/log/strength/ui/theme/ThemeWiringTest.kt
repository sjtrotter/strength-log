package cloud.trotter.log.strength.ui.theme

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Text
import androidx.compose.material3.Typography
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.text.TextStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

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
            AppTheme {
                colors = MaterialTheme.colorScheme
                type = MaterialTheme.typography
                shapes = MaterialTheme.shapes
            }
        }
        assertSame(AppColorScheme, colors)
        assertEquals(AppTypography, type)
        assertEquals(AppShapes, shapes)
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
