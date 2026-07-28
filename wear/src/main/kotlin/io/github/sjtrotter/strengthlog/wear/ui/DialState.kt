package io.github.sjtrotter.strengthlog.wear.ui

import io.github.sjtrotter.strengthlog.domain.library.TrackingType
import io.github.sjtrotter.strengthlog.domain.sync.WatchExercise
import io.github.sjtrotter.strengthlog.domain.sync.WatchSnapshot
import io.github.sjtrotter.strengthlog.domain.units.WeightUnit
import kotlin.math.roundToLong

/**
 * The whole workout flow as a pure function: snapshot + the watch's own local
 * timer/phase state in, one [DialUiState] out (brief §5). Every screen the
 * lifter sees is decided here, so the seven states and the transitions between
 * them are JVM-testable without a device.
 *
 * Local state is deliberately minimal — which exercise and which round are
 * *derived* from the snapshot's done flags (the client echoes a tick
 * optimistically, so the ring moves the instant it's tapped), and only what the
 * snapshot genuinely cannot know is passed in: has the lifter started this set,
 * is a rest running, and when did this session's first set begin.
 */

/** Whether the lifter has started the set in front of them. */
enum class SetPhase { READY, LIFTING }

/** A rest in flight. [deadlineElapsedMillis] is an `elapsedRealtime()` instant. */
data class RestState(
    val deadlineElapsedMillis: Long,
    val totalSeconds: Int,
    /** True for the rest between two exercises — the disc's NEXT line names the
     *  next exercise rather than the next set. */
    val betweenExercises: Boolean,
)

/** Wall-clock bookends of this session on the wrist, for the day-done stats (§5.7). */
data class SessionStamps(
    val firstStartedAtMillis: Long = 0L,
    val lastCompletedAtMillis: Long = 0L,
)

data class DialInputs(
    val snapshot: WatchSnapshot,
    val exerciseIndex: Int,
    val phase: SetPhase,
    /** True once the lifter has tapped through the "today" preview (§5.1). */
    val begun: Boolean,
    val pendingCount: Int,
    val rest: RestState?,
    /** The rest that just ended, in seconds — drives the "✓ RESTED 2:30" badge
     *  and the halo bloom (§5.5). Null at every other moment. */
    val restedSeconds: Int? = null,
    val liftingElapsedMillis: Long = 0L,
    val nowElapsedMillis: Long = 0L,
    val session: SessionStamps = SessionStamps(),
)

/**
 * The exercise the dial is on: the first with work left. The watch never
 * auto-opens past an unfinished exercise and never re-orders the day.
 */
fun currentExerciseIndex(snapshot: WatchSnapshot): Int {
    val index = snapshot.day.exercises.indexOfFirst { ex -> ex.sets.any { !it.done } }
    return if (index >= 0) index else (snapshot.day.exercises.size - 1).coerceAtLeast(0)
}

/** The round in [exercise] the dial is on: the first undone one, else the last. */
fun currentRoundIndex(exercise: WatchExercise): Int {
    val index = exercise.sets.indexOfFirst { !it.done }
    return if (index >= 0) index else (exercise.sets.size - 1).coerceAtLeast(0)
}

/**
 * The hold goal for this round in seconds, or 0 when this isn't a timed hold —
 * the trigger for the timed-hold screen (§5.6) and for its auto-tick at goal.
 */
fun holdGoalSeconds(exercise: WatchExercise, roundIndex: Int): Int {
    if (watchTracking(exercise.tracking) != TrackingType.TIMED) return 0
    return exercise.sets.getOrNull(roundIndex)?.seconds ?: 0
}

/**
 * One round as a single read-only line — "235 × 5", "×12", "45s". The dial's
 * prescription line, and the label the rest timer carries into ambient.
 */
fun roundLabel(round: RoundUiState): String =
    listOf(round.heroDisplay, round.secondaryDisplay).filter { it.isNotBlank() }.joinToString(" ")

