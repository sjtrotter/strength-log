package cloud.trotter.log.strength.ui.components

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.up
import cloud.trotter.log.strength.ui.theme.AppTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The ± segment's auto-repeat, pinned while #156 rewired what drives it.
 *
 * The repeat used to hang off `collectIsPressedAsState` — a second collector
 * coroutine per segment, and a recomposition every time a finger landed on the
 * app's most-multiplied control. It now reads the interaction source once,
 * directly, from an effect keyed on the source, which never changes. That last
 * part is the hazard: an effect that outlives the composition around it will
 * happily go on calling the `onClick` it closed over on its first pass.
 *
 * So these tests hold the stepper the way the day screen does — the emitted
 * value goes back into the state the stepper renders — and assert the exact
 * sequence that comes out. A stale callback still *fires*, and would still
 * satisfy a test that only counted calls; it cannot produce 12.5, 15.0, 17.5.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp")
class StepperLongPressTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    /** Emissions from a stepper that starts at 10.0 and steps by 2.5, wired
     *  like a real caller: what it emits becomes what it renders. */
    private fun setLiveStepper(emitted: MutableList<Double>) {
        composeTestRule.setContent {
            var value by remember { mutableDoubleStateOf(10.0) }
            AppTheme {
                Stepper(
                    value = value,
                    onValueChange = {
                        value = it
                        emitted += it
                    },
                    step = { 2.5 },
                )
            }
        }
    }

    @Test
    fun aTapStepsOnceFromTheValueOnScreen() {
        val emitted = mutableListOf<Double>()
        setLiveStepper(emitted)

        composeTestRule.onNodeWithContentDescription("increase").performTouchInput { down(center) }
        composeTestRule.onNodeWithContentDescription("increase").performTouchInput { up() }
        composeTestRule.waitForIdle()

        assertEquals(listOf(12.5), emitted)
    }

    @Test
    fun aHoldWaitsOutTheInitialDelayThenWalksTheValueUp() {
        val emitted = mutableListOf<Double>()
        composeTestRule.mainClock.autoAdvance = false
        setLiveStepper(emitted)
        composeTestRule.mainClock.advanceTimeBy(50)

        val segment = composeTestRule.onNodeWithContentDescription("increase")
        segment.performTouchInput { down(center) }

        // Short of the 400ms initial delay: a normal tap must never double-fire.
        composeTestRule.mainClock.advanceTimeBy(300)
        assertEquals("a hold shorter than the initial delay stepped anyway", emptyList<Double>(), emitted)

        // Past it, then several 90ms repeats. Each one must step from what the
        // last one produced — a repeat closed over a stale value would emit
        // 12.5 over and over.
        composeTestRule.mainClock.advanceTimeBy(200 + 90 * 6)
        assertTrue("holding past the initial delay never repeated", emitted.size >= 3)
        assertEquals(listOf(12.5, 15.0, 17.5), emitted.take(3))

        // Letting go stops it, and the release's own click is swallowed so a
        // long press doesn't tack on one extra step.
        val whileHeld = emitted.toList()
        segment.performTouchInput { up() }
        composeTestRule.mainClock.advanceTimeBy(500)
        assertEquals("the repeat outlived the finger", whileHeld, emitted)
    }
}
