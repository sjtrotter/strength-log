package cloud.trotter.log.strength.data

import cloud.trotter.log.strength.data.serialization.SlotRuleJson
import cloud.trotter.log.strength.domain.progression.SlotRule
import cloud.trotter.log.strength.domain.progression.SlotRole
import cloud.trotter.log.strength.domain.progression.RepTarget
import cloud.trotter.log.strength.domain.progression.Scheme
import kotlin.test.assertNull
import cloud.trotter.log.strength.data.mapping.toDomain
import cloud.trotter.log.strength.data.mapping.toEntity
import cloud.trotter.log.strength.domain.model.PrescribedTarget
import cloud.trotter.log.strength.domain.model.Prescription
import cloud.trotter.log.strength.domain.model.ProgramExercise
import cloud.trotter.log.strength.domain.model.SetStructure
import cloud.trotter.log.strength.domain.model.SupersetPartner
import kotlin.test.Test
import kotlin.test.assertEquals

class ProgramExerciseMappingTest {
    @Test fun `every structure survives entity mapping with all slot metadata`() {
        for (structure in SetStructure.entries) {
            val slot = ProgramExercise(
                exerciseId = "bb_back_squat",
                structure = structure,
                targetSets = 2,
                superset = SupersetPartner("ez_curl"),
                note = "belt",
                prescription = Prescription(PrescribedTarget.LoadReps(225.0, 5)),
                keepToPlan = true,
            )
            val entity = slot.toEntity(7, "B", 4)
            assertEquals(structure.name, entity.structure)
            assertEquals(7L, entity.planId)
            assertEquals("B", entity.dayId)
            assertEquals(4, entity.position)
            assertEquals(slot, entity.toDomain())
        }
    }

    @Test fun `an unknown stored structure falls back to straight`() {
        val slot = ProgramExercise("bb_back_squat")
        val entity = slot.toEntity(1, "A", 0).copy(structure = "FUTURE_STRUCTURE")
        assertEquals(slot, entity.toDomain())
    }
    @Test fun `a partner rule maps with its partner`() {
        val rule = SlotRule(SlotRole.ACCESSORY, RepTarget.Fixed(10), 2, 0.0125, 1, 0.05, Scheme.STRENGTH)
        val json = SlotRuleJson.encode(rule)
        val entity = ProgramExercise("bb_bench").toEntity(1, "A", 0).copy(
            supersetExerciseId = "cable_row", partnerRuleJson = json)
        val domain = entity.toDomain()
        assertEquals(SupersetPartner("cable_row", rule), domain.superset)
        assertEquals(json, domain.toEntity(1, "A", 0).partnerRuleJson)
        assertNull(entity.copy(supersetExerciseId = null).toDomain().superset)
    }

}
