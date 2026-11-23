package cloud.trotter.log.strength.ui.day

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
import org.junit.Assert.assertFalse
import androidx.compose.ui.unit.dp
import cloud.trotter.log.strength.domain.library.TrackingType
import cloud.trotter.log.strength.domain.model.Side
import cloud.trotter.log.strength.domain.model.SideResult
import cloud.trotter.log.strength.domain.model.Sides
import cloud.trotter.log.strength.domain.units.WeightUnit
import cloud.trotter.log.strength.ui.theme.AppTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp")
class EachSideSetScreenTest {
    @get:Rule val rule = createComposeRule()

    private fun state(): DayUiState {
        val base = workoutSetSingleFixture()
        val card = base.exercises.single().copy(
            title = "Skater Squat", exerciseId = "skater_squat", tracking = TrackingType.REPS,
            headerTarget = HeaderTarget.Prescribed("2 × 10", eachSide = true),
            rows = List(2) { SetRowState(it, "${it + 1}", false, 0.0, 10, false) }, focusIndex = 0,
        )
        return base.copy(exercises = listOf(card)).withWorkoutSelection()
    }

    @Test fun `an each side reps set offers Left and right as a text button`() {
        val calls = mutableListOf<String>()
        rule.setContent { AppTheme { WorkoutSetHost(state(), workoutActions().copy(
            onEachSide = { id, index, split -> calls += "$id $index $split" },
            onOpenSetEditor = { id, index -> calls += "$id $index" },
        )) } }
        rule.onNodeWithTag("workout-action-filled").assert(hasText("Log"))
        rule.onNodeWithTag("workout-left-and-right").assertHeightIsAtLeast(48.dp).performClick()
        rule.runOnIdle { assertEquals(listOf("1 0 true", "1 0"), calls) }
    }

    @Test fun `no Left and right on a hold a logged set or a set that is not each side`() {
        val base = state()
        val card = base.exercises.single()
        val current = mutableStateOf(base)
        rule.setContent { AppTheme { WorkoutSetHost(current.value, workoutActions()) } }
        for (candidate in listOf(
            card.copy(tracking = TrackingType.TIMED),
            card.copy(headerTarget = HeaderTarget.Prescribed("2 × 10", eachSide = false)),
            card.copy(rows = card.rows.map { it.copy(done = true) }, allDone = true),
        )) {
            rule.runOnIdle { current.value = base.copy(exercises = listOf(candidate)).withWorkoutSelection() }
            rule.onNodeWithTag("workout-left-and-right").assertDoesNotExist()
        }
    }

    @Test fun `a set with sides shows them in the hero`() {
        val base = state()
        val sides = Sides(SideResult(0.0, 10, 0), SideResult(0.0, 8, 0))
        val current = base.copy(exercises = base.exercises.map { card -> card.copy(rows = card.rows.map {
            if (it.index == 0) it.copy(reps = 8, sides = sides) else it
        }) })
        rule.setContent { AppTheme { WorkoutSetHost(current, workoutActions()) } }
        rule.onNodeWithText("L 10 · R 8 reps").assertIsDisplayed()
        val layouts = mutableListOf<TextLayoutResult>()
        rule.onNodeWithText("L 10 · R 8 reps").performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertFalse(layouts.single().hasVisualOverflow)
        rule.onNodeWithTag("workout-left-and-right").assertDoesNotExist()
    }

    @Test fun `the set editor switches between Each side and Left and right`() {
        val card = state().exercises.single()
        val row = mutableStateOf(card.rows.first())
        val currentCard = mutableStateOf(card)
        var choice: List<Any>? = null
        var change: List<Any?>? = null
        rule.setContent { AppTheme { SetEditSheet(currentCard.value, row.value, WeightUnit.LB, 0,
            workoutActions().copy(
                onEachSide = { id, index, split -> choice = listOf(id, index, split) },
                onSideChange = { id, index, side, weight, reps, seconds -> change = listOf(id, index, side, weight, reps, seconds) },
            ), {}) } }
        rule.onNodeWithTag("set-edit-each-side").assertHeightIsAtLeast(48.dp)
        rule.onNodeWithTag("set-edit-left-and-right").assertHeightIsAtLeast(48.dp).performClick()
        rule.runOnIdle {
            assertEquals(listOf(1L, 0, true), choice)
            row.value = row.value.copy(sides = Sides(SideResult(0.0, 10, 0), SideResult(0.0, 8, 0)), reps = 8)
        }
        rule.onNodeWithText("Left").assertExists()
        rule.onNodeWithText("Right").assertExists()
        rule.onNodeWithContentDescription("Right, Reps").performScrollTo().performTextReplacement("9")
        rule.runOnIdle { assertEquals(listOf(1L, 0, Side.RIGHT, null, 9, null), change) }
        rule.onNodeWithTag("set-edit-each-side").performScrollTo().performClick()
        rule.runOnIdle {
            assertEquals(listOf(1L, 0, false), choice)
            row.value = row.value.copy(sides = null)
            currentCard.value = card.copy(headerTarget = HeaderTarget.Prescribed("2 × 10", eachSide = false))
        }
        rule.onNodeWithTag("set-edit-each-side").assertDoesNotExist()
        rule.onNodeWithTag("set-edit-left-and-right").assertDoesNotExist()
    }
}
