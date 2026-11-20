package cloud.trotter.log.strength.playtest

import android.graphics.BitmapFactory
import android.os.Looper
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.scrollBy
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.Popup
import androidx.test.espresso.Espresso
import cloud.trotter.log.strength.domain.model.CardioActivity
import cloud.trotter.log.strength.ui.components.AppModalBottomSheet
import cloud.trotter.log.strength.ui.day.CardioBlockDraft
import cloud.trotter.log.strength.ui.day.CardioBlockPage
import cloud.trotter.log.strength.ui.theme.AppTheme
import com.github.takahirom.roborazzi.InternalRoborazziApi
import com.github.takahirom.roborazzi.fetchRobolectricWindowRoots
import java.io.File
import kotlin.test.assertFailsWith
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h720dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ReplaySafetyTest {
    private lateinit var crashes: AppCrashes
    @get:Rule val compose = createComposeRule(effectContext = CoroutineExceptionHandler { _, error -> crashes.record(error) })
    @get:Rule val temporary = TemporaryFolder()

    @Test fun enabledSelectorFollowsTheMaterialButtonGate() {
        val enabled = mutableStateOf(false)
        compose.setContent { AppTheme {
            Button(onClick = {}, enabled = enabled.value, modifier = Modifier.testTag("gate")) { Text("End rest") }
        } }
        val selectors = Selectors(compose, compose.density.density)
        fun matches(value: Boolean): Boolean {
            val step = Step.parse(Json.parseToJsonElement("""{"expect":{"tag":"gate","enabled":$value}}"""))
            return selectors.matcher(requireNotNull(step.selector)).matches(compose.onNodeWithTag("gate").fetchSemanticsNode())
        }
        assertTrue(matches(false))
        assertFalse(matches(true))
        compose.runOnIdle { enabled.value = true }
        assertTrue(matches(true))
        assertFalse(matches(false))
    }

    @Test fun interactionSettleKeepsTheFakeWallClockAndPausedClockPolicy() {
        compose.mainClock.autoAdvance = false
        compose.setContent { AppTheme { Text("Stable screen") } }
        val time = PlaytestTime()
        val before = time.clock.instant()
        var frames = 0
        val deadline = StepDeadline(10_000)
        val snapshot = deadline.run(compose) {
            compose.settleSemantics(deadline, awaitPendingWork = { deadline.io {} }) { frames++ }
        }
        assertTrue(snapshot.captureTexts().contains(CaptureText("Stable screen")))
        assertTrue(frames >= 6)
        assertEquals(before, time.clock.instant())
        assertFalse(compose.mainClock.autoAdvance)
    }

    @Test fun interactionStorageWaitSharesTheStepDeadline() {
        compose.mainClock.autoAdvance = false
        compose.setContent { AppTheme { Text("Stable screen") } }
        val time = PlaytestTime()
        val before = time.clock.instant()
        val pending = CompletableDeferred<Unit>()
        var waited = false
        val deadline = StepDeadline(1_000)
        val error = assertFailsWith<PlaytestError> {
            deadline.run(compose) {
                compose.settleSemantics(deadline, awaitPendingWork = {
                    waited = true
                    deadline.io { pending.await() }
                }) {}
            }
        }
        assertTrue(waited)
        assertEquals("timeout", error.kind)
        assertEquals(before, time.clock.instant())
        assertFalse(compose.mainClock.autoAdvance)
    }

    @OptIn(ExperimentalMaterial3Api::class, InternalRoborazziApi::class)
    @Test fun consecutiveBacksWaitForSheetWindowRemovalBeforeLeavingTheScreen() {
        val requestedSize = System.getProperty("robolectric.windowManager.useRequestedSize")
        System.setProperty("robolectric.windowManager.useRequestedSize", "true")
        try {
            val workout = mutableStateOf(true)
            val sheet = mutableStateOf(false)
            val time = PlaytestTime()
            val before = time.clock.instant()
            compose.mainClock.autoAdvance = false
            compose.setContent {
                AppTheme {
                    BackHandler(enabled = workout.value) { workout.value = false }
                    Text(if (workout.value) "Workout" else "Today")
                    if (sheet.value) {
                        AppModalBottomSheet(
                            onDismissRequest = { sheet.value = false },
                            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
                        ) { Text("Lift list") }
                    }
                }
            }
            fun settle() {
                val deadline = StepDeadline(10_000)
                deadline.run(compose) {
                    compose.settleSemantics(deadline, awaitPendingWork = { deadline.io {} }) {}
                }
            }
            settle()
            val activityWindows = compose.runOnUiThread { fetchRobolectricWindowRoots().map { it.decorView }.toSet() }
            compose.runOnUiThread { sheet.value = true }
            settle()
            compose.onNodeWithText("Lift list").assertExists()
            assertTrue(compose.runOnUiThread { fetchRobolectricWindowRoots().size } > activityWindows.size)

            Espresso.pressBackUnconditionally()
            settle()
            assertFalse(sheet.value)
            assertTrue(workout.value)
            assertEquals(activityWindows, compose.runOnUiThread { fetchRobolectricWindowRoots().map { it.decorView }.toSet() })

            Espresso.pressBackUnconditionally()
            settle()
            assertFalse(workout.value)
            compose.onNodeWithText("Today").assertExists()
            assertEquals(before, time.clock.instant())
            assertFalse(compose.mainClock.autoAdvance)
        } finally {
            if (requestedSize == null) System.clearProperty("robolectric.windowManager.useRequestedSize")
            else System.setProperty("robolectric.windowManager.useRequestedSize", requestedSize)
        }
    }

    @Test fun cardioActivityAnchorAllowsDropdownTap() {
        val draft = mutableStateOf(CardioBlockDraft.new(1, "A"))
        compose.setContent {
            AppTheme { CardioBlockPage(draft.value, { draft.value = it }, {}, {}) }
        }
        val anchor = compose.onNodeWithText("Road run")
        assertTrue(anchor.fetchSemanticsNode().config.contains(SemanticsProperties.EditableText))
        assertEquals(Role.DropdownList, anchor.fetchSemanticsNode().config[SemanticsProperties.Role])
        assertTrue(anchor.fetchSemanticsNode().opensOnTap())
        perform("""{"tap":{"text":"Road run"}}""")
        compose.onNodeWithText("Trail run").performClick()
        assertEquals(CardioActivity.TRAIL_RUN, draft.value.activity)
    }

    @Test fun plainTextFieldStillRefusesTap() {
        compose.setContent { AppTheme { OutlinedTextField("185", {}, label = { Text("Weight") }) } }
        val node = compose.onNodeWithText("185").fetchSemanticsNode()
        assertTrue(node.config.contains(SemanticsProperties.EditableText))
        assertFalse(node.opensOnTap())
        val error = assertFailsWith<PlaytestError> { perform("""{"tap":{"text":"Weight"}}""") }
        assertEquals("script", error.kind)
        assertTrue(requireNotNull(error.message).contains("Use type instead of tap"))
    }

    @Test fun readOnlyPickerFieldAllowsTap() {
        var opened = false
        compose.setContent { AppTheme {
            OutlinedTextField("17:05", {}, readOnly = true, label = { Text("Start time") },
                modifier = Modifier.semantics { onClick { opened = true; true } })
        } }
        perform("""{"tap":{"text":"Start time"}}""")
        assertTrue(opened)
    }

    @Test fun readOnlyPickerFieldRefusesTypeWithPickerHint() {
        compose.setContent { AppTheme {
            OutlinedTextField("17:05", {}, readOnly = true, label = { Text("Start time") },
                modifier = Modifier.semantics { onClick { true } })
        } }
        val error = assertFailsWith<PlaytestError> {
            perform("""{"type":{"text":"Start time","value":"16:10"}}""")
        }
        assertEquals("not-editable", error.kind)
        assertTrue(requireNotNull(error.message).contains("this field opens a picker; tap it instead"))
    }

    @Test fun dialogLazySearchLeavesUnderlyingListAtItsOriginalPosition() {
        assertOverlaySearch(inDialog = true)
    }

    @Test fun unscopedLazySearchStaysInTopmostDialog() {
        assertOverlaySearch(inDialog = false)
    }

    @Test fun unscopedLazySearchStaysInTopmostPopup() {
        assertOverlaySearch(inDialog = false, popup = true)
    }

    private fun assertOverlaySearch(inDialog: Boolean, popup: Boolean = false) {
        val underneath = LazyListState()
        compose.mainClock.autoAdvance = false
        compose.setContent { AppTheme {
            LazyColumn(state = underneath, modifier = Modifier.height(160.dp)) {
                items(40) { Text("Behind $it", Modifier.height(48.dp)) }
            }
            if (popup) {
                Popup { LazyColumn(Modifier.height(160.dp)) {
                    items(40) { Text("Overlay $it", Modifier.height(48.dp)) }
                } }
            } else {
                Dialog(onDismissRequest = {}) { LazyColumn(Modifier.height(160.dp)) {
                    items(40) { Text("Overlay $it", Modifier.height(48.dp)) }
                } }
            }
        } }
        val scope = if (inDialog) ",\"inDialog\":true" else ""
        assertTrue(compose.onAllNodesWithText("Overlay 30").fetchSemanticsNodes().isEmpty())
        perform("""{"scrollTo":{"text":"Overlay 30"$scope}}""")
        assertTrue(compose.onNodeWithText("Overlay 30").fetchSemanticsNode().visibleForTouch())
        listOf("Behind 30", "Behind 0").forEach { label ->
            val error = assertFailsWith<PlaytestError> {
                perform("""{"scrollTo":{"text":"$label"$scope},"timeoutMs":10000}""")
            }
            assertEquals("not-found", error.kind)
        }
        assertEquals(0, underneath.firstVisibleItemIndex)
        assertEquals(0, underneath.firstVisibleItemScrollOffset)
    }

    @Test fun lazySearchContinuesAfterAContainerIsDisposed() {
        val visible = mutableStateOf(true)
        compose.mainClock.autoAdvance = false
        compose.setContent { AppTheme {
            Column {
                if (visible.value) LazyColumn(Modifier.height(120.dp).semantics {
                    scrollBy { _, _ -> visible.value = false; true }
                }) { items(40) { Text("Removed $it", Modifier.height(48.dp)) } }
                LazyColumn(Modifier.height(120.dp)) {
                    items(40) { Text("Remaining $it", Modifier.height(48.dp)) }
                }
            }
        } }
        perform("""{"scrollTo":{"text":"Remaining 30"}}""")
        assertFalse(visible.value)
        assertTrue(compose.onNodeWithText("Remaining 30").fetchSemanticsNode().visibleForTouch())
    }

    @Test fun filledWorkoutActionTapWaitsForTheFrameClockGuard() {
        assertWorkoutAction("workout-action-filled", "tap")
    }

    @Test fun tonalWorkoutActionTapWaitsForTheFrameClockGuard() {
        assertWorkoutAction("workout-action-tonal", "tap")
    }

    @Test fun workoutActionLongPressWaitsForTheFrameClockGuard() {
        assertWorkoutAction("workout-action-tonal", "longPress")
    }

    private fun assertWorkoutAction(tag: String, action: String) {
        val label = mutableStateOf("Start")
        val guarded = mutableStateOf(false)
        var delivered = 0
        compose.mainClock.autoAdvance = false
        compose.setContent { AppTheme {
            LaunchedEffect(label.value) {
                guarded.value = true
                delay(600L)
                guarded.value = false
            }
            val onAction: () -> Unit = { if (!guarded.value) delivered++ }
            Box(Modifier.size(64.dp).testTag(tag).combinedClickable(
                role = Role.Button, onClick = onAction, onLongClick = onAction,
            )) { Text(label.value) }
        } }
        val selectors = Selectors(compose, compose.density.density)
        fun awaitLabel(text: String) {
            val step = Step.parse(Json.parseToJsonElement("""{"expect":{"text":"$text"}}"""))
            val deadline = StepDeadline(10_000)
            deadline.run(compose) { selectors.await(requireNotNull(step.selector), deadline) }
        }
        awaitLabel("Start")
        compose.runOnUiThread { label.value = "Log set" }
        awaitLabel("Log set")
        compose.onNodeWithText("Log set").assertExists()
        assertTrue(guarded.value)
        val before = compose.mainClock.currentTime
        perform("""{"$action":{"tag":"$tag"}}""")
        assertEquals(1, delivered)
        assertFalse(guarded.value)
        assertTrue(compose.mainClock.currentTime - before >= 700L)
        assertFalse(compose.mainClock.autoAdvance)
    }

    private fun perform(json: String) {
        val step = Step.parse(Json.parseToJsonElement(json))
        val deadline = StepDeadline(step.timeoutMs)
        val actions = ReplayActions(compose, Selectors(compose, compose.density.density))
        deadline.run(compose) {
            if (step.action == "scrollTo") actions.scrollTo(requireNotNull(step.selector), deadline)
            else actions.act(step, deadline)
        }
    }

    @Test fun zeroSizeAndOffScreenNodesRequireScrolling() {
        compose.setContent {
            Column {
                Box(Modifier.size(0.dp).testTag("empty").clickable {})
                Column(Modifier.height(120.dp).verticalScroll(rememberScrollState())) {
                    Spacer(Modifier.height(500.dp))
                    Box(Modifier.size(48.dp).testTag("target").clickable {})
                }
            }
        }
        assertFalse(compose.onNodeWithTag("empty").fetchSemanticsNode().visibleForTouch())
        val target = compose.onNodeWithTag("target")
        assertFalse(target.fetchSemanticsNode().visibleForTouch())
        target.performScrollTo()
        assertTrue(target.fetchSemanticsNode().visibleForTouch())
    }

    @Test fun screenshotsEncodeSingleAndMultipleWindows() {
        compose.mainClock.autoAdvance = false
        val dialog = mutableStateOf(false)
        compose.setContent {
            AppTheme {
                Text("Background")
                if (dialog.value) Dialog(onDismissRequest = {}) { Text("Dialog content") }
            }
        }
        fun capture(name: String) {
            assertFalse(compose.mainClock.autoAdvance)
            val file = File(temporary.root, "$name.png")
            compose.writePlaytestShot(file, compose.density.density)
            val bitmap = checkNotNull(BitmapFactory.decodeFile(file.absolutePath))
            try {
                assertTrue(bitmap.width > 0 && bitmap.height > 0)
            } finally {
                bitmap.recycle()
            }
            val dump = File(temporary.root, "$name.json").readText()
            assertTrue(dump.contains("Background"))
            if (dialog.value) assertTrue(dump.contains("Dialog content"))
        }
        capture("single")
        compose.runOnIdle { dialog.value = true }
        compose.waitUntil(5_000) {
            compose.mainClock.advanceTimeByFrame()
            shadowOf(Looper.getMainLooper()).idle()
            compose.onAllNodesWithText("Dialog content").fetchSemanticsNodes()
                .singleOrNull()?.visibleForTouch() == true
        }
        capture("dialog")
        capture("dialog-again")
    }

    @Test fun launchedEffectFailureFailsTheStep() {
        val crash = IllegalStateException("Compose effect failed")
        compose.mainClock.autoAdvance = false
        AppCrashes().use { crashes ->
            this.crashes = crashes
            val error = assertFailsWith<PlaytestError> {
                crashes.step(compose) {
                    compose.setContent {
                        LaunchedEffect(Unit) { throw crash }
                        Text("Screen")
                    }
                    compose.mainClock.advanceTimeByFrame()
                    compose.onNodeWithText("Screen").assertExists()
                }
            }
            assertEquals("app-crash", error.kind)
            assertSame(crash, error.cause)
            val json = failureJson(error, 0)
            assertEquals(JsonPrimitive(crash.javaClass.name), json["exception_class"])
            assertEquals(JsonPrimitive(crash.message), json["exception_message"])
            assertTrue((json["exception_stack"] as JsonArray).size in 1..12)
        }
    }
}
