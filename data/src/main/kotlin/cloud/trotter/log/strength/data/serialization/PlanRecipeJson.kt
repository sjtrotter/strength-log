package cloud.trotter.log.strength.data.serialization

import cloud.trotter.log.strength.domain.model.CardioActivity
import cloud.trotter.log.strength.domain.model.CardioMode
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
    val split: V1Split = V1Split.FULL_BODY,
    val anchorScheme: V1Anchors = V1Anchors.PROTOTYPE,
    val deadliftVariant: V1Deadlift = V1Deadlift.TRAP_BAR,
) {
    /** The old wizard's mains for these answers ([V1Mains]); conversion keeps those the plan still holds. */
    fun mainLiftIds(): Set<String> = V1Mains.of(daysPerWeek, split, anchorScheme, deadliftVariant, equipment).toSet()
}

object PlanRecipeJson {
    /** The old cardio-first lift-day answers. */
    private val V1_LIFT_DAYS = setOf(0, 2, 3)

    const val MAX_GOAL_LB = 2000.0

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
            require(it.liftDays in V1_LIFT_DAYS) { "liftDays must be 0, 2 or 3" }
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
                split = V1Split.entries.firstOrNull { it.name == recipe.split } ?: V1Split.FULL_BODY,
                anchorScheme = V1Anchors.entries.firstOrNull { it.name == recipe.anchorScheme } ?: V1Anchors.PROTOTYPE,
                deadliftVariant = V1Deadlift.entries.firstOrNull { it.name == recipe.deadliftVariant } ?: V1Deadlift.TRAP_BAR,
            )
        }.getOrNull()
    }
}
