package cloud.trotter.log.strength.wear.ui

import cloud.trotter.log.strength.domain.sync.WatchSnapshot

/**
 * True when a workout is underway on the wrist: the suggested day has been
 * started (at least one round ticked done) but isn't finished (at least one
 * round still undone).
 *
 * This is the snapshot-derived half of the OngoingActivity re-entry chip's
 * lifecycle (redesign §1.4 / R6). Recomputed on every inbound snapshot and on
 * first composition after a process restart, it reconciles a stale chip left by
 * a killed process: a finished, not-yet-started, or empty day evaluates false.
 * [isSessionUnderway] adds the local pre-first-tick window without weakening
 * those snapshot guards.
 */
fun isSessionActive(snapshot: WatchSnapshot?): Boolean {
    val sets = snapshot?.day?.exercises?.flatMap { it.sets } ?: return false
    if (sets.isEmpty()) return false
    return sets.any { it.done } && sets.any { !it.done }
}

/** True while the snapshot is active or a locally started day still has work. */
fun isSessionUnderway(snapshot: WatchSnapshot?, localSessionStarted: Boolean): Boolean {
    val sets = snapshot?.day?.exercises?.flatMap { it.sets } ?: return false
    return isSessionActive(snapshot) || (localSessionStarted && sets.any { !it.done })
}
