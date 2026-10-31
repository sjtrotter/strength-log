package cloud.trotter.log.strength.store

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import cloud.trotter.log.strength.data.catalog.ExerciseCatalog
import cloud.trotter.log.strength.domain.library.tracking
import cloud.trotter.log.strength.domain.model.LifterConfig
import cloud.trotter.log.strength.domain.model.ProgramExercise
import cloud.trotter.log.strength.domain.model.SetKind
import cloud.trotter.log.strength.domain.seeding.SetSeeder
import cloud.trotter.log.strength.domain.standards.GoalCalculator
import cloud.trotter.log.strength.domain.standards.PhoneRest
import cloud.trotter.log.strength.domain.theme.ThemePreference
import cloud.trotter.log.strength.domain.units.WeightUnit
import cloud.trotter.log.strength.ui.day.DayActions
import cloud.trotter.log.strength.ui.day.DayEditActions
import cloud.trotter.log.strength.ui.day.DayEditSlotState
import cloud.trotter.log.strength.ui.day.DayEditUiState
import cloud.trotter.log.strength.ui.day.DayScreen
import cloud.trotter.log.strength.ui.day.DayScreenBuilder
import cloud.trotter.log.strength.ui.day.DayUiState
import cloud.trotter.log.strength.ui.day.ExerciseCardState
import cloud.trotter.log.strength.ui.day.ReceiptLift
import cloud.trotter.log.strength.ui.day.RestUiState
import cloud.trotter.log.strength.ui.day.SessionReceipt
import cloud.trotter.log.strength.ui.day.SetRowState
import cloud.trotter.log.strength.ui.navigation.AppNavigationBar
import cloud.trotter.log.strength.ui.navigation.TopLevelDestination
import cloud.trotter.log.strength.ui.theme.AppTheme
import cloud.trotter.log.strength.ui.today.TodayActions
import cloud.trotter.log.strength.ui.today.TodayLift
import cloud.trotter.log.strength.ui.today.TodayScreen
import cloud.trotter.log.strength.ui.today.TodayScreenBuilder
import cloud.trotter.log.strength.ui.today.TodayUiState
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
@Config(sdk = [35], qualifiers = "w360dp-h720dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class StoreScreenshotsTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var originalLocale: Locale
    private lateinit var originalTimeZone: TimeZone
    private val catalog = ExerciseCatalog.CODE_ONLY
    private val config = LifterConfig(bodyweightLb = 235, age = 40)
    private val exercises = listOf(
        ProgramExercise("bb_back_squat", isMain = true),
        ProgramExercise("bb_bench", isMain = true),
        ProgramExercise("bb_row"),
        ProgramExercise("plank"),
    )

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
    fun today() {
        val day = dayState()
        val state = TodayUiState(
            hasProgram = true,
            dayId = "A",
            dayIndex = day.dayIndex,
            dayTitle = day.dayTitle,
            coachLine = TodayScreenBuilder.progressLine(day.doneSets, day.totalSets),
            lifts = day.exercises.map { TodayLift(it.title, it.rows.size) },
            actionLabel = TodayScreenBuilder.actionLabel("A", day.doneSets, day.totalSets),
            planId = 1L,
            planName = "Four-day strength",
        )
        render("01-today.png") {
            Scaffold(
                contentWindowInsets = WindowInsets(0),
                bottomBar = {
                    AppNavigationBar(selectedRoute = TopLevelDestination.TODAY.route, onSelect = {})
                },
            ) { innerPadding ->
                Box(Modifier.fillMaxSize().padding(innerPadding)) {
                    TodayScreen(state, todayActions())
                }
            }
        }
    }

    @Test
    fun workout() {
        renderDay("02-workout.png", dayState())
    }

    @Test
    fun resting() {
        val rest = RestUiState(
            rest = PhoneRest(
                deadlineEpochMillis = Instant.parse("2026-03-03T10:09:30Z").toEpochMilli(),
                totalSeconds = 150,
                nextSetLabel = "190 × 5",
            ),
            remainingSeconds = 90,
            remainingFraction = 90f / 150f,
            over = false,
        )
        renderDay("03-resting.png", dayState().copy(rest = rest))
    }

    @Test
    fun backoff() {
        renderDay("04-backoff.png", dayState(doneCounts = listOf(5, 0, 0, 0)))
    }

    @Test
    fun liftDone() {
        renderDay("05-lift-done.png", dayState(doneCounts = listOf(6, 0, 0, 0)))
    }

    @Test
    fun timed() {
        renderDay(
            "06-timed.png",
            dayState(doneCounts = listOf(6, 6, 3, 0), selectedExercise = 3),
        )
    }

    @Test
    fun receipt() {
        val day = dayState(doneCounts = listOf(6, 6, 3, 3))
        renderDay(
            "07-receipt.png",
            day,
            SessionReceipt(
                sessionId = 1L,
                dayIndex = day.dayIndex,
                headline = "DAY A COMPLETE",
                dayTitle = day.dayTitle,
                completedSetCount = day.doneSets,
                totalSetCount = day.totalSets,
                setCount = day.doneSets,
                strongest = ReceiptLift(catalog.get("bb_back_squat").name, "235 × 5"),
                nextDayLine = "DAY B · UPPER",
            ),
        )
    }

    private fun renderDay(name: String, state: DayUiState, receipt: SessionReceipt? = null) {
        render(name) {
            DayScreen(
                state = state,
                actions = dayActions(),
                dayEditState = dayEditState(state),
                dayEditActions = dayEditActions(),
                sessionReceipt = receipt,
                onBack = {},
            )
        }
    }

    private fun render(name: String, content: @Composable () -> Unit) {
        composeTestRule.mainClock.autoAdvance = false
        composeTestRule.setContent {
            AppTheme(preference = ThemePreference.DARK, content = content)
        }
        composeTestRule.mainClock.advanceTimeBy(2_000)
        composeTestRule.waitForIdle()
        composeTestRule.onRoot().assertExists()
        if (System.getProperty("roborazzi.test.record") == "true") {
            val file = File(System.getProperty("storeShots.dir"), "phoneScreenshots/$name")
            file.parentFile.mkdirs()
            composeTestRule.onRoot().captureRoboImage(file)
        }
    }

    private fun dayState(
        doneCounts: List<Int> = listOf(2, 0, 0, 0),
        selectedExercise: Int = 0,
    ) = DayUiState(
        hasProgram = true,
        viewDayId = "A",
        planId = 1L,
        dayIndex = 0,
        dayTitle = "Squat focus",
        emphasisLine = "Strength · full body",
        unit = WeightUnit.LB,
        nextDayId = "B",
        selectedPageKey = "exercise:${selectedExercise + 1}",
        exercises = exercises.mapIndexed { index, exercise ->
            exerciseCard(index, exercise, doneCounts[index])
        },
    )

    private fun exerciseCard(position: Int, exercise: ProgramExercise, doneCount: Int): ExerciseCardState {
        val entry = catalog.get(exercise.exerciseId)
        val target = GoalCalculator.targetFor(entry, config)
        val sets = SetSeeder.seed(exercise, target, config).mapIndexed { index, set ->
            set.copy(done = index < doneCount)
        }
        val labels = DayScreenBuilder.kindLabels(sets)
        var rampIndex = 0
        val rows = sets.mapIndexed { index, set ->
            SetRowState(
                index = index,
                kindLabel = labels[index],
                isTop = set.kind == SetKind.TOP,
                weightDisplay = set.weightLb,
                reps = set.reps,
                done = set.done,
                seconds = set.seconds,
                kind = set.kind,
                rampIndex = if (set.kind == SetKind.RAMP) ++rampIndex else 0,
            )
        }
        val focus = DayScreenBuilder.focusIndex(rows)
        return ExerciseCardState(
            programExerciseId = position + 1L,
            position = position,
            exerciseId = entry.id,
            title = entry.name,
            isMain = exercise.isMain,
            isSuperset = false,
            hasWarmupHint = false,
            headerTarget = DayScreenBuilder.headerTarget(exercise, target, WeightUnit.LB),
            perHand = entry.perHand,
            tracking = entry.tracking,
            equipment = entry.equipment,
            allDone = DayScreenBuilder.allDone(sets),
            rows = rows,
            focusIndex = focus,
            focusLabel = DayScreenBuilder.focusLabel(sets.map { it.kind }, focus),
            summaryLine = DayScreenBuilder.summaryLine(sets, null, entry.tracking, entry.tracking, WeightUnit.LB),
            nextSetDisplay = DayScreenBuilder.nextSetDisplay(rows, focus, entry.tracking, WeightUnit.LB),
            topSetComparison = DayScreenBuilder.topSetComparison(sets, null, WeightUnit.LB),
            plateLine = DayScreenBuilder.plateLine(sets, entry.equipment, WeightUnit.LB),
            weightSwap = DayScreenBuilder.weightSwapAffordance(entry, catalog),
        )
    }

    private fun dayEditState(state: DayUiState) = DayEditUiState(
        catalog = catalog,
        dayId = state.viewDayId,
        planId = state.planId,
        slots = state.exercises.map { card ->
            DayEditSlotState(
                programExerciseId = card.programExerciseId,
                position = card.position,
                exerciseId = card.exerciseId,
                title = card.title,
                pattern = catalog.get(card.exerciseId).pattern,
                isSuperset = false,
                targetSets = card.rows.size,
            )
        },
    )

    private fun dayActions() = DayActions(
        onSelectDay = {},
        onWeightChange = { _, _, _, _ -> },
        onRepsChange = { _, _, _, _ -> },
        onSecondsChange = { _, _, _, _ -> },
        onToggleDone = { _, _, _, _ -> },
        onAddSet = { _, _ -> },
        onRemoveSet = { _, _, _ -> },
        onSelectPage = {},
        onKeepScreenOnChange = {},
        onDiscardWorkout = {},
        onDone = {},
        onCreateExercise = {},
        onSetUpProgram = {},
    )

    private fun dayEditActions() = DayEditActions(
        onSwap = { _, _ -> },
        onAdd = {},
        onRemove = {},
        onSetSuperset = { _, _ -> },
        onRemoveSuperset = {},
        onResetToTemplate = {},
    )

    private fun todayActions() = TodayActions(
        onStart = {},
        onOpenSettings = {},
        onOpenLog = {},
        onSetUpProgram = {},
    )
}
