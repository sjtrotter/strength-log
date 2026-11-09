package cloud.trotter.log.strength.data

import cloud.trotter.log.strength.data.serialization.RampDto
import cloud.trotter.log.strength.data.serialization.RepTargetDto
import cloud.trotter.log.strength.data.serialization.SetJson
import cloud.trotter.log.strength.data.serialization.SlotRuleDto
import cloud.trotter.log.strength.data.serialization.SlotRuleJson
import cloud.trotter.log.strength.data.serialization.SlotStateDto
import cloud.trotter.log.strength.domain.progression.NextTimeMode
import cloud.trotter.log.strength.domain.progression.RepTarget
import cloud.trotter.log.strength.domain.progression.Scheme
import cloud.trotter.log.strength.domain.progression.SlotRole
import cloud.trotter.log.strength.domain.progression.SlotRule
import cloud.trotter.log.strength.domain.progression.SlotState
import cloud.trotter.log.strength.domain.standards.RestPolicy
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SlotRuleJsonTest {
    private val squat = SlotRule(SlotRole.MAIN, RepTarget.Fixed(5), 2, 0.025, 1, 0.05, Scheme.PROTOTYPE)
    private val dto = SetJson.decode(SlotRuleDto.serializer(), SlotRuleJson.encode(squat))

    @Test fun `case 28 and a range with full state round trip exactly`() {
        val range = squat.copy(target = RepTarget.Range(8, 12, 10), scheme = Scheme.STRENGTH,
            restSeconds = 150, nextTime = NextTimeMode.ASK,
            state = SlotState(true, 2, 3, 4, listOf(20000, 20001), 9))
        for (rule in listOf(squat, range)) {
            val json = SlotRuleJson.encode(rule)
            SlotRuleJson.validate(json)
            assertEquals(rule, SlotRuleJson.decode(json))
        }
        val json = SlotRuleJson.encode(squat)
        for (key in listOf("state", "restSeconds", "nextTime")) assertTrue(key !in json)
        assertEquals(squat, SlotRuleJson.decode(json.dropLast(1) + ",\"future\":true}"))
        assertNull(SlotRuleJson.decode(null))
        assertNull(SlotRuleJson.decode("junk"))
    }

    @Test fun `every bound accepts its edge and rejects the first outside`() {
        fun ramps(count: Int) = dto.scheme.copy(ramps = List(count) { RampDto(0.5, 5) })
        fun ramp(pct: Double = 0.5, reps: Int = 5) = dto.scheme.copy(ramps = listOf(RampDto(pct, reps)))
        val valid = listOf(
            dto.copy(rir = 0), dto.copy(rir = 5),
            dto.copy(incrementPct = 0.0), dto.copy(incrementPct = 0.10),
            dto.copy(dropPct = 0.0), dto.copy(dropPct = 0.10),
            dto.copy(everyExposures = 1), dto.copy(everyExposures = 3),
            dto.copy(target = RepTargetDto("fixed", reps = 1)), dto.copy(target = RepTargetDto("fixed", reps = 999)),
            dto.copy(target = RepTargetDto("range", lo = 1, hi = 999, current = 1)),
            dto.copy(target = RepTargetDto("range", lo = 1, hi = 999, current = 999)),
            dto.copy(scheme = ramps(0)), dto.copy(scheme = ramps(10)),
            dto.copy(scheme = ramp(pct = Double.MIN_VALUE)), dto.copy(scheme = ramp(pct = Math.nextDown(1.0))),
            dto.copy(scheme = ramp(reps = 1)), dto.copy(scheme = ramp(reps = 999)),
            dto.copy(scheme = dto.scheme.copy(backoffs = 0)), dto.copy(scheme = dto.scheme.copy(backoffs = 10)),
            dto.copy(scheme = dto.scheme.copy(backoffPct = Double.MIN_VALUE)),
            dto.copy(scheme = dto.scheme.copy(backoffPct = 1.0)),
            dto.copy(scheme = dto.scheme.copy(backoffReps = 1)), dto.copy(scheme = dto.scheme.copy(backoffReps = 999)),
            dto.copy(restSeconds = 0), dto.copy(restSeconds = RestPolicy.MAX_REST_SECONDS),
            dto.copy(state = SlotStateDto()), dto.copy(state = SlotStateDto(true, 9999, 9999, 9999, listOf(1, 1), 9999)),
            dto.copy(state = SlotStateDto(resetEpochDays = listOf(Long.MIN_VALUE, Long.MAX_VALUE))),
        )
        for (value in valid) {
            val json = SetJson.encode(SlotRuleDto.serializer(), value)
            SlotRuleJson.validate(json)
            val rule = requireNotNull(SlotRuleJson.decode(json))
            assertEquals(rule, SlotRuleJson.decode(SlotRuleJson.encode(rule)))
        }
        val invalid = listOf(
            dto.copy(role = "BOSS"), dto.copy(nextTime = "NEVER"), dto.copy(target = RepTargetDto("unknown")),
            dto.copy(target = RepTargetDto("fixed")), dto.copy(target = RepTargetDto("fixed", reps = 0)),
            dto.copy(target = RepTargetDto("fixed", reps = 1000)),
            dto.copy(target = RepTargetDto("fixed", reps = 5, lo = 0)),
            dto.copy(target = RepTargetDto("range", reps = 0, lo = 1, hi = 10, current = 5)),
            dto.copy(rir = -1), dto.copy(rir = 6), dto.copy(incrementPct = -0.01), dto.copy(incrementPct = 0.100001),
            dto.copy(dropPct = -0.01), dto.copy(dropPct = 0.100001), dto.copy(everyExposures = 0), dto.copy(everyExposures = 4),
            dto.copy(scheme = ramps(11)), dto.copy(scheme = ramp(pct = 0.0)), dto.copy(scheme = ramp(pct = 1.0)),
            dto.copy(scheme = ramp(reps = 0)), dto.copy(scheme = ramp(reps = 1000)),
            dto.copy(scheme = dto.scheme.copy(backoffs = -1)), dto.copy(scheme = dto.scheme.copy(backoffs = 11)),
            dto.copy(scheme = dto.scheme.copy(backoffPct = 0.0)), dto.copy(scheme = dto.scheme.copy(backoffPct = 1.000001)),
            dto.copy(scheme = dto.scheme.copy(backoffReps = 0)), dto.copy(scheme = dto.scheme.copy(backoffReps = 1000)),
            dto.copy(restSeconds = -1), dto.copy(restSeconds = RestPolicy.MAX_REST_SECONDS + 1),
            dto.copy(state = SlotStateDto(resetEpochDays = listOf(1, 2, 3))),
            dto.copy(state = SlotStateDto(resetEpochDays = listOf(2, 1))),
        ) + listOf(0, 1000, null).flatMap { value ->
            listOf(RepTargetDto("range", lo = value, hi = 999, current = 5),
                RepTargetDto("range", lo = 1, hi = value, current = 5),
                RepTargetDto("range", lo = 1, hi = 999, current = value)).map { dto.copy(target = it) }
        } + listOf(RepTargetDto("range", lo = 8, hi = 7, current = 8),
            RepTargetDto("range", lo = 8, hi = 12, current = 7),
            RepTargetDto("range", lo = 8, hi = 12, current = 13)).map { dto.copy(target = it) } +
            listOf(-1, 10000).flatMap { value ->
                listOf(SlotStateDto(successStreak = value), SlotStateDto(repMissStreak = value),
                    SlotStateDto(effortMissStreak = value), SlotStateDto(exposures = value)).map { dto.copy(state = it) }
            }
        for (value in invalid) {
            val json = SetJson.encode(SlotRuleDto.serializer(), value)
            assertNull(SlotRuleJson.decode(json), json)
            assertFailsWith<IllegalArgumentException>(json) { SlotRuleJson.validate(json) }
        }
    }

    @Test fun `encode validates domain values before writing`() {
        val invalid = listOf(
            squat.copy(target = RepTarget.Fixed(0)), squat.copy(target = RepTarget.Fixed(1000)),
            squat.copy(rir = 6), squat.copy(incrementPct = 0.2), squat.copy(everyExposures = 4), squat.copy(dropPct = -0.01),
            squat.copy(restSeconds = -1), squat.copy(state = SlotState(successStreak = -1)),
            squat.copy(scheme = Scheme(emptyList(), 11, 1.0, 5)),
        )
        for (rule in invalid) assertFailsWith<IllegalArgumentException> { SlotRuleJson.encode(rule) }
    }

    @Test fun `non-finite percentages reject in storage and on encode`() {
        for (number in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            for (rule in listOf(squat.copy(incrementPct = number), squat.copy(dropPct = number),
                squat.copy(scheme = squat.scheme.copy(backoffPct = number)),
                squat.copy(scheme = squat.scheme.copy(ramps = listOf(Scheme.Ramp(number, 5)))))) {
                assertFailsWith<IllegalArgumentException> { SlotRuleJson.encode(rule) }
            }
        }
        for (key in listOf("incrementPct", "dropPct", "pct", "backoffPct")) {
            val json = SlotRuleJson.encode(squat).replace(Regex("\"$key\":[0-9.]+"), "\"$key\":1e999")
            assertNull(SlotRuleJson.decode(json))
            assertFailsWith<IllegalArgumentException> { SlotRuleJson.validate(json) }
        }
    }
}
