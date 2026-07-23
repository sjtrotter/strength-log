package io.github.sjtrotter.strengthlog.wear.ui

import io.github.sjtrotter.strengthlog.domain.library.TrackingType
import io.github.sjtrotter.strengthlog.domain.standards.SetFormatter
import io.github.sjtrotter.strengthlog.domain.sync.WatchDay
import io.github.sjtrotter.strengthlog.domain.sync.WatchExercise
import io.github.sjtrotter.strengthlog.domain.sync.WatchSet
import io.github.sjtrotter.strengthlog.domain.sync.WatchSnapshot
import io.github.sjtrotter.strengthlog.domain.units.WeightUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WatchUiModelsTest {

    private val squat = WatchExercise(
        programExerciseId = 1L,
        slot = "main",
        name = "Barbell Back Squat",
        goal = 235.0,
        perHand = false,
        supersetPartnerName = null,
        sets = listOf(
            WatchSet(130.0, 5, "RAMP", done = true),
            WatchSet(165.0, 5, "RAMP", done = true),
            WatchSet(190.0, 5, "RAMP", done = false),
            WatchSet(210.0, 3, "RAMP", done = false),
            WatchSet(235.0, 5, "TOP", done = false),
            WatchSet(175.0, 8, "BACKOFF", done = false),
        ),
        ssSets = emptyList(),
    )

    private val press = WatchExercise(
        programExerciseId = 2L,
        slot = "main",
        name = "Incline DB Press",
        goal = 75.0,
        perHand = true,
        supersetPartnerName = "Rope Pushdown",
        sets = listOf(WatchSet(75.0, 8, "WORK", done = false)),
        ssSets = listOf(WatchSet(50.0, 12, "WORK", done = false)),
    )

    private val snapshot = WatchSnapshot(
        revision = 1L,
        suggestedDayId = "A",
        day = WatchDay("A", "Day A — Squat Focus", accentIndex = 0, exercises = listOf(squat, press), emphasisLine = "lower · squat focus"),
        unit = "lb",
    )

    @Test
    fun `day list rows report per-exercise done count out of total`() {
        val state = snapshot.toDayListUiState()
        assertEquals("A", state.dayId)
        assertEquals(2, state.rows[0].doneCount)
        assertEquals(6, state.rows[0].totalCount)
        assertFalse(state.rows[0].allDone)
    }

    @Test
    fun `subtitle carries the real emphasisLine, not hardcoded filler`() {
        assertEquals("lower · squat focus", snapshot.toDayListUiState().subtitle)
    }

    @Test
    fun `subtitle is omitted (null) when the snapshot's emphasisLine is blank`() {
        val noEmphasis = snapshot.copy(day = snapshot.day.copy(emphasisLine = ""))
        assertNull(noEmphasis.toDayListUiState().subtitle)
    }

    @Test
    fun `a fully-checked exercise reports allDone`() {
        val allDone = squat.copy(sets = squat.sets.map { it.copy(done = true) })
        val row = WatchSnapshot(
            revision = 1L,
            suggestedDayId = "A",
            day = WatchDay("A", "t", accentIndex = 0, exercises = listOf(allDone)),
            unit = "lb",
        ).toDayListUiState().rows.single()
        assertTrue(row.allDone)
    }

    @Test
    fun `a row's firstUndoneIndex is the first not-done round`() {
        val row = snapshot.toDayListUiState().rows.single { it.programExerciseId == 1L }
        assertEquals(2, row.firstUndoneIndex) // rounds 0,1 done; round 2 is the first undone
    }

    @Test
    fun `a fully-done row's firstUndoneIndex falls back to the last round`() {
        val allDone = squat.copy(sets = squat.sets.map { it.copy(done = true) })
        val row = WatchSnapshot(
            revision = 1L,
            suggestedDayId = "A",
            day = WatchDay("A", "t", accentIndex = 0, exercises = listOf(allDone)),
            unit = "lb",
        ).toDayListUiState().rows.single()
        assertEquals(5, row.firstUndoneIndex)
    }

    @Test
    fun `upNextIndex picks the first not-yet-complete row`() {
        val state = snapshot.toDayListUiState()
        assertEquals(0, upNextIndex(state.rows)) // squat (row 0) isn't done yet
    }

    @Test
    fun `upNextIndex is null once every row is done`() {
        val allDoneRows = snapshot.toDayListUiState().rows.map { it.copy(doneCount = it.totalCount) }
        assertNull(upNextIndex(allDoneRows))
    }

    @Test
    fun `set-round kind labels follow the ramp-numbering, TOP, B-O convention`() {
        val stream = squat.toStreamUiState(WeightUnit.LB, dayId = "A", accentIndex = 0)
        assertEquals(listOf("R1", "R2", "R3", "R4", "TOP", "B/O"), stream.rounds.map { it.kindLabel })
    }

    @Test
    fun `GOAL and round weights convert from canonical lb to the display unit`() {
        val stream = squat.toStreamUiState(WeightUnit.KG, dayId = "A", accentIndex = 0)
        assertEquals(WeightUnit.KG.fromLb(235.0), stream.rounds.single { it.kindLabel == "TOP" }.weightDisplay)
        assertEquals("106.59", stream.goalDisplay)
    }

    @Test
    fun `a plain exercise is not a superset and has no partner rounds`() {
        val stream = squat.toStreamUiState(WeightUnit.LB, dayId = "A", accentIndex = 0)
        assertFalse(stream.isSuperset)
        assertTrue(stream.rounds.all { it.partner == null })
        assertNull(stream.partnerName)
    }

    @Test
    fun `a superset exercise pairs partner rounds by index`() {
        val stream = press.toStreamUiState(WeightUnit.LB, dayId = "A", accentIndex = 0)
        assertTrue(stream.isSuperset)
        assertEquals("Rope Pushdown", stream.partnerName)
        assertEquals(50.0, stream.rounds.single().partner?.weightDisplay)
        assertEquals(12, stream.rounds.single().partner?.reps)
    }

    // --- read-only hero/secondary display (redesign §1.2) ------------------------

    @Test
    fun `a WEIGHTED round's hero is the weight and its secondary is the rep count`() {
        val stream = squat.toStreamUiState(WeightUnit.LB, dayId = "A", accentIndex = 0)
        val top = stream.rounds.single { it.kindLabel == "TOP" }
        assertEquals("235", top.heroDisplay)
        assertEquals("× 5", top.secondaryDisplay)
    }

    @Test
    fun `a REPS round's hero uses the multiplier glyph and has no secondary`() {
        val stream = pullup.toStreamUiState(WeightUnit.LB, dayId = "A", accentIndex = 0)
        assertEquals("×6", stream.rounds.first().heroDisplay)
        assertEquals("", stream.rounds.first().secondaryDisplay)
    }

    @Test
    fun `a TIMED round's hero is the formatted hold and has no secondary`() {
        val stream = plank.toStreamUiState(WeightUnit.LB, dayId = "A", accentIndex = 0)
        assertEquals("45s", stream.rounds.first().heroDisplay)
        assertEquals("", stream.rounds.first().secondaryDisplay)
    }

    @Test
    fun `a superset round's hero is the SetFormatter summary and the partner carries its own summary`() {
        val stream = press.toStreamUiState(WeightUnit.LB, dayId = "A", accentIndex = 0)
        val round = stream.rounds.single()
        assertEquals("75×8", round.heroDisplay)
        assertEquals("", round.secondaryDisplay)
        assertEquals("50×12", round.partner?.summaryDisplay)
    }

    private val benchDip = WatchExercise(
        programExerciseId = 6L,
        slot = "main",
        name = "Bench Press",
        goal = 195.0,
        perHand = false,
        supersetPartnerName = "Bench Dip",
        sets = listOf(WatchSet(195.0, 5, "WORK", done = false)),
        ssSets = listOf(WatchSet(0.0, 12, "WORK", done = false)),
        ssTracking = "reps",
    )

    @Test
    fun `a REPS-tracked partner renders its own summary, not the WEIGHTED main's`() {
        // #74: the partner used to format with the main's tracking, so a REPS
        // partner (e.g. Bench Dip under a WEIGHTED main) read like a weighted
        // set ("0×12") instead of its own "×12" shape.
        val stream = benchDip.toStreamUiState(WeightUnit.LB, dayId = "A", accentIndex = 0)
        val partner = stream.rounds.single().partner
        assertEquals("×12", partner?.summaryDisplay)
        assertEquals(SetFormatter.summary(TrackingType.REPS, 0.0, 12, 0, WeightUnit.LB), partner?.summaryDisplay)
    }

    @Test
    fun `day-done rounds-logged sums every exercise's round count`() {
        val doneState = snapshot.toDayDoneUiState()
        assertEquals(7, doneState.roundsLogged) // 6 squat rounds + 1 press round
        assertEquals("A", doneState.dayId)
    }

    @Test
    fun `watchUnit parses the wire string case-insensitively and defaults to LB`() {
        assertEquals(WeightUnit.KG, watchUnit("kg"))
        assertEquals(WeightUnit.LB, watchUnit("LB"))
        assertEquals(WeightUnit.LB, watchUnit("garbage"))
    }

    // --- per-type stream state (§3) ---------------------------------------------

    private val pullup = WatchExercise(
        programExerciseId = 3L,
        slot = "main",
        name = "Pull-Up",
        goal = 0.0,
        perHand = false,
        supersetPartnerName = null,
        sets = listOf(WatchSet(0.0, 6, "WORK", done = false), WatchSet(0.0, 5, "WORK", done = false)),
        ssSets = emptyList(),
        goalLabel = "6 reps",
        tracking = "reps",
    )

    private val plank = WatchExercise(
        programExerciseId = 4L,
        slot = "main",
        name = "Plank",
        goal = 0.0,
        perHand = false,
        supersetPartnerName = null,
        sets = listOf(WatchSet(0.0, 0, "WORK", done = false, seconds = 45)),
        ssSets = emptyList(),
        goalLabel = "45s",
        tracking = "timed",
    )

    private val weightedPlank = WatchExercise(
        programExerciseId = 5L,
        slot = "main",
        name = "Weighted Plank",
        goal = 25.0,
        perHand = false,
        supersetPartnerName = null,
        sets = listOf(WatchSet(25.0, 0, "WORK", done = false, seconds = 100)),
        ssSets = emptyList(),
        goalLabel = "45s +25",
        tracking = "timed",
    )

    @Test
    fun `watchTracking parses the wire string and defaults to WEIGHTED`() {
        assertEquals(TrackingType.REPS, watchTracking("reps"))
        assertEquals(TrackingType.TIMED, watchTracking("TIMED"))
        assertEquals(TrackingType.WEIGHTED, watchTracking("weighted"))
        assertEquals(TrackingType.WEIGHTED, watchTracking("garbage"))
    }

    @Test
    fun `a REPS exercise carries no weight control and uses the goalLabel`() {
        val stream = pullup.toStreamUiState(WeightUnit.LB, dayId = "A", accentIndex = 0)
        assertEquals(TrackingType.REPS, stream.tracking)
        assertFalse(stream.hasAddedLoad)
        assertEquals("", stream.addedLoadDisplay) // never "0 lb"
        assertEquals("6 reps", stream.goalDisplay) // the phone's per-type label, not a bare weight
        assertEquals(6, stream.rounds.first().reps)
    }

    @Test
    fun `a plain TIMED hold shows seconds via the shared formatter and no added load`() {
        val stream = plank.toStreamUiState(WeightUnit.LB, dayId = "A", accentIndex = 0)
        assertEquals(TrackingType.TIMED, stream.tracking)
        assertFalse(stream.hasAddedLoad)
        assertEquals("45s", stream.rounds.first().secondsDisplay) // under the 90s threshold
        assertEquals("45s", stream.goalDisplay)
    }

    @Test
    fun `a loaded TIMED hold shows a read-only added-load caption and m ss over 90s`() {
        val stream = weightedPlank.toStreamUiState(WeightUnit.LB, dayId = "A", accentIndex = 0)
        assertTrue(stream.hasAddedLoad)
        assertEquals("+25", stream.addedLoadDisplay)
        assertEquals("1:40", stream.rounds.first().secondsDisplay) // 100s -> m:ss
    }

    @Test
    fun `a loaded TIMED hold converts its added-load caption to the display unit`() {
        val stream = weightedPlank.toStreamUiState(WeightUnit.KG, dayId = "A", accentIndex = 0)
        assertEquals("+${io.github.sjtrotter.strengthlog.domain.units.WeightStepper.format(WeightUnit.KG.fromLb(25.0))}", stream.addedLoadDisplay)
    }

    @Test
    fun `a weighted exercise still falls back to the weight numeral when goalLabel is blank`() {
        // squat's fixture leaves goalLabel default "" (a pre-P1.5 wire) — the goal
        // display must degrade to the converted weight, not go blank.
        val stream = squat.toStreamUiState(WeightUnit.LB, dayId = "A", accentIndex = 0)
        assertEquals(TrackingType.WEIGHTED, stream.tracking)
        assertEquals("235", stream.goalDisplay)
    }
}
