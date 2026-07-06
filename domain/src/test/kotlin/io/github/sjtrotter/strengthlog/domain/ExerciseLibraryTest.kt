package io.github.sjtrotter.strengthlog.domain

import io.github.sjtrotter.strengthlog.domain.library.ExerciseLibrary
import io.github.sjtrotter.strengthlog.domain.library.GoalSource
import io.github.sjtrotter.strengthlog.domain.model.MovementPattern
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
    fun `Std goal source is limited to the six original main lift ids`() {
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
}
