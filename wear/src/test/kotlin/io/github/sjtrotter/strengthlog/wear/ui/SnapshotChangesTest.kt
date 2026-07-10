package io.github.sjtrotter.strengthlog.wear.ui

import io.github.sjtrotter.strengthlog.domain.sync.WatchDay
import io.github.sjtrotter.strengthlog.domain.sync.WatchExercise
import io.github.sjtrotter.strengthlog.domain.sync.WatchSet
import io.github.sjtrotter.strengthlog.domain.sync.WatchSnapshot
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The "updated from phone" pill trigger (design digest §1.1/§1.3), pulled out pure. */
class SnapshotChangesTest {

    private val squat = WatchExercise(
        programExerciseId = 1L,
        slot = "main",
        name = "Barbell Back Squat",
        goal = 235.0,
        perHand = false,
        supersetPartnerName = null,
        sets = listOf(WatchSet(130.0, 5, "RAMP", done = false), WatchSet(235.0, 5, "TOP", done = false)),
        ssSets = emptyList(),
    )

    private fun snapshot(revision: Long, exercises: List<WatchExercise>) = WatchSnapshot(
        revision = revision,
        suggestedDayId = "A",
        day = WatchDay("A", "Day", accentIndex = 0, exercises = exercises),
        unit = "lb",
    )

    @Test
    fun `never fires on the very first snapshot`() {
        assertFalse(isUpdatedFromPhone(previous = null, current = snapshot(1L, listOf(squat))))
    }

    @Test
    fun `never fires when nothing in the day actually changed`() {
        val a = snapshot(1L, listOf(squat))
        val b = snapshot(2L, listOf(squat)) // bumped revision, identical content — an idle republish
        assertFalse(isUpdatedFromPhone(a, b))
    }

    @Test
    fun `fires when a phone-added exercise appears`() {
        val before = snapshot(1L, listOf(squat))
        val calf = squat.copy(programExerciseId = 2L, name = "Standing Calf Raise")
        val after = snapshot(2L, listOf(squat, calf))
        assertTrue(isUpdatedFromPhone(before, after))
    }

    @Test
    fun `fires when a cascade changes an already-rendered weight`() {
        val before = snapshot(1L, listOf(squat))
        val cascaded = squat.copy(sets = squat.sets.map { it.copy(weightLb = it.weightLb + 10) })
        val after = snapshot(2L, listOf(cascaded))
        assertTrue(isUpdatedFromPhone(before, after))
    }

    @Test
    fun `never fires without a revision increase, even if content somehow differs`() {
        val a = snapshot(3L, listOf(squat))
        val differentButSameRevision = snapshot(3L, listOf(squat.copy(name = "Renamed")))
        assertFalse(isUpdatedFromPhone(a, differentButSameRevision))
    }
}
