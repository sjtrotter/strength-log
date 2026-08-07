package cloud.trotter.log.strength.ui

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import cloud.trotter.log.strength.ui.day.ReceiptLift
import cloud.trotter.log.strength.ui.day.SessionReceipt
import cloud.trotter.log.strength.ui.day.SessionReceiptScrim
import cloud.trotter.log.strength.ui.theme.AppTheme
import cloud.trotter.log.strength.ui.theme.ReadableWidth
import cloud.trotter.log.strength.ui.theme.chromeVerticalPadding
import cloud.trotter.log.strength.ui.theme.isShortWindow
import cloud.trotter.log.strength.ui.today.RotationMark
import cloud.trotter.log.strength.ui.today.TodayActions
import cloud.trotter.log.strength.ui.today.TodayLift
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
        val today = composeTestRule.onNodeWithText("TODAY").fetchSemanticsNode().boundsInRoot
        val start = composeTestRule.onNodeWithText("START DAY B").fetchSemanticsNode().boundsInRoot
        val readable = with(composeTestRule.density) { ReadableWidth.toPx() }
        val gutters = with(composeTestRule.density) { 32.dp.toPx() }
        val leftGap = start.left
        val rightGap = rootWidth - start.right

        assertTrue(
            "start width ${start.width}px exceeded readable $readable px plus $gutters px padding",
            start.width <= readable + gutters,
        )
        assertTrue(
            "start gaps were $leftGap px left and $rightGap px right",
            abs(leftGap - rightGap) <= 2f,
        )
        assertTrue(
            "TODAY at ${today.left}..${today.right}px escaped the centred $readable px band",
            today.left >= (rootWidth - readable) / 2f && today.right <= (rootWidth + readable) / 2f,
        )
    }

    /**
     * The two post-DONE surfaces cover the whole window on purpose — they are
     * hiding one — but their content is a column of text like any other. Before
     * this cap the receipt was the single surface that went full-bleed on a
     * tablet, and it arrived the instant DONE fired, immediately after the day
     * screen behind it had capped itself.
     *
     * Coverage is asserted alongside the cap, because the fix must not have been
     * to shrink the scrim.
     */
    @Test
    @Config(qualifiers = "w1280dp-h800dp")
    fun theReceiptCoversTheWindowButItsLedgerKeepsTheReadableCap() {
        composeTestRule.setContent {
            AppTheme {
                SessionReceiptScrim(
                    receipt = SessionReceipt(
                        sessionId = 1L,
                        dayIndex = 0,
                        headline = "DAY A COMPLETE",
                        dayTitle = "Lower — squat focus",
                        setCount = 18,
                        strongest = ReceiptLift("Barbell Back Squat", "235×3"),
                        nextDayLine = "DAY B · UPPER",
                    ),
                    onShare = {},
                    onFinish = {},
                )
            }
        }

        val root = composeTestRule.onRoot().fetchSemanticsNode().size
        val readable = with(composeTestRule.density) { ReadableWidth.toPx() }
        val headline = composeTestRule.onNodeWithText("DAY A COMPLETE").fetchSemanticsNode().boundsInRoot
        val back = composeTestRule.onNodeWithText("BACK TO TODAY").fetchSemanticsNode().boundsInRoot

        assertTrue(
            "the ledger is ${back.right - headline.left}px wide, past the $readable px cap",
            back.right - headline.left <= readable,
        )
        assertTrue(
            "the ledger drifted off centre: ${headline.left}px left, ${root.width - back.right}px right",
            abs(headline.left - (root.width - back.right)) <= 2f,
        )
    }

    // CascadeScrim takes the same cap, and is deliberately not pinned here. Its
    // column wraps its content instead of filling the width, so the only thing
    // that could push it past 600dp is a long lift name — and Robolectric has no
    // real font metrics, so it measures every string at a fraction of its true
    // width. A test on that number would be green whether the cap existed or not.
    // The receipt above is layout-driven (a fillMaxWidth column, a weighted row)
    // and so measures the band for real; it is the pin for both.

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
    fun shortLandscapeWindowLeavesMostOfItsHeightForTheSummary() {
        setTodayContent()

        val rootHeight = composeTestRule.onRoot().fetchSemanticsNode().size.height.toFloat()
        val headerBottom = composeTestRule.onNodeWithText("TODAY")
            .fetchSemanticsNode().boundsInRoot.bottom
        val startTop = composeTestRule.onNodeWithText("START DAY B")
            .fetchSemanticsNode().boundsInRoot.top
        // More than half the measured window must remain between the two fixed chrome bands.
        val summaryHeight = startTop - headerBottom

        assertTrue(
            "summary height $summaryHeight px was not more than half of $rootHeight px",
            summaryHeight > rootHeight / 2f,
        )
    }

    private fun setTodayContent() {
        composeTestRule.setContent {
            AppTheme { TodayScreen(state = todayState(), actions = todayActions()) }
        }
    }

    private fun todayState() = TodayUiState(
        hasProgram = true,
        dayId = "B",
        dayIndex = 1,
        dayLine = "DAY B · LOWER",
        overline = "NEXT IN ROTATION",
        emphasisLine = "hip-hinge hamstrings",
        statLine = "5 LIFTS · 21 SETS",
        lifts = listOf(TodayLift("Barbell Back Squat", 5, isMain = true)),
        actionLabel = "START DAY B",
        rotation = listOf(RotationMark("B", 1, isNext = true)),
    )

    private fun todayActions() = TodayActions(
        onStart = {},
        onOpenSettings = {},
        onOpenLog = {},
        onSetUpProgram = {},
    )
}
