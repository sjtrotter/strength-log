package cloud.trotter.log.strength.data.serialization

import cloud.trotter.log.strength.domain.model.CardioActivity
import cloud.trotter.log.strength.domain.model.PlanGoal
import cloud.trotter.log.strength.domain.model.PlanGoalDate
import cloud.trotter.log.strength.domain.model.ShuttleAttempt
import cloud.trotter.log.strength.domain.model.SprintAttempt
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
        is PlanGoal.Sprint -> PlanGoalDto("sprint", metres = goal.metres, targetCentis = goal.targetCentis,
            sprintAttempts = goal.attempts.map { SprintAttemptDto(it.completedAt, it.time.centis,
                it.time.timingMethod.name, it.time.startProtocol.name) }, byEpochDay = goal.byEpochDay)
    })

    /** Null for a goal this build cannot show: an unreadable one or a timed goal with no time;
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
            "sprint" -> PlanGoal.Sprint(requireNotNull(dto.metres), dto.targetCentis,
                dto.sprintAttempts.map { SprintAttempt(it.completedAt,
                    requireNotNull(CardioTiming.of(it.centis, it.timingMethod, it.startProtocol))) }, dto.byEpochDay)
            "shuttle" -> PlanGoal.Shuttle(requireNotNull(dto.protocol), requireNotNull(dto.targetShuttles),
                dto.attempts.map { ShuttleAttempt(it.completedAt, it.shuttles) }, dto.byEpochDay)
            else -> throw IllegalArgumentException("unknown plan goal type '${dto.type}'")
        }
    }
}
