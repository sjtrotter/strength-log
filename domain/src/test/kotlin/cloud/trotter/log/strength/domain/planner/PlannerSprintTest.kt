package cloud.trotter.log.strength.domain.planner

import cloud.trotter.log.strength.domain.progression.plannedSeconds
import cloud.trotter.log.strength.domain.library.ExerciseLibrary
import cloud.trotter.log.strength.domain.model.MovementPattern
import cloud.trotter.log.strength.domain.model.CardioActivity
import cloud.trotter.log.strength.domain.model.CardioBlock
import cloud.trotter.log.strength.domain.model.CardioRole
import cloud.trotter.log.strength.domain.model.CardioSetSpec
import cloud.trotter.log.strength.domain.model.Measure
import cloud.trotter.log.strength.domain.model.PlanGoal
import cloud.trotter.log.strength.domain.model.ProgramDayKind
import cloud.trotter.log.strength.domain.progression.CardioRule
import cloud.trotter.log.strength.domain.progression.LiftGoal
import cloud.trotter.log.strength.domain.progression.RepTarget
import cloud.trotter.log.strength.domain.progression.RunRange
import cloud.trotter.log.strength.domain.progression.RunSession
import cloud.trotter.log.strength.domain.progression.SlotRole
import cloud.trotter.log.strength.domain.progression.sprintBlock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

internal fun sprint(
    event: SprintEvent = SprintEvent.M100,
    days: Int = 3,
    minutes: Int = 60,
    answers: SprintAnswers = SprintAnswers(event),
) = SetupAnswers(branch = PlanBranch.SPRINT, days = days, minutes = minutes, sprint = answers)

class PlannerSprintTest {
    private fun day(draft: PlanDraft, id: String) = draft.program.days.single { it.id == id }
    private fun ids(draft: PlanDraft, id: String) = day(draft, id).exercises.map { it.exerciseId }
    private fun roles(draft: PlanDraft, id: String) = day(draft, id).exercises.map { requireNotNull(it.rule).role }
    private fun seconds(draft: PlanDraft) = draft.program.days.map { SessionBudget.seconds(it, LiftGoal.STRENGTH) }
    private fun intervals(rounds: Int, metres: Int, recovery: Int, warmup: Boolean = true, hard: Boolean = true) =
        CardioBlock(CardioActivity.TRACK_RUN, buildList<CardioSetSpec> {
            if (warmup) add(CardioSetSpec(CardioRole.WARMUP, Measure.Time(600)))
            repeat(rounds) { index ->
                if (index > 0) add(CardioSetSpec(CardioRole.RECOVERY, Measure.Time(recovery)))
                add(CardioSetSpec(CardioRole.WORK, Measure.Distance(metres), hard = hard))
            }
        })
    private val acc0 = intervals(4, 10, 60)
    private val acc5 = intervals(8, 20, 120)
    private val maxv0 = intervals(3, 10, 240)
    // audit 2026-11-13, row 7: intro = 2*100 = 200 m; non-intro = 200+5*20 = 300 m, with 600 s rests.
    private val se0 = intervals(2, 100, 600)
    private val se5 = intervals(3, 100, 600)
    private val tempo0 = intervals(6, 100, 60, warmup = false, hard = false)

