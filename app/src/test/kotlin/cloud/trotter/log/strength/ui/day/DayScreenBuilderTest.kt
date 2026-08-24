package cloud.trotter.log.strength.ui.day

import cloud.trotter.log.strength.data.LastPerformed
import cloud.trotter.log.strength.data.PersonalRecord
import cloud.trotter.log.strength.data.ProgramSlot
import cloud.trotter.log.strength.data.catalog.ExerciseCatalog
import cloud.trotter.log.strength.data.db.entity.Slot
import cloud.trotter.log.strength.domain.library.ExerciseEntry
import cloud.trotter.log.strength.domain.library.GoalSource
import cloud.trotter.log.strength.domain.library.TrackingType
import cloud.trotter.log.strength.domain.model.Equipment
import cloud.trotter.log.strength.domain.model.LifterConfig
import cloud.trotter.log.strength.domain.model.LoggedSet
import cloud.trotter.log.strength.domain.model.MovementPattern
import cloud.trotter.log.strength.domain.model.ProgramExercise
import cloud.trotter.log.strength.domain.model.SetKind
import cloud.trotter.log.strength.domain.model.SupersetPartner
import cloud.trotter.log.strength.domain.seeding.SetEditor
import cloud.trotter.log.strength.domain.seeding.SetSeeder
import cloud.trotter.log.strength.domain.standards.GoalCalculator
import cloud.trotter.log.strength.domain.units.WeightUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DayScreenBuilderTest {

    @Test
    fun doneButtonStateResolvesNothingPartialAndComplete() {
        assertEquals(DoneButtonState.NOTHING_LOGGED, DayScreenBuilder.doneButtonState(0, 18))
        assertEquals(DoneButtonState.PARTIAL, DayScreenBuilder.doneButtonState(7, 18))
        assertEquals(DoneButtonState.ALL_DONE, DayScreenBuilder.doneButtonState(18, 18))
    }

    @Test
    fun markNext_marks_first_undone_row_of_first_unfinished_card_only() {
        fun card(id: Long, done: List<Boolean>) = ExerciseCardState(
            programExerciseId = id,
            position = id.toInt(),
            title = "Card $id",
            isMain = false,
            isSuperset = false,
            hasWarmupHint = false,
            goalDisplay = "",
            perHand = false,
            allDone = done.isNotEmpty() && done.all { it },
            collapsed = false,
            collapsedSummary = "",
            rows = done.mapIndexed { index, checked ->
                SetRowState(index, "${index + 1}", false, 0.0, 1, checked)
            },
        )

        val cards = DayScreenBuilder.markNext(
            listOf(card(1, listOf(true)), card(2, listOf(true, false, false)), card(3, listOf(false))),
        )

        assertEquals(listOf(false), cards[0].rows.map { it.isNext })
        assertEquals(listOf(false, true, false), cards[1].rows.map { it.isNext })
        assertEquals(listOf(false), cards[2].rows.map { it.isNext })
    }

    private val cfg = LifterConfig() // bw 235, age 40, INTERMEDIATE, BALANCED
    private val catalog = ExerciseCatalog.CODE_ONLY

    private fun work(w: Double, r: Int, done: Boolean = false, seconds: Int = 0) =
        LoggedSet(w, r, SetKind.WORK, done, seconds)

    private fun top(w: Double, r: Int) = LoggedSet(w, r, SetKind.TOP)

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
        val plan = DayScreenBuilder.seedPlan(listOf(slot), existing = emptyMap(), cfg = cfg, catalog = catalog)

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
        val existing = mapOf((2L to Slot.MAIN) to 3)
        assertTrue(DayScreenBuilder.seedPlan(listOf(slot), existing, cfg, catalog).isEmpty())
    }

    @Test
    fun seedPlan_aligns_a_late_added_partner_to_the_live_main_track_not_the_seed() {
        // A partner attached to a slot the lifter has been training (#93): the main
        // track has grown to 4 rows with EXTRA sets, while a fresh seed would be 2.
        val slot = ProgramSlot(
            programExerciseId = 9,
            position = 0,
            exercise = ProgramExercise(
                exerciseId = "ez_curl",
                targetSets = 2,
                superset = SupersetPartner("rope_pushdown"),
            ),
        )
        val existing = mapOf((9L to Slot.MAIN) to 4)
        val plan = DayScreenBuilder.seedPlan(listOf(slot), existing, cfg, catalog)

        val partner = plan.single()
        assertEquals(Slot.SS, partner.slot)
        assertEquals(4, partner.sets.size)
        assertTrue(partner.sets.all { it.weightLb == 50.0 })
    }

    // A catalog carrying synthetic REPS/TIMED entries (P2 will reclassify real
    // ones; today the whole live catalog is WEIGHTED, so these prove crash-safety
    // ahead of that). `find` is a plain id lookup, so custom entries resolve.
    private val trackingCatalog = ExerciseCatalog(
        listOf(
            ExerciseEntry("custom_pullup", "Pull-up", MovementPattern.V_PULL, listOf(Equipment.BODYWEIGHT), perHand = false, goal = GoalSource.Reps(6), subRank = ExerciseCatalog.CUSTOM_SUBRANK),
            ExerciseEntry("custom_plank", "Plank", MovementPattern.CORE_ANTI_EXT, listOf(Equipment.BODYWEIGHT), perHand = false, goal = GoalSource.Time(45, 25.0), subRank = ExerciseCatalog.CUSTOM_SUBRANK),
        ),
    )

    @Test
    fun seedPlan_routes_a_REPS_entry_through_targetFor_without_throwing() {
        val slot = ProgramSlot(3, 0, ProgramExercise("custom_pullup", targetSets = 3))
        val plan = DayScreenBuilder.seedPlan(listOf(slot), emptyMap(), cfg, trackingCatalog)
        // No goalFor error() branch: all-WORK rows at the rep target, zero weight/seconds.
        assertEquals(List(3) { LoggedSet(0.0, 6, SetKind.WORK, seconds = 0) }, plan.single().sets)
    }

    @Test
    fun seedPlan_routes_a_TIMED_partner_through_targetFor_without_throwing() {
        // A weighted main with a TIMED superset partner — the partner path used to
        // call goalFor and would throw the moment the partner is reclassified.
        val slot = ProgramSlot(
            4, 0,
            ProgramExercise("ez_curl", targetSets = 3, superset = SupersetPartner("custom_plank")),
        )
        val plan = DayScreenBuilder.seedPlan(listOf(slot), emptyMap(), cfg, trackingCatalog)
        val partner = plan.first { it.slot == Slot.SS }
        assertEquals(List(3) { LoggedSet(25.0, 0, SetKind.WORK, seconds = 45) }, partner.sets)
    }

    @Test
    fun seedPlan_weighted_seed_is_unchanged_by_the_targetFor_routing() {
        // Behavior-preserving: the routed path must produce the exact same rows as
        // the old goalFor->seed path for a WEIGHTED slot.
        val pe = ProgramExercise("bb_back_squat", isMain = true, targetSets = 6)
        val slot = ProgramSlot(1, 0, pe)
        val routed = DayScreenBuilder.seedPlan(listOf(slot), emptyMap(), cfg, catalog).single().sets
        val direct = SetSeeder.seed(pe, GoalCalculator.goalFor(catalog.get("bb_back_squat"), cfg), cfg)
        assertEquals(direct, routed)
    }

    @Test
    fun seedPlan_seeds_main_lift_full_ramp_sequence() {
        val slot = ProgramSlot(1, 0, ProgramExercise(exerciseId = "bb_back_squat", isMain = true, targetSets = 6))
        val plan = DayScreenBuilder.seedPlan(listOf(slot), emptyMap(), cfg, catalog)
        val weights = plan.single().sets.map { it.weightLb }
        // Pinned §11 squat seed: 130/165/190/210 · TOP 235 · B/O 175.
        assertEquals(listOf(130.0, 165.0, 190.0, 210.0, 235.0, 175.0), weights)
    }

    // --- cascade triggering (VM calls SetEditor on the TOP row) --------------

    @Test
    fun editing_the_top_row_cascades_to_the_pinned_numbers() {
        val goal = GoalCalculator.goalForMain(
            cloud.trotter.log.strength.domain.model.StandardLift.SQUAT,
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

    @Test
    fun collapsedSummary_formats_a_REPS_track_with_no_weight_at_all() {
        val main = listOf(work(0.0, 12, done = true), work(0.0, 10, done = true))
        assertEquals(
            "×12 · ×10",
            DayScreenBuilder.collapsedSummary(main, partner = null, goalDisplay = "12 reps", unit = WeightUnit.LB, tracking = TrackingType.REPS),
        )
    }

    @Test
    fun collapsedSummary_formats_a_TIMED_track_as_a_hold() {
        val main = listOf(work(0.0, 0, done = true, seconds = 45))
        assertEquals(
            "45s",
            DayScreenBuilder.collapsedSummary(main, partner = null, goalDisplay = "45s", unit = WeightUnit.LB, tracking = TrackingType.TIMED),
        )
    }

    @Test
    fun collapsedSummary_formats_main_and_partner_independently_when_their_tracking_differs() {
        // A weighted main superset with a TIMED accessory partner — valid per
        // §3 (only mains must be WEIGHTED); each track formats by its own type.
        val main = listOf(work(60.0, 12, done = true))
        val partner = listOf(work(0.0, 0, seconds = 30))
        assertEquals(
            "60×12(30s)",
            DayScreenBuilder.collapsedSummary(
                main, partner, goalDisplay = "60", unit = WeightUnit.LB,
                tracking = TrackingType.WEIGHTED, partnerTracking = TrackingType.TIMED,
            ),
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

    // --- kind labels from raw kinds (Log screen reuse, #14) ------------------

    @Test
    fun kindLabelsForKinds_matches_kindLabels_over_the_same_sequence() {
        val main = SetSeeder.seed(ProgramExercise("bb_back_squat", isMain = true, targetSets = 6), 235.0, cfg)
        assertEquals(DayScreenBuilder.kindLabels(main), DayScreenBuilder.kindLabelsForKinds(main.map { it.kind }))
    }

    @Test
    fun kindLabelsForKinds_restarts_the_ramp_counter_per_call() {
        // Each history exercise group (#14) is labeled independently — R1 always
        // starts over, it never keeps counting from a previous exercise's group.
        assertEquals(
            listOf("R1", "TOP"),
            DayScreenBuilder.kindLabelsForKinds(listOf(SetKind.RAMP, SetKind.TOP)),
        )
    }

    // --- "last time" chip (PLAN.md A1 bonus, #14) ----------------------------

    @Test
    fun lastTimeDisplay_formats_weight_and_reps_in_the_display_unit() {
        assertEquals("185×8", DayScreenBuilder.lastTimeDisplay(LastPerformed(185.0, 8), WeightUnit.LB))
        // 44.092452436 lb == exactly 20 kg — an even round trip so the assertion
        // isn't sensitive to WeightStepper's decimal formatting.
        assertEquals("20×8", DayScreenBuilder.lastTimeDisplay(LastPerformed(44.092452436, 8), WeightUnit.KG))
    }

    @Test
    fun lastTimeDisplay_is_null_when_never_performed() {
        assertNull(DayScreenBuilder.lastTimeDisplay(null, WeightUnit.LB))
    }

    @Test
    fun lastTimeDisplay_reads_a_hold_from_seconds_when_present() {
        assertEquals("45s", DayScreenBuilder.lastTimeDisplay(LastPerformed(0.0, 0, seconds = 45), WeightUnit.LB))
    }

    @Test
    fun lastTimeDisplay_renders_a_legacy_reps_shaped_TIMED_row_as_reps_never_0s() {
        // Logged before the exercise's reclassification to TIMED: seconds is
        // still 0, the hold sits in reps (P3 Decision 5's assumption) — the
        // history chip must read the value, not "0s".
        assertEquals("×45", DayScreenBuilder.lastTimeDisplay(LastPerformed(0.0, 45), WeightUnit.LB))
    }

    // --- "Best" profile chip (performance-profile.md Phase 1) ----------------

    @Test
    fun personalRecordDisplay_formats_weight_and_reps_in_the_display_unit() {
        val record = PersonalRecord("bb_bench", 245.0, 5, 1_000L)
        assertEquals("245×5", DayScreenBuilder.personalRecordDisplay(record, lastTime = null, WeightUnit.LB))
        // 44.092452436 lb == exactly 20 kg, same even round trip as the last-time test.
        val kgRecord = PersonalRecord("bb_bench", 44.092452436, 8, 1_000L)
        assertEquals("20×8", DayScreenBuilder.personalRecordDisplay(kgRecord, lastTime = null, WeightUnit.KG))
    }

    @Test
    fun personalRecordDisplay_is_null_when_there_is_no_record() {
        assertNull(DayScreenBuilder.personalRecordDisplay(null, lastTime = LastPerformed(185.0, 5), WeightUnit.LB))
    }

    @Test
    fun personalRecordDisplay_renders_a_legacy_reps_shaped_TIMED_row_as_reps_never_0s() {
        val record = PersonalRecord("plank", weightLb = 0.0, reps = 60, achievedAtMillis = 1_000L)
        assertEquals("×60", DayScreenBuilder.personalRecordDisplay(record, lastTime = null, WeightUnit.LB))
    }

    @Test
    fun personalRecordDisplay_is_suppressed_when_it_equals_the_last_time_chip() {
        // The record IS the most recent performance — showing "245×5" twice
        // right next to each other would be redundant noise, not signal.
        val record = PersonalRecord("bb_back_squat", 245.0, 5, 1_000L)
        assertNull(DayScreenBuilder.personalRecordDisplay(record, lastTime = LastPerformed(245.0, 5), WeightUnit.LB))
    }

    @Test
    fun personalRecordDisplay_shows_when_it_differs_from_the_last_time_chip() {
        val record = PersonalRecord("bb_back_squat", 245.0, 5, 1_000L)
        assertEquals(
            "245×5",
            DayScreenBuilder.personalRecordDisplay(record, lastTime = LastPerformed(225.0, 5), WeightUnit.LB),
        )
    }

    // --- ADD WEIGHT / REMOVE WEIGHT pill (§4.2) ------------------------------

    @Test
    fun weightSwapAffordance_offers_ADD_WEIGHT_for_an_entry_that_declares_a_weighted_pair() {
        val plank = catalog.get("plank")
        val swap = DayScreenBuilder.weightSwapAffordance(plank, catalog)
        assertEquals("weighted_plank", swap?.targetExerciseId)
        assertEquals("Weighted Plank", swap?.targetName)
        assertEquals(false, swap?.isRemove)
    }

    @Test
    fun weightSwapAffordance_offers_REMOVE_WEIGHT_for_the_declared_target_itself() {
        val weightedPlank = catalog.get("weighted_plank")
        val swap = DayScreenBuilder.weightSwapAffordance(weightedPlank, catalog)
        assertEquals("plank", swap?.targetExerciseId)
        assertEquals("Plank / Side Plank", swap?.targetName)
        assertEquals(true, swap?.isRemove)
    }

    @Test
    fun weightSwapAffordance_is_null_for_an_entry_with_no_pair_link_at_all() {
        assertNull(DayScreenBuilder.weightSwapAffordance(catalog.get("bb_back_squat"), catalog))
    }

    @Test
    fun weightSwapAffordance_is_null_for_an_unresolved_entry() {
        assertNull(DayScreenBuilder.weightSwapAffordance(null, catalog))
    }

    // --- "Plates: …" line (docs/briefs/plate-math.md §2) ---------------------

    private val barbellEquipment = catalog.get("bb_back_squat").equipment

    @Test
    fun plateLine_shows_the_load_for_a_barbell_exercise() {
        val main = listOf(work(235.0, 5))
        assertEquals(cloud.trotter.log.strength.ui.text.UiText.DayPlate("45 + 45 + 5"), DayScreenBuilder.plateLine(main, barbellEquipment, WeightUnit.LB))
    }

    @Test
    fun plateLine_is_null_for_a_non_barbell_exercise() {
        val main = listOf(work(60.0, 8))
        val ezBarEquipment = catalog.get("ez_curl").equipment
        assertNull(DayScreenBuilder.plateLine(main, ezBarEquipment, WeightUnit.LB))
    }

    @Test
    fun plateLine_follows_the_first_undone_set_through_a_ramp() {
        val main = listOf(
            work(130.0, 5, done = true),
            work(165.0, 5, done = true),
            work(190.0, 3),
            work(210.0, 1),
        )
        // First two ramp sets are ticked, so the line reads the next one (190), not the TOP.
        assertEquals(cloud.trotter.log.strength.ui.text.UiText.DayPlate("45 + 25 + 2.5"), DayScreenBuilder.plateLine(main, barbellEquipment, WeightUnit.LB))
    }

    @Test
    fun plateLine_updates_when_the_next_sets_weight_is_edited() {
        val main = listOf(work(235.0, 5))
        assertEquals(cloud.trotter.log.strength.ui.text.UiText.DayPlate("45 + 45 + 5"), DayScreenBuilder.plateLine(main, barbellEquipment, WeightUnit.LB))
        val edited = listOf(work(245.0, 5))
        assertEquals(cloud.trotter.log.strength.ui.text.UiText.DayPlate("45 + 45 + 10"), DayScreenBuilder.plateLine(edited, barbellEquipment, WeightUnit.LB))
    }

    @Test
    fun plateLine_reads_empty_bar_at_bar_weight() {
        val main = listOf(work(45.0, 5))
        assertEquals(cloud.trotter.log.strength.ui.text.UiText.DayPlate(null), DayScreenBuilder.plateLine(main, barbellEquipment, WeightUnit.LB))
    }

    @Test
    fun plateLine_is_null_when_every_set_is_done() {
        val main = listOf(work(235.0, 5, done = true))
        assertNull(DayScreenBuilder.plateLine(main, barbellEquipment, WeightUnit.LB))
    }

    @Test
    fun plateLine_is_null_when_the_weight_cannot_be_loaded_exactly() {
        val main = listOf(work(137.0, 5))
        assertNull(DayScreenBuilder.plateLine(main, barbellEquipment, WeightUnit.LB))
    }

    // --- the in-progress status line (#126) ----------------------------------

    @Test
    fun sessionStatusLine_is_null_before_the_first_tick() {
        assertNull(DayScreenBuilder.sessionStatusLine(doneSets = 0, totalSets = 18))
    }

    @Test
    fun sessionStatusLine_reads_in_progress_with_the_count() {
        assertEquals(cloud.trotter.log.strength.ui.text.UiText.DayStatus(false, 4, 18), DayScreenBuilder.sessionStatusLine(4, 18))
    }

    /** The same phase vocabulary Today speaks — a fully ticked day is waiting
     *  on DONE, not still in progress. */
    @Test
    fun sessionStatusLine_turns_over_once_every_round_is_ticked() {
        assertEquals(cloud.trotter.log.strength.ui.text.UiText.DayStatus(true, 18, 18), DayScreenBuilder.sessionStatusLine(18, 18))
    }

    /** An over-count reads as a finished day, matching GlanceLines' `>=` rule. */
    @Test
    fun sessionStatusLine_treats_an_over_count_as_finished() {
        assertEquals(cloud.trotter.log.strength.ui.text.UiText.DayStatus(true, 19, 18), DayScreenBuilder.sessionStatusLine(19, 18))
    }

    // --- TOP set comparison -------------------------------------------------

    @Test
    fun topSetComparison_shows_a_positive_weight_delta() {
        assertEquals("+5 LB FROM LAST", DayScreenBuilder.topSetComparison(listOf(top(235.0, 5)), LastPerformed(230.0, 5), WeightUnit.LB))
    }

    @Test
    fun topSetComparison_shows_a_negative_weight_delta() {
        assertEquals("−5 LB FROM LAST", DayScreenBuilder.topSetComparison(listOf(top(235.0, 5)), LastPerformed(240.0, 5), WeightUnit.LB))
    }

    @Test
    fun topSetComparison_matches_equal_weight_and_reps() {
        assertEquals("MATCHED", DayScreenBuilder.topSetComparison(listOf(top(235.0, 5)), LastPerformed(235.0, 5), WeightUnit.LB))
    }

    @Test
    fun topSetComparison_singularizes_a_one_rep_gain() {
        assertEquals("+1 REP FROM LAST", DayScreenBuilder.topSetComparison(listOf(top(235.0, 6)), LastPerformed(235.0, 5), WeightUnit.LB))
    }

    @Test
    fun topSetComparison_shows_a_multi_rep_loss() {
        assertEquals("−2 REPS FROM LAST", DayScreenBuilder.topSetComparison(listOf(top(235.0, 3)), LastPerformed(235.0, 5), WeightUnit.LB))
    }

    @Test
    fun topSetComparison_marks_a_top_set_without_history_as_a_first_log() {
        assertEquals("FIRST LOG", DayScreenBuilder.topSetComparison(listOf(top(235.0, 5)), null, WeightUnit.LB))
    }

    @Test
    fun topSetComparison_is_null_for_an_accessory_track() {
        assertNull(DayScreenBuilder.topSetComparison(listOf(work(60.0, 10)), LastPerformed(60.0, 10), WeightUnit.LB))
    }

    @Test
    fun topSetComparison_is_null_for_a_timed_track() {
        assertNull(DayScreenBuilder.topSetComparison(listOf(work(0.0, 0, seconds = 45)), LastPerformed(0.0, 0, seconds = 45), WeightUnit.LB))
    }

    // 235 lb reads 106.59 kg and 230 lb reads 104.33 kg, and 106.59 − 104.33 is
    // 2.26. The raw conversion difference is 2.2679, which would print 2.27 —
    // a number the lifter cannot get from the two the card shows.
    @Test
    fun topSetComparison_converts_the_weight_delta_to_kg() {
        assertEquals("+2.26 KG FROM LAST", DayScreenBuilder.topSetComparison(listOf(top(235.0, 5)), LastPerformed(230.0, 5), WeightUnit.KG))
    }

    @Test
    fun topSetComparison_is_null_for_reps_shaped_history() {
        assertNull(DayScreenBuilder.topSetComparison(listOf(top(235.0, 5)), LastPerformed(0.0, 12), WeightUnit.LB))
    }

    // The comparison has to agree with the two numbers the lifter can actually
    // read. A kg conversion carries far more precision than the card prints, so
    // both sides are snapped to display precision before they're subtracted.

    @Test
    fun topSetComparison_reports_no_kg_delta_between_weights_that_render_the_same() {
        // 45.356 and 45.364 kg both print "45.36", but differ by 0.008 raw —
        // enough to have narrated a phantom "+0.01 KG FROM LAST".
        val lastLb = WeightUnit.KG.toLb(45.356)
        val topLb = WeightUnit.KG.toLb(45.364)
        assertEquals(
            "MATCHED",
            DayScreenBuilder.topSetComparison(listOf(top(topLb, 5)), LastPerformed(lastLb, 5), WeightUnit.KG),
        )
    }

    @Test
    fun topSetComparison_reports_exactly_one_step_between_weights_that_render_one_apart() {
        val lastLb = WeightUnit.KG.toLb(45.36)
        val topLb = WeightUnit.KG.toLb(45.37)
        assertEquals(
            "+0.01 KG FROM LAST",
            DayScreenBuilder.topSetComparison(listOf(top(topLb, 5)), LastPerformed(lastLb, 5), WeightUnit.KG),
        )
    }
}
