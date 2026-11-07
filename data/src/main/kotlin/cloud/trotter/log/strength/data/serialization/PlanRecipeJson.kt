package cloud.trotter.log.strength.data.serialization

import cloud.trotter.log.strength.data.GlobalLifter
import cloud.trotter.log.strength.domain.generator.CardioFirst
import cloud.trotter.log.strength.domain.generator.WizardAnswers
import cloud.trotter.log.strength.domain.model.CardioActivity
import cloud.trotter.log.strength.domain.model.CardioPrefs
import cloud.trotter.log.strength.domain.model.Equipment
import cloud.trotter.log.strength.domain.model.Measure
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
    val goalsLb: Map<String, Double> = emptyMap(),
    val cardioFirst: CardioFirstDto? = null,
)

@Serializable
internal data class CardioFirstDto(
    val activity: String,
    val seconds: Int? = null,
    val metres: Int? = null,
    val sessions: Int,
    val liftDays: Int,
    val restDays: Int = 0,
)

object PlanRecipeJson {
    const val MAX_GOAL_LB = 2000.0

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
            goalsLb = answers.config.goalsLb,
            cardioFirst = answers.cardioFirst?.let {
                CardioFirstDto(it.activity.name, (it.comfortable as? Measure.Time)?.seconds,
                    (it.comfortable as? Measure.Distance)?.metres, it.sessions, it.liftDays, it.restDays)
            },
        ),
    )

    /** Checks a portable recipe with the stored-payload decoder and the supported day range. */
    fun validate(json: String) {
        val recipe = SetJson.decode(PlanRecipeDto.serializer(), json)
        require(recipe.daysPerWeek in 2..6) { "daysPerWeek must be 2..6, was ${recipe.daysPerWeek}" }
        require(recipe.goalsLb.values.all { it.isFinite() && it > 0.0 && it <= MAX_GOAL_LB })
        recipe.cardioFirst?.let {
            require(CardioActivity.entries.any { activity -> activity.name == it.activity }) { "unknown cardio activity" }
            require((it.seconds != null) != (it.metres != null)) { "exactly one comfortable measure is required" }
            require(it.seconds == null || it.seconds in 1..86_400) { "seconds must be 1..86400" }
            require(it.metres == null || it.metres in 1..200_000) { "metres must be 1..200000" }
            require(it.restDays in 0..2) { "restDays must be 0..2" }
            require(it.sessions in 1..6) { "sessions must be 1..6" }
            require(it.liftDays in CardioFirst.LIFT_DAY_CHOICES) { "liftDays must be 0, 2 or 3" }
        }
    }

    fun decode(json: String, global: GlobalLifter): WizardAnswers {
        val recipe = SetJson.decode(PlanRecipeDto.serializer(), json)
        val defaults = WizardAnswers()
        val cardioFirst = recipe.cardioFirst?.let {
            val cardioDefaults = CardioFirst()
            CardioFirst(
                activity = enum(it.activity, cardioDefaults.activity),
                comfortable = when {
                    it.seconds != null && it.metres == null && it.seconds in 1..86_400 -> Measure.Time(it.seconds)
                    it.metres != null && it.seconds == null && it.metres in 1..200_000 -> Measure.Distance(it.metres)
                    else -> cardioDefaults.comfortable
                },
                restDays = it.restDays.coerceIn(0, 2),
                sessions = it.sessions.takeIf { n -> n in 1..6 } ?: cardioDefaults.sessions,
                liftDays = it.liftDays.takeIf { n -> n in CardioFirst.LIFT_DAY_CHOICES } ?: cardioDefaults.liftDays,
            )
        }
        return WizardAnswers(
            daysPerWeek = cardioFirst?.liftDays?.takeIf { it > 0 }
                ?: recipe.daysPerWeek.takeIf { it in 2..6 } ?: defaults.daysPerWeek,
            split = enum(recipe.split, defaults.split),
            anchorScheme = enum(recipe.anchorScheme, defaults.anchorScheme),
            deadliftVariant = enum(recipe.deadliftVariant, defaults.deadliftVariant),
            config = global.toConfig().copy(
                emphasis = enum(recipe.emphasis, defaults.config.emphasis),
                goalsLb = recipe.goalsLb,
            ),
            cardio = CardioPrefs(
                mode = enum(recipe.cardioMode, defaults.cardio.mode),
                placement = enum(recipe.cardioPlacement, defaults.cardio.placement),
                fiveKGoal = recipe.fiveKGoal,
            ),
            cardioFirst = cardioFirst,
            equipment = recipe.equipment.mapNotNull { name -> Equipment.entries.firstOrNull { it.name == name } }.toSet(),
        )
    }

    private inline fun <reified E : Enum<E>> enum(name: String, default: E): E =
        enumValues<E>().firstOrNull { it.name == name } ?: default
}
