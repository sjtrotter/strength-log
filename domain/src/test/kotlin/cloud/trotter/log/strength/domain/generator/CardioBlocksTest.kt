package cloud.trotter.log.strength.domain.generator

import cloud.trotter.log.strength.domain.model.CardioActivity
import cloud.trotter.log.strength.domain.model.CardioBlock
import cloud.trotter.log.strength.domain.model.CardioMode
import cloud.trotter.log.strength.domain.model.CardioPrefs
import cloud.trotter.log.strength.domain.model.CardioRole
import cloud.trotter.log.strength.domain.model.CardioSetSpec
import cloud.trotter.log.strength.domain.model.CardioSuggestion
import cloud.trotter.log.strength.domain.model.Measure
import cloud.trotter.log.strength.domain.model.ProgramDay
import cloud.trotter.log.strength.domain.model.ProgramDayKind
import cloud.trotter.log.strength.domain.model.effectiveCardio
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame

class CardioBlocksTest {
    private val easy = CardioSuggestion("Zone 2", "easy", hard = false)
    private val hard = CardioSuggestion("Intervals", "hard", hard = true)
    private val activity = CardioActivity.ROAD_RUN

    @Test
    fun `every activity retains its legacy mode`() {
        val expected = mapOf(
            CardioActivity.ROAD_RUN to CardioMode.OUTDOOR_RUN,
            CardioActivity.TRAIL_RUN to CardioMode.OUTDOOR_RUN,
            CardioActivity.TRACK_RUN to CardioMode.OUTDOOR_RUN,
            CardioActivity.TREADMILL_RUN to CardioMode.TREADMILL,
            CardioActivity.OUTDOOR_WALK to CardioMode.LOW_IMPACT,
            CardioActivity.TREADMILL_WALK to CardioMode.LOW_IMPACT,
            CardioActivity.OUTDOOR_RIDE to CardioMode.LOW_IMPACT,
            CardioActivity.STATIONARY_BIKE to CardioMode.LOW_IMPACT,
            CardioActivity.ELLIPTICAL to CardioMode.LOW_IMPACT,
            CardioActivity.ROWER to CardioMode.LOW_IMPACT,
        )
        assertEquals(CardioActivity.entries.toSet(), expected.keys)
        expected.forEach { (activity, mode) -> assertEquals(mode, CardioBlocks.legacyModeOf(activity)) }
    }

    @Test
    fun `easy is one work set of 1500 seconds for either fiveK preference`() {
        listOf(false, true).forEach { fiveK ->
            assertEquals(
                CardioBlock(activity, listOf(CardioSetSpec(CardioRole.WORK, Measure.Time(1500)))),
                CardioBlocks.fromLegacy(easy, fiveK, activity),
            )
        }
    }

    @Test
    fun `tempo is one hard work set of 1200 seconds`() {
        assertEquals(
            CardioBlock(activity, listOf(CardioSetSpec(CardioRole.WORK, Measure.Time(1200), hard = true))),
            CardioBlocks.fromLegacy(hard, fiveK = false, activity = activity),
        )
    }

    @Test
    fun `fiveK keeps warmup and all five hard recovery pairs including the last recovery`() {
        val block = CardioBlocks.fromLegacy(hard, fiveK = true, activity = activity)
        val expected = listOf(CardioSetSpec(CardioRole.WARMUP, Measure.Time(300))) + List(5) {
            listOf(
                CardioSetSpec(CardioRole.WORK, Measure.Time(120), hard = true),
                CardioSetSpec(CardioRole.RECOVERY, Measure.Time(120)),
            )
        }.flatten()
        assertEquals(CardioBlock(activity, expected), block)
        assertEquals(11, block.sets.size)
        assertEquals(1500, block.sets.sumOf { (it.end as Measure.Time).seconds })
        assertEquals(CardioRole.RECOVERY, block.sets.last().role)
    }

