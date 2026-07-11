package io.github.sjtrotter.strengthlog.domain.sync

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
 */
@Serializable
data class WatchSnapshot(
    val schemaVersion: Int = 1,
    val revision: Long,
    val suggestedDayId: String,
    val day: WatchDay,
    /** "lb" or "kg" — matches [io.github.sjtrotter.strengthlog.domain.units.WeightUnit].name, lowercased. */
    val unit: String,
)

@Serializable
data class WatchDay(
    val dayId: String,
    val title: String,
    val accentIndex: Int,
    val exercises: List<WatchExercise>,
    /**
     * The day's muscle-angle emphasis ([io.github.sjtrotter.strengthlog.domain.model.ProgramDay.emphasisLine]),
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
    /** "main" or "ss" — see [io.github.sjtrotter.strengthlog.domain.model.SupersetPartner]'s track. */
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
     * The pre-formatted GOAL string ([io.github.sjtrotter.strengthlog.domain.standards.GoalFormatter]),
     * so a reclassified REPS/TIMED slot can read "6 reps" / "45s" without the
     * watch ever doing goal math. Additive groundwork appended last: defaults to
     * "" so older publishers and existing fixtures decode fine (mirrors
     * [WatchDay.emphasisLine]); for WEIGHTED slots it equals the number the watch
     * already derives from [goal].
     */
    val goalLabel: String = "",
)

/** One round. [kind] mirrors [io.github.sjtrotter.strengthlog.domain.model.SetKind]'s name. */
@Serializable
data class WatchSet(
    val weightLb: Double,
    val reps: Int,
    val kind: String,
    val done: Boolean,
)
