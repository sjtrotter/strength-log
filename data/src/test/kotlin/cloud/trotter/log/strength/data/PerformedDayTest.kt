package cloud.trotter.log.strength.data

import cloud.trotter.log.strength.data.catalog.ExerciseCatalog
import cloud.trotter.log.strength.data.db.entity.Slot
import cloud.trotter.log.strength.domain.generator.WizardAnswers
import cloud.trotter.log.strength.domain.library.ExerciseEntry
import cloud.trotter.log.strength.domain.library.GoalSource
import cloud.trotter.log.strength.domain.library.TrackingType
import cloud.trotter.log.strength.domain.model.Equipment
import cloud.trotter.log.strength.domain.model.LoggedSet
import cloud.trotter.log.strength.domain.model.MovementPattern
import cloud.trotter.log.strength.domain.model.PrescribedTarget
import cloud.trotter.log.strength.domain.model.Prescription
import cloud.trotter.log.strength.domain.model.Program
import cloud.trotter.log.strength.domain.model.ProgramDay
import cloud.trotter.log.strength.domain.model.ProgramExercise
import cloud.trotter.log.strength.domain.model.SetKind
import cloud.trotter.log.strength.domain.model.SupersetPartner
import cloud.trotter.log.strength.domain.standards.GoalTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class PerformedDayTest {
    private val answers = WizardAnswers(equipment = setOf(Equipment.BODYWEIGHT))
    private val catalog = ExerciseCatalog(listOf(
        ExerciseEntry("custom_loaded_hold", "Loaded hold", MovementPattern.CORE_ANTI_EXT,
            listOf(Equipment.BODYWEIGHT), perHand = false, goal = GoalSource.Time(45, 25.0),
            subRank = ExerciseCatalog.CUSTOM_SUBRANK),
    ))

    @Test fun `stored rows win including a deliberately empty track`() {
        val rows = listOf(LoggedSet(37.5, 9, SetKind.EXTRA, done = true))
        val partnerRows = listOf(LoggedSet(20.0, 12, SetKind.WORK))
        val slots = listOf(
            ProgramSlot(1, 0, ProgramExercise("ez_curl", superset = SupersetPartner("rope_pushdown"))),
            ProgramSlot(2, 1, ProgramExercise("rope_pushdown")),
        )
        val day = day(slots, DayTracks(null, false, listOf(
            LoggedSlot(1, Slot.MAIN, rows), LoggedSlot(1, Slot.SS, partnerRows),
            LoggedSlot(2, Slot.MAIN, emptyList()),
        )))

        assertSame(rows, day.slots[0].main.stored)
        assertSame(rows, day.slots[0].main.sets)
        assertNull(day.slots[0].main.seed)
        assertEquals(GoalTarget.Weight(60.0, false), day.slots[0].main.goal)
        val partner = requireNotNull(day.slots[0].partner)
        assertSame(partnerRows, partner.stored)
        assertSame(partnerRows, partner.sets)
        assertNull(partner.seed)
        assertEquals(emptyList<LoggedSet>(), day.slots[1].main.stored)
        assertTrue(day.slots[1].main.sets.isEmpty())
        assertNull(day.slots[1].main.seed)
        assertEquals(7L, day.planId)
        assertEquals("A", day.dayId)
        assertSame(day.program.days.single(), day.day)
        assertEquals(answers.config, day.config)
        assertEquals(answers.equipment, day.equipment)
        assertSame(catalog, day.catalog)
    }

    @Test fun `new partner seeds row aligned to a lived in main`() {
        val slot = ProgramSlot(9, 0, ProgramExercise("ez_curl", targetSets = 2,
            superset = SupersetPartner("rope_pushdown")))
        val rows = List(4) { LoggedSet(70.0, 8, SetKind.EXTRA) }
        val performed = day(listOf(slot), DayTracks("run", true, listOf(LoggedSlot(9, Slot.MAIN, rows)))).slots.single()

        assertSame(rows, performed.main.sets)
        val partner = requireNotNull(performed.partner)
        assertNull(partner.stored)
        assertEquals(List(4) { LoggedSet(50.0, 10, SetKind.WORK) }, partner.seed)
        assertSame(partner.seed, partner.sets)
    }

    @Test fun `unseedable track has no seed and no sets`() {
        val track = day(listOf(ProgramSlot(1, 0, ProgramExercise("unknown")))).slots.single().main

        assertNull(track.performed.entry)
        assertNull(track.goal)
        assertNull(track.stored)
        assertNull(track.seed)
        assertTrue(track.sets.isEmpty())
        assertEquals(0.0, track.loadLb, 0.0)
        assertFalse(track.showsLoad)
    }

    @Test fun `run drops partners and skips lifts in position order`() {
        val slots = listOf(
            ProgramSlot(3, 2, ProgramExercise("pushup")),
            ProgramSlot(2, 1, ProgramExercise("bb_bench")),
            ProgramSlot(1, 0, ProgramExercise("ez_curl", superset = SupersetPartner("rope_pushdown"))),
        )
        val tracks = DayTracks("run", true, emptyList(), droppedPartners = setOf(1), skippedLifts = setOf(2))
        val day = day(slots, tracks)

        assertSame(tracks, day.tracks)
        assertEquals(listOf(1L, 3L), day.slots.map { it.slot.programExerciseId })
        assertNull(day.slots.first().slot.exercise.superset)
        assertNull(day.slots.first().partner)
        assertEquals(listOf("ez_curl", "pushup"), day.performedExerciseIds)
    }

    @Test fun `load in pounds follows every prescribed and goal target arm`() {
        val cases = listOf(
            ProgramExercise("ez_curl", prescription = Prescription(PrescribedTarget.LoadReps(37.5, 8))) to 37.5,
            ProgramExercise("ez_curl", prescription = Prescription(PrescribedTarget.Hold(30, 15.0))) to 15.0,
            ProgramExercise("ez_curl", prescription = Prescription(PrescribedTarget.Reps(12))) to 0.0,
            ProgramExercise("ez_curl", prescription = Prescription(PrescribedTarget.RepsInTime(30, 12))) to 0.0,
            ProgramExercise("ez_curl") to 60.0,
            ProgramExercise("custom_loaded_hold") to 25.0,
            ProgramExercise("pushup") to 0.0,
        )

        cases.forEach { (exercise, expected) ->
            val track = day(listOf(ProgramSlot(1, 0, exercise))).slots.single().main
            assertEquals(exercise.toString(), expected, track.loadLb, 0.0)
        }
    }

    @Test fun `only timed tracks with positive load show load`() {
        val cases = listOf(
            Triple(ProgramExercise("custom_loaded_hold"), TrackingType.TIMED, true),
            Triple(ProgramExercise("plank"), TrackingType.TIMED, false),
            Triple(ProgramExercise("ez_curl", prescription = Prescription(PrescribedTarget.Hold(30, 15.0))), TrackingType.TIMED, true),
            Triple(ProgramExercise("ez_curl", prescription = Prescription(PrescribedTarget.Hold(30))), TrackingType.TIMED, false),
            Triple(ProgramExercise("plank", prescription = Prescription(PrescribedTarget.LoadReps(37.5, 8))), TrackingType.WEIGHTED, false),
            Triple(ProgramExercise("custom_loaded_hold", prescription = Prescription(PrescribedTarget.RepsInTime(30))), TrackingType.REPS, false),
        )

        cases.forEach { (exercise, tracking, showsLoad) ->
            val track = day(listOf(ProgramSlot(1, 0, exercise))).slots.single().main
            assertEquals(exercise.toString(), tracking, track.tracking)
            assertEquals(exercise.toString(), showsLoad, track.showsLoad)
        }
        val partner = requireNotNull(day(listOf(ProgramSlot(1, 0,
            ProgramExercise("ez_curl", superset = SupersetPartner("custom_loaded_hold")),
        ))).slots.single().partner)
        assertTrue(partner.showsLoad)
        assertEquals(25.0, partner.loadLb, 0.0)
    }

    @Test fun `history ids follow independent main and partner swaps and are distinct`() {
        val slots = listOf(
            ProgramSlot(1, 0, ProgramExercise("bb_bench", prescription = Prescription(PrescribedTarget.LoadReps(100.0, 5)),
                superset = SupersetPartner("ez_curl"))),
            ProgramSlot(2, 1, ProgramExercise("pushup")),
        )
        val day = day(slots, DayTracks("run", true, listOf(
            LoggedSlot(1, Slot.MAIN, emptyList(), "pushup"),
            LoggedSlot(1, Slot.SS, emptyList(), "plank"),
        )))

        assertEquals(listOf("pushup", "plank"), day.performedExerciseIds)
        assertEquals(catalog.get("pushup"), day.slots.first().main.performed.entry)
        assertNull(day.slots.first().main.performed.prescription)
        val partner = requireNotNull(day.slots.first().partner)
        assertEquals(catalog.get("plank"), partner.performed.entry)
        assertNull(partner.performed.prescription)
    }

    @Test fun `unknown day keeps available slots with no program day`() {
        val slots = listOf(ProgramSlot(1, 0, ProgramExercise("ez_curl")))
        val day = PerformedDay.of(7, "missing", Program(emptyList()), slots,
            DayTracks(null, false, emptyList()), answers, catalog)

        assertNull(day.day)
        assertEquals(slots, day.slots.map { it.slot })
    }

    private fun day(slots: List<ProgramSlot>, tracks: DayTracks = DayTracks(null, false, emptyList())): PerformedDay =
        PerformedDay.of(7, "A", Program(listOf(ProgramDay("A", "Arms", "", slots.map { it.exercise }))),
            slots, tracks, answers, catalog)
}
