package cloud.trotter.log.strength.wear.ui

import cloud.trotter.log.strength.domain.glance.DayProgress
import cloud.trotter.log.strength.domain.library.TrackingType
import cloud.trotter.log.strength.domain.sync.WatchExercise
import cloud.trotter.log.strength.domain.sync.WatchSnapshot
import cloud.trotter.log.strength.domain.generator.CardioIntervals
import cloud.trotter.log.strength.domain.units.WeightUnit
import kotlin.math.roundToLong

/**
 * The whole workout flow as a pure function: snapshot + the watch's own local
 * timer/phase state in, one [DialUiState] out (brief §5). Every screen the
 * lifter sees is decided here, so both faces, the states within them and the
 * transitions between them are JVM-testable without a device.
 *
 * Local state is deliberately minimal — which exercise and which round are
 * *derived* from the snapshot's done flags (the client echoes a tick
 * optimistically, so the ring moves the instant it's tapped), and only what the
 * snapshot genuinely cannot know is passed in: which face the lifter is on, has
 * the lifter started this set, is a rest running, and when did this session's
 * first set begin.
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
    /** Which of the two faces the lifter is on (v3 §3). */
    val face: DialFace,
    val pendingCount: Int,
    val rest: RestState?,
    /** The rest that just ended, in seconds — drives the "✓ RESTED 2:30" badge
     *  and the halo bloom (§5.5). Null at every other moment. */
    val restedSeconds: Int? = null,
    val liftingElapsedMillis: Long = 0L,
    val nowElapsedMillis: Long = 0L,
    val nowWallMillis: Long = 0L,
    val session: SessionStamps = SessionStamps(),
    /** The cycle day the overview is previewing (v3 §3), or null when it is showing
     *  today. Like the crown's peek, it is a glance — never restored. */
    val browseDayIndex: Int? = null,
    /** The round the crown is peeking at (§6), or null when nobody is browsing. */
    val peekRoundIndex: Int? = null,
    /** The prescribed alternate the crown is proposing (§6 Swap, #90), or null when
     *  the lifter is keeping the lift they were given. Like the peek, a glance —
     *  never restored. */
    val swapAlternateIndex: Int? = null,
    /** The lifts with a swap the phone hasn't answered yet. Those lifts stop being
     *  loggable until it does: their rows still describe the exercise being replaced,
     *  and the replacement's are seeded phone-side (§8.3). */
    val pendingSwapExerciseIds: Set<Long> = emptySet(),
    /** What this watch remembers logging today ([TickMemory]): the peek's `TOOK`
     *  line, and the chronology the undo needs to reach the *latest* tick rather
     *  than the positionally nearest one. */
    val tickMemory: TickMemory = TickMemory.EMPTY,
    /** Non-null only while cardio executes; its step and elapsed are always derived. */
    val cardioAnchors: CardioAnchors? = null,
)

/**
 * The face a cold launch belongs on: whatever the day itself implies (v3 §3). A
 * workout with something logged in it opens on the workout face; an untouched day
 * opens on the overview. Nothing is stored for this — the sets are the record.
 */
fun impliedFace(snapshot: WatchSnapshot): DialFace =
    if (snapshot.day.exercises.any { ex -> ex.sets.any { it.done } }) {
        DialFace.WORKOUT
    } else {
        DialFace.OVERVIEW
    }

/** The system owns back at the dial's top level; only a workout face descends. */
fun systemBackTarget(face: DialFace, screen: DialScreen): DialFace? =
    DialFace.OVERVIEW.takeIf { face == DialFace.WORKOUT && screen != DialScreen.DAY_DONE }

/**
 * The outer ring: the program's days in order, today's segment carrying the accent
 * and the browsed one the white marker (v3 §1). The marks are checked in that
 * order for the same reason the exercise ring checks CURRENT before PEEKED —
 * where you're looking never overwrites where you are.
 *
 * A snapshot from a phone that doesn't publish a cycle yet — or one whose cycle
 * has lost today — falls back to a single segment for today, which is exactly the
 * whole-circle ring the dial had before the cycle existed.
 */
