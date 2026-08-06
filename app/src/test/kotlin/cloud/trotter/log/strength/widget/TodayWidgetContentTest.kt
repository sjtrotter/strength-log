package cloud.trotter.log.strength.widget

import cloud.trotter.log.strength.domain.sync.WatchDay
import cloud.trotter.log.strength.domain.sync.WatchExercise
import cloud.trotter.log.strength.domain.sync.WatchSet
import cloud.trotter.log.strength.domain.sync.WatchSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The widget's whole derivation (glance-surfaces.md §1): the four faces, their
 * exact lines, and the progress numbers the RemoteViews layer clips the bar to.
 */
class TodayWidgetContentTest {

    private fun sets(total: Int, done: Int) = List(total) {
        WatchSet(weightLb = 135.0, reps = 5, kind = "TOP", done = it < done)
    }

    /** [doneBySets]: rounds done per exercise, in order; each gets 7 rounds. */
    private fun snapshot(vararg doneBySets: Int, title: String = "Lower", accentIndex: Int = 0) =
        WatchSnapshot(
            revision = 0L,
            suggestedDayId = "A",
            day = WatchDay(
                dayId = "A",
                title = title,
                accentIndex = accentIndex,
                exercises = doneBySets.mapIndexed { i, done ->
                    WatchExercise(
                        programExerciseId = i.toLong(),
                        slot = "main",
                        name = "Lift $i",
                        goal = 235.0,
                        perHand = false,
                        supersetPartnerName = null,
                        sets = sets(total = 7, done = done),
                        ssSets = emptyList(),
                    )
                },
            ),
            unit = "lb",
        )

    @Test
    fun `an untouched day advertises the work ahead`() {
        val content = todayWidgetContent(snapshot(0, 0, 0))

        assertEquals("DAY A · LOWER", content.dayLine)
        assertEquals("3 LIFTS · 21 SETS", content.statLine)
        assertEquals(TodayWidgetState.BEFORE, content.state)
        assertEquals(0, content.setsDone)
        assertEquals(21, content.totalSets)
    }

    @Test
    fun `mid-session counts every logged round of the day, not just the current lift`() {
        val content = todayWidgetContent(snapshot(7, 5, 0))

        assertEquals("12 / 21 SETS", content.statLine)
        assertEquals(TodayWidgetState.IN_PROGRESS, content.state)
        assertEquals(12, content.setsDone)
        assertEquals(21, content.totalSets)
    }

    @Test
    fun `a finished day says so and keeps the total`() {
        val content = todayWidgetContent(snapshot(7, 7, 7))

        assertEquals("DONE · 21 SETS", content.statLine)
        assertEquals(TodayWidgetState.DONE, content.state)
        assertEquals(21, content.totalSets)
    }

    @Test
    fun `no program is a single line, never a blank widget`() {
        val content = todayWidgetContent(null)

        assertNull(content.dayLine)
        assertEquals("SET UP YOUR PROGRAM", content.statLine)
        assertEquals(TodayWidgetState.NO_PROGRAM, content.state)
    }

    @Test
    fun `a day with no exercises reads as no program rather than zero of zero`() {
        val content = todayWidgetContent(snapshot())

        assertEquals(TodayWidgetState.NO_PROGRAM, content.state)
        assertEquals("SET UP YOUR PROGRAM", content.statLine)
    }

    @Test
    fun `the accent index rides through untouched, so the tint stays DayAccentColors'`() {
        assertEquals(3, todayWidgetContent(snapshot(0, accentIndex = 3)).accentIndex)
    }

    @Test
    fun `a titleless day still names the letter`() {
        assertEquals("DAY A", todayWidgetContent(snapshot(0, title = "")).dayLine)
    }

    @Test
    fun `singular counts don't read as one lifts`() {
        val oneRound = WatchSnapshot(
            revision = 0L,
            suggestedDayId = "B",
            day = WatchDay(
                dayId = "B",
                title = "Upper",
                accentIndex = 1,
                exercises = listOf(
                    WatchExercise(
                        programExerciseId = 1L,
                        slot = "main",
                        name = "Bench",
                        goal = 185.0,
                        perHand = false,
                        supersetPartnerName = null,
                        sets = sets(total = 1, done = 0),
                        ssSets = emptyList(),
                    ),
                ),
            ),
            unit = "lb",
        )

        assertEquals("1 LIFT · 1 SET", todayWidgetContent(oneRound).statLine)
    }

    @Test
    fun `superset partner rows ride inside the round instead of inflating the total`() {
        val withPartner = WatchSnapshot(
            revision = 0L,
            suggestedDayId = "A",
            day = WatchDay(
                dayId = "A",
                title = "Lower",
                accentIndex = 0,
                exercises = listOf(
                    WatchExercise(
                        programExerciseId = 1L,
                        slot = "main",
                        name = "Curl",
                        goal = 40.0,
                        perHand = true,
                        supersetPartnerName = "Triceps Pushdown",
                        sets = sets(total = 3, done = 1),
                        ssSets = sets(total = 3, done = 1),
                    ),
                ),
            ),
            unit = "lb",
        )

        assertEquals("1 / 3 SETS", todayWidgetContent(withPartner).statLine)
    }
}
