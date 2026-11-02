package cloud.trotter.log.strength.ui

import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import cloud.trotter.log.strength.ui.day.DayEditUiState
import cloud.trotter.log.strength.ui.day.withWorkoutSelection
import cloud.trotter.log.strength.ui.day.DayScreen
import cloud.trotter.log.strength.ui.day.CascadeLift
import cloud.trotter.log.strength.ui.day.SessionReceipt
import cloud.trotter.log.strength.ui.day.SessionReceiptScreen
import cloud.trotter.log.strength.ui.day.workoutActions
import cloud.trotter.log.strength.ui.day.workoutEditActions
import cloud.trotter.log.strength.ui.day.workoutDayFixture
import cloud.trotter.log.strength.ui.theme.AppTheme
import cloud.trotter.log.strength.ui.theme.ReadableWidth
import cloud.trotter.log.strength.ui.theme.chromeVerticalPadding
import cloud.trotter.log.strength.ui.theme.isShortWindow
import cloud.trotter.log.strength.ui.today.TodayActions
import cloud.trotter.log.strength.ui.today.TodayDay
import cloud.trotter.log.strength.ui.today.TodayScreen
import cloud.trotter.log.strength.ui.today.TodayUiState
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Pins the centred readable column on wide windows and compact fixed chrome on short ones. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class WindowSizeTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    @Config(qualifiers = "w1280dp-h800dp")
    fun todayUsesACentredReadableBandOnAWideWindow() {
        setTodayContent()

        val rootWidth = composeTestRule.onRoot().fetchSemanticsNode().size.width.toFloat()
        val dayLabel = composeTestRule.onNodeWithText("Strength · Day B").fetchSemanticsNode().boundsInRoot
        val start = composeTestRule.onNodeWithText("Start").fetchSemanticsNode().boundsInRoot
        val readable = with(composeTestRule.density) { ReadableWidth.toPx() }
        val gutters = with(composeTestRule.density) { 32.dp.toPx() }

        assertTrue(
            "start width ${start.width}px exceeded readable $readable px plus $gutters px padding",
            start.width <= readable + gutters,
        )
        assertTrue(
            "start escaped the readable band",
            start.left >= (rootWidth - readable) / 2f && start.right <= (rootWidth + readable) / 2f,
        )
        assertTrue(
            "Day label at ${dayLabel.left}..${dayLabel.right}px escaped the centred $readable px band",
            dayLabel.left >= (rootWidth - readable) / 2f && dayLabel.right <= (rootWidth + readable) / 2f,
        )
    }

    /**
     * The opaque receipt fills the window while its scrollable content keeps
     * the centred [ReadableWidth] cap, even on a tablet.
     */
    @Test
    @Config(qualifiers = "w1280dp-h800dp")
    fun theReceiptCoversTheWindowButItsContentKeepsTheReadableCap() {
        composeTestRule.setContent {
            AppTheme {
                SessionReceiptScreen(
                    receipt = SessionReceipt(
                        sessionId = 1L,
                        dayIndex = 0,
                        dayId = "A",
                        completedAt = 1_793_361_600_000L,
                        minutes = 52,
                        completedSetCount = 18,
                        totalSetCount = 18,
                        setCount = 18,
                        newTopSets = listOf(CascadeLift("Barbell Back Squat", "235", "245")),
                        nextDayLine = "Day B, Upper",
                    ),
                    onShare = {},
                    onFinishSession = {},
                    modifier = Modifier.testTag("receipt"),
                )
            }
        }

        val root = composeTestRule.onRoot().fetchSemanticsNode().size
        assertEquals("the receipt must cover the whole window", root,
            composeTestRule.onNodeWithTag("receipt").fetchSemanticsNode().size)
        val readable = with(composeTestRule.density) { ReadableWidth.toPx() }
        val headline = composeTestRule.onNodeWithText("Day A, done.").fetchSemanticsNode().boundsInRoot
        val back = composeTestRule.onNodeWithText("Done").fetchSemanticsNode().boundsInRoot

        assertTrue(
            "the ledger is ${back.right - headline.left}px wide, past the $readable px cap",
            back.right - headline.left <= readable,
        )
        assertTrue(
            "the ledger drifted off centre: ${headline.left}px left, ${root.width - back.right}px right",
            abs(headline.left - (root.width - back.right)) <= 2f,
        )
    }

    /**
     * The screen-level assertion below passes either way — it is a sanity check
     * on the result, not a pin on the cause. This is the pin: a landscape phone
     * window reads as short, and short chrome spends 4dp of rhythm, not 10.
     */
    @Test
    @Config(qualifiers = "w891dp-h360dp")
    fun aLandscapePhoneWindowReadsAsShortAndTightensItsChrome() {
        var short: Boolean? = null
        var padding: Dp? = null
        composeTestRule.setContent {
            short = isShortWindow()
            padding = chromeVerticalPadding()
        }
        composeTestRule.waitForIdle()

        assertEquals(true, short)
        assertEquals(4.dp, padding)
    }

    @Test
    @Config(qualifiers = "w411dp-h891dp")
    fun aPortraitPhoneWindowKeepsTheFullChromeRhythm() {
        var short: Boolean? = null
        var padding: Dp? = null
        composeTestRule.setContent {
            short = isShortWindow()
            padding = chromeVerticalPadding()
        }
        composeTestRule.waitForIdle()

        assertEquals(false, short)
        assertEquals(10.dp, padding)
    }

    @Test
    @Config(qualifiers = "w891dp-h360dp")
    fun shortLandscapeWindowKeepsTheDayAndPrimaryActionVisible() {
        setTodayContent()

        val rootHeight = composeTestRule.onRoot().fetchSemanticsNode().size.height.toFloat()
        val headerBottom = composeTestRule.onNodeWithText("Strength · Day B")
            .fetchSemanticsNode().boundsInRoot.bottom
        val start = composeTestRule.onNodeWithText("Start")
            .fetchSemanticsNode().boundsInRoot

        assertTrue(
            "day row bottom $headerBottom px escaped the $rootHeight px window",
            headerBottom <= rootHeight,
        )
        assertTrue(
            "primary action bottom ${start.bottom}px escaped the $rootHeight px window",
            start.bottom <= rootHeight,
        )
    }

    @Test
    @Config(qualifiers = "w891dp-h411dp")
    fun workoutLogAndAllSetsRemainReachableInAShortWindow() {
        composeTestRule.setContent {
            AppTheme { DayScreen(workoutDayFixture(), workoutActions(), DayEditUiState(), workoutEditActions()) }
        }
        assertFullyInsideWindow("Log 95 × 5")
        assertFullyInsideWindow("All sets")
    }

    @Test
    @Config(qualifiers = "w891dp-h360dp")
    fun runningCardioStopRemainsReachableInAShortWindow() {
        val state = workoutDayFixture().copy(
            selectedPageKey = "cardio",
            cardioRunning = true,
            cardio = cloud.trotter.log.strength.ui.day.runningCardioFixture(),
        )
        composeTestRule.setContent {
            AppTheme { DayScreen(state.withWorkoutSelection("cardio"), workoutActions(), DayEditUiState(), workoutEditActions()) }
        }
        assertFullyInsideWindow("Stop")
    }

    private fun assertFullyInsideWindow(label: String) {
        val root = composeTestRule.onRoot().fetchSemanticsNode().size
        val node = composeTestRule.onNodeWithText(label).assertIsDisplayed().fetchSemanticsNode()
        // Use the full layout size: clipped bounds alone can make a partial target look reachable.
        val position = node.positionInRoot
        assertTrue("$label extends outside the ${root.width} × ${root.height}px window",
            position.x >= 0f && position.y >= 0f &&
                position.x + node.size.width <= root.width && position.y + node.size.height <= root.height)
        val visible = node.boundsInRoot
        assertTrue("$label is clipped by its parent",
            abs(visible.width - node.size.width) <= 1f && abs(visible.height - node.size.height) <= 1f)
    }

    private fun setTodayContent() {
        composeTestRule.setContent {
            AppTheme { TodayScreen(state = todayState(), actions = todayActions()) }
        }
    }

    private fun todayState() = TodayUiState(
        hasProgram = true,
        upNext = listOf(TodayDay(1, "Strength", "B", 1, "Lower body")),
        fact = "Last trained Oct 28",
    )

    private fun todayActions() = TodayActions(
        onStart = {},
        onOpenSettings = {},
        onSetUpProgram = {},
    )
}
