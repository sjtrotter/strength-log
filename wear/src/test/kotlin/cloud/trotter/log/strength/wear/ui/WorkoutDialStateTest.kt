package cloud.trotter.log.strength.wear.ui

import androidx.compose.runtime.saveable.SaverScope
import cloud.trotter.log.strength.domain.sync.ExerciseSwapDelta
import cloud.trotter.log.strength.domain.sync.SetEditDelta
import cloud.trotter.log.strength.domain.sync.WatchAlternate
import cloud.trotter.log.strength.domain.sync.WatchCycleDay
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

class WorkoutDialStateTest {

    private val saveScope = object : SaverScope {
        override fun canBeSaved(value: Any) = true
    }

    private fun inProgress() = WorkoutDialState(1L, "A", null, DialFace.WORKOUT,
        lifting = true,
        startedAtWallMillis = 100_000L,
        startedAtElapsedMillis = 10_000L,
        restDeadlineMillis = 20_000L,
        restTotalSeconds = 60,
        restBetweenExercises = true,
        restedSeconds = 30,
        sessionStartedAtMillis = 90_000L,
        sessionEndedAtMillis = 95_000L,
        selectedExercise = 1,
        tickMemory = TickMemory.EMPTY.record(7L, 0, 5),
    )

    private fun save(state: WorkoutDialState): Any = with(workoutDialStateSaver(state.planId, state.dayId, state.runId)) {
        assertNotNull(saveScope.save(state))
    }

    @Test
    fun `restored in-progress state is discarded when the first snapshot belongs to another plan`() {
        val saved = save(inProgress())

        assertNull(workoutDialStateSaver(2L, "A", null).restore(saved))
    }

    @Test
    fun `restored in-progress state is discarded when the first snapshot belongs to another day`() {
        val saved = save(inProgress())

        assertNull(workoutDialStateSaver(1L, "B", null).restore(saved))
    }

    @Test
    fun `restored in-progress state is kept when the first snapshot has the same owner`() {
        val original = inProgress()
        val saved = save(original)

        val restored = assertNotNull(workoutDialStateSaver(1L, "A", null).restore(saved))

        assertEquals(original.planId, restored.planId)
        assertEquals(original.dayId, restored.dayId)
        assertEquals(DialFace.WORKOUT, restored.face)
        assertTrue(restored.lifting)
        assertEquals(original.startedAtWallMillis, restored.startedAtWallMillis)
        assertEquals(original.startedAtElapsedMillis, restored.startedAtElapsedMillis)
        assertEquals(original.restDeadlineMillis, restored.restDeadlineMillis)
        assertEquals(original.restTotalSeconds, restored.restTotalSeconds)
        assertEquals(original.restBetweenExercises, restored.restBetweenExercises)
        assertEquals(original.restedSeconds, restored.restedSeconds)
        assertEquals(original.sessionStartedAtMillis, restored.sessionStartedAtMillis)
        assertEquals(original.sessionEndedAtMillis, restored.sessionEndedAtMillis)
        assertEquals(original.selectedExercise, restored.selectedExercise)
        assertEquals(original.tickMemory.encode(), restored.tickMemory.encode())
    }

    /** [F] */
    @Test
    fun `restored dial state belongs to exactly one run`() {
        val original = WorkoutDialState(1L, "A", "r-1", DialFace.WORKOUT,
            lifting = true,
            startedAtWallMillis = 100_000L,
            restDeadlineMillis = 20_000L,
            tickMemory = TickMemory.EMPTY.record(7L, 0, 5),
        )
        val saved = save(original)
        assertNull(workoutDialStateSaver(1L, "A", "r-2").restore(saved))
        val restored = assertNotNull(workoutDialStateSaver(1L, "A", "r-1").restore(saved))
        assertEquals("r-1", restored.runId)
        assertTrue(restored.lifting)
        assertEquals(original.startedAtWallMillis, restored.startedAtWallMillis)
        assertEquals(original.restDeadlineMillis, restored.restDeadlineMillis)
        assertEquals(original.tickMemory.encode(), restored.tickMemory.encode())
    }

