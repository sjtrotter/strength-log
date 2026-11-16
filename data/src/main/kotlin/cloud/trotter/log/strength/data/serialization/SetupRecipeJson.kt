package cloud.trotter.log.strength.data.serialization

import cloud.trotter.log.strength.data.db.entity.PlanEntity
import cloud.trotter.log.strength.domain.planner.EquipmentPreset
import cloud.trotter.log.strength.domain.planner.LiftExperience
import cloud.trotter.log.strength.domain.planner.PlanBranch
import cloud.trotter.log.strength.domain.planner.RecentNumber
import cloud.trotter.log.strength.domain.planner.SetupAnswers
import cloud.trotter.log.strength.domain.progression.Effort
import cloud.trotter.log.strength.domain.progression.LiftGoal
import cloud.trotter.log.strength.domain.progression.LoadSteps
import cloud.trotter.log.strength.domain.units.WeightUnit
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

@Serializable internal data class SetupRecipeDto(
    val v: Int, val branch: String, val goal: String, val days: Int, val minutes: Int, val experience: String,
    val olderAdult: Boolean = false, val mains: List<String>? = null,
    val recent: Map<String, RecentNumberDto> = emptyMap(), val excluded: List<String> = emptyList(),
    val balanceDays: Boolean = false,
)
@Serializable internal data class RecentNumberDto(val weightLb: Double, val reps: Int, val effort: String)

/** Recipe v2 (design §4.1): the setup answers a plan was made from, without equipment, steps and unit, which live
 *  in plan settings and the app's settings. Read by Reset day to plan; the live goal is `plan.goalJson`. */
object SetupRecipeJson {
    const val VERSION = 2

    /** PR 7 adds the other branches' answers; storing them now would lose those answers. */
    fun encode(answers: SetupAnswers): String {
        require(answers.branch == PlanBranch.LIFT)
        return SetJson.encode(SetupRecipeDto.serializer(), SetupRecipeDto(
            v = VERSION, branch = answers.branch.name, goal = answers.goal.name, days = answers.days,
            minutes = answers.minutes, experience = answers.experience.name, olderAdult = answers.olderAdult,
            mains = answers.mains, recent = answers.recent.mapValues { (_, number) ->
                RecentNumberDto(number.weightLb, number.reps, number.effort.name)
            }, excluded = answers.excluded.sorted(), balanceDays = answers.balanceDays,
        ))
    }

    fun isV2(json: String): Boolean = runCatching {
        val version = (Json.parseToJsonElement(json) as? JsonObject)?.get("v") as? JsonPrimitive
        version != null && !version.isString && version.intOrNull == VERSION
    }.getOrDefault(false)

    fun decode(json: String, settings: PlanSettings, unit: WeightUnit): SetupAnswers? {
        if (!isV2(json)) return null
        return runCatching {
            validate(json)
            val dto = SetJson.decode(SetupRecipeDto.serializer(), json)
            SetupAnswers(
                goal = LiftGoal.valueOf(dto.goal), days = dto.days, minutes = dto.minutes,
                equipment = settings.equipment ?: EquipmentPreset.FULL_GYM.equipment, unit = unit,
                steps = settings.loadSteps ?: LoadSteps.defaults(unit), experience = LiftExperience.valueOf(dto.experience),
                olderAdult = dto.olderAdult, mains = dto.mains,
                recent = dto.recent.mapValues { (_, number) ->
                    RecentNumber(number.weightLb, number.reps, Effort.valueOf(number.effort))
                }, excluded = dto.excluded.toSet(), branch = PlanBranch.valueOf(dto.branch), balanceDays = dto.balanceDays,
            )
        }.getOrNull()
    }

    fun validate(json: String) {
        require(isV2(json))
        val dto = SetJson.decode(SetupRecipeDto.serializer(), json)
        require(dto.v == VERSION && dto.branch == PlanBranch.LIFT.name)
        LiftGoal.valueOf(dto.goal)
        LiftExperience.valueOf(dto.experience)
        require(dto.days in 1..6 && dto.minutes in setOf(30, 45, 60, 75, 90))
        require(dto.mains.orEmpty().all { it.isNotBlank() } && dto.excluded.all { it.isNotBlank() })
        dto.recent.forEach { (id, number) ->
            require(id.isNotBlank() && number.weightLb.isFinite() && number.weightLb > 0.0 &&
                number.weightLb <= PlanRecipeJson.MAX_GOAL_LB && number.reps in 1..10)
            Effort.valueOf(number.effort)
        }
    }
}

/** Whether this plan's recipe is a v2 setup recipe, readable by this build or not. */
val PlanEntity.hasSetupRecipe: Boolean get() = recipeJson?.let(SetupRecipeJson::isV2) == true

/** The plan's setup answers; null without a v2 recipe or when this build cannot read it. */
fun PlanEntity.setupAnswers(unit: WeightUnit): SetupAnswers? =
    recipeJson?.let { SetupRecipeJson.decode(it, planSettings, unit) }
