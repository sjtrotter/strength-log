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

/**
 * Whether the "updated from phone" pill should actually flash for [current].
 *
 * [isUpdatedFromPhone] alone over-fires: when the lifter edits on the watch, the
 * phone applies it and re-publishes with its cascade/seeding — a genuine
 * revision+content change that the optimistic echo didn't predict — so the pill
 * would announce the lifter's *own* action. We suppress that by ignoring any
 * inbound change that lands within [suppressionWindowMillis] of the last local
 * edit ([elapsedSinceLocalEditMillis]); after the window, an inbound change is
 * attributable to the phone (a program edit, an inserted exercise, a change the
 * user made on the phone) and fires normally.
 */
fun shouldFlashUpdatedFromPhone(
    previous: WatchSnapshot?,
    current: WatchSnapshot,
    elapsedSinceLocalEditMillis: Long,
    suppressionWindowMillis: Long,
): Boolean =
    isUpdatedFromPhone(previous, current) && elapsedSinceLocalEditMillis >= suppressionWindowMillis
