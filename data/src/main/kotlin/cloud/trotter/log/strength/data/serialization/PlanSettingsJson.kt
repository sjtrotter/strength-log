package cloud.trotter.log.strength.data.serialization

import cloud.trotter.log.strength.domain.progression.LiftGoal
import cloud.trotter.log.strength.domain.progression.LoadSteps
import kotlinx.serialization.Serializable

/** A plan's own settings (`plan.settingsJson`, design §4.1). An absent column or field means the default: Strength,
 *  and the steps of the lifter's current unit ([LoadSteps.defaults]). Plan settings (PR 2c) and setup (PR 6) write
 *  it; PR 6 adds the equipment and typed goals. */
data class PlanSettings(val defaultsFor: LiftGoal = LiftGoal.STRENGTH, val loadSteps: LoadSteps? = null)

@Serializable internal data class PlanSettingsDto(val defaultsFor: String? = null, val loadSteps: LoadStepsDto? = null) {
    fun toDomain(): PlanSettings = PlanSettings(
        defaultsFor?.let(LiftGoal::valueOf) ?: LiftGoal.STRENGTH,
        loadSteps?.let {
            require(listOf(it.barLb, it.dumbbellLb, it.stackLb).all { step ->
                step.isFinite() && step > 0.0 && step <= 100.0
            })
            LoadSteps(it.barLb, it.dumbbellLb, it.stackLb)
        },
    )
}
@Serializable internal data class LoadStepsDto(val barLb: Double, val dumbbellLb: Double, val stackLb: Double)

object PlanSettingsJson {
    fun encode(settings: PlanSettings): String {
        val dto = PlanSettingsDto(settings.defaultsFor.name, settings.loadSteps?.let {
            LoadStepsDto(it.barLb, it.dumbbellLb, it.stackLb)
        })
        dto.toDomain()
        return SetJson.encode(PlanSettingsDto.serializer(), dto)
    }

    fun decode(json: String?): PlanSettings? = json?.let { runCatching { read(it) }.getOrNull() }
    fun validate(json: String) { read(json) }
    private fun read(json: String): PlanSettings = SetJson.decode(PlanSettingsDto.serializer(), json).toDomain()
}
