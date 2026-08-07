package cloud.trotter.log.strength.domain.sync

import kotlinx.serialization.Serializable

/**
 * A watch->phone request to use one of the phone's own prescribed alternates for a
 * slot today (brief §6 "Swap", issue #90). It rides the same MessageClient transport
 * as [SetEditDelta] but on its own path ([WearSyncPaths.EXERCISE_SWAP]), because a
 * swap is not a set edit:
 *
 *  - it addresses a *slot*, not a row — there is no `setIndex`, no `slot` track, and
 *    none of [SetEditDelta]'s three guards (index-in-range, per-row dedupe,
 *    [guardedFor]'s tracking strip) means anything for it;
 *  - the phone applies it through `TrackerRepository.swapExercise` (spec §8.3 — the
 *    slot keeps its `programExerciseId`, its live log is cleared, the new exercise
 *    reseeds from its own GOAL), not through `updateSets`.
 *
 * Folding it into [SetEditDelta] would mean a sentinel index and a branch that skips
 * every guard the type exists to enforce. A separate path also degrades better: an
 * older phone's listener filters the path it doesn't know and drops the message
 * silently, instead of logging a decode failure for every re-send.
 *
 * [exerciseId] is the catalog id the phone acts on. [exerciseName] is what the watch
 * asked for in the lifter's words — it is what the outbound queue settles against
 * (the next snapshot's [WatchExercise.name]), and the queue must survive process
 * death with the snapshot that carried the alternates long gone, so the name travels
 * with the request rather than being looked up again.
 *
 * The phone re-derives the slot's alternates and refuses an [exerciseId] that isn't
 * among them: "the watch never invents alternates" is enforced, not trusted.
 */
@Serializable
data class ExerciseSwapDelta(
    val schemaVersion: Int = 1,
    val dayId: String,
    val programExerciseId: Long,
    val exerciseId: String,
    val exerciseName: String,
    /** Last-write-wins tiebreaker and dedupe key on the phone side, same rule and
     *  same monotonic issuer as [SetEditDelta.editedAtMillis]. */
    val editedAtMillis: Long,
)
