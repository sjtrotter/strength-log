package cloud.trotter.log.strength.ui.day

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import cloud.trotter.log.strength.ui.theme.AppTheme
import org.junit.Assert.assertEquals
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
}
