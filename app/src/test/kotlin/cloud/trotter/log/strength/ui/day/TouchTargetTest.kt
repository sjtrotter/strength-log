package cloud.trotter.log.strength.ui.day

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import cloud.trotter.log.strength.data.catalog.ExerciseCatalog
import cloud.trotter.log.strength.domain.library.TrackingType
import cloud.trotter.log.strength.domain.model.LoggedSet
import cloud.trotter.log.strength.domain.model.MovementPattern
import cloud.trotter.log.strength.domain.model.SetKind
import cloud.trotter.log.strength.domain.units.WeightUnit
import cloud.trotter.log.strength.ui.TouchTargets.assertEveryTouchTargetIsAtLeast48dp
import cloud.trotter.log.strength.ui.TouchTargets.assertOverlappingTouchTargetsAreExactly
import cloud.trotter.log.strength.ui.theme.AppTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Pins issue #123 on the busiest screen in the app: the day screen carries the
 * tab strip, the ✎ chip, a card header with three competing affordances, set
 * rows of steppers, ticks and remove glyphs, and a bottom bar where DONE now
 * shares a line with the keep-screen-on switch (#125). If a 48dp target can
 * collide with a neighbour anywhere, it collides here.
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

    // The × is off the row now (behind a swipe), so it no longer falls off the
    // card — but at 411dp the reps + and the tick still share their 48dp halos.
    // That remaining squeeze is what #136 stays open for.
    private val issue136SetRowSqueeze = mapOf(setOf("Increase reps", "Set done") to 2)

    @Test
    fun theDayScreenGivesEveryControlItsOwn48dp() {
        setDayContent()

        composeTestRule.assertEveryTouchTargetIsAtLeast48dp()
        composeTestRule.assertOverlappingTouchTargetsAreExactly(issue136SetRowSqueeze)
    }

    /**
     * The header restructure from PR #134 plus this issue's tab/chip growth:
     * four day tabs 8dp apart, then the ✎ chip on the title row. Every one of
     * those was 40dp or less before #123. (The keep-on switch used to share that
     * row; #125 moved it to the bottom bar, which is what left the tabs room.)
     */
    @Test
    fun theHeaderTabsAndChipDoNotShareAnyPixels() {
        setDayContent(state = fixture(tabCount = 4))

        composeTestRule.assertOverlappingTouchTargetsAreExactly(issue136SetRowSqueeze)
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

        composeTestRule.assertOverlappingTouchTargetsAreExactly(issue136SetRowSqueeze)
        composeTestRule.assertEveryTouchTargetIsAtLeast48dp()
    }

    /**
     * The remove-set undo offer (#124) drops a full-width control *between* two
     * set rows, in a card already short of room — the one place a new target
     * would take its 48dp out of a neighbour's.
     */
    @Test
    fun theRemovedSetUndoOfferTakesItsOwn48dpAndNoOnesElse() {
        setDayContent(removedSets = listOf(RemovedSet(1L, 1, "A", LoggedSet(210.0, 5, SetKind.WORK))))

        composeTestRule.assertEveryTouchTargetIsAtLeast48dp()
        composeTestRule.assertOverlappingTouchTargetsAreExactly(issue136SetRowSqueeze)
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

    private fun setDayContent(state: DayUiState = fixture(), removedSets: List<RemovedSet> = emptyList()) {
        composeTestRule.setContent {
            AppTheme {
                DayScreen(
                    state = state,
                    removedSets = removedSets,
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
        onSetUpProgram = {},
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
