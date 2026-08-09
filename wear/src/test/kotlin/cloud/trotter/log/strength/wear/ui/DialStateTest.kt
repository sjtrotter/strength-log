package cloud.trotter.log.strength.wear.ui

import cloud.trotter.log.strength.domain.sync.WatchCycleDay
import cloud.trotter.log.strength.domain.sync.WatchCycleExercise
import cloud.trotter.log.strength.domain.sync.WatchAlternate
import cloud.trotter.log.strength.domain.sync.WatchDay
import cloud.trotter.log.strength.domain.sync.WatchExercise
import cloud.trotter.log.strength.domain.sync.WatchSet
import cloud.trotter.log.strength.domain.sync.WatchSnapshot
import cloud.trotter.log.strength.domain.units.WeightUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The two faces (v3 §3), the screens within them (brief §5) and the transitions
 * between them, as a pure mapping. This is the watch's only device-free
 * verification of the workout flow, so each screen gets its own case and each
 * transition is a state pair rather than a click.
 */
class DialStateTest {

    @Test
    fun `every dial action has distinct terse accessibility copy`() {
        val actions = DialTap.entries.filterNot { it == DialTap.NONE }
        val labels = actions.map { it.accessibilityClickLabel }

        assertTrue(labels.all { !it.isNullOrBlank() })
        assertEquals(labels.size, labels.distinct().size)
        assertNull(DialTap.NONE.accessibilityClickLabel)
    }

    // --- fixtures ----------------------------------------------------------------

    private fun set(
        weight: Double = 235.0,
        reps: Int = 5,
        kind: String = "TOP",
        done: Boolean = false,
        seconds: Int = 0,
        rest: Int = 150,
    ) = WatchSet(weight, reps, kind, done, seconds, rest)

    private fun exercise(
        id: Long,
        name: String,
        sets: List<WatchSet>,
        tracking: String = "weighted",
        partnerName: String? = null,
        ssSets: List<WatchSet> = emptyList(),
        alternates: List<WatchAlternate> = emptyList(),
    ) = WatchExercise(
        programExerciseId = id,
        slot = "main",
        name = name,
        goal = 235.0,
        perHand = false,
        supersetPartnerName = partnerName,
        sets = sets,
        ssSets = ssSets,
        goalLabel = "235",
        tracking = tracking,
        alternates = alternates,
    )

    private fun snapshot(vararg exercises: WatchExercise) = WatchSnapshot(
        revision = 1L,
        suggestedDayId = "A",
        day = WatchDay(
            dayId = "A",
            title = "Lower",
            // Realistically long on purpose: the band must never show this —
            // an arc reading "FLAT PRE…" was the on-wrist bug this pins.
            emphasisLine = "flat press · vertical pull · hinge · quad",
            accentIndex = 0,
            exercises = exercises.toList(),
        ),
        unit = "lb",
        cycle = listOf(
            WatchCycleDay("A", "Lower", listOf(WatchCycleExercise("Squat", 3))),
            WatchCycleDay("B", "Upper", listOf(WatchCycleExercise("Bench", 4), WatchCycleExercise("Row", 3))),
            WatchCycleDay("C", "Full Body", listOf(WatchCycleExercise("Deadlift", 2))),
        ),
    )

    private val squat = exercise(1L, "Squat", List(3) { set() })
    private val press = exercise(2L, "Press", List(2) { set(weight = 120.0, reps = 8, kind = "WORK") })
    private val day = snapshot(squat, press)

    private fun inputs(
        snapshot: WatchSnapshot = day,
        exerciseIndex: Int = currentExerciseIndex(snapshot),
        phase: SetPhase = SetPhase.READY,
        face: DialFace = DialFace.WORKOUT,
        pendingCount: Int = 0,
        rest: RestState? = null,
        restedSeconds: Int? = null,
        liftingElapsedMillis: Long = 0L,
        nowElapsedMillis: Long = 0L,
        session: SessionStamps = SessionStamps(),
        browseDayIndex: Int? = null,
        peekRoundIndex: Int? = null,
        swapAlternateIndex: Int? = null,
        pendingSwapExerciseIds: Set<Long> = emptySet(),
        tickMemory: TickMemory = TickMemory.EMPTY,
    ) = DialInputs(
        snapshot = snapshot,
        exerciseIndex = exerciseIndex,
        phase = phase,
        face = face,
        pendingCount = pendingCount,
        rest = rest,
        restedSeconds = restedSeconds,
        liftingElapsedMillis = liftingElapsedMillis,
        nowElapsedMillis = nowElapsedMillis,
        session = session,
        browseDayIndex = browseDayIndex,
        peekRoundIndex = peekRoundIndex,
        swapAlternateIndex = swapAlternateIndex,
        pendingSwapExerciseIds = pendingSwapExerciseIds,
        tickMemory = tickMemory,
    )

    private fun DialUiState.discText(): List<String> =
        disc.lines.map { line -> line.spans.joinToString(" ") { it.text } }

    // --- 1 · Overview (v3 §3) -----------------------------------------------------

    private fun overview(
        snapshot: WatchSnapshot = day,
        browseDayIndex: Int? = null,
        rest: RestState? = null,
        nowElapsedMillis: Long = 0L,
    ) = dialUiState(
        inputs(
            snapshot = snapshot,
            face = DialFace.OVERVIEW,
            browseDayIndex = browseDayIndex,
            rest = rest,
            nowElapsedMillis = nowElapsedMillis,
        ),
    )

