package cloud.trotter.log.strength.ui.preview

import androidx.lifecycle.SavedStateHandle
import cloud.trotter.log.strength.data.RunSeed
import cloud.trotter.log.strength.data.RunStartResult
import cloud.trotter.log.strength.data.db.entity.SessionSetEntity
import cloud.trotter.log.strength.data.db.entity.Slot
import cloud.trotter.log.strength.data.db.entity.WorkoutSessionEntity
import cloud.trotter.log.strength.domain.model.CardioActivity
import cloud.trotter.log.strength.domain.model.CardioBlock
import cloud.trotter.log.strength.domain.model.CardioRole
import cloud.trotter.log.strength.domain.model.CardioSetSpec
import cloud.trotter.log.strength.domain.model.Program
import cloud.trotter.log.strength.domain.model.ProgramDay
import cloud.trotter.log.strength.domain.model.ProgramExercise
import cloud.trotter.log.strength.domain.model.Measure
import cloud.trotter.log.strength.domain.model.LoggedSet
import cloud.trotter.log.strength.domain.model.SetKind
import cloud.trotter.log.strength.ui.Routes
import cloud.trotter.log.strength.ui.day.DaySelection
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PreviewViewModelTest : OverviewFixture() {
    @Test fun unopenedPreviewUsesTheWorkoutSeedWithoutWritingWorkingValues() = runTest(dispatcher) {
        insertProgram()
        val plan = repo.selectedPlanIdFlow.first()!!
        val vm = preview(plan, "A")
        advanceUntilIdle()
        assertEquals("235 × 5", vm.uiState.value.lifts.single().working)
        assertEquals("Barbell Back Squat", vm.uiState.value.lifts.single().name)
        assertNull(vm.uiState.value.lifts.single().last)
        assertTrue(repo.dayTracksFlow(plan, "A").first().tracks.isEmpty())
        assertNull(repo.openRunFlow.first())
        val workout = day()
        advanceUntilIdle()
        assertEquals(vm.uiState.value.lifts.single().working,
            workout.uiState.value.exercises.single().rows.first { it.kind == SetKind.TOP }.let {
                "${it.weightDisplay.toInt()} × ${it.reps}"
            })
    }

    @Test fun repsOnlyWorkingValueHasNoLeadingSpace() = runTest(dispatcher) {
        repo.replaceProgram(Program(listOf(ProgramDay("A", "Push", "", listOf(ProgramExercise("pushup"))))))
        val plan = repo.selectedPlanIdFlow.first()!!
        val slot = repo.slotsFor(plan, "A").first().single().programExerciseId
        repo.seedIfEmpty("A", slot, Slot.MAIN, listOf(LoggedSet(0.0, 8, SetKind.WORK)))
        val vm = preview(plan, "A")
        advanceUntilIdle()
        assertEquals("× 8", vm.uiState.value.lifts.single().working)
    }

    @Test fun previewUsesStoredFirstWorkingValuesRatherThanTheGoalOrRamp() = runTest(dispatcher) {
        insertProgram()
        val plan = repo.selectedPlanIdFlow.first()!!
        val slot = repo.slotsFor(plan, "A").first().single().programExerciseId
        repo.seedIfEmpty("A", slot, Slot.MAIN, listOf(
            LoggedSet(100.0, 5, SetKind.RAMP), LoggedSet(225.0, 7, SetKind.TOP), LoggedSet(175.0, 8, SetKind.BACKOFF),
        ))
        val vm = preview(plan, "A")
        advanceUntilIdle()
        assertEquals("225 × 7", vm.uiState.value.lifts.single().working)
        vm.start()
        advanceUntilIdle()
        val track = repo.openRunFlow.first()!!.tracks.single()
        assertEquals(listOf(100.0, 225.0, 175.0), track.sets.map { it.weightLb })
        assertEquals(DaySelection(plan, "A"), vm.workoutStart.navigation.first())
    }

    @Test fun performedNameLoadAndLastFollowTheRunSwapAndHistoryEdits() = runTest(dispatcher) {
        insertProgram()
        val plan = repo.selectedPlanIdFlow.first()!!
        val slot = repo.slotsFor(plan, "A").first().single().programExerciseId
        val started = repo.startRun(plan, "A", emptyList()) as RunStartResult.Started
        assertTrue(repo.swapInRun(started.runId, slot, Slot.MAIN, "hack_squat",
            RunSeed(slot, Slot.MAIN, listOf(LoggedSet(180.0, 8, SetKind.WORK)))))
        val vm = preview(plan, "A")
        advanceUntilIdle()
        assertEquals("Hack Squat", vm.uiState.value.lifts.single().name)
        assertEquals("180 × 8", vm.uiState.value.lifts.single().working)
        assertNull(vm.uiState.value.lifts.single().last)
        val session = db.sessionDao().insertSession(WorkoutSessionEntity(0, "A", "Squat", null, 1000, 235, planId = plan))
        db.sessionDao().insertSets(listOf(SessionSetEntity(0, session, "hack_squat", "Hack Squat", Slot.MAIN, 0,
            SetKind.WORK.name, 170.0, 9, true)))
        advanceUntilIdle()
        assertEquals("170 × 9", vm.uiState.value.lifts.single().last)
        assertEquals("bb_back_squat", repo.slotsFor(plan, "A").first().single().exercise.exerciseId)
    }

    @Test fun supersetJoinsBothPerformedNamesAndValues() = runTest(dispatcher) {
        insertProgram()
        val plan = repo.selectedPlanIdFlow.first()!!
        val vm = preview(plan, "B")
        advanceUntilIdle()
        val catalog = repo.catalogFlow.first()
        assertEquals("${catalog.find("ez_curl")!!.name} + ${catalog.find("rope_pushdown")!!.name}", vm.uiState.value.lifts.single().name)
        assertTrue(vm.uiState.value.lifts.single().working!!.contains(" + "))
        vm.start()
        advanceUntilIdle()
        val slot = repo.slotsFor(plan, "B").first().single().programExerciseId
        repo.swapInRun(repo.openRunFlow.first()!!.id, slot, Slot.SS, "bb_bench",
            RunSeed(slot, Slot.SS, List(2) { LoggedSet(90.0, 8, SetKind.WORK) }))
        advanceUntilIdle()
        assertEquals("${catalog.find("ez_curl")!!.name} + ${catalog.find("bb_bench")!!.name}", vm.uiState.value.lifts.single().name)
    }

    @Test fun previewStartSeedsBothTracksAndEmitsItsOwnPlanAndDay() = runTest(dispatcher) {
        insertProgram()
        val plan = repo.selectedPlanIdFlow.first()!!
        val other = repo.duplicatePlan(plan, "Preferred")
        repo.selectPlan(other)
        val vm = preview(plan, "B")
        advanceUntilIdle()
        vm.start()
        advanceUntilIdle()
        assertEquals(DaySelection(plan, "B"), vm.workoutStart.navigation.first())
        val open = repo.openRunFlow.first()!!
        assertEquals(plan, open.planId)
        assertEquals(setOf(Slot.MAIN, Slot.SS), open.tracks.map { it.slot }.toSet())
        assertTrue(open.tracks.all { it.sets.size == 2 })
    }

    @Test fun cardioOnlyPreviewNavigatesToTheExistingDayScreenWithoutAStrengthRun() = runTest(dispatcher) {
        val plan = repo.createAuthoredPlan("Running", "Run")
        repo.publishCreatedAuthoredPlan(plan)
        repo.setCardioBlock(plan, "A", CardioBlock(CardioActivity.ROAD_RUN, listOf(
            CardioSetSpec(CardioRole.WORK, Measure.Time(120)),
        )))
        val vm = preview(plan, "A")
        advanceUntilIdle()
        assertEquals(PreviewCardio("Road run", "2:00"), vm.uiState.value.cardio)
        vm.start()
        advanceUntilIdle()
        assertEquals(DaySelection(plan, "A"), vm.workoutStart.navigation.first())
        assertNull(repo.openRunFlow.first())
    }

    @Test fun conflictingPreviewStartUsesTheSharedConfirmationPolicy() = runTest(dispatcher) {
        insertProgram()
        val plan = repo.selectedPlanIdFlow.first()!!
        repo.startRun(plan, "B", emptyList())
        val vm = preview(plan, "A")
        advanceUntilIdle()
        vm.start()
        advanceUntilIdle()
        assertEquals("B", vm.workoutStart.conflict.value?.run?.dayId)
        vm.workoutStart.confirmDiscard()
        vm.workoutStart.discardAndStart()
        advanceUntilIdle()
        assertEquals("A", repo.openRunFlow.first()!!.dayId)
        assertEquals(DaySelection(plan, "A"), vm.workoutStart.navigation.first())
    }

    @Test fun editArgumentOpensEditorOnceAndDismissalSurvivesRecreation() = runTest(dispatcher) {
        insertProgram()
        val plan = repo.selectedPlanIdFlow.first()!!
        val handle = SavedStateHandle(mapOf(Routes.DAY_PLAN_ID_ARG to plan.toString(), Routes.DAY_ID_ARG to "B", Routes.DAY_EDITOR_ARG to true))
        val vm = day(handle)
        advanceUntilIdle()
        assertEquals("B", vm.uiState.value.viewDayId)
        assertTrue(vm.dayEditState.value.editorOpen)
        vm.setDayEditorOpen(false)
        advanceUntilIdle()
        val restored = day(SavedStateHandle(handle.keys().associateWith { handle.get<Any?>(it) }))
        advanceUntilIdle()
        assertFalse(restored.dayEditState.value.editorOpen)
    }
}
