package cloud.trotter.log.strength.transfer.backup

import cloud.trotter.log.strength.data.FullSnapshot
import cloud.trotter.log.strength.data.db.entity.PlanEntity
import cloud.trotter.log.strength.data.db.entity.PlanOrigin
import cloud.trotter.log.strength.data.db.entity.ProgramDayEntity
import cloud.trotter.log.strength.data.serialization.PlanRecipeJson
import cloud.trotter.log.strength.domain.generator.WizardAnswers
import cloud.trotter.log.strength.domain.model.ExperienceLevel
import cloud.trotter.log.strength.domain.model.GoalEmphasis
import cloud.trotter.log.strength.domain.model.LifterConfig
import cloud.trotter.log.strength.domain.standards.RestSettings
import cloud.trotter.log.strength.domain.units.WeightUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/** v9 carries the generator inputs and rotation on each plan, with only a global lifter in settings. */
class BackupV9Test {

    private val codec = BackupCodec()
    private val global = LifterConfig(bodyweightLb = 210, age = 33, level = ExperienceLevel.ADVANCED)
    private val strength = WizardAnswers(daysPerWeek = 3, config = global.copy(emphasis = GoalEmphasis.STRENGTH))
    private val physique = WizardAnswers(daysPerWeek = 5, config = global.copy(emphasis = GoalEmphasis.PHYSIQUE))
    private val recipeFields = listOf(
        "emphasis", "cardioMode", "cardioPlacement", "fiveKGoal", "daysPerWeek",
        "split", "anchorScheme", "deadliftVariant", "equipment", "suggestedDay",
    )

    private fun snapshot() = FullSnapshot(
        answers = strength,
        answersCarryRecipe = false,
        unit = WeightUnit.KG,
        wizardComplete = true,
        suggestedDay = "B",
        selectedPlanId = 42,
        restSettings = RestSettings(),
        plans = listOf(
            PlanEntity(
                7, "Strength", PlanOrigin.GENERATED.name, 0, 1_000,
                recipeJson = PlanRecipeJson.encode(strength), suggestedDayId = "B",
            ),
            PlanEntity(
                42, "Physique", PlanOrigin.GENERATED.name, 1, 2_000,
                recipeJson = PlanRecipeJson.encode(physique), suggestedDayId = "C",
            ),
            PlanEntity(99, "Authored", PlanOrigin.AUTHORED.name, 2, 3_000),
        ),
        days = listOf(7L to listOf("A", "B", "C"), 42L to listOf("A", "B", "C", "D", "E"))
            .flatMap { (planId, ids) ->
                ids.mapIndexed { index, id -> ProgramDayEntity(planId, id, index, "Day $id", "", null) }
            },
        customExercises = emptyList(),
        exercises = emptyList(),
        logs = emptyList(),
        sessions = emptyList(),
        sessionSets = emptyList(),
    )

    private fun decode(document: BackupDocument) = codec.decode(codec.encode(document))

    @Test
    fun `different generated recipes and rotations and an authored plan survive a full cycle`() {
        val before = snapshot()
        val encoded = codec.encode(before.toDocument())
        val decoded = codec.decode(encoded)
        val restored = decoded.toSnapshot()

        assertEquals(CURRENT_SCHEMA_VERSION, decoded.schemaVersion)
        assertEquals(before.plans, restored.plans)
        assertEquals(before.days, restored.days)
        assertEquals(listOf("B", "C", null), restored.plans.map { it.suggestedDayId })
        assertNull(restored.plans.last().recipeJson)
        assertEquals(42L, restored.selectedPlanId)
        assertEquals(WizardAnswers(config = global), restored.answers)
        assertNull(restored.suggestedDay)
        assertFalse(restored.answersCarryRecipe)

        val settings = Json.parseToJsonElement(encoded).jsonObject.getValue("settings").jsonObject
        recipeFields.forEach { field -> assertEquals(JsonNull, settings.getValue(field), field) }
        assertEquals(before.unit, restored.unit)
        assertEquals(before.wizardComplete, restored.wizardComplete)
    }

    @Test
    fun `encoding v9 twice and after mapping back is byte-identical`() {
        val before = snapshot()
        val first = codec.encode(before.toDocument())
        assertEquals(first, codec.encode(before.toDocument()))
        assertEquals(first, codec.encode(codec.decode(first).toSnapshot().toDocument()))
    }

    @Test
    fun `v9 ignores legacy settings fields even when their types are invalid`() {
        val document = snapshot().toDocument()
        val root = Json.parseToJsonElement(codec.encode(document)).jsonObject
        val settings = root.getValue("settings").jsonObject
        val stray = JsonObject(settings + recipeFields.associateWith { JsonObject(emptyMap()) })
        val decoded = codec.decode(JsonObject(root + ("settings" to stray)).toString())

        assertEquals(document, decoded)
        assertEquals(WizardAnswers(config = global), decoded.toSnapshot().answers)
        assertNull(decoded.toSnapshot().suggestedDay)
    }

    @Test
    fun `undecodable plan recipe is an unreadable embedded payload`() {
        val document = snapshot().toDocument()
        decode(document)
        val plan = document.plans.first().copy(recipeJson = "{}")
        val error = assertFailsWith<BackupError.InvalidPayload> {
            decode(document.copy(plans = listOf(plan) + document.plans.drop(1)))
        }
        assertTrue("recipeJson of plan 7" in error.message.orEmpty())
    }

    @Test
    fun `recipe days per week outside two through six are rejected`() {
        val document = snapshot().toDocument()
        decode(document)
        val plan = document.plans.first().copy(
            recipeJson = PlanRecipeJson.encode(strength).replace("\"daysPerWeek\":3", "\"daysPerWeek\":7"),
        )
        val error = assertFailsWith<BackupError.InvalidPayload> {
            decode(document.copy(plans = listOf(plan) + document.plans.drop(1)))
        }
        assertTrue("recipeJson of plan 7" in error.message.orEmpty())
        assertTrue("daysPerWeek must be 2..6, was 7" in error.message.orEmpty())
    }

    @Test
    fun `authored plan must not carry a generator recipe`() {
        val document = snapshot().toDocument()
        decode(document)
        val plan = document.plans.last().copy(recipeJson = PlanRecipeJson.encode(strength))
        val error = assertFailsWith<BackupError.Inconsistent> {
            decode(document.copy(plans = document.plans.dropLast(1) + plan))
        }
        assertTrue("AUTHORED plan 99 must have a null recipeJson" in error.message.orEmpty())
    }

    @Test
    fun `suggested day must belong to its own plan`() {
        val document = snapshot().toDocument()
        decode(document)
        // E exists in the other generated plan, but not in this one.
        val plan = document.plans.first().copy(suggestedDay = "E")
        val error = assertFailsWith<BackupError.Inconsistent> {
            decode(document.copy(plans = listOf(plan) + document.plans.drop(1)))
        }
        assertTrue("suggestedDay 'E' is not a day in this backup" in error.message.orEmpty())
    }

    @Test
    fun `v9 rejects a non-empty legacy program`() {
        val document = snapshot().toDocument()
        val error = assertFailsWith<BackupError.Malformed> {
            decode(document.copy(schemaVersion = 9, program = document.plans.first().days))
        }
        assertTrue("program must be empty in schema v9" in error.message.orEmpty())
    }
}
