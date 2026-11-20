package cloud.trotter.log.strength.transfer.backup

import cloud.trotter.log.strength.data.OldWizardPlans
import cloud.trotter.log.strength.data.insertRuledOldWizardPlan
import cloud.trotter.log.strength.data.runLaunchFixups
import cloud.trotter.log.strength.data.serialization.planSettings
import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import cloud.trotter.log.strength.data.BackupRepository
import cloud.trotter.log.strength.data.TrackerRepository
import cloud.trotter.log.strength.data.db.StrengthDatabase
import cloud.trotter.log.strength.data.prefs.CardioRunPrefs
import cloud.trotter.log.strength.data.prefs.RestoreJournal
import cloud.trotter.log.strength.data.prefs.SettingsStore
import cloud.trotter.log.strength.data.serialization.PlanRecipeJson
import cloud.trotter.log.strength.domain.units.WeightUnit
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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
import kotlin.test.assertFailsWith

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PlanGoalBackupTest {
    private lateinit var cardioRunPrefs: CardioRunPrefs
    private lateinit var db: StrengthDatabase
    private lateinit var scope: CoroutineScope
    private lateinit var settings: SettingsStore
    private lateinit var repository: TrackerRepository
    private lateinit var service: BackupService

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, StrengthDatabase::class.java).allowMainThreadQueries().build()
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        cardioRunPrefs = CardioRunPrefs(PreferenceDataStoreFactory.create(scope = scope) {
            File.createTempFile("PlanGoalBackupTest-cardio", ".preferences_pb")
        })
        settings = SettingsStore(PreferenceDataStoreFactory.create(scope = scope) {
            File.createTempFile("plan-goal-backup", ".preferences_pb")
        })
        val journal = RestoreJournal(PreferenceDataStoreFactory.create(scope = scope) {
            File.createTempFile("plan-goal-backup-journal", ".preferences_pb")
        }, settings, cardioRunPrefs = cardioRunPrefs)
        repository = TrackerRepository(db, db.programDao(), db.sessionDao(), db.customExerciseDao(), settings)
        service = BackupService(BackupRepository(db, settings, repository.plans, repository.history,
            cardioRunPrefs = cardioRunPrefs), journal)
    }

    @After
    fun tearDown() {
        db.close()
        scope.cancel()
    }

    private suspend fun createPlan(): Long {
        return db.insertRuledOldWizardPlan(OldWizardPlans.DEFAULT, settings, "Goals").also {
            db.runLaunchFixups(settings)
            repository.plans.publishPlannedPlan(it)
        }
    }

    @Test
    fun `saved goal boundaries export decode and restore in both units`() = runTest {
        val plan = createPlan()
        for ((unit, goalLb) in listOf(
            WeightUnit.LB to PlanRecipeJson.MAX_GOAL_LB,
            WeightUnit.KG to WeightUnit.KG.toLb(905.0),
            WeightUnit.KG to PlanRecipeJson.MAX_GOAL_LB,
        )) {
            settings.setUnit(unit)
            repository.plans.setPlanGoal(plan, "bb_back_squat", goalLb)

            val backup = service.export()
            val decoded = BackupCodec().decode(backup).toSnapshot()
            val saved = decoded.plans.single().planSettings
            val expected = mapOf("bb_back_squat" to goalLb)
            assertEquals(expected, saved.goalsLb)
            assertEquals(unit, decoded.unit)

            repository.plans.setPlanGoal(plan, "bb_back_squat", null)
            service.import(backup)

            assertEquals(expected, repository.plans.planSettingsFor(plan).first().goalsLb)
            assertEquals(backup, service.export())
        }
    }

    @Test
    fun `over-limit goal writes leave saved settings and backup decodable`() = runTest {
        val plan = createPlan()
        repository.plans.setPlanGoal(plan, "bb_back_squat", 200.0)
        for ((unit, display) in listOf(
            WeightUnit.LB to 2500.0,
            WeightUnit.LB to 2000.1,
            WeightUnit.KG to 907.2,
            WeightUnit.KG to 907.5,
        )) {
            settings.setUnit(unit)
            val before = service.export()

            assertFailsWith<IllegalArgumentException> {
                repository.plans.setPlanGoal(plan, "bb_back_squat", unit.toLb(display))
            }

            val backup = service.export()
            assertEquals(before, backup)
            val saved = BackupCodec().decode(backup).toSnapshot().plans.single().planSettings
            assertEquals(mapOf("bb_back_squat" to 200.0),
                saved.goalsLb)
        }
    }
}
