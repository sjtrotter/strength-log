package cloud.trotter.log.strength.data

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import cloud.trotter.log.strength.data.db.StrengthDatabase
import cloud.trotter.log.strength.data.prefs.SettingsStore
import cloud.trotter.log.strength.data.serialization.PlanRecipeJson
import cloud.trotter.log.strength.data.serialization.PrescriptionJson
import cloud.trotter.log.strength.data.serialization.SlotRuleJson
import cloud.trotter.log.strength.domain.generator.CardioFirst
import cloud.trotter.log.strength.domain.generator.ProgramGenerator
import cloud.trotter.log.strength.domain.generator.SplitTemplate
import cloud.trotter.log.strength.domain.generator.WizardAnswers
import cloud.trotter.log.strength.domain.model.Equipment
import cloud.trotter.log.strength.domain.model.ExperienceLevel
import cloud.trotter.log.strength.domain.model.LifterConfig
import cloud.trotter.log.strength.domain.model.PrescribedTarget
import cloud.trotter.log.strength.domain.model.Program
import cloud.trotter.log.strength.domain.model.ProgramDayKind
import cloud.trotter.log.strength.domain.model.ProgramExercise
import cloud.trotter.log.strength.domain.progression.SlotRule
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class OldWizardPlansTest {
    private val answers = linkedMapOf(
        OldWizardPlans.DEFAULT to WizardAnswers(),
        OldWizardPlans.MACHINE_NOVICE to WizardAnswers(equipment = setOf(Equipment.MACHINE),
            config = LifterConfig(level = ExperienceLevel.NOVICE, goalsLb = mapOf("hack_squat" to 150.0))),
        OldWizardPlans.DUMBBELL_NOVICE to WizardAnswers(
            equipment = setOf(Equipment.DUMBBELL, Equipment.BENCH, Equipment.BODYWEIGHT),
            config = LifterConfig(level = ExperienceLevel.NOVICE, goalsLb = mapOf("goblet_squat" to 80.0))),
        OldWizardPlans.DUMBBELL_INTERMEDIATE to WizardAnswers(
            equipment = setOf(Equipment.DUMBBELL, Equipment.BENCH, Equipment.BODYWEIGHT),
            config = LifterConfig(level = ExperienceLevel.INTERMEDIATE, goalsLb = mapOf("goblet_squat" to 80.0))),
        OldWizardPlans.UPPER_LOWER_MACHINE_NOVICE to WizardAnswers(daysPerWeek = 4, split = SplitTemplate.UPPER_LOWER,
            equipment = setOf(Equipment.MACHINE), config = LifterConfig(level = ExperienceLevel.NOVICE)),
        OldWizardPlans.PPL to WizardAnswers(daysPerWeek = 6, split = SplitTemplate.PPL),
        OldWizardPlans.CARDIO_FIRST to WizardAnswers(daysPerWeek = 3, cardioFirst = CardioFirst(liftDays = 3)),
    )

    @Test fun `each fixture is what the old wizard wrote`() {
        assertEquals(OldWizardPlans.ALL, answers.keys.toList())
        for ((fixture, answer) in answers) {
            assertEquals(PlanRecipeJson.encode(answer), fixture.recipeJson)
            val generated = ProgramGenerator.generate(answer)
            assertEquals(generated, fixture.program, render(generated))
            assertEquals(ProgramGenerator.mainLiftIds(answer), fixture.mains)
            assertEquals(answer.equipment, fixture.equipment)
            assertEquals(answer.config.goalsLb, fixture.goalsLb)
            assertEquals(answer.cardioFirst != null, fixture.cardioFirst)
        }
    }

    @Test fun `a fixture stores what the old create stored`() = runTest {
        for ((fixture, answer) in answers) {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val db = Room.inMemoryDatabaseBuilder(context, StrengthDatabase::class.java).allowMainThreadQueries().build()
            val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
            val settings = SettingsStore(PreferenceDataStoreFactory.create(scope = scope) {
                File.createTempFile("old-wizard-proof", ".preferences_pb")
            })
            try {
                val plans = PlanRepository(db, settings)
                val b = db.insertRuledOldWizardPlan(fixture, settings)
                val a = plans.createGeneratedPlan("Old", answer, ProgramGenerator.generate(answer))
                assertStoredEqual(db, a, b)
                db.runLaunchFixups(settings)
                assertStoredEqual(db, a, b)
            } finally {
                db.close()
                scope.cancel()
            }
        }
    }

    @Test fun `fixture recipes read as their facts`() {
        for (fixture in OldWizardPlans.ALL) {
            val recipe = assertNotNull(PlanRecipeJson.read(fixture.recipeJson))
            assertEquals(fixture.equipment, recipe.equipment)
            assertEquals(fixture.goalsLb, recipe.goalsLb)
            assertEquals(fixture.mains.toSet(), recipe.mainLiftIds())
        }
    }

    private suspend fun assertStoredEqual(db: StrengthDatabase, a: Long, b: Long) {
        fun cloud.trotter.log.strength.data.db.entity.PlanEntity.normalized() =
            copy(id = 0, position = 0, createdAt = 0, onToday = false)
        assertEquals(assertNotNull(db.planDao().plan(a)).normalized(), assertNotNull(db.planDao().plan(b)).normalized())
        assertEquals(db.programDao().daysForPlan(a).map { it.copy(planId = 0, nextRunId = "") },
            db.programDao().daysForPlan(b).map { it.copy(planId = 0, nextRunId = "") })
        val aSlots = db.programDao().exercisesForPlan(a)
        val bSlots = db.programDao().exercisesForPlan(b)
        assertEquals(aSlots.map { it.copy(id = 0, planId = 0) }, bSlots.map { it.copy(id = 0, planId = 0) })
        suspend fun logs(id: Long): List<List<Any>> {
            val positions = db.programDao().exercisesForPlan(id).associate { it.id to it.position }
            return db.programDao().logsForPlan(id).map {
                listOf(it.dayId, positions.getValue(it.programExerciseId), it.slot, it.setsJson)
            }.sortedBy { it.take(3).joinToString("|") }
        }
        assertEquals(logs(a), logs(b))
    }

    private fun render(program: Program): String = program.days.joinToString("\n") { day ->
        val slots = day.exercises.joinToString(", ") { renderSlot(it) }
        val cardio = when (day.cardioBlock) {
            null -> ""
            easyRun -> ", cardio = easyRun"
            longRun -> ", cardio = longRun"
            intervalRun -> if (day.exercises.isEmpty()) ", cardio = intervalRun" else ", cardio = finisher(true)"
            finisher(false) -> ", cardio = finisher(false)"
            else -> ", cardio = ${day.cardioBlock}"
        }
        val kind = if (day.kind == ProgramDayKind.STRENGTH) "" else ", kind = ProgramDayKind.${day.kind}"
        "oldDay(\"${day.id}\", \"${day.title}\", \"${day.emphasisLine}\"${if (slots.isEmpty()) "" else ", $slots"}$cardio$kind),"
    }

    private fun renderSlot(slot: ProgramExercise): String {
        val id = quote(slot.exerciseId)
        val target = slot.prescription?.target
        val partner = slot.superset
        return when {
            slot == rampMain(slot.exerciseId) -> "rampMain($id)"
            target is PrescribedTarget.LoadReps && slot == noviceMain(slot.exerciseId, target.lb, target.reps) ->
                "noviceMain($id, ${target.lb}, ${target.reps})"
            slot == accessory(slot.exerciseId) -> "accessory($id)"
            partner != null && slot == armsPair(slot.exerciseId, partner.exerciseId) ->
                "armsPair($id, ${quote(partner.exerciseId)})"
            else -> {
                val superset = partner?.let { "SupersetPartner(${quote(it.exerciseId)}, ${renderRule(it.rule)})" }
                val prescription = slot.prescription?.let {
                    "cloud.trotter.log.strength.data.serialization.PrescriptionJson.decode(${quote(PrescriptionJson.encode(it))})"
                }
                "ProgramExercise(exerciseId = $id, structure = SetStructure.${slot.structure}, " +
                    "targetSets = ${slot.targetSets}, superset = $superset, note = ${quote(slot.note)}, " +
                    "prescription = $prescription, keepToPlan = ${slot.keepToPlan}, rule = ${renderRule(slot.rule)})"
            }
        }
    }

    private fun renderRule(rule: SlotRule?): String = rule?.let {
        "cloud.trotter.log.strength.data.serialization.SlotRuleJson.decode(${quote(SlotRuleJson.encode(it))})"
    } ?: "null"

    private fun quote(value: String): String = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"")
        .replace("$", "\\$").replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t") + "\""
}
