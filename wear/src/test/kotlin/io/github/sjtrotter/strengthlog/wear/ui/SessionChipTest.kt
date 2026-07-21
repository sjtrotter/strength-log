package io.github.sjtrotter.strengthlog.wear.ui

import io.github.sjtrotter.strengthlog.domain.sync.WatchDay
import io.github.sjtrotter.strengthlog.domain.sync.WatchExercise
import io.github.sjtrotter.strengthlog.domain.sync.WatchSet
import io.github.sjtrotter.strengthlog.domain.sync.WatchSnapshot
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The OngoingActivity chip's lifecycle, pulled out pure (redesign §1.4 / R6):
 * post while a day is in progress, clear (and reconcile away a stale chip) the
 * instant it is finished, not-yet-started, empty, or absent.
 */
class SessionChipTest {

    private fun set(done: Boolean) = WatchSet(weightLb = 100.0, reps = 5, kind = "WORK", done = done)

    private fun exercise(id: Long, doneFlags: List<Boolean>) = WatchExercise(
        programExerciseId = id,
        slot = "main",
        name = "Ex$id",
        goal = 100.0,
        perHand = false,
        supersetPartnerName = null,
        sets = doneFlags.map { set(it) },
        ssSets = emptyList(),
    )

    private fun snapshot(exercises: List<WatchExercise>) = WatchSnapshot(
        revision = 1L,
        suggestedDayId = "A",
        day = WatchDay("A", "Day", accentIndex = 0, exercises = exercises),
        unit = "lb",
    )

    @Test
    fun `inactive when the snapshot is null (still loading, reconcile-on-launch)`() {
        assertFalse(isSessionActive(null))
    }

    @Test
    fun `inactive when the day has no exercises`() {
        assertFalse(isSessionActive(snapshot(emptyList())))
    }

    @Test
    fun `inactive when nothing is done yet — the workout has not started`() {
        assertFalse(isSessionActive(snapshot(listOf(exercise(1, listOf(false, false))))))
    }

    @Test
    fun `active once the first round is ticked and work remains`() {
        assertTrue(isSessionActive(snapshot(listOf(exercise(1, listOf(true, false))))))
    }

    @Test
    fun `active when one exercise is fully done but another still has undone rounds`() {
        val snap = snapshot(listOf(exercise(1, listOf(true, true)), exercise(2, listOf(false))))
        assertTrue(isSessionActive(snap))
    }

    @Test
    fun `inactive when every round of every exercise is done — day complete, clear the chip`() {
        val snap = snapshot(listOf(exercise(1, listOf(true, true)), exercise(2, listOf(true))))
        assertFalse(isSessionActive(snap))
    }

    @Test
    fun `inactive when exercises exist but carry no rounds`() {
        assertFalse(isSessionActive(snapshot(listOf(exercise(1, emptyList())))))
    }
}
