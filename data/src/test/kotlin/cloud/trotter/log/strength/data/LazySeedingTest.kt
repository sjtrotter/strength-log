package cloud.trotter.log.strength.data

import cloud.trotter.log.strength.data.db.entity.ExerciseLogEntity
import cloud.trotter.log.strength.data.db.entity.SessionSetEntity
import cloud.trotter.log.strength.data.db.entity.Slot
import cloud.trotter.log.strength.data.db.entity.WorkoutSessionEntity
import cloud.trotter.log.strength.data.serialization.SetJson
import cloud.trotter.log.strength.data.serialization.SlotRuleJson
import cloud.trotter.log.strength.domain.model.LoggedSet
import cloud.trotter.log.strength.domain.model.MovementPattern
import cloud.trotter.log.strength.domain.model.ProgramDay
import cloud.trotter.log.strength.domain.model.ProgramExercise
import cloud.trotter.log.strength.domain.model.SetKind
import cloud.trotter.log.strength.domain.model.SetStructure
import cloud.trotter.log.strength.domain.model.SupersetPartner
import cloud.trotter.log.strength.domain.progression.LiftGoal
import cloud.trotter.log.strength.domain.progression.SlotRole
import cloud.trotter.log.strength.domain.progression.SlotRule
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
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
class LazySeedingTest : FinishProgressionFixture() {
    private fun partneredBench() = ProgramDay("A", "Day A", "", listOf(
        ProgramExercise("bb_bench", targetSets = 3, superset = SupersetPartner("cable_row")),
    ))

    private suspend fun history(plan: Long) {
        val time = clock.millis() - Duration.ofDays(1).toMillis()
        val session = db.sessionDao().insertSession(WorkoutSessionEntity(0, "A", "Day A", time, time, null, planId = plan))
        db.sessionDao().insertSets(listOf(SessionSetEntity(0, session, "bb_bench", "bb_bench",
            Slot.MAIN, 0, "TOP", 185.0, 5, true)))
    }

    @Test fun `a watched day reseeds a lazy partner when history arrives`() = runTest {
        val plan = converted(partneredBench())
        val b = id(plan, "bb_bench")
        lifter(plan, b, work(4, 185.0, 5), rule())
        val emitted = Channel<List<LoggedSet>?>(Channel.UNLIMITED)
        val collector = backgroundScope.launch {
            repo.observeDay(plan, "A").map { it.slots.single().partner?.seed }.collect { emitted.send(it) }
        }
        assertEquals(work(4, 20.0, 10), emitted.receive())

        val time = clock.millis() - Duration.ofDays(1).toMillis()
        val session = db.sessionDao().insertSession(WorkoutSessionEntity(0, "A", "Day A", time, time, null, planId = plan))
        db.sessionDao().insertSets(listOf(SessionSetEntity(0, session, "cable_row", "cable_row",
            Slot.SS, 0, "WORK", 120.0, 10, true)))

        val expected = work(4, 120.0, 10)
        var received = emitted.receive()
        while (received != expected) received = emitted.receive()
        assertEquals(expected, received)
        collector.cancelAndJoin()
    }

    @Test fun `a watched day reseeds a lazy partner when another day's row changes`() = runTest {
        val plan = converted(partneredBench(), day("cable_row", id = "B"))
        val b = id(plan, "bb_bench")
        val c = id(plan, "cable_row", "B")
        lifter(plan, b, work(4, 185.0, 5), rule())
        lifter(plan, c, work(3, 20.0, 10), rule(SlotRole.ACCESSORY, 10), "B")
        val emitted = Channel<List<LoggedSet>?>(Channel.UNLIMITED)
        val collector = backgroundScope.launch {
            repo.observeDay(plan, "A").map { it.slots.single().partner?.seed }.collect { emitted.send(it) }
        }
        assertEquals(work(4, 20.0, 10), emitted.receive())

        db.programDao().upsertLog(ExerciseLogEntity(plan, "B", c, Slot.MAIN,
            SetJson.encodeSets(work(3, 120.0, 10)), 2000))

        val expected = work(4, 120.0, 10)
        var received = emitted.receive()
        while (received != expected) received = emitted.receive()
        assertEquals(expected, received)
        collector.cancelAndJoin()
    }

