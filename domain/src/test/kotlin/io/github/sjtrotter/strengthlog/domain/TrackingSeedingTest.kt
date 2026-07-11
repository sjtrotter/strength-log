package io.github.sjtrotter.strengthlog.domain

import io.github.sjtrotter.strengthlog.domain.model.LifterConfig
import io.github.sjtrotter.strengthlog.domain.model.LoggedSet
import io.github.sjtrotter.strengthlog.domain.model.ProgramExercise
import io.github.sjtrotter.strengthlog.domain.model.SetKind
import io.github.sjtrotter.strengthlog.domain.seeding.SetEditor
import io.github.sjtrotter.strengthlog.domain.seeding.SetSeeder
import io.github.sjtrotter.strengthlog.domain.standards.GoalTarget
import kotlin.test.Test
import kotlin.test.assertEquals

class TrackingSeedingTest {

    private val cfg = LifterConfig(bodyweightLb = 235, age = 40)

    @Test
    fun `REPS seeds targetSets all-WORK rows at the rep target, zero weight and seconds`() {
        val pe = ProgramExercise("pullup", targetSets = 3)
        val seeded = SetSeeder.seed(pe, GoalTarget.Reps(6), cfg)
        assertEquals(
            List(3) { LoggedSet(0.0, 6, SetKind.WORK, seconds = 0) },
            seeded,
        )
    }

    @Test
    fun `TIMED seeds targetSets all-WORK rows at the seconds target, added load carried`() {
        val pe = ProgramExercise("weighted_plank", targetSets = 3)
        val seeded = SetSeeder.seed(pe, GoalTarget.Time(45, 25.0), cfg)
        assertEquals(
            List(3) { LoggedSet(25.0, 0, SetKind.WORK, seconds = 45) },
            seeded,
        )
    }

    @Test
    fun `TIMED with no added load seeds zero weight`() {
        val pe = ProgramExercise("plank", targetSets = 4)
        val seeded = SetSeeder.seed(pe, GoalTarget.Time(30, 0.0), cfg)
        assertEquals(List(4) { LoggedSet(0.0, 0, SetKind.WORK, seconds = 30) }, seeded)
    }

    @Test
    fun `Weight GoalTarget routes to the unchanged weighted seed path`() {
        val pe = ProgramExercise("cable_row", targetSets = 3)
        assertEquals(
            SetSeeder.seed(pe, 120.0, cfg),
            SetSeeder.seed(pe, GoalTarget.Weight(120.0, perHand = false), cfg),
        )
    }

    @Test
    fun `editSeconds changes only its own row and never cascades`() {
        val sets = List(3) { LoggedSet(0.0, 0, SetKind.WORK, seconds = 45) }
        val edited = SetEditor.editSeconds(sets, 1, 60)
        assertEquals(
            listOf(
                LoggedSet(0.0, 0, SetKind.WORK, seconds = 45),
                LoggedSet(0.0, 0, SetKind.WORK, seconds = 60),
                LoggedSet(0.0, 0, SetKind.WORK, seconds = 45),
            ),
            edited,
        )
    }

    @Test
    fun `cascade is unreachable for an all-WORK REPS track`() {
        val sets = SetSeeder.seed(ProgramExercise("pullup", targetSets = 3), GoalTarget.Reps(6), cfg)
        // No TOP row exists, so an editWeight on any index only touches that row.
        val edited = SetEditor.editWeight(sets, 0, 45.0)
        assertEquals(45.0, edited[0].weightLb, 1e-9)
        assertEquals(sets.drop(1), edited.drop(1)) // every other row untouched
    }

    @Test
    fun `cascade is unreachable for an all-WORK TIMED track`() {
        val sets = SetSeeder.seed(ProgramExercise("plank", targetSets = 3), GoalTarget.Time(45, 25.0), cfg)
        val edited = SetEditor.editWeight(sets, 2, 35.0)
        assertEquals(35.0, edited[2].weightLb, 1e-9)
        assertEquals(sets.dropLast(1), edited.dropLast(1))
    }
}
