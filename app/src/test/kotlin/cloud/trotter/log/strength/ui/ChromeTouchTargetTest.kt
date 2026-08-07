package cloud.trotter.log.strength.ui

import androidx.compose.ui.test.junit4.v2.createComposeRule
import cloud.trotter.log.strength.ui.TouchTargets.assertEveryTouchTargetIsAtLeast48dp
import cloud.trotter.log.strength.ui.TouchTargets.assertNoOverlappingTouchTargets
import cloud.trotter.log.strength.ui.setup.SetupActions
import cloud.trotter.log.strength.ui.setup.SetupScreen
import cloud.trotter.log.strength.ui.setup.SetupUiState
import cloud.trotter.log.strength.ui.theme.AppTheme
import cloud.trotter.log.strength.ui.today.RotationMark
import cloud.trotter.log.strength.ui.today.TodayActions
import cloud.trotter.log.strength.ui.today.TodayLift
import cloud.trotter.log.strength.ui.today.TodayScreen
import cloud.trotter.log.strength.ui.today.TodayUiState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The two screens that are almost entirely chrome: Today's header, where the LOG
 * pill and the ⚙ chip sit 8dp apart and were 40dp each, and Setup, a column of
 * nav rows with a 40dp back chip above them. Both had every target grown by
 * #123, and neither has a card header to hide a collision in — if reserving
 * space pushed something onto its neighbour, it shows up here.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp")
class ChromeTouchTargetTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun todaysHeaderAndStartBarKeepTheirTargetsApart() {
        composeTestRule.setContent {
            AppTheme { TodayScreen(state = todayState(), actions = todayActions()) }
        }

        composeTestRule.assertEveryTouchTargetIsAtLeast48dp()
        composeTestRule.assertNoOverlappingTouchTargets()
    }

    @Test
    fun theSetupListKeepsItsTargetsApart() {
        composeTestRule.setContent {
            AppTheme { SetupScreen(state = SetupUiState(), actions = setupActions()) }
        }

        composeTestRule.assertEveryTouchTargetIsAtLeast48dp()
        composeTestRule.assertNoOverlappingTouchTargets()
    }

    private fun todayState() = TodayUiState(
        hasProgram = true,
        dayId = "B",
        dayIndex = 1,
        dayLine = "DAY B · LOWER",
        overline = "NEXT IN ROTATION",
        emphasisLine = "hip-hinge hamstrings",
        statLine = "5 LIFTS · 21 SETS",
        lifts = listOf(
            TodayLift("Barbell Back Squat", 5, isMain = true),
            TodayLift("Seated Leg Curl", 3, isMain = false),
        ),
        actionLabel = "START DAY B",
        lastSession = "Jul 30, 2026 · 18 sets · Back Squat 245",
        rotation = listOf(
            RotationMark("A", 0, isNext = false),
            RotationMark("B", 1, isNext = true),
            RotationMark("C", 2, isNext = false),
        ),
    )

    private fun todayActions() = TodayActions(onStart = {}, onOpenSettings = {}, onOpenLog = {}, onSetUpProgram = {})

    private fun setupActions() = SetupActions(
        onBodyweightChange = {},
        onAgeChange = {},
        onLevelChange = {},
        onEmphasisChange = {},
        onCardioModeChange = {},
        onCardioPlacementChange = {},
        onFiveKChange = {},
        onUnitToggle = {},
        onRestTimerEnabledChange = {},
        onRestOverrideChange = { _, _ -> },
        onRestOverridesReset = {},
        onRerunWizard = {},
        onCreateCustomExercise = {},
        onOpenBackup = {},
        onOpenLicenses = {},
        onBack = {},
    )
}
