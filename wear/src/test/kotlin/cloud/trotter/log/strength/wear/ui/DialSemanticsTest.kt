package cloud.trotter.log.strength.wear.ui

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performCustomAccessibilityActionWithLabel
import androidx.compose.ui.test.performRotaryScrollInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import cloud.trotter.log.strength.domain.sync.WatchDay
import cloud.trotter.log.strength.domain.sync.WatchExercise
import cloud.trotter.log.strength.domain.sync.WatchSet
import cloud.trotter.log.strength.domain.sync.WatchSnapshot
import cloud.trotter.log.strength.wear.theme.WearTrackerTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DialSemanticsTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun `every dial tap is one named merged node and invokes its callback once`() {
        val actions = DialTap.entries.filterNot { it == DialTap.NONE }
        // Pinned copy: distinct, terse, and total over every tappable state.
        assertEquals(actions.toSet(), clickLabels.keys)
        assertEquals(clickLabels.size, clickLabels.values.distinct().size)
        val current = mutableStateOf(actions.first())
        var taps = 0
        composeTestRule.setContent {
            WearTrackerTheme { Dial(state(current.value), onTap = { taps++ }) }
        }

        actions.forEach { tap ->
            composeTestRule.runOnIdle { current.value = tap }
            val node = composeTestRule.onNode(hasClickLabel(clickLabels.getValue(tap)))
            node.assertHasClickAction().assertTextContains("ACTION")
            node.performClick()
            assertEquals("$tap callback count", actions.indexOf(tap) + 1, taps)
        }
    }

    @Test
    fun `undo long click exists only with a target and uses the hold callback`() {
        val target = UndoTarget(1, 2)
        val currentHold = mutableStateOf<DialHold?>(hold(target))
        var undos = 0
        var received: UndoTarget? = null
        composeTestRule.setContent {
            WearTrackerTheme {
                Dial(
                    state = state(DialTap.START_SET, hold = currentHold.value),
                    onTap = {},
                    onHoldComplete = { undos++; received = it.target },
                )
            }
        }

        composeTestRule.onNode(hasLongClickLabel("undo last set"))
            .performSemanticsAction(SemanticsActions.OnLongClick)
        assertEquals(1, undos)
        assertEquals(target, received)

        composeTestRule.runOnIdle { currentHold.value = null }
        composeTestRule.onNode(hasClickLabel("start set")).assertExists()
        composeTestRule.onAllNodes(hasLongClickLabel("undo last set")).assertCountEquals(0)
    }

    @Test
    fun `cardio hold exposes stop and log label`() {
        var action: DialHoldAction? = null
        val hold = DialHold(
            action = DialHoldAction.STOP_CARDIO,
            disc = DiscContent(DiscStyle.FLAT, emptyList()),
        )
        composeTestRule.setContent {
            WearTrackerTheme {
                Dial(
                    state = state(DialTap.NONE, hold),
                    onTap = {},
                    onHoldComplete = { action = it.action },
                )
            }
        }
        composeTestRule.onNode(hasLongClickLabel("stop and log cardio"))
            .performSemanticsAction(SemanticsActions.OnLongClick)
        assertEquals(DialHoldAction.STOP_CARDIO, action)
    }

    @Test
    fun `timed rep hold exposes cancel without a tap or cardio log action`() {
        var action: DialHoldAction? = null
        var taps = 0
        val hold = DialHold(DialHoldAction.STOP_TIMED_REP, DiscContent(DiscStyle.FLAT, emptyList()))
        composeTestRule.setContent {
            WearTrackerTheme {
                Dial(
                    state = state(DialTap.NONE, hold).copy(screen = DialScreen.TIMED_REP_RUNNING),
                    onTap = { taps++ },
                    onHoldComplete = { action = it.action },
                )
            }
        }
        composeTestRule.onNode(hasLongClickLabel("Stop rep timer"))
            .assertHasNoClickAction()
            .performSemanticsAction(SemanticsActions.OnLongClick)
        assertEquals(DialHoldAction.STOP_TIMED_REP, action)
        assertEquals(0, taps)
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `cancelled touch then crown change then touch tap logs the latest count`() {
        val attempt = mutableStateOf(newTimedRepAttempt().timeUp())
        var loggedReps: Int? = null
        composeTestRule.setContent {
            val current = attempt.value
            val crown = rememberCrownModifier(enabled = true) { detents ->
                attempt.value = current.turn(detents)
            }
            WearTrackerTheme {
                Dial(
                    state = timedRepDial(current),
                    modifier = crown,
                    onTap = { loggedReps = buildTimedRepDelta(current, timedRepSnapshot, 240_000L)?.reps },
                )
            }
        }

        val disc = composeTestRule.onNode(hasClickLabel("log set"))
        // Start pointer input before changing the callback's captured count.
        disc.performTouchInput { down(center); cancel() }
        composeTestRule.onRoot().performRotaryScrollInput {
            rotateToScrollVertically(38 * RotaryDetents.DETENT_PIXELS)
        }
        composeTestRule.runOnIdle { assertEquals(38, attempt.value.count) }
        disc.assertTextContains("38").performTouchInput { click() }
        composeTestRule.runOnIdle { assertEquals(38, loggedReps) }
    }

    @Test
    fun `a 550 ms press straddling expiry never finishes stop at 700 ms`() {
        composeTestRule.mainClock.autoAdvance = false
        val attempt = mutableStateOf(newTimedRepAttempt())
        var stops = 0
        var taps = 0
        composeTestRule.setContent {
            WearTrackerTheme {
                Dial(
                    state = timedRepDial(attempt.value),
                    onTap = { taps++ },
                    onHoldComplete = { stops++ },
                )
            }
        }
        composeTestRule.mainClock.advanceTimeByFrame()
        composeTestRule.onNode(hasLongClickLabel("Stop rep timer"))
            .performTouchInput { down(center) }
        composeTestRule.mainClock.advanceTimeBy(400L, ignoreFrameDuration = true)
        composeTestRule.runOnIdle { attempt.value = attempt.value.timeUp().turn(38) }
        composeTestRule.mainClock.advanceTimeBy(150L, ignoreFrameDuration = true)
        composeTestRule.onRoot().performTouchInput { advanceEventTime(550L); up() }
        composeTestRule.mainClock.advanceTimeBy(400L)
        composeTestRule.runOnIdle {
            assertEquals(0, stops)
            assertEquals(0, taps)
            assertEquals(TimedRepPhase.COUNT, attempt.value.phase)
            assertEquals(38, attempt.value.count)
        }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `count semantics increment and decrement within zero to 999 without logging`() {
        val attempt = mutableStateOf(newTimedRepAttempt().timeUp())
        var taps = 0
        composeTestRule.setContent {
            val current = attempt.value
            WearTrackerTheme {
                Dial(
                    state = timedRepDial(current),
                    onTap = { taps++ },
                    onRepCountChange = { attempt.value = current.turn(it) },
                )
            }
        }
        val disc = composeTestRule.onNode(hasClickLabel("log set"))
        disc.performCustomAccessibilityActionWithLabel("Decrease reps")
        disc.assertTextContains("0")
        disc.performCustomAccessibilityActionWithLabel("Increase reps")
        disc.assertTextContains("1")
        disc.performCustomAccessibilityActionWithLabel("Decrease reps")
        disc.assertTextContains("0")
        composeTestRule.runOnIdle { attempt.value = attempt.value.turn(999) }
        disc.performCustomAccessibilityActionWithLabel("Increase reps")
        disc.assertTextContains("999")
        disc.performCustomAccessibilityActionWithLabel("Decrease reps")
        disc.assertTextContains("998")
        composeTestRule.runOnIdle {
            assertEquals(998, attempt.value.count)
            assertEquals(0, taps)
        }
        // The actions belong only to count entry, never a running window.
        composeTestRule.runOnIdle { attempt.value = newTimedRepAttempt() }
        composeTestRule.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.CustomActions))
            .assertCountEquals(0)
    }

    @Test
    fun `none keeps merged copy without exposing a click action`() {
        composeTestRule.setContent {
            WearTrackerTheme { Dial(state(DialTap.NONE), onTap = {}) }
        }

        composeTestRule.onNodeWithText("ACTION").assertHasNoClickAction()
    }

    @Test
    fun `time pill is its own plain node and yields to a bottom band`() {
        val time = "12:58"
        val currentBottomBand = mutableStateOf<BandContent?>(null)
        composeTestRule.setContent {
            WearTrackerTheme {
                Dial(
                    state = state(DialTap.OPEN_WORKOUT).copy(bottomBand = currentBottomBand.value),
                    onTap = {},
                    timePillText = time,
                )
            }
        }

        composeTestRule.onNodeWithTag(TIME_PILL_TEST_TAG).assertExists()
        composeTestRule.onNodeWithText(time).assertExists().assertHasNoClickAction()
        composeTestRule.runOnIdle {
            currentBottomBand.value = BandContent("WORKOUT STATUS", DialTone.TERTIARY)
        }
        composeTestRule.onNodeWithTag(TIME_PILL_TEST_TAG).assertDoesNotExist()
        composeTestRule.onNodeWithText(time).assertDoesNotExist()
    }

    private fun state(tap: DialTap, hold: DialHold? = null) = DialUiState(
        screen = when (tap) {
            DialTap.OPEN_WORKOUT -> DialScreen.OVERVIEW
            DialTap.START_SET, DialTap.CONFIRM_SWAP, DialTap.NONE -> DialScreen.READY
            DialTap.TICK -> DialScreen.LIFTING
            DialTap.SKIP_REST -> DialScreen.REST
            DialTap.DISMISS -> DialScreen.DAY_DONE
            DialTap.START_CARDIO, DialTap.CARDIO_SET -> DialScreen.CARDIO_OFFER
        },
        accentIndex = 0,
        cycle = emptyList(),
        dayProgress = 0f,
        rounds = emptyList(),
        arc = null,
        topBand = null,
        bottomBand = null,
        disc = DiscContent(
            style = DiscStyle.FILLED,
            lines = listOf(DiscLine("ACTION", DialTextRole.DISC_LABEL, DialTone.ON_DISC)),
        ),
        bloom = false,
        tap = tap,
        hold = hold,
    )

    private fun hold(target: UndoTarget) = DialHold(
        action = DialHoldAction.UNDO,
        target = target,
        disc = DiscContent(DiscStyle.FLAT, emptyList()),
    )

    private val timedRepSnapshot = WatchSnapshot(
        revision = 1L, suggestedDayId = "A", unit = "lb", planId = 9L,
        day = WatchDay("A", "Test", 0, listOf(WatchExercise(
            programExerciseId = 7L, slot = "main", name = "Push-ups", goal = 0.0,
            perHand = false, supersetPartnerName = null,
            sets = listOf(WatchSet(0.0, 0, "WORK", false)), ssSets = emptyList(),
            exerciseId = "push-ups", tracking = "reps", repWindowSeconds = 120,
        ))),
    )

    private fun newTimedRepAttempt() = requireNotNull(startTimedRep(
        timedRepSnapshot, 0, false, null, 100_000L, 10_000L, 4,
    ))

    private fun timedRepDial(attempt: TimedRepState) = dialUiState(DialInputs(
        snapshot = timedRepSnapshot, exerciseIndex = 0, phase = SetPhase.READY,
        face = DialFace.WORKOUT, pendingCount = 0, rest = null,
        nowElapsedMillis = 10_000L, timedRep = attempt, timedRepDeadlineMillis = 130_000L,
    ))

    private val clickLabels = mapOf(
        DialTap.OPEN_WORKOUT to "open workout",
        DialTap.START_SET to "start set",
        DialTap.TICK to "log set",
        DialTap.SKIP_REST to "skip rest",
        DialTap.CONFIRM_SWAP to "confirm swap",
        DialTap.DISMISS to "dismiss",
        DialTap.START_CARDIO to "start finisher",
        DialTap.CARDIO_SET to "ACTION",
    )

    private fun hasClickLabel(label: String) = SemanticsMatcher("click label '$label'") { node ->
        node.config.getOrNull(SemanticsActions.OnClick)?.label == label
    }

    private fun hasLongClickLabel(label: String) = SemanticsMatcher("long-click label '$label'") { node ->
        node.config.getOrNull(SemanticsActions.OnLongClick)?.label == label
    }
}
