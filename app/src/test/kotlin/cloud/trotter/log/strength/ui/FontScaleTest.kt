package cloud.trotter.log.strength.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import cloud.trotter.log.strength.domain.units.WeightStepper
import cloud.trotter.log.strength.domain.units.WeightUnit
import cloud.trotter.log.strength.ui.components.SetRow
import cloud.trotter.log.strength.ui.components.Stepper
import cloud.trotter.log.strength.ui.components.DAY_INDEX_LETTER_TAG
import cloud.trotter.log.strength.ui.components.DayIndex
import cloud.trotter.log.strength.ui.components.DayIndexSize
import cloud.trotter.log.strength.ui.day.DayTab
import cloud.trotter.log.strength.ui.theme.AppTheme
import cloud.trotter.log.strength.ui.theme.accentSoft
import cloud.trotter.log.strength.ui.theme.dayAccent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Pins the text-bearing controls whose old fixed bounds clipped enlarged system type. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp")
class FontScaleTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun stepperGrowsPastItsOldFloorAtDoubleFontScale() {
        val actual = heightAt(2f) {
            Stepper(
                value = 135.0,
                onValueChange = {},
                step = { WeightStepper.increment(it, WeightUnit.LB) },
                format = WeightStepper::format,
                round = { WeightStepper.round(it, WeightUnit.LB) },
                decreaseDescription = "Decrease weight",
                increaseDescription = "Increase weight",
            )
        }
        val floor = with(composeTestRule.density) { 40.dp.roundToPx() }

        assertTrue(
            "Stepper height $actual px did not exceed its 40dp/$floor px floor",
            actual > floor,
        )
    }

    @Test
    fun dayTabGrowsPastItsOldFloorAtDoubleFontScale() {
        // DayTab is both the composable and its state class; the import carries
        // both and each call resolves by shape, as it does inside DayScreen.kt.
        val actual = heightAt(2f) {
            DayTab(tab = DayTab("A", 0, isSuggested = false, isSelected = true), onClick = {})
        }
        val floor = with(composeTestRule.density) { 40.dp.roundToPx() }

        assertTrue(
            "DayTab height $actual px did not exceed its 40dp/$floor px floor",
            actual > floor,
        )
    }

    @Test
    fun weightedSetRowGrowsPastItsPrimaryFloorAtLargeFontScale() {
        val actual = heightAt(1.3f) {
            SetRow(
                kindLabel = "R1",
                accent = dayAccent(0),
                accentSoft = accentSoft(0),
                weight = 135.0,
                onWeightChange = {},
                weightStep = { WeightStepper.increment(it, WeightUnit.LB) },
                weightFormat = WeightStepper::format,
                weightRound = { WeightStepper.round(it, WeightUnit.LB) },
                reps = 5,
                onRepsChange = {},
            )
        }
        val floor = with(composeTestRule.density) { 52.dp.roundToPx() }

        assertTrue(
            "SetRow height $actual px did not exceed its 52dp/$floor px floor",
            actual > floor,
        )
    }

    @Test
    fun largeDayIndexKeepsItsFixedBoundsAndContainsTheLetterAtDoubleFontScale() {
        composeTestRule.setContent {
            val base = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(base.density, 2f)) {
                AppTheme { DayIndex(dayIndex = 0, letter = "A", size = DayIndexSize.Large) }
            }
        }

        val indexNode = composeTestRule.onNodeWithContentDescription("Day A").assertIsDisplayed()
        val letterNode = composeTestRule.onNodeWithTag(DAY_INDEX_LETTER_TAG).assertIsDisplayed()
        val index = indexNode.fetchSemanticsNode().boundsInRoot
        val letter = letterNode.fetchSemanticsNode().boundsInRoot
        val expectedWidth = with(composeTestRule.density) { 112.dp.toPx() }
        val expectedHeight = with(composeTestRule.density) { 128.dp.toPx() }

        assertEquals(expectedWidth, index.width, 0.5f)
        assertEquals(expectedHeight, index.height, 0.5f)
        assertTrue("letter $letter escaped day index $index", letter.left >= index.left)
        assertTrue("letter $letter escaped day index $index", letter.top >= index.top)
        assertTrue("letter $letter escaped day index $index", letter.right <= index.right)
        assertTrue("letter $letter escaped day index $index", letter.bottom <= index.bottom)
    }

    private fun heightAt(scale: Float, content: @Composable () -> Unit): Int {
        composeTestRule.setContent {
            val base = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(base.density, scale)) {
                AppTheme { content() }
            }
        }
        return composeTestRule.onRoot().fetchSemanticsNode().size.height
    }
}
