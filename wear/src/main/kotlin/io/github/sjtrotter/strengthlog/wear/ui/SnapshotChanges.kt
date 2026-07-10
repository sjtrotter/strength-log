package io.github.sjtrotter.strengthlog.wear.ui

import io.github.sjtrotter.strengthlog.domain.sync.WatchSnapshot

/**
 * True when [current] is a newer snapshot than [previous] that also *changed
 * something visible* — the trigger for the transient "updated from phone" pill
 * (design digest §1.1/§1.3: a phone-added exercise, or a cascaded weight ramp).
 *
 * Compares [WatchSnapshot.day] rather than the whole snapshot so a bare
 * revision bump with no visible change (e.g. an idle republish) never flashes
 * the pill. This intentionally does *not* special-case the watch's own edits:
 * [io.github.sjtrotter.strengthlog.wear.data.WatchEditOptimism] already echoes
 * them into the snapshot this function compares *against* the moment they're
 * sent, so the phone's confirming snapshot — same content, no cascade — is
 * already reflected in [previous] and produces no diff here. Only a genuine
 * phone-side change (one [previous] hasn't already rendered) fires the pill.
 * The watch never recomputes cascades itself — it only ever detects that the
 * phone did.
 */
fun isUpdatedFromPhone(previous: WatchSnapshot?, current: WatchSnapshot): Boolean =
    previous != null && current.revision > previous.revision && current.day != previous.day