    @Test
    fun `the overview shows the day with one segment per exercise and one way in`() {
        val state = overview()
        assertEquals(DialScreen.OVERVIEW, state.screen)
        assertEquals(0f, state.dayProgress)
        assertEquals(2, state.rounds.size) // exercises, not sets
        assertEquals(1, state.rounds.count { it == RoundState.CURRENT })
        // The day's identity is on the ring now, so the band is the title alone.
        assertEquals("LOWER", state.topBand?.text)
        assertEquals("2 LIFTS · 5 SETS", state.bottomBand?.text)
        assertEquals(DiscStyle.FILLED, state.disc.style)
        assertEquals(listOf("START", "SQUAT"), state.discText())
        assertEquals(DialTap.OPEN_WORKOUT, state.tap)
        assertEquals(DialSwipe.BROWSE_DAY, state.swipe)
    }

    @Test
    fun `the overview says CONTINUE, and where, once anything is logged`() {
        val started = snapshot(squat.copy(sets = listOf(set(done = true)) + squat.sets.drop(1)), press)
        assertEquals(listOf("CONTINUE", "SET 2 OF 3"), overview(started).discText())
    }

    @Test
    fun `the tap into the workout lands on the set in front of the lifter`() {
        val opened = dialUiState(inputs(face = DialFace.WORKOUT))
        assertEquals(DialScreen.READY, opened.screen)
        assertEquals(DialTap.START_SET, opened.tap)
    }

    @Test
    fun `a cold launch opens on the face the day implies`() {
        assertEquals(DialFace.OVERVIEW, impliedFace(day))
        val started = snapshot(squat.copy(sets = listOf(set(done = true)) + squat.sets.drop(1)), press)
        assertEquals(DialFace.WORKOUT, impliedFace(started))
    }

    @Test
    fun `the overview keeps a running rest on the clock ring`() {
        val rest = RestState(deadlineElapsedMillis = 90_000L, totalSeconds = 150, betweenExercises = false)
        val state = overview(rest = rest, nowElapsedMillis = 6_000L)
        assertEquals(DialScreen.OVERVIEW, state.screen)
        assertEquals(84f / 150f, state.arc!!)
    }

    // --- the cycle ring (v3 §1) ---------------------------------------------------

    @Test
    fun `the ring is the program's days in order, today's in the accent`() {
        val cycle = dialUiState(inputs()).cycle
        assertEquals(listOf("A", "B", "C"), cycle.map { it.dayLabel })
        assertEquals(listOf(0, 1, 2), cycle.map { it.accentIndex }) // accent by position
        assertEquals(listOf(CycleMark.TODAY, CycleMark.OTHER, CycleMark.OTHER), cycle.map { it.mark })
    }

    @Test
    fun `the ring stays the same on both faces and through the day's end`() {
        val screens = listOf(
            overview(),
            dialUiState(inputs()),
            dialUiState(inputs(phase = SetPhase.LIFTING)),
            dialUiState(inputs(snapshot = allDone())),
        )
        screens.forEach { assertEquals(3, it.cycle.size, "${it.screen} lost the cycle ring") }
    }

    @Test
    fun `a phone that publishes no cycle still gets a ring — one segment, today`() {
        val old = day.copy(cycle = emptyList())
        val cycle = dialUiState(inputs(snapshot = old)).cycle
        assertEquals(1, cycle.size)
        assertEquals(CycleMark.TODAY, cycle.single().mark)
        assertEquals("A", cycle.single().dayLabel)
        assertEquals(0, cycle.single().accentIndex)
    }

    @Test
    fun `a cycle that has lost today falls back rather than losing the accent`() {
        val stale = day.copy(cycle = day.cycle.drop(1))
        assertEquals(listOf(CycleMark.TODAY), dialUiState(inputs(snapshot = stale)).cycle.map { it.mark })
    }

    // --- day browse (v3 §3) -------------------------------------------------------

    @Test
    fun `browsing another day is read-only, in that day's own colours`() {
        val state = overview(browseDayIndex = 1)
        assertEquals(DialScreen.OVERVIEW, state.screen)
        assertEquals(1, state.accentIndex) // day B's accent, not today's
        assertEquals("UPPER", state.topBand?.text)
        assertEquals("2 LIFTS · 7 SETS", state.bottomBand?.text)
        assertEquals(2, state.rounds.size) // one segment per lift of the browsed day
        assertTrue(state.rounds.all { it == RoundState.UPCOMING })
        assertEquals(DiscStyle.DIMMED, state.disc.style)
        assertEquals(listOf("DAY B", "BENCH"), state.discText())
        assertEquals(DialTap.NONE, state.tap)
        assertEquals(DialCrown.NONE, state.crown)
        assertEquals(DialSwipe.BROWSE_DAY, state.swipe)
    }

    @Test
    fun `the browsed day is marked white while the accent stays on today`() {
        val cycle = overview(browseDayIndex = 2).cycle
        assertEquals(listOf(CycleMark.TODAY, CycleMark.OTHER, CycleMark.BROWSED), cycle.map { it.mark })
        assertEquals(1, cycle.count { it.mark == CycleMark.TODAY })
    }

    @Test
    fun `today's progress keeps riding today's segment while another day is browsed`() {
        val partly = snapshot(squat.copy(sets = listOf(set(done = true), set(), set())), press)
        assertEquals(1f / 5f, overview(partly, browseDayIndex = 1).dayProgress)
    }

    @Test
    fun `a browse position the program no longer has falls back to the live overview`() {
        val state = overview(browseDayIndex = 9)
        assertEquals(DiscStyle.FILLED, state.disc.style)
        assertEquals(DialTap.OPEN_WORKOUT, state.tap)
    }

