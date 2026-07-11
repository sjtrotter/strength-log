package io.github.sjtrotter.strengthlog.domain.library

import io.github.sjtrotter.strengthlog.domain.model.Equipment.BARBELL
import io.github.sjtrotter.strengthlog.domain.model.Equipment.BENCH
import io.github.sjtrotter.strengthlog.domain.model.Equipment.BODYWEIGHT
import io.github.sjtrotter.strengthlog.domain.model.Equipment.CABLE
import io.github.sjtrotter.strengthlog.domain.model.Equipment.DUMBBELL
import io.github.sjtrotter.strengthlog.domain.model.Equipment.EZ_BAR
import io.github.sjtrotter.strengthlog.domain.model.Equipment.KETTLEBELL
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
 * suggested weight and the substitution graph.
 */
object ExerciseLibrary {

    private fun std(lift: StandardLift, tune: Double? = null) = GoalSource.Std(lift, tune)

    private fun frac(fraction: Double, lift: StandardLift) = GoalSource.FracOfStd(fraction, lift)

    private fun flat(weightLb: Double) = GoalSource.Flat(weightLb)

    private fun reps(targetReps: Int) = GoalSource.Reps(targetReps)

    private fun time(targetSeconds: Int, addedWeightLb: Double = 0.0) =
        GoalSource.Time(targetSeconds, addedWeightLb)

