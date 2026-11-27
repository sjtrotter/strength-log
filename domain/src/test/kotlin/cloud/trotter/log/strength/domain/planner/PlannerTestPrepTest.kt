package cloud.trotter.log.strength.domain.planner

import cloud.trotter.log.strength.domain.library.ExerciseLibrary
import cloud.trotter.log.strength.domain.model.CardioFamily
import cloud.trotter.log.strength.domain.progression.RecentMaxima
import cloud.trotter.log.strength.domain.model.CardioActivity
import cloud.trotter.log.strength.domain.model.CardioRole
import cloud.trotter.log.strength.domain.model.Measure
import cloud.trotter.log.strength.domain.model.PlanGoal
import cloud.trotter.log.strength.domain.model.Program
import cloud.trotter.log.strength.domain.model.ProgramDay
import cloud.trotter.log.strength.domain.model.SetStructure
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
import cloud.trotter.log.strength.domain.progression.RunSession
import cloud.trotter.log.strength.domain.progression.Scheme
import cloud.trotter.log.strength.domain.progression.SlotRole
import cloud.trotter.log.strength.domain.progression.plannedSeconds
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

    private fun calisthenics(minutes: Int = 60) = SetupAnswers(branch = PlanBranch.BOTH,
        goal = LiftGoal.STRENGTH, days = 3, minutes = minutes,
        both = BothAnswers(true, BothAnswers.Together.SAME_VISIT),
        run = RunAnswers(RunGoal.FITNESS_TEST, RunExperience.SOME, test = FitnessTest.ONE_AND_HALF_MILE,
            goalSeconds = 780, longest = Measure.Time(1500), dateEpochDay = TODAY + 84),
        tests = listOf(TestAnswer(TestEventGoal(TestRef("custom_pushup_window", 1, 60), 30)),
            TestAnswer(TestEventGoal(TestRef("custom_situp_window", 1, 60), 35))), testDateEpochDay = TODAY + 84)

    // The session maximum permits day C's 55 minutes; the nonstop bout remains 25 minutes.
    private val calisthenicsHistory = KnownLoads.NONE.copy(
        longestRecent = mapOf(CardioFamily.RUN to RecentMaxima(null, 3300)),
        longestRunning = mapOf(CardioFamily.RUN to 1500),
        longestBout = mapOf(CardioFamily.RUN to RecentMaxima(null, 1500)))

    private fun prep(day: ProgramDay) = day.exercises.filter { it.prescription?.test != null }

    private fun assertNonPrep(answers: SetupAnswers, allowShortSession: Boolean = true) {
        val base = Planner.plan(answers.copy(tests = emptyList()))
        val draft = Planner.plan(answers)
        for (day in draft.program.days) {
            if (draft.reasons.any { it is PlanReason.ShortSession && it.dayId == day.id }) {
                assertTrue(allowShortSession, "Unexpected ShortSession on ${day.id}")
                continue
            }
            val replaced = prep(day).map { it.exerciseId }.toSet()
            assertEquals(base.program.days.single { it.id == day.id }.exercises.filter { it.exerciseId !in replaced },
                day.exercises.filter { it.prescription?.test == null })
        }
    }

    @Test fun `case 54 a run and calisthenics test is a run-first plan with prep inside the budget`() {
        val answers = calisthenics()
        val draft = Planner.plan(answers, calisthenicsHistory, TODAY)
        assertEquals(listOf(RunSession.EASY, RunSession.TEST_INTERVALS, RunSession.EASY), draft.sessions.values.toList())
        assertEquals(listOf(ProgramDayKind.CARDIO, ProgramDayKind.CARDIO, ProgramDayKind.CARDIO, ProgramDayKind.REST),
            draft.program.days.map { it.kind })
        assertEquals(listOf(3582, 3192, 3600, 0), draft.program.days.map { SessionBudget.seconds(it, answers.slotGoal) })
        val a = draft.program.days[0]
        val b = draft.program.days[1]
        val c = draft.program.days[2]
        assertEquals(Measure.Time(1200), a.cardioBlock!!.sets.single().end)
        assertEquals(Measure.Time(3300), c.cardioBlock!!.sets.single().end)
        assertEquals(900, TestPrep.reservedSeconds(a, answers.slotGoal))
        assertEquals(900, TestPrep.reservedSeconds(b, answers.slotGoal))
        for (day in listOf(a, b)) {
            assertEquals(listOf(PrescribedTarget.RepsInTime(60, 15), PrescribedTarget.RepsInTime(60, 18)),
                prep(day).map { it.prescription!!.target })
            assertEquals(listOf(3, 3), prep(day).map { it.targetSets })
        }
        val intervals = requireNotNull(b.cardioBlock)
        val work = intervals.sets.filter { it.role == CardioRole.WORK }
        assertEquals(852, plannedSeconds(intervals))
        assertEquals(List(4) { Measure.Distance(400) }, work.map { it.end })
        assertEquals(List(4) { Measure.Time(123) }, work.map { it.aim })
        assertEquals(List(3) { Measure.Time(120) }, intervals.sets.filter { it.role == CardioRole.RECOVERY }.map { it.end })
        assertEquals(PlanGoal.Timed(CardioActivity.ROAD_RUN, 2414, 780, TODAY + 84), draft.goal)
        assertEquals(answers.testGoal, draft.testGoal)
        assertTrue(draft.reasons.none { it is PlanReason.ShortSession || it is PlanReason.OverBudget || it == PlanReason.DateTooClose })
        assertEquals(60, Planner.fullDoseMinutes(answers, calisthenicsHistory, TODAY))
    }

    @Test fun `case 55 a four-event test ladders a new runner, preps the push-up and plank and keeps the deadlift goal`() {
        val answers = calisthenics().copy(days = 4, experience = LiftExperience.A_YEAR_OR_MORE,
            run = RunAnswers(RunGoal.FITNESS_TEST, RunExperience.NEW, goalSeconds = 960, dateEpochDay = TODAY + 70),
            tests = listOf(TestAnswer(goal40, 31), TestAnswer(goal220)), testDateEpochDay = TODAY + 70,
            lifts = listOf(LiftTest("trap_dl", 3)))
        val draft = Planner.plan(answers, todayEpochDay = TODAY)
        assertEquals(RunPhase.LADDER, draft.phase)
        assertEquals(3, draft.sessions.size)
        assertEquals(listOf(3330, 3528), draft.program.days.take(2).map { SessionBudget.seconds(it, answers.goal) })
        for (day in draft.program.days.take(2)) {
            assertEquals(1230, TestPrep.reservedSeconds(day, answers.slotGoal))
            assertEquals(listOf(PrescribedTarget.RepsInTime(120, 16), PrescribedTarget.Hold(110)),
                prep(day).map { it.prescription!!.target })
            assertEquals(day.exercises.size, day.exercises.map { it.exerciseId }.distinct().size)
        }
        val deadlift = draft.program.days[1].exercises.single { it.exerciseId == "trap_dl" }
        assertEquals(SlotRole.SECONDARY, deadlift.rule!!.role)
        assertEquals(RepTarget.Fixed(5), deadlift.rule!!.target)
        assertEquals(2, deadlift.targetSets)
        assertTrue(PlanReason.ShortSession("A", 1) in draft.reasons)
        assertTrue(PlanReason.ShortSession("B", 1) in draft.reasons)
        assertTrue(PlanReason.LadderWeeks(8) in draft.reasons)
        assertTrue(PlanReason.DateTooClose in draft.reasons)
    }

    @Test fun `case 56 prep that does not fit reports the day over and is never droppable`() {
        val answers = calisthenics(30)
        val draft = Planner.plan(answers, todayEpochDay = TODAY)
        assertEquals(listOf(2400, 2052, 1800, 0), draft.program.days.map { SessionBudget.seconds(it, answers.slotGoal) })
        assertEquals(Measure.Time(1500), draft.program.days[2].cardioBlock!!.sets.single().end)
        assertEquals(listOf(PlanReason.OverBudget("A", 600), PlanReason.OverBudget("B", 252)),
            draft.reasons.filterIsInstance<PlanReason.OverBudget>())
        assertEquals(listOf(PlanReason.ShortSession("A", 6), PlanReason.ShortSession("B", 6)),
            draft.reasons.filterIsInstance<PlanReason.ShortSession>())
        for (day in draft.program.days.take(2)) {
            assertEquals(prep(day), day.exercises)
            assertEquals(listOf(3, 3), prep(day).map { it.targetSets })
            assertTrue(day.exercises.none { it.rule?.role == SlotRole.MAIN })
        }
    }

    @Test fun `case 57 a zero last count preps the easier movement at the floor`() {
        fun slot(last: Int) = Planner.plan(plain.copy(tests = listOf(TestAnswer(goal40, last))))
            .program.days.first().exercises.single { it.prescription?.test == hrp }
        assertEquals("knee_pushup", slot(0).exerciseId)
        assertEquals(3, slot(0).targetSets)
        assertEquals(PrescribedTarget.RepsInTime(120, 5), slot(0).prescription!!.target)
        assertEquals("hand_release_pushup", slot(12).exerciseId)
        assertEquals(PrescribedTarget.RepsInTime(120, 6), slot(12).prescription!!.target)
        assertEquals("knee_plank", TestPrep.easier("plank"))
        assertEquals("negative_pullup", TestPrep.easier("pullup"))
        assertEquals("negative_pullup", TestPrep.easier("neutral_pullup"))
        assertNull(TestPrep.easier("bw_dip"))
        assertFailsWith<IllegalArgumentException> { TestAnswer(TestEventGoal(TestRef("bw_dip:REPS_CONTINUOUS", 1), 10), 0) }
    }

    @Test fun `case 58 prep is invisible to volume balance`() {
        val answers = plain.copy(days = 6, minutes = 90, tests = listOf(
            TestAnswer(TestEventGoal(TestRef("plank:MAX_HOLD", 1), 220)),
            TestAnswer(TestEventGoal(TestRef("custom_situp_window", 1, 60), 45))))
        assertNonPrep(answers, allowShortSession = false)
        val draft = Planner.plan(answers)
        assertTrue(draft.reasons.none { it is PlanReason.ShortSession })
        assertEquals(listOf(2, 2, 2, 0, 0, 0), draft.program.strengthDays.map { prep(it).size })
        assertTrue(draft.program.days.flatMap(::prep).all { it.targetSets == 3 })
        fun core(program: Program) = program.strengthDays.flatMap { it.exercises }
            .filter { it.prescription?.test == null && it.rule?.role == SlotRole.CORE }.sumOf { it.targetSets }
        assertEquals(10, core(draft.program))
        assertEquals(12, core(Planner.plan(answers.copy(tests = emptyList())).program))
    }

    @Test fun `replacing a push-up main with prep adds no dips or other balancing sets`() {
        val answers = plain.copy(days = 1, minutes = 60, equipment = EquipmentPreset.BODYWEIGHT.equipment,
            tests = listOf(TestAnswer(TestEventGoal(TestRef("custom_pushup_window", 1, 60), 30))))
        val base = Planner.plan(answers.copy(tests = emptyList()))
        val draft = Planner.plan(answers)
        assertTrue(base.program.days.single().exercises.any { it.exerciseId == "pushup" && it.rule?.role == SlotRole.MAIN })
        assertEquals(listOf("pushup"), prep(draft.program.days.single()).map { it.exerciseId })
        for (plan in listOf(base, draft)) {
            assertTrue(plan.reasons.none { it is PlanReason.ShortSession || it is PlanReason.OverBudget })
            assertTrue(plan.program.days.flatMap { it.exercises }.none { it.exerciseId == "bw_dip" || it.exerciseId == "dips" })
        }
        assertNonPrep(answers, allowShortSession = false)
    }

    @Test fun `case 59 a max unbroken prep rule starts at its aim and a missing pull-up bar places no slot`() {
        val ref = TestRef("pullup:REPS_CONTINUOUS", 1)
        val answers = plain.copy(tests = listOf(TestAnswer(TestEventGoal(ref, 20), 8)))
        val draft = Planner.plan(answers)
        val slots = draft.program.days.flatMap(::prep)
        assertEquals(3, slots.size)
        for (slot in slots) {
            assertEquals(PrescribedTarget.Reps(5), slot.prescription!!.target)
            assertEquals(RepTarget.Fixed(5), slot.rule!!.target)
            assertEquals(3, slot.targetSets)
        }
        val missing = Planner.plan(answers.copy(equipment = EquipmentPreset.DUMBBELLS.equipment))
        assertTrue(missing.program.days.flatMap(::prep).isEmpty())
        assertEquals(answers.testGoal, missing.testGoal)
        assertTrue(TestPrep.slots(answers.copy(excluded = setOf("pullup"))).isEmpty())
    }

    @Test fun `case 60 a capped hold seeds from its anchor and an each side hold keeps both sides`() {
        val wall = TestRef("wall_sit:MAX_HOLD", 1)
        val side = TestRef("side_plank:MAX_HOLD", 1)
        val answers = plain.copy(olderAdult = true, tests = listOf(TestAnswer(TestEventGoal(wall, 90), 110),
            TestAnswer(TestEventGoal(side, 60))))
        val draft = Planner.plan(answers)
        for (day in draft.program.strengthDays) {
            val slots = prep(day)
            assertEquals(PrescribedTarget.Hold(55), slots.single { it.exerciseId == "wall_sit" }.prescription!!.target)
            val eachSide = slots.single { it.exerciseId == "side_plank" }
            assertEquals(Prescription(PrescribedTarget.Hold(30), eachSide = true, test = side), eachSide.prescription)
            assertEquals(3, eachSide.targetSets)
            assertEquals(480 + 405, TestPrep.reservedSeconds(day, answers.slotGoal))
        }
    }

    @Test fun `case 61 an under a year lifter's test lift is straight sets with no attempt`() {
        val answers = plain.copy(goal = LiftGoal.STRENGTH, experience = LiftExperience.UNDER_A_YEAR,
            lifts = listOf(LiftTest("bb_bench", 5)))
        val draft = Planner.plan(answers)
        val bench = draft.program.days.flatMap { it.exercises }.single { it.exerciseId == "bb_bench" && it.rule?.role == SlotRole.MAIN }
        assertEquals(SetStructure.STRAIGHT, bench.structure)
        assertEquals(3, bench.targetSets)
        assertEquals(RepTarget.Fixed(5), bench.rule!!.target)
        assertTrue(draft.program.days.flatMap { it.exercises }.none { it.prescription?.test != null })
        val ramped = Planner.plan(answers.copy(experience = LiftExperience.A_YEAR_OR_MORE))
        assertEquals(SetStructure.RAMP_TOP_BACKOFF, ramped.program.days.flatMap { it.exercises }
            .single { it.exerciseId == "bb_bench" && it.rule?.role == SlotRole.MAIN }.structure)
        assertEquals(draft, Planner.plan(answers.copy(lifts = emptyList())))
    }

    @Test fun `a test date inside four weeks adds one close date reason`() {
        assertEquals(4, TestPrep.TEST_WEEKS)
        for (days in listOf(27L, 28L)) {
            val draft = Planner.plan(answers.copy(testDateEpochDay = TODAY + days), todayEpochDay = TODAY)
            assertEquals(if (days == 27L) 1 else 0, draft.reasons.count { it == PlanReason.DateTooClose })
        }
        val run = calisthenics().copy(testDateEpochDay = TODAY + 6,
            run = RunAnswers(RunGoal.FITNESS_TEST, RunExperience.NEW, goalSeconds = 780, dateEpochDay = TODAY + 6))
        assertEquals(1, Planner.plan(run, todayEpochDay = TODAY).reasons.count { it == PlanReason.DateTooClose })
    }

    @Test fun `new easier entries never enter a plan without tests on any preset`() {
        val added = setOf("knee_pushup", "knee_plank", "negative_pullup")
        for (preset in EquipmentPreset.entries) for (goal in LiftGoal.entries) for (days in 1..6) {
            val draft = Planner.plan(SetupAnswers(equipment = preset.equipment, goal = goal, days = days, minutes = 90))
            assertTrue(draft.program.days.flatMap { it.exercises }.none { it.exerciseId in added }, "$preset $goal $days")
        }
        assertEquals(21, ExerciseLibrary.get("knee_pushup").subRank)
        assertEquals(8, ExerciseLibrary.get("knee_plank").subRank)
        assertEquals(13, ExerciseLibrary.get("negative_pullup").subRank)
    }

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
            assertNonPrep(answers.copy(days = count))
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