    @Test
    fun `browsing is an overview gesture — the workout face never sees it`() {
        val state = dialUiState(inputs(face = DialFace.WORKOUT, browseDayIndex = 1))
        assertEquals(DialScreen.READY, state.screen)
        assertEquals(0, state.accentIndex)
        assertEquals(listOf(CycleMark.TODAY, CycleMark.OTHER, CycleMark.OTHER), state.cycle.map { it.mark })
    }

    // --- 2 · Ready ---------------------------------------------------------------

    @Test
    fun `ready offers the set in front of the lifter`() {
        val state = dialUiState(inputs())
        assertEquals(DialScreen.READY, state.screen)
        assertEquals("SQUAT · TOP", state.topBand?.text)
        assertEquals("SET 1 OF 3", state.bottomBand?.text)
        assertEquals(DiscStyle.FILLED, state.disc.style)
        assertEquals(listOf("START", "235 × 5"), state.discText())
        assertEquals(DialTap.START_SET, state.tap)
        assertEquals(listOf(RoundState.CURRENT, RoundState.UPCOMING, RoundState.UPCOMING), state.rounds)
    }

    @Test
    fun `the day ring is the day's logged proportion`() {
        val partly = snapshot(squat.copy(sets = listOf(set(done = true), set(), set())), press)
        assertEquals(1f / 5f, dialUiState(inputs(snapshot = partly)).dayProgress)
    }

    // --- 3 · Lifting -------------------------------------------------------------

    @Test
    fun `lifting hollows the disc and puts the elapsed timer in the top band`() {
        val state = dialUiState(inputs(phase = SetPhase.LIFTING, liftingElapsedMillis = 47_400L))
        assertEquals(DialScreen.LIFTING, state.screen)
        assertEquals(DiscStyle.OUTLINED, state.disc.style)
        assertEquals("0:47", state.topBand?.text)
        assertNotNull(state.topBand?.dotTone)
        assertEquals(listOf("235 ×5", "TAP TO LOG"), state.discText())
        // The band says which set is under way. It used to say HOLD TO UNDO, on a
        // screen that offers no hold (#151).
        assertEquals("SET 1 OF 3", state.bottomBand?.text)
        assertEquals(DialTap.TICK, state.tap)
        // The elapsed timer is never near the disc numeral (§5.3).
        assertTrue(state.disc.lines.none { line -> line.spans.any { it.text == "0:47" } })
    }

    @Test
    fun `a superset round shows the partner's prescription while lifting`() {
        val curl = exercise(
            id = 3L,
            name = "Bench",
            sets = List(2) { set(weight = 185.0) },
            partnerName = "Curl",
            ssSets = List(2) { set(weight = 50.0, reps = 12) },
        )
        val state = dialUiState(inputs(snapshot = snapshot(curl), phase = SetPhase.LIFTING))
        assertEquals("THEN CURL 50×12", state.bottomBand?.text)
        // One tick still logs the whole round.
        assertEquals(DialTap.TICK, state.tap)
    }

    // --- 4 · Rest ----------------------------------------------------------------

    @Test
    fun `rest drains the clock ring and offers a skip`() {
        val rest = RestState(deadlineElapsedMillis = 90_000L, totalSeconds = 150, betweenExercises = false)
        val state = dialUiState(inputs(rest = rest, nowElapsedMillis = 6_000L))
        assertEquals(DialScreen.REST, state.screen)
        assertEquals(84f / 150f, state.arc!!)
        assertEquals("REST", state.topBand?.text)
        assertEquals(DiscStyle.FLAT, state.disc.style)
        assertEquals(listOf("1:24", "NEXT 235 × 5"), state.discText())
        assertEquals("TAP TO SKIP", state.bottomBand?.text)
        assertEquals(DialTap.SKIP_REST, state.tap)
        // The clock is its own ring, so the rounds stay countable through the rest.
        assertEquals(3, state.rounds.size)
        assertEquals(1, state.rounds.count { it == RoundState.CURRENT })
    }

    @Test
    fun `a between-exercise rest names the next exercise`() {
        val squatDone = squat.copy(sets = squat.sets.map { it.copy(done = true) })
        val rest = RestState(deadlineElapsedMillis = 60_000L, totalSeconds = 120, betweenExercises = true)
        val state = dialUiState(inputs(snapshot = snapshot(squatDone, press), rest = rest))
        assertEquals(DialScreen.REST, state.screen)
        assertTrue(state.discText()[1].contains("NEXT PRESS"))
    }

    @Test
    fun `an expired rest is not a rest`() {
        val rest = RestState(deadlineElapsedMillis = 10_000L, totalSeconds = 150, betweenExercises = false)
        assertEquals(DialScreen.READY, dialUiState(inputs(rest = rest, nowElapsedMillis = 10_000L)).screen)
    }

    // --- 5 · Rest over -----------------------------------------------------------

    @Test
    fun `rest over is a state change, not a new screen`() {
        val state = dialUiState(inputs(restedSeconds = 150))
        assertEquals(DialScreen.REST_OVER, state.screen)
        assertEquals("✓ RESTED 2:30", state.topBand?.text)
        assertEquals(DiscStyle.FILLED, state.disc.style)
        assertEquals(listOf("START", "235 × 5"), state.discText())
        assertEquals("SET 1 OF 3 · TOP", state.bottomBand?.text)
        assertTrue(state.bloom)
        assertEquals(DialTap.START_SET, state.tap)
    }

    @Test
    fun `starting the next set clears the rested badge`() {
        val state = dialUiState(inputs(restedSeconds = 150, phase = SetPhase.LIFTING))
        assertEquals(DialScreen.LIFTING, state.screen)
        assertFalse(state.bloom)
    }

    // --- 6 · Timed hold ----------------------------------------------------------

