package cloud.trotter.log.strength.ui.day

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import cloud.trotter.log.strength.data.NextOffer
import cloud.trotter.log.strength.data.RuleEdit
import cloud.trotter.log.strength.data.SlotSession
import cloud.trotter.log.strength.data.catalog.ExerciseCatalog
import cloud.trotter.log.strength.data.db.entity.PlanEntity
import cloud.trotter.log.strength.data.db.entity.Slot
import cloud.trotter.log.strength.domain.library.tracking
import cloud.trotter.log.strength.domain.library.TrackingType
import cloud.trotter.log.strength.domain.model.LoggedSet
import cloud.trotter.log.strength.domain.model.SetKind
import cloud.trotter.log.strength.domain.model.SetStructure
import cloud.trotter.log.strength.domain.progression.EffortScale
import cloud.trotter.log.strength.domain.progression.LiftGoal
import cloud.trotter.log.strength.domain.progression.NextTimeMode
import cloud.trotter.log.strength.domain.progression.SlotRole
import cloud.trotter.log.strength.domain.progression.SlotRule
import cloud.trotter.log.strength.domain.standards.RestCategory
import cloud.trotter.log.strength.domain.standards.RestSettings
import cloud.trotter.log.strength.domain.units.WeightUnit
import cloud.trotter.log.strength.ui.theme.AppTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h900dp")
class ProgressionControlsTest {
    @get:Rule val rule = createComposeRule()
    private val plan = PlanEntity(1, "Strength", "GENERATED", 0, 0)

    @Test fun automaticAndOverrideChipsIgnoreOfferSwitchWhileAskFollowsIt() {
        val off = plan.copy(offerStrengthIncreases = false, offerCardioIncreases = false)
        assertTrue(NextTimeGate.strength(off.copy(autoApply = true), null))
        assertFalse(NextTimeGate.strength(off, null))
        assertTrue(NextTimeGate.strength(off, NextTimeMode.AUTO))
        assertTrue(NextTimeGate.cardio(off.copy(autoApply = true)))
        assertFalse(NextTimeGate.cardio(off))
    }

    @Test fun appliedAutomaticOfferShowsSelectedInTheLiftSheetWithTheSwitchOff() {
        val automatic = plan.copy(autoApply = true, offerStrengthIncreases = false)
        val before = List(3) { LoggedSet(185.0, 5, SetKind.WORK) }
        val after = before.map { it.copy(weightLb = 190.0) }
        val history = SlotSession(before, before.mapIndexed { index, set -> set.copy(done = true, plannedIndex = index) },
            false, NextOffer(185.0, 190.0))
        val next = DayScreenBuilder.nextTime(after, history, false, WeightUnit.LB,
            offersOn = NextTimeGate.strength(automatic, null))
        assertEquals(NextTime.Offer("+5 lb", true, 190.0, 185.0), next)
        val card = workoutLiftSheetFixture().exercises.first().copy(nextTime = next)
        rule.setContent { AppTheme { LiftSheet(card, Slot.MAIN, null, workoutActions(), false, null, {}, {}, {}) } }
        rule.onNodeWithText("+5 lb").assertIsSelected()
    }

    @Test fun liftOptionsMenuForwardsItsAction() {
        var opened = false
        val card = workoutLiftSheetFixture().exercises.first()
        rule.setContent { AppTheme {
            LiftSheet(card, Slot.MAIN, null, workoutActions(), false, null, {}, {}, {}, onOptions = { opened = true })
        } }
        rule.onNodeWithContentDescription("More").performClick()
        rule.onNodeWithText("Lift options").performClick()
        rule.runOnIdle { assertTrue(opened) }
    }

    @Test fun legacyUnprescribedPlanModeHasNoSelectionAndCanChooseAsk() {
        var picked: NextTimeMode? = null
        rule.setContent { AppTheme {
            NextTimeControl(NextTimeMode.AUTO, { picked = it }, keptToPlan = true)
        } }
        rule.onNodeWithText("Kept to plan").assertExists()
        rule.onNodeWithText("Plan").assertDoesNotExist()
        rule.onNodeWithText("Automatic").assertIsNotSelected()
        rule.onNodeWithText("Ask").assertIsNotSelected().performClick()
        rule.runOnIdle { assertEquals(NextTimeMode.ASK, picked) }
    }

    @Test fun convertedSquatOptionsUseTheTimerDefaultAndKilogramStep() {
        val entry = ExerciseCatalog.CODE_ONLY.get("bb_back_squat")
        val liftRule = SlotRule.defaults(SlotRole.MAIN, LiftGoal.STRENGTH, SetStructure.STRAIGHT, 5, entry.pattern)
        val options = requireNotNull(liftOptionsState(Slot.MAIN, entry.name, liftRule, entry.tracking,
            entry.equipment, plan, WeightUnit.KG, RestSettings(overrides = mapOf(RestCategory.TOP to 200))))
        assertEquals(200, options.restSeconds)
        assertEquals(200, options.defaultRestSeconds)
        assertEquals("2.5 kg", options.stepText)
        val slot = DayEditSlotState(1, 0, entry.id, entry.name, entry.pattern, false, lifts = listOf(options))
        rule.setContent { AppTheme { SlotOptionsScreen(slot, {}, {}, {}, {}, {}, {}, false, {}, {}, {}, {}) } }
        rule.onNodeWithText("Ask").assertIsSelected()
        rule.onNodeWithText("Default, 3:20").assertExists()
        rule.onNodeWithText("2.5%, at least 2.5 kg").assertExists()
        rule.onNodeWithText("2").assertIsSelected()
        rule.onNodeWithText("Plan").assertDoesNotExist()
        rule.onNodeWithText("Reset to plan").assertDoesNotExist()
    }