fun dialUiState(inputs: DialInputs): DialUiState {
    val day = inputs.snapshot.day
    if (day.exercises.isEmpty()) return noProgram(day.accentIndex)

    val unit = watchUnit(inputs.snapshot.unit)
    val exercise = day.exercises[inputs.exerciseIndex.coerceIn(day.exercises.indices)]
    val stream = exercise.toStreamUiState(unit, day.dayId, day.accentIndex)
    val roundIndex = currentRoundIndex(exercise)
    val allSets = day.exercises.flatMap { it.sets }
    val doneSets = allSets.count { it.done }
    val dayDone = allSets.isNotEmpty() && doneSets == allSets.size
    val restRunning = inputs.rest?.takeIf { !RestTimer.isExpired(it.deadlineElapsedMillis, inputs.nowElapsedMillis) }

    val context = ScreenContext(
        inputs = inputs,
        exercise = exercise,
        stream = stream,
        roundIndex = roundIndex,
        dayProgress = if (allSets.isEmpty()) 0f else doneSets.toFloat() / allSets.size,
        setCount = allSets.size,
        doneSetCount = doneSets,
        unit = unit,
    )

    val state = when {
        dayDone -> context.dayDone()
        restRunning != null -> context.rest(restRunning)
        inputs.restedSeconds != null && inputs.phase == SetPhase.READY -> context.restOver(inputs.restedSeconds)
        inputs.phase == SetPhase.LIFTING && holdGoalSeconds(exercise, roundIndex) > 0 -> context.timedHold()
        inputs.phase == SetPhase.LIFTING -> context.lifting()
        !inputs.begun && doneSets == 0 -> context.today()
        else -> context.ready()
    }
    return state.withQueuedStatus(inputs.pendingCount)
}