fun cycleSegments(snapshot: WatchSnapshot, browseDayIndex: Int? = null): List<CycleSegment> {
    val day = snapshot.day
    val days = snapshot.cycle.takeIf { cycle -> cycle.any { it.dayId == day.dayId } }
        ?: return listOf(CycleSegment(day.dayId.uppercase(), day.accentIndex, CycleMark.TODAY))
    return days.mapIndexed { index, cycleDay ->
        CycleSegment(
            dayLabel = cycleDay.dayId.uppercase(),
            // Accent by position, the rule every other surface uses.
            accentIndex = index,
            mark = when {
                cycleDay.dayId == day.dayId -> CycleMark.TODAY
                index == browseDayIndex -> CycleMark.BROWSED
                else -> CycleMark.OTHER
            },
        )
    }
}

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

fun dialUiState(inputs: DialInputs, copy: DialCopy): DialUiState {
    val day = inputs.snapshot.day
    if (day.exercises.isEmpty()) return noProgram(inputs.snapshot, copy)
    val browsing = inputs.browseDayIndex?.takeIf { inputs.face == DialFace.OVERVIEW }

    val unit = watchUnit(inputs.snapshot.unit)
    val exerciseIndex = inputs.exerciseIndex.coerceIn(day.exercises.indices)
    val exercise = day.exercises[exerciseIndex]
    val stream = exercise.toStreamUiState(unit, day.dayId, day.accentIndex, copy)
    val roundIndex = currentRoundIndex(exercise)
    val progress = DayProgress.of(day)
    val dayDone = progress.total > 0 && progress.done == progress.total
    val restRunning = inputs.rest?.takeIf { !RestTimer.isExpired(it.deadlineElapsedMillis, inputs.nowElapsedMillis) }

    val context = ScreenContext(
        inputs = inputs,
        exerciseIndex = exerciseIndex,
        exercise = exercise,
        stream = stream,
        roundIndex = roundIndex,
        dayProgress = if (progress.total == 0) 0f else progress.done.toFloat() / progress.total,
        setCount = progress.total,
        doneSetCount = progress.done,
        unit = unit,
        cycle = cycleSegments(inputs.snapshot, browsing),
        copy = copy,
    )

    val cardioSuggestion = inputs.snapshot.cardio
    // A suggestion without execution facts (old phone) is not offerable.
    val cardioUnlogged = cardioSuggestion?.mode != null && cardioSuggestion.loggedStartedAt == null
    val state = when {
        inputs.cardioAnchors != null && cardioSuggestion != null -> context.cardioExecuting(inputs.cardioAnchors)
        dayDone && cardioUnlogged && inputs.face == DialFace.OVERVIEW -> context.cardioOffer()
        dayDone -> context.dayDone()
        // The overview is a face, not a step: it stays available with a rest
        // running, and the clock keeps its ring there too (v3 §3).
        inputs.face == DialFace.OVERVIEW -> context.overview(restRunning, browsing)
        restRunning != null -> context.rest(restRunning)
        inputs.restedSeconds != null && inputs.phase == SetPhase.READY -> context.restOver(inputs.restedSeconds)
        inputs.phase == SetPhase.LIFTING && holdGoalSeconds(exercise, roundIndex) > 0 -> context.timedHold()
        inputs.phase == SetPhase.LIFTING -> context.lifting()
        else -> context.ready()
    }
    return state
        .withPeek(context, inputs.peekRoundIndex)
        .withSwapPreview(context, inputs.swapAlternateIndex)
        .withQueuedStatus(inputs.pendingCount, copy)
}

