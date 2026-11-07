package cloud.trotter.log.strength.data

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import cloud.trotter.log.strength.data.db.StrengthDatabase
import cloud.trotter.log.strength.data.prefs.SettingsStore
import cloud.trotter.log.strength.domain.model.Program
import cloud.trotter.log.strength.domain.model.ProgramDay
import cloud.trotter.log.strength.domain.model.ProgramExercise
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PlanOfferSwitchesTest {
    @Test fun `offer switches persist without changing working values or slot choices`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, StrengthDatabase::class.java).allowMainThreadQueries().build()
        val scope = CoroutineScope(Dispatchers.IO + Job())
        val settings = SettingsStore(PreferenceDataStoreFactory.create(scope = scope) {
            File.createTempFile("plan-offers", ".preferences_pb")
        })
        val repo = TrackerRepository(db, db.programDao(), db.sessionDao(), db.customExerciseDao(), settings)
        try {
            repo.replaceProgram(Program(listOf(ProgramDay("A", "Squat", "", listOf(ProgramExercise("bb_back_squat"))))))
            val plan = repo.plans.selectedPlanIdFlow.first()!!
            assertTrue(db.planDao().plan(plan)!!.offerStrengthIncreases)
            assertFalse(db.planDao().plan(plan)!!.offerCardioIncreases)
            repo.ensureSeeded(plan, "A")
            val logs = db.programDao().allLogs()
            val slots = db.programDao().allExercises()
            repo.plans.setOfferStrengthIncreases(plan, false)
            repo.plans.setOfferCardioIncreases(plan, true)
            assertFalse(db.planDao().plan(plan)!!.offerStrengthIncreases)
            assertTrue(db.planDao().plan(plan)!!.offerCardioIncreases)
            assertEquals(logs, db.programDao().allLogs())
            assertEquals(slots, db.programDao().allExercises())
            repo.plans.setOfferStrengthIncreases(plan, true)
            repo.plans.setOfferCardioIncreases(plan, false)
            assertTrue(repo.plans.plansFlow.first().single().offerStrengthIncreases)
            assertFalse(repo.plans.plansFlow.first().single().offerCardioIncreases)
        } finally { db.close(); scope.cancel() }
    }
}
