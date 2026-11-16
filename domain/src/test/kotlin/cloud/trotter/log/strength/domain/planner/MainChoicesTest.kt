package cloud.trotter.log.strength.domain.planner

import cloud.trotter.log.strength.domain.library.ExerciseLibrary
import cloud.trotter.log.strength.domain.library.TrackingType
import cloud.trotter.log.strength.domain.library.availableWith
import cloud.trotter.log.strength.domain.library.tracking
import cloud.trotter.log.strength.domain.progression.LiftGoal
import cloud.trotter.log.strength.domain.progression.SlotRole
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MainChoicesTest {
    @Test fun `each row starts with its default`() {
        for (preset in EquipmentPreset.entries) {
            assertEquals(Planner.defaultMains(preset.equipment),
                Planner.mainChoices(preset.equipment).mapNotNull { it.firstOrNull() }, preset.name)
        }
    }

    @Test fun `every choice is a main the planner accepts`() {
        for (preset in EquipmentPreset.entries) {
            val choices = Planner.mainChoices(preset.equipment)
            for ((row, options) in choices.withIndex()) for (choice in options) {
                val heads = choices.map { it.firstOrNull() }.toMutableList()
                heads[row] = choice
                val draft = Planner.plan(SetupAnswers(goal = LiftGoal.STRENGTH, equipment = preset.equipment,
                    mains = heads.filterNotNull()))
                assertEquals(1, draft.program.days.flatMap { it.exercises }.count {
                    it.exerciseId == choice && it.rule?.role == SlotRole.MAIN
                }, "$preset $choice")
                val entry = ExerciseLibrary.get(choice)
                assertFalse(entry.tracking == TrackingType.TIMED)
                assertTrue(entry.availableWith(preset.equipment))
            }
        }
    }

    @Test fun `excluded lifts are not offered`() {
        val equipment = EquipmentPreset.FULL_GYM.equipment
        val excluded = setOf("bb_back_squat")
        val row = Planner.mainChoices(equipment, excluded).first()
        assertFalse("bb_back_squat" in row)
        assertEquals(Planner.defaultMains(equipment, excluded).first(), row.first())
    }

    @Test fun `weighted lifts come before reps lifts after the default`() {
        for (preset in EquipmentPreset.entries) for (row in Planner.mainChoices(preset.equipment)) {
            val tracking = row.drop(1).map { ExerciseLibrary.get(it).tracking }
            assertEquals(tracking.sortedBy { if (it == TrackingType.WEIGHTED) 0 else 1 }, tracking)
        }
    }
}
