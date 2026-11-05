package cloud.trotter.log.strength.ui.day

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import cloud.trotter.log.strength.data.catalog.ExerciseCatalog
import cloud.trotter.log.strength.domain.library.ExerciseEntry
import cloud.trotter.log.strength.domain.model.Equipment
import cloud.trotter.log.strength.domain.model.MovementPattern
import cloud.trotter.log.strength.ui.theme.AppTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The picker's search/filter draft survives recreation. Pinned on
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

    private fun show(
        key: String,
        allCandidates: List<ExerciseEntry>? = null,
        defaultEquipment: Set<Equipment> = emptySet(),
    ): StateRestorationTester {
        val restoration = StateRestorationTester(composeTestRule)
        restoration.setContent {
            AppTheme {
                ExercisePickerScreen(
                    key = key,
                    title = "SWAP",
                    pattern = MovementPattern.SQUAT_BILATERAL,
                    candidates = candidates,
                    defaultEquipment = defaultEquipment,
                    accent = Color.White,
                    onPick = {},
                    onBack = {},
                    onCreateExercise = {},
                    allCandidates = allCandidates,
                )
            }
        }
        return restoration
    }

    private fun assertDraftSurvives(restoration: StateRestorationTester) {
        composeTestRule.onNode(hasSetTextAction()).performTextInput("press")
        composeTestRule.onNodeWithText("Barbell").performClick()
        composeTestRule.onNodeWithText("Barbell").assertIsSelected()
        restoration.emulateSavedInstanceStateRestore()
        composeTestRule.onNode(hasSetTextAction()).assertTextContains("press")
        composeTestRule.onNodeWithText("Barbell").assertIsSelected()
    }

    @Test
    fun swapPickerDraftSurvivesRestoration() = assertDraftSurvives(show(key = "swap:1"))

    @Test
    fun addPickerDraftSurvivesRestoration() = assertDraftSurvives(show(key = "add:SQUAT_BILATERAL"))

    @Test
    fun supersetPickerDraftSurvivesRestoration() = assertDraftSurvives(show(key = "ss:1"))
    @Test
    fun searchStaysDisplayedAndStationaryWhenNothingMatches() {
        show(key = "empty-results")
        val search = composeTestRule.onNode(hasSetTextAction())
        val before = search.fetchSemanticsNode().boundsInRoot.top
        search.performTextInput("zzzz-no-such-exercise")
        search.assertIsDisplayed()
        composeTestRule.onNodeWithText("No matching exercises").assertIsDisplayed()
        org.junit.Assert.assertEquals(before, search.fetchSemanticsNode().boundsInRoot.top)
    }

    @Test
    fun clearingAnEmptyEquipmentSelectionRestoresResultsAndSelectsEveryOption() {
        show(key = "clear-empty-equipment")
        composeTestRule.onNodeWithText("Barbell Back Squat").assertDoesNotExist()
        composeTestRule.onNodeWithText("Clear filters").performClick()
        composeTestRule.onNodeWithText("Barbell Back Squat").assertIsDisplayed()
        composeTestRule.onNodeWithText("Hack Squat").assertIsDisplayed()
        composeTestRule.onNodeWithText("Barbell").assertIsSelected()
        composeTestRule.onNodeWithText("Rack").assertIsSelected()

        // An unmatched query alone is not an equipment restriction.
        composeTestRule.onNode(hasSetTextAction()).performTextInput("zzzz-no-such-exercise")
        composeTestRule.onNodeWithText("No matching exercises").assertIsDisplayed()
        composeTestRule.onNodeWithText("Clear filters").assertDoesNotExist()
    }

    @Test
    fun clearingAPartialEquipmentSelectionRestoresMultiEquipmentExercises() {
        show(key = "clear-partial-equipment")
        composeTestRule.onNodeWithText("Barbell").performClick()
        // The squat needs both the barbell and rack; selecting only the barbell still excludes it.
        composeTestRule.onNodeWithText("Barbell Back Squat").assertDoesNotExist()
        composeTestRule.onNodeWithText("Clear filters").performClick()
        composeTestRule.onNodeWithText("Barbell Back Squat").assertIsDisplayed()
        composeTestRule.onNodeWithText("Rack").assertIsSelected()
    }

    @Test
    fun searchAllPreservesTheQueryAndScopeThroughRestoration() {
        val restoration = show(key = "swap:all",
            allCandidates = ExerciseCatalog.CODE_ONLY.entries.filter { it.id != "bb_back_squat" },
            defaultEquipment = Equipment.entries.toSet())
        composeTestRule.onNodeWithText("Search all exercises").assertDoesNotExist()
        composeTestRule.onNode(hasSetTextAction()).performTextInput("bridge")
        composeTestRule.onNodeWithText("No matching exercises").assertIsDisplayed()
        composeTestRule.onNodeWithText("Search all exercises").performClick()
        composeTestRule.onNodeWithText("Barbell Glute Bridge").assertIsDisplayed()
        composeTestRule.onNodeWithText("Search all exercises").assertDoesNotExist()

        restoration.emulateSavedInstanceStateRestore()

        composeTestRule.onNode(hasSetTextAction()).assertTextContains("bridge")
        composeTestRule.onNodeWithText("Barbell Glute Bridge").assertIsDisplayed()
        composeTestRule.onNode(hasSetTextAction()).performTextReplacement("row dumbbell")
        composeTestRule.onNodeWithText("One-Arm DB Row").assertIsDisplayed()
        composeTestRule.onNode(hasSetTextAction()).performTextReplacement("barbell back squat")
        composeTestRule.onNodeWithText("Barbell Back Squat").assertDoesNotExist()
        composeTestRule.onNodeWithText("No matching exercises").assertIsDisplayed()
    }

    @Test
    fun searchAllKeepsTheEquipmentFilterAndAddsLibraryEquipmentOptions() {
        show(key = "swap:equipment", allCandidates = ExerciseCatalog.CODE_ONLY.entries,
            defaultEquipment = setOf(Equipment.MACHINE))
        composeTestRule.onNodeWithText("Cable").assertDoesNotExist()
        composeTestRule.onNode(hasSetTextAction()).performTextInput("shoulder press")
        composeTestRule.onNodeWithText("Search all exercises").performClick()

        composeTestRule.onNode(hasSetTextAction()).assertTextContains("shoulder press")
        composeTestRule.onNodeWithText("Machine Shoulder Press").assertIsDisplayed()
        composeTestRule.onNodeWithText("Seated DB Shoulder Press").assertDoesNotExist()
        composeTestRule.onNode(hasText("Machine") and isSelectable()).assertIsSelected()
        composeTestRule.onNodeWithText("Cable").assertExists()
    }

}
