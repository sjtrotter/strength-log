package cloud.trotter.log.strength.transfer.health

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import cloud.trotter.log.strength.data.ImportedSession
import cloud.trotter.log.strength.data.TrackerRepository
import cloud.trotter.log.strength.data.db.StrengthDatabase
import cloud.trotter.log.strength.data.db.entity.SessionSetEntity
import cloud.trotter.log.strength.data.db.entity.Slot
import cloud.trotter.log.strength.data.db.entity.WorkoutSessionEntity
import cloud.trotter.log.strength.data.prefs.SettingsStore
import cloud.trotter.log.strength.domain.model.SetKind
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
 *
 * The backfill (#159) shares that path, so its own section pins what only it
 * cares about: which sessions it writes, that a re-run deduplicates instead of
 * duplicating, and that it answers honestly when a session didn't make it.
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

    private suspend fun seedSession(sets: List<SessionSetEntity>, session: WorkoutSessionEntity = session()): Long {
        repo.importSessionHistory(
            listOf(ImportedSession(session, sets)),
            newCustomExercises = emptyList(),
        )
        return repo.sessionSummariesFlow.first().first().session.id
    }

    private fun session(
        startedAt: Long? = null,
        completedAt: Long = 10_000L,
        bodyweightLb: Int? = 180,
    ) = WorkoutSessionEntity(
        id = 0, dayId = "A", dayTitle = "Lower", startedAt = startedAt, completedAt = completedAt, bodyweightLb = bodyweightLb,
    )

    private fun set(exerciseId: String, done: Boolean = true) = SessionSetEntity(
        id = 0, sessionId = 0, exerciseId = exerciseId, exerciseName = exerciseId, slot = Slot.MAIN,
        setIndex = 0, kind = SetKind.WORK.name, weightLb = 100.0, reps = 8, done = done,
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
    fun allUndoneSession_doesNotInsert() = runTest {
        // Sets exist but nothing was checked off — nothing was performed, so the
        // session must not be written to the user's shared health record.
        val id = seedSession(listOf(set("bb_back_squat", done = false), set("bb_bench", done = false)))
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

    // --- HC calories (session-start-calories brief) -----------------------------

    @Test
    fun caloriesGranted_withRealStartInWindow_insertsBothRecords() = runTest {
        val id = seedSession(
            listOf(set("bb_back_squat")),
            session(startedAt = 10_000L, completedAt = 10_000L + 30 * 60_000L, bodyweightLb = 200), // 30 minutes
        )
        val client = FakeHealthConnectClient(
            grantedPermissions = setOf(HealthConnectPermissions.WRITE_EXERCISE, HealthConnectPermissions.WRITE_CALORIES),
        )
        publisher(client).publish(id)

        assertEquals(1, client.insertCallCount) // one batched insert call
        assertEquals(2, client.insertedRecords.size)
        assertTrue(client.insertedRecords.any { it is ExerciseSessionRecord })
        assertTrue(client.insertedRecords.any { it is ActiveCaloriesBurnedRecord })
    }

    @Test
    fun caloriesPermissionNotGranted_onlyExerciseRecordInserted() = runTest {
        val id = seedSession(
            listOf(set("bb_back_squat")),
            session(startedAt = 10_000L, completedAt = 10_000L + 30 * 60_000L),
        )
        // Exercise-write granted, calories-write withheld — degrades per permission.
        val client = FakeHealthConnectClient(grantedPermissions = setOf(HealthConnectPermissions.WRITE_EXERCISE))
        publisher(client).publish(id)

        assertEquals(1, client.insertedRecords.size)
        assertTrue(client.insertedRecords.first() is ExerciseSessionRecord)
    }

    @Test
    fun caloriesGranted_butNoRecordedStart_onlyExerciseRecordInserted() = runTest {
        // startedAt null (the synthesized-window case) — the session record still
        // writes, but calories must never be estimated from a fabricated window.
        val id = seedSession(listOf(set("bb_back_squat")), session(startedAt = null))
        val client = FakeHealthConnectClient(
            grantedPermissions = setOf(HealthConnectPermissions.WRITE_EXERCISE, HealthConnectPermissions.WRITE_CALORIES),
        )
        publisher(client).publish(id)

        assertEquals(1, client.insertedRecords.size)
        assertTrue(client.insertedRecords.first() is ExerciseSessionRecord)
    }

    @Test
    fun caloriesGranted_butDurationOutsideSanityWindow_onlyExerciseRecordInserted() = runTest {
        // Ticked-yesterday-finished-today shape: 7 hours, past the 6-hour ceiling.
        val id = seedSession(
            listOf(set("bb_back_squat")),
            session(startedAt = 0L, completedAt = 7 * 3_600_000L),
        )
        val client = FakeHealthConnectClient(
            grantedPermissions = setOf(HealthConnectPermissions.WRITE_EXERCISE, HealthConnectPermissions.WRITE_CALORIES),
        )
        publisher(client).publish(id)

        assertEquals(1, client.insertedRecords.size)
        assertTrue(client.insertedRecords.first() is ExerciseSessionRecord)
    }

    @Test
    fun caloriesRecordClientRecordIdIsStableAndDistinctFromTheSessionRecords() = runTest {
        val id = seedSession(
            listOf(set("bb_back_squat")),
            session(startedAt = 10_000L, completedAt = 10_000L + 30 * 60_000L),
        )
        val client = FakeHealthConnectClient(
            grantedPermissions = setOf(HealthConnectPermissions.WRITE_EXERCISE, HealthConnectPermissions.WRITE_CALORIES),
        )
        publisher(client).publish(id)

        val exercise = client.insertedRecords.filterIsInstance<ExerciseSessionRecord>().single()
        val calories = client.insertedRecords.filterIsInstance<ActiveCaloriesBurnedRecord>().single()
        assertEquals(CaloriesRecordMapper.clientRecordId(id), calories.metadata.clientRecordId)
        assertEquals(SessionRecordMapper.clientRecordId(id), exercise.metadata.clientRecordId)
        assertTrue(calories.metadata.clientRecordId != exercise.metadata.clientRecordId)
    }

    // --- backfill (#159) ---------------------------------------------------------

    /** [count] sessions, oldest to newest, each with one done set. */
    private suspend fun seedSessions(count: Int): List<Long> {
        repo.importSessionHistory(
            (1..count).map { i ->
                ImportedSession(session(completedAt = 10_000L * i), listOf(set("bb_back_squat")))
            },
            newCustomExercises = emptyList(),
        )
        return repo.sessionSummariesFlow.first().map { it.session.id }
    }

    private fun grantedClient(insertThrows: Boolean = false) = FakeHealthConnectClient(
        grantedPermissions = setOf(HealthConnectPermissions.WRITE_EXERCISE, HealthConnectPermissions.WRITE_CALORIES),
        insertThrows = insertThrows,
    )

    @Test
    fun backfillPublishesEverySessionUnderItsOwnClientRecordId() = runTest {
        val ids = seedSessions(3)
        val client = grantedClient()

        assertTrue(publisher(client).publishAll(ids))

        assertEquals(3, client.insertCallCount)
        assertEquals(
            ids.map { SessionRecordMapper.clientRecordId(it) }.toSet(),
            client.storedRecords.keys,
        )
    }

    /** Idempotency: the ids are derived from the session, not from when the
     *  publish happened, so a second backfill lands on the same records. */
    @Test
    fun republishingTheSameSessionsDeduplicatesByClientIdRatherThanDuplicating() = runTest {
        val ids = seedSessions(2)
        val client = grantedClient()

        assertTrue(publisher(client).publishAll(ids))
        assertTrue(publisher(client).publishAll(ids))

        assertEquals(4, client.insertCallCount)
        assertEquals(2, client.storedRecords.size)
        // Every write keeps client record version 0 — the record a session
        // produces is deterministic, so no write is ever "newer" than another.
        assertTrue(client.storedRecords.values.all { it.metadata.clientRecordVersion == 0L })
    }

    @Test
    fun replacingAnEditedSessionDeletesStableClientIdsBeforeReinserting() = runTest {
        val id = seedSession(listOf(set("bb_back_squat")))
        val client = grantedClient()
        val publisher = publisher(client)
        publisher.publish(id)

        publisher.replace(id)

        assertTrue(SessionRecordMapper.clientRecordId(id) in client.deletedClientRecordIds)
        assertTrue(CaloriesRecordMapper.clientRecordId(id) in client.deletedClientRecordIds)
        assertTrue(SessionRecordMapper.clientRecordId(id) in client.storedRecords)
    }

    @Test
    fun deletingASessionDeletesItsHealthConnectRecordsByStableClientId() = runTest {
        val id = seedSession(listOf(set("bb_back_squat")))
        val client = grantedClient()
        val publisher = publisher(client)
        publisher.publish(id)

        publisher.delete(id)

        assertFalse(SessionRecordMapper.clientRecordId(id) in client.storedRecords)
        assertTrue(SessionRecordMapper.clientRecordId(id) in client.deletedClientRecordIds)
    }

    /** A session that predates the bodyweight capture (#171, and every CSV
     *  import) still belongs in the backfill — it just has no calorie estimate
     *  to publish alongside it. */
    @Test
    fun backfillOfASessionWithNoRecordedBodyweightPublishesWithoutCalories() = runTest {
        val id = seedSession(
            listOf(set("bb_back_squat")),
            session(startedAt = 10_000L, completedAt = 10_000L + 30 * 60_000L, bodyweightLb = null),
        )
        val client = grantedClient()

        assertTrue(publisher(client).publishAll(listOf(id)))

        assertEquals(1, client.insertedRecords.size)
        assertTrue(client.insertedRecords.single() is ExerciseSessionRecord)
    }

    /** Nothing ticked off is nothing to publish, not a failure — otherwise one
     *  abandoned session would block the one-shot backfill forever. */
    @Test
    fun backfillSucceedsOverASessionWithNothingTickedOff() = runTest {
        val skipped = seedSession(listOf(set("bb_back_squat", done = false)))
        val client = grantedClient()

        assertTrue(publisher(client).publishAll(listOf(skipped)))
        assertEquals(0, client.insertCallCount)
    }

    @Test
    fun backfillWithoutTheWriteGrantReportsFailureAndWritesNothing() = runTest {
        val ids = seedSessions(2)
        val client = FakeHealthConnectClient(grantedPermissions = emptySet())

        assertFalse(publisher(client).publishAll(ids))
        assertEquals(0, client.insertCallCount)
    }

    @Test
    fun backfillWithNoProviderReportsFailure() = runTest {
        val ids = seedSessions(2)
        assertFalse(publisher(client = null).publishAll(ids))
    }

    /** Partial failure must be reported, so the caller can leave the offer
     *  standing instead of recording a backfill that didn't happen. */
    @Test
    fun backfillReportsFailureWhenTheProviderRefusesAnInsert() = runTest {
        val ids = seedSessions(2)
        val client = grantedClient(insertThrows = true)

        assertFalse(publisher(client).publishAll(ids))
        // Every session was still attempted — one bad insert doesn't abandon the rest.
        assertEquals(2, client.insertCallCount)
    }
}
