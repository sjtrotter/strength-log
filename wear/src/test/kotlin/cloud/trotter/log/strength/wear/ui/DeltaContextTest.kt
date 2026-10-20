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
    fun `tick undo swap and cardio echo the installed plan`() {
        val snapshot = oldSnapshot.copy(planId = 17L, planName = "Strength")
        assertEquals(List(4) { 17L }, deltaContexts(snapshot))
    }

    @Test
    fun `an installed snapshot from an older phone produces legacy nulls`() {
        assertEquals(List(4) { null }, deltaContexts(oldSnapshot))
    }

    @Test
    fun `cardio stop after another plan snapshot keeps the start-time plan`() {
        for (startPlanId in listOf(17L, null)) {
            val startedSnapshot = oldSnapshot.copy(planId = startPlanId)
            val anchors = CardioAnchors(1_000L, 1_000L, planId = startedSnapshot.planId)
            val installedSnapshot = startedSnapshot.copy(planId = 23L)
            val suggestion = CardioSuggestion("Easy Zone 2", "", hard = false)
            val progress = cardioProgress(CardioIntervals.plan(suggestion, fiveK = false), anchors, 61_000L)

            val stopped = buildCardioDelta(
                installedSnapshot.day.dayId, "OUTDOOR_RUN", false, suggestion.label,
                anchors, progress, 61_000L,
            )!!

            assertEquals(startPlanId, stopped.planId)
            assertEquals(60, stopped.seconds)
        }
    }

    private fun deltaContexts(snapshot: WatchSnapshot): List<Long?> {
        val tick = buildTickDelta("A", 7L, 0, 100L, 200L, snapshot = snapshot)
        val undo = buildUndoDelta("A", 7L, 0, 300L, snapshot = snapshot)
        val swap = buildSwapDelta(snapshot, 7L, WatchAlternate("hack_squat", "Hack Squat"), 400L)
        val anchors = CardioAnchors(1_000L, 1_000L, planId = snapshot.planId)
        val suggestion = CardioSuggestion("Easy Zone 2", "", hard = false)
        val progress = cardioProgress(CardioIntervals.plan(suggestion, fiveK = false), anchors, 61_000L)
        val cardio = buildCardioDelta("A", "OUTDOOR_RUN", false, suggestion.label, anchors, progress, 61_000L)!!
        return listOf(
            tick.planId,
            undo.planId,
            swap.planId,
            cardio.planId,
        )
    }
}
