package io.github.sjtrotter.strengthlog.sync

/**
 * The per-slot last-write-wins dedupe marker (m5-wear.md #20). [SetEditApplier]
 * drops any delta whose `editedAtMillis` is not newer than what was last applied
 * to the same slot, which is exactly what makes the watch's fire-and-forget
 * re-sends idempotent: a replayed delta carries the same timestamp and is dropped.
 *
 * An interface so the applier's logic is unit-testable against an in-memory fake
 * without a DataStore; production is [WearSyncStore].
 */
interface AppliedEditMarkers {

    /** The newest `editedAtMillis` already applied to [slotKey], or 0 if none. */
    suspend fun lastApplied(slotKey: String): Long

    suspend fun markApplied(slotKey: String, editedAtMillis: Long)
}
