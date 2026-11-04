package cloud.trotter.log.strength.domain.records

import cloud.trotter.log.strength.domain.library.TrackingType
import cloud.trotter.log.strength.domain.model.LoggedSet
import cloud.trotter.log.strength.domain.model.SetKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PersonalBestsTest {
    private fun set(
        weightLb: Double = 0.0,
        reps: Int = 0,
        kind: SetKind = SetKind.WORK,
        done: Boolean = true,
        seconds: Int = 0,
    ) = LoggedSet(weightLb, reps, kind, done, seconds)

    @Test
    fun `best excludes ramps and ranks top and backoff sets together`() {
        val top = set(245.0, 5, SetKind.TOP)
        val sets = listOf(
            set(135.0, 5, SetKind.RAMP),
            set(165.0, 3, SetKind.RAMP),
            set(190.0, 2, SetKind.RAMP),
            set(210.0, 1, SetKind.RAMP),
            top,
            set(185.0, 8, SetKind.BACKOFF),
            set(185.0, 8, SetKind.BACKOFF),
        )
        assertEquals(top, PersonalBests.best(sets))
    }

    @Test
    fun `weighted PR kind distinguishes weight and reps and excludes ties and worse sets`() {
        assertEquals(PrKind.WEIGHT, PersonalBests.prKind(set(245.0, 5, SetKind.TOP), set(235.0, 5)))
        assertEquals(PrKind.REPS, PersonalBests.prKind(set(235.0, 5), set(235.0, 3)))
        assertNull(PersonalBests.prKind(set(235.0, 5), set(235.0, 5)))
        assertNull(PersonalBests.prKind(set(230.0, 8), set(235.0, 5)))
        assertEquals(set(235.0, 5), PersonalBests.best(listOf(set(235.0, 3), set(235.0, 5))))
    }

    @Test
    fun `work sets earn rep and weight PRs`() {
        assertEquals(PrKind.REPS, PersonalBests.prKind(set(35.0, 10), set(35.0, 9)))
        assertEquals(PrKind.WEIGHT, PersonalBests.prKind(set(40.0, 6), set(35.0, 10)))
    }

    @Test
    fun `first performance is never a PR`() {
        assertNull(PersonalBests.prKind(set(245.0, 5), standing = null))
    }

    @Test
    fun `a heavier ramp never counts or earns a PR`() {
        val ramp = set(250.0, 1, SetKind.RAMP)
        val top = set(245.0, 5, SetKind.TOP)
        assertFalse(PersonalBests.counts(ramp))
        assertEquals(top, PersonalBests.best(listOf(ramp, top)))
        assertNull(PersonalBests.prKind(ramp, top))
        assertNull(PersonalBests.best(listOf(ramp)))
    }

    @Test
    fun `unchecked and empty sets never count`() {
        val unchecked = set(275.0, 5, done = false)
        val empty = set()
        assertFalse(PersonalBests.counts(unchecked))
        assertFalse(PersonalBests.counts(empty))
        assertNull(PersonalBests.prKind(unchecked, set(245.0, 5)))
        assertNull(PersonalBests.prKind(empty, set(245.0, 5)))
        assertNull(PersonalBests.best(listOf(unchecked, empty)))
        assertNull(PersonalBests.best(emptyList()))
    }

    @Test
    fun `all non ramp kinds count with any positive measure`() {
        for (kind in listOf(SetKind.TOP, SetKind.BACKOFF, SetKind.WORK, SetKind.EXTRA)) {
            assertTrue(PersonalBests.counts(set(weightLb = 25.0, kind = kind)))
            assertTrue(PersonalBests.counts(set(reps = 12, kind = kind)))
            assertTrue(PersonalBests.counts(set(seconds = 60, kind = kind)))
        }
        val extra = set(255.0, 5, SetKind.EXTRA)
        assertEquals(extra, PersonalBests.best(listOf(set(245.0, 5), extra)))
    }

    @Test
    fun `reps rank by rep count and equal reps are not a PR`() {
        assertEquals(PrKind.REPS, PersonalBests.prKind(set(reps = 12), set(reps = 10)))
        assertNull(PersonalBests.prKind(set(reps = 10), set(reps = 10)))
        assertEquals(set(reps = 12), PersonalBests.best(listOf(set(reps = 10), set(reps = 12))))
    }

    @Test
    fun `timed sets rank hold first and added load second`() {
        val standing = set(weightLb = 25.0, seconds = 60)
        val longer = set(seconds = 90)
        val heavier = set(weightLb = 30.0, seconds = 60)
        assertEquals(PrKind.HOLD, PersonalBests.prKind(longer, standing))
        assertEquals(PrKind.WEIGHT, PersonalBests.prKind(heavier, standing))
        assertNull(PersonalBests.prKind(standing, standing))
        assertNull(PersonalBests.prKind(standing, longer))
        assertEquals(longer, PersonalBests.best(listOf(standing, longer)))
        assertEquals(heavier, PersonalBests.best(listOf(standing, heavier)))
    }

    @Test
    fun `tracking comes from logged values`() {
        assertEquals(TrackingType.WEIGHTED, PersonalBests.tracking(set(35.0, 10)))
        assertEquals(TrackingType.REPS, PersonalBests.tracking(set(reps = 60)))
        assertEquals(TrackingType.TIMED, PersonalBests.tracking(set(25.0, 60, seconds = 90)))
    }

    @Test
    fun `different tracking types never earn a PR or compare`() {
        val hold = set(seconds = 60)
        val reps = set(reps = 60)
        assertNull(PersonalBests.prKind(hold, reps))
        assertFailsWith<IllegalArgumentException> { PersonalBests.compare(hold, reps) }
    }

    @Test
    fun `mixed best uses the newest counting tracking type`() {
        val reps = set(reps = 60)
        val hold = set(seconds = 90)
        val ramp = set(250.0, 1, SetKind.RAMP)
        assertEquals(hold, PersonalBests.best(listOf(ramp, set(), reps, hold)))
        assertEquals(reps, PersonalBests.best(listOf(ramp, hold, reps)))
    }

    @Test
    fun `weighted best replaces an earlier reps tracking type`() {
        val weighted = set(45.0, 8)
        val sets = listOf(set(reps = 20), weighted)

        assertEquals(1, PersonalBests.bestIndex(sets))
        assertEquals(weighted, PersonalBests.best(sets))
    }

    @Test
    fun `best index keeps the earliest tied performance`() {
        val sets = listOf(set(245.0, 5, SetKind.TOP), set(245.0, 5, SetKind.TOP))

        assertEquals(0, PersonalBests.bestIndex(sets))
    }

    @Test
    fun `returning to a tracking type includes its older history`() {
        val earlierWeighted = set(50.0, 8)
        val sets = listOf(earlierWeighted, set(reps = 20), set(45.0, 10))

        assertEquals(0, PersonalBests.bestIndex(sets))
        assertEquals(earlierWeighted, PersonalBests.best(sets))
    }

    @Test
    fun `weight comparisons use exact stored doubles`() {
        assertEquals(PrKind.WEIGHT, PersonalBests.prKind(set(235.000001, 3), set(235.0, 5)))
        assertEquals(
            PrKind.WEIGHT,
            PersonalBests.prKind(set(weightLb = 25.000001, seconds = 60), set(weightLb = 25.0, seconds = 60)),
        )
    }

    @Test
    fun `prKinds returns every improved dimension with the existing label first`() {
        val cases = listOf(
            Triple(set(245.0, 6), set(240.0, 5), listOf(PrKind.WEIGHT, PrKind.REPS)),
            Triple(set(245.0, 4), set(240.0, 5), listOf(PrKind.WEIGHT)),
            Triple(set(235.0, 5), set(235.0, 3), listOf(PrKind.REPS)),
            Triple(set(235.0, 5), set(235.0, 5), emptyList()),
            Triple(set(reps = 12), set(reps = 10), listOf(PrKind.REPS)),
            Triple(set(weightLb = 25.0, seconds = 90), set(seconds = 75), listOf(PrKind.HOLD, PrKind.WEIGHT)),
            Triple(set(weightLb = 25.0, seconds = 75), set(seconds = 75), listOf(PrKind.WEIGHT)),
            Triple(set(245.0, 6, SetKind.RAMP), set(240.0, 5), emptyList()),
            Triple(set(245.0, 6), null, emptyList()),
        )
        for ((candidate, standing, expected) in cases) {
            val kinds = PersonalBests.prKinds(candidate, standing)
            assertEquals(expected, kinds)
            assertEquals(kinds.firstOrNull(), PersonalBests.prKind(candidate, standing))
        }
    }
}
