package cloud.trotter.log.strength.domain.planner

import cloud.trotter.log.strength.domain.library.ExerciseLibrary
import cloud.trotter.log.strength.domain.model.CardioActivity
import cloud.trotter.log.strength.domain.model.CardioBlock
import cloud.trotter.log.strength.domain.model.CardioFamily
import cloud.trotter.log.strength.domain.model.CardioRole
import cloud.trotter.log.strength.domain.model.CardioSetSpec
import cloud.trotter.log.strength.domain.model.Measure
import cloud.trotter.log.strength.domain.model.MovementPattern
import cloud.trotter.log.strength.domain.model.PlanGoal
import cloud.trotter.log.strength.domain.model.ProgramDay
import cloud.trotter.log.strength.domain.model.ProgramDayKind
import cloud.trotter.log.strength.domain.generator.Rotation
import cloud.trotter.log.strength.domain.progression.CardioHistory
import cloud.trotter.log.strength.domain.progression.CardioRule
import cloud.trotter.log.strength.domain.progression.GoalPace
import cloud.trotter.log.strength.domain.progression.KnownLoads
import cloud.trotter.log.strength.domain.progression.LiftGoal
import cloud.trotter.log.strength.domain.progression.Progression
import cloud.trotter.log.strength.domain.progression.RecentMaxima
import cloud.trotter.log.strength.domain.progression.RepTarget
import cloud.trotter.log.strength.domain.progression.Riegel
import cloud.trotter.log.strength.domain.progression.RunCap
import cloud.trotter.log.strength.domain.progression.RunWalkLadder
import cloud.trotter.log.strength.domain.progression.SlotRole
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

internal const val TODAY = 20_000L
internal fun run(
    goal: RunGoal,
    days: Int = 4,
    minutes: Int = 60,
    experience: RunExperience = RunExperience.SOME,
    runAnswers: RunAnswers = RunAnswers(goal, experience),
) = SetupAnswers(branch = PlanBranch.RUN, days = days, minutes = minutes, run = runAnswers)

internal fun known(metres: Int?, seconds: Int?) =
    KnownLoads.NONE.copy(longestRecent = mapOf(CardioFamily.RUN to RecentMaxima(metres, seconds)))

class PlannerRunTest {
    private fun time(seconds: Int, hard: Boolean = false, role: CardioRole = CardioRole.WORK) =
        CardioSetSpec(role, Measure.Time(seconds), hard = hard)
    private fun distance(metres: Int, aim: Int? = null, hard: Boolean = false) =
        CardioSetSpec(CardioRole.WORK, Measure.Distance(metres), aim?.let { Measure.Time(it) }, hard)
    private fun block(vararg sets: CardioSetSpec) = CardioBlock(CardioActivity.ROAD_RUN, sets.toList())
    private fun day(draft: PlanDraft, id: String) = draft.program.days.single { it.id == id }
    private fun work(day: ProgramDay) = requireNotNull(day.cardioBlock).sets.filter { it.role == CardioRole.WORK }
    private fun plan(answers: RunAnswers, known: KnownLoads = KnownLoads.NONE, days: Int = 4, minutes: Int = 60) =
        Planner.plan(run(answers.goal, days, minutes, answers.experience, answers), known, TODAY)
    private fun half() = RunAnswers(RunGoal.HALF_MARATHON, RunExperience.SOME)

