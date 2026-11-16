package cloud.trotter.log.strength.data.serialization

import cloud.trotter.log.strength.domain.model.Equipment
import cloud.trotter.log.strength.domain.progression.LiftGoal
import cloud.trotter.log.strength.domain.progression.LoadSteps
import kotlinx.serialization.Serializable
import cloud.trotter.log.strength.data.db.entity.PlanEntity

/** A plan's own settings (`plan.settingsJson`, design §4.1). An absent column or field means the default: Strength,
 *  and the steps of the lifter's current unit ([LoadSteps.defaults]). Null equipment reads as every piece of
 *  equipment. [goalsLb] holds typed lift goals in lb for plans without a v1 recipe (decision 4). */
data class PlanSettings(
    val defaultsFor: LiftGoal = LiftGoal.STRENGTH,
    val loadSteps: LoadSteps? = null,
    val runCap: Boolean = true,
    val equipment: Set<Equipment>? = null,
    val goalsLb: Map<String, Double> = emptyMap(),
) {
    val equipmentOrAll: Set<Equipment> get() = equipment ?: Equipment.entries.toSet()
}

@Serializable internal data class PlanSettingsDto(
    val defaultsFor: String? = null,
    val loadSteps: LoadStepsDto? = null,
    val runCap: Boolean? = null,
    val equipment: List<String>? = null,
    val goalsLb: Map<String, Double>? = null,
) {
    fun toDomain(strict: Boolean = false): PlanSettings = PlanSettings(
        defaultsFor?.let(LiftGoal::valueOf) ?: LiftGoal.STRENGTH,
        loadSteps?.let {
            require(listOf(it.barLb, it.dumbbellLb, it.stackLb).all { step ->
                step.isFinite() && step > 0.0 && step <= 100.0
            })
            LoadSteps(it.barLb, it.dumbbellLb, it.stackLb)
        },
        runCap ?: true,
        equipment?.mapNotNull { name ->
            Equipment.entries.firstOrNull { it.name == name }.also { if (strict) requireNotNull(it) }
        }?.toSet(),
        goalsLb.orEmpty().filter { (id, goal) ->
            val valid = id.isNotBlank() && goal.isFinite() && goal > 0.0 && goal <= PlanRecipeJson.MAX_GOAL_LB
            if (strict) require(valid)
            valid
        },
    )
}
@Serializable internal data class LoadStepsDto(val barLb: Double, val dumbbellLb: Double, val stackLb: Double)

object PlanSettingsJson {
    fun encode(settings: PlanSettings): String {
        val dto = PlanSettingsDto(settings.defaultsFor.name, settings.loadSteps?.let {
            LoadStepsDto(it.barLb, it.dumbbellLb, it.stackLb)
        }, settings.runCap, settings.equipment?.let { equipment ->
            Equipment.entries.filter { it in equipment }.map { it.name }
        }, settings.goalsLb.takeIf { it.isNotEmpty() })
        dto.toDomain(strict = true)
        return SetJson.encode(PlanSettingsDto.serializer(), dto)
    }

    fun decode(json: String?): PlanSettings? = json?.let { runCatching { read(it) }.getOrNull() }
    fun validate(json: String) { SetJson.decode(PlanSettingsDto.serializer(), json).toDomain(strict = true) }
    fun hasPlanFacts(json: String): Boolean = runCatching {
        val dto = SetJson.decode(PlanSettingsDto.serializer(), json)
        dto.equipment != null || !dto.goalsLb.isNullOrEmpty()
    }.getOrDefault(false)
    private fun read(json: String): PlanSettings = SetJson.decode(PlanSettingsDto.serializer(), json).toDomain()
}

/** The plan's settings, or defaults when the column is absent or unreadable. */
val PlanEntity.planSettings: PlanSettings get() = PlanSettingsJson.decode(settingsJson) ?: PlanSettings()
