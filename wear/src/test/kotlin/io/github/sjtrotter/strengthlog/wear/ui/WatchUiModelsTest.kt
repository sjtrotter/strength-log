package io.github.sjtrotter.strengthlog.wear.ui

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
        day = WatchDay("A", "Day A — Squat Focus", accentIndex = 0, exercises = listOf(squat, press)),
        unit = "lb",
    )

    @Test
    fun `day list rows report per-exercise done count out of total`() {
        val state = snapshot.toDayListUiState()
        assertEquals("Day A — Squat Focus", state.dayTitle)
        assertEquals(2, state.rows[0].doneCount)
        assertEquals(6, state.rows[0].totalCount)
        assertFalse(state.rows[0].allDone)
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
    fun `set-row kind labels follow the ramp-numbering, TOP, B-O convention`() {
        val detail = squat.toDetailUiState(WeightUnit.LB, accentIndex = 0)
        assertEquals(listOf("R1", "R2", "R3", "R4", "TOP", "B/O"), detail.rows.map { it.kindLabel })
    }

    @Test
    fun `GOAL and set weights convert from canonical lb to the display unit`() {
        val detail = squat.toDetailUiState(WeightUnit.KG, accentIndex = 0)
        assertEquals(WeightUnit.KG.fromLb(235.0), detail.rows.single { it.kindLabel == "TOP" }.weightDisplay)
        assertEquals("106.59", detail.goalDisplay)
    }

    @Test
    fun `a plain exercise has no partner rows`() {
        val detail = squat.toDetailUiState(WeightUnit.LB, accentIndex = 0)
        assertTrue(detail.rows.all { it.partner == null })
        assertNull(detail.partnerName)
    }

    @Test
    fun `a superset exercise pairs partner rows by index`() {
        val detail = press.toDetailUiState(WeightUnit.LB, accentIndex = 0)
        assertEquals("Rope Pushdown", detail.partnerName)
        assertEquals(50.0, detail.rows.single().partner?.weightDisplay)
        assertEquals(12, detail.rows.single().partner?.reps)
    }

    @Test
    fun `watchUnit parses the wire string case-insensitively and defaults to LB`() {
        assertEquals(WeightUnit.KG, watchUnit("kg"))
        assertEquals(WeightUnit.LB, watchUnit("LB"))
        assertEquals(WeightUnit.LB, watchUnit("garbage"))
    }
}
