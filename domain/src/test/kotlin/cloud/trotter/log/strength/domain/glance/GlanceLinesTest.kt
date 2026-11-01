package cloud.trotter.log.strength.domain.glance

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The strings the widget and the watch's glance surfaces both print. A changed
 * string here is a changed string on the home screen and on the wrist.
 */
class GlanceLinesTest {

    @Test
    fun `sample day uses sentence case on every glance`() {
        assertEquals("4 lifts, 18 sets", GlanceLines.statLine(4, 0, 18))
        assertEquals("2 of 18 sets", GlanceLines.statLine(4, 2, 18))
        assertEquals("Done, 18 sets", GlanceLines.statLine(4, 18, 18))
    }

    @Test
    fun `day line pairs the day with its title`() {
        assertEquals("Day A, Squat focus", GlanceLines.dayLine("A", "Squat focus"))
    }

    @Test
    fun `a titleless day is just the day`() {
        assertEquals("Day A", GlanceLines.dayLine("A", ""))
    }

    @Test
    fun `a title of nothing but spaces is no title`() {
        assertEquals("Day A", GlanceLines.dayLine("A", "   "))
    }

    @Test
    fun `a blank day id keeps the DAY prefix rather than dropping the clause`() {
        // "day " isn't blank, so it survives the blank filter and keeps the
        // DAY prefix.
        assertEquals("Day , Lower", GlanceLines.dayLine("", "Lower"))
    }

    @Test
    fun `untouched work shows lift and set counts`() {
        assertEquals("3 lifts, 21 sets", GlanceLines.statLine(3, 0, 21))
    }

    @Test
    fun `work in progress shows completed over total sets`() {
        assertEquals("12 of 21 sets", GlanceLines.statLine(3, 12, 21))
    }

    @Test
    fun `completed work shows done and the set count`() {
        assertEquals("Done, 21 sets", GlanceLines.statLine(3, 21, 21))
    }

    @Test
    fun `one lift with one set keeps both nouns singular`() {
        assertEquals("1 lift, 1 set", GlanceLines.statLine(1, 0, 1))
    }

    @Test
    fun `one lift with several sets keeps only lift singular`() {
        assertEquals("1 lift, 4 sets", GlanceLines.statLine(1, 0, 4))
    }

    @Test
    fun `an over-count reads as done, not as more sets than there are`() {
        // Neither surface can produce this — done counts a subset of the list it totals —
        // but a public formatter has to answer for it without printing "2 of 1 set".
        assertEquals("Done, 1 set", GlanceLines.statLine(3, 2, 1))
    }

    @Test
    fun `zero total sets is never done`() {
        assertEquals("3 lifts, 0 sets", GlanceLines.statLine(3, 0, 0))
    }
}
