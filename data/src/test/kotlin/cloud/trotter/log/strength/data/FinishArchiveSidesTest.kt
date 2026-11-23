package cloud.trotter.log.strength.data

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
import cloud.trotter.log.strength.domain.model.SetStructure
import cloud.trotter.log.strength.data.mapping.sides
import cloud.trotter.log.strength.domain.model.SideResult
import cloud.trotter.log.strength.domain.model.Sides
import java.io.File
import kotlin.test.assertNull
import kotlin.test.assertEquals
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class FinishArchiveSidesTest {
    private lateinit var db: StrengthDatabase
    private lateinit var scope: CoroutineScope
    private lateinit var repo: TrackerRepository
    private lateinit var history: HistoryRepository

    @Before fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, StrengthDatabase::class.java).allowMainThreadQueries().build()
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val settings = SettingsStore(PreferenceDataStoreFactory.create(scope = scope) {
            File.createTempFile("finish-sides", ".preferences_pb")
        })
        repo = TrackerRepository(db, db.programDao(), db.sessionDao(), db.customExerciseDao(), settings)
        history = HistoryRepository(db = db, sessionDao = db.sessionDao(), customExerciseDao = db.customExerciseDao(),
            settings = settings, plans = PlanRepository(db, settings))
    }

    @After fun tearDown() { db.close(); scope.cancel() }

    private val sides = Sides(SideResult(0.0, 0, 40), SideResult(0.0, 0, 52))

    private suspend fun archive(): Long {
        repo.replaceProgram(Program(listOf(ProgramDay("A", "Day A", "", listOf(
            ProgramExercise("side_plank", structure = SetStructure.STRAIGHT, targetSets = 2),
        )))))
        val slot = db.programDao().allExercises().single()
        val day = db.programDao().allDays().single()
        val result = repo.writeTestRows(day.nextRunId, "A", slot.id, Slot.MAIN, listOf(
            LoggedSet(0.0, 0, SetKind.WORK, done = true, seconds = 40, sides = sides),
            LoggedSet(0.0, 0, SetKind.WORK, done = true, seconds = 30),
        ))
        check(result is RunWriteResult.InRun)
        return requireNotNull(repo.finishRun(repo.openRunFlow.first()!!.id))
    }

    @Test fun `finish archives both sides and the weaker value`() = runTest {
        val rows = db.sessionDao().setsForSession(archive())
        assertEquals(40, rows[0].seconds)
        assertEquals(sides, rows[0].sides())
        val plain = rows[1]
        assertNull(plain.sides())
        assertEquals(List<Any?>(6) { null }, listOf(plain.leftWeightLb, plain.leftReps, plain.leftSeconds,
            plain.rightWeightLb, plain.rightReps, plain.rightSeconds))
    }

    @Test fun `a History edit to a shared value moves both sides`() = runTest {
        val row = db.sessionDao().setsForSession(archive()).first()
        val written = history.updateSessionSet(row.copy(weightLb = 10.0))
        assertEquals(Sides(sides.left.copy(weightLb = 10.0), sides.right.copy(weightLb = 10.0)), written.sides())
        assertEquals(written, db.sessionDao().setById(row.id))
    }

    @Test fun `a History edit to a value they differ on drops them`() = runTest {
        val row = db.sessionDao().setsForSession(archive()).first()
        val written = history.updateSessionSet(row.copy(seconds = 45))
        assertEquals(45, written.seconds)
        assertNull(written.sides())
        assertEquals(List<Any?>(6) { null }, listOf(written.leftWeightLb, written.leftReps, written.leftSeconds,
            written.rightWeightLb, written.rightReps, written.rightSeconds))
        assertEquals(written, db.sessionDao().setById(row.id))
    }
}
