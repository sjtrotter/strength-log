package io.github.sjtrotter.strengthlog.wear.ui

/**
 * What the dial shows, as data. One composable renders every workout screen
 * (brief §9), so "which screen am I on" is a value here, not a navigation
 * destination — the screens never slide, the same layout re-renders in place.
 *
 * Everything in this file is pure Kotlin: it's built by [dialUiState] and
 * JVM-tested, and the composable does layout only.
 */

/** The brief's seven screens (§5). */
enum class DialScreen { TODAY, READY, LIFTING, REST, REST_OVER, TIMED_HOLD, DAY_DONE }

/** Disc grammar (§4) — the fill states the mode, and the mode states what a tap does. */
enum class DiscStyle { FILLED, OUTLINED, FLAT, DASHED, DIMMED, FILLED_GREEN }

/** The type scale (§3), three sizes with two steps each. Nothing in between. */
enum class DialTextRole { NUMERAL_LARGE, NUMERAL, DISC_LABEL, DISC_LABEL_SMALL, BAND, BAND_SECONDARY }

/**
 * A colour *role*, resolved against the day accent by the composable — the state
 * layer never names a colour. [ON_DISC] means "whatever reads on the disc's own
 * fill", which is how one disc description works filled, outlined or green.
 */
enum class DialTone { PRIMARY, SECONDARY, TERTIARY, ACCENT_BRIGHT, SUCCESS, ON_DISC }

/** A run of text inside a disc line; several spans sit on one baseline. */
data class DialSpan(val text: String, val role: DialTextRole, val tone: DialTone)

/** One line of the disc, top to bottom. Two lines max, and only in the disc (§2). */
data class DiscLine(val spans: List<DialSpan>) {
    constructor(text: String, role: DialTextRole, tone: DialTone) :
        this(listOf(DialSpan(text, role, tone)))
}

data class DiscContent(val style: DiscStyle, val lines: List<DiscLine>)

/** A label band: one line of caps, optionally led by a pulsing dot. */
data class BandContent(
    val text: String,
    val tone: DialTone,
    val role: DialTextRole = DialTextRole.BAND,
    /** Non-null draws a pulsing dot of that tone before the text. */
    val dotTone: DialTone? = null,
)

/** What tapping the disc does — the dial's one tap target (§1). */
enum class DialTap { NONE, BEGIN_EXERCISE, START_SET, TICK, SKIP_REST, DISMISS }

data class DialUiState(
    val screen: DialScreen,
    val accentIndex: Int,
    /** Outer ring: sets logged today / sets today, 0f..1f. */
    val dayProgress: Float,
    /** Inner ring segments; empty means the inner ring is gone (day done, §5.7). */
    val rounds: List<RoundState>,
    /**
     * Non-null turns the inner ring into one continuous arc of this fraction:
     * a rest draining (§5.4) or a timed hold filling (§5.6). The [rounds] stay
     * populated so the composable can melt between the two forms (§8).
     */
    val arc: Float?,
    val topBand: BandContent?,
    val bottomBand: BandContent?,
    val disc: DiscContent,
    /** True while the disc is the one a rest just handed over to — the halo
     *  bloom fires on the transition into it and decays (§8). */
    val bloom: Boolean,
    val tap: DialTap,
)
