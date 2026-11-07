package cloud.trotter.log.strength.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.ViewConfiguration
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import cloud.trotter.log.strength.ui.TouchTargets.assertEveryTouchTargetIsAtLeast48dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp")
class TouchTargetsTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun fullyVisible48dpTargetPasses() {
        setScrollingTarget()

        composeTestRule.assertEveryTouchTargetIsAtLeast48dp()
    }

    @Test
    fun targetClippedAtTheTopDoesNotFailTheSizeCheck() {
        setScrollingTarget(scroll = 3.dp)

        assertVisibleHeight(45.dp)
        composeTestRule.assertEveryTouchTargetIsAtLeast48dp()
    }

    @Test
    fun targetClippedAtTheBottomDoesNotFailTheSizeCheck() {
        setScrollingTarget(topSpace = 51.dp)

        assertVisibleHeight(45.dp)
        composeTestRule.assertEveryTouchTargetIsAtLeast48dp()
    }

    @Test
    fun fullyVisibleTargetBelow48dpInHeightFails() {
        setScrollingTarget(height = 47.dp)

        assertThrows(AssertionError::class.java) {
            composeTestRule.assertEveryTouchTargetIsAtLeast48dp()
        }
    }

    @Test
    fun fullyVisibleTargetBelow48dpInWidthFails() {
        setScrollingTarget(width = 47.dp)

        assertThrows(AssertionError::class.java) {
            composeTestRule.assertEveryTouchTargetIsAtLeast48dp()
        }
    }

    private fun assertVisibleHeight(height: Dp) {
        val node = composeTestRule.onNodeWithTag("target").fetchSemanticsNode()
        with(composeTestRule.density) {
            assertEquals(48.dp.roundToPx(), node.size.height)
            assertEquals(height.toPx(), node.boundsInRoot.height, 0.01f)
            assertEquals(height.toPx(), node.touchBoundsInRoot.height, 0.01f)
        }
    }

    private fun setScrollingTarget(
        width: Dp = 48.dp,
        height: Dp = 48.dp,
        topSpace: Dp = 0.dp,
        scroll: Dp = 0.dp,
    ) {
        composeTestRule.setContent {
            val configuration = LocalViewConfiguration.current
            CompositionLocalProvider(LocalViewConfiguration provides object : ViewConfiguration by configuration {
                // Disable automatic expansion so undersized fixtures retain their actual targets.
                override val minimumTouchTargetSize = DpSize.Zero
            }) {
                val scrollPixels = with(LocalDensity.current) { scroll.roundToPx() }
                Column(Modifier.size(96.dp).verticalScroll(rememberScrollState(initial = scrollPixels))) {
                    Spacer(Modifier.height(topSpace))
                    Box(Modifier.size(width, height).testTag("target").semantics { onClick { true } })
                    Spacer(Modifier.height(96.dp))
                }
            }
        }
    }
}
