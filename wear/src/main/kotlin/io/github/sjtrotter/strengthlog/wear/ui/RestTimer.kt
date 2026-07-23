package io.github.sjtrotter.strengthlog.wear.ui

/**
 * Pure, JVM-testable arithmetic and decisions for the watch rest timer
 * (redesign §2.3 / R5) — the composables and [RestTimerController] do IO and
 * layout only, this file owns every rule. The countdown is **deadline-anchored**:
 * a deadline (an `elapsedRealtime()` instant) is captured once at the tick and
 * every value below is derived from it, so recomposition, ambient, rotation, or a
 * delayed wake can never make the countdown drift or the buzz double-fire.
 *
 * The rest duration itself is never computed here — it arrives on the wire in
 * [io.github.sjtrotter.strengthlog.domain.sync.WatchSet.restAfterSeconds], stamped
 * phone-side from `RestPolicy` (SSOT). This file only counts a given number down.
 */
object RestTimer {

    /** Extra time a partial wake lock is held past the deadline so a slightly
     *  late wake still catches the buzz; kept small (battery vs. punctuality). */
    const val WAKE_LOCK_SLACK_MILLIS = 10_000L

    /** Ceiling used only to bound the wake-lock hold ([wakeLockTimeoutMillis]) so a
     *  garbled deadline can never pin the CPU indefinitely. It does NOT clamp the
     *  countdown itself — the phone already clamps rest to 300s via
     *  `RestPolicy.MAX_REST_SECONDS` before stamping the wire, and the watch counts
     *  down whatever it receives. Kept as a local const — the watch imports no
     *  `RestPolicy`. */
    const val MAX_REST_SECONDS = 300

    /**
     * A rest countdown runs after a done-tick only when the stream advances to a
     * **next round within the same exercise** ([StreamAdvance.NextRound]) and the
     * just-completed set carries a rest. The back-to-list and day-done transitions
     * never rest (task scope: rest is between sets of an exercise, not the last
     * undone / day-done handoff).
     */
    fun shouldRest(advance: StreamAdvance, restAfterSeconds: Int): Boolean =
        advance is StreamAdvance.NextRound && restAfterSeconds > 0

    /**
     * A between-exercise rest — the day-list countdown pill (issue #81) — runs after
     * a done-tick finishes an exercise's last set with other exercises still to go
     * ([StreamAdvance.BackToList]) and that set carries a rest. [StreamAdvance.DayDone]
     * is the only transition that never rests, by design (issue #81 / design Decision 3);
     * the within-exercise next-round path is [shouldRest]'s job, not this one.
     */
    fun shouldRestAfterExercise(advance: StreamAdvance, restAfterSeconds: Int): Boolean =
        advance is StreamAdvance.BackToList && restAfterSeconds > 0

    /** The deadline to capture at the tick: [nowElapsedMillis] is `elapsedRealtime()`. */
    fun deadlineFrom(nowElapsedMillis: Long, restAfterSeconds: Int): Long =
        nowElapsedMillis + restAfterSeconds.coerceAtLeast(0) * 1000L

    /** Milliseconds left until the deadline, never negative. */
    fun remainingMillis(deadlineMillis: Long, nowElapsedMillis: Long): Long =
        (deadlineMillis - nowElapsedMillis).coerceAtLeast(0L)

    /**
     * Whole seconds left, rounded **up**, so the numeral reads "1" through the
     * final second and only hits 0 at the buzz — the same way a workout clock
     * counts down.
     */
    fun remainingSeconds(deadlineMillis: Long, nowElapsedMillis: Long): Int {
        val ms = remainingMillis(deadlineMillis, nowElapsedMillis)
        return ((ms + 999L) / 1000L).toInt()
    }

    /** Fraction of the rest still to run: 1f at the start, draining to 0f at the
     *  deadline — the draining accent arc's sweep fraction. */
    fun remainingFraction(deadlineMillis: Long, nowElapsedMillis: Long, restAfterSeconds: Int): Float {
        if (restAfterSeconds <= 0) return 0f
        val totalMillis = restAfterSeconds * 1000f
        return (remainingMillis(deadlineMillis, nowElapsedMillis).toFloat() / totalMillis).coerceIn(0f, 1f)
    }

    /** True once the deadline has arrived. */
    fun isExpired(deadlineMillis: Long, nowElapsedMillis: Long): Boolean =
        nowElapsedMillis >= deadlineMillis

    /**
     * Fire-once guard: the single action at zero (the buzz, or the UI's advance)
     * happens only when the deadline has passed **and** it hasn't already fired
     * for this countdown. Guarding on [alreadyFired] is what makes a recomposition
     * or a re-run effect unable to double-fire.
     */
    fun shouldFire(deadlineMillis: Long, nowElapsedMillis: Long, alreadyFired: Boolean): Boolean =
        !alreadyFired && isExpired(deadlineMillis, nowElapsedMillis)

    /**
     * Bounded partial-wake-lock hold: [remainingMillis] left plus a small slack,
     * hard-capped at [MAX_REST_SECONDS] + slack (~5.2 min) so even a bogus deadline
     * can never hold the CPU indefinitely. Deriving the bound from *remaining*
     * (not the original duration) keeps it correct when the timer is re-armed after
     * process death partway through a rest.
     */
    fun wakeLockTimeoutMillis(remainingMillis: Long): Long =
        remainingMillis.coerceIn(0L, MAX_REST_SECONDS * 1000L) + WAKE_LOCK_SLACK_MILLIS
}
