package io.github.sjtrotter.strengthlog.domain

import io.github.sjtrotter.strengthlog.domain.library.ExerciseLibrary
import io.github.sjtrotter.strengthlog.domain.library.GoalSource
import io.github.sjtrotter.strengthlog.domain.library.TrackingType
import io.github.sjtrotter.strengthlog.domain.library.tracking
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
import io.github.sjtrotter.strengthlog.domain.units.WeightStepper
import io.github.sjtrotter.strengthlog.domain.units.WeightUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ExerciseLibraryTest {

    @Test
    fun `ids are unique`() {
        val ids = ExerciseLibrary.entries.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test
    fun `substitutions are same pattern, exclude self, ranked`() {
        val subs = ExerciseLibrary.substitutionsFor("bb_back_squat")
        assertFalse(subs.any { it.id == "bb_back_squat" })
        assertTrue(subs.all { it.pattern == MovementPattern.SQUAT_BILATERAL })
        assertEquals(
            listOf("hack_squat", "leg_press", "goblet_squat", "front_squat", "smith_squat"),
            subs.take(5).map { it.id },
        )
    }

    @Test
    fun `leg extension no longer falls back, KNEE_EXTENSION now has siblings`() {
        // fallbackPattern is retained on the entry but dormant now that KNEE_EXTENSION
        // has its own substitution candidates.
        val subs = ExerciseLibrary.substitutionsFor("leg_ext")
        assertEquals(listOf("sl_leg_ext", "sissy_squat", "reverse_nordic"), subs.map { it.id })
    }

    @Test
    fun `subRanks within each pattern are unique and contiguous from 1`() {
        for (pattern in MovementPattern.entries) {
            val ranks = ExerciseLibrary.byPattern(pattern).map { it.subRank }
            if (ranks.isEmpty()) continue
            assertEquals(ranks.size, ranks.toSet().size, "duplicate subRank in $pattern")
            assertEquals((1..ranks.size).toList(), ranks.sorted(), "non-contiguous subRanks in $pattern")
        }
    }

    @Test
    fun `catalog has at least 150 entries`() {
        assertTrue(ExerciseLibrary.entries.size >= 150)
    }

    @Test
    fun `every non-CARDIO movement pattern has at least 2 entries`() {
        for (pattern in MovementPattern.entries) {
            if (pattern == MovementPattern.CARDIO) continue
            assertTrue(
                ExerciseLibrary.byPattern(pattern).size >= 2,
                "$pattern has fewer than 2 entries",
            )
        }
    }

    @Test
    fun `Std goal source is limited to the original main-capable lift ids`() {
        val allowedStdIds = setOf(
            "bb_back_squat", "conv_dl", "trap_dl", "sumo_dl", "bb_bench", "incline_db", "ohp", "bb_row",
        )
        val stdIds = ExerciseLibrary.entries.filter { it.goal is GoalSource.Std }.map { it.id }
        assertTrue(stdIds.toSet().all { it in allowedStdIds })
    }

    @Test
    fun `weight stepper is 2_5 at or below 20lb else 5`() {
        assertEquals(2.5, WeightStepper.increment(15.0, WeightUnit.LB), 1e-9)
        assertEquals(2.5, WeightStepper.increment(20.0, WeightUnit.LB), 1e-9)
        assertEquals(5.0, WeightStepper.increment(20.5, WeightUnit.LB), 1e-9)
        assertEquals(5.0, WeightStepper.increment(225.0, WeightUnit.LB), 1e-9)
    }

    // --- Tracking-types P2 (catalog classification + variant pairs) ---------

    @Test
    fun `catalog totals are 184 entries, 149 weighted, 28 reps, 7 timed`() {
        val byType = ExerciseLibrary.entries.groupingBy { it.tracking }.eachCount()
        assertEquals(184, ExerciseLibrary.entries.size)
        assertEquals(149, byType[TrackingType.WEIGHTED] ?: 0)
        assertEquals(28, byType[TrackingType.REPS] ?: 0)
        assertEquals(7, byType[TrackingType.TIMED] ?: 0)
    }

    @Test
    fun `exact set of REPS entries`() {
        val expected = setOf(
            "pistol_squat", "skater_squat", "back_ext", "ball_curl", "nordic", "ghr", "slider_curl",
            "sissy_squat", "reverse_nordic", "pushup", "pike_pushup", "hspu", "inverted_row", "pullup",
            "neutral_pullup", "bench_dip", "diamond_pushup", "bw_calf_raise", "ab_wheel", "dead_bug",
            "body_saw", "hanging_raise", "decline_situp", "reverse_crunch", "v_up", "knee_raise",
            "bw_dip", "hanging_knee_raise",
        )
        val actual = ExerciseLibrary.entries.filter { it.tracking == TrackingType.REPS }.map { it.id }.toSet()
        assertEquals(expected, actual)
    }

    @Test
    fun `exact set of TIMED entries`() {
        val expected = setOf(
            "plank", "hollow_hold", "weighted_plank", "suitcase_carry", "dead_hang", "farmers_carry", "wall_sit",
        )
        val actual = ExerciseLibrary.entries.filter { it.tracking == TrackingType.TIMED }.map { it.id }.toSet()
        assertEquals(expected, actual)
    }

    @Test
    fun `reclassified REPS entries carry their exact declared target`() {
        val expected = mapOf(
            "pistol_squat" to 5, "skater_squat" to 8, "back_ext" to 12, "ball_curl" to 12, "nordic" to 6,
            "ghr" to 8, "slider_curl" to 10, "sissy_squat" to 10, "reverse_nordic" to 10, "pushup" to 15,
            "pike_pushup" to 10, "hspu" to 5, "inverted_row" to 10, "pullup" to 6, "neutral_pullup" to 6,
            "bench_dip" to 12, "diamond_pushup" to 12, "bw_calf_raise" to 15, "ab_wheel" to 10,
            "dead_bug" to 10, "body_saw" to 10, "hanging_raise" to 10, "decline_situp" to 20,
            "reverse_crunch" to 15, "v_up" to 12, "knee_raise" to 12,
        )
        for ((id, reps) in expected) {
            assertEquals(GoalSource.Reps(reps), ExerciseLibrary.get(id).goal, "unexpected goal for $id")
        }
    }

    @Test
    fun `reclassified TIMED entries carry the old Flat weight into addedWeightLb`() {
        assertEquals(GoalSource.Time(45, 0.0), ExerciseLibrary.get("plank").goal)
        assertEquals(GoalSource.Time(30, 0.0), ExerciseLibrary.get("hollow_hold").goal)
        assertEquals(GoalSource.Time(45, 25.0), ExerciseLibrary.get("weighted_plank").goal)
        assertEquals(GoalSource.Time(40, 50.0), ExerciseLibrary.get("suitcase_carry").goal)
    }

    @Test
    fun `dips is the single sanctioned existing WEIGHTED goal change`() {
        assertEquals(GoalSource.Flat(45.0), ExerciseLibrary.get("dips").goal)
    }

    @Test
    fun `new entries exist with their declared GoalSource and subRank`() {
        val weightedPullup = ExerciseLibrary.get("weighted_pullup")
        assertEquals(V_PULL, weightedPullup.pattern)
        assertEquals(GoalSource.Flat(25.0), weightedPullup.goal)
        assertEquals(10, weightedPullup.subRank)

        val weightedNeutralPullup = ExerciseLibrary.get("weighted_neutral_pullup")
        assertEquals(V_PULL, weightedNeutralPullup.pattern)
        assertEquals(GoalSource.Flat(25.0), weightedNeutralPullup.goal)
        assertEquals(11, weightedNeutralPullup.subRank)

        val deadHang = ExerciseLibrary.get("dead_hang")
        assertEquals(V_PULL, deadHang.pattern)
        assertEquals(GoalSource.Time(30, 0.0), deadHang.goal)
        assertEquals(12, deadHang.subRank)

        val bwDip = ExerciseLibrary.get("bw_dip")
        assertEquals(H_PUSH, bwDip.pattern)
        assertEquals(GoalSource.Reps(10), bwDip.goal)
        assertEquals(18, bwDip.subRank)
        assertEquals("dips", bwDip.weightedPairId)

        val weightedPushup = ExerciseLibrary.get("weighted_pushup")
        assertEquals(H_PUSH, weightedPushup.pattern)
        assertEquals(GoalSource.Flat(25.0), weightedPushup.goal)
        assertEquals(19, weightedPushup.subRank)

        val hangingKneeRaise = ExerciseLibrary.get("hanging_knee_raise")
        assertEquals(CORE_FLEX, hangingKneeRaise.pattern)
        assertEquals(GoalSource.Reps(12), hangingKneeRaise.goal)
        assertEquals(9, hangingKneeRaise.subRank)
        assertEquals("weighted_knee_raise", hangingKneeRaise.weightedPairId)

        val weightedKneeRaise = ExerciseLibrary.get("weighted_knee_raise")
        assertEquals(CORE_FLEX, weightedKneeRaise.pattern)
        assertEquals(GoalSource.Flat(10.0), weightedKneeRaise.goal)
        assertEquals(10, weightedKneeRaise.subRank)

        val farmersCarry = ExerciseLibrary.get("farmers_carry")
        assertEquals(CORE_ANTI_ROT, farmersCarry.pattern)
        assertEquals(GoalSource.Time(40, 50.0), farmersCarry.goal)
        assertEquals(7, farmersCarry.subRank)
        assertTrue(farmersCarry.perHand)

        val wallSit = ExerciseLibrary.get("wall_sit")
        assertEquals(SQUAT_BILATERAL, wallSit.pattern)
        assertEquals(GoalSource.Time(45, 0.0), wallSit.goal)
        assertEquals(14, wallSit.subRank)
    }

    @Test
    fun `every declared weightedPairId resolves through the index to a same-pattern loaded target`() {
        val pairs = mapOf(
            "pullup" to "weighted_pullup",
            "neutral_pullup" to "weighted_neutral_pullup",
            "bw_dip" to "dips",
            "pushup" to "weighted_pushup",
            "hanging_knee_raise" to "weighted_knee_raise",
            "back_ext" to "back_ext_45",
            "plank" to "weighted_plank",
            "bw_calf_raise" to "db_calf",
        )
        assertEquals(pairs, ExerciseLibrary.entries.mapNotNull { e -> e.weightedPairId?.let { e.id to it } }.toMap())
        for ((sourceId, targetId) in pairs) {
            val source = ExerciseLibrary.get(sourceId)
            val target = ExerciseLibrary.get(targetId)
            assertEquals(targetId, source.weightedPairId)
            assertEquals(source.pattern, target.pattern, "$sourceId -> $targetId crosses pattern")
            val loaded = target.tracking == TrackingType.WEIGHTED ||
                (target.tracking == TrackingType.TIMED && (target.goal as GoalSource.Time).addedWeightLb > 0.0)
            assertTrue(loaded, "$targetId is not a loaded target (${target.tracking})")
            assertEquals(sourceId, ExerciseLibrary.bodyweightPairFor(targetId))
        }
    }

    @Test
    fun `weightedPairId targets are injective and no target declares its own pair`() {
        val targets = ExerciseLibrary.entries.mapNotNull { it.weightedPairId }
        assertEquals(targets.size, targets.toSet().size, "a pair target is claimed by more than one source")
        for (targetId in targets) {
            assertEquals(null, ExerciseLibrary.get(targetId).weightedPairId, "$targetId is itself a pair source")
        }
    }

    @Test
    fun `every Std entry is WEIGHTED`() {
        for (entry in ExerciseLibrary.entries) {
            if (entry.goal is GoalSource.Std) {
                assertEquals(TrackingType.WEIGHTED, entry.tracking, "${entry.id} is Std but not WEIGHTED")
            }
        }
    }

    @Test
    fun `Reps and Time targets are within sane ranges, no stray perHand`() {
        for (entry in ExerciseLibrary.entries) {
            when (val goal = entry.goal) {
                is GoalSource.Reps -> {
                    assertTrue(goal.targetReps in 5..20, "${entry.id} reps target out of range: ${goal.targetReps}")
                    assertFalse(entry.perHand, "${entry.id} is REPS but perHand")
                }
                is GoalSource.Time -> {
                    assertTrue(
                        goal.targetSeconds in 30..60,
                        "${entry.id} time target out of range: ${goal.targetSeconds}",
                    )
                    assertTrue(goal.addedWeightLb >= 0.0, "${entry.id} has negative addedWeightLb")
                    if (entry.perHand) {
                        assertTrue(
                            entry.id in setOf("farmers_carry", "suitcase_carry"),
                            "${entry.id} is TIMED and perHand but not a known carry",
                        )
                    }
                }
                else -> Unit
            }
        }
    }

    @Test
    fun `RECLASSIFIED_TO_TIMED_IDS is the exact legacy-fixup set and all members are TIMED`() {
        val expected = setOf("plank", "hollow_hold", "weighted_plank", "suitcase_carry")
        assertEquals(expected, ExerciseLibrary.RECLASSIFIED_TO_TIMED_IDS)
        for (id in ExerciseLibrary.RECLASSIFIED_TO_TIMED_IDS) {
            assertEquals(TrackingType.TIMED, ExerciseLibrary.get(id).tracking)
        }
    }

    /** (pattern, subRank) for every id that existed before the tracking-types P2
     *  data pass, snapshotted so no future edit can silently shift a rank while
     *  reclassifying a goal (contract: only the `goal` argument moves on the 31
     *  reclassified/changed lines; nothing moves on the other 144). */
    private val preExistingRanks: Map<String, Pair<MovementPattern, Int>> = mapOf(
        "bb_back_squat" to (SQUAT_BILATERAL to 1),
        "hack_squat" to (SQUAT_BILATERAL to 2),
        "leg_press" to (SQUAT_BILATERAL to 3),
        "goblet_squat" to (SQUAT_BILATERAL to 4),
        "front_squat" to (SQUAT_BILATERAL to 5),
        "smith_squat" to (SQUAT_BILATERAL to 6),
        "ssb_squat" to (SQUAT_BILATERAL to 7),
        "box_squat" to (SQUAT_BILATERAL to 8),
        "belt_squat" to (SQUAT_BILATERAL to 9),
        "pendulum_squat" to (SQUAT_BILATERAL to 10),
        "kb_goblet_squat" to (SQUAT_BILATERAL to 11),
        "kb_front_squat" to (SQUAT_BILATERAL to 12),
        "zercher_squat" to (SQUAT_BILATERAL to 13),
        "bss" to (SINGLE_LEG to 1),
        "walking_lunge" to (SINGLE_LEG to 2),
        "reverse_lunge" to (SINGLE_LEG to 3),
        "step_up" to (SINGLE_LEG to 4),
        "bb_bss" to (SINGLE_LEG to 5),
        "ffe_split_squat" to (SINGLE_LEG to 6),
        "forward_lunge" to (SINGLE_LEG to 7),
        "lateral_lunge" to (SINGLE_LEG to 8),
        "curtsy_lunge" to (SINGLE_LEG to 9),
        "smith_split_squat" to (SINGLE_LEG to 10),
        "sl_leg_press" to (SINGLE_LEG to 11),
        "cossack_squat" to (SINGLE_LEG to 12),
        "pistol_squat" to (SINGLE_LEG to 13),
        "skater_squat" to (SINGLE_LEG to 14),
        "trap_dl" to (HINGE to 1),
        "conv_dl" to (HINGE to 2),
        "sumo_dl" to (HINGE to 3),
        "rdl" to (HINGE to 4),
        "stiff_dl" to (HINGE to 5),
        "good_morning" to (HINGE to 6),
        "back_ext" to (HINGE to 7),
        "db_rdl" to (HINGE to 8),
        "kb_rdl" to (HINGE to 9),
        "sl_rdl" to (HINGE to 10),
        "hip_thrust" to (HINGE to 11),
        "glute_bridge" to (HINGE to 12),
        "machine_hip_thrust" to (HINGE to 13),
        "cable_pullthrough" to (HINGE to 14),
        "kb_swing" to (HINGE to 15),
        "kb_deadlift" to (HINGE to 16),
        "back_ext_45" to (HINGE to 17),
        "reverse_hyper" to (HINGE to 18),
        "block_pull" to (HINGE to 19),
        "smith_rdl" to (HINGE to 20),
        "seated_curl" to (KNEE_FLEXION to 1),
        "lying_curl" to (KNEE_FLEXION to 2),
        "ball_curl" to (KNEE_FLEXION to 3),
        "nordic" to (KNEE_FLEXION to 4),
        "standing_leg_curl" to (KNEE_FLEXION to 5),
        "ghr" to (KNEE_FLEXION to 6),
        "slider_curl" to (KNEE_FLEXION to 7),
        "leg_ext" to (KNEE_EXTENSION to 1),
        "sl_leg_ext" to (KNEE_EXTENSION to 2),
        "sissy_squat" to (KNEE_EXTENSION to 3),
        "reverse_nordic" to (KNEE_EXTENSION to 4),
        "bb_bench" to (H_PUSH to 1),
        "db_bench" to (H_PUSH to 2),
        "incline_db" to (H_PUSH to 3),
        "incline_bb" to (H_PUSH to 4),
        "machine_chest" to (H_PUSH to 5),
        "pec_deck" to (H_PUSH to 6),
        "dips" to (H_PUSH to 7),
        "smith_bench" to (H_PUSH to 8),
        "decline_bb" to (H_PUSH to 9),
        "incline_machine" to (H_PUSH to 10),
        "smith_incline" to (H_PUSH to 11),
        "floor_press" to (H_PUSH to 12),
        "kb_floor_press" to (H_PUSH to 13),
        "db_fly" to (H_PUSH to 14),
        "cable_fly" to (H_PUSH to 15),
        "low_cable_fly" to (H_PUSH to 16),
        "pushup" to (H_PUSH to 17),
        "ohp" to (V_PUSH to 1),
        "db_shoulder" to (V_PUSH to 2),
        "machine_shoulder" to (V_PUSH to 3),
        "landmine_press" to (V_PUSH to 4),
        "seated_bb_ohp" to (V_PUSH to 5),
        "push_press" to (V_PUSH to 6),
        "standing_db_press" to (V_PUSH to 7),
        "kb_press" to (V_PUSH to 8),
        "arnold_press" to (V_PUSH to 9),
        "smith_shoulder" to (V_PUSH to 10),
        "z_press" to (V_PUSH to 11),
        "pike_pushup" to (V_PUSH to 12),
        "hspu" to (V_PUSH to 13),
        "bb_row" to (H_PULL to 1),
        "cs_row" to (H_PULL to 2),
        "cable_row" to (H_PULL to 3),
        "db_row" to (H_PULL to 4),
        "pendlay_row" to (H_PULL to 5),
        "tbar_row" to (H_PULL to 6),
        "db_cs_row" to (H_PULL to 7),
        "machine_row" to (H_PULL to 8),
        "seal_row" to (H_PULL to 9),
        "single_cable_row" to (H_PULL to 10),
        "kb_row" to (H_PULL to 11),
        "inverted_row" to (H_PULL to 12),
        "pullup" to (V_PULL to 1),
        "lat_pd_wide" to (V_PULL to 2),
        "lat_pd_neutral" to (V_PULL to 3),
        "assisted_pullup" to (V_PULL to 4),
        "machine_pulldown" to (V_PULL to 5),
        "neutral_pullup" to (V_PULL to 6),
        "single_arm_pulldown" to (V_PULL to 7),
        "straight_arm_pd" to (V_PULL to 8),
        "db_pullover" to (V_PULL to 9),
        "cable_lateral" to (SIDE_DELT to 1),
        "db_lateral" to (SIDE_DELT to 2),
        "machine_lateral" to (SIDE_DELT to 3),
        "seated_db_lateral" to (SIDE_DELT to 4),
        "lean_away_lateral" to (SIDE_DELT to 5),
        "db_upright_row" to (SIDE_DELT to 6),
        "bb_upright_row" to (SIDE_DELT to 7),
        "face_pull" to (REAR_DELT to 1),
        "reverse_pec" to (REAR_DELT to 2),
        "cs_reverse_fly" to (REAR_DELT to 3),
        "db_reverse_fly" to (REAR_DELT to 4),
        "cable_rear_fly" to (REAR_DELT to 5),
        "rear_delt_row" to (REAR_DELT to 6),
        "ez_curl" to (BICEPS to 1),
        "incline_curl" to (BICEPS to 2),
        "hammer_curl" to (BICEPS to 3),
        "cable_curl" to (BICEPS to 4),
        "bb_curl" to (BICEPS to 5),
        "preacher_curl" to (BICEPS to 6),
        "machine_curl" to (BICEPS to 7),
        "rope_hammer_curl" to (BICEPS to 8),
        "concentration_curl" to (BICEPS to 9),
        "spider_curl" to (BICEPS to 10),
        "bayesian_curl" to (BICEPS to 11),
        "reverse_curl" to (BICEPS to 12),
        "rope_pushdown" to (TRICEPS to 1),
        "oh_tri_ext" to (TRICEPS to 2),
        "skullcrusher" to (TRICEPS to 3),
        "bar_pushdown" to (TRICEPS to 4),
        "single_pushdown" to (TRICEPS to 5),
        "db_oh_tri_ext" to (TRICEPS to 6),
        "db_skullcrusher" to (TRICEPS to 7),
        "machine_tri_ext" to (TRICEPS to 8),
        "cgbp" to (TRICEPS to 9),
        "jm_press" to (TRICEPS to 10),
        "bench_dip" to (TRICEPS to 11),
        "diamond_pushup" to (TRICEPS to 12),
        "kickback" to (TRICEPS to 13),
        "standing_calf" to (CALF_GASTROC to 1),
        "legpress_calf" to (CALF_GASTROC to 2),
        "smith_calf" to (CALF_GASTROC to 3),
        "donkey_calf" to (CALF_GASTROC to 4),
        "db_calf" to (CALF_GASTROC to 5),
        "bw_calf_raise" to (CALF_GASTROC to 6),
        "seated_calf" to (CALF_SOLEUS to 1),
        "bent_knee_lp_calf" to (CALF_SOLEUS to 2),
        "plank" to (CORE_ANTI_EXT to 1),
        "ab_wheel" to (CORE_ANTI_EXT to 2),
        "dead_bug" to (CORE_ANTI_EXT to 3),
        "weighted_plank" to (CORE_ANTI_EXT to 4),
        "body_saw" to (CORE_ANTI_EXT to 5),
        "hollow_hold" to (CORE_ANTI_EXT to 6),
        "pallof" to (CORE_ANTI_ROT to 1),
        "suitcase_carry" to (CORE_ANTI_ROT to 2),
        "half_kneel_pallof" to (CORE_ANTI_ROT to 3),
        "landmine_rotation" to (CORE_ANTI_ROT to 4),
        "kb_windmill" to (CORE_ANTI_ROT to 5),
        "turkish_getup" to (CORE_ANTI_ROT to 6),
        "cable_crunch" to (CORE_FLEX to 1),
        "hanging_raise" to (CORE_FLEX to 2),
        "machine_crunch" to (CORE_FLEX to 3),
        "weighted_crunch" to (CORE_FLEX to 4),
        "decline_situp" to (CORE_FLEX to 5),
        "reverse_crunch" to (CORE_FLEX to 6),
        "v_up" to (CORE_FLEX to 7),
        "knee_raise" to (CORE_FLEX to 8),
    )

    @Test
    fun `no id churn - all 175 pre-existing ids are present, catalog grows to 184`() {
        assertEquals(175, preExistingRanks.size)
        val currentIds = ExerciseLibrary.entries.map { it.id }.toSet()
        assertTrue(preExistingRanks.keys.all { it in currentIds })
        assertEquals(184, currentIds.size)
    }

    @Test
    fun `no rank shifts - every pre-existing id keeps its exact (pattern, subRank)`() {
        for ((id, expected) in preExistingRanks) {
            val entry = ExerciseLibrary.get(id)
            assertEquals(expected, entry.pattern to entry.subRank, "rank shifted for $id")
        }
    }

    @Test
    fun `pre-existing ids outside the reclassified-31 stay WEIGHTED`() {
        val reclassified = setOf(
            "pistol_squat", "skater_squat", "back_ext", "ball_curl", "nordic", "ghr", "slider_curl",
            "sissy_squat", "reverse_nordic", "pushup", "pike_pushup", "hspu", "inverted_row", "pullup",
            "neutral_pullup", "bench_dip", "diamond_pushup", "bw_calf_raise", "ab_wheel", "dead_bug",
            "body_saw", "hanging_raise", "decline_situp", "reverse_crunch", "v_up", "knee_raise",
            "plank", "hollow_hold", "weighted_plank", "suitcase_carry", "dips",
        )
        assertEquals(31, reclassified.size)
        for (id in preExistingRanks.keys - reclassified) {
            assertEquals(TrackingType.WEIGHTED, ExerciseLibrary.get(id).tracking, "$id unexpectedly not WEIGHTED")
        }
    }
}