    private fun exercise(id: Long = 7L, sets: Int = 2) = WatchExercise(
        programExerciseId = id, slot = "main", name = "Squat", goal = 235.0, perHand = false,
        supersetPartnerName = null, sets = List(sets) { WatchSet(235.0, 5, "TOP", false, restAfterSeconds = 150) },
        ssSets = emptyList(), exerciseId = "squat",
        alternates = listOf(WatchAlternate("hack_squat", "Hack Squat"), WatchAlternate("leg_press", "Leg Press")),
    )

    private fun snapshot(vararg exercises: WatchExercise) = WatchSnapshot(
        revision = 1L, suggestedDayId = "A", day = WatchDay("A", "Lower", 0, exercises.toList()),
        unit = "lb", planId = 1L, runId = "r-1",
        cycle = listOf(WatchCycleDay("A", "Lower", emptyList()), WatchCycleDay("B", "Upper", emptyList())),
    )

    private fun ready() = WorkoutDialState(1L, "A", "r-1", DialFace.WORKOUT, swapExerciseIndex = 0)
    private val startTime = DeviceTime(100_000L, 10_000L, 4)
    private val tickTime = DeviceTime(145_000L, 55_000L, 4)

    private fun WorkoutDialState.event(
        event: WorkoutEvent,
        time: DeviceTime = startTime,
        snapshot: WatchSnapshot = snapshot(exercise()),
        timedRep: TimedRepState? = null,
        cardioRunning: Boolean = false,
    ) = reduce(event, time, WorkoutInputs(snapshot, timedRep, cardioRunning))

    @Test
    fun `start and tick preserve stamps identity memory and effect order`() {
        val original = ready()
        val started = original.event(WorkoutEvent.StartSet)
        assertEquals(original.copy(lifting = true, startedAtWallMillis = 100_000L,
            startedAtElapsedMillis = 10_000L, sessionStartedAtMillis = 100_000L), started.state)
        assertEquals(listOf(WorkoutEffect.SessionStarted(100_000L), WorkoutEffect.Haptic(DialHaptics.Cue.START)), started.effects)
        assertFalse(original.lifting)

        val ticked = started.state.event(WorkoutEvent.Tick, tickTime)
        assertEquals(listOf(
            WorkoutEffect.DisarmTimer,
            WorkoutEffect.SendEdit(SetEditDelta(dayId = "A", programExerciseId = 7L, slot = "main", setIndex = 0,
                done = true, editedAtMillis = 145_000L, startedAtMillis = 100_000L, completedAtMillis = 145_000L,
                planId = 1L, runId = "r-1", exerciseId = "squat")),
            WorkoutEffect.Haptic(DialHaptics.Cue.CONFIRM_TICK),
            WorkoutEffect.TimedRepChanged(null),
        ), ticked.effects)
        assertFalse(ticked.state.lifting)
        assertEquals(0L, ticked.state.startedAtWallMillis)
        assertEquals(0L, ticked.state.startedAtElapsedMillis)
        assertEquals(45, ticked.state.tickMemory.secondsFor(7L, 0))
        assertEquals(listOf(TickRef(7L, 0)), ticked.state.tickMemory.newestFirst())
        assertEquals(100_000L, ticked.state.sessionStartedAtMillis)
        assertEquals(145_000L, ticked.state.sessionEndedAtMillis)
        assertEquals(205_000L, ticked.state.restDeadlineMillis)
        assertEquals(150, ticked.state.restTotalSeconds)
        assertFalse(ticked.state.restBetweenExercises)
        assertEquals(NO_REST, ticked.state.restedSeconds)
        assertEquals(DialFace.WORKOUT, ticked.state.face)
    }