    @Test fun `a never-opened partner shows its SlotSeeding rows and ensure seeded writes them with its rule`() = runTest {
        val plan = converted(partneredBench())
        val b = id(plan, "bb_bench")
        lifter(plan, b, work(4, 185.0, 5), rule())
        val main = log(plan, b)
        val before = assertNotNull(db.programDao().exercise(b))
        assertNull(db.programDao().logForSlot(plan, "A", b, Slot.SS))
        assertEquals(rule(SlotRole.ACCESSORY, 10), SlotRuleJson.decode(before.partnerRuleJson))

        val partner = assertNotNull(repo.observeDay(plan, "A").first().slots.single().partner)
        assertNull(partner.stored)
        assertEquals(work(4, 20.0, 10), partner.seed)
        assertNull(db.programDao().logForSlot(plan, "A", b, Slot.SS))
        assertEquals(before.partnerRuleJson, db.programDao().exercise(b)!!.partnerRuleJson)

        repo.ensureSeeded(plan, "A")

        assertEquals(work(4, 20.0, 10), SetJson.decodeSets(
            assertNotNull(db.programDao().logForSlot(plan, "A", b, Slot.SS)).setsJson))
        assertEquals(rule(SlotRole.ACCESSORY, 10, calibrating = true),
            SlotRuleJson.decode(db.programDao().exercise(b)!!.partnerRuleJson))
        assertEquals(main, log(plan, b))
        assertEquals(before.ruleJson, db.programDao().exercise(b)!!.ruleJson)
    }

    @Test fun `a pre-start edit on a lazy partner writes its rule with its rows`() = runTest {
        val plan = converted(partneredBench())
        val b = id(plan, "bb_bench")
        lifter(plan, b, work(4, 185.0, 5), rule())
        val day = assertNotNull(db.programDao().day(plan, "A"))

        assertEquals(RunWriteResult.WorkingValues, repo.applyToRun(day.nextRunId, plan, "A", listOf(
            TrackEdit(b, Slot.SS) { rows -> rows.mapIndexed { i, row ->
                if (i == 0) row.copy(weightLb = 30.0) else row
            } },
        )))

        assertEquals(listOf(LoggedSet(30.0, 10, SetKind.WORK)) + work(3, 20.0, 10), SetJson.decodeSets(
            assertNotNull(db.programDao().logForSlot(plan, "A", b, Slot.SS)).setsJson))
        assertEquals(rule(SlotRole.ACCESSORY, 10, calibrating = true),
            SlotRuleJson.decode(db.programDao().exercise(b)!!.partnerRuleJson))
        assertNull(repo.openRunFlow.first())
    }

    @Test fun `a structure edit leaves a lazy main that seeds from the plan's weight on another day`() = runTest {
        val plan = converted(day("bb_bench"), day("bb_bench", id = "B"))
        val a = id(plan, "bb_bench")
        lifter(plan, id(plan, "bb_bench", "B"), work(3, 185.0, 5), rule(), "B")

        repo.setStructure(a, SetStructure.RAMP_TOP)

        val expectedRule = SlotRule.defaults(SlotRole.MAIN, LiftGoal.STRENGTH,
            SetStructure.RAMP_TOP, 5, MovementPattern.H_PUSH)
        assertNull(db.programDao().logForSlot(plan, "A", a, Slot.MAIN))
        assertEquals(expectedRule, rule(a))
        repo.ensureSeeded(plan, "A")
        assertEquals(listOf(
            LoggedSet(100.0, 5, SetKind.RAMP), LoggedSet(130.0, 5, SetKind.RAMP),
            LoggedSet(150.0, 5, SetKind.RAMP), LoggedSet(165.0, 3, SetKind.RAMP),
            LoggedSet(185.0, 5, SetKind.TOP),
        ), rows(plan, a))
        assertEquals(expectedRule, rule(a))
    }