    @Test
    fun `a timed hold counts up toward its goal and fills the ring`() {
        val plank = exercise(
            id = 4L,
            name = "Plank",
            sets = List(3) { set(weight = 0.0, reps = 0, seconds = 45, kind = "WORK") },
            tracking = "timed",
        )
        val state = dialUiState(
            inputs(snapshot = snapshot(plank), phase = SetPhase.LIFTING, liftingElapsedMillis = 28_000L),
        )
        assertEquals(DialScreen.TIMED_HOLD, state.screen)
        assertEquals(DiscStyle.OUTLINED, state.disc.style)
        assertEquals(listOf("0:28", "GOAL 0:45"), state.discText())
        assertEquals(28f / 45f, state.arc!!)
        assertEquals("SET 1 OF 3", state.bottomBand?.text)
        assertEquals(DialTap.TICK, state.tap)
        // The exercise ring is untouched by the fill — the clock has its own ring.
        assertEquals(3, state.rounds.size)
        assertEquals(1, state.rounds.count { it == RoundState.CURRENT })
    }

    // --- the clock ring nests, it never transforms another ring (v2 §3) -----------

    @Test
    fun `every screen but day done keeps a countable exercise ring`() {
        val rest = RestState(deadlineElapsedMillis = 90_000L, totalSeconds = 150, betweenExercises = false)
        val plank = exercise(
            id = 4L,
            name = "Plank",
            sets = List(3) { set(weight = 0.0, reps = 0, seconds = 45, kind = "WORK") },
            tracking = "timed",
        )
        val screens = listOf(
            inputs(face = DialFace.OVERVIEW),
            inputs(),
            inputs(phase = SetPhase.LIFTING),
            inputs(rest = rest, nowElapsedMillis = 6_000L),
            inputs(restedSeconds = 150),
            inputs(snapshot = snapshot(plank), phase = SetPhase.LIFTING, liftingElapsedMillis = 28_000L),
        ).map(::dialUiState)
        screens.forEach { assertTrue(it.rounds.isNotEmpty(), "${it.screen} lost its exercise ring") }
        assertTrue(dialUiState(inputs(snapshot = allDone())).rounds.isEmpty())
    }

    @Test
    fun `a clock ring exists exactly when a clock is running`() {
        val rest = RestState(deadlineElapsedMillis = 90_000L, totalSeconds = 150, betweenExercises = false)
        val plank = exercise(
            id = 4L,
            name = "Plank",
            sets = List(3) { set(weight = 0.0, reps = 0, seconds = 45, kind = "WORK") },
            tracking = "timed",
        )
        assertNotNull(dialUiState(inputs(rest = rest, nowElapsedMillis = 6_000L)).arc)
        assertNotNull(
            dialUiState(
                inputs(snapshot = snapshot(plank), phase = SetPhase.LIFTING, liftingElapsedMillis = 28_000L),
            ).arc,
        )
        // The screens that offer an undo carry no arc, so the two never share the rim.
        assertNull(dialUiState(inputs()).arc)
        assertNull(dialUiState(inputs(restedSeconds = 150)).arc)
        assertNull(dialUiState(inputs(face = DialFace.OVERVIEW)).arc)
        assertNull(dialUiState(inputs(phase = SetPhase.LIFTING)).arc)
    }

    @Test
    fun `only a TIMED track has a hold goal`() {
        val plank = exercise(4L, "Plank", listOf(set(seconds = 45)), tracking = "timed")
        assertEquals(45, holdGoalSeconds(plank, 0))
        assertEquals(0, holdGoalSeconds(squat, 0))
    }

    // --- 7 · Day done ------------------------------------------------------------

    @Test
    fun `day done drops the inner ring and closes the outer one`() {
        val state = dialUiState(inputs(snapshot = allDone()))
        assertEquals(DialScreen.DAY_DONE, state.screen)
        assertEquals(1f, state.dayProgress)
        assertTrue(state.rounds.isEmpty())
        assertNull(state.arc)
        assertEquals(DiscStyle.FILLED_GREEN, state.disc.style)
        assertEquals("✓ SYNCED · 5 SETS", state.bottomBand?.text)
        assertEquals(DialTap.DISMISS, state.tap)
    }

    @Test
    fun `day-done stats come from the session's own timestamps and logged weight`() {
        val state = dialUiState(
            inputs(
                snapshot = allDone(),
                session = SessionStamps(
                    firstStartedAtMillis = 1_000_000L,
                    lastCompletedAtMillis = 1_000_000L + 38 * 60_000L,
                ),
            ),
        )
        // 3 × 235 × 5 + 2 × 120 × 8 = 3525 + 1920 = 5445
        // One stat per line: joined, they don't fit the disc at BAND size (v2 §4).
        assertEquals(listOf("DONE", "38 MIN", "5,445 LB"), state.discText())
    }

    @Test
    fun `day-done stats fall back to the set count when the watch logged none of it`() {
        val state = dialUiState(inputs(snapshot = allDone(weight = 0.0)))
        assertEquals(listOf("DONE", "5 SETS"), state.discText())
    }

    @Test
    fun `day done reports the queue instead of a sync it hasn't had`() {
        val state = dialUiState(inputs(snapshot = allDone(), pendingCount = 2))
        assertEquals("2 QUEUED · 5 SETS", state.bottomBand?.text)
    }

    private fun allDone(weight: Double = 235.0) = snapshot(
        squat.copy(sets = List(3) { set(weight = weight, done = true) }),
        press.copy(sets = List(2) { set(weight = if (weight == 0.0) 0.0 else 120.0, reps = 8, done = true) }),
    )

