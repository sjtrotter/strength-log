package cloud.trotter.log.strength.store

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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.isPopup
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.Density
import cloud.trotter.log.strength.data.db.entity.Slot
import cloud.trotter.log.strength.domain.library.TrackingType
import cloud.trotter.log.strength.domain.model.CardioActivity
import cloud.trotter.log.strength.domain.model.CardioBlock
import cloud.trotter.log.strength.domain.model.CardioRole
import cloud.trotter.log.strength.domain.model.CardioSetSpec
import cloud.trotter.log.strength.domain.model.LoggedSet
import cloud.trotter.log.strength.domain.model.Measure
import cloud.trotter.log.strength.domain.model.PrescribedTarget
import cloud.trotter.log.strength.domain.model.Prescription
import cloud.trotter.log.strength.domain.model.SetKind
import cloud.trotter.log.strength.domain.progression.CardioEffort
import cloud.trotter.log.strength.domain.progression.NextTimeMode
import cloud.trotter.log.strength.domain.progression.EffortScale
import cloud.trotter.log.strength.domain.theme.ThemePreference
import cloud.trotter.log.strength.domain.units.WeightUnit
import cloud.trotter.log.strength.ui.day.CardioIncrease
import cloud.trotter.log.strength.ui.day.CardioNextTime
import cloud.trotter.log.strength.ui.day.DayEditSlotState
import cloud.trotter.log.strength.ui.day.LiftOptionsState
import cloud.trotter.log.strength.ui.day.SessionReceiptScreen
import cloud.trotter.log.strength.ui.day.SlotOptionsScreen
import cloud.trotter.log.strength.ui.day.UndoOffer
import cloud.trotter.log.strength.ui.day.WorkoutSetHost
import cloud.trotter.log.strength.ui.day.receipt.PlannedSets
import cloud.trotter.log.strength.ui.day.receipt.ReceiptGroup
import cloud.trotter.log.strength.ui.day.receipt.ReceiptLiftLine
import cloud.trotter.log.strength.ui.day.receipt.ReceiptLiftProgress
import cloud.trotter.log.strength.ui.day.receipt.ReceiptMutation
import cloud.trotter.log.strength.ui.day.receipt.ReceiptProgressState
import cloud.trotter.log.strength.ui.day.receipt.ReceiptRow
import cloud.trotter.log.strength.ui.day.receipt.ReceiptSet
import cloud.trotter.log.strength.ui.day.receipt.ReceiptSlotRef
import cloud.trotter.log.strength.ui.day.receipt.ValueLine
import cloud.trotter.log.strength.ui.day.receipt.receiptFixture
import cloud.trotter.log.strength.ui.day.receipt.timeTargetFixture
import cloud.trotter.log.strength.ui.day.workoutRest
import cloud.trotter.log.strength.ui.day.workoutSetSingleFixture
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
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h720dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ProgressionPreviewShotsTest {
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
        val base = workoutSetSingleFixture()
        val state = base.copy(effortScale = EffortScale.REPS_LEFT,
            rest = workoutRest().copy(remainingSeconds = 64, remainingFraction = 64f / 90f),
            workout = base.workout?.copy(undo = UndoOffer(1, Slot.MAIN, 1, "Barbell Back Squat",
                LoggedSet(185.0, 5, SetKind.WORK, done = true, rir = 3), TrackingType.WEIGHTED, WeightUnit.LB, null)))
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
        rule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                AppTheme(preference = ThemePreference.DARK, content = content)
            }
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
