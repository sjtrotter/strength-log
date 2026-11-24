package cloud.trotter.log.strength.domain.planner

import cloud.trotter.log.strength.domain.model.Equipment
import cloud.trotter.log.strength.domain.progression.Effort
import cloud.trotter.log.strength.domain.progression.LiftGoal
import cloud.trotter.log.strength.domain.progression.LoadSteps
import cloud.trotter.log.strength.domain.units.WeightUnit
import cloud.trotter.log.strength.domain.model.PlanGoalDate
import cloud.trotter.log.strength.domain.model.TestEventGoal
import cloud.trotter.log.strength.domain.model.TestGoal

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
    val balanceDays: Boolean = false,
    val tests: List<TestAnswer> = emptyList(),
    val testDateEpochDay: Long? = null,
) {
    init {
        require(days in 1..6)
        require(minutes in MINUTES)
        require(tests.isEmpty() || branch == PlanBranch.LIFT || branch == PlanBranch.BOTH && !both.sprint)
        require(PlanGoalDate.isValid(testDateEpochDay))
        require(tests.map { it.goal.ref }.distinct().size == tests.size)
    }

    val testGoal: TestGoal? get() = tests.takeIf { it.isNotEmpty() }?.let { TestGoal(it.map(TestAnswer::goal), testDateEpochDay) }

    companion object {
        val MINUTES = listOf(30, 45, 60, 75, 90)
    }

    /** The rules for this plan's lift slots: Run and Sprint use Strength (D8, revision 31),
     *  Lift and Both the lift sub-goal. */
    val slotGoal: LiftGoal get() = if (branch == PlanBranch.RUN || branch == PlanBranch.SPRINT) LiftGoal.STRENGTH else goal

    /** Design §5: the careful group, 40 or older, 65+ or a hamstring strain in the past 12 months
     *  (Green 2020 risk factors, strong-moderate; the cutoffs and doses are practice). */
    val careful: Boolean get() = sprint.fortyOrOlder || olderAdult || sprint.hamstringStrain

    /** Design §5: the careful group always opens with the intro, even after recent all-out sprinting. */
    val sprintIntro: Boolean get() = sprint.intro || careful

    /** Both's Together answer as the planner applies it (design §2.2; audit 2026-11-13 row 14). */
    fun together(): BothAnswers.Together {
        val liftFirst = !both.cardioFirst
        // C04: separate days at two days would leave one strength day (WHO 2020: two or more).
        if (liftFirst && days == 2) return BothAnswers.Together.SAME_VISIT
        both.together?.let { return it }
        if (days >= 4) return BothAnswers.Together.SEPARATE_DAYS
        // C03: trained Strength lifters keep lifting apart from running when the lift side still gets two days.
        if (liftFirst && days == 3 && goal == LiftGoal.STRENGTH && experience == LiftExperience.A_YEAR_OR_MORE) {
            return BothAnswers.Together.SEPARATE_DAYS
        }
        return BothAnswers.Together.SAME_VISIT
    }
}

/** Stored by name; new constants go at the end. */
enum class PlanBranch { LIFT, RUN, BOTH, SPRINT }

/** Both's Which first and Together (design §2.2). [SetupAnswers.together] applies the default and strength floor. */
data class BothAnswers(
    val cardioFirst: Boolean = false,
    val together: Together? = null,
    /** The cardio sub-goal is a sprint event: the sprint layout with the lift sub-goal's rules, and neither
     *  Which first nor Together is asked (design revision 30). */
    val sprint: Boolean = false,
) {
    init { require(!sprint || (!cardioFirst && together == null)) }
    enum class Together { SAME_VISIT, SEPARATE_DAYS }
}

/** The optional last result anchors prep; benchmarks never set training targets. */
data class TestAnswer(val goal: TestEventGoal, val lastResult: Int? = null) {
    init { lastResult?.let { require(it in 1..3600) } }
}
