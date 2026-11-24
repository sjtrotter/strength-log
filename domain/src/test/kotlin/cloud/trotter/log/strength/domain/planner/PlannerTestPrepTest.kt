package cloud.trotter.log.strength.domain.planner

import cloud.trotter.log.strength.domain.library.ExerciseLibrary
import cloud.trotter.log.strength.domain.model.LoggedSet
import cloud.trotter.log.strength.domain.model.PrescribedTarget
import cloud.trotter.log.strength.domain.model.Prescription
import cloud.trotter.log.strength.domain.model.ProgramDayKind
import cloud.trotter.log.strength.domain.model.SetKind
import cloud.trotter.log.strength.domain.model.TestEventGoal
import cloud.trotter.log.strength.domain.model.TestGoal
import cloud.trotter.log.strength.domain.progression.LiftGoal
import cloud.trotter.log.strength.domain.progression.KnownLoads
import cloud.trotter.log.strength.domain.progression.RepTarget
import cloud.trotter.log.strength.domain.progression.Scheme
import cloud.trotter.log.strength.domain.progression.SlotRole
import cloud.trotter.log.strength.domain.standards.TestEvents
import cloud.trotter.log.strength.domain.standards.TestRef
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlannerTestPrepTest {
    private val hrp = TestRef("aft_hrp", 1, 120)
    private val plank = TestRef("aft_plank", 1)
    private val goal40 = TestEventGoal(hrp, 40)
    private val goal220 = TestEventGoal(plank, 220)
    private val plain = referenceAnswers().copy(goal = LiftGoal.GENERAL, days = 3,
        experience = LiftExperience.NEW, recent = emptyMap(), mains = null)
    private val answers = plain.copy(tests = listOf(TestAnswer(goal40), TestAnswer(goal220)))

    @Test fun `case 52 a test goal adds a prep slot per event to each lifting day`() {
        for (count in listOf(3, 5)) {
            val draft = Planner.plan(answers.copy(days = count))
            val lifting = draft.program.strengthDays
            assertEquals(count, lifting.size)
            for ((dayIndex, day) in lifting.withIndex()) {
                val prep = day.exercises.withIndex().filter { it.value.prescription?.test != null }
                assertEquals(if (dayIndex < 3) 2 else 0, prep.size)
                for ((index, slot) in prep) {
                    val window = slot.exerciseId == "hand_release_pushup"
                    assertEquals(if (window) Prescription(PrescribedTarget.RepsInTime(120, 20), test = hrp)
                        else Prescription(PrescribedTarget.Hold(110), test = plank), slot.prescription)
                    assertEquals(3, slot.targetSets)
                    val rule = assertNotNull(slot.rule)
                    if (window) assertEquals(RepTarget.Fixed(20), rule.target)
                    assertTrue(rule.rir >= 2)
                    assertEquals(1, rule.everyExposures)
                    assertEquals(Scheme.STRENGTH, rule.scheme)
                    val row = if (window) LoggedSet(0.0, 20, SetKind.WORK)
                        else LoggedSet(0.0, 0, SetKind.WORK, seconds = 110)
                    assertEquals(List(3) { row }, draft.working[SlotKey(day.id, index)])
                }
            }
            assertEquals(TestGoal(listOf(goal40, goal220)), draft.testGoal)
            assertTrue(PlanReason.TestPrep(2) in draft.reasons)
            assertFalse(Planner.day(answers.copy(days = count), "A")!!.reasons.any { it is PlanReason.TestPrep })
        }
    }

    @Test fun `the anchor is the last result when given and holds use the five second grid`() {
        assertEquals(12, TestPrep.aim(TestEvents.get("aft_hrp"), TestAnswer(goal40, 24)))
        assertEquals(13, TestPrep.aim(TestEvents.get("aft_hrp"), TestAnswer(goal40, 25)))
        assertEquals(5, TestPrep.aim(TestEvents.get("aft_hrp"), TestAnswer(TestEventGoal(hrp, 6))))
        assertEquals(45, TestPrep.aim(TestEvents.get("aft_plank"), TestAnswer(goal220, 95)))
        assertEquals(20, TestPrep.aim(TestEvents.get("aft_plank"), TestAnswer(TestEventGoal(plank, 30))))
    }

    @Test fun `a custom window carries the typed window`() {
        val ref = TestRef("custom_situp_window", 1, 60)
        val draft = Planner.plan(plain.copy(tests = listOf(TestAnswer(TestEventGoal(ref, 45)))))
        draft.program.strengthDays.forEach { day ->
            val slot = day.exercises.single { it.prescription?.test == ref }
            assertEquals("situp", slot.exerciseId)
            assertEquals(Prescription(PrescribedTarget.RepsInTime(60, 23), test = ref), slot.prescription)
        }
    }

    @Test fun `without tests the plan is unchanged`() {
        val beforePrep = liftDraft(plain, KnownLoads.NONE).withBalanceDays(plain, KnownLoads.NONE)
        val draft = Planner.plan(plain)
        // #646: compare with generation before withTestPrep, so added unlinked exercises cannot pass.
        assertEquals(beforePrep.program.days.associate { it.id to it.exercises.size },
            draft.program.days.associate { it.id to it.exercises.size })
        assertEquals(beforePrep.program, draft.program)
        assertEquals(beforePrep.working, draft.working)
        assertTrue(draft.program.days.all { day -> day.exercises.none { it.prescription?.test != null } })
        assertNull(draft.testGoal)
        assertTrue(draft.reasons.none { it is PlanReason.TestPrep })
    }

    @Test fun `a Both plan gets prep slots on its lifting days and a Run plan refuses them`() {
        assertFailsWith<IllegalArgumentException> { answers.copy(branch = PlanBranch.RUN) }
        assertFailsWith<IllegalArgumentException> { answers.copy(branch = PlanBranch.SPRINT) }
        assertFailsWith<IllegalArgumentException> { answers.copy(branch = PlanBranch.BOTH, both = BothAnswers(sprint = true)) }
        for (cardioFirst in listOf(false, true)) for (together in BothAnswers.Together.entries) {
            val both = answers.copy(branch = PlanBranch.BOTH, days = 4,
                run = RunAnswers(RunGoal.FIVE_TO_TEN_K, RunExperience.SOME),
                both = BothAnswers(cardioFirst, together))
            val base = Planner.plan(both.copy(tests = emptyList()))
            val draft = Planner.plan(both)
            val lifting = base.program.days.filter { it.exercises.isNotEmpty() }.take(3).map { it.id }
            assertTrue(lifting.isNotEmpty())
            draft.program.days.forEach { day ->
                assertEquals(if (day.id in lifting) 2 else 0, day.exercises.count { it.prescription?.test != null })
            }
            assertEquals(base.goal, draft.goal)
            assertEquals(base.sessions, draft.sessions)
        }
    }

    @Test fun `every generated prep link passes the Start movement and normalized window checks`() {
        val tests = TestEvents.all.map { event ->
            TestAnswer(TestEventGoal(TestRef(event.id, event.version, TestEvents.window(event, 60)), 40))
        }
        val draft = Planner.plan(plain.copy(tests = tests))
        val linked = draft.program.days.flatMap { it.exercises }.filter { it.prescription?.test != null }
        assertEquals(3 * tests.size, linked.size)
        for (slot in linked) {
            val ref = assertNotNull(slot.prescription?.test)
            val event = assertNotNull(TestEvents.matches(ref))
            assertEquals(event.exerciseId, slot.exerciseId)
            assertEquals(ref.windowSeconds, TestEvents.window(event, ref.windowSeconds))
            assertNotNull(draft.testGoal?.forRef(ref.copy(windowSeconds = TestEvents.window(event, ref.windowSeconds))))
            assertNull(slot.superset)
            assertFalse(slot.keepToPlan)
            assertEquals(ExerciseLibrary.get(event.exerciseId).id, slot.exerciseId)
        }
    }

    @Test fun `balance only days do not consume the three lifting days of prep`() {
        val draft = Planner.plan(answers.copy(days = 2, olderAdult = true, balanceDays = true))
        val balance = draft.program.days.filter { day -> day.exercises.all { it.rule?.role == SlotRole.BALANCE } }
        assertEquals(1, balance.size)
        assertTrue(balance.all { day -> day.exercises.none { it.prescription?.test != null } })
        assertEquals(4, draft.program.days.flatMap { it.exercises }.count { it.prescription?.test != null })
        assertTrue(draft.program.days.all { it.kind == ProgramDayKind.STRENGTH })
    }
}
