package io.github.sjtrotter.strengthlog.domain.sync

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The wire codec is the SSOT both transports go through, so its round-trips and —
 * crucially — its forward-migration leniency are pinned here (m5-wear.md #20
 * "Serialization" requirement: decode both DTOs with `ignoreUnknownKeys = true`).
 */
class SyncCodecTest {

    private val snapshot = WatchSnapshot(
        revision = 42L,
        suggestedDayId = "B",
        day = WatchDay(
            dayId = "B",
            title = "Day B — Hinge",
            accentIndex = 1,
            exercises = listOf(
                WatchExercise(
                    programExerciseId = 9L,
                    slot = "main",
                    name = "Trap-Bar Deadlift",
                    goal = 255.0,
                    perHand = false,
                    supersetPartnerName = null,
                    sets = listOf(WatchSet(190.0, 3, "RAMP", done = true), WatchSet(255.0, 5, "TOP", done = false)),
                    ssSets = emptyList(),
                ),
            ),
        ),
        unit = "kg",
    )

    private val delta = SetEditDelta(
        dayId = "B",
        programExerciseId = 9L,
        slot = "main",
        setIndex = 1,
        weightLb = 265.0,
        editedAtMillis = 1_700_000_000_000L,
    )

    @Test
    fun `snapshot round-trips through the wire bytes`() {
        assertEquals(snapshot, SyncCodec.decodeSnapshot(SyncCodec.encodeSnapshot(snapshot)))
    }

    @Test
    fun `delta round-trips through the wire bytes`() {
        assertEquals(delta, SyncCodec.decodeDelta(SyncCodec.encodeDelta(delta)))
    }

    @Test
    fun `snapshot decode tolerates an unknown future field`() {
        val withExtra = """
            {"schemaVersion":2,"revision":1,"suggestedDayId":"A","unit":"lb","futureFlag":true,
             "day":{"dayId":"A","title":"A","accentIndex":0,"exercises":[]}}
        """.trimIndent()
        assertEquals("A", SyncCodec.decodeSnapshot(withExtra.encodeToByteArray()).suggestedDayId)
    }

    @Test
    fun `delta decode tolerates an unknown future field`() {
        val withExtra = """
            {"schemaVersion":2,"dayId":"A","programExerciseId":1,"slot":"main","setIndex":0,
             "editedAtMillis":5,"rpe":8}
        """.trimIndent()
        val decoded = SyncCodec.decodeDelta(withExtra.encodeToByteArray())
        assertEquals(0, decoded.setIndex)
        assertEquals(5L, decoded.editedAtMillis)
    }

    @Test
    fun `delta queue round-trips and an empty string decodes to empty`() {
        val queue = listOf(delta, delta.copy(setIndex = 0, editedAtMillis = 2L))
        assertEquals(queue, SyncCodec.decodeDeltaQueue(SyncCodec.encodeDeltaQueue(queue)))
        assertEquals(emptyList(), SyncCodec.decodeDeltaQueue(""))
    }
}
