package cloud.trotter.log.strength.domain.planner

import cloud.trotter.log.strength.domain.library.ExerciseLibrary
import cloud.trotter.log.strength.domain.library.Muscle
import cloud.trotter.log.strength.domain.library.availableWith
import cloud.trotter.log.strength.domain.model.MovementPattern
import cloud.trotter.log.strength.domain.model.ProgramDay
import cloud.trotter.log.strength.domain.model.ProgramDayKind
import cloud.trotter.log.strength.domain.progression.LiftGoal
import cloud.trotter.log.strength.domain.progression.SlotRole
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PlannerInvariantsTest {
    private val exposureGroups = listOf(
        setOf(Muscle.QUADS), setOf(Muscle.HAMSTRINGS), setOf(Muscle.GLUTES), setOf(Muscle.CHEST),
        setOf(Muscle.LATS, Muscle.UPPER_BACK), setOf(Muscle.FRONT_DELT), setOf(Muscle.BICEPS),
        setOf(Muscle.TRICEPS), setOf(Muscle.CORE),
    )
    private fun credits(day: ProgramDay): Set<Muscle> = day.exercises.flatMap { slot ->
        listOfNotNull(slot.exerciseId, slot.superset?.exerciseId).flatMap { id ->
            val entry = ExerciseLibrary.get(id)
            entry.primary + entry.secondary
        }
    }.toSet()

    private fun check(answers: SetupAnswers, label: String) {
        val draft = Planner.plan(answers)
        val chosen = Planner.defaultMains(answers.equipment, answers.excluded)
        val strength = draft.program.strengthDays
        assertTrue(SetCount.weekly(draft.program).values.all { it <= 20.0 }, "$label weekly cap")
        assertEquals(answers.days, strength.size, label)
        assertEquals(if (answers.days >= 4) 1 else 0,
            draft.program.days.count { it.kind == ProgramDayKind.REST }, label)
        if (answers.days >= 4) {
            assertEquals(ProgramDayKind.REST, draft.program.days[3].kind, label)
            assertEquals("D", draft.program.days[3].id, label)
        }
        val actualKeys = mutableSetOf<SlotKey>()
        for (day in strength) {
            assertTrue(day.exercises.isNotEmpty(), "$label ${day.id} empty")
            val estimate = SessionBudget.seconds(day, answers.goal)
            if (draft.reasons.none { it is PlanReason.ShortSession && it.dayId == day.id && it.step >= 1 }) {
                assertTrue(estimate <= answers.minutes * 60, "$label ${day.id} fill budget")
            }
            val over = draft.reasons.filterIsInstance<PlanReason.OverBudget>().singleOrNull { it.dayId == day.id }
            if (estimate > answers.minutes * 60) {
                assertEquals(estimate - answers.minutes * 60, over?.seconds, "$label ${day.id} budget")
            } else assertTrue(over == null, "$label ${day.id} unexpected OverBudget")
            for ((index, slot) in day.exercises.withIndex()) {
                actualKeys += SlotKey(day.id, index)
                assertEquals(slot.targetSets, draft.working.getValue(SlotKey(day.id, index)).size, "$label rows")
                val entries = listOf(slot.exerciseId to slot.rule) + listOfNotNull(slot.superset?.let { it.exerciseId to it.rule })
                for ((id, rule) in entries) {
                    assertTrue(ExerciseLibrary.get(id).availableWith(answers.equipment, answers.excluded), "$label $id unavailable")
                    val required = requireNotNull(rule) { "$label $id missing rule" }
                    if (answers.experience == LiftExperience.NEW) assertTrue(required.rir >= 2, "$label $id New RIR")
                    if (answers.olderAdult) assertTrue(required.rir >= 3, "$label $id older RIR")
                }
            }
        }
        assertEquals(actualKeys, draft.working.keys, "$label opening keys")
        for (id in chosen) assertEquals(1, strength.flatMap { it.exercises }.count {
            it.exerciseId == id && it.rule?.role == SlotRole.MAIN
        }, "$label $id main lost")
        if (draft.reasons.none { it is PlanReason.ShortSession }) {
            val available = ExerciseLibrary.entries.filter {
                it.pattern != MovementPattern.PLYOMETRIC && it.pattern != MovementPattern.BALANCE &&
                    it.availableWith(answers.equipment, answers.excluded)
            }.flatMap { it.primary + it.secondary }.toSet()
            val dayCredits = strength.map(::credits)
            if (answers.days >= 2) assertTrue(strength.size >= 2, label)
            for (group in exposureGroups.filter { it.any(available::contains) }) {
                assertTrue(dayCredits.any { muscles -> group.any(muscles::contains) }, "$label $group uncovered")
                assertTrue(dayCredits.count { muscles -> group.any(muscles::contains) } >= minOf(2, answers.days),
                    "$label $group exposure floor")
            }
        }
    }

    @Test fun `lift invariants hold over all 3240 setup combinations`() {
        var plans = 0
        for (goal in LiftGoal.entries) for (days in 1..6) for (minutes in listOf(30, 45, 60, 75, 90)) {
            for (preset in EquipmentPreset.entries) for (experience in LiftExperience.entries) for (older in listOf(false, true)) {
                val label = "$goal $days days $minutes min $preset $experience older=$older"
                check(SetupAnswers(goal = goal, days = days, minutes = minutes, equipment = preset.equipment,
                    experience = experience, olderAdult = older), label)
                plans++
            }
        }
        assertEquals(3240, plans)
    }

    @Test fun `excluded mains stay out of every slot and partner across the Full gym grid`() {
        for (goal in LiftGoal.entries) for (days in 1..6) for (minutes in listOf(30, 45, 60, 75, 90)) {
            for (experience in LiftExperience.entries) for (older in listOf(false, true)) {
                check(SetupAnswers(goal = goal, days = days, minutes = minutes,
                    experience = experience, olderAdult = older, excluded = setOf("bb_bench", "bb_back_squat")),
                    "excluded $goal $days days $minutes min $experience older=$older")
            }
        }
    }

    @Test fun `weekly set floors hold over 2160 setup combinations`() {
        val seven = setOf(Muscle.QUADS, Muscle.HAMSTRINGS, Muscle.GLUTES, Muscle.CHEST,
            Muscle.LATS, Muscle.UPPER_BACK, Muscle.FRONT_DELT)
        val musclesByGoal = mapOf(
            LiftGoal.STRENGTH to seven,
            LiftGoal.GENERAL to seven,
            LiftGoal.MUSCLE to seven + setOf(Muscle.BICEPS, Muscle.TRICEPS),
        )
        val catalogBound = mutableSetOf<String>()
        var plans = 0
        for (goal in LiftGoal.entries) for (days in 2..6) for (minutes in listOf(45, 60, 75, 90)) {
            for (preset in EquipmentPreset.entries) for (experience in LiftExperience.entries) for (older in listOf(false, true)) {
                val label = "$goal $days days $minutes min $preset $experience older=$older"
                val answers = SetupAnswers(goal = goal, days = days, minutes = minutes, equipment = preset.equipment,
                    experience = experience, olderAdult = older)
                val draft = Planner.plan(answers)
                plans++
                if (draft.reasons.any { it is PlanReason.ShortSession }) continue
                val floor = when {
                    older -> 6
                    goal == LiftGoal.STRENGTH -> 6
                    goal == LiftGoal.GENERAL -> 4
                    experience == LiftExperience.A_YEAR_OR_MORE -> 12
                    else -> 10
                }
                val weekly = SetCount.weekly(draft.program)
                val available = ExerciseLibrary.entries.filter { it.availableWith(answers.equipment, answers.excluded) }
                val chosen = Planner.defaultMains(answers.equipment, answers.excluded).toSet()
                for (muscle in musclesByGoal.getValue(goal)) {
                    if (available.none { muscle in it.primary }) continue
                    if (weekly.getOrDefault(muscle, 0.0) >= floor) continue
                    val eligible = available.filter { muscle in it.primary && (!it.mainCapable || it.id in chosen) &&
                        ExerciseLibrary.bodyweightPairFor(it.id) == null }
                    assertTrue(eligible.all { entry ->
                        draft.program.strengthDays.all { day ->
                            val slots = day.exercises.filter { it.exerciseId == entry.id || it.superset?.exerciseId == entry.id }
                            slots.isNotEmpty() && slots.all { it.targetSets >= 5 }
                        }
                    }, "$label $muscle below $floor at ${weekly[muscle]} and not catalog-bound")
                    catalogBound += "$label $muscle"
                }
            }
        }
        assertEquals(2160, plans)
        assertEquals(setOf(
            "MUSCLE 2 days 90 min KETTLEBELLS A_YEAR_OR_MORE older=false LATS",
            "MUSCLE 2 days 90 min KETTLEBELLS A_YEAR_OR_MORE older=false UPPER_BACK",
        ), catalogBound)
    }
}
