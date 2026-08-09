package cloud.trotter.log.strength.domain.sync

import kotlinx.serialization.Serializable

/**
 * The one item the phone publishes to the Wearable Data Layer (D6, m5-wear.md
 * wire protocol). Always the full current state of the *suggested* day only —
 * the watch is glanceable, not a program browser, so there is no per-day
 * history on the wrist.
 *
 * [revision] is a phone-side monotonic counter (persisted so app restarts
 * don't regress it); the watch uses it only to detect a newer snapshot, never
 * to order or merge — last-write-wins is resolved entirely on the phone.
 *
 * It is load-bearing in one direction: the watch installs a snapshot only when its
 * revision is *strictly greater* than the one it already holds, so the publisher must
 * spend a fresh revision on every publish carrying new content and must never reuse
 * one. An equal revision reads as a redelivery and is ignored — which is what keeps a
 * reconnect resync from wiping the watch's optimistic echo, since that echo
 * deliberately leaves the revision alone.
 */
@Serializable
data class WatchSnapshot(
    val schemaVersion: Int = 1,
    val revision: Long,
    val suggestedDayId: String,
    val day: WatchDay,
    /** "lb" or "kg" — matches [cloud.trotter.log.strength.domain.units.WeightUnit].name, lowercased. */
    val unit: String,
    /**
     * The program's days in order — what the watch's outer ring draws itself as
     * (dial v3 §1). Only what a read-only preview needs: no sets, no weights, no
     * done flags, because the watch logs against [day] and nothing else.
     *
     * Additive, appended last, defaulting to empty so an older publisher decodes
     * fine (mirrors [WatchDay.emphasisLine] / [WatchSet.restAfterSeconds]); an
     * empty list makes the ring one segment — today — which is what the whole
     * dial was before this field existed.
     */
    val cycle: List<WatchCycleDay> = emptyList(),
)

/** One day of the program as the cycle ring and its day-browse preview read it. */
@Serializable
data class WatchCycleDay(
    val dayId: String,
    val title: String,
    val exercises: List<WatchCycleExercise> = emptyList(),
)

/** One lift of a previewed day: the name a lifter says, and how many rounds of it. */
@Serializable
data class WatchCycleExercise(val name: String, val setCount: Int)

@Serializable
data class WatchDay(
    val dayId: String,
    val title: String,
    val accentIndex: Int,
    val exercises: List<WatchExercise>,
    /**
     * The day's muscle-angle emphasis ([cloud.trotter.log.strength.domain.model.ProgramDay.emphasisLine]),
     * shown as the today-list subtitle when non-blank. Defaults to "" so older
     * publishers (and every existing test fixture) decode fine without it —
     * the watch UI treats blank as "no subtitle", never invents one.
     */
    val emphasisLine: String = "",
)

