package cloud.trotter.log.strength.domain

import cloud.trotter.log.strength.domain.generator.ProgramGenerator
import cloud.trotter.log.strength.domain.generator.WizardAnswers
import cloud.trotter.log.strength.domain.library.ExerciseEntry
import cloud.trotter.log.strength.domain.library.ExerciseLibrary
import cloud.trotter.log.strength.domain.library.GoalSource
import cloud.trotter.log.strength.domain.library.TrackingType
import cloud.trotter.log.strength.domain.library.tracking
import cloud.trotter.log.strength.domain.model.Equipment
import cloud.trotter.log.strength.domain.model.LifterConfig
import cloud.trotter.log.strength.domain.model.MovementPattern.CORE_ANTI_EXT
import cloud.trotter.log.strength.domain.model.StandardLift
import cloud.trotter.log.strength.domain.standards.GoalCalculator
import cloud.trotter.log.strength.domain.standards.GoalTarget
import kotlin.test.Test
import kotlin.test.assertEquals

class TrackingTypeTest {

    private val cfg = LifterConfig(bodyweightLb = 235, age = 40)

    private fun entry(goal: GoalSource, perHand: Boolean = false) =
        ExerciseEntry("fix", "Fixture", CORE_ANTI_EXT, listOf(Equipment.BODYWEIGHT), perHand, goal, 1)

    @Test
    fun `tracking derivation is exhaustive over every GoalSource variant`() {
        assertEquals(TrackingType.WEIGHTED, entry(GoalSource.Std(StandardLift.SQUAT)).tracking)
        assertEquals(TrackingType.WEIGHTED, entry(GoalSource.FracOfStd(0.5, StandardLift.SQUAT)).tracking)
        assertEquals(TrackingType.WEIGHTED, entry(GoalSource.Flat(45.0)).tracking)
        assertEquals(TrackingType.REPS, entry(GoalSource.Reps(6)).tracking)
        assertEquals(TrackingType.TIMED, entry(GoalSource.Time(45)).tracking)
        assertEquals(TrackingType.TIMED, entry(GoalSource.Time(45, 25.0)).tracking)
    }

    @Test
    fun `targetFor routes each source to the right GoalTarget`() {
        // Weighted sources reuse goalFor verbatim (perHand carried through).
        assertEquals(
            GoalTarget.Weight(235.0, perHand = false),
            GoalCalculator.targetFor(ExerciseLibrary.get("bb_back_squat"), cfg),
        )
        assertEquals(
            GoalTarget.Weight(45.0, perHand = false),
            GoalCalculator.targetFor(entry(GoalSource.Flat(45.0)), cfg),
        )
        assertEquals(
            GoalTarget.Reps(6),
            GoalCalculator.targetFor(entry(GoalSource.Reps(6)), cfg),
        )
        assertEquals(
            GoalTarget.Time(45, 25.0),
            GoalCalculator.targetFor(entry(GoalSource.Time(45, 25.0)), cfg),
        )
        assertEquals(
            GoalTarget.Time(30, 0.0),
            GoalCalculator.targetFor(entry(GoalSource.Time(30)), cfg),
        )
    }

    @Test
    fun `every Std-sourced catalog entry is WEIGHTED`() {
        val stdEntries = ExerciseLibrary.entries.filter { it.goal is GoalSource.Std }
        assertEquals(
            emptyList(),
            stdEntries.filter { it.tracking != TrackingType.WEIGHTED }.map { it.id },
        )
    }

    @Test
    fun `catalog reclassification landed in P2 - non-WEIGHTED entries now exist`() {
        // P1 shipped only the machinery (this test used to assert the catalog
        // was still all-WEIGHTED); P2's data pass reclassifies ~35 entries and
        // appends new REPS/TIMED entries. Exact counts are pinned in
        // ExerciseLibraryTest ("catalog totals are 184 entries, ...").
        val nonWeighted = ExerciseLibrary.entries.filter { it.tracking != TrackingType.WEIGHTED }
        assertEquals(35, nonWeighted.size)
    }

    @Test
    fun `generator never marks a non-WEIGHTED slot as main`() {
        for (days in 2..6) {
            val program = ProgramGenerator.generate(WizardAnswers(daysPerWeek = days)).program
            val badMains = program.days
                .flatMap { it.exercises }
                .filter { it.isMain && ExerciseLibrary.get(it.exerciseId).tracking != TrackingType.WEIGHTED }
                .map { it.exerciseId }
            assertEquals(emptyList(), badMains, "non-WEIGHTED main produced for $days-day split")
        }
    }
}
