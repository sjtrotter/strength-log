package cloud.trotter.log.strength.domain.progression

import cloud.trotter.log.strength.domain.model.PrescribedTarget
import cloud.trotter.log.strength.domain.model.Prescription
import cloud.trotter.log.strength.domain.model.ProgramExercise
import cloud.trotter.log.strength.domain.model.SetStructure
import cloud.trotter.log.strength.domain.model.SupersetPartner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SharedProgressionTest {
    private fun rule(role: SlotRole = SlotRole.MAIN, target: RepTarget = RepTarget.Fixed(5), rir: Int = 2,
                     scheme: Scheme = Scheme.STRENGTH) = SlotRule(role, target, rir, 0.0125, 1, scheme = scheme)
    private fun slot(rule: SlotRule = rule()) = ProgramExercise("bb_bench", targetSets = 3, rule = rule)

    @Test fun `the same exercise sets reps and effort share`() {
        for (automatic in listOf(false, true)) {
            assertTrue(SharedProgression.shares(slot(), slot(), automatic))
        }
    }

    @Test fun `a SECONDARY exposure keeps its own progression`() {
        val main = slot()
        val secondary = slot(rule(SlotRole.SECONDARY, rir = 3)).copy(targetSets = 2)
        assertFalse(SharedProgression.shares(main, secondary, true))
        assertFalse(SharedProgression.shares(main.copy(structure = SetStructure.RAMP_TOP_BACKOFF), secondary, true))
    }

    @Test fun `each differing field keeps slots apart`() {
        val main = slot()
        val different = listOf(
            main.copy(exerciseId = "db_bench"),
            main.copy(structure = SetStructure.RAMP_TOP_BACKOFF),
            main.copy(targetSets = 2),
            slot(rule(rir = 3)),
            slot(rule(SlotRole.SECONDARY)),
            slot(rule(scheme = Scheme.PROTOTYPE)),
            slot(rule(target = RepTarget.Fixed(6))),
            main.copy(keepToPlan = true),
            main.copy(rule = null),
            slot(rule().copy(nextTime = NextTimeMode.ASK)),
        )
        for (other in different) {
            assertFalse(SharedProgression.shares(main, other, true))
            assertFalse(SharedProgression.shares(other, main, true))
        }
        assertFalse(SharedProgression.shares(slot(rule(target = RepTarget.Range(8, 12, 8))),
            slot(rule(target = RepTarget.Range(8, 14, 8))), true))
    }

    @Test fun `a range shares whatever its current reps`() {
        assertTrue(SharedProgression.shares(slot(rule(target = RepTarget.Range(8, 12, 9))),
            slot(rule(target = RepTarget.Range(8, 12, 11))), true))
    }

    @Test fun `load is left out of an authored target`() {
        for ((first, second) in listOf(
            PrescribedTarget.LoadReps(25.0, 10) to PrescribedTarget.LoadReps(30.0, 10),
            PrescribedTarget.Hold(30) to PrescribedTarget.Hold(30, addedLb = 10.0),
        )) {
            assertTrue(SharedProgression.shares(slot().copy(prescription = Prescription(first)),
                slot().copy(prescription = Prescription(second)), true))
        }
    }

    @Test fun `the authored type reps and side keep slots apart`() {
        for ((first, second) in listOf(
            PrescribedTarget.LoadReps(25.0, 10) to PrescribedTarget.Reps(10),
            PrescribedTarget.LoadReps(25.0, 10) to PrescribedTarget.LoadReps(25.0, 12),
            PrescribedTarget.Hold(30) to PrescribedTarget.Hold(45),
        )) {
            assertFalse(SharedProgression.shares(slot().copy(prescription = Prescription(first)),
                slot().copy(prescription = Prescription(second)), true))
        }
        val prescribed = slot().copy(prescription = Prescription(PrescribedTarget.LoadReps(25.0, 10)))
        assertFalse(SharedProgression.shares(prescribed,
            prescribed.copy(prescription = prescribed.prescription!!.copy(eachSide = true)), true))
        assertFalse(SharedProgression.shares(prescribed, slot(), true))
    }

    @Test fun `a superset partner does not change the match`() {
        assertTrue(SharedProgression.shares(slot().copy(superset = SupersetPartner("cable_row")), slot(), true))
    }

    @Test fun `a copy takes progression and keeps settings`() {
        val into = rule(SlotRole.ACCESSORY, rir = 3, scheme = Scheme.PROTOTYPE).copy(
            incrementPct = 0.025, dropPct = 0.0, restSeconds = 90, nextTime = NextTimeMode.ASK)
        val from = rule(target = RepTarget.Range(8, 12, 10)).copy(everyExposures = 2,
            state = SlotState(calibrating = true, successStreak = 1, repMissStreak = 2, effortMissStreak = 3,
                resetEpochDays = listOf(100L, 120L), exposures = 16)) // #647: the shared-credit flag no longer exists
        assertEquals(into.copy(target = from.target, everyExposures = from.everyExposures, state = from.state),
            SharedProgression.copied(into, from))
    }

    @Test fun `automatic follows the slot then the plan`() {
        for (automatic in listOf(false, true)) {
            assertEquals(automatic, rule().automatic(automatic))
            assertTrue(rule().copy(nextTime = NextTimeMode.AUTO).automatic(automatic))
            assertFalse(rule().copy(nextTime = NextTimeMode.ASK).automatic(automatic))
        }
    }
}
