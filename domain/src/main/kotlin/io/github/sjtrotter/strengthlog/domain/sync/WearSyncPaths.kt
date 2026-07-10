package io.github.sjtrotter.strengthlog.domain.sync

/**
 * The Wearable Data Layer paths both sides address (m5-wear.md wire protocol).
 * Pure strings in `:domain` so the phone publisher/listener and the watch client
 * can't drift apart — a typo'd path silently drops every message, and this is the
 * one place the literals live. The manifest listener filter is scoped to
 * [PREFIX], so only these paths can wake the phone service.
 */
object WearSyncPaths {

    const val PREFIX = "/strengthlog"

    /** DataClient: the single always-current snapshot item (phone -> watch). */
    const val SNAPSHOT = "$PREFIX/snapshot"

    /** MessageClient: one set-edit delta (watch -> phone). */
    const val SET_EDIT = "$PREFIX/set-edit"
}