    // --- transitions -------------------------------------------------------------

    @Test
    fun `START keeps the rings still and only changes the disc and top band`() {
        val ready = dialUiState(inputs())
        val lifting = dialUiState(inputs(phase = SetPhase.LIFTING))
        assertEquals(ready.rounds, lifting.rounds)
        assertEquals(ready.dayProgress, lifting.dayProgress)
        assertEquals(DiscStyle.FILLED, ready.disc.style)
        assertEquals(DiscStyle.OUTLINED, lifting.disc.style)
    }

    @Test
    fun `the tick advances the accent segment to the next round`() {
        val ticked = snapshot(squat.copy(sets = listOf(set(done = true), set(), set())), press)
        val state = dialUiState(inputs(snapshot = ticked))
        assertEquals(listOf(RoundState.DONE, RoundState.CURRENT, RoundState.UPCOMING), state.rounds)
        assertEquals("SET 2 OF 3", state.bottomBand?.text)
    }

    @Test
    fun `finishing an exercise moves the dial to the next one`() {
        val squatDone = squat.copy(sets = squat.sets.map { it.copy(done = true) })
        val snap = snapshot(squatDone, press)
        assertEquals(1, currentExerciseIndex(snap))
        assertEquals("PRESS · 1", dialUiState(inputs(snapshot = snap)).topBand?.text)
    }

    @Test
    fun `the last exercise's last round still resolves to a round index`() {
        assertEquals(2, currentRoundIndex(squat.copy(sets = squat.sets.map { it.copy(done = true) })))
    }

    // --- status ------------------------------------------------------------------

    @Test
    fun `a queued edit shows in the top band and nowhere else`() {
        val state = dialUiState(inputs(pendingCount = 2))
        assertEquals("2 QUEUED", state.topBand?.text)
        assertNotNull(state.topBand?.dotTone)
        assertEquals("SET 1 OF 3", state.bottomBand?.text)
    }

    @Test
    fun `a queue never takes the band the elapsed timer is using`() {
        val state = dialUiState(inputs(phase = SetPhase.LIFTING, pendingCount = 2, liftingElapsedMillis = 5_000L))
        assertEquals("0:05", state.topBand?.text)
    }

    @Test
    fun `no program means nothing to act on`() {
        val state = dialUiState(inputs(snapshot = snapshot()))
        assertEquals(DiscStyle.DASHED, state.disc.style)
        assertEquals(DialTap.NONE, state.tap)
    }

    // --- crown: peek (§6) ---------------------------------------------------------

    @Test
    fun `the crown picks exercises on today and rounds inside a session`() {
        assertEquals(DialCrown.SELECT_EXERCISE, dialUiState(inputs(face = DialFace.OVERVIEW)).crown)
        assertEquals(DialCrown.PEEK, dialUiState(inputs()).crown)
        assertEquals(DialCrown.PEEK, dialUiState(inputs(phase = SetPhase.LIFTING)).crown)
        assertEquals(DialCrown.PEEK, dialUiState(inputs(restedSeconds = 150)).crown)
        assertEquals(DialCrown.NONE, dialUiState(inputs(snapshot = allDone())).crown)
    }

    @Test
    fun `swap is offered only on an untouched ready lift with alternates`() {
        val alternates = listOf(WatchAlternate("front_squat", "Front Squat"))
        val offered = squat.copy(alternates = alternates)
        assertEquals(DialCrown.SELECT_ALTERNATE, dialUiState(inputs(snapshot = snapshot(offered, press))).crown)

        val logged = offered.copy(sets = listOf(set(done = true), set(), set()))
        assertEquals(DialCrown.PEEK, dialUiState(inputs(snapshot = snapshot(logged, press))).crown)
        assertEquals(DialCrown.PEEK, dialUiState(inputs(snapshot = snapshot(squat, press))).crown)
    }

    @Test
    fun `swap is not offered away from ready`() {
        val offered = squat.copy(alternates = listOf(WatchAlternate("front_squat", "Front Squat")))
        val snap = snapshot(offered, press)
        val rest = RestState(deadlineElapsedMillis = 90_000L, totalSeconds = 150, betweenExercises = false)
        assertEquals(DialCrown.PEEK, dialUiState(inputs(snapshot = snap, restedSeconds = 150)).crown)
        assertEquals(DialCrown.PEEK, dialUiState(inputs(snapshot = snap, phase = SetPhase.LIFTING)).crown)
        assertEquals(DialCrown.PEEK, dialUiState(inputs(snapshot = snap, rest = rest, nowElapsedMillis = 6_000L)).crown)

        val done = allDone().let { it.copy(day = it.day.copy(exercises = it.day.exercises.map { ex -> ex.copy(alternates = offered.alternates) })) }
        val dayDone = dialUiState(inputs(snapshot = done))
        assertEquals(DialScreen.DAY_DONE, dayDone.screen)
        assertTrue(dayDone.crown != DialCrown.SELECT_ALTERNATE)
        assertEquals(DialCrown.SELECT_EXERCISE, dialUiState(inputs(snapshot = snap, face = DialFace.OVERVIEW)).crown)
    }

