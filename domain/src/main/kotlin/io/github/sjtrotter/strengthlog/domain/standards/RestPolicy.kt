package io.github.sjtrotter.strengthlog.domain.standards

import io.github.sjtrotter.strengthlog.domain.library.TrackingType
import io.github.sjtrotter.strengthlog.domain.model.SetKind

/**
 * The five rest buckets a set can fall into. LIGHT covers bodyweight/core/hold
 * work and user-appended EXTRA rows — the "catch your breath" bucket. The
 * category, not the raw [SetKind], is the override unit: [SetKind] alone can't
 * tell a weighted accessory from a bodyweight one, and the user's chosen buckets
 * lump REPS/TIMED/EXTRA together.
 */
enum class RestCategory { RAMP, TOP, BACKOFF, WORK, LIGHT }

/**
 * The single place effective rest-after-a-set is computed (SSOT). "Stepped"
 * rest — rest that varies with what the set *is* — is already encoded by
 * [SetKind] plus the tracking type, so rest is a pure `:domain` policy, not
 * per-row stored data. Phone, wire, and (later) watch all read numbers that
 * came from [effectiveRestSeconds]; nothing else computes rest.
 *
 * Pure Kotlin — no Android imports, like everything else in `standards/`.
 */
object RestPolicy {

    /** Stepper bound for the Setup editor; also the resolver's clamp. */
    const val MAX_REST_SECONDS = 300

    /** Signed-off defaults (user decision, 2026-07-20). Pinned by RestPolicyTest.
     *  Heavy neural work rests long, accessories short. */
    fun defaultSeconds(category: RestCategory): Int = when (category) {
        RestCategory.RAMP -> 90
        RestCategory.TOP -> 180
        RestCategory.BACKOFF -> 120
        RestCategory.WORK -> 90     // weighted accessory / superset work sets
        RestCategory.LIGHT -> 60    // REPS + TIMED work, and every EXTRA row
    }

    /**
     * [SetKind] + [TrackingType] fully determine the bucket: RAMP/TOP/BACKOFF are
     * seeded only on main lifts, WORK only on accessories and superset tracks,
     * EXTRA is user-appended anywhere. No `isMain` flag is needed — it is
     * derivable, so passing it would be a second source of truth.
     */
    fun categoryFor(kind: SetKind, tracking: TrackingType): RestCategory = when (kind) {
        SetKind.RAMP -> RestCategory.RAMP
        SetKind.TOP -> RestCategory.TOP
        SetKind.BACKOFF -> RestCategory.BACKOFF
        SetKind.EXTRA -> RestCategory.LIGHT
        SetKind.WORK -> when (tracking) {
            TrackingType.WEIGHTED -> RestCategory.WORK
            TrackingType.REPS, TrackingType.TIMED -> RestCategory.LIGHT
        }
    }

    /**
     * THE resolver — the only place effective rest is computed. An absent
     * [overrides] entry means "use the default", so shipping new defaults in an
     * update still reaches users who never edited that bucket. `0` (default or
     * override) means "no timer": the watch advances immediately, no countdown.
     * The result is clamped to `0..`[MAX_REST_SECONDS].
     *
     * The master on/off toggle is not the resolver's business — it gates
     * stamping in the snapshot builder; this stays a pure per-set function.
     */
    fun effectiveRestSeconds(
        kind: SetKind,
        tracking: TrackingType,
        overrides: Map<RestCategory, Int> = emptyMap(),
    ): Int {
        val category = categoryFor(kind, tracking)
        return (overrides[category] ?: defaultSeconds(category)).coerceIn(0, MAX_REST_SECONDS)
    }
}

/**
 * The user's rest-timer preferences as one domain-pure value: the master
 * [enabled] gate plus per-category [overrides] (absent key ⇒ the RestPolicy
 * default). Read from [io.github.sjtrotter.strengthlog.data.prefs.SettingsStore]
 * and fed to the snapshot builder; device-local (not part of the backup payload)
 * for now.
 */
data class RestSettings(
    val enabled: Boolean = true,
    val overrides: Map<RestCategory, Int> = emptyMap(),
)