    @Test
    fun `tick without a start sends no invented start and still records chronology`() {
        val ticked = ready().event(WorkoutEvent.Tick, tickTime)
        val edit = ticked.effects.filterIsInstance<WorkoutEffect.SendEdit>().single().delta
        assertNull(edit.startedAtMillis)
        assertEquals(145_000L, edit.completedAtMillis)
        assertEquals(145_000L, ticked.state.sessionStartedAtMillis)
        assertEquals(145_000L, ticked.state.sessionEndedAtMillis)
        assertNull(ticked.state.tickMemory.secondsFor(7L, 0))
        assertEquals(listOf(TickRef(7L, 0)), ticked.state.tickMemory.newestFirst())
    }

    @Test
    fun `finishing an exercise rests before the next exercise and finishing the day opens overview`() {
        val first = exercise(sets = 1)
        val between = ready().event(WorkoutEvent.Tick, tickTime, snapshot(first, exercise(8L)))
        assertEquals(205_000L, between.state.restDeadlineMillis)
        assertEquals(150, between.state.restTotalSeconds)
        assertTrue(between.state.restBetweenExercises)
        assertEquals(DialFace.WORKOUT, between.state.face)

        val done = ready().event(WorkoutEvent.Tick, tickTime, snapshot(first))
        assertEquals(0L, done.state.restDeadlineMillis)
        assertEquals(0, done.state.restTotalSeconds)
        assertEquals(DialFace.OVERVIEW, done.state.face)
    }

    @Test
    fun `done or missing rounds never send a second edit or haptic`() {
        val ex = exercise(sets = 1)
        val done = snapshot(ex.copy(sets = ex.sets.map { it.copy(done = true) }))
        assertEquals(WorkoutTransition(ready()), ready().event(WorkoutEvent.Tick, tickTime, done))
        assertTrue(ready().event(WorkoutEvent.Tick, tickTime, snapshot(ex.copy(sets = emptyList()))).effects.isEmpty())
        assertTrue(ready().event(WorkoutEvent.Tick, tickTime, snapshot()).effects.isEmpty())
    }

    @Test
    fun `rest starts from elapsed time and clear and skip never earn a rested badge`() {
        val resting = ready().event(WorkoutEvent.StartRest(60, false)).state
        assertEquals(70_000L, resting.restDeadlineMillis)
        assertEquals(listOf(WorkoutEffect.ArmRest(70_000L, 60)), resting.event(WorkoutEvent.ResumeRest).effects)
        assertEquals(ready(), resting.event(WorkoutEvent.ClearRest).state)
        assertTrue(resting.event(WorkoutEvent.ClearRest).effects.isEmpty())
        val skipped = resting.event(WorkoutEvent.SkipRest)
        assertEquals(ready(), skipped.state)
        assertEquals(listOf(WorkoutEffect.DisarmTimer), skipped.effects)
        assertTrue(ready().event(WorkoutEvent.ResumeRest).effects.isEmpty())
    }

    @Test
    fun `rest expiry uses the saved deadline after a screen-off jump and ignores stale completions`() {
        val resting = ready().event(WorkoutEvent.StartRest(60, false)).state
        val expired = WorkoutEvent.RestExpired(70_000L)
        assertEquals(resting, resting.event(expired, DeviceTime(159_999L, 69_999L, 4)).state)
        val wake = DeviceTime(600_000L, 510_000L, 4)
        val finished = resting.event(expired, wake)
        assertEquals(ready().copy(restedSeconds = 60), finished.state)
        assertTrue(finished.effects.isEmpty())
        assertTrue(finished.state.event(expired, wake).effects.isEmpty())
        val newer = resting.event(WorkoutEvent.StartRest(90, false), tickTime).state
        assertEquals(newer, newer.event(expired, wake).state)
        val between = resting.copy(restBetweenExercises = true).event(expired, wake)
        assertEquals(ready(), between.state)
    }

