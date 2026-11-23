package cloud.trotter.log.strength.data

import cloud.trotter.log.strength.data.db.entity.Slot
import cloud.trotter.log.strength.data.serialization.OpeningJson
import cloud.trotter.log.strength.data.serialization.SessionPlanJson
import cloud.trotter.log.strength.domain.model.PrescribedTarget
import cloud.trotter.log.strength.domain.model.Prescription
import cloud.trotter.log.strength.domain.progression.RepTarget
import cloud.trotter.log.strength.domain.progression.SlotRole
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
        // #647: removed the pendingIncrease assertion; the zero streak still pins independence.
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

    @Test fun `a light rep window with no aim measures and counts the exposure`() = runTest {
        val plan = authored()
        val window = add(plan, "pushup")
        repo.setPrescription(window, Prescription(PrescribedTarget.RepsInTime(60)))
        val before = rule(window)
        val run = (repo.startRun(plan, "A", lighter = true) as RunStartResult.Started).runId
        assertEquals(true, OpeningJson.decode(db.runDao().tracks(run).single().openingJson)?.light)
        perform(run, plan, window, 0.0, listOf(25, 24, 22))
        val session = assertNotNull(repo.finishRun(run))
        assertEquals("MEASURED", SessionPlanJson.decode(db.sessionDao().sessionById(session)!!.plannedJson!!).single().reason)
        assertEquals(before.copy(state = before.state.copy(exposures = 1)), rule(window))
        assertEquals(work(w = 0.0, r = 0), rows(plan, window))
        assertNull(offer(plan, "pushup"))
    }

    @Test fun `a light rep window keeps its aim and counts the exposure`() = runTest {
        val plan = authored()
        val window = add(plan, "pushup")
        repo.setPrescription(window, Prescription(PrescribedTarget.RepsInTime(60, 20)))
        lifter(plan, window, work(w = 0.0, r = 21), rule(SlotRole.ACCESSORY, 21))
        val before = rule(window)
        val run = (repo.startRun(plan, "A", lighter = true) as RunStartResult.Started).runId
        assertEquals(true, OpeningJson.decode(db.runDao().tracks(run).single().openingJson)?.light)
        perform(run, plan, window, 0.0, listOf(21, 21, 21))
        val session = assertNotNull(repo.finishRun(run))
        assertEquals("LIGHT_CYCLE", SessionPlanJson.decode(db.sessionDao().sessionById(session)!!.plannedJson!!).single().reason)
        assertEquals(work(w = 0.0, r = 21), rows(plan, window))
        assertEquals(before.copy(state = before.state.copy(exposures = 1)), rule(window))
        assertNull(offer(plan, "pushup"))
    }

    @Test fun `a measured Finish clears legacy reps in Automatic and Ask`() = runTest {
        for (automatic in listOf(true, false)) {
            val plan = authored()
            db.planDao().updatePlan(db.planDao().plan(plan)!!.copy(autoApply = automatic))
            val window = add(plan, "pushup")
            repo.setPrescription(window, Prescription(PrescribedTarget.RepsInTime(60)))
            lifter(plan, window, work(w = 0.0, r = 13), rule(SlotRole.ACCESSORY, 13))
            val session = finish(plan, window, 0.0, listOf(25, 24, 22))
            assertEquals("MEASURED", SessionPlanJson.decode(db.sessionDao().sessionById(session)!!.plannedJson!!).single().reason)
            assertEquals(work(w = 0.0, r = 0), rows(plan, window))
            assertEquals(RepTarget.Fixed(13), rule(window).target)
            assertEquals(1, rule(window).state.exposures)
            val archived = repo.history.slotSessionsById(plan, "A").getValue(window to Slot.MAIN)
            assertEquals(listOf(25, 24, 22), archived.actual.map { it.reps })
            assertNull(archived.offer)
        }
    }

    @Test fun `plain reps grant no shared credit to a rep window`() = runTest {
        val plan = authored()
        val plain = add(plan, "pushup")
        lifter(plan, plain, work(w = 0.0, r = 20), rule(SlotRole.ACCESSORY, 20))
        assertEquals("B", repo.addDay(plan, "Day B"))
        val window = add(plan, "pushup", "B")
        repo.setPrescription(window, Prescription(PrescribedTarget.RepsInTime(60, 20)))
        val before = rule(window)
        finish(plan, plain, 0.0, listOf(20, 20, 20))
        // #647: removed the pendingIncrease assertion; the whole-rule comparison still pins independence.
        assertEquals(before, rule(window))
    }
}
