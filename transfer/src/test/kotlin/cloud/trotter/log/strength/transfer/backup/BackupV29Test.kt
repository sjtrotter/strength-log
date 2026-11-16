package cloud.trotter.log.strength.transfer.backup

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import cloud.trotter.log.strength.data.BackupRepository
import cloud.trotter.log.strength.data.TrackerRepository
import cloud.trotter.log.strength.data.db.StrengthDatabase
import cloud.trotter.log.strength.data.prefs.RestoreJournal
import cloud.trotter.log.strength.data.prefs.SettingsStore
import cloud.trotter.log.strength.data.serialization.PlanRecipeJson
import cloud.trotter.log.strength.data.serialization.PlanSettings
import cloud.trotter.log.strength.data.serialization.PlanSettingsJson
import cloud.trotter.log.strength.data.serialization.planSettings
import cloud.trotter.log.strength.domain.generator.ProgramGenerator
import cloud.trotter.log.strength.domain.generator.WizardAnswers
import cloud.trotter.log.strength.domain.model.Equipment
import cloud.trotter.log.strength.domain.model.ExperienceLevel
import cloud.trotter.log.strength.domain.model.LifterConfig
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
        try {
            val settings = SettingsStore(PreferenceDataStoreFactory.create(scope = scope) {
                File.createTempFile("v29-restore", ".preferences_pb")
            })
            val journal = RestoreJournal(PreferenceDataStoreFactory.create(scope = scope) {
                File.createTempFile("v29-journal", ".preferences_pb")
            }, settings)
            val repo = TrackerRepository(db, db.programDao(), db.sessionDao(), db.customExerciseDao(), settings)
            val backup = BackupRepository(db, settings, repo.plans, repo.history)
            val novice = WizardAnswers(equipment = setOf(Equipment.MACHINE), config = LifterConfig(
                level = ExperienceLevel.NOVICE, goalsLb = mapOf("hack_squat" to 150.0)))
            val id = repo.plans.createGeneratedPlan("Old", novice, ProgramGenerator.generate(novice))
            repo.plans.publishCreatedPlan(id, novice)
            val source = backup.exportSnapshot()
            val expected = source.plans.single().planSettings
            val decoded = codec.decode(codec.encode(source.toDocument()))
            val old = decoded.copy(schemaVersion = 28, plans = decoded.plans.map { it.copy(
                recipeJson = PlanRecipeJson.encode(novice), settingsJson = null,
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
        try {
            val settings = SettingsStore(PreferenceDataStoreFactory.create(scope = scope) {
                File.createTempFile("v18-restore", ".preferences_pb")
            })
            val journal = RestoreJournal(PreferenceDataStoreFactory.create(scope = scope) {
                File.createTempFile("v18-journal", ".preferences_pb")
            }, settings)
            val repo = TrackerRepository(db, db.programDao(), db.sessionDao(), db.customExerciseDao(), settings)
            val backup = BackupRepository(db, settings, repo.plans, repo.history)
            val answers = WizardAnswers(equipment = setOf(Equipment.DUMBBELL, Equipment.BENCH, Equipment.BODYWEIGHT),
                config = LifterConfig(level = ExperienceLevel.NOVICE, goalsLb = mapOf("goblet_squat" to 80.0)))
            val id = repo.plans.createGeneratedPlan("Dumbbells", answers, ProgramGenerator.generate(answers))
            val squats = db.programDao().exercisesForPlan(id).filter { it.exerciseId == "goblet_squat" }
            assertTrue(squats.isNotEmpty())
            for (slot in squats) {
                db.programDao().replaceExercise(slot.id, slot.exerciseId, SetStructure.STRAIGHT.name, 3)
                db.programDao().setRule(slot.id, SlotRuleJson.encode(SlotRule.defaults(SlotRole.ACCESSORY,
                    LiftGoal.STRENGTH, SetStructure.STRAIGHT, null, MovementPattern.SQUAT_BILATERAL)))
            }
            repo.plans.publishCreatedPlan(id, answers)
            val source = backup.exportSnapshot()
            val decoded = codec.decode(codec.encode(source.toDocument()))
            val old = decoded.copy(schemaVersion = 18, plans = decoded.plans.map { plan -> plan.copy(
                recipeJson = PlanRecipeJson.encode(answers), settingsJson = null,
                days = plan.days.map { day -> day.copy(exercises = day.exercises.map {
                    it.copy(ruleJson = null, partnerRuleJson = null)
                }) },
            ) })
            BackupService(backup, journal).import(codec.encode(old))
            val restored = assertNotNull(db.planDao().plan(id))
            assertNull(restored.recipeJson)
            assertEquals(source.plans.single().planSettings, restored.planSettings)
            assertEquals(ProgramGenerator.mainLiftIds(answers).toSet(), restored.planSettings.legacyMains)
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
