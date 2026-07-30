package io.github.sjtrotter.strengthlog.wear.ui

import io.github.sjtrotter.strengthlog.domain.sync.WatchDay
import io.github.sjtrotter.strengthlog.domain.sync.WatchExercise
import io.github.sjtrotter.strengthlog.domain.sync.WatchSet
import io.github.sjtrotter.strengthlog.domain.sync.WatchSnapshot
import io.github.sjtrotter.strengthlog.domain.units.WeightUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The seven screens (brief §5) and the transitions between them, as a pure
 * mapping. This is the watch's only device-free verification of the workout
 * flow, so each of the brief's screens gets its own case and each transition
 * is a state pair rather than a click.
 */
class DialStateTest {

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
    )

    private fun snapshot(vararg exercises: WatchExercise) = WatchSnapshot(
        revision = 1L,
        suggestedDayId = "A",
        day = WatchDay(
            dayId = "A",
            title = "Day A",
            accentIndex = 0,
            exercises = exercises.toList(),
            emphasisLine = "lower",
        ),
        unit = "lb",
    )

    private val squat = exercise(1L, "Squat", List(3) { set() })
    private val press = exercise(2L, "Press", List(2) { set(weight = 120.0, reps = 8, kind = "WORK") })
    private val day = snapshot(squat, press)

    private fun inputs(
        snapshot: WatchSnapshot = day,
        exerciseIndex: Int = currentExerciseIndex(snapshot),
        phase: SetPhase = SetPhase.READY,
        begun: Boolean = true,
        pendingCount: Int = 0,
        rest: RestState? = null,
        restedSeconds: Int? = null,
        liftingElapsedMillis: Long = 0L,
        nowElapsedMillis: Long = 0L,
        session: SessionStamps = SessionStamps(),
        peekRoundIndex: Int? = null,
        peekTookSeconds: Int? = null,
    ) = DialInputs(
        snapshot = snapshot,
        exerciseIndex = exerciseIndex,
        phase = phase,
        begun = begun,
        pendingCount = pendingCount,
        rest = rest,
        restedSeconds = restedSeconds,
        liftingElapsedMillis = liftingElapsedMillis,
        nowElapsedMillis = nowElapsedMillis,
        session = session,
        peekRoundIndex = peekRoundIndex,
        peekTookSeconds = peekTookSeconds,
    )

    private fun DialUiState.discText(): List<String> =
        disc.lines.map { line -> line.spans.joinToString(" ") { it.text } }

    // --- 1 · Today ---------------------------------------------------------------

    @Test
    fun `today previews the day with one segment per exercise`() {
        val state = dialUiState(inputs(begun = false))
        assertEquals(DialScreen.TODAY, state.screen)
        assertEquals(0f, state.dayProgress)
        assertEquals(2, state.rounds.size) // exercises, not sets
        assertEquals(1, state.rounds.count { it == RoundState.CURRENT })
        assertEquals("DAY A · LOWER", state.topBand?.text)
        assertEquals("2 LIFTS · 5 SETS", state.bottomBand?.text)
        assertEquals(DiscStyle.FILLED, state.disc.style)
        assertEquals(listOf("SQUAT", "BEGIN · 3 SETS"), state.discText())
        assertEquals(DialTap.BEGIN_EXERCISE, state.tap)
    }

    @Test
    fun `today is gone the moment anything is logged, begun or not`() {
        val started = snapshot(squat.copy(sets = listOf(set(done = true)) + squat.sets.drop(1)), press)
        assertEquals(DialScreen.READY, dialUiState(inputs(snapshot = started, begun = false)).screen)
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
        assertEquals("HOLD TO UNDO", state.bottomBand?.text)
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
            inputs(begun = false),
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
        assertNull(dialUiState(inputs(begun = false)).arc)
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
        assertEquals(DialCrown.SELECT_EXERCISE, dialUiState(inputs(begun = false)).crown)
        assertEquals(DialCrown.PEEK, dialUiState(inputs()).crown)
        assertEquals(DialCrown.PEEK, dialUiState(inputs(phase = SetPhase.LIFTING)).crown)
        assertEquals(DialCrown.PEEK, dialUiState(inputs(restedSeconds = 150)).crown)
        assertEquals(DialCrown.NONE, dialUiState(inputs(snapshot = allDone())).crown)
    }

    @Test
    fun `a peek is read-only, white where you're looking and accent where you are`() {
        val logged = snapshot(
            squat.copy(sets = listOf(set(done = true), set(done = true), set())),
            press,
        )
        val state = dialUiState(inputs(snapshot = logged, peekRoundIndex = 0, peekTookSeconds = 52))
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
    fun `there is nothing to peek at before the day starts`() {
        val state = dialUiState(inputs(begun = false, peekRoundIndex = 1))
        assertEquals(DialScreen.TODAY, state.screen)
        assertEquals(DiscStyle.FILLED, state.disc.style)
        assertEquals(DialTap.BEGIN_EXERCISE, state.tap)
    }

    // --- crown: undo (§6) ---------------------------------------------------------

    @Test
    fun `a hold offers the most recently logged set, between sets only`() {
        val logged = snapshot(squat.copy(sets = listOf(set(done = true), set(), set())), press)
        val ready = dialUiState(inputs(snapshot = logged))
        assertEquals(0, ready.hold?.roundIndex)
        assertEquals(listOf("UNDO", "SET 1"), ready.hold?.disc?.lines?.map { it.spans.single().text })

        val restOver = dialUiState(inputs(snapshot = logged, restedSeconds = 150))
        assertEquals(0, restOver.hold?.roundIndex)
    }

    @Test
    fun `there is nothing to undo before the first set is logged`() {
        assertNull(dialUiState(inputs()).hold)
        assertNull(dialUiState(inputs(begun = false)).hold)
    }

    @Test
    fun `a set in progress is not a set to undo — it hasn't been locked in yet`() {
        val logged = snapshot(squat.copy(sets = listOf(set(done = true), set(), set())), press)
        assertNull(dialUiState(inputs(snapshot = logged, phase = SetPhase.LIFTING)).hold)
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
