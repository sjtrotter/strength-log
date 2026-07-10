package io.github.sjtrotter.strengthlog.transfer.health

/**
 * The write-side seam for session completion (brief D7). The day flow fires
 * [publish] after `advanceDay` returns; the default binding is [NoOp], so a
 * device with no Health Connect provider — or a user who never granted the
 * permission — simply gets a no-op and the feature degrades invisibly (PLAN.md
 * A3). [HealthConnectPublisher] is the real implementation.
 *
 * The interface lives in `:transfer` and takes only a session id (a `:data`
 * primary key) so no androidx.health type ever reaches the `:app` call site.
 */
interface SessionPublisher {

    /**
     * Publishes the just-completed session identified by [sessionId]. Always
     * safe to call and never throws: every failure path (unavailable, denied,
     * provider error) is swallowed by the implementation. Callers fire this
     * non-blocking and ignore the outcome.
     */
    suspend fun publish(sessionId: Long)

    /** The binding used when Health Connect is not wired in (tests, and the
     *  safety net if a device can't provide it at all). */
    object NoOp : SessionPublisher {
        override suspend fun publish(sessionId: Long) = Unit
    }
}