    @Test fun `a 100 m plan at two days matches the table`() {
        val draft = Planner.plan(sprint(days = 2))
        // sprint week design §2.2: two high days + two rest entries = four entries.
        assertEquals(listOf("A", "B", "C", "D"), draft.program.days.map { it.id })
        // sprint week design §2.2: high + rest + high + rest = four entries.
        assertEquals(listOf(ProgramDayKind.CARDIO, ProgramDayKind.REST, ProgramDayKind.CARDIO, ProgramDayKind.REST), draft.program.days.map { it.kind })
        assertEquals(mapOf("A" to RunSession.ACCELERATION, "C" to RunSession.MAX_VELOCITY), draft.sessions)
        assertEquals(acc0, day(draft, "A").cardioBlock)
        assertEquals(maxv0, day(draft, "C").cardioBlock)
        assertEquals(CardioRule(null, RunSession.ACCELERATION, intro = 3), day(draft, "A").cardioRule)
        assertEquals(CardioRule(null, RunSession.MAX_VELOCITY, intro = 3), day(draft, "C").cardioRule)
        // sprint week design §2.2: A has two plyos + squat + single-leg + row 2 main.
        assertEquals(listOf("pogo_hop", "a_skip", "bb_back_squat", "bss", "bb_bench"), ids(draft, "A"))
        // sprint week design §2.2: two plyos + two mains + one accessory = five slots.
        assertEquals(listOf(SlotRole.PLYO, SlotRole.PLYO, SlotRole.MAIN, SlotRole.ACCESSORY, SlotRole.MAIN), roles(draft, "A"))
        // sprint week design §2.2: C has two plyos + hinge + rows 3 and 4 + Nordic.
        assertEquals(listOf("pogo_hop", "a_skip", "trap_dl", "ohp", "bb_row", "nordic"), ids(draft, "C"))
        // sprint week design §2.2: two plyos + three mains + one Nordic = six slots.
        assertEquals(List(2) { SlotRole.PLYO } + List(3) { SlotRole.MAIN } + SlotRole.ISOLATION, roles(draft, "C"))
        assertEquals(listOf(2, 1), day(draft, "C").exercises.filter { it.rule?.role == SlotRole.PLYO }.map { it.targetSets })
        assertTrue(draft.reasons.isEmpty())
        assertEquals(PlanGoal.Sprint(100), draft.goal)
        assertNull(draft.phase)
        // sprint week design §2.2: A=794+495+630+387+630=2936; C=1091+375+9*210+174=3530.
        assertEquals(listOf(2936, 0, 3530, 0), seconds(draft))
    }

    @Test fun `a 100 m plan at three days matches the table`() {
        val draft = Planner.plan(sprint())
        assertEquals(listOf("A", "B", "C", "D", "E"), draft.program.days.map { it.id })
        assertEquals(listOf(ProgramDayKind.CARDIO, ProgramDayKind.STRENGTH, ProgramDayKind.REST,
            ProgramDayKind.CARDIO, ProgramDayKind.REST), draft.program.days.map { it.kind })
        // sprint week design §2.2: B holds three upper mains, zero lower lifts.
        assertEquals("Upper", day(draft, "B").title)
        // sprint week design §2.2: B holds rows 2 through 4 = three mains.
        assertEquals(listOf("bb_bench", "ohp", "bb_row"), ids(draft, "B"))
        // sprint week design §2.2: A has two plyos + squat + single-leg = four slots.
        assertEquals(listOf("pogo_hop", "a_skip", "bb_back_squat", "bss"), ids(draft, "A"))
        // sprint week design §2.2: D has two plyos + hinge + Nordic = four slots.
        assertEquals(listOf("pogo_hop", "a_skip", "trap_dl", "nordic"), ids(draft, "D"))
        assertEquals(mapOf("A" to RunSession.ACCELERATION, "D" to RunSession.MAX_VELOCITY), draft.sessions)
        assertEquals(acc0, day(draft, "A").cardioBlock)
        assertEquals(maxv0, day(draft, "D").cardioBlock)
        // sprint week design §2.2: A=794+495+630+387=2306; B=300+9*210=2190; D=1091+375+630+174=2270.
        assertEquals(listOf(2306, 2190, 0, 2270, 0), seconds(draft))
        assertTrue(draft.reasons.isEmpty())
    }

    @Test fun `a 100 m plan at four days matches the table`() {
        val draft = Planner.plan(sprint(days = 4))
        // sprint week design §2.2: four training + two rest entries = six entries.
        assertEquals(listOf("A", "B", "C", "D", "E", "F"), draft.program.days.map { it.id })
        // sprint week design §2.2: HA U R HB U R = six entries.
        assertEquals(listOf(ProgramDayKind.CARDIO, ProgramDayKind.STRENGTH, ProgramDayKind.REST,
            ProgramDayKind.CARDIO, ProgramDayKind.STRENGTH, ProgramDayKind.REST), draft.program.days.map { it.kind })
        // sprint week design §2.2: B holds rows 2 through 4 = three mains.
        assertEquals(listOf("bb_bench", "ohp", "bb_row"), ids(draft, "B"))
        // sprint week design §2.2: E holds three upper mains; Nordic moves to max velocity.
        assertEquals(listOf("bb_bench", "ohp", "bb_row"), ids(draft, "E"))
        // sprint week design §2.2: A=794+495+630+387; B=E=300+1890; D=1091+375+630+174.
        assertEquals(listOf(2306, 2190, 0, 2270, 2190, 0), seconds(draft))
        assertTrue(draft.reasons.isEmpty())
    }

