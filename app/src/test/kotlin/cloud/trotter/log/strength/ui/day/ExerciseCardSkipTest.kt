package cloud.trotter.log.strength.ui.day

import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import cloud.trotter.log.strength.ui.theme.AppTheme
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * #156's central claim, as behaviour rather than as a stability bit: when the
 * ViewModel rebuilds the day, a card whose content did not change must not be
 * recomposed.
 *
 * [ScrollPathStabilityTest] covers the two preconditions — the compiler calls
 * [ExerciseCardState] stable, and the ViewModel rebuilds untouched cards equal.
 * This one runs the consequence: real [ExerciseCardState] values, through a
 * real [LazyColumn] carrying the day screen's own `key`/`contentType`, counting
 * how many composable bodies actually execute.
 *
 * **The control is the point.** A test that counts recompositions can pass
 * because nothing recomposed *at all* — because the list never re-executed its
 * items, or because the cards were never on screen. So every item renders two
 * probes side by side: one taking the production [ExerciseCardState], one
 * taking a deliberately-unstable holder of the same value. The unstable probe
 * must recompose for every card, which proves the items really were re-entered;
 * the stable one must recompose only for the card that changed.
 *
 * What it does not cover: [DayScreen]'s `ExerciseCard` is private, so this
 * measures the skip decision on the parameter that drives it rather than on
 * that composable itself. The compiler reports carry the rest — every one of
 * `ExerciseCard`'s parameters reads `stable` in `app-composables.txt`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp")
class ExerciseCardSkipTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Before
    fun resetCounters() {
        ProbeRuns.stable = 0
        ProbeRuns.unstable = 0
    }

    @Test
    fun rebuildingTheDayRecomposesOnlyTheCardThatChanged() {
        val cards = mutableStateOf(cards(count = CARD_COUNT, tickedFirstRow = false))
        composeTestRule.setContent {
            AppTheme {
                LazyColumn {
                    items(cards.value, key = { it.programExerciseId }, contentType = { "exercise" }) { card ->
                        StableProbe(card)
                        UnstableProbe(UnstableHolder(card))
                    }
                }
            }
        }
        composeTestRule.waitForIdle()
        // Every card has to be on screen, or "it didn't recompose" is vacuous.
        composeTestRule.onNodeWithText(titleOf(CARD_COUNT - 1)).assertIsDisplayed()

        ProbeRuns.stable = 0
        ProbeRuns.unstable = 0
        // Exactly what DayViewModel emits for one tick: a whole new list, every
        // card object freshly built, only the first one holding a new value.
        cards.value = cards(count = CARD_COUNT, tickedFirstRow = true)
        composeTestRule.waitForIdle()

        assertEquals(
            "the unstable control did not recompose for every card, so this test never re-entered the " +
                "items and proves nothing",
            CARD_COUNT,
            ProbeRuns.unstable,
        )
        assertEquals(
            "a tick on one card recomposed ${ProbeRuns.stable} cards instead of 1 — the untouched cards " +
                "are no longer skipping (#156)",
            1,
            ProbeRuns.stable,
        )
    }

    private companion object {
        const val CARD_COUNT = 4

        fun titleOf(index: Int) = "Lift ${index + 1}"

        fun cards(count: Int, tickedFirstRow: Boolean) = List(count) { index ->
            ExerciseCardState(
                programExerciseId = index.toLong() + 1,
                position = index,
                title = titleOf(index),
                isMain = false,
                isSuperset = false,
                hasWarmupHint = false,
                goalDisplay = "100",
                perHand = false,
                allDone = false,
                collapsed = true,
                collapsedSummary = "3 sets · GOAL 100",
                rows = List(3) { row ->
                    SetRowState(
                        index = row,
                        kindLabel = "${row + 1}",
                        isTop = false,
                        weightDisplay = 100.0,
                        reps = 5,
                        // Only the first card's first row moves.
                        done = tickedFirstRow && index == 0 && row == 0,
                    )
                },
            )
        }
    }
}

/** Plain fields, not snapshot state: these are counted, never rendered, and a
 *  snapshot read here would itself invalidate the probes. */
private object ProbeRuns {
    var stable = 0
    var unstable = 0
}

/** Top-level, so neither probe picks up the test class as an unstable receiver
 *  and stops being skippable for a reason that has nothing to do with the card. */
@Composable
private fun StableProbe(card: ExerciseCardState) {
    ProbeRuns.stable++
    Text(card.title)
}

/** The control's `var` is what makes it unstable — the compiler cannot promise
 *  the field it reads today is the one it reads next frame, so it compares
 *  instances instead of values, exactly as it did for every card before #156. */
private class UnstableHolder(var card: ExerciseCardState)

@Composable
private fun UnstableProbe(holder: UnstableHolder) {
    ProbeRuns.unstable++
    Text(holder.card.collapsedSummary)
}
