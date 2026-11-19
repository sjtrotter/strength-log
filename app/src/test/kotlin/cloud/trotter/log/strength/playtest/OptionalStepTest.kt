package cloud.trotter.log.strength.playtest

import androidx.compose.material3.Text
import androidx.compose.ui.test.junit4.v2.createComposeRule
import cloud.trotter.log.strength.ui.theme.AppTheme
import java.io.File
import kotlin.test.assertFailsWith
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h720dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class OptionalStepTest {
    @get:Rule val compose = createComposeRule()
    @get:Rule val temporary = TemporaryFolder()

    private fun script(steps: String): Script = Script.parse(temporary.newFile("script.json").apply {
        writeText("""{"seat":"optional","steps":[$steps]}""")
    })

    @Test fun optionalNotFoundSkipsAndTheNextStepRuns() {
        compose.mainClock.autoAdvance = false
        compose.setContent { AppTheme { Text("Visible") } }
        val script = script("""{"tap":{"text":"Missing"},"optional":true,"timeoutMs":1000},{"expect":{"text":"Visible"}}""")
        val artifacts = RunArtifacts(temporary.newFolder("artifacts"), 0).apply { this.script = script }
        val captures = ReplayCaptures(compose, artifacts, script)
        val results = mutableListOf<Pair<Boolean, String?>>()
        script.steps.forEachIndexed { index, step ->
            artifacts.stepsRun++
            runCapturedStep(index, step, artifacts, captures, log = { ok, skipped -> results += ok to skipped }) {
                val deadline = StepDeadline(step.timeoutMs, index)
                deadline.run(compose) {
                    Selectors(compose, compose.density.density).await(requireNotNull(step.selector), deadline)
                }
            }
        }
        artifacts.writeSummary(PlaytestTime())
        val summary = Json.parseToJsonElement(File(artifacts.directory, "summary.json").readText()).jsonObject
        assertEquals(listOf(true to "not-found", true to null), results)
        assertEquals(JsonPrimitive(2), summary["steps_run"])
        assertEquals(JsonNull, summary["error_class"])
        assertEquals(JsonArray(listOf(JsonPrimitive(0))), summary["optional_skipped"])
        listOf("png", "json").forEach { suffix ->
            assertTrue(File(artifacts.directory, "steps/000-SKIPPED-tap-missing.$suffix").isFile)
        }
    }

    @Test fun optionalStepStillFailsOnAppCrash() {
        compose.setContent { AppTheme { Text("Visible") } }
        val script = script("""{"tap":{"text":"Visible"},"optional":true}""")
        val artifacts = RunArtifacts(temporary.newFolder("artifacts"), 0).apply { this.script = script }
        val captures = ReplayCaptures(compose, artifacts, script)
        val crash = PlaytestError("app-crash", "App failed")
        var ok = true
        assertSame(crash, assertFailsWith<PlaytestError> {
            runCapturedStep(0, script.steps.single(), artifacts, captures, log = { result, _ -> ok = result }) { throw crash }
        })
        assertEquals(false, ok)
        artifacts.writeSummary(PlaytestTime())
        val summary = Json.parseToJsonElement(File(artifacts.directory, "summary.json").readText()).jsonObject
        assertEquals(JsonPrimitive("app-crash"), summary["error_class"])
        assertEquals(JsonArray(emptyList()), summary["optional_skipped"])
    }
}
