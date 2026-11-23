package cloud.trotter.log.strength.domain.standards

import cloud.trotter.log.strength.domain.library.ExerciseLibrary
import cloud.trotter.log.strength.domain.progression.Benchmark
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TestEventsTest {
    @Test
    fun `case 48 the shipped events are the AFT push-up and plank and two custom windows`() {
        assertEquals(listOf("aft_hrp", "aft_plank", "custom_pushup_window", "custom_situp_window"), TestEvents.all.map { it.id })
        val hrp = TestEvents.get("aft_hrp")
        assertEquals(TestSetType.REPS_IN_WINDOW, hrp.setType)
        assertEquals(120, hrp.windowSeconds)
        assertEquals("hand_release_pushup", hrp.exerciseId)
        assertEquals(1, hrp.version)
        val plank = TestEvents.get("aft_plank")
        assertEquals(TestSetType.MAX_HOLD, plank.setType)
        assertEquals(220, plank.ceilingSeconds)
        assertEquals("plank", plank.exerciseId)
        for (id in listOf("custom_pushup_window", "custom_situp_window")) {
            val event = TestEvents.get(id)
            assertEquals(TestSetType.REPS_IN_WINDOW, event.setType)
            assertTrue(event.custom)
            assertNull(event.windowSeconds)
        }
        assertEquals("situp", TestEvents.get("custom_situp_window").exerciseId)
    }

    @Test
    fun `every event is verifiable and runnable`() {
        assertEquals(TestEvents.all.size, TestEvents.all.map { it.id }.distinct().size)
        TestEvents.all.forEach { event ->
            assertNotNull(ExerciseLibrary.find(event.exerciseId))
            assertTrue(event.sourceUrl.startsWith("https://"))
            LocalDate.parse(event.sourceDate)
            assertTrue(Benchmark.runnable(event))
            assertEquals(event, TestEvents.matches(TestRef(event.id, event.version, event.windowSeconds)))
        }
    }

    @Test
    fun `an event rejects a shape its set type forbids`() {
        assertFailsWith<IllegalArgumentException> { TestEvents.get("aft_plank").copy(windowSeconds = 120) }
        assertFailsWith<IllegalArgumentException> { TestEvents.get("aft_hrp").copy(windowSeconds = null) }
        assertFailsWith<IllegalArgumentException> { TestEvents.get("aft_hrp").copy(sourceDate = "June 2025") }
    }

    @Test
    fun `the window is the event's or the typed one for a custom event`() {
        assertEquals(120, TestEvents.window(TestEvents.get("aft_hrp"), 60))
        assertEquals(60, TestEvents.window(TestEvents.get("custom_pushup_window"), 60))
        assertNull(TestEvents.window(TestEvents.get("custom_pushup_window"), null))
        assertNull(TestEvents.window(TestEvents.get("aft_plank"), 60))
    }
}
