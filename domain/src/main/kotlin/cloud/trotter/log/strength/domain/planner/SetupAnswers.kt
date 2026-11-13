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

/** Setup answers (design §2.1, §2.2). [mains] null takes [Planner.defaultMains].
 *  [goal] is the lift sub-goal; a Run plan's strength blocks use the Strength rules (D8).
 *  [run] is read on Run and Both, [both] on Both, [sprint] on Sprint and on Both with [BothAnswers.sprint];
 *  a Sprint plan's lifts use the Strength rules. */
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
    val branch: PlanBranch = PlanBranch.LIFT,
    val run: RunAnswers = RunAnswers(),
    val both: BothAnswers = BothAnswers(),
    val sprint: SprintAnswers = SprintAnswers(),
) {
    init {
        require(days in 1..6)
        require(minutes in setOf(30, 45, 60, 75, 90))
    }
}

/** Stored by name; new constants go at the end. */
enum class PlanBranch { LIFT, RUN, BOTH, SPRINT }

/** Both's Which first and Together (design §2.2). [together] null takes the default: separate days from 4 days. */
data class BothAnswers(
    val cardioFirst: Boolean = false,
    val together: Together? = null,
    /** The cardio sub-goal is a sprint event: the sprint layout with the lift sub-goal's rules, and neither
     *  Which first nor Together is asked (design revision 30). */
    val sprint: Boolean = false,
) {
    init { require(!sprint || (!cardioFirst && together == null)) }
    enum class Together { SAME_VISIT, SEPARATE_DAYS }
    fun together(days: Int): Together = together ?: if (days >= 4) Together.SEPARATE_DAYS else Together.SAME_VISIT
}
