package cloud.trotter.log.strength.data

import cloud.trotter.log.strength.data.db.entity.ProgramDayEntity
import cloud.trotter.log.strength.data.db.entity.ProgramExerciseEntity
import cloud.trotter.log.strength.data.db.entity.Slot
import cloud.trotter.log.strength.data.run.LegacyLiveLogSplit
import cloud.trotter.log.strength.data.run.LegacyLog
import cloud.trotter.log.strength.data.run.SplitInput
import cloud.trotter.log.strength.data.serialization.SetJson
import cloud.trotter.log.strength.domain.library.ExerciseLibrary
import cloud.trotter.log.strength.domain.model.LoggedSet
import cloud.trotter.log.strength.domain.model.SetKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LegacyLiveLogSplitTest {
    private fun input() = SplitInput(
        logs = listOf(
            LegacyLog(1, "A", 1, Slot.MAIN, listOf(LoggedSet(235.0, 5, SetKind.TOP, done = true,
                startedAtMillis = 4_000, completedAtMillis = 5_000)), "2026-10-19", 6_000),
            LegacyLog(1, "A", 2, Slot.MAIN, listOf(LoggedSet(195.0, 5, SetKind.WORK, done = true)), "", 6_000),
            LegacyLog(1, "B", 3, Slot.MAIN, listOf(LoggedSet(95.0, 10, SetKind.WORK, done = true,
                completedAtMillis = 3_000)), "2026-10-19", 3_500),
        ),
        slots = listOf(slot(1, "A", 0, "bb_back_squat"), slot(2, "A", 1, "bb_bench"), slot(3, "B", 0, "bb_row")),
        days = listOf(day("A", "id-A"), day("B", "id-B")), planNames = mapOf(1L to "My plan"),
        exerciseName = { ExerciseLibrary.find(it)?.name ?: it }, today = "2026-10-19", now = 9_000,
        openRun = null, mint = { "m-1" },
    )

    /** [F] */
    @Test fun `latest day becomes attached run and other day is archived and closed`() {
        val result = LegacyLiveLogSplit.split(input())
        assertEquals("id-A", result.run!!.id)
        assertEquals(4_000L, result.run!!.startedAt)
        assertEquals(listOf(0, 1), result.tracks.map { it.position })
        val main = SetJson.decodeSets(result.tracks[0].setsJson).single()
        assertTrue(main.done)
        assertEquals(0, main.plannedIndex)
        assertFalse(SetJson.decodeSets(result.tracks[0].plannedJson).single().done)
        assertFalse(SetJson.decodeSets(result.tracks[1].setsJson).single().done)
        val archive = result.archived.single()
        assertEquals("Day B", archive.session.dayTitle)
        assertEquals("My plan", archive.session.planName)
        assertEquals(3_000L, archive.session.completedAt)
        assertNull(archive.session.runId)
        assertNull(archive.session.bodyweightLb)
        assertEquals("Barbell Row", archive.sets.single().exerciseName)
        assertEquals(95.0, archive.sets.single().weightLb, 0.0)
        assertEquals(10, archive.sets.single().reps)
        assertTrue(archive.sets.single().done)
        assertEquals("m-1", result.closes.single().nextRunId)
        assertEquals(9_000L, result.closes.single().closedAt)
        result.logs.flatMap { SetJson.decodeSets(it.setsJson) }.forEach {
            assertFalse(it.done)
            assertNull(it.startedAtMillis)
            assertNull(it.completedAtMillis)
        }
    }

    /** [F] */
    @Test fun `an existing run archives both candidate days`() {
        val result = LegacyLiveLogSplit.split(input().copy(openRun = 1L to "C"))
        assertNull(result.run)
        assertTrue(result.tracks.isEmpty())
        assertEquals(listOf("A", "B"), result.archived.map { it.session.dayId })
        assertEquals(2, result.closes.size)
    }

    /** [F] */
    @Test fun `an existing run keeps its own day attached and only clears its legacy rows`() {
        val result = LegacyLiveLogSplit.split(input().copy(openRun = 1L to "A"))
        assertNull(result.run)
        assertEquals(listOf("B"), result.archived.map { it.session.dayId })
        assertEquals(listOf("B"), result.closes.map { it.dayId })
        assertTrue(result.logs.filter { it.dayId == "A" }.flatMap { SetJson.decodeSets(it.setsJson) }.none { it.done })
    }

    /** [F] */
    @Test fun `timestamp fallback uses only normalized done rows for runs and archives`() {
        val source = input()
        val logs = source.logs.map { log ->
            if (log.programExerciseId == 2L) log.copy(updatedAt = 6_000 - 10 * 86_400_000L)
            else log.copy(sets = log.sets.map { it.copy(startedAtMillis = null, completedAtMillis = null) })
        }
        val result = LegacyLiveLogSplit.split(source.copy(logs = logs))
        assertEquals(6_000L, result.run!!.startedAt)
        val archived = LegacyLiveLogSplit.split(source.copy(logs = logs, openRun = 1L to "C"))
            .archived.first { it.session.dayId == "A" }.session
        assertEquals(6_000L, archived.startedAt)
        assertEquals(6_000L, archived.completedAt)
        val withNewerUntouchedRow = logs.map { if (it.programExerciseId == 2L) it.copy(updatedAt = 20_000) else it }
        val newer = LegacyLiveLogSplit.split(source.copy(logs = withNewerUntouchedRow, openRun = 1L to "B"))
            .archived.single().session
        assertEquals(6_000L, newer.startedAt)
        assertEquals(6_000L, newer.completedAt)
    }

    /** [F] */
    @Test fun `a phone tick without timing outranks an earlier watch completion`() {
        val source = input()
        val logs = source.logs.map { log ->
            if (log.dayId == "B") log.copy(updatedAt = 7_000,
                sets = log.sets.map { it.copy(completedAtMillis = null) }) else log
        }
        assertEquals("id-B", LegacyLiveLogSplit.split(source.copy(logs = logs)).run!!.id)
    }

    /** [F] */
    @Test fun `yesterday's ticks become working values only`() {
        val source = input()
        val result = LegacyLiveLogSplit.split(source.copy(logs = source.logs.map { it.copy(checkDate = "2026-10-18") }))
        assertNull(result.run)
        assertTrue(result.archived.isEmpty())
        assertTrue(result.closes.isEmpty())
        assertTrue(result.logs.flatMap { SetJson.decodeSets(it.setsJson) }.none { it.done })
    }

    /** [F] */
    @Test fun `equal completion timestamps are broken by updatedAt`() {
        val source = input()
        val logs = source.logs.map { log -> if (log.dayId == "B") log.copy(updatedAt = 7_000,
            sets = log.sets.map { it.copy(completedAtMillis = 5_000) }) else log }
        assertEquals("id-B", LegacyLiveLogSplit.split(source.copy(logs = logs)).run!!.id)
    }

    /** [F] */
    @Test fun `missing days cannot become candidates and invalid SS tracks are skipped`() {
        val source = input()
        val result = LegacyLiveLogSplit.split(source.copy(days = source.days.take(1),
            logs = source.logs + source.logs.first().copy(slot = Slot.SS)))
        assertEquals("id-A", result.run!!.id)
        assertEquals(2, result.tracks.size)
        assertTrue(result.archived.isEmpty())
    }

    private fun slot(id: Long, day: String, position: Int, exercise: String) =
        ProgramExerciseEntity(id, 1, day, position, exercise, true, 1, "5", false, null, "")
    private fun day(id: String, run: String) = ProgramDayEntity(1, id, 0, "Day $id", "", null, nextRunId = run)
}
