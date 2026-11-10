package cloud.trotter.log.strength.data

import kotlinx.coroutines.flow.map
import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import cloud.trotter.log.strength.data.db.StrengthDatabase
import cloud.trotter.log.strength.data.db.entity.Slot
import cloud.trotter.log.strength.data.prefs.SettingsStore
import cloud.trotter.log.strength.domain.model.LoggedSet
import cloud.trotter.log.strength.domain.model.Program
import cloud.trotter.log.strength.domain.model.ProgramDay
import cloud.trotter.log.strength.domain.model.ProgramExercise
import cloud.trotter.log.strength.domain.model.SetKind
import cloud.trotter.log.strength.domain.model.SupersetPartner
import cloud.trotter.log.strength.domain.seeding.SetEditor
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [TrackerRepository.applyToRun] must land a superset's two tracks as one
 * transactional write — two separate upserts could be split by process death and
 * misalign the tracks forever (spec §4/§8.2 alignment; feeds A1 history).
 * Robolectric + in-memory Room because the transaction is the thing under test.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TrackerRepositoryPairedWriteTest {

    private lateinit var db: StrengthDatabase
    private lateinit var repo: TrackerRepository
    private lateinit var storeScope: CoroutineScope

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, StrengthDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        storeScope = CoroutineScope(Dispatchers.IO + Job())
        val dataStore = PreferenceDataStoreFactory.create(scope = storeScope) {
            File.createTempFile("paired-write-settings", ".preferences_pb")
        }
        repo = TrackerRepository(
            db = db,
            programDao = db.programDao(),
            sessionDao = db.sessionDao(),
            customExerciseDao = db.customExerciseDao(),
            settings = SettingsStore(dataStore),
        )
    }

    @After
    fun tearDown() {
        db.close()
        storeScope.cancel()
    }

    /** Day A with one superset slot; writes are addressed by the slot's own row. */
    private suspend fun supersetSlot(): Long {
        val slot = ProgramExercise("ez_curl", superset = SupersetPartner("rope_pushdown"))
        repo.replaceProgram(Program(listOf(ProgramDay("A", "Day A", "", listOf(slot)))))
        return repo.plans.daySlotsFlow("A").first().single().programExerciseId
    }

    @Test
    fun pairedWritePersistsBothTracksInOneRun() = runTest {
        val slotId = supersetSlot()
        val main = listOf(
            LoggedSet(60.0, 12, SetKind.WORK, done = true),
            LoggedSet(60.0, 11, SetKind.WORK),
        )
        val partner = listOf(
            LoggedSet(50.0, 15, SetKind.WORK, done = true),
            LoggedSet(50.0, 14, SetKind.WORK),
        )

        val planId = requireNotNull(repo.plans.planIdOfSlot(slotId))
        val runId = requireNotNull(repo.dayTracksFlow(planId, "A").first().runId)
        repo.applyToRun(runId, planId, "A", listOf(
            TrackEdit(slotId, Slot.MAIN) { main },
            TrackEdit(slotId, Slot.SS) { partner },
        ))

        val slots = repo.dayTracksFlow(repo.plans.selectedPlanIdFlow.first()!!, "A").map { it.tracks }.first().associateBy { it.slot }
        assertEquals(setOf(Slot.MAIN, Slot.SS), slots.keys)
        assertEquals(main, slots.getValue(Slot.MAIN).sets)
        assertEquals(partner, slots.getValue(Slot.SS).sets)
        assertEquals(repo.openRunFlow.first()!!.id,
            repo.dayTracksFlow(repo.plans.selectedPlanIdFlow.first()!!, "A").first().runId)
    }

    @Test
    fun pairedAddAndRemoveKeepBothTracksAligned() = runTest {
        val slotId = supersetSlot()
        db.storeTestRows("A", slotId, Slot.MAIN, listOf(LoggedSet(60.0, 12, SetKind.WORK)))
        db.storeTestRows("A", slotId, Slot.SS, listOf(LoggedSet(50.0, 15, SetKind.WORK)))
        val grownMain = listOf(LoggedSet(60.0, 12, SetKind.WORK, plannedIndex = 0), LoggedSet(60.0, 12, SetKind.EXTRA))
        val grownSs = listOf(LoggedSet(50.0, 15, SetKind.WORK, plannedIndex = 0), LoggedSet(50.0, 15, SetKind.EXTRA))

        val planId = requireNotNull(repo.plans.planIdOfSlot(slotId))
        val runId = requireNotNull(repo.dayTracksFlow(planId, "A").first().runId)
        assertEquals(RunWriteResult.InRun(runId, false), repo.applyToRun(runId, planId, "A", listOf(
            TrackEdit(slotId, Slot.MAIN) { SetEditor.addSet(it) },
            TrackEdit(slotId, Slot.SS) { SetEditor.addSet(it) },
        )))

        val slots = repo.dayTracksFlow(repo.plans.selectedPlanIdFlow.first()!!, "A").map { it.tracks }.first().associateBy { it.slot }
        assertEquals(2, slots.size)
        assertEquals(grownMain, slots.getValue(Slot.MAIN).sets)
        assertEquals(grownSs, slots.getValue(Slot.SS).sets)
        val remove = listOf(
            TrackEdit(slotId, Slot.MAIN) { rows ->
                if (rows.size <= 1) null else SetEditor.removeSet(rows, 1)
            },
            TrackEdit(slotId, Slot.SS) { SetEditor.removeSet(it, 1) },
        )
        assertEquals(RunWriteResult.InRun(runId, false), repo.applyToRun(runId, planId, "A", remove))
        val shrunk = repo.dayTracksFlow(planId, "A").first().tracks.associateBy { it.slot }
        assertEquals(grownMain.take(1), shrunk.getValue(Slot.MAIN).sets)
        assertEquals(grownSs.take(1), shrunk.getValue(Slot.SS).sets)
        assertEquals(RunWriteResult.InRun(runId, false), repo.applyToRun(runId, planId, "A", listOf(
            TrackEdit(slotId, Slot.SS) { SetEditor.addSet(it) },
        )))
        val before = db.runDao().tracks(runId)
        assertEquals(RunWriteResult.Unchanged, repo.applyToRun(runId, planId, "A", remove))
        assertEquals(before, db.runDao().tracks(runId))
        assertEquals(RunStartResult.Started(runId), repo.startRun(planId, "A"))
        val captured = repo.dayTracksFlow(planId, "A").first().tracks.associate { it.slot to it.sets }
        assertEquals(RunWriteResult.InRun(runId, false), repo.applyToRun(runId, planId, "A", listOf(
            TrackEdit(slotId, Slot.MAIN) { SetEditor.addSet(it) },
            TrackEdit(slotId, Slot.SS) { SetEditor.addSet(it) },
        )))
        val added = repo.dayTracksFlow(planId, "A").first().tracks.associate { it.slot to it.sets }
        assertEquals(SetEditor.addSet(captured.getValue(Slot.MAIN)), added.getValue(Slot.MAIN))
        assertEquals(SetEditor.addSet(captured.getValue(Slot.SS)), added.getValue(Slot.SS))
        assertEquals(RunWriteResult.InRun(runId, false), repo.applyToRun(runId, planId, "A", remove))
        val tracksBeforeDecline = db.runDao().tracks(runId)
        assertEquals(RunWriteResult.Unchanged, repo.applyToRun(runId, planId, "A", remove))
        assertEquals(tracksBeforeDecline, db.runDao().tracks(runId))
    }
}
