package cloud.trotter.log.strength.domain.library

import cloud.trotter.log.strength.domain.model.PrescribedTarget
import cloud.trotter.log.strength.domain.model.Prescription
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EachSideTest {
    @Test fun `the catalog decides without a prescription`() {
        assertTrue(eachSideOf(ExerciseLibrary.find("side_plank"), null))
        assertTrue(eachSideOf(ExerciseLibrary.find("single_leg_stance"), null))
        assertFalse(eachSideOf(ExerciseLibrary.find("plank"), null))
        assertFalse(eachSideOf(null, null))
    }

    @Test fun `an authored prescription decides over the catalog`() {
        assertFalse(eachSideOf(ExerciseLibrary.find("side_plank"), Prescription(PrescribedTarget.Hold(30), eachSide = false)))
        assertTrue(eachSideOf(ExerciseLibrary.find("plank"), Prescription(PrescribedTarget.Hold(30), eachSide = true)))
    }
}
