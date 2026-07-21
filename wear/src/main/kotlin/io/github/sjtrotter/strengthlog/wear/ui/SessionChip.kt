package io.github.sjtrotter.strengthlog.wear.ui

import io.github.sjtrotter.strengthlog.domain.sync.WatchSnapshot

/**
 * True when a workout is underway on the wrist: the suggested day has been
 * started (at least one round ticked done) but isn't finished (at least one
 * round still undone).
 *
 * This is the whole lifecycle of the OngoingActivity re-entry chip (redesign
 * §1.4 / R6), expressed as a pure function of the snapshot so it reconciles for
 * free — recomputed on every inbound snapshot and on first composition after a
 * process restart, it says "post" the instant a day is in progress and "clear"
 * the instant it is finished, not-yet-started, or empty. No separate
 * "session started" stamp is needed: a stale chip left by a killed process is
 * cancelled simply because the reloaded snapshot evaluates to false.
 */
fun isSessionActive(snapshot: WatchSnapshot?): Boolean {
    val sets = snapshot?.day?.exercises?.flatMap { it.sets } ?: return false
    if (sets.isEmpty()) return false
    return sets.any { it.done } && sets.any { !it.done }
}