    @Test fun `half at four sixty minute days stores three easy sessions and two strength blocks`() {
        val draft = plan(half(), known(12_000, 4_200))
        assertEquals(listOf("A", "B", "C", "D", "E"), draft.program.days.map { it.id })
        assertEquals(List(4) { ProgramDayKind.CARDIO } + ProgramDayKind.REST, draft.program.days.map { it.kind })
        assertEquals(listOf(RunSession.EASY, RunSession.THRESHOLD, RunSession.EASY, RunSession.LONG), draft.sessions.values.toList())
        assertEquals(RunPhase.HALF_BUILD, draft.phase)
        assertEquals(3, draft.sessions.values.count { !it.quality })
        assertEquals(listOf("A", "C"), draft.program.days.filter { it.exercises.isNotEmpty() }.map { it.id })
        assertEquals(block(time(1800)), day(draft, "A").cardioBlock)
        assertEquals(block(time(1200, hard = true)), day(draft, "B").cardioBlock)
        assertEquals(block(distance(13_200)), day(draft, "D").cardioBlock)
        assertEquals(13_200, RunCap.limit(12_000, 100))
        assertEquals(listOf(3330, 1500, 3330), draft.program.days.take(3).map { SessionBudget.seconds(it, LiftGoal.STRENGTH) })
        for ((id, main, calf, core) in listOf(
            listOf("A", "bb_back_squat", "CALF_GASTROC", "CORE_ANTI_EXT"),
            listOf("C", "trap_dl", "CALF_SOLEUS", "CORE_ANTI_ROT"),
        )) {
            val slots = day(draft, id).exercises
            assertEquals(main, slots[0].exerciseId)
            assertEquals("nordic", slots[3].exerciseId)
            assertEquals(listOf(SlotRole.SECONDARY, SlotRole.ACCESSORY, SlotRole.ISOLATION, SlotRole.ISOLATION, SlotRole.CORE),
                slots.map { it.rule!!.role })
            assertEquals(listOf(MovementPattern.SINGLE_LEG, MovementPattern.valueOf(calf), MovementPattern.KNEE_FLEXION,
                MovementPattern.valueOf(core)), slots.drop(1).map { ExerciseLibrary.get(it.exerciseId).pattern })
            for ((index, slot) in slots.withIndex()) {
                assertEquals(2, slot.targetSets)
                assertTrue(draft.working.getValue(SlotKey(id, index)).isNotEmpty())
            }
            assertEquals(RepTarget.Fixed(5), slots.first().rule!!.target)
        }
        assertEquals(listOf(PlanReason.RunCapped("D")), draft.reasons)
        assertNull(draft.goal)
    }

    @Test fun `case 17 history wins over the setup seed and caps the base long run`() {
        val answers = half().copy(longest = Measure.Distance(8050))
        for ((history, metres) in listOf(known(8050, 2900) to 8800, KnownLoads.NONE to 8800, known(6000, 2100) to 6600)) {
            val draft = plan(answers, history)
            assertEquals(RunPhase.BASE, draft.phase)
            assertEquals(block(distance(metres)), day(draft, "D").cardioBlock)
            assertTrue(PlanReason.RunCapped("D") in draft.reasons)
        }
        val history = CardioHistory(seed = answers.seed(), planCreatedEpochDay = TODAY)
        assertEquals(RecentMaxima(8050, null), RunCap.anchor(history, TODAY + 10))
        assertNull(RunCap.anchor(history, TODAY + 30))
    }

    @Test fun `case 19 stored long run opens through the existing taper`() {
        val draft = plan(half().copy(goalSeconds = 6300, dateEpochDay = TODAY + 12), known(17_000, 6000))
        assertEquals(PlanGoal.Timed(CardioActivity.ROAD_RUN, 21_097, 6300, TODAY + 12), draft.goal)
        assertEquals(block(distance(18_000)), day(draft, "D").cardioBlock)
        assertFalse(draft.reasons.any { it is PlanReason.RunCapped })
        assertTrue(PlanReason.DateTooClose in draft.reasons)
        fun opening(id: String, today: Long) = Progression.cardioAtStart(requireNotNull(day(draft, id).cardioBlock), null,
            CardioHistory(), draft.goal, Progression.CardioContext(today, null, 0))
        assertEquals(block(distance(12_600)), opening("D", TODAY))
        assertEquals(block(distance(9000)), opening("D", TODAY + 7))
        assertTrue(opening("B", TODAY).sets.single().hard)
    }

    @Test fun `case 20 goal check rounds once and excludes ultras`() {
        val ten = RunAnswers(RunGoal.FIVE_TO_TEN_K, RunExperience.SOME, RunEvent.K10,
            goalSeconds = 3000, recent = RecentRace(5000, 1500))
        assertTrue(PlanReason.GoalCheck(3127) in plan(ten).reasons)
        assertFalse(plan(ten.copy(goalSeconds = 3200)).reasons.any { it is PlanReason.GoalCheck })
        val marathon = RunAnswers(RunGoal.MARATHON, RunExperience.SOME, goalSeconds = 13_500, recent = RecentRace(21_097, 6600))
        assertTrue(PlanReason.GoalCheck(14_449) in plan(marathon).reasons)
        assertFalse(plan(RunAnswers(RunGoal.ULTRA, goalSeconds = 20_000, recent = RecentRace(21_097, 6600)))
            .reasons.any { it is PlanReason.GoalCheck })
    }

