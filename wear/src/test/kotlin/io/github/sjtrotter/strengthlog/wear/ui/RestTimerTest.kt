package io.github.sjtrotter.strengthlog.wear.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Pure rest-timer arithmetic and decisions (redesign §2.3 / R5), pulled out of
 *  the composables so the deadline math, the "should a rest show" rule, the
 *  fire-once guard, and the wake-lock bound are JVM-verifiable. */
class RestTimerTest {

    // --- shouldRest: rest only before a next round within the same exercise ---

    @Test
    fun `rest shows when advancing to a next round with a positive rest`() {
        assertTrue(RestTimer.shouldRest(StreamAdvance.NextRound(2), restAfterSeconds = 90))
    }

    @Test
    fun `no rest when the set carries a zero rest`() {
        assertFalse(RestTimer.shouldRest(StreamAdvance.NextRound(2), restAfterSeconds = 0))
    }

    @Test
    fun `no rest on the back-to-list transition`() {
        assertFalse(RestTimer.shouldRest(StreamAdvance.BackToList, restAfterSeconds = 90))
    }

    @Test
    fun `no rest on the day-done transition`() {
        assertFalse(RestTimer.shouldRest(StreamAdvance.DayDone, restAfterSeconds = 180))
    }

    // --- shouldRestAfterExercise: the between-exercise (day-list pill) rest, issue #81 ---

    @Test
    fun `between-exercise rest shows on back-to-list with a positive rest`() {
        assertTrue(RestTimer.shouldRestAfterExercise(StreamAdvance.BackToList, restAfterSeconds = 90))
    }

    @Test
    fun `no between-exercise rest when the last set carries a zero rest`() {
        assertFalse(RestTimer.shouldRestAfterExercise(StreamAdvance.BackToList, restAfterSeconds = 0))
    }

    @Test
    fun `day-done is the only no-rest transition — never a between-exercise rest`() {
        assertFalse(RestTimer.shouldRestAfterExercise(StreamAdvance.DayDone, restAfterSeconds = 90))
    }

    @Test
    fun `a next-round transition is not the between-exercise path — that's shouldRest's job`() {
        assertFalse(RestTimer.shouldRestAfterExercise(StreamAdvance.NextRound(2), restAfterSeconds = 90))
    }

    // --- deadline anchoring ---

    @Test
    fun `deadline is now plus the rest in millis`() {
        assertEquals(100_000L + 90_000L, RestTimer.deadlineFrom(nowElapsedMillis = 100_000L, restAfterSeconds = 90))
    }

    @Test
    fun `deadline never runs backwards for a negative rest`() {
        assertEquals(100_000L, RestTimer.deadlineFrom(nowElapsedMillis = 100_000L, restAfterSeconds = -5))
    }

    // --- remaining time ---

    @Test
    fun `remaining millis clamps at zero once past the deadline`() {
        assertEquals(5_000L, RestTimer.remainingMillis(deadlineMillis = 10_000L, nowElapsedMillis = 5_000L))
        assertEquals(0L, RestTimer.remainingMillis(deadlineMillis = 10_000L, nowElapsedMillis = 12_000L))
    }

    @Test
    fun `remaining seconds rounds up so the numeral holds until the true zero`() {
        // 1.5s left reads as 2; a full second reads as 1; the buzz-instant reads 0.
        assertEquals(2, RestTimer.remainingSeconds(deadlineMillis = 1_500L, nowElapsedMillis = 0L))
        assertEquals(1, RestTimer.remainingSeconds(deadlineMillis = 1_000L, nowElapsedMillis = 0L))
        assertEquals(1, RestTimer.remainingSeconds(deadlineMillis = 1L, nowElapsedMillis = 0L))
        assertEquals(0, RestTimer.remainingSeconds(deadlineMillis = 0L, nowElapsedMillis = 0L))
    }

    @Test
    fun `remaining fraction drains from one to zero`() {
        // 90s rest, deadline at 90_000 from a zero start.
        assertEquals(1f, RestTimer.remainingFraction(90_000L, 0L, 90))
        assertEquals(0.5f, RestTimer.remainingFraction(90_000L, 45_000L, 90))
        assertEquals(0f, RestTimer.remainingFraction(90_000L, 90_000L, 90))
        assertEquals(0f, RestTimer.remainingFraction(90_000L, 120_000L, 90))
    }

    @Test
    fun `remaining fraction is zero when there is no rest total`() {
        assertEquals(0f, RestTimer.remainingFraction(0L, 0L, restAfterSeconds = 0))
    }

    // --- fire-once guard ---

    @Test
    fun `fires once at the deadline and never again`() {
        assertTrue(RestTimer.shouldFire(deadlineMillis = 100L, nowElapsedMillis = 100L, alreadyFired = false))
        assertTrue(RestTimer.shouldFire(deadlineMillis = 100L, nowElapsedMillis = 250L, alreadyFired = false))
        // Once fired, a recomposition / re-run effect cannot fire again.
        assertFalse(RestTimer.shouldFire(deadlineMillis = 100L, nowElapsedMillis = 250L, alreadyFired = true))
    }

    @Test
    fun `does not fire before the deadline`() {
        assertFalse(RestTimer.shouldFire(deadlineMillis = 100L, nowElapsedMillis = 99L, alreadyFired = false))
    }

    @Test
    fun `isExpired flips exactly at the deadline`() {
        assertFalse(RestTimer.isExpired(deadlineMillis = 100L, nowElapsedMillis = 99L))
        assertTrue(RestTimer.isExpired(deadlineMillis = 100L, nowElapsedMillis = 100L))
    }

    // --- wake-lock bound ---

    @Test
    fun `wake lock hold is the remaining time plus slack`() {
        assertEquals(
            60_000L + RestTimer.WAKE_LOCK_SLACK_MILLIS,
            RestTimer.wakeLockTimeoutMillis(remainingMillis = 60_000L),
        )
    }

    @Test
    fun `wake lock hold is hard-capped so a bogus deadline cannot pin the CPU`() {
        val cap = RestTimer.MAX_REST_SECONDS * 1000L + RestTimer.WAKE_LOCK_SLACK_MILLIS
        assertEquals(cap, RestTimer.wakeLockTimeoutMillis(remainingMillis = 10_000_000L))
    }

    @Test
    fun `wake lock hold never goes negative`() {
        assertEquals(RestTimer.WAKE_LOCK_SLACK_MILLIS, RestTimer.wakeLockTimeoutMillis(remainingMillis = -5_000L))
    }
}
