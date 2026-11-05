package cloud.trotter.log.strength.playtest

import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import cloud.trotter.log.strength.MainActivity
import cloud.trotter.log.strength.audit.writeShot
import cloud.trotter.log.strength.data.db.StrengthDatabase
import cloud.trotter.log.strength.data.prefs.SettingsStore
import cloud.trotter.log.strength.transfer.backup.BackupService
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import java.io.File
import java.util.Locale
import java.util.TimeZone
import javax.inject.Inject
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@HiltAndroidTest
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h720dp-night-xxhdpi", application = HiltTestApplication::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PlaytestReplayTest {
    private val hilt = HiltAndroidRule(this)
    private val compose = createEmptyComposeRule()
    @get:Rule val rules: RuleChain = RuleChain.outerRule(hilt).around(compose)

    @Inject internal lateinit var time: PlaytestTime
    @Inject internal lateinit var cardioAlarm: PlaytestCardioAlarm
    @Inject lateinit var backup: BackupService
    @Inject lateinit var settings: SettingsStore
    @Inject lateinit var database: StrengthDatabase

    @Test fun replay() {
        val path = System.getProperty("playtest.script").orEmpty()
        assumeTrue(path.isNotEmpty())
        val captureFromText = System.getProperty("playtest.captureFrom", "0")
        val artifacts = RunArtifacts(File(requireNotNull(System.getProperty("playtest.dir"))), captureFromText.toIntOrNull() ?: 0)
        val originalLocale = Locale.getDefault()
        val originalTimeZone = TimeZone.getDefault()
        var failure: Throwable? = null
        var launchAttempted = false
        var backupAttempted = false
        startRunWatchdog(artifacts)
        fun writeBackup() {
            backupAttempted = true
            artifacts.writeSummary(if (this::time.isInitialized) time else null)
            try {
                if (!this::backup.isInitialized) hilt.inject()
                artifacts.writeBackup(backup)
            } catch (exportError: Throwable) {
                artifacts.backupFailure(exportError.playtestError())
                throw exportError
            } finally {
                artifacts.writeSummary(if (this::time.isInitialized) time else null)
            }
        }
        try {
            scriptCheck(captureFromText.toIntOrNull()?.let { it >= 0 } == true, "playtestCaptureFrom must be a nonnegative integer")
            val script = Script.parse(File(path))
            artifacts.script = script
            try {
                RuntimeEnvironment.setQualifiers(script.device)
            } catch (error: IllegalArgumentException) {
                throw PlaytestError("script", "Invalid device qualifiers: ${script.device}", cause = error)
            }
            Locale.setDefault(Locale.US)
            TimeZone.setDefault(TimeZone.getTimeZone(script.start.zone))
            hilt.inject()
            time.set(script.start.instant(), script.start.zone)
            script.start.restore?.let { file ->
                try {
                    waitForStorage(60_000) { backup.import(file.readText()) }
                } catch (error: PlaytestError) {
                    // The import may still be committing; an export now could mix old and new state.
                    backupAttempted = true
                    artifacts.backupFailure(PlaytestError("timeout", "Restore did not finish, so no backup was exported"))
                    throw error
                } catch (error: Exception) {
                    throw PlaytestError("script", "Cannot restore ${file.path}: ${error.message}", cause = error)
                }
            }
            compose.mainClock.autoAdvance = false
            launchAttempted = true
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                var replayFailure: Throwable? = null
                try {
                    Replay(compose, scenario, time, cardioAlarm, settings, database, artifacts).run(script)
                } catch (error: Throwable) {
                    replayFailure = error
                    artifacts.failure(error.playtestError(), null)
                    throw error
                } finally {
                    // Export while the foreground graph is still alive, including on a failed step.
                    try {
                        writeBackup()
                    } catch (exportError: Throwable) {
                        val original = replayFailure
                        if (original != null) original.addSuppressed(exportError) else throw exportError
                    }
                }
            }
        } catch (error: Throwable) {
            failure = error
            if (launchAttempted && artifacts.stepsRun == 0) {
                try {
                    StepDeadline(1_000).run(compose) {
                        compose.writeShot(File(artifacts.directory, "steps/000-FAILED-launch.png"), compose.density.density)
                    }
                } catch (captureError: Throwable) {
                    error.addSuppressed(captureError)
                }
            }
            artifacts.failure(error.playtestError(), null)
            throw error
        } finally {
            try {
                if (!backupAttempted) writeBackup()
            } catch (exportError: Throwable) {
                val original = failure
                if (original != null) original.addSuppressed(exportError) else {
                    artifacts.failure(exportError.playtestError(), null)
                    throw exportError
                }
            } finally {
                try {
                    artifacts.writeSummary(if (this::time.isInitialized) time else null)
                } finally {
                    Locale.setDefault(originalLocale)
                    TimeZone.setDefault(originalTimeZone)
                }
            }
        }
    }

    /** Any wait that cannot be interrupted (a looper drain that never empties, a blocked callback)
     *  still ends with a summary before the workflow kills the job. */
    private fun startRunWatchdog(artifacts: RunArtifacts) {
        Thread({
            try {
                Thread.sleep(RUN_LIMIT_MS)
            } catch (interrupted: InterruptedException) {
                return@Thread
            }
            try {
                artifacts.failure(PlaytestError("timeout", "The replay exceeded ${RUN_LIMIT_MS / 60_000} minutes"), null)
                artifacts.writeSummary(null)
            } finally {
                Runtime.getRuntime().halt(1)
            }
        }, "playtest-run-watchdog").apply { isDaemon = true }.start()
    }
}

/** Below the workflow's 25-minute job timeout, after setup and compilation, leaving time to upload. */
private const val RUN_LIMIT_MS = 15 * 60_000L
