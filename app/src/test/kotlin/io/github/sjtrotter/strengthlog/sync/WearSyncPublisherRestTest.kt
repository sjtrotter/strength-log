package io.github.sjtrotter.strengthlog.sync

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.sjtrotter.strengthlog.data.TrackerRepository
import io.github.sjtrotter.strengthlog.data.db.StrengthDatabase
import io.github.sjtrotter.strengthlog.data.db.entity.Slot
import io.github.sjtrotter.strengthlog.data.prefs.SettingsStore
import io.github.sjtrotter.strengthlog.domain.model.LoggedSet
import io.github.sjtrotter.strengthlog.domain.model.Program
import io.github.sjtrotter.strengthlog.domain.model.ProgramDay
import io.github.sjtrotter.strengthlog.domain.model.ProgramExercise
import io.github.sjtrotter.strengthlog.domain.model.SetKind
import io.github.sjtrotter.strengthlog.domain.standards.RestCategory
import com.google.android.gms.wearable.DataClient
import com.google.android.gms.wearable.Wearable
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The publisher folds [SettingsStore.restSettingsFlow] into its content combine, so
 * a Setup rest edit changes the projected snapshot content — the byte change that,
 * via distinctUntilChanged + publish's nextRevision (WearSyncStoreTest), spends a
 * fresh revision. R8 risk #3 flags forgetting this combine as the real bug; this
 * test is that checklist item. Real repo + Room + DataStore on the test dispatcher.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class WearSyncPublisherRestTest {

    private val dispatcher = StandardTestDispatcher()
    private lateinit var db: StrengthDatabase
    private lateinit var settings: SettingsStore
    private lateinit var repo: TrackerRepository
    private lateinit var storeScope: CoroutineScope

    // snapshotContent() never touches the DataClient; a real (unconnected) one lets
    // us construct the publisher without a GMS fake and is never invoked here.
    private lateinit var neverCalledDataClient: DataClient

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        neverCalledDataClient = Wearable.getDataClient(context)
        db = Room.inMemoryDatabaseBuilder(context, StrengthDatabase::class.java)
            .allowMainThreadQueries()
            .setQueryCoroutineContext(dispatcher)
            .build()
        storeScope = CoroutineScope(dispatcher + Job())
        val dataStore = PreferenceDataStoreFactory.create(scope = storeScope) {
            File.createTempFile("pub-rest-settings", ".preferences_pb")
        }
        settings = SettingsStore(dataStore)
        repo = TrackerRepository(
            db = db,
            programDao = db.programDao(),
            sessionDao = db.sessionDao(),
            customExerciseDao = db.customExerciseDao(),
            settings = settings,
        )
    }

    @After
    fun tearDown() {
        db.close()
        storeScope.cancel()
    }

    private fun newPublisher(): WearSyncPublisher {
        val syncStore = WearSyncStore(
            PreferenceDataStoreFactory.create(scope = storeScope) {
                File.createTempFile("pub-rest-sync", ".preferences_pb")
            },
        )
        return WearSyncPublisher(repo, syncStore, neverCalledDataClient, storeScope)
    }

    @Test
    fun `a rest-settings edit changes the projected snapshot content`() = runTest(dispatcher) {
        repo.replaceProgram(
            Program(listOf(ProgramDay("A", "Squat", "", listOf(ProgramExercise("bb_back_squat", isMain = true)), cardio = null))),
        )
        settings.setSuggestedDay("A")
        val slotId = repo.daySlotsFlow("A").first().single().programExerciseId
        repo.updateSets("A", slotId, Slot.MAIN, listOf(LoggedSet(235.0, 5, SetKind.TOP)))

        val publisher = newPublisher()

        // Default (master on, no overrides): TOP rests its 180s default.
        val before = publisher.snapshotContent().first { it != null }!!
        assertEquals(180, before.day.exercises.single().sets.single().restAfterSeconds)

        // Editing the TOP override republishes with the new number.
        settings.setRestOverride(RestCategory.TOP, 210)
        val afterOverride = publisher.snapshotContent().first { it != null }!!
        assertEquals(210, afterOverride.day.exercises.single().sets.single().restAfterSeconds)

        // Master toggle off zeroes it — a distinct content again.
        settings.setRestTimerEnabled(false)
        val afterOff = publisher.snapshotContent().first { it != null }!!
        assertEquals(0, afterOff.day.exercises.single().sets.single().restAfterSeconds)
    }
}
