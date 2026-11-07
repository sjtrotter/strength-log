package cloud.trotter.log.strength.ui.preview

import cloud.trotter.log.strength.domain.cardio.CardioNextTime
import cloud.trotter.log.strength.domain.cardio.CardioIncrease
import cloud.trotter.log.strength.domain.model.CardioSetResult
import cloud.trotter.log.strength.domain.model.CardioSetStatus
import cloud.trotter.log.strength.data.db.entity.CardioSessionEntity
import androidx.lifecycle.SavedStateHandle
import cloud.trotter.log.strength.data.RunStartResult
import cloud.trotter.log.strength.data.db.entity.SessionSetEntity
import cloud.trotter.log.strength.data.db.entity.Slot
import cloud.trotter.log.strength.data.db.entity.WorkoutSessionEntity
import cloud.trotter.log.strength.data.storeTestRows
import cloud.trotter.log.strength.domain.library.ExerciseLibrary
import cloud.trotter.log.strength.domain.model.CardioActivity
import cloud.trotter.log.strength.domain.model.CardioBlock
import cloud.trotter.log.strength.domain.model.CardioRole
import cloud.trotter.log.strength.domain.model.CardioSetSpec
import cloud.trotter.log.strength.domain.model.LoggedSet
import cloud.trotter.log.strength.domain.model.Measure
import cloud.trotter.log.strength.domain.model.Program
import cloud.trotter.log.strength.domain.model.ProgramDay
import cloud.trotter.log.strength.domain.model.ProgramExercise
import cloud.trotter.log.strength.domain.model.PrescribedTarget
import cloud.trotter.log.strength.domain.model.Prescription
import cloud.trotter.log.strength.domain.model.SetKind
import cloud.trotter.log.strength.ui.Routes
import cloud.trotter.log.strength.ui.day.DaySelection
import cloud.trotter.log.strength.ui.day.NextTime
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

    @Test fun `cardio offer applies to working values and newest same-day session is judged`() = runTest(dispatcher) {
        val spec = CardioSetSpec(CardioRole.WORK, Measure.Time(1200))
        val block = CardioBlock(CardioActivity.ROAD_RUN, listOf(spec))
        repo.replaceProgram(Program(listOf(ProgramDay("A", "Run", "", emptyList(), cardioBlock = block))))
        val plan = repo.plans.selectedPlanIdFlow.first()!!
        repo.plans.setOfferCardioIncreases(plan, true)
        val session = CardioSessionEntity(planId = plan, dayId = "A", mode = "OUTDOOR_RUN", hard = false,
            label = "Run", startedAt = 1000, completedAt = 2000, seconds = 1200, stepsCompleted = 1, activity = block.activity.name)
        repo.logCardioSession(session, listOf(CardioSetResult(spec, CardioSetStatus.FINISHED, 1200)))
        // Newer evidence from another day or plan cannot replace this day's evidence.
        repo.logCardioSession(session.copy(dayId = "B", completedAt = 8000), listOf(CardioSetResult(spec, CardioSetStatus.SHORTENED, 1100)))
        repo.logCardioSession(session.copy(planId = 999, startedAt = 7000, completedAt = 9000), listOf(CardioSetResult(spec, CardioSetStatus.SHORTENED, 1100)))
        val vm = preview(plan, "A")
        advanceUntilIdle()
        val offer = vm.uiState.value.cardio?.nextTime as CardioNextTime.Offer
        assertEquals(CardioIncrease.Time(60), offer.change)
        vm.applyCardioNextTime(offer.offered)
        advanceUntilIdle()
        assertTrue((vm.uiState.value.cardio?.nextTime as CardioNextTime.Offer).applied)
        assertEquals(offer.offered, repo.plans.programFor(plan).first().days.single().cardioToRun)
        vm.applyCardioNextTime(offer.opening)
        advanceUntilIdle()
        assertFalse((vm.uiState.value.cardio?.nextTime as CardioNextTime.Offer).applied)
        repo.logCardioSession(session.copy(startedAt = 2100, completedAt = 3000), listOf(CardioSetResult(spec, CardioSetStatus.FINISHED, 1100)))
        advanceUntilIdle()
        assertEquals(CardioNextTime.Repeat, vm.uiState.value.cardio?.nextTime)
        repo.plans.setOfferCardioIncreases(plan, false)
        advanceUntilIdle()
        assertNull(vm.uiState.value.cardio?.nextTime)
    }
    @Test fun `last shows every set`() = runTest(dispatcher) {
        repo.replaceProgram(Program(listOf(ProgramDay("A", "Bench", "", listOf(
            ProgramExercise("bb_bench", targetSets = 3, prescription = Prescription(PrescribedTarget.LoadReps(155.0, 5))),
        )))))
        val plan = repo.plans.selectedPlanIdFlow.first()!!
        val id = repo.plans.slotsFor(plan, "A").first().single().programExerciseId
        val started = repo.startRun(plan, "A") as RunStartResult.Started
        db.storeTestRows("A", id, Slot.MAIN,
            listOf(5, 5, 4).mapIndexed { index, reps ->
                LoggedSet(155.0, reps, SetKind.WORK, done = true, plannedIndex = index)
            }, started.runId)
        repo.finishRun(started.runId)

        val vm = preview(plan, "A")
        advanceUntilIdle()
        assertEquals("155 lb × 5, 5, 4", vm.uiState.value.lifts.single().previous.single().last)
    }

    @Test fun `last shows every working set of a mixed ramp session`() = runTest(dispatcher) {
        insertProgram()
        val plan = repo.plans.selectedPlanIdFlow.first()!!
        val id = repo.plans.slotsFor(plan, "A").first().single().programExerciseId
        val started = repo.startRun(plan, "A") as RunStartResult.Started
        db.storeTestRows("A", id, Slot.MAIN, listOf(
            LoggedSet(135.0, 5, SetKind.RAMP, done = true, plannedIndex = 0),
            LoggedSet(235.0, 5, SetKind.TOP, done = true, plannedIndex = 1),
            LoggedSet(175.0, 8, SetKind.BACKOFF, done = true, plannedIndex = 2),
        ), started.runId)
        repo.finishRun(started.runId)

        val vm = preview(plan, "A")
        advanceUntilIdle()
        assertEquals("235 lb × 5, 175 lb × 8", vm.uiState.value.lifts.single().previous.single().last)
    }

    @Test fun previewReadsTheSlotsStickyNoteAndTracksEdits() = runTest(dispatcher) {
        insertProgram()
        val plan = repo.plans.selectedPlanIdFlow.first()!!
        val slot = repo.plans.slotsFor(plan, "B").first().single().programExerciseId
        val vm = preview(plan, "B")
        advanceUntilIdle()
        assertEquals("", vm.uiState.value.lifts.single().note)
        repo.setExerciseNote(slot, "Seat 4")
        advanceUntilIdle()
        assertEquals("Seat 4", vm.uiState.value.lifts.single().note)
        repo.setExerciseNote(slot, "")
        advanceUntilIdle()
        assertEquals("", vm.uiState.value.lifts.single().note)
    }

    @Test fun previewKeepsAnOfferAfterSuccessThenAMissAndHidesItInPlanMode() = runTest(dispatcher) {
        repo.replaceProgram(Program(listOf(ProgramDay("A", "Bench", "", listOf(
            ProgramExercise("bb_bench", targetSets = 3, prescription = Prescription(PrescribedTarget.LoadReps(70.0, 5))),
        )))))
        val plan = repo.plans.selectedPlanIdFlow.first()!!
        val id = repo.plans.slotsFor(plan, "A").first().single().programExerciseId
        val vm = preview(plan, "A")
        advanceUntilIdle()
        assertNull(vm.uiState.value.lifts.single().nextTime)
        val success = repo.startRun(plan, "A") as RunStartResult.Started
        db.storeTestRows("A", id, Slot.MAIN,
            List(3) { LoggedSet(70.0, 5, SetKind.WORK, done = true, plannedIndex = it) }, success.runId)
        repo.finishRun(success.runId)
        advanceUntilIdle()
        assertEquals("3 × 5 · 70 lb", vm.uiState.value.lifts.single().working)
        assertEquals(NextTime.Offer("+5 lb", false, 75.0, 70.0), vm.uiState.value.lifts.single().nextTime)

        val miss = repo.startRun(plan, "A") as RunStartResult.Started
        db.storeTestRows("A", id, Slot.MAIN,
            List(3) { LoggedSet(70.0, if (it == 2) 4 else 5, SetKind.WORK, done = true, plannedIndex = it) }, miss.runId)
        repo.finishRun(miss.runId)
        advanceUntilIdle()
        assertEquals("3 × 5 · 70 lb", vm.uiState.value.lifts.single().working)
        assertEquals(NextTime.Offer("+5 lb", false, 75.0, 70.0), vm.uiState.value.lifts.single().nextTime)
        repo.setKeepToPlan(id, true)
        advanceUntilIdle()
        assertNull(vm.uiState.value.lifts.single().nextTime)
    }

    @Test fun lastPrefersTheSamePlanDayAndSlotAndLabelsTheFallbackDay() = runTest(dispatcher) {
        insertProgram()
        val plan = repo.plans.selectedPlanIdFlow.first()!!
        val own = db.sessionDao().insertSession(WorkoutSessionEntity(0, "A", "Squat", null, 1000, 235.0, planId = plan))
        db.sessionDao().insertSets(listOf(SessionSetEntity(0, own, "bb_back_squat", "Squat", Slot.MAIN, 0,
            SetKind.TOP.name, 235.0, 5, true)))
        val elsewhere = db.sessionDao().insertSession(WorkoutSessionEntity(0, "B", "Other day", null, 2000, 235.0, planId = plan))
        db.sessionDao().insertSets(listOf(SessionSetEntity(0, elsewhere, "bb_back_squat", "Squat", Slot.MAIN, 0,
            SetKind.TOP.name, 225.0, 6, true)))
        val vm = preview(plan, "A")
        advanceUntilIdle()
        assertEquals("235 lb × 5", vm.uiState.value.lifts.single().previous.single().last)
        assertNull(vm.uiState.value.lifts.single().previous.single().source)

        repo.history.deleteSession(own)
        advanceUntilIdle()
        val fallback = vm.uiState.value.lifts.single().previous.single()
        assertEquals("225 lb × 6", fallback.last)
        assertEquals("B", fallback.source)
        assertNull(fallback.sourcePlanName)
    }

    @Test fun heavierSquatOnDayBOffersOnDayAAndAMissElsewhereDoesNotChangeLast() = runTest(dispatcher) {
        repo.replaceProgram(Program(listOf("A", "B").map { day ->
            ProgramDay(day, "Squat", "", listOf(ProgramExercise("bb_back_squat", targetSets = 3,
                prescription = Prescription(PrescribedTarget.LoadReps(if (day == "A") 45.0 else 50.0, 5)))))
        }))
        val plan = repo.plans.selectedPlanIdFlow.first()!!
        val a = repo.plans.slotsFor(plan, "A").first().single().programExerciseId
        val b = repo.plans.slotsFor(plan, "B").first().single().programExerciseId
        val own = repo.startRun(plan, "A") as RunStartResult.Started
        db.storeTestRows("A", a, Slot.MAIN,
            List(3) { LoggedSet(45.0, 4, SetKind.WORK, done = true, plannedIndex = it) }, own.runId)
        repo.finishRun(own.runId)
        val vm = preview(plan, "A")
        advanceUntilIdle()
        assertEquals(NextTime.Repeat, vm.uiState.value.lifts.single().nextTime)

        val success = repo.startRun(plan, "B") as RunStartResult.Started
        db.storeTestRows("B", b, Slot.MAIN,
            List(3) { LoggedSet(55.0, 5, SetKind.WORK, done = true, plannedIndex = it) }, success.runId)
        repo.finishRun(success.runId)
        val workout = day(SavedStateHandle(mapOf("day_view_selection" to DaySelection(plan, "A"))))
        advanceUntilIdle()
        val offer = NextTime.Offer("+10 lb", false, 55.0, 45.0)
        assertEquals(offer, vm.uiState.value.lifts.single().nextTime)
        assertEquals(offer, workout.uiState.value.exercises.single().nextTime)
        assertEquals("3 × 5 · 45 lb", vm.uiState.value.lifts.single().working)
        assertEquals("45 lb × 4, 4, 4", vm.uiState.value.lifts.single().previous.single().last)
        assertNull(vm.uiState.value.lifts.single().previous.single().source)

        val miss = repo.startRun(plan, "B") as RunStartResult.Started
        db.storeTestRows("B", b, Slot.MAIN,
            List(3) { LoggedSet(60.0, 4, SetKind.WORK, done = true, plannedIndex = it) }, miss.runId)
        repo.finishRun(miss.runId)
        advanceUntilIdle()
        assertEquals(offer, vm.uiState.value.lifts.single().nextTime)
        assertEquals(offer, workout.uiState.value.exercises.single().nextTime)
        assertEquals("45 lb × 4, 4, 4", vm.uiState.value.lifts.single().previous.single().last)
        assertNull(vm.uiState.value.lifts.single().previous.single().source)
    }

    @Test fun heavierSquatOnDayBOffersOnUnperformedDayAInPreviewAndWorkout() = runTest(dispatcher) {
        repo.replaceProgram(Program(listOf("A", "B").map { day ->
            ProgramDay(day, "Squat", "", listOf(ProgramExercise("bb_back_squat", targetSets = 3,
                prescription = Prescription(PrescribedTarget.LoadReps(if (day == "A") 45.0 else 50.0, 5)))))
        }))
        val plan = repo.plans.selectedPlanIdFlow.first()!!
        val a = repo.plans.slotsFor(plan, "A").first().single().programExerciseId
        val b = repo.plans.slotsFor(plan, "B").first().single().programExerciseId
        val success = repo.startRun(plan, "B") as RunStartResult.Started
        db.storeTestRows("B", b, Slot.MAIN,
            List(3) { LoggedSet(55.0, 5, SetKind.WORK, done = true, plannedIndex = it) }, success.runId)
        repo.finishRun(success.runId)
        assertTrue(repo.history.slotSessions(plan, "A").isEmpty())

        val vm = preview(plan, "A")
        advanceUntilIdle()
        val offer = NextTime.Offer("+10 lb", false, 55.0, 45.0)
        assertEquals(offer, vm.uiState.value.lifts.single().nextTime)
        assertEquals("3 × 5 · 45 lb", vm.uiState.value.lifts.single().working)
        assertTrue(repo.dayTracksFlow(plan, "A").first().tracks.isEmpty())
        val workout = day(SavedStateHandle(mapOf("day_view_selection" to DaySelection(plan, "A"))))
        advanceUntilIdle()
        assertEquals(offer, workout.uiState.value.exercises.single().nextTime)
        assertEquals("55 lb × 5, 5, 5", vm.uiState.value.lifts.single().previous.single().last)
        assertEquals("B", vm.uiState.value.lifts.single().previous.single().source)

        vm.setWorkingWeight(a, offer.offerLb)
        advanceUntilIdle()
        assertEquals(offer.copy(applied = true), vm.uiState.value.lifts.single().nextTime)
        assertEquals(offer.copy(applied = true), workout.uiState.value.exercises.single().nextTime)
        workout.setWorkingWeight(a, Slot.MAIN, offer.openingLb)
        advanceUntilIdle()
        assertEquals(offer, vm.uiState.value.lifts.single().nextTime)
        assertEquals(offer, workout.uiState.value.exercises.single().nextTime)
    }

    @Test fun lastLabelsAnotherPlanAndDoesNotTreatAnotherSlotAsItsOwn() = runTest(dispatcher) {
        insertProgram()
        val plan = repo.plans.selectedPlanIdFlow.first()!!
        val other = repo.plans.duplicatePlan(plan, "Other plan")
        val session = db.sessionDao().insertSession(WorkoutSessionEntity(0, "B", "Other day", null, 1000, 235.0,
            planId = other, planName = "Other plan"))
        db.sessionDao().insertSets(listOf(SessionSetEntity(0, session, "bb_back_squat", "Squat", Slot.MAIN, 0,
            SetKind.TOP.name, 225.0, 5, true)))
        val vm = preview(plan, "A")
        advanceUntilIdle()
        val fallback = vm.uiState.value.lifts.single().previous.single()
        assertEquals("B", fallback.source)
        assertEquals("Other plan", fallback.sourcePlanName)

        val ownPartner = db.sessionDao().insertSession(WorkoutSessionEntity(0, "A", "Squat", null, 2000, 235.0, planId = plan))
        db.sessionDao().insertSets(listOf(SessionSetEntity(0, ownPartner, "bb_back_squat", "Squat", Slot.SS, 0,
            SetKind.WORK.name, 230.0, 5, true)))
        advanceUntilIdle()
        val otherSlot = vm.uiState.value.lifts.single().previous.single()
        assertEquals("230 lb × 5", otherSlot.last)
        assertEquals("A", otherSlot.source)
        assertNull(otherSlot.sourcePlanName)
    }

    @Test fun lastHoldUsesSecondsAndSuppressesMismatchedLegacyHistory() = runTest(dispatcher) {
        repo.replaceProgram(Program(listOf(ProgramDay("A", "Core", "", listOf(ProgramExercise("plank"))))))
        val plan = repo.plans.selectedPlanIdFlow.first()!!
        val vm = preview(plan, "A")
        advanceUntilIdle()
        assertNull(vm.uiState.value.lifts.single().previous.single().last)
        assertTrue(vm.uiState.value.lifts.single().previous.single().comparable)

        val weightedSession = db.sessionDao().insertSession(WorkoutSessionEntity(0, "A", "Core", null, 1000, 235.0, planId = plan))
        db.sessionDao().insertSets(listOf(SessionSetEntity(0, weightedSession, "plank", "Plank", Slot.MAIN, 0,
            SetKind.WORK.name, 235.0, 5, true)))
        advanceUntilIdle()
        assertNull(vm.uiState.value.lifts.single().previous.single().last)
        assertFalse(vm.uiState.value.lifts.single().previous.single().comparable)

        val holdSession = db.sessionDao().insertSession(WorkoutSessionEntity(0, "A", "Core", null, 2000, 235.0, planId = plan))
        db.sessionDao().insertSets(listOf(SessionSetEntity(0, holdSession, "plank", "Plank", Slot.MAIN, 0,
            SetKind.WORK.name, 0.0, 0, true, seconds = 45)))
        advanceUntilIdle()
        assertEquals("45 s", vm.uiState.value.lifts.single().previous.single().last)
        assertTrue(vm.uiState.value.lifts.single().previous.single().comparable)
    }

    @Test fun aGapInDayLettersKeepsTheThirdAccentInPreview() = runTest(dispatcher) {
        insertProgram()
        val plan = repo.plans.selectedPlanIdFlow.first()!!
        val program = repo.plans.programFor(plan).first()
        repo.replaceProgram(Program(listOf(program.days[0], program.days[1].copy(id = "C"))))
        val vm = preview(plan, "C")
        advanceUntilIdle()

        assertEquals("C", vm.uiState.value.selection.dayId)
        assertEquals(2, vm.uiState.value.dayIndex)
    }

    @Test fun unopenedPreviewUsesTheWorkoutSeedWithoutWritingWorkingValues() = runTest(dispatcher) {
        insertProgram()
        val plan = repo.plans.selectedPlanIdFlow.first()!!
        val vm = preview(plan, "A")
        advanceUntilIdle()
        assertEquals("TOP × 5 · 235 lb", vm.uiState.value.lifts.single().working)
        assertEquals("Barbell Back Squat", vm.uiState.value.lifts.single().name)
        assertNull(vm.uiState.value.lifts.single().previous.single().last)
        assertTrue(repo.dayTracksFlow(plan, "A").first().tracks.isEmpty())
        assertNull(repo.openRunFlow.first())
        val workout = day()
        advanceUntilIdle()
        assertEquals(vm.uiState.value.lifts.single().working,
            workout.uiState.value.exercises.single().rows.first { it.kind == SetKind.TOP }.let {
                "TOP × ${it.reps} · ${it.weightDisplay.toInt()} lb"
            })
    }

    @Test fun openRunWithASkippedLiftLeavesItOutOfThePreview() = runTest(dispatcher) {
        repo.replaceProgram(Program(listOf(ProgramDay("A", "Push", "", listOf(
            ProgramExercise("bb_bench"), ProgramExercise("pushup"),
        )))))
        val plan = repo.plans.selectedPlanIdFlow.first()!!
        val skipped = repo.plans.slotsFor(plan, "A").first().first()
        repo.startRun(plan, "A")
        val vm = preview(plan, "A")
        advanceUntilIdle()
        assertEquals(2, vm.uiState.value.lifts.size)

        repo.skipLift(plan, "A", skipped.programExerciseId, Slot.MAIN, "bb_bench", null)
        advanceUntilIdle()

        assertTrue(repo.dayTracksFlow(plan, "A").first().runOpen)
        assertEquals(setOf(skipped.programExerciseId), repo.dayTracksFlow(plan, "A").first().skippedLifts)
        assertEquals(listOf(repo.catalog.catalogFlow.first().get("pushup").name), vm.uiState.value.lifts.map { it.name })
    }

    @Test fun repsOnlyWorkingValueHasNoLeadingSpace() = runTest(dispatcher) {
        repo.replaceProgram(Program(listOf(ProgramDay("A", "Push", "", listOf(ProgramExercise("pushup"))))))
        val plan = repo.plans.selectedPlanIdFlow.first()!!
        val slot = repo.plans.slotsFor(plan, "A").first().single().programExerciseId
        db.storeTestRows("A", slot, Slot.MAIN, listOf(LoggedSet(0.0, 8, SetKind.WORK)))
        val vm = preview(plan, "A")
        advanceUntilIdle()
        assertEquals("1 × 8", vm.uiState.value.lifts.single().working)
    }

    @Test fun previewUsesStoredFirstWorkingValuesRatherThanTheGoalOrRamp() = runTest(dispatcher) {
        insertProgram()
        val plan = repo.plans.selectedPlanIdFlow.first()!!
        val slot = repo.plans.slotsFor(plan, "A").first().single().programExerciseId
        db.storeTestRows("A", slot, Slot.MAIN, listOf(
            LoggedSet(100.0, 5, SetKind.RAMP), LoggedSet(225.0, 7, SetKind.TOP), LoggedSet(175.0, 8, SetKind.BACKOFF),
        ))
        val vm = preview(plan, "A")
        advanceUntilIdle()
        assertEquals("TOP × 7 · 225 lb", vm.uiState.value.lifts.single().working)
        vm.start()
        advanceUntilIdle()
        val track = repo.openRunFlow.first()!!.tracks.single()
        assertEquals(listOf(100.0, 225.0, 175.0), track.sets.map { it.weightLb })
        assertEquals(DaySelection(plan, "A"), vm.workoutStart.navigation.first())
    }

    @Test fun performedNameLoadAndLastFollowTheRunSwapAndHistoryEdits() = runTest(dispatcher) {
        insertProgram()
        val plan = repo.plans.selectedPlanIdFlow.first()!!
        val slot = repo.plans.slotsFor(plan, "A").first().single().programExerciseId
        val started = repo.startRun(plan, "A") as RunStartResult.Started
        assertTrue(repo.swapInRun(started.runId, slot, Slot.MAIN, "hack_squat"))
        db.storeTestRows("A", slot, Slot.MAIN, listOf(LoggedSet(180.0, 8, SetKind.WORK)), started.runId)
        val vm = preview(plan, "A")
        advanceUntilIdle()
        assertEquals("Hack Squat", vm.uiState.value.lifts.single().name)
        assertEquals("1 × 8 · 180 lb", vm.uiState.value.lifts.single().working)
        assertNull(vm.uiState.value.lifts.single().previous.single().last)
        val session = db.sessionDao().insertSession(WorkoutSessionEntity(0, "A", "Squat", null, 1000, 235.0, planId = plan))
        db.sessionDao().insertSets(listOf(SessionSetEntity(0, session, "hack_squat", "Hack Squat", Slot.MAIN, 0,
            SetKind.WORK.name, 170.0, 9, true)))
        advanceUntilIdle()
        assertEquals("170 lb × 9", vm.uiState.value.lifts.single().previous.single().last)
        assertEquals("bb_back_squat", repo.plans.slotsFor(plan, "A").first().single().exercise.exerciseId)
    }

    @Test fun supersetJoinsBothPerformedNamesAndValues() = runTest(dispatcher) {
        insertProgram()
        val plan = repo.plans.selectedPlanIdFlow.first()!!
        val vm = preview(plan, "B")
        advanceUntilIdle()
        val catalog = repo.catalog.catalogFlow.first()
        assertEquals("${catalog.find("ez_curl")!!.name} + ${catalog.find("rope_pushdown")!!.name}", vm.uiState.value.lifts.single().name)
        assertTrue(vm.uiState.value.lifts.single().working!!.contains(" + "))
        vm.start()
        advanceUntilIdle()
        val slot = repo.plans.slotsFor(plan, "B").first().single().programExerciseId
        repo.swapInRun(repo.openRunFlow.first()!!.id, slot, Slot.SS, "bb_bench")
        advanceUntilIdle()
        assertEquals("${catalog.find("ez_curl")!!.name} + ${catalog.find("bb_bench")!!.name}", vm.uiState.value.lifts.single().name)
    }

    @Test fun supersetKeepsMainHistoryWhenPartnerHasNoHistory() = runTest(dispatcher) {
        insertProgram()
        val plan = repo.plans.selectedPlanIdFlow.first()!!
        val session = db.sessionDao().insertSession(WorkoutSessionEntity(0, "B", "Arms", null, 1000, 235.0, planId = plan))
        db.sessionDao().insertSets(listOf(SessionSetEntity(0, session, "ez_curl", "EZ-Bar Curl", Slot.MAIN, 0,
            SetKind.WORK.name, 50.0, 10, true)))
        val vm = preview(plan, "B")
        advanceUntilIdle()

        assertEquals(listOf(PreviewPreviousResult("50 lb × 10", exerciseName = ExerciseLibrary.get("ez_curl").name), PreviewPreviousResult(null, exerciseName = ExerciseLibrary.get("rope_pushdown").name)),
            vm.uiState.value.lifts.single().previous)

        db.sessionDao().insertSets(listOf(SessionSetEntity(0, session, "rope_pushdown", "Rope Pushdown", Slot.SS, 0,
            SetKind.WORK.name, 60.0, 12, true)))
        advanceUntilIdle()
        assertEquals(listOf(PreviewPreviousResult("50 lb × 10", exerciseName = ExerciseLibrary.get("ez_curl").name), PreviewPreviousResult("60 lb × 12", exerciseName = ExerciseLibrary.get("rope_pushdown").name)),
            vm.uiState.value.lifts.single().previous)
    }

    @Test fun supersetKeepsMainHistoryWhenPartnerHistoryHasMismatchedTracking() = runTest(dispatcher) {
        insertProgram()
        val plan = repo.plans.selectedPlanIdFlow.first()!!
        val session = db.sessionDao().insertSession(WorkoutSessionEntity(0, "B", "Arms", null, 1000, 235.0, planId = plan))
        db.sessionDao().insertSets(listOf(
            SessionSetEntity(0, session, "ez_curl", "EZ-Bar Curl", Slot.MAIN, 0, SetKind.WORK.name, 50.0, 10, true),
            SessionSetEntity(0, session, "rope_pushdown", "Rope Pushdown", Slot.SS, 0, SetKind.WORK.name,
                0.0, 0, true, seconds = 45),
        ))
        val vm = preview(plan, "B")
        advanceUntilIdle()

        assertEquals(listOf(PreviewPreviousResult("50 lb × 10", exerciseName = ExerciseLibrary.get("ez_curl").name), PreviewPreviousResult(null, comparable = false, exerciseName = ExerciseLibrary.get("rope_pushdown").name)),
            vm.uiState.value.lifts.single().previous)
    }

    @Test fun previewStartSeedsBothTracksAndEmitsItsOwnPlanAndDay() = runTest(dispatcher) {
        insertProgram()
        val plan = repo.plans.selectedPlanIdFlow.first()!!
        val other = repo.plans.duplicatePlan(plan, "Preferred")
        repo.plans.selectPlan(other)
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
        val plan = repo.plans.createAuthoredPlan("Running", "Run")
        repo.plans.publishCreatedAuthoredPlan(plan)
        repo.setCardioBlock(plan, "A", CardioBlock(CardioActivity.ROAD_RUN, listOf(
            CardioSetSpec(CardioRole.WORK, Measure.Time(120)),
        )))
        val vm = preview(plan, "A")
        advanceUntilIdle()
        assertEquals(PreviewCardio("Road run", "2:00", cloud.trotter.log.strength.domain.model.CardioActivity.ROAD_RUN), vm.uiState.value.cardio)
        vm.start()
        advanceUntilIdle()
        assertEquals(DaySelection(plan, "A"), vm.workoutStart.navigation.first())
        assertNull(repo.openRunFlow.first())
    }

    @Test fun conflictingPreviewStartUsesTheSharedConfirmationPolicy() = runTest(dispatcher) {
        insertProgram()
        val plan = repo.plans.selectedPlanIdFlow.first()!!
        repo.startRun(plan, "B")
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

    @Test fun conflictDisappearanceThenAcceptAndUndoKeepsOpeningWeightOnStart() = runTest(dispatcher) {
        repo.replaceProgram(Program(listOf(
            ProgramDay("A", "Bench", "", listOf(
                ProgramExercise("bb_bench", targetSets = 3, prescription = Prescription(PrescribedTarget.LoadReps(70.0, 5))),
            )),
            ProgramDay("B", "Arms", "", listOf(ProgramExercise("ez_curl"))),
        )))
        val plan = repo.plans.selectedPlanIdFlow.first()!!
        val id = repo.plans.slotsFor(plan, "A").first().single().programExerciseId
        val success = repo.startRun(plan, "A") as RunStartResult.Started
        db.storeTestRows("A", id, Slot.MAIN,
            List(3) { LoggedSet(70.0, 5, SetKind.WORK, done = true, plannedIndex = it) }, success.runId)
        repo.finishRun(success.runId)
        val other = repo.startRun(plan, "B") as RunStartResult.Started
        val vm = preview(plan, "A")
        advanceUntilIdle()
        val offer = vm.uiState.value.lifts.single().nextTime as NextTime.Offer
        assertEquals(NextTime.Offer("+5 lb", false, 75.0, 70.0), offer)

        vm.setWorkingWeight(id, offer.offerLb)
        advanceUntilIdle()
        assertEquals(other.runId, vm.workoutStart.conflict.value?.run?.id)
        assertTrue(repo.discardRun(other.runId))
        advanceUntilIdle()
        assertNull(vm.workoutStart.conflict.value)

        vm.setWorkingWeight(id, offer.offerLb)
        advanceUntilIdle()
        assertEquals("3 × 5 · 75 lb", vm.uiState.value.lifts.single().working)
        assertEquals(offer.copy(applied = true), vm.uiState.value.lifts.single().nextTime)
        vm.setWorkingWeight(id, offer.openingLb)
        advanceUntilIdle()
        assertEquals("3 × 5 · 70 lb", vm.uiState.value.lifts.single().working)
        assertEquals(offer, vm.uiState.value.lifts.single().nextTime)

        vm.start()
        advanceUntilIdle()
        val open = repo.openRunFlow.first()!!
        assertEquals("A", open.dayId)
        assertEquals(List(3) { 70.0 }, open.tracks.single().sets.map { it.weightLb })
        assertEquals(DaySelection(plan, "A"), vm.workoutStart.navigation.first())
    }

    @Test fun editArgumentOpensEditorOnceAndDismissalSurvivesRecreation() = runTest(dispatcher) {
        insertProgram()
        val plan = repo.plans.selectedPlanIdFlow.first()!!
        val handle = SavedStateHandle(mapOf(Routes.DAY_PLAN_ID_ARG to plan.toString(), Routes.DAY_ID_ARG to "B", Routes.DAY_EDITOR_ARG to true))
        val vm = day(handle)
        advanceUntilIdle()
        assertEquals("B", vm.uiState.value.viewDayId)
        assertTrue(vm.dayEditState.value.editorOpen)
        vm.editor.setDayEditorOpen(false)
        advanceUntilIdle()
        val restored = day(SavedStateHandle(handle.keys().associateWith { handle.get<Any?>(it) }))
        advanceUntilIdle()
        assertFalse(restored.dayEditState.value.editorOpen)
    }
}