    @Test
    fun `legacy activities map explicitly and NONE has no activity`() {
        assertEquals(CardioActivity.ROAD_RUN, CardioBlocks.activityOf(CardioMode.OUTDOOR_RUN))
        assertEquals(CardioActivity.TREADMILL_RUN, CardioBlocks.activityOf(CardioMode.TREADMILL))
        assertEquals(CardioActivity.STATIONARY_BIKE, CardioBlocks.activityOf(CardioMode.LOW_IMPACT))
        assertNull(CardioBlocks.activityOf(CardioMode.NONE))
        CardioActivity.entries.forEach {
            assertEquals(it, CardioBlocks.fromLegacy(easy, false, it).activity)
        }
    }

    @Test
    fun `time and distance bounds apply to both end and aim`() {
        listOf(0, -1, 86_401, Int.MAX_VALUE).forEach { seconds ->
            assertFailsWith<IllegalArgumentException> { CardioSetSpec(CardioRole.WORK, Measure.Time(seconds)) }
            assertFailsWith<IllegalArgumentException> {
                CardioSetSpec(CardioRole.WORK, Measure.Distance(400), Measure.Time(seconds))
            }
        }
        listOf(0, -1, 200_001, Int.MAX_VALUE).forEach { metres ->
            assertFailsWith<IllegalArgumentException> { CardioSetSpec(CardioRole.WORK, Measure.Distance(metres)) }
            assertFailsWith<IllegalArgumentException> {
                CardioSetSpec(CardioRole.WORK, Measure.Time(105), Measure.Distance(metres))
            }
        }
        listOf(1, 86_400).forEach { seconds ->
            listOf(1, 200_000).forEach { metres ->
                val time = Measure.Time(seconds)
                val distance = Measure.Distance(metres)
                assertEquals(time, CardioSetSpec(CardioRole.WORK, time).end)
                assertEquals(distance, CardioSetSpec(CardioRole.WORK, distance).end)
                assertEquals(distance, CardioSetSpec(CardioRole.WORK, time, distance).aim)
                assertEquals(time, CardioSetSpec(CardioRole.WORK, distance, time).aim)
            }
        }
    }

    @Test
    fun `same kind aims and empty blocks are rejected`() {
        assertFailsWith<IllegalArgumentException> {
            CardioSetSpec(CardioRole.WORK, Measure.Time(120), Measure.Time(105))
        }
        assertFailsWith<IllegalArgumentException> {
            CardioSetSpec(CardioRole.WORK, Measure.Distance(400), Measure.Distance(500))
        }
        assertFailsWith<IllegalArgumentException> { CardioBlock(activity, emptyList()) }
    }

    @Test
    fun `stored block wins with or without legacy suggestion or recipe`() {
        val stored = CardioBlock(CardioActivity.ROWER, listOf(CardioSetSpec(CardioRole.COOLDOWN, Measure.Distance(500))))
        val authored = day(hard).copy(cardioBlock = stored, kind = ProgramDayKind.CARDIO)
        assertSame(stored, authored.effectiveCardio(CardioPrefs()))
        assertSame(stored, authored.effectiveCardio(null))
        assertSame(stored, authored.effectiveCardio(CardioPrefs(mode = CardioMode.NONE)))
        assertSame(stored, authored.copy(cardio = null).effectiveCardio(null))
    }

    @Test
    fun `legacy uses owning recipe fiveK and activity rather than snapshot facts`() {
        val suggestion = hard.copy(mode = CardioMode.OUTDOOR_RUN.name, fiveK = true)
        val prefs = CardioPrefs(mode = CardioMode.TREADMILL, fiveKGoal = false)
        assertEquals(
            CardioBlocks.fromLegacy(suggestion, false, CardioActivity.TREADMILL_RUN),
            day(suggestion).effectiveCardio(prefs),
        )
        assertEquals(
            CardioBlocks.fromLegacy(hard, true, CardioActivity.STATIONARY_BIKE),
            day(hard).effectiveCardio(CardioPrefs(mode = CardioMode.LOW_IMPACT, fiveKGoal = true)),
        )
    }

    @Test
    fun `absent cardio or missing or disabled recipe has no effective legacy block`() {
        assertNull(day(null).effectiveCardio(CardioPrefs()))
        assertNull(day(hard).effectiveCardio(null))
        assertNull(day(hard).effectiveCardio(CardioPrefs(mode = CardioMode.NONE)))
    }

    private fun day(cardio: CardioSuggestion?) = ProgramDay("A", "Run", "", emptyList(), cardio)
}
