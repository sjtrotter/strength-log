package io.github.sjtrotter.strengthlog.transfer.health

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.sjtrotter.strengthlog.data.ImportedSession
import io.github.sjtrotter.strengthlog.data.TrackerRepository
import io.github.sjtrotter.strengthlog.data.db.StrengthDatabase
import io.github.sjtrotter.strengthlog.data.db.entity.SessionSetEntity
import io.github.sjtrotter.strengthlog.data.db.entity.Slot
import io.github.sjtrotter.strengthlog.data.db.entity.WorkoutSessionEntity
import io.github.sjtrotter.strengthlog.data.prefs.SettingsStore
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [HealthConnectPublisher] degrade-invisibly paths (#17, A3) against a fake
 * client — the emulator has no Health Connect provider (D10), so every branch
 * (unavailable, denied, empty session, provider throws, happy path) is proven
 * here on the JVM with [FakeHealthConnectClient]. The pure record shape itself
 * is [SessionRecordMapperTest]'s job.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class HealthConnectPublisherTest {

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
            File.createTempFile("hc-publisher-settings", ".preferences_pb")
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

    private suspend fun seedSession(sets: List<SessionSetEntity>): Long {
        repo.importSessionHistory(
            listOf(ImportedSession(session(), sets)),
            newCustomExercises = emptyList(),
        )
        return repo.sessionSummariesFlow.first().first().session.id
    }

    private fun session() = WorkoutSessionEntity(
        id = 0, dayId = "A", dayTitle = "Lower", startedAt = null, completedAt = 10_000L, bodyweightLb = 180,
    )

    private fun set(exerciseId: String) = SessionSetEntity(
        id = 0, sessionId = 0, exerciseId = exerciseId, exerciseName = exerciseId, slot = Slot.MAIN,
        setIndex = 0, kind = SetKind.WORK.name, weightLb = 100.0, reps = 8, done = true,
    )

    private fun publisher(client: FakeHealthConnectClient?) =
        HealthConnectPublisher(HealthConnectClientProvider { client }, repo)

    @Test
    fun unavailableProvider_isANoOp() = runTest {
        val id = seedSession(listOf(set("bb_back_squat")))
        // Provider returns null (no Health Connect on device): must not throw.
        publisher(client = null).publish(id)
    }

    @Test
    fun writePermissionDenied_doesNotInsert() = runTest {
        val id = seedSession(listOf(set("bb_back_squat")))
        val client = FakeHealthConnectClient(grantedPermissions = emptySet())
        publisher(client).publish(id)
        assertEquals(0, client.insertCallCount)
    }

    @Test
    fun granted_withSets_insertsOneExerciseSession() = runTest {
        val id = seedSession(listOf(set("bb_back_squat"), set("bb_bench")))
        val client = FakeHealthConnectClient(grantedPermissions = setOf(HealthConnectPermissions.WRITE_EXERCISE))
        publisher(client).publish(id)

        assertEquals(1, client.insertCallCount)
        assertEquals(1, client.insertedRecords.size)
        assertTrue(client.insertedRecords.first() is ExerciseSessionRecord)
    }

    @Test
    fun emptySession_doesNotInsert() = runTest {
        val id = seedSession(sets = emptyList())
        val client = FakeHealthConnectClient(grantedPermissions = setOf(HealthConnectPermissions.WRITE_EXERCISE))
        publisher(client).publish(id)
        assertEquals(0, client.insertCallCount)
    }

    @Test
    fun providerThrows_isSwallowed() = runTest {
        val id = seedSession(listOf(set("bb_back_squat")))
        val client = FakeHealthConnectClient(
            grantedPermissions = setOf(HealthConnectPermissions.WRITE_EXERCISE),
            insertThrows = true,
        )
        // Must not propagate: a completed, already-saved workout can't fail on export.
        publisher(client).publish(id)
        assertEquals(1, client.insertCallCount)
    }

    @Test
    fun missingSession_isANoOp() = runTest {
        val client = FakeHealthConnectClient(grantedPermissions = setOf(HealthConnectPermissions.WRITE_EXERCISE))
        publisher(client).publish(sessionId = 4242L)
        assertEquals(0, client.insertCallCount)
    }
}
