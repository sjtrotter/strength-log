package cloud.trotter.log.strength.domain.library

import cloud.trotter.log.strength.domain.model.Equipment
import cloud.trotter.log.strength.domain.model.MovementPattern
import cloud.trotter.log.strength.domain.planner.EquipmentPreset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MuscleTableTest {
    private fun credits(id: String, primary: Set<Muscle>, secondary: Set<Muscle> = emptySet()) {
        val entry = ExerciseLibrary.get(id)
        assertEquals(primary, entry.primary, "$id primary")
        assertEquals(secondary, entry.secondary, "$id secondary")
    }

    @Test fun `only the six drills and holds have no primary muscle`() {
        assertEquals(setOf("dead_hang", "pogo_hop", "a_skip", "bound", "box_jump", "single_leg_stance"),
            ExerciseLibrary.entries.filter { it.primary.isEmpty() }.map { it.id }.toSet())
    }

    @Test fun `overhead presses credit no chest except the landmine press`() {
        for (entry in ExerciseLibrary.byPattern(MovementPattern.V_PUSH)) {
            credits(entry.id, setOf(Muscle.FRONT_DELT),
                if (entry.id == "landmine_press") setOf(Muscle.CHEST, Muscle.TRICEPS) else setOf(Muscle.TRICEPS))
        }
    }

    @Test fun `owner muscle exceptions and pattern defaults`() {
        credits("trap_dl", setOf(Muscle.QUADS, Muscle.GLUTES), setOf(Muscle.HAMSTRINGS, Muscle.UPPER_BACK))
        credits("conv_dl", setOf(Muscle.GLUTES, Muscle.HAMSTRINGS), setOf(Muscle.QUADS, Muscle.UPPER_BACK))
        credits("rdl", setOf(Muscle.HAMSTRINGS, Muscle.GLUTES))
        credits("hip_thrust", setOf(Muscle.GLUTES), setOf(Muscle.HAMSTRINGS))
        credits("bb_bench", setOf(Muscle.CHEST), setOf(Muscle.FRONT_DELT, Muscle.TRICEPS))
        credits("bb_row", setOf(Muscle.UPPER_BACK, Muscle.LATS), setOf(Muscle.BICEPS, Muscle.REAR_DELT))
        credits("dips", setOf(Muscle.CHEST, Muscle.TRICEPS), setOf(Muscle.FRONT_DELT))
        credits("cgbp", setOf(Muscle.TRICEPS), setOf(Muscle.CHEST, Muscle.FRONT_DELT))
        credits("farmers_carry", setOf(Muscle.CORE), setOf(Muscle.UPPER_BACK))
        credits("sit_to_stand", setOf(Muscle.QUADS, Muscle.GLUTES))
        credits("bound", emptySet(), setOf(Muscle.QUADS, Muscle.GLUTES))
        credits("pogo_hop", emptySet(), setOf(Muscle.CALVES))
        credits("seated_calf", setOf(Muscle.CALVES))
    }

    @Test fun `each side and doses are the exact owner sets`() {
        assertEquals(setOf("single_leg_stance", "side_plank", "suitcase_carry"),
            ExerciseLibrary.entries.filter { it.eachSide }.map { it.id }.toSet())
        assertEquals(mapOf("pogo_hop" to Dose(3, 60), "a_skip" to Dose(2, 60), "bound" to Dose(3, 90),
            "box_jump" to Dose(3, 90), "single_leg_stance" to Dose(2, 30), "sit_to_stand" to Dose(2, 60),
            "dead_hang" to Dose(3, 60)),
            ExerciseLibrary.entries.mapNotNull { entry -> entry.dose?.let { entry.id to it } }.toMap())
    }

    @Test fun `six new entries keep their catalog targets and ranks`() {
        val expected = listOf(
            Triple("pogo_hop", GoalSource.Reps(15), 1), Triple("a_skip", GoalSource.Reps(20), 2),
            Triple("bound", GoalSource.Reps(6), 3), Triple("box_jump", GoalSource.Reps(5), 4),
            Triple("single_leg_stance", GoalSource.Time(30), 1), Triple("sit_to_stand", GoalSource.Reps(10), 15),
        )
        assertEquals(expected.map { it.first }, ExerciseLibrary.entries.takeLast(6).map { it.id })
        for ((id, goal, rank) in expected) {
            val entry = ExerciseLibrary.get(id)
            assertEquals(goal, entry.goal, id)
            assertEquals(rank, entry.subRank, id)
            assertEquals(when (id) {
                "sit_to_stand" -> MovementPattern.SQUAT_BILATERAL
                "single_leg_stance" -> MovementPattern.BALANCE
                else -> MovementPattern.PLYOMETRIC
            }, entry.pattern, id)
            assertEquals(listOf(Equipment.BODYWEIGHT), entry.equipment, id)
            assertFalse(entry.perHand, id)
            assertFalse(entry.mainCapable, id)
        }
        assertEquals("goblet_squat", ExerciseLibrary.get("sit_to_stand").weightedPairId)
    }

    @Test fun `availability includes exclusions and all equipment requirements`() {
        val bench = ExerciseLibrary.get("bb_bench")
        assertTrue(bench.availableWith(EquipmentPreset.FULL_GYM.equipment))
        assertFalse(bench.availableWith(EquipmentPreset.FULL_GYM.equipment, setOf("bb_bench")))
        assertFalse(bench.availableWith(EquipmentPreset.DUMBBELLS.equipment))
    }
}