/** Everything the seven screen builders share, so each reads as its brief entry. */
private class ScreenContext(
    val inputs: DialInputs,
    val exerciseIndex: Int,
    val exercise: WatchExercise,
    val stream: ExerciseStreamUiState,
    val roundIndex: Int,
    val dayProgress: Float,
    val setCount: Int,
    val doneSetCount: Int,
    val unit: WeightUnit,
    val cycle: List<CycleSegment>,
    val copy: DialCopy,
) {
    val day get() = inputs.snapshot.day
    val round: RoundUiState? get() = stream.rounds.getOrNull(roundIndex)
    val roundStates: List<RoundState>
        get() = DialGeometry.roundStates(exercise.sets.map { it.done }, roundIndex)

    /** "235 × 5" / "×12" / "45s" — the round as one read-only line. */
    fun prescription(): String = round?.let(::roundLabel).orEmpty()

    fun setOfLine(): String = copy.setOf(roundIndex + 1, exercise.sets.size).uppercase()

    fun base(
        screen: DialScreen,
        rounds: List<RoundState>,
        arc: Float?,
        topBand: BandContent?,
        bottomBand: BandContent?,
        disc: DiscContent,
        tap: DialTap,
        crown: DialCrown = DialCrown.NONE,
        swipe: DialSwipe = DialSwipe.NONE,
        hold: DialHold? = null,
        bloom: Boolean = false,
        dayProgressOverride: Float? = null,
        accentOverride: Int? = null,
    ) = DialUiState(
        screen = screen,
        accentIndex = accentOverride ?: day.accentIndex,
        cycle = cycle,
        dayProgress = dayProgressOverride ?: dayProgress,
        rounds = rounds,
        arc = arc,
        topBand = topBand,
        bottomBand = bottomBand,
        disc = disc,
        bloom = bloom,
        tap = tap,
        crown = crown,
        swipe = swipe,
        hold = hold,
    )

    /**
     * The face the app opens on (v3 §3): the cycle on the rim, the day's lifts on
     * the exercise ring, and one way in. The disc says what the tap does and the
     * ring says where the day is — the old "begin" gate is gone, because tapping
     * the disc no longer changes the copy, it changes the face.
     */
    fun overview(rest: RestState?, browseDayIndex: Int?): DialUiState {
        if (browseDayIndex != null) browse(browseDayIndex)?.let { return it }
        val exerciseStates = DialGeometry.roundStates(
            doneFlags = day.exercises.map { ex -> ex.sets.isNotEmpty() && ex.sets.all { it.done } },
            currentIndex = exerciseIndex,
        )
        val started = doneSetCount > 0
        return base(
            screen = DialScreen.OVERVIEW,
            rounds = exerciseStates,
            arc = rest?.let {
                RestTimer.remainingFraction(it.deadlineElapsedMillis, inputs.nowElapsedMillis, it.totalSeconds)
            },
            // The title alone: which day this is now lives on the ring, and the
            // emphasis line is a five-clause sentence an arc can only truncate.
            topBand = dayTitleBand(day.title),
            bottomBand = null,
            disc = DiscContent(
                style = DiscStyle.FILLED,
                lines = listOf(
                    DiscLine(
                        (if (started) copy.continueText else copy.start).uppercase(),
                        DialTextRole.DISC_LABEL,
                        DialTone.ON_DISC,
                    ),
                    DiscLine(
                        if (started) setOfLine() else exercise.name.uppercase(),
                        DialTextRole.DISC_LABEL_SMALL,
                        DialTone.ON_DISC,
                    ),
                ),
            ),
            tap = DialTap.OPEN_WORKOUT,
            // The inner ring is exercises here, so the crown picks one of those —
            // and the tap opens the one the crown left the accent on, which is not
            // necessarily the first with work left (§5.1).
            crown = DialCrown.SELECT_EXERCISE,
            swipe = DialSwipe.BROWSE_DAY,
        )
    }

    /**
     * Another day of the program, read-only (v3 §3): its own accent, its own title,
     * one ring segment per lift it holds. The disc is dimmed and does nothing —
     * looking at Wednesday must never be one slip away from starting it — and the
     * white marker on its cycle segment says which day is being looked at while the
     * accent stays on today.
     *
     * Null when the index doesn't name a day, which is how a stale browse position
     * (the program changed under it) falls back to the live overview.
     */
    private fun browse(index: Int): DialUiState? {
        val browsed = inputs.snapshot.cycle.getOrNull(index) ?: return null
        return base(
            screen = DialScreen.OVERVIEW,
            rounds = List(browsed.exercises.size) { RoundState.UPCOMING },
            arc = null,
            topBand = dayTitleBand(browsed.title),
            bottomBand = null,
            disc = DiscContent(
                style = DiscStyle.DIMMED,
                lines = listOfNotNull(
                    DiscLine(
                        copy.day(browsed.dayId).uppercase(),
                        DialTextRole.DISC_LABEL,
                        DialTone.PRIMARY,
                    ),
                    browsed.exercises.firstOrNull()?.let {
                        DiscLine(it.name.uppercase(), DialTextRole.BAND, DialTone.SECONDARY)
                    },
                ),
            ),
            tap = DialTap.NONE,
            // The crown picks *today's* exercises; there is nothing here for it to
            // move, and a dimmed face that still responds isn't read-only.
            crown = DialCrown.NONE,
            swipe = DialSwipe.BROWSE_DAY,
            accentOverride = index,
        )
    }

    /** The day's name on the top band, in whichever accent the face is wearing. */
    private fun dayTitleBand(title: String): BandContent? =
        title.takeIf { it.isNotBlank() }?.let { BandContent(it.uppercase(), DialTone.ACCENT_BRIGHT) }

    /**
     * True where the dial may offer Swap: a lift the phone has ranked replacements
     * for, with **nothing logged against it**, and no swap of ours already in flight.
     *
     * The "nothing logged" clause is not a nicety, it is the whole rule. The phone's
     * swap clears the slot's live log (§8.3), so offering it only while there is
     * nothing to clear makes the destructive half of the operation cost exactly
     * nothing — and it is the same condition that frees the crown, because a peek
     * scrubs logged results and an untouched lift has none. Everything else falls
     * out of it: REST_OVER always follows a tick, DAY_DONE has no unlogged lift left,
     * and the overview's crown keeps picking lifts.
     */
    val swapOffered: Boolean
        get() = exercise.alternates.isNotEmpty() &&
            exercise.sets.none { it.done } &&
            !swapPending

    /** A swap the phone hasn't answered yet for the lift on screen. */
    val swapPending: Boolean
        get() = exercise.programExerciseId in inputs.pendingSwapExerciseIds

    fun ready(): DialUiState {
        if (swapPending) return swapping()
        return base(
            screen = DialScreen.READY,
            rounds = roundStates,
            arc = null,
            topBand = BandContent(
                text = copy.exerciseKind(exercise.name, round?.kindLabel.orEmpty()).uppercase(),
                tone = DialTone.SECONDARY,
            ),
            bottomBand = BandContent(setOfLine(), DialTone.TERTIARY),
            disc = startDisc(),
            tap = DialTap.START_SET,
            // Swap takes the crown only where the peek has nothing to scrub.
            crown = if (swapOffered) DialCrown.SELECT_ALTERNATE else DialCrown.PEEK,
            // A set's start is the one moment on this face where changing lifts costs
            // nothing — nothing is under way to lose (v3 §3).
            swipe = DialSwipe.NEXT_EXERCISE,
            hold = undoHold(),
        )
    }

    /**
     * The lift is waiting on the phone's answer to a swap (#90). The name is already
     * the one the lifter asked for — the client echoes that much — but the rows under
     * it still describe the exercise being replaced, and the replacement's are seeded
     * phone-side from its own GOAL. So the disc goes read-only: DIMMED already means
     * "nothing here to act on", and the alternative is drawing 235 × 5 under a lift
     * nobody prescribed 235 for.
     *
     * The swipe stays live on purpose. Offline the phone may not answer for a while,
     * and the lifter must not be trapped: only the lift they just asked to have
     * replaced is un-loggable, and the rest of the day is one swipe away. The queued
     * count in the top band is what says why ([withQueuedStatus] — the swap is in it).
     */
    fun swapping(): DialUiState = base(
        screen = DialScreen.READY,
        rounds = roundStates,
        arc = null,
        topBand = BandContent(exercise.name.uppercase(), DialTone.SECONDARY),
        bottomBand = BandContent(copy.waitingOnPhone.uppercase(), DialTone.TERTIARY),
        disc = DiscContent(
            style = DiscStyle.DIMMED,
            lines = listOf(
                DiscLine(copy.swapping.uppercase(), DialTextRole.DISC_LABEL, DialTone.PRIMARY),
                DiscLine(exercise.name.uppercase(), DialTextRole.BAND, DialTone.SECONDARY),
            ),
        ),
        tap = DialTap.NONE,
        crown = DialCrown.NONE,
        swipe = DialSwipe.NEXT_EXERCISE,
    )

    /**
     * The alternate under consideration, as the disc reads it (§6): its name, and
     * `USE THIS` for what the tap will do.
     *
     * OUTLINED, not FILLED. Both mean the tap acts, but READY's own disc is a FILLED
     * `START` and these two live one crown-flick apart — a swap preview that looked
     * like the start button is a mis-tap waiting to happen. OUTLINED is also honest
     * on its own terms: a decision in progress, finished by the tap.
     */
    fun swapDisc(alternateIndex: Int): DiscContent = DiscContent(
        style = DiscStyle.OUTLINED,
        lines = listOf(
            DiscLine(
                exercise.alternates[alternateIndex].name.uppercase(),
                DialTextRole.DISC_LABEL,
                DialTone.PRIMARY,
            ),
            DiscLine(copy.useThis.uppercase(), DialTextRole.BAND, DialTone.ACCENT_BRIGHT),
        ),
    )

    fun swapBand(alternateIndex: Int): BandContent = BandContent(
        text = copy.alternateOf(alternateIndex + 1, exercise.alternates.size).uppercase(),
        tone = DialTone.TERTIARY,
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
                DiscLine(copy.tapToLog.uppercase(), DialTextRole.BAND, DialTone.ACCENT_BRIGHT),
            ),
        ),
        tap = DialTap.TICK,
        crown = DialCrown.PEEK,
    )

    fun timedHold(): DialUiState {
        val goal = holdGoalSeconds(exercise, roundIndex)
        val elapsedSeconds = inputs.liftingElapsedMillis / 1000L
        return base(
            screen = DialScreen.TIMED_HOLD,
            rounds = roundStates,
            arc = (elapsedSeconds.toFloat() / goal).coerceIn(0f, 1f),
            topBand = BandContent(
                text = copy.exerciseKind(exercise.name, round?.kindLabel.orEmpty()).uppercase(),
                tone = DialTone.SECONDARY,
            ),
            bottomBand = BandContent(setOfLine(), DialTone.TERTIARY),
            disc = DiscContent(
                style = DiscStyle.OUTLINED,
                lines = listOf(
                    DiscLine(DialFormat.clock(elapsedSeconds), DialTextRole.NUMERAL_LARGE, DialTone.PRIMARY),
                    DiscLine(
                        copy.goal(DialFormat.clock(goal.toLong())).uppercase(),
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
            topBand = BandContent(copy.rest.uppercase(), DialTone.SECONDARY),
            bottomBand = BandContent(copy.tapToSkip.uppercase(), DialTone.TERTIARY),
            disc = DiscContent(
                style = DiscStyle.FLAT,
                lines = listOf(
                    DiscLine(DialFormat.clock(remaining.toLong()), DialTextRole.NUMERAL_LARGE, DialTone.PRIMARY),
                    DiscLine(copy.next(next).uppercase(), DialTextRole.BAND, DialTone.SECONDARY),
                ),
            ),
            tap = DialTap.SKIP_REST,
            crown = DialCrown.PEEK,
        )
    }

    fun restOver(restedSeconds: Int): DialUiState = base(
        screen = DialScreen.REST_OVER,
        rounds = roundStates,
        arc = null,
        topBand = BandContent(
            text = copy.rested(DialFormat.clock(restedSeconds.toLong())).uppercase(),
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
        crown = DialCrown.PEEK,
        swipe = DialSwipe.NEXT_EXERCISE,
        hold = undoHold(),
        bloom = true,
    )

    /**
     * The day is finished (§5.7) — and a mis-tick that lands here has to be
     * recoverable on the wrist, or the last set of the day is the one set the
     * phone alone can fix. So the disc keeps DISMISS on tap and layers the hold
     * on top: one tap target still (redesign §10.3), the same 700ms undo the rest
     * of the flow uses. Finishing stays a tap; accessibility exposes both actions.
     *
     * There is no "lift in front of the lifter" on a finished day — [exerciseIndex]
     * is only whatever [currentExerciseIndex] fell back to — so the fallback search
     * is told where to start outright: the last lift in the day's own order, which
     * is where a day worked in order ends.
     */
    fun dayDone(): DialUiState = base(
        screen = DialScreen.DAY_DONE,
        rounds = emptyList(),
        arc = null,
        topBand = null,
        bottomBand = null,
        disc = DiscContent(
            style = DiscStyle.FILLED_GREEN,
            lines = listOf(DiscLine(copy.done.uppercase(), DialTextRole.NUMERAL, DialTone.ON_DISC)) +
                dayDoneStats().map { DiscLine(it, DialTextRole.BAND, DialTone.ON_DISC) },
        ),
        tap = DialTap.DISMISS,
        hold = undoHold(from = day.exercises.lastIndex),
        dayProgressOverride = 1f,
    )

    /** Optional extra credit after the lifts: still the workout face, never a third face. */
    fun cardioOffer(): DialUiState = base(
        screen = DialScreen.CARDIO_OFFER,
        rounds = emptyList(),
        arc = null,
        topBand = BandContent(copy.cardio.uppercase(), DialTone.ACCENT_BRIGHT),
        bottomBand = null,
        disc = DiscContent(
            DiscStyle.FILLED,
            listOf(
                DiscLine(copy.cardioStart.uppercase(), DialTextRole.DISC_LABEL, DialTone.ON_DISC),
                DiscLine(inputs.snapshot.cardio?.label.orEmpty().uppercase(), DialTextRole.BAND, DialTone.ON_DISC),
            ),
        ),
        tap = DialTap.START_CARDIO,
        // Cardio owns the authored hold slot. Undo is deliberately absent here.
        hold = null,
        dayProgressOverride = 1f,
    )

    fun cardioExecuting(anchors: CardioAnchors): DialUiState {
        val suggestion = requireNotNull(inputs.snapshot.cardio)
        val plan = CardioIntervals.plan(suggestion, suggestion.fiveK ?: false)
        val progress = cardioProgress(plan, anchors, inputs.nowElapsedMillis, inputs.nowWallMillis)
        val step = plan.steps[progress.stepIndex]
        return base(
            screen = if (progress.overrun) DialScreen.CARDIO_OVERRUN else DialScreen.CARDIO_EXECUTING,
            rounds = emptyList(),
            arc = progress.stepRemainingFraction,
            topBand = BandContent(step.label.uppercase(), DialTone.ACCENT_BRIGHT),
            bottomBand = BandContent(copy.cardioHoldToStop.uppercase(), DialTone.TERTIARY),
            disc = DiscContent(
                DiscStyle.FLAT,
                listOf(
                    DiscLine(DialFormat.clock(progress.stepRemainingSeconds.toLong()), DialTextRole.NUMERAL_LARGE, DialTone.PRIMARY),
                    DiscLine(copy.cardio.uppercase(), DialTextRole.BAND, DialTone.SECONDARY),
                ),
            ),
            tap = DialTap.NONE,
            hold = DialHold(
                action = DialHoldAction.STOP_CARDIO,
                disc = DiscContent(DiscStyle.FLAT, listOf(DiscLine(copy.cardioStop.uppercase(), DialTextRole.DISC_LABEL, DialTone.PRIMARY))),
            ),
            dayProgressOverride = 1f,
        )
    }

    /**
     * "38 MIN", "12,450 LB" — real logged work, never a placeholder (§5.7), one
     * line each: joined with a separator they overflow the disc at BAND size,
     * which is the whole reason this returns parts (v2 §4).
     */
    fun dayDoneStats(): List<String> {
        val minutes = DialFormat.wholeMinutes(
            inputs.session.firstStartedAtMillis,
            inputs.session.lastCompletedAtMillis,
        )
        val volume = sessionVolume(day.exercises, unit)
        val parts = buildList {
            if (minutes > 0) add(copy.minutes(minutes))
            if (volume > 0) add(copy.volume(DialFormat.grouped(volume), unit.name))
        }
        return parts.ifEmpty { listOf(copy.sets(doneSetCount)) }.map { it.uppercase() }
    }

    /**
     * The undo a 700ms hold offers, or null when nothing of the day is logged (§6).
     * One confident tap locks a set in — this is the deliberate way back out, and
     * it is offered on the three screens where the lifter is *between* efforts and
     * nothing is under way to lose: the two between sets, and the finished day.
     *
     * The target is the day's most recent tick, not this lift's, because the two
     * ticks hardest to take back are exactly the ones that move the dial off the
     * lift they belong to — a lift's final set, and the day's. When it does reach
     * across, the disc names the lift as well: "SET 3" alone would read as a round
     * of what is on screen. That name is disclosure, not correctness — it says
     * which set is about to go, it does not excuse picking the wrong one.
     *
     * [from] is where the lifter is looking, which only matters when the memory
     * has nothing live to offer and the search falls back to position.
     */
    fun undoHold(from: Int = exerciseIndex): DialHold? {
        val target = UndoTarget.of(day.exercises, from, inputs.tickMemory) ?: return null
        val liftName = day.exercises[target.exerciseIndex].name
            .takeIf { target.exerciseIndex != exerciseIndex }
        return DialHold(
            action = DialHoldAction.UNDO,
            target = target,
            disc = DiscContent(
                style = DiscStyle.FLAT,
                lines = listOfNotNull(
                    DiscLine(copy.undo.uppercase(), DialTextRole.DISC_LABEL, DialTone.PRIMARY),
                    liftName?.let { DiscLine(it.uppercase(), DialTextRole.BAND, DialTone.SECONDARY) },
                    DiscLine(copy.set(target.roundIndex + 1).uppercase(), DialTextRole.BAND, DialTone.SECONDARY),
                ),
            ),
        )
    }

    /** The peeked round as the disc reads it: the result, and how long it took. */
    fun peekDisc(roundIndex: Int): DiscContent = DiscContent(
        style = DiscStyle.DIMMED,
        lines = listOfNotNull(
            DiscLine(
                stream.rounds.getOrNull(roundIndex)?.let(::roundLabel).orEmpty(),
                DialTextRole.DISC_LABEL,
                DialTone.PRIMARY,
            ),
            inputs.tickMemory.secondsFor(exercise.programExerciseId, roundIndex)?.let {
                DiscLine(
                    copy.took(DialFormat.clock(it.toLong())).uppercase(),
                    DialTextRole.BAND,
                    DialTone.SECONDARY,
                )
            },
        ),
    )

    fun peekedRoundStates(peekedIndex: Int): List<RoundState> =
        DialGeometry.roundStates(exercise.sets.map { it.done }, roundIndex, peekedIndex)

    private fun startDisc() = DiscContent(
        style = DiscStyle.FILLED,
        lines = listOf(
            DiscLine(copy.start.uppercase(), DialTextRole.DISC_LABEL, DialTone.ON_DISC),
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
     * the set position — the line READY and the timed hold already carry, and the
     * only thing on this screen that says which set is under way.
     *
     * What it no longer carries is `HOLD TO UNDO`. A set in progress hasn't been
     * locked in, so there is nothing here to take back: [undoHold] is offered on
     * READY, REST_OVER and DAY_DONE and nowhere else, and this band was
     * advertising a gesture that did nothing where it was written (#151, from the
     * v1 brief's §5.3 screen list). The offer is the honest half and it stays put;
     * the hint goes, because an undo is deliberate by design and the three screens
     * that do offer it announce it with nothing at all.
     */
    private fun liftingBottomBand(): BandContent {
        val partner = round?.partner
        return if (stream.partnerName != null && partner != null) {
            BandContent(
                text = copy.thenPartner(stream.partnerName, partner.summaryDisplay).uppercase(),
                tone = DialTone.SECONDARY,
            )
        } else {
            BandContent(setOfLine(), DialTone.TERTIARY)
        }
    }
}

/**
 * Crown-peek, laid over whatever screen the lifter is actually on (§6): the ring
 * grows a second, white marker where they're looking, the disc dims to read-only
 * and shows that round's result, and the tap goes away — browsing must never be
 * one slip away from logging.
 *
 * A peek during a rest keeps the countdown: the white marker lives on the exercise
 * ring, the clock has a ring of its own on the disc rim (v2 §3), and neither has to
 * give way for the other. The clock dims with the disc, so the centre still reads
 * as one read-only object.
 */
private fun DialUiState.withPeek(
    context: ScreenContext,
    peekRoundIndex: Int?,
): DialUiState {
    if (peekRoundIndex == null || crown != DialCrown.PEEK || rounds.isEmpty()) return this
    val index = peekRoundIndex.coerceIn(rounds.indices)
    return copy(
        rounds = context.peekedRoundStates(index),
        bottomBand = BandContent(context.copy.releaseToReturn.uppercase(), DialTone.TERTIARY),
        disc = context.peekDisc(index),
        bloom = false,
        tap = DialTap.NONE,
        swipe = DialSwipe.NONE,
        hold = null,
    )
}

/**
 * The Swap layer (§6), laid over the READY disc the same way the peek is: the rings
 * don't move — the day hasn't changed — the disc names the alternate the crown is
 * proposing, and the bottom band counts the list off. The undo hold and the
 * next-exercise swipe both stand down while a decision is on the disc, so the one
 * gesture that means anything here is the tap that confirms it.
 *
 * Guarded on [DialCrown.SELECT_ALTERNATE], so it can only ever appear where the
 * offer rule already put the crown ([ScreenContext.swapOffered]) — never during a
 * peek, never on a lift with work logged against it, never on a finished day.
 */
private fun DialUiState.withSwapPreview(
    context: ScreenContext,
    alternateIndex: Int?,
): DialUiState {
    if (crown != DialCrown.SELECT_ALTERNATE) return this
    // The same resolution the confirm uses, so the disc can never name one alternate
    // while the tap acts on another (or on none).
    val index = SwapPicker.resolve(alternateIndex, context.exercise.alternates.size) ?: return this
    return copy(
        bottomBand = context.swapBand(index),
        disc = context.swapDisc(index),
        bloom = false,
        tap = DialTap.CONFIRM_SWAP,
        swipe = DialSwipe.NONE,
        hold = null,
    )
}

/**
 * Offline status lives in the top band and nowhere else (§7) — except while the
 * lifter is under the bar, where the band is carrying the live elapsed timer and
 * a queue that will flush itself is not worth taking it for.
 */
private fun DialUiState.withQueuedStatus(pendingCount: Int, copy: DialCopy): DialUiState {
    if (pendingCount <= 0 || screen == DialScreen.LIFTING || screen == DialScreen.DAY_DONE) return this
    return copy(
        topBand = BandContent(
            text = copy.queued(pendingCount).uppercase(),
            tone = DialTone.SECONDARY,
            dotTone = DialTone.SECONDARY,
        ),
    )
}

/** The phone hasn't generated a program yet (§7): a dashed disc and nothing to act on. */
private fun noProgram(snapshot: WatchSnapshot, copy: DialCopy) = DialUiState(
    screen = DialScreen.OVERVIEW,
    accentIndex = snapshot.day.accentIndex,
    cycle = cycleSegments(snapshot),
    dayProgress = 0f,
    rounds = emptyList(),
    arc = null,
    topBand = null,
    bottomBand = null,
    disc = DiscContent(
        style = DiscStyle.DASHED,
        lines = listOf(
            DiscLine(copy.noProgram.uppercase(), DialTextRole.DISC_LABEL_SMALL, DialTone.PRIMARY),
            DiscLine(copy.setUpOnPhone.uppercase(), DialTextRole.BAND_SECONDARY, DialTone.TERTIARY),
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
