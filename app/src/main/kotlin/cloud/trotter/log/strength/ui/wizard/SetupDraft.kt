package cloud.trotter.log.strength.ui.wizard

import cloud.trotter.log.strength.domain.library.ExerciseLibrary
import cloud.trotter.log.strength.domain.standards.TestEvents
import cloud.trotter.log.strength.domain.model.CardioActivity
import cloud.trotter.log.strength.domain.planner.SprintEvent
import cloud.trotter.log.strength.domain.planner.BothAnswers
import cloud.trotter.log.strength.domain.planner.FitnessTest
import cloud.trotter.log.strength.domain.planner.PlanBranch
import cloud.trotter.log.strength.domain.planner.RunEvent
import cloud.trotter.log.strength.domain.planner.RunExperience
import cloud.trotter.log.strength.domain.planner.RunGoal
import cloud.trotter.log.strength.domain.model.Equipment
import cloud.trotter.log.strength.domain.planner.EquipmentPreset
import cloud.trotter.log.strength.domain.planner.LiftExperience
import cloud.trotter.log.strength.domain.progression.Effort
import cloud.trotter.log.strength.domain.progression.LiftGoal
import cloud.trotter.log.strength.domain.units.WeightUnit
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** A time is selected but its field holds no valid time; null is Just finish. */
internal const val INVALID_GOAL_TIME = -1

/** The setup answers and step, saved as one JSON value: a Parcelable could never gain PR 7's answers.
 *  New fields take defaults; [v] marks a shape this build cannot read. */
@Serializable
data class SetupDraft(
    val v: Int = VERSION,
    val step: SetupStep = SetupStep.GOAL,
    val goal: LiftGoal = LiftGoal.GENERAL,
    val days: Int = 3,
    val minutes: Int = 60,
    val equipment: Set<Equipment> = EquipmentPreset.FULL_GYM.equipment,
    val chooseExactly: Boolean = false,
    val unit: WeightUnit = WeightUnit.LB,
    val barStep: Int = 0,
    val dumbbellStep: Int = 0,
    val stackStep: Int = 0,
    val experience: LiftExperience = LiftExperience.NEW,
    val olderAdult: Boolean = false,
    val mains: List<String>? = null,
    val recent: Map<String, RecentDraft> = emptyMap(),
    val balanceDays: Boolean = false,
    val branch: PlanBranch = PlanBranch.LIFT,
    val runGoal: RunGoal = RunGoal.CARDIO_FITNESS,
    val runExperience: RunExperience = RunExperience.NEW,
    val runEvent: RunEvent? = null,
    val eventDate: Long? = null,
    val goalSeconds: Int? = null,
    val test: FitnessTest = FitnessTest.TWO_MILE,
    val testScore: Int? = null,
    val recentMetres: Int? = null,
    val recentSeconds: Int? = null,
    val longestByDistance: Boolean = false,
    val longestSeconds: Int? = null,
    val longestMetres: Int? = null,
    val activity: CardioActivity = CardioActivity.ROAD_RUN,
    val marathonBestSeconds: Int? = null,
    val runningInjury: Boolean = false,
    val strengthBlocks: Int = 2,
    val cardioFirst: Boolean = false,
    val together: BothAnswers.Together? = null,
    val sprintEvent: SprintEvent = SprintEvent.M100,
    val sprintCardio: Boolean = false,
    val sprintedRecently: Boolean = false,
    val hamstringStrain: Boolean = false,
    val fortyOrOlder: Boolean = false,
    val tests: List<TestDraft> = emptyList(),
    val testDate: Long? = null,
    val fitnessTest: Boolean? = null,
    val testRun: Boolean = false,
    val testLifts: List<LiftTestDraft> = emptyList(),
    val excluded: Set<String> = emptySet(),
) {
    val isSprint: Boolean get() = branch == PlanBranch.SPRINT || branch == PlanBranch.BOTH && sprintCardio

    companion object { const val VERSION = 1 }
}

@Serializable
data class LiftTestDraft(val exerciseId: String, val reps: Int, val goalLb: Double? = null)

@Serializable
data class TestDraft(val eventId: String, val required: Int? = null, val lastResult: Int? = null, val windowSeconds: Int? = null)

/** One Recent numbers row as typed: blank fields stay null; weight is in lb. */
@Serializable
data class RecentDraft(val weightLb: Double? = null, val reps: Int? = null, val effort: Effort = Effort.GOOD)

object SetupDraftJson {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun encode(draft: SetupDraft): String = json.encodeToString(draft)

    private fun normalize(draft: SetupDraft): SetupDraft {
        val tests = draft.tests.filter { TestEvents.find(it.eventId) != null }
        if (draft.fitnessTest != null) return draft.copy(tests = tests)
        val run = draft.runGoal == RunGoal.FITNESS_TEST &&
            (draft.branch == PlanBranch.RUN || draft.branch == PlanBranch.BOTH && !draft.isSprint)
        val entry = run || draft.branch == PlanBranch.LIFT && tests.isNotEmpty()
        val together = if (draft.branch == PlanBranch.BOTH && !draft.isSprint && (run || tests.isNotEmpty())) {
            draft.together ?: SetupFlowStateBuilder.answers(draft.copy(fitnessTest = false)).together()
        } else draft.together
        return draft.copy(fitnessTest = if (entry) true else null, testRun = run, tests = tests, together = together,
            testDate = if (entry) draft.eventDate ?: draft.testDate else draft.testDate,
            step = if (entry && draft.step in listOf(SetupStep.RUN_GOAL, SetupStep.EVENT)) SetupStep.TEST else draft.step)
    }

    /** Null for unreadable versions, malformed text or values the setup answers and step rows reject. */
    fun decode(text: String?): SetupDraft? {
        if (text.isNullOrBlank()) return null
        return try {
            json.decodeFromString<SetupDraft>(text).takeIf { draft ->
                draft.tests.map { it.eventId }.distinct().size == draft.tests.size &&
                    draft.testLifts.all { lift ->
                        ExerciseLibrary.find(lift.exerciseId)?.mainCapable == true && lift.reps >= 0
                    } && draft.testLifts.map { it.exerciseId }.distinct().size == draft.testLifts.size &&
                    draft.strengthBlocks in 0..2 && draft.v == SetupDraft.VERSION && draft.days in 1..6 &&
                    draft.minutes in SetupFlowStateBuilder.minutesOptions &&
                    draft.barStep in SetupFlowStateBuilder.stepOptionsLb(StepRow.BAR, draft.unit).indices &&
                    draft.dumbbellStep in SetupFlowStateBuilder.stepOptionsLb(StepRow.DUMBBELL, draft.unit).indices &&
                    draft.stackStep in SetupFlowStateBuilder.stepOptionsLb(StepRow.STACK, draft.unit).indices
            }?.let(::normalize)
        } catch (_: SerializationException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
    }
}
