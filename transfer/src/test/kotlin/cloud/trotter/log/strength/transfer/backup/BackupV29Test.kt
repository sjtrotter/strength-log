package cloud.trotter.log.strength.transfer.backup

import cloud.trotter.log.strength.data.LifterProfile
import cloud.trotter.log.strength.data.OldWizardPlans
import cloud.trotter.log.strength.data.insertRuledOldWizardPlan
import cloud.trotter.log.strength.data.runLaunchFixups
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
import cloud.trotter.log.strength.data.serialization.PlanSettings
import cloud.trotter.log.strength.data.serialization.PlanSettingsJson
import cloud.trotter.log.strength.data.serialization.planSettings
import cloud.trotter.log.strength.data.serialization.SlotRuleJson
import cloud.trotter.log.strength.domain.model.MovementPattern
import cloud.trotter.log.strength.domain.model.SetStructure
import cloud.trotter.log.strength.domain.progression.LiftGoal
import cloud.trotter.log.strength.domain.progression.SlotRole
import cloud.trotter.log.strength.domain.progression.SlotRule
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BackupV29Test {
    private val codec = BackupCodec()
    private fun document() = BackupDocument(
        settings = SettingsBackup(200, 40, "INTERMEDIATE", weightUnit = "LB", wizardComplete = true,
            selectedPlanId = 1, bodyweightLbPrecise = 200.0),
        plans = listOf(PlanBackup(1, "Plan", "GENERATED", 1000, onToday = true,
            settingsJson = PlanSettingsJson.encode(PlanSettings(legacyMains = setOf("goblet_squat"))),
            suggestedDay = "A", days = listOf(ProgramDayBackup("A", "Full Body", "", nextRunId = "next",
                exercises = listOf(ProgramExerciseBackup(7, "goblet_squat", "STRAIGHT", 2, note = "", keepToPlan = false)))))),
    )

    @Test fun `a v29 document carries legacy mains`() {
        val source = document()
        val decoded = codec.decode(codec.encode(source))
        assertEquals(source.plans.single().settingsJson, decoded.plans.single().settingsJson)
        assertEquals(CURRENT_SCHEMA_VERSION, decoded.toSnapshot().toDocument().schemaVersion)
    }

    @Test fun `older documents cannot carry legacy mains`() {
        for (version in listOf(28, 26)) {
            val failure = assertFailsWith<BackupError.Inconsistent> {
                codec.decode(codec.encode(document().copy(schemaVersion = version)))
            }
            assertTrue(failure.message.orEmpty().contains("schema v29"))
        }
    }

    @Test fun `v29 rejects a blank legacy main`() {
        val source = document()
        val bad = source.copy(plans = source.plans.map { it.copy(settingsJson = """{"legacyMains":[""]}""") })
        assertFailsWith<BackupError.Inconsistent> { codec.decode(codec.encode(bad)) }
    }

    @Test fun `a v28 old wizard plan restores and converts`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, StrengthDatabase::class.java).allowMainThreadQueries().build()
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val cardioRunPrefs = CardioRunPrefs(PreferenceDataStoreFactory.create(scope = scope) {
            File.createTempFile("BackupV29Test-cardio", ".preferences_pb")
        })
        try {
            val settings = SettingsStore(PreferenceDataStoreFactory.create(scope = scope) {
                File.createTempFile("v29-restore", ".preferences_pb")
            })
            val journal = RestoreJournal(PreferenceDataStoreFactory.create(scope = scope) {
                File.createTempFile("v29-journal", ".preferences_pb")
            }, settings, cardioRunPrefs = cardioRunPrefs)
            settings.setLifterProfile(LifterProfile(235.0, 40))
            val repo = TrackerRepository(db, db.programDao(), db.sessionDao(), db.customExerciseDao(), settings)
            val backup = BackupRepository(db, settings, repo.plans, repo.history, cardioRunPrefs = cardioRunPrefs)
            val novice = OldWizardPlans.MACHINE_NOVICE
            val id = db.insertRuledOldWizardPlan(novice, settings)
            db.runLaunchFixups(settings)
            repo.plans.publishPlannedPlan(id)
            val source = backup.exportSnapshot()
            val expected = source.plans.single().planSettings
            val decoded = codec.decode(codec.encode(source.toDocument()))
            val old = decoded.copy(schemaVersion = 28, plans = decoded.plans.map { it.copy(
                recipeJson = novice.recipeJson, settingsJson = null,
            ) })
            BackupService(backup, journal).import(codec.encode(old))
            val restored = assertNotNull(db.planDao().plan(id))
            assertNull(restored.recipeJson)
            assertEquals(expected, restored.planSettings)
            assertEquals(source.exercises, db.programDao().allExercises())
            assertEquals(source.plans.single().planSettings.legacyMains, restored.planSettings.legacyMains)
        } finally {
            db.close()
            scope.cancel()
        }
    }

    @Test fun `a v18 straightened wizard main restores with the same membership`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, StrengthDatabase::class.java).allowMainThreadQueries().build()
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val cardioRunPrefs = CardioRunPrefs(PreferenceDataStoreFactory.create(scope = scope) {
            File.createTempFile("BackupV29Test-cardio", ".preferences_pb")
        })
        try {
            val settings = SettingsStore(PreferenceDataStoreFactory.create(scope = scope) {
                File.createTempFile("v18-restore", ".preferences_pb")
            })
            val journal = RestoreJournal(PreferenceDataStoreFactory.create(scope = scope) {
                File.createTempFile("v18-journal", ".preferences_pb")
            }, settings, cardioRunPrefs = cardioRunPrefs)
            settings.setLifterProfile(LifterProfile(235.0, 40))
            val repo = TrackerRepository(db, db.programDao(), db.sessionDao(), db.customExerciseDao(), settings)
            val backup = BackupRepository(db, settings, repo.plans, repo.history, cardioRunPrefs = cardioRunPrefs)
            val fixture = OldWizardPlans.DUMBBELL_NOVICE
            val id = db.insertRuledOldWizardPlan(fixture, settings, "Dumbbells")
            val squats = db.programDao().exercisesForPlan(id).filter { it.exerciseId == "goblet_squat" }
            assertTrue(squats.isNotEmpty())
            for (slot in squats) {
                db.programDao().replaceExercise(slot.id, slot.exerciseId, SetStructure.STRAIGHT.name, 3)
                db.programDao().setRule(slot.id, SlotRuleJson.encode(SlotRule.defaults(SlotRole.ACCESSORY,
                    LiftGoal.STRENGTH, SetStructure.STRAIGHT, null, MovementPattern.SQUAT_BILATERAL)))
            }
            db.runLaunchFixups(settings)
            repo.plans.publishPlannedPlan(id)
            val source = backup.exportSnapshot()
            val decoded = codec.decode(codec.encode(source.toDocument()))
            val old = decoded.copy(schemaVersion = 18, plans = decoded.plans.map { plan -> plan.copy(
                recipeJson = fixture.recipeJson, settingsJson = null,
                days = plan.days.map { day -> day.copy(exercises = day.exercises.map {
                    it.copy(ruleJson = null, partnerRuleJson = null)
                }) },
            ) })
            BackupService(backup, journal).import(codec.encode(old))
            val restored = assertNotNull(db.planDao().plan(id))
            assertNull(restored.recipeJson)
            assertEquals(source.plans.single().planSettings, restored.planSettings)
            assertEquals(fixture.mains.toSet(), restored.planSettings.legacyMains)
            assertEquals(80.0, restored.planSettings.goalsLb["goblet_squat"])
            val restoredSquats = db.programDao().exercisesForPlan(id).filter { it.exerciseId == "goblet_squat" }
            assertEquals(squats.map { it.id }, restoredSquats.map { it.id })
            assertTrue(restoredSquats.all { it.structure == SetStructure.STRAIGHT.name && it.prescriptionJson == null &&
                SlotRuleJson.decode(it.ruleJson)?.role == SlotRole.ACCESSORY })
        } finally {
            db.close()
            scope.cancel()
        }
    }
}
