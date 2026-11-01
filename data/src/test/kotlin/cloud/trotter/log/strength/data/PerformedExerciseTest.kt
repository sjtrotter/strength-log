package cloud.trotter.log.strength.data

import cloud.trotter.log.strength.data.catalog.ExerciseCatalog
import cloud.trotter.log.strength.data.db.entity.Slot
import cloud.trotter.log.strength.domain.library.TrackingType
import cloud.trotter.log.strength.domain.model.LifterConfig
import cloud.trotter.log.strength.domain.model.PrescribedTarget
import cloud.trotter.log.strength.domain.model.Prescription
import cloud.trotter.log.strength.domain.model.ProgramExercise
import cloud.trotter.log.strength.domain.model.SupersetPartner
import cloud.trotter.log.strength.domain.standards.GoalTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PerformedExerciseTest {
    private val catalog = ExerciseCatalog.CODE_ONLY
    private val prescription = Prescription(PrescribedTarget.LoadReps(100.0, 5))
    private val slot = ProgramSlot(1L, 0, ProgramExercise("bb_bench", prescription = prescription,
        superset = SupersetPartner("ez_curl")))

    @Test fun `only the plan exercise receives the slots prescription`() {
        assertEquals(prescription, performedExercise(slot, null, catalog).prescription)
        assertEquals(prescription, performedExercise(slot, "bb_bench", catalog).prescription)
        val reps = performedExercise(slot, "pushup", catalog)
        assertNull(reps.prescription)
        assertEquals("pushup", reps.exerciseId)
        assertEquals(catalog.get("pushup"), reps.entry)
        assertEquals(TrackingType.REPS, reps.tracking)
        assertEquals(GoalTarget.Reps(15), reps.goal(LifterConfig()))
        val timed = performedExercise(slot, "plank", catalog)
        assertNull(timed.prescription)
        assertEquals(TrackingType.TIMED, timed.tracking)
        assertEquals(GoalTarget.Time(45, 0.0), timed.goal(LifterConfig()))
    }

    @Test fun `partner resolution uses the performed partner without the MAIN prescription`() {
        val partner = performedExercise(slot, "rope_pushdown", catalog, Slot.SS)
        assertEquals("rope_pushdown", partner.exerciseId)
        assertEquals(catalog.get("rope_pushdown"), partner.entry)
        assertNull(partner.prescription)
        assertEquals(TrackingType.WEIGHTED, partner.tracking)
    }
}
