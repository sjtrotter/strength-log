package cloud.trotter.log.strength.data

import cloud.trotter.log.strength.data.db.entity.SessionSetEntity
import cloud.trotter.log.strength.data.db.entity.Slot
import cloud.trotter.log.strength.data.db.entity.WorkoutSessionEntity
import cloud.trotter.log.strength.data.serialization.PlannedTrackDto
import cloud.trotter.log.strength.data.serialization.SessionPlanJson
import cloud.trotter.log.strength.data.serialization.SetDto
import cloud.trotter.log.strength.domain.model.LoggedSet
import cloud.trotter.log.strength.domain.model.ProgramExercise
import cloud.trotter.log.strength.domain.model.SetKind
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Test

class ArchivedRunTest {
    @Test fun `archive recovers retired work skipped rows and live counts by track position`() {
        val planned = List(3) { LoggedSet(185.0, 5, SetKind.WORK) }
        val dtos = listOf(
            PlannedTrackDto(0, "bb_bench", Slot.MAIN, true, planned.map(SetDto::of), liveSetCount = 0, programExerciseId = 7),
            PlannedTrackDto(1, "ohp", Slot.MAIN, false, planned.map(SetDto::of), liveSetCount = 4, programExerciseId = 7),
            PlannedTrackDto(2, "ez_curl", Slot.MAIN, true, planned.map(SetDto::of), liveSetCount = 0, programExerciseId = 8, dropped = true),
            PlannedTrackDto(3, "cable_row", Slot.SS, true, planned.map(SetDto::of), liveSetCount = 0, programExerciseId = 7, dropped = true),
        )
        val session = WorkoutSessionEntity(1, "A", "Day A", 1000, 2000, null, plannedJson = SessionPlanJson.encode(dtos))
        val sets = listOf(
            SessionSetEntity(1, 1, "bb_bench", "Bench", Slot.MAIN, 1, "WORK", 185.0, 5, true, trackPosition = 0, plannedIndex = 1),
            SessionSetEntity(2, 1, "ohp", "Press", Slot.MAIN, 0, "WORK", 95.0, 5, true, trackPosition = 1, plannedIndex = 0),
            SessionSetEntity(3, 1, "ohp", "Press", Slot.MAIN, 1, "WORK", 95.0, 5, false, trackPosition = 1, plannedIndex = 1),
        )
        val slots = listOf(ProgramSlot(7, 0, ProgramExercise("ohp")), ProgramSlot(8, 1, ProgramExercise("ez_curl")))
        val recovered = ArchivedRun.of(session, sets, slots)
        assertEquals(slots, recovered.slots)
        assertEquals(listOf(0, 1, 2, 3), recovered.tracks.map { it.position })
        assertEquals(listOf(Slot.MAIN, Slot.MAIN, Slot.MAIN, Slot.SS), recovered.tracks.map { it.slot })
        assertEquals(1, recovered.tracks[0].sets.single().plannedIndex)
        assertEquals(2, recovered.tracks.sumOf { track -> track.sets.count { it.done } })
        assertEquals(4, recovered.tracks[1].sets.size)
        assertTrue(recovered.tracks[2].dropped)
        assertTrue(recovered.tracks[3].dropped)
        assertEquals(RunSetCounts(2, 4, 3), recovered.tracks.setCounts())
    }
}
