package io.github.sjtrotter.strengthlog.ui.day

import io.github.sjtrotter.strengthlog.data.catalog.ExerciseCatalog
import io.github.sjtrotter.strengthlog.domain.model.Equipment
import io.github.sjtrotter.strengthlog.domain.model.MovementPattern
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DayEditLogicTest {

    private val catalog = ExerciseCatalog.CODE_ONLY

    // --- ExercisePicker.filter: rank order is never disturbed -----------------

    @Test
    fun filter_with_no_query_or_equipment_narrowing_returns_the_candidates_unchanged() {
        val candidates = catalog.byPattern(MovementPattern.SQUAT_BILATERAL)
        val all = Equipment.entries.toSet()

        val result = ExercisePicker.filter(candidates, query = "", equipment = all)

        // Same list, same subRank order — filter only narrows, it never re-sorts.
        assertEquals(candidates, result)
        assertTrue(candidates.size > 1)
    }

    @Test
    fun filter_over_substitutionsFor_keeps_subRank_order_and_self_stays_excluded() {
        val candidates = catalog.substitutionsFor("ez_curl")

        val result = ExercisePicker.filter(candidates, query = "", equipment = Equipment.entries.toSet())

        assertEquals(candidates, result)
        assertFalse(result.any { it.id == "ez_curl" })
        assertEquals(listOf("incline_curl", "hammer_curl"), result.take(2).map { it.id })
    }

    // --- search ----------------------------------------------------------------

    @Test
    fun filter_matches_a_case_insensitive_name_substring() {
        val candidates = catalog.byPattern(MovementPattern.SQUAT_BILATERAL)
        val all = Equipment.entries.toSet()

        val result = ExercisePicker.filter(candidates, query = "PRESS", equipment = all)

        assertEquals(listOf("leg_press"), result.map { it.id })
    }

    @Test
    fun filter_with_blank_query_matches_everything() {
        val candidates = catalog.byPattern(MovementPattern.SQUAT_BILATERAL)
        val all = Equipment.entries.toSet()

        val result = ExercisePicker.filter(candidates, query = "   ", equipment = all)

        assertEquals(candidates, result)
    }

    // --- equipment filter (PLAN.md A4) -----------------------------------------

    @Test
    fun filter_excludes_entries_needing_equipment_outside_the_set() {
        val candidates = catalog.byPattern(MovementPattern.TRICEPS)
        // Only cable-only exercises pass; anything needing a barbell, dumbbell,
        // machine, bench, or bodyweight is excluded.
        val cableOnly = setOf(Equipment.CABLE)

        val result = ExercisePicker.filter(candidates, query = "", equipment = cableOnly)

        assertEquals(listOf("rope_pushdown", "oh_tri_ext", "bar_pushdown", "single_pushdown"), result.map { it.id })
    }

    @Test
    fun filter_requires_every_piece_of_an_entrys_equipment_not_just_one() {
        // incline_curl needs DUMBBELL *and* BENCH; owning only DUMBBELL excludes it.
        val candidates = catalog.substitutionsFor("ez_curl")
        val dumbbellOnly = setOf(Equipment.DUMBBELL)

        val result = ExercisePicker.filter(candidates, query = "", equipment = dumbbellOnly)

        assertEquals(listOf("hammer_curl"), result.map { it.id })
    }

    @Test
    fun filter_combines_query_and_equipment_narrowing() {
        val candidates = catalog.byPattern(MovementPattern.TRICEPS)
        val cableOnly = setOf(Equipment.CABLE)

        // "cable" matches only "Cable Overhead Triceps Extension" by name; the
        // other cable-equipped rows (rope/bar/single pushdown) don't say "cable".
        val result = ExercisePicker.filter(candidates, query = "cable", equipment = cableOnly)

        assertEquals(listOf("oh_tri_ext"), result.map { it.id })
    }

    // --- min-3-remove enforcement (spec §8.3) -----------------------------------

    @Test
    fun canRemove_is_false_at_or_below_the_minimum() {
        assertFalse(DayEditRules.canRemove(2))
        assertFalse(DayEditRules.canRemove(3))
    }

    @Test
    fun canRemove_is_true_above_the_minimum() {
        assertTrue(DayEditRules.canRemove(4))
        assertTrue(DayEditRules.canRemove(6))
    }
}
