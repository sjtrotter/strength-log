package cloud.trotter.log.strength.wear.store

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import cloud.trotter.log.strength.domain.sync.WatchCycleDay
import cloud.trotter.log.strength.domain.sync.WatchCycleExercise
import cloud.trotter.log.strength.wear.data.FakeWatchClient
import cloud.trotter.log.strength.wear.theme.Background
import cloud.trotter.log.strength.wear.theme.WearTrackerTheme
import cloud.trotter.log.strength.wear.ui.Dial
import cloud.trotter.log.strength.wear.ui.DialFace
import cloud.trotter.log.strength.wear.ui.DialInputs
import cloud.trotter.log.strength.wear.ui.RestState
import cloud.trotter.log.strength.wear.ui.SessionStamps
import cloud.trotter.log.strength.wear.ui.SetPhase
import cloud.trotter.log.strength.wear.ui.dialUiState
import cloud.trotter.log.strength.wear.ui.rememberDialCopy
import com.github.takahirom.roborazzi.captureRoboImage
import java.io.File
import java.time.Instant
import java.util.Locale
import java.util.TimeZone
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w227dp-h227dp-round-watch-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class StoreScreenshotsTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var originalLocale: Locale
    private lateinit var originalTimeZone: TimeZone

    @Before
    fun fixLocaleAndTimeZone() {
        originalLocale = Locale.getDefault()
        originalTimeZone = TimeZone.getDefault()
        Locale.setDefault(Locale.US)
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
    }

    @After
    fun restoreLocaleAndTimeZone() {
        Locale.setDefault(originalLocale)
        TimeZone.setDefault(originalTimeZone)
    }

    @Test
    fun overview() {
        render("01-overview.png", inputs(face = DialFace.OVERVIEW))
    }

    @Test
    fun set() {
        render("02-set.png", inputs(face = DialFace.WORKOUT, doneCount = 2, phase = SetPhase.LIFTING))
    }

    @Test
    fun resting() {
        val inputs = inputs(face = DialFace.WORKOUT, doneCount = 2)
        render(
            "03-resting.png",
            inputs.copy(
                rest = RestState(
                    deadlineElapsedMillis = inputs.nowElapsedMillis + 90_000L,
                    totalSeconds = 150,
                    betweenExercises = false,
                ),
            ),
        )
    }

    private fun inputs(face: DialFace, doneCount: Int = 0, phase: SetPhase = SetPhase.READY): DialInputs {
        val seed = FakeWatchClient().snapshotFlow().value
        val snapshot = seed.copy(
            day = seed.day.copy(
                title = "Squat focus",
                exercises = seed.day.exercises.mapIndexed { index, exercise ->
                    if (index != 0) exercise else exercise.copy(
                        sets = exercise.sets.mapIndexed { setIndex, set ->
                            set.copy(done = setIndex < doneCount)
                        },
                    )
                },
            ),
            cycle = listOf(
                WatchCycleDay(
                    dayId = seed.day.dayId,
                    title = "Squat focus",
                    exercises = seed.day.exercises.map { WatchCycleExercise(it.name, it.sets.size) },
                ),
                WatchCycleDay("B", "Upper"),
                WatchCycleDay("C", "Hinge focus"),
                WatchCycleDay("D", "Press focus"),
            ),
        )
        val nowWallMillis = Instant.parse("2026-03-03T10:08:00Z").toEpochMilli()
        return DialInputs(
            snapshot = snapshot,
            exerciseIndex = 0,
            phase = phase,
            face = face,
            pendingCount = 0,
            rest = null,
            liftingElapsedMillis = if (phase == SetPhase.LIFTING) 18_000L else 0L,
            nowElapsedMillis = 100_000L,
            nowWallMillis = nowWallMillis,
            session = if (doneCount > 0) SessionStamps(firstStartedAtMillis = nowWallMillis - 12 * 60_000L) else SessionStamps(),
        )
    }

    private fun render(name: String, inputs: DialInputs) {
        composeTestRule.mainClock.autoAdvance = false
        composeTestRule.setContent {
            WearTrackerTheme {
                Box(Modifier.fillMaxSize().background(Background)) {
                    Dial(
                        state = dialUiState(inputs, rememberDialCopy()),
                        onTap = {},
                        timePillText = "10:08",
                    )
                }
            }
        }
        composeTestRule.mainClock.advanceTimeBy(2_000)
        composeTestRule.waitForIdle()
        composeTestRule.onRoot().assertExists()
        if (System.getProperty("roborazzi.test.record") == "true") {
            val file = File(System.getProperty("storeShots.dir"), "wearScreenshots/$name")
            file.parentFile.mkdirs()
            composeTestRule.onRoot().captureRoboImage(file)
        }
    }
}
