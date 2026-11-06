package cloud.trotter.log.strength.data

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import cloud.trotter.log.strength.data.db.StrengthDatabase
import cloud.trotter.log.strength.data.db.entity.SessionSetEntity
import cloud.trotter.log.strength.data.db.entity.Slot
import cloud.trotter.log.strength.data.db.entity.WorkoutSessionEntity
import cloud.trotter.log.strength.data.prefs.SettingsStore
import cloud.trotter.log.strength.data.serialization.PlannedTrackDto
import cloud.trotter.log.strength.data.serialization.SessionPlanJson
import cloud.trotter.log.strength.data.serialization.SetDto
import cloud.trotter.log.strength.domain.model.LoggedSet
import cloud.trotter.log.strength.domain.model.SetKind
import cloud.trotter.log.strength.domain.seeding.CarryForward
import cloud.trotter.log.strength.domain.units.WeightUnit
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
// These Android versions use SQLite's 999-parameter limit.
@SdkSuppress(minSdkVersion = 26, maxSdkVersion = 28)
class HistoryRepositoryLargeHistoryTest {
    private lateinit var db: StrengthDatabase
    private lateinit var repo: HistoryRepository
    private lateinit var storeScope: CoroutineScope
    private lateinit var prefsFile: File

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, StrengthDatabase::class.java).build()
        storeScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        prefsFile = File.createTempFile("large-history-settings", ".preferences_pb", context.cacheDir)
        val settings = SettingsStore(PreferenceDataStoreFactory.create(scope = storeScope) { prefsFile })
        repo = HistoryRepository(db, settings, PlanRepository(db, settings))
    }

    @After
    fun tearDown() {
        db.close()
        runBlocking { storeScope.coroutineContext.job.cancelAndJoin() }
        prefsFile.delete()
    }

    @Test
    fun progressionSessionsReadsMoreThan999SessionsAndKeepsTheOldestQualifyingEvidence() = runTest {
        val sessions = List(1_000) { index ->
            archivedSession(index.toLong(), if (index == 0) 55.0 else 60.0, if (index == 0) 5 else 4)
        }
        repo.importSessionHistory(sessions + listOf(
            archivedSession(2_000L, 100.0, 5, planId = 20L),
            archivedSession(3_000L, 100.0, 5, planId = null),
        ), emptyList())

        val result = repo.progressionSessions(10L, listOf("bb_back_squat", "unknown"))
        assertEquals(setOf("bb_back_squat"), result.keys)
        val evidence = result.getValue("bb_back_squat")
        assertEquals(1_000, evidence.size)
        assertEquals(listOf(LoggedSet(60.0, 4, SetKind.WORK, done = true, plannedIndex = 0)), evidence.first().actual)
        assertEquals(listOf(LoggedSet(55.0, 5, SetKind.WORK, done = true, plannedIndex = 0)), evidence.last().actual)
        val opening = listOf(LoggedSet(45.0, 5, SetKind.WORK))
        assertEquals(55.0, CarryForward.offer(opening, WeightUnit.LB, evidence)!!, 0.0)
    }

    private fun archivedSession(completedAt: Long, weightLb: Double, reps: Int, planId: Long? = 10L): ImportedSession {
        val planned = listOf(LoggedSet(50.0, 5, SetKind.WORK))
        return ImportedSession(
            WorkoutSessionEntity(
                id = 0, dayId = "B", dayTitle = "Squat", startedAt = null, completedAt = completedAt,
                bodyweightLb = null, planId = planId,
                plannedJson = SessionPlanJson.encode(listOf(
                    PlannedTrackDto(0, "bb_back_squat", Slot.MAIN, sets = planned.map(SetDto::of)),
                )),
            ),
            listOf(SessionSetEntity(
                id = 0, sessionId = 0, exerciseId = "bb_back_squat", exerciseName = "Squat", slot = Slot.MAIN,
                setIndex = 0, kind = SetKind.WORK.name, weightLb = weightLb, reps = reps, done = true,
                trackPosition = 0, plannedIndex = 0,
            )),
        )
    }
}
