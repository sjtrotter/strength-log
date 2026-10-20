package cloud.trotter.log.strength.sync

/**
 * The per-row last-write-wins dedupe marker (m5-wear.md #20). [SetEditApplier]
 * drops any delta whose `editedAtMillis` is not newer than what was last applied
 * to the same row (day|exercise|track|setIndex), which is exactly what makes the
 * watch's fire-and-forget re-sends idempotent: a replayed delta carries the same
 * timestamp and is dropped. Per-row, not per-track, so a failed send of a row-0
 * edit can't be permanently starved by a later edit to row 1 of the same track.
 *
 * An interface so the applier's logic is unit-testable against an in-memory fake
 * without a DataStore; production is [WearSyncStore].
 */
interface AppliedEditMarkers {

    /** The newest `editedAtMillis` already applied to [rowKey], or 0 if none. */
    suspend fun lastApplied(rowKey: String): Long

    suspend fun markApplied(rowKey: String, editedAtMillis: Long)

    /** [lastApplied] as a cold flow, for values the snapshot must republish on
     *  (the cardio ack stamp — settlement rides the snapshot, so an advance
     *  that never republishes would strand the watch queue). */
    fun lastAppliedFlow(rowKey: String): kotlinx.coroutines.flow.Flow<Long>
}