    @Test fun `the other events keep the table`() {
        for (event in SprintEvent.entries) for (days in 2..4) {
            // sprint week design §2.2: two event groups swap hinge and squat between high days.
            val reference = Planner.plan(sprint(if (event in listOf(SprintEvent.M200, SprintEvent.M400)) SprintEvent.M200
                else SprintEvent.M100, days = days, minutes = 75))
            val draft = Planner.plan(sprint(event, days, minutes = 75))
            assertTrue(draft.reasons.isEmpty(), "$event $days reasons")
            assertTrue(seconds(draft).all { it <= 4500 }, "$event $days budget")
            // sprint week design §2.2: both event groups retain the same count of high, Upper and rest entries.
            assertEquals(Planner.plan(sprint(days = days, minutes = 75)).program.days.map { it.kind }, draft.program.days.map { it.kind }, "$event $days kinds")
            assertEquals(reference.program.days.map { it.exercises.map { slot -> slot.exerciseId } },
                draft.program.days.map { it.exercises.map { slot -> slot.exerciseId } }, "$event $days lifts")
            assertEquals(PlanGoal.Sprint(event.metres), draft.goal)
            if (event in listOf(SprintEvent.M200, SprintEvent.M400)) {
                val cardio = draft.program.cardioDays
                assertEquals(maxv0, cardio[0].cardioBlock)
                assertEquals(CardioRule(null, RunSession.MAX_VELOCITY, intro = 3), cardio[0].cardioRule)
                // sprint week design §2.2: two-day A=1091+495+630+630+174=3020; C=1272+375+630+387+630+630=3924.
                assertEquals(se0, cardio[1].cardioBlock)
                assertEquals(CardioRule(null, RunSession.SPRINT_ENDURANCE, intro = 3), cardio[1].cardioRule)
                assertEquals(listOf(RunSession.MAX_VELOCITY, RunSession.SPRINT_ENDURANCE), draft.sessions.values.toList())
            } else {
                assertEquals(reference.program.days.map { it.cardioBlock }, draft.program.days.map { it.cardioBlock })
                assertEquals(reference.sessions, draft.sessions)
            }
        }
        assertEquals(37, SprintEvent.YD40.metres)
    }

    @Test fun `200 and 400 m non-intro at two days fit sixty minutes at ladder step three`() {
        for (event in listOf(SprintEvent.M200, SprintEvent.M400)) {
            val recent = SprintAnswers(event, sprintedRecently = true)
            // sprint week design §3.4: C=4560 > 75*60=4500; 90*60=5400 fits.
            val reference = Planner.plan(sprint(event, days = 2, minutes = 90, answers = recent))
            val draft = Planner.plan(sprint(event, days = 2, minutes = 60, answers = recent))
            assertTrue(reference.reasons.isEmpty(), "$event reference reasons")
            // sprint week design §2.2: A=1091+495+1260+174=3020; C=1908+375+1890+387=4560.
            assertEquals(listOf(3020, 0, 4560, 0), seconds(reference), "$event reference seconds")
            assertEquals(listOf(PlanReason.ShortSession("C", 3)), draft.reasons, "$event reasons")
            // sprint week design §3.4: C step 1=4560-387=4173; step 3=1908+375+3*2*210=3543.
            assertEquals(listOf(3020, 0, 3543, 0), seconds(draft), "$event seconds")
            // sprint week design §2.2: the two rest entries and max-velocity A are unchanged.
            for (id in listOf("A", "B", "D")) {
                assertEquals(day(reference, id), day(draft, id), "$event $id unchanged")
            }
            // sprint week design §3.4: step 1 removes one BSS; step 3 keeps two plyos + three mains.
            assertEquals(listOf("pogo_hop", "a_skip", "bb_back_squat", "ohp", "bb_row"), ids(draft, "C"), "$event C lifts")
            // sprint week design §3.4: two plyos + three mains = five slots.
            assertEquals(List(2) { SlotRole.PLYO } + List(3) { SlotRole.MAIN }, roles(draft, "C"), "$event C roles")
            // sprint week design §3.4: C=1908+2*120+135+6*210=3543.
            assertEquals(listOf(2, 1, 2, 2, 2), day(draft, "C").exercises.map { it.targetSets }, "$event C sets")
            assertEquals(day(reference, "C").cardioBlock, day(draft, "C").cardioBlock, "$event C block")
            assertEquals(day(reference, "C").cardioRule, day(draft, "C").cardioRule, "$event C rule")
            assertEquals(reference.sessions, draft.sessions, "$event sessions")
        }
    }

