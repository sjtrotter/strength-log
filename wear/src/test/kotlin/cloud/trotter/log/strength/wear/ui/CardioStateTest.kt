package cloud.trotter.log.strength.wear.ui

import cloud.trotter.log.strength.domain.generator.CardioIntervals
import cloud.trotter.log.strength.domain.model.CardioSuggestion
import cloud.trotter.log.strength.domain.sync.WatchDay
import cloud.trotter.log.strength.domain.sync.WatchExercise
import cloud.trotter.log.strength.domain.sync.WatchSet
import cloud.trotter.log.strength.domain.sync.WatchSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CardioStateTest {
    @Test
    fun `a watch reboot hands elapsed to the wall clock exactly`() {
        val plan = CardioIntervals.plan(suggestion, fiveK = false)
        val anchors = CardioAnchors(startedAtWallMillis = 1_000_000L, startedAtElapsedMillis = 600_000L, bootCount = 4)
        // Post-reboot uptime exceeds the old anchor: the monotonic delta looks
        // plausible (100s) but boots differ, so the wall delta (300s) wins.
        val progress = cardioProgress(plan, anchors, nowElapsedMillis = 700_000L, nowWallMillis = 1_300_000L, nowBootCount = 5)
        assertEquals(300, progress.elapsedSeconds)
    }

    @Test
    fun `boundary deadlines are relative to the current boot's clock`() {
        val plan = CardioIntervals.plan(suggestion, fiveK = false)
        val anchors = CardioAnchors(startedAtWallMillis = 1_000_000L, startedAtElapsedMillis = 600_000L, bootCount = 4)
        val progress = cardioProgress(plan, anchors, nowElapsedMillis = 50_000L, nowWallMillis = 1_100_000L, nowBootCount = 5)
        val boundary = progress.nextBoundaryElapsedMillis
        if (boundary != null) {
            val remainingMillis = boundary - 50_000L
            assertEquals(progress.stepRemainingSeconds.toLong(), remainingMillis / 1_000L)
        }
    }

    private val suggestion = CardioSuggestion("Hard cardio — intervals", "", hard = true, mode = "TREADMILL", fiveK = true)
    private val plan = CardioIntervals.plan(suggestion, fiveK = true)
    private val anchors = CardioAnchors(1_700_000_000_000L, 10_000L)

    @Test fun `steps derive from anchors exactly at boundaries`() {
        val before = cardioProgress(plan, anchors, 309_999L)
        assertEquals(0, before.stepIndex)
        assertEquals(1, before.stepRemainingSeconds)

        val boundary = cardioProgress(plan, anchors, 310_000L)
        assertEquals(1, boundary.stepIndex)
        assertEquals(1, boundary.stepsCompleted)
        assertEquals(120, boundary.stepRemainingSeconds)
        assertEquals(430_000L, boundary.nextBoundaryElapsedMillis)
    }

    @Test fun `stop builds actual seconds and fully completed prefix`() {
        val progress = cardioProgress(plan, anchors, 550_000L)
        val delta = buildCardioDelta("A", "TREADMILL", true, suggestion.label, anchors, progress, 1_700_000_540_000L)!!
        assertEquals(540, delta.seconds)
        assertEquals(3, delta.stepsCompleted)
        assertEquals(anchors.startedAtWallMillis, delta.startedAt)
        assertEquals("TREADMILL", delta.mode)
    }

    @Test fun `under sixty seconds discards`() {
        assertNull(buildCardioDelta("A", "OUTDOOR_RUN", true, suggestion.label, anchors, cardioProgress(plan, anchors, 69_999L), 2L))
        assertTrue(buildCardioDelta("A", "OUTDOOR_RUN", true, suggestion.label, anchors, cardioProgress(plan, anchors, 70_000L), 2L) != null)
    }

    @Test fun `executing state has no tap and cardio hold cannot collide with undo`() {
        val progress = cardioProgress(plan, anchors, 70_000L)
        assertFalse(progress.overrun)
        assertEquals(0, progress.stepsCompleted)
        // The render contract is structural: STOP_CARDIO carries no undo target.
        val hold = DialHold(DialHoldAction.STOP_CARDIO, DiscContent(DiscStyle.FLAT, emptyList()))
        assertNull(hold.target)
    }

    @Test fun `offer requires both a finisher and completed lifts`() {
        assertEquals(DialScreen.READY, render(done = false, cardio = suggestion).screen)
        assertEquals(DialScreen.DAY_DONE, render(done = true, cardio = null).screen)
        val offer = render(done = true, cardio = suggestion)
        assertEquals(DialScreen.CARDIO_OFFER, offer.screen)
        assertEquals(DialTap.START_CARDIO, offer.tap)
        assertNull(offer.hold)
    }

    @Test fun `executing render ignores tap and owns stop hold`() {
        val state = render(done = true, cardio = suggestion, cardioAnchors = anchors, now = 70_000L)
        assertEquals(DialScreen.CARDIO_EXECUTING, state.screen)
        assertEquals(DialTap.NONE, state.tap)
        assertEquals(DialHoldAction.STOP_CARDIO, state.hold?.action)
        assertNull(state.hold?.target)
    }

    private fun render(
        done: Boolean,
        cardio: CardioSuggestion?,
        cardioAnchors: CardioAnchors? = null,
        now: Long = 0L,
    ): DialUiState = dialUiState(
        DialInputs(
            snapshot = WatchSnapshot(
                revision = 1L,
                suggestedDayId = "A",
                day = WatchDay("A", "Full body", 2, listOf(
                    WatchExercise(
                        programExerciseId = 1L, slot = "main", name = "Squat", goal = 100.0,
                        perHand = false, supersetPartnerName = null,
                        sets = listOf(WatchSet(100.0, 5, "WORK", done)), ssSets = emptyList(),
                    ),
                )),
                unit = "lb",
                cardio = cardio,
            ),
            exerciseIndex = 0,
            phase = SetPhase.READY,
            face = if (done && cardio != null && cardioAnchors == null) DialFace.OVERVIEW else DialFace.WORKOUT,
            pendingCount = 0,
            rest = null,
            cardioAnchors = cardioAnchors,
            nowElapsedMillis = now,
        ),
    )
}
