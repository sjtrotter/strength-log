package cloud.trotter.log.strength.ui.day

import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import cloud.trotter.log.strength.data.db.entity.Slot
import cloud.trotter.log.strength.ui.theme.AppTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp")
class DaySheetTest {
    @get:Rule val rule = createComposeRule()

    @Test fun pairBenchAndCardioRowsSelectAndDismiss() {
        var selected: String? = null
        var dismissals = 0
        rule.setContent { AppTheme {
            DaySheet(workoutDaySheetFixture(), pagerActions().copy(onSelectWorkoutLift = { selected = it }),
                {}, {}, { dismissals++ })
        } }
        rule.onNodeWithText("Day A").assertIsDisplayed()
        rule.onNodeWithText("Squat focus, 2 of 18 sets").assertIsDisplayed()
        rule.onNodeWithText("Superset").assertIsDisplayed()
        rule.onNodeWithText("DB Bench Press + Seated Cable Row").assertIsDisplayed()
        rule.onNodeWithText("Round 2 of 3, now").assertIsDisplayed()
        rule.onAllNodesWithText("6 sets").assertCountEquals(2)
        rule.onNodeWithText("Zone 2").assertIsDisplayed()
        rule.onNodeWithText("Barbell Bench Press").performClick()
        rule.runOnIdle {
            assertEquals("exercise:2", selected)
            assertEquals(1, dismissals)
        }
        rule.onNodeWithText("Zone 2").performClick()
        rule.runOnIdle { assertEquals("cardio", selected) }
        rule.onNodeWithText("Keep screen on").assertDoesNotExist()
    }

    @Test fun finishedLiftShowsDoneAndDayActionsCloseTheSheet() {
        val base = workoutDaySheetFixture()
        val state = base.copy(exercises = base.exercises.map {
            if (it.programExerciseId == 2L) it.copy(allDone = true, rows = it.rows.map { row -> row.copy(done = true) }) else it
        })
        var edits = 0
        var clears = 0
        var dismissals = 0
        var discards = 0
        rule.setContent { AppTheme {
            DaySheet(state, pagerActions(), { edits++ }, { clears++ }, { dismissals++ }, { discards++ })
        } }
        rule.onNodeWithText("Done").assertIsDisplayed()
        rule.onNodeWithText("Edit day").performScrollTo().performClick()
        rule.onNodeWithText("Clear today's checks").performScrollTo().performClick()
        rule.onNodeWithText("Discard workout").performScrollTo().performClick()
        rule.runOnIdle {
            assertEquals(1, edits)
            assertEquals(1, clears)
            assertEquals(3, dismissals)
            assertEquals(1, discards)
        }
    }

    @Test fun selectedPlainLiftShowsProgress() {
        val base = workoutLiftSheetFixture()
        rule.setContent { AppTheme { DaySheet(base, pagerActions(), {}, {}, {}) } }
        rule.onNodeWithText("2 of 6, now").assertIsDisplayed()
    }

    @Test fun skippedLiftKeepsItsPositionAndBringBackUsesItsSlot() {
        val base = workoutDaySheetFixture()
        val skipped = base.exercises[1]
        var restored: Pair<Long, String>? = null
        val state = base.copy(exercises = base.exercises.filterNot { it.programExerciseId == skipped.programExerciseId },
            skippedLifts = listOf(SkippedLift(skipped.programExerciseId, skipped.position, skipped.title)))
        rule.setContent { AppTheme {
            DaySheet(state, pagerActions().copy(onRestoreSkipped = { id, half -> restored = id to half }), {}, {}, {})
        } }
        rule.onNodeWithText("Skipped").assertIsDisplayed()
        val before = rule.onNodeWithText("Barbell Back Squat").fetchSemanticsNode().boundsInRoot.top
        val middle = rule.onNodeWithText("Barbell Bench Press").fetchSemanticsNode().boundsInRoot.top
        val after = rule.onNodeWithText("DB Bench Press + Seated Cable Row").fetchSemanticsNode().boundsInRoot.top
        rule.runOnIdle { assertTrue(before < middle && middle < after) }
        rule.onNodeWithText("Bring back").assertHeightIsAtLeast(48.dp).performClick()
        rule.runOnIdle { assertEquals(skipped.programExerciseId to Slot.MAIN, restored) }
    }

    @Test fun skippedRowsAreAbsentWithoutTheSetScreen() {
        val base = workoutLiftSheetFixture().copy(workout = null,
            skippedLifts = listOf(SkippedLift(99L, 0, "Skipped lift")))
        rule.setContent { AppTheme { DaySheet(base, pagerActions(), {}, {}, {}) } }
        rule.onNodeWithText("Skipped").assertDoesNotExist()
        rule.onNodeWithText("Bring back").assertDoesNotExist()
        rule.onNodeWithText("Skipped lift").assertDoesNotExist()
    }
}
