package cloud.trotter.log.strength.domain.glance

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The strings the widget and the watch's glance surfaces both print. Parity used
 * to be two test files agreeing by hand; it is now one contract, and a changed
 * string here is a changed string on the home screen and on the wrist.
 */
class GlanceLinesTest {

    @Test
    fun `day line pairs the day with its title`() {
        assertEquals("DAY A · LOWER", GlanceLines.dayLine("A", "Lower"))
    }

    @Test
    fun `a titleless day is just the day`() {
        assertEquals("DAY A", GlanceLines.dayLine("A", ""))
    }

    @Test
    fun `a title of nothing but spaces is no title`() {
        assertEquals("DAY A", GlanceLines.dayLine("A", "   "))
    }

    @Test
    fun `a blank day id keeps the DAY prefix rather than dropping the clause`() {
        // "day " isn't blank, so it survives the filter — the same string the widget
        // produced when it uppercased each clause before filtering.
        assertEquals("DAY  · LOWER", GlanceLines.dayLine("", "Lower"))
    }

    @Test
    fun `untouched work shows lift and set counts`() {
        assertEquals("3 LIFTS · 21 SETS", GlanceLines.statLine(3, 0, 21))
    }

    @Test
    fun `work in progress shows completed over total sets`() {
        assertEquals("12 / 21 SETS", GlanceLines.statLine(3, 12, 21))
    }

    @Test
    fun `completed work shows done and the set count`() {
        assertEquals("DONE · 21 SETS", GlanceLines.statLine(3, 21, 21))
    }

    @Test
    fun `one lift with one set keeps both nouns singular`() {
        assertEquals("1 LIFT · 1 SET", GlanceLines.statLine(1, 0, 1))
    }

    @Test
    fun `one lift with several sets keeps only lift singular`() {
        assertEquals("1 LIFT · 4 SETS", GlanceLines.statLine(1, 0, 4))
    }

    @Test
    fun `an over-count reads as done, not as more sets than there are`() {
        // Neither surface can produce this — done counts a subset of the list it totals —
        // but a public formatter has to answer for it, and the old widget's `done == total`
        // would have said "2 / 1 SET".
        assertEquals("DONE · 1 SET", GlanceLines.statLine(3, 2, 1))
    }

    @Test
    fun `zero total sets is never done`() {
        assertEquals("3 LIFTS · 0 SETS", GlanceLines.statLine(3, 0, 0))
    }
}
