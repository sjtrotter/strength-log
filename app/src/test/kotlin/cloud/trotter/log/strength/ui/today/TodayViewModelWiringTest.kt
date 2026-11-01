package cloud.trotter.log.strength.ui.today

import androidx.lifecycle.SavedStateHandle
import cloud.trotter.log.strength.data.db.entity.CardioSessionEntity
import cloud.trotter.log.strength.data.db.entity.PlanEntity
import cloud.trotter.log.strength.data.RunStartResult
import cloud.trotter.log.strength.data.db.entity.WorkoutSessionEntity
import cloud.trotter.log.strength.domain.model.CardioActivity
import cloud.trotter.log.strength.domain.model.CardioBlock
import cloud.trotter.log.strength.domain.model.CardioRole
import cloud.trotter.log.strength.domain.model.CardioSetSpec
import cloud.trotter.log.strength.domain.model.Measure
import cloud.trotter.log.strength.domain.model.Program
import cloud.trotter.log.strength.domain.model.ProgramDay
import cloud.trotter.log.strength.domain.model.ProgramDayKind
import cloud.trotter.log.strength.ui.day.DaySelection
import cloud.trotter.log.strength.ui.preview.OverviewFixture
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TodayViewModelWiringTest : OverviewFixture() {
    @Test fun twoPlansOnTodayPutPreferredFirstAndKeepTheirOwnSuggestedDays() = runTest(dispatcher) {
        insertProgram()
        val first = repo.selectedPlanIdFlow.first()!!
        val second = repo.duplicatePlan(first, "Prep")
        repo.useAlongside(second)
        db.planDao().updateSuggestedDay(second, "B")
        repo.selectPlan(second)
        val vm = today()
        advanceUntilIdle()
        assertEquals(listOf(second, first), vm.uiState.value.upNext.map { it.planId })
        assertEquals(listOf("B", "A"), vm.uiState.value.upNext.map { it.dayId })
        assertNull(repo.openRunFlow.first())
    }

    @Test fun openRunAddsResumeWithoutReplacingSuggestedRows() = runTest(dispatcher) {
        insertProgram()
        val plan = repo.selectedPlanIdFlow.first()!!
        assertTrue(repo.startRun(plan, "B", emptyList()) is RunStartResult.Started)
        val vm = today()
        advanceUntilIdle()
        assertEquals(DaySelection(plan, "B"), vm.uiState.value.resume?.selection)
        assertEquals("A", vm.uiState.value.upNext.single().dayId)
    }

    @Test fun pausedOpenWorkoutStillHasResumeWithItsOwnDayAccent() = runTest(dispatcher) {
        insertProgram()
        val plan = repo.selectedPlanIdFlow.first()!!
        repo.startRun(plan, "B", emptyList())
        repo.pause(plan)
        val vm = today()
        advanceUntilIdle()
        assertEquals(DaySelection(plan, "B"), vm.uiState.value.resume?.selection)
        assertEquals(1, vm.uiState.value.resume?.dayIndex)
        assertTrue(vm.uiState.value.hasProgram)
        assertTrue(vm.uiState.value.nothingOnToday)
        assertTrue(vm.uiState.value.upNext.isEmpty())
    }

    @Test fun otherDaysIncludeRemainingStrengthAndStandaloneCardioAndExpansionRestores() = runTest(dispatcher) {
        insertProgram()
        val plan = repo.selectedPlanIdFlow.first()!!
        val program = repo.programFor(plan).first()
        repo.replaceProgram(Program(program.days + ProgramDay("C1", "Run", "", emptyList(),
            kind = ProgramDayKind.CARDIO, cardioBlock = CardioBlock(CardioActivity.ROAD_RUN, listOf(
                CardioSetSpec(CardioRole.WORK, Measure.Time(120)),
            )))))
        val handle = SavedStateHandle()
        val vm = today(handle)
        advanceUntilIdle()
        assertEquals(listOf("B", "C1"), vm.uiState.value.otherDays.map { it.dayId })
        assertTrue(vm.uiState.value.otherDays.last().cardioOnly)
        vm.toggleOtherDays()
        advanceUntilIdle()
        val restored = today(SavedStateHandle(handle.keys().associateWith { handle.get<Any?>(it) }))
        advanceUntilIdle()
        assertTrue(restored.uiState.value.otherDaysExpanded)
    }

    @Test fun noPlansAndNothingOnTodayRemainDistinct() = runTest(dispatcher) {
        val vm = today()
        advanceUntilIdle()
        assertFalse(vm.uiState.value.hasProgram)
        assertFalse(vm.uiState.value.nothingOnToday)
        insertProgram()
        repo.pause(repo.selectedPlanIdFlow.first()!!)
        advanceUntilIdle()
        assertTrue(vm.uiState.value.nothingOnToday)
        assertTrue(vm.uiState.value.upNext.isEmpty())
    }

    @Test fun pausedPreferredPlanGivesTheFirstPlanOnTodayDirectStart() = runTest(dispatcher) {
        insertProgram()
        val first = repo.selectedPlanIdFlow.first()!!
        val other = repo.duplicatePlan(first, "Other")
        repo.useAlongside(other)
        repo.pause(first)
        val vm = today()
        advanceUntilIdle()
        assertEquals(other, vm.uiState.value.upNext.single().planId)
        assertEquals(DaySelection(other, "A"), vm.uiState.value.upNext.first().selection)
        vm.workoutStart.start(vm.uiState.value.upNext.first().selection)
        advanceUntilIdle()
        assertEquals(other, repo.openRunFlow.first()!!.planId)
    }

    @Test fun startCreatesRunBeforeEmittingDayNavigationAndDoubleStartReusesIt() = runTest(dispatcher) {
        insertProgram()
        val vm = today()
        advanceUntilIdle()
        val target = vm.uiState.value.upNext.single().selection
        vm.workoutStart.start(target)
        advanceUntilIdle()
        val open = repo.openRunFlow.first()!!
        assertEquals(target.planId, open.planId)
        assertTrue(open.tracks.all { it.sets.none { set -> set.done } })
        assertEquals(target, vm.workoutStart.navigation.first())
        vm.workoutStart.start(target)
        advanceUntilIdle()
        assertEquals(open.id, repo.openRunFlow.first()!!.id)
        assertEquals(target, vm.workoutStart.navigation.first())
    }

    @Test fun conflictingStartConfirmsThenDiscardsAndStartsRequestedDay() = runTest(dispatcher) {
        insertProgram()
        val plan = repo.selectedPlanIdFlow.first()!!
        repo.startRun(plan, "B", emptyList())
        val old = repo.openRunFlow.first()!!
        val handle = SavedStateHandle()
        val vm = today(handle)
        advanceUntilIdle()
        vm.workoutStart.start(DaySelection(plan, "A"))
        advanceUntilIdle()
        assertEquals(old.id, vm.workoutStart.conflict.value?.run?.id)
        vm.workoutStart.discardAndStart()
        advanceUntilIdle()
        assertEquals(old.id, repo.openRunFlow.first()!!.id)
        vm.workoutStart.confirmDiscard()
        advanceUntilIdle()
        val restored = today(SavedStateHandle(handle.keys().associateWith { handle.get<Any?>(it) }))
        advanceUntilIdle()
        assertTrue(restored.workoutStart.conflict.value!!.confirming)
        restored.workoutStart.discardAndStart()
        advanceUntilIdle()
        assertEquals("A", repo.openRunFlow.first()!!.dayId)
        assertNotEquals(old.id, repo.openRunFlow.first()!!.id)
        assertEquals(DaySelection(plan, "A"), restored.workoutStart.navigation.first())
        assertNull(restored.workoutStart.conflict.value)
    }

    @Test fun conflictResumeKeepsTheOpenRun() = runTest(dispatcher) {
        insertProgram()
        val plan = repo.selectedPlanIdFlow.first()!!
        repo.startRun(plan, "B", emptyList())
        val vm = today()
        advanceUntilIdle()
        vm.workoutStart.start(DaySelection(plan, "A"))
        advanceUntilIdle()
        vm.workoutStart.resume()
        advanceUntilIdle()
        assertEquals(DaySelection(plan, "B"), vm.workoutStart.navigation.first())
        assertEquals("B", repo.openRunFlow.first()!!.dayId)
    }

    @Test fun factIsOneLastTrainedDateFromTheDisplayedPlan() = runTest(dispatcher) {
        insertProgram()
        val plan = repo.selectedPlanIdFlow.first()!!
        db.sessionDao().insertSession(WorkoutSessionEntity(0, "A", "Squat", null,
            java.time.Instant.parse("2026-10-28T18:00:00Z").toEpochMilli(), 180, planId = plan))
        val vm = today()
        advanceUntilIdle()
        assertEquals("Oct 28", vm.uiState.value.fact)
    }

    @Test fun emptyAuthoredDayRemainsAnUpNextRowAndRemovalMovesIt() = runTest(dispatcher) {
        val plan = repo.createAuthoredPlan("Physio", "Day A")
        repo.publishCreatedAuthoredPlan(plan)
        repo.addDay(plan, "Day B")
        val vm = today()
        advanceUntilIdle()
        assertEquals("A", vm.uiState.value.upNext.single().dayId)
        repo.removeDay(plan, "A")
        advanceUntilIdle()
        assertEquals("B", vm.uiState.value.upNext.single().dayId)
    }

    @Test fun lastTrainedDateFollowsPlanSelection() = runTest(dispatcher) {
        insertProgram()
        val first = repo.selectedPlanIdFlow.first()!!
        val second = repo.duplicatePlan(first, "Test prep")
        db.sessionDao().insertSessions(listOf(historySession(first, "2026-10-15"), historySession(second, "2026-10-18")))
        val vm = today()
        advanceUntilIdle()
        assertEquals("Oct 15", vm.uiState.value.fact)
        assertEquals(completionAt("2026-10-15"), repo.lastSessionAtFor(first).first())
        repo.selectPlan(second)
        advanceUntilIdle()
        assertEquals(second, vm.uiState.value.upNext.first().planId)
        assertEquals("Oct 18", vm.uiState.value.fact)
        assertEquals(completionAt("2026-10-18"), repo.lastSessionAtFor(second).first())
        repo.selectPlan(first)
        advanceUntilIdle()
        assertEquals("Oct 15", vm.uiState.value.fact)
    }

    @Test fun onlyTheLegacyPlanCountsNullPlanWorkoutHistory() = runTest(dispatcher) {
        insertProgram()
        assertEquals(PlanEntity.LEGACY_ID, repo.selectedPlanIdFlow.first())
        val second = repo.duplicatePlan(PlanEntity.LEGACY_ID, "Test prep")
        db.sessionDao().insertSessions(listOf(historySession(PlanEntity.LEGACY_ID, "2026-10-15"), historySession(null, "2026-10-19")))
        val vm = today()
        advanceUntilIdle()
        assertEquals("Oct 19", vm.uiState.value.fact)
        assertEquals(completionAt("2026-10-19"), repo.lastSessionAtFor(PlanEntity.LEGACY_ID).first())
        repo.selectPlan(second)
        advanceUntilIdle()
        assertNull(vm.uiState.value.fact)
        assertNull(repo.lastSessionAtFor(second).first())
    }

    @Test fun cardioCountsForItsPlanAndNullPlanCardioCountsForLegacy() = runTest(dispatcher) {
        insertProgram()
        val first = repo.selectedPlanIdFlow.first()!!
        val second = repo.duplicatePlan(first, "Test prep")
        db.sessionDao().insertSession(historySession(first, "2026-10-15"))
        db.cardioSessionDao().insertAll(listOf(cardioSession(first, "2026-10-18"), cardioSession(second, "2026-10-19")))
        val vm = today()
        advanceUntilIdle()
        assertEquals("Oct 18", vm.uiState.value.fact)
        assertEquals(completionAt("2026-10-18"), repo.lastSessionAtFor(first).first())
        db.cardioSessionDao().insert(cardioSession(null, "2026-10-20"))
        advanceUntilIdle()
        assertEquals("Oct 20", vm.uiState.value.fact)
        assertEquals(completionAt("2026-10-20"), repo.lastSessionAtFor(first).first())
        repo.selectPlan(second)
        advanceUntilIdle()
        assertEquals("Oct 19", vm.uiState.value.fact)
        assertEquals(completionAt("2026-10-19"), repo.lastSessionAtFor(second).first())
    }

    @Test fun everyEmissionKeepsTheFactWithItsRowsWhileMembershipProgramReadIsDelayed() = runTest(dispatcher) {
        insertProgram()
        val first = repo.selectedPlanIdFlow.first()!!
        repo.renamePlan(first, "Strength")
        val second = repo.duplicatePlan(first, "Running")
        repo.renameDay(second, "A", "Run prep") // Same day id, different plan.
        db.sessionDao().insertSessions(listOf(historySession(first, "2026-10-15"), historySession(second, "2026-10-18")))
        val vm = today()
        val emissions = mutableListOf<TodayUiState>()
        val collect = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            vm.uiState.collect { emissions += it }
        }
        advanceUntilIdle()
        assertEquals(first, vm.uiState.value.upNext.single().planId)
        assertEquals("Oct 15", vm.uiState.value.fact)

        delayedPlanId = second
        val gate = CompletableDeferred<Unit>()
        programReadGate = gate
        repo.useOnly(second)
        advanceUntilIdle()
        assertTrue("the new program must be waiting", delayedProgramReadStarted)
        assertEquals(first, vm.uiState.value.upNext.single().planId)
        assertEquals("Oct 15", vm.uiState.value.fact)
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(second, vm.uiState.value.upNext.single().planId)
        assertEquals("Oct 18", vm.uiState.value.fact)
        repo.useOnly(first)
        advanceUntilIdle()
        assertEquals(first, vm.uiState.value.upNext.single().planId)
        assertTrue(emissions.any { it.upNext.firstOrNull()?.planId == second })
        emissions.filter { it.upNext.isNotEmpty() }.forEach { state ->
            val row = state.upNext.first()
            val expected = when (row.planId) {
                first -> Triple("Strength", "Squat", "Oct 15")
                second -> Triple("Running", "Run prep", "Oct 18")
                else -> error("Unexpected plan in $state")
            }
            assertEquals("mixed plan content in $state", expected, Triple(row.planName, row.title, state.fact))
            assertTrue(state.otherDays.all { it.planId == row.planId })
        }
        collect.cancel()
    }

    private fun historySession(planId: Long?, date: String) = WorkoutSessionEntity(
        0, "A", "Day A", null, completionAt(date), 180, planId = planId,
    )

    private fun cardioSession(planId: Long?, date: String) = CardioSessionEntity(
        dayId = "A", mode = "OUTDOOR_RUN", hard = false, label = "Zone 2",
        startedAt = completionAt(date) - 60_000, completedAt = completionAt(date),
        seconds = 60, stepsCompleted = 1, planId = planId,
    )

    private fun completionAt(date: String) = java.time.LocalDate.parse(date).atTime(12, 0)
        .atZone(java.time.ZoneId.of("America/Chicago")).toInstant().toEpochMilli()

}