    @Test fun `200 and 400 m intro at two days drop only the single-leg accessory at sixty minutes`() {
        for (event in listOf(SprintEvent.M200, SprintEvent.M400)) {
            val draft = Planner.plan(sprint(event, days = 2))
            // sprint week design §3.4: C full=3924 > 3600; removing 3*129 BSS seconds leaves 3537.
            assertEquals(listOf(PlanReason.ShortSession("C", 1)), draft.reasons)
            // sprint week design §2.2: A=1091+495+1260+174=3020; C=1272+375+1890=3537.
            assertEquals(listOf(3020, 0, 3537, 0), seconds(draft))
            assertEquals(se0, day(draft, "C").cardioBlock)
            // sprint week design §3.4: step 1 keeps two plyos + three mains, removing BSS.
            assertEquals(listOf("pogo_hop", "a_skip", "bb_back_squat", "ohp", "bb_row"), ids(draft, "C"))
            // sprint week design §3.4: 2*120+135+9*210=2265 lift seconds; 1272+2265=3537.
            assertEquals(listOf(2, 1, 3, 3, 3), day(draft, "C").exercises.map { it.targetSets })
        }
    }

    @Test fun `five and six days append tempo then upper lifts`() {
        val four = Planner.plan(sprint(days = 4))
        val five = Planner.plan(sprint(days = 5))
        val six = Planner.plan(sprint(days = 6))
        // sprint week design §2.2: four has six entries; compare the first five before rest F becomes tempo F.
        assertEquals(four.program.days.take(5), five.program.days.take(5))
        assertEquals(five.program.days, six.program.days.take(6))
        val tempo = day(five, "F")
        assertEquals(ProgramDayKind.CARDIO, tempo.kind)
        assertEquals(tempo0, tempo.cardioBlock)
        assertEquals(CardioRule(null, RunSession.SPRINT_TEMPO, intro = 3), tempo.cardioRule)
        assertTrue(tempo.exercises.isEmpty())
        assertEquals(816, SessionBudget.seconds(tempo, LiftGoal.STRENGTH))
        val upper = day(six, "G")
        assertEquals(ProgramDayKind.STRENGTH, upper.kind)
        assertEquals("Upper", upper.title)
        assertEquals(listOf("bb_bench", "ohp", "bb_row"), ids(six, "G"))
        assertEquals(List(3) { SlotRole.MAIN }, roles(six, "G"))
        // audit 2026-11-13, row 13: 300+9*(15+5*3+180)=2190.
        assertEquals(2190, SessionBudget.seconds(upper, LiftGoal.STRENGTH))
        assertTrue(five.reasons.isEmpty())
        assertTrue(six.reasons.isEmpty())
    }

