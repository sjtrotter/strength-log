package cloud.trotter.log.strength.data

import cloud.trotter.log.strength.data.db.entity.ExerciseLogEntity
import cloud.trotter.log.strength.data.db.entity.SessionSetEntity
import cloud.trotter.log.strength.data.db.entity.Slot
import cloud.trotter.log.strength.data.db.entity.WorkoutSessionEntity
import cloud.trotter.log.strength.data.serialization.SetJson
import cloud.trotter.log.strength.domain.model.LoggedSet
import cloud.trotter.log.strength.domain.model.SetKind
import cloud.trotter.log.strength.domain.model.SetStructure
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PlanRowsTest : FinishProgressionFixture() {
    private suspend fun history(plan: Long) {
        val time = clock.millis() - Duration.ofDays(1).toMillis()
        val session = db.sessionDao().insertSession(WorkoutSessionEntity(0, "A", "Day A", time, time, null, planId = plan))
        db.sessionDao().insertSets(listOf(SessionSetEntity(0, session, "bb_bench", "bb_bench",
            Slot.MAIN, 0, "TOP", 185.0, 5, true)))
    }

    @Test fun `plan rows are the stored rows whatever run is open`() = runTest {
        val plan = converted(day("bb_bench"))
        val a = id(plan, "bb_bench")
        lifter(plan, a, work(4, 175.0, 5), rule())
        val expected = mapOf(a to work(4, 175.0, 5))
        assertEquals(expected, repo.observePlanRows(plan, "A").first())

        val run = start(plan)
        assertIs<RunWriteResult.InRun>(repo.writeTestRows(run, "A", a, Slot.MAIN, work(4, 200.0, 5)))
        assertTrue(repo.swapInRun(run, a, Slot.MAIN, "db_bench"))

        assertEquals(expected, repo.observePlanRows(plan, "A").first())
    }

    @Test fun `a slot without a stored row reads its seed and writes nothing`() = runTest {
        val plan = converted(day("bb_bench"), day("bb_bench", id = "B"))
        val a = id(plan, "bb_bench")
        lifter(plan, id(plan, "bb_bench", "B"), work(3, 185.0, 5), rule(), "B")
        repo.setStructure(a, SetStructure.RAMP_TOP)

        assertEquals(mapOf(a to listOf(
            LoggedSet(100.0, 5, SetKind.RAMP), LoggedSet(130.0, 5, SetKind.RAMP),
            LoggedSet(150.0, 5, SetKind.RAMP), LoggedSet(165.0, 3, SetKind.RAMP),
            LoggedSet(185.0, 5, SetKind.TOP),
        )), repo.observePlanRows(plan, "A").first())
        assertNull(db.programDao().logForSlot(plan, "A", a, Slot.MAIN))
    }

    @Test fun `an unprescribed Plan mode slot's plan rows are its seed`() = runTest {
        val plan = converted(day("bb_bench"))
        val b = id(plan, "bb_bench")
        history(plan)
        repo.setKeepToPlan(b, true)

        assertEquals(mapOf(b to work(3, 185.0, 5)), repo.observePlanRows(plan, "A").first())
        assertNull(db.programDao().logForSlot(plan, "A", b, Slot.MAIN))
    }

    @Test fun `an unknown unprescribed slot has no plan rows even with a stored row`() = runTest {
        val plan = converted(day("bb_bench", "ghost_unknown"))
        val ghost = id(plan, "ghost_unknown")
        db.programDao().upsertLog(ExerciseLogEntity(plan, "A", ghost, Slot.MAIN,
            SetJson.encodeSets(work(3, 50.0, 8)), 1000))

        assertEquals(mapOf(id(plan, "bb_bench") to work(3, 45.0, 5)), repo.observePlanRows(plan, "A").first())
    }

    @Test fun `watched plan rows reseed when history arrives`() = runTest {
        val plan = converted(day("bb_bench"))
        val b = id(plan, "bb_bench")
        db.programDao().deleteLogForSlot(plan, "A", b, Slot.MAIN)
        val emitted = Channel<List<LoggedSet>?>(Channel.UNLIMITED)
        val collector = backgroundScope.launch {
            repo.observePlanRows(plan, "A").map { it[b] }.collect { emitted.send(it) }
        }
        assertEquals(work(3, 45.0, 5), emitted.receive())

        history(plan)

        val expected = work(3, 185.0, 5)
        var received = emitted.receive()
        while (received != expected) received = emitted.receive()
        assertEquals(expected, received)
        collector.cancelAndJoin()
    }
}
