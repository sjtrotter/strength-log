package cloud.trotter.log.strength.ui.day

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import cloud.trotter.log.strength.data.catalog.ExerciseCatalog
import cloud.trotter.log.strength.domain.model.MovementPattern
import cloud.trotter.log.strength.ui.theme.AppTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The picker's search/filter draft survives recreation (#178). Pinned on
 * [ExercisePickerScreen] directly rather than through [DayEditSheet]: the M3
 * sheet hosts its content in a dialog WINDOW, whose saveable registry
 * [StateRestorationTester] cannot reach — testing through it exercises the
 * tool's blind spot, not the code. The dialog's own restoration is the
 * platform's contract (its wrapper is a SavedStateRegistryOwner).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp")
class DayEditPickerRestorationTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val candidates = ExerciseCatalog.CODE_ONLY.byPattern(MovementPattern.SQUAT_BILATERAL)

    private fun show(key: String): StateRestorationTester {
        val restoration = StateRestorationTester(composeTestRule)
        restoration.setContent {
            AppTheme {
                ExercisePickerScreen(
                    key = key,
                    title = "SWAP",
                    pattern = MovementPattern.SQUAT_BILATERAL,
                    candidates = candidates,
                    defaultEquipment = emptySet(),
                    accent = Color.White,
                    onPick = {},
                    onBack = {},
                    onCreateExercise = {},
                )
            }
        }
        return restoration
    }

    private fun assertDraftSurvives(restoration: StateRestorationTester) {
        composeTestRule.onNode(hasSetTextAction()).performTextInput("press")
        composeTestRule.onNodeWithText("Barbell").performClick()
        composeTestRule.onNodeWithText("Barbell").assertIsOn()
        restoration.emulateSavedInstanceStateRestore()
        composeTestRule.onNode(hasSetTextAction()).assertTextContains("press")
        composeTestRule.onNodeWithText("Barbell").assertIsOn()
    }

    @Test
    fun swapPickerDraftSurvivesRestoration() = assertDraftSurvives(show(key = "swap:1"))

    @Test
    fun addPickerDraftSurvivesRestoration() = assertDraftSurvives(show(key = "add:SQUAT_BILATERAL"))

    @Test
    fun supersetPickerDraftSurvivesRestoration() = assertDraftSurvives(show(key = "ss:1"))
}