    @Test fun `leaving Plan mode with nothing parked seeds rows and rule together from history`() = runTest {
        val plan = converted(day("bb_bench"))
        val b = id(plan, "bb_bench")
        history(plan)
        val before = rule(b)
        db.programDao().deleteLogForSlot(plan, "A", b, Slot.MAIN)
        repo.setKeepToPlan(b, true)
        repo.setKeepToPlan(b, false)
        assertNull(db.programDao().logForSlot(plan, "A", b, Slot.MAIN))

        repo.ensureSeeded(plan, "A")

        assertEquals(work(3, 185.0, 5), rows(plan, b))
        assertEquals(before.copy(state = before.state.copy(calibrating = false)), rule(b))
    }

    @Test fun `a Plan mode slot seeds rows only`() = runTest {
        val plan = converted(day("bb_bench"))
        val b = id(plan, "bb_bench")
        history(plan)
        val before = db.programDao().exercise(b)!!.ruleJson
        repo.setKeepToPlan(b, true)
        assertNull(db.programDao().logForSlot(plan, "A", b, Slot.MAIN))

        repo.ensureSeeded(plan, "A")

        assertEquals(work(3, 185.0, 5), rows(plan, b))
        assertEquals(before, db.programDao().exercise(b)!!.ruleJson)
    }

    @Test fun `a restored skipped lift reopens at its stored rows`() = runTest {
        val plan = converted(day("bb_bench", "ohp"))
        val b = id(plan, "bb_bench")
        lifter(plan, b, work(3, 185.0, 5), rule())
        val run = start(plan)
        assertIs<RunWriteResult.InRun>(repo.skipLift(plan, "A", b, Slot.MAIN, "bb_bench", null))
        lifter(plan, b, work(3, 190.0, 5), rule())

        assertIs<RunWriteResult.InRun>(repo.restoreSkippedLift(run, b))

        val bench = repo.openRunFlow.first()!!.tracks.single {
            it.programExerciseId == b && it.slot == Slot.MAIN && !it.retired
        }
        assertEquals(work(3, 190.0, 5), bench.planned)
        assertEquals(work(3, 190.0, 5).mapIndexed { i, row -> row.copy(plannedIndex = i) }, bench.sets)
    }

    @Test fun `restoring a missing stored track builds from its seed without writing rows or rule`() = runTest {
        val plan = converted(day("bb_bench", "ohp"))
        val b = id(plan, "bb_bench")
        val before = assertNotNull(db.programDao().exercise(b)).ruleJson
        val run = start(plan)
        assertIs<RunWriteResult.InRun>(repo.skipLift(plan, "A", b, Slot.MAIN, "bb_bench", null))
        db.programDao().deleteLogForSlot(plan, "A", b, Slot.MAIN)
        history(plan)

        assertIs<RunWriteResult.InRun>(repo.restoreSkippedLift(run, b))

        val bench = repo.openRunFlow.first()!!.tracks.single {
            it.programExerciseId == b && it.slot == Slot.MAIN && !it.retired
        }
        assertEquals(work(3, 185.0, 5), bench.planned)
        assertEquals(work(3, 185.0, 5).mapIndexed { i, row -> row.copy(plannedIndex = i) }, bench.sets)
        assertNull(db.programDao().logForSlot(plan, "A", b, Slot.MAIN))
        assertEquals(before, db.programDao().exercise(b)!!.ruleJson)
    }

    @Test fun `a partner's working rows seed the same lift added as a main on another day`() = runTest {
        val plan = converted(partneredBench(), day("ohp", id = "B"))
        val b = id(plan, "bb_bench")
        db.programDao().upsertLog(ExerciseLogEntity(plan, "A", b, Slot.SS,
            SetJson.encodeSets(work(3, 120.0, 10)), 1000))

        val c = add(plan, "cable_row", "B")

        assertEquals(work(3, 120.0, 10), rows(plan, c, "B"))
        assertEquals(rule(SlotRole.ACCESSORY, 10), rule(c))
    }
}
