package cloud.trotter.log.strength.ui.components

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import cloud.trotter.log.strength.domain.library.TrackingType
import cloud.trotter.log.strength.domain.units.WeightStepper
import cloud.trotter.log.strength.domain.units.WeightUnit
// DayTab is both a data class (DayScreenModels) and the day-tab composable
// (DayScreen, made `internal` for this test) living in the same package —
// the wildcard import resolves each call by shape, exactly as it already
// does inside DayScreen.kt itself. WeightSwapPill/WeightSwapConfirmDialog/
// WeightSwapAffordance (§4.2) resolve the same way.
import cloud.trotter.log.strength.ui.day.*
import cloud.trotter.log.strength.ui.licenses.LicenseEntry
import cloud.trotter.log.strength.ui.licenses.LicensesScreen
import cloud.trotter.log.strength.ui.theme.AppTheme
import cloud.trotter.log.strength.ui.theme.accentSoft
import cloud.trotter.log.strength.ui.theme.dayAccent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Semantics smoke tests (A7, issue #21): a lean Robolectric + compose-ui-test
 * pass pinning the TalkBack-facing contract promised by key app components
 * — not a full accessibility audit (that's the on-device TalkBack checklist
 * in the #21 PR description), just enough to catch a regression that strips
 * a content description or state.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class A11ySemanticsTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun stepperSegmentsExposeTheirDecreaseAndIncreaseLabels() {
        composeTestRule.setContent {
            AppTheme {
                Stepper(
                    value = 135.0,
                    onValueChange = {},
                    step = { WeightStepper.increment(it, WeightUnit.LB) },
                    format = WeightStepper::format,
                    round = { WeightStepper.round(it, WeightUnit.LB) },
                    decreaseDescription = "Decrease weight",
                    increaseDescription = "Increase weight",
                )
            }
        }

        composeTestRule.onNodeWithContentDescription("Decrease weight").assertExists()
        composeTestRule.onNodeWithContentDescription("Increase weight").assertExists()
    }

    @Test
    fun checkmarkToggleExposesToggleableState() {
        composeTestRule.setContent {
            AppTheme {
                var checked by remember { mutableStateOf(false) }
                CheckmarkToggle(checked = checked, onCheckedChange = { checked = it })
            }
        }

        val toggle = composeTestRule.onNodeWithContentDescription("Set done")
        toggle.assertIsOff()
        toggle.performClick()
        toggle.assertIsOn()
    }

    @Test
    fun setRowExposesWeightAndRepsDescriptions() {
        composeTestRule.setContent {
            AppTheme {
                SetRow(
                    kindLabel = "R1",
                    accent = dayAccent(0),
                    accentSoft = accentSoft(0),
                    weight = 135.0,
                    onWeightChange = {},
                    weightStep = { WeightStepper.increment(it, WeightUnit.LB) },
                    weightFormat = WeightStepper::format,
                    weightRound = { WeightStepper.round(it, WeightUnit.LB) },
                    reps = 5,
                    onRepsChange = {},
                )
            }
        }

        composeTestRule.onNodeWithContentDescription("Decrease weight").assertExists()
        composeTestRule.onNodeWithContentDescription("Increase weight").assertExists()
        composeTestRule.onNodeWithContentDescription("Decrease reps").assertExists()
        composeTestRule.onNodeWithContentDescription("Increase reps").assertExists()
        composeTestRule.onNodeWithContentDescription("Remove set").assertExists()
    }

    // --- per-type set rows (tracking-types §3) -----------------------------------

    @Test
    fun repsTrackRowHasNoWeightControlAtAll() {
        composeTestRule.setContent {
            AppTheme {
                SetRow(
                    kindLabel = "1",
                    accent = dayAccent(0),
                    accentSoft = accentSoft(0),
                    weight = 0.0,
                    onWeightChange = {},
                    weightStep = { WeightStepper.increment(it, WeightUnit.LB) },
                    weightFormat = WeightStepper::format,
                    weightRound = { WeightStepper.round(it, WeightUnit.LB) },
                    reps = 12,
                    onRepsChange = {},
                    tracking = TrackingType.REPS,
                )
            }
        }

        composeTestRule.onNodeWithContentDescription("Decrease weight").assertDoesNotExist()
        composeTestRule.onNodeWithContentDescription("Increase weight").assertDoesNotExist()
        composeTestRule.onNodeWithContentDescription("Decrease reps").assertExists()
        composeTestRule.onNodeWithContentDescription("Increase reps").assertExists()
    }

    @Test
    fun timedTrackRowShowsAHoldStepperAndHidesWeightUnlessTheGoalCarriesLoad() {
        composeTestRule.setContent {
            AppTheme {
                SetRow(
                    kindLabel = "1",
                    accent = dayAccent(0),
                    accentSoft = accentSoft(0),
                    weight = 0.0,
                    onWeightChange = {},
                    weightStep = { WeightStepper.increment(it, WeightUnit.LB) },
                    weightFormat = WeightStepper::format,
                    weightRound = { WeightStepper.round(it, WeightUnit.LB) },
                    reps = 0,
                    onRepsChange = {},
                    tracking = TrackingType.TIMED,
                    seconds = 45,
                    showTimedWeight = false,
                )
            }
        }

        composeTestRule.onNodeWithContentDescription("Decrease hold").assertExists()
        composeTestRule.onNodeWithContentDescription("Increase hold").assertExists()
        composeTestRule.onNodeWithText("45s").assertExists()
        composeTestRule.onNodeWithContentDescription("Decrease weight").assertDoesNotExist()
    }

    @Test
    fun timedTrackRowShowsTheWeightStepperWhenTheGoalCarriesLoad() {
        composeTestRule.setContent {
            AppTheme {
                SetRow(
                    kindLabel = "1",
                    accent = dayAccent(0),
                    accentSoft = accentSoft(0),
                    weight = 25.0,
                    onWeightChange = {},
                    weightStep = { WeightStepper.increment(it, WeightUnit.LB) },
                    weightFormat = WeightStepper::format,
                    weightRound = { WeightStepper.round(it, WeightUnit.LB) },
                    reps = 0,
                    onRepsChange = {},
                    tracking = TrackingType.TIMED,
                    seconds = 90,
                    showTimedWeight = true,
                )
            }
        }

        composeTestRule.onNodeWithContentDescription("Decrease weight").assertExists()
        // 90s crosses the m:ss threshold, same as the GOAL chip/history formatters.
        composeTestRule.onNodeWithText("1:30").assertExists()
    }

    // --- ADD WEIGHT / REMOVE WEIGHT pill (§4.2) ----------------------------------

    @Test
    fun addWeightPillReadsAsALabeledAffordanceAndFiresOnClick() {
        var tapped = false
        composeTestRule.setContent {
            AppTheme {
                WeightSwapPill(
                    swap = WeightSwapAffordance("weighted_plank", "Weighted Plank", isRemove = false),
                    accent = dayAccent(0),
                    onClick = { tapped = true },
                )
            }
        }

        val pill = composeTestRule.onNodeWithText("+ ADD WEIGHT")
        pill.assertExists()
        pill.performClick()
        assertTrue(tapped)
    }

    @Test
    fun removeWeightPillReadsDifferentlyFromAddWeight() {
        composeTestRule.setContent {
            AppTheme {
                WeightSwapPill(
                    swap = WeightSwapAffordance("plank", "Plank / Side Plank", isRemove = true),
                    accent = dayAccent(0),
                    onClick = {},
                )
            }
        }

        composeTestRule.onNodeWithText("− REMOVE WEIGHT").assertExists()
    }

    @Test
    fun weightSwapConfirmDialogNamesTheTargetAndConfirmingInvokesTheCallback() {
        var confirmed = false
        composeTestRule.setContent {
            AppTheme {
                WeightSwapConfirmDialog(
                    swap = WeightSwapAffordance("weighted_pullup", "Weighted Pull-Up", isRemove = false),
                    onConfirm = { confirmed = true },
                    onDismiss = {},
                )
            }
        }

        composeTestRule.onNodeWithText("Switch to Weighted Pull-Up?").assertExists()
        composeTestRule.onNodeWithText("Switch").performClick()
        assertTrue(confirmed)
    }

    @Test
    fun dayTabExposesSelectedState() {
        composeTestRule.setContent {
            AppTheme {
                DayTab(
                    tab = DayTab(dayId = "A", dayIndex = 0, isSuggested = false, isSelected = true),
                    onClick = {},
                )
            }
        }

        composeTestRule.onNodeWithContentDescription("Day A").assertIsSelected()
    }

    // --- Today took the app-wide chrome off Day (#121) ---------------------------

    /** Pins #121: Settings and "Open log" left the day header for Today, and the
     *  day-tab cluster became horizontally scrollable so a 6-day program still
     *  fits — every tab must still render, just not all in the viewport. */
    @Test
    fun dayScreenScrollsAllDayTabsAndNoLongerHostsSettingsOrLog() {
        val state = DayUiState(
            hasProgram = true,
            tabs = listOf(
                DayTab("A", 0, isSuggested = false, isSelected = true),
                DayTab("B", 1, isSuggested = false, isSelected = false),
                DayTab("C", 2, isSuggested = false, isSelected = false),
                DayTab("D", 3, isSuggested = false, isSelected = false),
                DayTab("E", 4, isSuggested = false, isSelected = false),
                DayTab("F", 5, isSuggested = false, isSelected = false),
            ),
            viewDayId = "A",
            dayIndex = 0,
            dayTitle = "Lower — squat focus",
            emphasisLine = "hip-hinge hamstrings · gastroc calves",
            unit = WeightUnit.LB,
            suggestedDayId = "A",
            nextDayId = "B",
            exercises = listOf(
                ExerciseCardState(
                    programExerciseId = 1,
                    position = 0,
                    title = "Barbell Back Squat",
                    isMain = true,
                    isSuperset = false,
                    hasWarmupHint = true,
                    goalDisplay = "235",
                    perHand = false,
                    allDone = false,
                    collapsed = false,
                    collapsedSummary = "5 sets · GOAL 235",
                    rows = listOf(
                        SetRowState(0, "R1", isTop = false, weightDisplay = 130.0, reps = 5, done = false),
                        SetRowState(1, "TOP", isTop = true, weightDisplay = 235.0, reps = 5, done = false),
                    ),
                ),
            ),
            keepScreenOn = false,
        )

        composeTestRule.setContent {
            AppTheme {
                DayScreen(
                    state = state,
                    actions = DayActions(
                        onSelectDay = {},
                        onWeightChange = { _, _, _, _ -> },
                        onRepsChange = { _, _, _, _ -> },
                        onSecondsChange = { _, _, _, _ -> },
                        onToggleDone = { _, _, _, _ -> },
                        onAddSet = { _, _ -> },
                        onRemoveSet = { _, _, _ -> },
                        onToggleCollapse = {},
                        onKeepScreenOnChange = {},
                        onClearChecks = {},
                        onDone = {},
                        onCreateExercise = {},
                        onSetUpProgram = {},
                    ),
                    dayEditState = DayEditUiState(),
                    dayEditActions = DayEditActions(
                        onSwap = { _, _ -> },
                        onAdd = {},
                        onRemove = {},
                        onSetSuperset = { _, _ -> },
                        onRemoveSuperset = {},
                        onResetToTemplate = {},
                    ),
                )
            }
        }

        listOf("A", "B", "C", "D", "E", "F").forEach {
            composeTestRule.onNodeWithContentDescription("Day $it").assertExists()
        }

        assertTrue(
            "Settings must not reappear on Day — it moved to Today (#121)",
            composeTestRule.onAllNodesWithContentDescription("Settings").fetchSemanticsNodes().isEmpty(),
        )
        assertTrue(
            "Open log must not reappear on Day — it moved to Today (#121)",
            composeTestRule.onAllNodesWithContentDescription("Open log").fetchSemanticsNodes().isEmpty(),
        )

        composeTestRule.onNodeWithContentDescription("Edit day").assertExists()
    }

    // --- License navigation ---------------------------------------------------

    @Test
    fun licensesBackButtonDoesNotExposeItsChevronGlyph() {
        composeTestRule.setContent {
            AppTheme {
                LicensesScreen(
                    entries = listOf(LicenseEntry("Barlow Condensed (SIL OFL 1.1)", "…")),
                    onBack = {},
                )
            }
        }

        val back = composeTestRule.onNodeWithContentDescription("Back")
        back.assertExists()
        val config = back.fetchSemanticsNode().config
        assertEquals(listOf("Back"), config.getOrNull(SemanticsProperties.ContentDescription))
        assertTrue(config.getOrNull(SemanticsProperties.Text).isNullOrEmpty())
    }
}
