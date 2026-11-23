package cloud.trotter.log.strength.data

import cloud.trotter.log.strength.data.serialization.PrescriptionJson
import cloud.trotter.log.strength.domain.model.PrescribedTarget
import cloud.trotter.log.strength.domain.model.Prescription
import cloud.trotter.log.strength.domain.standards.TestRef
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PrescriptionLinkTest : FinishProgressionFixture() {
    @Test
    fun `editing a window keeps its test link and changing its kind drops it`() = runTest {
        val plan = authored()
        val id = add(plan, "hand_release_pushup")
        val hrp = TestRef("aft_hrp", 1, 120)
        repo.setPrescription(id, Prescription(PrescribedTarget.RepsInTime(120, 20), test = hrp))
        repo.setPrescription(id, Prescription(PrescribedTarget.RepsInTime(120, 25)))
        val window = assertNotNull(PrescriptionJson.decode(db.programDao().exercise(id)!!.prescriptionJson))
        assertEquals(hrp, window.test)
        assertEquals(PrescribedTarget.RepsInTime(120, 25), window.target)

        repo.setPrescription(id, Prescription(PrescribedTarget.Reps(25)))
        assertNull(assertNotNull(PrescriptionJson.decode(db.programDao().exercise(id)!!.prescriptionJson)).test)

        val plank = TestRef("aft_plank", 1)
        repo.setPrescription(id, Prescription(PrescribedTarget.Hold(45), test = plank))
        repo.setPrescription(id, Prescription(PrescribedTarget.Hold(50)))
        assertEquals(Prescription(PrescribedTarget.Hold(50), test = plank),
            PrescriptionJson.decode(db.programDao().exercise(id)!!.prescriptionJson))
    }
}
