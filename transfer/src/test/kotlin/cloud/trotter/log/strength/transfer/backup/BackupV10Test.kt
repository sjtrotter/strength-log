package cloud.trotter.log.strength.transfer.backup

import cloud.trotter.log.strength.data.FullSnapshot
import cloud.trotter.log.strength.data.db.entity.PlanEntity
import cloud.trotter.log.strength.data.db.entity.PlanOrigin
import cloud.trotter.log.strength.data.db.entity.ProgramDayEntity
import cloud.trotter.log.strength.data.mapping.toEntity
import cloud.trotter.log.strength.data.serialization.PrescriptionJson
import cloud.trotter.log.strength.domain.generator.WizardAnswers
import cloud.trotter.log.strength.domain.model.PrescribedTarget
import cloud.trotter.log.strength.domain.model.Prescription
import cloud.trotter.log.strength.domain.model.ProgramExercise
import cloud.trotter.log.strength.domain.standards.RestSettings
import cloud.trotter.log.strength.domain.units.WeightUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

class BackupV10Test {
    private val codec = BackupCodec()
    private val prescriptions = listOf(
        Prescription(PrescribedTarget.LoadReps(37.5, 8), eachSide = true),
        Prescription(PrescribedTarget.Reps(12), resistance = "red band"),
        Prescription(PrescribedTarget.Hold(45, 25.0), eachSide = true),
        Prescription(PrescribedTarget.RepsInTime(120, 50)),
    )

    private fun snapshot() = FullSnapshot(
        answers = WizardAnswers(),
        answersCarryRecipe = false,
        unit = WeightUnit.LB,
        wizardComplete = true,
        suggestedDay = null,
        selectedPlanId = 1,
        restSettings = RestSettings(),
        plans = listOf(PlanEntity(1, "Physio", PlanOrigin.AUTHORED.name, 0, 1_000, suggestedDayId = "A")),
        days = listOf(ProgramDayEntity(1, "A", 0, "Physio day", "", null)),
        exercises = prescriptions.mapIndexed { index, prescription ->
            ProgramExercise("plank", targetSets = 2, note = "physio note", prescription = prescription)
                .toEntity(1, "A", index).copy(id = index + 1L, keepToPlan = true)
        },
        customExercises = emptyList(),
        logs = emptyList(),
        sessions = emptyList(),
        sessionSets = emptyList(),
    )

    private fun decode(document: BackupDocument) = codec.decode(codec.encode(document))

    private fun withExercise(exercise: ProgramExerciseBackup): BackupDocument {
        val document = snapshot().toDocument().copy(schemaVersion = 10)
        val plan = document.plans.single()
        val day = plan.days.single().copy(exercises = listOf(exercise))
        return document.copy(plans = listOf(plan.copy(days = listOf(day))))
    }

    @Test
    fun `v10 carries all prescribed targets and metadata through deterministic export and restore`() {
        val before = snapshot()
        val encoded = codec.encode(before.toDocument().copy(schemaVersion = 10))
        val decoded = codec.decode(encoded)
        val restored = decoded.toSnapshot()
        assertEquals(10, decoded.schemaVersion)
        assertEquals(before.plans, restored.plans)
        assertEquals(before.days, restored.days)
        assertEquals(before.exercises, restored.exercises)
        assertEquals(prescriptions, restored.exercises.map { PrescriptionJson.decode(it.prescriptionJson) })
        assertEquals(encoded, codec.encode(before.toDocument().copy(schemaVersion = 10)))
        assertEquals(encoded, codec.encode(restored.toDocument().copy(schemaVersion = 10)))
    }

    @Test
    fun `a v9 file without the prescription field restores its original rows`() {
        val expected = snapshot().copy(exercises = snapshot().exercises.map { it.copy(prescriptionJson = null, keepToPlan = false) })
        // A literal v9-shaped file: the additive v10 key is absent entirely.
        val document = expected.toDocument().withoutV14Fields().copy(schemaVersion = 9)
        val v9 = codec.encode(document).replace(",\"prescriptionJson\":null", "").replace(",\"keepToPlan\":null", "")
        val decoded = codec.decode(v9)
        assertEquals(document, decoded)
        assertEquals(expected.exercises, decoded.toSnapshot().exercises)
        assertEquals(expected.plans, decoded.toSnapshot().plans)
        assertTrue(decoded.toSnapshot().exercises.all { it.prescriptionJson == null })
    }

    @Test
    fun `pre-v10 treats prescription keys as unknown even when they have the wrong JSON type`() {
        val document = snapshot().toDocument().copy(schemaVersion = 9)
        val root = Json.parseToJsonElement(codec.encode(document)).jsonObject
        val plan = root.getValue("plans").jsonArray.single().jsonObject
        val day = plan.getValue("days").jsonArray.single().jsonObject
        val exercises = day.getValue("exercises").jsonArray.map {
            JsonObject(it.jsonObject + ("prescriptionJson" to JsonObject(emptyMap())))
        }
        val strayDay = JsonObject(day + ("exercises" to JsonArray(exercises)))
        val strayPlan = JsonObject(plan + ("days" to JsonArray(listOf(strayDay))))
        val withUnknown = JsonObject(root + ("plans" to JsonArray(listOf(strayPlan))))
        val restored = codec.decode(withUnknown.toString()).toSnapshot()
        assertTrue(restored.exercises.all { it.prescriptionJson == null })
        // The mapping also preserves pre-v10 semantics for documents built directly.
        assertTrue(document.toSnapshot().exercises.all { it.prescriptionJson == null })
    }

    @Test
    fun `v10 rejects malformed unknown or invalid embedded prescriptions before restore`() {
        val exercise = snapshot().toDocument().plans.single().days.single().exercises.first()
        listOf("{}", "not json", """{"type":"future"}""", """{"type":"hold","seconds":0}""").forEach { json ->
            val error = assertFailsWith<BackupError.InvalidPayload> {
                decode(withExercise(exercise.copy(prescriptionJson = json)))
            }
            assertTrue("prescriptionJson of slot 1" in error.message.orEmpty())
        }
        assertFailsWith<BackupError.Inconsistent> {
            decode(withExercise(exercise.copy(targetSets = 100)))
        }
    }

    @Test
    fun `an absent prescription remains a generated slot in v10`() {
        val exercise = snapshot().toDocument().plans.single().days.single().exercises.first()
        val restored = decode(withExercise(exercise.copy(prescriptionJson = null))).toSnapshot()
        assertNull(restored.exercises.single().prescriptionJson)
    }
}
