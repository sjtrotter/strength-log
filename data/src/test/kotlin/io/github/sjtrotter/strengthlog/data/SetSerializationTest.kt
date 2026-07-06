package io.github.sjtrotter.strengthlog.data

import io.github.sjtrotter.strengthlog.data.serialization.CardioDto
import io.github.sjtrotter.strengthlog.data.serialization.SetJson
import io.github.sjtrotter.strengthlog.domain.model.CardioSuggestion
import io.github.sjtrotter.strengthlog.domain.model.LoggedSet
import io.github.sjtrotter.strengthlog.domain.model.SetKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Round-trip tests for everything `:data` stores in a text column (spec §11.2). */
class SetSerializationTest {

    @Test
    fun `a seeded main-lift sequence round-trips exactly`() {
        // The pinned squat sequence (spec §11): ramps, top, back-off, mixed reps.
        val sets = listOf(
            LoggedSet(130.0, 5, SetKind.RAMP),
            LoggedSet(165.0, 5, SetKind.RAMP),
            LoggedSet(190.0, 5, SetKind.RAMP),
            LoggedSet(210.0, 3, SetKind.RAMP),
            LoggedSet(235.0, 5, SetKind.TOP, done = true),
            LoggedSet(175.0, 8, SetKind.BACKOFF),
        )
        assertEquals(sets, SetJson.decodeSets(SetJson.encodeSets(sets)))
    }

    @Test
    fun `every SetKind and both done states survive`() {
        val sets = SetKind.entries.mapIndexed { i, kind ->
            LoggedSet(weightLb = 12.5 * (i + 1), reps = i + 1, kind = kind, done = i % 2 == 0)
        }
        assertEquals(sets, SetJson.decodeSets(SetJson.encodeSets(sets)))
    }

    @Test
    fun `fractional and zero weights survive`() {
        val sets = listOf(
            LoggedSet(0.0, 12, SetKind.WORK),      // bodyweight
            LoggedSet(12.5, 15, SetKind.WORK),     // light isolation half-step
            LoggedSet(2.5, 20, SetKind.EXTRA, done = true),
        )
        assertEquals(sets, SetJson.decodeSets(SetJson.encodeSets(sets)))
    }

    @Test
    fun `empty list round-trips to empty`() {
        assertTrue(SetJson.decodeSets(SetJson.encodeSets(emptyList())).isEmpty())
    }

    @Test
    fun `an unknown field written by a newer build is ignored on read`() {
        val forward = """[{"weightLb":100.0,"reps":5,"kind":"TOP","done":true,"rpe":8.5}]"""
        val decoded = SetJson.decodeSets(forward)
        assertEquals(listOf(LoggedSet(100.0, 5, SetKind.TOP, done = true)), decoded)
    }

    @Test
    fun `cardio suggestion round-trips through the cardioJson column`() {
        val hard = CardioSuggestion("Intervals", "5 min easy, then 4-6 x 2 min hard / 2 min easy", hard = true)
        assertEquals(hard, CardioDto.decode(CardioDto.encode(hard)))

        val easy = CardioSuggestion("Zone 2", "20-30 min conversational", hard = false)
        assertEquals(easy, CardioDto.decode(CardioDto.encode(easy)))

        // A day with no finisher stores and reads back as null.
        assertEquals(null, CardioDto.decode(CardioDto.encode(null)))
    }
}
