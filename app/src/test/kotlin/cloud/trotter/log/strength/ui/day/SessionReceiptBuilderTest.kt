package cloud.trotter.log.strength.ui.day

import cloud.trotter.log.strength.data.db.entity.SessionSetEntity
import cloud.trotter.log.strength.data.db.entity.Slot
import cloud.trotter.log.strength.domain.model.SetKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Pins the receipt's saved facts and round counting. */
class SessionReceiptBuilderTest {

    @Test
    fun readsBackTheCompletedDayAndWhereTheRotationNowStands() {
        val receipt = build(sets = listOf(set(weightLb = 225.0, reps = 5, done = true)))

        assertEquals("A", receipt.dayId)
        assertEquals(52, receipt.minutes)
        assertEquals(3_120_000L, receipt.completedAt)
        assertEquals(1, receipt.setCount)
        assertEquals("Day B, Upper", receipt.nextDayLine)
        assertEquals(0, receipt.dayIndex)
        assertEquals(7L, receipt.sessionId)
    }

    @Test
    fun partialDayKeepsCompletedAndTotalCounts() {
        val receipt = build(sets = listOf(set(done = true)), completedSetCount = 7, totalSetCount = 18)

        assertEquals(7, receipt.completedSetCount)
        assertEquals(18, receipt.totalSetCount)
    }

    /**
     * The rotation always wraps, so a null next day is not an end-of-program
     * state — it is the guard for the completed day having left the program
     * between the DONE tap and the advance (a wizard re-run). The receipt then
     * names no next day instead of the caller throwing on a workout that is
     * already committed.
     */
    @Test
    fun namesNoNextDayWhenTheCompletedDayIsNoLongerInTheProgram() {
        val receipt = build(sets = listOf(set(done = true)), nextDayId = null)

        assertNull(receipt.nextDayLine)
    }

    @Test
    fun countsOnlyTheSetsActuallyTicked() {
        val receipt = build(
            sets = listOf(
                set(weightLb = 135.0, reps = 5, done = true),
                set(weightLb = 185.0, reps = 5, done = true),
                set(weightLb = 225.0, reps = 5, done = false),
            ),
        )

        assertEquals(2, receipt.setCount)
    }

    /**
     * A superset day's receipt has to agree with the header that was on screen
     * a second earlier. Three ticked rounds of a superset write six done rows —
     * a main and a partner each — and the receipt counts rounds, so it says
     * three, exactly like the day header, Today, the widget and the watch.
     */
    @Test
    fun countsRoundsNotRowsSoASupersetDayMatchesEveryOtherSurface() {
        val rounds = 3
        val sets = (0 until rounds).flatMap { index ->
            listOf(
                set(name = "Barbell Curl", weightLb = 60.0, reps = 10, done = true, slot = Slot.MAIN, index = index),
                set(name = "Rope Pushdown", weightLb = 50.0, reps = 12, done = true, slot = Slot.SS, index = index),
            )
        }
        val receipt = build(sets = sets)

        assertEquals(6, sets.count { it.done })
        assertEquals(rounds, receipt.setCount)
    }

    @Test
    fun unknownStartOmitsMinutes() {
        assertNull(build(sets = listOf(set(done = true)), startedAt = null).minutes)
    }

    @Test
    fun twentySecondsStillReportsOneMinute() {
        assertEquals(1, build(sets = listOf(set(done = true)), completedAt = 20_000L).minutes)
    }

    @Test
    fun roundsElapsedMinutesToTheNearestMinute() {
        assertEquals(53, build(sets = listOf(set(done = true)), completedAt = 3_150_000L).minutes)
    }

    @Test
    fun preservesTheFrozenTopSetsAndTheAuthoredNextTitle() {
        val topSets = listOf(CascadeLift("Barbell Back Squat", "235", "245"))
        val receipt = build(sets = listOf(set(done = true)), newTopSets = topSets, nextDayTitle = "Upper body")
        assertEquals(topSets, receipt.newTopSets)
        assertEquals("Day B, Upper body", receipt.nextDayLine)
    }

    private fun build(
        sets: List<SessionSetEntity>,
        nextDayId: String? = "B",
        startedAt: Long? = 0L,
        completedAt: Long = 3_120_000L,
        newTopSets: List<CascadeLift> = emptyList(),
        nextDayTitle: String = "Upper",
        completedSetCount: Int = sets.count { it.done && Slot.isRound(it.slot) },
        totalSetCount: Int = sets.count { Slot.isRound(it.slot) },
    ): SessionReceipt = SessionReceiptBuilder.from(
        sessionId = 7L,
        dayId = "A",
        dayIndex = 0,
        startedAt = startedAt,
        completedAt = completedAt,
        completedSetCount = completedSetCount,
        totalSetCount = totalSetCount,
        sessionSets = sets,
        nextDayId = nextDayId,
        nextDayTitle = nextDayTitle,
        newTopSets = newTopSets,
    )

    private fun set(
        name: String = "Barbell Back Squat",
        weightLb: Double = 135.0,
        reps: Int = 5,
        seconds: Int = 0,
        done: Boolean,
        slot: String = Slot.MAIN,
        index: Int = 0,
    ) = SessionSetEntity(
        id = 0,
        sessionId = 7L,
        exerciseId = name.lowercase().replace(' ', '_'),
        exerciseName = name,
        slot = slot,
        setIndex = index,
        kind = SetKind.WORK.name,
        weightLb = weightLb,
        reps = reps,
        done = done,
        seconds = seconds,
    )
}