    @Test fun convertedStraightSquatShowsAskAndItsUnchangedDefaults() {
        val entry = ExerciseCatalog.CODE_ONLY.get("bb_back_squat")
        val liftRule = SlotRule.defaults(SlotRole.MAIN, LiftGoal.STRENGTH, SetStructure.STRAIGHT, 5, entry.pattern)
        val options = requireNotNull(liftOptionsState(Slot.MAIN, entry.name, liftRule, entry.tracking,
            entry.equipment, plan, WeightUnit.LB, RestSettings()))
        val slot = DayEditSlotState(1, 0, entry.id, entry.name, entry.pattern, false, lifts = listOf(options))
        rule.setContent { AppTheme { SlotOptionsScreen(slot, {}, {}, {}, {}, {}, {}, false, {}, {}, {}, {}) } }
        rule.onNodeWithText("Ask").assertIsSelected()
        rule.onNodeWithText("2").assertIsSelected()
        rule.onNodeWithText("Default, 3:00").assertExists()
        rule.onNodeWithText("2.5%, at least 5 lb").assertExists()
        rule.onNodeWithText("Plan").assertDoesNotExist()
        rule.onNodeWithText("Reset to plan").assertDoesNotExist()
    }

    @Test fun supersetOptionsShowRestForMainAndKeepBothRepsLeftAndIncreaseControls() {
        var edited: Pair<String, RuleEdit>? = null
        val main = LiftOptionsState(Slot.MAIN, "DB Bench Press", 180, 180, false, 2, 0.025, "5 lb")
        val partner = main.copy(slot = Slot.SS, title = "Cable Row", restSeconds = 90, defaultRestSeconds = 90)
        val slot = DayEditSlotState(1, 0, "db_bench", main.title, null, true,
            partnerExerciseId = "cable_row", partnerTitle = partner.title, lifts = listOf(main, partner))
        rule.setContent { AppTheme {
            SlotOptionsScreen(slot, {}, {}, {}, {}, {}, {}, false, {}, {}, {}, {},
                onRuleEdit = { half, edit -> edited = half to edit })
        } }
        rule.onAllNodesWithText("Rest").assertCountEquals(1)
        rule.onAllNodesWithText("Reps left").assertCountEquals(2)
        rule.onAllNodesWithText("Increase").assertCountEquals(2)
        rule.onNodeWithText("Default, 3:00").assertExists()
        rule.onNodeWithText("Default, 1:30").assertDoesNotExist()
        rule.onNodeWithText("Rest").performClick()
        rule.onNodeWithText("Minutes").performTextReplacement("2")
        rule.onNodeWithText("Seconds").performTextReplacement("30")
        rule.onNodeWithText("Save").performClick()
        rule.runOnIdle { assertEquals(Slot.MAIN to RuleEdit.Rest(150), edited) }
    }

    @Test fun repsLeftAtLargeFontUsesTwoRowsWith48DpTargetsAndOneSelection() {
        val selected = mutableStateOf<Int?>(null)
        rule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) { AppTheme {
                Box(Modifier.width(320.dp)) {
                    SetEffortRow(selected.value, EffortScale.REPS_LEFT, {}, { selected.value = it }, "workout-effort")
                }
            } }
        }
        for (value in 0..5) rule.onNodeWithTag("workout-effort-$value").assertWidthIsAtLeast(48.dp)
        val top = rule.onNodeWithTag("workout-effort-0").fetchSemanticsNode().boundsInRoot
        val bottom = rule.onNodeWithTag("workout-effort-3").fetchSemanticsNode().boundsInRoot
        assertTrue(bottom.top >= top.bottom)
        rule.onNodeWithTag("workout-effort-3").performClick().assertIsSelected()
        rule.runOnIdle { assertEquals(3, selected.value) }
        rule.onNodeWithTag("workout-effort-1").performClick().assertIsSelected()
        rule.onNodeWithTag("workout-effort-3").assertIsNotSelected()
    }

    @Test fun repsLeftBandForwardsTheNumberAndSetIdentity() {
        val base = workoutSetSingleFixture()
        val undo = UndoOffer(1, Slot.MAIN, 1, "Squat", LoggedSet(185.0, 5, SetKind.WORK, done = true),
            TrackingType.WEIGHTED, WeightUnit.LB, null)
        var picked: List<Any?>? = null
        rule.setContent { AppTheme {
            WorkoutSetHost(base.copy(effortScale = EffortScale.REPS_LEFT, workout = base.workout?.copy(undo = undo)),
                workoutActions().copy(onSetRepsLeft = { id, half, index, rir -> picked = listOf(id, half, index, rir) }))
        } }
        for (value in 0..5) rule.onNodeWithTag("workout-effort-$value").assertExists()
        rule.onNodeWithTag("workout-effort-3").performClick()
        rule.runOnIdle { assertEquals(listOf(1L, Slot.MAIN, 1, 3), picked) }
    }

    @Test fun liftSheetMapsThreeToGoodInWordsAndTwoToTwoLeft() {
        val rir = mutableStateOf(3)
        val scale = mutableStateOf(EffortScale.WORDS)
        val base = workoutLiftSheetFixture().exercises.first()
        rule.setContent { AppTheme {
            LiftSheet(base.copy(rows = listOf(base.rows.first().copy(done = true, rir = rir.value))), Slot.MAIN,
                null, workoutActions(), false, null, {}, {}, {}, effortScale = scale.value)
        } }
        rule.onNodeWithText("Good").assertExists()
        rule.runOnIdle { rir.value = 2; scale.value = EffortScale.REPS_LEFT }
        rule.onNodeWithText("2 left").assertExists()
    }
}