    @Test fun `case 21 field tests carry aims and supported goal forms`() {
        val answers = RunAnswers(RunGoal.FITNESS_TEST, RunExperience.SOME, goalSeconds = 960)
        val draft = plan(answers, days = 3)
        assertEquals(RunSession.TEST_INTERVALS, draft.sessions["B"])
        val sets = requireNotNull(day(draft, "B").cardioBlock).sets
        assertEquals(7, sets.size)
        assertEquals(List(4) { distance(400, 113, true) }, work(day(draft, "B")))
        assertEquals(List(3) { time(120, role = CardioRole.RECOVERY) }, sets.filter { it.role == CardioRole.RECOVERY })
        assertEquals(PlanGoal.Timed(CardioActivity.ROAD_RUN, 3219, 960), draft.goal)
        val cooper = plan(answers.copy(test = FitnessTest.COOPER, testScore = 2800), days = 3)
        assertEquals(98, GoalPace.aimSeconds(720.0, 2800.0, 400, GoalPace.TEST_FACTOR))
        assertTrue(work(day(cooper, "B")).all { it.aim == Measure.Time(98) })
        assertNull(cooper.goal)
        val pacer = plan(answers.copy(test = FitnessTest.PACER, testScore = 60), days = 3)
        assertTrue(work(day(pacer, "B")).all { it.aim == null })
        assertEquals(PlanGoal.Shuttle("PACER", 60), pacer.goal)
        assertEquals(PlanGoal.Shuttle("HAMR", 60), plan(answers.copy(test = FitnessTest.HAMR, testScore = 60)).goal)
        assertEquals(PlanGoal.Timed(CardioActivity.ROAD_RUN, 2414, 960), plan(answers.copy(test = FitnessTest.ONE_AND_HALF_MILE)).goal)
    }

    @Test fun `case 24 run letters rotate through rest and keep the anchor unit`() {
        val answers = RunAnswers(RunGoal.FIVE_TO_TEN_K, RunExperience.SOME)
        val draft = plan(answers, known(8050, 2900), days = 3)
        assertEquals(listOf(RunSession.EASY, RunSession.THRESHOLD, RunSession.LONG), draft.sessions.values.toList())
        assertEquals(listOf("A", "B"), draft.program.days.filter { it.exercises.isNotEmpty() }.map { it.id })
        assertEquals(block(distance(8800)), day(draft, "C").cardioBlock)
        val timed = plan(answers.copy(longest = Measure.Time(2900)), days = 3)
        assertEquals(block(time(3180)), day(timed, "C").cardioBlock)
        assertEquals("B", Rotation.upNextAfter(draft.program, "A", "A"))
        assertEquals("C", Rotation.upNextAfter(draft.program, "B", "B"))
        assertEquals("D", Rotation.upNextAfter(draft.program, "C", "C"))
        assertEquals("A", Rotation.trainingDayFrom(draft.program, "D"))
        assertEquals("A", draft.program.dayToShow("D")?.id)
    }

