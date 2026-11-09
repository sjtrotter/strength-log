package cloud.trotter.log.strength.domain.standards

import cloud.trotter.log.strength.domain.library.TrackingType
import cloud.trotter.log.strength.domain.model.SetKind
import cloud.trotter.log.strength.domain.progression.LiftGoal
import cloud.trotter.log.strength.domain.progression.SlotRole

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
 * per-row stored data. Phone, wire, and watch all read numbers that
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
     * seeded only by ramped structures, WORK by straight structures and superset tracks,
     * EXTRA is user-appended anywhere. The slot's structure is not needed here.
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

    /** The role's bucket for the user's bucket overrides. */
    fun categoryFor(role: SlotRole): RestCategory = when (role) {
        SlotRole.MAIN -> RestCategory.TOP
        SlotRole.SECONDARY -> RestCategory.BACKOFF
        SlotRole.ACCESSORY, SlotRole.PLYO -> RestCategory.WORK
        SlotRole.ISOLATION, SlotRole.CORE, SlotRole.BALANCE -> RestCategory.LIGHT
    }

    /** Design §3.1.1's rest by role and goal; null where the table is silent (the bucket default applies). */
    fun defaultSeconds(role: SlotRole, goal: LiftGoal): Int? = when (goal) {
        LiftGoal.STRENGTH, LiftGoal.MUSCLE -> when (role) {
            SlotRole.MAIN -> if (goal == LiftGoal.STRENGTH) 180 else 120
            SlotRole.SECONDARY -> 120
            SlotRole.ACCESSORY -> 90
            SlotRole.ISOLATION, SlotRole.CORE -> 60
            SlotRole.BALANCE, SlotRole.PLYO -> null
        }
        LiftGoal.GENERAL -> when (role) {
            SlotRole.MAIN, SlotRole.ACCESSORY -> 90
            SlotRole.ISOLATION -> 60
            else -> null
        }
    }

    /**
     * THE resolver — the only place effective rest is computed. An absent
     * [overrides] entry means "use the default", so shipping new defaults in an
     * update still reaches users who never edited that bucket. `0` (default or
     * override) means "no timer": the watch advances immediately, no countdown.
     * Slot override, bucket override, role/goal default, then bucket default apply in order; RAMP and BACKOFF use only their buckets.
     * The result is clamped to `0..`[MAX_REST_SECONDS].
     *
     * The master on/off toggle is not the resolver's business — it gates
     * stamping in the snapshot builder; this stays a pure per-set function.
     */
    fun effectiveRestSeconds(
        kind: SetKind,
        tracking: TrackingType,
        overrides: Map<RestCategory, Int> = emptyMap(),
        role: SlotRole? = null,
        goal: LiftGoal? = null,
        slotOverride: Int? = null,
    ): Int {
        val seconds = if (kind == SetKind.RAMP || kind == SetKind.BACKOFF) {
            val bucket = categoryFor(kind, tracking)
            overrides[bucket] ?: defaultSeconds(bucket)
        } else {
            val bucket = role?.let { categoryFor(it) } ?: categoryFor(kind, tracking)
            slotOverride ?: overrides[bucket] ?: (if (role != null && goal != null) defaultSeconds(role, goal) else null)
                ?: defaultSeconds(bucket)
        }
        return seconds.coerceIn(0, MAX_REST_SECONDS)
    }
}

/**
 * The user's rest-timer preferences as one domain-pure value: the master
 * [enabled] gate plus per-category [overrides] (absent key ⇒ the RestPolicy
 * default). Read from [cloud.trotter.log.strength.data.prefs.SettingsStore]
 * and fed to the snapshot builder, and carried by the full backup (schema v3)
 * with the absences intact.
 */
data class RestSettings(
    val enabled: Boolean = true,
    val overrides: Map<RestCategory, Int> = emptyMap(),
)
