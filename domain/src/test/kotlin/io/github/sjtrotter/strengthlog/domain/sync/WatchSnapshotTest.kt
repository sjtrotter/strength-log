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
    fun `a superset partner's rows align by index with the main track`() {
        val press = sample.day.exercises[1]
        assertEquals(press.sets.size, press.ssSets.size)
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
