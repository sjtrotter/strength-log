package io.github.sjtrotter.strengthlog.domain.sync

import kotlinx.serialization.Serializable

/**
 * A watch->phone edit, sent over the MessageClient path (D6, m5-wear.md wire
 * protocol). Null fields mean "unchanged" — the watch sends only what the
 * user actually touched, so a reps-only edit doesn't clobber weight.
 *
 * The watch never computes derived sets (cascade/seeding stay phone-side): it
 * sends this delta, the phone applies it through the same paired-mutation
 * repository path the day screen uses, and the resulting higher-[WatchSnapshot.revision]
 * snapshot is the watch's ack — it reconciles optimistic local state against
 * that, never against this delta echoing back.
 */
@Serializable
data class SetEditDelta(
    val schemaVersion: Int = 1,
    val dayId: String,
    val programExerciseId: Long,
    val slot: String,
    val setIndex: Int,
    val weightLb: Double? = null,
    val reps: Int? = null,
    val done: Boolean? = null,
    /** Last-write-wins tiebreaker and dedupe key on the phone side. */
    val editedAtMillis: Long,
)