    @Test fun `the intro opens every range at its low end`() {
        val recent = SprintAnswers(SprintEvent.M100, sprintedRecently = true)
        val draft = Planner.plan(sprint(answers = recent))
        val a = day(draft, "A")
        assertEquals(acc5, a.cardioBlock)
        assertEquals(CardioRule(null, RunSession.ACCELERATION, steps = 12), a.cardioRule)
        assertEquals(160, requireNotNull(a.cardioBlock).sets.filter { it.role == CardioRole.WORK }
            .sumOf { (it.end as Measure.Distance).metres })
        // sprint week design §2.2: A=1498+495+630+387=3010.
        assertEquals(3010, SessionBudget.seconds(a, LiftGoal.STRENGTH))
        assertEquals(maxv0, day(draft, "D").cardioBlock)
        assertEquals(CardioRule(null, RunSession.MAX_VELOCITY), day(draft, "D").cardioRule)
        val strain = Planner.plan(sprint(answers = recent.copy(hamstringStrain = true)))
        assertEquals(acc0, day(strain, "A").cardioBlock)
        // sprint week design §5: strain is careful; 6 counted intro sessions instead of 3.
        assertEquals(CardioRule(null, RunSession.ACCELERATION, intro = 6), day(strain, "A").cardioRule)
        val longEvent = Planner.plan(sprint(answers = recent.copy(event = SprintEvent.M200)))
        assertEquals(maxv0, day(longEvent, "A").cardioBlock)
        assertEquals(CardioRule(null, RunSession.MAX_VELOCITY), day(longEvent, "A").cardioRule)
        // audit 2026-11-13, row 7: 200+5*20=300 m, preserving the non-intro 3*100 m opening.
        assertEquals(se5, day(longEvent, "D").cardioBlock)
        assertEquals(CardioRule(null, RunSession.SPRINT_ENDURANCE, steps = 5), day(longEvent, "D").cardioRule)
        assertTrue(SprintAnswers().intro)
        assertFalse(recent.intro)
        assertFalse(sprint().careful)
        for (answers in listOf(sprint(answers = recent.copy(fortyOrOlder = true)),
            sprint(answers = recent).copy(olderAdult = true), sprint(answers = recent.copy(hamstringStrain = true)))) {
            assertTrue(answers.careful)
            assertTrue(answers.sprintIntro)
            val careful = Planner.plan(answers)
            assertEquals(acc0, day(careful, "A").cardioBlock)
            assertEquals(CardioRule(null, RunSession.ACCELERATION, intro = 6), day(careful, "A").cardioRule)
            assertEquals(CardioRule(null, RunSession.MAX_VELOCITY, intro = 6), day(careful, "D").cardioRule)
        }
        val carefulFive = Planner.plan(sprint(days = 5, answers = recent.copy(fortyOrOlder = true)))
        assertEquals(CardioRule(null, RunSession.SPRINT_TEMPO, intro = 6), day(carefulFive, "F").cardioRule)
    }

    @Test fun `plyometrics and Nordic open at the owner doses`() {
        val draft = Planner.plan(sprint())
        // sprint week design §2.1: A has two plyo slots + squat + BSS; count only 3*15+20=65 contacts.
        val plyos = day(draft, "A").exercises.filter { it.rule?.role == SlotRole.PLYO }
        assertEquals(listOf(3, 1), plyos.map { it.targetSets })
        assertEquals(listOf(RepTarget.Fixed(15), RepTarget.Fixed(20)), plyos.map { it.rule!!.target })
        for (slot in plyos) {
            assertEquals(SlotRole.PLYO, slot.rule!!.role)
            assertEquals(4, slot.rule!!.rir)
            assertEquals(60, slot.rule!!.restSeconds)
        }
        assertEquals(65, plyos.sumOf { it.targetSets * it.rule!!.target.reps })
        val second = day(draft, "D").exercises.filter { it.rule?.role == SlotRole.PLYO }
        assertEquals(listOf(2, 1), second.map { it.targetSets })
        assertEquals(50, second.sumOf { it.targetSets * it.rule!!.target.reps })
        // audit 2026-11-13, row 6: the 2 Nordic sets move from C to D, after Sprint B.
        val nordic = day(draft, "D").exercises.last()
        assertEquals("nordic", nordic.exerciseId)
        assertEquals(2, nordic.targetSets)
        assertEquals(RepTarget.Range(4, 6, 4), nordic.rule!!.target)
        for (day in draft.program.days) for ((index, slot) in day.exercises.withIndex()) {
            assertEquals(slot.targetSets, draft.working.getValue(SlotKey(day.id, index)).size)
        }
        val excluded = Planner.plan(sprint().copy(excluded = setOf("nordic")))
        // audit 2026-11-13, row 6: the 2 knee-flexion fallback sets follow the Nordic slot from C to D.
        val fallback = day(excluded, "D").exercises.last()
        assertEquals("seated_curl", fallback.exerciseId)
        assertEquals(2, fallback.targetSets)
        assertEquals(RepTarget.Range(8, 12, 8), fallback.rule!!.target)
    }

