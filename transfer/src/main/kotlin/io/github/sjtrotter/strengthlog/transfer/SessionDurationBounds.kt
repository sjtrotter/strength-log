package io.github.sjtrotter.strengthlog.transfer

/**
 * The window a single training session's wall-clock duration must fall in to be
 * treated as real, shared by every export that reads `startedAt..completedAt`
 * (SSOT — the calorie estimate and the CSV Duration column must agree on what a
 * sane session looks like).
 *
 * The ceiling guards the stale-stamp case: a session-start stamp that survived a
 * calendar-day boundary or a crash produces a `completedAt - startedAt` far
 * larger than any real workout (e.g. "ticked yesterday, finished today"), and
 * neither a calorie record nor a CSV duration should be emitted from it. The
 * floor is the calorie estimate's own noise guard (a sub-5-minute "session" is
 * dedupe/roundoff, not training); the CSV column doesn't apply it, because a
 * genuinely short logged session is still honest wall-clock time to report.
 */
object SessionDurationBounds {

    /** Below this, a session is too short to be real (calorie estimate only). */
    const val MIN_MILLIS: Long = 5 * 60_000L

    /** Above this, the span is a stale/corrupt stamp, not one continuous session. */
    const val MAX_MILLIS: Long = 6 * 60 * 60_000L
}
