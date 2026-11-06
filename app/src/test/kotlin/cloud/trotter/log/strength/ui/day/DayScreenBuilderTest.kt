package cloud.trotter.log.strength.ui.day

import cloud.trotter.log.strength.data.LastPerformed
import cloud.trotter.log.strength.data.PersonalRecord
import cloud.trotter.log.strength.data.SlotSession
import cloud.trotter.log.strength.data.catalog.ExerciseCatalog
import cloud.trotter.log.strength.data.db.entity.Slot
import cloud.trotter.log.strength.domain.library.TrackingType
import cloud.trotter.log.strength.domain.model.LifterConfig
import cloud.trotter.log.strength.domain.model.LoggedSet
import cloud.trotter.log.strength.domain.model.PrescribedTarget
import cloud.trotter.log.strength.domain.model.Prescription
import cloud.trotter.log.strength.domain.model.ProgramExercise
import cloud.trotter.log.strength.domain.model.SetKind
import cloud.trotter.log.strength.domain.model.SetStructure
import cloud.trotter.log.strength.domain.seeding.SetEditor
import cloud.trotter.log.strength.domain.seeding.SetSeeder
import cloud.trotter.log.strength.domain.standards.GoalCalculator
import cloud.trotter.log.strength.domain.standards.GoalTarget
import cloud.trotter.log.strength.domain.units.WeightUnit
import cloud.trotter.log.strength.sync.RemoteTick
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DayScreenBuilderTest {

    @Test
    fun `offer label and delta use display precision`() {
        assertEquals("+5 lb", DayScreenBuilder.offerLabel(225.0, 220.0, WeightUnit.LB))
        assertEquals(5.0, DayScreenBuilder.offerDelta(225.0, 220.0, WeightUnit.LB))
        val offer = WeightUnit.KG.toLb(102.5)
        val opening = WeightUnit.KG.toLb(100.0)
        assertEquals("+2.5 kg", DayScreenBuilder.offerLabel(offer, opening, WeightUnit.KG))
        assertEquals(2.5, DayScreenBuilder.offerDelta(offer, opening, WeightUnit.KG))
        assertEquals("+2.26 kg", DayScreenBuilder.offerLabel(235.0, 230.0, WeightUnit.KG))
        assertEquals(0.0, DayScreenBuilder.offerDelta(WeightUnit.KG.toLb(45.364), WeightUnit.KG.toLb(45.356), WeightUnit.KG))
    }

    @Test
    fun `working heading reads the rows not the prescription`() {
        val rows = List(3) { work(80.0, 10) }
        assertEquals("3 × 10 · 80 lb", DayScreenBuilder.workingHeading(rows, TrackingType.WEIGHTED, WeightUnit.LB))
        assertEquals("3 × 10 · 36.29 kg", DayScreenBuilder.workingHeading(rows, TrackingType.WEIGHTED, WeightUnit.KG))
        val squat = SetSeeder.seed(ProgramExercise("bb_back_squat", structure = SetStructure.RAMP_TOP_BACKOFF), 235.0, LifterConfig())
        assertEquals("TOP × 5 · 235 lb", DayScreenBuilder.workingHeading(squat, TrackingType.WEIGHTED, WeightUnit.LB))
        assertEquals("70 × 5, 70 × 5, 70 × 4", DayScreenBuilder.workingHeading(
            listOf(work(70.0, 5), work(70.0, 5), work(70.0, 4)), TrackingType.WEIGHTED, WeightUnit.LB))
        assertEquals("3 × 12", DayScreenBuilder.workingHeading(List(3) { work(0.0, 12) }, TrackingType.REPS, WeightUnit.LB))
        assertEquals("3 × 0:45 · 25 lb", DayScreenBuilder.workingHeading(
            List(3) { work(25.0, 0, seconds = 45) }, TrackingType.TIMED, WeightUnit.LB))
        assertEquals("3 × 0:45", DayScreenBuilder.workingHeading(
            List(3) { work(0.0, 0, seconds = 45) }, TrackingType.TIMED, WeightUnit.LB))
        assertNull(DayScreenBuilder.workingHeading(emptyList(), TrackingType.WEIGHTED, WeightUnit.LB))
        assertNull(DayScreenBuilder.workingHeading(listOf(LoggedSet(45.0, 5, SetKind.RAMP)), TrackingType.WEIGHTED, WeightUnit.LB))
    }

    @Test
    fun `next time is repeat after a miss and an offer after a success`() {
        val planned = List(3) { work(70.0, 5) }
        val success = planned.mapIndexed { i, set -> set.copy(done = true, plannedIndex = i) }
        val history = SlotSession(planned, success, retired = false)
        val miss = history.copy(actual = success.mapIndexed { i, set -> if (i == 2) set.copy(reps = 4) else set })
        assertEquals(NextTime.Repeat, DayScreenBuilder.nextTime(planned, miss, false, WeightUnit.LB))
        assertEquals(NextTime.Offer("+5 lb", false, 75.0, 70.0), DayScreenBuilder.nextTime(planned, history, false, WeightUnit.LB))
        val increased = planned.map { it.copy(weightLb = 75.0) }
        assertEquals(NextTime.Offer("+5 lb", true, 75.0, 70.0), DayScreenBuilder.nextTime(increased, history, false, WeightUnit.LB))
        assertNull(DayScreenBuilder.nextTime(planned.mapIndexed { i, set -> set.copy(done = i == 0) }, history, false, WeightUnit.LB))
        assertNull(DayScreenBuilder.nextTime(planned, history.copy(retired = true), false, WeightUnit.LB))
        assertNull(DayScreenBuilder.nextTime(planned, history, true, WeightUnit.LB))
        val kgRows = List(3) { work(WeightUnit.KG.toLb(60.0), 5) }
        val kgHistory = SlotSession(kgRows, kgRows.mapIndexed { i, set -> set.copy(done = true, plannedIndex = i) }, false)
        assertEquals("+2.5 kg", (DayScreenBuilder.nextTime(kgRows, kgHistory, false, WeightUnit.KG) as NextTime.Offer).label)
    }

    @Test
    fun `next time says nothing after a skip or a manual edit`() {
        val rows = List(3) { work(70.0, 5) }
        val history = SlotSession(rows, rows, false)
        assertNull(DayScreenBuilder.nextTime(rows, null, false, WeightUnit.LB))
        assertEquals(NextTime.Repeat, DayScreenBuilder.nextTime(rows, history, false, WeightUnit.LB))
        assertNull(DayScreenBuilder.nextTime(rows, history.copy(retired = true), false, WeightUnit.LB))
        val edited = rows.map { it.copy(weightLb = 72.5) }
        assertNull(DayScreenBuilder.nextTime(edited, history, false, WeightUnit.LB))
        val success = history.copy(actual = rows.mapIndexed { i, set -> set.copy(done = true, plannedIndex = i) })
        assertNull(DayScreenBuilder.nextTime(edited, success, false, WeightUnit.LB))
        assertNull(DayScreenBuilder.nextTime(emptyList(), history, false, WeightUnit.LB))
    }

    @Test
    fun `next time compares weights at display precision`() {
        val rows = List(3) { work(WeightUnit.KG.toLb(60.0), 5) }
        val success = SlotSession(rows, rows.mapIndexed { i, set -> set.copy(done = true, plannedIndex = i) }, false)
        val opening = rows.map { it.copy(weightLb = WeightUnit.KG.toLb(60.001)) }
        assertEquals(false, (DayScreenBuilder.nextTime(opening, success, false, WeightUnit.KG) as NextTime.Offer).applied)
        val applied = rows.map { it.copy(weightLb = WeightUnit.KG.toLb(62.501)) }
        assertEquals(true, (DayScreenBuilder.nextTime(applied, success, false, WeightUnit.KG) as NextTime.Offer).applied)
        assertEquals(NextTime.Repeat, DayScreenBuilder.nextTime(opening, success.copy(actual = emptyList()), false, WeightUnit.KG))
    }

    @Test
    fun loggedCardioAloneEnablesFinish() {
        val state = DayUiState(
            hasProgram = true, viewDayId = "A",
            exercises = workoutDayFixture().exercises.map { card ->
                card.copy(rows = card.rows.map { row ->
                    row.copy(done = false, partner = row.partner?.copy(done = false))
                })
            },
            cardio = CardioCardState("Road run", "", false, loggedSeconds = 300),
        )
        assertTrue(state.canFinish)
        assertNull(state.finishBlockedReason)
        val unlogged = state.copy(cardio = CardioCardState("Road run", "", false))
        assertFalse(unlogged.canFinish)
        assertEquals(WorkoutBlockedReason.NO_LOGGED_SETS, unlogged.finishBlockedReason)
    }

    @Test
    fun `generated header retains the exact goal value in either unit`() {
        val exercise = ProgramExercise("bb_back_squat", structure = SetStructure.RAMP_TOP_BACKOFF)
        val goal = GoalTarget.Weight(235.0, perHand = false)
        assertEquals(HeaderTarget.Goal("235"), DayScreenBuilder.headerTarget(exercise, goal, WeightUnit.LB))
        assertEquals(HeaderTarget.Goal("106.59"), DayScreenBuilder.headerTarget(exercise, goal, WeightUnit.KG))
    }

    @Test
    fun `prescribed headers use authored sets and targets in either unit`() {
        val cases = listOf(
            Triple(PrescribedTarget.LoadReps(95.0, 10), "3 × 10 · 95 lb", "3 × 10 · 43.09 kg"),
            Triple(PrescribedTarget.Reps(12), "3 × 12", "3 × 12"),
            Triple(PrescribedTarget.Hold(45), "3 × 0:45", "3 × 0:45"),
            Triple(PrescribedTarget.Hold(45, 25.0), "3 × 0:45 · 25 lb", "3 × 0:45 · 11.34 kg"),
            Triple(PrescribedTarget.RepsInTime(120), "2:00", "2:00"),
            Triple(PrescribedTarget.RepsInTime(120, 40), "2:00", "2:00"),
        )
        for ((target, lb, kg) in cases) {
            val exercise = ProgramExercise(
                "bb_back_squat", structure = SetStructure.RAMP_TOP_BACKOFF, targetSets = 3,
                prescription = Prescription(target, eachSide = true, resistance = "Red band"),
            )
            for ((unit, display) in listOf(WeightUnit.LB to lb, WeightUnit.KG to kg)) {
                assertEquals(
                    HeaderTarget.Prescribed(display, (target as? PrescribedTarget.RepsInTime)?.repAim, true, "Red band"),
                    DayScreenBuilder.headerTarget(exercise, GoalTarget.Weight(235.0, perHand = false), unit),
                )
            }
        }
    }

    @Test
    fun `prescribed header follows the slot set count and keeps sub-minute clocks`() {
        val exercise = ProgramExercise("unknown", targetSets = 5, prescription = Prescription(PrescribedTarget.Hold(5)))
        val goal = GoalTarget.Weight(235.0, perHand = false)
        assertEquals(HeaderTarget.Prescribed("5 × 0:05"), DayScreenBuilder.headerTarget(exercise, goal, WeightUnit.LB))
        assertEquals(HeaderTarget.Prescribed("0:30"), DayScreenBuilder.headerTarget(
            exercise.copy(prescription = Prescription(PrescribedTarget.RepsInTime(30))), goal, WeightUnit.KG,
        ))
    }

    @Test
    fun focusIndexFindsFirstUndoneOrMinusOne() {
        val rows = List(3) { SetRowState(it, "${it + 1}", false, 95.0, 5, false) }
        assertEquals(0, DayScreenBuilder.focusIndex(rows))
        assertEquals(2, DayScreenBuilder.focusIndex(rows.mapIndexed { index, row -> row.copy(done = index < 2) }))
        assertEquals(-1, DayScreenBuilder.focusIndex(rows.map { it.copy(done = true) }))
        assertEquals(-1, DayScreenBuilder.focusIndex(emptyList()))
    }

    @Test
    fun rowLabelsUseKindAndRampIndexInsteadOfNotation() {
        val row = SetRowState(4, "not a kind", false, 95.0, 5, false, kind = SetKind.RAMP, rampIndex = 3)
        assertEquals(cloud.trotter.log.strength.ui.text.UiText.SetLabel(SetKind.RAMP, 3), setLabel(row))
        assertEquals(SetKind.BACKOFF, setLabel(row.copy(kind = SetKind.BACKOFF, rampIndex = 0)).kind)
        assertEquals(SetKind.EXTRA, setLabel(row.copy(kind = SetKind.EXTRA, rampIndex = 0)).kind)
    }

    @Test
    fun summaryLineIncludesEveryOverheadPressPrescriptionInOrder() {
        val rows = listOf(65.0 to 5, 85.0 to 5, 95.0 to 5, 110.0 to 3, 120.0 to 5, 90.0 to 8)
            .map { (weight, reps) -> work(weight, reps) }
        assertEquals("65×5, 85×5, 95×5, 110×3, 120×5, 90×8",
            DayScreenBuilder.summaryLine(rows, null, TrackingType.WEIGHTED, TrackingType.WEIGHTED, WeightUnit.LB))
    }

    @Test
    fun summaryLineFormatsSupersetTracksIndependentlyIncludingUndoneRows() {
        assertEquals("60×12(×6), 60×10(×5)", DayScreenBuilder.summaryLine(
            listOf(work(60.0, 12, true), work(60.0, 10)), listOf(work(0.0, 6), work(0.0, 5)),
            TrackingType.WEIGHTED, TrackingType.REPS, WeightUnit.LB))
        assertEquals("45s", DayScreenBuilder.summaryLine(listOf(work(0.0, 0, seconds = 45)), null,
            TrackingType.TIMED, TrackingType.WEIGHTED, WeightUnit.LB))
    }

    @Test
    fun currentPageHonorsSelectionThenUndoneThenLastLift() {
        val cards = workoutDayFixture().exercises
        val keys = listOf("exercise:1", "exercise:2", "exercise:3", "cardio")
        assertEquals(2, DayScreenBuilder.currentPage(keys, "exercise:3", cards))
        assertEquals(1, DayScreenBuilder.currentPage(keys, "exercise:99", cards))
        assertEquals(1, DayScreenBuilder.currentPage(keys, null, cards))
        assertEquals(3, DayScreenBuilder.currentPage(keys, "cardio", cards))
        assertEquals(2, DayScreenBuilder.currentPage(keys, null, cards.map { card -> card.copy(rows = card.rows.map { it.copy(done = true) }) }))
        assertEquals(0, DayScreenBuilder.currentPage(listOf("cardio"), null, emptyList()))
    }

    @Test
    fun currentPageFallsBackToPageZeroWhenEveryExerciseIsUnseeded() {
        val cards = workoutDayFixture().exercises.map { it.copy(rows = emptyList()) }
        val keys = listOf("exercise:1", "exercise:2", "exercise:3", "cardio")
        assertEquals(0, DayScreenBuilder.currentPage(keys, null, cards))
        assertEquals(0, DayScreenBuilder.currentPage(keys, "exercise:99", cards))
        assertEquals(3, DayScreenBuilder.currentPage(keys, "cardio", cards))
    }

    @Test
    fun watchStatusBuilderCoversActiveSyncingAndOfflineQueued() {
        assertEquals(WatchStatus(WatchStatusKind.ACTIVE), DayScreenBuilder.watchStatusLine(900, 0, 0, true, 1_000))
        assertEquals(WatchStatus(WatchStatusKind.SYNCING, 2), DayScreenBuilder.watchStatusLine(900, 2, 0, true, 1_000))
        assertEquals(WatchStatus(WatchStatusKind.OFFLINE_QUEUED, 3), DayScreenBuilder.watchStatusLine(900, 0, 3, false, 1_000))
    }

    @Test
    fun remoteTickMarksOnlyItsAffectedRow() {
        val card = ExerciseCardState(
            programExerciseId = 7, position = 0, title = "Squat",
            isSuperset = false, headerTarget = HeaderTarget.Goal("235"), perHand = false,
            allDone = false, rows = listOf(
                SetRowState(0, "R1", false, 135.0, 5, false),
                SetRowState(1, "TOP", true, 235.0, 5, true),
            ),
        )
        val marked = DayScreenBuilder.markRemoteTick(
            DayUiState(hasProgram = true, viewDayId = "A", exercises = listOf(card)),
            RemoteTick("A", 7, 1, eventId = 9),
        )
        assertEquals(listOf(false, true), marked.exercises.single().rows.map { it.justTickedRemotely })
    }

    private val cfg = LifterConfig() // bw 235, age 40, INTERMEDIATE, BALANCED
    private val catalog = ExerciseCatalog.CODE_ONLY

    private fun work(w: Double, r: Int, done: Boolean = false, seconds: Int = 0) =
        LoggedSet(w, r, SetKind.WORK, done, seconds)

    private fun top(w: Double, r: Int) = LoggedSet(w, r, SetKind.TOP)

    // --- cascade triggering (VM calls SetEditor on the TOP row) --------------

    @Test
    fun editing_the_top_row_cascades_to_the_pinned_numbers() {
        val goal = GoalCalculator.goalForMain(
            cloud.trotter.log.strength.domain.model.StandardLift.SQUAT,
            perHand = false,
            cfg = cfg,
        )
        val seeded = SetSeeder.seed(
            ProgramExercise("bb_back_squat", structure = SetStructure.RAMP_TOP_BACKOFF, targetSets = 6),
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
        val main = SetSeeder.seed(ProgramExercise("bb_back_squat", structure = SetStructure.RAMP_TOP_BACKOFF, targetSets = 6), 235.0, cfg)
        assertEquals(listOf("R1", "R2", "R3", "R4", "TOP", "B/O"), DayScreenBuilder.kindLabels(main))
    }

    @Test
    fun kindLabels_number_accessory_work_sets() {
        val work = listOf(work(60.0, 10), work(60.0, 10), work(60.0, 10))
        assertEquals(listOf("1", "2", "3"), DayScreenBuilder.kindLabels(work))
    }

    // --- collapse resolution -------------------------------------------------

    @Test
    fun supersetFocusAndCompletionRequireBothTracks() {
        val row = SetRowState(0, "1", false, 60.0, 12, false, PartnerRowState(50.0, 15))
        for ((mainDone, partnerDone) in listOf(false to false, true to false, false to true, true to true)) {
            val rows = listOf(row.copy(done = mainDone, partner = row.partner!!.copy(done = partnerDone)))
            assertEquals(if (mainDone && partnerDone) -1 else 0, DayScreenBuilder.focusIndex(rows))
            assertEquals(mainDone && partnerDone, rows.single().complete)
            assertEquals(mainDone && partnerDone, DayScreenBuilder.allDone(rows))
            assertEquals(if (mainDone && !partnerDone) Slot.SS else Slot.MAIN, rows.single().nextSlot)
        }
    }

    @Test
    fun plainExerciseCompletionStillFollowsItsOwnTick() {
        val row = SetRowState(0, "1", false, 100.0, 10, false)
        assertEquals(false, row.complete)
        assertTrue(row.copy(done = true).complete)
        assertEquals(false, DayScreenBuilder.allDone(emptyList()))
    }

    @Test
    fun completionCountsRequireAllAvailableSetsAndAllowExtraLoggedSets() {
        assertEquals(false, DayScreenBuilder.allDone(0, 0))
        assertEquals(false, DayScreenBuilder.allDone(2, 3))
        assertTrue(DayScreenBuilder.allDone(3, 3))
        assertTrue(DayScreenBuilder.allDone(4, 3))
    }

    // --- kind labels from raw kinds (Log screen reuse) -----------------------

    @Test
    fun kindLabelsForKinds_matches_kindLabels_over_the_same_sequence() {
        val main = SetSeeder.seed(ProgramExercise("bb_back_squat", structure = SetStructure.RAMP_TOP_BACKOFF, targetSets = 6), 235.0, cfg)
        assertEquals(DayScreenBuilder.kindLabels(main), DayScreenBuilder.kindLabelsForKinds(main.map { it.kind }))
    }

    @Test
    fun kindLabelsForKinds_restarts_the_ramp_counter_per_call() {
        // Each history exercise group is labeled independently — R1 always
        // starts over, it never keeps counting from a previous exercise's group.
        assertEquals(
            listOf("R1", "TOP"),
            DayScreenBuilder.kindLabelsForKinds(listOf(SetKind.RAMP, SetKind.TOP)),
        )
    }

    // --- "last time" chip (PLAN.md A1 bonus) ---------------------------------

    @Test
    fun lastTimeDisplay_formats_weight_and_reps_in_the_display_unit() {
        assertEquals("185 × 8", DayScreenBuilder.lastTimeDisplay(LastPerformed(185.0, 8), WeightUnit.LB))
        // 44.092452436 lb == exactly 20 kg — an even round trip so the assertion
        // isn't sensitive to WeightStepper's decimal formatting.
        assertEquals("20 × 8", DayScreenBuilder.lastTimeDisplay(LastPerformed(44.092452436, 8), WeightUnit.KG))
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
        // still 0, the hold sits in reps — the history chip must read the
        // value, not "0s".
        assertEquals(" × 45", DayScreenBuilder.lastTimeDisplay(LastPerformed(0.0, 45), WeightUnit.LB))
    }

    // --- "Best" profile chip (performance-profile.md) ------------------------

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
        assertEquals("45", DayScreenBuilder.plateLine(listOf(work(135.0, 5)), barbellEquipment, WeightUnit.LB,
            TrackingType.WEIGHTED, focusedIndex = 0))
    }

    @Test
    fun plateLine_is_null_for_a_non_barbell_exercise() {
        assertNull(DayScreenBuilder.plateLine(listOf(work(60.0, 8)), catalog.get("ez_curl").equipment, WeightUnit.LB,
            TrackingType.WEIGHTED, focusedIndex = 0))
    }

    @Test
    fun plateLine_follows_the_second_focused_ramp_even_when_the_first_is_undone() {
        val main = listOf(work(130.0, 5), work(165.0, 5), work(190.0, 3), work(210.0, 1))
        assertEquals("45 + 10 + 5", DayScreenBuilder.plateLine(main, barbellEquipment, WeightUnit.LB,
            TrackingType.WEIGHTED, focusedIndex = 1))
    }

    @Test
    fun plateLine_updates_when_the_focused_sets_weight_is_edited() {
        val main = listOf(work(235.0, 5))
        assertEquals("45 + 45 + 5", DayScreenBuilder.plateLine(main, barbellEquipment, WeightUnit.LB,
            TrackingType.WEIGHTED, focusedIndex = 0))
        assertEquals("45 + 45 + 10", DayScreenBuilder.plateLine(listOf(work(245.0, 5)), barbellEquipment, WeightUnit.LB,
            TrackingType.WEIGHTED, focusedIndex = 0))
    }

    @Test
    fun plateLine_reads_empty_bar_at_bar_weight() {
        assertEquals("0", DayScreenBuilder.plateLine(listOf(work(45.0, 5)), barbellEquipment, WeightUnit.LB,
            TrackingType.WEIGHTED, focusedIndex = 0))
    }

    @Test
    fun plateLine_can_show_a_completed_focused_set() {
        assertEquals("45", DayScreenBuilder.plateLine(listOf(work(135.0, 5, done = true)), barbellEquipment, WeightUnit.LB,
            TrackingType.WEIGHTED, focusedIndex = 0))
    }

    @Test
    fun plateLine_requires_a_positive_exactly_loadable_weight_and_a_real_focus() {
        for (weight in listOf(0.0, -45.0, 137.0)) {
            assertNull(DayScreenBuilder.plateLine(listOf(work(weight, 5)), barbellEquipment, WeightUnit.LB,
                TrackingType.WEIGHTED, focusedIndex = 0))
        }
        assertNull(DayScreenBuilder.plateLine(emptyList(), barbellEquipment, WeightUnit.LB,
            TrackingType.WEIGHTED, focusedIndex = -1))
    }

    @Test
    fun plateLine_rejects_holds_and_reps_even_with_barbell_equipment_and_weight() {
        for (tracking in listOf(TrackingType.TIMED, TrackingType.REPS)) {
            assertNull(DayScreenBuilder.plateLine(listOf(work(135.0, 5, seconds = 45)), barbellEquipment, WeightUnit.LB,
                tracking, focusedIndex = 0))
        }
    }

    @Test
    fun plateLine_uses_kg_denominations() {
        assertEquals("25 + 15 + 1.25", DayScreenBuilder.plateLine(listOf(work(WeightUnit.KG.toLb(102.5), 12)),
            barbellEquipment, WeightUnit.KG, TrackingType.WEIGHTED, focusedIndex = 0))
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