    @Test fun `thirty minutes fits through the ladder or reports the excess`() {
        val one = Planner.plan(sprint(days = 1, minutes = 30))
        // sprint week design §3.4: step 1=794+495+630=1919 > 1800; step 3=794+495+420=1709.
        assertEquals(listOf(PlanReason.ShortSession("A", 3)), one.reasons)
        // sprint week design §3.4: step 1 keeps two plyos and squat; step 3 reduces squat to two sets.
        assertEquals(listOf("pogo_hop", "a_skip", "bb_back_squat"), ids(one, "A"))
        // sprint week design §3.4: 794+495+2*210=1709.
        assertEquals(1709, SessionBudget.seconds(day(one, "A"), LiftGoal.STRENGTH))
        assertEquals(listOf("A", "B"), one.program.days.map { it.id })
        val recent = Planner.plan(sprint(minutes = 30, answers = SprintAnswers(sprintedRecently = true)))
        // sprint week design §3.4: A step 5=2257 then 7=1762; B step 3=1560; D step 5=1904 then 7=1529.
        assertEquals(listOf(PlanReason.ShortSession("A", 7), PlanReason.ShortSession("B", 3), PlanReason.ShortSession("D", 7)), recent.reasons)
        // sprint week design §3.4: step 7 drops both plyos; A=1498+264=1762.
        assertEquals(listOf("bb_back_squat"), ids(recent, "A"))
        assertEquals(1762, SessionBudget.seconds(day(recent, "A"), LiftGoal.STRENGTH))
        assertEquals(1560, SessionBudget.seconds(day(recent, "B"), LiftGoal.STRENGTH))
        assertEquals(listOf("trap_dl", "nordic"), ids(recent, "D"))
        assertEquals(1529, SessionBudget.seconds(day(recent, "D"), LiftGoal.STRENGTH))
    }

    @Test fun `sprint sessions skip the run cap and ignore the lift goal answer`() {
        val answers = sprint(days = 4)
        val draft = Planner.plan(answers)
        assertEquals(draft, Planner.plan(answers, known(100, 30), TODAY))
        assertFalse(draft.reasons.any { it is PlanReason.RunCapped })
        assertEquals(draft, Planner.plan(answers.copy(goal = LiftGoal.MUSCLE)))
    }

    @Test fun `Both with a sprint sub-goal builds the sprint layout with the lift rules`() {
        val answers = sprint(days = 4).copy(branch = PlanBranch.BOTH, goal = LiftGoal.STRENGTH,
            both = BothAnswers(sprint = true))
        val strength = Planner.plan(sprint(days = 4))
        assertEquals(strength, Planner.plan(answers))
        val muscleAnswers = answers.copy(goal = LiftGoal.MUSCLE, mains = listOf("bb_back_squat", "conv_dl", "bb_bench"))
        val muscle = Planner.plan(muscleAnswers)
        assertEquals(strength.program.days.map { it.kind }, muscle.program.days.map { it.kind })
        assertEquals(strength.sessions, muscle.sessions)
        // sprint week design §2.2: squat MAIN moves from B to high A; three sets stay unchanged.
        val squat = day(muscle, "A").exercises.first { it.rule?.role == SlotRole.MAIN }
        assertEquals("bb_back_squat", squat.exerciseId)
        assertEquals(3, squat.targetSets)
        assertEquals(RepTarget.Range(8, 12, 8), squat.rule!!.target)
        assertEquals(3, squat.rule!!.rir)
        // sprint week design §2.2: hinge MAIN moves from E to max-velocity D.
        assertEquals("conv_dl", day(muscle, "D").exercises.single { it.rule?.role == SlotRole.MAIN }.exerciseId)
        // sprint week design §2.2: two Upper entries each hold the one chosen upper main.
        for (id in listOf("B", "E")) assertEquals(listOf("bb_bench"), ids(muscle, id))
        val six = Planner.plan(muscleAnswers.copy(days = 6))
        for (id in listOf("B", "E")) assertEquals(listOf("bb_bench"), ids(six, id))
        assertEquals(listOf("bb_bench"), ids(six, "G"))
        assertEquals(listOf(SlotRole.MAIN), roles(six, "G"))
        assertFailsWith<IllegalArgumentException> { BothAnswers(cardioFirst = true, sprint = true) }
        assertFailsWith<IllegalArgumentException> { BothAnswers(together = BothAnswers.Together.SAME_VISIT, sprint = true) }
    }

