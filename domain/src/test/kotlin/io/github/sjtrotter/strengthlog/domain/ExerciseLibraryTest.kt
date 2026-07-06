package io.github.sjtrotter.strengthlog.domain

import io.github.sjtrotter.strengthlog.domain.library.ExerciseLibrary
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
        assertEquals(listOf("hack_squat", "leg_press", "goblet_squat", "front_squat", "smith_squat"), subs.map { it.id })
    }

    @Test
    fun `leg extension falls back to squat pattern when its own has no siblings`() {
        // KNEE_EXTENSION holds only leg_ext, so substitutions come from the noted fallback.
        val subs = ExerciseLibrary.substitutionsFor("leg_ext")
        assertTrue(subs.isNotEmpty())
        assertTrue(subs.all { it.pattern == MovementPattern.SQUAT_BILATERAL })
        assertEquals("bb_back_squat", subs.first().id)
    }

    @Test
    fun `weight stepper is 2_5 at or below 20lb else 5`() {
        assertEquals(2.5, WeightStepper.increment(15.0, WeightUnit.LB), 1e-9)
        assertEquals(2.5, WeightStepper.increment(20.0, WeightUnit.LB), 1e-9)
        assertEquals(5.0, WeightStepper.increment(20.5, WeightUnit.LB), 1e-9)
        assertEquals(5.0, WeightStepper.increment(225.0, WeightUnit.LB), 1e-9)
    }
}
