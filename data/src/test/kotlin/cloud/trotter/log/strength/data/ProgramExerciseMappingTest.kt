package cloud.trotter.log.strength.data

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
}
