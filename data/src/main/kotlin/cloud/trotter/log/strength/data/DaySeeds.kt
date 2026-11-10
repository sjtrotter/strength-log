package cloud.trotter.log.strength.data

import cloud.trotter.log.strength.data.catalog.ExerciseCatalog
import cloud.trotter.log.strength.domain.model.LifterConfig
import cloud.trotter.log.strength.domain.model.LoggedSet
import cloud.trotter.log.strength.domain.model.SetKind
import cloud.trotter.log.strength.domain.seeding.SlotSeeds

object DaySeeds {
    /** Complete unopened-main preview policy shared by Today and snapshots. */
    fun previewMain(slot: ProgramSlot, config: LifterConfig, catalog: ExerciseCatalog): List<LoggedSet> =
        SlotSeeds.main(slot.exercise, catalog.find(slot.exercise.exerciseId), config)
            // Preserve the authored count without inventing a load or prescription.
            ?: List(slot.exercise.targetSets) { LoggedSet(0.0, 0, SetKind.WORK, done = false) }
}
