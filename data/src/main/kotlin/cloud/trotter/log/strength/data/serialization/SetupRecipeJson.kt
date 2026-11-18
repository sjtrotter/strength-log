package cloud.trotter.log.strength.data.serialization

import cloud.trotter.log.strength.data.db.entity.PlanEntity
import cloud.trotter.log.strength.domain.model.CardioActivity
import cloud.trotter.log.strength.domain.model.Measure
import cloud.trotter.log.strength.domain.planner.BothAnswers
import cloud.trotter.log.strength.domain.planner.FitnessTest
import cloud.trotter.log.strength.domain.planner.RecentRace
import cloud.trotter.log.strength.domain.planner.RunAnswers
import cloud.trotter.log.strength.domain.planner.RunEvent
import cloud.trotter.log.strength.domain.planner.RunExperience
import cloud.trotter.log.strength.domain.planner.RunPhase
import cloud.trotter.log.strength.domain.planner.RunGoal
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
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull

@Serializable internal data class SetupRecipeDto(
    val v: Int, val branch: String, val goal: String, val days: Int, val minutes: Int, val experience: String,
    val olderAdult: Boolean = false, val mains: List<String>? = null,
    val recent: Map<String, RecentNumberDto> = emptyMap(), val excluded: List<String> = emptyList(),
    val balanceDays: Boolean = false, val run: RunRecipeDto? = null, val both: BothRecipeDto? = null,
    val runPhase: String? = null,
)
@Serializable internal data class RecentNumberDto(val weightLb: Double, val reps: Int, val effort: String)

@Serializable internal data class RunRecipeDto(
    val goal: String, val experience: String, val event: String? = null, val test: String = "TWO_MILE",
    val dateEpochDay: Long? = null, val goalSeconds: Int? = null, val testScore: Int? = null,
    val recentMetres: Int? = null, val recentSeconds: Int? = null,
    val longestSeconds: Int? = null, val longestMetres: Int? = null, val marathonBestSeconds: Int? = null,
    val activity: String = "ROAD_RUN", val runningInjury: Boolean = false, val strengthBlocks: Int = 2,
) {
    fun answers(): RunAnswers {
        require((recentMetres == null) == (recentSeconds == null))
        require(longestSeconds == null || longestMetres == null)
        return RunAnswers(
            goal = RunGoal.valueOf(goal), experience = RunExperience.valueOf(experience),
            event = event?.let(RunEvent::valueOf), test = FitnessTest.valueOf(test), dateEpochDay = dateEpochDay,
            goalSeconds = goalSeconds, testScore = testScore,
            recent = recentMetres?.let { RecentRace(it, requireNotNull(recentSeconds)) },
            longest = longestSeconds?.let { Measure.Time(it) } ?: longestMetres?.let { Measure.Distance(it) },
            marathonBestSeconds = marathonBestSeconds, activity = CardioActivity.valueOf(activity),
            runningInjury = runningInjury, strengthBlocks = strengthBlocks,
        )
    }
}
@Serializable internal data class BothRecipeDto(val cardioFirst: Boolean = false, val together: String? = null) {
    fun answers(): BothAnswers = BothAnswers(cardioFirst, together?.let(BothAnswers.Together::valueOf))
}

/** Recipe v2 (design §4.1): the setup answers a plan was made from, without equipment, steps and unit, which live
 *  in plan settings and the app's settings. Read by Reset day to plan; the live goal is `plan.goalJson`. */
object SetupRecipeJson {
    const val VERSION = 2

