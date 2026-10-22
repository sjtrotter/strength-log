package cloud.trotter.log.strength.wear.ui

import androidx.compose.runtime.saveable.SaverScope
import cloud.trotter.log.strength.domain.generator.CardioBlocks
import cloud.trotter.log.strength.domain.model.CardioActivity
import cloud.trotter.log.strength.domain.model.CardioSuggestion
import cloud.trotter.log.strength.domain.sync.WatchCardioBlock
import cloud.trotter.log.strength.domain.sync.WatchCardioSetSpec
import cloud.trotter.log.strength.domain.sync.WatchDay
import cloud.trotter.log.strength.domain.sync.WatchExercise
import cloud.trotter.log.strength.domain.sync.WatchSet
import cloud.trotter.log.strength.domain.sync.WatchSnapshot
import cloud.trotter.log.strength.domain.sync.toWatch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CardioSetStateTest {
    private val time = WatchCardioSetSpec("WORK", "TIME", 120)
    private val distance = WatchCardioSetSpec("WORK", "DISTANCE", 400, "TIME", 105, hard = true)
    private val recovery = WatchCardioSetSpec("RECOVERY", "TIME", 60)
    private fun block(vararg sets: WatchCardioSetSpec) = WatchCardioBlock("TRACK_RUN", sets.toList())
    private fun snapshot(block: WatchCardioBlock) = WatchSnapshot(revision = 1, suggestedDayId = "A",
        day = WatchDay("A", "Track", 0, emptyList()), unit = "lb", planId = 9, cardioBlock = block)
    private fun run(vararg sets: WatchCardioSetSpec) = CardioSetState(9, "A", block(*sets))
    private fun render(run: CardioSetState, elapsed: Long = 10_000, wall: Long = 100_000) = dialUiState(
        DialInputs(snapshot(run.block), 0, SetPhase.READY, DialFace.WORKOUT, 0, null,
            nowElapsedMillis = elapsed, nowWallMillis = wall, cardioSets = run, bootCount = 4))
    private fun texts(ui: DialUiState) = ui.disc.lines.flatMap { it.spans }.map { it.text }

    @Test fun `legacy eleven sets wait for work starts and auto-start each recovery including the last`() {
        val legacy = CardioBlocks.fromLegacy(CardioSuggestion("Intervals", "", true), true, CardioActivity.TRACK_RUN).toWatch()
        var state = CardioSetState(9, "A", legacy)
        assertEquals(11, state.block.sets.size)
        assertNull(state.active)
        var wall = 100_000L
        var elapsed = 10_000L
        state = state.start(wall, elapsed, 4)
        wall += 300_000; elapsed += 300_000
        state = state.resume(wall, elapsed, 4)
        assertEquals(1, state.position)
        assertNull(state.active)
        repeat(5) { index ->
            // Waiting between work sets never contributes to elapsed duration.
            wall += 5_000; elapsed += 5_000
            state = state.start(wall, elapsed, 4)
            assertEquals(state, state.tap(wall + 1_000, elapsed + 1_000, 4))
            assertEquals(state, state.turn(3))
            wall += 120_000; elapsed += 120_000
            state = state.resume(wall, elapsed, 4)
            assertEquals("RECOVERY", state.block.sets[state.position].role)
            assertNotNull(state.active)
            wall += 120_000; elapsed += 120_000
            state = state.resume(wall, elapsed, 4)
            if (index < 4) assertNull(state.active)
        }
        assertTrue(state.complete)
        assertNull(state.active)
        val delta = assertNotNull(state.delta())
        assertEquals(1_500, delta.seconds)
        assertEquals(11, delta.stepsCompleted)
        assertEquals(9L, delta.planId)
        assertEquals("TRACK_RUN", delta.activity)
        assertEquals(wall, delta.completedAt)
        assertEquals(state, state.resume(wall + 900_000, elapsed + 900_000, 4))
    }

    @Test fun `distance has a static target no arc and Reached records manual metres regardless of aim`() {
        val ready = run(distance, recovery)
        assertTrue("Target" in texts(render(ready)))
        var state = ready.start(100_000, 10_000, 4)
        val ui = render(state, elapsed = 137_000, wall = 227_000)
        assertNull(ui.arc)
        assertTrue("400 m" in texts(ui))
        assertTrue("Reached 400 m" in texts(ui))
        assertTrue("Elapsed 2:07" in texts(ui))
        assertEquals(DialCrown.NONE, ui.crown)
        state = state.tap(227_000, 137_000, 4)
        assertEquals("FINISHED", state.results.single().status)
        assertEquals(127, state.results.single().elapsedSeconds)
        assertEquals(400, state.results.single().metres)
        assertEquals("MANUAL", state.results.single().distanceSource)
        assertNotNull(state.active)
    }

    @Test fun `hold stop shortens immediately and blank Done keeps unknown distance then next set waits`() {
        var state = run(time, recovery).start(100_000, 10_000, 4)
        val ui = render(state)
        assertEquals(DialTap.NONE, ui.tap)
        assertEquals(DialHoldAction.STOP_CARDIO_SET, ui.hold?.action)
        state = state.stop(101_500, 11_500, 4)
        assertEquals("SHORTENED", state.results.single().status)
        assertEquals(1, state.results.single().elapsedSeconds)
        assertNull(state.active)
        assertTrue("Distance (m)" in texts(render(state)))
        assertEquals("Set stopped", render(state).topBand?.text)
        state = state.tap(105_000, 15_000, 4)
        assertNull(state.results.single().metres)
        assertNull(state.results.single().distanceSource)
        assertTrue(state.entries.isEmpty())
        assertNull(state.active)
        assertEquals(DialHoldAction.CARDIO_SET_OPTIONS, render(state).hold?.action)
    }

    @Test fun `timed distance entry runs recovery behind it and final entry precedes one delta`() {
        val aimed = time.copy(endValue = 2, aimKind = "DISTANCE", aimValue = 20)
        var state = run(aimed, recovery).start(100_000, 10_000, 4).resume(102_000, 12_000, 4)
        assertEquals("Time’s up", render(state).topBand?.text)
        assertNotNull(state.active)
        state = state.turn(25).tap(103_000, 13_000, 4)
        assertEquals(25, state.results.single().metres)
        assertEquals(102_000L, state.active?.startedAtWallMillis)
        state = state.resume(162_000, 72_000, 4)
        assertEquals(62, state.delta()?.seconds)
        var final = run(aimed).start(100_000, 10_000, 4).resume(102_000, 12_000, 4)
        assertTrue(final.complete)
        assertNull(final.delta())
        final = final.turn(19).tap(110_000, 20_000, 4)
        assertEquals(102_000L, final.delta()?.completedAt)
        assertEquals(19, final.delta()?.sets?.single()?.metres)
        assertEquals(2, final.delta()?.seconds)
    }

    @Test fun `Ready options skip a set and require confirmation to end without advancing untouched blocks`() {
        var state = run(time, recovery).openOptions()
        assertEquals("Set options", render(state).topBand?.text)
        assertEquals(listOf("Skip set"), texts(render(state)))
        state = state.tap(100_000, 10_000, 4)
        assertEquals("SKIPPED", state.results.single().status)
        assertEquals(0, state.results.single().elapsedSeconds)
        assertNull(state.active)
        assertEquals(RoundState.SKIPPED, render(state).rounds[0])
        state = state.openOptions().turn(1).tap(100_000, 10_000, 4)
        assertTrue(state.confirmingEnd)
        assertEquals("End block?", render(state).topBand?.text)
        assertTrue("Log completed work and skip remaining sets?" in texts(render(state)))
        val kept = state.tap(100_000, 10_000, 4)
        assertFalse(kept.confirmingEnd)
        state = state.turn(1).tap(100_000, 10_000, 4)
        assertTrue(state.complete)
        assertNull(state.delta())
        assertFalse(state.ownsExecution)
        val back = run(time).openOptions().turn(2).tap(100_000, 10_000, 4)
        assertFalse(back.options)
        assertEquals(0, back.position)
    }

    @Test fun `short intentional effort logs while End shortens active work and skips untouched sets`() {
        val ended = run(time, recovery).start(100_000, 10_000, 4).end(100_100, 10_100, 4)
        assertTrue(ended.complete)
        assertEquals(listOf("SHORTENED", "SKIPPED"), ended.results.map { it.status })
        assertNull(ended.delta())
        val entered = ended.tap(101_000, 11_000, 4)
        assertEquals(0, assertNotNull(entered.delta()).seconds)
        assertEquals(RoundState.SHORTENED, render(entered).rounds[0])
    }

    @Test fun `late restore ends at zero using monotonic time on same boot and wall time across boots`() {
        val state = run(time).start(100_000, 10_000, 4)
        val sameBoot = state.resume(9_000_000, 150_000, 4)
        assertEquals(120, sameBoot.delta()?.seconds)
        assertEquals(220_000L, sameBoot.delta()?.completedAt)
        val reboot = state.resume(230_000, 500_000, 5)
        assertEquals(120, reboot.delta()?.seconds)
        assertEquals(220_000L, reboot.delta()?.completedAt)
    }

    @Test fun `state and entry drafts keep their frozen prescription but restore only for their owner`() {
        val state = run(time, recovery).start(100_000, 10_000, 4).stop(101_000, 11_000, 4).turn(13)
        val scope = object : SaverScope { override fun canBeSaved(value: Any) = true }
        val saved = with(cardioSetStateSaver) { assertNotNull(scope.save(state)) }
        val restored = assertNotNull(cardioSetStateSaver.restore(saved))
        assertEquals(state, restored)
        val snapshot = snapshot(state.block)
        assertEquals(restored, restored.reconcile(snapshot))
        assertNull(restored.reconcile(snapshot.copy(planId = 10)))
        assertNull(restored.reconcile(snapshot.copy(day = snapshot.day.copy(dayId = "B"))))
        assertEquals(restored, restored.reconcile(snapshot.copy(cardioBlock = block(distance))))
        assertEquals(restored, restored.reconcile(snapshot.copy(cardioBlock = null)))
        assertNull(restored.reconcile(snapshot.copy(cardio = CardioSuggestion("Phone", "", false, loggedStartedAt = 999))))
    }

    @Test fun `unstarted offers follow edits but started active and paused runs stay frozen`() {
        val offer = run(time, time)
        val edited = snapshot(block(distance))
        assertNull(offer.reconcile(edited))
        val active = offer.start(100_000, 10_000, 4)
        assertEquals(active, active.reconcile(edited))
        val paused = active.resume(220_000, 130_000, 4)
        assertEquals(paused, paused.reconcile(edited))
        assertNull(active.reconcile(edited.copy(planId = 10)))
        assertNull(active.reconcile(edited.copy(day = edited.day.copy(dayId = "B"))))
    }

    @Test fun `eligibility is captured on first start and survives edits subsequent starts and restore`() {
        val exercise = WatchExercise(1, "main", "Squat", 100.0, false, null,
            listOf(WatchSet(100.0, 5, "WORK", true)), emptyList())
        val cardioOnly = snapshot(block(time, time))
        val mixed = cardioOnly.copy(day = cardioOnly.day.copy(exercises = listOf(exercise)))
        val scope = object : SaverScope { override fun canBeSaved(value: Any) = true }
        listOf(cardioOnly to mixed, mixed to cardioOnly).forEach { (atStart, edited) ->
            val eligible = atStart.day.exercises.isEmpty()
            val started = assertNotNull(newCardioSetState(atStart, null, false))
                .tap(100_000, 10_000, 4, finishesDay = eligible)
            val saved = with(cardioSetStateSaver) { assertNotNull(scope.save(started)) }
            val restored = assertNotNull(cardioSetStateSaver.restore(saved))
            val afterEdit = assertNotNull(restored.reconcile(edited)).resume(220_000, 130_000, 4)
            val finished = afterEdit.tap(230_000, 140_000, 4, finishesDay = !eligible)
                .resume(350_000, 260_000, 4)
            assertEquals(eligible, assertNotNull(finished.delta()).finishesDay)
        }
    }

    @Test fun `older saved runs default to summary eligibility`() {
        val original = run(time).start(100_000, 10_000, 4, finishesDay = true)
        val scope = object : SaverScope { override fun canBeSaved(value: Any) = true }
        val saved = with(cardioSetStateSaver) { assertNotNull(scope.save(original)) } as List<*>
        val legacy = saved.chunked(2).filter { it[0] != "finishesDay" && it[0] != "saveFailed" }.flatten()
        val restored = assertNotNull(cardioSetStateSaver.restore(legacy))
        assertFalse(restored.finishesDay)
        assertFalse(restored.saveFailed)
    }

    @Test fun `saving and failed enqueue keep results and retry until a durable receipt exists`() {
        val finished = run(time).start(100_000, 10_000, 4).resume(220_000, 130_000, 4)
        assertEquals(listOf("Saving…"), texts(render(finished)))
        assertEquals(DialTap.NONE, render(finished).tap)
        val failed = finished.copy(saveFailed = true)
        assertEquals(listOf("Couldn't save", "Tap to retry"), texts(render(failed)))
        assertEquals(DialTap.CARDIO_SET, render(failed).tap)
        val scope = object : SaverScope { override fun canBeSaved(value: Any) = true }
        val saved = with(cardioSetStateSaver) { assertNotNull(scope.save(failed)) }
        val restored = assertNotNull(cardioSetStateSaver.restore(saved))
        assertEquals(failed, restored)
        val retry = restored.tap(230_000, 140_000, 4)
        assertEquals(finished, retry)
        assertEquals(finished.delta(), retry.delta())
        assertEquals("Saved on watch", retry.copy(queuedStamp = 220_001).receipt(snapshot(retry.block)))
    }

    @Test fun `a held Stop crossing zero cannot shorten auto-started recovery`() {
        val state = run(time, recovery).start(100_000, 10_000, 4)
        val next = state.stop(220_100, 130_100, 4)
        assertEquals(1, next.position)
        assertEquals("FINISHED", next.results.single().status)
        assertNotNull(next.active)
        assertTrue(next.entries.isEmpty())
    }

    @Test fun `live anchors restore and one armed boundary buzzes only once`() {
        val original = run(time, recovery).start(100_000, 10_000, 4)
        val scope = object : SaverScope { override fun canBeSaved(value: Any) = true }
        val saved = with(cardioSetStateSaver) { assertNotNull(scope.save(original)) }
        var state = assertNotNull(cardioSetStateSaver.restore(saved))
        assertEquals(original, state)
        var now = 10_000L
        var buzzes = 0
        var fire: (() -> Unit)? = null
        val scheduler = object : RestAlarmScheduler {
            override fun schedule(deadlineMillis: Long, onAlarm: () -> Unit) { fire = onAlarm }
            override fun cancel() { fire = null }
        }
        val timer = RestTimerController(scheduler, { now }, { buzzes++ })
        timer.arm(assertNotNull(state.deadline(now, 100_000, 4)), 120) {
            state = state.resume(220_000, now, 4)
        }
        val delivery = assertNotNull(fire)
        now = 130_000
        timer.completeIfExpired()
        delivery()
        assertEquals(1, buzzes)
        assertEquals(1, state.position)
        assertEquals("FINISHED", state.results.single().status)
        assertNotNull(state.active)
    }

    @Test fun `receipt waits for actual issued stamp and survives rotation until acknowledged`() {
        val state = run(time).start(100_000, 10_000, 4).resume(220_000, 130_000, 4).copy(queuedStamp = 999_000)
        assertEquals("Saved on watch", state.receipt(snapshot(state.block).copy(cardioAckStamp = 998_999)))
        assertEquals("Logged 2:00", state.receipt(snapshot(state.block).copy(cardioAckStamp = 999_000)))
        val next = snapshot(state.block).copy(day = WatchDay("B", "Next", 1, emptyList()), cardioAckStamp = 999_000)
        assertEquals(state, state.reconcile(next))
        assertFalse(state.ownsExecution)
        assertEquals(DialTap.NONE, render(state).tap)
        assertEquals(listOf("Saved on watch"), texts(render(state)))
        val logged = dialUiState(DialInputs(next, 0, SetPhase.READY, DialFace.WORKOUT, 0, null, cardioSets = state))
        assertEquals(listOf("Logged 2:00"), texts(logged))
        assertEquals(DialTap.DISMISS, logged.tap)
    }

    @Test fun `receipt and dismiss wait for the queued cardio stamp in the processed list`() {
        val state = run(time).start(100_000, 10_000, 4).resume(220_000, 130_000, 4).copy(queuedStamp = 100L)
        val laterOnly = snapshot(state.block).copy(cardioAckStamp = 101L, cardioAckedStamps = listOf(101L))
        assertEquals("Saved on watch", state.receipt(laterOnly))
        val pending = dialUiState(DialInputs(laterOnly, 0, SetPhase.READY, DialFace.WORKOUT, 0, null, cardioSets = state))
        assertEquals(listOf("Saved on watch"), texts(pending))
        assertEquals(DialTap.NONE, pending.tap)

        val acknowledged = laterOnly.copy(cardioAckedStamps = listOf(100L))
        assertEquals("Logged 2:00", state.receipt(acknowledged))
        val logged = dialUiState(DialInputs(acknowledged, 0, SetPhase.READY, DialFace.WORKOUT, 0, null, cardioSets = state))
        assertEquals(listOf("Logged 2:00"), texts(logged))
        assertEquals(DialTap.DISMISS, logged.tap)
    }

    @Test fun `cardio-only snapshot offers sets and timed reps cannot start while cardio owns execution`() {
        val state = run(time)
        val snap = snapshot(state.block)
        val offered = dialUiState(DialInputs(snap, 0, SetPhase.READY, DialFace.OVERVIEW, 0, null))
        assertEquals(DialTap.CARDIO_SET, offered.tap)
        assertTrue("Start" in texts(offered))
        val exercise = WatchExercise(1, "main", "Push-ups", 0.0, false, null,
            listOf(WatchSet(0.0, 0, "WORK", false)), emptyList(), tracking = "reps", repWindowSeconds = 120)
        val timed = snap.copy(day = snap.day.copy(exercises = listOf(exercise)))
        assertNull(startTimedRep(timed, 0, state.ownsExecution, null, 100_000, 10_000, 4))
        val attempt = assertNotNull(startTimedRep(timed, 0, false, null, 100_000, 10_000, 4))
        assertNull(newCardioSetState(snap, attempt, false))
        assertNull(newCardioSetState(snap, null, true))
        assertNotNull(newCardioSetState(snap, null, false))
    }

    @Test fun `old-phone snapshot keeps legacy timer gestures and discard behavior`() {
        val suggestion = CardioSuggestion("Intervals", "", true, mode = "OUTDOOR_RUN", fiveK = true)
        val exercise = WatchExercise(1, "main", "Squat", 100.0, false, null,
            listOf(WatchSet(100.0, 5, "WORK", true)), emptyList())
        val old = WatchSnapshot(revision = 1, suggestedDayId = "A", unit = "lb", cardio = suggestion,
            day = WatchDay("A", "Lifts", 0, listOf(exercise)))
        val inputs = DialInputs(old, 0, SetPhase.READY, DialFace.OVERVIEW, 0, null)
        assertEquals(DialTap.START_CARDIO, dialUiState(inputs).tap)
        val running = dialUiState(inputs.copy(cardioAnchors = CardioAnchors(100_000, 10_000), nowElapsedMillis = 11_000))
        assertEquals(DialTap.NONE, running.tap)
        assertEquals(DialHoldAction.STOP_CARDIO, running.hold?.action)
        assertNull(old.cardioBlock)
    }
}
