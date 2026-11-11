package cloud.trotter.log.strength.data

import cloud.trotter.log.strength.data.db.entity.Slot
import cloud.trotter.log.strength.domain.progression.Effort
import cloud.trotter.log.strength.domain.seeding.SetEditor
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class FinishEffortTest : FinishProgressionFixture() {
    @Test fun `three Easy sets double the increase`() = runTest {
        val plan = authored()
        val id = add(plan, "bb_back_squat")
        lifter(plan, id, work(w = 275.0), rule(inc = 0.025))
        val run = start(plan)
        perform(run, plan, id, 275.0)
        for (i in 0..2) pick(run, plan, id, i, Effort.EASY)
        val session = assertNotNull(repo.finishRun(run))
        assertEquals(work(w = 285.0), rows(plan, id))
        assertEquals(1, rule(id).state.successStreak)
        assertEquals(listOf(4, 4, 4), db.sessionDao().setsForSession(session).map { it.rir })
    }

    @Test fun `no picks add one step`() = runTest {
        val plan = authored()
        val id = add(plan, "bb_back_squat")
        lifter(plan, id, work(w = 275.0), rule(inc = 0.025))
        val run = start(plan)
        perform(run, plan, id, 275.0)
        val session = assertNotNull(repo.finishRun(run))
        assertEquals(work(w = 280.0), rows(plan, id))
        assertEquals(listOf(null, null, null), db.sessionDao().setsForSession(session).map { it.rir })
    }

    @Test fun `a Max set holds the load as an effort miss`() = runTest {
        val plan = authored()
        val id = add(plan, "bb_back_squat")
        lifter(plan, id, work(w = 275.0), rule(inc = 0.025))
        val run = start(plan)
        perform(run, plan, id, 275.0)
        pick(run, plan, id, 1, Effort.MAX)
        repo.finishRun(run)
        assertEquals(work(w = 275.0), rows(plan, id))
        assertEquals(0, rule(id).state.successStreak)
        assertEquals(1, rule(id).state.effortMissStreak)
    }

    @Test fun `a Hard set still earns the step`() = runTest {
        val plan = authored()
        val id = add(plan, "bb_back_squat")
        lifter(plan, id, work(w = 275.0), rule(inc = 0.025))
        val run = start(plan)
        perform(run, plan, id, 275.0)
        pick(run, plan, id, 1, Effort.HARD)
        repo.finishRun(run)
        assertEquals(work(w = 280.0), rows(plan, id))
    }

    @Test fun `a cleared pick reads as on target`() = runTest {
        val plan = authored()
        val id = add(plan, "bb_back_squat")
        lifter(plan, id, work(w = 275.0), rule(inc = 0.025))
        val run = start(plan)
        perform(run, plan, id, 275.0)
        pick(run, plan, id, 1, Effort.MAX)
        pick(run, plan, id, 1, null)
        val session = assertNotNull(repo.finishRun(run))
        assertEquals(work(w = 280.0), rows(plan, id))
        assertEquals(listOf(null, null, null), db.sessionDao().setsForSession(session).map { it.rir })
    }

    private suspend fun pick(run: String, plan: Long, id: Long, index: Int, effort: Effort?) {
        repo.applyToRun(run, plan, "A", listOf(
            TrackEdit(id, Slot.MAIN) { SetEditor.effort(it, index, effort) },
        ))
    }
}
