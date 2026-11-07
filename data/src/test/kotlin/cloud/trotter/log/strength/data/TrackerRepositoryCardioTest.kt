package cloud.trotter.log.strength.data

import cloud.trotter.log.strength.data.mapping.toDomain
import cloud.trotter.log.strength.data.serialization.CardioBlockJson
import cloud.trotter.log.strength.domain.model.CardioActivity
import cloud.trotter.log.strength.domain.model.CardioBlock
import cloud.trotter.log.strength.domain.model.CardioRole
import cloud.trotter.log.strength.domain.model.CardioSetSpec
import cloud.trotter.log.strength.domain.model.Measure
import cloud.trotter.log.strength.domain.model.Program
import cloud.trotter.log.strength.domain.model.ProgramDay
import cloud.trotter.log.strength.domain.model.ProgressMeasure
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import cloud.trotter.log.strength.data.db.StrengthDatabase
import cloud.trotter.log.strength.data.db.entity.CardioSessionEntity
import cloud.trotter.log.strength.data.prefs.SettingsStore
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TrackerRepositoryCardioTest {
    @Test fun `unknown mode stays unknown for typed consumers`() {
        assertNull(cardio("Future", 2_000).copy(mode = "FUTURE_MODE").decodedCardioMode())
    }

    @Test fun `cardio write commits and flow reads newest first`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, StrengthDatabase::class.java).allowMainThreadQueries().build()
        val scope = CoroutineScope(Dispatchers.IO + Job())
        val settings = SettingsStore(PreferenceDataStoreFactory.create(scope = scope) {
            File.createTempFile("cardio-repo", ".preferences_pb")
        })
        val plans = PlanRepository(db, settings)
        val history = HistoryRepository(db, settings, plans)
        val repo = TrackerRepository(db, db.programDao(), db.sessionDao(), db.customExerciseDao(), settings, plans = plans, history = history)
        try {
            repo.logCardioSession(cardio("Easy Zone 2", 2_000))
            val id = repo.logCardioSession(cardio("Tempo", 3_000))
            repo.logCardioSession(cardio("Later id at same time", 3_000))
            assertEquals(
                listOf("Later id at same time", "Tempo", "Easy Zone 2"),
                history.cardioSessionsFlow.first().map { it.label },
            )
            assertEquals("Tempo", history.cardioSession(id)?.label)
        } finally { db.close(); scope.cancel() }
    }

    private fun block(seconds: Int) = CardioBlock(CardioActivity.ROAD_RUN,
        listOf(CardioSetSpec(CardioRole.WORK, Measure.Time(seconds))))

    private fun workingTest(body: suspend (TrackerRepository, StrengthDatabase, Long) -> Unit) = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, StrengthDatabase::class.java).allowMainThreadQueries().build()
        val scope = CoroutineScope(Dispatchers.IO + Job())
        val settings = SettingsStore(PreferenceDataStoreFactory.create(scope = scope) {
            File.createTempFile("cardio-working", ".preferences_pb")
        })
        val repo = TrackerRepository(db, db.programDao(), db.sessionDao(), db.customExerciseDao(), settings)
        try {
            repo.replaceProgram(Program(listOf(ProgramDay("A", "Run", "", emptyList(), cardioBlock = block(1200)))))
            body(repo, db, repo.plans.selectedPlanIdFlow.first()!!)
        } finally { db.close(); scope.cancel() }
    }

    @Test fun `applying an offer writes the working block and the next run uses it`() = workingTest { repo, db, plan ->
        assertTrue(repo.setCardioWorking(plan, "A", block(1260)))
        val day = db.programDao().day(plan, "A")!!.toDomain(emptyList())
        assertEquals(block(1260), day.cardioToRun)
        assertEquals(block(1200), day.cardioBlock)
    }

    @Test fun `Plan parks the working block and Progress restores it`() = workingTest { repo, db, plan ->
        repo.setCardioWorking(plan, "A", block(1260))
        val working = db.programDao().day(plan, "A")!!.cardioWorkingJson
        repo.setCardioKeepToPlan(plan, "A", true)
        assertEquals(block(1200), db.programDao().day(plan, "A")!!.toDomain(emptyList()).cardioToRun)
        assertEquals(working, db.programDao().day(plan, "A")!!.cardioWorkingJson)
        repo.setCardioKeepToPlan(plan, "A", false)
        assertEquals(block(1260), db.programDao().day(plan, "A")!!.toDomain(emptyList()).cardioToRun)
    }

    @Test fun `editing the plan block clears the working block`() = workingTest { repo, db, plan ->
        repo.setCardioWorking(plan, "A", block(1260))
        assertTrue(repo.setCardioBlock(plan, "A", block(1500)))
        val day = db.programDao().day(plan, "A")!!.toDomain(emptyList())
        assertNull(day.cardioProgress.working)
        assertEquals(block(1500), day.cardioToRun)
    }

    @Test fun `a working block with other roles is refused`() = workingTest { repo, db, plan ->
        val added = block(1260).copy(sets = block(1260).sets + CardioSetSpec(CardioRole.RECOVERY, Measure.Time(60)))
        assertFalse(repo.setCardioWorking(plan, "A", added))
        assertFalse(repo.setCardioWorking(plan, "A", block(1260).copy(activity = CardioActivity.ROWER)))
        assertNull(db.programDao().day(plan, "A")!!.cardioWorkingJson)
        repo.setCardioWorking(plan, "A", block(1260))
        repo.setCardioKeepToPlan(plan, "A", true)
        assertFalse(repo.setCardioWorking(plan, "A", block(1300)))
        assertEquals(block(1260), CardioBlockJson.decode(db.programDao().day(plan, "A")!!.cardioWorkingJson))
        assertFalse(repo.setCardioWorking(plan, "missing", block(1260)))
    }

    @Test fun `writing the plan's own numbers stores null`() = workingTest { repo, db, plan ->
        repo.setCardioWorking(plan, "A", block(1260))
        assertTrue(repo.setCardioWorking(plan, "A", block(1200)))
        assertNull(db.programDao().day(plan, "A")!!.cardioWorkingJson)
    }

    @Test fun `progression writes clamp the percent and preserve the choice`() = workingTest { repo, db, plan ->
        repo.setCardioProgression(plan, "A", ProgressMeasure.TIME, 50)
        var progress = db.programDao().day(plan, "A")!!.toDomain(emptyList()).cardioProgress
        assertEquals(ProgressMeasure.TIME, progress.measure)
        assertEquals(25, progress.increasePercent)
        repo.setCardioProgression(plan, "A", ProgressMeasure.DISTANCE, 0)
        progress = db.programDao().day(plan, "A")!!.toDomain(emptyList()).cardioProgress
        assertEquals(ProgressMeasure.DISTANCE, progress.measure)
        assertEquals(1, progress.increasePercent)
    }

    private fun cardio(label: String, completedAt: Long) = CardioSessionEntity(
        dayId = "A", mode = "OUTDOOR_RUN", hard = false, label = label,
        startedAt = completedAt - 60_000, completedAt = completedAt, seconds = 60, stepsCompleted = 1,
    )
}
