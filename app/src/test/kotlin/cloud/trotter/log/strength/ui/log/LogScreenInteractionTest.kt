package cloud.trotter.log.strength.ui.log

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import cloud.trotter.log.strength.ui.theme.AppTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Pins the session card's post-M3 interaction contract (PR #185): disclosure
 * lives on the header row — one owner per action — and SHARE is a sibling that
 * never also toggles the row. Before the migration the whole card toggled and
 * SHARE was nested inside it; these tests are the record of the new shape.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp")
class LogScreenInteractionTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun item(expanded: Boolean) = SessionListItem(
        sessionId = 7L,
        dateDisplay = "Jul 6, 2026",
        dayLetter = "A",
        dayIndex = 0,
        dayTitle = "DAY A · LOWER",
        setCount = 21,
        bodyweightDisplay = "185 lb",
        expanded = expanded,
        exerciseGroups = if (expanded) {
            listOf(SessionExerciseGroup("Back Squat", listOf(SessionSetSummary("TOP", "225×5"))))
        } else {
            null
        },
    )

    private fun setContent(
        expanded: Boolean,
        onToggle: (Long) -> Unit = {},
        onShare: (Long) -> Unit = {},
    ) {
        composeTestRule.setContent {
            AppTheme {
                LogScreen(
                    LogUiState(sessions = listOf(item(expanded))),
                    LogActions(
                        onBack = {},
                        onToggleExpanded = onToggle,
                        onPageCalendar = {},
                        onConnectHealth = {},
                        onApplyBodyweight = {},
                        onDismissBodyweight = {},
                        onShare = onShare,
                        onStartSession = {},
                        onSetUpProgram = {},
                    ),
                )
            }
        }
    }

    @Test
    fun tappingTheHeaderTogglesTheSession() {
        val toggled = mutableListOf<Long>()
        setContent(expanded = false, onToggle = { toggled += it })

        val header = composeTestRule.onNodeWithText("DAY A · LOWER")
        assertEquals(
            "Collapsed",
            header.fetchSemanticsNode().config.getOrNull(SemanticsProperties.StateDescription),
        )
        header.performClick()
        assertEquals(listOf(7L), toggled)
    }

    @Test
    fun shareIsASiblingActionThatNeverTogglesTheRow() {
        val toggled = mutableListOf<Long>()
        val shared = mutableListOf<Long>()
        setContent(expanded = true, onToggle = { toggled += it }, onShare = { shared += it })

        assertEquals(
            "Expanded",
            composeTestRule.onNodeWithText("DAY A · LOWER")
                .fetchSemanticsNode().config.getOrNull(SemanticsProperties.StateDescription),
        )
        composeTestRule.onNodeWithText("SHARE").performClick()
        assertEquals(listOf(7L), shared)
        assertEquals(emptyList<Long>(), toggled)
    }
}
