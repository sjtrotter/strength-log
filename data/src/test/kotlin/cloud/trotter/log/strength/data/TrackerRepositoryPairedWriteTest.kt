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
 * [TrackerRepository.updateSetsPaired] must land a superset's two tracks as one
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
        repo.replaceProgram(Program(listOf(ProgramDay("A", "Day A", "", listOf(slot), cardio = null))))
        return repo.daySlotsFlow("A").first().single().programExerciseId
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

        repo.updateSetsPaired(repo.dayTracksFlow(repo.planIdOfSlot(slotId) ?: -1, "A").first().runId.orEmpty(), "A", programExerciseId = slotId, main = main, ss = partner)

        val slots = repo.dayTracksFlow(repo.selectedPlanIdFlow.first()!!, "A").map { it.tracks }.first().associateBy { it.slot }
        assertEquals(setOf(Slot.MAIN, Slot.SS), slots.keys)
        assertEquals(main.mapIndexed { i, set -> set.copy(plannedIndex = i) }, slots.getValue(Slot.MAIN).sets)
        assertEquals(partner.mapIndexed { i, set -> set.copy(plannedIndex = i) }, slots.getValue(Slot.SS).sets)
        assertEquals(repo.openRunFlow.first()!!.id,
            repo.dayTracksFlow(repo.selectedPlanIdFlow.first()!!, "A").first().runId)
    }

    @Test
    fun pairedWriteOverwritesBothTracksInPlace() = runTest {
        val slotId = supersetSlot()
        repo.updateSetsPaired(repo.dayTracksFlow(repo.planIdOfSlot(slotId) ?: -1, "A").first().runId.orEmpty(),
            "A", slotId,
            main = listOf(LoggedSet(60.0, 12, SetKind.WORK)),
            ss = listOf(LoggedSet(50.0, 15, SetKind.WORK)),
        )
        val grownMain = listOf(LoggedSet(60.0, 12, SetKind.WORK), LoggedSet(60.0, 12, SetKind.EXTRA))
        val grownSs = listOf(LoggedSet(50.0, 15, SetKind.WORK), LoggedSet(50.0, 15, SetKind.EXTRA))

        repo.updateSetsPaired(repo.dayTracksFlow(repo.planIdOfSlot(slotId) ?: -1, "A").first().runId.orEmpty(), "A", slotId, grownMain, grownSs)

        val slots = repo.dayTracksFlow(repo.selectedPlanIdFlow.first()!!, "A").map { it.tracks }.first().associateBy { it.slot }
        assertEquals(2, slots.size)
        assertEquals(grownMain, slots.getValue(Slot.MAIN).sets)
        assertEquals(grownSs, slots.getValue(Slot.SS).sets)
    }
}
