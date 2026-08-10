package cloud.trotter.log.strength.wear.ui

import cloud.trotter.log.strength.domain.glance.DayProgress
import cloud.trotter.log.strength.domain.sync.WatchSnapshot

/**
 * The dial as ambient mode may draw it (brief §7): the same geometry, outline
 * arcs only, no accent, no filled shapes, dim gray type (pure white on low-bit
 * devices, which get no grays) — and only three things
 * to say. Kept as a pure mapping for the same reason the interactive dial is one:
 * "what does ambient show at 12/21 while resting" is a value, testable
 * without a watch that is asleep.
 *
 * The day arc is the only thing always drawn. When a clock is running the centre
 * gives its numeral over to a cadence-safe resting label and the time steps down
 * to the bottom band. Seconds belong only to the interactive face because ambient
 * receives no per-second repaint signal.
 */
data class AmbientDialState(
    /** Outer ring: sets logged today / sets today, as an outline arc. */
    val dayProgress: Float,
    /** "DAY A · 12/21" — the top band. */
    val topText: String,
    /** The centre: a resting label while a rest runs, else the time. */
    val centerText: String,
    /** The bottom band: the time, displaced there by a running countdown. */
    val bottomText: String?,
)

fun ambientDialState(
    snapshot: WatchSnapshot,
    timeText: String,
    restRemainingSeconds: Int? = null,
    dayText: (String) -> String,
    dayProgressText: (String, Int, Int) -> String,
    restingText: String,
): AmbientDialState {
    val progress = DayProgress.of(snapshot.day)
    val resting = restRemainingSeconds?.let { it > 0 } == true
    return AmbientDialState(
        dayProgress = if (progress.total == 0) 0f else progress.done.toFloat() / progress.total,
        topText = if (progress.total == 0) {
            dayText(snapshot.day.dayId).uppercase()
        } else {
            dayProgressText(snapshot.day.dayId, progress.done, progress.total).uppercase()
        },
        centerText = if (resting) restingText.uppercase() else timeText,
        bottomText = timeText.takeIf { resting },
    )
}
