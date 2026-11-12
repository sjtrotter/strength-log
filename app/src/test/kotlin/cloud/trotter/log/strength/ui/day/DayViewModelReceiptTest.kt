package cloud.trotter.log.strength.ui.day

import cloud.trotter.log.strength.data.storeTestRows
import cloud.trotter.log.strength.data.convertTestPlans
import kotlinx.coroutines.flow.map
import android.content.Context
import android.os.Parcel
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import cloud.trotter.log.strength.R
import cloud.trotter.log.strength.rest.PhoneRestController
import cloud.trotter.log.strength.rest.NoOpRestRuntime
import cloud.trotter.log.strength.data.TrackerRepository
import cloud.trotter.log.strength.data.db.StrengthDatabase
import cloud.trotter.log.strength.data.db.dao.SessionDao
import cloud.trotter.log.strength.data.db.dao.PersonalRecordRow
import cloud.trotter.log.strength.data.serialization.SetJson
import cloud.trotter.log.strength.data.serialization.SessionPlanJson
import cloud.trotter.log.strength.data.serialization.SlotRuleJson
import cloud.trotter.log.strength.domain.progression.NextTimeMode
import cloud.trotter.log.strength.ui.day.receipt.ReceiptLines
import cloud.trotter.log.strength.ui.day.receipt.ReceiptMutation
import cloud.trotter.log.strength.data.db.entity.WorkoutSessionEntity
import cloud.trotter.log.strength.data.db.entity.CardioSessionEntity
import cloud.trotter.log.strength.domain.model.CardioMode
import cloud.trotter.log.strength.domain.model.CardioBlock
import cloud.trotter.log.strength.data.serialization.CardioBlockJson
import cloud.trotter.log.strength.domain.model.CardioActivity
import cloud.trotter.log.strength.domain.model.CardioRole
import cloud.trotter.log.strength.domain.model.CardioSetResult
import cloud.trotter.log.strength.domain.model.CardioSetSpec
import cloud.trotter.log.strength.domain.model.CardioSetStatus
import cloud.trotter.log.strength.domain.model.ReadingSource
import cloud.trotter.log.strength.domain.model.DistanceSource
import cloud.trotter.log.strength.domain.model.Measure
import cloud.trotter.log.strength.domain.records.PrKind
import cloud.trotter.log.strength.ui.day.receipt.FinishAttempt
import cloud.trotter.log.strength.ui.day.receipt.NoteSave
import cloud.trotter.log.strength.ui.day.receipt.ReceiptRow
import cloud.trotter.log.strength.ui.day.receipt.ReceiptNextTime
import cloud.trotter.log.strength.ui.day.receipt.ReceiptNextTimes
import cloud.trotter.log.strength.ui.day.receipt.WorkoutReceipt
import cloud.trotter.log.strength.ui.day.receipt.isPartial
import cloud.trotter.log.strength.ui.day.receipt.loggedSetCount
import cloud.trotter.log.strength.data.db.entity.Slot
import cloud.trotter.log.strength.data.prefs.SettingsStore
import cloud.trotter.log.strength.domain.glance.DayProgress
import cloud.trotter.log.strength.domain.units.WeightUnit
import cloud.trotter.log.strength.sync.WatchSnapshotBuilder
import cloud.trotter.log.strength.domain.model.Program
import cloud.trotter.log.strength.domain.model.LoggedSet
import cloud.trotter.log.strength.domain.model.SetKind
import cloud.trotter.log.strength.domain.model.ProgramDay
import cloud.trotter.log.strength.domain.model.ProgramDayKind
import cloud.trotter.log.strength.domain.model.ProgramExercise
import cloud.trotter.log.strength.domain.model.PrescribedTarget
import cloud.trotter.log.strength.domain.model.Prescription
import cloud.trotter.log.strength.domain.model.SupersetPartner
import cloud.trotter.log.strength.domain.model.SetStructure
import cloud.trotter.log.strength.transfer.health.SessionPublisher
import cloud.trotter.log.strength.ui.log.share.ShareCardService
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The session receipt's *wiring*: that it comes off the DONE-advance
 * transition, reports the committed session and survives process death until
 * dismissed. [cloud.trotter.log.strength.ui.day.receipt.ReceiptBuilderTest] pins the rows.
 *
 * The SHARE render itself is not re-tested here. [ShareCardService] hops onto
 * `Dispatchers.IO` for the Canvas work, which this class's virtual-time
 * dispatcher cannot observe, and
 * [cloud.trotter.log.strength.ui.log.share.ShareCardServiceTest] already pins
 * that end of the path for a session id. What matters here is the handle: the
 * receipt names the id of the row [TrackerRepository.finishRun] wrote, and
 * that is the only thing this screen ever hands the share service.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DayViewModelReceiptTest {

    private val dispatcher = StandardTestDispatcher()
    private lateinit var db: StrengthDatabase
    private lateinit var settings: SettingsStore
    private lateinit var repo: TrackerRepository
    private lateinit var shareCardService: ShareCardService
    private lateinit var storeScope: CoroutineScope
    private var finishBaselineReads = 0
    private var sessionByRunIdCalls = 0
    private var failSessionByRunId = false
    private var failPersonalRecords = false
    private var failSetNote = false
    private var failSessionById = false
    private var beforeSessionsRead: (() -> Unit)? = null
    private var beforeFinishBaseline: (suspend () -> Unit)? = null
    private val published = mutableListOf<Long>()
    private val publisher = object : SessionPublisher by SessionPublisher.NoOp {
        override suspend fun publish(sessionId: Long) { published += sessionId }
    }
    private val vms = mutableListOf<DayViewModel>()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, StrengthDatabase::class.java)
            .allowMainThreadQueries()
            .setQueryCoroutineContext(dispatcher)
            .build()
        storeScope = CoroutineScope(dispatcher + Job())
        val dataStore = PreferenceDataStoreFactory.create(scope = storeScope) {
            File.createTempFile("receipt-vm-settings", ".preferences_pb")
        }
        settings = SettingsStore(dataStore)
        repo = TrackerRepository(
            db = db,
            programDao = db.programDao(),
            sessionDao = object : SessionDao by db.sessionDao() {
                override fun observeSessions() = db.sessionDao().observeSessions()
                    .onStart { beforeSessionsRead?.invoke() }

                override suspend fun sessionByRunId(runId: String): WorkoutSessionEntity? {
                    sessionByRunIdCalls++
                    check(!failSessionByRunId)
                    return db.sessionDao().sessionByRunId(runId)
                }

                override suspend fun personalRecordRows(exerciseIds: List<String>, excludedRunId: String?): List<PersonalRecordRow> {
                    if (excludedRunId != null) {
                        finishBaselineReads++
                        beforeFinishBaseline?.also { beforeFinishBaseline = null }?.invoke()
                    }
                    check(!failPersonalRecords || excludedRunId == null)
                    return db.sessionDao().personalRecordRows(exerciseIds, excludedRunId)
                }

                override suspend fun sessionById(sessionId: Long): WorkoutSessionEntity? {
                    check(!failSessionById)
                    return db.sessionDao().sessionById(sessionId)
                }

                override suspend fun setNote(sessionId: Long, note: String) {
                    check(!failSetNote)
                    db.sessionDao().setNote(sessionId, note)
                }
            },
            customExerciseDao = db.customExerciseDao(),
            settings = settings,
        )
        shareCardService = ShareCardService(context, repo.history, settings = settings)
    }

    @After
    fun tearDown() {
        vms.forEach { it.viewModelScope.cancel() }
        db.close()
        storeScope.cancel()
        Dispatchers.resetMain()
    }

    // --- it appears, once, and only on a DONE --------------------------------

    @Test
    fun `the receipt offers the increase after a full success`() = runVmTest {
        insertStraightProgram()
        val vm = newCollectingViewModel()
        advanceUntilIdle()
        completeOneSession(vm)

        val receipt = requireNotNull(vm.finisher.receipt.value)
        assertEquals(ReceiptNextTimes(receipt.sessionId, listOf(ReceiptNextTime(0, 0, 220.0, 215.0))),
            nextTimes(vm))
        val plan = repo.plans.selectedPlanIdFlow.first()!!
        assertEquals(List(3) { LoggedSet(215.0, 5, SetKind.WORK) }, SetJson.decodeSets(
            db.programDao().logForSlot(plan, "A", slotId(), Slot.MAIN)!!.setsJson))
        assertTrue(vm.finisher.dismissReceipt())
        vm.selectDay("A")
        advanceUntilIdle()
        assertEquals(NextTime.Offer("+5 lb", false, 220.0, 215.0), vm.uiState.value.exercises.single().nextTime)
    }

    @Test
    fun `the receipt says repeat after a miss`() = runVmTest {
        insertStraightProgram()
        val vm = newCollectingViewModel()
        advanceUntilIdle()
        vm.startWorkout()
        advanceUntilIdle()
        vm.toggleDone(slotId(), 0, checked = true, slot = Slot.MAIN)
        advanceUntilIdle()
        vm.changeReps(slotId(), Slot.MAIN, 2, 4)
        advanceUntilIdle()
        completeOneSession(vm)

        val receipt = requireNotNull(vm.finisher.receipt.value)
        assertEquals(ReceiptNextTimes(receipt.sessionId, listOf(ReceiptNextTime(0, 0, null, 215.0))),
            nextTimes(vm))
    }

    @Test
    fun `the receipt shows the stored load with strength offers off`() = runVmTest {
        insertStraightProgram()
        val planId = requireNotNull(repo.plans.selectedPlanIdFlow.first())
        repo.plans.setOfferStrengthIncreases(planId, false)
        val vm = newCollectingViewModel()
        advanceUntilIdle()
        completeOneSession(vm)

        val receipt = requireNotNull(vm.finisher.receipt.value)
        assertEquals(ReceiptNextTimes(receipt.sessionId, listOf(ReceiptNextTime(0, 0, null, 215.0))), nextTimes(vm))
        val why = requireNotNull(vm.finisher.progressState.value.lifts.single().whyLine)
        assertEquals("Bench Press 220 lb offered (+5): all sets made.", ReceiptLines.why("Bench Press", why,
            WeightUnit.LB, ApplicationProvider.getApplicationContext<Context>().resources))
    }

    @Test
    fun `the receipt shows the stored load after a miss with strength offers off`() = runVmTest {
        insertStraightProgram()
        val planId = requireNotNull(repo.plans.selectedPlanIdFlow.first())
        repo.plans.setOfferStrengthIncreases(planId, false)
        val vm = newCollectingViewModel()
        advanceUntilIdle()
        vm.startWorkout()
        advanceUntilIdle()
        vm.toggleDone(slotId(), 0, checked = true, slot = Slot.MAIN)
        advanceUntilIdle()
        vm.changeReps(slotId(), Slot.MAIN, 2, 4)
        advanceUntilIdle()
        completeOneSession(vm)

        val receipt = requireNotNull(vm.finisher.receipt.value)
        assertEquals(ReceiptNextTimes(receipt.sessionId, listOf(ReceiptNextTime(0, 0, null, 215.0))), nextTimes(vm))
    }

    @Test
    fun `receipt strength offers use the finishing plan switch`() = runVmTest {
        insertStraightProgram()
        val owner = requireNotNull(repo.plans.selectedPlanIdFlow.first())
        val other = repo.plans.duplicatePlan(owner, "Preferred")
        repo.plans.setOfferStrengthIncreases(other, false)
        val vm = newCollectingViewModel()
        advanceUntilIdle()
        tickWholeTrack(vm)
        advanceUntilIdle()
        repo.plans.useOnly(other)
        advanceUntilIdle()
        assertEquals(owner, vm.uiState.value.planId)
        vm.completeDay()
        advanceUntilIdle()

        val receipt = requireNotNull(vm.finisher.receipt.value)
        assertEquals(owner, repo.history.session(receipt.sessionId)?.planId)
        assertEquals(ReceiptNextTimes(receipt.sessionId, listOf(ReceiptNextTime(0, 0, 220.0, 215.0))),
            nextTimes(vm))
    }

    @Test
    fun `a mixed session gives each lift its own next time`() = runVmTest {
        repo.replaceProgram(Program(listOf(ProgramDay("A", "Mixed", "", listOf(
            ProgramExercise("bb_bench", targetSets = 3, prescription = Prescription(PrescribedTarget.LoadReps(215.0, 5)),
                superset = SupersetPartner("bb_row")),
            ProgramExercise("ez_curl", targetSets = 3, prescription = Prescription(PrescribedTarget.LoadReps(35.0, 5))),
            ProgramExercise("ohp", targetSets = 3, prescription = Prescription(PrescribedTarget.LoadReps(95.0, 5)),
                keepToPlan = true),
        )))))
        db.convertTestPlans(settings)
        val planId = requireNotNull(repo.plans.selectedPlanIdFlow.first())
        val slots = repo.plans.slotsFor(planId, "A").first()
        db.storeTestRows("A", slots[0].programExerciseId, Slot.SS, List(3) { LoggedSet(45.0, 10, SetKind.WORK) })
        val vm = newCollectingViewModel()
        advanceUntilIdle()
        val id = slots[0].programExerciseId
        vm.startWorkout()
        advanceUntilIdle()
        vm.toggleDone(id, 0, checked = true, slot = Slot.MAIN)
        advanceUntilIdle()
        val tracks = repo.dayTracksFlow(planId, "A").first().tracks
        val partner = tracks.first { it.programExerciseId == id && it.slot == Slot.SS }.sets
        vm.changeReps(id, Slot.SS, partner.lastIndex, partner.last().reps - 1)
        advanceUntilIdle()
        for (track in tracks.filter { it.programExerciseId != slots[1].programExerciseId }) {
            track.sets.indices.forEach { vm.toggleDone(track.programExerciseId, it, checked = true, slot = track.slot) }
        }
        advanceUntilIdle()
        vm.skipLift(slots[1].programExerciseId, Slot.MAIN)
        advanceUntilIdle()
        vm.completeDay()
        advanceUntilIdle()

        val receipt = requireNotNull(vm.finisher.receipt.value)
        assertEquals(2, receipt.groups[0].rows.size)
        assertEquals(ReceiptRow.Shape.SKIPPED, receipt.groups[1].rows.single().shape)
        assertEquals(3, receipt.groups[2].rows.single().sets.size)
        assertEquals(ReceiptNextTimes(receipt.sessionId, listOf(
            ReceiptNextTime(0, 0, 220.0, 215.0), ReceiptNextTime(0, 1, null, partner.first().weightLb),
        )), nextTimes(vm))
    }

    @Test
    fun `dismissing the receipt clears next times`() = runVmTest {
        insertStraightProgram()
        val handle = SavedStateHandle()
        val vm = newViewModel(handle)
        advanceUntilIdle()
        completeOneSession(vm)
        assertNotNull(vm.finisher.receiptProgress.value)
        assertEquals(vm.finisher.receiptProgress.value,
            handle.get<cloud.trotter.log.strength.ui.day.receipt.ReceiptProgress>("day_receipt_progress"))
        assertNull(handle.get<ReceiptNextTimes>("day_receipt_next_times"))

        assertTrue(vm.finisher.dismissReceipt())

        assertNull(vm.finisher.receiptProgress.value)
        assertNull(handle.get<cloud.trotter.log.strength.ui.day.receipt.ReceiptProgress>("day_receipt_progress"))
    }

    @Test
    fun aGapInDayLettersKeepsTheCompletedDaysThirdAccentOnTheReceipt() = runVmTest {
        insertProgram()
        val program = repo.plans.programFlow.first()
        repo.replaceProgram(Program(listOf(program.days[0], program.days[1].copy(id = "C"))))
        val plan = repo.plans.selectedPlanIdFlow.first()!!
        val vm = newCollectingViewModel()
        vm.selectDay("C")
        advanceUntilIdle()
        val slot = repo.plans.slotsFor(plan, "C").first().single()
        val sets = repo.dayTracksFlow(plan, "C").first().tracks.single().sets
        vm.startWorkout()
        advanceUntilIdle()
        sets.indices.forEach { vm.toggleDone(slot.programExerciseId, it, checked = true, slot = Slot.MAIN) }
        advanceUntilIdle()
        vm.completeDay()
        advanceUntilIdle()

        assertEquals("C", vm.finisher.receipt.value?.dayId)
        assertEquals(2, vm.finisher.receipt.value?.dayIndex)
        assertEquals("A", repo.plans.suggestedDayFlow.first())
        assertEquals("Upper", vm.finisher.receipt.value?.dayTitle)
    }

    @Test
    fun finishingCWhileBIsSuggestedKeepsBAndReportsCOnTheReceipt() = runVmTest {
        insertProgram()
        val program = repo.plans.programFlow.first()
        repo.replaceProgram(Program(program.days + program.days[0].copy(id = "C", title = "Full body")))
        val plan = repo.plans.selectedPlanIdFlow.first()!!
        db.planDao().updateSuggestedDay(plan, "B")
        val vm = newCollectingViewModel()
        vm.selectDay("C")
        advanceUntilIdle()
        val slot = repo.plans.slotsFor(plan, "C").first().single()
        vm.startWorkout()
        advanceUntilIdle()
        vm.toggleDone(slot.programExerciseId, 0, checked = true, slot = Slot.MAIN)
        advanceUntilIdle()
        assertTrue(vm.uiState.value.doneSets > 0)

        vm.completeDay()
        advanceUntilIdle()

        val receipt = requireNotNull(vm.finisher.receipt.value)
        assertEquals("C", receipt.dayId)
        assertEquals("Full body", receipt.dayTitle)
        assertEquals(plan, repo.history.session(receipt.sessionId)?.planId)
        assertEquals("B", db.planDao().plan(plan)!!.suggestedDayId)
        assertEquals("B", repo.plans.suggestedDayFlow.first())
    }

    @Test
    fun finishingAPausedNonpreferredRunAdvancesItsOwnPlan() = runVmTest {
        insertProgram()
        val owner = repo.plans.selectedPlanIdFlow.first()!!
        val other = repo.plans.duplicatePlan(owner, "Preferred")
        val vm = newCollectingViewModel()
        advanceUntilIdle()
        val slot = repo.plans.slotsFor(owner, "A").first().first()
        vm.startWorkout()
        advanceUntilIdle()
        vm.toggleDone(slot.programExerciseId, 0, checked = true, slot = Slot.MAIN)
        advanceUntilIdle()
        repo.plans.useOnly(other)
        advanceUntilIdle()
        assertEquals(owner, vm.uiState.value.planId)
        vm.completeDay()
        advanceUntilIdle()
        assertEquals("B", db.planDao().plan(owner)!!.suggestedDayId)
        assertEquals("A", db.planDao().plan(other)!!.suggestedDayId)
        assertEquals("Lower", vm.finisher.receipt.value?.dayTitle)
        assertEquals(owner, repo.history.session(vm.finisher.receipt.value!!.sessionId)?.planId)
    }

    @Test
    fun completingStandaloneCardioProducesAReceiptWithoutAdvancingStrength() = runVmTest {
        insertProgram()
        val cardio = ProgramDay(
            id = "C", title = "Cardio + Core", emphasisLine = "",
            exercises = listOf(ProgramExercise("plank", targetSets = 2)), kind = ProgramDayKind.CARDIO,
        )
        repo.replaceProgram(Program(repo.plans.programFlow.first().days + cardio))
        db.planDao().updateSuggestedDay(repo.plans.selectedPlanIdFlow.first()!!, "B")
        val vm = newCollectingViewModel()
        vm.selectDay("C")
        advanceUntilIdle()
        val slot = repo.plans.daySlotsFlow("C").first().single()
        val rows = repo.dayTracksFlow(repo.plans.selectedPlanIdFlow.first()!!, "C").map { it.tracks }.first().single().sets
        vm.startWorkout()
        advanceUntilIdle()
        rows.indices.forEach { vm.toggleDone(slot.programExerciseId, it, checked = true, slot = Slot.MAIN) }
        advanceUntilIdle()
        val completedSetCount = vm.uiState.value.doneSets

        vm.completeDay()
        advanceUntilIdle()

        val receipt = vm.finisher.receipt.value!!
        assertEquals("C", receipt.dayId)
        assertEquals(2, completedSetCount)
        assertEquals(completedSetCount, receipt.groups.single().rows.single().sets.size)
        assertEquals("Cardio + Core", receipt.dayTitle)
        assertEquals("B", repo.plans.suggestedDayFlow.first())
        assertEquals("C", repo.history.session(receipt.sessionId)?.dayId)
        assertEquals(false, repo.dayTracksFlow(repo.plans.selectedPlanIdFlow.first()!!, "C").map { it.tracks }.first().single().sets.any { it.done })
    }

    @Test
    fun authoredStrengthCompletionSelectsTheFollowingCardioDay() = runVmTest {
        val planId = repo.plans.createAuthoredPlan("Mixed plan", "Lower")
        repo.plans.publishCreatedAuthoredPlan(planId)
        insertProgram()
        val days = repo.plans.programFlow.first().days
        repo.replaceProgram(Program(listOf(
            days[0],
            ProgramDay("B", "Cardio", "", emptyList(), cardioBlock = cardioFixture().block),
            days[1].copy(id = "C"),
        )))
        val vm = newViewModel()
        advanceUntilIdle()
        completeOneSession(vm)
        assertEquals("B", repo.plans.suggestedDayFlow.first())
        assertEquals("Lower", vm.finisher.receipt.value?.dayTitle)
    }

    @Test
    fun generatedStrengthCompletionSkipsTheFollowingStandaloneCardioDay() = runVmTest {
        insertProgram()
        val days = repo.plans.programFlow.first().days
        repo.replaceProgram(Program(listOf(
            days[0],
            ProgramDay("C1", "Cardio", "", listOf(ProgramExercise("plank", targetSets = 2)),
                kind = ProgramDayKind.CARDIO, cardioBlock = cardioFixture().block),
            days[1],
        )))
        val vm = newViewModel()
        advanceUntilIdle()
        completeOneSession(vm)
        assertEquals("B", repo.plans.suggestedDayFlow.first())
        assertEquals("Lower", vm.finisher.receipt.value?.dayTitle)
    }

    @Test
    fun thereIsNoReceiptUntilDoneCommitsSomething() = runVmTest {
        insertProgram()
        val vm = newViewModel()
        advanceUntilIdle()

        tickWholeTrack(vm)
        advanceUntilIdle()

        assertNull("a ticked-but-unfinished day is not a receipt", vm.finisher.receipt.value)
    }

    @Test
    fun reportsTheCompletedDayWhileTheRotationMovesToTheNext() = runVmTest {
        insertProgram()
        val vm = newViewModel()
        advanceUntilIdle()
        completeOneSession(vm)

        val receipt = vm.finisher.receipt.value
        assertNotNull(receipt)
        assertEquals("A", receipt!!.dayId)
        assertEquals(repo.history.session(receipt.sessionId)?.completedAt, receipt.completedAt)
        assertEquals("B", repo.plans.suggestedDayFlow.first())
        // The completed day's accent, not the one the pointer now stands on.
        assertEquals(0, receipt.dayIndex)
    }

    @Test
    fun midRunPrescriptionShrinkUsesTheEditedCountAndTargetsOnTheReceipt() = runVmTest {
        insertProgram()
        val id = slotId()
        repo.setStructure(id, SetStructure.STRAIGHT)
        repo.setPrescription(id, Prescription(PrescribedTarget.LoadReps(40.0, 5)), sets = 6)
        val vm = newCollectingViewModel()
        vm.selectDay("A")
        advanceUntilIdle()
        vm.startWorkout()
        advanceUntilIdle()
        vm.toggleDone(id, 0, checked = true, slot = Slot.MAIN)
        advanceUntilIdle()
        assertEquals(6, vm.uiState.value.totalSets)

        repo.setPrescription(id, Prescription(PrescribedTarget.LoadReps(30.0, 8)), sets = 3)
        advanceUntilIdle()
        assertEquals(1, vm.uiState.value.doneSets)
        assertEquals(3, vm.uiState.value.totalSets)
        vm.completeDay()
        advanceUntilIdle()

        val receipt = requireNotNull(vm.finisher.receipt.value)
        assertEquals(1, receipt.loggedSetCount)
        assertEquals(3, vm.finisher.receiptSetCounts.value?.total)
        val planned = requireNotNull(receipt.groups.single().rows.single().planned)
        assertEquals(3, planned.count)
        assertEquals(30.0, planned.weightLb, 0.0)
        assertEquals(8, planned.reps)
        val context = ApplicationProvider.getApplicationContext<Context>()
        assertEquals("1 of 3 sets logged", context.getString(
            R.string.operation_receipt_partial_count, receipt.loggedSetCount, vm.finisher.receiptSetCounts.value?.total,
        ))
        assertEquals(1, repo.history.sessionSummariesFlow.first().single().setCount)
        assertEquals(3, repo.history.sessionSets(receipt.sessionId).size)
        val snapshot = SessionPlanJson.decode(requireNotNull(repo.history.session(receipt.sessionId)?.plannedJson))
        assertEquals(3, snapshot.sumOf { requireNotNull(it.liveSetCount) })
    }

    @Test
    fun midRunPrescriptionGrowthUsesTheLiveCountAndNewPlannedTargets() = runVmTest {
        insertProgram()
        val id = slotId()
        repo.setStructure(id, SetStructure.STRAIGHT)
        repo.setPrescription(id, Prescription(PrescribedTarget.LoadReps(40.0, 5)), sets = 3)
        val vm = newCollectingViewModel()
        vm.selectDay("A")
        advanceUntilIdle()
        vm.startWorkout()
        advanceUntilIdle()
        vm.toggleDone(id, 0, checked = true, slot = Slot.MAIN)
        advanceUntilIdle()
        val captured = repo.openRunFlow.first()!!.tracks.single().planned

        repo.setPrescription(id, Prescription(PrescribedTarget.LoadReps(50.0, 6)), sets = 5)
        advanceUntilIdle()
        val live = vm.uiState.value
        assertEquals(1, live.doneSets)
        assertEquals(5, live.totalSets)
        assertEquals(captured, repo.openRunFlow.first()!!.tracks.single().planned)
        vm.completeDay()
        advanceUntilIdle()

        val receipt = requireNotNull(vm.finisher.receipt.value)
        assertEquals(live.doneSets, receipt.loggedSetCount)
        assertEquals(live.totalSets, vm.finisher.receiptSetCounts.value?.total)
        val planned = requireNotNull(receipt.groups.single().rows.single().planned)
        assertEquals(5, planned.count)
        assertEquals(50.0, planned.weightLb, 0.0)
        assertEquals(6, planned.reps)
        val context = ApplicationProvider.getApplicationContext<Context>()
        assertEquals("1 of 5 sets logged", context.getString(
            R.string.operation_receipt_partial_count, receipt.loggedSetCount, vm.finisher.receiptSetCounts.value?.total,
        ))
        val snapshot = SessionPlanJson.decode(requireNotNull(repo.history.session(receipt.sessionId)?.plannedJson))
        assertEquals(live.totalSets, snapshot.sumOf { requireNotNull(it.liveSetCount) })
    }

    @Test
    fun supersetSwapAfterRemovingARoundUsesOnlyTheLiveRowsAtFinish() = runVmTest {
        insertSupersetProgram()
        val vm = newCollectingViewModel()
        advanceUntilIdle()
        val id = slotId()
        vm.startWorkout()
        advanceUntilIdle()
        vm.toggleDone(id, 0, checked = true, slot = Slot.MAIN)
        vm.toggleDone(id, 0, checked = true, slot = Slot.SS)
        advanceUntilIdle()
        vm.removeSet(id, index = 2, isSuperset = true)
        advanceUntilIdle()
        val runId = requireNotNull(repo.openRunFlow.first()).id
        assertTrue(repo.swapInRun(runId, id, Slot.SS, "face_pull"))
        advanceUntilIdle()
        val live = vm.uiState.value
        val tracks = requireNotNull(repo.openRunFlow.first()).tracks
        assertEquals(listOf(2, 2), tracks.filterNot { it.retired || it.dropped }.map { it.sets.size })
        assertEquals(2, live.doneSets)
        assertEquals(4, live.totalSets)
        vm.completeDay()
        advanceUntilIdle()

        val receipt = requireNotNull(vm.finisher.receipt.value)
        assertEquals(live.doneSets, receipt.loggedSetCount)
        assertEquals(live.totalSets, vm.finisher.receiptSetCounts.value?.total)
        val snapshot = SessionPlanJson.decode(requireNotNull(repo.history.session(receipt.sessionId)?.plannedJson))
        assertEquals(live.totalSets, snapshot.sumOf { requireNotNull(it.liveSetCount) })
        assertEquals(0, snapshot.single { it.retired }.liveSetCount)
        assertEquals(2, repo.history.sessionSummariesFlow.first().single().setCount)
    }

    @Test
    fun droppingAPartnerExcludesItsRowsFromTheFinishCount() = runVmTest {
        insertSupersetProgram()
        val vm = newCollectingViewModel()
        advanceUntilIdle()
        val id = slotId()
        vm.startWorkout()
        advanceUntilIdle()
        vm.toggleDone(id, 0, checked = true, slot = Slot.MAIN)
        vm.toggleDone(id, 0, checked = true, slot = Slot.SS)
        advanceUntilIdle()
        vm.skipLift(id, Slot.SS)
        advanceUntilIdle()
        val live = vm.uiState.value
        assertEquals(2, live.doneSets)
        assertEquals(3, live.totalSets)
        vm.completeDay()
        advanceUntilIdle()

        val receipt = requireNotNull(vm.finisher.receipt.value)
        assertEquals(live.doneSets, receipt.loggedSetCount)
        assertEquals(live.totalSets, vm.finisher.receiptSetCounts.value?.total)
        val snapshot = SessionPlanJson.decode(requireNotNull(repo.history.session(receipt.sessionId)?.plannedJson))
        assertEquals(live.totalSets, snapshot.sumOf { requireNotNull(it.liveSetCount) })
        assertEquals(0, snapshot.single { it.slot == Slot.SS }.liveSetCount)
        assertEquals(2, repo.history.sessionSets(receipt.sessionId).count { it.done })
    }

    @Test
    fun prescriptionAtTheFinishCommitSuppliesThePlannedLine() = runVmTest {
        insertProgram()
        val id = slotId()
        repo.setStructure(id, SetStructure.STRAIGHT)
        repo.setPrescription(id, Prescription(PrescribedTarget.LoadReps(40.0, 5)), sets = 3)
        val vm = newCollectingViewModel()
        vm.selectDay("A")
        advanceUntilIdle()
        vm.startWorkout()
        advanceUntilIdle()
        vm.toggleDone(id, 0, checked = true, slot = Slot.MAIN)
        advanceUntilIdle()
        beforeFinishBaseline = {
            repo.setPrescription(id, Prescription(PrescribedTarget.LoadReps(30.0, 8)), sets = 2)
        }
        vm.completeDay()
        advanceUntilIdle()

        val receipt = requireNotNull(vm.finisher.receipt.value)
        val planned = requireNotNull(receipt.groups.single().rows.single().planned)
        assertEquals(2, planned.count)
        assertEquals(30.0, planned.weightLb, 0.0)
        assertEquals(8, planned.reps)
        assertEquals(2, vm.finisher.receiptSetCounts.value?.total)
    }

    @Test
    fun completingAnEditedPrescriptionKeepsTheReceiptComplete() = runVmTest {
        insertProgram()
        val id = slotId()
        repo.setStructure(id, SetStructure.STRAIGHT)
        repo.setPrescription(id, Prescription(PrescribedTarget.LoadReps(40.0, 5)), sets = 6)
        val vm = newCollectingViewModel()
        vm.selectDay("A")
        advanceUntilIdle()
        vm.startWorkout()
        advanceUntilIdle()
        vm.toggleDone(id, 0, checked = true, slot = Slot.MAIN)
        advanceUntilIdle()

        repo.setPrescription(id, Prescription(PrescribedTarget.LoadReps(30.0, 8)), sets = 3)
        advanceUntilIdle()
        tickWholeTrack(vm)
        advanceUntilIdle()
        assertEquals(3, vm.uiState.value.doneSets)
        assertEquals(3, vm.uiState.value.totalSets)
        vm.completeDay()
        advanceUntilIdle()

        val receipt = requireNotNull(vm.finisher.receipt.value)
        assertEquals(3, receipt.loggedSetCount)
        assertEquals(3, vm.finisher.receiptSetCounts.value?.total)
        assertFalse(receipt.isPartial)
    }

    @Test
    fun countsTheSetsTheSessionActuallyRecorded() = runVmTest {
        insertProgram()
        val vm = newViewModel()
        advanceUntilIdle()
        val rows = mainTrackRowCount()
        completeOneSession(vm)

        assertEquals(rows, vm.finisher.receipt.value?.groups?.single()?.rows?.single()?.sets?.size)
    }

    @Test
    fun aSupersetDaysReceiptCountsWhatTheHeaderCounted() = runVmTest {
        insertSupersetProgram()
        val vm = newCollectingViewModel()
        advanceUntilIdle()
        tickWholeTrack(vm)
        advanceUntilIdle()

        val headerCount = vm.uiState.value.doneSets
        val partnerRows = repo.dayTracksFlow(repo.plans.selectedPlanIdFlow.first()!!, "A").map { it.tracks }.first().first { it.slot == Slot.SS }.sets.size
        vm.completeDay()
        advanceUntilIdle()

        // The partner track really is populated, so this isn't passing by having
        // nothing to over-count.
        assertEquals(partnerRows * 2, headerCount)
        assertEquals(partnerRows, vm.finisher.receipt.value?.groups?.single()?.rounds)
        assertEquals(headerCount, vm.finisher.receiptSetCounts.value?.total)
        assertEquals(listOf(partnerRows, partnerRows), vm.finisher.receipt.value?.groups?.single()?.rows?.map { it.sets.size })
    }

    @Test
    fun partialSupersetRowsCountEachTrackOnTheWorkoutAndReceipt() = runVmTest {
        for ((mainDone, partnerDone) in listOf(true to false, false to true, true to true)) {
            repo.replaceProgram(Program(listOf(ProgramDay("A", "Arms", "", listOf(
                ProgramExercise("ez_curl", targetSets = 1, superset = SupersetPartner("rope_pushdown")),
            )))))
            val vm = newCollectingViewModel()
            advanceUntilIdle()
            val planId = requireNotNull(repo.plans.selectedPlanIdFlow.first())
            val id = repo.plans.slotsFor(planId, "A").first().single().programExerciseId
            vm.startWorkout()
            advanceUntilIdle()
            if (mainDone) vm.toggleDone(id, 0, true, Slot.MAIN)
            if (partnerDone) vm.toggleDone(id, 0, true, Slot.SS)
            advanceUntilIdle()
            val expectedDone = if (mainDone) 1 else 0
            val tracks = repo.dayTracksFlow(planId, "A").first()
            assertEquals(mainDone, tracks.tracks.first { it.slot == Slot.MAIN }.sets.single().done)
            assertEquals(partnerDone, tracks.tracks.first { it.slot == Slot.SS }.sets.single().done)
            assertEquals(listOf(mainDone, partnerDone).count { it }, vm.uiState.value.doneSets)
            assertEquals(2, vm.uiState.value.totalSets)
            assertEquals(0, tracks.retiredRoundsDone)
            val snapshot = requireNotNull(WatchSnapshotBuilder.build(
                day = repo.observeDay(planId, "A").first(),
                unit = WeightUnit.LB, revision = 1,
            ))
            assertEquals(DayProgress(expectedDone, 1), DayProgress.of(snapshot))
            vm.completeDay()
            advanceUntilIdle()
            val receipt = requireNotNull(vm.finisher.receipt.value)
            assertEquals(2, vm.finisher.receiptSetCounts.value?.total)
            val group = receipt.groups.single()
            assertEquals(expectedDone, group.rounds)
            assertEquals(listOf(if (mainDone) 1 else 0, if (partnerDone) 1 else 0), group.rows.map { it.sets.size })
            assertEquals(if (mainDone) ReceiptRow.Shape.STRAIGHT else ReceiptRow.Shape.SKIPPED, group.rows[0].shape)
            assertEquals(if (partnerDone) ReceiptRow.Shape.STRAIGHT else ReceiptRow.Shape.SKIPPED, group.rows[1].shape)
            val archived = repo.history.sessionSets(receipt.sessionId)
            assertEquals(1, archived.count { Slot.isRound(it.slot) })
            assertEquals(expectedDone, archived.count { Slot.isRound(it.slot) && it.done })
            vm.viewModelScope.cancel()
        }
    }

    /** The receipt's only live handle is the row the advance wrote — the same id
     *  the share path consumes. */
    @Test
    fun holdsTheIdOfTheSessionTheAdvanceJustWrote() = runVmTest {
        insertProgram()
        val vm = newViewModel()
        advanceUntilIdle()
        completeOneSession(vm)

        assertEquals(repo.history.sessionsFlow.first().single().id, vm.finisher.receipt.value?.sessionId)
    }

    /** One Finish captures the new top set inside the committed day's receipt. */
    @Test
    fun theReceiptCarriesTheNewTopSetFromTheCompletedDay() = runVmTest {
        insertProgram()
        val vm = newViewModel()
        advanceUntilIdle()
        completeOneSession(vm)
        vm.finisher.dismissReceipt()
        raiseTopSet(vm)
        completeOneSession(vm)

        val receipt = requireNotNull(vm.finisher.receipt.value)
        assertEquals("A", receipt.dayId)
        assertEquals(0, receipt.dayIndex)
        val row = receipt.groups.single().rows.single()
        val record = requireNotNull(row.record)
        assertEquals(PrKind.WEIGHT, record.kind)
        assertEquals(235.0, record.previous.weightLb, 0.0)
        assertEquals(245.0, row.sets[record.setIndex].weightLb, 0.0)
    }

    // --- and it goes ---------------------------------------------------------

    @Test
    fun dismissingItClearsIt() = runVmTest {
        insertProgram()
        val handle = SavedStateHandle()
        val vm = newViewModel(handle)
        advanceUntilIdle()
        completeOneSession(vm)
        assertNotNull(handle.get<WorkoutReceipt>("day_workout_receipt"))

        assertTrue(vm.finisher.dismissReceipt())

        assertNull(vm.finisher.receipt.value)
        assertNull(handle.get<WorkoutReceipt>("day_workout_receipt"))
    }

    /** A configuration change keeps the ViewModel, so it keeps the receipt. */
    @Test
    fun survivesAStateReEmission() = runVmTest {
        insertProgram()
        val vm = newViewModel()
        advanceUntilIdle()
        completeOneSession(vm)

        vm.selectDay("B")
        advanceUntilIdle()

        assertNotNull(vm.finisher.receipt.value)
    }

    /** Restoring a frozen receipt must not re-read history to derive its top sets. */
    @Test
    fun aNewViewModelKeepsTheReceiptAndRecordsWithoutReadingAFinishBaseline() = runVmTest {
        insertProgram()
        val handle = SavedStateHandle()
        val vm = newViewModel(handle)
        advanceUntilIdle()
        completeOneSession(vm)
        vm.finisher.dismissReceipt()
        raiseTopSet(vm)
        completeOneSession(vm)
        val receipt = requireNotNull(vm.finisher.receipt.value)
        val row = receipt.groups.single().rows.single()
        val record = requireNotNull(row.record)
        assertEquals(PrKind.WEIGHT, record.kind)
        assertEquals(235.0, record.previous.weightLb, 0.0)
        assertEquals(245.0, row.sets[record.setIndex].weightLb, 0.0)
        vm.viewModelScope.cancel()
        val readsAtFinish = finishBaselineReads
        assertTrue("the counter must observe Finish's history fetch", readsAtFinish > 0)

        val restored = newViewModel(handle)
        assertEquals(receipt, restored.finisher.receipt.value)
        advanceUntilIdle()

        assertEquals(receipt, restored.finisher.receipt.value)
        assertEquals("restore must not rebuild records", readsAtFinish, finishBaselineReads)
    }

    @Test
    fun theWholeReceiptSurvivesAParcelRoundTrip() = runVmTest {
        insertProgram()
        val handle = SavedStateHandle()
        val vm = newViewModel(handle)
        advanceUntilIdle()
        completeOneSession(vm)
        vm.finisher.dismissReceipt()
        raiseTopSet(vm)
        completeOneSession(vm)
        vm.finisher.setNote("  Felt steady  ")
        advanceUntilIdle()
        val receipt = requireNotNull(vm.finisher.receipt.value)
        assertEquals("Felt steady", receipt.note)
        assertEquals(NoteSave.Saved("Felt steady"), vm.finisher.noteSave.value)
        assertEquals(receipt.note, repo.history.session(receipt.sessionId)?.note)
        vm.viewModelScope.cancel()
        val parcel = Parcel.obtain()
        val restoredHandle = try {
            parcel.writeBundle(handle.savedStateProvider().saveState())
            parcel.setDataPosition(0)
            SavedStateHandle.createHandle(parcel.readBundle(WorkoutReceipt::class.java.classLoader), null)
        } finally {
            parcel.recycle()
        }

        val counts = vm.finisher.receiptSetCounts.value
        assertNotNull(counts)
        val readsAtFinish = finishBaselineReads
        val restored = newViewModel(restoredHandle)
        advanceUntilIdle()
        assertEquals(readsAtFinish, finishBaselineReads)
        assertEquals(receipt, restored.finisher.receipt.value)
        assertEquals(receipt, restoredHandle.get<WorkoutReceipt>("day_workout_receipt"))
        assertEquals(counts, restored.finisher.receiptSetCounts.value)
        restored.finisher.dismissReceipt()
        assertNull(restored.finisher.receiptSetCounts.value)
    }

    @Test
    fun aFailureAfterTheCommitPublishesAndTryAgainRecoversTheReceipt() = runVmTest {
        insertProgram()
        val vm = newViewModel()
        advanceUntilIdle()
        tickWholeTrack(vm)
        advanceUntilIdle()
        failSessionById = true
        vm.completeDay()
        advanceUntilIdle()
        assertTrue(requireNotNull(vm.finisher.attempt.value).failed)
        assertEquals(1, repo.history.sessionsFlow.first().size)
        assertEquals(1, published.size)
        failSessionById = false
        vm.finisher.retry()
        advanceUntilIdle()
        assertNull(vm.finisher.attempt.value)
        assertEquals(2, published.size)
        assertNotNull(vm.finisher.receipt.value)
        assertEquals(1, repo.history.sessionsFlow.first().size)
    }

    @Test
    fun anEditLandingBeforeTheCommitIsWhatTheReceiptShows() = runVmTest {
        insertProgram()
        val vm = newViewModel()
        advanceUntilIdle()
        tickWholeTrack(vm)
        advanceUntilIdle()
        // A watch edit lands between the finisher's read of the run and the commit; the watch's
        // applier does not take the phone's mutation lock.
        beforeFinishBaseline = {
            val run = requireNotNull(db.runDao().openRun())
            val track = db.runDao().tracks(run.id).first { it.slot == Slot.MAIN && !it.retired }
            val sets = SetJson.decodeSets(track.setsJson)
            val top = sets.indices.maxBy { sets[it].weightLb }
            val edited = sets.mapIndexed { i, set -> if (i == top) set.copy(weightLb = set.weightLb + 20.0) else set }
            db.runDao().updateTrack(track.copy(setsJson = SetJson.encodeSets(edited)))
        }
        vm.completeDay()
        advanceUntilIdle()
        val receipt = requireNotNull(vm.finisher.receipt.value)
        val committed = repo.history.sessionSets(receipt.sessionId).filter { it.done }.maxOf { it.weightLb }
        assertEquals(committed, receipt.groups.single().rows.single().sets.maxOf { it.weightLb }, 0.0)
    }

    @Test
    fun completeDaySavesTheAttemptThenTheReceiptAndClearsTheAttempt() = runVmTest {
        insertProgram()
        val handle = SavedStateHandle()
        val vm = newViewModel(handle)
        advanceUntilIdle()
        tickWholeTrack(vm)
        advanceUntilIdle()
        val attempts = mutableListOf<FinishAttempt?>()
        val collecting = backgroundScope.launch(kotlinx.coroutines.test.UnconfinedTestDispatcher(testScheduler)) {
            handle.getStateFlow<FinishAttempt?>("day_finish", null).collect { attempts += it }
        }
        vm.completeDay()
        advanceUntilIdle()
        assertTrue(attempts.any { it != null && !it.failed && it.dayTitle == "Lower" })
        assertNull(handle.get<FinishAttempt>("day_finish"))
        val receipt = requireNotNull(handle.get<WorkoutReceipt>("day_workout_receipt"))
        assertEquals(receipt, vm.finisher.receipt.value)
        assertEquals("Lower", receipt.dayTitle)
        assertEquals(repo.plans.plansFlow.first().single().name, receipt.planName)
        assertEquals(ReceiptRow.Shape.TOP_SET, receipt.groups.single().rows.single().shape)
        assertFalse(receipt.allPlansDone)
        assertEquals(listOf(receipt.sessionId), published)
        collecting.cancel()
    }

    @Test
    fun anUncommittedFailureCanRetryOnceWithoutLosingTheRunOrAdvancingThePlan() = runVmTest {
        insertProgram()
        val handle = SavedStateHandle()
        val vm = newViewModel(handle)
        advanceUntilIdle()
        tickWholeTrack(vm)
        advanceUntilIdle()
        val runId = requireNotNull(repo.openRunFlow.first()).id
        val suggested = repo.plans.suggestedDayFlow.first()
        failSessionByRunId = true
        vm.completeDay()
        advanceUntilIdle()
        assertTrue(requireNotNull(handle.get<FinishAttempt>("day_finish")).failed)
        assertEquals(runId, repo.openRunFlow.first()?.id)
        assertEquals(suggested, repo.plans.suggestedDayFlow.first())
        assertTrue(repo.history.sessionsFlow.first().isEmpty())
        assertNull(vm.finisher.receipt.value)
        assertTrue(published.isEmpty())
        failSessionByRunId = false
        val calls = sessionByRunIdCalls
        vm.finisher.retry()
        vm.finisher.retry()
        advanceUntilIdle()
        assertEquals(calls + 1, sessionByRunIdCalls)
        val receipt = requireNotNull(vm.finisher.receipt.value)
        assertEquals(1, repo.history.sessionsFlow.first().size)
        assertEquals(listOf(receipt.sessionId), published)
        assertNull(handle.get<FinishAttempt>("day_finish"))
    }

    @Test
    fun aBaselineFailureStillFinishesWithoutClaimingRecords() = runVmTest {
        insertProgram()
        val vm = newViewModel()
        advanceUntilIdle()
        completeOneSession(vm)
        vm.finisher.dismissReceipt()
        raiseTopSet(vm)
        tickWholeTrack(vm)
        advanceUntilIdle()
        failPersonalRecords = true
        vm.completeDay()
        advanceUntilIdle()
        val receipt = requireNotNull(vm.finisher.receipt.value)
        assertFalse(receipt.recordsChecked)
        assertTrue(receipt.groups.flatMap { it.rows }.none { it.record != null })
        assertEquals(245.0, receipt.groups.single().rows.single().sets.maxOf { it.weightLb }, 0.0)
        assertEquals(2, repo.history.sessionsFlow.first().size)
        assertEquals(2, published.size)
    }

    @Test
    fun anUnfailedAttemptResumesTheOpenRunWithoutATap() = runVmTest {
        insertProgram()
        val first = newViewModel()
        advanceUntilIdle()
        tickWholeTrack(first)
        advanceUntilIdle()
        val run = requireNotNull(repo.openRunFlow.first())
        val attempt = FinishAttempt(run.id, requireNotNull(run.planId), "A", 0, "Lower", run.planName.orEmpty(), run.startedAt, 100L)
        first.viewModelScope.cancel()
        val calls = sessionByRunIdCalls
        val handle = SavedStateHandle(mapOf("day_finish" to attempt))
        val restored = newViewModel(handle)
        advanceUntilIdle()
        assertNotNull(restored.finisher.receipt.value)
        assertNull(handle.get<FinishAttempt>("day_finish"))
        assertEquals(calls + 1, sessionByRunIdCalls)
        assertEquals(1, repo.history.sessionsFlow.first().size)
        assertEquals(1, published.size)
    }

    @Test
    fun aFailedAttemptRestoresWithoutRetryingUntilAsked() = runVmTest {
        insertProgram()
        val handle = SavedStateHandle()
        val first = newViewModel(handle)
        advanceUntilIdle()
        tickWholeTrack(first)
        advanceUntilIdle()
        failSessionByRunId = true
        first.completeDay()
        advanceUntilIdle()
        val failed = requireNotNull(first.finisher.attempt.value)
        assertTrue(failed.failed)
        first.viewModelScope.cancel()
        failSessionByRunId = false
        val calls = sessionByRunIdCalls
        val restored = newViewModel(handle)
        advanceUntilIdle()
        assertEquals(failed, restored.finisher.attempt.value)
        assertEquals(calls, sessionByRunIdCalls)
        assertEquals(failed.runId, repo.openRunFlow.first()?.id)
        assertTrue(published.isEmpty())
        restored.finisher.retry()
        advanceUntilIdle()
        assertNotNull(restored.finisher.receipt.value)
        assertEquals(1, repo.history.sessionsFlow.first().size)
        assertEquals(1, published.size)
    }

    @Test
    fun anAttemptRestoredAfterCommitRecoversTheReceiptWithoutFinishingAgain() = runVmTest {
        insertProgram()
        val first = newViewModel()
        advanceUntilIdle()
        tickWholeTrack(first)
        advanceUntilIdle()
        val run = requireNotNull(repo.openRunFlow.first())
        val attempt = FinishAttempt(run.id, requireNotNull(run.planId), "A", 0, "Lower", run.planName.orEmpty(), run.startedAt, 100L)
        repo.finishRun(run.id)
        first.viewModelScope.cancel()
        val handle = SavedStateHandle(mapOf("day_finish" to attempt))
        val restored = newViewModel(handle)
        advanceUntilIdle()
        assertNull(handle.get<FinishAttempt>("day_finish"))
        assertNotNull(restored.finisher.receipt.value)
        assertEquals(1, repo.history.sessionsFlow.first().size)
        assertEquals(listOf(restored.finisher.receipt.value!!.sessionId), published)
    }

    @Test
    fun backToWorkoutClearsOnlyTheFailedAttemptAndKeepsTheSelectedPage() = runVmTest {
        insertProgram()
        val handle = SavedStateHandle()
        val vm = newViewModel(handle)
        advanceUntilIdle()
        tickWholeTrack(vm)
        advanceUntilIdle()
        val runId = repo.openRunFlow.first()?.id
        val selected = "exercise:${slotId()}"
        handle["selectedPageKey"] = selected
        failSessionByRunId = true
        vm.completeDay()
        advanceUntilIdle()
        assertTrue(requireNotNull(vm.finisher.attempt.value).failed)
        vm.finisher.backToWorkout()
        assertNull(handle.get<FinishAttempt>("day_finish"))
        assertEquals(selected, handle.get<String>("selectedPageKey"))
        assertEquals(runId, repo.openRunFlow.first()?.id)
    }

    @Test
    fun aFailedNoteWriteKeepsTheReceiptAndDatabaseNote() = runVmTest {
        insertProgram()
        val vm = newViewModel()
        advanceUntilIdle()
        completeOneSession(vm)
        vm.finisher.setNote("Saved note")
        advanceUntilIdle()
        val receipt = requireNotNull(vm.finisher.receipt.value)
        failSetNote = true
        vm.finisher.setNote("  Felt steady  ")
        advanceUntilIdle()
        assertEquals(NoteSave.Failed("Felt steady"), vm.finisher.noteSave.value)
        assertEquals(receipt, vm.finisher.receipt.value)
        assertEquals("Saved note", repo.history.session(receipt.sessionId)?.note)
        failSetNote = false
        vm.finisher.setNote("  Felt steady  ")
        advanceUntilIdle()
        assertEquals(NoteSave.Saved("Felt steady"), vm.finisher.noteSave.value)
        assertEquals("Felt steady", vm.finisher.receipt.value?.note)
        assertEquals("Felt steady", repo.history.session(receipt.sessionId)?.note)
        vm.finisher.noteSaveHandled()
        assertNull(vm.finisher.noteSave.value)
    }

    @Test
    fun aLegacyReceiptBundleIsDroppedAtStartup() = runVmTest {
        val legacy = SessionReceipt(1L, "A", 0, 100L, 1, 1, 1, 1,
            listOf(CascadeLift("Squat", "235", "245")), null)
        val record = CardioReceiptRecord("400 m", "1:38", "1:42", PrKind.FASTER)
        val handle = SavedStateHandle(mapOf(
            "day_session_receipt" to legacy,
            "day_cardio_receipt_records:x" to arrayListOf(record),
            "day_cardio_receipt_records:y" to arrayListOf(record),
            "day_cardio_standing_bests:x" to arrayListOf(SavedCardioBest("frozen", 102, null)),
        ))
        val vm = newViewModel(handle)
        assertFalse(handle.keys().contains("day_session_receipt"))
        assertFalse(handle.keys().any { it.startsWith("day_cardio_receipt_records:") })
        assertTrue(handle.keys().contains("day_cardio_standing_bests:x"))
        assertNull(vm.finisher.receipt.value)
        advanceUntilIdle()
    }

    @Test
    fun allPlansDoneIncludesStrengthAndCardioOnTheSameCivilDay() = runVmTest {
        insertProgram()
        val strength = requireNotNull(repo.plans.selectedPlanIdFlow.first())
        val cardio = repo.plans.createAuthoredPlan("Running", "Run")
        val spec = CardioSetSpec(CardioRole.WORK, Measure.Time(10))
        val block = CardioBlock(CardioActivity.ROAD_RUN, listOf(spec))
        repo.setCardioBlock(cardio, "A", block)
        repo.plans.useAlongside(cardio)
        val vm = newViewModel()
        advanceUntilIdle()
        completeOneSession(vm)
        assertFalse(requireNotNull(vm.finisher.receipt.value).allPlansDone)
        val completedAt = requireNotNull(vm.finisher.receipt.value).completedAt
        val json = CardioBlockJson.encode(block)
        val run = CardioRun(cardio, "Running", "A", 0, json, json, true, null,
            results = listOf(SavedCardioResult(CardioSetStatus.FINISHED.name, 10)),
            startedAt = completedAt - 10_000, completedAt = completedAt)
        val session = CardioSessionEntity(
            dayId = run.dayId, mode = CardioMode.OUTDOOR_RUN.name, hard = false, label = "Run",
            startedAt = completedAt - 10_000, completedAt = completedAt, seconds = 10, stepsCompleted = 1,
            planId = cardio, planName = "Running", activity = CardioActivity.ROAD_RUN.name,
        )
        val result = CardioSetResult(spec, CardioSetStatus.FINISHED, 10)
        val id = repo.logCardioSession(session, listOf(result), finishDay = true)
        vm.finisher.showCardio(run.copy(sessionId = id), emptyMap(), session.copy(id = id))
        assertTrue(requireNotNull(vm.finisher.receipt.value).allPlansDone)
        assertEquals(strength, repo.history.sessionsFlow.first().single().planId)
        assertEquals(cardio, repo.history.cardioSessionsFlow.first().single().planId)
    }

    @Test
    fun aCardioReceiptWaitsIfExecutionTakesOwnershipDuringItsReads() = runVmTest {
        insertProgram()
        val planId = requireNotNull(repo.plans.selectedPlanIdFlow.first())
        val block = requireNotNull(cardioFixture().block)
        val json = CardioBlockJson.encode(block)
        val now = System.currentTimeMillis()
        val session = CardioSessionEntity(
            dayId = "A", mode = CardioMode.OUTDOOR_RUN.name, hard = false, label = "Run",
            startedAt = now - 300_000, completedAt = now, seconds = 300, stepsCompleted = 1,
            planId = planId, activity = block.activity.name,
        )
        val id = repo.logCardioSession(session,
            listOf(CardioSetResult(block.sets.single(), CardioSetStatus.FINISHED, 300)))
        val run = CardioRun(planId, "Plan", "A", 0, json, json, true, null,
            results = listOf(SavedCardioResult(CardioSetStatus.FINISHED.name, 300)),
            startedAt = session.startedAt, completedAt = now, sessionId = id)
        var executionActive = false
        val finisher = SessionFinisher(
            repo, repo.history, repo.plans, repo.catalog, settings, publisher, shareCardService,
            SavedStateHandle(), backgroundScope, kotlinx.coroutines.sync.Mutex(), java.time.Clock.systemUTC(),
            afterCommit = { _, _ -> }, executionActive = { executionActive },
            loggedCardio = { _, _ -> null },
        )
        beforeSessionsRead = { executionActive = true }
        finisher.showCardio(run, emptyMap(), session.copy(id = id))
        assertNull(finisher.receipt.value)
        beforeSessionsRead = null
        executionActive = false
        finisher.showCardio(run, emptyMap(), session.copy(id = id))
        assertEquals(id, finisher.receipt.value?.sessionId)
    }

    // --- share ---------------------------------------------------------------

    @Test
    fun shareIsANoOpWithNoReceiptToShare() = runVmTest {
        insertProgram()
        val vm = newViewModel()
        advanceUntilIdle()

        vm.finisher.share()
        advanceUntilIdle()

        assertNull(vm.finisher.pendingShare.value)
    }

    // --- fixtures ------------------------------------------------------------

    private fun runVmTest(block: suspend TestScope.() -> Unit) = runTest(dispatcher) { block() }

    private fun newViewModel(handle: SavedStateHandle = SavedStateHandle(), cardioClock: CardioClock = FixedCardioClock()): DayViewModel =
        DayViewModel(repo, publisher, shareCardService, handle, cardioClock, InertCardioAlarm, PhoneRestController(settings, NoOpRestRuntime), settings = settings, plans = repo.plans, catalog = repo.catalog, historyRepository = repo.history).also { vms += it }

    /** [DayViewModel.uiState] is `WhileSubscribed`: without a live collector its
     *  `.value` never leaves the initial state, and the header count would read
     *  zero no matter what was ticked. */
    private fun newCollectingViewModel(cardioClock: CardioClock = FixedCardioClock()): DayViewModel =
        newViewModel(cardioClock = cardioClock).also { vm -> vm.viewModelScope.launch { vm.uiState.collect {} } }

    private suspend fun insertProgram() {
        repo.replaceProgram(
            Program(
                listOf(
                    ProgramDay(
                        id = "A",
                        title = "Lower",
                        emphasisLine = "",
                        exercises = listOf(ProgramExercise("bb_back_squat", structure = SetStructure.RAMP_TOP_BACKOFF, targetSets = 6)),
                    ),
                    ProgramDay(
                        id = "B",
                        title = "Upper",
                        emphasisLine = "",
                        exercises = listOf(ProgramExercise("bb_bench", structure = SetStructure.RAMP_TOP_BACKOFF, targetSets = 6)),
                    ),
                ),
            ),
        )
        db.storeTestRows("A", slotId(), Slot.MAIN, listOf(
            LoggedSet(130.0, 5, SetKind.RAMP), LoggedSet(165.0, 5, SetKind.RAMP),
            LoggedSet(190.0, 5, SetKind.RAMP), LoggedSet(210.0, 3, SetKind.RAMP),
            LoggedSet(235.0, 5, SetKind.TOP), LoggedSet(175.0, 8, SetKind.BACKOFF),
        ))
    }

    private suspend fun insertStraightProgram() {
        insertProgram()
        val id = slotId()
        repo.setStructure(id, SetStructure.STRAIGHT)
        repo.setPrescription(id, Prescription(PrescribedTarget.LoadReps(215.0, 5)), sets = 3)
    }

    /** One day with one superset slot. */
    private suspend fun insertSupersetProgram() {
        repo.replaceProgram(
            Program(
                listOf(
                    ProgramDay(
                        id = "A",
                        title = "Arms",
                        emphasisLine = "",
                        exercises = listOf(
                            ProgramExercise("ez_curl", targetSets = 3, superset = SupersetPartner("rope_pushdown")),
                        ),
                    ),
                    ProgramDay(
                        id = "B",
                        title = "Upper",
                        emphasisLine = "",
                        exercises = listOf(ProgramExercise("bb_bench", structure = SetStructure.RAMP_TOP_BACKOFF, targetSets = 6)),
                    ),
                ),
            ),
        )
    }

    private suspend fun slotId(): Long = repo.plans.daySlotsFlow("A").first().first().programExerciseId

    private suspend fun mainTrack() = repo.dayTracksFlow(repo.plans.selectedPlanIdFlow.first()!!, "A").map { it.tracks }.first().first { it.slot == Slot.MAIN }.sets

    private suspend fun mainTrackRowCount(): Int = mainTrack().size

    private suspend fun TestScope.tickWholeTrack(vm: DayViewModel) {
        if (repo.openRunFlow.first()?.draft != false) {
            vm.startWorkout()
            advanceUntilIdle()
        }
        val id = slotId()
        val hasPartner = repo.dayTracksFlow(repo.plans.selectedPlanIdFlow.first()!!, "A").map { it.tracks }.first().any { it.slot == Slot.SS }
        repeat(mainTrackRowCount()) { index ->
            vm.toggleDone(id, index, checked = true, slot = Slot.MAIN)
            if (hasPartner) vm.toggleDone(id, index, checked = true, slot = Slot.SS)
        }
    }

    /** Edits the TOP set up a notch so the next advance records a new all-time
     *  high, which is what the cascade needs to fire. */
    private suspend fun TestScope.raiseTopSet(vm: DayViewModel) {
        vm.selectDay("A")
        advanceUntilIdle()
        val sets = mainTrack()
        val topIndex = sets.indices.maxByOrNull { sets[it].weightLb }!!
        vm.changeWeight(slotId(), Slot.MAIN, topIndex, sets[topIndex].weightLb + 10.0)
        advanceUntilIdle()
    }

    private suspend fun TestScope.completeOneSession(vm: DayViewModel) {
        vm.selectDay("A")
        advanceUntilIdle()
        tickWholeTrack(vm)
        advanceUntilIdle()
        vm.completeDay()
        advanceUntilIdle()
    }
    @Test fun `an authored plan applies the increase and shows it applied`() = runVmTest {
        val plan = repo.plans.createAuthoredPlan("Mine", "Bench")
        repo.plans.publishCreatedAuthoredPlan(plan)
        repo.addExercise("A", ProgramExercise("bb_bench", targetSets = 3,
            prescription = Prescription(PrescribedTarget.LoadReps(215.0, 5))))
        val vm = newCollectingViewModel()
        advanceUntilIdle()
        completeOneSession(vm)
        val receipt = requireNotNull(vm.finisher.receipt.value)
        assertEquals(ReceiptNextTimes(receipt.sessionId, listOf(ReceiptNextTime(0, 0, 220.0, 215.0))),
            nextTimes(vm))
        assertEquals(List(3) { LoggedSet(220.0, 5, SetKind.WORK) }, SetJson.decodeSets(
            db.programDao().logForSlot(plan, "A", slotId(), Slot.MAIN)!!.setsJson))
        assertTrue(vm.finisher.dismissReceipt())
        advanceUntilIdle()
        assertEquals(NextTime.Offer("+5 lb", true, 220.0, 215.0), vm.uiState.value.exercises.single().nextTime)
    }

    @Test fun `a partner offer reaches the receipt and the partner chip`() = runVmTest {
        repo.replaceProgram(Program(listOf(ProgramDay("A", "Day A", "", listOf(
            ProgramExercise("bb_bench", targetSets = 3, prescription = Prescription(PrescribedTarget.LoadReps(215.0, 5)),
                superset = SupersetPartner("cable_row")),
        )))))
        db.convertTestPlans(settings)
        val plan = requireNotNull(repo.plans.selectedPlanIdFlow.first())
        val id = slotId()
        val rows = List(3) { LoggedSet(120.0, 10, SetKind.WORK) }
        db.storeTestRows("A", id, Slot.SS, rows)
        val vm = newCollectingViewModel()
        advanceUntilIdle()
        vm.startWorkout()
        advanceUntilIdle()
        vm.toggleDone(id, 0, checked = true, slot = Slot.MAIN)
        advanceUntilIdle()
        val tracks = repo.dayTracksFlow(plan, "A").first().tracks
        for (track in tracks) {
            track.sets.indices.forEach { vm.toggleDone(id, it, checked = true, slot = track.slot) }
        }
        advanceUntilIdle()
        vm.completeDay()
        advanceUntilIdle()
        val receipt = requireNotNull(vm.finisher.receipt.value)
        assertEquals(ReceiptNextTimes(receipt.sessionId, listOf(
            ReceiptNextTime(0, 0, 220.0, 215.0), ReceiptNextTime(0, 1, 130.0, 120.0),
        )), nextTimes(vm))
        assertEquals(rows, SetJson.decodeSets(db.programDao().logForSlot(plan, "A", id, Slot.SS)!!.setsJson))
        assertTrue(vm.finisher.dismissReceipt())
        advanceUntilIdle()
        assertEquals(NextTime.Offer("+10 lb", false, 130.0, 120.0), vm.uiState.value.exercises.single().partnerNextTime)
    }

    private suspend fun automaticBench(): Pair<Long, Long> {
        val plan = repo.plans.createAuthoredPlan("Mine", "Bench")
        repo.plans.publishCreatedAuthoredPlan(plan)
        repo.addExercise("A", ProgramExercise("bb_bench", targetSets = 3,
            prescription = Prescription(PrescribedTarget.LoadReps(185.0, 5))), plan)
        return plan to slotId()
    }

    private suspend fun doneBench(plan: Long, bench: Long): cloud.trotter.log.strength.data.OpenRun {
        val id = (repo.startRun(plan, "A") as cloud.trotter.log.strength.data.RunStartResult.Started).runId
        repo.applyToRun(id, plan, "A", listOf(cloud.trotter.log.strength.data.TrackEdit(bench, Slot.MAIN) { rows ->
            rows.map { it.copy(done = true, rir = 2) }
        }))
        return requireNotNull(repo.openRunFlow.first())
    }

    @Test fun `post commit death recovers counts publishing and Undo through Finish`() = runVmTest {
        val (plan, bench) = automaticBench()
        val run = doneBench(plan, bench)
        val captured = requireNotNull(repo.finishRunCapturing(run.id))
        val attempt = FinishAttempt(run.id, plan, "A", 0, "Bench", "Mine", run.startedAt, run.startedAt)
        val vm = newViewModel(SavedStateHandle(mapOf("day_finish" to attempt)))
        advanceUntilIdle()
        val receipt = requireNotNull(vm.finisher.receipt.value)
        assertEquals(captured.sessionId, receipt.sessionId)
        assertEquals(3, receipt.loggedSetCount)
        assertEquals(3, vm.finisher.receiptSetCounts.value?.total)
        assertTrue(vm.finisher.progressState.value.undoVisible)
        assertEquals(listOf(receipt.sessionId), published)
        vm.finisher.undoChanges()
        advanceUntilIdle()
        assertEquals(List(3) { LoggedSet(185.0, 5, SetKind.WORK) }, SetJson.decodeSets(
            db.programDao().logForSlot(plan, "A", bench, Slot.MAIN)!!.setsJson))
        assertEquals(cloud.trotter.log.strength.ui.day.receipt.ReceiptMutation.Undone, vm.finisher.mutation.value)
        assertFalse(vm.finisher.progressState.value.undoVisible)
        assertFalse(vm.finisher.progressState.value.roughEnabled)
        assertTrue(vm.finisher.progressState.value.lifts.single().line.offered)
    }

    @Test fun `a saved receipt and its identity restore live Undo`() = runVmTest {
        val (plan, bench) = automaticBench()
        val run = doneBench(plan, bench)
        val session = requireNotNull(repo.finishRun(run.id))
        val archived = requireNotNull(repo.history.session(session))
        val groups = cloud.trotter.log.strength.ui.day.receipt.ReceiptBuilder.strength(run.tracks,
            repo.plans.slotsFor(plan, "A").first(), { it }, emptyMap())
        val receipt = WorkoutReceipt(session, false, "A", 0, "Bench", "Mine", archived.completedAt, 1,
            WeightUnit.LB, true, false, groups, null, emptyList())
        val identity = cloud.trotter.log.strength.ui.day.receipt.ReceiptProgress(session, null, plan, "A",
            listOf(cloud.trotter.log.strength.ui.day.receipt.ReceiptSlotRef(0, 0, bench, Slot.MAIN)))
        val vm = newViewModel(SavedStateHandle(mapOf("day_workout_receipt" to receipt, "day_receipt_progress" to identity)))
        advanceUntilIdle()
        assertTrue(vm.finisher.progressState.value.undoVisible)
        vm.finisher.undoChanges()
        advanceUntilIdle()
        assertEquals(185.0, SetJson.decodeSets(db.programDao().logForSlot(plan, "A", bench, Slot.MAIN)!!.setsJson).first().weightLb, 0.0)
        assertEquals(cloud.trotter.log.strength.ui.day.receipt.ReceiptMutation.Undone, vm.finisher.mutation.value)
    }

    @Test fun `overnight recovery keeps cardio rows and the run window Undo identity`() = runVmTest {
        val (plan, bench) = automaticBench()
        val spec = CardioSetSpec(CardioRole.WORK, Measure.Time(900), Measure.Distance(5000))
        repo.setCardioBlock(plan, "A", CardioBlock(CardioActivity.TRACK_RUN, listOf(spec)))
        repo.setCardioProgression(plan, "A", cloud.trotter.log.strength.domain.model.ProgressMeasure.DISTANCE, 5)
        val run = doneBench(plan, bench)
        val start = java.time.Instant.parse("2026-11-10T23:45:00Z").toEpochMilli()
        db.openHelper.writableDatabase.execSQL("UPDATE workout_run SET startedAt = ? WHERE id = ?", arrayOf<Any>(start, run.id))
        val cardioId = repo.logCardioSession(CardioSessionEntity(dayId = "A", mode = "OUTDOOR_RUN", hard = false,
            label = "Run", startedAt = start, completedAt = start + 300000, seconds = 900, stepsCompleted = 1,
            planId = plan, activity = "TRACK_RUN"), listOf(CardioSetResult(spec, CardioSetStatus.FINISHED, 900, 5000, DistanceSource.MANUAL)))
        val session = requireNotNull(repo.finishRun(run.id))
        val end = java.time.Instant.parse("2026-11-11T00:10:00Z").toEpochMilli()
        db.openHelper.writableDatabase.execSQL("UPDATE workout_session SET completedAt = ? WHERE id = ?", arrayOf(end, session))
        val attempt = FinishAttempt(run.id, plan, "A", 0, "Bench", "Mine", start, end)
        val vm = newViewModel(SavedStateHandle(mapOf("day_finish" to attempt)))
        advanceUntilIdle()
        assertEquals(5000, vm.finisher.receipt.value?.cardioRows?.single()?.intervals?.single()?.metres)
        assertEquals(cardioId, vm.finisher.receiptProgress.value?.cardioSessionId)
        assertNotNull(vm.finisher.progressState.value.cardioNext)
        vm.finisher.undoChanges()
        advanceUntilIdle()
        assertNull(db.programDao().day(plan, "A")!!.cardioWorkingJson)
        assertEquals(185.0, SetJson.decodeSets(db.programDao().logForSlot(plan, "A", bench, Slot.MAIN)!!.setsJson).first().weightLb, 0.0)
    }

    @Test fun `cardio receipt effort writes and a second tap clears the selection`() = runVmTest {
        val (plan, _) = automaticBench()
        val spec = CardioSetSpec(CardioRole.WORK, Measure.Time(900))
        repo.setCardioBlock(plan, "A", CardioBlock(CardioActivity.TRACK_RUN, listOf(spec)))
        val id = repo.logCardioSession(CardioSessionEntity(dayId = "A", mode = "OUTDOOR_RUN", hard = false,
            label = "Run", startedAt = 1000, completedAt = 901000, seconds = 900, stepsCompleted = 1,
            planId = plan, activity = "TRACK_RUN"), listOf(CardioSetResult(spec, CardioSetStatus.FINISHED, 900)))
        val vm = newViewModel()
        vm.finisher.showLoggedCardio(requireNotNull(repo.history.cardioSession(id)), repo.history.cardioSetsFor(id), 0, emptyMap())
        advanceUntilIdle()
        vm.finisher.setCardioEffort(cloud.trotter.log.strength.domain.progression.CardioEffort.GOOD)
        advanceUntilIdle()
        assertEquals(cloud.trotter.log.strength.domain.progression.CardioEffort.GOOD, vm.finisher.progressState.value.cardioEffort)
        vm.finisher.setCardioEffort(null)
        advanceUntilIdle()
        assertNull(vm.finisher.progressState.value.cardioEffort)
        assertNull(repo.history.cardioSession(id)!!.effort)
    }

    @Test fun `a failed cardio effort write preserves the selection and reports a save failure`() = runVmTest {
        val (plan, _) = automaticBench()
        val spec = CardioSetSpec(CardioRole.WORK, Measure.Time(900))
        repo.setCardioBlock(plan, "A", CardioBlock(CardioActivity.TRACK_RUN, listOf(spec)))
        val id = repo.logCardioSession(CardioSessionEntity(dayId = "A", mode = "OUTDOOR_RUN", hard = false,
            label = "Run", startedAt = 1000, completedAt = 901000, seconds = 900, stepsCompleted = 1,
            planId = plan, activity = "TRACK_RUN", effort = "GOOD"), listOf(CardioSetResult(spec, CardioSetStatus.FINISHED, 900)))
        val vm = newViewModel()
        vm.finisher.showLoggedCardio(requireNotNull(repo.history.cardioSession(id)), repo.history.cardioSetsFor(id), 0, emptyMap())
        advanceUntilIdle()
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER reject_effort BEFORE UPDATE ON cardio_session BEGIN SELECT RAISE(ABORT, 'save failed'); END")

        vm.finisher.setCardioEffort(cloud.trotter.log.strength.domain.progression.CardioEffort.HARD)
        advanceUntilIdle()

        assertEquals(ReceiptMutation.EffortFailed, vm.finisher.mutation.value)
        assertEquals("GOOD", repo.history.cardioSession(id)!!.effort)
        assertEquals(cloud.trotter.log.strength.domain.progression.CardioEffort.GOOD, vm.finisher.progressState.value.cardioEffort)
    }

    @Test fun `mixed Automatic and Ask lifts show Undo at the receipt state`() = runVmTest {
        val (plan, bench) = automaticBench()
        repo.addExercise("A", ProgramExercise("ohp", targetSets = 3,
            prescription = Prescription(PrescribedTarget.LoadReps(95.0, 5))), plan)
        val press = db.programDao().exercisesForDay(plan, "A").single { it.exerciseId == "ohp" }
        val rule = requireNotNull(SlotRuleJson.decode(press.ruleJson))
        db.programDao().setRule(press.id, SlotRuleJson.encode(rule.copy(nextTime = NextTimeMode.ASK)))
        val vm = newCollectingViewModel()
        advanceUntilIdle()
        val run = doneBench(plan, bench)
        repo.applyToRun(run.id, plan, "A", listOf(cloud.trotter.log.strength.data.TrackEdit(press.id, Slot.MAIN) { rows ->
            rows.map { it.copy(done = true, rir = 2) }
        }))
        advanceUntilIdle()
        vm.completeDay()
        advanceUntilIdle()
        val progress = vm.finisher.progressState.value
        val resources = ApplicationProvider.getApplicationContext<Context>().resources
        assertEquals(listOf("Next time: 190 lb (+5)", "Next time: 100 lb offered (+5)"),
            progress.lifts.map { ReceiptLines.label(it.line, WeightUnit.LB, resources) })
        assertTrue(progress.undoVisible)
    }

    @Test fun `a rotation wrap alone on Ask hides Undo at the receipt state`() = runVmTest {
        val (plan, bench) = automaticBench()
        db.planDao().updatePlan(db.planDao().plan(plan)!!.copy(autoApply = false))
        val vm = newCollectingViewModel()
        advanceUntilIdle()
        doneBench(plan, bench)
        advanceUntilIdle()
        vm.completeDay()
        advanceUntilIdle()
        assertEquals(1, db.planDao().plan(plan)!!.cyclesCompleted)
        assertTrue(vm.finisher.progressState.value.lifts.single().line.offered)
        assertFalse(vm.finisher.progressState.value.undoVisible)
    }

    @Test fun `progress recovers on the next emission after a history read fails`() = runVmTest {
        val (plan, bench) = automaticBench()
        val vm = newCollectingViewModel()
        advanceUntilIdle()
        doneBench(plan, bench)
        advanceUntilIdle()
        vm.completeDay()
        advanceUntilIdle()
        val receipt = requireNotNull(vm.finisher.receipt.value)
        assertTrue(vm.finisher.progressState.value.undoVisible)
        failSessionById = true
        repo.history.setSessionNote(receipt.sessionId, "First note")
        advanceUntilIdle()
        assertTrue(vm.finisher.progressState.value.lifts.isEmpty())
        assertFalse(vm.finisher.progressState.value.undoVisible)
        failSessionById = false
        repo.history.setSessionNote(receipt.sessionId, "Second note")
        advanceUntilIdle()
        assertTrue(vm.finisher.progressState.value.undoVisible)
        assertEquals(190.0, vm.finisher.progressState.value.lifts.single().line.weightLb, 0.0)
    }

    @Test fun `Rough day refusals use Rough day mutation results`() = runVmTest {
        val (plan, bench) = automaticBench()
        val vm = newCollectingViewModel()
        advanceUntilIdle()
        doneBench(plan, bench)
        advanceUntilIdle()
        vm.completeDay()
        advanceUntilIdle()
        val run = (repo.startRun(plan, "A") as cloud.trotter.log.strength.data.RunStartResult.Started).runId
        vm.finisher.setRoughDay(true)
        advanceUntilIdle()
        assertEquals(ReceiptMutation.RoughDayOpen("A"), vm.finisher.mutation.value)
        repo.discardRun(run)
        vm.finisher.setRoughDay(true)
        advanceUntilIdle()
        assertEquals(ReceiptMutation.RoughDayFailed, vm.finisher.mutation.value)
    }

    @Test fun `receipt reloads measured legs from Room after restoring its saved identity`() = runVmTest {
        val (plan, _) = automaticBench()
        val spec = CardioSetSpec(CardioRole.WORK, Measure.Time(1500))
        repo.setCardioBlock(plan, "A", CardioBlock(CardioActivity.ROAD_RUN, listOf(spec)))
        val end = System.currentTimeMillis() - 1000
        val result = CardioSetResult(spec, CardioSetStatus.FINISHED, 1690, 5600, DistanceSource.MANUAL,
            metresAtEnd = 5000, secondsAtEnd = 1500, readingSource = ReadingSource.TYPED)
        val id = repo.logCardioSession(CardioSessionEntity(dayId = "A", mode = "OUTDOOR_RUN", hard = false,
            label = "Run", startedAt = end - 1690000, completedAt = end, seconds = 1690, stepsCompleted = 1,
            planId = plan, activity = "ROAD_RUN"), listOf(result))
        val vm = newCollectingViewModel(cardioClock = FixedCardioClock(end))
        vm.finisher.showLoggedCardio(requireNotNull(repo.history.cardioSession(id)), listOf(result), 0, emptyMap())
        advanceUntilIdle()
        assertEquals(listOf(listOf(result)), vm.finisher.progressState.value.cardioSets)
        val restored = newViewModel(SavedStateHandle(mapOf(
            "day_workout_receipt" to vm.finisher.receipt.value,
            "day_receipt_progress" to vm.finisher.receiptProgress.value,
        )), cardioClock = FixedCardioClock(end))
        advanceUntilIdle()
        assertEquals(listOf(listOf(result)), restored.finisher.progressState.value.cardioSets)
    }

    @Test fun `cardio logged before Start has rows but no next time on the strength receipt`() = runVmTest {
        val (plan, bench) = automaticBench()
        val spec = CardioSetSpec(CardioRole.WORK, Measure.Time(900), Measure.Distance(5000))
        repo.setCardioBlock(plan, "A", CardioBlock(CardioActivity.TRACK_RUN, listOf(spec)))
        repo.setCardioProgression(plan, "A", cloud.trotter.log.strength.domain.model.ProgressMeasure.DISTANCE, 5)
        val completedAt = System.currentTimeMillis() - 1000
        val id = repo.logCardioSession(CardioSessionEntity(dayId = "A", mode = "OUTDOOR_RUN", hard = false,
            label = "Run", startedAt = completedAt - 900000, completedAt = completedAt, seconds = 900, stepsCompleted = 1,
            planId = plan, activity = "TRACK_RUN"), listOf(CardioSetResult(spec, CardioSetStatus.FINISHED, 900,
                5000, DistanceSource.MANUAL)))
        val vm = newCollectingViewModel(cardioClock = FixedCardioClock(completedAt))
        vm.finisher.showLoggedCardio(requireNotNull(repo.history.cardioSession(id)), repo.history.cardioSetsFor(id), 0, emptyMap())
        advanceUntilIdle()
        assertTrue(vm.finisher.progressState.value.undoVisible)
        assertNotNull(vm.finisher.progressState.value.cardioNext)
        assertTrue(vm.finisher.dismissReceipt())
        val after = db.programDao().day(plan, "A")!!.cardioWorkingJson
        doneBench(plan, bench)
        assertNull(db.cardioSessionDao().byId(id)!!.progressionUndoJson)
        advanceUntilIdle()
        vm.completeDay()
        advanceUntilIdle()
        assertEquals(5000, vm.finisher.receipt.value?.cardioRows?.single()?.intervals?.single()?.metres)
        assertNull(vm.finisher.receiptProgress.value?.cardioSessionId)
        assertNull(vm.finisher.progressState.value.cardioNext)
        assertNull(vm.finisher.progressState.value.cardioStored)
        assertEquals(id, vm.finisher.progressState.value.cardioSessionId)
        vm.finisher.undoChanges()
        advanceUntilIdle()
        assertEquals(after, db.programDao().day(plan, "A")!!.cardioWorkingJson)
    }

    @Test fun `a counter only cardio receipt shows Undo and restores the light counter`() = runVmTest {
        val (plan, _) = automaticBench()
        val spec = CardioSetSpec(CardioRole.WORK, Measure.Time(900))
        repo.setCardioBlock(plan, "A", CardioBlock(CardioActivity.TRACK_RUN, listOf(spec)))
        repo.setCardioKeepToPlan(plan, "A", true)
        db.planDao().updateLightCycle(plan, 2, 0)
        val id = repo.logCardioSession(CardioSessionEntity(dayId = "A", mode = "OUTDOOR_RUN", hard = false,
            label = "Run", startedAt = 1000, completedAt = 901000, seconds = 900, stepsCompleted = 1,
            planId = plan, activity = "TRACK_RUN"), listOf(CardioSetResult(spec, CardioSetStatus.FINISHED, 900)), finishDay = true)
        val vm = newViewModel()
        vm.finisher.showLoggedCardio(requireNotNull(repo.history.cardioSession(id)), repo.history.cardioSetsFor(id), 0, emptyMap())
        advanceUntilIdle()
        assertEquals(1, db.planDao().plan(plan)!!.lightCycleRemaining)
        assertNull(db.programDao().day(plan, "A")!!.cardioWorkingJson)
        assertTrue(vm.finisher.progressState.value.undoVisible)
        vm.finisher.undoChanges()
        advanceUntilIdle()
        assertEquals(2, db.planDao().plan(plan)!!.lightCycleRemaining)
        assertFalse(vm.finisher.progressState.value.undoVisible)
    }

    private fun nextTimes(vm: DayViewModel): ReceiptNextTimes? {
        val receipt = vm.finisher.receipt.value ?: return null
        return ReceiptNextTimes(receipt.sessionId, vm.finisher.progressState.value.lifts.map { lift ->
            val line = lift.line
            ReceiptNextTime(lift.ref.groupIndex, lift.ref.rowIndex, line.weightLb.takeIf { line.delta != null },
                line.weightLb - (line.delta ?: 0.0))
        })
    }

}
