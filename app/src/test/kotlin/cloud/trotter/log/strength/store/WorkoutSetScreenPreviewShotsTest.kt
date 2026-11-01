package cloud.trotter.log.strength.store

import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onLast
import cloud.trotter.log.strength.data.db.entity.Slot
import cloud.trotter.log.strength.domain.library.TrackingType
import cloud.trotter.log.strength.domain.model.LoggedSet
import cloud.trotter.log.strength.domain.model.SetKind
import cloud.trotter.log.strength.domain.theme.ThemePreference
import cloud.trotter.log.strength.domain.units.WeightUnit
import cloud.trotter.log.strength.ui.day.DayUiState
import cloud.trotter.log.strength.ui.day.UndoOffer
import cloud.trotter.log.strength.ui.day.WorkoutFocus
import cloud.trotter.log.strength.ui.day.WorkoutSelection
import cloud.trotter.log.strength.ui.day.WorkoutSetHost
import cloud.trotter.log.strength.ui.day.WorkoutState
import cloud.trotter.log.strength.ui.day.pagerRest
import cloud.trotter.log.strength.ui.day.workoutSetSingleFixture
import cloud.trotter.log.strength.ui.day.workoutSetSupersetFixture
import cloud.trotter.log.strength.ui.theme.AppTheme
import com.github.takahirom.roborazzi.captureRoboImage
import java.io.File
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
@Config(sdk = [35], qualifiers = "w360dp-h720dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WorkoutSetScreenPreviewShotsTest {
    @get:Rule val rule = createComposeRule()
    private lateinit var originalLocale: Locale
    private lateinit var originalTimeZone: TimeZone

    @Before fun fixLocaleAndTimeZone() {
        originalLocale = Locale.getDefault()
        originalTimeZone = TimeZone.getDefault()
        Locale.setDefault(Locale.US)
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
    }

    @After fun restoreLocaleAndTimeZone() {
        Locale.setDefault(originalLocale)
        TimeZone.setDefault(originalTimeZone)
    }

    @Test fun set() = render("01-set.png", workoutSetSingleFixture())

    @Test fun supersetMain() = render("02-superset-main.png", workoutSetSupersetFixture())

    @Test fun supersetPartner() {
        val state = workoutSetSupersetFixture()
        render("03-superset-ss.png", state.copy(
            exercises = state.exercises.map { card -> card.copy(rows = card.rows.map { row ->
                if (row.index == 1) row.copy(done = true) else row
            }) },
            workout = state.workout?.copy(focus = WorkoutFocus(1L, Slot.SS, 1), undo = UndoOffer(
                1L, Slot.MAIN, 1, "DB Bench Press", LoggedSet(80.0, 10, SetKind.WORK), TrackingType.WEIGHTED, WeightUnit.LB, null,
            )),
        ))
    }

    @Test fun rest() {
        val state = workoutSetSingleFixture()
        render("04-rest.png", state.copy(
            rest = pagerRest().copy(remainingSeconds = 64, remainingFraction = 64f / 90f),
            workout = state.workout?.copy(undo = UndoOffer(
                1L, Slot.MAIN, 1, "Barbell Back Squat", LoggedSet(165.0, 5, SetKind.RAMP), TrackingType.WEIGHTED, WeightUnit.LB, null,
            )),
        ))
    }

    @Test fun completed() {
        val state = workoutSetSingleFixture()
        val card = state.exercises.single()
        render("05-completed.png", state.copy(
            exercises = listOf(card.copy(allDone = true, rows = card.rows.map { it.copy(done = true) },
                summaryLine = "130×5, 165×5, 190×5, 210×3, 235×5, 175×8"),
                card.copy(programExerciseId = 2L, position = 1, exerciseId = "bb_bench", title = "Barbell Bench Press")),
            workout = state.workout?.copy(shown = WorkoutSelection.Shown.Group("exercise:1", true), focus = null, completedView = true),
        ))
    }

    @Test fun allLogged() {
        val state = workoutSetSingleFixture()
        render("06-all-logged.png", state.copy(
            exercises = state.exercises.map { card -> card.copy(allDone = true, rows = card.rows.map { it.copy(done = true) }) },
            workout = WorkoutState(WorkoutSelection.Shown.AllLogged, null, false, null, 0L),
        ))
    }

    @Test fun supersetLight() = render("07-superset-light.png", workoutSetSupersetFixture(), ThemePreference.LIGHT)

    private fun render(name: String, state: DayUiState, theme: ThemePreference = ThemePreference.DARK) {
        rule.mainClock.autoAdvance = false
        rule.setContent { AppTheme(preference = theme) {
            WorkoutSetHost(state, nowMillis = { rule.mainClock.currentTime })
        } }
        rule.mainClock.advanceTimeBy(4_000)
        rule.waitForIdle()
        val root = rule.onAllNodes(isRoot()).onLast()
        root.assertExists()
        if (System.getProperty("roborazzi.test.record") == "true") {
            val file = File(System.getProperty("previewShots.dir"), name)
            file.parentFile.mkdirs()
            root.captureRoboImage(file)
        }
    }
}
