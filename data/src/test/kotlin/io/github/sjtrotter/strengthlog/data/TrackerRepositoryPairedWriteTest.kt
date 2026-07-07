package io.github.sjtrotter.strengthlog.data

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.sjtrotter.strengthlog.data.db.StrengthDatabase
import io.github.sjtrotter.strengthlog.data.db.entity.Slot
import io.github.sjtrotter.strengthlog.data.prefs.SettingsStore
import io.github.sjtrotter.strengthlog.domain.model.LoggedSet
import io.github.sjtrotter.strengthlog.domain.model.SetKind
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

    @Test
    fun pairedWritePersistsBothTracksWithOneCheckDate() = runTest {
        val main = listOf(
            LoggedSet(60.0, 12, SetKind.WORK, done = true),
            LoggedSet(60.0, 11, SetKind.WORK),
        )
        val partner = listOf(
            LoggedSet(50.0, 15, SetKind.WORK, done = true),
            LoggedSet(50.0, 14, SetKind.WORK),
        )

        repo.updateSetsPaired("A", programExerciseId = 7, mainSets = main, ssSets = partner)

        val slots = repo.logFlow("A").first().associateBy { it.slot }
        assertEquals(setOf(Slot.MAIN, Slot.SS), slots.keys)
        assertEquals(main, slots.getValue(Slot.MAIN).sets)
        assertEquals(partner, slots.getValue(Slot.SS).sets)
        assertEquals(
            slots.getValue(Slot.MAIN).checkDate,
            slots.getValue(Slot.SS).checkDate,
        )
    }

    @Test
    fun pairedWriteOverwritesBothTracksInPlace() = runTest {
        repo.updateSetsPaired(
            "A", 7,
            mainSets = listOf(LoggedSet(60.0, 12, SetKind.WORK)),
            ssSets = listOf(LoggedSet(50.0, 15, SetKind.WORK)),
        )
        val grownMain = listOf(LoggedSet(60.0, 12, SetKind.WORK), LoggedSet(60.0, 12, SetKind.EXTRA))
        val grownSs = listOf(LoggedSet(50.0, 15, SetKind.WORK), LoggedSet(50.0, 15, SetKind.EXTRA))

        repo.updateSetsPaired("A", 7, grownMain, grownSs)

        val slots = repo.logFlow("A").first().associateBy { it.slot }
        assertEquals(2, slots.size)
        assertEquals(grownMain, slots.getValue(Slot.MAIN).sets)
        assertEquals(grownSs, slots.getValue(Slot.SS).sets)
    }
}