    @Test
    fun `rest alarm stays suppressed while another execution owns the timer`() {
        val resting = ready().event(WorkoutEvent.StartRest(60, false)).state
        assertTrue(resting.event(WorkoutEvent.ResumeRest, cardioRunning = true).effects.isEmpty())
        val timed = exercise().copy(repWindowSeconds = 120)
        val snap = snapshot(timed)
        val attempt = assertNotNull(startTimedRep(snap, 0, false, null, 100_000L, 10_000L, 4))
        assertTrue(resting.event(WorkoutEvent.ResumeRest, snapshot = snap, timedRep = attempt).effects.isEmpty())
    }

    @Test
    fun `Undo immediately or long after the tick reopens the target without a time limit`() {
        val first = exercise(sets = 1)
        val logged = snapshot(first.copy(sets = first.sets.map { it.copy(done = true) }), exercise(8L))
        val memory = TickMemory.EMPTY.record(7L, 0, 45)
        val resting = ready().copy(restDeadlineMillis = 205_000L, restTotalSeconds = 150,
            restBetweenExercises = true, selectedExercise = 1, tickMemory = memory)
        for (time in listOf(DeviceTime(146_000L, 56_000L, 4), DeviceTime(3_745_000L, 3_655_000L, 4))) {
            val undone = resting.event(WorkoutEvent.Undo(UndoTarget(0, 0)), time, logged)
            assertEquals(listOf(
                WorkoutEffect.DisarmTimer,
                WorkoutEffect.SendEdit(SetEditDelta(dayId = "A", programExerciseId = 7L, slot = "main", setIndex = 0,
                    done = false, editedAtMillis = time.wallMillis, planId = 1L, runId = "r-1", exerciseId = "squat")),
                WorkoutEffect.Haptic(DialHaptics.Cue.UNDO),
            ), undone.effects)
            assertEquals(0, undone.state.selectedExercise)
            assertEquals(0L, undone.state.restDeadlineMillis)
            assertEquals(0, undone.state.restTotalSeconds)
            assertFalse(undone.state.restBetweenExercises)
            assertFalse(undone.state.lifting)
            assertEquals(NO_REST, undone.state.restedSeconds)
            assertTrue(undone.state.tickMemory.newestFirst().isEmpty())
        }
    }

    @Test
    fun `swap confirms the displayed alternate after a prescription shrinks and forgets only that lift`() {
        val memory = TickMemory.EMPTY.record(7L, 0, 45).record(8L, 0, 20)
        val preview = ready().copy(tickMemory = memory).event(WorkoutEvent.TurnCrown(DialCrown.SELECT_ALTERNATE, 2))
        assertEquals(SwapPreview(1, 10_000L), preview.state.swapPreview)
        assertEquals(List(2) { WorkoutEffect.Haptic(DialHaptics.Cue.ROTARY_DETENT) }, preview.effects)
        val ex = exercise()
        val shrunk = snapshot(ex.copy(alternates = ex.alternates.take(1)))
        val swapped = preview.state.event(WorkoutEvent.ConfirmSwap, tickTime, shrunk)
        assertEquals(listOf(
            WorkoutEffect.SendSwap(ExerciseSwapDelta(dayId = "A", programExerciseId = 7L,
                exerciseId = "hack_squat", exerciseName = "Hack Squat", editedAtMillis = 145_000L,
                planId = 1L, runId = "r-1")),
            WorkoutEffect.Haptic(DialHaptics.Cue.CONFIRM_TICK),
        ), swapped.effects)
        assertNull(swapped.state.swapPreview)
        assertEquals(listOf(TickRef(8L, 0)), swapped.state.tickMemory.newestFirst())
    }

