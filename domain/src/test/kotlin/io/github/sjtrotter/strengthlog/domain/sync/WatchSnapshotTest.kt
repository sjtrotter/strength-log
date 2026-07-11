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
