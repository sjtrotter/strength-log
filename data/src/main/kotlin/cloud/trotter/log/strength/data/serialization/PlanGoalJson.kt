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
)

@Serializable
internal data class ShuttleAttemptDto(val completedAt: Long, val shuttles: Int)

object PlanGoalJson {
    fun encode(goal: PlanGoal): String = SetJson.encode(PlanGoalDto.serializer(), when (goal) {
        is PlanGoal.Timed -> PlanGoalDto("timed", activity = goal.activity.name, metres = goal.metres,
            underSeconds = goal.underSeconds, byEpochDay = goal.byEpochDay)
        is PlanGoal.Shuttle -> PlanGoalDto("shuttle", protocol = goal.protocol, targetShuttles = goal.targetShuttles,
            attempts = goal.attempts.map { ShuttleAttemptDto(it.completedAt, it.shuttles) }, byEpochDay = goal.byEpochDay)
    })

    fun decode(json: String?): PlanGoal? = json?.let { runCatching { parse(it) }.getOrNull() }

    fun validate(json: String) { parse(json) }

    private fun parse(json: String): PlanGoal {
        val dto = SetJson.decode(PlanGoalDto.serializer(), json)
        require(PlanGoalDate.isValid(dto.byEpochDay))
        return when (dto.type) {
            "timed" -> PlanGoal.Timed(CardioActivity.valueOf(requireNotNull(dto.activity)),
                requireNotNull(dto.metres), requireNotNull(dto.underSeconds), dto.byEpochDay)
            "shuttle" -> PlanGoal.Shuttle(requireNotNull(dto.protocol), requireNotNull(dto.targetShuttles),
                dto.attempts.map { ShuttleAttempt(it.completedAt, it.shuttles) }, dto.byEpochDay)
            else -> throw IllegalArgumentException("unknown plan goal type '${dto.type}'")
        }
    }
}
