package cloud.trotter.log.strength.ui.day

import android.view.View
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
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
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import cloud.trotter.log.strength.data.catalog.ExerciseCatalog
import cloud.trotter.log.strength.data.db.entity.Slot
import cloud.trotter.log.strength.domain.model.LoggedSet
import cloud.trotter.log.strength.domain.model.PrescribedTarget
import cloud.trotter.log.strength.domain.model.Prescription
import cloud.trotter.log.strength.domain.model.SetStructure
import cloud.trotter.log.strength.domain.model.SetKind
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
    private lateinit var view: View

    private val squatRamp = listOf(
        LoggedSet(130.0, 5, SetKind.RAMP), LoggedSet(165.0, 5, SetKind.RAMP),
        LoggedSet(190.0, 5, SetKind.RAMP), LoggedSet(210.0, 3, SetKind.RAMP),
        LoggedSet(235.0, 5, SetKind.TOP), LoggedSet(175.0, 8, SetKind.BACKOFF),
    )
    private val benchRamp = listOf(
        LoggedSet(100.0, 5, SetKind.RAMP), LoggedSet(130.0, 5, SetKind.RAMP),
        LoggedSet(150.0, 5, SetKind.RAMP), LoggedSet(165.0, 3, SetKind.RAMP),
        LoggedSet(185.0, 5, SetKind.TOP), LoggedSet(140.0, 8, SetKind.BACKOFF),
    )

    private fun show(
        canResetToTemplate: Boolean = false,
        slotCount: Int = 1,
        onRemove: (Int) -> Unit = {},
        onOpenPrescription: (Long) -> Unit = {},
        onOpenStraightSets: (Long) -> Unit = {},
        prescriptionDraft: PrescriptionDraft? = null,
        prescriptionStructure: SetStructure? = null,
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
        onResetToTemplate: () -> Unit = {},
        onDismiss: () -> Unit = {},
        rows: List<LoggedSet>? = null,
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
            rows = rows,
        )
        val openedDraft = mutableStateOf(prescriptionDraft)
        composeTestRule.setContent {
            AppTheme {
                DayEditSheet(
                    state = DayEditUiState(
                        slots = if (hasSlot) List(slotCount) { slot.copy(programExerciseId = it + 1L, position = it) } else emptyList(), canResetToTemplate = canResetToTemplate,
                        runOpen = runOpen,
                        unit = unit,
                        prescriptionDraft = openedDraft.value,
                        prescriptionStructure = prescriptionStructure,
                        cardioBlock = if (hasCardio) cardioFixture().block else null,
                    ),
                    actions = DayEditActions(
                        onSwap = { _, _ -> },
                        onAdd = {},
                        onRemove = onRemove,
                        onOpenPrescription = { id ->
                            onOpenPrescription(id)
                            openedDraft.value = PrescriptionDraft.from(slot, ExerciseCatalog.CODE_ONLY)
                        },
                        onSetSuperset = { _, _ -> },
                        onRemoveSuperset = {},
                        onResetToTemplate = onResetToTemplate,
                        onRemoveCardio = onRemoveCardio,
                        onSetKeepToPlan = onKeepToPlan,
                        onResetSlotToPlan = onResetSlot,
                        onStructureChange = onStructureChange,
                        onOpenStraightSets = onOpenStraightSets,
                    ),
                    accent = Color.White,
                    onDismiss = onDismiss,
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
        canResetToTemplate: Boolean = false,
        onResetToTemplate: () -> Unit = {},
        prescriptionDraft: PrescriptionDraft? = null,
    ) {
        val entry = ExerciseCatalog.CODE_ONLY.get("bb_back_squat")
        composeTestRule.setContent {
            backDispatcher = requireNotNull(LocalOnBackPressedDispatcherOwner.current).onBackPressedDispatcher
            view = LocalView.current
            AppTheme {
                DayPlanEditor(
                    state = DayEditUiState(
                        planEditing = true,
                        canResetToTemplate = canResetToTemplate,
                        prescriptionDraft = prescriptionDraft,
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
                        onRemoveSuperset = {}, onResetToTemplate = onResetToTemplate,
                    ),
                    onBack = onBack,
                    onCreateExercise = {},
                )
            }
        }
    }

    @Test
    fun planEditorResetKeepsTheEditorOpen() {
        var resets = 0
        var exits = 0
        showPlanEditor(canResetToTemplate = true, onResetToTemplate = { resets++ }, onBack = { exits++ })
        composeTestRule.onNodeWithContentDescription("More").performClick()
        composeTestRule.onNodeWithText("Reset day to template").performClick()
        composeTestRule.onNodeWithText("Reset day to template?").assertIsDisplayed()
        composeTestRule.runOnIdle { assertEquals(0, resets) }
        composeTestRule.onNodeWithText("Reset").performClick()
        composeTestRule.runOnIdle {
            assertEquals(1, resets)
            assertEquals(0, exits)
        }
        composeTestRule.onNodeWithText("Reset day to template?").assertDoesNotExist()
        composeTestRule.onNodeWithText("Add exercise").assertIsDisplayed()
    }

    @Test
    fun workoutSheetResetStillDismissesTheSheet() {
        val events = mutableListOf<String>()
        show(canResetToTemplate = true, edit = false,
            onResetToTemplate = { events += "reset" }, onDismiss = { events += "dismiss" })
        composeTestRule.onNodeWithContentDescription("More").performClick()
        composeTestRule.onNodeWithText("Reset day to template").performClick()
        composeTestRule.onNodeWithText("Reset").performClick()
        composeTestRule.runOnIdle { assertEquals(listOf("reset", "dismiss"), events) }
    }

    @Test
    fun planEditorPrescriptionKeepsItsBodyAndFooterAboveTheKeyboard() {
        showPlanEditor(prescriptionDraft = PrescriptionDraft(slotId = 1L))
        val resistance = composeTestRule.onNodeWithText("Band or resistance")
        resistance.performScrollTo().performClick()
        val imeHeight = (300 * view.resources.displayMetrics.density).toInt()
        val navigationBarHeight = (24 * view.resources.displayMetrics.density).toInt()
        val keyboardTop = view.height - imeHeight
        composeTestRule.runOnIdle {
            ViewCompat.dispatchApplyWindowInsets(view, WindowInsetsCompat.Builder()
                .setInsets(WindowInsetsCompat.Type.navigationBars(), Insets.of(0, 0, 0, navigationBarHeight))
                .setInsets(WindowInsetsCompat.Type.ime(), Insets.of(0, 0, 0, imeHeight))
                .setVisible(WindowInsetsCompat.Type.ime(), true)
                .build())
        }
        resistance.performScrollTo().assertIsDisplayed()
        val save = composeTestRule.onNodeWithText("Save").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val cancel = composeTestRule.onNodeWithText("Cancel").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertTrue("Save must stay above the keyboard", save.bottom <= keyboardTop)
        assertTrue("Cancel must stay above the keyboard", cancel.bottom <= keyboardTop)
        assertTrue("Resistance must scroll above the keyboard", resistance.fetchSemanticsNode().boundsInRoot.bottom <= keyboardTop)
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
    fun `choosing straight sets on a ramp opens the straight sets sheet`() {
        val opened = mutableListOf<Long>()
        val changed = mutableListOf<Pair<Long, SetStructure>>()
        show(weightedTarget = true, structure = SetStructure.RAMP_TOP_BACKOFF,
            exerciseId = "bb_back_squat", targetSets = 6,
            onOpenStraightSets = { opened += it },
            onStructureChange = { id, structure -> changed += id to structure })

        composeTestRule.onNodeWithText("Straight sets").performScrollTo().performClick()

        composeTestRule.runOnIdle {
            assertEquals(listOf(1L), opened)
            assertTrue(changed.isEmpty())
        }
    }

    @Test
    fun `the straight sets sheet shows sets reps and weight`() {
        show(edit = false, weightedTarget = true, structure = SetStructure.RAMP_TOP_BACKOFF,
            exerciseId = "bb_back_squat", targetSets = 6,
            prescriptionDraft = PrescriptionDraft(slotId = 1L, sets = 3, reps = 5, loadLb = 215.0),
            prescriptionStructure = SetStructure.STRAIGHT)

        composeTestRule.onNodeWithText("Straight sets").assertIsDisplayed()
        composeTestRule.onNodeWithText("Sets").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithText("Reps").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithText("Weight (lb)").performScrollTo().assertIsDisplayed()
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
        show(prescription = Prescription(PrescribedTarget.Reps(12)), onKeepToPlan = { slot, keep -> choice = slot to keep })
        composeTestRule.onNodeWithText("Next time").assertExists()
        val progress = composeTestRule.onNodeWithText("Ask").assertIsSelected().fetchSemanticsNode().boundsInRoot
        val disclosure = composeTestRule.onNodeWithText("Finish offers changes")
            .assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertTrue(disclosure.top >= progress.bottom)
        composeTestRule.onNodeWithText("Plan").performClick()
        composeTestRule.runOnIdle { assertEquals(1L to true, choice) }
    }

    @Test
    fun unprescribedSlotOffersAutomaticAndAskWithoutPlanOrReset() {
        show()
        composeTestRule.onNodeWithText("Ask").assertIsSelected()
        composeTestRule.onNodeWithText("Automatic").assertExists()
        composeTestRule.onNodeWithText("Finish offers changes").assertExists()
        composeTestRule.onNodeWithText("Plan").assertDoesNotExist()
        composeTestRule.onNodeWithText("Reset to plan").assertDoesNotExist()
    }

    @Test
    fun resetToPlanAsksBeforeForwardingTheSlot() {
        var resetSlot: Long? = null
        show(prescription = Prescription(PrescribedTarget.Reps(12)), onResetSlot = { resetSlot = it })
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
        show(prescription = Prescription(PrescribedTarget.Reps(12)), keepToPlan = true)
        composeTestRule.onNodeWithText("Reset to plan").assertDoesNotExist()
    }

    @Test
    fun openDayHidesResetToPlan() {
        var choice: Pair<Long, Boolean>? = null
        show(prescription = Prescription(PrescribedTarget.Reps(12)), runOpen = true,
            onKeepToPlan = { slot, keep -> choice = slot to keep })
        composeTestRule.onNodeWithText("Reset to plan").assertDoesNotExist()
        composeTestRule.onNodeWithText("Plan").assertIsDisplayed().performClick()
        composeTestRule.runOnIdle { assertEquals(1L to true, choice) }
    }
    @Test
    fun tappingLiftOpensItsPrescription() {
        var opened: Long? = null
        show(edit = false, onOpenPrescription = { opened = it })
        composeTestRule.onNodeWithText("Plank").performClick()
        composeTestRule.runOnIdle { assertEquals(1L, opened) }
        composeTestRule.onNodeWithText("Tracking").assertExists()
        composeTestRule.onNodeWithText("Save").assertIsEnabled()
    }

    @Test
    fun liftMenuOffersSwapAndRemoveAndRemovesItsPosition() {
        var removed: Int? = null
        show(edit = false, hasCardio = true, onRemove = { removed = it })
        composeTestRule.onNodeWithContentDescription("More options for Plank").performClick()
        composeTestRule.onNodeWithText("Swap").assertIsEnabled()
        composeTestRule.onNodeWithText("Remove").assertIsEnabled().performClick()
        composeTestRule.runOnIdle { assertEquals(0, removed) }
    }

    @Test
    fun lastLiftCannotBeRemovedAndExplainsWhy() {
        show(edit = false)
        composeTestRule.onNodeWithContentDescription("More options for Plank").performClick()
        composeTestRule.onNodeWithText("Remove").assertIsNotEnabled()
        composeTestRule.onNodeWithText("A day keeps at least one lift").assertExists()
    }

    @Test
    fun fourLiftsAllowRemovalEvenWhenTheFixtureOmitsCanRemove() {
        var removed: Int? = null
        // Leave canRemove unset: DayEditRules must decide whether removal is allowed.
        show(edit = false, slotCount = 4, onRemove = { removed = it })
        composeTestRule.onAllNodesWithContentDescription("More options for Plank")[1].performClick()
        composeTestRule.onNodeWithText("Remove").assertIsEnabled().performClick()
        composeTestRule.runOnIdle { assertEquals(1, removed) }
    }

    @Test
    fun generatedRampSummarizesItsSeededTopAndBackoffInsteadOfInventingStraightSets() {
        show(edit = false, exerciseId = "bb_back_squat", structure = SetStructure.RAMP_TOP_BACKOFF, targetSets = 6,
            rows = squatRamp)
        composeTestRule.onNodeWithText("Ramp to 235 lb, then 1 back-off").assertExists()
        composeTestRule.onNodeWithText("6 × 10 at 235 lb").assertDoesNotExist()
    }

    @Test
    fun generatedRampWithoutBackoffOnlyDescribesTheRamp() {
        show(edit = false, exerciseId = "bb_back_squat", structure = SetStructure.RAMP_TOP, targetSets = 5,
            rows = squatRamp.dropLast(1))
        composeTestRule.onNodeWithText("Ramp to 235 lb").assertExists()
        composeTestRule.onNodeWithText("Ramp to 235 lb, then 1 back-off").assertDoesNotExist()
    }

    @Test
    fun explicitRampUsesTheAuthoredTopAndSeededStructureRegardlessOfTargetCount() {
        show(edit = false, exerciseId = "bb_back_squat", structure = SetStructure.RAMP_TOP_BACKOFF, targetSets = 3,
            prescription = Prescription(PrescribedTarget.LoadReps(185.0, 5)), rows = benchRamp)
        composeTestRule.onNodeWithText("Ramp to 185 lb, then 1 back-off").assertExists()
        composeTestRule.onNodeWithText("3 × 5 at 185 lb").assertDoesNotExist()
        composeTestRule.onNodeWithText("Ramp to 235 lb, then 1 back-off").assertDoesNotExist()
    }

    @Test
    fun explicitStraightSetsSummarizeTheAuthoredCountRepsAndLoad() {
        show(edit = false, exerciseId = "bb_back_squat", targetSets = 3,
            prescription = Prescription(PrescribedTarget.LoadReps(185.0, 5)),
            rows = List(3) { LoggedSet(185.0, 5, SetKind.WORK) })
        composeTestRule.onNodeWithText("3 × 5 at 185 lb").assertExists()
    }

    @Test
    fun generatedStraightSetsSummarizeTheSeededValues() {
        show(edit = false, exerciseId = "bb_back_squat", targetSets = 3,
            rows = List(3) { LoggedSet(235.0, 5, SetKind.WORK) })
        composeTestRule.onNodeWithText("3 × 5 at 235 lb").assertExists()
    }

    @Test
    fun holdsStayStraightEvenIfTheSlotHasARampStructure() {
        show(edit = false, structure = SetStructure.RAMP_TOP_BACKOFF,
            prescription = Prescription(PrescribedTarget.Hold(45)),
            rows = List(3) { LoggedSet(0.0, 0, SetKind.WORK, seconds = 45) })
        composeTestRule.onNodeWithText("3 × 45 s").assertExists()
    }

    @Test
    fun loadedHoldsIncludeTheirAddedLoad() {
        show(edit = false, prescription = Prescription(PrescribedTarget.Hold(45, 25.0)),
            rows = List(3) { LoggedSet(25.0, 0, SetKind.WORK, seconds = 45) })
        composeTestRule.onNodeWithText("3 × 45 s at 25 lb").assertExists()
    }

    @Test
    fun rampTopUsesTheSelectedWeightUnit() {
        show(edit = false, exerciseId = "bb_back_squat", structure = SetStructure.RAMP_TOP, unit = WeightUnit.KG,
            prescription = Prescription(PrescribedTarget.LoadReps(185.0, 5)), rows = benchRamp.dropLast(1))
        composeTestRule.onNodeWithText("Ramp to 83.91 kg").assertExists()
    }

    @Test
    fun unknownExerciseWithoutAPrescriptionShowsItsStructureWithoutInventingNumbers() {
        show(edit = false, exerciseId = "missing-exercise", structure = SetStructure.RAMP_TOP_BACKOFF)
        composeTestRule.onNodeWithText("Ramp, top set, back-off").assertExists()
        composeTestRule.onNodeWithText("3 × 10 at 0 lb").assertDoesNotExist()
    }

    @Test
    fun `a prescribed slot counts its stored rows`() {
        show(edit = false, exerciseId = "hammer_curl", prescription = Prescription(PrescribedTarget.LoadReps(30.0, 8)),
            rows = List(4) { LoggedSet(30.0, 8, SetKind.WORK) })
        composeTestRule.onNodeWithText("4 × 8 at 30 lb").assertExists()
        composeTestRule.onNodeWithText("3 × 8 at 30 lb").assertDoesNotExist()
    }

    @Test
    fun `an unprescribed slot summarizes its own rows`() {
        show(edit = false, exerciseId = "bb_bench", rows = List(4) { LoggedSet(185.0, 5, SetKind.WORK) })
        composeTestRule.onNodeWithText("4 × 5 at 185 lb").assertExists()
    }

    @Test
    fun `an empty stored track has no sets`() {
        show(edit = false, exerciseId = "bb_bench", rows = emptyList())
        composeTestRule.onNodeWithText("No sets yet").assertExists()
    }

}
