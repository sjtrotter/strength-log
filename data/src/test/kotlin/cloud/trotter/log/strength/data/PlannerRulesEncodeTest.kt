package cloud.trotter.log.strength.data

import cloud.trotter.log.strength.data.serialization.SlotRuleJson
import cloud.trotter.log.strength.domain.planner.EquipmentPreset
import cloud.trotter.log.strength.domain.planner.LiftExperience
import cloud.trotter.log.strength.domain.planner.Planner
import cloud.trotter.log.strength.domain.planner.SetupAnswers
import cloud.trotter.log.strength.domain.progression.LiftGoal
import cloud.trotter.log.strength.domain.units.WeightUnit
import kotlin.test.assertTrue
import org.junit.Test

/** Every rule the planner writes must pass the stored-rule codec, or creating the plan fails. */
class PlannerRulesEncodeTest {
    @Test fun `every lift setup writes rules the codec accepts`() {
        val failures = mutableListOf<String>()
        for (goal in LiftGoal.entries) for (days in 1..6) for (minutes in listOf(30, 45, 60, 75, 90))
            for (preset in EquipmentPreset.entries) for (experience in LiftExperience.entries)
                for (older in listOf(false, true)) for (balanceDays in listOf(false, true)) {
                    val answers = SetupAnswers(goal = goal, days = days, minutes = minutes, equipment = preset.equipment,
                        experience = experience, olderAdult = older, balanceDays = balanceDays)
                    encodeRules(answers, failures)
                }
        assertTrue(failures.isEmpty(), failures.joinToString("\n"))
    }

    @Test fun `alternative mains write rules the codec accepts in sampled lift setups`() {
        val failures = mutableListOf<String>()
        for (preset in EquipmentPreset.entries) {
            val choices = Planner.mainChoices(preset.equipment)
            val units = if (preset == EquipmentPreset.FULL_GYM) WeightUnit.entries else listOf(WeightUnit.LB)
            for (unit in units) for (goal in LiftGoal.entries) for (days in listOf(1, 3, 6))
                for (minutes in listOf(30, 60, 90)) for ((row, options) in choices.withIndex())
                    for (choice in options.drop(1)) {
                        val mains = choices.map { it.firstOrNull() }.toMutableList()
                        mains[row] = choice
                        encodeRules(SetupAnswers(goal = goal, days = days, minutes = minutes,
                            equipment = preset.equipment, unit = unit, experience = LiftExperience.A_YEAR_OR_MORE,
                            olderAdult = true, balanceDays = true, mains = mains.filterNotNull()), failures)
                    }
        }
        assertTrue(failures.isEmpty(), failures.joinToString("\n"))
    }

    private fun encodeRules(answers: SetupAnswers, failures: MutableList<String>) {
        val rules = Planner.plan(answers).program.days.flatMap { day ->
            day.exercises.flatMap { listOfNotNull(it.rule, it.superset?.rule) }
        }
        for (rule in rules) runCatching { SlotRuleJson.encode(rule) }.onFailure {
            if (failures.size < 5) failures += "$answers: $rule"
        }
    }
}
