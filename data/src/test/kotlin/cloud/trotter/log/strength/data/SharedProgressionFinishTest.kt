package cloud.trotter.log.strength.data

import cloud.trotter.log.strength.data.db.entity.Slot
import cloud.trotter.log.strength.data.serialization.SetJson
import cloud.trotter.log.strength.domain.progression.RepTarget
import cloud.trotter.log.strength.domain.progression.SlotRole
import cloud.trotter.log.strength.domain.progression.WorkingRows
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SharedProgressionFinishTest : FinishProgressionFixture() {
    @Test fun `finishing day A moves the matching slot on day B`() = runTest {
        val plan = authored()
        repo.addDay(plan, "Day B")
        val a = add(plan, "bb_bench")
        val b = add(plan, "bb_bench", "B")
        lifter(plan, a)
        lifter(plan, b, day = "B")
        finish(plan, a)
        assertEquals(work(w = 190.0), rows(plan, a))
        assertEquals(work(w = 190.0), rows(plan, b, "B"))
        assertEquals(rule(a), rule(b))
        val run = start(plan, "B")
        val track = db.runDao().tracks(run).single { it.slot == Slot.MAIN }
        assertEquals(work(w = 190.0), WorkingRows.cleared(SetJson.decodeSets(track.plannedJson)))
    }

    @Test fun `heavier work on day A becomes day B's next time`() = runTest {
        val plan = authored()
        repo.addDay(plan, "Day B")
        val a = add(plan, "bss")
        val b = add(plan, "bss", "B")
        val original = rule(SlotRole.ACCESSORY, 10, inc = 0.025)
        lifter(plan, a, work(w = 40.0, r = 10), original)
        lifter(plan, b, work(w = 40.0, r = 10), original, "B")
        finish(plan, a, 45.0, listOf(10, 10, 10))
        assertEquals(rows(plan, a), rows(plan, b, "B"))
        assertTrue(requireNotNull(WorkingRows.workingWeight(rows(plan, b, "B"))) >= 45.0)
        assertEquals(rule(a).target, rule(b).target)
        assertEquals(rule(a).state, rule(b).state)
    }

    @Test fun `a SECONDARY exposure keeps its own numbers`() = runTest {
        val plan = authored()
        repo.addDay(plan, "Day B")
        val a = add(plan, "bb_bench")
        val b = add(plan, "bb_bench", "B")
        lifter(plan, a)
        lifter(plan, b, work(n = 2, w = 165.0), rule(SlotRole.SECONDARY).copy(rir = 3), "B")
        finish(plan, a)
        assertEquals(work(n = 2, w = 165.0), rows(plan, b, "B"))
        assertEquals(RepTarget.Fixed(5), rule(b).target)
        assertEquals(3, rule(b).rir)
        assertEquals(1, rule(b).state.successStreak)
    }

    @Test fun `Ask acceptance reaches the matching slot and a revert follows it`() = runTest {
        val plan = converted(day("bb_bench"), day("bb_bench", id = "B"))
        val a = id(plan, "bb_bench")
        val b = id(plan, "bb_bench", "B")
        lifter(plan, a)
        lifter(plan, b, day = "B")
        finish(plan, a)
        assertEquals(NextOffer(185.0, 190.0), offer(plan, "bb_bench"))
        assertEquals(work(), rows(plan, a))
        assertEquals(work(), rows(plan, b, "B"))
        assertEquals(rule(a), rule(b))
        val next = db.programDao().day(plan, "A")!!.nextRunId
        repo.setWorkingWeight(next, plan, "A", a, Slot.MAIN, 190.0)
        assertEquals(work(w = 190.0), rows(plan, a))
        assertEquals(work(w = 190.0), rows(plan, b, "B"))
        repo.setWorkingWeight(next, plan, "A", a, Slot.MAIN, 185.0)
        assertEquals(work(), rows(plan, a))
        assertEquals(work(), rows(plan, b, "B"))
    }

    @Test fun `Undo restores both days`() = runTest {
        val plan = authored()
        repo.addDay(plan, "Day B")
        val a = add(plan, "bb_bench")
        val b = add(plan, "bb_bench", "B")
        lifter(plan, a)
        lifter(plan, b, day = "B")
        val logs = db.programDao().allLogs()
        val exercises = db.programDao().allExercises()
        val session = finish(plan, a)
        assertTrue("\"dayId\":\"B\"" in db.sessionDao().sessionById(session)!!.progressionUndoJson!!)
        assertEquals(UndoResult.Undone, repo.undoFinish(session, null))
        assertEquals(logs, db.programDao().allLogs())
        assertEquals(exercises, db.programDao().allExercises())
    }

    @Test fun `Undo waits while the matching day is open`() = runTest {
        val plan = authored()
        repo.addDay(plan, "Day B")
        val a = add(plan, "bb_bench")
        val b = add(plan, "bb_bench", "B")
        lifter(plan, a)
        lifter(plan, b, day = "B")
        val session = finish(plan, a)
        val run = start(plan, "B")
        assertEquals(UndoResult.DayOpen, repo.undoFinish(session, null))
        assertEquals(work(w = 190.0), rows(plan, b, "B"))
        repo.discardRun(run)
        assertEquals(UndoResult.Undone, repo.undoFinish(session, null))
        assertEquals(work(), rows(plan, b, "B"))
    }

    @Test fun `Rough day reverts both days and unticking restores them`() = runTest {
        val plan = authored()
        repo.addDay(plan, "Day B")
        val a = add(plan, "bb_bench")
        val b = add(plan, "bb_bench", "B")
        lifter(plan, a)
        lifter(plan, b, day = "B")
        val session = finish(plan, a)
        repo.setRoughDay(session, true)
        assertEquals(work(), rows(plan, a))
        assertEquals(work(), rows(plan, b, "B"))
        assertEquals(rule(), rule(b))
        repo.setRoughDay(session, false)
        assertEquals(work(w = 190.0), rows(plan, a))
        assertEquals(work(w = 190.0), rows(plan, b, "B"))
    }

    @Test fun `a slot edited into another shape keeps its rows`() = runTest {
        val plan = authored()
        repo.addDay(plan, "Day B")
        val a = add(plan, "bb_bench")
        val b = add(plan, "bb_bench", "B")
        lifter(plan, a)
        lifter(plan, b, work(n = 4), day = "B")
        finish(plan, a)
        assertEquals(work(n = 4), rows(plan, b, "B"))
        assertEquals(1, rule(b).state.successStreak)
    }
}
