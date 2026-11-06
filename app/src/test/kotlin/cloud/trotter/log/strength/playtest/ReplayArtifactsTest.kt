package cloud.trotter.log.strength.playtest

import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import cloud.trotter.log.strength.MainActivity
import cloud.trotter.log.strength.data.db.StrengthDatabase
import cloud.trotter.log.strength.data.prefs.SettingsStore
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import java.io.File
import java.util.Locale
import java.util.TimeZone
import javax.inject.Inject
import kotlin.test.assertFailsWith
import kotlinx.coroutines.CoroutineExceptionHandler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@HiltAndroidTest
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h720dp-night-xxhdpi", application = HiltTestApplication::class,
    shadows = [PlaytestHandler::class])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ReplayArtifactsTest {
    private val hilt = HiltAndroidRule(this)
    private lateinit var crashes: AppCrashes
    private val compose = createEmptyComposeRule(effectContext = CoroutineExceptionHandler { _, error -> crashes.record(error) })
    private val temporary = TemporaryFolder()
    @get:Rule val rules: RuleChain = RuleChain.outerRule(hilt).around(temporary).around(compose)

    @Inject internal lateinit var time: PlaytestTime
    @Inject internal lateinit var cardioAlarm: PlaytestCardioAlarm
    @Inject lateinit var settings: SettingsStore
    @Inject lateinit var database: StrengthDatabase

    @Test fun compactReplayWritesTextOnlyBetweenFullLaunchCheckpointAndFinalCaptures() {
        val artifacts = runArtifacts()
        replay(artifacts, """[
            {"tap":{"text":"Next"}},
            {"checkpoint":"days-per-week"},
            {"tap":{"text":"Next"}},
            {"note":"Compact note capture"},
            {"expect":{"text":"Next"}}
        ]""")
        assertFiles(artifacts, setOf(
            "000-launch.json", "000-launch.png", "000-tap-next.txt",
            "001-checkpoint-days-per-week.json", "001-checkpoint-days-per-week.png",
            "002-tap-next.txt", "003-note.txt", "004-expect-next.json", "004-expect-next.png",
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
            replay(artifacts, """[
                {"tap":{"text":"Next"}},
                {"expect":{"text":"Missing control"},"timeoutMs":1000},
                {"note":"Unreached final step"}
            ]""")
        }
        assertEquals("not-found", error.kind)
        assertFiles(artifacts, setOf(
            "000-launch.json", "000-launch.png", "000-tap-next.txt",
            "001-FAILED-expect-missing-control.json", "001-FAILED-expect-missing-control.png",
        ))
    }

    @Test fun compactReplayKeepsFullCheckpointAndFinalNoteBeforeCaptureCutoff() {
        val artifacts = runArtifacts(captureFrom = 100)
        replay(artifacts, """[
            {"tap":{"text":"Next"}},
            {"checkpoint":"days-per-week"},
            {"note":"Final note"}
        ]""")
        assertFiles(artifacts, setOf(
            "000-launch.json", "000-launch.png",
            "001-checkpoint-days-per-week.json", "001-checkpoint-days-per-week.png",
            "002-note.json", "002-note.png",
        ))
    }

    private fun runArtifacts(captureFrom: Int = 0): RunArtifacts =
        RunArtifacts(temporary.newFolder("artifacts"), captureFrom)

    private fun assertFiles(artifacts: RunArtifacts, expected: Set<String>) {
        val files = checkNotNull(File(artifacts.directory, "steps").listFiles())
        assertEquals(expected, files.map { it.name }.toSet())
        assertTrue(files.all { it.length() > 0 })
    }

    private fun replay(artifacts: RunArtifacts, steps: String) {
        val scriptFile = temporary.newFile("script.json").apply {
            writeText("""{"seat":"compact-artifacts","capture":"compact","steps":$steps}""")
        }
        val script = Script.parse(scriptFile)
        artifacts.script = script
        val locale = Locale.getDefault()
        val zone = TimeZone.getDefault()
        val requestedSize = System.getProperty("robolectric.windowManager.useRequestedSize")
        try {
            Locale.setDefault(Locale.US)
            TimeZone.setDefault(TimeZone.getTimeZone(script.start.zone))
            System.setProperty("robolectric.windowManager.useRequestedSize", "true")
            hilt.inject()
            time.set(script.start.instant(), script.start.zone)
            compose.mainClock.autoAdvance = false
            AppCrashes().use { crashes ->
                this.crashes = crashes
                crashes.step { ActivityScenario.launch(MainActivity::class.java) }.use { scenario ->
                    Replay(compose, scenario, time, cardioAlarm, settings, database, artifacts, crashes).run(script)
                }
            }
        } finally {
            Locale.setDefault(locale)
            TimeZone.setDefault(zone)
            if (requestedSize == null) System.clearProperty("robolectric.windowManager.useRequestedSize")
            else System.setProperty("robolectric.windowManager.useRequestedSize", requestedSize)
        }
    }
}
