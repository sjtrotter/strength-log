package cloud.trotter.log.strength.store

import cloud.trotter.log.strength.ui.day.receipt.CardioLastTime
import cloud.trotter.log.strength.ui.day.receipt.rowerPaceReceiptFixture
import cloud.trotter.log.strength.ui.log.JournalBuilder
import cloud.trotter.log.strength.ui.log.JournalUiState
import cloud.trotter.log.strength.ui.log.LogScreenBuilder
import cloud.trotter.log.strength.ui.log.cardioTrendHistoryFixture
import cloud.trotter.log.strength.audit.auditHistoryFixture

import cloud.trotter.log.strength.data.SpacingReason
import cloud.trotter.log.strength.data.catalog.ExerciseCatalog
import cloud.trotter.log.strength.data.db.entity.PlanEntity
import cloud.trotter.log.strength.data.serialization.PlanGoalJson
import cloud.trotter.log.strength.domain.planner.PlanBranch
import cloud.trotter.log.strength.domain.planner.Planner
import cloud.trotter.log.strength.domain.planner.SetupAnswers
import cloud.trotter.log.strength.domain.progression.SprintDose
import cloud.trotter.log.strength.domain.units.WeightUnit
import cloud.trotter.log.strength.data.serialization.CardioBlockJson
import cloud.trotter.log.strength.domain.model.Equipment
import cloud.trotter.log.strength.domain.model.LoggedSet
import cloud.trotter.log.strength.domain.model.MovementPattern
import cloud.trotter.log.strength.domain.model.SetKind
import cloud.trotter.log.strength.ui.day.CardioEntryDraft
import cloud.trotter.log.strength.ui.day.CardioEntrySheet
import cloud.trotter.log.strength.ui.day.ExercisePickerScreen
import cloud.trotter.log.strength.ui.day.HeaderTarget
import cloud.trotter.log.strength.ui.day.SavedCardioResult
import cloud.trotter.log.strength.ui.day.workoutSetCardioRunningFixture
import cloud.trotter.log.strength.ui.day.DayPlanEditor
import cloud.trotter.log.strength.ui.day.sprintEditorFixture
import cloud.trotter.log.strength.ui.day.sprintFortyYardReceiptFixture
import cloud.trotter.log.strength.ui.day.sprintReceiptFixture
import cloud.trotter.log.strength.ui.day.sprintReceiptProgressFixture
import cloud.trotter.log.strength.ui.day.LocalSprintElapsedMillis
import cloud.trotter.log.strength.ui.day.sprintRepFixture
import cloud.trotter.log.strength.ui.day.SprintTimeDraft
import cloud.trotter.log.strength.ui.day.SprintTimeSheet
import cloud.trotter.log.strength.ui.plan.PlanSprintGoalUi
import cloud.trotter.log.strength.ui.plan.SprintGoalDraft
import cloud.trotter.log.strength.domain.model.SprintAttempt
import cloud.trotter.log.strength.domain.model.SprintTime
import cloud.trotter.log.strength.domain.model.StartProtocol
import cloud.trotter.log.strength.domain.model.TimingMethod
import cloud.trotter.log.strength.ui.day.sprintStartFixture
import cloud.trotter.log.strength.ui.plan.PlanScreenBuilder