    @Test fun `case 29 middle distance uses dated race reps and unrounded predictions`() {
        val answers = RunAnswers(RunGoal.MIDDLE_DISTANCE, RunExperience.SOME, RunEvent.M800, goalSeconds = 120, dateEpochDay = TODAY + 30)
        val late = plan(answers)
        assertEquals(RunSession.RACE_PACE, late.sessions["B"])
        assertEquals(List(3) { distance(300, 45, true) }, work(day(late, "B")))
        assertEquals(List(2) { time(240, role = CardioRole.RECOVERY) }, day(late, "B").cardioBlock!!.sets.filter { it.role == CardioRole.RECOVERY })
        for (date in listOf(TODAY + 60, null)) {
            val early = day(plan(answers.copy(dateEpochDay = date)), "B")
            assertEquals(List(6) { distance(400, 60, true) }, work(early))
            assertEquals(List(5) { time(120, role = CardioRole.RECOVERY) }, early.cardioBlock!!.sets.filter { it.role == CardioRole.RECOVERY })
        }
        val fifteen = answers.copy(event = RunEvent.M1500, goalSeconds = 270)
        assertEquals(List(6) { distance(400, 72, true) }, work(day(plan(fifteen), "B")))
        val predicted = plan(fifteen.copy(goalSeconds = null, recent = RecentRace(5000, 1200)))
        val aim = Math.round(Riegel.predictedSeconds(5000.0, 1200.0, 1500.0) * 400 / 1500).toInt()
        assertEquals(89, aim)
        assertEquals(List(6) { distance(400, aim, true) }, work(day(predicted, "B")))
    }

    @Test fun `case 29 ultra long forms start at the target and obey the cap`() {
        val answers = RunAnswers(RunGoal.ULTRA, RunExperience.SOME)
        val history = known(30_000, 10_800)
        val fifty = plan(answers, history)
        assertEquals(RunPhase.ULTRA_BUILD, fifty.phase)
        assertEquals(block(distance(30_000)), day(fifty, "D").cardioBlock)
        assertFalse(fifty.reasons.any { it is PlanReason.RunCapped })
        val hundred = plan(answers.copy(event = RunEvent.K100), history)
        assertEquals(block(time(11_880)), day(hundred, "D").cardioBlock)
        assertTrue(PlanReason.RunCapped("D") in hundred.reasons)
        val fiftyMiles = plan(answers.copy(event = RunEvent.MI50), known(50_000, 20_000))
        assertEquals(block(distance(48_280)), day(fiftyMiles, "D").cardioBlock)
        assertEquals(block(time(14_400)), day(plan(answers.copy(event = RunEvent.MI50, longest = Measure.Time(20_000))), "D").cardioBlock)
        assertEquals(block(time(14_400)), day(plan(answers.copy(event = RunEvent.MI100), known(50_000, 20_000)), "D").cardioBlock)
    }

    @Test fun `case 30 marathon from no longest starts on the untrimmed ladder`() {
        val answers = RunAnswers(RunGoal.MARATHON, RunExperience.NEW, dateEpochDay = TODAY + 210)
        val draft = plan(answers)
        assertEquals(RunPhase.LADDER, draft.phase)
        assertEquals(List(3) { ProgramDayKind.CARDIO } + ProgramDayKind.REST, draft.program.days.map { it.kind })
        for (day in draft.program.cardioDays) {
            assertEquals(RunWalkLadder.block(1, CardioActivity.ROAD_RUN), day.cardioBlock)
            assertEquals(CardioRule(1), day.cardioRule)
        }
        assertEquals(listOf("A", "B"), draft.program.days.filter { it.exercises.isNotEmpty() }.map { it.id })
        assertFalse(draft.reasons.any { it is PlanReason.RunCapped || it == PlanReason.DateTooClose })
        assertTrue(PlanReason.DateTooClose in plan(answers.copy(dateEpochDay = TODAY + 56)).reasons)
    }

    @Test fun `readiness selects the first unmet single run gate`() {
        val marathon = RunAnswers(RunGoal.MARATHON, RunExperience.SOME)
        for ((history, expected) in listOf(KnownLoads.NONE to RunPhase.BASE, known(9000, 3000) to RunPhase.BASE,
            known(12_000, 4200) to RunPhase.HALF_BUILD, known(22_000, 8000) to RunPhase.MARATHON_BUILD)) {
            assertEquals(expected, plan(marathon, history).phase)
        }
        assertEquals(RunPhase.ULTRA_BUILD, plan(RunAnswers(RunGoal.ULTRA, marathonBestSeconds = 15_000), known(22_000, 8000)).phase)
        val novice = marathon.copy(experience = RunExperience.NEW, longest = Measure.Time(1200))
        val draft = plan(novice)
        assertEquals(RunPhase.LADDER, draft.phase)
        assertEquals(CardioRule(10), day(draft, "A").cardioRule)
        assertEquals(block(time(1200)), day(draft, "A").cardioBlock)
        assertEquals(CardioRule(9), day(plan(novice.copy(runningInjury = true)), "A").cardioRule)
        assertEquals(RunPhase.BASE, plan(novice.copy(longest = Measure.Distance(6000))).phase)
        for (goal in listOf(RunGoal.MIDDLE_DISTANCE, RunGoal.FITNESS_TEST, RunGoal.CARDIO_FITNESS)) assertNull(plan(RunAnswers(goal)).phase)
    }

