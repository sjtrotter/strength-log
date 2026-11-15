package cloud.trotter.log.strength.domain.planner

import cloud.trotter.log.strength.domain.library.Muscle
import cloud.trotter.log.strength.domain.model.ProgramExercise
import cloud.trotter.log.strength.domain.progression.LiftGoal
import cloud.trotter.log.strength.domain.progression.SlotRole
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class VolumeBalanceTest {
    private fun slot(draft: PlanDraft, day: String, id: String): ProgramExercise =
        draft.program.days.single { it.id == day }.exercises.single { it.exerciseId == id }
    private fun seconds(draft: PlanDraft, goal: LiftGoal) = draft.program.days.map { SessionBudget.seconds(it, goal) }
    private fun pulls(draft: PlanDraft) = draft.program.strengthDays.flatMap { day ->
        day.exercises.filter { it.exerciseId == "kb_row" }.map { Triple(day.id, it.rule!!.role, it.targetSets) }
    }
    private fun kettlebells(days: Int) = SetupAnswers(goal = LiftGoal.MUSCLE, days = days, minutes = 90,
        equipment = EquipmentPreset.KETTLEBELLS.equipment, experience = LiftExperience.A_YEAR_OR_MORE)

    @Test fun `floors follow goal experience age and available primary muscles`() {
        val seven = setOf(Muscle.CHEST, Muscle.FRONT_DELT, Muscle.LATS, Muscle.UPPER_BACK,
            Muscle.QUADS, Muscle.HAMSTRINGS, Muscle.GLUTES)
        val nine = seven + setOf(Muscle.BICEPS, Muscle.TRICEPS)
        fun check(answers: SetupAnswers, muscles: Set<Muscle>, sets: Int) {
            val floors = VolumeBalance.floors(answers)
            assertEquals(muscles.associateWith { sets }, floors, answers.toString())
            assertEquals(muscles.sortedBy { it.ordinal }, floors.keys.toList())
        }
        for (experience in LiftExperience.entries) {
            check(SetupAnswers(goal = LiftGoal.STRENGTH, experience = experience), seven, 6)
            check(SetupAnswers(goal = LiftGoal.GENERAL, experience = experience), seven, 4)
            check(SetupAnswers(goal = LiftGoal.MUSCLE, experience = experience), nine,
                if (experience == LiftExperience.A_YEAR_OR_MORE) 12 else 10)
            check(SetupAnswers(goal = LiftGoal.MUSCLE, experience = experience, olderAdult = true), nine, 6)
            check(SetupAnswers(goal = LiftGoal.GENERAL, experience = experience, olderAdult = true), seven, 6)
        }
        check(SetupAnswers(goal = LiftGoal.STRENGTH, equipment = EquipmentPreset.BODYWEIGHT.equipment),
            seven - Muscle.UPPER_BACK, 6)
        check(kettlebells(3), nine - Muscle.BICEPS, 12)
        check(kettlebells(3).copy(equipment = EquipmentPreset.BODYWEIGHT.equipment),
            nine - setOf(Muscle.UPPER_BACK, Muscle.BICEPS), 12)
    }

    @Test fun `six day reference trims the largest latest primary accessory first`() {
        val draft = Planner.plan(referenceAnswers().copy(days = 6))
        assertEquals(2, slot(draft, "A", "bss").targetSets)
        assertEquals(3, slot(draft, "A", "rdl").targetSets)
        assertEquals(2, slot(draft, "C", "bss").targetSets)
        assertEquals(2, slot(draft, "F", "hack_squat").targetSets)
        assertEquals(2, slot(draft, "F", "bss").targetSets)
        val weekly = SetCount.weekly(draft.program)
        assertEquals(18.0, weekly[Muscle.QUADS])
        assertEquals(20.0, weekly[Muscle.GLUTES])
        // audit 2026-11-13, row 13: base 540, ramped main 744, secondary set 150,
        // accessory 129 (pullup 123), isolation 99, arm pair 138, plank 120; trim shape stays.
        // A=540+744+3*129+2*129+6*99+2*120=2763; B/G=540+744+2*150+3*129+3*123+5*99+3*138=3249.
        // C=540+744+2*150+2*129+8*99=2634; E=B+42=3291; F=540+2*150+4*129+8*99=2148.
        assertEquals(listOf(2763, 3249, 2634, 0, 3291, 2148, 3249), seconds(draft, LiftGoal.STRENGTH))
        assertTrue(draft.reasons.isEmpty())
    }

    @Test fun `six older days trim core slots while keeping balance carries`() {
        val draft = Planner.plan(SetupAnswers(goal = LiftGoal.STRENGTH, days = 6, minutes = 90, olderAdult = true))
        assertEquals(listOf(2, 2, 1, 1, 1, 1), draft.program.strengthDays.map { day ->
            day.exercises.single { it.rule?.role == SlotRole.CORE }.targetSets
        })
        for (day in draft.program.strengthDays) assertEquals(2, slot(draft, day.id, "farmers_carry").targetSets)
        assertEquals(20.0, SetCount.weekly(draft.program)[Muscle.CORE])
        // audit 2026-11-13, row 13: base+balance=940; main set153, accessory129 (pullup123), isolation99,
        // arm pair138, plank120. A=940+2*153+4*129+4*99+2*120=2398;
        // B=940+2*153+4*129+2*123+4*99+2*138=2680;
        // C=940+2*153+4*129+5*99=2257; E=B-2*99+120=2602;
        // F=940+6*129+5*99=2209; G=B-99=2581. Core trim shape stays.
        assertEquals(listOf(2398, 2680, 2257, 0, 2602, 2209, 2581), seconds(draft, LiftGoal.STRENGTH))
        assertTrue(draft.reasons.isEmpty())
    }

    @Test fun `fill stops at minutes and reports step zero on only the blocked days`() {
        val answers = SetupAnswers(goal = LiftGoal.STRENGTH, days = 2)
        val draft = Planner.plan(answers)
        // audit 2026-11-13, row 13: A=540+9*210+4*150+3*99+2*120=3567;
        // B=540+6*210+7*150+3*138+123+2*99=3585. Row growth would reach 3612;
        // a one-set pullup fits instead; no 99-second curl fits either day.
        assertEquals(listOf(3567, 3585), seconds(draft, answers.goal))
        assertEquals(3, slot(draft, "B", "bb_bench").targetSets)
        // audit 2026-11-13, row 13: 3462+150=3612 > 3600; keep two row sets.
        assertEquals(2, slot(draft, "B", "bb_row").targetSets)
        // audit 2026-11-13, row 13: 3462+123=3585 fits; 3585+99 > 3600, so pullup replaces curl.
        val pull = slot(draft, "B", "pullup")
        assertEquals(SlotRole.ACCESSORY, pull.rule!!.role)
        assertEquals(1, pull.targetSets)
        assertEquals(listOf("pullup", "pallof"),
            draft.program.days.last().exercises.takeLast(2).map { it.exerciseId })
        // audit 2026-11-13, row 13: trap main 3/2 + secondary 2/2 = 2.5; no curl fits.
        assertEquals(2.5, SetCount.weekly(draft.program)[Muscle.HAMSTRINGS])
        assertEquals(listOf(PlanReason.ShortSession("A", 0), PlanReason.ShortSession("B", 0)), draft.reasons)
        assertEquals(listOf(PlanReason.ShortSession("B", 0)), Planner.day(answers, "B")!!.reasons)
        val longer = Planner.plan(answers.copy(minutes = 75))
        assertTrue(longer.reasons.isEmpty())
        // audit 2026-11-13, row 13: A=3567; B=540+6*210+8*150+3*138+6*99=4008.
        assertEquals(listOf(3567, 4008), seconds(longer, answers.goal))
        assertEquals(4, slot(longer, "B", "seated_curl").targetSets)
    }

    @Test fun `fill reuses a chosen non main capable row on a day without it`() {
        val draft = Planner.plan(kettlebells(3))
        assertEquals(listOf(Triple("A", SlotRole.ACCESSORY, 2), Triple("B", SlotRole.MAIN, 5),
            Triple("C", SlotRole.SECONDARY, 5)), pulls(draft))
        val weekly = SetCount.weekly(draft.program)
        assertEquals(12.0, weekly[Muscle.LATS])
        assertEquals(12.0, weekly[Muscle.UPPER_BACK])
        assertTrue(draft.reasons.isEmpty())
    }

    @Test fun `catalog bound back floors stay short without a minutes reason`() {
        val draft = Planner.plan(kettlebells(2))
        assertEquals(listOf(Triple("A", SlotRole.MAIN, 5), Triple("B", SlotRole.SECONDARY, 5)), pulls(draft))
        val weekly = SetCount.weekly(draft.program)
        assertEquals(10.0, weekly[Muscle.LATS])
        assertEquals(10.0, weekly[Muscle.UPPER_BACK])
        assertTrue(draft.reasons.isEmpty())
    }

    @Test fun `fill uses knee flexion when growing a hinge would exceed the glute cap`() {
        val draft = Planner.plan(kettlebells(3))
        val curl = slot(draft, "C", "ball_curl")
        assertEquals(SlotRole.ISOLATION, curl.rule!!.role)
        assertEquals(4, curl.targetSets)
        assertEquals(2, slot(draft, "C", "kb_rdl").targetSets)
        val weekly = SetCount.weekly(draft.program)
        assertEquals(20.0, weekly[Muscle.GLUTES])
        assertEquals(12.0, weekly[Muscle.HAMSTRINGS])
        assertTrue(weekly.values.all { it <= 20.0 })
    }
}
