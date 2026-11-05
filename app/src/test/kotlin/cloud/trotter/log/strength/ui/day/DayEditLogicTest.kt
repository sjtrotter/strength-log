package cloud.trotter.log.strength.ui.day

import cloud.trotter.log.strength.data.catalog.ExerciseCatalog
import cloud.trotter.log.strength.domain.model.Equipment
import cloud.trotter.log.strength.domain.model.MovementPattern
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DayEditLogicTest {

    @Test
    fun every_day_without_cardio_keeps_one_exercise() {
        assertFalse(DayEditRules.canRemove(0))
        assertFalse(DayEditRules.canRemove(1))
        assertTrue(DayEditRules.canRemove(2))
        assertTrue(DayEditRules.canRemove(3))
        assertTrue(DayEditRules.canRemove(4))
    }


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

    @Test
    fun filter_finds_dumbbell_rows() {
        val result = ExercisePicker.filter(catalog.byPattern(MovementPattern.H_PULL),
            query = "dumbbell row", equipment = Equipment.entries.toSet())

        assertEquals(listOf("db_row", "db_cs_row"), result.map { it.id })
    }

    @Test
    fun filter_finds_shoulder_presses() {
        val result = ExercisePicker.filter(catalog.byPattern(MovementPattern.V_PUSH),
            query = "shoulder press", equipment = Equipment.entries.toSet())

        assertEquals(listOf("db_shoulder", "machine_shoulder", "smith_shoulder"), result.map { it.id })
    }

    @Test
    fun filter_finds_db_curls() {
        val result = ExercisePicker.filter(catalog.byPattern(MovementPattern.BICEPS),
            query = "db curl", equipment = Equipment.entries.toSet())

        assertEquals(listOf("incline_curl"), result.map { it.id })
    }

    @Test
    fun filter_finds_glute_bridge() {
        val result = ExercisePicker.filter(catalog.entries,
            query = "bridge", equipment = Equipment.entries.toSet())

        assertEquals(listOf("glute_bridge"), result.map { it.id })
    }

    @Test
    fun filter_normalizes_both_sides_and_ignores_word_order_case_and_hyphens() {
        val expected = mapOf(
            "  ROW   DuMbBeLl  " to listOf("db_row", "db_cs_row", "db_upright_row", "rear_delt_row"),
            "BB row" to listOf("bb_row", "bb_upright_row"),
            "kettlebell row" to listOf("kb_row"),
            "SL extension" to listOf("sl_leg_ext"),
            "single leg rdl" to listOf("sl_rdl"),
            "press DB shoulder" to listOf("db_shoulder"),
            "dumb row" to listOf("db_row", "db_cs_row", "db_upright_row", "rear_delt_row"),
        )
        for ((query, ids) in expected) {
            assertEquals(ids, ExercisePicker.filter(catalog.entries, query, Equipment.entries.toSet()).map { it.id }, query)
        }
    }

    @Test
    fun filter_finds_every_curl_across_patterns() {
        val result = ExercisePicker.filter(catalog.entries,
            query = "curl", equipment = Equipment.entries.toSet())

        assertEquals(listOf("seated_curl", "lying_curl", "ball_curl", "nordic", "standing_leg_curl", "slider_curl",
            "ez_curl", "incline_curl", "hammer_curl", "cable_curl", "bb_curl", "preacher_curl", "machine_curl",
            "rope_hammer_curl", "concentration_curl", "spider_curl", "bayesian_curl", "reverse_curl"), result.map { it.id })
    }

    @Test
    fun filter_requires_every_query_word_and_keeps_equipment_restrictions() {
        assertTrue(ExercisePicker.filter(catalog.entries,
            query = "dumbbell row missing", equipment = Equipment.entries.toSet()).isEmpty())
        assertTrue(ExercisePicker.filter(catalog.entries,
            query = "dumbbell row", equipment = setOf(Equipment.CABLE)).isEmpty())
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


    @Test
    fun cardioAllowsRemovingTheLastExerciseInEveryPlan() {
        assertTrue(DayEditRules.canRemove(1, hasCardio = true))
        assertFalse(DayEditRules.canRemove(1))
        assertFalse(DayEditRules.canRemove(0, hasCardio = true))
        assertTrue(DayEditRules.canRemove(3, hasCardio = true))
    }

    @Test
    fun cardioPagesPopTheSetBeforeTheBlock() {
        assertEquals(DayEditPage.CARDIO_SET, dayEditBackTarget(false, false, false, false, false, false,
            cardio = true, cardioSet = true))
        assertEquals(DayEditPage.CARDIO, dayEditBackTarget(false, false, false, false, false, false,
            cardio = true))
    }

    @Test
    fun canRemove_is_true_above_the_minimum() {
        assertTrue(DayEditRules.canRemove(4))
        assertTrue(DayEditRules.canRemove(6))
    }

    // --- the sheet's back stack -------------------------------------------------

    private fun backTarget(
        swapping: Boolean = false,
        supersetSlot: Boolean = false,
        supersetPatternPicked: Boolean = false,
        pickingPattern: Boolean = false,
        addingFromPattern: Boolean = false,
        options: Boolean = false,
    ) = dayEditBackTarget(swapping, supersetSlot, supersetPatternPicked, pickingPattern, addingFromPattern, options)

    @Test
    fun back_on_the_root_slot_list_has_no_page_to_pop_so_the_sheet_dismisses() {
        assertNull(backTarget())
    }

    @Test
    fun back_from_the_swap_picker_pops_the_picker_not_the_sheet() {
        // Reached as slot list -> options -> swap, so the options page is still
        // live underneath: back must land there, not dismiss.
        assertEquals(DayEditPage.SWAP, backTarget(swapping = true, options = true))
    }

    @Test
    fun back_from_the_options_page_pops_to_the_slot_list() {
        assertEquals(DayEditPage.OPTIONS, backTarget(options = true))
    }

    @Test
    fun back_walks_the_superset_flow_one_page_at_a_time() {
        assertEquals(
            DayEditPage.SUPERSET_EXERCISE,
            backTarget(supersetSlot = true, supersetPatternPicked = true, options = true),
        )
        assertEquals(DayEditPage.SUPERSET_PATTERN, backTarget(supersetSlot = true, options = true))
    }

    @Test
    fun back_walks_the_add_flow_one_page_at_a_time() {
        assertEquals(DayEditPage.ADD_EXERCISE, backTarget(addingFromPattern = true))
        assertEquals(DayEditPage.ADD_PATTERN, backTarget(pickingPattern = true))
    }
}
