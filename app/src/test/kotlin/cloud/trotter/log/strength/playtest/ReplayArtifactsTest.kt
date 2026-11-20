package cloud.trotter.log.strength.playtest

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import cloud.trotter.log.strength.ui.theme.AppTheme
import java.io.File
import kotlin.test.assertFailsWith
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h720dp-night-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ReplayArtifactsTest {
    @get:Rule val compose = createComposeRule()
    @get:Rule val temporary = TemporaryFolder()

    @Test fun compactReplayWritesTextOnlyBetweenFullLaunchCheckpointAndFinalCaptures() {
        val artifacts = runArtifacts()
        captureSteps(artifacts, """[
            {"tap":{"text":"Next"}},
            {"checkpoint":"days-per-week"},
            {"tap":{"text":"Next"}},
            {"note":"Compact note capture"},
            {"expect":{"text":"Next"}}
        ]""")
        // catalog: listing beside every full capture
        assertFiles(artifacts, setOf(
            "000-launch.json", "000-launch.png", "000-launch.screen.txt", "000-tap-next.txt",
            "001-checkpoint-days-per-week.json", "001-checkpoint-days-per-week.png", "001-checkpoint-days-per-week.screen.txt",
            "002-tap-next.txt", "003-note.txt", "004-expect-next.json", "004-expect-next.png", "004-expect-next.screen.txt",
        ))
        val diff = File(artifacts.directory, "steps/000-tap-next.txt").readLines()
        assertEquals("000-tap-next", diff.first())
        assertTrue(diff.drop(1).all { it.startsWith("+ ") || it.startsWith("- ") })
        assertTrue(diff.any { it.startsWith("+ ") })
        assertTrue(diff.any { it.startsWith("- ") })
        assertEquals("003-note\n", File(artifacts.directory, "steps/003-note.txt").readText())
    }

    @Test fun compactReplayWritesFullFailureCaptureForAnIntermediateStep() {
        val artifacts = runArtifacts()
        val error = assertFailsWith<PlaytestError> {
            captureSteps(artifacts, """[
                {"tap":{"text":"Next"}},
                {"expect":{"text":"Missing control"},"timeoutMs":5000},
                {"note":"Unreached final step"}
            ]""")
        }
        assertEquals("not-found", error.kind)
        // catalog: listing beside every full capture
        assertFiles(artifacts, setOf(
            "000-launch.json", "000-launch.png", "000-launch.screen.txt", "000-tap-next.txt",
            "001-FAILED-expect-missing-control.json", "001-FAILED-expect-missing-control.png", "001-FAILED-expect-missing-control.screen.txt",
        ))
    }

    @Test fun compactReplayKeepsFullCheckpointAndFinalNoteBeforeCaptureCutoff() {
        val artifacts = runArtifacts(captureFrom = 100)
        captureSteps(artifacts, """[
            {"tap":{"text":"Next"}},
            {"checkpoint":"days-per-week"},
            {"note":"Final note"}
        ]""")
        // catalog: listing beside every full capture
        assertFiles(artifacts, setOf(
            "000-launch.json", "000-launch.png", "000-launch.screen.txt",
            "001-checkpoint-days-per-week.json", "001-checkpoint-days-per-week.png", "001-checkpoint-days-per-week.screen.txt",
            "002-note.json", "002-note.png", "002-note.screen.txt",
        ))
    }

    @Test fun listingRecordsTheActionsSinceThePreviousListing() {
        val artifacts = runArtifacts(captureFrom = 100)
        captureSteps(artifacts, """[
            {"tap":{"text":"Next"}},
            {"tap":{"text":"Next"}},
            {"checkpoint":"Two pages"}
        ]""")
        val listing = File(artifacts.directory, "steps/002-checkpoint-two-pages.screen.txt").readText()
        assertTrue(listing.startsWith("## Two pages\nafter: tap \"Next\"; tap \"Next\"\n"))
        assertTrue(listing.contains("  button \"Next\"\n"))
    }

    @Test fun probeReplayWritesAListingForEveryStep() {
        val artifacts = runArtifacts(captureFrom = 100)
        captureSteps(artifacts, """[
            {"tap":{"text":"Next"}},
            {"tap":{"text":"Missing"}},
            {"expect":{"text":"Next"}}
        ]""", probe = true)
        val files = checkNotNull(File(artifacts.directory, "steps").listFiles()).map { it.name }.toSet()
        listOf("000-tap-next", "001-SKIPPED-tap-missing", "002-expect-next").forEach { name ->
            assertTrue("$name.json" in files)
            assertTrue("$name.screen.txt" in files)
        }
        assertTrue(files.none { it.endsWith(".txt") && !it.endsWith(".screen.txt") })
        assertEquals(listOf(1), artifacts.optionalSkipped)
    }

    private fun runArtifacts(captureFrom: Int = 0): RunArtifacts =
        RunArtifacts(temporary.newFolder("artifacts"), captureFrom)

    private fun assertFiles(artifacts: RunArtifacts, expected: Set<String>) {
        val files = checkNotNull(File(artifacts.directory, "steps").listFiles())
        assertEquals(expected, files.map { it.name }.toSet())
        assertTrue(files.all { it.length() > 0 })
    }

    private fun captureSteps(artifacts: RunArtifacts, steps: String, probe: Boolean = false) {
        val probeOption = if (probe) "\"probe\":true," else ""
        val scriptFile = temporary.newFile("script.json").apply {
            writeText("""{"seat":"compact-artifacts","capture":"compact",${probeOption}"steps":$steps}""")
        }
        val script = Script.parse(scriptFile)
        artifacts.script = script
        val page = mutableStateOf(0)
        compose.setContent {
            AppTheme {
                Column {
                    Text("Page ${page.value}")
                    Button(onClick = { page.value++ }) { Text("Next") }
                }
            }
        }
        val captures = ReplayCaptures(compose, artifacts, script)
        val launchDeadline = StepDeadline(20_000)
        launchDeadline.run(compose) { captures.launch(launchDeadline) }
        script.steps.forEachIndexed { index, step ->
            val deadline = StepDeadline(step.timeoutMs, index)
            runCapturedStep(index, step, artifacts, captures, log = { _, _ -> }) {
                deadline.run(compose) {
                    when (step.action) {
                        "tap" -> {
                            Selectors(compose, compose.density.density).await(requireNotNull(step.selector), deadline)
                            compose.onNodeWithText(requireNotNull(step.selector?.text("text"))).performClick()
                        }
                        "expect" -> Selectors(compose, compose.density.density).await(requireNotNull(step.selector), deadline)
                        "checkpoint", "note" -> Unit
                        else -> error("Unsupported capture fixture action: ${step.action}")
                    }
                    captures.step(index, step, deadline)
                }
            }
        }
    }
}
