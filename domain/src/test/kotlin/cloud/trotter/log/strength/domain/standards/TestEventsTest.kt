package cloud.trotter.log.strength.domain.standards

import cloud.trotter.log.strength.domain.library.ExerciseLibrary
import cloud.trotter.log.strength.domain.model.TestEventGoal
import cloud.trotter.log.strength.domain.planner.TestAnswer
import cloud.trotter.log.strength.domain.progression.Benchmark
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TestEventsTest {
    @Test fun `case 59 a derived event resolves by exercise and format and the two custom ids stay the derived spelling`() {
        val event = assertNotNull(TestEvents.find("pullup:REPS_CONTINUOUS"))
        assertEquals("pullup", event.exerciseId)
        assertEquals(1, event.version)
        assertEquals(TestSetType.REPS_CONTINUOUS, event.setType)
        assertEquals("Pull-Up / Chin-Up, max unbroken", event.name)
        assertEquals(false, event.custom)
        assertNull(TestEvents.window(event, 60))
        assertEquals("Reps without rest until you stop, under your test's own standard.", event.form)
        assertEquals("custom_pushup_window", TestEvents.derivedId("pushup", TestFormat.REPS_IN_WINDOW))
        assertEquals("custom_situp_window", TestEvents.derivedId("situp", TestFormat.REPS_IN_WINDOW))
        assertEquals(TestEvents.all.first(), TestEvents.find("aft_hrp"))
        for (id in listOf("pushup:REPS_IN_WINDOW", "situp:REPS_IN_WINDOW", "plank:REPS_IN_WINDOW",
            "farmers_carry:MAX_HOLD", "box_jump:REPS_CONTINUOUS", "pullup:reps_continuous", "pullup:REPS_CONTINUOUS:other")) {
            assertNull(TestEvents.find(id), id)
        }
        for (entry in ExerciseLibrary.entries) for (format in TestFormat.eligible(entry)) {
            assertTrue(TestEvents.derivedId(entry.id, format).length <= 40)
            if (format.setType != null) {
                val derived = assertNotNull(TestEvents.derived(entry.id, format))
                assertEquals(derived, TestEvents.find(derived.id))
                assertEquals(format == TestFormat.REPS_IN_WINDOW, derived.custom)
            }
        }
        assertEquals(listOf(TestEvents.get("aft_hrp")), TestEvents.editions("hand_release_pushup", TestFormat.REPS_IN_WINDOW))
        assertEquals(listOf(TestEvents.get("aft_plank")), TestEvents.editions("plank", TestFormat.MAX_HOLD))
        assertTrue(TestEvents.editions("pushup", TestFormat.REPS_IN_WINDOW).isEmpty())
        assertEquals(listOf(TestFormat.HEAVIEST_FOR_REPS), TestFormat.eligible(ExerciseLibrary.get("bb_bench")))
        assertTrue(TestFormat.eligible(ExerciseLibrary.get("weighted_plank")).isEmpty())
    }

    @Test fun `case 60 a pressure caution hold derives with its cap and refuses numbers above it`() {
        val event = assertNotNull(TestEvents.find("wall_sit:MAX_HOLD"))
        val ref = TestRef(event.id, event.version)
        assertEquals(120, event.ceilingSeconds)
        assertEquals(120, TestEvents.requiredLimit(event))
        assertEquals("Held to 2:00 at most here. Keep breathing.", event.form)
        assertFailsWith<IllegalArgumentException> { TestEventGoal(ref, 130) }
        val goal = TestEventGoal(ref, 90)
        assertFailsWith<IllegalArgumentException> { TestAnswer(goal, 130) }
        assertEquals(110, TestAnswer(goal, 110).lastResult)
        assertEquals(3600, TestEventGoal(TestRef("future_event", 1), 3600).required)
    }

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