    @Test fun `Q2 follows the five and six day rows with the first requested strength flags`() {
        val answers = RunAnswers(RunGoal.FIVE_TO_TEN_K, RunExperience.SOME)
        val history = known(8050, 2900)
        val five = plan(answers, history, days = 5)
        assertEquals(listOf(RunSession.EASY, RunSession.THRESHOLD, RunSession.EASY, RunSession.VO2MAX, RunSession.LONG), five.sessions.values.toList())
        assertEquals(listOf("A", "C"), five.program.days.filter { it.exercises.isNotEmpty() }.map { it.id })
        val novice = plan(answers.copy(experience = RunExperience.NEW), history, days = 5)
        assertEquals(listOf(RunSession.EASY, RunSession.THRESHOLD, RunSession.EASY, RunSession.EASY, RunSession.LONG), novice.sessions.values.toList())
        assertEquals(listOf("A", "D"), novice.program.days.filter { it.exercises.isNotEmpty() }.map { it.id })
        val six = plan(answers, history, days = 6)
        assertEquals(RunSession.VO2MAX, six.sessions["D"])
        assertEquals(listOf("A", "E"), six.program.days.filter { it.exercises.isNotEmpty() }.map { it.id })
        assertEquals(1, plan(answers, history).sessions.values.count { it.quality })
        assertFalse(RunSession.VO2MAX in plan(half(), known(12_000, 4200), days = 6).sessions.values)
        val cardio = plan(RunAnswers(RunGoal.CARDIO_FITNESS, RunExperience.SOME), days = 5)
        assertTrue(cardio.sessions.values.all { it == RunSession.EASY })
        assertEquals(listOf("A", "C"), cardio.program.days.filter { it.exercises.isNotEmpty() }.map { it.id })
        for (blocks in 0..2) {
            val two = plan(answers.copy(strengthBlocks = blocks), history, days = 2)
            assertEquals(listOf(RunSession.EASY, RunSession.LONG), two.sessions.values.toList())
            assertEquals(listOf("A", "B").take(blocks), two.program.days.filter { it.exercises.isNotEmpty() }.map { it.id })
        }
    }

    @Test fun `minutes drop interval rounds and fit strength without trimming the long run`() {
        val draft = plan(RunAnswers(RunGoal.FIVE_TO_TEN_K, RunExperience.SOME), days = 6, minutes = 30)
        assertEquals(4, work(day(draft, "D")).size)
        assertEquals(1560, SessionBudget.seconds(day(draft, "D"), LiftGoal.STRENGTH))
        assertEquals(block(time(1200)), day(draft, "A").cardioBlock)
        assertTrue(draft.reasons.any { it is PlanReason.ShortSession && it.dayId == "A" })
        assertFalse(draft.reasons.any { it is PlanReason.OverBudget && it.dayId == "F" })
        val cardio = plan(RunAnswers(activity = CardioActivity.STATIONARY_BIKE), days = 3, minutes = 30)
        assertTrue(cardio.sessions.values.all { it == RunSession.EASY })
        assertTrue(cardio.program.cardioDays.all { it.cardioBlock!!.activity == CardioActivity.STATIONARY_BIKE })
        assertEquals(listOf(time(1500)), day(cardio, "C").cardioBlock!!.sets)
    }

