package cloud.trotter.log.strength.domain

import cloud.trotter.log.strength.domain.library.TrackingType
import cloud.trotter.log.strength.domain.library.tracking
import cloud.trotter.log.strength.domain.model.LoggedSet
import cloud.trotter.log.strength.domain.model.PrescribedTarget
import cloud.trotter.log.strength.domain.model.Prescription
import cloud.trotter.log.strength.domain.model.SetKind
import cloud.trotter.log.strength.domain.seeding.SetSeeder
import kotlin.test.Test
import kotlin.test.assertEquals

class PrescriptionTest {
    @Test
    fun `each target seeds exact WORK rows with no inferred reps in a time window`() {
        val cases = listOf(
            PrescribedTarget.LoadReps(37.5, 8) to LoggedSet(37.5, 8, SetKind.WORK),
            PrescribedTarget.Reps(12) to LoggedSet(0.0, 12, SetKind.WORK),
            PrescribedTarget.Hold(45) to LoggedSet(0.0, 0, SetKind.WORK, seconds = 45),
            PrescribedTarget.Hold(60, 22.5) to LoggedSet(22.5, 0, SetKind.WORK, seconds = 60),
            PrescribedTarget.RepsInTime(120) to LoggedSet(0.0, 0, SetKind.WORK),
            PrescribedTarget.RepsInTime(120, 50) to LoggedSet(0.0, 0, SetKind.WORK),
        )
        cases.forEach { (target, row) ->
            assertEquals(List(3) { row }, SetSeeder.seedPrescribed(3, Prescription(target, true, "red band")))
        }
    }

    @Test
    fun `tracking follows the prescribed target`() {
        assertEquals(TrackingType.WEIGHTED, Prescription(PrescribedTarget.LoadReps(20.0, 5)).tracking)
        assertEquals(TrackingType.REPS, Prescription(PrescribedTarget.Reps(10)).tracking)
        assertEquals(TrackingType.TIMED, Prescription(PrescribedTarget.Hold(30, 10.0)).tracking)
        assertEquals(TrackingType.REPS, Prescription(PrescribedTarget.RepsInTime(120, 50)).tracking)
    }
}
