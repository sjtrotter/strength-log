package cloud.trotter.log.strength.data.serialization

import cloud.trotter.log.strength.data.GlobalLifter
import cloud.trotter.log.strength.domain.generator.WizardAnswers
import cloud.trotter.log.strength.domain.model.CardioPrefs
import cloud.trotter.log.strength.domain.model.Equipment
import kotlinx.serialization.Serializable

/** Persistence shape of the generator inputs owned by a plan. */
@Serializable
internal data class PlanRecipeDto(
    val daysPerWeek: Int,
    val split: String,
    val anchorScheme: String,
    val deadliftVariant: String,
    val emphasis: String,
    val cardioMode: String,
    val cardioPlacement: String,
    val fiveKGoal: Boolean,
    val equipment: List<String>,
)

object PlanRecipeJson {
    fun encode(answers: WizardAnswers): String = SetJson.encode(
        PlanRecipeDto.serializer(),
        PlanRecipeDto(
            daysPerWeek = answers.daysPerWeek,
            split = answers.split.name,
            anchorScheme = answers.anchorScheme.name,
            deadliftVariant = answers.deadliftVariant.name,
            emphasis = answers.config.emphasis.name,
            cardioMode = answers.cardio.mode.name,
            cardioPlacement = answers.cardio.placement.name,
            fiveKGoal = answers.cardio.fiveKGoal,
            equipment = answers.equipment.map { it.name },
        ),
    )

    fun decode(json: String, global: GlobalLifter): WizardAnswers {
        val recipe = SetJson.decode(PlanRecipeDto.serializer(), json)
        val defaults = WizardAnswers()
        return WizardAnswers(
            daysPerWeek = recipe.daysPerWeek.takeIf { it in 2..6 } ?: defaults.daysPerWeek,
            split = enum(recipe.split, defaults.split),
            anchorScheme = enum(recipe.anchorScheme, defaults.anchorScheme),
            deadliftVariant = enum(recipe.deadliftVariant, defaults.deadliftVariant),
            config = global.toConfig().copy(emphasis = enum(recipe.emphasis, defaults.config.emphasis)),
            cardio = CardioPrefs(
                mode = enum(recipe.cardioMode, defaults.cardio.mode),
                placement = enum(recipe.cardioPlacement, defaults.cardio.placement),
                fiveKGoal = recipe.fiveKGoal,
            ),
            equipment = recipe.equipment.mapNotNull { name -> Equipment.entries.firstOrNull { it.name == name } }.toSet(),
        )
    }

    private inline fun <reified E : Enum<E>> enum(name: String, default: E): E =
        enumValues<E>().firstOrNull { it.name == name } ?: default
}
