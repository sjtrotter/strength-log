package cloud.trotter.log.strength.data

import cloud.trotter.log.strength.data.serialization.SessionPlanJson
import cloud.trotter.log.strength.domain.model.PrescribedTarget
import cloud.trotter.log.strength.domain.model.Prescription
import cloud.trotter.log.strength.domain.progression.RepTarget
import cloud.trotter.log.strength.domain.progression.SlotRole
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class FinishRepsInTimeTest : FinishProgressionFixture() {
    @Test fun `a rep window at its aim moves to the next aim and grants no shared credit`() = runTest {
        val plan = authored()
        val window = add(plan, "pushup")
        repo.setPrescription(window, Prescription(PrescribedTarget.RepsInTime(60, 20)))
        assertEquals("B", repo.addDay(plan, "Day B"))
        val plain = add(plan, "pushup", "B")
        lifter(plan, plain, work(w = 0.0, r = 20), rule(SlotRole.ACCESSORY, 20), day = "B")
        val session = finish(plan, window, 0.0, listOf(20, 20, 20))
        assertEquals(work(w = 0.0, r = 21), rows(plan, window))
        assertEquals(RepTarget.Fixed(21), rule(window).target)
        assertEquals("ALL_MADE", SessionPlanJson.decode(db.sessionDao().sessionById(session)!!.plannedJson!!).single().reason)
        assertEquals(0, rule(plain).state.successStreak)
        assertFalse(rule(plain).state.pendingIncrease)
    }

    @Test fun `a rep window with no aim keeps measuring`() = runTest {
        val plan = authored()
        val window = add(plan, "pushup")
        repo.setPrescription(window, Prescription(PrescribedTarget.RepsInTime(60)))
        val before = rule(window)
        val session = finish(plan, window, 0.0, listOf(25, 24, 22))
        assertEquals(work(w = 0.0, r = 0), rows(plan, window))
        assertEquals(before.target, rule(window).target)
        assertEquals(1, rule(window).state.exposures)
        assertEquals("MEASURED", SessionPlanJson.decode(db.sessionDao().sessionById(session)!!.plannedJson!!).single().reason)
        assertNull(offer(plan, "pushup"))
    }
}
