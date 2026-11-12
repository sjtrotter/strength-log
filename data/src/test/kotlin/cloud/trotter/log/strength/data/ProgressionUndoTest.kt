package cloud.trotter.log.strength.data

import cloud.trotter.log.strength.data.db.entity.Slot
import cloud.trotter.log.strength.domain.model.ProgramExercise
import cloud.trotter.log.strength.domain.progression.SlotRole
import android.database.sqlite.SQLiteException
import kotlin.test.assertFailsWith
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
class ProgressionUndoTest : FinishProgressionFixture() {
    override val fileBacked = true

    private suspend fun fixture(): Triple<Long, Long, Long> {
        val plan = authored()
        val s = add(plan, "bb_back_squat")
        val c = add(plan, "ez_curl")
        lifter(plan, s, work(w = 95.0), rule(inc = 0.025))
        lifter(plan, c, work(w = 60.0, r = 10), rule(SlotRole.ACCESSORY, 10))
        return Triple(plan, s, c)
    }
    private suspend fun finishBoth(plan: Long, s: Long, c: Long): Long {
        val run = start(plan)
        perform(run, plan, s, 95.0)
        perform(run, plan, c, 60.0, listOf(10, 10, 8))
        return assertNotNull(repo.finishRun(run))
    }

    @Test fun `undo after a restart restores every row and rule the Finish wrote`() = runTest {
        val (plan, s, c) = fixture()
        val beforeLogs = listOf(log(plan, s), log(plan, c))
        val beforeRules = listOf(db.programDao().exercise(s)!!.ruleJson, db.programDao().exercise(c)!!.ruleJson)
        val sessionId = finishBoth(plan, s, c)
        assertEquals(work(w = 100.0), rows(plan, s))
        assertEquals(1, rule(c).state.repMissStreak)
        val session = assertNotNull(db.sessionDao().sessionById(sessionId))
        assertNotNull(session.progressionUndoJson)
        val sets = db.sessionDao().allSessionSets()
        val suggested = db.planDao().plan(plan)!!.suggestedDayId
        val nextRunId = db.programDao().day(plan, "A")!!.nextRunId
        restart()
        assertEquals(UndoResult.Undone, repo.undoFinish(sessionId, null))
        assertEquals(beforeLogs, listOf(log(plan, s), log(plan, c)))
        assertEquals(beforeRules, listOf(db.programDao().exercise(s)!!.ruleJson, db.programDao().exercise(c)!!.ruleJson))
        assertEquals(session.copy(progressionUndoJson = null), db.sessionDao().sessionById(sessionId))
        assertEquals(sets, db.sessionDao().allSessionSets())
        assertEquals(suggested, db.planDao().plan(plan)!!.suggestedDayId)
        assertEquals(nextRunId, db.programDao().day(plan, "A")!!.nextRunId)
        assertEquals(UndoResult.Nothing, repo.undoFinish(sessionId, null))
    }

    @Test fun `undo leaves a row changed since Finish`() = runTest {
        val (plan, s, c) = fixture()
        val beforeRules = listOf(rule(s), rule(c))
        val beforeCurl = log(plan, c)
        val session = finishBoth(plan, s, c)
        repo.setWorkingWeight(db.programDao().day(plan, "A")!!.nextRunId, plan, "A", s, Slot.MAIN, 95.0)
        val edited = log(plan, s)
        assertEquals(UndoResult.Undone, repo.undoFinish(session, null))
        assertEquals(edited, log(plan, s))
        assertEquals(work(w = 95.0), rows(plan, s))
        assertEquals(beforeCurl, log(plan, c))
        assertEquals(beforeRules, listOf(rule(s), rule(c)))
    }

    @Test fun `a later Finish on the plan or the day's next start clears the record`() = runTest {
        val plan = authored()
        val s = add(plan, "bb_back_squat")
        repo.addDay(plan, "Day B")
        repo.addExercise("B", ProgramExercise("bb_bench", targetSets = 3), plan)
        val b = id(plan, "bb_bench", "B")
        lifter(plan, s, work(w = 95.0), rule(inc = 0.025))
        lifter(plan, b, day = "B")
        val aSession = finish(plan, s, 95.0)
        val bSession = finish(plan, b, day = "B")
        assertNull(db.sessionDao().sessionById(aSession)!!.progressionUndoJson)
        assertNotNull(db.sessionDao().sessionById(bSession)!!.progressionUndoJson)
        start(plan, "B")
        assertNull(db.sessionDao().sessionById(bSession)!!.progressionUndoJson)
        assertEquals(UndoResult.Nothing, repo.undoFinish(aSession, null))
        assertEquals(UndoResult.DayOpen, repo.undoFinish(bSession, null))
    }
    @Test fun `undo also restores cross-day success credit`() = runTest {
        val plan = converted(day("bb_back_squat"), day("bb_back_squat", id = "C"))
        val a = id(plan, "bb_back_squat")
        val c = id(plan, "bb_back_squat", "C")
        lifter(plan, a, work(w = 95.0), rule(inc = 0.025))
        lifter(plan, c, work(w = 95.0), rule(inc = 0.025), "C")
        val before = db.programDao().exercise(c)!!.ruleJson
        val session = finish(plan, a, 95.0)
        assertEquals(1, rule(c).state.successStreak)
        assertEquals(UndoResult.Undone, repo.undoFinish(session, null))
        assertEquals(before, db.programDao().exercise(c)!!.ruleJson)
    }

    @Test fun `an unreadable undo record stays untouched`() = runTest {
        val (plan, s, c) = fixture()
        val id = finishBoth(plan, s, c)
        db.openHelper.writableDatabase.execSQL("UPDATE workout_session SET progressionUndoJson = 'junk' WHERE id = ?", arrayOf(id))
        val before = db.sessionDao().sessionById(id)
        assertEquals(UndoResult.Nothing, repo.undoFinish(id, null))
        assertEquals(before, db.sessionDao().sessionById(id))
    }

    @Test fun `a failed undo rolls back every restore and keeps its record`() = runTest {
        val (plan, s, c) = fixture()
        val session = finishBoth(plan, s, c)
        val logs = db.programDao().allLogs()
        val rules = db.programDao().allExercises()
        val archive = db.sessionDao().sessionById(session)
        db.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER reject_undo BEFORE UPDATE OF ruleJson ON program_exercise " +
                "BEGIN SELECT RAISE(ABORT, 'undo failed'); END",
        )
        assertFailsWith<SQLiteException> { repo.undoFinish(session, null) }
        assertEquals(logs, db.programDao().allLogs())
        assertEquals(rules, db.programDao().allExercises())
        assertEquals(archive, db.sessionDao().sessionById(session))
        db.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_undo")
        assertEquals(UndoResult.Undone, repo.undoFinish(session, null))
    }

}
