package cloud.trotter.log.strength.data.serialization

import cloud.trotter.log.strength.domain.progression.EffortScale
import cloud.trotter.log.strength.domain.progression.NextTimeMode
import cloud.trotter.log.strength.domain.progression.RepTarget
import cloud.trotter.log.strength.domain.progression.Scheme
import cloud.trotter.log.strength.domain.progression.SlotRole
import cloud.trotter.log.strength.domain.progression.SlotRule
import cloud.trotter.log.strength.domain.progression.SlotState
import cloud.trotter.log.strength.domain.standards.RestPolicy
import kotlinx.serialization.Serializable

@Serializable internal data class SlotRuleDto(
    val role: String, val target: RepTargetDto, val rir: Int, val incrementPct: Double, val everyExposures: Int,
    val dropPct: Double, val scheme: SchemeDto, val restSeconds: Int? = null, val nextTime: String? = null,
    val state: SlotStateDto = SlotStateDto(),
) {
    fun toDomain(): SlotRule {
        val parsedRole = SlotRole.valueOf(role)
        val parsedNextTime = nextTime?.let(NextTimeMode::valueOf)
        fun rep(value: Int?): Int = requireNotNull(value).also { require(it in 1..999) }
        require(listOfNotNull(target.reps, target.lo, target.hi, target.current).all { it in 1..999 })
        val parsedTarget = when (target.type) {
            "fixed" -> RepTarget.Fixed(rep(target.reps))
            "range" -> RepTarget.Range(rep(target.lo), rep(target.hi), rep(target.current))
            else -> throw IllegalArgumentException("unknown rep target '${target.type}'")
        }
        require(rir in 0..EffortScale.MAX_REPS_LEFT)
        require(incrementPct.isFinite() && incrementPct in 0.0..0.10)
        require(everyExposures in 1..3)
        require(dropPct.isFinite() && dropPct in 0.0..0.10)
        require(scheme.ramps.size <= 10)
        val ramps = scheme.ramps.map {
            require(it.pct.isFinite() && it.pct > 0.0 && it.pct < 1.0)
            Scheme.Ramp(it.pct, rep(it.reps))
        }
        require(scheme.backoffs in 0..10)
        require(scheme.backoffPct.isFinite() && scheme.backoffPct > 0.0 && scheme.backoffPct <= 1.0)
        val parsedScheme = Scheme(ramps, scheme.backoffs, scheme.backoffPct, rep(scheme.backoffReps))
        require(restSeconds == null || restSeconds in 0..RestPolicy.MAX_REST_SECONDS)
        require(listOf(state.successStreak, state.repMissStreak, state.effortMissStreak, state.exposures)
            .all { it in 0..9_999 })
        require(state.resetEpochDays.size <= 2)
        require(state.resetEpochDays.zipWithNext().all { (a, b) -> a <= b })
        return SlotRule(parsedRole, parsedTarget, rir, incrementPct, everyExposures, dropPct, parsedScheme,
            restSeconds, parsedNextTime, SlotState(state.calibrating, state.successStreak, state.repMissStreak,
                state.effortMissStreak, state.resetEpochDays, state.exposures))
    }
}

/** "fixed" carries [reps]; "range" carries [lo], [hi] and [current]. */
@Serializable internal data class RepTargetDto(val type: String, val reps: Int? = null, val lo: Int? = null,
    val hi: Int? = null, val current: Int? = null)
@Serializable internal data class SchemeDto(val ramps: List<RampDto>, val backoffs: Int, val backoffPct: Double,
    val backoffReps: Int)
@Serializable internal data class RampDto(val pct: Double, val reps: Int)
@Serializable internal data class SlotStateDto(val calibrating: Boolean = false, val successStreak: Int = 0,
    val repMissStreak: Int = 0, val effortMissStreak: Int = 0, val resetEpochDays: List<Long> = emptyList(),
    val exposures: Int = 0)

/** Persistence shape of a [SlotRule] (`program_exercise.ruleJson`). */
object SlotRuleJson {
    fun encode(rule: SlotRule): String {
        val target = when (val t = rule.target) {
            is RepTarget.Fixed -> RepTargetDto("fixed", reps = t.reps)
            is RepTarget.Range -> RepTargetDto("range", lo = t.lo, hi = t.hi, current = t.current)
        }
        val state = rule.state
        val dto = SlotRuleDto(rule.role.name, target, rule.rir, rule.incrementPct, rule.everyExposures,
            rule.dropPct, SchemeDto(rule.scheme.ramps.map { RampDto(it.pct, it.reps) }, rule.scheme.backoffs,
                rule.scheme.backoffPct, rule.scheme.backoffReps), rule.restSeconds, rule.nextTime?.name,
            SlotStateDto(state.calibrating, state.successStreak, state.repMissStreak, state.effortMissStreak,
                state.resetEpochDays, state.exposures))
        dto.toDomain()
        return SetJson.encode(SlotRuleDto.serializer(), dto)
    }

    fun decode(json: String?): SlotRule? = json?.let { runCatching { read(it) }.getOrNull() }
    fun validate(json: String) { read(json) }
    private fun read(json: String): SlotRule = SetJson.decode(SlotRuleDto.serializer(), json).toDomain()
}
