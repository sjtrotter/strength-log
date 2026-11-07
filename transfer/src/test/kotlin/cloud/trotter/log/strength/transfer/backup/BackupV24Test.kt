package cloud.trotter.log.strength.transfer.backup

import cloud.trotter.log.strength.data.GlobalLifter
import cloud.trotter.log.strength.data.serialization.PlanRecipeJson
import cloud.trotter.log.strength.domain.generator.CardioFirst
import cloud.trotter.log.strength.domain.generator.WizardAnswers
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class BackupV24Test {
    private val codec = BackupCodec()
    private val rest = ProgramDayBackup("C", "Rest", "", kind = "REST")
    private fun document(day: ProgramDayBackup = rest, version: Int = CURRENT_SCHEMA_VERSION) = BackupDocument(
        schemaVersion = version,
        settings = SettingsBackup(180, 40, "INTERMEDIATE", weightUnit = "LB", wizardComplete = true, selectedPlanId = 1),
        plans = listOf(PlanBackup(1, "Plan", "AUTHORED", 1000, suggestedDay = "C", days = listOf(
            ProgramDayBackup("A", "Run", ""), day,
        ))),
    )

    @Test fun `Rest entries round trip with their rotation position`() {
        val snapshot = document().toSnapshot()
        val exported = snapshot.toDocument()
        assertEquals(CURRENT_SCHEMA_VERSION, exported.schemaVersion)
        val decoded = codec.decode(codec.encode(exported))
        assertEquals("C", decoded.plans.single().suggestedDay)
        assertEquals("REST", decoded.plans.single().days.last().kind)
        assertEquals(snapshot, decoded.toSnapshot())
    }

    @Test fun `v21 through v23 reject Rest entries`() {
        for (version in 21..23) {
            assertFailsWith<BackupError.Inconsistent>("v$version") {
                codec.decode(codec.encode(document(version = version)))
            }
        }
    }

    @Test fun `Rest with an exercise or cardio is inconsistent`() {
        val exercise = ProgramExerciseBackup(1, "bb_back_squat", targetSets = 3, note = "")
        for (invalid in listOf(
            rest.copy(exercises = listOf(exercise)),
            rest.copy(cardioJson = "{}"),
            rest.copy(cardioBlockJson = "{}"),
        )) {
            assertFailsWith<BackupError.Inconsistent> { codec.decode(codec.encode(document(invalid))) }
        }
    }

    @Test fun `rest days in recipes round trip and invalid values fail`() {
        val answers = WizardAnswers(cardioFirst = CardioFirst(restDays = 2))
        val recipe = PlanRecipeJson.encode(answers)
        val doc = document().let { it.copy(plans = listOf(it.plans.single().copy(recipeJson = recipe))) }
        val restored = codec.decode(codec.encode(doc))
        assertEquals(2, PlanRecipeJson.decode(restored.plans.single().recipeJson!!, GlobalLifter()).cardioFirst?.restDays)
        for (invalid in listOf(-1, 3)) {
            val bad = doc.copy(plans = listOf(doc.plans.single().copy(
                recipeJson = recipe.replace("\"restDays\":2", "\"restDays\":$invalid"),
            )))
            assertFailsWith<BackupError.InvalidPayload> { codec.decode(codec.encode(bad)) }
        }
    }

    @Test fun `v9 through v23 training days restore without changes`() {
        for (version in 9..23) {
            val doc = document(rest.copy(kind = "STRENGTH"), version)
            val decoded = codec.decode(codec.encode(doc))
            assertEquals(version, decoded.schemaVersion)
            assertEquals(doc.plans.single().days, decoded.plans.single().days)
        }
    }
}