    @Test
    fun `swap preview names and counts the selected alternate without moving the rings`() {
        val alternatives = listOf(
            WatchAlternate("front_squat", "Front Squat"),
            WatchAlternate("goblet_squat", "Goblet Squat"),
        )
        val snap = snapshot(squat.copy(alternates = alternatives), press)
        val ready = dialUiState(inputs(snapshot = snap))
        val first = dialUiState(inputs(snapshot = snap, swapAlternateIndex = 0))
        assertEquals(DialTap.CONFIRM_SWAP, first.tap)
        assertTrue(first.discText().any { "FRONT SQUAT" in it })
        assertTrue(first.discText().any { "USE THIS" in it })
        assertEquals("1 OF 2 ALTERNATES", first.bottomBand?.text)
        assertNull(first.hold)
        assertEquals(DialSwipe.NONE, first.swipe)
        assertEquals(DiscStyle.OUTLINED, first.disc.style)
        assertEquals(ready.rounds, first.rounds)

        val second = dialUiState(inputs(snapshot = snap, swapAlternateIndex = 1))
        assertTrue(second.discText().any { "GOBLET SQUAT" in it })
        assertEquals("2 OF 2 ALTERNATES", second.bottomBand?.text)
    }

    /** A snapshot can shorten the prescription while a preview is up. The disc must
     *  keep naming a real alternate — the last one left — rather than going blank or
     *  offering a confirm that would act on nothing. */
    @Test
    fun `a preview that outlived its alternate names the last one left`() {
        val shrunk = squat.copy(alternates = listOf(WatchAlternate("front_squat", "Front Squat")))
        val state = dialUiState(inputs(snapshot = snapshot(shrunk, press), swapAlternateIndex = 2))

        assertEquals(DialTap.CONFIRM_SWAP, state.tap)
        assertTrue(state.discText().any { "FRONT SQUAT" in it })
        assertEquals("1 OF 1 ALTERNATES", state.bottomBand?.text)
    }

    @Test
    fun `swap preview is ignored when the crown is peeking`() {
        val logged = squat.copy(
            sets = listOf(set(done = true), set(), set()),
            alternates = listOf(WatchAlternate("front_squat", "Front Squat")),
        )
        val normal = dialUiState(inputs(snapshot = snapshot(logged, press)))
        val preview = dialUiState(inputs(snapshot = snapshot(logged, press), swapAlternateIndex = 0))
        assertEquals(normal.disc, preview.disc)
        assertEquals(DialTap.START_SET, preview.tap)
    }

    @Test
    fun `a pending swap makes only its lift read only without trapping the lifter`() {
        val offered = squat.copy(alternates = listOf(WatchAlternate("front_squat", "Front Squat")))
        val snap = snapshot(offered, press)
        val state = dialUiState(inputs(snapshot = snap, pendingSwapExerciseIds = setOf(1L)))
        assertEquals(DialScreen.READY, state.screen)
        assertEquals(DiscStyle.DIMMED, state.disc.style)
        assertTrue(state.discText().any { "SWAPPING" in it })
        assertEquals(DialTap.NONE, state.tap)
        assertEquals(DialCrown.NONE, state.crown)
        assertEquals(DialSwipe.NEXT_EXERCISE, state.swipe)

        val other = dialUiState(inputs(snapshot = snap, pendingSwapExerciseIds = setOf(2L)))
        assertEquals(DialCrown.SELECT_ALTERNATE, other.crown)
        assertEquals(DialTap.START_SET, other.tap)
    }

    @Test
    fun `a peek is read-only, white where you're looking and accent where you are`() {
        val logged = snapshot(
            squat.copy(sets = listOf(set(done = true), set(done = true), set())),
            press,
        )
        val state = dialUiState(
            inputs(snapshot = logged, peekRoundIndex = 0, tickMemory = TickMemory.EMPTY.record(1L, 0, 52)),
        )
        assertEquals(
            listOf(RoundState.PEEKED, RoundState.DONE, RoundState.CURRENT),
            state.rounds,
        )
        assertEquals(1, state.rounds.count { it == RoundState.CURRENT })
        assertEquals(DiscStyle.DIMMED, state.disc.style)
        assertEquals(listOf("235 × 5", "TOOK 0:52"), state.discText())
        assertEquals("↺ RELEASE TO RETURN", state.bottomBand?.text)
        assertEquals(DialTap.NONE, state.tap)
        assertNull(state.hold)
    }

    @Test
    fun `a round this watch never timed peeks without a TOOK line`() {
        val logged = snapshot(squat.copy(sets = listOf(set(done = true), set(), set())), press)
        val state = dialUiState(inputs(snapshot = logged, peekRoundIndex = 0))
        assertEquals(listOf("235 × 5"), state.discText())
    }

    @Test
    fun `peeking at where you already are keeps the accent, not the white marker`() {
        val state = dialUiState(inputs(peekRoundIndex = 0))
        assertEquals(RoundState.CURRENT, state.rounds[0])
        assertFalse(state.rounds.contains(RoundState.PEEKED))
    }

    @Test
    fun `a peek during a rest keeps the countdown running on its own ring`() {
        val rest = RestState(deadlineElapsedMillis = 90_000L, totalSeconds = 150, betweenExercises = false)
        val logged = snapshot(squat.copy(sets = listOf(set(done = true), set(), set())), press)
        val state = dialUiState(
            inputs(snapshot = logged, rest = rest, nowElapsedMillis = 6_000L, peekRoundIndex = 0),
        )
        assertEquals(84f / 150f, state.arc!!)
        assertEquals(RoundState.PEEKED, state.rounds[0])
        assertEquals(DiscStyle.DIMMED, state.disc.style)
        assertEquals(DialTap.NONE, state.tap)
    }

    @Test
    fun `the crown's peek belongs to the workout face, not the overview`() {
        val state = dialUiState(inputs(face = DialFace.OVERVIEW, peekRoundIndex = 1))
        assertEquals(DialScreen.OVERVIEW, state.screen)
        assertEquals(DiscStyle.FILLED, state.disc.style)
        assertEquals(DialTap.OPEN_WORKOUT, state.tap)
    }

