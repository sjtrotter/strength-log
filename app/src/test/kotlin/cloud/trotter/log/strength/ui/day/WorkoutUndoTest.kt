package cloud.trotter.log.strength.ui.day

import cloud.trotter.log.strength.data.prefs.UndoTarget
import cloud.trotter.log.strength.data.serialization.SetDto
import cloud.trotter.log.strength.domain.model.LoggedSet
import cloud.trotter.log.strength.domain.model.SetKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WorkoutUndoTest {
    private val before = LoggedSet(50.0, 8, SetKind.WORK, watchEditedAt = 100L)
    private val after = before.copy(done = true, completedAtMillis = 70L)
    private val target = UndoTarget(1L, "MAIN", 0, SetDto.of(before), SetDto.of(after), 70L, false)

    @Test fun `undo restores only the target and keeps the newer watch stamp`() {
        val other = before.copy(reps = 11)
        val result = WorkoutUndo.revert(listOf(after.copy(watchEditedAt = 200L), other), target, before)
        assertEquals(listOf(before.copy(watchEditedAt = 200L), other), result)
        assertEquals(listOf(before), WorkoutUndo.revert(listOf(after.copy(watchEditedAt = 50L)), target, before))
    }

    @Test fun `undo declines a missing unticked or changed target inside the transform`() {
        assertNull(WorkoutUndo.revert(emptyList(), target, before))
        for (changed in listOf(after.copy(done = false), after.copy(weightLb = 55.0),
            after.copy(reps = 9), after.copy(seconds = 30), after.copy(completedAtMillis = 80L))) {
            assertNull(WorkoutUndo.revert(listOf(changed), target, before))
        }
    }

    @Test fun `undo clears timing from the saved before row`() {
        val timedBefore = before.copy(startedAtMillis = 10L, completedAtMillis = 20L)
        assertEquals(listOf(before), WorkoutUndo.revert(listOf(after), target, timedBefore))
    }
}