    @Test
    fun `crown selection peek and timeout retain their boundaries and haptics`() {
        val snap = snapshot(exercise(), exercise(8L))
        val selected = ready().event(WorkoutEvent.TurnCrown(DialCrown.SELECT_EXERCISE, 3), snapshot = snap)
        assertEquals(1, selected.state.selectedExercise)
        assertEquals(listOf(WorkoutEffect.Haptic(DialHaptics.Cue.ROTARY_DETENT),
            WorkoutEffect.Haptic(DialHaptics.Cue.BOUNDARY)), selected.effects)
        val peeked = ready().event(WorkoutEvent.TurnCrown(DialCrown.PEEK, 1))
        assertEquals(PeekState(1, 10_000L), peeked.state.peek)
        assertEquals(peeked.state, peeked.state.event(WorkoutEvent.PeekTimeout,
            DeviceTime(101_499L, 11_499L, 4)).state)
        assertNull(peeked.state.event(WorkoutEvent.PeekTimeout, DeviceTime(101_500L, 11_500L, 4)).state.peek)
    }

    @Test
    fun `swap preview expires or disappears when leaving its exercise`() {
        val snap = snapshot(exercise(), exercise(8L))
        val preview = ready().event(WorkoutEvent.TurnCrown(DialCrown.SELECT_ALTERNATE, 1), snapshot = snap).state
        assertEquals(preview, preview.event(WorkoutEvent.SwapTimeout, DeviceTime(103_999L, 13_999L, 4), snap).state)
        assertNull(preview.event(WorkoutEvent.SwapTimeout, DeviceTime(104_000L, 14_000L, 4), snap).state.swapPreview)
        val moved = preview.event(WorkoutEvent.TurnCrown(DialCrown.SELECT_EXERCISE, 1), snapshot = snap).state
        assertNull(moved.swapPreviewFor(1))
        val reconciled = moved.event(WorkoutEvent.ExerciseChanged, snapshot = snap).state
        assertNull(reconciled.swapPreview)
        assertEquals(1, reconciled.swapExerciseIndex)
    }

    @Test
    fun `selection reconciliation waits for pending edits and swipe and browse use the existing order`() {
        val first = exercise()
        val second = exercise(8L)
        val snap = snapshot(first, second)
        val selected = ready().event(WorkoutEvent.Swipe(DialSwipe.NEXT_EXERCISE), snapshot = snap).state
        assertEquals(1, selected.selectedExercise)
        val logged = snapshot(first, second.copy(sets = second.sets.map { it.copy(done = true) }))
        assertEquals(1, selected.event(WorkoutEvent.ReconcileSelection(setOf(8L)), snapshot = logged).state.selectedExercise)
        assertEquals(-1, selected.event(WorkoutEvent.ReconcileSelection(emptySet()), snapshot = logged).state.selectedExercise)
        val browsed = ready().event(WorkoutEvent.Swipe(DialSwipe.BROWSE_DAY), snapshot = snap).state
        assertEquals(1, browsed.browseDay)
        assertNull(browsed.event(WorkoutEvent.Swipe(DialSwipe.BROWSE_DAY), snapshot = snap).state.browseDay)
    }

    @Test
    fun `timed reps start at the supplied boot and count sends the exact timed delta`() {
        val snap = snapshot(exercise().copy(repWindowSeconds = 120, tracking = "reps"))
        val started = ready().copy(restDeadlineMillis = 20_000L, restTotalSeconds = 10).event(WorkoutEvent.StartSet, snapshot = snap)
        val attempt = TimedRepState(TimedRepOwner(1L, "A", 7L, "squat", "main", 0, "r-1"),
            120, 100_000L, 10_000L, 4)
        assertEquals(listOf(WorkoutEffect.TimedRepChanged(attempt), WorkoutEffect.SessionStarted(100_000L),
            WorkoutEffect.Haptic(DialHaptics.Cue.START)), started.effects)
        assertEquals(0L, started.state.restDeadlineMillis)
        assertFalse(started.state.lifting)
        val counted = started.state.event(WorkoutEvent.TurnCrown(DialCrown.REP_COUNT, 38), snapshot = snap,
            timedRep = attempt.timeUp())
        assertEquals(WorkoutEffect.TimedRepChanged(attempt.timeUp().copy(count = 38)), counted.effects.first())
        assertEquals(List(38) { WorkoutEffect.Haptic(DialHaptics.Cue.ROTARY_DETENT) }, counted.effects.drop(1))
        val end = DeviceTime(240_000L, 150_000L, 4)
        val ticked = started.state.event(WorkoutEvent.Tick, end, snap, attempt.timeUp().copy(count = 38))
        assertEquals(listOf(
            WorkoutEffect.DisarmTimer,
            WorkoutEffect.SendEdit(SetEditDelta(dayId = "A", programExerciseId = 7L, slot = "main", setIndex = 0,
                reps = 38, done = true, editedAtMillis = 240_000L, startedAtMillis = 100_000L,
                completedAtMillis = 240_000L, planId = 1L, runId = "r-1", exerciseId = "squat")),
            WorkoutEffect.Haptic(DialHaptics.Cue.CONFIRM_TICK), WorkoutEffect.TimedRepChanged(null),
        ), ticked.effects)
        assertEquals(140, ticked.state.tickMemory.secondsFor(7L, 0))
        assertEquals(300_000L, ticked.state.restDeadlineMillis)
    }

