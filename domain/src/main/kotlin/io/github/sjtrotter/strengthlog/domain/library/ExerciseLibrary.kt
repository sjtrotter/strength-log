package io.github.sjtrotter.strengthlog.domain.library

import io.github.sjtrotter.strengthlog.domain.model.Equipment.BARBELL
import io.github.sjtrotter.strengthlog.domain.model.Equipment.BENCH
import io.github.sjtrotter.strengthlog.domain.model.Equipment.BODYWEIGHT
import io.github.sjtrotter.strengthlog.domain.model.Equipment.CABLE
import io.github.sjtrotter.strengthlog.domain.model.Equipment.DUMBBELL
import io.github.sjtrotter.strengthlog.domain.model.Equipment.EZ_BAR
import io.github.sjtrotter.strengthlog.domain.model.Equipment.MACHINE
import io.github.sjtrotter.strengthlog.domain.model.Equipment.PULLUP_BAR
import io.github.sjtrotter.strengthlog.domain.model.Equipment.RACK
import io.github.sjtrotter.strengthlog.domain.model.Equipment.TRAP_BAR
import io.github.sjtrotter.strengthlog.domain.model.MovementPattern
import io.github.sjtrotter.strengthlog.domain.model.MovementPattern.BICEPS
import io.github.sjtrotter.strengthlog.domain.model.MovementPattern.CALF_GASTROC
import io.github.sjtrotter.strengthlog.domain.model.MovementPattern.CALF_SOLEUS
import io.github.sjtrotter.strengthlog.domain.model.MovementPattern.CORE_ANTI_EXT
import io.github.sjtrotter.strengthlog.domain.model.MovementPattern.CORE_ANTI_ROT
import io.github.sjtrotter.strengthlog.domain.model.MovementPattern.CORE_FLEX
import io.github.sjtrotter.strengthlog.domain.model.MovementPattern.HINGE
import io.github.sjtrotter.strengthlog.domain.model.MovementPattern.H_PULL
import io.github.sjtrotter.strengthlog.domain.model.MovementPattern.H_PUSH
import io.github.sjtrotter.strengthlog.domain.model.MovementPattern.KNEE_EXTENSION
import io.github.sjtrotter.strengthlog.domain.model.MovementPattern.KNEE_FLEXION
import io.github.sjtrotter.strengthlog.domain.model.MovementPattern.REAR_DELT
import io.github.sjtrotter.strengthlog.domain.model.MovementPattern.SIDE_DELT
import io.github.sjtrotter.strengthlog.domain.model.MovementPattern.SINGLE_LEG
import io.github.sjtrotter.strengthlog.domain.model.MovementPattern.SQUAT_BILATERAL
import io.github.sjtrotter.strengthlog.domain.model.MovementPattern.TRICEPS
import io.github.sjtrotter.strengthlog.domain.model.MovementPattern.V_PULL
import io.github.sjtrotter.strengthlog.domain.model.MovementPattern.V_PUSH
import io.github.sjtrotter.strengthlog.domain.model.StandardLift
import io.github.sjtrotter.strengthlog.domain.model.StandardLift.BENCH as STD_BENCH
import io.github.sjtrotter.strengthlog.domain.model.StandardLift.DEADLIFT
import io.github.sjtrotter.strengthlog.domain.model.StandardLift.INCLINE
import io.github.sjtrotter.strengthlog.domain.model.StandardLift.OHP
import io.github.sjtrotter.strengthlog.domain.model.StandardLift.ROW
import io.github.sjtrotter.strengthlog.domain.model.StandardLift.SQUAT

/**
 * Static in-code exercise catalog (spec §5). This is the SSOT for every
 * suggested weight and the substitution graph. Issue #3 extends [entries]
 * toward ~200 rows without reshaping this object.
 */
object ExerciseLibrary {

    private fun std(lift: StandardLift, tune: Double? = null) = GoalSource.Std(lift, tune)

    private fun frac(fraction: Double, lift: StandardLift) = GoalSource.FracOfStd(fraction, lift)

    private fun flat(weightLb: Double) = GoalSource.Flat(weightLb)

