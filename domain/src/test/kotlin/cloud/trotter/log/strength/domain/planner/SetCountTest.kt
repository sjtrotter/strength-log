package cloud.trotter.log.strength.domain.planner

import cloud.trotter.log.strength.domain.library.Muscle
import cloud.trotter.log.strength.domain.model.Program
import cloud.trotter.log.strength.domain.model.ProgramDay
import cloud.trotter.log.strength.domain.model.ProgramDayKind
import cloud.trotter.log.strength.domain.model.ProgramExercise
import cloud.trotter.log.strength.domain.model.SetStructure
import cloud.trotter.log.strength.domain.model.SupersetPartner
import cloud.trotter.log.strength.domain.progression.LiftGoal
import cloud.trotter.log.strength.domain.progression.RepTarget
import cloud.trotter.log.strength.domain.progression.Scheme
import cloud.trotter.log.strength.domain.progression.SlotRole
import cloud.trotter.log.strength.domain.progression.SlotRule
import kotlin.test.Test
import kotlin.test.assertEquals

class SetCountTest {
    private fun day(slots: List<ProgramExercise>, kind: ProgramDayKind = ProgramDayKind.STRENGTH) =
        ProgramDay("A", "Upper", "", slots, kind = kind)

    @Test fun `case 22 bench press and row count primary and half secondary sets`() {
        val program = Program(listOf(day(listOf("bb_bench", "ohp", "bb_row").map { ProgramExercise(it) })))
        assertEquals(mapOf(Muscle.CHEST to 3.0, Muscle.FRONT_DELT to 4.5, Muscle.TRICEPS to 3.0,
            Muscle.UPPER_BACK to 3.0, Muscle.LATS to 3.0, Muscle.BICEPS to 1.5, Muscle.REAR_DELT to 1.5),
            SetCount.weekly(program))
        assertEquals(SetCount.weekly(program).keys.associateWith { 1 }, SetCount.exposures(program))
    }

    @Test fun `ramps never count and non strength days never count`() {
        val rule = SlotRule(SlotRole.MAIN, RepTarget.Fixed(5), 2, 0.025, 2, scheme = Scheme.STRENGTH)
        val squat = ProgramExercise("bb_back_squat", SetStructure.RAMP_TOP_BACKOFF, 5, rule = rule)
        val slots = listOf(squat)
        val program = Program(listOf(day(slots), day(slots, ProgramDayKind.REST), day(slots, ProgramDayKind.CARDIO)))
        assertEquals(mapOf(Muscle.QUADS to 3.0, Muscle.GLUTES to 3.0), SetCount.weekly(program))
        assertEquals(mapOf(Muscle.QUADS to 1, Muscle.GLUTES to 1), SetCount.exposures(program))
        val topOnly = Program(listOf(day(listOf(squat.copy(structure = SetStructure.RAMP_TOP)))))
        assertEquals(mapOf(Muscle.QUADS to 1.0, Muscle.GLUTES to 1.0), SetCount.weekly(topOnly))
    }

    @Test fun `partners count their own muscles and unknown ids count nothing`() {
        val program = Program(listOf(day(listOf(
            ProgramExercise("ez_curl", superset = SupersetPartner("rope_pushdown")), ProgramExercise("unknown"),
        )))))
        assertEquals(mapOf(Muscle.BICEPS to 3.0, Muscle.TRICEPS to 3.0), SetCount.weekly(program))
        assertEquals(mapOf(Muscle.BICEPS to 1, Muscle.TRICEPS to 1), SetCount.exposures(program))
    }

    @Test fun `estimate shares ramp kinds and counts a superset only once`() {
        val squat = ProgramExercise("bb_back_squat", SetStructure.RAMP_TOP_BACKOFF)
        assertEquals(300 + 4 * 135 + 225 + 165, SessionBudget.seconds(day(listOf(squat)), LiftGoal.STRENGTH))
        val curl = ProgramExercise("ez_curl", superset = SupersetPartner("rope_pushdown"))
        assertEquals(SessionBudget.seconds(day(listOf(curl.copy(superset = null))), LiftGoal.MUSCLE),
            SessionBudget.seconds(day(listOf(curl)), LiftGoal.MUSCLE))
        assertEquals(0, SessionBudget.seconds(day(listOf(squat), ProgramDayKind.REST), LiftGoal.STRENGTH))
        assertEquals(300 + 3 * 135, SessionBudget.seconds(day(listOf(ProgramExercise("unknown"))), LiftGoal.STRENGTH))
    }

    @Test fun `reference rotation exposes compounds twice and core four times`() {
        val exposures = SetCount.exposures(Planner.plan(referenceAnswers()).program)
        for (muscle in listOf(Muscle.CHEST, Muscle.QUADS, Muscle.HAMSTRINGS, Muscle.BICEPS)) {
            assertEquals(2, exposures[muscle], muscle.name)
        }
        assertEquals(4, exposures[Muscle.CORE])
    }
}
