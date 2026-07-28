package io.github.sjtrotter.strengthlog.wear.ui

import io.github.sjtrotter.strengthlog.domain.sync.WatchSnapshot

/**
 * The dial as ambient mode may draw it (brief §7): the same geometry, outline
 * arcs only, no accent, no filled shapes, dim gray type — and only three things
 * to say. Kept as a pure mapping for the same reason the interactive dial is one:
 * "what does ambient show at 12/21 with 1:24 of rest left" is a value, testable
 * without a watch that is asleep.
 *
 * The day arc is the only thing always drawn. When a clock is running the centre
 * gives its numeral over to the countdown and the time steps down to the bottom
 * band — the countdown is why the lifter is looking, and ambient has no fourth
 * zone to put it in.
 */
data class AmbientDialState(
    /** Outer ring: sets logged today / sets today, as an outline arc. */
    val dayProgress: Float,
    /** Inner ring: the rest still to run, or null when no clock is running. */
    val restFraction: Float?,
    /** "DAY A · 12/21" — the top band. */
    val topText: String,
    /** The centre numeral: the rest countdown while one runs, else the time. */
    val centerText: String,
    /** The bottom band: the time, displaced there by a running countdown. */
    val bottomText: String?,
)

fun ambientDialState(
    snapshot: WatchSnapshot,
    timeText: String,
    restRemainingSeconds: Int? = null,
    restTotalSeconds: Int = 0,
): AmbientDialState {
    val sets = snapshot.day.exercises.flatMap { it.sets }
    val done = sets.count { it.done }
    val remaining = restRemainingSeconds?.takeIf { it > 0 }
    return AmbientDialState(
        dayProgress = if (sets.isEmpty()) 0f else done.toFloat() / sets.size,
        restFraction = if (remaining != null && restTotalSeconds > 0) {
            (remaining.toFloat() / restTotalSeconds).coerceIn(0f, 1f)
        } else {
            null
        },
        topText = if (sets.isEmpty()) {
            "day ${snapshot.day.dayId}".uppercase()
        } else {
            "day ${snapshot.day.dayId} · $done/${sets.size}".uppercase()
        },
        centerText = if (remaining != null) DialFormat.clock(remaining.toLong()) else timeText,
        bottomText = timeText.takeIf { remaining != null },
    )
}
