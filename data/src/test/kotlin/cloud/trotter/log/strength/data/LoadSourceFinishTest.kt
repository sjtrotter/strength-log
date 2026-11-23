package cloud.trotter.log.strength.data

import cloud.trotter.log.strength.data.db.entity.Slot
import cloud.trotter.log.strength.data.serialization.ProgressionUndoJson
import cloud.trotter.log.strength.data.serialization.OpeningDto
import cloud.trotter.log.strength.data.serialization.OpeningJson
import cloud.trotter.log.strength.data.serialization.SetJson
import cloud.trotter.log.strength.data.serialization.SessionPlanJson
import cloud.trotter.log.strength.domain.model.ProgramExercise
import cloud.trotter.log.strength.domain.model.PrescribedTarget
import cloud.trotter.log.strength.domain.model.Prescription
import cloud.trotter.log.strength.domain.progression.LoadSource
import cloud.trotter.log.strength.domain.progression.RepTarget
import cloud.trotter.log.strength.domain.progression.SlotRole
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LoadSourceFinishTest : FinishProgressionFixture() {
    private suspend fun linked(ask: Boolean = false, exercise: String = "bb_back_squat"): Triple<Long, Long, Long> {
        val plan = if (ask) converted(day(exercise), day(exercise, id = "C")) else authored().also {
            repo.addDay(it, "Day B")
            assertEquals("C", repo.addDay(it, "Day C"))
        }
        val a = if (ask) id(plan, exercise) else add(plan, exercise)
        val c = if (ask) id(plan, exercise, "C") else add(plan, exercise, "C")
        lifter(plan, a)
        lifter(plan, c, work(w = 170.0), rule().copy(rir = 3), "C")
        repo.editRule(c, Slot.MAIN, RuleEdit.LoadSource("A"))
        assertEquals(LoadSource("A", 170.0 / 185.0), rule(c).loadSource)
        assertEquals(work(w = 170.0), rows(plan, c, "C"))
        return Triple(plan, a, c)
    }

    private suspend fun weight(plan: Long, id: Long, day: String, weight: Double) =
        repo.setWorkingWeight(db.programDao().day(plan, day)!!.nextRunId, plan, day, id, Slot.MAIN, weight)

    @Test fun `case 46 day A's increase moves the follower and a miss holds it`() = runTest {
        val (plan, a, c) = linked()
        finish(plan, a)
        assertEquals(work(w = 190.0), rows(plan, a))
        assertEquals(work(w = 175.0), rows(plan, c, "C"))
        assertFalse(rule(c).state.calibrating)
        finish(plan, c, 175.0, listOf(5, 5, 4), "C")
        assertEquals(work(w = 175.0), rows(plan, c, "C"))
        assertEquals(1, rule(c).state.repMissStreak)
        assertEquals(work(w = 190.0), rows(plan, a))
        finish(plan, a, 190.0)
        assertEquals(work(w = 195.0), rows(plan, a))
        assertEquals(work(w = 175.0), rows(plan, c, "C"))
        val session = finish(plan, c, 175.0, day = "C")
        assertEquals(work(w = 180.0), rows(plan, c, "C"))
        val state = rule(c).state
        assertEquals(listOf(1, 0, 0), listOf(state.successStreak, state.repMissStreak, state.effortMissStreak))
        assertEquals("FOLLOWED", SessionPlanJson.decode(db.sessionDao().sessionById(session)!!.plannedJson!!).single().reason)
    }

    @Test fun `case 47 Undo restores the follower and switching the source leaves the load`() = runTest {
        val (plan, a, c) = linked()
        val session = finish(plan, a)
        val undo = db.sessionDao().sessionById(session)!!.progressionUndoJson!!
        assertTrue("\"followed\":true" in undo)
        assertTrue("\"dayId\":\"C\"" in undo)
        assertEquals(UndoResult.Undone, repo.undoFinish(session, null))
        assertEquals(work(w = 170.0), rows(plan, c, "C"))
        assertEquals(LoadSource("A", 170.0 / 185.0), rule(c).loadSource)
        finish(plan, a)
        repo.editRule(c, Slot.MAIN, RuleEdit.LoadSource(null))
        assertEquals(work(w = 175.0), rows(plan, c, "C"))
        assertNull(rule(c).loadSource)
        finish(plan, a, 190.0)
        assertEquals(work(w = 195.0), rows(plan, a))
        assertEquals(work(w = 175.0), rows(plan, c, "C"))
        repo.editRule(c, Slot.MAIN, RuleEdit.LoadSource("A"))
        assertEquals(work(w = 175.0), rows(plan, c, "C"))
        assertEquals(LoadSource("A", 175.0 / 195.0), rule(c).loadSource)
    }

    @Test fun `an edit to the follower's load resets the ratio`() = runTest {
        val (plan, a, c) = linked()
        weight(plan, c, "C", 165.0)
        assertEquals(LoadSource("A", 165.0 / 185.0), rule(c).loadSource)
        finish(plan, a)
        assertEquals(work(w = 170.0), rows(plan, c, "C"))
    }

    @Test fun `an in-run edit to the follower's load resets the ratio`() = runTest {
        val (plan, _, c) = linked()
        val run = start(plan, "C")
        repo.setWorkingWeight(run, plan, "C", c, Slot.MAIN, 165.0)
        assertEquals(work(w = 165.0), rows(plan, c, "C"))
        assertEquals(LoadSource("A", 165.0 / 185.0), rule(c).loadSource)
    }

    @Test fun `Rough day reverts the follower and unticking restores it`() = runTest {
        val (plan, a, c) = linked()
        val session = finish(plan, a)
        assertEquals(UndoResult.Undone, repo.setRoughDay(session, true))
        assertEquals(work(w = 170.0), rows(plan, c, "C"))
        assertEquals(UndoResult.Undone, repo.setRoughDay(session, false))
        assertEquals(work(w = 175.0), rows(plan, c, "C"))
    }

    @Test fun `Undo waits while the follower's day is open`() = runTest {
        val (plan, a, _) = linked()
        val session = finish(plan, a)
        start(plan, "C")
        assertEquals(UndoResult.DayOpen, repo.undoFinish(session, null))
    }

    @Test fun `an Ask acceptance moves the follower and nothing moves before it`() = runTest {
        val (plan, a, c) = linked(ask = true)
        finish(plan, a)
        assertEquals(NextOffer(185.0, 190.0), offer(plan, "bb_back_squat"))
        assertEquals(work(w = 170.0), rows(plan, c, "C"))
        weight(plan, a, "A", 190.0)
        assertEquals(work(w = 175.0), rows(plan, c, "C"))
        weight(plan, a, "A", 185.0)
        assertEquals(work(w = 170.0), rows(plan, c, "C"))
    }

    @Test fun `a follower in Ask takes its derived load without an offer`() = runTest {
        val (plan, a, c) = linked(ask = true)
        finish(plan, a)
        weight(plan, a, "A", 190.0)
        finish(plan, c, 175.0, listOf(5, 5, 4), "C")
        finish(plan, a, 190.0)
        weight(plan, a, "A", 195.0)
        finish(plan, c, 175.0, day = "C")
        assertEquals(work(w = 180.0), rows(plan, c, "C"))
        assertNull(offer(plan, "bb_back_squat", "C"))
    }

    @Test fun `a chain follows in one Finish`() = runTest {
        val (plan, a, c) = linked()
        repo.addDay(plan, "Day D")
        assertEquals("E", repo.addDay(plan, "Day E"))
        val e = add(plan, "bb_back_squat", "E")
        lifter(plan, e, work(w = 160.0), rule().copy(rir = 3), "E")
        repo.editRule(e, Slot.MAIN, RuleEdit.LoadSource("C"))
        val session = finish(plan, a)
        assertEquals(work(w = 175.0), rows(plan, c, "C"))
        assertEquals(work(w = 165.0), rows(plan, e, "E"))
        val followed = ProgressionUndoJson.decode(db.sessionDao().sessionById(session)!!.progressionUndoJson)!!.logs.filter { it.followed }
        assertEquals(setOf(c, e), followed.map { it.programExerciseId }.toSet())
        assertTrue(followed.all { it.sharedFrom == a })
    }

    @Test fun `a same day source is the first eligible slot`() = runTest {
        val (plan, a, c) = linked()
        repo.addExercise("A", ProgramExercise("bb_back_squat"), plan)
        val later = db.programDao().exercisesForDay(plan, "A").last().id
        lifter(plan, later, work(w = 100.0))
        finish(plan, later, 100.0)
        assertEquals(work(w = 170.0), rows(plan, c, "C"))
        finish(plan, a)
        assertEquals(work(w = 175.0), rows(plan, c, "C"))
    }

    @Test fun `a Range follower keeps its reps progression and its ratio`() = runTest {
        val (plan, a, c) = linked(exercise = "db_row")
        lifter(plan, a, work(w = 100.0, r = 8), rule(reps = 8))
        lifter(plan, c, work(w = 60.0, r = 8), rule(SlotRole.ACCESSORY).copy(target = RepTarget.Range(8, 12, 8)), "C")
        repo.editRule(c, Slot.MAIN, RuleEdit.LoadSource("A"))
        assertEquals(LoadSource("A", 0.6), rule(c).loadSource)
        finish(plan, c, 60.0, listOf(10, 9, 8), "C")
        assertEquals(work(w = 60.0, r = 9), rows(plan, c, "C"))
        assertEquals(RepTarget.Range(8, 12, 9), rule(c).target)
        assertEquals(LoadSource("A", 0.6), rule(c).loadSource)
        finish(plan, a, 100.0, listOf(8, 8, 8))
        assertEquals(work(w = 105.0, r = 8), rows(plan, a))
        assertEquals(work(w = 65.0, r = 8), rows(plan, c, "C"))
        assertEquals(RepTarget.Range(8, 12, 8), rule(c).target)
        assertEquals(LoadSource("A", 0.6), rule(c).loadSource)
        lifter(plan, c, work(w = 65.0, r = 11), rule(c).copy(target = RepTarget.Range(8, 12, 11)), "C")
        finish(plan, a, 105.0, listOf(8, 8, 8))
        assertEquals(work(w = 110.0, r = 8), rows(plan, a))
        assertEquals(work(w = 65.0, r = 11), rows(plan, c, "C"))
        assertEquals(LoadSource("A", 0.6), rule(c).loadSource)
        lifter(plan, a, work(w = 120.0, r = 8), rule(reps = 8))
        finish(plan, a, 120.0, listOf(8, 8, 8))
        assertEquals(work(w = 125.0, r = 8), rows(plan, a))
        assertEquals(work(w = 75.0, r = 8), rows(plan, c, "C"))
    }

    @Test fun `a copied sibling moves its followers`() = runTest {
        val (plan, a, c) = linked()
        val b = add(plan, "bb_back_squat", "B")
        lifter(plan, b, day = "B")
        repo.editRule(c, Slot.MAIN, RuleEdit.LoadSource("B"))
        finish(plan, a)
        assertEquals(work(w = 190.0), rows(plan, b, "B"))
        assertEquals(work(w = 175.0), rows(plan, c, "C"))
    }

    @Test fun `a source changed to reps leaves the follower alone`() = runTest {
        val (plan, a, c) = linked()
        repo.setPrescription(a, Prescription(PrescribedTarget.Reps(5)))
        finish(plan, a)
        assertEquals(work(w = 170.0), rows(plan, c, "C"))
        assertEquals(LoadSource("A", 170.0 / 185.0), rule(c).loadSource)
    }

    @Test fun `a fixed source past the bound keeps its established follower`() = runTest {
        val (plan, a, c) = linked()
        lifter(plan, a, work(r = 8), rule(reps = 8).copy(rir = 3))
        finish(plan, a, 185.0, listOf(8, 8, 8))
        assertEquals(work(w = 190.0, r = 8), rows(plan, a))
        assertEquals(work(w = 175.0), rows(plan, c, "C"))
        assertEquals(LoadSource("A", 170.0 / 185.0), rule(c).loadSource)
    }

    @Test fun `source edits allow a chain without a cycle`() = runTest {
        val (plan, a, c) = linked()
        repo.editRule(c, Slot.MAIN, RuleEdit.LoadSource(null))
        val b = add(plan, "bb_back_squat", "B")
        lifter(plan, b, work(w = 180.0), day = "B")
        repo.editRule(b, Slot.MAIN, RuleEdit.LoadSource("C"))
        repo.editRule(a, Slot.MAIN, RuleEdit.LoadSource("B"))
        assertEquals(LoadSource("B", 185.0 / 180.0), rule(a).loadSource)
        assertEquals(LoadSource("C", 180.0 / 170.0), rule(b).loadSource)
        assertNull(rule(c).loadSource)
        assertEquals(work(), rows(plan, a))
        assertEquals(work(w = 180.0), rows(plan, b, "B"))
        assertEquals(work(w = 170.0), rows(plan, c, "C"))
    }

    @Test fun `source edits reject a two hop cycle`() = runTest {
        val (plan, a, c) = linked()
        val b = add(plan, "bb_back_squat", "B")
        lifter(plan, b, work(w = 180.0), day = "B")
        repo.editRule(c, Slot.MAIN, RuleEdit.LoadSource("B"))
        repo.editRule(a, Slot.MAIN, RuleEdit.LoadSource("C"))
        assertEquals(LoadSource("C", 185.0 / 170.0), rule(a).loadSource)
        assertEquals(LoadSource("B", 170.0 / 180.0), rule(c).loadSource)
        repo.editRule(b, Slot.MAIN, RuleEdit.LoadSource("A"))
        assertNull(rule(b).loadSource)
        assertEquals(LoadSource("C", 185.0 / 170.0), rule(a).loadSource)
        assertEquals(LoadSource("B", 170.0 / 180.0), rule(c).loadSource)
        assertEquals(work(), rows(plan, a))
        assertEquals(work(w = 180.0), rows(plan, b, "B"))
        assertEquals(work(w = 170.0), rows(plan, c, "C"))
    }

    @Test fun `source edits reject a cycle and Plan slots`() = runTest {
        val (plan, a, c) = linked()
        repo.editRule(a, Slot.MAIN, RuleEdit.LoadSource("C"))
        assertNull(rule(a).loadSource)
        repo.editRule(c, Slot.MAIN, RuleEdit.LoadSource(null))
        assertNull(rule(c).loadSource)
        repo.setPrescription(c, Prescription(PrescribedTarget.LoadReps(170.0, 5)))
        assertTrue(repo.setKeepToPlan(c, true))
        repo.editRule(c, Slot.MAIN, RuleEdit.LoadSource("A"))
        assertNull(rule(c).loadSource)
        repo.editRule(a, Slot.MAIN, RuleEdit.LoadSource("C"))
        assertNull(rule(a).loadSource)
        assertEquals(work(), rows(plan, a))
    }
    @Test fun `a removed source leaves the follower alone`() = runTest {
        val (plan, a, c) = linked()
        add(plan, "ez_curl")
        assertTrue(repo.removeExercise(a))
        finish(plan, c, 170.0, day = "C")
        assertEquals(work(w = 175.0), rows(plan, c, "C"))
        assertEquals(LoadSource("A", 170.0 / 185.0), rule(c).loadSource)
    }

    @Test fun `a plain source edit waits for a Finish that moves its load`() = runTest {
        val (plan, a, c) = linked()
        weight(plan, a, "A", 190.0)
        assertEquals(work(w = 170.0), rows(plan, c, "C"))
        finish(plan, a, 190.0)
        assertEquals(work(w = 195.0), rows(plan, a))
        assertEquals(work(w = 180.0), rows(plan, c, "C"))
    }

    @Test fun `followed writes name the day and both loads`() = runTest {
        val (plan, a, c) = linked()
        val session = finish(plan, a)
        assertEquals(mapOf(a to listOf(FollowedWrite("C", 170.0, 175.0))), repo.followedWrites(session))
        finish(plan, c, 175.0, listOf(5, 5, 4), "C")
        val held = finish(plan, a, 190.0)
        assertTrue(repo.followedWrites(held).isEmpty())
    }

    @Test fun `source edits refuse an excessive ratio and the picker omits that day`() = runTest {
        val (plan, _, c) = linked()
        repo.editRule(c, Slot.MAIN, RuleEdit.LoadSource(null))
        lifter(plan, c, work(w = 800.0), rule().copy(rir = 3), "C")
        val before = rule(c)
        repo.editRule(c, Slot.MAIN, RuleEdit.LoadSource("A")) // #647: refusal has no result type.
        assertEquals(before, rule(c))
        assertNull(rule(c).loadSource)
        assertEquals(work(w = 800.0), rows(plan, c, "C"))
        assertTrue(repo.observeLoadSourceDays(plan).first().getValue(c).isEmpty())
        lifter(plan, c, work(w = 740.0), rule().copy(rir = 3), "C")
        assertEquals(listOf("A"), repo.observeLoadSourceDays(plan).first().getValue(c))
        repo.editRule(c, Slot.MAIN, RuleEdit.LoadSource("A")) // #647: the stored rule proves acceptance.
        assertEquals(LoadSource("A", 4.0), rule(c).loadSource)
    }

    @Test fun `a hand edit outside the ratio bound leaves the stored ratio intact`() = runTest {
        val (plan, _, c) = linked()
        val prior = rule(c).loadSource
        weight(plan, c, "C", 800.0)
        assertEquals(work(w = 800.0), rows(plan, c, "C"))
        assertEquals(prior, rule(c).loadSource)
        val run = start(plan, "C")
        repo.setWorkingWeight(run, plan, "C", c, Slot.MAIN, 900.0)
        assertEquals(work(w = 900.0), rows(plan, c, "C"))
        assertEquals(prior, rule(c).loadSource)
    }

    @Test fun `a copied source skips a follower with its own track in the run`() = runTest {
        val plan = authored()
        repo.addDay(plan, "Day B")
        val a = add(plan, "bb_back_squat")
        val b = add(plan, "bb_back_squat", "B")
        repo.addExercise("A", ProgramExercise("bb_back_squat", targetSets = 3), plan)
        val follower = db.programDao().exercisesForDay(plan, "A").last().id
        lifter(plan, a)
        lifter(plan, b, day = "B")
        lifter(plan, follower, work(w = 170.0), rule().copy(rir = 3))
        repo.editRule(follower, Slot.MAIN, RuleEdit.LoadSource("B"))
        val link = rule(follower).loadSource
        val run = start(plan)
        perform(run, plan, a, 185.0)
        perform(run, plan, follower, 170.0, listOf(5, 5, 4))
        val session = requireNotNull(repo.finishRun(run))
        assertEquals(work(w = 190.0), rows(plan, b, "B"))
        assertEquals(work(w = 170.0), rows(plan, follower))
        assertEquals(link, rule(follower).loadSource)
        assertEquals(1, rule(follower).state.repMissStreak)
        assertTrue(repo.followedWrites(session).isEmpty())
    }

    @Test fun `followed writes track Rough day while preserving restart exclusions`() = runTest {
        for (opening in listOf(OpeningDto(OpeningDto.SCALED, 0.85), OpeningDto(OpeningDto.RECALIBRATE))) {
            val (plan, a, c) = linked()
            val bench = add(plan, "bb_bench")
            val benchFollower = add(plan, "bb_bench", "C")
            lifter(plan, bench)
            lifter(plan, benchFollower, work(w = 170.0), rule().copy(rir = 3), "C")
            repo.editRule(benchFollower, Slot.MAIN, RuleEdit.LoadSource("A"))
            val run = start(plan)
            val track = db.runDao().tracks(run).single { it.programExerciseId == a }
            db.runDao().updateTrack(track.copy(plannedJson = SetJson.encodeSets(work(w = 155.0)),
                openingJson = OpeningJson.encode(opening)))
            perform(run, plan, a, 155.0, listOf(5, 5, 4))
            perform(run, plan, bench, 185.0)
            val session = requireNotNull(repo.finishRun(run))
            val applied = repo.followedWrites(session)
            assertEquals(setOf(a, bench), applied.keys)
            val restartRows = rows(plan, c, "C")
            assertEquals(UndoResult.Undone, repo.setRoughDay(session, true))
            assertEquals(mapOf(a to applied.getValue(a)), repo.followedWrites(session))
            assertEquals(restartRows, rows(plan, c, "C"))
            assertEquals(work(w = 170.0), rows(plan, benchFollower, "C"))
            assertEquals(UndoResult.Undone, repo.setRoughDay(session, false))
            assertEquals(applied, repo.followedWrites(session))
            assertEquals(work(w = 175.0), rows(plan, benchFollower, "C"))
        }
    }

}
