package cloud.trotter.log.strength.ui.day

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class WorkoutSelectionTest {
    private fun groups(vararg completion: Boolean): List<WorkoutSelection.Group> =
        listOf("Squat", "Bench", "Row", "Plank").zip(completion.toList()) { key, complete ->
            WorkoutSelection.Group(key, complete)
        }

    @Test
    fun actionLogAdvancesAfterTheChosenLiftAndWrapsToUnfinishedSquat() {
        val cases = listOf(
            Triple(groups(false, false, true, false), "Row", "Plank"),
            Triple(groups(false, false, true, true), "Plank", "Squat"),
            Triple(groups(false, true, true, true), "Squat", "Squat"),
            Triple(groups(true, true, true, true), "Plank", null),
        )
        for ((groups, logged, expected) in cases) {
            assertEquals(expected, WorkoutSelection.afterActionLog(groups, logged))
        }
    }

    @Test
    fun sheetCompletionKeepsTheSelectedCompletedViewAndNextFindsPlank() {
        val planned = groups(false, false, true, false)
        assertEquals(WorkoutSelection.Shown.Group("Row", true), WorkoutSelection.shown(planned, "Row"))
        assertEquals("Plank", WorkoutSelection.next(planned, "Row"))
        assertNull(WorkoutSelection.next(groups(true, true, false, true), "Row"))
    }

    @Test
    fun missingSelectionFindsFirstIncompleteOrAllLoggedOrEmpty() {
        val cases = listOf(
            groups(true, false, false, false) to WorkoutSelection.Shown.Group("Bench", false),
            groups(true, true, true, true) to WorkoutSelection.Shown.AllLogged,
            emptyList<WorkoutSelection.Group>() to WorkoutSelection.Shown.Empty,
        )
        for ((groups, expected) in cases) {
            assertEquals(expected, WorkoutSelection.shown(groups, null))
            assertEquals(expected, WorkoutSelection.shown(groups, "removed"))
        }
        assertNull(WorkoutSelection.next(emptyList(), "removed"))
    }

    @Test
    fun unfinishedCardioFollowsTheLastStrengthGroup() {
        val groups = groups(true, true, true, true) + WorkoutSelection.Group("cardio", false)
        assertEquals("cardio", WorkoutSelection.afterActionLog(groups, "Plank"))
        assertEquals(WorkoutSelection.Shown.Group("cardio", false), WorkoutSelection.shown(groups, null))
    }
}
