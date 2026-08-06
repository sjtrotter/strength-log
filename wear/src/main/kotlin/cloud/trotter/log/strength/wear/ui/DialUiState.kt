package cloud.trotter.log.strength.wear.ui

/**
 * What the dial shows, as data. One composable renders every workout screen
 * (brief §9), so "which screen am I on" is a value here, not a navigation
 * destination — the screens never slide, the same layout re-renders in place.
 *
 * Everything in this file is pure Kotlin: it's built by [dialUiState] and
 * JVM-tested, and the composable does layout only.
 */

/**
 * The two faces the watch has, and never a third (v3 §3): the overview the app
 * opens on, and the workout you tap into. The platform dismiss gesture moves
 * between them; nothing else does.
 */
enum class DialFace { OVERVIEW, WORKOUT }

/** The dial's screens (§5): the overview, and the six of the workout face. */
enum class DialScreen { OVERVIEW, READY, LIFTING, REST, REST_OVER, TIMED_HOLD, DAY_DONE }

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

/** One line of the disc, top to bottom. Multiple lines live only in the disc (§2). */
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
enum class DialTap { NONE, OPEN_WORKOUT, START_SET, TICK, SKIP_REST, DISMISS }

/** What turning the crown does on this screen (§6). */
enum class DialCrown { NONE, SELECT_EXERCISE, PEEK }

/**
 * What a leftward swipe does here (v3 §3). It is contextual and it is rare: only
 * where the lifter is *between* efforts, never under the bar or mid-rest, where a
 * brushed sleeve would cost them a set.
 */
enum class DialSwipe { NONE, NEXT_EXERCISE, BROWSE_DAY }

/** How a day's segment of the cycle ring reads (v3 §1): the accent is "you are
 *  here", the white marker is "you are looking here" (the peek vocabulary, §4). */
enum class CycleMark { TODAY, OTHER, BROWSED }

/** One day of the program, as one segment of the outer ring. */
data class CycleSegment(val dayLabel: String, val accentIndex: Int, val mark: CycleMark)

/**
 * What a 700ms hold on the disc does (§6) — undo the round at [roundIndex]. Null
 * when there is nothing logged to take back. [disc] is what the disc says while
 * the hold fills; the fill itself is the disc's own ring, drawn by the composable.
 */
data class DialHold(val roundIndex: Int, val disc: DiscContent)

data class DialUiState(
    val screen: DialScreen,
    val accentIndex: Int,
    /** Outer ring: the program's days in order, clockwise from 12 (v3 §1). */
    val cycle: List<CycleSegment>,
    /** Sets logged today / sets today, 0f..1f — drawn inside today's own segment. */
    val dayProgress: Float,
    /** Inner ring segments; empty means the inner ring is gone (day done, §5.7). */
    val rounds: List<RoundState>,
    /**
     * A clock running right now, as a fraction: a rest draining (§5.4) or a timed
     * hold filling (§5.6). It is its own ring on the disc's rim, so it neither
     * reshapes nor replaces [rounds] — the round count stays readable through a
     * rest, which is when the lifter most wants it (v2 §3).
     */
    val arc: Float?,
    val topBand: BandContent?,
    val bottomBand: BandContent?,
    val disc: DiscContent,
    /** True while the disc is the one a rest just handed over to — the halo
     *  bloom fires on the transition into it and decays (§8). */
    val bloom: Boolean,
    val tap: DialTap,
    val crown: DialCrown = DialCrown.NONE,
    val swipe: DialSwipe = DialSwipe.NONE,
    val hold: DialHold? = null,
)
