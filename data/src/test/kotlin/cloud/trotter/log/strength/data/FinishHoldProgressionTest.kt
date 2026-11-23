package cloud.trotter.log.strength.data

import cloud.trotter.log.strength.data.db.entity.Slot
import cloud.trotter.log.strength.domain.model.LoggedSet
import cloud.trotter.log.strength.domain.model.PrescribedTarget
import cloud.trotter.log.strength.domain.model.Prescription
import cloud.trotter.log.strength.domain.model.SetKind
import cloud.trotter.log.strength.domain.progression.Effort
import cloud.trotter.log.strength.domain.progression.SlotRole
import cloud.trotter.log.strength.domain.seeding.SetEditor
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
class FinishHoldProgressionTest : FinishProgressionFixture() {
    private fun holdRows(seconds: Int) = List(3) { LoggedSet(0.0, 0, SetKind.WORK, seconds = seconds) }
    private suspend fun hold(run: String, plan: Long, id: Long, held: List<Int>) {
        repo.applyToRun(run, plan, "A", listOf(TrackEdit(id, Slot.MAIN) { rows ->
            rows.mapIndexed { i, row -> row.copy(seconds = held[i], done = true) }
        }))
    }
    private suspend fun finishHold(plan: Long, id: Long, held: List<Int> = listOf(45, 45, 45)): Long {
        val run = start(plan)
        hold(run, plan, id, held)
        return assertNotNull(repo.finishRun(run)).also { advanceDays(1) }
    }

    @Test fun `case 39 an Automatic plank steps and an Ask plank offers the step`() = runTest {
        val automatic = authored()
        val a = add(automatic, "plank")
        lifter(automatic, a, holdRows(45), rule(SlotRole.CORE, 10))
        assertEquals("B", repo.addDay(automatic, "Day B"))
        val b = add(automatic, "plank", "B")
        repo.setPrescription(b, Prescription(PrescribedTarget.Hold(30)))
        lifter(automatic, b, holdRows(30), rule(SlotRole.CORE, 10), "B")
        val before = rule(b)
        finishHold(automatic, a)
        val expected = NextOffer(0.0, 0.0, fromSeconds = 45, toSeconds = 50)
        assertEquals(holdRows(50), rows(automatic, a))
        assertEquals(expected, offer(automatic, "plank"))
        assertEquals("HOLD_STEP", repo.history.slotSessionsById(automatic, "A").getValue(a to Slot.MAIN).reason)
        assertEquals(holdRows(30), rows(automatic, b, "B"))
        assertEquals(before.state.successStreak, rule(b).state.successStreak)
        assertEquals(before.state.pendingIncrease, rule(b).state.pendingIncrease)

        lifter(automatic, a, holdRows(45), rule(SlotRole.CORE, 10).copy(everyExposures = 2))
        finishHold(automatic, a)
        assertEquals("STREAK", repo.history.slotSessionsById(automatic, "A").getValue(a to Slot.MAIN).reason)
        assertEquals(holdRows(30), rows(automatic, b, "B"))
        assertEquals(before.state.successStreak, rule(b).state.successStreak)
        assertEquals(before.state.pendingIncrease, rule(b).state.pendingIncrease)

        val ask = converted(day("plank"))
        val id = id(ask, "plank")
        lifter(ask, id, holdRows(45), rule(SlotRole.CORE, 10))
        finishHold(ask, id)
        assertEquals(holdRows(45), rows(ask, id))
        assertEquals(expected, offer(ask, "plank"))
        val nextRun = db.programDao().day(ask, "A")!!.nextRunId
        repo.setWorkingWeight(nextRun, ask, "A", id, Slot.MAIN, 0.0)
        assertEquals(holdRows(50), rows(ask, id))
    }

    @Test fun `case 39 a met stance reports its ceiling`() = runTest {
        val plan = authored()
        val id = add(plan, "single_leg_stance")
        lifter(plan, id, holdRows(30), rule(SlotRole.CORE, 10))
        finishHold(plan, id, listOf(30, 30, 30))
        assertEquals(holdRows(30), rows(plan, id))
        assertNull(offer(plan, "single_leg_stance"))
        assertEquals("HOLD_CEILING", repo.history.slotSessionsById(plan, "A").getValue(id to Slot.MAIN).reason)
    }

    @Test fun `case 39 Easy hold ratings persist and offer a ten second step`() = runTest {
        for (ask in listOf(false, true)) {
            val plan = if (ask) converted(day("plank")) else authored()
            val id = if (ask) id(plan, "plank") else add(plan, "plank")
            lifter(plan, id, holdRows(45), rule(SlotRole.CORE, 10))
            val run = start(plan)
            hold(run, plan, id, listOf(45, 45, 45))
            repo.applyToRun(run, plan, "A", listOf(TrackEdit(id, Slot.MAIN) { rows ->
                rows.indices.fold(rows) { rated, index -> requireNotNull(SetEditor.effort(rated, index, Effort.EASY)) }
            }))
            val session = assertNotNull(repo.finishRun(run))
            assertEquals(listOf(4, 4, 4), repo.history.sessionSets(session).map { it.rir })
            assertEquals(holdRows(if (ask) 45 else 55), rows(plan, id))
            assertEquals(NextOffer(0.0, 0.0, fromSeconds = 45, toSeconds = 55), offer(plan, "plank"))
            if (ask) {
                val nextRun = db.programDao().day(plan, "A")!!.nextRunId
                repo.setWorkingWeight(nextRun, plan, "A", id, Slot.MAIN, 0.0)
                assertEquals(holdRows(55), rows(plan, id))
            }
            advanceDays(1)
        }
    }

    @Test fun `case 39 a hold above the ceiling archives neutral status without an offer`() = runTest {
        val plan = authored()
        val id = add(plan, "plank")
        lifter(plan, id, holdRows(90), rule(SlotRole.CORE, 10))
        finishHold(plan, id, listOf(90, 90, 90))
        assertEquals(holdRows(90), rows(plan, id))
        assertNull(offer(plan, "plank"))
        assertEquals("TIMED", repo.history.slotSessionsById(plan, "A").getValue(id to Slot.MAIN).reason)
    }

    @Test fun `case 40 an overshoot archives the held seconds beside the target and steps once`() = runTest {
        val plan = authored()
        val id = add(plan, "plank")
        lifter(plan, id, holdRows(45), rule(SlotRole.CORE, 10))
        val session = finishHold(plan, id, listOf(107, 45, 45))
        assertEquals(listOf(107, 45, 45), repo.history.sessionSets(session).map { it.seconds })
        assertEquals(listOf(45, 45, 45), repo.history.slotSessionsById(plan, "A").getValue(id to Slot.MAIN).planned.map { it.seconds })
        assertEquals(holdRows(50), rows(plan, id))
    }
}