/** Everything the seven screen builders share, so each reads as its brief entry. */
private class ScreenContext(
    val inputs: DialInputs,
    val exercise: WatchExercise,
    val stream: ExerciseStreamUiState,
    val roundIndex: Int,
    val dayProgress: Float,
    val setCount: Int,
    val doneSetCount: Int,
    val unit: WeightUnit,
) {
    val day get() = inputs.snapshot.day
    val round: RoundUiState? get() = stream.rounds.getOrNull(roundIndex)
    val roundStates: List<RoundState>
        get() = DialGeometry.roundStates(exercise.sets.map { it.done }, roundIndex)

    /** "235 × 5" / "×12" / "45s" — the round as one read-only line. */
    fun prescription(): String = round?.let(::roundLabel).orEmpty()

    fun setOfLine(): String = "set ${roundIndex + 1} of ${exercise.sets.size}".uppercase()

    fun base(
        screen: DialScreen,
        rounds: List<RoundState>,
        arc: Float?,
        topBand: BandContent?,
        bottomBand: BandContent?,
        disc: DiscContent,
        tap: DialTap,
        bloom: Boolean = false,
        dayProgressOverride: Float? = null,
    ) = DialUiState(
        screen = screen,
        accentIndex = day.accentIndex,
        dayProgress = dayProgressOverride ?: dayProgress,
        rounds = rounds,
        arc = arc,
        topBand = topBand,
        bottomBand = bottomBand,
        disc = disc,
        bloom = bloom,
        tap = tap,
    )

    fun today(): DialUiState {
        val exerciseStates = DialGeometry.roundStates(
            doneFlags = day.exercises.map { ex -> ex.sets.isNotEmpty() && ex.sets.all { it.done } },
            currentIndex = day.exercises.indexOf(exercise),
        )
        val subtitle = day.emphasisLine.takeIf { it.isNotBlank() }
        return base(
            screen = DialScreen.TODAY,
            rounds = exerciseStates,
            arc = null,
            topBand = BandContent(
                text = listOfNotNull("day ${day.dayId}", subtitle).joinToString(" · ").uppercase(),
                tone = DialTone.ACCENT_BRIGHT,
            ),
            bottomBand = BandContent(
                text = "${day.exercises.size} exercises · $setCount sets".uppercase(),
                tone = DialTone.TERTIARY,
                role = DialTextRole.BAND_SECONDARY,
            ),
            disc = DiscContent(
                style = DiscStyle.FILLED,
                lines = listOf(
                    DiscLine(exercise.name.uppercase(), DialTextRole.DISC_LABEL_SMALL, DialTone.ON_DISC),
                    DiscLine(
                        "begin · ${exercise.sets.size} sets".uppercase(),
                        DialTextRole.BAND,
                        DialTone.ON_DISC,
                    ),
                ),
            ),
            tap = DialTap.BEGIN_EXERCISE,
        )
    }

    fun ready(): DialUiState = base(
        screen = DialScreen.READY,
        rounds = roundStates,
        arc = null,
        topBand = BandContent(
            text = "${exercise.name} · ${round?.kindLabel.orEmpty()}".uppercase(),
            tone = DialTone.SECONDARY,
        ),
        bottomBand = BandContent(setOfLine(), DialTone.TERTIARY),
        disc = startDisc(),
        tap = DialTap.START_SET,
    )

    fun lifting(): DialUiState = base(
        screen = DialScreen.LIFTING,
        rounds = roundStates,
        arc = null,
        topBand = BandContent(
            text = DialFormat.clock(inputs.liftingElapsedMillis / 1000L),
            tone = DialTone.PRIMARY,
            dotTone = DialTone.ACCENT_BRIGHT,
        ),
        bottomBand = liftingBottomBand(),
        disc = DiscContent(
            style = DiscStyle.OUTLINED,
            lines = listOf(
                DiscLine(numeralGroup()),
                DiscLine("tap when racked".uppercase(), DialTextRole.BAND, DialTone.ACCENT_BRIGHT),
            ),
        ),
        tap = DialTap.TICK,
    )

    fun timedHold(): DialUiState {
        val goal = holdGoalSeconds(exercise, roundIndex)
        val elapsedSeconds = inputs.liftingElapsedMillis / 1000L
        return base(
            screen = DialScreen.TIMED_HOLD,
            rounds = roundStates,
            arc = (elapsedSeconds.toFloat() / goal).coerceIn(0f, 1f),
            topBand = BandContent(
                text = "${exercise.name} · ${round?.kindLabel.orEmpty()}".uppercase(),
                tone = DialTone.SECONDARY,
            ),
            bottomBand = BandContent(setOfLine(), DialTone.TERTIARY),
            disc = DiscContent(
                style = DiscStyle.OUTLINED,
                lines = listOf(
                    DiscLine(DialFormat.clock(elapsedSeconds), DialTextRole.NUMERAL_LARGE, DialTone.PRIMARY),
                    DiscLine(
                        "goal ${DialFormat.clock(goal.toLong())}".uppercase(),
                        DialTextRole.BAND,
                        DialTone.SECONDARY,
                    ),
                ),
            ),
            tap = DialTap.TICK,
        )
    }

    fun rest(rest: RestState): DialUiState {
        val remaining = RestTimer.remainingSeconds(rest.deadlineElapsedMillis, inputs.nowElapsedMillis)
        val next = if (rest.betweenExercises) exercise.name else prescription()
        return base(
            screen = DialScreen.REST,
            rounds = roundStates,
            arc = RestTimer.remainingFraction(
                rest.deadlineElapsedMillis,
                inputs.nowElapsedMillis,
                rest.totalSeconds,
            ),
            topBand = BandContent("rest".uppercase(), DialTone.SECONDARY),
            bottomBand = BandContent("tap to skip".uppercase(), DialTone.TERTIARY),
            disc = DiscContent(
                style = DiscStyle.FLAT,
                lines = listOf(
                    DiscLine(DialFormat.clock(remaining.toLong()), DialTextRole.NUMERAL_LARGE, DialTone.PRIMARY),
                    DiscLine("next $next".uppercase(), DialTextRole.BAND, DialTone.SECONDARY),
                ),
            ),
            tap = DialTap.SKIP_REST,
        )
    }

    fun restOver(restedSeconds: Int): DialUiState = base(
        screen = DialScreen.REST_OVER,
        rounds = roundStates,
        arc = null,
        topBand = BandContent(
            text = "✓ rested ${DialFormat.clock(restedSeconds.toLong())}".uppercase(),
            tone = DialTone.SUCCESS,
        ),
        bottomBand = BandContent(
            text = listOf(setOfLine(), round?.kindLabel.orEmpty())
                .filter { it.isNotBlank() }
                .joinToString(" · ")
                .uppercase(),
            tone = DialTone.TERTIARY,
        ),
        disc = startDisc(),
        tap = DialTap.START_SET,
        bloom = true,
    )

    fun dayDone(): DialUiState = base(
        screen = DialScreen.DAY_DONE,
        rounds = emptyList(),
        arc = null,
        topBand = null,
        bottomBand = BandContent(
            text = if (inputs.pendingCount > 0) {
                "${inputs.pendingCount} queued · $setCount sets".uppercase()
            } else {
                "✓ synced · $setCount sets".uppercase()
            },
            tone = if (inputs.pendingCount > 0) DialTone.TERTIARY else DialTone.SUCCESS,
        ),
        disc = DiscContent(
            style = DiscStyle.FILLED_GREEN,
            lines = listOf(
                DiscLine("done".uppercase(), DialTextRole.NUMERAL, DialTone.ON_DISC),
                DiscLine(dayDoneStats(), DialTextRole.BAND, DialTone.ON_DISC),
            ),
        ),
        tap = DialTap.DISMISS,
        dayProgressOverride = 1f,
    )

    /** "38 MIN · 12,450 LB" — real logged work, never a placeholder (§5.7). */
    fun dayDoneStats(): String {
        val minutes = DialFormat.wholeMinutes(
            inputs.session.firstStartedAtMillis,
            inputs.session.lastCompletedAtMillis,
        )
        val volume = sessionVolume(day.exercises, unit)
        val parts = buildList {
            if (minutes > 0) add("$minutes min")
            if (volume > 0) add("${DialFormat.grouped(volume)} ${unit.name}")
        }
        return (if (parts.isEmpty()) listOf("$doneSetCount sets") else parts).joinToString(" · ").uppercase()
    }

    private fun startDisc() = DiscContent(
        style = DiscStyle.FILLED,
        lines = listOf(
            DiscLine("start".uppercase(), DialTextRole.DISC_LABEL, DialTone.ON_DISC),
            DiscLine(prescription(), DialTextRole.DISC_LABEL_SMALL, DialTone.ON_DISC),
        ),
    )

    /** The lifting numeral: weight big, reps as a smaller trailing group (§5.3). */
    private fun numeralGroup(): List<DialSpan> {
        val r = round ?: return emptyList()
        val hero = DialSpan(r.heroDisplay, DialTextRole.NUMERAL, DialTone.PRIMARY)
        if (stream.isSuperset || r.secondaryDisplay.isBlank()) return listOf(hero)
        return listOf(hero, DialSpan("×${r.reps}", DialTextRole.DISC_LABEL, DialTone.SECONDARY))
    }

    /**
     * A superset round is one tick for both lifts, so the partner's prescription
     * is context while lifting, not a second thing to log. Everything else gets
     * the undo hint (the long-press itself lands with the crown layer, §6).
     */
    private fun liftingBottomBand(): BandContent {
        val partner = round?.partner
        return if (stream.partnerName != null && partner != null) {
            BandContent(
                text = "then ${stream.partnerName} ${partner.summaryDisplay}".uppercase(),
                tone = DialTone.SECONDARY,
            )
        } else {
            BandContent("hold to undo".uppercase(), DialTone.TERTIARY)
        }
    }
}