import cloud.trotter.log.strength.data.serialization.RunResult
import cloud.trotter.log.strength.domain.progression.CardioChange
import cloud.trotter.log.strength.domain.progression.LadderHold
import cloud.trotter.log.strength.domain.progression.RunWalkLadder
import cloud.trotter.log.strength.domain.model.ProgressMeasure
import cloud.trotter.log.strength.domain.model.PlanGoal
import cloud.trotter.log.strength.domain.planner.RecentRace
import cloud.trotter.log.strength.domain.planner.RunPhase
import cloud.trotter.log.strength.ui.day.CardioNextTimePage
import cloud.trotter.log.strength.domain.model.CardioProgress
import cloud.trotter.log.strength.ui.day.DayEditUiState
import cloud.trotter.log.strength.ui.day.DayEditActions
import cloud.trotter.log.strength.ui.day.workoutSetCardioFixture
import cloud.trotter.log.strength.ui.day.receipt.ReceiptReplan
import cloud.trotter.log.strength.ui.plan.PlanGoalUi
import cloud.trotter.log.strength.domain.library.ExerciseLibrary
import cloud.trotter.log.strength.domain.planner.EquipmentPreset
import cloud.trotter.log.strength.domain.routine.CreditedExercise
import cloud.trotter.log.strength.domain.routine.Move
import cloud.trotter.log.strength.domain.routine.Routines
import cloud.trotter.log.strength.ui.day.RoutineKind
import cloud.trotter.log.strength.ui.day.RoutineSheet
import cloud.trotter.log.strength.ui.day.RoutineSheetState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import cloud.trotter.log.strength.domain.model.CardioSetResult
import cloud.trotter.log.strength.domain.model.CardioSetStatus
import cloud.trotter.log.strength.domain.model.DistanceSource
import cloud.trotter.log.strength.domain.model.ReadingSource
import cloud.trotter.log.strength.domain.units.DistanceUnit
import cloud.trotter.log.strength.ui.components.LocalDistanceUnit
import cloud.trotter.log.strength.ui.day.WorkoutFocus
import cloud.trotter.log.strength.ui.day.SetRowState
import cloud.trotter.log.strength.ui.day.DayUiState
import cloud.trotter.log.strength.ui.day.LiftMarker
import cloud.trotter.log.strength.ui.day.OpenSheet
import cloud.trotter.log.strength.ui.day.receipt.ReceiptBuilder
import cloud.trotter.log.strength.ui.today.TodayActions
import cloud.trotter.log.strength.ui.today.TodayDay
import cloud.trotter.log.strength.ui.today.TodayScreen
import cloud.trotter.log.strength.ui.today.TodayUiState
import cloud.trotter.log.strength.ui.log.LogActions
import cloud.trotter.log.strength.ui.log.LogScreen
import cloud.trotter.log.strength.ui.log.LogUiState
import cloud.trotter.log.strength.ui.log.SessionListItem
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.isPopup
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import cloud.trotter.log.strength.data.db.entity.Slot
import cloud.trotter.log.strength.data.db.entity.SessionSetEntity
import cloud.trotter.log.strength.data.mapping.withSides
import cloud.trotter.log.strength.domain.library.TrackingType
import cloud.trotter.log.strength.domain.model.CardioActivity
import cloud.trotter.log.strength.domain.model.CardioBlock
import cloud.trotter.log.strength.domain.model.CardioRole
import cloud.trotter.log.strength.domain.model.CardioSetSpec
import cloud.trotter.log.strength.domain.model.Measure
import cloud.trotter.log.strength.domain.model.PrescribedTarget
import cloud.trotter.log.strength.domain.model.Prescription
import cloud.trotter.log.strength.domain.progression.CardioEffort
import cloud.trotter.log.strength.domain.progression.NextTimeMode
import cloud.trotter.log.strength.domain.progression.EffortScale
import cloud.trotter.log.strength.domain.theme.ThemePreference
import cloud.trotter.log.strength.ui.day.CardioIncrease
import cloud.trotter.log.strength.ui.day.CardioNextTime
import cloud.trotter.log.strength.ui.day.DayEditSlotState
import cloud.trotter.log.strength.ui.day.LiftOptionsState
import cloud.trotter.log.strength.ui.day.SessionReceiptScreen
import cloud.trotter.log.strength.ui.day.SlotOptionsScreen
import cloud.trotter.log.strength.ui.day.WorkoutSetHost
import cloud.trotter.log.strength.ui.day.SetEditSheet
import cloud.trotter.log.strength.ui.day.UndoOffer
import cloud.trotter.log.strength.ui.day.workoutActions
import cloud.trotter.log.strength.data.RunTrack
import cloud.trotter.log.strength.data.ProgramSlot
import cloud.trotter.log.strength.data.serialization.OpeningDto
import cloud.trotter.log.strength.domain.standards.TestRef
import cloud.trotter.log.strength.ui.day.receipt.ReceiptSetCounts
import cloud.trotter.log.strength.ui.day.receipt.PlannedSets
import cloud.trotter.log.strength.ui.day.receipt.ReceiptGroup
import cloud.trotter.log.strength.ui.day.receipt.ReceiptFollower
import cloud.trotter.log.strength.ui.day.receipt.ReceiptLiftLine
import cloud.trotter.log.strength.ui.day.receipt.ReceiptLiftProgress
import cloud.trotter.log.strength.ui.day.receipt.ReceiptMutation
import cloud.trotter.log.strength.ui.day.receipt.ReceiptProgressState
import cloud.trotter.log.strength.ui.day.receipt.ReceiptRow
import cloud.trotter.log.strength.ui.day.receipt.ReceiptSet
import cloud.trotter.log.strength.ui.day.receipt.ReceiptSides
import cloud.trotter.log.strength.ui.day.receipt.ReceiptSideSet
import cloud.trotter.log.strength.ui.day.receipt.ReceiptSideGap
import cloud.trotter.log.strength.ui.day.receipt.ReceiptSlotRef
import cloud.trotter.log.strength.ui.day.receipt.ValueLine
import cloud.trotter.log.strength.ui.day.receipt.receiptFixture
import cloud.trotter.log.strength.ui.day.receipt.timeTargetFixture
import cloud.trotter.log.strength.ui.day.workoutSetLoggedFixture
import cloud.trotter.log.strength.ui.day.workoutSetSingleFixture
import cloud.trotter.log.strength.ui.day.workoutSetEachSideFixture
import cloud.trotter.log.strength.ui.day.withWorkoutSelection
import cloud.trotter.log.strength.ui.log.logSessionFixture
import cloud.trotter.log.strength.ui.plan.PlanActions
import cloud.trotter.log.strength.ui.plan.PlanDay
import cloud.trotter.log.strength.ui.plan.PlanScreen
import cloud.trotter.log.strength.ui.plan.PlanUiState
import cloud.trotter.log.strength.ui.setup.SetupActions
import cloud.trotter.log.strength.ui.setup.SetupScreen
import cloud.trotter.log.strength.ui.setup.SetupUiState
import cloud.trotter.log.strength.ui.theme.AppTheme
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.captureRoboImage
import com.github.takahirom.roborazzi.captureScreenRoboImage
import java.io.File
import java.util.Locale
import java.util.TimeZone
import org.junit.After
import org.junit.Before
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h720dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ProgressionPreviewShotsTest {
    @get:Rule val rule = createComposeRule()
    private lateinit var originalLocale: Locale
    private lateinit var originalTimeZone: TimeZone
    private var originalFontScale = 1f
    private var originalWindowSize: String? = null

    @Before fun fixLocaleAndTimeZone() {
        originalLocale = Locale.getDefault()
        originalTimeZone = TimeZone.getDefault()
        originalFontScale = RuntimeEnvironment.getApplication().resources.configuration.fontScale
        originalWindowSize = System.getProperty(REQUESTED_WINDOW_SIZE)
        Locale.setDefault(Locale.US)
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        System.setProperty(REQUESTED_WINDOW_SIZE, "true")
    }

    @After fun restoreLocaleAndTimeZone() {
        Locale.setDefault(originalLocale)
        TimeZone.setDefault(originalTimeZone)
        RuntimeEnvironment.setFontScale(originalFontScale)
        val windowSize = originalWindowSize
        if (windowSize == null) System.clearProperty(REQUESTED_WINDOW_SIZE)
        else System.setProperty(REQUESTED_WINDOW_SIZE, windowSize)
    }

    @Test @Config(qualifiers = "w360dp-h1200dp-xxhdpi")
    fun receiptAutomatic() = receipt("p01-receipt-automatic.png")

    @Test @Config(qualifiers = "w360dp-h1200dp-xxhdpi")
    fun receiptAfterUndo() = receipt("p02-receipt-after-undo.png", offered = true, undone = true)

    @Test @Config(qualifiers = "w360dp-h1200dp-xxhdpi")
    fun receiptAsk() = receipt("p03-receipt-ask.png", offered = true)

    @Test @Config(qualifiers = "w360dp-h1200dp-xxhdpi")
    fun receiptRoughDay() = receipt("p04-receipt-rough-day.png", rough = true)

    @Test @Config(qualifiers = "w360dp-h1200dp-xxhdpi")
    fun receiptWhy() = receipt("p05-receipt-why.png", why = true)

    @Test @Config(qualifiers = "w360dp-h1000dp-xxhdpi")
    fun receiptCardio() {
        val opening = CardioBlock(CardioActivity.ROAD_RUN, listOf(CardioSetSpec(CardioRole.WORK, Measure.Distance(5000))))
        val next = opening.copy(sets = listOf(CardioSetSpec(CardioRole.WORK, Measure.Distance(5250))))
        render {
            SessionReceiptScreen(timeTargetFixture(), {}, {}, progress = ReceiptProgressState(
                undoVisible = true, cardioSessionId = 2, cardioEffort = CardioEffort.GOOD,
                cardioNext = CardioNextTime.Offer(next, opening, true, CardioIncrease.Distance(250)), cardioStored = next))
        }
        capture("p06-receipt-cardio.png")
    }

    @Test @Config(qualifiers = "w360dp-h1100dp-xxhdpi")
    fun planAutomatic() = plan("p07-plan-automatic.png", true)
    @Test @Config(qualifiers = "w360dp-h1100dp-xxhdpi")
    fun planAsk() = plan("p08-plan-ask.png", false)

    @Test @Config(qualifiers = "w360dp-h1200dp-xxhdpi")
    fun liftOptionsPrescribed() = options("p09-lift-options-prescribed.png", prescribed = true)

    @Test @Config(qualifiers = "w360dp-h1200dp-xxhdpi")
    fun liftOptionsUnprescribed() = options("p10-lift-options-unprescribed.png")

    @Test @Config(qualifiers = "w360dp-h1200dp-xxhdpi")
    fun liftOptionsKeptToPlan() = options("p11-lift-options-kept-to-plan.png", kept = true)

    @Test fun settingsEffortScale() {
        render {
            SetupScreen(SetupUiState(effortScale = EffortScale.REPS_LEFT), SetupActions(
                onUnitToggle = {}, onRestTimerEnabledChange = {}, onRestOverrideChange = { _, _ -> },
                onRestOverridesReset = {}, onCreateCustomExercise = {}, onOpenBackup = {}, onOpenLicenses = {}, onBack = {}))
        }
        rule.onNode(hasScrollAction()).performScrollToNode(hasText("Effort scale"))
        capture("p12-settings-effort-scale.png")
    }

    @Test fun repsLeft() = repsLeft("p13-reps-left-1x.png", 1f)
    @Test fun repsLeftLargeFont() = repsLeft("p14-reps-left-2x.png", 2f)

    @Test fun markerEstimate() = marker("p15-marker-estimate.png", LiftMarker.ESTIMATE)
    @Test fun markerLightWeek() = marker("p16-marker-light-week.png", LiftMarker.LIGHT_WEEK)
    @Test fun markerAfterBreak() = marker("p17-marker-after-break.png", LiftMarker.AFTER_BREAK)

    @Test fun liftSheetMarker() {
        val base = markerState(LiftMarker.LIGHTER_TODAY)
        render { WorkoutSetHost(base.copy(
            openSheet = OpenSheet("A", OpenSheet.Kind.LIFT, base.exercises.single().programExerciseId, 0))) }
        capture("p18-lift-sheet-marker.png", sheet = true)
    }

    @Test fun todayLighter() {
        render { TodayScreen(TodayUiState(hasProgram = true,
            upNext = listOf(TodayDay(1, "Strength", "A", 0, "Bench Press", lighterAvailable = true))), TodayActions({}, {}, {})) }
        capture("p19-today-lighter.png")
    }

    @Test fun receiptTwoLegs() {
        val set = twoLegsSet()
        val receipt = timeTargetFixture().copy(minutes = 28,
            cardioRows = ReceiptBuilder.cardio(CardioActivity.ROAD_RUN, listOf(set), emptyMap()))
        render { CompositionLocalProvider(LocalDistanceUnit provides DistanceUnit.MI) {
            SessionReceiptScreen(receipt, {}, {}, progress = ReceiptProgressState(cardioSessionId = 2,
                cardioSets = listOf(listOf(set))))
        } }
        rule.onNodeWithText("3.11 mi at 25:00", useUnmergedTree = true).assertExists()
        rule.onNodeWithText("3:10, 0.37 mi past the target", useUnmergedTree = true).assertExists()
        capture("p20-receipt-two-legs.png")
    }

    @Test fun historyTwoLegs() {
        val set = twoLegsSet()
        val item = SessionListItem(1, "Nov 12", "A", 0, "Road run", 0, null, false,
            cardioId = 2, cardioSets = listOf(set), cardioSeconds = set.elapsedSeconds, activity = CardioActivity.ROAD_RUN)
        render { CompositionLocalProvider(LocalDistanceUnit provides DistanceUnit.MI) {
            LogScreen(LogUiState(sessions = listOf(item)), LogActions({}, {}, {}, {}, {}, {}, {},
                onStartSession = {}, onSetUpProgram = {}))
        } }
        rule.onNodeWithText("3.11 mi at 25:00", useUnmergedTree = true).assertExists()
        rule.onNodeWithText("3:10, 0.37 mi past the target", useUnmergedTree = true).assertExists()
        capture("p21-history-two-legs.png")
    }

    private fun ladderProgress(hold: LadderHold? = null): ReceiptProgressState {
        val block = RunWalkLadder.block(if (hold == null) 2 else 1, CardioActivity.ROAD_RUN)
        return ReceiptProgressState(cardioSessionId = 2, cardioEffort = CardioEffort.EASY,
            undoVisible = true, cardioStored = block, runLadder = true,
            run = RunResult(if (hold == null) CardioChange.LevelTo(2) else CardioChange.Repeat,
                1, hold, false, false, block))
    }

    private fun ladderReceipt(name: String, scale: Float = 1f, why: Boolean = false,
        hold: LadderHold? = null, replan: Boolean = false) {
        val opening = RunWalkLadder.block(if (replan) 12 else 1, CardioActivity.ROAD_RUN)
        val results = opening.sets.map { spec ->
            CardioSetResult(spec, CardioSetStatus.FINISHED, (spec.end as Measure.Time).seconds, null, null)
        }
        val receipt = timeTargetFixture().copy(dayId = "A", dayIndex = 0,
            dayTitle = cloud.trotter.log.strength.domain.progression.RunSession.LADDER.title,
            minutes = (results.sumOf { it.elapsedSeconds } + 59) / 60,
            cardioRows = ReceiptBuilder.cardio(opening.activity, results, emptyMap()))
        val progress = if (replan) {
            val continuous = CardioBlock(opening.activity, listOf(CardioSetSpec(CardioRole.WORK, Measure.Time(1800))))
            ReceiptProgressState(cardioSessionId = 2, cardioEffort = CardioEffort.EASY, undoVisible = true,
                cardioStored = continuous, runLadder = true, replan = ReceiptReplan(RunPhase.BASE, true),
                run = RunResult(CardioChange.LadderDone, 12, null, false, false, continuous))
        } else ladderProgress(hold)
        render(scale) {
            SessionReceiptScreen(receipt, {}, {}, progress = progress.copy(
                cardioSets = ReceiptBuilder.cardioGroups(results).map { it.sets }))
        }
        if (why) rule.onNodeWithText("Why").performScrollTo().performClick()
        capture(name, sheet = why)
    }

    @Test @Config(qualifiers = "w360dp-h1200dp-xxhdpi")
    fun receiptLadder() = ladderReceipt("p18-receipt-ladder.png")
    @Test @Config(qualifiers = "w360dp-h1200dp-xxhdpi")
    fun receiptLadderLargeFont() = ladderReceipt("p18-receipt-ladder-2x.png", scale = 2f)
    @Test @Config(qualifiers = "w360dp-h1200dp-xxhdpi")
    fun receiptLadderWhy() = ladderReceipt("p19-receipt-ladder-why.png", why = true)
    @Test @Config(qualifiers = "w360dp-h1200dp-xxhdpi")
    fun receiptLadderWhyLargeFont() = ladderReceipt("p19-receipt-ladder-why-2x.png", scale = 2f, why = true)
    @Test @Config(qualifiers = "w360dp-h1200dp-xxhdpi")
    fun receiptLadderWeekCap() = ladderReceipt("p20-receipt-ladder-week-cap.png", why = true, hold = LadderHold.WEEK_CAP)
    @Test @Config(qualifiers = "w360dp-h1200dp-xxhdpi")
    fun receiptReplanOffer() = ladderReceipt("p21-receipt-replan-offer.png", replan = true)

    private fun keepLadder(name: String, scale: Float = 1f) {
        render(scale) { Surface(color = MaterialTheme.colorScheme.surface) {
            CardioNextTimePage(DayEditUiState(cardioBlock = RunWalkLadder.block(1, CardioActivity.ROAD_RUN),
                cardioLadder = true, cardioProgress = CardioProgress(keepToPlan = true),
                cardioMeasures = setOf(ProgressMeasure.TIME)),
                DayEditActions(onSwap = { _, _ -> }, onAdd = {}, onRemove = {}, onSetSuperset = { _, _ -> },
                    onRemoveSuperset = {}, onResetToTemplate = {}), {})
        } }
        capture(name)
    }
    @Test @Config(qualifiers = "w360dp-h1200dp-xxhdpi")
    fun keepLadder() = keepLadder("p22-keep-ladder.png")
    @Test @Config(qualifiers = "w360dp-h1200dp-xxhdpi")
    fun keepLadderLargeFont() = keepLadder("p22-keep-ladder-2x.png", 2f)

    private fun ladderChip(name: String, scale: Float) {
        val base = workoutSetCardioFixture()
        val opening = RunWalkLadder.block(1, CardioActivity.ROAD_RUN)
        val offered = RunWalkLadder.block(2, CardioActivity.ROAD_RUN)
        render(scale) { WorkoutSetHost(base.copy(cardio = base.cardio!!.copy(block = opening,
            nextTime = CardioNextTime.Offer(offered, opening, false, CardioIncrease.Level(2), 1)))) }
        rule.onNodeWithText("Level 2").assertIsDisplayed()
        capture(name)
    }
    @Test fun chipLadder() = ladderChip("p23-chip-ladder-1x.png", 1f)
    @Test fun chipLadderLargeFont() = ladderChip("p24-chip-ladder-2x.png", 2f)

    @Test @Config(qualifiers = "w360dp-h1200dp-xxhdpi")
    fun planGoalHours() {
        render {
            PlanScreen(PlanUiState(name = "Marathon", hasProgram = true, runPhase = RunPhase.MARATHON_BUILD,
                goal = PlanGoalUi(PlanGoal.Timed(CardioActivity.ROAD_RUN, 42195, 12600), null, false,
                    predictedSeconds = 14449, predictedFrom = RecentRace(21097, 6600)),
                days = listOf(PlanDay("A", 0, "Easy", 0, 0, true, cardioActivity = CardioActivity.ROAD_RUN))),
                PlanActions({}, {}, {}))
        }
        rule.onNode(hasScrollAction()).performScrollToNode(hasText("Predicted 4:00:49", substring = true))
        capture("p25-plan-goal-hours.png")
    }

    private fun sprintStart(name: String, scale: Float, dose: SprintDose = SprintDose.NORMAL, careful: Boolean = false) {
        render(scale) { WorkoutSetHost(sprintStartFixture(dose,
            reason = if (careful) SpacingReason.FORTY_OR_OLDER else SpacingReason.STARTING_OUT, nineTenths = careful)) }
        capture(name)
    }
    @Test @Config(qualifiers = "w360dp-h1600dp-xxhdpi")
    fun sprintStartFirstWeeks() = sprintStart("p26-sprint-start-first-weeks.png", 1f)
    @Test @Config(qualifiers = "w360dp-h1600dp-xxhdpi")
    fun sprintStartFirstWeeksLargeFont() = sprintStart("p27-sprint-start-first-weeks-2x.png", 2f)
    @Test @Config(qualifiers = "w360dp-h1600dp-xxhdpi")
    fun sprintStartCareful() = sprintStart("p28-sprint-start-careful.png", 1f, careful = true)
    @Test @Config(qualifiers = "w360dp-h1600dp-xxhdpi")
    fun sprintStartCarefulLargeFont() = sprintStart("p29-sprint-start-careful-2x.png", 2f, careful = true)
    @Test @Config(qualifiers = "w360dp-h1600dp-xxhdpi")
    fun sprintStartShortened() = sprintStart("p30-sprint-start-shortened.png", 1f, SprintDose.SHORTENED)
    @Test @Config(qualifiers = "w360dp-h1600dp-xxhdpi")
    fun sprintStartShortenedLargeFont() = sprintStart("p31-sprint-start-shortened-2x.png", 2f, SprintDose.SHORTENED)
    @Test @Config(qualifiers = "w360dp-h1600dp-xxhdpi")
    fun sprintStartSubstitute() = sprintStart("p32-sprint-start-substitute.png", 1f, SprintDose.SUBSTITUTE)
    @Test @Config(qualifiers = "w360dp-h1600dp-xxhdpi")
    fun sprintStartSubstituteLargeFont() = sprintStart("p33-sprint-start-substitute-2x.png", 2f, SprintDose.SUBSTITUTE)

    private fun sprintReceipt(name: String, scale: Float, change: CardioChange = CardioChange.Step,
                              dose: SprintDose = SprintDose.NORMAL, introLeft: Int? = null) {
        render(scale) { SessionReceiptScreen(sprintReceiptFixture(introLeft != null, dose), {}, {},
            progress = sprintReceiptProgressFixture(change, dose, introLeft)) }
        rule.onNodeWithText("Why").performScrollTo().performClick()
        capture(name, sheet = true)
    }
    @Test @Config(qualifiers = "w360dp-h1200dp-xxhdpi")
    fun sprintReceiptStep() = sprintReceipt("p34-sprint-receipt-step.png", 1f)
    @Test @Config(qualifiers = "w360dp-h1200dp-xxhdpi")
    fun sprintReceiptStepLargeFont() = sprintReceipt("p35-sprint-receipt-step-2x.png", 2f)
    @Test @Config(qualifiers = "w360dp-h1200dp-xxhdpi")
    fun sprintReceiptIntro() = sprintReceipt("p36-sprint-receipt-intro.png", 1f, CardioChange.Repeat, introLeft = 2)
    @Test @Config(qualifiers = "w360dp-h1200dp-xxhdpi")
    fun sprintReceiptIntroLargeFont() = sprintReceipt("p37-sprint-receipt-intro-2x.png", 2f, CardioChange.Repeat, introLeft = 2)
    @Test @Config(qualifiers = "w360dp-h1200dp-xxhdpi")
    fun sprintReceiptShortened() = sprintReceipt("p38-sprint-receipt-shortened.png", 1f, CardioChange.Repeat, SprintDose.SHORTENED)
    @Test @Config(qualifiers = "w360dp-h1200dp-xxhdpi")
    fun sprintReceiptShortenedLargeFont() = sprintReceipt("p39-sprint-receipt-shortened-2x.png", 2f, CardioChange.Repeat, SprintDose.SHORTENED)

    private fun restBeforeSprint(name: String, scale: Float) {
        render(scale) { TodayScreen(TodayUiState(hasProgram = true,
            upNext = listOf(TodayDay(1, "Sprint", "B", 1, "Rest", rest = true, trainDayId = "C", beforeSprint = true))),
            TodayActions({}, {}, {})) }
        capture(name)
    }
    @Test fun todayRestBeforeSprint() = restBeforeSprint("p40-today-rest-before-sprint.png", 1f)
    @Test fun todayRestBeforeSprintLargeFont() = restBeforeSprint("p41-today-rest-before-sprint-2x.png", 2f)

    private fun sprintPlan(name: String, scale: Float) {
        val program = Planner.plan(SetupAnswers(branch = PlanBranch.SPRINT, days = 2)).program.let { program ->
            program.copy(days = program.days.map { day ->
                if (day.id == "A") day.copy(cardioRule = requireNotNull(day.cardioRule).copy(intro = 0, steps = 2)) else day
            })
        }
        val plan = PlanEntity(1, "Sprint", "AUTHORED", 0, 0, suggestedDayId = "A",
            goalJson = PlanGoalJson.encode(PlanGoal.Sprint(100)))
        val state = PlanScreenBuilder.buildUiState(plan, program, ExerciseCatalog.CODE_ONLY, WeightUnit.LB, todayEpochDay = 0L)
        render(scale) { PlanScreen(state, PlanActions({}, {}, {})) }
        capture(name)
    }
    @Test @Config(qualifiers = "w360dp-h1600dp-xxhdpi")
    fun planSprintDays() = sprintPlan("p42-plan-sprint-days.png", 1f)
    @Test @Config(qualifiers = "w360dp-h1600dp-xxhdpi")
    fun planSprintDaysLargeFont() = sprintPlan("p43-plan-sprint-days-2x.png", 2f)

    private fun sprintEditor(name: String, scale: Float) {
        render(scale) { DayPlanEditor(sprintEditorFixture(), "A", false, MaterialTheme.colorScheme.primary,
            DayEditActions(onSwap = { _, _ -> }, onAdd = {}, onRemove = {}, onSetSuperset = { _, _ -> },
                onRemoveSuperset = {}, onResetToTemplate = {}), {}, {}) }
        capture(name)
    }
    @Test fun dayEditorSprint() = sprintEditor("p44-day-editor-sprint.png", 1f)
    @Test fun dayEditorSprintLargeFont() = sprintEditor("p45-day-editor-sprint-2x.png", 2f)

    private fun sprintRep(name: String, scale: Float, offsetMillis: Long? = null, recovery: Boolean = false) {
        val elapsed = 10_000L
        val state = sprintRepFixture(offsetMillis?.let { elapsed + it }, recovery)
        render(scale) {
            CompositionLocalProvider(LocalSprintElapsedMillis provides { elapsed }) { WorkoutSetHost(state) }
        }
        if (offsetMillis == null && !recovery) rule.onNodeWithText("Start on the beep").assertIsDisplayed()
        if (recovery) rule.onNodeWithText("4.52 (Phone-timed)").performScrollTo()
        capture(name)
    }
    @Test fun sprintRepReady() = sprintRep("p48-sprint-rep-ready.png", 1f)
    @Test fun sprintRepReadyLargeFont() = sprintRep("p49-sprint-rep-ready-2x.png", 2f)
    @Test fun sprintRepArmed() = sprintRep("p50-sprint-rep-armed.png", 1f, 3_000L)
    @Test fun sprintRepArmedLargeFont() = sprintRep("p51-sprint-rep-armed-2x.png", 2f, 3_000L)
    @Test fun sprintRepRunning() = sprintRep("p52-sprint-rep-running.png", 1f, -4_520L)
    @Test fun sprintRepRunningLargeFont() = sprintRep("p53-sprint-rep-running-2x.png", 2f, -4_520L)
    @Test fun sprintRecoveryTime() = sprintRep("p54-sprint-recovery-time.png", 1f, recovery = true)
    @Test fun sprintRecoveryTimeLargeFont() = sprintRep("p55-sprint-recovery-time-2x.png", 2f, recovery = true)

    private fun sprintTimeEntry(name: String, scale: Float) {
        render(scale) {
            WorkoutSetHost(sprintRepFixture())
            SprintTimeSheet(SprintTimeDraft("run", 1, "8.30", "GATES", "THREE_POINT"), {}, { _, _, _ -> }, {})
        }
        capture(name, sheet = true)
    }
    @Test fun sprintTimeSheet() = sprintTimeEntry("p56-sprint-time-sheet.png", 1f)
    @Test fun sprintTimeSheetLargeFont() = sprintTimeEntry("p57-sprint-time-sheet-2x.png", 2f)

    private fun sprintGoal(name: String, scale: Float, editing: Boolean = false) {
        val best = SprintAttempt(1L, SprintTime(1284, TimingMethod.PHONE, StartProtocol.THREE_POINT))
        val goal = PlanGoal.Sprint(100, targetCentis = 1250, attempts = listOf(best), byEpochDay = 20_788)
        val state = PlanUiState(hasProgram = true, planId = 1, name = "Sprint",
            days = listOf(PlanDay("A", 0, "Sprint A", 0, 0, true, cardioActivity = CardioActivity.TRACK_RUN, cardioSetCount = 4)),
            sprintGoal = PlanSprintGoalUi(goal, best),
            sprintGoalDraft = SprintGoalDraft("12.50", goal.byEpochDay).takeIf { editing })
        render(scale) { PlanScreen(state, PlanActions({}, {}, {})) }
        if (!editing) rule.onNode(hasScrollAction()).performScrollToNode(hasText("Start type: Three-point"))
        capture(name, sheet = editing)
    }
    @Test fun planSprintGoal() = sprintGoal("p58-plan-sprint-goal.png", 1f)
    @Test fun planSprintGoalLargeFont() = sprintGoal("p59-plan-sprint-goal-2x.png", 2f)
    @Test fun planSprintGoalEditor() = sprintGoal("p60-plan-sprint-goal-editor.png", 1f, editing = true)
    @Test fun planSprintGoalEditorLargeFont() = sprintGoal("p61-plan-sprint-goal-editor-2x.png", 2f, editing = true)

    private fun fortyYardReached(name: String, scale: Float) {
        render(scale) { WorkoutSetHost(sprintRepFixture(metres = 37)) }
        rule.onNodeWithText("Reached 40 yd").performScrollTo().assertIsDisplayed()
        capture(name)
    }
    @Test @Config(qualifiers = "w360dp-h1200dp-xxhdpi")
    fun sprintFortyYardReached() = fortyYardReached("p62-sprint-40yd-reached.png", 1f)
    @Test @Config(qualifiers = "w360dp-h1600dp-xxhdpi")
    fun sprintFortyYardReachedLargeFont() = fortyYardReached("p63-sprint-40yd-reached-2x.png", 2f)

    private fun fortyYardReceipt(name: String, scale: Float) {
        val (receipt, progress) = sprintFortyYardReceiptFixture()
        render(scale) { SessionReceiptScreen(receipt, {}, {}, progress = progress) }
        rule.onNodeWithText("40 yd", useUnmergedTree = true).performScrollTo().assertIsDisplayed()
        capture(name)
    }
    @Test @Config(qualifiers = "w360dp-h1200dp-xxhdpi")
    fun receiptFortyYards() = fortyYardReceipt("p64-receipt-40yd.png", 1f)
    @Test @Config(qualifiers = "w360dp-h1600dp-xxhdpi")
    fun receiptFortyYardsLargeFont() = fortyYardReceipt("p65-receipt-40yd-2x.png", 2f)

    private fun pastCardioEndsAfterNow(name: String, scale: Float) {
        val noon = java.time.Instant.parse("2026-11-09T12:00:00Z").toEpochMilli()
        val draft = CardioEntryDraft(CardioActivity.ROAD_RUN, noon - 25 * 60_000L, 2872, "4989",
            "Easy loop after work. Kept my first mile slow and walked up the hill.")
        render(scale) { CompositionLocalProvider(LocalDistanceUnit provides DistanceUnit.MI) {
            CardioEntrySheet(draft, "Log past session", { noon }, {}, {}, {})
        } }
        rule.onNodeWithText("Ends at 12:22, after now").assertIsDisplayed()
        capture(name, sheet = true)
    }

    @Test fun pastCardioEndsAfterNow() = pastCardioEndsAfterNow("p66-past-cardio-ends-after-now.png", 1f)
    @Test @Config(qualifiers = "w360dp-h1600dp-xxhdpi")
    fun pastCardioEndsAfterNowLargeFont() = pastCardioEndsAfterNow("p67-past-cardio-ends-after-now-2x.png", 2f)

    private fun cardioRecoveryNextStarts(name: String, scale: Float) {
        val base = workoutSetCardioRunningFixture()
        val cardio = requireNotNull(base.cardio)
        val original = requireNotNull(cardio.run)
        val block = RunWalkLadder.block(1, CardioActivity.ROAD_RUN)
        val run = original.copy(blockJson = CardioBlockJson.encode(block), sourceBlockJson = CardioBlockJson.encode(block),
            results = listOf(SavedCardioResult(CardioSetStatus.FINISHED.name, 60)),
            startedAt = 1_000L, wallStart = 61_000L, elapsedStart = 61_000L)
        val state = base.copy(cardio = cardio.copy(block = block, run = run, stepSecondsLeft = 50, elapsedSeconds = 40))
        render(scale) { WorkoutSetHost(state, nowMillis = { 101_000L }) }
        rule.onNodeWithText("The next interval starts on its own.").assertIsDisplayed()
        capture(name)
    }

    @Test fun cardioRecoveryNextStarts() = cardioRecoveryNextStarts("p68-cardio-recovery-next-starts.png", 1f)
    @Test @Config(qualifiers = "w360dp-h1600dp-xxhdpi")
    fun cardioRecoveryNextStartsLargeFont() = cardioRecoveryNextStarts("p69-cardio-recovery-next-starts-2x.png", 2f)

    private fun cardioDayOpensOnRun(name: String, scale: Float) {
        val base = workoutSetCardioFixture()
        val lift = workoutSetSingleFixture().exercises.single()
        val state = base.copy(cardioLeads = true, dayTitle = "Easy", exercises = listOf(lift,
            lift.copy(programExerciseId = 2, position = 1, title = "Barbell Row", exerciseId = "bb_row")))
        render(scale) { WorkoutSetHost(state) }
        rule.onNodeWithText("Road run").assertIsDisplayed()
        rule.onNodeWithText("Start").assertIsDisplayed()
        rule.onNodeWithText("Log past session").assertIsDisplayed()
        capture(name)
    }

    @Test fun cardioDayOpensOnRun() = cardioDayOpensOnRun("p70-cardio-day-opens-on-run.png", 1f)
    @Test @Config(qualifiers = "w360dp-h1600dp-xxhdpi")
    fun cardioDayOpensOnRunLargeFont() = cardioDayOpensOnRun("p71-cardio-day-opens-on-run-2x.png", 2f)

    private fun todayRunDayActivity(name: String, scale: Float) {
        render(scale) { TodayScreen(TodayUiState(hasProgram = true, upNext = listOf(
            TodayDay(1, "Running", "A", 0, "Easy", cardioOnly = true, cardioActivity = CardioActivity.ROAD_RUN, liftCount = 2))),
            TodayActions({}, {}, {})) }
        rule.onNodeWithText("Easy, road run and 2 lifts").assertIsDisplayed()
        capture(name)
    }

    @Test fun todayRunDayActivity() = todayRunDayActivity("p72-today-run-day-activity.png", 1f)
    @Test @Config(qualifiers = "w360dp-h1600dp-xxhdpi")
    fun todayRunDayActivityLargeFont() = todayRunDayActivity("p73-today-run-day-activity-2x.png", 2f)

    private fun allSetsPlannedTarget(name: String, scale: Float) {
        val base = workoutSetSingleFixture()
        val card = base.exercises.single().copy(title = "DB Bench Press", exerciseId = "db_bench",
            headerTarget = HeaderTarget.Prescribed("35 lb × 8"), workingHeading = "3 × 8 · 35 lb",
            rows = List(3) { index -> SetRowState(index, "${index + 1}", false,
                if (index == 2) 30.0 else 35.0, if (index == 1) 6 else 8, true) })
        render(scale) { WorkoutSetHost(base.copy(exercises = listOf(card),
            openSheet = OpenSheet("A", OpenSheet.Kind.LIFT, card.programExerciseId, 0))) }
        capture(name, sheet = true)
    }

    @Test fun allSetsPlannedTarget() = allSetsPlannedTarget("p74-all-sets-planned-target.png", 1f)
    @Test @Config(qualifiers = "w360dp-h1600dp-xxhdpi")
    fun allSetsPlannedTargetLargeFont() = allSetsPlannedTarget("p75-all-sets-planned-target-2x.png", 2f)

    private fun swapOtherExercises(name: String, scale: Float) {
        val catalog = ExerciseCatalog.CODE_ONLY
        render(scale) { Surface { ExercisePickerScreen("bench", "Swap Bench Press", MovementPattern.H_PUSH,
            catalog.substitutionsFor("bb_bench"), Equipment.entries.toSet(), MaterialTheme.colorScheme.primary,
            {}, {}, {}, allCandidates = catalog.entries) } }
        rule.onNodeWithText("Search exercises").performTextReplacement("overhead")
        rule.onNodeWithText("Other exercises").assertIsDisplayed()
        capture(name)
    }

    @Test fun swapOtherExercises() = swapOtherExercises("p76-swap-other-exercises.png", 1f)
    @Test @Config(qualifiers = "w360dp-h1600dp-xxhdpi")
    fun swapOtherExercisesLargeFont() = swapOtherExercises("p77-swap-other-exercises-2x.png", 2f)

    private fun receiptCardioPace(name: String, scale: Float, record: Boolean) {
        render(scale) {
            SessionReceiptScreen(rowerPaceReceiptFixture(record), {}, {},
                progress = ReceiptProgressState(cardioLastTimes = listOf(CardioLastTime(null, 3412, true))))
        }
        rule.onNodeWithText("Pace 2:10 /500 m", useUnmergedTree = true).assertIsDisplayed()
        capture(name)
    }
    @Test fun receiptCardioPaceLastTime() = receiptCardioPace("p78-receipt-cardio-pace-last-time.png", 1f, false)
    @Test @Config(qualifiers = "w360dp-h1600dp-xxhdpi")
    fun receiptCardioPaceLastTimeLargeFont() = receiptCardioPace("p79-receipt-cardio-pace-last-time-2x.png", 2f, false)
    @Test fun receiptCardioRecordPace() = receiptCardioPace("p80-receipt-cardio-record-pace.png", 1f, true)
    @Test @Config(qualifiers = "w360dp-h1600dp-xxhdpi")
    fun receiptCardioRecordPaceLargeFont() = receiptCardioPace("p81-receipt-cardio-record-pace-2x.png", 2f, true)

    private fun historyCardioPace(name: String, scale: Float) {
        val result = CardioSetResult(CardioSetSpec(CardioRole.WORK, Measure.Time(1800)),
            CardioSetStatus.FINISHED, 1800, 5000, DistanceSource.MANUAL)
        val session = cloud.trotter.log.strength.data.db.entity.CardioSessionEntity(
            id = 1, dayId = "B", mode = "OUTDOOR_RUN", activity = "ROAD_RUN", hard = false, label = "Road run",
            startedAt = 1_794_657_600_000L, completedAt = 1_794_659_400_000L, seconds = 1800, stepsCompleted = 1)
        render(scale) { CompositionLocalProvider(LocalDistanceUnit provides DistanceUnit.MI) {
            LogScreen(LogUiState(sessions = listOf(LogScreenBuilder.cardioItem(session, listOf(result)))),
                LogActions({}, {}, {}, {}, {}, {}, {}, onStartSession = {}, onSetUpProgram = {}))
        } }
        rule.onNodeWithText("Pace 9:39 /mi").assertIsDisplayed()
        capture(name)
    }
    @Test fun historyCardioPace() = historyCardioPace("p82-history-cardio-pace.png", 1f)
    @Test @Config(qualifiers = "w360dp-h1600dp-xxhdpi")
    fun historyCardioPaceLargeFont() = historyCardioPace("p83-history-cardio-pace-2x.png", 2f)

    private fun historyCardioTrends(name: String, scale: Float) {
        val (sessions, sets) = cardioTrendHistoryFixture()
        val history = auditHistoryFixture()
        val journal = JournalUiState(trajectories = history.journal.trajectories.take(1),
            cardioTrends = JournalBuilder.cardioTrends(sessions, sets, java.time.ZoneOffset.UTC))
        render(scale) {
            LogScreen(LogUiState(sessions = history.sessions, journal = journal),
                LogActions({}, {}, {}, {}, {}, {}, {}, onStartSession = {}, onSetUpProgram = {}))
        }
        capture(name)
    }
    @Test @Config(qualifiers = "w360dp-h1200dp-xxhdpi")
    fun historyCardioTrends() = historyCardioTrends("p84-history-cardio-trends.png", 1f)
    @Test @Config(qualifiers = "w360dp-h1600dp-xxhdpi")
    fun historyCardioTrendsLargeFont() = historyCardioTrends("p85-history-cardio-trends-2x.png", 2f)

    private fun liftOptionsLoadSource(name: String, scale: Float) {
        val slot = DayEditSlotState(1, 0, "bb_back_squat", "Barbell Back Squat", null, false,
            weightedTarget = true, lifts = listOf(LiftOptionsState(Slot.MAIN, "Barbell Back Squat",
                180, 180, false, 2, 0.025, "5 lb", sourceDays = listOf("A"), loadSource = "A", loadRatio = 0.92)))
        render(scale) { Surface(color = MaterialTheme.colorScheme.surface) {
            SlotOptionsScreen(slot, {}, {}, {}, {}, {}, {}, false, {}, {}, {}, {})
        } }
        rule.onNodeWithText("Day A").performScrollTo().assertIsDisplayed().assertIsSelected()
        rule.onNodeWithText("Load source").assertIsDisplayed()
        rule.onNodeWithText("Own results").assertIsDisplayed().assertIsNotSelected()
        capture(name)
    }
    @Test @Config(qualifiers = "w360dp-h1200dp-xxhdpi")
    fun liftOptionsLoadSource() = liftOptionsLoadSource("p97-lift-options-load-source.png", 1f)
    @Test @Config(qualifiers = "w360dp-h1600dp-xxhdpi")
    fun liftOptionsLoadSourceLargeFont() = liftOptionsLoadSource("p98-lift-options-load-source-2x.png", 2f)

    private fun dayEditorFollows(name: String, scale: Float) {
        val slot = DayEditSlotState(1, 0, "bb_back_squat", "Barbell Back Squat", null, false,
            rows = List(3) { LoggedSet(170.0, 5, SetKind.WORK) }, follows = "A")
        render(scale) { DayPlanEditor(DayEditUiState(slots = listOf(slot), planEditing = true),
            "C", false, MaterialTheme.colorScheme.primary,
            DayEditActions(onSwap = { _, _ -> }, onAdd = {}, onRemove = {}, onSetSuperset = { _, _ -> },
                onRemoveSuperset = {}, onResetToTemplate = {}), {}, {}) }
        rule.onNodeWithText("Follows Day A").assertIsDisplayed()
        capture(name)
    }
    @Test fun dayEditorFollows() = dayEditorFollows("p99-day-editor-follows.png", 1f)
    @Test fun dayEditorFollowsLargeFont() = dayEditorFollows("p100-day-editor-follows-2x.png", 2f)

    private fun receiptFollowers(name: String, scale: Float) {
        val row = ReceiptRow("Barbell Back Squat", TrackingType.WEIGHTED, ReceiptRow.Shape.STRAIGHT,
            List(3) { ReceiptSet(185.0, 5, 0) }, listOf(ValueLine(185.0, listOf(0, 1, 2))),
            0, PlannedSets(3, 185.0, 5, 0), null)
        val line = ReceiptLiftLine(190.0, null, null, 5.0, false, "ALL_MADE", 1, true)
        val progress = ReceiptProgressState(lifts = listOf(ReceiptLiftProgress(ReceiptSlotRef(0, 0, 7, Slot.MAIN),
            row.name, line, whyLine = line, followers = listOf(ReceiptFollower("C", 175.0, 5.0)))))
        render(scale) {
            SessionReceiptScreen(receiptFixture().copy(groups = listOf(ReceiptGroup(null, listOf(row))),
                cardioRows = emptyList(), activity = null), {}, {}, progress = progress)
        }
        rule.onNodeWithText("Day C: 175 lb (+5)", useUnmergedTree = true).performScrollTo().assertIsDisplayed()
        capture(name)
    }
    @Test @Config(qualifiers = "w360dp-h1200dp-xxhdpi")
    fun receiptFollowers() = receiptFollowers("p101-receipt-followers.png", 1f)
    @Test @Config(qualifiers = "w360dp-h1600dp-xxhdpi")
    fun receiptFollowersLargeFont() = receiptFollowers("p102-receipt-followers-2x.png", 2f)

    private fun benchmarkReceipt(name: String, scale: Float) {
        val card = benchmarkWindowPreviewState().exercises.single()
        val planned = card.rows.map { LoggedSet(0.0, it.reps, SetKind.WORK) }
        val track = RunTrack(1L, 0, 1L, Slot.MAIN, card.exerciseId, false, planned,
            planned.map { it.copy(reps = 27, done = true) }, opening = OpeningDto(benchmark = true))
        val slot = ProgramSlot(1L, 0, ProgramExercise(card.exerciseId, targetSets = 3,
            prescription = Prescription(PrescribedTarget.RepsInTime(120, 20), test = TestRef("aft_hrp", 1, 120))))
        val groups = ReceiptBuilder.strength(listOf(track), listOf(slot), { card.title }, emptyMap())
        render(scale) {
            val receipt = receiptFixture().copy(groups = groups, minutes = 2, cardioRows = emptyList(), activity = null)
            SessionReceiptScreen(receipt, {}, {}, setCounts = ReceiptSetCounts(receipt.sessionId, 1, 0))
        }
        rule.onNodeWithText("27 reps", useUnmergedTree = true).performScrollTo().assertIsDisplayed()
        capture(name)
    }
    @Test @Config(qualifiers = "w360dp-h1200dp-xxhdpi")
    fun benchmarkReceipt() = benchmarkReceipt("p109-benchmark-receipt.png", 1f)
    @Test @Config(qualifiers = "w360dp-h1600dp-xxhdpi")
    fun benchmarkReceiptLargeFont() = benchmarkReceipt("p110-benchmark-receipt-2x.png", 2f)

    private fun eachSideEditor(name: String, scale: Float) {
        val state = workoutSetEachSideFixture(split = true)
        val card = state.exercises.single()
        render(scale) {
            WorkoutSetHost(state)
            SetEditSheet(card, card.rows.first(), state.unit, state.dayIndex, workoutActions(), {})
        }
        rule.onNodeWithTag("set-edit-each-side").assertIsDisplayed()
        rule.onNodeWithTag("set-edit-left-and-right").assertIsDisplayed()
        rule.onNodeWithContentDescription("Left, Reps").assertIsDisplayed()
        rule.onNodeWithContentDescription("Right, Reps").assertIsDisplayed()
        capture(name, sheet = true)
    }

    @Test @Config(qualifiers = "w360dp-h1200dp-xxhdpi")
    fun eachSideEditor() = eachSideEditor("p93-each-side-editor.png", 1f)
    @Test @Config(qualifiers = "w360dp-h1600dp-xxhdpi")
    fun eachSideEditorLargeFont() = eachSideEditor("p93-each-side-editor-2x.png", 2f)

    private fun eachSideLoggedSnackbar(name: String, scale: Float) {
        val base = workoutSetEachSideFixture(split = true)
        val card = base.exercises.single()
        val row = card.rows.first()
        val logged = LoggedSet(0.0, row.reps, SetKind.WORK, done = true, completedAtMillis = 100_000L, sides = row.sides)
        val state = base.copy(exercises = listOf(card.copy(rows = card.rows.map {
            if (it.index == row.index) it.copy(done = true) else it
        }))).withWorkoutSelection()
        render(scale) {
            WorkoutSetHost(state.copy(workout = state.workout?.copy(
                undo = UndoOffer(card.programExerciseId, Slot.MAIN, row.index, card.title, logged, card.tracking, state.unit),
            )), nowMillis = { rule.mainClock.currentTime })
        }
        rule.onNodeWithText("✓ Set 1 · L 10 · R 8 reps").assertIsDisplayed()
        rule.onNodeWithText("Undo").assertIsDisplayed()
        capture(name)
    }

    @Test fun eachSideLoggedSnackbar() = eachSideLoggedSnackbar("p94-each-side-logged-snackbar.png", 1f)
    @Test fun eachSideLoggedSnackbarLargeFont() = eachSideLoggedSnackbar("p94-each-side-logged-snackbar-2x.png", 2f)

    private fun eachSideReceipt(name: String, scale: Float) {
        val row = ReceiptRow("Skater Squat", TrackingType.REPS, ReceiptRow.Shape.STRAIGHT,
            listOf(ReceiptSet(0.0, 8, 0), ReceiptSet(0.0, 9, 0)),
            listOf(ValueLine(null, listOf(0, 1))), 0, null, null)
        val receipt = receiptFixture().copy(minutes = 8, groups = listOf(ReceiptGroup(null, listOf(row))))
        val sides = ReceiptSides(receipt.sessionId, row.sets.mapIndexed { index, set ->
            ReceiptSideSet(0, 0, index, set, ReceiptSet(0.0, 10, 0))
        }, listOf(ReceiptSideGap(0, 0, true)))
        render(scale) { SessionReceiptScreen(receipt, {}, {}, sides = sides) }
        rule.onNodeWithContentDescription("Skater Squat.", substring = true).performScrollTo().performClick()
        rule.onNodeWithText("L 8 · R 10 reps", useUnmergedTree = true).assertIsDisplayed()
        rule.onNodeWithText("L 9 · R 10 reps", useUnmergedTree = true).assertIsDisplayed()
        rule.onNodeWithText("Left side lower the last 3 sessions", useUnmergedTree = true).assertIsDisplayed()
        capture(name)
    }

    @Test @Config(qualifiers = "w360dp-h1200dp-xxhdpi")
    fun eachSideReceipt() = eachSideReceipt("p95-each-side-receipt-expanded-gap.png", 1f)
    @Test @Config(qualifiers = "w360dp-h1600dp-xxhdpi")
    fun eachSideReceiptLargeFont() = eachSideReceipt("p95-each-side-receipt-expanded-gap-2x.png", 2f)

    private fun eachSideHistory(name: String, scale: Float) {
        val sides = requireNotNull(workoutSetEachSideFixture(split = true).exercises.single().rows.first().sides)
        val base = logSessionFixture(expanded = true)
        val legacy = SessionSetEntity(id = 2, sessionId = base.sessionId, exerciseId = "pullup", exerciseName = "Pull-up",
            slot = Slot.MAIN, setIndex = 0, kind = SetKind.WORK.name, weightLb = 0.0, reps = 8, done = true)
        val split = legacy.copy(id = 1, exerciseId = "skater_squat", exerciseName = "Skater Squat").withSides(sides)
        val session = base.copy(setCount = 2,
            exerciseGroups = LogScreenBuilder.groupByExercise(listOf(split, legacy), base.unit))
        render(scale) {
            LogScreen(LogUiState(sessions = listOf(session)),
                LogActions({}, {}, {}, {}, {}, {}, {}, onStartSession = {}, onSetUpProgram = {}))
        }
        rule.onNodeWithText("1: L ×10 · R ×8").assertIsDisplayed()
        rule.onNodeWithText("1: ×8").assertIsDisplayed()
        capture(name)
    }

    @Test @Config(qualifiers = "w360dp-h1200dp-xxhdpi")
    fun eachSideHistory() = eachSideHistory("p96-each-side-history-and-legacy.png", 1f)
    @Test @Config(qualifiers = "w360dp-h1600dp-xxhdpi")
    fun eachSideHistoryLargeFont() = eachSideHistory("p96-each-side-history-and-legacy-2x.png", 2f)

    private fun twoLegsSet() = CardioSetResult(CardioSetSpec(CardioRole.WORK, Measure.Time(1500)),
        CardioSetStatus.FINISHED, 1690, 5600, DistanceSource.MANUAL, metresAtEnd = 5000,
        secondsAtEnd = 1500, readingSource = ReadingSource.TYPED)

    private fun markerState(marker: LiftMarker): DayUiState {
        val base = workoutSetSingleFixture()
        val count = if (marker == LiftMarker.LIGHT_WEEK || marker == LiftMarker.LIGHTER_TODAY) 2 else 3
        val weight = when (marker) {
            LiftMarker.LIGHT_WEEK, LiftMarker.LIGHTER_TODAY -> 165.0
            LiftMarker.AFTER_BREAK -> 155.0
            LiftMarker.ESTIMATE -> 185.0
            LiftMarker.BENCHMARK -> 0.0 // #646: keep the marker fixture exhaustive
        }
        return base.copy(runOpen = true, exercises = base.exercises.map { it.copy(marker = marker,
            workingHeading = "$count × 5 · ${weight.toInt()} lb",
            rows = List(count) { index -> SetRowState(index, "${index + 1}", false, weight, 5, false) }) },
            workout = base.workout?.copy(focus = WorkoutFocus(1, Slot.MAIN, 0)))
    }

    private fun marker(name: String, marker: LiftMarker) {
        render { WorkoutSetHost(markerState(marker)) }
        capture(name)
    }

    private fun receipt(name: String, offered: Boolean = false, undone: Boolean = false, rough: Boolean = false, why: Boolean = false) {
        val names = listOf("Bench Press", "Pull-up", "Barbell Row")
        val rows = names.mapIndexed { index, title ->
            val weight = if (index == 1) 0.0 else 185.0
            val reps = if (index == 1) 8 else 5
            ReceiptRow(title, if (index == 1) TrackingType.REPS else TrackingType.WEIGHTED, ReceiptRow.Shape.STRAIGHT,
                List(3) { ReceiptSet(weight, reps, 0) }, listOf(ValueLine(weight.takeIf { it > 0 }, listOf(0, 1, 2))),
                0, PlannedSets(3, weight, reps, 0), null)
        }
        val lines = listOf(
            ReceiptLiftLine(if (rough) 185.0 else 190.0, null, null, if (rough) null else 5.0, offered, "ALL_MADE", 1, true),
            ReceiptLiftLine(0.0, if (rough) 8 else 9, null, null, offered, "ALL_MADE", 1, true),
            ReceiptLiftLine(185.0, null, null, null, false, "REP_MISS", 1, false),
        )
        val progress = ReceiptProgressState(lifts = lines.mapIndexed { index, line ->
            ReceiptLiftProgress(ReceiptSlotRef(index, 0, index.toLong() + 1, Slot.MAIN), names[index], line, whyLine = line)
        }, undoVisible = !offered && !rough, rough = rough, roughEnabled = !undone,
            lightWeekNext = !offered && !rough, lightRemaining = if (!offered && !rough) 1 else 0)
        render {
            SessionReceiptScreen(receiptFixture().copy(groups = rows.map { ReceiptGroup(null, listOf(it)) }), {}, {},
                progress = progress, mutation = ReceiptMutation.Undone.takeIf { undone })
        }
        if (why) {
            rule.onNodeWithText("Why").performScrollTo().performClick()
            rule.waitForIdle()
            rule.onNodeWithText("Bench Press 190 lb next time (+5): all sets made.").assertIsDisplayed()
        }
        capture(name, sheet = why)
    }

    private fun plan(name: String, automatic: Boolean) {
        render {
            PlanScreen(PlanUiState(name = "Strength", hasProgram = true, autoApply = automatic, lightWeek = 6,
                days = listOf(PlanDay("A", 0, "Day A", 3, 9, true, cardioActivity = CardioActivity.ROAD_RUN))),
                PlanActions({}, {}, {}))
        }
        rule.onNode(hasScrollAction()).performScrollToNode(hasText("Progression"))
        capture(name)
    }

    private fun options(name: String, prescribed: Boolean = false, kept: Boolean = false) {
        val slot = DayEditSlotState(1, 0, "bb_back_squat", "Barbell Back Squat", null, false,
            prescription = if (prescribed) Prescription(PrescribedTarget.LoadReps(185.0, 5)) else null,
            keepToPlan = kept, weightedTarget = true, nextTime = if (prescribed) NextTimeMode.AUTO else NextTimeMode.ASK,
            lifts = listOf(LiftOptionsState(Slot.MAIN, "Barbell Back Squat", 180, 180, false, 2, 0.025, "5 lb")))
        render { Surface(color = MaterialTheme.colorScheme.surface) {
            SlotOptionsScreen(slot, {}, {}, {}, {}, {}, {}, false, {}, {}, {}, {})
        } }
        capture(name)
    }

    private fun repsLeft(name: String, fontScale: Float) {
        val state = workoutSetLoggedFixture(rir = 3).copy(effortScale = EffortScale.REPS_LEFT)
        render(fontScale) { WorkoutSetHost(state, nowMillis = { rule.mainClock.currentTime }) }
        capture(name)
    }

    @Test @Config(qualifiers = "w360dp-h1200dp-xxhdpi")
    fun warmUpSheet() {
        val moves = upperWarmUp()
        render { routine(RoutineKind.WARM_UP, moves, checked = setOf(Move.ARM_CIRCLES)) }
        capture("p22-warm-up-sheet.png", sheet = true)
    }

    @Test @Config(qualifiers = "w360dp-h1200dp-xxhdpi")
    fun warmUpTimerLargeFont() {
        val moves = upperWarmUp()
        render(fontScale = 2f) { routine(RoutineKind.WARM_UP, moves, checked = setOf(Move.ARM_CIRCLES), startedAt = 0) }
        val timer = rule.onNodeWithTag("routine-timer-3:12", useUnmergedTree = true)
            .assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val progress = rule.onNode(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "4 minutes left"))
            .assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val stop = rule.onNodeWithText("Stop timer").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertTrue(progress.top >= timer.bottom)
        assertTrue(stop.top >= timer.bottom)
        assertTrue(progress.width > 0f && progress.right <= stop.left)
        capture("p23-warm-up-timer-2x.png", sheet = true)
    }

    @Test @Config(qualifiers = "w360dp-h1200dp-xxhdpi")
    fun coolDownSheet() {
        val work = listOf("bb_back_squat", "rdl", "lying_curl", "standing_calf", "plank")
            .map { CreditedExercise(ExerciseLibrary.get(it), 3) }
        val moves = Routines.coolDown(work, null, EquipmentPreset.FULL_GYM.equipment)
        render { routine(RoutineKind.COOL_DOWN, moves) }
        capture("p24-cool-down-sheet.png", sheet = true)
    }

    @Test @Config(qualifiers = "w360dp-h1200dp-xxhdpi")
    fun warmUpRunDay() {
        val block = CardioBlock(CardioActivity.ROAD_RUN, listOf(CardioSetSpec(CardioRole.WORK, Measure.Time(1800))))
        val moves = Routines.warmUp(emptyList(), block, EquipmentPreset.FULL_GYM.equipment)
        render { routine(RoutineKind.WARM_UP, moves, startsCardio = true) }
        capture("p25-warm-up-run-day.png", sheet = true)
    }

    @Test fun settingsWarmUp() {
        render {
            SetupScreen(SetupUiState(coolDown = false), SetupActions(
                onUnitToggle = {}, onRestTimerEnabledChange = {}, onRestOverrideChange = { _, _ -> },
                onRestOverridesReset = {}, onCreateCustomExercise = {}, onOpenBackup = {}, onOpenLicenses = {}, onBack = {}))
        }
        rule.onNode(hasScrollAction()).performScrollToNode(hasText("Suggested warm-up"))
        capture("p26-settings-warm-up.png")
    }

    private fun upperWarmUp(): List<Move> = Routines.warmUp(
        listOf("bb_bench", "bb_row", "ohp", "pullup").map { CreditedExercise(ExerciseLibrary.get(it), 3) },
        null, EquipmentPreset.FULL_GYM.equipment)

    @Composable
    private fun routine(kind: RoutineKind, moves: List<Move>, checked: Set<Move> = emptySet(),
                        startedAt: Long? = null, startsCardio: Boolean = false) {
        val seconds = when (kind) {
            RoutineKind.WARM_UP -> cloud.trotter.log.strength.domain.planner.SessionBudget.WARM_UP_SECONDS
            RoutineKind.COOL_DOWN -> Routines.seconds(moves)
        }
        RoutineSheet(kind, RoutineSheetState("preview", moves, checked, seconds, startedAt, null, startsCardio),
            0, { 108_000 }, {}, {}, {}, {}, {})
    }

    private fun render(fontScale: Float = 1f, content: @Composable () -> Unit) {
        RuntimeEnvironment.setFontScale(fontScale)
        rule.setContent {
            AppTheme(preference = ThemePreference.DARK, content = content)
        }
        rule.mainClock.advanceTimeBy(1_000)
        rule.waitForIdle()
    }

    @OptIn(ExperimentalRoborazziApi::class)
    private fun capture(name: String, sheet: Boolean = false) {
        rule.mainClock.advanceTimeBy(1_000)
        rule.waitForIdle()
        val root = if (sheet) {
            val dialogs = rule.onAllNodes(isDialog())
            if (dialogs.fetchSemanticsNodes().isNotEmpty()) dialogs.onLast() else rule.onAllNodes(isPopup()).onLast()
        } else rule.onAllNodes(isRoot()).onLast()
        root.assertExists()
        if (System.getProperty("roborazzi.test.record") == "true") {
            val file = File(System.getProperty("previewShots.dir", "app/build/outputs/preview"), name)
            file.parentFile.mkdirs()
            if (sheet) captureScreenRoboImage(file.absolutePath) else root.captureRoboImage(file)
        }
    }
}

/** Robolectric's full-screen frame for wrap-content dialogs keeps a dialog text field relaying out forever. */
private const val REQUESTED_WINDOW_SIZE = "robolectric.windowManager.useRequestedSize"