    // --- swipe left, and only where nothing is under way (v3 §3) -------------------

    @Test
    fun `a set's start is the only place on the workout face a swipe changes lifts`() {
        val rest = RestState(deadlineElapsedMillis = 90_000L, totalSeconds = 150, betweenExercises = false)
        assertEquals(DialSwipe.NEXT_EXERCISE, dialUiState(inputs()).swipe)
        assertEquals(DialSwipe.NEXT_EXERCISE, dialUiState(inputs(restedSeconds = 150)).swipe)
        assertEquals(DialSwipe.NONE, dialUiState(inputs(phase = SetPhase.LIFTING)).swipe)
        assertEquals(DialSwipe.NONE, dialUiState(inputs(rest = rest, nowElapsedMillis = 6_000L)).swipe)
        assertEquals(DialSwipe.NONE, dialUiState(inputs(snapshot = allDone())).swipe)
    }

    @Test
    fun `a swipe means nothing while the crown is peeking`() {
        val logged = snapshot(squat.copy(sets = listOf(set(done = true), set(), set())), press)
        assertEquals(DialSwipe.NONE, dialUiState(inputs(snapshot = logged, peekRoundIndex = 0)).swipe)
    }

    // --- crown: undo (§6) ---------------------------------------------------------
    // The cases that pass no memory are the positional fallback: what the undo degrades
    // to when the watch has no chronology of its own to go on.

    /** The lifter took press first and squat second, so the day's last tick is squat's. */
    private val pressThenSquat = TickMemory.EMPTY
        .record(2L, 0, 30).record(2L, 1, 30)
        .record(1L, 0, 40).record(1L, 1, 40).record(1L, 2, 40)

    @Test
    fun `a hold offers the most recently logged set, between sets only`() {
        val logged = snapshot(squat.copy(sets = listOf(set(done = true), set(), set())), press)
        val ready = dialUiState(inputs(snapshot = logged))
        assertEquals(0, ready.hold?.target?.exerciseIndex)
        assertEquals(0, ready.hold?.target?.roundIndex)
        assertEquals(listOf("UNDO", "SET 1"), ready.hold?.disc?.lines?.map { it.spans.single().text })

        val restOver = dialUiState(inputs(snapshot = logged, restedSeconds = 150))
        assertEquals(0, restOver.hold?.target?.exerciseIndex)
        assertEquals(0, restOver.hold?.target?.roundIndex)
    }

    @Test
    fun `the finished day can still take back the set that finished it`() {
        val state = dialUiState(inputs(snapshot = allDone()))
        assertEquals(DialScreen.DAY_DONE, state.screen)
        assertEquals(DialTap.DISMISS, state.tap)
        assertNotNull(state.hold)
        assertEquals(UndoTarget(1, 1), state.hold?.target)
        assertEquals(listOf("UNDO", "SET 2"), state.hold?.disc?.lines?.map { it.spans.single().text })
    }

    @Test
    fun `a finished lift's last set is still reachable from the next lift's first`() {
        val logged = snapshot(squat.copy(sets = List(3) { set(done = true) }), press)
        val state = dialUiState(inputs(snapshot = logged))
        assertEquals(DialScreen.READY, state.screen)
        assertEquals(UndoTarget(0, 2), state.hold?.target)
        assertEquals(
            listOf("UNDO", "SQUAT", "SET 3"),
            state.hold?.disc?.lines?.map { it.spans.single().text },
        )
    }

    @Test
    fun `undoing a cross-lift tick brings the dial back to that lift`() {
        val reopened = snapshot(
            squat.copy(sets = listOf(set(done = true), set(done = true), set())),
            press,
        )
        val state = dialUiState(
            inputs(
                snapshot = reopened,
                exerciseIndex = ExerciseSelection.resolve(reopened, selectedIndex = 0),
            ),
        )
        assertEquals(DialScreen.READY, state.screen)
        assertTrue(state.topBand?.text?.startsWith("SQUAT") == true)
        assertEquals(UndoTarget(0, 1), state.hold?.target)
    }

    @Test
    fun `there is nothing to undo before the first set is logged`() {
        assertNull(dialUiState(inputs()).hold)
        assertNull(dialUiState(inputs(face = DialFace.OVERVIEW)).hold)
    }

    @Test
    fun `a set in progress is not a set to undo — it hasn't been locked in yet`() {
        val logged = snapshot(squat.copy(sets = listOf(set(done = true), set(), set())), press)
        val lifting = dialUiState(inputs(snapshot = logged, phase = SetPhase.LIFTING))
        assertNull(lifting.hold)
        // …and with a day's worth of logged sets behind it, it still doesn't say
        // otherwise: the hint follows the offer, not the other way round (#151).
        assertEquals("SET 2 OF 3", lifting.bottomBand?.text)
    }

