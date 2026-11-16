package cloud.trotter.log.strength.data.serialization

import cloud.trotter.log.strength.domain.generator.CardioFirst
import cloud.trotter.log.strength.domain.generator.AnchorScheme
import cloud.trotter.log.strength.domain.generator.DeadliftVariant
import cloud.trotter.log.strength.domain.generator.ProgramGenerator
import cloud.trotter.log.strength.domain.generator.SplitTemplate
import cloud.trotter.log.strength.domain.generator.WizardAnswers
import cloud.trotter.log.strength.domain.model.CardioActivity
import cloud.trotter.log.strength.domain.model.CardioMode
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

/** The old wizard facts needed to convert plan settings, main membership and legacy cardio (design §4.4).
 *  The defaults are the old wizard's. */
data class V1Recipe(
    val equipment: Set<Equipment> = Equipment.entries.toSet(),
    val goalsLb: Map<String, Double> = emptyMap(),
    val cardioMode: CardioMode = CardioMode.OUTDOOR_RUN,
    val fiveKGoal: Boolean = true,
    val daysPerWeek: Int = 4,
    val split: SplitTemplate = SplitTemplate.FULL_BODY,
    val anchorScheme: AnchorScheme = AnchorScheme.PROTOTYPE,
    val deadliftVariant: DeadliftVariant = DeadliftVariant.TRAP_BAR,
) {
    /** 6d must keep this derivation as a frozen legacy table when it deletes the generator; restored old backups still convert. */
    fun mainLiftIds(): Set<String> = ProgramGenerator.mainLiftIds(WizardAnswers(
        daysPerWeek = daysPerWeek, split = split, anchorScheme = anchorScheme,
        deadliftVariant = deadliftVariant, equipment = equipment,
    )).toSet()
}

object PlanRecipeJson {
    const val MAX_GOAL_LB = 2000.0

    /** Writes an old wizard recipe. Only the test fixtures createGeneratedPlan and setWizardAnswers call it;
     *  6d deletes it with them. */
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
        if (SetupRecipeJson.isV2(json)) return SetupRecipeJson.validate(json)
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

    fun read(json: String): V1Recipe? {
        if (SetupRecipeJson.isV2(json)) return null
        return runCatching {
            val recipe = SetJson.decode(PlanRecipeDto.serializer(), json)
            V1Recipe(
                equipment = recipe.equipment.mapNotNull { name -> Equipment.entries.firstOrNull { it.name == name } }.toSet(),
                goalsLb = recipe.goalsLb.filter { (id, goal) ->
                    id.isNotBlank() && goal.isFinite() && goal > 0.0 && goal <= MAX_GOAL_LB
                },
                cardioMode = CardioMode.entries.firstOrNull { it.name == recipe.cardioMode } ?: CardioMode.OUTDOOR_RUN,
                fiveKGoal = recipe.fiveKGoal,
                daysPerWeek = recipe.daysPerWeek.coerceIn(2, 6),
                split = SplitTemplate.entries.firstOrNull { it.name == recipe.split } ?: SplitTemplate.FULL_BODY,
                anchorScheme = AnchorScheme.entries.firstOrNull { it.name == recipe.anchorScheme } ?: AnchorScheme.PROTOTYPE,
                deadliftVariant = DeadliftVariant.entries.firstOrNull { it.name == recipe.deadliftVariant } ?: DeadliftVariant.TRAP_BAR,
            )
        }.getOrNull()
    }
}
