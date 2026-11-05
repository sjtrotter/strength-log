package cloud.trotter.log.strength.ui.day

import cloud.trotter.log.strength.data.catalog.ExerciseCatalog
import cloud.trotter.log.strength.domain.model.GoalEmphasis
import cloud.trotter.log.strength.domain.model.LifterConfig
import cloud.trotter.log.strength.domain.model.PrescribedTarget
import cloud.trotter.log.strength.domain.model.Prescription
import cloud.trotter.log.strength.domain.model.SetStructure
import cloud.trotter.log.strength.domain.standards.StrengthStandards
import kotlin.test.Test
import kotlin.test.assertEquals

class PrescriptionDraftTest {
    private val slot = DayEditSlotState(
        programExerciseId = 1, position = 0, exerciseId = "conv_dl", title = "Deadlift",
        pattern = null, isSuperset = false, structure = SetStructure.RAMP_TOP_BACKOFF,
    )
    private val catalog = ExerciseCatalog.CODE_ONLY

    @Test
    fun `missing prescription uses emphasis reps for both top structures and ten for straight sets`() {
        for (emphasis in GoalEmphasis.entries) {
            val config = LifterConfig(emphasis = emphasis)
            for (structure in SetStructure.entries) {
                val draft = PrescriptionDraft.from(slot.copy(structure = structure), catalog, config)
                val expected = if (structure.hasTopSet) StrengthStandards.topSetReps(emphasis) else 10
                assertEquals(expected, draft.reps)
                assertEquals(expected, (draft.prescription().target as PrescribedTarget.LoadReps).reps)
            }
            val written = slot.copy(prescription = Prescription(PrescribedTarget.LoadReps(275.0, 6)))
            assertEquals(6, PrescriptionDraft.from(written, catalog, config).reps)
        }
    }
}
