package cloud.trotter.log.strength.domain.routine

import cloud.trotter.log.strength.domain.library.ExerciseEntry
import cloud.trotter.log.strength.domain.library.ExerciseLibrary
import cloud.trotter.log.strength.domain.library.GoalSource
import cloud.trotter.log.strength.domain.library.Muscle
import cloud.trotter.log.strength.domain.model.CardioActivity
import cloud.trotter.log.strength.domain.model.CardioBlock
import cloud.trotter.log.strength.domain.model.CardioRole
import cloud.trotter.log.strength.domain.model.CardioSetSpec
import cloud.trotter.log.strength.domain.model.Equipment
import cloud.trotter.log.strength.domain.model.Measure
import cloud.trotter.log.strength.domain.model.MovementPattern
import cloud.trotter.log.strength.domain.planner.EquipmentPreset
import cloud.trotter.log.strength.domain.routine.Move.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RoutinesTest {
    private val full = EquipmentPreset.FULL_GYM.equipment
    private val bw = EquipmentPreset.BODYWEIGHT.equipment
    private fun work(vararg ids: String) = ids.map { CreditedExercise(ExerciseLibrary.get(it), 3) }
    private val legs = work("bb_back_squat", "rdl", "lying_curl", "standing_calf", "plank")
    private val upper = work("bb_bench", "bb_row", "ohp", "pullup")
    private val bodyweight = work("pushup", "pullup", "skater_squat", "plank")
    private val custom = listOf(CreditedExercise(ExerciseEntry("custom_x", "X", MovementPattern.H_PUSH,
        listOf(Equipment.BODYWEIGHT), false, GoalSource.Reps(10), 1000), 3))
    private fun block(activity: CardioActivity) = CardioBlock(activity, listOf(CardioSetSpec(CardioRole.WORK, Measure.Time(1800))))

    // (F)
    @Test fun `leg day follows weighted muscle credits`() {
        assertEquals(listOf(BIKE_OR_ROWER, LEG_SWINGS, BODYWEIGHT_SQUATS, CALF_RAISES, CAT_COW), Routines.warmUp(legs, null, full))
        val cool = Routines.coolDown(legs, null, full)
        assertEquals(listOf(HAMSTRING_STRETCH, FIGURE_FOUR, QUAD_STRETCH, CALF_STRETCH, COBRA), cool)
        assertEquals(270, Routines.seconds(cool))
    }

    // (F)
    @Test fun `upper day has distinct dynamic moves`() {
        val warm = Routines.warmUp(upper, null, full)
        assertEquals(listOf(BIKE_OR_ROWER, UPPER_BACK_ROTATIONS, ARM_CIRCLES, PUSH_UPS, ARM_SWINGS), warm)
        val cool = Routines.coolDown(upper, null, full)
        assertEquals(listOf(HANG, DOORWAY_CHEST, WALL_BICEPS, OVERHEAD_TRICEPS, CROSS_BODY), cool)
        assertEquals(270, Routines.seconds(cool))
        assertTrue(warm.filter { it.kind == MoveKind.DYNAMIC }.intersect(Routines.warmUp(legs, null, full).toSet()).isEmpty())
    }

    // (F)
    @Test fun `bodyweight gear selects the available hang or floor stretch`() {
        val warm = Routines.warmUp(bodyweight, null, bw)
        assertEquals(listOf(JACKS_OR_MARCH, PUSH_UPS, UPPER_BACK_ROTATIONS, BODYWEIGHT_SQUATS, CAT_COW, ARM_SWINGS), warm)
        assertTrue(warm.all { it.equipment.isEmpty() })
        val cool = Routines.coolDown(bodyweight, null, bw)
        assertEquals(listOf(DOORWAY_CHEST, HANG, QUAD_STRETCH, FIGURE_FOUR, COBRA, WALL_BICEPS), cool)
        assertEquals(300, Routines.seconds(cool))
        assertEquals(cool.map { if (it == HANG) CHILDS_POSE else it }, Routines.coolDown(bodyweight, null, setOf(Equipment.BODYWEIGHT)))
    }

    // (F)
    @Test fun `cardio families select their moves and respect an opening warmup set`() {
        val run = block(CardioActivity.ROAD_RUN)
        val dynamics = listOf(LEG_SWINGS, HIGH_KNEES, BUTT_KICKS, CALF_RAISES)
        assertEquals(listOf(EASY_JOG_OR_WALK) + dynamics, Routines.warmUp(emptyList(), run, full))
        val cool = Routines.coolDown(emptyList(), run, full)
        assertEquals(listOf(HAMSTRING_STRETCH, CALF_STRETCH, QUAD_STRETCH, FIGURE_FOUR), cool)
        assertEquals(240, Routines.seconds(cool))
        assertEquals(dynamics, Routines.warmUp(emptyList(), run.copy(sets = listOf(CardioSetSpec(CardioRole.WARMUP, Measure.Time(300))) + run.sets), full))
        assertEquals(listOf(EASY_CARDIO, BODYWEIGHT_SQUATS, UPPER_BACK_ROTATIONS, LEG_SWINGS, REVERSE_LUNGES),
            Routines.warmUp(emptyList(), block(CardioActivity.ROWER), full))
    }

    // (F)
    @Test fun `superset partners contribute their own muscles`() {
        val pair = work("db_bench", "cable_row")
        assertEquals(listOf(BIKE_OR_ROWER, PUSH_UPS, UPPER_BACK_ROTATIONS, Y_T_RAISES, ARM_SWINGS), Routines.warmUp(pair, null, full))
        val cool = Routines.coolDown(pair, null, full)
        assertEquals(listOf(DOORWAY_CHEST, HANG, CROSS_BODY, WALL_BICEPS, OVERHEAD_TRICEPS), cool)
        assertEquals(270, Routines.seconds(cool))
        val bench = work("db_bench")
        assertEquals(listOf(BIKE_OR_ROWER, PUSH_UPS, ARM_SWINGS, SCAP_PUSH_UPS, ARM_CIRCLES), Routines.warmUp(bench, null, full))
        val benchCool = Routines.coolDown(bench, null, full)
        assertEquals(listOf(DOORWAY_CHEST, OVERHEAD_TRICEPS, WALL_BICEPS), benchCool)
        assertEquals(180, Routines.seconds(benchCool))
    }

    // (F)
    @Test fun `custom only day uses whole body fallback`() {
        assertEquals(listOf(BIKE_OR_ROWER, BODYWEIGHT_SQUATS, LEG_SWINGS, PUSH_UPS, Y_T_RAISES, CAT_COW), Routines.warmUp(custom, null, full))
        val cool = Routines.coolDown(custom, null, full)
        assertEquals(listOf(QUAD_STRETCH, FIGURE_FOUR, HAMSTRING_STRETCH, DOORWAY_CHEST, CROSS_BODY), cool)
        assertEquals(300, Routines.seconds(cool))
    }

    @Test fun `muscles rank by working sets then enum order`() {
        assertEquals(listOf(Muscle.GLUTES, Muscle.HAMSTRINGS, Muscle.QUADS), Routines.rankedMuscles(
            listOf(CreditedExercise(ExerciseLibrary.get("rdl"), 5), CreditedExercise(ExerciseLibrary.get("bb_back_squat"), 1)), null))
        assertEquals(listOf(Muscle.CHEST, Muscle.LATS, Muscle.UPPER_BACK, Muscle.FRONT_DELT, Muscle.REAR_DELT, Muscle.BICEPS, Muscle.TRICEPS),
            Routines.rankedMuscles(work("bb_bench", "bb_row"), null))
        assertEquals(listOf(Muscle.QUADS, Muscle.LATS, Muscle.UPPER_BACK, Muscle.GLUTES, Muscle.HAMSTRINGS),
            Routines.rankedMuscles(emptyList(), block(CardioActivity.ROWER)))
    }

    @Test fun `catalog covers all muscles and day kinds`() {
        assertEquals(31, Move.entries.size)
        assertEquals(4, Move.entries.count { it.kind == MoveKind.GENERAL })
        assertEquals(15, Move.entries.count { it.kind == MoveKind.DYNAMIC })
        assertEquals(12, Move.entries.count { it.kind == MoveKind.STRETCH })
        for (muscle in Muscle.entries) {
            assertTrue(Move.entries.any { muscle in it.muscles && it.kind == MoveKind.DYNAMIC && RoutineDay.LIFT in it.days && it.equipment.isEmpty() })
            assertTrue(Move.entries.any { muscle in it.muscles && it.kind == MoveKind.STRETCH && it.equipment.isEmpty() })
        }
        for (move in Move.entries) when (move.kind) {
            MoveKind.GENERAL -> assertEquals(MoveDose.Time(120), move.dose)
            MoveKind.DYNAMIC -> Unit
            MoveKind.STRETCH -> assertEquals(30, (move.dose as MoveDose.Time).seconds)
        }
        for (day in RoutineDay.entries) assertEquals(1, Move.entries.count { it.kind == MoveKind.GENERAL && day in it.days && it.equipment.isEmpty() })
    }

    @Test fun `all presets and day kinds stay within bounds and available gear`() {
        val days = listOf(legs to null, upper to null, bodyweight to null, custom to null) +
            listOf(CardioActivity.ROAD_RUN, CardioActivity.OUTDOOR_RIDE, CardioActivity.ROWER, CardioActivity.ELLIPTICAL).map { emptyList<CreditedExercise>() to block(it) }
        for (preset in EquipmentPreset.entries) for ((work, cardio) in days) {
            val warm = Routines.warmUp(work, cardio, preset.equipment)
            val cool = Routines.coolDown(work, cardio, preset.equipment)
            assertTrue(warm.count { it.kind == MoveKind.DYNAMIC } in 4..5)
            assertTrue(warm.count { it.kind == MoveKind.GENERAL } <= 1)
            assertTrue(warm.drop(1).none { it.kind == MoveKind.GENERAL })
            assertTrue(Routines.seconds(cool) in 180..300)
            assertTrue((warm + cool).all { move -> move.equipment.all { it in preset.equipment } })
        }
    }
}
