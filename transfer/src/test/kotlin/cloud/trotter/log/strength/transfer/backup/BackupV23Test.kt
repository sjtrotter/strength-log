package cloud.trotter.log.strength.transfer.backup

import cloud.trotter.log.strength.data.GlobalLifter
import cloud.trotter.log.strength.data.serialization.PlanRecipeJson
import cloud.trotter.log.strength.domain.generator.CardioFirst
import cloud.trotter.log.strength.domain.generator.WizardAnswers
import cloud.trotter.log.strength.domain.model.CardioActivity
import cloud.trotter.log.strength.domain.model.LifterConfig
import cloud.trotter.log.strength.domain.model.Measure
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BackupV23Test {
    private val codec = BackupCodec()
    private val goals = mapOf("bb_back_squat" to 200.0)
    private val answers = WizardAnswers(
        config = LifterConfig(goalsLb = goals),
        cardioFirst = CardioFirst(CardioActivity.TRAIL_RUN, Measure.Distance(4828)),
    )
    private fun document(recipe: String = PlanRecipeJson.encode(answers)) = BackupDocument(
        settings = SettingsBackup(180, 40, "INTERMEDIATE", weightUnit = "LB", wizardComplete = true, selectedPlanId = 1),
        plans = listOf(PlanBackup(1, "Running", "GENERATED", 1000, recipeJson = recipe,
            days = listOf(ProgramDayBackup("A", "Easy", "")), offerCardioIncreases = true)),
    )

    @Test
    fun `v23 preserves cardio first answers and edited goals`() {
        val snapshot = document().toSnapshot()
        val exported = snapshot.toDocument()
        assertEquals(CURRENT_SCHEMA_VERSION, exported.schemaVersion)
        val restored = codec.decode(codec.encode(exported)).toSnapshot()
        assertEquals(snapshot, restored)
        val restoredAnswers = PlanRecipeJson.decode(restored.plans.single().recipeJson!!, GlobalLifter())
        assertEquals(answers.cardioFirst, restoredAnswers.cardioFirst)
        assertEquals(goals, restoredAnswers.config.goalsLb)
    }

    @Test
    fun `v23 preserves cardio notes and absent lifter answers with cardio first recipes`() {
        val base = document()
        val source = base.copy(
            settings = base.settings.copy(hasLifterAnswers = false),
            cardioSessions = listOf(CardioSessionBackup(
                id = 7, dayId = "A", mode = "OUTDOOR_RUN", hard = false, label = "Trail run",
                startedAt = 1000, completedAt = 1801000, seconds = 1800, stepsCompleted = 1,
                activity = "TRAIL_RUN", note = "Easy",
            )),
        )
        val decoded = codec.decode(codec.encode(source))
        assertEquals(CURRENT_SCHEMA_VERSION, decoded.schemaVersion)
        assertFalse(decoded.settings.hasLifterAnswers)
        assertEquals("Easy", decoded.cardioSessions.single().note)
        val snapshot = decoded.toSnapshot()
        val exported = snapshot.toDocument()
        assertEquals(CURRENT_SCHEMA_VERSION, exported.schemaVersion)
        assertFalse(exported.settings.hasLifterAnswers)
        assertEquals("Easy", exported.cardioSessions.single().note)
        assertEquals(base.plans.single().recipeJson, exported.plans.single().recipeJson)
        assertEquals(snapshot, codec.decode(codec.encode(exported)).toSnapshot())
    }

    @Test
    fun `backups without lifter answer presence retain their answers`() {
        val answers = WizardAnswers()
        for (version in 1..CURRENT_SCHEMA_VERSION) {
            val legacy = BackupDocument(
                schemaVersion = version,
                settings = SettingsBackup(
                    180, 40, "INTERMEDIATE", weightUnit = "LB", wizardComplete = false,
                    emphasis = answers.config.emphasis.name,
                    cardioMode = answers.cardio.mode.name,
                    cardioPlacement = answers.cardio.placement.name,
                    fiveKGoal = answers.cardio.fiveKGoal,
                    daysPerWeek = answers.daysPerWeek,
                    split = answers.split.name,
                    anchorScheme = answers.anchorScheme.name,
                    deadliftVariant = answers.deadliftVariant.name,
                    equipment = answers.equipment.map { it.name },
                ),
            )
            val text = codec.encode(legacy).replace(",\"hasLifterAnswers\":true", "")
            val snapshot = codec.decode(text).toSnapshot()
            assertTrue(snapshot.hasLifterAnswers, "v$version")
            assertTrue(snapshot.toDocument().settings.hasLifterAnswers, "v$version")
        }
    }

    @Test
    fun `older versions cannot carry cardio first recipes`() {
        for (version in 1..22) {
            assertFailsWith<BackupError.Inconsistent>("v$version") {
                codec.decode(codec.encode(document().copy(schemaVersion = version)))
            }
        }
    }

    @Test
    fun `v18 through v22 lifting recipes restore and re-export as v23`() {
        for (version in 18..22) {
            val legacyGoals = if (version >= 21) goals else emptyMap()
            val legacyAnswers = WizardAnswers(config = LifterConfig(goalsLb = legacyGoals))
            val recipe = PlanRecipeJson.encode(legacyAnswers)
            val doc = document(recipe).copy(schemaVersion = version)
            val decoded = codec.decode(codec.encode(doc))
            assertEquals(version, decoded.schemaVersion)
            val restored = decoded.toSnapshot()
            val restoredRecipe = restored.plans.single().recipeJson!!
            assertEquals(recipe, restoredRecipe)
            val restoredAnswers = PlanRecipeJson.decode(restoredRecipe, GlobalLifter())
            assertNull(restoredAnswers.cardioFirst)
            assertEquals(legacyGoals, restoredAnswers.config.goalsLb)
            val exported = restored.toDocument()
            assertEquals(CURRENT_SCHEMA_VERSION, exported.schemaVersion)
            assertEquals(restored, codec.decode(codec.encode(exported)).toSnapshot())
        }
    }

    @Test
    fun `v23 rejects invalid cardio first recipe payloads`() {
        val recipe = PlanRecipeJson.encode(answers).replace("\"sessions\":3", "\"sessions\":7")
        assertFailsWith<BackupError.InvalidPayload> { codec.decode(codec.encode(document(recipe))) }
    }

    @Test
    fun `v23 rejects invalid edited goals in cardio first recipes`() {
        val recipe = PlanRecipeJson.encode(answers).replace("200.0", "2000.1")
        assertFailsWith<BackupError.InvalidPayload> { codec.decode(codec.encode(document(recipe))) }
    }
}
