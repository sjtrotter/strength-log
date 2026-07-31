package io.github.sjtrotter.strengthlog.domain.sync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

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
    fun `snapshot round-trips a set's restAfterSeconds through the wire bytes`() {
        val withRest = snapshot.copy(
            day = snapshot.day.copy(
                exercises = listOf(
                    snapshot.day.exercises.single().copy(
                        sets = listOf(WatchSet(255.0, 5, "TOP", done = false, restAfterSeconds = 180)),
                    ),
                ),
            ),
        )
        val decoded = SyncCodec.decodeSnapshot(SyncCodec.encodeSnapshot(withRest))
        assertEquals(withRest, decoded)
        assertEquals(180, decoded.day.exercises.single().sets.single().restAfterSeconds)
    }

    @Test
    fun `snapshot round-trips the program's cycle through the wire bytes`() {
        val withCycle = snapshot.copy(
            cycle = listOf(
                WatchCycleDay("A", "Day A — Squat", listOf(WatchCycleExercise("Squat", 6))),
                WatchCycleDay("B", "Day B — Hinge", listOf(WatchCycleExercise("Deadlift", 4))),
            ),
        )
        val decoded = SyncCodec.decodeSnapshot(SyncCodec.encodeSnapshot(withCycle))
        assertEquals(withCycle, decoded)
        assertEquals(listOf("A", "B"), decoded.cycle.map { it.dayId })
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

    // --- per-set timing (#85) ------------------------------------------------

    @Test
    fun `a delta carrying the set timing round-trips through the wire bytes`() {
        val tick = delta.copy(
            weightLb = null,
            done = true,
            startedAtMillis = 1_700_000_000_000L,
            completedAtMillis = 1_700_000_045_000L,
        )
        val decoded = SyncCodec.decodeDelta(SyncCodec.encodeDelta(tick))
        assertEquals(tick, decoded)
        assertEquals(1_700_000_000_000L, decoded.startedAtMillis)
        assertEquals(1_700_000_045_000L, decoded.completedAtMillis)
        // The LWW/dedupe stamp keeps its own job — the facts never overwrite it.
        assertEquals(1_700_000_000_000L, decoded.editedAtMillis)
    }

    @Test
    fun `an old-format delta without the timing keys decodes with nulls`() {
        val oldFormat = """
            {"schemaVersion":1,"dayId":"B","programExerciseId":9,"slot":"main","setIndex":1,
             "done":true,"editedAtMillis":7}
        """.trimIndent()
        val decoded = SyncCodec.decodeDelta(oldFormat.encodeToByteArray())
        assertNull(decoded.startedAtMillis)
        assertNull(decoded.completedAtMillis)
        assertEquals(true, decoded.done)
        assertEquals(7L, decoded.editedAtMillis)
    }

    @Test
    fun `a new-format delta still decodes everything an old reader expects`() {
        // The other direction: a schemaVersion-1 payload written by a *new* watch
        // carries the extra keys, and every field an older phone reads is untouched.
        val newFormat = """
            {"schemaVersion":1,"dayId":"B","programExerciseId":9,"slot":"main","setIndex":1,
             "reps":5,"done":true,"editedAtMillis":7,
             "startedAtMillis":1700000000000,"completedAtMillis":1700000045000}
        """.trimIndent()
        val decoded = SyncCodec.decodeDelta(newFormat.encodeToByteArray())
        assertEquals(1, decoded.schemaVersion)
        assertEquals("B", decoded.dayId)
        assertEquals(9L, decoded.programExerciseId)
        assertEquals(1, decoded.setIndex)
        assertEquals(5, decoded.reps)
        assertEquals(true, decoded.done)
        assertEquals(7L, decoded.editedAtMillis)
        assertEquals(1_700_000_045_000L, decoded.completedAtMillis)
    }

    @Test
    fun `a queue mixing old and new format deltas round-trips`() {
        val queue = listOf(
            delta,
            delta.copy(
                setIndex = 0,
                editedAtMillis = 2L,
                done = true,
                startedAtMillis = 10L,
                completedAtMillis = 20L,
            ),
        )
        assertEquals(queue, SyncCodec.decodeDeltaQueue(SyncCodec.encodeDeltaQueue(queue)))
    }

    @Test
    fun `delta queue round-trips and an empty string decodes to empty`() {
        val queue = listOf(delta, delta.copy(setIndex = 0, editedAtMillis = 2L))
        assertEquals(queue, SyncCodec.decodeDeltaQueue(SyncCodec.encodeDeltaQueue(queue)))
        assertEquals(emptyList(), SyncCodec.decodeDeltaQueue(""))
    }
}
