package cloud.trotter.log.strength.domain.seeding

import cloud.trotter.log.strength.domain.library.ExerciseEntry
import cloud.trotter.log.strength.domain.model.LifterConfig
import cloud.trotter.log.strength.domain.model.LoggedSet
import cloud.trotter.log.strength.domain.model.Prescription
import cloud.trotter.log.strength.domain.model.ProgramExercise
import cloud.trotter.log.strength.domain.standards.GoalCalculator

/**
 * Main tracks use the prescription, otherwise the known exercise's goal.
 * Partners use their own goal for the caller's live or freshly seeded main row count.
 * Unknown entries without a prescription cannot be seeded. Every seeding path calls this rule.
 */
object SlotSeeds {
    fun main(exercise: ProgramExercise, entry: ExerciseEntry?, config: LifterConfig): List<LoggedSet>? =
        exercise.prescription?.let { SetSeeder.seedPrescribed(exercise.targetSets, it, exercise.structure) }
            ?: entry?.let { SetSeeder.seed(exercise, GoalCalculator.targetFor(it, config), config) }

    fun prescribed(rows: Int, prescription: Prescription): List<LoggedSet> =
        SetSeeder.seedPrescribed(rows, prescription)

    fun partner(partnerEntry: ExerciseEntry?, mainRows: Int, config: LifterConfig): List<LoggedSet>? =
        partnerEntry?.let { SetSeeder.seedPartner(mainRows, GoalCalculator.targetFor(it, config)) }
}
