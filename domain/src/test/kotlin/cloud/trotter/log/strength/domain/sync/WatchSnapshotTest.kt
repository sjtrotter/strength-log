package cloud.trotter.log.strength.domain.sync

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WatchSnapshotTest {

    private val json = Json

    private val sample = WatchSnapshot(
        revision = 7L,
        suggestedDayId = "A",
        day = WatchDay(
            dayId = "A",
            title = "Day A — Squat Focus",
            accentIndex = 0,
            exercises = listOf(
                WatchExercise(
                    programExerciseId = 1L,
                    slot = "main",
                    name = "Barbell Back Squat",
                    goal = 235.0,
                    perHand = false,
                    supersetPartnerName = null,
                    sets = listOf(
                        WatchSet(130.0, 5, "RAMP", done = true),
                        WatchSet(235.0, 5, "TOP", done = false),
                    ),
                    ssSets = emptyList(),
                ),
                WatchExercise(
                    programExerciseId = 2L,
                    slot = "main",
                    name = "Incline DB Press",
                    goal = 75.0,
                    perHand = true,
                    supersetPartnerName = "Rope Pushdown",
                    sets = listOf(WatchSet(75.0, 8, "WORK", done = false)),
                    ssSets = listOf(WatchSet(50.0, 12, "WORK", done = false)),
                ),
            ),
        ),
        unit = "lb",
    )

    @Test
    fun `round-trips through JSON with every field intact`() {
        val encoded = json.encodeToString(WatchSnapshot.serializer(), sample)
        val decoded = json.decodeFromString(WatchSnapshot.serializer(), encoded)
        assertEquals(sample, decoded)
    }

    @Test
    fun `authored rep window and detail decode without a schema bump`() {
        val exercise = sample.day.exercises.first().copy(
            goal = 0.0, goalLabel = "2:00 · aim 40", tracking = "reps",
            targetDetail = "Each side · Red band", repWindowSeconds = 120, repAim = 40,
            prescribed = true,
        )
        val snapshot = sample.copy(day = sample.day.copy(exercises = listOf(exercise)))
        val decoded = json.decodeFromString(WatchSnapshot.serializer(),
            json.encodeToString(WatchSnapshot.serializer(), snapshot))
        assertEquals(snapshot, decoded)
        assertEquals(1, decoded.schemaVersion)
        assertEquals(120, decoded.day.exercises.single().repWindowSeconds)
        assertEquals(40, decoded.day.exercises.single().repAim)
    }

    @Test
    fun `schemaVersion defaults to 1`() {
        assertEquals(1, sample.schemaVersion)
    }

    @Test
    fun `old snapshot payloads decode with null processed edit stamps`() {
        val oldWire = """{"schemaVersion":1,"revision":2,"suggestedDayId":"A","unit":"lb","day":{"dayId":"A","title":"A","accentIndex":0,"exercises":[]}}"""
        val decoded = SyncCodec.decodeSnapshot(oldWire.encodeToByteArray())
        assertNull(decoded.processedEditStamps)
        assertNull(decoded.runId)
        assertEquals(1, decoded.schemaVersion)
    }

    @Test
    fun `a null processed edit list preserves the exact previous snapshot encoding`() {
        val snapshot = WatchSnapshot(
            revision = 2, suggestedDayId = "A", day = WatchDay("A", "A", 0, emptyList()),
            unit = "lb", planId = 7, runId = "r-a", processedEditStamps = null,
        )
        val oldWire = """{"revision":2,"suggestedDayId":"A","day":{"dayId":"A","title":"A","accentIndex":0,"exercises":[]},"unit":"lb","planId":7,"runId":"r-a"}"""
        assertEquals(oldWire, SyncCodec.encodeSnapshot(snapshot).decodeToString())
    }

    @Test
    fun `processed edit stamps round-trip in processing order without a schema bump`() {
        val snapshot = sample.copy(processedEditStamps = listOf(101L, 100L))
        val decoded = SyncCodec.decodeSnapshot(SyncCodec.encodeSnapshot(snapshot))
        assertEquals(snapshot, decoded)
        assertEquals(1, decoded.schemaVersion)
        val empty = sample.copy(processedEditStamps = emptyList())
        assertEquals(empty, SyncCodec.decodeSnapshot(SyncCodec.encodeSnapshot(empty)))
    }

    @Test
    fun `cardio acknowledgement list settles only processed stamps despite a higher max`() {
        val snapshot = sample.copy(cardioAckStamp = 101L, cardioAckedStamps = listOf(101L))
        assertFalse(snapshot.acknowledgesCardio(100L))
        assertTrue(snapshot.acknowledgesCardio(101L))
        assertFalse(snapshot.acknowledgesCardio(102L))
        assertTrue(snapshot.copy(cardioAckedStamps = listOf(100L, 101L)).acknowledgesCardio(100L))
    }

    @Test
    fun `empty cardio acknowledgement list preserves the legacy max rule`() {
        val snapshot = sample.copy(cardioAckStamp = 101L)
        assertTrue(snapshot.acknowledgesCardio(100L))
        assertTrue(snapshot.acknowledgesCardio(101L))
        assertFalse(snapshot.acknowledgesCardio(102L))
    }

    @Test
    fun `old JSON without cardio acknowledgement list decodes empty and uses max`() {
        val oldWire = """
            {"schemaVersion":1,"revision":2,"suggestedDayId":"A","unit":"lb","cardioAckStamp":101,
             "day":{"dayId":"A","title":"A","accentIndex":0,"exercises":[]}}
        """.trimIndent()
        val decoded = SyncCodec.decodeSnapshot(oldWire.encodeToByteArray())
        assertEquals(emptyList(), decoded.cardioAckedStamps)
        assertTrue(decoded.acknowledgesCardio(100L))
        assertEquals(1, decoded.schemaVersion)
    }

    @Test
    fun `cardio acknowledgement stamps round-trip in processing order`() {
        val snapshot = sample.copy(cardioAckStamp = 101L, cardioAckedStamps = listOf(101L, 100L))
        assertEquals(snapshot, SyncCodec.decodeSnapshot(SyncCodec.encodeSnapshot(snapshot)))
    }

    @Test
    fun `emphasisLine defaults to blank for a publisher that doesn't set it`() {
        assertEquals("", sample.day.emphasisLine)
    }

    @Test
    fun `emphasisLine round-trips when set`() {
        val withEmphasis = sample.copy(day = sample.day.copy(emphasisLine = "quads"))
        val encoded = json.encodeToString(WatchSnapshot.serializer(), withEmphasis)
        val decoded = json.decodeFromString(WatchSnapshot.serializer(), encoded)
        assertEquals("quads", decoded.day.emphasisLine)
    }

    @Test
    fun `a superset partner's rows align by index with the main track`() {
        val press = sample.day.exercises[1]
        assertEquals(press.sets.size, press.ssSets.size)
    }

    @Test
    fun `tracking defaults to weighted and seconds to 0 for a publisher that doesn't set them`() {
        val ex = sample.day.exercises.first()
        assertEquals("weighted", ex.tracking)
        assertEquals(0, ex.sets.first().seconds)
    }

    @Test
    fun `restAfterSeconds defaults to 0 for a publisher that doesn't set it`() {
        assertEquals(0, sample.day.exercises.first().sets.first().restAfterSeconds)
    }

    @Test
    fun `a snapshot carrying restAfterSeconds round-trips it`() {
        val withRest = sample.copy(
            day = sample.day.copy(
                exercises = listOf(
                    sample.day.exercises.first().copy(
                        sets = listOf(
                            WatchSet(130.0, 5, "RAMP", done = true, restAfterSeconds = 90),
                            WatchSet(235.0, 5, "TOP", done = false, restAfterSeconds = 180),
                        ),
                    ),
                ),
            ),
        )
        val decoded = json.decodeFromString(
            WatchSnapshot.serializer(),
            json.encodeToString(WatchSnapshot.serializer(), withRest),
        )
        assertEquals(withRest, decoded)
        val sets = decoded.day.exercises.single().sets
        assertEquals(90, sets[0].restAfterSeconds)
        assertEquals(180, sets[1].restAfterSeconds)
    }

    @Test
    fun `an old snapshot without the restAfterSeconds key decodes to 0 (no timer)`() {
        // A pre-W2a wire: the set carries no `restAfterSeconds`. It must decode as
        // 0 so a pre-rest phone and a rest-aware watch interoperate — 0 = no timer,
        // the watch advances immediately (mirrors the `seconds` precedent).
        val lenient = Json { ignoreUnknownKeys = true }
        val oldWire = """
            {"schemaVersion":1,"revision":2,"suggestedDayId":"A","unit":"lb",
             "day":{"dayId":"A","title":"A","accentIndex":0,"exercises":[
               {"programExerciseId":1,"slot":"main","name":"Squat","goal":235.0,"perHand":false,
                "supersetPartnerName":null,
                "sets":[{"weightLb":235.0,"reps":5,"kind":"TOP","done":false,"seconds":0}],"ssSets":[]}
             ]}}
        """.trimIndent()
        val decoded = lenient.decodeFromString(WatchSnapshot.serializer(), oldWire)
        assertEquals(0, decoded.day.exercises.single().sets.single().restAfterSeconds)
        // Same wire, same rule for the epoch: a publisher too old to stamp a
        // generation decodes as epoch 0 — the oldest generation, and its own. The
        // watch installs it, then supersedes it with the first epoched publish.
        assertEquals(0L, decoded.epoch)
    }

    @Test
    fun `a REPS or TIMED snapshot round-trips tracking, seconds and goalLabel`() {
        val timed = WatchSnapshot(
            revision = 9L,
            suggestedDayId = "A",
            day = WatchDay(
                dayId = "A",
                title = "Core",
                accentIndex = 0,
                exercises = listOf(
                    WatchExercise(
                        programExerciseId = 3L,
                        slot = "main",
                        name = "Weighted Plank",
                        goal = 25.0,
                        perHand = false,
                        supersetPartnerName = null,
                        sets = listOf(WatchSet(25.0, 0, "WORK", done = false, seconds = 45)),
                        ssSets = emptyList(),
                        goalLabel = "45s +25",
                        tracking = "timed",
                    ),
                ),
            ),
            unit = "lb",
        )
        val decoded = json.decodeFromString(
            WatchSnapshot.serializer(),
            json.encodeToString(WatchSnapshot.serializer(), timed),
        )
        assertEquals(timed, decoded)
        val ex = decoded.day.exercises.single()
        assertEquals("timed", ex.tracking)
        assertEquals(45, ex.sets.single().seconds)
        assertEquals("45s +25", ex.goalLabel)
    }

    @Test
    fun `an old snapshot without tracking or seconds keys decodes to the weighted defaults`() {
        // An older wire: the exercise carries no `tracking`, its set no `seconds`.
        // Both must decode as the weighted / 0 defaults so an older phone and a
        // newer watch interoperate without a schemaVersion gate.
        val lenient = Json { ignoreUnknownKeys = true }
        val oldWire = """
            {"schemaVersion":1,"revision":2,"suggestedDayId":"A","unit":"lb",
             "day":{"dayId":"A","title":"A","accentIndex":0,"exercises":[
               {"programExerciseId":1,"slot":"main","name":"Squat","goal":235.0,"perHand":false,
                "supersetPartnerName":null,
                "sets":[{"weightLb":235.0,"reps":5,"kind":"TOP","done":false}],"ssSets":[]}
             ]}}
        """.trimIndent()
        val decoded = lenient.decodeFromString(WatchSnapshot.serializer(), oldWire)
        val ex = decoded.day.exercises.single()
        assertEquals("weighted", ex.tracking)
        assertEquals(0, ex.sets.single().seconds)
        assertEquals("", ex.goalLabel)
        assertEquals("", ex.targetDetail)
        assertEquals(0, ex.repWindowSeconds)
        assertEquals(null, ex.repAim)
        assertEquals(false, ex.prescribed)
    }

    @Test
    fun `a superset partner's ssTracking round-trips when set`() {
        val press = sample.day.exercises[1].copy(ssTracking = "reps")
        val withSsTracking = sample.copy(day = sample.day.copy(exercises = listOf(sample.day.exercises[0], press)))
        val decoded = json.decodeFromString(
            WatchSnapshot.serializer(),
            json.encodeToString(WatchSnapshot.serializer(), withSsTracking),
        )
        assertEquals(withSsTracking, decoded)
        assertEquals("reps", decoded.day.exercises[1].ssTracking)
    }

    @Test
    fun `an old snapshot without the ssTracking key decodes to the weighted default`() {
        // An older wire: the exercise carries no `ssTracking`. Must decode as
        // "weighted" so an older phone and a newer watch interoperate — rendering
        // the partner with the main's tracking makes a WEIGHTED main the common
        // case this default silently preserves.
        val lenient = Json { ignoreUnknownKeys = true }
        val oldWire = """
            {"schemaVersion":1,"revision":2,"suggestedDayId":"A","unit":"lb",
             "day":{"dayId":"A","title":"A","accentIndex":0,"exercises":[
               {"programExerciseId":1,"slot":"main","name":"Squat","goal":235.0,"perHand":false,
                "supersetPartnerName":null,
                "sets":[{"weightLb":235.0,"reps":5,"kind":"TOP","done":false}],"ssSets":[]}
             ]}}
        """.trimIndent()
        val decoded = lenient.decodeFromString(WatchSnapshot.serializer(), oldWire)
        assertEquals("weighted", decoded.day.exercises.single().ssTracking)
    }

    @Test
    fun `the cycle defaults to empty for a publisher that doesn't send one`() {
        assertEquals(emptyList(), sample.cycle)
    }

    @Test
    fun `a snapshot carrying the program's cycle round-trips it in order`() {
        val withCycle = sample.copy(
            cycle = listOf(
                WatchCycleDay("A", "Day A — Squat Focus", listOf(WatchCycleExercise("Squat", 6))),
                WatchCycleDay("B", "Day B — Hinge", listOf(WatchCycleExercise("Deadlift", 4))),
            ),
        )
        val decoded = json.decodeFromString(
            WatchSnapshot.serializer(),
            json.encodeToString(WatchSnapshot.serializer(), withCycle),
        )
        assertEquals(withCycle, decoded)
        assertEquals(listOf("A", "B"), decoded.cycle.map { it.dayId })
        assertEquals(6, decoded.cycle.first().exercises.single().setCount)
    }

    @Test
    fun `an old snapshot without the cycle key decodes to an empty one (today alone)`() {
        // A pre-v3 wire: no `cycle`. It must decode as empty, which the watch renders
        // as the single full-circle segment its outer ring always was — no
        // schemaVersion gate, same posture as restAfterSeconds.
        val lenient = Json { ignoreUnknownKeys = true }
        val oldWire = """
            {"schemaVersion":1,"revision":2,"suggestedDayId":"A","unit":"lb",
             "day":{"dayId":"A","title":"A","accentIndex":0,"exercises":[]}}
        """.trimIndent()
        val decoded = lenient.decodeFromString(WatchSnapshot.serializer(), oldWire)
        assertEquals(emptyList(), decoded.cycle)
    }

    @Test
    fun `decoding tolerates an unknown future field (forward migration)`() {
        val lenient = Json { ignoreUnknownKeys = true }
        val withExtra = """
            {"schemaVersion":1,"revision":7,"suggestedDayId":"A","unit":"lb","futureField":"x",
             "day":{"dayId":"A","title":"Day A","accentIndex":0,"exercises":[]}}
        """.trimIndent()
        val decoded = lenient.decodeFromString(WatchSnapshot.serializer(), withExtra)
        assertEquals("A", decoded.suggestedDayId)
    }
}