/**
 * Offline status lives in the top band and nowhere else (§7) — except while the
 * lifter is under the bar, where the band is carrying the live elapsed timer and
 * a queue that will flush itself is not worth taking it for.
 */
private fun DialUiState.withQueuedStatus(pendingCount: Int): DialUiState {
    if (pendingCount <= 0 || screen == DialScreen.LIFTING || screen == DialScreen.DAY_DONE) return this
    return copy(
        topBand = BandContent(
            text = "$pendingCount queued".uppercase(),
            tone = DialTone.SECONDARY,
            dotTone = DialTone.SECONDARY,
        ),
    )
}

/** The phone hasn't generated a program yet (§7): a dashed disc and nothing to act on. */
private fun noProgram(accentIndex: Int) = DialUiState(
    screen = DialScreen.TODAY,
    accentIndex = accentIndex,
    dayProgress = 0f,
    rounds = emptyList(),
    arc = null,
    topBand = null,
    bottomBand = null,
    disc = DiscContent(
        style = DiscStyle.DASHED,
        lines = listOf(
            DiscLine("no program".uppercase(), DialTextRole.DISC_LABEL_SMALL, DialTone.PRIMARY),
            DiscLine("set up on your phone".uppercase(), DialTextRole.BAND_SECONDARY, DialTone.TERTIARY),
        ),
    ),
    bloom = false,
    tap = DialTap.NONE,
)

/** Logged volume in display units: every done round, both tracks of a superset. */
fun sessionVolume(exercises: List<WatchExercise>, unit: WeightUnit): Long {
    val lb = exercises.sumOf { exercise ->
        (exercise.sets + exercise.ssSets)
            .filter { it.done }
            .sumOf { it.weightLb * it.reps }
    }
    return unit.fromLb(lb).roundToLong()
}

/** Display formatting the dial owns — clocks and grouped numerals, no locale surprises. */
object DialFormat {

    /** "0:47", "2:30", "1:05:03" — the shape a workout clock reads. */
    fun clock(totalSeconds: Long): String {
        val seconds = totalSeconds.coerceAtLeast(0L)
        val minutes = seconds / 60
        val rest = (seconds % 60).toString().padStart(2, '0')
        if (minutes < 60) return "$minutes:$rest"
        return "${minutes / 60}:${(minutes % 60).toString().padStart(2, '0')}:$rest"
    }

    /** Whole minutes between two wall-clock stamps; 0 when either is missing. */
    fun wholeMinutes(fromMillis: Long, toMillis: Long): Int {
        if (fromMillis <= 0L || toMillis <= fromMillis) return 0
        return ((toMillis - fromMillis) / 60_000L).toInt()
    }

    /** "12,450" — thousands separated by hand, so the wrist reads the same everywhere. */
    fun grouped(value: Long): String {
        val digits = value.toString()
        return digits.reversed().chunked(3).joinToString(",").reversed()
    }
}
