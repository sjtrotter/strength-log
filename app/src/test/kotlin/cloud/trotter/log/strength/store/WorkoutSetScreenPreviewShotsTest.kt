package cloud.trotter.log.strength.store

import androidx.compose.ui.unit.Density
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onLast
import cloud.trotter.log.strength.data.db.entity.Slot
import cloud.trotter.log.strength.ui.day.workoutSetTimedRepFixture
import cloud.trotter.log.strength.ui.day.workoutSetTimedRepRunningFixture
import cloud.trotter.log.strength.ui.day.workoutSetCardioFixture
import cloud.trotter.log.strength.ui.day.workoutSetCardioRunningFixture
import cloud.trotter.log.strength.ui.day.workoutSetCardioDistanceFixture
import cloud.trotter.log.strength.ui.day.workoutSetCardioRecoveryFixture
import cloud.trotter.log.strength.ui.day.workoutSetCardioDoneFixture
import cloud.trotter.log.strength.ui.day.workoutSetEmptyFixture
import cloud.trotter.log.strength.ui.day.DayEditUiState
import cloud.trotter.log.strength.ui.day.WorkoutSwapUndo
import cloud.trotter.log.strength.domain.library.TrackingType
import cloud.trotter.log.strength.domain.model.LoggedSet
import cloud.trotter.log.strength.domain.model.SetKind
import cloud.trotter.log.strength.domain.theme.ThemePreference
import cloud.trotter.log.strength.domain.units.WeightUnit
import cloud.trotter.log.strength.ui.day.OpenSheet
import cloud.trotter.log.strength.ui.day.workoutLiftSheetFixture
import cloud.trotter.log.strength.ui.day.workoutDaySheetFixture
import cloud.trotter.log.strength.ui.day.DayUiState
import cloud.trotter.log.strength.ui.day.UndoOffer
import cloud.trotter.log.strength.ui.day.WorkoutFocus
import cloud.trotter.log.strength.ui.day.WorkoutSelection
import cloud.trotter.log.strength.ui.day.WorkoutSetHost
import cloud.trotter.log.strength.ui.day.WorkoutState
import cloud.trotter.log.strength.ui.day.pagerRest
import cloud.trotter.log.strength.ui.day.withWorkoutRestHint
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
        ).withWorkoutRestHint(true, emptyMap()))
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

    @Test fun supersetRest() {
        val state = workoutSetSupersetFixture()
        render("08-superset-rest.png", state.copy(
            exercises = state.exercises.map { card -> card.copy(rows = card.rows.map { row ->
                if (row.index < 2) row.copy(done = true, partner = row.partner?.copy(done = true)) else row
            }) },
            rest = pagerRest().copy(remainingSeconds = 64, remainingFraction = 64f / 90f),
            workout = state.workout?.copy(focus = WorkoutFocus(1L, Slot.MAIN, 2), undo = UndoOffer(
                1L, Slot.SS, 1, "Seated Cable Row", LoggedSet(120.0, 10, SetKind.WORK), TrackingType.WEIGHTED, WeightUnit.LB, null,
            )),
        ))
    }

    @Test fun supersetCompleted() {
        val state = workoutSetSupersetFixture()
        val card = state.exercises.single()
        render("09-superset-completed.png", state.copy(
            exercises = listOf(card.copy(allDone = true, rows = card.rows.map { row ->
                row.copy(done = true, partner = row.partner?.copy(done = true))
            }), workoutSetSingleFixture().exercises.single().copy(
                programExerciseId = 2L, position = 1, exerciseId = "bb_row", title = "Barbell Row",
            )),
            workout = state.workout?.copy(shown = WorkoutSelection.Shown.Group("exercise:1", true),
                focus = null, completedView = true),
        ))
    }

    @Test fun supersetLargeFont() = render("10-superset-large-font.png", workoutSetSupersetFixture(), fontScale = 2f)

    @Test fun liftSheet() {
        val state = workoutLiftSheetFixture()
        render("11-lift-sheet.png", state.copy(openSheet = OpenSheet("A", OpenSheet.Kind.LIFT, 1L, 0)))
    }

    @Test fun liftSheetPartner() {
        val state = workoutSetSupersetFixture()
        render("12-lift-sheet-partner.png", state.copy(
            openSheet = OpenSheet("A", OpenSheet.Kind.LIFT, 1L, 0, slot = Slot.SS)))
    }

    @Test fun daySheet() {
        val state = workoutDaySheetFixture()
        render("13-day-sheet.png", state.copy(openSheet = OpenSheet("A", OpenSheet.Kind.DAY, 0L, 0)))
    }

    @Test fun liftSheetSkip() {
        val base = workoutSetSupersetFixture()
        val state = base.copy(exercises = base.exercises.map { card -> card.copy(
            isSuperset = false, partnerExerciseId = null, partnerTitle = null, partnerTracking = null,
            rows = card.rows.map { it.copy(partner = null) },
        ) })
        render("14-lift-sheet-skip.png", state.copy(
            openSheet = OpenSheet("A", OpenSheet.Kind.LIFT, 1L, 0)))
    }

    @Test fun daySheetSkipped() {
        val state = workoutDaySheetFixture()
        val skipped = state.exercises.first()
        render("16-day-sheet-skipped.png", state.copy(
            exercises = state.exercises.filterNot { it.programExerciseId == skipped.programExerciseId },
            skippedLifts = listOf(cloud.trotter.log.strength.ui.day.SkippedLift(skipped.programExerciseId, skipped.position, skipped.title)),
            openSheet = OpenSheet("A", OpenSheet.Kind.DAY, 0L, 0)))
    }

    @Test fun afterDrop() {
        val state = workoutSetSupersetFixture()
        val card = state.exercises.single()
        render("15-after-drop.png", state.copy(runOpen = true, runId = "drop-preview",
            exercises = listOf(card.copy(isSuperset = false, partnerExerciseId = null, partnerTitle = null,
                partnerTracking = null, rows = card.rows.map { it.copy(partner = null) })),
            swapUndo = WorkoutSwapUndo("A", "drop-preview", 1L, Slot.MAIN, "cable_row", "db_bench",
                card.partnerTitle.orEmpty(), skippedSlot = Slot.SS)))
    }

    @Test fun timedRepReady() = render("17-timed-rep-ready.png", workoutSetTimedRepFixture())

    @Test fun timedRepRunning() = render("18-timed-rep-running.png", workoutSetTimedRepRunningFixture())

    @Test fun cardioReady() = render("19-cardio-ready.png", workoutSetCardioFixture())

    @Test fun cardioRunningTime() = render("20-cardio-running-time.png", workoutSetCardioRunningFixture())

    @Test fun cardioRunningDistance() = render("21-cardio-running-distance.png", workoutSetCardioDistanceFixture())

    @Test fun cardioRecovery() = render("22-cardio-recovery.png", workoutSetCardioRecoveryFixture())

    @Test fun cardioDone() = render("23-cardio-done.png", workoutSetCardioDoneFixture())

    @Test fun cardioSheet() = render("24-cardio-sheet.png", workoutSetCardioRecoveryFixture().let {
        it.copy(openSheet = OpenSheet("A", OpenSheet.Kind.CARDIO_SETS, 0L, 0))
    })

    @Test fun clockChip() = render("25-clock-chip.png", workoutSetCardioRunningFixture().let {
        it.copy(workout = workoutSetSingleFixture().workout)
    })

    @Test fun cardioSaveFailed() = render("26-cardio-save-failed.png", workoutSetCardioDoneFixture().let { done ->
        val running = requireNotNull(workoutSetCardioRunningFixture().cardio?.run)
        done.copy(cardio = done.cardio?.copy(loggedSeconds = null, loggedSets = emptyList(),
            run = running.copy(wallStart = null, results = listOf(
                cloud.trotter.log.strength.ui.day.SavedCardioResult("FINISHED", 300)), saveFailed = true)),
            workout = done.workout?.copy(shown = WorkoutSelection.Shown.Group("cardio", false), completedView = false))
    })

    @Test fun emptyDay() = render("27-empty-day.png", workoutSetEmptyFixture())

    @Test fun cardioRunningLight() = render("28-cardio-running-light.png", workoutSetCardioRunningFixture(), ThemePreference.LIGHT)

    @Test fun timedRepLargeFont() = render("29-timed-rep-large-font.png", workoutSetTimedRepFixture(), fontScale = 2f)

    private fun render(
        name: String, state: DayUiState, theme: ThemePreference = ThemePreference.DARK, fontScale: Float? = null,
        dayEditState: DayEditUiState = DayEditUiState(),
    ) {
        rule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale ?: density.fontScale)) {
                AppTheme(preference = theme) {
                    WorkoutSetHost(state, dayEditState = dayEditState, nowMillis = { rule.mainClock.currentTime })
                }
            }
        }
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
