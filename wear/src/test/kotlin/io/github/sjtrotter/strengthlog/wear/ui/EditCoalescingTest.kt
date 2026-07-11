package io.github.sjtrotter.strengthlog.wear.ui

import io.github.sjtrotter.strengthlog.domain.sync.SetEditDelta
import io.github.sjtrotter.strengthlog.domain.sync.WatchExercise
import io.github.sjtrotter.strengthlog.domain.sync.WatchSet
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The crown/±-button coalescing contract: a burst of edits collapses into one
 * delta per touched field carrying the final value (the fix for the crown
 * spamming one delta per detent), while the numeral still tracks live.
 */
class EditCoalescingTest {

    private fun weightDelta(index: Int, weightLb: Double, at: Long, slot: String = "main") =
        SetEditDelta(dayId = "A", programExerciseId = 1L, slot = slot, setIndex = index, weightLb = weightLb, editedAtMillis = at)

    private fun repsDelta(index: Int, reps: Int, at: Long, slot: String = "main") =
        SetEditDelta(dayId = "A", programExerciseId = 1L, slot = slot, setIndex = index, reps = reps, editedAtMillis = at)

    private fun secondsDelta(index: Int, seconds: Int, at: Long, slot: String = "main") =
        SetEditDelta(dayId = "A", programExerciseId = 1L, slot = slot, setIndex = index, seconds = seconds, editedAtMillis = at)

    private val exercise = WatchExercise(
        programExerciseId = 1L,
        slot = "main",
        name = "Barbell Back Squat",
        goal = 235.0,
        perHand = false,
        supersetPartnerName = "Rope Pushdown",
        sets = listOf(WatchSet(200.0, 5, "RAMP", done = false), WatchSet(235.0, 5, "TOP", done = false)),
        ssSets = listOf(WatchSet(50.0, 12, "WORK", done = false), WatchSet(50.0, 12, "WORK", done = false)),
    )

    @Test
    fun `a burst of weight edits to one round collapses to a single final delta`() {
        val pending = listOf(205.0, 210.0, 215.0).fold(emptyList<SetEditDelta>()) { acc, w ->
            mergePending(acc, weightDelta(index = 0, weightLb = w, at = w.toLong()))
        }
        assertEquals(1, pending.size)
        assertEquals(215.0, pending.single().weightLb)
    }

    @Test
    fun `distinct fields coexist — reps never swallows a pending weight edit to the same round`() {
        var pending = emptyList<SetEditDelta>()
        pending = mergePending(pending, weightDelta(index = 0, weightLb = 215.0, at = 1))
        pending = mergePending(pending, repsDelta(index = 0, reps = 4, at = 2))
        assertEquals(2, pending.size)
        assertEquals(215.0, pending.first { it.weightLb != null }.weightLb)
        assertEquals(4, pending.first { it.reps != null }.reps)
    }

    @Test
    fun `edits to different rounds and different tracks stay separate`() {
        var pending = emptyList<SetEditDelta>()
        pending = mergePending(pending, weightDelta(index = 0, weightLb = 215.0, at = 1))
        pending = mergePending(pending, weightDelta(index = 1, weightLb = 240.0, at = 2))
        pending = mergePending(pending, weightDelta(index = 0, weightLb = 60.0, at = 3, slot = "ss"))
        assertEquals(3, pending.size)
    }

    @Test
    fun `overlay reflects pending values live before anything is sent`() {
        val pending = listOf(
            weightDelta(index = 0, weightLb = 215.0, at = 1),
            repsDelta(index = 1, reps = 3, at = 2),
        )
        val shown = applyPendingOverlay(exercise, pending)
        assertEquals(215.0, shown.sets[0].weightLb)
        assertEquals(3, shown.sets[1].reps)
        // Untouched fields keep the snapshot value.
        assertEquals(235.0, shown.sets[1].weightLb)
    }

    @Test
    fun `overlay of no pending edits is the snapshot unchanged`() {
        assertEquals(exercise, applyPendingOverlay(exercise, emptyList()))
    }

    // --- TIMED seconds are a distinct coalescing field (§3) ---------------------

    @Test
    fun `a burst of seconds edits to one round collapses to a single final delta`() {
        val pending = listOf(50, 55, 60).fold(emptyList<SetEditDelta>()) { acc, s ->
            mergePending(acc, secondsDelta(index = 0, seconds = s, at = s.toLong()))
        }
        assertEquals(1, pending.size)
        assertEquals(60, pending.single().seconds)
    }

    @Test
    fun `a seconds edit never swallows a pending weight or reps edit to the same round`() {
        var pending = emptyList<SetEditDelta>()
        pending = mergePending(pending, weightDelta(index = 0, weightLb = 215.0, at = 1))
        pending = mergePending(pending, secondsDelta(index = 0, seconds = 60, at = 2))
        pending = mergePending(pending, repsDelta(index = 0, reps = 4, at = 3))
        assertEquals(3, pending.size) // weight, seconds, reps are three distinct field streams
        assertEquals(60, pending.first { it.seconds != null }.seconds)
    }

    @Test
    fun `the seconds overlay reflects a pending TIMED hold live before it is sent`() {
        val shown = applyPendingOverlay(exercise, listOf(secondsDelta(index = 0, seconds = 75, at = 1)))
        assertEquals(75, shown.sets[0].seconds)
    }
}
