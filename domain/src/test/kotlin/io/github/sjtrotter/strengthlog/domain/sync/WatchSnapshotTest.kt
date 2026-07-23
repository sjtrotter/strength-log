package io.github.sjtrotter.strengthlog.domain.sync

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

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
    fun `schemaVersion defaults to 1`() {
        assertEquals(1, sample.schemaVersion)
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
        // A pre-P5 wire: the exercise carries no `tracking`, its set no `seconds`.
        // Both must decode as the only old behavior (weighted / 0) so a P4 phone and
        // a P5 watch interoperate without a schemaVersion gate.
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
        // A pre-#74-fix wire: the exercise carries no `ssTracking`. Must decode as
        // "weighted" so a stale publisher and a fixed watch interoperate — the only
        // old behavior was rendering the partner with the main's tracking, and a
        // WEIGHTED main is the common case this default silently preserves.
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
