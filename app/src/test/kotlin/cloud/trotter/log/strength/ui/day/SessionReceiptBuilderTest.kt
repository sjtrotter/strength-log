package cloud.trotter.log.strength.ui.day

import cloud.trotter.log.strength.data.db.entity.SessionSetEntity
import cloud.trotter.log.strength.data.db.entity.Slot
import cloud.trotter.log.strength.domain.model.SetKind
import cloud.trotter.log.strength.domain.units.WeightUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * What the session receipt (#126) contains. [DayViewModelReceiptTest] pins when
 * it exists; this pins what it says — and, mostly, what it refuses to say: an
 * unfinished set is not a set the lifter did, and a day with nothing loaded in
 * it has no strongest set to name.
 */
class SessionReceiptBuilderTest {

    @Test
    fun readsBackTheCompletedDayAndWhereTheRotationNowStands() {
        val receipt = build(sets = listOf(set(weightLb = 225.0, reps = 5, done = true)))

        assertEquals("DAY A COMPLETE", receipt.headline)
        assertEquals("Lower", receipt.dayTitle)
        assertEquals("DAY B · UPPER", receipt.nextDayLine)
        assertEquals(0, receipt.dayIndex)
        assertEquals(7L, receipt.sessionId)
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

    /** Counting narrows to rounds; "strongest" does not. A partner set is still
     *  weight the lifter moved, and hiding it would understate the session. */
    @Test
    fun stillRanksASupersetPartnersSetAsTheStrongest() {
        val receipt = build(
            sets = listOf(
                set(name = "Barbell Curl", weightLb = 60.0, reps = 10, done = true, slot = Slot.MAIN),
                set(name = "Weighted Dip", weightLb = 90.0, reps = 8, done = true, slot = Slot.SS),
            ),
        )

        assertEquals("Weighted Dip", receipt.strongest?.name)
        assertEquals(1, receipt.setCount)
    }

    @Test
    fun namesTheHeaviestCompletedSetOfTheWholeSession() {
        val receipt = build(
            sets = listOf(
                set(name = "Barbell Back Squat", weightLb = 235.0, reps = 3, done = true),
                set(name = "Romanian Deadlift", weightLb = 205.0, reps = 8, done = true),
            ),
        )

        assertEquals("Barbell Back Squat", receipt.strongest?.name)
        assertEquals("235×3", receipt.strongest?.value)
    }

    /** A set the lifter set up but never performed is not a set they did. */
    @Test
    fun ignoresHeavierSetsThatWereNeverTicked() {
        val receipt = build(
            sets = listOf(
                set(name = "Barbell Back Squat", weightLb = 235.0, reps = 3, done = false),
                set(name = "Barbell Back Squat", weightLb = 190.0, reps = 5, done = true),
            ),
        )

        assertEquals("190×5", receipt.strongest?.value)
    }

    @Test
    fun breaksATieOnTheSameWeightWithReps() {
        val receipt = build(
            sets = listOf(
                set(weightLb = 200.0, reps = 5, done = true),
                set(weightLb = 200.0, reps = 8, done = true),
            ),
        )

        assertEquals("200×8", receipt.strongest?.value)
    }

    /** Holds and bodyweight reps carry no load to rank, so a day made of them
     *  simply has no strongest-set line rather than a fabricated one. */
    @Test
    fun hasNoStrongestSetWhenNothingLoadedWasCompleted() {
        val receipt = build(
            sets = listOf(
                set(name = "Plank", weightLb = 0.0, reps = 0, seconds = 60, done = true),
                set(name = "Push-up", weightLb = 0.0, reps = 20, done = true),
            ),
        )

        assertNull(receipt.strongest)
        assertEquals(2, receipt.setCount)
    }

    @Test
    fun formatsTheStrongestSetInTheLiftersUnit() {
        val receipt = build(
            sets = listOf(set(weightLb = 220.5, reps = 5, done = true)),
            unit = WeightUnit.KG,
        )

        assertEquals("100.02×5", receipt.strongest?.value)
    }

    private fun build(
        sets: List<SessionSetEntity>,
        nextDayId: String? = "B",
        unit: WeightUnit = WeightUnit.LB,
    ): SessionReceipt = SessionReceiptBuilder.from(
        sessionId = 7L,
        dayId = "A",
        dayIndex = 0,
        dayTitle = "Lower",
        sessionSets = sets,
        nextDayId = nextDayId,
        nextDayTitle = "Upper",
        unit = unit,
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