    val entries: List<ExerciseEntry> = listOf(
        ExerciseEntry("bb_back_squat", "Barbell Back Squat", SQUAT_BILATERAL, listOf(BARBELL, RACK), false, std(SQUAT), 1),
        ExerciseEntry("hack_squat", "Hack Squat", SQUAT_BILATERAL, listOf(MACHINE), false, flat(180.0), 2),
        ExerciseEntry("leg_press", "Leg Press", SQUAT_BILATERAL, listOf(MACHINE), false, frac(1.4, SQUAT), 3),
        ExerciseEntry("goblet_squat", "Goblet Squat", SQUAT_BILATERAL, listOf(DUMBBELL), false, flat(70.0), 4),
        ExerciseEntry("front_squat", "Front Squat", SQUAT_BILATERAL, listOf(BARBELL, RACK), false, frac(0.8, SQUAT), 5),
        ExerciseEntry("smith_squat", "Smith Machine Squat", SQUAT_BILATERAL, listOf(MACHINE), false, frac(0.9, SQUAT), 6),
        ExerciseEntry("ssb_squat", "Safety-Bar Squat", SQUAT_BILATERAL, listOf(BARBELL, RACK), false, frac(0.9, SQUAT), 7),
        ExerciseEntry("box_squat", "Box Squat", SQUAT_BILATERAL, listOf(BARBELL, RACK, BENCH), false, frac(0.9, SQUAT), 8),
        ExerciseEntry("belt_squat", "Belt Squat", SQUAT_BILATERAL, listOf(MACHINE), false, flat(180.0), 9),
        ExerciseEntry("pendulum_squat", "Pendulum Squat", SQUAT_BILATERAL, listOf(MACHINE), false, flat(160.0), 10),
        ExerciseEntry("kb_goblet_squat", "KB Goblet Squat", SQUAT_BILATERAL, listOf(KETTLEBELL), false, flat(53.0), 11),
        ExerciseEntry("kb_front_squat", "Double-KB Front Squat", SQUAT_BILATERAL, listOf(KETTLEBELL), true, flat(44.0), 12),
        ExerciseEntry("zercher_squat", "Zercher Squat", SQUAT_BILATERAL, listOf(BARBELL, RACK), false, frac(0.65, SQUAT), 13),
        ExerciseEntry("wall_sit", "Wall Sit", SQUAT_BILATERAL, listOf(BODYWEIGHT), false, time(45), 14),

        ExerciseEntry("bss", "Bulgarian Split Squat", SINGLE_LEG, listOf(DUMBBELL, BENCH), true, flat(35.0), 1),
        ExerciseEntry("walking_lunge", "Walking Lunge", SINGLE_LEG, listOf(DUMBBELL), true, flat(30.0), 2),
        ExerciseEntry("reverse_lunge", "Reverse Lunge", SINGLE_LEG, listOf(DUMBBELL), true, flat(30.0), 3),
        ExerciseEntry("step_up", "Step-Up", SINGLE_LEG, listOf(DUMBBELL, BENCH), true, flat(25.0), 4),
        ExerciseEntry("bb_bss", "Barbell Bulgarian Split Squat", SINGLE_LEG, listOf(BARBELL, RACK, BENCH), false, frac(0.5, SQUAT), 5),
        ExerciseEntry("ffe_split_squat", "Front-Foot-Elevated Split Squat", SINGLE_LEG, listOf(DUMBBELL), true, flat(30.0), 6),
        ExerciseEntry("forward_lunge", "Forward Lunge", SINGLE_LEG, listOf(DUMBBELL), true, flat(30.0), 7),
        ExerciseEntry("lateral_lunge", "Lateral Lunge", SINGLE_LEG, listOf(DUMBBELL), true, flat(25.0), 8),
        ExerciseEntry("curtsy_lunge", "Curtsy Lunge", SINGLE_LEG, listOf(DUMBBELL), true, flat(25.0), 9),
        ExerciseEntry("smith_split_squat", "Smith Machine Split Squat", SINGLE_LEG, listOf(MACHINE), false, flat(95.0), 10),
        ExerciseEntry("sl_leg_press", "Single-Leg Press", SINGLE_LEG, listOf(MACHINE), false, flat(160.0), 11),
        ExerciseEntry("cossack_squat", "Cossack Squat", SINGLE_LEG, listOf(KETTLEBELL), false, flat(35.0), 12),
        ExerciseEntry("pistol_squat", "Pistol Squat", SINGLE_LEG, listOf(BODYWEIGHT), false, reps(5), 13),
        ExerciseEntry("skater_squat", "Skater Squat", SINGLE_LEG, listOf(BODYWEIGHT), false, reps(8), 14),

        ExerciseEntry("trap_dl", "Trap-Bar Deadlift", HINGE, listOf(TRAP_BAR), false, std(DEADLIFT), 1),
        ExerciseEntry("conv_dl", "Conventional Deadlift", HINGE, listOf(BARBELL), false, std(DEADLIFT, tune = 1.0), 2),
        ExerciseEntry("sumo_dl", "Sumo Deadlift", HINGE, listOf(BARBELL), false, std(DEADLIFT), 3),
        ExerciseEntry("rdl", "Romanian Deadlift", HINGE, listOf(BARBELL), false, frac(0.72, SQUAT), 4),
        ExerciseEntry("stiff_dl", "Stiff-Leg Deadlift", HINGE, listOf(BARBELL), false, frac(0.65, SQUAT), 5),
        ExerciseEntry("good_morning", "Good Morning", HINGE, listOf(BARBELL, RACK), false, frac(0.45, SQUAT), 6),
        ExerciseEntry("back_ext", "Back Extension", HINGE, listOf(BODYWEIGHT), false, reps(12), 7, weightedPairId = "back_ext_45"),
        ExerciseEntry("db_rdl", "DB Romanian Deadlift", HINGE, listOf(DUMBBELL), true, flat(55.0), 8),
        ExerciseEntry("kb_rdl", "Double-KB RDL", HINGE, listOf(KETTLEBELL), true, flat(53.0), 9),
        ExerciseEntry("sl_rdl", "Single-Leg RDL", HINGE, listOf(DUMBBELL), true, flat(40.0), 10),
        ExerciseEntry("hip_thrust", "Barbell Hip Thrust", HINGE, listOf(BARBELL, BENCH), false, frac(1.2, SQUAT), 11),
        ExerciseEntry("glute_bridge", "Barbell Glute Bridge", HINGE, listOf(BARBELL), false, frac(1.2, SQUAT), 12),
        ExerciseEntry("machine_hip_thrust", "Machine Hip Thrust", HINGE, listOf(MACHINE), false, flat(230.0), 13),
        ExerciseEntry("cable_pullthrough", "Cable Pull-Through", HINGE, listOf(CABLE), false, flat(70.0), 14),
        ExerciseEntry("kb_swing", "KB Swing", HINGE, listOf(KETTLEBELL), false, flat(53.0), 15),
        ExerciseEntry("kb_deadlift", "KB Deadlift", HINGE, listOf(KETTLEBELL), false, flat(70.0), 16),
        ExerciseEntry("back_ext_45", "45° Back Extension (Weighted)", HINGE, listOf(MACHINE), false, flat(45.0), 17),
        ExerciseEntry("reverse_hyper", "Reverse Hyper", HINGE, listOf(MACHINE), false, flat(90.0), 18),
        ExerciseEntry("block_pull", "Block Pull", HINGE, listOf(BARBELL), false, frac(1.1, DEADLIFT), 19),
        ExerciseEntry("smith_rdl", "Smith Machine RDL", HINGE, listOf(MACHINE), false, frac(0.7, SQUAT), 20),

        ExerciseEntry("seated_curl", "Seated Leg Curl", KNEE_FLEXION, listOf(MACHINE), false, flat(90.0), 1),
        ExerciseEntry("lying_curl", "Lying Leg Curl", KNEE_FLEXION, listOf(MACHINE), false, flat(90.0), 2),
        ExerciseEntry("ball_curl", "Swiss Ball Leg Curl", KNEE_FLEXION, listOf(BODYWEIGHT), false, reps(12), 3),
        ExerciseEntry("nordic", "Nordic Curl (assisted)", KNEE_FLEXION, listOf(BODYWEIGHT), false, reps(6), 4),
        ExerciseEntry("standing_leg_curl", "Standing Single-Leg Curl", KNEE_FLEXION, listOf(MACHINE), false, flat(45.0), 5),
        ExerciseEntry("ghr", "Glute-Ham Raise", KNEE_FLEXION, listOf(MACHINE), false, reps(8), 6),
        ExerciseEntry("slider_curl", "Slider Leg Curl", KNEE_FLEXION, listOf(BODYWEIGHT), false, reps(10), 7),

        ExerciseEntry("leg_ext", "Leg Extension", KNEE_EXTENSION, listOf(MACHINE), false, flat(90.0), 1, fallbackPattern = SQUAT_BILATERAL),
        ExerciseEntry("sl_leg_ext", "Single-Leg Extension", KNEE_EXTENSION, listOf(MACHINE), false, flat(45.0), 2),
        ExerciseEntry("sissy_squat", "Sissy Squat", KNEE_EXTENSION, listOf(BODYWEIGHT), false, reps(10), 3),
        ExerciseEntry("reverse_nordic", "Reverse Nordic", KNEE_EXTENSION, listOf(BODYWEIGHT), false, reps(10), 4),

        ExerciseEntry("bb_bench", "Barbell Bench Press", H_PUSH, listOf(BARBELL, BENCH, RACK), false, std(STD_BENCH), 1),
        ExerciseEntry("db_bench", "DB Bench Press", H_PUSH, listOf(DUMBBELL, BENCH), true, frac(0.4, STD_BENCH), 2),
        ExerciseEntry("incline_db", "Incline DB Press", H_PUSH, listOf(DUMBBELL, BENCH), true, std(INCLINE), 3),
        ExerciseEntry("incline_bb", "Incline Barbell Press", H_PUSH, listOf(BARBELL, BENCH, RACK), false, frac(0.8, STD_BENCH), 4),
        ExerciseEntry("machine_chest", "Machine Chest Press", H_PUSH, listOf(MACHINE), false, flat(100.0), 5),
        ExerciseEntry("pec_deck", "Pec Deck", H_PUSH, listOf(MACHINE), false, flat(100.0), 6),
        ExerciseEntry("dips", "Weighted Dip", H_PUSH, listOf(BODYWEIGHT), false, flat(45.0), 7),
        ExerciseEntry("smith_bench", "Smith Machine Bench Press", H_PUSH, listOf(MACHINE, BENCH), false, frac(0.9, STD_BENCH), 8),
        ExerciseEntry("decline_bb", "Decline Barbell Press", H_PUSH, listOf(BARBELL, BENCH, RACK), false, frac(0.95, STD_BENCH), 9),
        ExerciseEntry("incline_machine", "Incline Machine Press", H_PUSH, listOf(MACHINE), false, flat(90.0), 10),
        ExerciseEntry("smith_incline", "Smith Machine Incline Press", H_PUSH, listOf(MACHINE, BENCH), false, frac(0.75, STD_BENCH), 11),
        ExerciseEntry("floor_press", "Barbell Floor Press", H_PUSH, listOf(BARBELL, RACK), false, frac(0.85, STD_BENCH), 12),
        ExerciseEntry("kb_floor_press", "KB Floor Press", H_PUSH, listOf(KETTLEBELL), true, flat(44.0), 13),
        ExerciseEntry("db_fly", "DB Fly", H_PUSH, listOf(DUMBBELL, BENCH), true, flat(30.0), 14),
        ExerciseEntry("cable_fly", "Cable Fly", H_PUSH, listOf(CABLE), false, flat(30.0), 15),
        ExerciseEntry("low_cable_fly", "Low-to-High Cable Fly", H_PUSH, listOf(CABLE), false, flat(25.0), 16),
        ExerciseEntry("pushup", "Push-Up", H_PUSH, listOf(BODYWEIGHT), false, reps(15), 17, weightedPairId = "weighted_pushup"),
        ExerciseEntry("bw_dip", "Dip", H_PUSH, listOf(BODYWEIGHT), false, reps(10), 18, weightedPairId = "dips"),
        ExerciseEntry("weighted_pushup", "Weighted Push-Up", H_PUSH, listOf(BODYWEIGHT), false, flat(25.0), 19),

        ExerciseEntry("ohp", "Overhead Press (Barbell)", V_PUSH, listOf(BARBELL, RACK), false, std(OHP), 1),
        ExerciseEntry("db_shoulder", "Seated DB Shoulder Press", V_PUSH, listOf(DUMBBELL, BENCH), true, flat(55.0), 2),
        ExerciseEntry("machine_shoulder", "Machine Shoulder Press", V_PUSH, listOf(MACHINE), false, flat(90.0), 3),
        ExerciseEntry("landmine_press", "Landmine Press", V_PUSH, listOf(BARBELL), false, flat(60.0), 4),
        ExerciseEntry("seated_bb_ohp", "Seated Barbell OHP", V_PUSH, listOf(BARBELL, BENCH, RACK), false, frac(0.9, OHP), 5),
        ExerciseEntry("push_press", "Push Press", V_PUSH, listOf(BARBELL, RACK), false, frac(1.15, OHP), 6),
        ExerciseEntry("standing_db_press", "Standing DB Press", V_PUSH, listOf(DUMBBELL), true, flat(50.0), 7),
        ExerciseEntry("kb_press", "KB Overhead Press", V_PUSH, listOf(KETTLEBELL), true, flat(44.0), 8),
        ExerciseEntry("arnold_press", "Arnold Press", V_PUSH, listOf(DUMBBELL, BENCH), true, flat(45.0), 9),
        ExerciseEntry("smith_shoulder", "Smith Machine Shoulder Press", V_PUSH, listOf(MACHINE, BENCH), false, frac(0.85, OHP), 10),
        ExerciseEntry("z_press", "Z Press", V_PUSH, listOf(BARBELL, RACK), false, frac(0.75, OHP), 11),
        ExerciseEntry("pike_pushup", "Pike Push-Up", V_PUSH, listOf(BODYWEIGHT), false, reps(10), 12),
        ExerciseEntry("hspu", "Handstand Push-Up", V_PUSH, listOf(BODYWEIGHT), false, reps(5), 13),

        ExerciseEntry("bb_row", "Barbell Row", H_PULL, listOf(BARBELL), false, std(ROW), 1),
        ExerciseEntry("cs_row", "Chest-Supported Row", H_PULL, listOf(MACHINE), false, frac(0.8, STD_BENCH), 2),
        ExerciseEntry("cable_row", "Seated Cable Row", H_PULL, listOf(CABLE), false, flat(120.0), 3),
        ExerciseEntry("db_row", "One-Arm DB Row", H_PULL, listOf(DUMBBELL, BENCH), true, flat(60.0), 4),
        ExerciseEntry("pendlay_row", "Pendlay Row", H_PULL, listOf(BARBELL), false, frac(0.95, ROW), 5),
        ExerciseEntry("tbar_row", "T-Bar Row", H_PULL, listOf(BARBELL), false, frac(0.9, ROW), 6),
        ExerciseEntry("db_cs_row", "Chest-Supported DB Row", H_PULL, listOf(DUMBBELL, BENCH), true, flat(50.0), 7),
        ExerciseEntry("machine_row", "Machine Row", H_PULL, listOf(MACHINE), false, flat(120.0), 8),
        ExerciseEntry("seal_row", "Seal Row", H_PULL, listOf(BARBELL, BENCH), false, frac(0.75, ROW), 9),
        ExerciseEntry("single_cable_row", "Single-Arm Cable Row", H_PULL, listOf(CABLE), false, flat(60.0), 10),
        ExerciseEntry("kb_row", "KB Row", H_PULL, listOf(KETTLEBELL), true, flat(53.0), 11),
        ExerciseEntry("inverted_row", "Inverted Row", H_PULL, listOf(BARBELL, RACK), false, reps(10), 12),

        ExerciseEntry("pullup", "Pull-Up / Chin-Up", V_PULL, listOf(PULLUP_BAR), false, reps(6), 1, weightedPairId = "weighted_pullup"),
        ExerciseEntry("lat_pd_wide", "Lat Pulldown (wide)", V_PULL, listOf(CABLE), false, flat(130.0), 2),
        ExerciseEntry("lat_pd_neutral", "Lat Pulldown (neutral)", V_PULL, listOf(CABLE), false, flat(125.0), 3),
        ExerciseEntry("assisted_pullup", "Assisted Pull-Up", V_PULL, listOf(MACHINE), false, flat(0.0), 4),
        ExerciseEntry("machine_pulldown", "Machine Pulldown", V_PULL, listOf(MACHINE), false, flat(120.0), 5),
        ExerciseEntry("neutral_pullup", "Neutral-Grip Pull-Up", V_PULL, listOf(PULLUP_BAR), false, reps(6), 6, weightedPairId = "weighted_neutral_pullup"),
        ExerciseEntry("single_arm_pulldown", "Single-Arm Cable Pulldown", V_PULL, listOf(CABLE), false, flat(50.0), 7),
        ExerciseEntry("straight_arm_pd", "Straight-Arm Pulldown", V_PULL, listOf(CABLE), false, flat(45.0), 8),
        ExerciseEntry("db_pullover", "DB Pullover", V_PULL, listOf(DUMBBELL, BENCH), false, flat(60.0), 9),
        ExerciseEntry("weighted_pullup", "Weighted Pull-Up", V_PULL, listOf(PULLUP_BAR), false, flat(25.0), 10),
        ExerciseEntry("weighted_neutral_pullup", "Weighted Neutral-Grip Pull-Up", V_PULL, listOf(PULLUP_BAR), false, flat(25.0), 11),
        ExerciseEntry("dead_hang", "Dead Hang", V_PULL, listOf(PULLUP_BAR), false, time(30), 12),

        ExerciseEntry("cable_lateral", "Cable Lateral Raise", SIDE_DELT, listOf(CABLE), false, flat(15.0), 1),
        ExerciseEntry("db_lateral", "DB Lateral Raise", SIDE_DELT, listOf(DUMBBELL), true, flat(15.0), 2),
        ExerciseEntry("machine_lateral", "Machine Lateral Raise", SIDE_DELT, listOf(MACHINE), false, flat(60.0), 3),
        ExerciseEntry("seated_db_lateral", "Seated DB Lateral Raise", SIDE_DELT, listOf(DUMBBELL, BENCH), true, flat(15.0), 4),
        ExerciseEntry("lean_away_lateral", "Lean-Away Cable Lateral", SIDE_DELT, listOf(CABLE), false, flat(12.5), 5),
        ExerciseEntry("db_upright_row", "DB Upright Row", SIDE_DELT, listOf(DUMBBELL), true, flat(30.0), 6),
        ExerciseEntry("bb_upright_row", "Barbell Upright Row", SIDE_DELT, listOf(BARBELL), false, flat(75.0), 7),

        ExerciseEntry("face_pull", "Face Pull", REAR_DELT, listOf(CABLE), false, flat(40.0), 1),
        ExerciseEntry("reverse_pec", "Reverse Pec Deck", REAR_DELT, listOf(MACHINE), false, flat(70.0), 2),
        ExerciseEntry("cs_reverse_fly", "Chest-Supported Reverse Fly", REAR_DELT, listOf(DUMBBELL, BENCH), true, flat(15.0), 3),
        ExerciseEntry("db_reverse_fly", "Bent-Over DB Reverse Fly", REAR_DELT, listOf(DUMBBELL), true, flat(15.0), 4),
        ExerciseEntry("cable_rear_fly", "Cable Rear-Delt Fly", REAR_DELT, listOf(CABLE), false, flat(15.0), 5),
        ExerciseEntry("rear_delt_row", "DB Rear-Delt Row", REAR_DELT, listOf(DUMBBELL), true, flat(35.0), 6),

        ExerciseEntry("ez_curl", "EZ-Bar Curl", BICEPS, listOf(EZ_BAR), false, flat(60.0), 1),
        ExerciseEntry("incline_curl", "Incline DB Curl", BICEPS, listOf(DUMBBELL, BENCH), true, flat(30.0), 2),
        ExerciseEntry("hammer_curl", "Hammer Curl", BICEPS, listOf(DUMBBELL), true, flat(30.0), 3),
        ExerciseEntry("cable_curl", "Cable Curl", BICEPS, listOf(CABLE), false, flat(50.0), 4),
        ExerciseEntry("bb_curl", "Barbell Curl", BICEPS, listOf(BARBELL), false, flat(65.0), 5),
        ExerciseEntry("preacher_curl", "EZ-Bar Preacher Curl", BICEPS, listOf(EZ_BAR, BENCH), false, flat(50.0), 6),
        ExerciseEntry("machine_curl", "Machine Preacher Curl", BICEPS, listOf(MACHINE), false, flat(60.0), 7),
        ExerciseEntry("rope_hammer_curl", "Rope Hammer Curl", BICEPS, listOf(CABLE), false, flat(50.0), 8),
        ExerciseEntry("concentration_curl", "Concentration Curl", BICEPS, listOf(DUMBBELL, BENCH), true, flat(25.0), 9),
        ExerciseEntry("spider_curl", "Spider Curl", BICEPS, listOf(DUMBBELL, BENCH), true, flat(20.0), 10),
        ExerciseEntry("bayesian_curl", "Bayesian Cable Curl", BICEPS, listOf(CABLE), false, flat(30.0), 11),
        ExerciseEntry("reverse_curl", "EZ-Bar Reverse Curl", BICEPS, listOf(EZ_BAR), false, flat(40.0), 12),

        ExerciseEntry("rope_pushdown", "Rope Pushdown", TRICEPS, listOf(CABLE), false, flat(50.0), 1),
        ExerciseEntry("oh_tri_ext", "Cable Overhead Triceps Extension", TRICEPS, listOf(CABLE), false, flat(50.0), 2),
        ExerciseEntry("skullcrusher", "Skull Crusher", TRICEPS, listOf(EZ_BAR, BENCH), false, flat(55.0), 3),
        ExerciseEntry("bar_pushdown", "Straight-Bar Pushdown", TRICEPS, listOf(CABLE), false, flat(60.0), 4),
        ExerciseEntry("single_pushdown", "Single-Arm Pushdown", TRICEPS, listOf(CABLE), false, flat(25.0), 5),
        ExerciseEntry("db_oh_tri_ext", "DB Overhead Triceps Extension", TRICEPS, listOf(DUMBBELL), false, flat(60.0), 6),
        ExerciseEntry("db_skullcrusher", "DB Skull Crusher", TRICEPS, listOf(DUMBBELL, BENCH), true, flat(25.0), 7),
        ExerciseEntry("machine_tri_ext", "Machine Triceps Extension", TRICEPS, listOf(MACHINE), false, flat(70.0), 8),
        ExerciseEntry("cgbp", "Close-Grip Bench Press", TRICEPS, listOf(BARBELL, BENCH, RACK), false, frac(0.85, STD_BENCH), 9),
        ExerciseEntry("jm_press", "JM Press", TRICEPS, listOf(BARBELL, BENCH, RACK), false, frac(0.6, STD_BENCH), 10),
        ExerciseEntry("bench_dip", "Bench Dip", TRICEPS, listOf(BENCH, BODYWEIGHT), false, reps(12), 11),
        ExerciseEntry("diamond_pushup", "Diamond Push-Up", TRICEPS, listOf(BODYWEIGHT), false, reps(12), 12),
        ExerciseEntry("kickback", "DB Triceps Kickback", TRICEPS, listOf(DUMBBELL), true, flat(15.0), 13),

        ExerciseEntry("standing_calf", "Standing Calf Raise", CALF_GASTROC, listOf(MACHINE), false, flat(90.0), 1),
        ExerciseEntry("legpress_calf", "Leg-Press Calf Raise", CALF_GASTROC, listOf(MACHINE), false, flat(180.0), 2),
        ExerciseEntry("smith_calf", "Smith Machine Calf Raise", CALF_GASTROC, listOf(MACHINE), false, flat(135.0), 3),
        ExerciseEntry("donkey_calf", "Donkey Calf Raise", CALF_GASTROC, listOf(MACHINE), false, flat(140.0), 4),
        ExerciseEntry("db_calf", "DB Single-Leg Calf Raise", CALF_GASTROC, listOf(DUMBBELL), true, flat(50.0), 5),
        ExerciseEntry("bw_calf_raise", "Single-Leg Calf Raise", CALF_GASTROC, listOf(BODYWEIGHT), false, reps(15), 6, weightedPairId = "db_calf"),

        ExerciseEntry("seated_calf", "Seated Calf Raise", CALF_SOLEUS, listOf(MACHINE), false, flat(90.0), 1),
        ExerciseEntry("bent_knee_lp_calf", "Leg-Press Calf Raise (Bent-Knee)", CALF_SOLEUS, listOf(MACHINE), false, flat(140.0), 2),

        ExerciseEntry("plank", "Plank / Side Plank", CORE_ANTI_EXT, listOf(BODYWEIGHT), false, time(45), 1, weightedPairId = "weighted_plank"),
        ExerciseEntry("ab_wheel", "Ab Wheel Rollout", CORE_ANTI_EXT, listOf(BODYWEIGHT), false, reps(10), 2),
        ExerciseEntry("dead_bug", "Dead Bug", CORE_ANTI_EXT, listOf(BODYWEIGHT), false, reps(10), 3),
        ExerciseEntry("weighted_plank", "Weighted Plank", CORE_ANTI_EXT, listOf(BODYWEIGHT), false, time(45, 25.0), 4),
        ExerciseEntry("body_saw", "Body Saw", CORE_ANTI_EXT, listOf(BODYWEIGHT), false, reps(10), 5),
        ExerciseEntry("hollow_hold", "Hollow Hold", CORE_ANTI_EXT, listOf(BODYWEIGHT), false, time(30), 6),

        ExerciseEntry("pallof", "Pallof Press", CORE_ANTI_ROT, listOf(CABLE), false, flat(25.0), 1),
        ExerciseEntry("suitcase_carry", "Suitcase Carry", CORE_ANTI_ROT, listOf(DUMBBELL), true, time(40, 50.0), 2),
        ExerciseEntry("half_kneel_pallof", "Half-Kneeling Pallof Press", CORE_ANTI_ROT, listOf(CABLE), false, flat(20.0), 3),
        ExerciseEntry("landmine_rotation", "Landmine Rotation", CORE_ANTI_ROT, listOf(BARBELL), false, flat(25.0), 4),
        ExerciseEntry("kb_windmill", "KB Windmill", CORE_ANTI_ROT, listOf(KETTLEBELL), true, flat(25.0), 5),
        ExerciseEntry("turkish_getup", "Turkish Get-Up", CORE_ANTI_ROT, listOf(KETTLEBELL), true, flat(35.0), 6),
        ExerciseEntry("farmers_carry", "Farmer's Carry", CORE_ANTI_ROT, listOf(DUMBBELL), true, time(40, 50.0), 7),

        ExerciseEntry("cable_crunch", "Cable Crunch", CORE_FLEX, listOf(CABLE), false, flat(90.0), 1),
        ExerciseEntry("hanging_raise", "Hanging Leg Raise", CORE_FLEX, listOf(PULLUP_BAR), false, reps(10), 2),
        ExerciseEntry("machine_crunch", "Machine Crunch", CORE_FLEX, listOf(MACHINE), false, flat(90.0), 3),
        ExerciseEntry("weighted_crunch", "Weighted Crunch", CORE_FLEX, listOf(DUMBBELL), false, flat(25.0), 4),
        ExerciseEntry("decline_situp", "Decline Sit-Up", CORE_FLEX, listOf(BENCH, BODYWEIGHT), false, reps(20), 5),
        ExerciseEntry("reverse_crunch", "Reverse Crunch", CORE_FLEX, listOf(BODYWEIGHT), false, reps(15), 6),
        ExerciseEntry("v_up", "V-Up", CORE_FLEX, listOf(BODYWEIGHT), false, reps(12), 7),
        ExerciseEntry("knee_raise", "Captain's Chair Knee Raise", CORE_FLEX, listOf(MACHINE), false, reps(12), 8),
        ExerciseEntry("hanging_knee_raise", "Hanging Knee Raise", CORE_FLEX, listOf(PULLUP_BAR), false, reps(12), 9, weightedPairId = "weighted_knee_raise"),
        ExerciseEntry("weighted_knee_raise", "Weighted Knee Raise", CORE_FLEX, listOf(PULLUP_BAR, DUMBBELL), false, flat(10.0), 10),
    )

    /** Ids reclassified from a bodyweight-fake [GoalSource.Flat] to
     *  [GoalSource.Time] in the tracking-types catalog pass. The P3 one-shot
     *  legacy-data fixup (reps→seconds carry) operates on exactly this set —
     *  declared once here, next to the catalog it describes, so the migration
     *  and its tests share a single source of truth. */
    val RECLASSIFIED_TO_TIMED_IDS: Set<String> = setOf("plank", "hollow_hold", "weighted_plank", "suitcase_carry")

    private val byId: Map<String, ExerciseEntry> = entries.associateBy { it.id }

    /** target id → declaring (unloaded) id, validated at init (see
     *  [buildWeightedPairIndex]). */
    private val bodyweightByTarget: Map<String, String> = buildWeightedPairIndex(entries)

    fun get(id: String): ExerciseEntry =
        byId[id] ?: error("Unknown exercise id: $id")

    fun find(id: String): ExerciseEntry? = byId[id]

    /** The unloaded (REMOVE-WEIGHT) counterpart of a loaded entry, if any —
     *  the reverse of [ExerciseEntry.weightedPairId]. */
    fun bodyweightPairFor(id: String): String? = bodyweightByTarget[id]

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
