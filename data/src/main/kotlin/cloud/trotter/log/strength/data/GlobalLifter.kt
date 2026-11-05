package cloud.trotter.log.strength.data

import cloud.trotter.log.strength.domain.model.ExperienceLevel
import cloud.trotter.log.strength.domain.model.LifterConfig
import cloud.trotter.log.strength.domain.units.WeightUnit

data class GlobalLifter(
    val bodyweightLb: Double = LifterConfig().bodyweightLb,
    val age: Int = LifterConfig().age,
    val level: ExperienceLevel = LifterConfig().level,
    val weightUnit: WeightUnit = WeightUnit.LB,
) {
    fun toConfig(): LifterConfig = LifterConfig(bodyweightLb = bodyweightLb, age = age, level = level, weightUnit = weightUnit)

    companion object {
        fun of(config: LifterConfig): GlobalLifter = GlobalLifter(config.bodyweightLb, config.age, config.level, config.weightUnit)
    }
}
