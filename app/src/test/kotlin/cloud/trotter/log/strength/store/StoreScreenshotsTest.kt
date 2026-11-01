package cloud.trotter.log.strength.store

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import cloud.trotter.log.strength.data.catalog.ExerciseCatalog
import cloud.trotter.log.strength.domain.library.tracking
import cloud.trotter.log.strength.domain.model.CardioActivity
import cloud.trotter.log.strength.domain.model.CardioBlock
import cloud.trotter.log.strength.domain.model.CardioRole
import cloud.trotter.log.strength.domain.model.CardioSetSpec
import cloud.trotter.log.strength.domain.model.LifterConfig
import cloud.trotter.log.strength.domain.model.Measure
import cloud.trotter.log.strength.domain.model.ProgramExercise
import cloud.trotter.log.strength.domain.model.SetKind
import cloud.trotter.log.strength.domain.model.SupersetPartner
import cloud.trotter.log.strength.domain.seeding.SetSeeder
import cloud.trotter.log.strength.domain.standards.GoalCalculator
import cloud.trotter.log.strength.domain.theme.ThemePreference
import cloud.trotter.log.strength.domain.units.WeightUnit
import cloud.trotter.log.strength.ui.day.CardioCardState
import cloud.trotter.log.strength.ui.day.DayActions
import cloud.trotter.log.strength.ui.day.DayEditActions
import cloud.trotter.log.strength.ui.day.DayEditSlotState
import cloud.trotter.log.strength.ui.day.DayEditUiState
import cloud.trotter.log.strength.ui.day.DayScreen
import cloud.trotter.log.strength.ui.day.DayScreenBuilder
import cloud.trotter.log.strength.ui.day.DayUiState
import cloud.trotter.log.strength.ui.day.ExerciseCardState
import cloud.trotter.log.strength.ui.day.OpenSheet
import cloud.trotter.log.strength.ui.day.PartnerRowState
import cloud.trotter.log.strength.ui.day.ReceiptLift
import cloud.trotter.log.strength.ui.day.SessionReceipt
import cloud.trotter.log.strength.ui.day.SetRowState
import cloud.trotter.log.strength.ui.log.JournalUiState
import cloud.trotter.log.strength.ui.log.LogActions
import cloud.trotter.log.strength.ui.log.LogScreen
import cloud.trotter.log.strength.ui.log.LogScreenBuilder
import cloud.trotter.log.strength.ui.log.LogUiState
import cloud.trotter.log.strength.ui.log.SessionListItem
import cloud.trotter.log.strength.ui.log.TrajectoryCard
import cloud.trotter.log.strength.ui.log.TrajectoryGridline
import cloud.trotter.log.strength.ui.log.TrajectoryPoint
import cloud.trotter.log.strength.ui.navigation.AppNavigationBar
import cloud.trotter.log.strength.ui.navigation.TopLevelDestination
import cloud.trotter.log.strength.ui.theme.AppTheme
import cloud.trotter.log.strength.ui.today.TodayActions
import cloud.trotter.log.strength.ui.today.TodayDay
import cloud.trotter.log.strength.ui.today.TodayScreen
import cloud.trotter.log.strength.ui.today.TodayUiState
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.captureRoboImage
import com.github.takahirom.roborazzi.captureScreenRoboImage
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
    private val supersetExercises = exercises.mapIndexed { index, exercise ->
        if (index == 2) {
            ProgramExercise("db_bench", superset = SupersetPartner("cable_row"), keepToPlan = true)
        } else {
            exercise
        }
    }

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
            upNext = listOf(
                TodayDay(1L, "Four-day strength", "A", day.dayIndex, day.dayTitle),
                TodayDay(2L, "Run prep", "B", 1, "Core and mobility"),
            ),
            otherDays = listOf(TodayDay(1L, "Four-day strength", "B", 1, "Upper body")),
            fact = "Oct 28",
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
    fun superset() {
        val day = supersetDayState()
        renderDay(
            "03-superset.png",
            day,
            edit = dayEditState(day, supersetExercises),
            beforeCapture = {
                composeTestRule.onNodeWithText("then Seated Cable Row 120 × 10").assertIsDisplayed()
            },
        )
    }

    @Test
    fun cardio() {
        // The ready set list shows the whole interval prescription, including pace aims.
        val block = CardioBlock(
            CardioActivity.TRACK_RUN,
            listOf(
                CardioSetSpec(CardioRole.WARMUP, Measure.Time(300)),
                CardioSetSpec(CardioRole.WORK, Measure.Distance(400), Measure.Time(105), hard = true),
                CardioSetSpec(CardioRole.RECOVERY, Measure.Time(90)),
                CardioSetSpec(CardioRole.WORK, Measure.Distance(400), Measure.Time(105), hard = true),
                CardioSetSpec(CardioRole.COOLDOWN, Measure.Time(300)),
            ),
        )
        renderDay(
            "04-cardio.png",
            dayState(doneCounts = listOf(6, 6, 3, 3)).copy(
                selectedPageKey = "cardio",
                cardio = CardioCardState("Track run", "2 × 400 m · aim 1:45", hard = true, block = block),
                openSheet = OpenSheet("A", OpenSheet.Kind.CARDIO_SETS, 0L, 0),
            ),
            beforeCapture = {
                composeTestRule.onNodeWithText("Set 1, Warm-up, Ready").assertIsDisplayed()
                composeTestRule.onNodeWithText("Set 5, Cool-down, Ready").assertIsDisplayed()
            },
        )
    }

    @Test
    fun receipt() {
        val day = dayState(doneCounts = listOf(6, 6, 3, 3))
        renderDay(
            "05-receipt.png",
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

    @Test
    fun history() {
        // Date, day letter, rotation index, title, sets: the last three sessions of the rotation.
        val sessions = listOf(
            listOf("2026-03-03", "A", 0, "Squat focus", 18),
            listOf("2026-03-01", "D", 3, "Bench focus", 17),
            listOf("2026-02-27", "C", 2, "Deadlift focus", 16),
        )
        val state = LogUiState(
            sessions = sessions.mapIndexed { index, (date, letter, dayIndex, title, sets) ->
                val completedAt = Instant.parse("${date}T10:30:00Z").toEpochMilli()
                SessionListItem(
                    sessionId = 3L - index,
                    dateDisplay = LogScreenBuilder.dateDisplay(completedAt),
                    completedAt = completedAt,
                    dayLetter = letter as String,
                    dayIndex = dayIndex as Int,
                    dayTitle = title as String,
                    setCount = sets as Int,
                    bodyweightDisplay = "235",
                    expanded = false,
                    planId = 1L,
                    planName = "Four-day strength",
                )
            },
            journal = JournalUiState(
                trajectories = listOf(
                    TrajectoryCard(
                        exerciseId = "bb_back_squat",
                        exerciseName = catalog.get("bb_back_squat").name,
                        dayIndex = 0,
                        points = listOf(215f, 220f, 220f, 225f, 230f, 235f).mapIndexed { index, weight ->
                            TrajectoryPoint(weight, newHigh = index != 2)
                        },
                        goalValue = 235f,
                        goalLabel = "GOAL 235",
                        goalMet = true,
                        latestLabel = "235",
                        caption = "6 SESSIONS · SINCE FEB 9",
                        axisMin = 210f,
                        axisMax = 240f,
                        gridlines = listOf(TrajectoryGridline(215f, "215"), TrajectoryGridline(235f, "235")),
                    ),
                ),
            ),
        )
        render("06-history.png") {
            Scaffold(
                contentWindowInsets = WindowInsets(0),
                bottomBar = {
                    AppNavigationBar(selectedRoute = TopLevelDestination.LOG.route, onSelect = {})
                },
            ) { innerPadding ->
                Box(Modifier.fillMaxSize().padding(innerPadding)) {
                    LogScreen(state, logActions())
                }
            }
        }
    }

    @Test
    fun plan() {
        val day = supersetDayState()
        // One slot keeps the sheet's Edit action unambiguous; its options are real UI state.
        val edit = dayEditState(day, supersetExercises).let {
            it.copy(editorOpen = true, slots = it.slots.filter { slot -> slot.isSuperset })
        }
        renderDay(
            "07-plan.png",
            day,
            edit = edit,
            beforeCapture = {
                composeTestRule.onNodeWithText("Edit").performClick()
                composeTestRule.mainClock.advanceTimeBy(2_000)
                composeTestRule.waitForIdle()
                composeTestRule.onNodeWithText("Next time").assertIsDisplayed()
                composeTestRule.onNodeWithText("Prescription").assertIsDisplayed()
                composeTestRule.onNodeWithText("Remove superset").assertIsDisplayed()
            },
        )
    }

    private fun renderDay(
        name: String,
        state: DayUiState,
        receipt: SessionReceipt? = null,
        edit: DayEditUiState = dayEditState(state),
        beforeCapture: () -> Unit = {},
    ) {
        render(name, beforeCapture) {
            DayScreen(
                state = state,
                actions = dayActions(),
                dayEditState = edit,
                dayEditActions = dayEditActions(),
                sessionReceipt = receipt,
                onBack = {},
            )
        }
    }

    @OptIn(ExperimentalRoborazziApi::class)
    private fun render(name: String, beforeCapture: () -> Unit = {}, content: @Composable () -> Unit) {
        composeTestRule.mainClock.autoAdvance = false
        composeTestRule.setContent {
            AppTheme(preference = ThemePreference.DARK, content = content)
        }
        composeTestRule.mainClock.advanceTimeBy(2_000)
        composeTestRule.waitForIdle()
        beforeCapture()
        val roots = composeTestRule.onAllNodes(isRoot())
        val root = roots.onLast()
        root.assertExists()
        if (System.getProperty("roborazzi.test.record") == "true") {
            val file = File(System.getProperty("storeShots.dir"), "phoneScreenshots/$name")
            file.parentFile.mkdirs()
            // Composite modal windows with the day behind their translucent scrim.
            if (roots.fetchSemanticsNodes().size > 1) {
                captureScreenRoboImage(file.absolutePath)
            } else {
                root.captureRoboImage(file)
            }
        }
    }

    private fun dayState(
        doneCounts: List<Int> = listOf(2, 0, 0, 0),
        selectedExercise: Int = 0,
        dayExercises: List<ProgramExercise> = exercises,
        partnerDoneCounts: List<Int> = doneCounts,
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
        exercises = dayExercises.mapIndexed { index, exercise ->
            exerciseCard(index, exercise, doneCounts[index], partnerDoneCounts[index])
        },
    )

    private fun supersetDayState() = dayState(
        doneCounts = listOf(6, 6, 0, 0),
        selectedExercise = 2,
        dayExercises = supersetExercises,
        partnerDoneCounts = listOf(6, 6, 0, 0),
    )

    private fun exerciseCard(
        position: Int,
        exercise: ProgramExercise,
        doneCount: Int,
        partnerDoneCount: Int,
    ): ExerciseCardState {
        val entry = catalog.get(exercise.exerciseId)
        val target = GoalCalculator.targetFor(entry, config)
        val sets = SetSeeder.seed(exercise, target, config).mapIndexed { index, set ->
            set.copy(done = index < doneCount)
        }
        val partnerEntry = exercise.superset?.let { catalog.get(it.exerciseId) }
        val partnerSets = partnerEntry?.let { partner ->
            SetSeeder.seedPartner(sets.size, GoalCalculator.targetFor(partner, config)).mapIndexed { index, set ->
                set.copy(done = index < partnerDoneCount)
            }
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
                partner = partnerSets?.getOrNull(index)?.let { partner ->
                    PartnerRowState(partner.weightLb, partner.reps, partner.done, partner.seconds)
                },
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
            isSuperset = partnerEntry != null,
            partnerTitle = partnerEntry?.name,
            partnerTracking = partnerEntry?.tracking,
            hasWarmupHint = false,
            headerTarget = DayScreenBuilder.headerTarget(exercise, target, WeightUnit.LB),
            perHand = entry.perHand,
            tracking = entry.tracking,
            equipment = entry.equipment,
            allDone = DayScreenBuilder.allDone(rows),
            rows = rows,
            focusIndex = focus,
            focusLabel = DayScreenBuilder.focusLabel(sets.map { it.kind }, focus),
            summaryLine = DayScreenBuilder.summaryLine(
                sets, partnerSets, entry.tracking, partnerEntry?.tracking ?: entry.tracking, WeightUnit.LB,
            ),
            nextSetDisplay = DayScreenBuilder.nextSetDisplay(rows, focus, entry.tracking, WeightUnit.LB),
            topSetComparison = DayScreenBuilder.topSetComparison(sets, null, WeightUnit.LB),
            plateLine = DayScreenBuilder.plateLine(sets, entry.equipment, WeightUnit.LB),
            weightSwap = DayScreenBuilder.weightSwapAffordance(entry, catalog),
        )
    }

    private fun dayEditState(
        state: DayUiState,
        dayExercises: List<ProgramExercise> = exercises,
    ) = DayEditUiState(
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
                isSuperset = card.isSuperset,
                partnerExerciseId = dayExercises[card.position].superset?.exerciseId,
                partnerTitle = card.partnerTitle,
                targetSets = card.rows.size,
                canMoveUp = card.position > 0,
                canMoveDown = card.position < state.exercises.lastIndex,
                keepToPlan = dayExercises[card.position].keepToPlan,
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
        onSetUpProgram = {},
    )

    private fun logActions() = LogActions(
        onToggleExpanded = {},
        onPageCalendar = {},
        onConnectHealth = {},
        onPublishPastWorkouts = {},
        onApplyBodyweight = {},
        onDismissBodyweight = {},
        onShare = {},
        onStartSession = {},
        onSetUpProgram = {},
    )
}
