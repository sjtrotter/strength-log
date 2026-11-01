package cloud.trotter.log.strength.data

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import cloud.trotter.log.strength.data.db.StrengthDatabase
import cloud.trotter.log.strength.data.db.entity.PlanEntity
import cloud.trotter.log.strength.data.db.entity.ProgramDayEntity
import cloud.trotter.log.strength.data.db.entity.RestoreMarkerEntity
import cloud.trotter.log.strength.data.db.entity.WorkoutRunEntity
import cloud.trotter.log.strength.data.prefs.SettingsStore
import cloud.trotter.log.strength.domain.generator.WizardAnswers
import cloud.trotter.log.strength.domain.model.Program
import cloud.trotter.log.strength.domain.model.ProgramDay
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class OnTodayTest {
    private lateinit var db: StrengthDatabase
    private lateinit var settings: SettingsStore
    private lateinit var repo: TrackerRepository
    private lateinit var scope: CoroutineScope

    @Before fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, StrengthDatabase::class.java).allowMainThreadQueries().build()
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        settings = SettingsStore(PreferenceDataStoreFactory.create(scope = scope) {
            File.createTempFile("on-today", ".preferences_pb")
        })
        repo = TrackerRepository(db, db.programDao(), db.sessionDao(), db.customExerciseDao(), settings)
    }

    @After fun tearDown() {
        db.close()
        scope.cancel()
    }

    private fun plan(id: Long, position: Int = id.toInt(), archived: Boolean = false) =
        PlanEntity(id, "Plan $id", "GENERATED", position, 1000, archived, suggestedDayId = "A")

    @Test fun `preferred fallback uses position and ignores archived and stale ids`() {
        val plans = listOf(plan(3, 0, archived = true), plan(2, 2), plan(1, 1))
        assertEquals(2L, preferredPlan(plans, 2)?.id)
        for (id in listOf(null, 99L, 3L)) assertEquals(1L, preferredPlan(plans, id)?.id)
        assertNull(preferredPlan(listOf(plan(3, archived = true)), 3))
    }

    @Test fun `membership fixup runs once even after all other fixups`() = runTest {
        db.planDao().insertPlans(listOf(plan(1), plan(2)))
        settings.setSelectedPlanId(2)
        settings.setPlanSettingsFixupDone()
        settings.setLegacyTimedFixupDone()
        repo.runPlanSettingsFixupIfNeeded()
        assertEquals(listOf(2L), db.planDao().allPlans().filter { it.onToday }.map { it.id })
        assertTrue(settings.onTodayFixupDoneFlow.first())
        db.planDao().updateOnToday(2, false)
        repo.runPlanSettingsFixupIfNeeded()
        assertTrue(db.planDao().allPlans().none { it.onToday })
    }

    @Test fun `first plan summaries include migrated membership`() = runTest {
        db.planDao().insertPlans(listOf(plan(1), plan(2)))
        settings.setSelectedPlanId(2)
        settings.setPlanSettingsFixupDone()

        val summaries = repo.planSummariesFlow.first()

        assertEquals(listOf(2L), summaries.filter { it.onToday }.map { it.id })
        assertTrue(settings.onTodayFixupDoneFlow.first())
    }

    @Test fun `today plans waits for migrated membership on its first emission`() = runTest {
        db.planDao().insertPlans(listOf(plan(1), plan(2)))
        settings.setSelectedPlanId(2)
        settings.setPlanSettingsFixupDone()
        assertEquals(listOf(2L), repo.todayPlans.first().map { it.id })
        assertTrue(settings.onTodayFixupDoneFlow.first())
    }

    @Test fun `today plans orders preferred first then position and excludes paused and archived`() = runTest {
        db.planDao().insertPlans(listOf(plan(1, 3), plan(2, 2), plan(3, 1), plan(4, 0, archived = true)))
        settings.setSelectedPlanId(1)
        repo.runPlanSettingsFixupIfNeeded()
        db.planDao().updateOnToday(2, true)
        db.planDao().updateOnToday(3, true)
        db.planDao().updateOnToday(4, true)
        assertEquals(listOf(1L, 3L, 2L), repo.todayPlans.first().map { it.id })
        repo.pause(3)
        assertEquals(listOf(1L, 2L), repo.todayPlans.first().map { it.id })
    }

    @Test fun `publishing a generated plan migrates the old preferred plan first`() = runTest {
        db.planDao().insertPlan(plan(1))
        db.programDao().upsertDay(ProgramDayEntity(1, "A", 0, "Day A", "", null))
        settings.setSelectedPlanId(1)
        settings.setPlanSettingsFixupDone()
        val program = Program(listOf(ProgramDay("A", "Day A", "", emptyList())))
        val generated = repo.createGeneratedPlan("Generated", WizardAnswers(), program)

        repo.publishCreatedPlan(generated, WizardAnswers())

        assertEquals(generated, settings.selectedPlanIdFlow.first())
        assertEquals(listOf(1L, generated), db.planDao().allPlans().filter { it.onToday }.map { it.id })
        assertTrue(settings.onTodayFixupDoneFlow.first())
    }

    @Test fun `publishing an authored plan migrates the old preferred plan first`() = runTest {
        db.planDao().insertPlan(plan(1))
        settings.setSelectedPlanId(1)
        settings.setPlanSettingsFixupDone()
        val authored = repo.createAuthoredPlan("Authored", "Day A")

        repo.publishCreatedAuthoredPlan(authored)

        assertEquals(authored, settings.selectedPlanIdFlow.first())
        assertEquals(listOf(1L, authored), db.planDao().allPlans().filter { it.onToday }.map { it.id })
        assertTrue(settings.onTodayFixupDoneFlow.first())
    }

    @Test fun `membership fixup and display defer under the restore marker`() = runTest {
        db.planDao().insertPlan(plan(1))
        db.restoreMarkerDao().put(RestoreMarkerEntity(nonce = "pending"))
        repo.runPlanSettingsFixupIfNeeded()
        assertFalse(settings.onTodayFixupDoneFlow.first())
        assertFalse(db.planDao().plan(1)!!.onToday)
        assertNull(repo.todayTargetFlow.first())
        db.restoreMarkerDao().clear()
        assertEquals(1L, (repo.todayTargetFlow.first() as TodayTarget.Day).plan.id)
    }

    @Test fun `display prefers the run then preferred membership then position then explicit emptiness`() = runTest {
        assertEquals(TodayTarget.NoPlans, repo.todayTargetFlow.first())
        db.planDao().insertPlans(listOf(plan(1), plan(2).copy(onToday = true), plan(3).copy(onToday = true)))
        settings.setSelectedPlanId(1)
        assertEquals(2L, (repo.todayTargetFlow.first() as TodayTarget.Day).plan.id)
        db.runDao().insertRun(WorkoutRunEntity("run", 1, "B", "Day B", "Plan 1", 1000))
        val running = repo.todayTargetFlow.first() as TodayTarget.Day
        assertEquals(1L, running.plan.id)
        assertEquals("B", running.dayId)
        assertFalse(running.plan.onToday)
        db.runDao().deleteRun("run")
        repo.selectPlan(3)
        assertEquals(3L, (repo.todayTargetFlow.first() as TodayTarget.Day).plan.id)
        repo.pause(2)
        repo.pause(3)
        assertEquals(TodayTarget.NothingOnToday, repo.todayTargetFlow.first())
    }

    @Test fun `use only selects one plan and pausing preferred or last keeps preference`() = runTest {
        db.planDao().insertPlans(listOf(plan(1), plan(2)))
        repo.useAlongside(2)
        repo.useOnly(2)
        assertEquals(2L, settings.selectedPlanIdFlow.first())
        assertEquals(listOf(2L), db.planDao().allPlans().filter { it.onToday }.map { it.id })
        repo.pause(2)
        assertEquals(2L, settings.selectedPlanIdFlow.first())
        assertTrue(db.planDao().allPlans().none { it.onToday })
        repo.selectPlan(1)
        assertTrue(db.planDao().plan(1)!!.onToday)
        repo.useAlongside(2)
        assertTrue(repo.archivePlan(2))
        assertFalse(db.planDao().plan(2)!!.onToday)
        repo.restorePlan(2)
        assertFalse(db.planDao().plan(2)!!.onToday)
        assertFalse(db.planDao().plan(2)!!.archived)
    }

    @Test fun `pausing does not close an open run`() = runTest {
        db.planDao().insertPlan(plan(1))
        db.runDao().insertRun(WorkoutRunEntity("run", 1, "A", "Day A", "Plan 1", 1000))
        repo.pause(1)
        assertEquals("run", repo.openRunFlow.first()?.id)
        assertEquals(1L, (repo.todayTargetFlow.first() as TodayTarget.Day).plan.id)
    }

    @Test fun `new plans are on Today and preferred and duplicates are paused`() = runTest {
        val program = Program(listOf(ProgramDay("A", "Day A", "", emptyList())))
        repo.replaceProgram(program)
        val inserted = db.planDao().allPlans().single()
        assertTrue(inserted.onToday)
        val authored = repo.createAuthoredPlan("Authored", "Day A")
        repo.publishCreatedAuthoredPlan(authored)
        assertTrue(db.planDao().plan(authored)!!.onToday)
        assertEquals(authored, settings.selectedPlanIdFlow.first())
        val generated = repo.createGeneratedPlan("Generated", WizardAnswers(), program)
        repo.publishCreatedPlan(generated, WizardAnswers())
        assertTrue(db.planDao().plan(generated)!!.onToday)
        assertTrue(db.planDao().plan(authored)!!.onToday)
        assertEquals(generated, settings.selectedPlanIdFlow.first())
        val copy = repo.duplicatePlan(generated, "Copy")
        assertFalse(db.planDao().plan(copy)!!.onToday)
    }

    @Test fun `use only rolls back all membership when a Room write fails`() = runTest {
        db.planDao().insertPlans(listOf(plan(1), plan(2)))
        repo.useOnly(1)
        val before = db.planDao().allPlans()
        db.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER fail_membership BEFORE UPDATE OF onToday ON plan WHEN NEW.id = 2 " +
                "BEGIN SELECT RAISE(ABORT, 'membership failed'); END",
        )
        assertTrue(runCatching { repo.useOnly(2) }.isFailure)
        assertEquals(before, db.planDao().allPlans())
        assertEquals(1L, settings.selectedPlanIdFlow.first())
    }
}
