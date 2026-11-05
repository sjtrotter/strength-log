package cloud.trotter.log.strength.ui.components

import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import cloud.trotter.log.strength.domain.model.NOTE_MAX_LENGTH
import cloud.trotter.log.strength.ui.theme.AppTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp")
class NoteSheetTest {
    @get:Rule val rule = createComposeRule()

    @Test fun typingPastTheLimitKeeps120CharactersAndShowsTheCounter() {
        var saved: String? = null
        rule.setContent { AppTheme { NoteSheet("", { saved = it }, {}) } }
        rule.onNodeWithText("0 / 120").assertExists()
        rule.onNode(hasSetTextAction()).performTextInput("x".repeat(NOTE_MAX_LENGTH + 10))
        rule.onNodeWithText("120 / 120").assertExists()
        rule.onNodeWithText("x".repeat(NOTE_MAX_LENGTH)).assertExists()
        rule.onNodeWithText("Save note").performClick()
        rule.runOnIdle { assertEquals("x".repeat(NOTE_MAX_LENGTH), saved) }
    }

    @Test fun existingNoteStartsWithItsCharacterCount() {
        rule.setContent { AppTheme { NoteSheet("Seat 4", {}, {}) } }
        rule.onNodeWithText("Seat 4").assertExists()
        rule.onNodeWithText("6 / 120").assertExists()
    }
}
