package cloud.trotter.log.strength.ui.day

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import cloud.trotter.log.strength.data.OpenRun
import cloud.trotter.log.strength.ui.theme.AppTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class WorkoutConflictDialogTest {
    @get:Rule val rule = createComposeRule()

    @Test fun `a draft asks to drop in one step and Cancel dismisses`() {
        val run = OpenRun("draft", 1L, "B", "Upper", "Strength", 0L, emptyList(), draft = true)
        var conflict by mutableStateOf<WorkoutConflict?>(WorkoutConflict(run, false))
        var drops = 0
        var confirms = 0
        var dismisses = 0
        var resumes = 0
        rule.setContent { AppTheme {
            WorkoutConflictDialog(conflict,
                onDismiss = { dismisses++; conflict = null }, onResume = { resumes++ },
                onConfirmDiscard = { confirms++ }, onDiscard = { drops++; conflict = null })
        } }
        rule.onNodeWithText("Drop today's changes to day B?").assertIsDisplayed()
        rule.onNodeWithText("They're for today only. To keep them, edit the plan.").assertIsDisplayed()
        rule.onNodeWithText("Resume").assertDoesNotExist()
        rule.onNodeWithText("Drop").performClick()
        assertEquals(1, drops)
        assertEquals(0, confirms)
        assertEquals(0, resumes)
        rule.runOnIdle { conflict = WorkoutConflict(run, false) }
        rule.onNodeWithText("Cancel").performClick()
        assertEquals(1, dismisses)
        assertEquals(1, drops)
    }

    @Test fun `a started conflict keeps Resume and two-step Discard and start`() {
        val run = OpenRun("started", 1L, "B", "Upper", "Strength", 1_000L, emptyList())
        var conflict by mutableStateOf<WorkoutConflict?>(WorkoutConflict(run, false))
        var confirms = 0
        var drops = 0
        var resumes = 0
        rule.setContent { AppTheme {
            WorkoutConflictDialog(conflict, onDismiss = { conflict = null }, onResume = { resumes++ },
                onConfirmDiscard = { confirms++; conflict = WorkoutConflict(run, true) }, onDiscard = { drops++ })
        } }
        rule.onNodeWithText("Workout open").assertIsDisplayed()
        rule.onNodeWithText("Resume").assertIsDisplayed().performClick()
        assertEquals(1, resumes)
        rule.onNodeWithText("Discard and start").performClick()
        assertEquals(1, confirms)
        assertEquals(0, drops)
        rule.onNodeWithText("Discard and start").performClick()
        assertEquals(1, drops)
    }
}
