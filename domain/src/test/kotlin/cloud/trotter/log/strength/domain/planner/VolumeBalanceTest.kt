package cloud.trotter.log.strength.domain.planner

import cloud.trotter.log.strength.domain.library.ExerciseLibrary
import cloud.trotter.log.strength.domain.library.Muscle
import cloud.trotter.log.strength.domain.model.ProgramDay
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
        // audit 2026-11-13, row 13: base 300, ramped main 744, secondary set 150,
        // accessory 129 (pullup 123), isolation 99, arm pair 138, plank 120; trim shape stays.
        // A=300+744+5*129+6*99+2*120=2523; B/G=300+744+2*150+3*129+3*123+5*99+3*138=3009.
        // C=300+744+2*150+2*129+8*99=2394; E=B+42=3051; F=300+2*150+4*129+8*99=1908.
        assertEquals(listOf(2523, 3009, 2394, 0, 3051, 1908, 3009), seconds(draft, LiftGoal.STRENGTH))
        assertTrue(draft.reasons.isEmpty())
    }

    @Test fun `six older days trim core slots while keeping balance carries`() {
        val draft = Planner.plan(SetupAnswers(goal = LiftGoal.STRENGTH, days = 6, minutes = 90, olderAdult = true))
        assertEquals(listOf(2, 2, 1, 1, 1, 1), draft.program.strengthDays.map { day ->
            day.exercises.single { it.rule?.role == SlotRole.CORE }.targetSets
        })
        for (day in draft.program.strengthDays) assertEquals(2, slot(draft, day.id, "farmers_carry").targetSets)
        assertEquals(20.0, SetCount.weekly(draft.program)[Muscle.CORE])
        // audit 2026-11-13, row 13: base+balance=300+400=700; main153, accessory129 (pullup123),
        // isolation99, arm pair138, plank120. A=700+2*153+4*129+4*99+2*120=2158;
        // B=700+2*153+4*129+2*123+4*99+2*138=2440;
        // C=700+2*153+4*129+5*99=2017; E=B-2*99+120=2362;
        // F=700+6*129+5*99=1969; G=B-99=2341. Core trim shape stays.
        assertEquals(listOf(2158, 2440, 2017, 0, 2362, 1969, 2341), seconds(draft, LiftGoal.STRENGTH))
        assertTrue(draft.reasons.isEmpty())
    }

    @Test fun `fill follows current day durations and reaches floors within sixty minutes`() {
        val answers = SetupAnswers(goal = LiftGoal.STRENGTH, days = 2)
        val draft = Planner.plan(answers)
        // audit 2026-11-13, row 13: A starts at 300+9*210+4*150+3*99+2*120=3327.
        // B starts at 3072; chest and lat floors grow B's bench and row: 3072+2*150=3372.
        // A is now shorter, so its curl grows to two sets: 3327+2*99=3525; a third reaches 3624.
        // The remaining two curl sets go on B: 3372+2*99=3570. All floors fit, with no step-zero reasons.
        assertEquals(listOf(3525, 3570), seconds(draft, answers.goal))
        assertEquals(3, slot(draft, "B", "bb_bench").targetSets)
        assertEquals(3, slot(draft, "B", "bb_row").targetSets)
        for (id in listOf("A", "B")) {
            val curl = slot(draft, id, "seated_curl")
            assertEquals(SlotRole.ISOLATION, curl.rule!!.role)
            assertEquals(2, curl.targetSets)
        }
        assertEquals(listOf("seated_curl", "pallof"),
            draft.program.days.last().exercises.takeLast(2).map { it.exerciseId })
        // Trap main 3/2 + trap secondary 2/2 + four curl sets = 6.5 hamstring sets.
        assertEquals(6.5, SetCount.weekly(draft.program)[Muscle.HAMSTRINGS])
        assertTrue(draft.reasons.isEmpty())
        assertTrue(Planner.day(answers, "B")!!.reasons.isEmpty())
        val longer = Planner.plan(answers.copy(minutes = 75))
        assertTrue(longer.reasons.isEmpty())
        // A=3327+4*99=3723; B=300+6*210+8*150+3*138+2*99=3372.
        // The old expectation put curls on B; host sorting after row growth puts all four on A.
        assertEquals(listOf(3723, 3372), seconds(longer, answers.goal))
        assertEquals(4, slot(longer, "A", "seated_curl").targetSets)
    }

    @Test fun `fill stops at the reserved budget and reports step zero on only the blocked days`() {
        val answers = SetupAnswers(goal = LiftGoal.STRENGTH, days = 2)
        val mains = resolveMains(answers)
        val (days, reasons) = VolumeBalance.balance(layout(answers, mains), answers,
            mains.map { it.id }.toSet(), emptySet(), budgetSeconds = 3500)
        // After bench and row growth A=3327, B=3372; one curl each gives 3426 and 3471.
        // Neither second curl fits: 3525 and 3570 > 3500. Hamstring credit 2.5+2=4.5 < 6.
        assertEquals(listOf(3426, 3471), days.map { SessionBudget.seconds(it, answers.goal) })
        assertEquals(listOf(PlanReason.ShortSession("A", 0), PlanReason.ShortSession("B", 0)), reasons)
        val (oneBlocked, oneReason) = VolumeBalance.balance(days, answers,
            mains.map { it.id }.toSet(), setOf("A"), budgetSeconds = 3500)
        assertEquals(days, oneBlocked)
        assertEquals(listOf(PlanReason.ShortSession("B", 0)), oneReason)
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

    @Test fun `prep reserves constrain both new slots and set growth at the same budget`() {
        val answers = SetupAnswers(goal = LiftGoal.STRENGTH, days = 2)
        val mains = resolveMains(answers)
        val days = layout(answers, mains)
        val chosen = mains.map { it.id }.toSet()
        val expected = VolumeBalance.balance(days, answers, chosen, emptySet(), budgetSeconds = 3500)
        val actual = VolumeBalance.balance(days, answers, chosen, emptySet(), budgetSeconds = 3600,
            reservedSeconds = days.associate { it.id to 100 })
        assertEquals(expected, actual)
        assertEquals(listOf(PlanReason.ShortSession("A", 0), PlanReason.ShortSession("B", 0)), actual.second)
        assertTrue(actual.first.all { SessionBudget.seconds(it, answers.goal) + 100 <= 3600 })
        assertEquals(VolumeBalance.balance(days, answers, chosen, emptySet()),
            VolumeBalance.balance(days, answers, chosen, emptySet(), reservedSeconds = days.associate { it.id to 0 }))
    }

    @Test fun `new slot hosts are ordered by ordinary seconds plus each day's reserve`() {
        val answers = SetupAnswers(goal = LiftGoal.GENERAL, days = 2,
            excluded = ExerciseLibrary.entries.map { it.id }.toSet() - "pushup")
        val days = listOf(ProgramDay("A", "A", "", emptyList()), ProgramDay("B", "B", "", emptyList()))
        val (ordinary, _) = VolumeBalance.balance(days, answers, emptySet(), emptySet())
        val (reserved, reasons) = VolumeBalance.balance(days, answers, emptySet(), emptySet(),
            reservedSeconds = mapOf("A" to 200))
        assertEquals(listOf(4, 0), ordinary.map { it.exercises.sumOf { slot -> slot.targetSets } })
        assertEquals(listOf(0, 4), reserved.map { it.exercises.sumOf { slot -> slot.targetSets } })
        assertTrue(reasons.isEmpty())
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
