package cloud.trotter.log.strength.domain.sync

import cloud.trotter.log.strength.domain.model.CardioActivity
import cloud.trotter.log.strength.domain.model.CardioBlock
import cloud.trotter.log.strength.domain.model.CardioRole
import cloud.trotter.log.strength.domain.model.CardioSetSpec
import cloud.trotter.log.strength.domain.model.Measure
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class WatchCardioWireTest {
    private val block = CardioBlock(CardioActivity.TRACK_RUN, listOf(
        CardioSetSpec(CardioRole.WARMUP, Measure.Time(60), Measure.Distance(100)),
        CardioSetSpec(CardioRole.WORK, Measure.Distance(400), Measure.Time(105), hard = true),
        CardioSetSpec(CardioRole.RECOVERY, Measure.Time(90)),
    ))
    private val snapshot = WatchSnapshot(revision = 1, suggestedDayId = "A",
        day = WatchDay("A", "Track", 0, emptyList()), unit = "lb", cardioBlock = block.toWatch())
    private val delta = CardioDelta(dayId = "A", mode = "OUTDOOR_RUN", hard = true,
        label = "Track run", startedAt = 1_000, completedAt = 256_000,
        seconds = 255, stepsCompleted = 2, stamp = 256_000, planId = 42,
        activity = "TRACK_RUN", finishesDay = true, sets = listOf(
            WatchCardioSetResult(block.sets[0].toWatch(), "FINISHED", 60, 110, "MANUAL"),
            WatchCardioSetResult(block.sets[1].toWatch(), "SHORTENED", 195),
            WatchCardioSetResult(block.sets[2].toWatch(), "SKIPPED", 0),
        ))

    @Test fun `structured snapshot and results round trip every prescription and outcome`() {
        val decoded = SyncCodec.decodeSnapshot(SyncCodec.encodeSnapshot(snapshot))
        assertEquals(snapshot, decoded)
        assertEquals(block, decoded.cardioBlock?.toDomain())
        val result = SyncCodec.decodeCardio(SyncCodec.encodeCardio(delta))
        assertEquals(delta, result)
        assertEquals(110, result.sets[0].toDomain().metres)
        assertNull(result.sets[1].toDomain().distanceSource)
        assertEquals(listOf(delta), SyncCodec.decodeCardioQueue(SyncCodec.encodeCardioQueue(listOf(delta))))
    }

    @Test fun `old payloads decode with absent structured fields and unchanged schema`() {
        val oldSnapshot = """{"revision":1,"suggestedDayId":"A","unit":"lb","day":{"dayId":"A","title":"Run","accentIndex":0,"exercises":[]}}"""
        val oldDelta = """{"dayId":"A","mode":"OUTDOOR_RUN","hard":false,"label":"Easy","startedAt":1000,"completedAt":61000,"seconds":60,"stepsCompleted":0,"stamp":61000}"""
        assertNull(SyncCodec.decodeSnapshot(oldSnapshot.encodeToByteArray()).cardioBlock)
        val decoded = SyncCodec.decodeCardio(oldDelta.encodeToByteArray())
        assertNull(decoded.activity)
        assertEquals(emptyList(), decoded.sets)
        assertEquals(false, decoded.finishesDay)
        assertEquals(1, decoded.schemaVersion)
        assertEquals(1, snapshot.schemaVersion)
    }

    @Test fun `older readers ignore the additions and retain the summary`() {
        val json = Json { ignoreUnknownKeys = true }
        assertEquals("A", json.decodeFromString(LegacySnapshot.serializer(),
            SyncCodec.encodeSnapshot(snapshot).decodeToString()).suggestedDayId)
        val summary = json.decodeFromString(LegacyCardio.serializer(), SyncCodec.encodeCardio(delta).decodeToString())
        assertEquals(255, summary.seconds)
        assertEquals("OUTDOOR_RUN", summary.mode)
    }

    @Serializable private data class LegacySnapshot(val suggestedDayId: String)
    @Serializable private data class LegacyCardio(val mode: String, val seconds: Int)
}
