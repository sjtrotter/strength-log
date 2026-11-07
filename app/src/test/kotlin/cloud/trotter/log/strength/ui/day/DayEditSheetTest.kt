package cloud.trotter.log.strength.ui.day

import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import cloud.trotter.log.strength.data.catalog.ExerciseCatalog
import cloud.trotter.log.strength.data.db.entity.Slot
import cloud.trotter.log.strength.domain.model.PrescribedTarget
import cloud.trotter.log.strength.domain.model.Prescription
import cloud.trotter.log.strength.domain.model.SetStructure
import cloud.trotter.log.strength.domain.units.WeightUnit
import cloud.trotter.log.strength.ui.theme.AppTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp")
class DayEditSheetTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var backDispatcher: OnBackPressedDispatcher

    private fun show(
        canResetToTemplate: Boolean = false,
        slotCount: Int = 1,
        onRemove: (Int) -> Unit = {},
        onOpenPrescription: (Long) -> Unit = {},
        prescription: Prescription? = null,
        partnerExerciseId: String? = null,
        edit: Boolean = true,
        hasSlot: Boolean = true,
        hasCardio: Boolean = false,
        onRemoveCardio: () -> Unit = {},
        keepToPlan: Boolean = false,
        runOpen: Boolean = false,
        onKeepToPlan: (Long, Boolean) -> Unit = { _, _ -> },
        onResetSlot: (Long) -> Unit = {},
        weightedTarget: Boolean = false,
        structure: SetStructure = SetStructure.STRAIGHT,
        onStructureChange: (Long, SetStructure) -> Unit = { _, _ -> },
        exerciseId: String = "plank",
        targetSets: Int = 3,
        unit: WeightUnit = WeightUnit.LB,
    ) {
        val slot = DayEditSlotState(
            programExerciseId = 1L,
            position = 0,
            exerciseId = exerciseId,
            title = ExerciseCatalog.CODE_ONLY.find(exerciseId)?.name ?: exerciseId,
            pattern = ExerciseCatalog.CODE_ONLY.find(exerciseId)?.pattern,
            isSuperset = partnerExerciseId != null,
            partnerExerciseId = partnerExerciseId,
            prescription = prescription,
            keepToPlan = keepToPlan,
            weightedTarget = weightedTarget,
            structure = structure,
            targetSets = targetSets,
        )
        val openedDraft = mutableStateOf<PrescriptionDraft?>(null)
        composeTestRule.setContent {
            AppTheme {
                DayEditSheet(
                    state = DayEditUiState(
                        slots = if (hasSlot) List(slotCount) { slot.copy(programExerciseId = it + 1L, position = it) } else emptyList(), canResetToTemplate = canResetToTemplate,
                        runOpen = runOpen,
                        unit = unit,
                        prescriptionDraft = openedDraft.value,
                        cardioBlock = if (hasCardio) cardioFixture().block else null,
                    ),
                    actions = DayEditActions(
                        onSwap = { _, _ -> },
                        onAdd = {},
                        onRemove = onRemove,
                        onOpenPrescription = { id ->
                            onOpenPrescription(id)
                            openedDraft.value = PrescriptionDraft.from(slot,
                                ExerciseCatalog.CODE_ONLY,
                                cloud.trotter.log.strength.domain.model.LifterConfig())
                        },
                        onSetSuperset = { _, _ -> },
                        onRemoveSuperset = {},
                        onResetToTemplate = {},
                        onRemoveCardio = onRemoveCardio,
                        onSetKeepToPlan = onKeepToPlan,
                        onResetSlotToPlan = onResetSlot,
                        onStructureChange = onStructureChange,
                    ),
                    accent = Color.White,
                    onDismiss = {},
                    onCreateExercise = {},
                )
            }
        }
        if (edit) {
            composeTestRule.onNodeWithContentDescription("More options for ${slot.title}").performClick()
            composeTestRule.onNodeWithText("Lift options").performClick()
        }
    }

    private fun showPlanEditor(
        onBack: () -> Unit = {},
        onSwap: (Int, String) -> Unit = { _, _ -> },
    ) {
        val entry = ExerciseCatalog.CODE_ONLY.get("bb_back_squat")
        composeTestRule.setContent {
            backDispatcher = requireNotNull(LocalOnBackPressedDispatcherOwner.current).onBackPressedDispatcher
            AppTheme {
                DayPlanEditor(
                    state = DayEditUiState(
                        planEditing = true,
                        slots = listOf(DayEditSlotState(
                            programExerciseId = 1L, position = 0, exerciseId = entry.id,
                            title = entry.name, pattern = entry.pattern, isSuperset = false,
                        )),
                    ),
                    dayId = "A",
                    loading = false,
                    accent = Color.White,
                    actions = DayEditActions(
                        onSwap = onSwap, onAdd = {}, onRemove = {}, onSetSuperset = { _, _ -> },
                        onRemoveSuperset = {}, onResetToTemplate = {},
                    ),
                    onBack = onBack,
                    onCreateExercise = {},
                )
            }
        }
    }

    @Test
    fun planEditorRootBackLeavesTheEditor() {
        var exits = 0
        showPlanEditor(onBack = { exits++ })
        composeTestRule.onNodeWithText("Edit day A").assertIsDisplayed()
        composeTestRule.runOnIdle { backDispatcher.onBackPressed() }
        composeTestRule.runOnIdle { assertEquals(1, exits) }
    }

    @Test
    fun planEditorBackFromAPageReturnsToTheSlotList() {
        var exits = 0
        showPlanEditor(onBack = { exits++ })
        composeTestRule.onNodeWithContentDescription("More options for Barbell Back Squat").performClick()
        composeTestRule.onNodeWithText("Lift options").performClick()
        composeTestRule.onNodeWithText("Prescription").assertIsDisplayed()
        composeTestRule.runOnIdle { backDispatcher.onBackPressed() }
        composeTestRule.onNodeWithText("Prescription").assertDoesNotExist()
        composeTestRule.onNodeWithText("Add exercise").assertIsDisplayed()
        composeTestRule.runOnIdle { assertEquals(0, exits) }
    }

    @Test
    fun planEditorSwapsApplyToThePlanWithoutAsking() {
        val swaps = mutableListOf<Pair<Int, String>>()
        val target = ExerciseCatalog.CODE_ONLY.substitutionsFor("bb_back_squat").first()
        showPlanEditor(onSwap = { position, id -> swaps += position to id })
        composeTestRule.onNodeWithContentDescription("More options for Barbell Back Squat").performClick()
        composeTestRule.onNodeWithText("Swap").performClick()
        composeTestRule.onNodeWithText("This workout").assertDoesNotExist()
        composeTestRule.onNodeWithText(target.name).performScrollTo().performClick()
        composeTestRule.runOnIdle { assertEquals(listOf(0 to target.id), swaps) }
        composeTestRule.onNodeWithText("This workout").assertDoesNotExist()
        composeTestRule.onNodeWithText("Add exercise").assertIsDisplayed()
    }

    @Test
    fun planEditorKeepsConfirmationForSwapsThatRemoveLegacyLoggedSets() {
        var confirmed = 0
        val state = workoutDayFixture().copy(viewDayId = "A", pendingPlanSwap = PendingPlanSwap(
            "A", 1L, 1L, Slot.MAIN, "hack_squat", "Barbell Back Squat", 2,
        ))
        composeTestRule.setContent {
            AppTheme {
                DayScreen(state, workoutActions().copy(onConfirmPlanSwap = { confirmed++ }),
                    DayEditUiState(planEditing = true), workoutEditActions())
            }
        }
        composeTestRule.onNodeWithText("Edit day A").assertExists()
        composeTestRule.onNodeWithText("Swap Barbell Back Squat? Its 2 logged sets today are removed.").assertIsDisplayed()
        composeTestRule.onNodeWithText("Swap").performClick()
        composeTestRule.runOnIdle { assertEquals(1, confirmed) }
    }

    @Test
    fun weightedTargetOffersLabeledRadioRowsAndWritesTheSelectedStructure() {
        var changed: Pair<Long, SetStructure>? = null
        show(weightedTarget = true, structure = SetStructure.RAMP_TOP,
            onStructureChange = { id, structure -> changed = id to structure })
        composeTestRule.onNodeWithText("Set structure").assertExists()
        composeTestRule.onNodeWithText("Straight sets").assertExists()
        composeTestRule.onNodeWithText("Every set at one weight.").assertExists()
        composeTestRule.onNodeWithText("Ramp to a top set").assertIsSelected()
        composeTestRule.onNodeWithText("Four warm-ups, then the top set.").assertExists()
        composeTestRule.onNodeWithText("Four warm-ups, the top set, one lighter set of 8.").assertExists()
        composeTestRule.onNodeWithText("Ramp, top set, back-off").performScrollTo().performClick()
        composeTestRule.runOnIdle { assertEquals(1L to SetStructure.RAMP_TOP_BACKOFF, changed) }
    }

    @Test
    fun holdTargetHidesStructureEvenWhenTheStoredStructureRamps() {
        show(prescription = Prescription(PrescribedTarget.Hold(45)), structure = SetStructure.RAMP_TOP)
        composeTestRule.onNodeWithText("Set structure").assertDoesNotExist()
        composeTestRule.onNodeWithText("Straight sets").assertDoesNotExist()
        composeTestRule.onNodeWithText("Ramp to a top set").assertDoesNotExist()
        composeTestRule.onNodeWithText("Ramp, top set, back-off").assertDoesNotExist()
    }

    @Test
    fun generatedDayOffersAddCardio() {
        show(edit = false)
        composeTestRule.onNodeWithText("Add cardio").assertExists()
    }

    @Test
    fun generatedDayOffersEditAndRemoveCardio() {
        var removals = 0
        show(edit = false, hasCardio = true, onRemoveCardio = { removals++ })
        composeTestRule.onNodeWithText("Edit cardio").assertExists()
        composeTestRule.onNodeWithText("Remove cardio").assertIsEnabled().performClick()
        composeTestRule.runOnIdle { assertEquals(1, removals) }
    }

    @Test
    fun cardioOnlyDayDisablesRemoveCardio() {
        show(edit = false, hasSlot = false, hasCardio = true)
        composeTestRule.onNodeWithText("Remove cardio").assertIsNotEnabled()
    }

    @Test
    fun dayWithAnExerciseCanRemoveCardio() {
        var removals = 0
        show(edit = false, hasCardio = true,
            onRemoveCardio = { removals++ })
        composeTestRule.onNodeWithText("Remove cardio").assertIsEnabled().performClick()
        composeTestRule.runOnIdle { assertEquals(1, removals) }
    }

    @Test
    fun prescribedSlotOffersPrescriptionMovesAndSupersets() {
        show(prescription = Prescription(PrescribedTarget.Reps(12)))
        composeTestRule.onNodeWithText("Prescription").assertExists()
        composeTestRule.onNodeWithText("Move up").assertExists()
        composeTestRule.onNodeWithText("Move down").assertExists()
        composeTestRule.onNodeWithText("Add superset").assertExists()
    }

    @Test
    fun authoredDayHidesResetToTemplate() {
        show(edit = false)
        composeTestRule.onNodeWithText("Reset day to template").assertDoesNotExist()
    }

    @Test
    fun generatedDayKeepsResetToTemplate() {
        show(canResetToTemplate = true, edit = false)
        composeTestRule.onNodeWithContentDescription("More").performClick()
        composeTestRule.onNodeWithText("Reset day to template").assertExists()
    }

    @Test
    fun prescribedPairOffersSwapAndRemoveSuperset() {
        show(prescription = Prescription(PrescribedTarget.Reps(12)), partnerExerciseId = "pushup")
        composeTestRule.onNodeWithText("Swap superset partner").assertExists()
        composeTestRule.onNodeWithText("Remove superset").assertExists()
    }

    @Test
    fun generatedSlotOffersAddSuperset() {
        show()
        composeTestRule.onNodeWithText("Prescription").assertExists()
        composeTestRule.onNodeWithText("Move up").assertExists()
        composeTestRule.onNodeWithText("Move down").assertExists()
        composeTestRule.onNodeWithText("Add superset").assertExists()
    }

    @Test
    fun generatedSlotOffersNextTimeAndForwardsItsChoice() {
        var choice: Pair<Long, Boolean>? = null
        show(onKeepToPlan = { slot, keep -> choice = slot to keep })
        composeTestRule.onNodeWithText("Next time").assertExists()
        val progress = composeTestRule.onNodeWithText("Progress").assertIsSelected().fetchSemanticsNode().boundsInRoot
        val disclosure = composeTestRule.onNodeWithText("Planned reps; increases need your approval.")
            .assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertTrue(disclosure.top >= progress.bottom)
        composeTestRule.onNodeWithText("Plan").performClick()
        composeTestRule.runOnIdle { assertEquals(1L to true, choice) }
    }

    @Test
    fun authoredSlotOffersNextTimeAndReset() {
        show()
        composeTestRule.onNodeWithText("Progress").assertExists()
        composeTestRule.onNodeWithText("Planned reps; increases need your approval.").assertExists()
        composeTestRule.onNodeWithText("Plan").assertExists()
        composeTestRule.onNodeWithText("Reset to plan").assertExists()
    }

    @Test
    fun resetToPlanAsksBeforeForwardingTheSlot() {
        var resetSlot: Long? = null
        show(onResetSlot = { resetSlot = it })
        composeTestRule.onNodeWithText("Reset to plan").performClick()
        composeTestRule.onNodeWithText("Reset to plan?").assertExists()
        composeTestRule.onNodeWithText("Next time starts from the plan instead of your progress.").assertExists()
        composeTestRule.runOnIdle { assertEquals(null, resetSlot) }
        composeTestRule.onNodeWithText("Cancel").performClick()
        composeTestRule.runOnIdle { assertEquals(null, resetSlot) }
        composeTestRule.onNodeWithText("Reset to plan").performClick()
        composeTestRule.onNodeWithText("Reset").performClick()
        composeTestRule.runOnIdle { assertEquals(1L, resetSlot) }
    }

    @Test
    fun keptSlotHidesResetToPlan() {
        show(keepToPlan = true)
        composeTestRule.onNodeWithText("Reset to plan").assertDoesNotExist()
    }

    @Test
    fun openDayHidesResetToPlan() {
        show(runOpen = true)
        composeTestRule.onNodeWithText("Reset to plan").assertDoesNotExist()
    }
    @Test
    fun tappingLiftOpensItsPrescription() {
        var opened: Long? = null
        show(edit = false, onOpenPrescription = { opened = it })
        composeTestRule.onNodeWithText("Plank / Side Plank").performClick()
        composeTestRule.runOnIdle { assertEquals(1L, opened) }
        composeTestRule.onNodeWithText("Tracking").assertExists()
        composeTestRule.onNodeWithText("Save").assertIsEnabled()
    }

    @Test
    fun liftMenuOffersSwapAndRemoveAndRemovesItsPosition() {
        var removed: Int? = null
        show(edit = false, hasCardio = true, onRemove = { removed = it })
        composeTestRule.onNodeWithContentDescription("More options for Plank / Side Plank").performClick()
        composeTestRule.onNodeWithText("Swap").assertIsEnabled()
        composeTestRule.onNodeWithText("Remove").assertIsEnabled().performClick()
        composeTestRule.runOnIdle { assertEquals(0, removed) }
    }

    @Test
    fun lastLiftCannotBeRemovedAndExplainsWhy() {
        show(edit = false)
        composeTestRule.onNodeWithContentDescription("More options for Plank / Side Plank").performClick()
        composeTestRule.onNodeWithText("Remove").assertIsNotEnabled()
        composeTestRule.onNodeWithText("A day keeps at least one lift").assertExists()
    }

    @Test
    fun fourLiftsAllowRemovalEvenWhenTheFixtureOmitsCanRemove() {
        var removed: Int? = null
        // Leave canRemove unset: DayEditRules must decide whether removal is allowed.
        show(edit = false, slotCount = 4, onRemove = { removed = it })
        composeTestRule.onAllNodesWithContentDescription("More options for Plank / Side Plank")[1].performClick()
        composeTestRule.onNodeWithText("Remove").assertIsEnabled().performClick()
        composeTestRule.runOnIdle { assertEquals(1, removed) }
    }

    @Test
    fun generatedRampSummarizesItsSeededTopAndBackoffInsteadOfInventingStraightSets() {
        show(edit = false, exerciseId = "bb_back_squat", structure = SetStructure.RAMP_TOP_BACKOFF, targetSets = 6)
        composeTestRule.onNodeWithText("Ramp to 235 lb, then 1 back-off").assertExists()
        composeTestRule.onNodeWithText("6 × 10 at 235 lb").assertDoesNotExist()
    }

    @Test
    fun generatedRampWithoutBackoffOnlyDescribesTheRamp() {
        show(edit = false, exerciseId = "bb_back_squat", structure = SetStructure.RAMP_TOP, targetSets = 5)
        composeTestRule.onNodeWithText("Ramp to 235 lb").assertExists()
        composeTestRule.onNodeWithText("Ramp to 235 lb, then 1 back-off").assertDoesNotExist()
    }

    @Test
    fun explicitRampUsesTheAuthoredTopAndSeededStructureRegardlessOfTargetCount() {
        show(edit = false, exerciseId = "bb_back_squat", structure = SetStructure.RAMP_TOP_BACKOFF, targetSets = 3,
            prescription = Prescription(PrescribedTarget.LoadReps(185.0, 5)))
        composeTestRule.onNodeWithText("Ramp to 185 lb, then 1 back-off").assertExists()
        composeTestRule.onNodeWithText("3 × 5 at 185 lb").assertDoesNotExist()
        composeTestRule.onNodeWithText("Ramp to 235 lb, then 1 back-off").assertDoesNotExist()
    }

    @Test
    fun explicitStraightSetsSummarizeTheAuthoredCountRepsAndLoad() {
        show(edit = false, exerciseId = "bb_back_squat", targetSets = 3,
            prescription = Prescription(PrescribedTarget.LoadReps(185.0, 5)))
        composeTestRule.onNodeWithText("3 × 5 at 185 lb").assertExists()
    }

    @Test
    fun generatedStraightSetsSummarizeTheSeededValues() {
        show(edit = false, exerciseId = "bb_back_squat", targetSets = 3)
        composeTestRule.onNodeWithText("3 × 10 at 235 lb").assertExists()
    }

    @Test
    fun holdsStayStraightEvenIfTheSlotHasARampStructure() {
        show(edit = false, structure = SetStructure.RAMP_TOP_BACKOFF,
            prescription = Prescription(PrescribedTarget.Hold(45)))
        composeTestRule.onNodeWithText("3 × 45 s").assertExists()
    }

    @Test
    fun loadedHoldsIncludeTheirAddedLoad() {
        show(edit = false, prescription = Prescription(PrescribedTarget.Hold(45, 25.0)))
        composeTestRule.onNodeWithText("3 × 45 s at 25 lb").assertExists()
    }

    @Test
    fun rampTopUsesTheSelectedWeightUnit() {
        show(edit = false, exerciseId = "bb_back_squat", structure = SetStructure.RAMP_TOP, unit = WeightUnit.KG,
            prescription = Prescription(PrescribedTarget.LoadReps(185.0, 5)))
        composeTestRule.onNodeWithText("Ramp to 83.91 kg").assertExists()
    }

    @Test
    fun unknownExerciseWithoutAPrescriptionShowsItsStructureWithoutInventingNumbers() {
        show(edit = false, exerciseId = "missing-exercise", structure = SetStructure.RAMP_TOP_BACKOFF)
        composeTestRule.onNodeWithText("Ramp, top set, back-off").assertExists()
        composeTestRule.onNodeWithText("3 × 10 at 0 lb").assertDoesNotExist()
    }

}
