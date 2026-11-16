package cloud.trotter.log.strength.data

import cloud.trotter.log.strength.data.serialization.SlotRuleJson
import cloud.trotter.log.strength.domain.planner.EquipmentPreset
import cloud.trotter.log.strength.domain.planner.LiftExperience
import cloud.trotter.log.strength.domain.planner.Planner
import cloud.trotter.log.strength.domain.planner.SetupAnswers
import cloud.trotter.log.strength.domain.progression.LiftGoal
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
                    val rules = Planner.plan(answers).program.days.flatMap { day ->
                        day.exercises.flatMap { listOfNotNull(it.rule, it.superset?.rule) }
                    }
                    for (rule in rules) runCatching { SlotRuleJson.encode(rule) }.onFailure {
                        if (failures.size < 5) failures += "$answers: $rule"
                    }
                }
        assertTrue(failures.isEmpty(), failures.joinToString("\n"))
    }
}
