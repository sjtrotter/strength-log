package cloud.trotter.log.strength.data

import cloud.trotter.log.strength.domain.model.ExperienceLevel
import cloud.trotter.log.strength.domain.model.LifterConfig

data class GlobalLifter(
    val bodyweightLb: Int = LifterConfig().bodyweightLb,
    val age: Int = LifterConfig().age,
    val level: ExperienceLevel = LifterConfig().level,
) {
    fun toConfig(): LifterConfig = LifterConfig(bodyweightLb = bodyweightLb, age = age, level = level)

    companion object {
        fun of(config: LifterConfig): GlobalLifter = GlobalLifter(config.bodyweightLb, config.age, config.level)
    }
}
