package cloud.trotter.log.strength.data

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.test.core.app.ApplicationProvider
import cloud.trotter.log.strength.data.db.StrengthDatabase
import cloud.trotter.log.strength.data.db.entity.CardioSessionEntity
import cloud.trotter.log.strength.data.mapping.toEntity
import cloud.trotter.log.strength.data.prefs.RestoreJournal
import cloud.trotter.log.strength.data.prefs.SettingsStore
import cloud.trotter.log.strength.domain.model.CardioActivity
import cloud.trotter.log.strength.domain.model.CardioBlock
import cloud.trotter.log.strength.domain.model.Program
import cloud.trotter.log.strength.domain.model.ProgramDay
import cloud.trotter.log.strength.domain.model.CardioRole
import cloud.trotter.log.strength.domain.model.CardioSetResult
import cloud.trotter.log.strength.domain.model.CardioSetSpec
import cloud.trotter.log.strength.domain.model.CardioSetStatus
import cloud.trotter.log.strength.domain.model.DistanceSource
import cloud.trotter.log.strength.domain.model.Measure
import java.io.File
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TrackerRepositoryCardioSetsTest {
    private lateinit var db: StrengthDatabase
    private lateinit var scope: CoroutineScope
    private lateinit var repo: TrackerRepository
    private lateinit var journal: RestoreJournal
    private val snapshotReads = Collections.synchronizedList(mutableListOf<Pair<String, Int?>>())
    private val snapshotQueries = listOf(
        "SELECT * FROM plan ORDER BY position, id",
        "SELECT * FROM custom_exercise ORDER BY id",
        "SELECT * FROM program_day ORDER BY planId, position",
        "SELECT * FROM program_exercise ORDER BY planId, dayId, position",
        "SELECT * FROM exercise_log ORDER BY planId, dayId, programExerciseId, slot",
        "SELECT * FROM workout_session ORDER BY id",
        "SELECT * FROM session_set ORDER BY id",
        "SELECT * FROM cardio_session ORDER BY id",
        "SELECT * FROM cardio_set_result ORDER BY cardioSessionId, position, id",
    )
    private val session = CardioSessionEntity(
        dayId = "A", mode = "OUTDOOR_RUN", hard = true, label = "Track",
        startedAt = 1_000, completedAt = 451_000, seconds = 450, stepsCompleted = 1,
        activity = "TRACK_RUN",
    )
    private val sets = listOf(
        CardioSetResult(CardioSetSpec(CardioRole.RECOVERY, Measure.Time(120)), CardioSetStatus.FINISHED, 120),
        CardioSetResult(
            CardioSetSpec(CardioRole.WORK, Measure.Distance(400), Measure.Time(105), hard = true),
            CardioSetStatus.SHORTENED, 100, 380, DistanceSource.MANUAL,
        ),
        CardioSetResult(CardioSetSpec(CardioRole.COOLDOWN, Measure.Time(300)), CardioSetStatus.SKIPPED, 0),
    )

    @Before fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val transactionSequence = AtomicInteger()
        val currentTransaction = ThreadLocal<Int?>()
        db = Room.inMemoryDatabaseBuilder(context, StrengthDatabase::class.java)
            .allowMainThreadQueries()
            // Unconfined records each query immediately on the issuing thread,
            // so BEGIN/END identify its actual transaction without scheduling races.
            .setQueryCallback(Dispatchers.Unconfined, object : RoomDatabase.QueryCallback {
                override fun onQuery(sqlQuery: String, bindArgs: List<Any?>) {
                    when {
                        sqlQuery.startsWith("BEGIN ") -> currentTransaction.set(transactionSequence.incrementAndGet())
                        sqlQuery == "END TRANSACTION" -> currentTransaction.remove()
                        sqlQuery in snapshotQueries -> snapshotReads.add(sqlQuery to currentTransaction.get())
                    }
                }
            })
            .build()
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val settings = SettingsStore(PreferenceDataStoreFactory.create(scope = scope) {
            File.createTempFile("cardio-sets", ".preferences_pb")
        })
        journal = RestoreJournal(PreferenceDataStoreFactory.create(scope = scope) {
            File.createTempFile("cardio-sets-journal", ".preferences_pb")
        }, settings)
        repo = TrackerRepository(db, db.programDao(), db.sessionDao(), db.customExerciseDao(), settings)
    }

    @After fun tearDown() { db.close(); scope.cancel() }

    @Test fun `three results round trip in order with null skipped distance`() = runTest {
        val id = repo.logCardioSession(session, sets)
        assertEquals(session.copy(id = id), repo.cardioSession(id))
        assertEquals(sets, repo.cardioSetsFor(id))
        assertEquals(listOf(0, 1, 2), db.cardioSetResultDao().forSession(id).map { it.position })
    }

    @Test fun `distance amendment preserves the session identity prescription and other results`() = runTest {
        val id = repo.logCardioSession(session, sets)
        val header = repo.cardioSession(id)
        val before = db.cardioSetResultDao().forSession(id)
        repo.amendCardioSetDistance(id, 1, 400)
        assertEquals(header, repo.cardioSession(id))
        assertEquals(sets[1].copy(metres = 400), repo.cardioSetsFor(id)[1])
        assertEquals(before[0], db.cardioSetResultDao().forSession(id)[0])
        assertEquals(before[2], db.cardioSetResultDao().forSession(id)[2])
        repo.amendCardioSetDistance(id, 1, null)
        assertNull(repo.cardioSetsFor(id)[1].metres)
        assertNull(repo.cardioSetsFor(id)[1].distanceSource)
    }

    @Test fun `set aware retry uses the existing day and start identity without appending results`() = runTest {
        val id = repo.logCardioSession(session, sets)
        assertEquals(id, repo.logCardioSession(session.copy(completedAt = 999_000), sets))
        assertEquals(1, db.cardioSessionDao().all().size)
        assertEquals(sets, repo.cardioSetsFor(id))
    }

    @Test fun `cardio completion advances the originating authored plan once even if another plan is selected`() = runTest {
        val planId = repo.createAuthoredPlan("Running", "Run")
        repo.publishCreatedAuthoredPlan(planId)
        val block = CardioBlock(CardioActivity.TRACK_RUN, sets.map { it.spec })
        repo.replaceProgram(Program(listOf("A", "B", "C").map {
            ProgramDay(it, "Run $it", "", emptyList(), null, cardioBlock = block)
        }))
        val otherPlan = repo.createAuthoredPlan("Other", "Other day")
        repo.publishCreatedAuthoredPlan(otherPlan)
        val origin = session.copy(planId = planId)
        val id = repo.logCardioSession(origin, sets, finishDay = true)
        assertEquals("B", db.planDao().plan(planId)?.suggestedDayId)
        assertEquals("A", db.planDao().plan(otherPlan)?.suggestedDayId)
        assertEquals(planId, repo.cardioSession(id)?.planId)
        assertEquals("Running", repo.cardioSession(id)?.planName)
        assertEquals(id, repo.logCardioSession(origin, sets, finishDay = true))
        assertEquals("B", db.planDao().plan(planId)?.suggestedDayId)
    }

    @Test fun `a second device completion for A logs without advancing its rotation again`() = runTest {
        val planId = repo.createAuthoredPlan("Running", "Run")
        repo.publishCreatedAuthoredPlan(planId)
        val block = CardioBlock(CardioActivity.TRACK_RUN, sets.map { it.spec })
        repo.replaceProgram(Program(listOf("A", "B", "C").map {
            ProgramDay(it, "Run $it", "", emptyList(), null, cardioBlock = block)
        }))
        val first = repo.logCardioSession(session.copy(planId = planId), sets, finishDay = true)
        assertEquals("B", db.planDao().plan(planId)?.suggestedDayId)
        val second = repo.logCardioSession(session.copy(planId = planId, startedAt = 2_000), sets, finishDay = true)
        assertTrue(first != second)
        assertEquals(2, db.cardioSessionDao().all().size)
        assertEquals("B", db.planDao().plan(planId)?.suggestedDayId)
        // A later phone completion of A must use the same guard after B advances.
        repo.logCardioSession(session.copy(planId = planId, dayId = "B", startedAt = 500_000,
            completedAt = 950_000), sets, finishDay = true)
        assertEquals("C", db.planDao().plan(planId)?.suggestedDayId)
        repo.logCardioSession(session.copy(planId = planId, startedAt = 3_000), sets, finishDay = true)
        assertEquals("C", db.planDao().plan(planId)?.suggestedDayId)
    }

    @Test fun `invalid stored set rows are skipped while valid results keep their order`() = runTest {
        val id = repo.logCardioSession(session)
        val valid = sets[1].toEntity(id, 0)
        val invalidRows = listOf(
            valid.copy(specJson = "not json"),
            valid.copy(specJson = "{}"),
            valid.copy(specJson = """{"role":"WORK","end":{"type":"time","seconds":0}}"""),
            valid.copy(status = "FUTURE"),
            valid.copy(distanceSource = "FUTURE"),
            valid.copy(elapsedSeconds = -1),
            valid.copy(metres = -1),
            valid.copy(metres = null),
            valid.copy(distanceSource = null),
        ).mapIndexed { index, row -> row.copy(position = index + 1) }
        // Insert out of position order so the assertion also exercises DAO ordering.
        db.cardioSetResultDao().insertAll(
            listOf(sets[2].toEntity(id, invalidRows.size + 2)) + invalidRows.reversed() +
                listOf(sets[0].toEntity(id, 0), sets[1].toEntity(id, invalidRows.size + 1)),
        )

        assertEquals(sets, repo.cardioSetsFor(id))
        assertEquals(invalidRows.size + sets.size, db.cardioSetResultDao().forSession(id).size)
    }

    @Test fun `all Room snapshot lists are read in one transaction in the original order`() = runTest {
        repo.logCardioSession(session, sets)
        // Finish lazy database initialization and the legacy preferences fixup
        // before recording only the export's snapshot reads.
        repo.exportSnapshot()
        snapshotReads.clear()

        repo.exportSnapshot()

        val reads = snapshotReads.toList()
        assertEquals(snapshotQueries, reads.map { it.first })
        val transaction = checkNotNull(reads.first().second)
        assertTrue(reads.all { it.second == transaction })
    }

    @Test fun `summary callers keep exactly one session and no sets`() = runTest {
        val legacy = session.copy(activity = null)
        val id = repo.logCardioSession(legacy)
        assertEquals(listOf(legacy.copy(id = id)), db.cardioSessionDao().all())
        assertEquals(emptyList<CardioSetResult>(), repo.cardioSetsFor(id))
        assertNull(repo.cardioSession(id)?.activity)
    }

    @Test fun `delete and undo carry exact rows without touching another session`() = runTest {
        val id = repo.logCardioSession(session, sets)
        val other = repo.logCardioSession(session.copy(startedAt = 2_000), sets)
        val originalRows = db.cardioSetResultDao().forSession(id)
        val deleted = checkNotNull(repo.deleteCardioSession(id))
        assertEquals(originalRows, deleted.sets)
        assertNull(repo.cardioSession(id))
        assertEquals(emptyList<CardioSetResult>(), repo.cardioSetsFor(id))
        assertEquals(sets, repo.cardioSetsFor(other))
        assertNull(repo.deleteCardioSession(id))
        repo.restoreCardioSession(deleted)
        assertEquals(deleted.session, repo.cardioSession(id))
        assertEquals(originalRows, db.cardioSetResultDao().forSession(id))
        assertEquals(sets, repo.cardioSetsFor(id))
    }

    @Test fun `full restore replaces sets and restores their original ids`() = runTest {
        val id = repo.logCardioSession(session, sets)
        val snapshot = repo.exportSnapshot()
        repo.logCardioSession(session.copy(startedAt = 2_000), sets)
        repo.importSnapshot(snapshot, journal)
        assertEquals(snapshot.cardioSessions, db.cardioSessionDao().all())
        assertEquals(snapshot.cardioSetResults, db.cardioSetResultDao().all())
        assertEquals(sets, repo.cardioSetsFor(id))
        repo.importSnapshot(snapshot.copy(cardioSetResults = emptyList()), journal)
        assertEquals(snapshot.cardioSessions, db.cardioSessionDao().all())
        assertEquals(emptyList<CardioSetResult>(), repo.cardioSetsFor(id))
        assertTrue(db.cardioSetResultDao().all().isEmpty())
    }

    @Test fun `a set insert failure rolls back both the header and preceding sets`() = runTest {
        db.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER reject_second_set BEFORE INSERT ON cardio_set_result " +
                "WHEN NEW.position = 1 BEGIN SELECT RAISE(ABORT, 'set write failed'); END",
        )
        val failure = runCatching { repo.logCardioSession(session, sets) }.exceptionOrNull()
        assertTrue(checkNotNull(failure).message.orEmpty().contains("set write failed"))
        assertTrue(db.cardioSessionDao().all().isEmpty())
        assertTrue(db.cardioSetResultDao().all().isEmpty())
    }
}