    val entries: List<ExerciseEntry> = listOf(
        ExerciseEntry("bb_back_squat", "Barbell Back Squat", SQUAT_BILATERAL, listOf(BARBELL, RACK), false, std(SQUAT), 1),
        ExerciseEntry("hack_squat", "Hack Squat", SQUAT_BILATERAL, listOf(MACHINE), false, flat(180.0), 2),
        ExerciseEntry("leg_press", "Leg Press", SQUAT_BILATERAL, listOf(MACHINE), false, frac(1.4, SQUAT), 3),
        ExerciseEntry("goblet_squat", "Goblet Squat", SQUAT_BILATERAL, listOf(DUMBBELL), false, flat(70.0), 4),
        ExerciseEntry("front_squat", "Front Squat", SQUAT_BILATERAL, listOf(BARBELL, RACK), false, frac(0.8, SQUAT), 5),
        ExerciseEntry("smith_squat", "Smith Machine Squat", SQUAT_BILATERAL, listOf(MACHINE), false, frac(0.9, SQUAT), 6),

        ExerciseEntry("bss", "Bulgarian Split Squat", SINGLE_LEG, listOf(DUMBBELL, BENCH), true, flat(35.0), 1),
        ExerciseEntry("walking_lunge", "Walking Lunge", SINGLE_LEG, listOf(DUMBBELL), true, flat(30.0), 2),
        ExerciseEntry("reverse_lunge", "Reverse Lunge", SINGLE_LEG, listOf(DUMBBELL), true, flat(30.0), 3),
        ExerciseEntry("step_up", "Step-Up", SINGLE_LEG, listOf(DUMBBELL, BENCH), true, flat(25.0), 4),

        ExerciseEntry("trap_dl", "Trap-Bar Deadlift", HINGE, listOf(TRAP_BAR), false, std(DEADLIFT), 1),
        ExerciseEntry("conv_dl", "Conventional Deadlift", HINGE, listOf(BARBELL), false, std(DEADLIFT, tune = 1.0), 2),
        ExerciseEntry("sumo_dl", "Sumo Deadlift", HINGE, listOf(BARBELL), false, std(DEADLIFT), 3),
        ExerciseEntry("rdl", "Romanian Deadlift", HINGE, listOf(BARBELL), false, frac(0.72, SQUAT), 4),
        ExerciseEntry("stiff_dl", "Stiff-Leg Deadlift", HINGE, listOf(BARBELL), false, frac(0.65, SQUAT), 5),
        ExerciseEntry("good_morning", "Good Morning", HINGE, listOf(BARBELL, RACK), false, frac(0.45, SQUAT), 6),
        ExerciseEntry("back_ext", "Back Extension", HINGE, listOf(BODYWEIGHT), false, flat(0.0), 7),

        ExerciseEntry("seated_curl", "Seated Leg Curl", KNEE_FLEXION, listOf(MACHINE), false, flat(90.0), 1),
        ExerciseEntry("lying_curl", "Lying Leg Curl", KNEE_FLEXION, listOf(MACHINE), false, flat(90.0), 2),
        ExerciseEntry("ball_curl", "Swiss Ball Leg Curl", KNEE_FLEXION, listOf(BODYWEIGHT), false, flat(0.0), 3),
        ExerciseEntry("nordic", "Nordic Curl (assisted)", KNEE_FLEXION, listOf(BODYWEIGHT), false, flat(0.0), 4),

        ExerciseEntry("leg_ext", "Leg Extension", KNEE_EXTENSION, listOf(MACHINE), false, flat(90.0), 1, fallbackPattern = SQUAT_BILATERAL),

        ExerciseEntry("bb_bench", "Barbell Bench Press", H_PUSH, listOf(BARBELL, BENCH, RACK), false, std(STD_BENCH), 1),
        ExerciseEntry("db_bench", "DB Bench Press", H_PUSH, listOf(DUMBBELL, BENCH), true, frac(0.4, STD_BENCH), 2),
        ExerciseEntry("incline_db", "Incline DB Press", H_PUSH, listOf(DUMBBELL, BENCH), true, std(INCLINE), 3),
        ExerciseEntry("incline_bb", "Incline Barbell Press", H_PUSH, listOf(BARBELL, BENCH, RACK), false, frac(0.8, STD_BENCH), 4),
        ExerciseEntry("machine_chest", "Machine Chest Press", H_PUSH, listOf(MACHINE), false, flat(100.0), 5),
        ExerciseEntry("pec_deck", "Pec Deck / Cable Press", H_PUSH, listOf(MACHINE, CABLE), false, flat(100.0), 6),
        ExerciseEntry("dips", "Weighted Dip", H_PUSH, listOf(BODYWEIGHT), false, flat(0.0), 7),

        ExerciseEntry("ohp", "Overhead Press (Barbell)", V_PUSH, listOf(BARBELL, RACK), false, std(OHP), 1),
        ExerciseEntry("db_shoulder", "Seated DB Shoulder Press", V_PUSH, listOf(DUMBBELL, BENCH), true, flat(55.0), 2),
        ExerciseEntry("machine_shoulder", "Machine Shoulder Press", V_PUSH, listOf(MACHINE), false, flat(90.0), 3),
        ExerciseEntry("landmine_press", "Landmine Press", V_PUSH, listOf(BARBELL), false, flat(60.0), 4),

        ExerciseEntry("bb_row", "Barbell Row", H_PULL, listOf(BARBELL), false, std(ROW), 1),
        ExerciseEntry("cs_row", "Chest-Supported Row", H_PULL, listOf(MACHINE, BENCH, DUMBBELL), false, frac(0.8, STD_BENCH), 2),
        ExerciseEntry("cable_row", "Seated Cable Row", H_PULL, listOf(CABLE), false, flat(120.0), 3),
        ExerciseEntry("db_row", "One-Arm DB Row", H_PULL, listOf(DUMBBELL, BENCH), true, flat(60.0), 4),

        ExerciseEntry("pullup", "Pull-Up / Chin-Up", V_PULL, listOf(PULLUP_BAR), false, flat(0.0), 1),
        ExerciseEntry("lat_pd_wide", "Lat Pulldown (wide)", V_PULL, listOf(CABLE), false, flat(130.0), 2),
        ExerciseEntry("lat_pd_neutral", "Lat Pulldown (neutral)", V_PULL, listOf(CABLE), false, flat(125.0), 3),
        ExerciseEntry("assisted_pullup", "Assisted Pull-Up", V_PULL, listOf(MACHINE), false, flat(0.0), 4),

        ExerciseEntry("cable_lateral", "Cable Lateral Raise", SIDE_DELT, listOf(CABLE), false, flat(15.0), 1),
        ExerciseEntry("db_lateral", "DB Lateral Raise", SIDE_DELT, listOf(DUMBBELL), true, flat(15.0), 2),

        ExerciseEntry("face_pull", "Face Pull", REAR_DELT, listOf(CABLE), false, flat(40.0), 1),
        ExerciseEntry("reverse_pec", "Reverse Pec Deck", REAR_DELT, listOf(MACHINE), false, flat(70.0), 2),

        ExerciseEntry("ez_curl", "EZ-Bar Curl", BICEPS, listOf(EZ_BAR), false, flat(60.0), 1),
        ExerciseEntry("incline_curl", "Incline DB Curl", BICEPS, listOf(DUMBBELL, BENCH), true, flat(30.0), 2),
        ExerciseEntry("hammer_curl", "Hammer Curl", BICEPS, listOf(DUMBBELL), true, flat(30.0), 3),
        ExerciseEntry("cable_curl", "Cable Curl", BICEPS, listOf(CABLE), false, flat(50.0), 4),

        ExerciseEntry("rope_pushdown", "Rope Pushdown", TRICEPS, listOf(CABLE), false, flat(50.0), 1),
        ExerciseEntry("oh_tri_ext", "Overhead Triceps Extension", TRICEPS, listOf(CABLE, DUMBBELL), false, flat(50.0), 2),
        ExerciseEntry("skullcrusher", "Skull Crusher", TRICEPS, listOf(EZ_BAR, BENCH), false, flat(55.0), 3),

        ExerciseEntry("standing_calf", "Standing Calf Raise", CALF_GASTROC, listOf(MACHINE, BODYWEIGHT), false, flat(90.0), 1),
        ExerciseEntry("legpress_calf", "Leg-Press Calf Raise", CALF_GASTROC, listOf(MACHINE), false, flat(180.0), 2),

        ExerciseEntry("seated_calf", "Seated Calf Raise", CALF_SOLEUS, listOf(MACHINE), false, flat(90.0), 1),

        ExerciseEntry("plank", "Plank / Side Plank", CORE_ANTI_EXT, listOf(BODYWEIGHT), false, flat(0.0), 1),
        ExerciseEntry("ab_wheel", "Ab Wheel Rollout", CORE_ANTI_EXT, listOf(BODYWEIGHT), false, flat(0.0), 2),
        ExerciseEntry("dead_bug", "Dead Bug", CORE_ANTI_EXT, listOf(BODYWEIGHT), false, flat(0.0), 3),

        ExerciseEntry("pallof", "Pallof Press", CORE_ANTI_ROT, listOf(CABLE), false, flat(25.0), 1),
        ExerciseEntry("suitcase_carry", "Suitcase Carry", CORE_ANTI_ROT, listOf(DUMBBELL), true, flat(50.0), 2),

        ExerciseEntry("cable_crunch", "Cable Crunch", CORE_FLEX, listOf(CABLE), false, flat(90.0), 1),
        ExerciseEntry("hanging_raise", "Hanging Leg Raise", CORE_FLEX, listOf(PULLUP_BAR), false, flat(0.0), 2),
    )

    private val byId: Map<String, ExerciseEntry> = entries.associateBy { it.id }

    fun get(id: String): ExerciseEntry =
        byId[id] ?: error("Unknown exercise id: $id")

    fun find(id: String): ExerciseEntry? = byId[id]

    fun byPattern(pattern: MovementPattern): List<ExerciseEntry> =
        entries.filter { it.pattern == pattern }.sortedBy { it.subRank }

    /**
     * Ranked same-pattern replacements for [id], excluding itself (spec §5).
     * Falls back to the entry's adjacent [ExerciseEntry.fallbackPattern] when
     * its own pattern has no other candidates.
     */
    fun substitutionsFor(id: String): List<ExerciseEntry> {
        val target = get(id)
        val samePattern = byPattern(target.pattern).filter { it.id != id }
        if (samePattern.isNotEmpty()) return samePattern
        val fallback = target.fallbackPattern ?: return emptyList()
        return byPattern(fallback).filter { it.id != id }
    }
}
