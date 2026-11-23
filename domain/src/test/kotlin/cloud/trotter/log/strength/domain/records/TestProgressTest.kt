package cloud.trotter.log.strength.domain.records

import cloud.trotter.log.strength.domain.standards.TestRef
import cloud.trotter.log.strength.domain.standards.TestStop
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TestProgressTest {
    private val hrp = TestRef("aft_hrp", 1, 120)
    private val plank = TestRef("aft_plank", 1)
    private val custom60 = TestRef("custom_pushup_window", 1, 60)
    private val custom120 = TestRef("custom_pushup_window", 1, 120)
    private val hrpV2 = TestRef("aft_hrp", 2, 120)

    @Test
    fun `case 48 results compare only within one identity`() {
        val results = listOf(
            TestResult(hrp, 1, 24, null, TestStop.TIME_UP),
            TestResult(hrp, 2, 31, null, TestStop.TIME_UP),
            TestResult(custom120, 3, 40, null, TestStop.TIME_UP),
            TestResult(hrpV2, 4, 50, null, TestStop.TIME_UP),
            TestResult(custom60, 5, 45, null, TestStop.TIME_UP),
        )
        assertEquals(31, TestProgress.best(hrp, results))
        assertEquals(40, TestProgress.best(custom120, results))
        assertEquals(45, TestProgress.best(custom60, results))
        assertEquals(50, TestProgress.best(hrpV2, results))
        assertNull(TestProgress.best(TestRef("aft_hrp", 1, 60), results))
        assertFalse(TestProgress.compatible(custom60, custom120))
    }

    @Test
    fun `case 48 the required number is met at or above it`() {
        assertFalse(TestProgress.met(40, 31))
        assertTrue(TestProgress.met(40, 40))
        assertFalse(TestProgress.met(40, null))
        val results = listOf(
            TestResult(plank, 1, null, 150, TestStop.STOPPED),
            TestResult(plank, 2, null, 190, TestStop.STOPPED),
        )
        assertEquals(190, TestProgress.best(plank, results))
        assertFalse(TestProgress.met(220, TestProgress.best(plank, results)))
        assertTrue(TestProgress.met(220, TestProgress.best(plank,
            results + TestResult(plank, 3, null, 225, TestStop.STOPPED))))
    }

    @Test
    fun `newest is by completion time`() {
        val results = listOf(1L, 3L, 2L).map { TestResult(hrp, it, 24, null, TestStop.TIME_UP) }
        assertEquals(3L, TestProgress.newest(hrp, results)?.completedAt)
        assertNull(TestProgress.newest(plank, results))
    }

    @Test
    fun `a result carries exactly one number`() {
        assertFailsWith<IllegalArgumentException> { TestResult(hrp, 0, null, null, TestStop.TIME_UP) }
        assertFailsWith<IllegalArgumentException> { TestResult(hrp, 0, 24, 120, TestStop.TIME_UP) }
    }
}
