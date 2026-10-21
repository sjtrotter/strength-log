package cloud.trotter.log.strength.wear.ui

import androidx.compose.runtime.saveable.SaverScope
import cloud.trotter.log.strength.domain.sync.WatchDay
import cloud.trotter.log.strength.domain.sync.WatchExercise
import cloud.trotter.log.strength.domain.sync.WatchSet
import cloud.trotter.log.strength.domain.sync.WatchSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TimedRepStateTest {
    private val exercise = WatchExercise(
        7L, "main", "Push-ups", 0.0, false, null,
        List(2) { WatchSet(0.0, 0, "WORK", false) }, emptyList(),
        goalLabel = "2:00 · aim 40", tracking = "reps", targetDetail = "Each side · Red band",
        repWindowSeconds = 120, repAim = 40, prescribed = true,
    )
    private val snapshot = WatchSnapshot(revision = 1L, suggestedDayId = "A",
        day = WatchDay("A", "Test", 0, listOf(exercise)), unit = "lb", planId = 9L)
    private val scope = object : SaverScope {
        override fun canBeSaved(value: Any) = true
    }

    private fun start(cardioRunning: Boolean = false) = startTimedRep(
        snapshot, 0, cardioRunning, null, 100_000L, 10_000L, 4,
    )

    private fun render(attempt: TimedRepState? = null, now: Long = 10_000L) = dialUiState(DialInputs(
        snapshot = snapshot, exerciseIndex = 0, phase = SetPhase.READY, face = DialFace.WORKOUT,
        pendingCount = 0, rest = null, nowElapsedMillis = now, timedRep = attempt,
        timedRepDeadlineMillis = 130_000L,
    ))

    private fun texts(state: DialUiState) = state.disc.lines.flatMap { it.spans }.map { it.text }

    @Test
    fun `ready tap starts draining countdown and hold cancels without logging`() {
        val ready = render()
        assertEquals(DialScreen.READY, ready.screen)
        assertEquals(DialTap.START_SET, ready.tap)
        assertTrue("2:00" in texts(ready))
        assertEquals("EACH SIDE · RED BAND", ready.bottomBand?.text)
        val attempt = assertNotNull(start())
        val running = render(attempt, 70_000L)
        assertEquals(DialScreen.TIMED_REP_RUNNING, running.screen)
        assertEquals(0.5f, running.arc)
        assertTrue("1:00" in texts(running))
        assertEquals(DialTap.NONE, running.tap)
        assertEquals(DialHoldAction.STOP_TIMED_REP, running.hold?.action)
        assertNull(buildTimedRepDelta(attempt, snapshot, 150_000L))
        val alarm = RecordingAlarm()
        var buzzes = 0
        val timer = RestTimerController(alarm, { 70_000L }, { buzzes++ })
        timer.arm(130_000L, 120)
        val queuedAlarm = assertNotNull(alarm.callback)
        // The stop hold disarms the alarm and clears the root attempt; no delta.
        timer.disarm()
        queuedAlarm()
        assertEquals(0, buzzes)
        assertEquals(DialScreen.READY, render(null).screen)
        assertFalse(snapshot.day.exercises.single().sets.first().done)
    }

    @Test
    fun `authored load shows phone text while generated ready copy is unchanged`() {
        val load = exercise.copy(repWindowSeconds = 0, goalLabel = "3 × 10 · 95 lb")
        fun ready(ex: WatchExercise): DialUiState = dialUiState(DialInputs(
            snapshot.copy(day = snapshot.day.copy(exercises = listOf(ex))),
            0, SetPhase.READY, DialFace.WORKOUT, 0, null,
        ))
        val authored = ready(load)
        assertTrue("3 × 10 · 95 lb" in texts(authored))
        assertEquals("EACH SIDE · RED BAND", authored.bottomBand?.text)
        val generated = load.copy(prescribed = false, goalLabel = "235", targetDetail = "",
            tracking = "weighted", goal = 235.0, sets = List(2) { WatchSet(235.0, 5, "TOP", false) })
        val ordinary = ready(generated)
        assertEquals(listOf("START", "235 × 5"), texts(ordinary))
        assertEquals("SET 1 OF 2", ordinary.bottomBand?.text)
        assertEquals(DialTap.START_SET, ordinary.tap)
        assertEquals(DialCrown.PEEK, ordinary.crown)
        assertEquals(ready(generated.copy(goalLabel = "")), ordinary)
    }

    @Test
    fun `expiry buzzes once then crown and tap log count and done with plan identity`() {
        var now = 10_000L
        var buzzes = 0
        var attempt = assertNotNull(start())
        val alarm = RecordingAlarm()
        val timer = RestTimerController(alarm, { now }, { buzzes++ })
        timer.arm(130_000L, 120) { attempt = attempt.timeUp() }
        val queuedAlarm = assertNotNull(alarm.callback)
        now = 130_000L
        timer.completeIfExpired()
        queuedAlarm()
        assertEquals(1, buzzes)
        assertEquals(TimedRepPhase.COUNT, attempt.phase)
        val count = render(attempt, now)
        assertTrue("0" in texts(count))
        assertTrue("/ 40" in texts(count))
        assertEquals(DialCrown.REP_COUNT, count.crown)
        assertEquals(DialTap.TICK, count.tap)
        assertNull(count.hold)
        attempt = attempt.turn(38)
        val delta = assertNotNull(buildTimedRepDelta(attempt, snapshot, 240_000L))
        assertEquals(38, delta.reps)
        assertEquals(true, delta.done)
        assertEquals(9L, delta.planId)
        assertEquals("A", delta.dayId)
        assertEquals(7L, delta.programExerciseId)
        assertEquals("main", delta.slot)
        assertEquals(0, delta.setIndex)
        assertEquals(100_000L, delta.startedAtMillis)
        assertNull(delta.seconds)
    }

    @Test
    fun `zero is explicit and crown is bounded independently of aim`() {
        val attempt = assertNotNull(start()).timeUp()
        assertEquals(0, assertNotNull(buildTimedRepDelta(attempt, snapshot, 240_000L)).reps)
        assertEquals(0, attempt.turn(-1).count)
        assertEquals(999, attempt.turn(Int.MAX_VALUE).count)
        assertEquals(0, attempt.turn(999).turn(Int.MIN_VALUE).count)
        assertEquals(41, attempt.turn(41).count)
        val noAim = snapshot.copy(day = snapshot.day.copy(exercises = listOf(exercise.copy(repAim = null))))
        val state = dialUiState(DialInputs(noAim, 0, SetPhase.READY, DialFace.WORKOUT, 0, null,
            timedRep = attempt))
        assertFalse(texts(state).any { it.startsWith("/") })
    }

    @Test
    fun `saved running attempt restores expired straight to count without arming or buzzing`() {
        val attempt = assertNotNull(start())
        val saved = with(timedRepStateSaver) { assertNotNull(scope.save(attempt)) }
        val restored = assertNotNull(timedRepStateSaver.restore(saved))
        val resumed = assertNotNull(restored.resume(snapshot, 130_000L, 220_000L, 4))
        assertEquals(TimedRepPhase.COUNT, resumed.phase)
        assertEquals(DialScreen.TIMED_REP_COUNT, render(resumed).screen)
        val alarm = RecordingAlarm()
        var buzzes = 0
        val timer = RestTimerController(alarm, { 130_000L }, { buzzes++ })
        // Root arms only RUNNING, so an expired restoration has no end boundary.
        if (resumed.phase == TimedRepPhase.RUNNING) timer.arm(130_000L, 120)
        timer.completeIfExpired()
        assertNull(alarm.callback)
        assertEquals(0, buzzes)
        val countSaved = with(timedRepStateSaver) { assertNotNull(scope.save(resumed.turn(38))) }
        assertEquals(38, assertNotNull(timedRepStateSaver.restore(countSaved)).count)
    }

    @Test
    fun `restore discards any owner mismatch or completed set`() {
        val saved = with(timedRepStateSaver) { assertNotNull(scope.save(assertNotNull(start()))) }
        val restored = assertNotNull(timedRepStateSaver.restore(saved))
        assertNull(restored.reconcile(snapshot.copy(planId = 10L)))
        assertNull(restored.reconcile(snapshot.copy(day = snapshot.day.copy(dayId = "B"))))
        for (changed in listOf(exercise.copy(programExerciseId = 8L), exercise.copy(slot = "ss"),
            exercise.copy(sets = exercise.sets.mapIndexed { i, set -> set.copy(done = i == 0) }),
            exercise.copy(repWindowSeconds = 60))) {
            assertNull(restored.reconcile(snapshot.copy(day = snapshot.day.copy(exercises = listOf(changed)))))
        }
        assertNull(restored.copy(owner = restored.owner.copy(setIndex = 1)).reconcile(snapshot))
        assertNotNull(restored.reconcile(snapshot))
    }

    @Test
    fun `cardio and another rep attempt refuse a timed start`() {
        assertNull(start(cardioRunning = true))
        assertNull(startTimedRep(snapshot, 0, false, assertNotNull(start()), 100L, 10L, 4))
    }

    @Test
    fun `reboot uses wall delta even when new uptime exceeds old anchor`() {
        val attempt = assertNotNull(start())
        assertEquals(760_000L, attempt.deadline(700_000L, 160_000L, 5))
        assertEquals(TimedRepPhase.COUNT, attempt.resume(snapshot, 700_000L, 220_000L, 5)?.phase)
    }

    private class RecordingAlarm : RestAlarmScheduler {
        var callback: (() -> Unit)? = null
        override fun schedule(deadlineMillis: Long, onAlarm: () -> Unit) { callback = onAlarm }
        override fun cancel() { callback = null }
    }
}
