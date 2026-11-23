package cloud.trotter.log.strength.data

import cloud.trotter.log.strength.data.db.entity.Slot
import cloud.trotter.log.strength.data.mapping.sides
import cloud.trotter.log.strength.domain.model.LoggedSet
import cloud.trotter.log.strength.domain.model.PrescribedTarget
import cloud.trotter.log.strength.domain.model.Prescription
import cloud.trotter.log.strength.domain.model.SetKind
import cloud.trotter.log.strength.domain.model.SideResult
import cloud.trotter.log.strength.domain.model.Sides
import cloud.trotter.log.strength.domain.progression.Effort
import cloud.trotter.log.strength.domain.progression.NextTimeMode
import cloud.trotter.log.strength.domain.progression.SlotRole
import cloud.trotter.log.strength.domain.seeding.SetEdit
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

    @Test fun `an each side plank misses on the shorter hold then steps when both sides meet the target`() = runTest {
        val plan = authored()
        val id = add(plan, "plank")
        repo.setPrescription(id, Prescription(PrescribedTarget.Hold(45), eachSide = true))
        lifter(plan, id, holdRows(45), rule(SlotRole.CORE, 10))
        for (rightSeconds in listOf(40, 45)) {
            val run = start(plan)
            val sides = Sides(SideResult(0.0, 0, 45), SideResult(0.0, 0, rightSeconds))
            repo.applyToRun(run, plan, "A", listOf(TrackEdit(id, Slot.MAIN) { rows ->
                rows.indices.fold(rows) { logged, index ->
                    SetEditor.edit(logged, index, SetEdit(done = true, sides = sides))
                }
            }))
            val session = assertNotNull(repo.finishRun(run))
            val archived = repo.history.sessionSets(session)
            assertEquals(List(3) { rightSeconds }, archived.map { it.seconds })
            assertEquals(List(3) { sides }, archived.map { it.sides() })
            val finished = repo.history.slotSessionsById(plan, "A").getValue(id to Slot.MAIN)
            assertEquals(listOf(45, 45, 45), finished.planned.map { it.seconds })
            if (rightSeconds < 45) {
                assertEquals("REP_MISS", finished.reason)
                assertEquals(holdRows(45), rows(plan, id))
                assertNull(offer(plan, "plank"))
            } else {
                assertEquals("HOLD_STEP", finished.reason)
                assertEquals(holdRows(50), rows(plan, id))
                assertEquals(NextOffer(0.0, 0.0, fromSeconds = 45, toSeconds = 50), offer(plan, "plank"))
            }
            advanceDays(1)
        }
    }

    @Test fun `case 40 an Automatic plank steps and an Ask plank offers the step`() = runTest {
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
        // #647: removed the pendingIncrease comparison; the field no longer exists.

        lifter(automatic, a, holdRows(45), rule(SlotRole.CORE, 10).copy(everyExposures = 2))
        finishHold(automatic, a)
        assertEquals("STREAK", repo.history.slotSessionsById(automatic, "A").getValue(a to Slot.MAIN).reason)
        assertEquals(holdRows(30), rows(automatic, b, "B"))
        assertEquals(before.state.successStreak, rule(b).state.successStreak)
        // #647: removed the pendingIncrease comparison; the field no longer exists.

        val ask = converted(day("plank"))
        val id = id(ask, "plank")
        lifter(ask, id, holdRows(45), rule(SlotRole.CORE, 10))
        // #643: converted replaces the selected authored plan's contents but retains its Automatic default.
        repo.editRule(id, Slot.MAIN, RuleEdit.NextTime(NextTimeMode.ASK))
        finishHold(ask, id)
        assertEquals(holdRows(45), rows(ask, id))
        assertEquals(expected, offer(ask, "plank"))
        val nextRun = db.programDao().day(ask, "A")!!.nextRunId
        repo.setWorkingWeight(nextRun, ask, "A", id, Slot.MAIN, 0.0)
        assertEquals(holdRows(50), rows(ask, id))
    }

    @Test fun `case 40 a met stance reports its ceiling`() = runTest {
        val plan = authored()
        val id = add(plan, "single_leg_stance")
        lifter(plan, id, holdRows(30), rule(SlotRole.CORE, 10))
        finishHold(plan, id, listOf(30, 30, 30))
        assertEquals(holdRows(30), rows(plan, id))
        assertNull(offer(plan, "single_leg_stance"))
        assertEquals("HOLD_CEILING", repo.history.slotSessionsById(plan, "A").getValue(id to Slot.MAIN).reason)
    }

    @Test fun `case 40 Easy hold ratings persist and offer a ten second step`() = runTest {
        for (ask in listOf(false, true)) {
            val plan = if (ask) converted(day("plank")) else authored()
            val id = if (ask) id(plan, "plank") else add(plan, "plank")
            lifter(plan, id, holdRows(45), rule(SlotRole.CORE, 10))
            // #643: converted retains the authored plan's Automatic default from the preceding iteration.
            if (ask) repo.editRule(id, Slot.MAIN, RuleEdit.NextTime(NextTimeMode.ASK))
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

    @Test fun `case 40 a hold above the ceiling archives neutral status without an offer`() = runTest {
        val plan = authored()
        val id = add(plan, "plank")
        lifter(plan, id, holdRows(90), rule(SlotRole.CORE, 10))
        finishHold(plan, id, listOf(90, 90, 90))
        assertEquals(holdRows(90), rows(plan, id))
        assertNull(offer(plan, "plank"))
        assertEquals("TIMED", repo.history.slotSessionsById(plan, "A").getValue(id to Slot.MAIN).reason)
    }

    @Test fun `case 41 an overshoot archives the held seconds beside the target and steps once`() = runTest {
        val plan = authored()
        val id = add(plan, "plank")
        lifter(plan, id, holdRows(45), rule(SlotRole.CORE, 10))
        val session = finishHold(plan, id, listOf(107, 45, 45))
        assertEquals(listOf(107, 45, 45), repo.history.sessionSets(session).map { it.seconds })
        assertEquals(listOf(45, 45, 45), repo.history.slotSessionsById(plan, "A").getValue(id to Slot.MAIN).planned.map { it.seconds })
        assertEquals(holdRows(50), rows(plan, id))
    }
    @Test fun `case 50 the side not held makes the finished hold a miss`() = runTest {
        val plan = authored()
        val id = add(plan, "plank")
        repo.setPrescription(id, Prescription(PrescribedTarget.Hold(30), eachSide = true))
        lifter(plan, id, holdRows(30), rule(SlotRole.CORE, 10))
        val run = start(plan)
        val sides = Sides(SideResult(0.0, 0, 30), SideResult(0.0, 0, 0))
        repo.applyToRun(run, plan, "A", listOf(TrackEdit(id, Slot.MAIN) { rows ->
            rows.indices.fold(rows) { logged, index -> SetEditor.edit(logged, index, SetEdit(done = true, sides = sides)) }
        }))
        val session = assertNotNull(repo.finishRun(run))
        assertEquals(cloud.trotter.log.strength.domain.library.TrackingType.TIMED,
            cloud.trotter.log.strength.domain.standards.SetFormatter.trackingOfSet(
                LoggedSet(0.0, 0, SetKind.WORK, true, sides = sides)))
        assertEquals(List(3) { 0 }, repo.history.sessionSets(session).map { it.seconds })
        assertEquals(List(3) { sides }, repo.history.sessionSets(session).map { it.sides() })
        assertEquals("REP_MISS", repo.history.slotSessionsById(plan, "A").getValue(id to Slot.MAIN).reason)
        assertEquals(holdRows(30), rows(plan, id))
        assertNull(offer(plan, "plank"))
    }

}
