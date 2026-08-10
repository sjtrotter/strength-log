package cloud.trotter.log.strength.transfer.health

/**
 * The write-side seam for session completion (brief D7). The day flow fires
 * [publish] after `advanceDay` returns; the default binding is [NoOp], so a
 * device with no Health Connect provider — or a user who never granted the
 * permission — simply gets a no-op and the feature degrades invisibly (PLAN.md
 * A3). [HealthConnectPublisher] is the real implementation.
 *
 * The interface lives in `:transfer` and takes only session ids (`:data`
 * primary keys) so no androidx.health type ever reaches the `:app` call site.
 */
interface SessionPublisher {

    /**
     * Publishes the just-completed session identified by [sessionId]. Always
     * safe to call and never throws: every failure path (unavailable, denied,
     * provider error) is swallowed by the implementation. Callers fire this
     * non-blocking and ignore the outcome.
     */
    suspend fun publish(sessionId: Long)

    /** Publishes one committed cardio row; default keeps existing test fakes source-compatible. */
    suspend fun publishCardio(sessionId: Long) = Unit

    /**
     * Publishes [sessionIds] — the backfill a late grant needs (#159), since
     * [publish] only ever fires at completion time and history written before
     * the grant would otherwise never reach Health Connect.
     *
     * Unlike [publish] this one answers, because the caller has to decide
     * whether to mark the one-shot backfill done: `true` only when every id
     * either wrote or had nothing to write (missing session, nothing ticked
     * off), `false` if the provider is absent, the write permission is denied,
     * or any single insert failed. A false must not be reported to the user as
     * success — leave the offer standing and let them try again.
     *
     * Idempotent: each session maps to the same client record ids as its live
     * publish would ([SessionRecordMapper.clientRecordId] /
     * [CaloriesRecordMapper.clientRecordId]), so re-running a backfill updates
     * those records rather than duplicating them.
     */
    suspend fun publishAll(sessionIds: List<Long>): Boolean

    suspend fun publishCardio(cardioSessionId: Long) = Unit

    suspend fun publishAllCardio(cardioSessionIds: List<Long>): Boolean = true

    /** The binding used when Health Connect is not wired in (tests, and the
     *  safety net if a device can't provide it at all). */
    object NoOp : SessionPublisher {
        override suspend fun publish(sessionId: Long) = Unit

        /** Nothing was published, so the caller must not record a backfill as done. */
        override suspend fun publishAll(sessionIds: List<Long>) = false
        override suspend fun publishCardio(cardioSessionId: Long) = Unit
        override suspend fun publishAllCardio(cardioSessionIds: List<Long>) = false
    }
}