    @Test
    fun `running timed reps refuse ticks and stale or expired stop holds`() {
        val snap = snapshot(exercise().copy(repWindowSeconds = 120))
        val attempt = assertNotNull(startTimedRep(snap, 0, false, null, 100_000L, 10_000L, 4))
        val runningTick = ready().event(WorkoutEvent.Tick, tickTime, snap, attempt)
        assertEquals(listOf(WorkoutEffect.DisarmTimer), runningTick.effects)
        assertEquals(ready(), runningTick.state)
        val stopped = ready().event(WorkoutEvent.StopTimedRep(attempt), tickTime, snap, attempt)
        assertEquals(listOf(WorkoutEffect.DisarmTimer, WorkoutEffect.TimedRepChanged(null),
            WorkoutEffect.Haptic(DialHaptics.Cue.START)), stopped.effects)
        for (pressed in listOf(null, attempt.copy(startedAtElapsedMillis = 20_000L))) {
            assertTrue(ready().event(WorkoutEvent.StopTimedRep(pressed), tickTime, snap, attempt).effects.isEmpty())
        }
        val expired = DeviceTime(220_000L, 130_000L, 4)
        assertTrue(ready().event(WorkoutEvent.StopTimedRep(attempt), expired, snap, attempt).effects.isEmpty())
        assertTrue(ready().event(WorkoutEvent.StopTimedRep(attempt), expired, snap, attempt.timeUp()).effects.isEmpty())
    }

    @Test
    fun `cardio and an existing rep attempt block starts without haptics`() {
        for (snap in listOf(snapshot(exercise()), snapshot(exercise().copy(repWindowSeconds = 120)))) {
            assertTrue(ready().event(WorkoutEvent.StartSet, snapshot = snap, cardioRunning = true).effects.isEmpty())
            assertFalse(ready().event(WorkoutEvent.StartSet, snapshot = snap, cardioRunning = true).state.lifting)
        }
        val snap = snapshot(exercise().copy(repWindowSeconds = 120))
        val attempt = assertNotNull(startTimedRep(snap, 0, false, null, 100_000L, 10_000L, 4))
        assertTrue(ready().event(WorkoutEvent.StartSet, snapshot = snap, timedRep = attempt).effects.isEmpty())
    }

    @Test
    fun `timed hold alarm keeps the start deadline and a delayed tick keeps actual wall stamps`() {
        val ex = exercise().copy(tracking = "timed", sets = exercise().sets.map { it.copy(seconds = 45) })
        val snap = snapshot(ex)
        val started = ready().event(WorkoutEvent.StartSet, snapshot = snap).state
        val wake = DeviceTime(700_000L, 610_000L, 4)
        assertEquals(listOf(WorkoutEffect.ArmTimedHold(55_000L, 45)),
            started.event(WorkoutEvent.ResumeTimedHold, wake, snap).effects)
        val ticked = started.event(WorkoutEvent.Tick, wake, snap)
        val edit = ticked.effects.filterIsInstance<WorkoutEffect.SendEdit>().single().delta
        assertEquals(100_000L, edit.startedAtMillis)
        assertEquals(700_000L, edit.completedAtMillis)
        assertEquals(600, ticked.state.tickMemory.secondsFor(7L, 0))
        assertEquals(760_000L, ticked.state.restDeadlineMillis)
    }

