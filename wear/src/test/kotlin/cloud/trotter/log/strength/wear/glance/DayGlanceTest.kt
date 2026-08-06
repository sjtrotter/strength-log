package cloud.trotter.log.strength.wear.glance

import cloud.trotter.log.strength.domain.sync.WatchDay
import cloud.trotter.log.strength.domain.sync.WatchExercise
import cloud.trotter.log.strength.domain.sync.WatchSet
import cloud.trotter.log.strength.domain.sync.WatchSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * What the tile and the complication say in each state the day can be in. These
 * lines are the contract the phone widget shares (glance-surfaces brief §1), so a
 * changed string here is a changed string on the home screen too.
 */
class DayGlanceTest {

    private fun exercise(id: Long, doneFlags: List<Boolean>) = WatchExercise(
        programExerciseId = id,
        slot = "main",
        name = "Ex$id",
        goal = 100.0,
        perHand = false,
        supersetPartnerName = null,
        sets = doneFlags.map { WatchSet(100.0, 5, "WORK", done = it) },
        ssSets = emptyList(),
    )

    private fun snapshot(
        title: String = "Lower",
        accentIndex: Int = 3,
        vararg exercises: WatchExercise,
    ) = WatchSnapshot(
        revision = 1L,
        suggestedDayId = "A",
        day = WatchDay(
            dayId = "A",
            title = title,
            accentIndex = accentIndex,
            exercises = exercises.toList(),
            // The five-clause emphasis line is deliberately not what the glance shows;
            // a realistic one here keeps that honest.
            emphasisLine = "flat press · vertical pull · hip-hinge hamstrings",
        ),
        unit = "lb",
    )

    /** 3 lifts, 21 sets — the fixture the whole file counts against. */
    private fun day(done: Int) = snapshot(
        "Lower",
        3,
        exercise(1L, List(7) { it < done }),
        exercise(2L, List(7) { it < done - 7 }),
        exercise(3L, List(7) { it < done - 14 }),
    )

    @Test
    fun `before the first set it counts the work ahead`() {
        val glance = DayGlance.of(day(done = 0))

        assertEquals("DAY A · LOWER", glance.titleLine)
        assertEquals("3 LIFTS · 21 SETS", glance.setLine)
        assertEquals("A", glance.shortText)
        assertEquals("0/21", glance.ratioText)
        assertEquals(0f, glance.progress)
        assertFalse(glance.done)
    }

    @Test
    fun `mid session it counts what is logged`() {
        val glance = DayGlance.of(day(done = 12))

        assertEquals("12 / 21 SETS", glance.setLine)
        assertEquals("12/21", glance.ratioText)
        assertEquals(12f / 21f, glance.progress)
        assertFalse(glance.done)
    }

    @Test
    fun `a finished day reads DONE and fills the ring`() {
        val glance = DayGlance.of(day(done = 21))

        assertEquals("DONE · 21 SETS", glance.setLine)
        assertEquals(1f, glance.progress)
        assertEquals(21f, glance.rangeValue)
        assertEquals(21f, glance.rangeMax)
        assertTrue(glance.done)
    }

    @Test
    fun `no snapshot is an em dash and an instruction, never a blank`() {
        val glance = DayGlance.of(null)

        assertEquals("NO PROGRAM", glance.titleLine)
        assertEquals("SET UP ON YOUR PHONE", glance.setLine)
        assertEquals("—", glance.shortText)
        assertEquals("", glance.ratioText)
        assertEquals(0f, glance.progress)
        assertFalse(glance.done)
    }

    @Test
    fun `the day title carries line one, never the five-clause emphasis`() {
        val glance = DayGlance.of(day(done = 0))

        assertEquals("DAY A · LOWER", glance.titleLine)
        assertEquals("Lower", glance.dayTitle)
    }

    @Test
    fun `a day with no sets is no program, but keeps its accent`() {
        val glance = DayGlance.of(snapshot("Lower", 5))

        assertFalse(glance.hasProgram)
        assertEquals(5, glance.accentIndex)
        assertEquals("NO PROGRAM", glance.titleLine)
    }

    @Test
    fun `an empty range still has room to move`() {
        val glance = DayGlance.of(null)

        assertEquals(0f, glance.rangeValue)
        assertEquals(1f, glance.rangeMax)
    }

    @Test
    fun `a day without a title is just the day, and one lift is singular`() {
        val glance = DayGlance.of(snapshot("", 0, exercise(1L, List(4) { false })))

        assertEquals("DAY A", glance.titleLine)
        assertEquals("1 LIFT · 4 SETS", glance.setLine)
    }

    @Test
    fun `a single set reads singular too, like the widget`() {
        val glance = DayGlance.of(snapshot("Lower", 0, exercise(1L, listOf(false))))

        assertEquals("1 LIFT · 1 SET", glance.setLine)
    }

    @Test
    fun `progress counts the main track only, like the dial's day ring`() {
        val paired = WatchExercise(
            programExerciseId = 9L,
            slot = "main",
            name = "Bench",
            goal = 185.0,
            perHand = false,
            supersetPartnerName = "Row",
            sets = List(4) { WatchSet(185.0, 5, "WORK", done = it < 2) },
            ssSets = List(4) { WatchSet(50.0, 12, "WORK", done = it < 2) },
        )

        val glance = DayGlance.of(snapshot("Upper", 1, paired))

        assertEquals(4, glance.totalSets)
        assertEquals(2, glance.doneSets)
        assertEquals("2 / 4 SETS", glance.setLine)
    }

    @Test
    fun `accent and day letter come straight off the snapshot`() {
        val glance = DayGlance.of(day(done = 3))

        assertEquals("A", glance.dayLetter)
        assertEquals(3, glance.accentIndex)
        assertEquals(3, glance.exerciseCount)
    }

    @Test
    fun `the content description says what the ring means`() {
        assertEquals("Day A, 12 of 21 sets done", DayGlance.of(day(done = 12)).contentDescription)
        assertEquals("Day A done, 21 sets", DayGlance.of(day(done = 21)).contentDescription)
        assertEquals("No program yet", DayGlance.of(null).contentDescription)
    }
}