    @Test fun `full prescription at ninety minutes`() {
        for (event in SprintEvent.entries) for (days in 1..6) for (recent in listOf(false, true)) {
            val draft = Planner.plan(sprint(event, days, minutes = 90, answers = SprintAnswers(event, sprintedRecently = recent)))
            assertTrue(draft.reasons.isEmpty(), "$event $days recent=$recent")
            val a = day(draft, "A")
            fun contacts(id: String) = day(draft, id).exercises.filter { it.rule?.role == SlotRole.PLYO }
                .sumOf { it.targetSets * requireNotNull(it.rule).target.reps }
            assertEquals(65, contacts("A"))
            if (days == 1) {
                assertEquals(listOf("pogo_hop", "a_skip", "bb_back_squat", "rdl", "bss"), ids(draft, "A"))
                assertEquals(listOf(3, 1), a.exercises.take(2).map { it.targetSets })
                assertEquals(listOf(RepTarget.Fixed(15), RepTarget.Fixed(20)), a.exercises.take(2).map { it.rule!!.target })
                assertTrue(a.exercises.none { ExerciseLibrary.get(it.exerciseId).pattern == MovementPattern.KNEE_FLEXION })
                assertEquals(ProgramDayKind.REST, day(draft, "B").kind)
            } else {
                assertEquals(50, contacts(if (days == 2) "C" else "D"))
                val highs = draft.program.days.filter { it.cardioRule?.session in listOf(
                    RunSession.ACCELERATION, RunSession.MAX_VELOCITY, RunSession.SPRINT_ENDURANCE) }
                val hinge = highs.single { it.cardioRule?.session == RunSession.MAX_VELOCITY }
                assertEquals("nordic", hinge.exercises.last().exerciseId)
                assertEquals(listOf("trap_dl"), hinge.exercises.filter { it.rule?.role == SlotRole.MAIN &&
                    mainRow(ExerciseLibrary.get(it.exerciseId).pattern) in 0..1 }.map { it.exerciseId })
                val squat = highs.single { it != hinge }
                assertEquals(listOf("bb_back_squat", "bss"), squat.exercises.filter {
                    ExerciseLibrary.get(it.exerciseId).pattern in listOf(MovementPattern.SQUAT_BILATERAL, MovementPattern.SINGLE_LEG)
                }.map { it.exerciseId })
            }
        }
    }

    @Test fun `Both with a sprint goal needs a lower main`() {
        val both = sprint(minutes = 90).copy(branch = PlanBranch.BOTH, both = BothAnswers(sprint = true))
        fun fails(answers: SetupAnswers, message: String) {
            assertEquals(message, assertFailsWith<IllegalArgumentException> { Planner.plan(answers) }.message)
        }
        fails(both.copy(mains = listOf("bb_bench", "ohp", "bb_row")),
            "Both with a sprint goal needs a squat or hinge main")
        val noSquat = both.copy(excluded = ExerciseLibrary.byPattern(MovementPattern.SQUAT_BILATERAL).map { it.id }.toSet())
        fails(noSquat.copy(olderAdult = true), "Both with a sprint goal needs a squat main")
        val fallback = Planner.plan(noSquat)
        for (id in listOf("A", "D")) assertEquals("trap_dl",
            day(fallback, id).exercises.single { it.rule?.role == SlotRole.MAIN }.exerciseId)
        val onlySquat = Planner.plan(both.copy(mains = listOf("bb_back_squat")))
        assertEquals(listOf("pogo_hop", "a_skip", "bb_back_squat", "bss"), ids(onlySquat, "A"))
        assertEquals(listOf("pogo_hop", "a_skip", "bb_back_squat", "nordic"), ids(onlySquat, "D"))
        assertEquals(ProgramDayKind.REST, day(onlySquat, "B").kind)
        assertEquals("Rest", day(onlySquat, "B").title)
        assertTrue(day(onlySquat, "B").exercises.isEmpty())
        assertEquals(2, onlySquat.program.days.count { day -> day.exercises.any { it.rule?.role == SlotRole.MAIN } })
        val olderSquat = Planner.plan(both.copy(mains = listOf("bb_back_squat"), olderAdult = true))
        assertEquals(ProgramDayKind.REST, day(olderSquat, "B").kind)
        assertTrue(day(olderSquat, "B").exercises.isEmpty())
        val hingeAnswers = both.copy(mains = listOf("trap_dl", "bb_bench"))
        val hinge = Planner.plan(hingeAnswers)
        for (id in listOf("A", "D")) assertEquals("trap_dl",
            day(hinge, id).exercises.single { it.rule?.role == SlotRole.MAIN }.exerciseId)
        assertEquals(listOf("bb_bench"), ids(hinge, "B"))
        fails(hingeAnswers.copy(sprint = SprintAnswers(fortyOrOlder = true)), "Both with a sprint goal needs a squat main")
        val sprintOnly = Planner.plan(hingeAnswers.copy(branch = PlanBranch.SPRINT))
        assertEquals(listOf("pogo_hop", "a_skip", "bss"), ids(sprintOnly, "A"))
        assertEquals(listOf("pogo_hop", "a_skip", "trap_dl", "nordic"), ids(sprintOnly, "D"))
        val oneBoth = Planner.plan(hingeAnswers.copy(days = 1))
        assertEquals(listOf("pogo_hop", "a_skip", "trap_dl", "bss"), ids(oneBoth, "A"))
        val oneSprint = Planner.plan(hingeAnswers.copy(days = 1, branch = PlanBranch.SPRINT))
        assertEquals(listOf("pogo_hop", "a_skip", "rdl", "bss"), ids(oneSprint, "A"))
        assertTrue(day(oneSprint, "A").exercises.none { it.rule?.role == SlotRole.MAIN })
    }

