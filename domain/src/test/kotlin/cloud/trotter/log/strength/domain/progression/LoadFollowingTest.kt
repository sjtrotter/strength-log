package cloud.trotter.log.strength.domain.progression

import cloud.trotter.log.strength.domain.library.TrackingType
import cloud.trotter.log.strength.domain.model.Equipment
import cloud.trotter.log.strength.domain.model.LoggedSet
import cloud.trotter.log.strength.domain.model.PrescribedTarget
import cloud.trotter.log.strength.domain.model.Prescription
import cloud.trotter.log.strength.domain.model.Program
import cloud.trotter.log.strength.domain.model.ProgramDay
import cloud.trotter.log.strength.domain.model.ProgramDayKind
import cloud.trotter.log.strength.domain.model.ProgramExercise
import cloud.trotter.log.strength.domain.model.SetKind
import cloud.trotter.log.strength.domain.units.WeightUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LoadFollowingTest {
    private val weighted = TrackingType.WEIGHTED
    private fun rule(target: RepTarget = RepTarget.Fixed(5), rir: Int = 2) =
        SlotRule(SlotRole.MAIN, target, rir, 0.0125, 1, scheme = Scheme.STRENGTH)
    private fun slot(rule: SlotRule? = rule()) = ProgramExercise("bb_bench", targetSets = 3, rule = rule)
    private fun day(id: String, vararg slots: ProgramExercise) = ProgramDay(id, "Day $id", "", slots.toList())
    private fun work(weight: Double, reps: Int) = List(3) { LoggedSet(weight, reps, SetKind.WORK) }

    @Test fun `a weighted slot inside the range is eligible with a fixed or a range target`() {
        for (value in listOf(slot(), slot().copy(prescription = Prescription(PrescribedTarget.LoadReps(185.0, 5))),
            slot(rule(RepTarget.Range(8, 12, 8))))) {
            assertTrue(LoadFollowing.eligible(value, weighted))
        }
    }

    @Test fun `each disqualifier keeps a slot out`() {
        val outside = listOf(slot(rule(RepTarget.Fixed(8), 3)), slot(rule(RepTarget.Range(8, 12, 9))))
        for (value in outside) {
            assertFalse(LoadFollowing.eligible(value, weighted))
            assertTrue(LoadFollowing.linkable(value, weighted))
        }
        val unlinked = listOf(slot().copy(keepToPlan = true), slot(null)) +
            listOf(PrescribedTarget.Reps(5), PrescribedTarget.Hold(30), PrescribedTarget.RepsInTime(60, 20))
                .map { slot().copy(prescription = Prescription(it)) }
        for (value in unlinked) {
            assertFalse(LoadFollowing.eligible(value, weighted))
            assertFalse(LoadFollowing.linkable(value, weighted))
        }
        for (tracking in listOf(TrackingType.REPS, TrackingType.TIMED)) {
            assertFalse(LoadFollowing.eligible(slot(), tracking))
            assertFalse(LoadFollowing.linkable(slot(), tracking))
        }
    }

    @Test fun `a range past the bound keeps an established link`() {
        val b = slot(rule(RepTarget.Range(8, 12, 11)).copy(loadSource = LoadSource("A", 0.9)))
        for (a in listOf(slot(), slot(rule(RepTarget.Range(6, 10, 10))))) {
            val program = Program(listOf(day("A", a), day("B", b)))
            assertEquals(emptyList<String>(), LoadFollowing.eligibleSources(program, "B", b, weighted))
            assertEquals(listOf("A"), LoadFollowing.liveSources(program, "B", b, weighted))
            assertEquals("A", LoadFollowing.followedDay(program, "B", b, weighted))
        }
    }

    @Test fun `held reads either miss streak`() {
        for ((state, held) in listOf(SlotState() to false, SlotState(repMissStreak = 1) to true,
            SlotState(effortMissStreak = 1) to true, SlotState(successStreak = 3) to false)) {
            assertEquals(held, LoadFollowing.held(rule().copy(state = state)))
        }
    }

    @Test fun `eligible sources are other strength days with a source and no cycle back`() {
        val a = slot()
        val b = slot(rule(rir = 3))
        val c = slot(rule(rir = 3).copy(loadSource = LoadSource("A", 0.9)))
        val rest = day("D").copy(kind = ProgramDayKind.REST)
        val curl = day("E", slot().copy(exerciseId = "ez_curl"))
        val program = Program(listOf(day("A", a), day("B", b), day("C", c), rest, curl))
        assertEquals(listOf("A", "C"), LoadFollowing.eligibleSources(program, "B", b, weighted))
        // C already reaches A, so A cannot follow C.
        assertEquals(listOf("B"), LoadFollowing.eligibleSources(program, "A", a, weighted))
        val chainedB = b.copy(rule = requireNotNull(b.rule).copy(loadSource = LoadSource("C", 1.0)))
        val chain = program.copy(days = listOf(day("A", a), day("B", chainedB), day("C", c)))
        assertEquals(emptyList<String>(), LoadFollowing.eligibleSources(chain, "A", a, weighted))
        val linkedA = a.copy(rule = rule().copy(loadSource = LoadSource("B", 1.0)))
        val cycle = Program(listOf(day("A", linkedA), day("B", chainedB), day("C", c)))
        assertEquals(emptyList<String>(), LoadFollowing.eligibleSources(cycle, "C", c, weighted))
    }

    @Test fun `followedDay is the stored day while the link is live`() {
        val b = slot(rule().copy(loadSource = LoadSource("A", 0.9)))
        val program = Program(listOf(day("A", slot()), day("B", b)))
        assertEquals("A", LoadFollowing.followedDay(program, "B", b, weighted))
        assertNull(LoadFollowing.followedDay(program, "B", b.copy(keepToPlan = true), weighted))
        for (a in listOf(day("A"), day("A", slot().copy(prescription = Prescription(PrescribedTarget.Reps(5)))))) {
            assertNull(LoadFollowing.followedDay(Program(listOf(a, day("B", b))), "B", b, weighted))
        }
    }

    @Test fun `atLoad restarts a range only when the load rose`() {
        val range = rule(RepTarget.Range(8, 12, 10))
        val rows = work(60.0, 10)
        fun loaded(load: Double, rule: SlotRule = range) =
            LoadFollowing.atLoad(rows, rule, load, WeightUnit.LB, listOf(Equipment.DUMBBELL))
        assertEquals(work(65.0, 8) to range.copy(target = RepTarget.Range(8, 12, 8)), loaded(65.0))
        assertEquals(work(55.0, 10) to range, loaded(55.0))
        assertEquals(work(65.0, 10) to rule(), loaded(65.0, rule()))
    }
}