    @Test
    fun `faces and legacy cardio handoffs emit no extra edits`() {
        val overview = ready().event(WorkoutEvent.ShowFace(DialFace.OVERVIEW)).state
        val start = overview.event(WorkoutEvent.StartCardio)
        assertEquals(DialFace.WORKOUT, start.state.face)
        assertEquals(listOf(WorkoutEffect.StartCardio), start.effects)
        assertEquals(listOf(WorkoutEffect.StopCardio), start.state.event(WorkoutEvent.StopCardio).effects)
        assertEquals(ready(), overview.event(WorkoutEvent.ShowFace(DialFace.WORKOUT)).state)
    }

    private fun WorkoutDialState.render(snapshot: WatchSnapshot, time: DeviceTime) = dialUiState(DialInputs(
        snapshot = snapshot,
        exerciseIndex = exerciseIndex(WorkoutInputs(snapshot)),
        phase = if (lifting) SetPhase.LIFTING else SetPhase.READY,
        face = face,
        pendingCount = 0,
        rest = restDeadlineMillis.takeIf { it > 0L }?.let { RestState(it, restTotalSeconds, restBetweenExercises) },
        restedSeconds = restedSeconds.takeIf { it != NO_REST },
        liftingElapsedMillis = if (lifting) (time.elapsedMillis - startedAtElapsedMillis).coerceAtLeast(0L) else 0L,
        nowElapsedMillis = time.elapsedMillis,
        nowWallMillis = time.wallMillis,
        session = SessionStamps(sessionStartedAtMillis, sessionEndedAtMillis),
        tickMemory = tickMemory,
    ))

    @Test
    fun `reducer transitions keep the ready lifting rest and rested dial faces`() {
        val ex = exercise()
        val snap = snapshot(ex)
        assertEquals(DialScreen.READY, ready().render(snap, startTime).screen)
        val started = ready().event(WorkoutEvent.StartSet, snapshot = snap).state
        assertEquals(DialScreen.LIFTING, started.render(snap, startTime).screen)
        val ticked = started.event(WorkoutEvent.Tick, tickTime, snap).state
        val echoed = snapshot(ex.copy(sets = ex.sets.mapIndexed { index, set -> set.copy(done = index == 0) }))
        assertEquals(DialScreen.REST, ticked.render(echoed, tickTime).screen)
        val wake = DeviceTime(400_000L, 310_000L, 4)
        val rested = ticked.event(WorkoutEvent.RestExpired(205_000L), wake, echoed).state
        assertEquals(DialScreen.REST_OVER, rested.render(echoed, wake).screen)
        val skipped = ticked.event(WorkoutEvent.SkipRest, tickTime, echoed).state
        assertEquals(DialScreen.READY, skipped.render(echoed, tickTime).screen)
        val restarted = rested.event(WorkoutEvent.StartSet, wake, echoed)
        assertEquals(100_000L, restarted.state.sessionStartedAtMillis)
        assertEquals(listOf(WorkoutEffect.Haptic(DialHaptics.Cue.START)), restarted.effects)
        assertEquals(DialScreen.LIFTING, restarted.state.render(echoed, wake).screen)
    }