    @Test
    fun `the undo is offered on exactly three screens, and no screen claims otherwise`() {
        // #151, wrist-confirmed: HOLD TO UNDO rendered on LIFTING while the hold was
        // only ever offered between efforts. Both halves are pinned here, because
        // either one alone is worthless. "Nothing advertises a hold it doesn't
        // offer" is vacuously true the moment no band says UNDO — READY could quietly
        // lose its offer and that assertion would still pass. So the offer map is
        // pinned outright, over every screen the dial builds.
        val logged = snapshot(squat.copy(sets = listOf(set(done = true), set(), set())), press)
        val plank = exercise(
            id = 4L,
            name = "Plank",
            sets = List(3) { set(weight = 0.0, reps = 0, seconds = 45, kind = "WORK") },
            tracking = "timed",
        )
        val states = listOf(
            dialUiState(inputs(snapshot = logged, face = DialFace.OVERVIEW)),
            dialUiState(inputs(snapshot = logged)),
            dialUiState(inputs(snapshot = logged, phase = SetPhase.LIFTING)),
            dialUiState(inputs(snapshot = logged, phase = SetPhase.LIFTING, peekRoundIndex = 0)),
            dialUiState(
                inputs(
                    snapshot = logged,
                    rest = RestState(deadlineElapsedMillis = 90_000L, totalSeconds = 150, betweenExercises = false),
                    nowElapsedMillis = 6_000L,
                ),
            ),
            dialUiState(inputs(snapshot = logged, restedSeconds = 150)),
            dialUiState(inputs(snapshot = snapshot(plank), phase = SetPhase.LIFTING, liftingElapsedMillis = 28_000L)),
            dialUiState(inputs(snapshot = allDone())),
        )

        // The sweep is only worth anything if it actually reaches every screen.
        assertEquals(DialScreen.entries.toSet(), states.map { it.screen }.toSet())

        // The offer: between sets, between exercises, and on the finished day —
        // the three places nothing is under way to lose. Nowhere else.
        assertEquals(
            setOf(DialScreen.READY, DialScreen.REST_OVER, DialScreen.DAY_DONE),
            states.filter { it.isUndoAvailable }.map { it.screen }.toSet(),
            "the undo's offer map changed",
        )

        // And the hint: no band may name a hold the state it sits in doesn't offer.
        states.forEach { state ->
            val bands = listOfNotNull(state.topBand?.text, state.bottomBand?.text)
            if (bands.any { "UNDO" in it }) {
                assertNotNull(state.hold, "${state.screen} names the undo but offers no hold")
            }
        }
    }

    @Test
    fun `the finished day takes back the set it actually ended on, not the last in its order`() {
        val state = dialUiState(inputs(snapshot = allDone(), tickMemory = pressThenSquat))
        assertEquals(DialScreen.DAY_DONE, state.screen)
        assertEquals(DialTap.DISMISS, state.tap)
        assertEquals(UndoTarget(0, 2), state.hold?.target)
        assertEquals(
            listOf("UNDO", "SQUAT", "SET 3"),
            state.hold?.disc?.lines?.map { it.spans.single().text },
        )
    }

    @Test
    fun `taking that set back drops the day onto the lift it reopened`() {
        val reopened = snapshot(
            squat.copy(sets = listOf(set(done = true), set(done = true), set())),
            press.copy(sets = List(2) { set(weight = 120.0, reps = 8, done = true) }),
        )
        val after = dialUiState(
            inputs(
                snapshot = reopened,
                exerciseIndex = ExerciseSelection.resolve(reopened, selectedIndex = 0),
                tickMemory = pressThenSquat.forget(1L, 2),
            ),
        )
        assertEquals(DialScreen.READY, after.screen)
        assertTrue(after.topBand?.text?.startsWith("SQUAT") == true)
        assertEquals(UndoTarget(0, 1), after.hold?.target)
    }

    @Test
    fun `with nothing remembered the finished day starts its search at the day's last lift`() {
        val state = dialUiState(inputs(snapshot = allDone()))
        assertEquals(UndoTarget(1, 1), state.hold?.target)
        assertEquals(listOf("UNDO", "SET 2"), state.hold?.disc?.lines?.map { it.spans.single().text })
    }

    @Test
    fun `a remembered tick outranks the nearer one when the lifter worked out of order`() {
        val logged = snapshot(
            squat.copy(sets = listOf(set(done = true), set(), set())),
            press.copy(
                sets = listOf(
                    set(weight = 120.0, reps = 8, done = true),
                    set(weight = 120.0, reps = 8),
                ),
            ),
        )
        val memory = TickMemory.EMPTY.record(1L, 0, 40).record(2L, 0, 30)
        val state = dialUiState(inputs(snapshot = logged, tickMemory = memory))
        assertEquals(UndoTarget(1, 0), state.hold?.target)
        assertEquals(
            listOf("UNDO", "PRESS", "SET 1"),
            state.hold?.disc?.lines?.map { it.spans.single().text },
        )
    }

    // --- formatting --------------------------------------------------------------

    @Test
    fun `clocks read like a workout clock`() {
        assertEquals("0:07", DialFormat.clock(7))
        assertEquals("1:24", DialFormat.clock(84))
        assertEquals("38:00", DialFormat.clock(2_280))
        assertEquals("1:05:03", DialFormat.clock(3_903))
        assertEquals("0:00", DialFormat.clock(-5))
    }

    @Test
    fun `volume counts every done round of both superset tracks`() {
        val paired = exercise(
            id = 5L,
            name = "Bench",
            sets = listOf(set(weight = 185.0, reps = 5, done = true), set(weight = 185.0, reps = 5)),
            partnerName = "Curl",
            ssSets = listOf(set(weight = 50.0, reps = 12, done = true), set(weight = 50.0, reps = 12)),
        )
        assertEquals(925L + 600L, sessionVolume(listOf(paired), WeightUnit.LB))
    }

    @Test
    fun `minutes need both ends of the session`() {
        assertEquals(38, DialFormat.wholeMinutes(1_000L, 1_000L + 38 * 60_000L))
        assertEquals(0, DialFormat.wholeMinutes(0L, 60_000L))
        assertEquals(0, DialFormat.wholeMinutes(60_000L, 1_000L))
    }

    @Test
    fun `numerals group in thousands`() {
        assertEquals("12,450", DialFormat.grouped(12_450L))
        assertEquals("945", DialFormat.grouped(945L))
        assertEquals("1,000,000", DialFormat.grouped(1_000_000L))
    }
}