    /** PR 7 adds sprint answers; storing them now would lose them. A null [phase] stores none: the plan has
     *  nothing to re-plan (a goal without phases, a runner past them, or a Both layout with no run session,
     *  revision 48). */
    fun encode(answers: SetupAnswers, phase: RunPhase? = null): String {
        require(answers.branch != PlanBranch.SPRINT && !(answers.branch == PlanBranch.BOTH && answers.both.sprint))
        val phases = if (answers.branch == PlanBranch.RUN || answers.branch == PlanBranch.BOTH) answers.run.phases else emptyList()
        require(phase == null || phase in phases)
        return SetJson.encode(SetupRecipeDto.serializer(), SetupRecipeDto(
            v = VERSION, branch = answers.branch.name, goal = answers.goal.name, days = answers.days,
            minutes = answers.minutes, experience = answers.experience.name, olderAdult = answers.olderAdult,
            mains = answers.mains, recent = answers.recent.mapValues { (_, number) ->
                RecentNumberDto(number.weightLb, number.reps, number.effort.name)
            }, excluded = answers.excluded.sorted(), balanceDays = answers.balanceDays,
            run = if (answers.branch == PlanBranch.RUN || answers.branch == PlanBranch.BOTH) answers.run.let { run ->
                RunRecipeDto(run.goal.name, run.experience.name, run.event?.name, run.test.name, run.dateEpochDay,
                    run.goalSeconds, run.testScore, run.recent?.metres, run.recent?.seconds,
                    (run.longest as? Measure.Time)?.seconds, (run.longest as? Measure.Distance)?.metres,
                    run.marathonBestSeconds, run.activity.name, run.runningInjury, run.strengthBlocks)
            } else null,
            both = if (answers.branch == PlanBranch.BOTH) BothRecipeDto(answers.both.cardioFirst, answers.both.together?.name) else null,
            runPhase = phase?.name,
        ))
    }

    private fun v2Object(json: String): JsonObject? = runCatching {
        (Json.parseToJsonElement(json) as? JsonObject)?.takeIf {
            val version = it["v"] as? JsonPrimitive
            version != null && !version.isString && version.intOrNull == VERSION
        }
    }.getOrNull()

    fun isV2(json: String): Boolean = v2Object(json) != null

    /** The recipe's 65+ answer: null unless [json] is a v2 recipe; false when the field is absent or unreadable. */
    fun olderAdult(json: String): Boolean? = v2Object(json)?.let {
        (it["olderAdult"] as? JsonPrimitive)?.booleanOrNull ?: false
    }

    /** The recipe's lifting experience: null unless [json] is a v2 recipe whose field names a [LiftExperience]. */
    fun experience(json: String): LiftExperience? = v2Object(json)?.let {
        val name = (it["experience"] as? JsonPrimitive)?.content
        LiftExperience.entries.firstOrNull { experience -> experience.name == name }
    }

    /** Null unless a valid v2 recipe carries a phase on the runner's path. */
    fun phase(json: String): RunPhase? = runCatching {
        validate(json)
        SetJson.decode(SetupRecipeDto.serializer(), json).runPhase?.let(RunPhase::valueOf)
    }.getOrNull()

    /** Preserve every other field, including fields a newer writer may have added. */
    fun withPhase(json: String, phase: RunPhase?): String {
        validate(json)
        val fields = requireNotNull(v2Object(json)) - "runPhase"
        val updated = JsonObject(if (phase == null) fields else fields + ("runPhase" to JsonPrimitive(phase.name))).toString()
        validate(updated)
        return updated
    }

    fun decode(json: String, settings: PlanSettings, unit: WeightUnit): SetupAnswers? {
        if (!isV2(json)) return null
        return runCatching {
            validate(json)
            val dto = SetJson.decode(SetupRecipeDto.serializer(), json)
            SetupAnswers(
                goal = LiftGoal.valueOf(dto.goal), days = dto.days, minutes = dto.minutes,
                equipment = settings.equipmentOrAll, unit = unit,
                steps = settings.loadSteps ?: LoadSteps.defaults(unit), experience = LiftExperience.valueOf(dto.experience),
                olderAdult = dto.olderAdult, mains = dto.mains,
                recent = dto.recent.mapValues { (_, number) ->
                    RecentNumber(number.weightLb, number.reps, Effort.valueOf(number.effort))
                }, excluded = dto.excluded.toSet(), branch = PlanBranch.valueOf(dto.branch), balanceDays = dto.balanceDays,
                run = dto.run?.answers() ?: RunAnswers(), both = dto.both?.answers() ?: BothAnswers(),
            )
        }.getOrNull()
    }

    fun validate(json: String) {
        require(isV2(json))
        val dto = SetJson.decode(SetupRecipeDto.serializer(), json)
        val branch = PlanBranch.valueOf(dto.branch)
        require(dto.v == VERSION && branch != PlanBranch.SPRINT)
        require((dto.run != null) == (branch == PlanBranch.RUN || branch == PlanBranch.BOTH))
        require((dto.both != null) == (branch == PlanBranch.BOTH))
        val answers = dto.run?.answers()
        val phases = answers?.let { it.goal.phases.ifEmpty { it.phases } }.orEmpty()
        require(dto.runPhase == null || RunPhase.valueOf(dto.runPhase) in phases)
        dto.both?.answers()
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
