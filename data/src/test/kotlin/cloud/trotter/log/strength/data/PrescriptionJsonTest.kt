package cloud.trotter.log.strength.data

import cloud.trotter.log.strength.data.mapping.toDomain
import cloud.trotter.log.strength.data.mapping.toEntity
import cloud.trotter.log.strength.data.serialization.PrescriptionJson
import cloud.trotter.log.strength.domain.model.PrescribedTarget
import cloud.trotter.log.strength.domain.model.Prescription
import cloud.trotter.log.strength.domain.model.ProgramExercise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class PrescriptionJsonTest {
    @Test
    fun `targets and metadata survive codec and entity round trips deterministically`() {
        val targets = listOf(
            PrescribedTarget.LoadReps(37.5, 8), PrescribedTarget.Reps(0),
            PrescribedTarget.Hold(45), PrescribedTarget.Hold(3600, 2000.0),
            PrescribedTarget.RepsInTime(120), PrescribedTarget.RepsInTime(1, 999),
        )
        targets.forEach { target ->
            val p = Prescription(target, eachSide = true, resistance = "red band")
            val json = PrescriptionJson.encode(p)
            PrescriptionJson.validate(json)
            assertEquals(p, PrescriptionJson.decode(json))
            assertEquals(json, PrescriptionJson.encode(p))
            val slot = ProgramExercise("plank", prescription = p)
            assertEquals(slot, slot.toEntity(1, "A", 0).toDomain())
        }
        val generated = ProgramExercise("plank")
        assertNull(generated.toEntity(1, "A", 0).prescriptionJson)
        assertEquals(generated, generated.toEntity(1, "A", 0).toDomain())
        assertNull(PrescriptionJson.decode(null))
    }

    @Test
    fun `invalid payloads become null in storage and throw in backup validation`() {
        val invalid = listOf(
            "not json", "null", "{}", """{"type":"future"}""",
            """{"type":"loadReps","reps":5}""",
            """{"type":"loadReps","lb":10}""",
            """{"type":"reps"}""", """{"type":"hold"}""", """{"type":"repsInTime"}""",
            """{"type":"loadReps","lb":-1,"reps":5}""",
            """{"type":"loadReps","lb":2000.1,"reps":5}""",
            """{"type":"loadReps","lb":1e999,"reps":5}""",
            """{"type":"reps","reps":-1}""", """{"type":"reps","reps":1000}""",
            """{"type":"hold","seconds":-1}""", """{"type":"hold","seconds":0}""",
            """{"type":"hold","seconds":3601}""",
            """{"type":"hold","seconds":30,"addedLb":-1}""",
            """{"type":"hold","seconds":30,"addedLb":2001}""",
            """{"type":"repsInTime","seconds":120,"repAim":-1}""",
            """{"type":"repsInTime","seconds":120,"repAim":1000}""",
            """{"type":"reps","reps":1,"resistance":"${"r".repeat(41)}"}""",
        )
        invalid.forEach { json ->
            assertNull(PrescriptionJson.decode(json), json)
            assertFailsWith<IllegalArgumentException>(json) { PrescriptionJson.validate(json) }
        }
    }

    @Test
    fun `bounds are inclusive and unknown additive fields are tolerated`() {
        val json = """{"type":"loadReps","lb":0,"reps":999,"resistance":"${"r".repeat(40)}","future":true}"""
        assertEquals(Prescription(PrescribedTarget.LoadReps(0.0, 999), resistance = "r".repeat(40)), PrescriptionJson.decode(json))
    }
}
