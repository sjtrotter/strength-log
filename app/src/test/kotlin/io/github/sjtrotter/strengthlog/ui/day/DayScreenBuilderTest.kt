package io.github.sjtrotter.strengthlog.ui.day

import io.github.sjtrotter.strengthlog.data.ProgramSlot
import io.github.sjtrotter.strengthlog.data.catalog.ExerciseCatalog
import io.github.sjtrotter.strengthlog.data.db.entity.Slot
import io.github.sjtrotter.strengthlog.domain.model.LifterConfig
import io.github.sjtrotter.strengthlog.domain.model.LoggedSet
import io.github.sjtrotter.strengthlog.domain.model.ProgramExercise
import io.github.sjtrotter.strengthlog.domain.model.SetKind
import io.github.sjtrotter.strengthlog.domain.model.SupersetPartner
import io.github.sjtrotter.strengthlog.domain.seeding.SetEditor
import io.github.sjtrotter.strengthlog.domain.seeding.SetSeeder
import io.github.sjtrotter.strengthlog.domain.standards.GoalCalculator
import io.github.sjtrotter.strengthlog.domain.units.WeightUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DayScreenBuilderTest {

    private val cfg = LifterConfig() // bw 235, age 40, INTERMEDIATE, BALANCED
    private val catalog = ExerciseCatalog.CODE_ONLY

    private fun work(w: Double, r: Int, done: Boolean = false) = LoggedSet(w, r, SetKind.WORK, done)

    // --- seeding-once --------------------------------------------------------

    @Test
    fun seedPlan_seeds_main_and_superset_partner_when_no_log_exists() {
        val slot = ProgramSlot(
            programExerciseId = 7,
            position = 0,
            exercise = ProgramExercise(
                exerciseId = "ez_curl",
                targetSets = 3,
                superset = SupersetPartner("rope_pushdown"),
            ),
        )
        val plan = DayScreenBuilder.seedPlan(listOf(slot), existing = emptySet(), cfg = cfg, catalog = catalog)

        assertEquals(2, plan.size)
        val main = plan.first { it.slot == Slot.MAIN }
        val partner = plan.first { it.slot == Slot.SS }
        assertEquals(7L, main.programExerciseId)
        assertEquals(3, main.sets.size)
        assertEquals(60.0, main.sets.first().weightLb)       // ez_curl flat GOAL 60
        assertEquals(3, partner.sets.size)
        assertEquals(50.0, partner.sets.first().weightLb)    // rope_pushdown flat GOAL 50
    }

    @Test
    fun seedPlan_never_reseeds_an_existing_slot() {
        val slot = ProgramSlot(2, 0, ProgramExercise(exerciseId = "ez_curl", targetSets = 3))
        val existing = setOf(2L to Slot.MAIN)
        assertTrue(DayScreenBuilder.seedPlan(listOf(slot), existing, cfg, catalog).isEmpty())
    }

    @Test
    fun seedPlan_seeds_main_lift_full_ramp_sequence() {
        val slot = ProgramSlot(1, 0, ProgramExercise(exerciseId = "bb_back_squat", isMain = true, targetSets = 6))
        val plan = DayScreenBuilder.seedPlan(listOf(slot), emptySet(), cfg, catalog)
        val weights = plan.single().sets.map { it.weightLb }
        // Pinned §11 squat seed: 130/165/190/210 · TOP 235 · B/O 175.
        assertEquals(listOf(130.0, 165.0, 190.0, 210.0, 235.0, 175.0), weights)
    }

    // --- cascade triggering (VM calls SetEditor on the TOP row) --------------

    @Test
    fun editing_the_top_row_cascades_to_the_pinned_numbers() {
        val goal = GoalCalculator.goalForMain(
            io.github.sjtrotter.strengthlog.domain.model.StandardLift.SQUAT,
            perHand = false,
            cfg = cfg,
        )
        val seeded = SetSeeder.seed(
            ProgramExercise("bb_back_squat", isMain = true, targetSets = 6),
            goal,
            cfg,
        )
        val topIndex = seeded.indexOfFirst { it.kind == SetKind.TOP }
        val cascaded = SetEditor.editWeight(seeded, topIndex, 245.0)
        // §11: squat TOP 245 → ramps 135/170/195/220, B/O 185.
        assertEquals(listOf(135.0, 170.0, 195.0, 220.0, 245.0, 185.0), cascaded.map { it.weightLb })
    }

    // --- kind labels ---------------------------------------------------------

    @Test
    fun kindLabels_number_ramps_and_mark_top_and_backoff() {
        val main = SetSeeder.seed(ProgramExercise("bb_back_squat", isMain = true, targetSets = 6), 235.0, cfg)
        assertEquals(listOf("R1", "R2", "R3", "R4", "TOP", "B/O"), DayScreenBuilder.kindLabels(main))
    }

    @Test
    fun kindLabels_number_accessory_work_sets() {
        val work = listOf(work(60.0, 10), work(60.0, 10), work(60.0, 10))
        assertEquals(listOf("1", "2", "3"), DayScreenBuilder.kindLabels(work))
    }

    // --- collapsed summary ---------------------------------------------------

    @Test
    fun collapsedSummary_shows_count_and_goal_when_nothing_checked() {
        val main = listOf(work(60.0, 10), work(60.0, 10), work(60.0, 10))
        assertEquals(
            "3 sets · GOAL 60",
            DayScreenBuilder.collapsedSummary(main, partner = null, goalDisplay = "60", unit = WeightUnit.LB),
        )
    }

    @Test
    fun collapsedSummary_lists_completed_plain_sets() {
        val main = listOf(work(100.0, 12, done = true), work(100.0, 10, done = true), work(100.0, 8))
        assertEquals(
            "100×12 · 100×10",
            DayScreenBuilder.collapsedSummary(main, partner = null, goalDisplay = "100", unit = WeightUnit.LB),
        )
    }

    @Test
    fun collapsedSummary_uses_superset_form() {
        val main = listOf(work(60.0, 12, done = true), work(60.0, 11, done = true))
        val partner = listOf(work(50.0, 15), work(50.0, 14))
        assertEquals(
            "60×12(50×15) / 60×11(50×14)",
            DayScreenBuilder.collapsedSummary(main, partner, goalDisplay = "60", unit = WeightUnit.LB),
        )
    }

    // --- collapse resolution -------------------------------------------------

    @Test
    fun allDone_and_autocollapse_only_when_every_round_checked() {
        val partial = listOf(work(60.0, 10, done = true), work(60.0, 10))
        val complete = listOf(work(60.0, 10, done = true), work(60.0, 10, done = true))
        assertEquals(false, DayScreenBuilder.allDone(partial))
        assertEquals(true, DayScreenBuilder.allDone(complete))
        assertEquals(false, DayScreenBuilder.collapsed(partial, manualOverride = null))
        assertEquals(true, DayScreenBuilder.collapsed(complete, manualOverride = null))
    }

    @Test
    fun manual_override_wins_over_auto_collapse() {
        val complete = listOf(work(60.0, 10, done = true))
        val partial = listOf(work(60.0, 10))
        // Manually expanded a finished card, and manually collapsed an unfinished one.
        assertEquals(false, DayScreenBuilder.collapsed(complete, manualOverride = false))
        assertEquals(true, DayScreenBuilder.collapsed(partial, manualOverride = true))
    }

    // --- one tick per round --------------------------------------------------

    @Test
    fun round_tick_flips_both_superset_tracks_at_the_same_index() {
        val main = listOf(work(60.0, 12), work(60.0, 11))
        val partner = listOf(work(50.0, 15), work(50.0, 14))
        val (newMain, newPartner) = DayScreenBuilder.applyRoundTick(main, partner, index = 0, checked = true)
        assertTrue(newMain[0].done)
        assertTrue(newPartner!![0].done)
        assertEquals(false, newMain[1].done)
        assertEquals(false, newPartner[1].done)
    }

    @Test
    fun round_tick_on_a_plain_exercise_leaves_partner_null() {
        val main = listOf(work(100.0, 10), work(100.0, 10))
        val (newMain, newPartner) = DayScreenBuilder.applyRoundTick(main, partner = null, index = 1, checked = true)
        assertTrue(newMain[1].done)
        assertEquals(null, newPartner)
    }

    // --- header helper copy (design-pass reference wording) ------------------

    @Test
    fun helper_copy_matches_the_design_reference() {
        assertEquals("Change the TOP set — ramp & back-off recalculate.", DayScreenBuilder.MAIN_HELPER)
        assertEquals("One tick checks the whole round — both moves, back-to-back.", DayScreenBuilder.SUPERSET_HELPER)
    }
}
