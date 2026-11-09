package cloud.trotter.log.strength.data.serialization

import cloud.trotter.log.strength.domain.model.CardioActivity
import cloud.trotter.log.strength.domain.model.PlanGoal
import cloud.trotter.log.strength.domain.model.PlanGoalDate
import cloud.trotter.log.strength.domain.model.ShuttleAttempt
import kotlinx.serialization.Serializable

@Serializable
internal data class PlanGoalDto(
    val type: String,
    val activity: String? = null,
    val metres: Int? = null,
    val underSeconds: Int? = null,
    val protocol: String? = null,
    val targetShuttles: Int? = null,
    val attempts: List<ShuttleAttemptDto> = emptyList(),
    val byEpochDay: Long? = null,
    val targetCentis: Int? = null,
    val sprintAttempts: List<SprintAttemptDto> = emptyList(),
)

@Serializable
internal data class ShuttleAttemptDto(val completedAt: Long, val shuttles: Int)

@Serializable
internal data class SprintAttemptDto(val completedAt: Long, val centis: Int, val timingMethod: String, val startProtocol: String)

object PlanGoalJson {
    fun encode(goal: PlanGoal): String = SetJson.encode(PlanGoalDto.serializer(), when (goal) {
        is PlanGoal.Timed -> PlanGoalDto("timed", activity = goal.activity.name, metres = goal.metres,
            underSeconds = goal.underSeconds, byEpochDay = goal.byEpochDay)
        is PlanGoal.Shuttle -> PlanGoalDto("shuttle", protocol = goal.protocol, targetShuttles = goal.targetShuttles,
            attempts = goal.attempts.map { ShuttleAttemptDto(it.completedAt, it.shuttles) }, byEpochDay = goal.byEpochDay)
    })

    /** Null for a goal this build cannot show: an unreadable one, a timed goal with no time, or a sprint goal;
     *  the stored JSON is kept as is. */
    fun decode(json: String?): PlanGoal? = json?.let { runCatching { parse(it) }.getOrNull() }

    fun validate(json: String) { parse(json) }

    private fun parse(json: String): PlanGoal? {
        val dto = SetJson.decode(PlanGoalDto.serializer(), json)
        require(PlanGoalDate.isValid(dto.byEpochDay))
        return when (dto.type) {
            "timed" -> {
                val activity = CardioActivity.valueOf(requireNotNull(dto.activity))
                val metres = requireNotNull(dto.metres).also { require(it in 1..200_000) }
                dto.underSeconds?.let {
                    require(it in 1..86_400)
                    PlanGoal.Timed(activity, metres, it, dto.byEpochDay)
                }
            }
            "sprint" -> {
                require(requireNotNull(dto.metres) in 1..1_000)
                require(dto.targetCentis == null || dto.targetCentis in 1..CardioTiming.MAX_CENTIS)
                dto.sprintAttempts.forEach {
                    require(it.centis in 1..CardioTiming.MAX_CENTIS)
                    require(CardioTiming.isName(it.timingMethod) && CardioTiming.isName(it.startProtocol))
                }
                null
            }
            "shuttle" -> PlanGoal.Shuttle(requireNotNull(dto.protocol), requireNotNull(dto.targetShuttles),
                dto.attempts.map { ShuttleAttempt(it.completedAt, it.shuttles) }, dto.byEpochDay)
            else -> throw IllegalArgumentException("unknown plan goal type '${dto.type}'")
        }
    }
}
