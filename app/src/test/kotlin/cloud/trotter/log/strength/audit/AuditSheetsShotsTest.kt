package cloud.trotter.log.strength.audit

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import cloud.trotter.log.strength.data.OpenRun
import cloud.trotter.log.strength.data.catalog.ExerciseCatalog
import cloud.trotter.log.strength.data.db.entity.Slot
import cloud.trotter.log.strength.domain.model.LoggedSet
import cloud.trotter.log.strength.domain.model.SetKind
import cloud.trotter.log.strength.ui.day.CardioBlockDraft
import cloud.trotter.log.strength.ui.day.CardioDistanceDraft
import cloud.trotter.log.strength.ui.day.DayScreen
import cloud.trotter.log.strength.ui.day.OpenSheet
import cloud.trotter.log.strength.ui.day.PendingPlanSwap
import cloud.trotter.log.strength.ui.day.PrescriptionDraft
import cloud.trotter.log.strength.ui.day.RemovedSet
import cloud.trotter.log.strength.ui.day.RetiredTrackLine
import cloud.trotter.log.strength.ui.day.SavedCardioResult
import cloud.trotter.log.strength.ui.day.WorkoutConflict
import cloud.trotter.log.strength.ui.day.WorkoutConflictDialog
import cloud.trotter.log.strength.ui.day.WorkoutSwapUndo
import cloud.trotter.log.strength.ui.day.receipt.NoteSave
import cloud.trotter.log.strength.ui.day.receipt.receiptFixture
import cloud.trotter.log.strength.ui.day.workoutActions
import cloud.trotter.log.strength.ui.day.workoutDaySheetFixture
import cloud.trotter.log.strength.ui.day.workoutEditActions
import cloud.trotter.log.strength.ui.day.workoutLiftSheetFixture
import cloud.trotter.log.strength.ui.day.workoutSetCardioDistanceFixture
import cloud.trotter.log.strength.ui.day.workoutSetCardioFixture
import cloud.trotter.log.strength.ui.day.workoutSetCardioRecoveryFixture
import cloud.trotter.log.strength.ui.day.workoutSetEmptyFixture
import cloud.trotter.log.strength.ui.day.workoutSetSingleFixture
import cloud.trotter.log.strength.ui.day.workoutSetSupersetFixture
import cloud.trotter.log.strength.ui.day.workoutSetTimedRepRunningFixture
import cloud.trotter.log.strength.ui.navigation.TopLevelDestination
import cloud.trotter.log.strength.ui.today.TodayActions
import cloud.trotter.log.strength.ui.today.TodayScreen
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h720dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AuditSheetsShotsTest : UiAuditTest() {

    @Test fun shot01SheetEditor() {
        assumeTrue(System.getProperty("uiAudit.record") == "true")
        val state = workoutSetSingleFixture()
        rule.auditShot("sheets/01-sheet-editor") {
            AuditDay(state.copy(openSheet = OpenSheet(state.viewDayId.orEmpty(), OpenSheet.Kind.EDITOR, 1, 0)))
        }
    }

    @Test fun shot02SheetTimedRepCount() {
        assumeTrue(System.getProperty("uiAudit.record") == "true")
        val state = workoutSetTimedRepRunningFixture(awaiting = true)
        rule.auditShot("sheets/02-sheet-timed-rep-count") {
            AuditDay(state.copy(openSheet = OpenSheet(state.viewDayId.orEmpty(), OpenSheet.Kind.TIMED_REP_COUNT, 1, 0)))
        }
    }

    @Test fun shot03SheetCardioDistance() {
        assumeTrue(System.getProperty("uiAudit.record") == "true")
        val base = workoutSetCardioDistanceFixture()
        val cardio = requireNotNull(base.cardio)
        val run = requireNotNull(cardio.run).copy(wallStart = null, elapsedStart = null, bootCount = null,
            results = listOf(SavedCardioResult("SHORTENED", 27)), drafts = listOf(CardioDistanceDraft(0, stopped = true)))
        rule.auditShot("sheets/03-sheet-cardio-distance") {
            AuditDay(base.copy(cardio = cardio.copy(run = run, distanceRuns = listOf(run)),
                openSheet = OpenSheet("A", OpenSheet.Kind.CARDIO_DISTANCE, 0, 0, cardioRunId = run.runId)))
        }
    }

    @Test fun shot04SheetCardioSets() {
        assumeTrue(System.getProperty("uiAudit.record") == "true")
        val state = workoutSetCardioRecoveryFixture()
        rule.auditShot("sheets/04-sheet-cardio-sets") {
            AuditDay(state.copy(openSheet = OpenSheet(state.viewDayId.orEmpty(), OpenSheet.Kind.CARDIO_SETS, 1, 0)))
        }
    }

    @Test fun shot05SheetLift() {
        assumeTrue(System.getProperty("uiAudit.record") == "true")
        val state = workoutLiftSheetFixture()
        rule.auditShot("sheets/05-sheet-lift") {
            AuditDay(state.copy(openSheet = OpenSheet(state.viewDayId.orEmpty(), OpenSheet.Kind.LIFT, 1, 0)))
        }
    }

    @Test fun shot06SheetDay() {
        assumeTrue(System.getProperty("uiAudit.record") == "true")
        val state = workoutDaySheetFixture()
        rule.auditShot("sheets/06-sheet-day") {
            AuditDay(state.copy(openSheet = OpenSheet(state.viewDayId.orEmpty(), OpenSheet.Kind.DAY, 1, 0)))
        }
    }

    @Test fun shot07SheetSwap() {
        assumeTrue(System.getProperty("uiAudit.record") == "true")
        val state = workoutLiftSheetFixture()
        rule.auditShot("sheets/07-sheet-swap") {
            AuditDay(state.copy(openSheet = OpenSheet(state.viewDayId.orEmpty(), OpenSheet.Kind.SWAP, 1, 0)))
        }
    }

    @Test fun shot08PickerMatching() {
        assumeTrue(System.getProperty("uiAudit.record") == "true")
        val state = workoutLiftSheetFixture().copy(openSheet = OpenSheet("A", OpenSheet.Kind.SWAP, 1, 0))
        rule.auditShot("sheets/08-picker-matching", beforeCapture = {
            rule.onNode(hasSetTextAction()).performTextReplacement("squat")
            rule.mainClock.advanceTimeBy(300)
            rule.onNodeWithText("No matching exercises.").assertDoesNotExist()
        }) { AuditDay(state) }
    }

    @Test fun shot09PickerNoMatches() {
        assumeTrue(System.getProperty("uiAudit.record") == "true")
        val state = workoutLiftSheetFixture().copy(openSheet = OpenSheet("A", OpenSheet.Kind.SWAP, 1, 0))
        rule.auditShot("sheets/09-picker-no-matches", beforeCapture = {
            rule.onNode(hasSetTextAction()).performTextReplacement("zzzz-no-such-exercise")
            rule.mainClock.advanceTimeBy(300)
            rule.onNodeWithText("No matching exercises.").assertIsDisplayed()
        }) { AuditDay(state) }
    }

    @Test fun shot10SetEdit() {
        assumeTrue(System.getProperty("uiAudit.record") == "true")
        val state = workoutSetSupersetFixture()
        rule.auditShot("sheets/10-set-edit") {
            AuditDay(state.copy(openSheet = OpenSheet(state.viewDayId.orEmpty(), OpenSheet.Kind.EDITOR, 1, 1)))
        }
    }

    @Test fun shot11NoteEmpty() {
        assumeTrue(System.getProperty("uiAudit.record") == "true")
        val state = workoutLiftSheetFixture()
        val save = mutableStateOf<NoteSave?>(null)
        rule.auditShot("sheets/11-note-empty", beforeCapture = {
            rule.onNodeWithText("Add a note").performScrollTo().performClick()
            rule.mainClock.advanceTimeBy(500)
        }) {
            DayScreen(state, workoutActions().copy(onSetSessionNote = { save.value = NoteSave.Failed(it) }),
                auditEditFixture(state), workoutEditActions(), receipt = receiptFixture().copy(note = ""), noteSave = save.value)
        }
    }

    @Test fun shot12NoteText() {
        assumeTrue(System.getProperty("uiAudit.record") == "true")
        val state = workoutLiftSheetFixture()
        val save = mutableStateOf<NoteSave?>(null)
        rule.auditShot("sheets/12-note-text", beforeCapture = {
            rule.onNodeWithText("Edit note").performScrollTo().performClick()
            rule.mainClock.advanceTimeBy(500)
        }) {
            DayScreen(state, workoutActions().copy(onSetSessionNote = { save.value = NoteSave.Failed(it) }),
                auditEditFixture(state), workoutEditActions(), receipt = receiptFixture().copy(note = "The last round felt steady. Add five pounds next time."), noteSave = save.value)
        }
    }

    @Test fun shot13NoteSaveFailed() {
        assumeTrue(System.getProperty("uiAudit.record") == "true")
        val state = workoutLiftSheetFixture()
        val save = mutableStateOf<NoteSave?>(null)
        rule.auditShot("sheets/13-note-save-failed", beforeCapture = {
            rule.onNodeWithText("Edit note").performScrollTo().performClick()
            rule.mainClock.advanceTimeBy(500)
            rule.onNodeWithText("Done").performClick()
            rule.mainClock.advanceTimeBy(300)
        }) {
            DayScreen(state, workoutActions().copy(onSetSessionNote = { save.value = NoteSave.Failed(it) }),
                auditEditFixture(state), workoutEditActions(), receipt = receiptFixture().copy(note = "Keep the same load next time."), noteSave = save.value)
        }
    }

    @Test fun shot14DayEdit() {
        assumeTrue(System.getProperty("uiAudit.record") == "true")
        val state = workoutDaySheetFixture()
        rule.auditShot("sheets/14-day-edit") { AuditDay(state, edit = auditEditFixture(state).copy(editorOpen = true)) }
    }

    @Test fun shot15WorkoutConflict() {
        assumeTrue(System.getProperty("uiAudit.record") == "true")
        rule.auditShot("sheets/15-workout-conflict") {
            AuditDestination(TopLevelDestination.TODAY) { TodayScreen(auditTodayFixture(), TodayActions({}, {}, {})) }
            WorkoutConflictDialog(WorkoutConflict(OpenRun("audit-run", 2, "B", "Upper", "Prep", 1_000, emptyList()), false), {}, {}, {}, {})
        }
    }

    @Test fun shot16WorkoutConflictConfirm() {
        assumeTrue(System.getProperty("uiAudit.record") == "true")
        rule.auditShot("sheets/16-workout-conflict-confirm") {
            AuditDestination(TopLevelDestination.TODAY) { TodayScreen(auditTodayFixture(), TodayActions({}, {}, {})) }
            WorkoutConflictDialog(WorkoutConflict(OpenRun("audit-run", 2, "B", "Upper", "Prep", 1_000, emptyList()), true), {}, {}, {}, {})
        }
    }

    @Config(fontScale = 2f)
    @Test fun shot17SheetLiftFont2() {
        assumeTrue(System.getProperty("uiAudit.record") == "true")
        val state = workoutLiftSheetFixture()
        rule.auditShot("sheets/17-sheet-lift-font2", fontScale = 2f) {
            AuditDay(state.copy(openSheet = OpenSheet(state.viewDayId.orEmpty(), OpenSheet.Kind.LIFT, 1, 0)))
        }
    }

    @Test fun shot18DayEditEmpty() {
        assumeTrue(System.getProperty("uiAudit.record") == "true")
        val state = workoutSetEmptyFixture()
        rule.auditShot("sheets/18-day-edit-empty") { AuditDay(state, edit = auditEditFixture(state).copy(editorOpen = true)) }
    }

    @Test fun shot19DayEditOtherWorkout() {
        assumeTrue(System.getProperty("uiAudit.record") == "true")
        val state = workoutLiftSheetFixture()
        rule.auditShot("sheets/19-day-edit-other-workout") { AuditDay(state, edit = auditEditFixture(state).copy(otherWorkoutOpenMessage = true)) }
    }

    @Test fun shot20SetRemovedSnackbar() {
        assumeTrue(System.getProperty("uiAudit.record") == "true")
        val state = workoutSetSingleFixture()
        rule.auditShot("sheets/20-set-removed-snackbar") { DayScreen(state, workoutActions(), auditEditFixture(state), workoutEditActions(),
            removedSets = listOf(RemovedSet(1, 1, "A", "audit-run", LoggedSet(165.0, 5, SetKind.RAMP))), nowMillis = { 1_000L }) }
    }

    @Test fun shot21DayEditCardio() {
        assumeTrue(System.getProperty("uiAudit.record") == "true")
        val state = workoutSetCardioFixture()
        rule.auditShot("sheets/21-day-edit-cardio") { AuditDay(state, edit = auditEditFixture(state).copy(editorOpen = true,
            cardioDraft = CardioBlockDraft.new(1, "A"))) }
    }

    @Test fun shot22SheetLiftPartner() {
        assumeTrue(System.getProperty("uiAudit.record") == "true")
        val state = workoutSetSupersetFixture()
        rule.auditShot("sheets/22-sheet-lift-partner") { AuditDay(state.copy(openSheet = OpenSheet("A", OpenSheet.Kind.LIFT, 1, 0, slot = Slot.SS))) }
    }

    @Test fun shot23SheetCardioDistanceInvalid() {
        assumeTrue(System.getProperty("uiAudit.record") == "true")
        val base = workoutSetCardioDistanceFixture()
        val cardio = requireNotNull(base.cardio)
        val run = requireNotNull(cardio.run).copy(wallStart = null, results = listOf(SavedCardioResult("SHORTENED", 27)),
            drafts = listOf(CardioDistanceDraft(0, text = "0", stopped = true)))
        rule.auditShot("sheets/23-sheet-cardio-distance-invalid") { AuditDay(base.copy(cardio = cardio.copy(distanceRuns = listOf(run)),
            openSheet = OpenSheet("A", OpenSheet.Kind.CARDIO_DISTANCE, 0, 0, cardioRunId = run.runId))) }
    }

    @Test fun shot24SheetTimedRepCountDisabled() {
        assumeTrue(System.getProperty("uiAudit.record") == "true")
        val base = workoutSetTimedRepRunningFixture(awaiting = true)
        val timed = requireNotNull(base.timedRep)
        rule.auditShot("sheets/24-sheet-timed-rep-count-disabled") { AuditDay(base.copy(timedRep = timed.copy(event = timed.event.copy(draft = "")),
            openSheet = OpenSheet("A", OpenSheet.Kind.TIMED_REP_COUNT, 1, 0))) }
    }

    @Test fun shot25DayEditPrescription() {
        assumeTrue(System.getProperty("uiAudit.record") == "true")
        val state = workoutLiftSheetFixture()
        val edit = auditEditFixture(state)
        val slot = edit.slots.single()
        rule.auditShot("sheets/25-day-edit-prescription") { AuditDay(state, edit = edit.copy(editorOpen = true,
            prescriptionDraft = PrescriptionDraft.from(slot, edit.catalog, edit.config))) }
    }

    @Test fun shot26DayEditCardioSet() {
        assumeTrue(System.getProperty("uiAudit.record") == "true")
        val state = workoutSetCardioFixture()
        rule.auditShot("sheets/26-day-edit-cardio-set") { AuditDay(state, edit = auditEditFixture(state).copy(editorOpen = true,
            cardioDraft = CardioBlockDraft.new(1, "A").copy(editingSet = 0))) }
    }

    @Test fun shot27WorkoutPartialFinish() {
        assumeTrue(System.getProperty("uiAudit.record") == "true")
        rule.auditShot("sheets/27-workout-partial-finish", beforeCapture = {
            rule.onNodeWithTag("workout-finish").performClick()
            rule.mainClock.advanceTimeBy(300)
        }) { AuditDay(workoutSetSingleFixture()) }
    }

    @Test fun shot28WorkoutPlanSwapConfirm() {
        assumeTrue(System.getProperty("uiAudit.record") == "true")
        rule.auditShot("sheets/28-workout-plan-swap-confirm") { AuditDay(workoutLiftSheetFixture().copy(
            pendingPlanSwap = PendingPlanSwap("A", 1, 1, Slot.MAIN, "bb_front_squat", "Barbell Front Squat", 2))) }
    }

    @Test fun shot29WorkoutSwapUndo() {
        assumeTrue(System.getProperty("uiAudit.record") == "true")
        rule.auditShot("sheets/29-workout-swap-undo") { AuditDay(workoutLiftSheetFixture().copy(runOpen = true, runId = "audit-run",
            swapUndo = WorkoutSwapUndo("A", "audit-run", 1, Slot.MAIN, "bb_front_squat", "bb_back_squat", "Barbell Back Squat"))) }
    }

    @Test fun shot30DayRetiredTracks() {
        assumeTrue(System.getProperty("uiAudit.record") == "true")
        val state = workoutDaySheetFixture().copy(retiredTracks = listOf(RetiredTrackLine("Barbell Front Squat", 2)),
            retiredRoundDoneCount = 2, openSheet = OpenSheet("A", OpenSheet.Kind.DAY, 0, 0))
        rule.auditShot("sheets/30-day-retired-tracks") { AuditDay(state) }
    }


    @Test fun shot31SheetSwapChoice() {
        assumeTrue(System.getProperty("uiAudit.record") == "true")
        val state = workoutLiftSheetFixture().copy(openSheet = OpenSheet("A", OpenSheet.Kind.SWAP, 1, 0))
        val target = ExerciseCatalog.CODE_ONLY.substitutionsFor("bb_back_squat").first()
        rule.auditShot("sheets/31-sheet-swap-choice", beforeCapture = {
            rule.onNodeWithText(target.name).performClick()
            rule.mainClock.advanceTimeBy(300)
        }) { AuditDay(state) }
    }

    @Test fun shot32WorkoutClearChecksConfirm() {
        assumeTrue(System.getProperty("uiAudit.record") == "true")
        val state = mutableStateOf(workoutLiftSheetFixture().copy(
            openSheet = OpenSheet("A", OpenSheet.Kind.DAY, 0, 0)))
        rule.auditShot("sheets/32-workout-clear-checks-confirm", beforeCapture = {
            rule.onNodeWithText("Clear today's checks").performScrollTo().performClick()
            rule.mainClock.advanceTimeBy(300)
        }) {
            AuditDay(state.value, actions = workoutActions().copy(onCloseAllSets = {
                state.value = state.value.copy(openSheet = null)
            }))
        }
    }

    @Test fun shot33WorkoutDiscardConfirm() {
        assumeTrue(System.getProperty("uiAudit.record") == "true")
        val state = mutableStateOf(workoutLiftSheetFixture().copy(
            openSheet = OpenSheet("A", OpenSheet.Kind.DAY, 0, 0)))
        rule.auditShot("sheets/33-workout-discard-confirm", beforeCapture = {
            rule.onNodeWithText("Discard workout").performScrollTo().performClick()
            rule.mainClock.advanceTimeBy(300)
        }) {
            AuditDay(state.value, actions = workoutActions().copy(onCloseAllSets = {
                state.value = state.value.copy(openSheet = null)
            }))
        }
    }

    @Test fun shot34WorkoutEndCardioConfirm() {
        assumeTrue(System.getProperty("uiAudit.record") == "true")
        val state = mutableStateOf(workoutSetCardioRecoveryFixture().copy(
            openSheet = OpenSheet("A", OpenSheet.Kind.CARDIO_SETS, 0, 0)))
        rule.auditShot("sheets/34-workout-end-cardio-confirm", beforeCapture = {
            rule.onNodeWithText("End cardio").performScrollTo().performClick()
            rule.mainClock.advanceTimeBy(300)
        }) {
            AuditDay(state.value, actions = workoutActions().copy(onCloseAllSets = {
                state.value = state.value.copy(openSheet = null)
            }))
        }
    }

    @Config(fontScale = 2f)
    @Test fun shot35SheetEditorFont2() {
        assumeTrue(System.getProperty("uiAudit.record") == "true")
        val state = workoutSetSingleFixture()
        rule.auditShot("sheets/35-sheet-editor-font2", fontScale = 2f) {
            AuditDay(state.copy(openSheet = OpenSheet(state.viewDayId.orEmpty(), OpenSheet.Kind.EDITOR, 1, 0)))
        }
    }

    @Config(fontScale = 2f)
    @Test fun shot36DayEditFont2() {
        assumeTrue(System.getProperty("uiAudit.record") == "true")
        val state = workoutDaySheetFixture()
        rule.auditShot("sheets/36-day-edit-font2", fontScale = 2f) { AuditDay(state, edit = auditEditFixture(state).copy(editorOpen = true)) }
    }
}
