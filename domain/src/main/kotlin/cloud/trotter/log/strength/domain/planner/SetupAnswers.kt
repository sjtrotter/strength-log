package cloud.trotter.log.strength.domain.planner

import cloud.trotter.log.strength.domain.model.Equipment
import cloud.trotter.log.strength.domain.progression.Effort
import cloud.trotter.log.strength.domain.progression.LiftGoal
import cloud.trotter.log.strength.domain.progression.LoadSteps
import cloud.trotter.log.strength.domain.units.WeightUnit

enum class LiftExperience { NEW, UNDER_A_YEAR, A_YEAR_OR_MORE }

/** A main's recent set from Recent numbers (design §2.2): ten reps or fewer. */
data class RecentNumber(val weightLb: Double, val reps: Int, val effort: Effort) {
    init { require(reps in 1..10) }
}

enum class EquipmentPreset(val equipment: Set<Equipment>) {
    FULL_GYM(Equipment.entries.toSet()),
    HOME_BARBELL(setOf(Equipment.BARBELL, Equipment.RACK, Equipment.BENCH, Equipment.PULLUP_BAR, Equipment.BODYWEIGHT)),
    DUMBBELLS(setOf(Equipment.DUMBBELL, Equipment.BENCH, Equipment.BODYWEIGHT)),
    KETTLEBELLS(setOf(Equipment.KETTLEBELL, Equipment.BODYWEIGHT)),
    MACHINES_ONLY(setOf(Equipment.MACHINE, Equipment.CABLE, Equipment.BENCH, Equipment.BODYWEIGHT)),
    BODYWEIGHT(setOf(Equipment.BODYWEIGHT, Equipment.PULLUP_BAR)),
}

/** The lift branches' setup answers (design §2.1, §2.2). [mains] null takes [Planner.defaultMains]. */
data class SetupAnswers(
    val goal: LiftGoal = LiftGoal.GENERAL,
    val days: Int = 3,
    val minutes: Int = 60,
    val equipment: Set<Equipment> = EquipmentPreset.FULL_GYM.equipment,
    val unit: WeightUnit = WeightUnit.LB,
    val steps: LoadSteps = LoadSteps.defaults(unit),
    val experience: LiftExperience = LiftExperience.NEW,
    val olderAdult: Boolean = false,
    val mains: List<String>? = null,
    val recent: Map<String, RecentNumber> = emptyMap(),
    val excluded: Set<String> = emptySet(),
) {
    init {
        require(days in 1..6)
        require(minutes in setOf(30, 45, 60, 75, 90))
    }
}