/** One exercise slot's set track for the suggested day, ready to render. */
@Serializable
data class WatchExercise(
    val programExerciseId: Long,
    /** "main" or "ss" — see [cloud.trotter.log.strength.domain.model.SupersetPartner]'s track. */
    val slot: String,
    val name: String,
    val goal: Double,
    val perHand: Boolean,
    /** Null when this exercise has no superset partner. */
    val supersetPartnerName: String?,
    val sets: List<WatchSet>,
    /** The partner's rows, aligned by index with [sets]; empty when no partner. */
    val ssSets: List<WatchSet>,
    /**
     * The pre-formatted GOAL string ([cloud.trotter.log.strength.domain.standards.GoalFormatter]),
     * so a reclassified REPS/TIMED slot can read "6 reps" / "45s" without the
     * watch ever doing goal math. Additive groundwork appended last: defaults to
     * "" so older publishers and existing fixtures decode fine (mirrors
     * [WatchDay.emphasisLine]); for WEIGHTED slots it equals the number the watch
     * already derives from [goal].
     */
    val goalLabel: String = "",
    /**
     * How this exercise is tracked — [cloud.trotter.log.strength.domain.library.TrackingType]'s
     * name, lowercased ("weighted"/"reps"/"timed"). Tells the watch which control to
     * render (weight numeral, reps-only, or a seconds hold) and which field the crown
     * edits. Additive, appended last, defaulting to "weighted" so a pre-P5 publisher and
     * every existing fixture decode as the (only) old behavior; a stale watch that
     * ignores it just keeps drawing the weighted view (self-heals on update).
     */
    val tracking: String = "weighted",
    /**
     * How the superset partner is tracked — same encoding as [tracking], for
     * [supersetPartnerName]'s exercise. Meaningless when there is no partner.
     * Additive, appended last, defaulting to "weighted" so a pre-existing
     * publisher and every existing fixture decode as the (only) old behavior:
     * the partner row rendered with the main's tracking type.
     */
    val ssTracking: String = "weighted",
    /**
     * The replacements the phone has already ranked for this lift (brief §6 "Swap",
     * issue #90) — the *only* alternates the watch may ever offer. The watch never
     * consults a catalog and never re-plans: it flicks through this list, echoes one
     * back as an [ExerciseSwapDelta], and the phone applies its own §8.3 swap.
     *
     * Ranked best-first (`substitutionsFor`'s subRank order) and capped small: a
     * wrist is not a picker, and an unbounded list would ride every snapshot for
     * every lift. Empty when the slot has no same-pattern peers the lifter can
     * actually use — and empty is also what an older publisher decodes to, which
     * simply means no Swap is offered (mirrors [WatchDay.emphasisLine]).
     *
     * MAIN track only. A superset partner has no swap gesture on the dial, so
     * prescribing alternates for one would be wire nobody reads.
     */
    val alternates: List<WatchAlternate> = emptyList(),
    /**
     * The catalog id of the exercise in this slot — the slot's *content* identity, as
     * distinct from [programExerciseId], which identifies the slot itself and survives
     * a swap.
     *
     * It exists because a swap has to be acked by identity, not by display text.
     * Settling a pending swap on [name] alone is wrong the moment two entries can
     * share a name (two custom exercises, or an alternate named like the lift it
     * replaces): an old-state snapshot would then look like the phone had answered,
     * and a swap that never landed would be dropped from the queue and never re-sent.
     *
     * Additive, appended last, defaulting to "" so an older publisher decodes fine.
     * Blank means "this phone doesn't publish ids", and the watch degrades to matching
     * on [name] — the only behavior available before this field existed.
     */
    val exerciseId: String = "",
)

/**
 * One phone-prescribed replacement for a lift. [name] is what the disc shows (and
 * what settles the swap in the outbound queue — it is the same string the next
 * snapshot's [WatchExercise.name] will carry); [exerciseId] is the catalog id the
 * phone acts on. Both travel because the watch displays one and echoes the other.
 */
@Serializable
data class WatchAlternate(val exerciseId: String, val name: String)

/** One round. [kind] mirrors [cloud.trotter.log.strength.domain.model.SetKind]'s name. */
@Serializable
data class WatchSet(
    val weightLb: Double,
    val reps: Int,
    val kind: String,
    val done: Boolean,
    /**
     * The TIMED hold duration in seconds ([cloud.trotter.log.strength.domain.model.LoggedSet.seconds]);
     * 0 and ignored for WEIGHTED/REPS. Additive, appended last, defaulting to 0 so
     * old publishers and existing fixtures decode fine.
     */
    val seconds: Int = 0,
    /**
     * Rest to run after completing this set, in seconds; 0 = no timer. Stamped
     * phone-side from [cloud.trotter.log.strength.domain.standards.RestPolicy.effectiveRestSeconds],
     * gated by the Setup master toggle — the watch counts a number down, it never
     * computes one. Superset partner rows carry 0: one round has one rest, and the
     * main track's value governs. Additive, appended last, defaulting to 0 so old
     * publishers and existing fixtures decode fine (mirrors [seconds]).
     */
    val restAfterSeconds: Int = 0,
)