    @Test
    fun `wall-clock rollback preserves observed stamps while rest stays on elapsed time`() {
        val started = ready().event(WorkoutEvent.StartSet).state
        val ticked = started.event(WorkoutEvent.Tick, DeviceTime(90_000L, 55_000L, 4))
        val edit = ticked.effects.filterIsInstance<WorkoutEffect.SendEdit>().single().delta
        assertEquals(100_000L, edit.startedAtMillis)
        assertEquals(90_000L, edit.completedAtMillis)
        assertEquals(90_000L, edit.editedAtMillis)
        assertEquals(205_000L, ticked.state.restDeadlineMillis)
        assertNull(ticked.state.tickMemory.secondsFor(7L, 0))
        assertEquals(listOf(TickRef(7L, 0)), ticked.state.tickMemory.newestFirst())
    }

    @Test
    fun `accessibility rep edits stay silent and cardio crown feedback follows actual movement`() {
        val snap = snapshot(exercise().copy(repWindowSeconds = 120))
        val attempt = assertNotNull(startTimedRep(snap, 0, false, null, 100_000L, 10_000L, 4)).timeUp()
        val counted = ready().event(WorkoutEvent.ChangeRepCount(1), snapshot = snap, timedRep = attempt)
        assertEquals(listOf(WorkoutEffect.TimedRepChanged(attempt.copy(count = 1))), counted.effects)
        val bounded = ready().event(WorkoutEvent.TurnCrown(DialCrown.REP_COUNT, -1), snapshot = snap, timedRep = attempt)
        assertEquals(listOf(WorkoutEffect.TimedRepChanged(attempt), WorkoutEffect.Haptic(DialHaptics.Cue.BOUNDARY)),
            bounded.effects)
        val cardio = ready().event(WorkoutEvent.TurnCrown(DialCrown.CARDIO_SET, 3, cardioMovement = 1))
        assertEquals(listOf(WorkoutEffect.Haptic(DialHaptics.Cue.ROTARY_DETENT),
            WorkoutEffect.Haptic(DialHaptics.Cue.BOUNDARY)), cardio.effects)
    }

    @Test
    fun `legacy saved map restores and new saves retain exactly the old keys and values`() {
        val legacy = listOf<Any?>(
            "planId", 1L, "dayId", "A", "runId", null, "face", "WORKOUT", "lifting", true,
            "startedAtWallMillis", 100_000L, "startedAtElapsedMillis", 10_000L,
            "restDeadlineMillis", 20_000L, "restTotalSeconds", 60, "restBetweenExercises", true,
            "restedSeconds", 30, "sessionStartedAtMillis", 90_000L, "sessionEndedAtMillis", 95_000L,
            "selectedExercise", 1, "tickMemory", "7:0=5@1",
        )
        val restored = assertNotNull(workoutDialStateSaver(1L, "A", null).restore(legacy))
        assertEquals(legacy, save(restored))
        assertEquals(legacy, save(inProgress()))
        val holderSaver = workoutDialStateHolderSaver(1L, "A", null)
        val holder = assertNotNull(holderSaver.restore(legacy))
        assertEquals(100_000L, holder.value.startedAtWallMillis)
        holder.value = holder.value.copy(peek = PeekState(2, 10_000L))
        assertEquals(legacy, with(holderSaver) { saveScope.save(holder) })
        holder.value = holder.value.event(WorkoutEvent.SkipRest).state
        val updated = assertNotNull(with(holderSaver) { saveScope.save(holder) })
        val updatedState = assertNotNull(holderSaver.restore(updated)).value
        assertEquals(0L, updatedState.restDeadlineMillis)
        assertEquals(0, updatedState.restTotalSeconds)
        assertEquals(NO_REST, updatedState.restedSeconds)
        assertNull(updatedState.peek)
        assertNull(workoutDialStateHolderSaver(2L, "A", null).restore(legacy))
        val glanced = inProgress().copy(peek = PeekState(2, 10_000L), browseDay = 1,
            swapPreview = SwapPreview(0, 10_000L), swapExerciseIndex = 1)
        assertEquals(legacy, save(glanced))
        assertNull(restored.peek)
        assertNull(restored.browseDay)
        assertNull(restored.swapPreview)
        assertNull(restored.swapExerciseIndex)
    }
}
