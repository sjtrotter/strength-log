package cloud.trotter.log.strength.domain.sync

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
 *
 * Forward-compat: [schemaVersion] only earns its keep if the #20 transport
 * decoder is lenient — decode both DTOs with `Json { ignoreUnknownKeys = true }`
 * so a future schemaVersion can add fields without breaking an older reader.
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
    /** A TIMED hold edit in seconds; null when the touched field wasn't seconds.
     *  Additive/backward-compatible: an older phone ignores the unknown key, a
     *  pre-P5 watch simply never sets it. The phone applies it only on TIMED tracks
     *  (see SetEditApplier's delta guard). */
    val seconds: Int? = null,
    /** Last-write-wins tiebreaker and dedupe key on the phone side. */
    val editedAtMillis: Long,
    /** Wall-clock millis the lifter started this set (the dial's START), as *observed
     *  on the wrist* — a fact, not a calculation: the phone derives active time
     *  (`completedAtMillis - startedAtMillis`) and actual rest (the gap to the next
     *  set's `startedAtMillis`). Distinct from [editedAtMillis], which stays the
     *  LWW/dedupe stamp and says nothing about when the set was performed.
     *  Additive/backward-compatible: null means "the watch didn't observe it" — a
     *  pre-dial watch never sets it and an older phone ignores the unknown key. */
    val startedAtMillis: Long? = null,
    /** Wall-clock millis the tick landed, same fact-not-calculation rule and same
     *  null-means-old-behavior default as [startedAtMillis]. Rides with the `done`
     *  tick that produced it (see PendingEdits' settling rule). */
    val completedAtMillis: Long? = null,
)
