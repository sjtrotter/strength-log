package cloud.trotter.log.strength.data.serialization

import cloud.trotter.log.strength.data.OldWizardPlans
import cloud.trotter.log.strength.domain.model.Equipment
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class V1MainsTest {

    @Test fun `pinned frozen mains`() {
        fun check(days: Int, split: V1Split, anchors: V1Anchors, deadlift: V1Deadlift,
            equipment: Set<Equipment>, vararg expected: String) {
            assertEquals(expected.toList(), V1Mains.of(days, split, anchors, deadlift, equipment),
                "$days $split $anchors $deadlift $equipment")
        }
        val all = Equipment.entries.toSet()
        check(4, V1Split.FULL_BODY, V1Anchors.PROTOTYPE, V1Deadlift.TRAP_BAR, all,
            "bb_back_squat", "bb_bench", "trap_dl", "incline_db")
        check(4, V1Split.FULL_BODY, V1Anchors.PROTOTYPE, V1Deadlift.TRAP_BAR, setOf(Equipment.MACHINE),
            "hack_squat", "machine_chest", "machine_hip_thrust", "pec_deck")
        check(4, V1Split.FULL_BODY, V1Anchors.PROTOTYPE, V1Deadlift.TRAP_BAR,
            setOf(Equipment.DUMBBELL, Equipment.BENCH, Equipment.BODYWEIGHT),
            "goblet_squat", "db_bench", "db_rdl", "incline_db")
        check(4, V1Split.UPPER_LOWER, V1Anchors.PROTOTYPE, V1Deadlift.TRAP_BAR, all,
            "bb_bench", "trap_dl", "bb_back_squat")
        check(6, V1Split.PPL, V1Anchors.BIG_4, V1Deadlift.CONVENTIONAL, all,
            "bb_bench", "bb_row", "conv_dl", "bb_back_squat")
        check(3, V1Split.PPL, V1Anchors.PROTOTYPE, V1Deadlift.TRAP_BAR, all, "bb_bench", "bb_row", "trap_dl")
        check(2, V1Split.FULL_BODY, V1Anchors.FIVE_THREE_ONE, V1Deadlift.SUMO, all, "bb_back_squat", "bb_bench")
        check(6, V1Split.FULL_BODY, V1Anchors.PROTOTYPE, V1Deadlift.TRAP_BAR, all,
            "bb_back_squat", "bb_bench", "trap_dl", "incline_db")
        for (days in listOf(2, 5)) check(days, V1Split.PPLUL, V1Anchors.PROTOTYPE, V1Deadlift.TRAP_BAR,
            setOf(Equipment.MACHINE), "machine_chest", "cs_row", "machine_hip_thrust")
        check(3, V1Split.FULL_BODY, V1Anchors.PROTOTYPE, V1Deadlift.TRAP_BAR, setOf(Equipment.KETTLEBELL),
            "kb_goblet_squat", "kb_floor_press", "kb_rdl")
        check(4, V1Split.FULL_BODY, V1Anchors.PROTOTYPE, V1Deadlift.TRAP_BAR, emptySet())
    }

    @Test fun `fixture recipes read as their facts`() {
        for (fixture in OldWizardPlans.ALL) {
            val recipe = assertNotNull(PlanRecipeJson.read(fixture.recipeJson))
            assertEquals(fixture.equipment, recipe.equipment)
            assertEquals(fixture.goalsLb, recipe.goalsLb)
            assertEquals(fixture.mains.toSet(), recipe.mainLiftIds())
        }
    }
}