    @Test fun `Reset day reproduces each sprint day`() {
        for (answers in listOf(sprint(days = 4), sprint(days = 2, minutes = 30))) {
            val full = Planner.plan(answers)
            for (day in full.program.days) {
                val draft = requireNotNull(Planner.day(answers, day.id))
                assertEquals(day, draft.day)
                assertEquals(full.working.filterKeys { it.dayId == day.id }.mapKeys { it.key.index }, draft.working)
                assertEquals(full.reasons.filter { when (it) {
                    is PlanReason.ShortSession -> it.dayId == day.id
                    is PlanReason.OverBudget -> it.dayId == day.id
                    is PlanReason.RunCapped -> it.dayId == day.id
                    // ladder-date: ladder length is plan-level; day expectations stay unchanged.
                    PlanReason.DateTooClose, is PlanReason.LadderWeeks, is PlanReason.GoalCheck, PlanReason.BalanceDays, PlanReason.BalanceDaysOffered -> false
                } }, draft.reasons)
            }
            assertNull(Planner.day(answers, "Z"))
        }
    }

    @Test fun `sprint ranges and blocks`() {
        val sessions = listOf(RunSession.ACCELERATION, RunSession.MAX_VELOCITY, RunSession.SPRINT_ENDURANCE, RunSession.SPRINT_TEMPO)
        // audit 2026-11-13, row 7: sprint-endurance top=(4*150-2*100)/20=20.
        assertEquals(listOf(20, 7, 20, 12), sessions.map { requireNotNull(RunRange.of(it)).top })
        assertEquals(listOf(false, false, false, true), sessions.map { requireNotNull(RunRange.of(it)).goodBand })
        assertEquals(intervals(8, 30, 180), sprintBlock(RunSession.ACCELERATION, 20))
        assertEquals(intervals(6, 300, 60, warmup = false, hard = false), sprintBlock(RunSession.SPRINT_TEMPO, 12))
        // audit 2026-11-13, row 7: step zero = 2*100 m with 1*600 s rest.
        assertEquals(listOf(acc0, acc5, maxv0, se0, tempo0), listOf(
            sprintBlock(RunSession.ACCELERATION, 0), sprintBlock(RunSession.ACCELERATION, 12),
            sprintBlock(RunSession.MAX_VELOCITY, 0), sprintBlock(RunSession.SPRINT_ENDURANCE, 0),
            sprintBlock(RunSession.SPRINT_TEMPO, 0)))
        // audit 2026-11-13, row 7: se0=600+2*36+600=1272; se5=600+3*36+2*600=1908.
        assertEquals(listOf(794, 1498, 1091, 1272, 516), listOf(acc0, acc5, maxv0, se0, tempo0).map(::plannedSeconds))
        assertEquals(se5, sprintBlock(RunSession.SPRINT_ENDURANCE, 5))
        assertEquals(1908, plannedSeconds(se5))
        assertEquals(20, CardioRule(null, RunSession.ACCELERATION, steps = 20).steps)
        assertFailsWith<IllegalArgumentException> { CardioRule(null, RunSession.ACCELERATION, steps = 21) }
    }
}
