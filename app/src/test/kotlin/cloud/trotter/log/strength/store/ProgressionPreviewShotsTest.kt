package cloud.trotter.log.strength.store

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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
        capture("p12-settings-effort-scale.png")
    }

    @Test fun repsLeft() = repsLeft("p13-reps-left-1x.png", 1f)
    @Test fun repsLeftLargeFont() = repsLeft("p14-reps-left-2x.png", 2f)

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
        if (why) rule.onNodeWithText("Why").performClick()
        capture(name)
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
        render { SlotOptionsScreen(slot, {}, {}, {}, {}, {}, {}, false, {}, {}, {}, {}) }
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

    private fun capture(name: String) {
        val root = rule.onAllNodes(isRoot()).onLast()
        root.assertExists()
        if (System.getProperty("roborazzi.test.record") == "true") {
            val file = File(System.getProperty("previewShots.dir", "app/build/outputs/preview"), name)
            file.parentFile.mkdirs()
            root.captureRoboImage(file)
        }
    }
}
