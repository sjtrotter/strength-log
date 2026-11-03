package cloud.trotter.log.strength.wear.ui

import android.os.SystemClock
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.InternalComposeApi
import androidx.compose.runtime.currentComposer
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.LocalSaveableStateRegistry
import androidx.compose.runtime.saveable.SaveableStateRegistry
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performRotaryScrollInput
import cloud.trotter.log.strength.domain.sync.CardioDelta
import cloud.trotter.log.strength.domain.sync.ExerciseSwapDelta
import cloud.trotter.log.strength.domain.sync.SetEditDelta
import cloud.trotter.log.strength.domain.sync.WatchDay
import cloud.trotter.log.strength.domain.sync.WatchExercise
import cloud.trotter.log.strength.domain.sync.WatchSet
import cloud.trotter.log.strength.domain.sync.WatchSnapshot
import cloud.trotter.log.strength.wear.theme.WearTrackerTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class WorkoutDialIntegrationTest {
    @get:Rule
    val compose = createComposeRule()

    private class Alarm : RestAlarmScheduler {
        var onAlarm: (() -> Unit)? = null
        override fun schedule(deadlineMillis: Long, onAlarm: () -> Unit) { this.onAlarm = onAlarm }
        override fun cancel() { onAlarm = null }
    }

    private class Registry(private val delegate: SaveableStateRegistry) : SaveableStateRegistry by delegate {
        val consumed = mutableMapOf<String, Any>()
        override fun consumeRestored(key: String): Any? = delegate.consumeRestored(key)?.also { consumed[key] = it }
    }

    private fun exercise(id: Long, timed: Boolean = false, firstDone: Boolean = false) = WatchExercise(
        programExerciseId = id, slot = "main", name = "Exercise $id", goal = 235.0, perHand = false,
        supersetPartnerName = null, tracking = if (timed) "timed" else "reps", exerciseId = "exercise-$id",
        sets = List(2) { WatchSet(235.0, 5, "TOP", firstDone && it == 0, seconds = if (timed) 45 else 0,
            restAfterSeconds = 150) }, ssSets = emptyList(),
    )

    private fun snapshot(vararg exercises: WatchExercise) = WatchSnapshot(
        revision = 1L, suggestedDayId = "A", day = WatchDay("A", "Lower", 0, exercises.toList()),
        unit = "lb", planId = 1L, runId = "r-1",
    )

    private fun legacyState(face: DialFace = DialFace.OVERVIEW) = linkedMapOf<String, Any?>(
        "planId" to 1L, "dayId" to "A", "runId" to "r-1", "face" to face.name, "lifting" to true,
        "startedAtWallMillis" to 100_000L, "startedAtElapsedMillis" to SystemClock.elapsedRealtime().coerceAtLeast(1L),
        "restDeadlineMillis" to 0L, "restTotalSeconds" to 0, "restBetweenExercises" to false,
        "restedSeconds" to NO_REST, "sessionStartedAtMillis" to 90_000L, "sessionEndedAtMillis" to 95_000L,
        "selectedExercise" to 0, "tickMemory" to "7:0=5@1",
    )

    // Invoke both private composables from the same parent group. Calling two
    // different wrapper composables would itself change the compound registry key.
    @OptIn(InternalComposeApi::class)
    private fun restoreThroughRegistry(
        snapshot: WatchSnapshot,
        legacy: Map<String, Any?>,
        controller: RestTimerController,
        scheduleTick: (RestTimerController.Firing, () -> Unit) -> Unit = { _, _ -> },
        sendEdit: (SetEditDelta) -> Unit = {},
    ): Pair<Registry, String> {
        mainWorkoutDialFixtureState = legacy
        val mounted = mutableStateOf(true)
        val fixture = mutableStateOf(true)
        val registry = mutableStateOf<SaveableStateRegistry>(SaveableStateRegistry(null) { true })
        val noAction: () -> Unit = {}
        val arguments = arrayOf<Any?>(
            snapshot, null, null, noAction, noAction, { _: DialHold -> }, { _: Int -> 0 },
            null, { _: TimedRepState? -> }, 4, noAction, noAction, 0, emptySet<Long>(), emptySet<Long>(),
            controller, scheduleTick, sendEdit, { _: ExerciseSwapDelta -> }, { _: CardioDelta -> },
            { _: Long -> }, noAction,
        )
        compose.setContent {
            CompositionLocalProvider(LocalSaveableStateRegistry provides registry.value) {
                WearTrackerTheme {
                    if (mounted.value) {
                        val facade = if (fixture.value) "MainWorkoutDialFixture" else "WearAppKt"
                        val method = Class.forName("cloud.trotter.log.strength.wear.ui.$facade")
                            .declaredMethods.single { it.name == "WorkoutDial" }
                        method.isAccessible = true
                        method.invoke(null, *arguments, currentComposer, 0, 0, 0)
                    }
                }
            }
        }
        var saved: Map<String, List<Any?>> = emptyMap()
        compose.runOnIdle {
            saved = registry.value.performSave()
            mounted.value = false
        }
        val key = saved.keys.single()
        val restored = Registry(SaveableStateRegistry(saved) { true })
        compose.runOnIdle {
            registry.value = restored
            fixture.value = false
            mounted.value = true
        }
        compose.runOnIdle { assertEquals(saved.getValue(key).single(), restored.consumed[key]) }
        return restored to key
    }

    private fun Registry.workout(key: String): Map<String, Any?> {
        val payload = performSave().getValue(key).single() as List<*>
        return payload.chunked(2).associate { (name, value) -> name as String to value }
    }

    @Test
    fun `main registry entry restores active timing rest selection and tick history`() {
        val alarm = Alarm()
        val controller = RestTimerController(alarm, SystemClock::elapsedRealtime) {}
        val legacy = legacyState().apply {
            this["restDeadlineMillis"] = SystemClock.elapsedRealtime() + 1_000_000L
            this["restTotalSeconds"] = 60
            this["restBetweenExercises"] = true
            this["selectedExercise"] = 1
        }
        val (registry, key) = restoreThroughRegistry(snapshot(exercise(7L), exercise(8L)), legacy, controller)
        compose.runOnIdle { assertEquals(legacy, registry.workout(key)) }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `timed hold captures selection at firing before the 250 ms settle delay`() {
        val alarm = Alarm()
        var elapsed = SystemClock.elapsedRealtime()
        val controller = RestTimerController(alarm, { elapsed }) {}
        val edits = mutableListOf<SetEditDelta>()
        var settle: (() -> Unit)? = null
        var firing: RestTimerController.Firing? = null
        val (registry, key) = restoreThroughRegistry(
            snapshot(exercise(7L, timed = true), exercise(8L, timed = true, firstDone = true), exercise(9L, timed = true)),
            legacyState(), controller,
            scheduleTick = { token, tick -> firing = token; settle = tick }, sendEdit = edits::add,
        )
        compose.onRoot().performRotaryScrollInput { rotateToScrollVertically(RotaryDetents.DETENT_PIXELS) }
        compose.runOnIdle {
            assertEquals(1, registry.workout(key)["selectedExercise"])
            elapsed = assertNotNull(controller.activeRest).deadlineMillis
            assertNotNull(alarm.onAlarm).invoke()
        }
        compose.onRoot().performRotaryScrollInput { rotateToScrollVertically(RotaryDetents.DETENT_PIXELS) }
        compose.runOnIdle {
            assertEquals(2, registry.workout(key)["selectedExercise"])
            assertTrue(assertNotNull(firing).isCurrent())
            assertTrue(edits.isEmpty())
        }
        compose.mainClock.advanceTimeBy(250L)
        compose.runOnIdle { assertNotNull(settle).invoke() }
        compose.runOnIdle {
            assertEquals(8L, edits.single().programExerciseId)
            assertEquals(1, edits.single().setIndex)
            assertEquals(TickRef(8L, 1), TickMemory.decode(registry.workout(key)["tickMemory"] as String).newestFirst().first())
        }
    }

    @Test
    fun `rest samples elapsed time after sending while completion keeps its earlier stamp`() {
        val alarm = Alarm()
        val controller = RestTimerController(alarm, SystemClock::elapsedRealtime) {}
        var elapsedAfterSend = 0L
        var wallAfterSend = 0L
        var sent: SetEditDelta? = null
        val (registry, key) = restoreThroughRegistry(snapshot(exercise(7L)), legacyState(DialFace.WORKOUT), controller,
            sendEdit = { delta ->
                sent = delta
                ShadowSystemClock.advanceBy(Duration.ofSeconds(3))
                elapsedAfterSend = SystemClock.elapsedRealtime()
                wallAfterSend = System.currentTimeMillis()
            })
        compose.onNode(SemanticsMatcher("log set") {
            it.config.getOrNull(SemanticsActions.OnClick)?.label == "log set"
        }).performClick()
        compose.runOnIdle {
            val edit = assertNotNull(sent)
            val state = registry.workout(key)
            assertEquals(edit.completedAtMillis, edit.editedAtMillis)
            assertEquals(edit.completedAtMillis, state["sessionEndedAtMillis"])
            assertTrue(assertNotNull(edit.completedAtMillis) <= wallAfterSend - 3_000L)
            assertTrue((state["restDeadlineMillis"] as Long) >= elapsedAfterSend + 150_000L)
            assertEquals(150, state["restTotalSeconds"])
        }
    }
}
