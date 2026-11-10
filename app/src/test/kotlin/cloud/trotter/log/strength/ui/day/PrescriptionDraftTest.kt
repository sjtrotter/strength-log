package cloud.trotter.log.strength.ui.day

import cloud.trotter.log.strength.data.catalog.ExerciseCatalog
import cloud.trotter.log.strength.domain.model.LoggedSet
import cloud.trotter.log.strength.domain.model.PrescribedTarget
import cloud.trotter.log.strength.domain.model.Prescription
import cloud.trotter.log.strength.domain.model.SetStructure
import cloud.trotter.log.strength.domain.model.SetKind
import cloud.trotter.log.strength.domain.seeding.SetSeeder
import kotlin.test.Test
import kotlin.test.assertEquals

class PrescriptionDraftTest {
    private val slot = DayEditSlotState(
        programExerciseId = 1, position = 0, exerciseId = "conv_dl", title = "Deadlift",
        pattern = null, isSuperset = false, structure = SetStructure.RAMP_TOP_BACKOFF,
    )
    private val catalog = ExerciseCatalog.CODE_ONLY

    @Test
    fun `straight sets from a ramp keeps the working weight and reps`() {
        val ramp = slot.copy(exerciseId = "bb_back_squat", targetSets = 6)
        val working = listOf(
            LoggedSet(120.0, 5, SetKind.RAMP),
            LoggedSet(150.0, 5, SetKind.RAMP),
            LoggedSet(175.0, 5, SetKind.RAMP),
            LoggedSet(195.0, 3, SetKind.RAMP),
            LoggedSet(215.0, 5, SetKind.TOP),
            LoggedSet(160.0, 8, SetKind.BACKOFF),
        )

        val draft = PrescriptionDraft.straightFrom(ramp, working, catalog)

        assertEquals(3, draft.sets)
        assertEquals(5, draft.reps)
        assertEquals(215.0, draft.loadLb)
        assertEquals(PrescriptionKind.LOAD_REPS, draft.kind)
        val fallback = PrescriptionDraft.from(ramp, catalog).copy(sets = 3)
        assertEquals(fallback, PrescriptionDraft.straightFrom(ramp, null, catalog))
    }

    @Test
    fun `missing prescription takes reps from the working row in every structure`() {
        for (structure in SetStructure.entries) {
            val rows = SetSeeder.shape(structure, 225.0, 3, 3)
            val draft = PrescriptionDraft.from(slot.copy(structure = structure, rows = rows), catalog)
            assertEquals(3, draft.reps)
            assertEquals(PrescribedTarget.LoadReps(225.0, 3), draft.prescription().target)
            val written = slot.copy(structure = structure, rows = rows,
                prescription = Prescription(PrescribedTarget.LoadReps(275.0, 6)))
            assertEquals(6, PrescriptionDraft.from(written, catalog).reps)
        }
        val accessory = slot.copy(exerciseId = "rdl", structure = SetStructure.STRAIGHT)
        assertEquals(10, PrescriptionDraft.from(accessory, catalog).reps)
    }

    @Test
    fun `editing only a straight squat weight preserves its seeded reps`() {
        val squat = slot.copy(exerciseId = "bb_back_squat", structure = SetStructure.STRAIGHT,
            rows = List(3) { LoggedSet(235.0, 5, SetKind.WORK) })

        val draft = PrescriptionDraft.from(squat, catalog)

        assertEquals(3, draft.sets)
        assertEquals(235.0, draft.loadLb)
        assertEquals(5, draft.reps)
        assertEquals(PrescribedTarget.LoadReps(125.0, 5), draft.copy(loadLb = 125.0).prescription().target)
    }

    @Test
    fun `an unprescribed draft reads its working row by the catalog's tracking`() {
        val hold = PrescriptionDraft.from(slot.copy(exerciseId = "plank",
            rows = List(3) { LoggedSet(25.0, 0, SetKind.WORK, seconds = 60) }), catalog)
        assertEquals(PrescriptionKind.HOLD, hold.kind)
        assertEquals(PrescribedTarget.Hold(60, 25.0), hold.prescription().target)

        val reps = PrescriptionDraft.from(slot.copy(exerciseId = "pushup",
            rows = List(3) { LoggedSet(0.0, 12, SetKind.WORK) }), catalog)
        assertEquals(PrescriptionKind.REPS, reps.kind)
        assertEquals(PrescribedTarget.Reps(12), reps.prescription().target)

        val weighted = PrescriptionDraft.from(slot.copy(exerciseId = "bb_bench", structure = SetStructure.STRAIGHT,
            rows = List(4) { LoggedSet(185.0, 5, SetKind.WORK) }), catalog)
        assertEquals(PrescriptionKind.LOAD_REPS, weighted.kind)
        assertEquals(PrescribedTarget.LoadReps(185.0, 5), weighted.prescription().target)
        assertEquals(3, weighted.sets)
    }

    @Test
    fun `a known draft without rows takes the catalog target or the class defaults`() {
        val reps = PrescriptionDraft.from(slot.copy(exerciseId = "pushup", rows = emptyList()), catalog)
        assertEquals(PrescriptionKind.REPS, reps.kind)
        assertEquals(PrescribedTarget.Reps(15), reps.prescription().target)

        val hold = PrescriptionDraft.from(slot.copy(exerciseId = "plank", rows = emptyList()), catalog)
        assertEquals(PrescriptionKind.HOLD, hold.kind)
        assertEquals(PrescribedTarget.Hold(45, 0.0), hold.prescription().target)

        val weighted = PrescriptionDraft.from(slot.copy(exerciseId = "bb_bench", rows = emptyList()), catalog)
        assertEquals(PrescriptionKind.LOAD_REPS, weighted.kind)
        assertEquals(PrescribedTarget.LoadReps(0.0, 10), weighted.prescription().target)

        val unknown = PrescriptionDraft.from(slot.copy(exerciseId = "missing", rows = null), catalog)
        assertEquals(PrescriptionKind.LOAD_REPS, unknown.kind)
        assertEquals(PrescribedTarget.LoadReps(0.0, 10), unknown.prescription().target)
    }

    @Test
    fun `straight sets without live rows take the slot's own working row`() {
        val squatRamp = listOf(
            LoggedSet(130.0, 5, SetKind.RAMP), LoggedSet(165.0, 5, SetKind.RAMP),
            LoggedSet(190.0, 5, SetKind.RAMP), LoggedSet(210.0, 3, SetKind.RAMP),
            LoggedSet(235.0, 5, SetKind.TOP), LoggedSet(175.0, 8, SetKind.BACKOFF),
        )
        val ramp = slot.copy(exerciseId = "bb_back_squat", targetSets = 6, rows = squatRamp)

        val draft = PrescriptionDraft.straightFrom(ramp, null, catalog)

        assertEquals(3, draft.sets)
        assertEquals(5, draft.reps)
        assertEquals(235.0, draft.loadLb)
        assertEquals(PrescriptionKind.LOAD_REPS, draft.kind)
    }
}
