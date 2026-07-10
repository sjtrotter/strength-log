package io.github.sjtrotter.strengthlog.wear.ui

/** What the queued/synced pill (design digest §3) should show right now. */
enum class SyncPillKind { NONE, QUEUED, SYNCED }

/**
 * Pure queued -> synced pill transition: a persistent "N queued" pill while
 * [currentCount] is above zero; the instant it drops to zero *after* having
 * been above zero, [SyncPillKind.SYNCED] — the caller renders that as a
 * transient confirmation and fades it after ~2s; a count that was already
 * zero and stays zero shows nothing.
 */
fun syncPillKind(previousCount: Int, currentCount: Int): SyncPillKind = when {
    currentCount > 0 -> SyncPillKind.QUEUED
    previousCount > 0 -> SyncPillKind.SYNCED
    else -> SyncPillKind.NONE
}
