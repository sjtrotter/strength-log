package cloud.trotter.log.strength.playtest

import android.graphics.BitmapFactory
import android.os.Looper
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import cloud.trotter.log.strength.domain.model.CardioActivity
import cloud.trotter.log.strength.ui.day.CardioBlockDraft
import cloud.trotter.log.strength.ui.day.CardioBlockPage
import cloud.trotter.log.strength.ui.theme.AppTheme
import java.io.File
import kotlin.test.assertFailsWith
import kotlinx.coroutines.CoroutineExceptionHandler
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

    @Test fun cardioActivityAnchorAllowsDropdownTap() {
        val draft = mutableStateOf(CardioBlockDraft.new(1, "A"))
        compose.setContent {
            AppTheme { CardioBlockPage(draft.value, { draft.value = it }, {}, {}) }
        }
        val anchor = compose.onNodeWithText("Road run")
        assertTrue(anchor.fetchSemanticsNode().config.contains(SemanticsProperties.EditableText))
        assertTrue(anchor.fetchSemanticsNode().dropdownAnchor())
        anchor.performClick()
        compose.onNodeWithText("Trail run").performClick()
        assertEquals(CardioActivity.TRAIL_RUN, draft.value.activity)
    }

    @Test fun plainTextFieldStillRefusesTap() {
        compose.setContent { AppTheme { OutlinedTextField("185", {}, label = { Text("Weight") }) } }
        val node = compose.onNodeWithText("185").fetchSemanticsNode()
        assertTrue(node.config.contains(SemanticsProperties.EditableText))
        assertFalse(node.dropdownAnchor())
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
