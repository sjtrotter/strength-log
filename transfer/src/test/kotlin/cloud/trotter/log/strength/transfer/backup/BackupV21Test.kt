package cloud.trotter.log.strength.transfer.backup

import cloud.trotter.log.strength.data.GlobalLifter
import cloud.trotter.log.strength.data.serialization.PlanRecipeJson
import cloud.trotter.log.strength.domain.generator.CardioFirst
import cloud.trotter.log.strength.domain.generator.WizardAnswers
import cloud.trotter.log.strength.domain.model.CardioActivity
import cloud.trotter.log.strength.domain.model.Measure
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class BackupV21Test {
    private val codec = BackupCodec()
    private val answers = WizardAnswers(cardioFirst = CardioFirst(CardioActivity.TRAIL_RUN, Measure.Distance(4828)))
    private fun document(recipe: String = PlanRecipeJson.encode(answers)) = BackupDocument(
        settings = SettingsBackup(180, 40, "INTERMEDIATE", weightUnit = "LB", wizardComplete = true, selectedPlanId = 1),
        plans = listOf(PlanBackup(1, "Running", "GENERATED", 1000, recipeJson = recipe,
            days = listOf(ProgramDayBackup("A", "Easy", "")), offerCardioIncreases = true)),
    )

    @Test
    fun `v21 cardio first recipe survives export and restore`() {
        val snapshot = document().toSnapshot()
        val exported = snapshot.toDocument()
        assertEquals(21, exported.schemaVersion)
        val restored = codec.decode(codec.encode(exported)).toSnapshot()
        assertEquals(snapshot, restored)
        assertEquals(answers.cardioFirst, PlanRecipeJson.decode(restored.plans.single().recipeJson!!, GlobalLifter()).cardioFirst)
    }

    @Test
    fun `older versions cannot carry cardio first recipes`() {
        for (version in 1..20) {
            assertFailsWith<BackupError.Inconsistent>("v$version") {
                codec.decode(codec.encode(document().copy(schemaVersion = version)))
            }
        }
    }

    @Test
    fun `v20 lifting recipe restores without cardio first`() {
        val doc = document(PlanRecipeJson.encode(WizardAnswers())).copy(schemaVersion = 20)
        val restored = codec.decode(codec.encode(doc)).toSnapshot()
        assertNull(PlanRecipeJson.decode(restored.plans.single().recipeJson!!, GlobalLifter()).cardioFirst)
    }

    @Test
    fun `v21 rejects invalid cardio first recipe payloads`() {
        val recipe = PlanRecipeJson.encode(answers).replace("\"sessions\":3", "\"sessions\":7")
        assertFailsWith<BackupError.InvalidPayload> { codec.decode(codec.encode(document(recipe))) }
    }
}