    @Test fun `D14 easy runs stop at whole minutes below the stored long run`() {
        val draft = plan(half(), known(12_000, 4200), minutes = 90)
        assertEquals(block(distance(13_200)), day(draft, "D").cardioBlock)
        for (id in listOf("A", "C")) {
            assertEquals(block(time(3540)), day(draft, id).cardioBlock)
            assertEquals(5070, SessionBudget.seconds(day(draft, id), LiftGoal.STRENGTH))
        }
        val timed = plan(RunAnswers(RunGoal.FIVE_TO_TEN_K, RunExperience.NEW, longest = Measure.Time(2900)), days = 5, minutes = 90)
        assertEquals(block(time(3180)), day(timed, "E").cardioBlock)
        for (id in listOf("A", "C", "D")) assertEquals(block(time(2340)), day(timed, id).cardioBlock)
        val middle = plan(RunAnswers(RunGoal.MIDDLE_DISTANCE, longest = Measure.Time(1200)), minutes = 90)
        assertEquals(block(time(960)), day(middle, "A").cardioBlock)
    }

    @Test fun `older run strength starts with balance and keeps three reps in reserve`() {
        val draft = Planner.plan(run(RunGoal.HALF_MARATHON).copy(olderAdult = true), known(12_000, 4200), TODAY)
        assertEquals(listOf("single_leg_stance", "farmers_carry"), day(draft, "A").exercises.take(2).map { it.exerciseId })
        for (day in draft.program.cardioDays) for (slot in day.exercises) assertTrue(slot.rule!!.rir >= 3)
    }

    @Test fun `day filters plan reasons and dates require an explicit today`() {
        val answers = run(RunGoal.HALF_MARATHON)
        assertEquals(listOf(PlanReason.RunCapped("D")), Planner.day(answers, "D", known(12_000, 4200), TODAY)!!.reasons)
        assertTrue(Planner.day(answers, "B", known(12_000, 4200), TODAY)!!.reasons.isEmpty())
        assertNull(Planner.day(answers, "Z", known(12_000, 4200), TODAY))
        val dated = answers.copy(run = answers.run.copy(dateEpochDay = TODAY + 12, goalSeconds = 6000, recent = RecentRace(5000, 1800)))
        assertFailsWith<IllegalArgumentException> { Planner.plan(dated) }
        assertTrue(Planner.day(dated, "B", known(12_000, 4200), TODAY)!!.reasons.isEmpty())
        Planner.plan(dated.copy(branch = PlanBranch.LIFT))
    }

    @Test fun `run answers validate inputs and resolve the documented defaults`() {
        assertEquals(RunGoal.CARDIO_FITNESS, RunAnswers().goal)
        assertEquals(RunEvent.M1500, RunAnswers(RunGoal.MIDDLE_DISTANCE).resolvedEvent)
        assertEquals(RunEvent.K50, RunAnswers(RunGoal.ULTRA).resolvedEvent)
        assertEquals(RunEvent.K5, RunAnswers(RunGoal.FIVE_TO_TEN_K).resolvedEvent)
        assertEquals(FitnessTest.TWO_MILE, RunAnswers(RunGoal.FITNESS_TEST).test)
        assertNull(plan(RunAnswers(goalSeconds = 1000)).goal)
        assertFailsWith<IllegalArgumentException> { RunAnswers(RunGoal.HALF_MARATHON, event = RunEvent.K5) }
        for (seconds in listOf(0, 86_401)) assertFailsWith<IllegalArgumentException> { RunAnswers(goalSeconds = seconds) }
        assertFailsWith<IllegalArgumentException> { RunAnswers(testScore = 0) }
        assertFailsWith<IllegalArgumentException> { RunAnswers(dateEpochDay = Long.MAX_VALUE) }
        for (measure in listOf(Measure.Time(0), Measure.Distance(0))) assertFailsWith<IllegalArgumentException> { RunAnswers(longest = measure) }
        assertFailsWith<IllegalArgumentException> { RunAnswers(marathonBestSeconds = 1000) }
        for (blocks in listOf(-1, 3)) assertFailsWith<IllegalArgumentException> { RunAnswers(strengthBlocks = blocks) }
        assertFailsWith<IllegalArgumentException> { RunAnswers(RunGoal.MARATHON, activity = CardioActivity.STATIONARY_BIKE) }
        assertFailsWith<IllegalArgumentException> { RecentRace(0, 100) }
        assertFailsWith<IllegalArgumentException> { RecentRace(5000, 86_401) }
    }
}
