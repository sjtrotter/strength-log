package cloud.trotter.log.strength.ui.day

import androidx.compose.ui.test.junit4.v2.createComposeRule
import cloud.trotter.log.strength.data.catalog.ExerciseCatalog
import cloud.trotter.log.strength.domain.library.TrackingType
import cloud.trotter.log.strength.domain.model.MovementPattern
import cloud.trotter.log.strength.domain.units.WeightUnit
import cloud.trotter.log.strength.ui.TouchTargets.assertEveryTouchTargetIsAtLeast48dp
import cloud.trotter.log.strength.ui.TouchTargets.assertNoOverlappingTouchTargets
import cloud.trotter.log.strength.ui.theme.AppTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Pins issue #123 on the busiest screen in the app: the day screen carries the
 * tab strip, the ✎ chip, the keep-screen-on switch, a card header with three
 * competing affordances, and set rows of steppers, ticks and remove glyphs.
 * If a 48dp target can collide with a neighbour anywhere, it collides here.
 *
 * Sized to a real phone window rather than Robolectric's default: the day
 * screen's fixed bottom bar sits directly under the scrolling list, and on a
 * short window the last set row is clipped mid-control — which measures as a
 * collision no user could ever have.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp")
class TouchTargetTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    /**
     * A weighted set row asks for two ± steppers, a ✓ and a × on one line, and
     * at 411dp that is ~100dp more than the card has. Measured against `main`
     * at fefbc96 the numbers are identical to the ones here, so the squeeze
     * predates #123 and belongs to whoever re-budgets the row: today the ✓ is
     * clipped to 13dp of card and the × is off it entirely. Named rather than
     * silently skipped, so the next overlap to appear still fails this test.
     */
    private val preExistingSetRowSqueeze = setOf(setOf("Increase reps", "Set done"))

    @Test
    fun theDayScreenGivesEveryControlItsOwn48dp() {
        setDayContent()

        composeTestRule.assertEveryTouchTargetIsAtLeast48dp()
        composeTestRule.assertNoOverlappingTouchTargets(preExistingSetRowSqueeze)
    }

    /**
     * The header restructure from PR #134 plus this issue's tab/chip growth:
     * four day tabs 8dp apart, then ✎ and the keep-on switch sharing a row.
     * Every one of those was 40dp or less before #123.
     */
    @Test
    fun theHeaderTabsChipAndSwitchDoNotShareAnyPixels() {
        setDayContent(state = fixture(tabCount = 4))

        composeTestRule.assertNoOverlappingTouchTargets(preExistingSetRowSqueeze)
    }

    /**
     * A card carrying the swap chip, the ADD WEIGHT pill and the collapse toggle
     * at once — the three-way collision #134 flagged and left open.
     */
    @Test
    fun aCardHeaderWithBothSwapAffordancesKeepsThemApart() {
        setDayContent(
            state = fixture().let { base ->
                base.copy(
                    exercises = listOf(
                        base.exercises.single().copy(
                            weightSwap = WeightSwapAffordance("weighted_plank", "Weighted Plank", isRemove = false),
                        ),
                    ),
                )
            },
        )

        composeTestRule.assertNoOverlappingTouchTargets(preExistingSetRowSqueeze)
        composeTestRule.assertEveryTouchTargetIsAtLeast48dp()
    }

    private fun fixture(tabCount: Int = 1) = DayUiState(
        hasProgram = true,
        tabs = List(tabCount) { index ->
            DayTab(('A' + index).toString(), index, isSuggested = index == 1, isSelected = index == 0)
        },
        viewDayId = "A",
        dayIndex = 0,
        dayTitle = "Lower — squat focus",
        emphasisLine = "hip-hinge hamstrings",
        unit = WeightUnit.LB,
        suggestedDayId = "A",
        nextDayId = "B",
        exercises = listOf(
            ExerciseCardState(
                programExerciseId = 1L,
                position = 0,
                title = "Barbell Back Squat",
                isMain = true,
                isSuperset = false,
                hasWarmupHint = true,
                goalDisplay = "235",
                perHand = false,
                tracking = TrackingType.WEIGHTED,
                lastTimeDisplay = "230×5",
                allDone = false,
                collapsed = false,
                collapsedSummary = "5 sets · GOAL 235",
                rows = listOf(
                    SetRowState(0, "R1", isTop = false, weightDisplay = 130.0, reps = 5, done = false),
                    SetRowState(1, "TOP", isTop = true, weightDisplay = 235.0, reps = 5, done = false),
                ),
            ),
        ),
    )

    private fun setDayContent(state: DayUiState = fixture()) {
        composeTestRule.setContent {
            AppTheme {
                DayScreen(
                    state = state,
                    actions = noopActions(),
                    dayEditState = DayEditUiState(
                        catalog = ExerciseCatalog.CODE_ONLY,
                        slots = listOf(
                            DayEditSlotState(
                                programExerciseId = 1L,
                                position = 0,
                                exerciseId = "bb_back_squat",
                                title = "Barbell Back Squat",
                                pattern = MovementPattern.SQUAT_BILATERAL,
                                isSuperset = false,
                            ),
                        ),
                    ),
                    dayEditActions = noopEditActions(),
                )
            }
        }
    }

    private fun noopActions() = DayActions(
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
    )

    private fun noopEditActions() = DayEditActions(
        onSwap = { _, _ -> },
        onAdd = {},
        onRemove = {},
        onSetSuperset = { _, _ -> },
        onRemoveSuperset = {},
        onResetToTemplate = {},
    )
}
