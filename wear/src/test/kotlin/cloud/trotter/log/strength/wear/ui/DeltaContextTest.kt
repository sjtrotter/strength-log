package cloud.trotter.log.strength.wear.ui

import cloud.trotter.log.strength.domain.generator.CardioIntervals
import cloud.trotter.log.strength.domain.model.CardioSuggestion
import cloud.trotter.log.strength.domain.sync.SyncCodec
import cloud.trotter.log.strength.domain.sync.WatchAlternate
import cloud.trotter.log.strength.domain.sync.WatchSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals

/** Every wrist mutation echoes the installed snapshot, including legacy nulls. */
class DeltaContextTest {
    private val oldSnapshot = SyncCodec.decodeSnapshot(
        """{"revision":1,"suggestedDayId":"A","unit":"lb","day":{"dayId":"A","title":"A","accentIndex":0,"exercises":[]}}"""
            .encodeToByteArray(),
    )

    @Test
    fun `tick undo swap and cardio echo the installed plan and run`() {
        val snapshot = oldSnapshot.copy(planId = 17L, planName = "Strength", runToken = 123L)
        assertEquals(List(4) { 17L to 123L }, deltaContexts(snapshot))
    }

    @Test
    fun `the first run echoes zero rather than a legacy null`() {
        assertEquals(List(4) { 17L to 0L }, deltaContexts(oldSnapshot.copy(planId = 17L)))
    }

    @Test
    fun `an installed snapshot from an older phone produces legacy nulls`() {
        assertEquals(List(4) { null to null }, deltaContexts(oldSnapshot))
    }

    private fun deltaContexts(snapshot: WatchSnapshot): List<Pair<Long?, Long?>> {
        val tick = buildTickDelta("A", 7L, 0, 100L, 200L, snapshot = snapshot)
        val undo = buildUndoDelta("A", 7L, 0, 300L, snapshot = snapshot)
        val swap = buildSwapDelta(snapshot, 7L, WatchAlternate("hack_squat", "Hack Squat"), 400L)
        val anchors = CardioAnchors(1_000L, 1_000L)
        val suggestion = CardioSuggestion("Easy Zone 2", "", hard = false)
        val progress = cardioProgress(CardioIntervals.plan(suggestion, fiveK = false), anchors, 61_000L)
        val cardio = buildCardioDelta("A", "OUTDOOR_RUN", false, suggestion.label, anchors, progress, 61_000L, snapshot)!!
        return listOf(
            tick.planId to tick.runToken,
            undo.planId to undo.runToken,
            swap.planId to swap.runToken,
            cardio.planId to cardio.runToken,
        )
    }
}
