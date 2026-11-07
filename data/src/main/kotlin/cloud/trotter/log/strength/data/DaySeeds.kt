package cloud.trotter.log.strength.data

import cloud.trotter.log.strength.data.catalog.ExerciseCatalog
import cloud.trotter.log.strength.data.db.entity.Slot
import cloud.trotter.log.strength.domain.model.LifterConfig
import cloud.trotter.log.strength.domain.model.LoggedSet
import cloud.trotter.log.strength.domain.model.SetKind
import cloud.trotter.log.strength.domain.model.SetStructure
import cloud.trotter.log.strength.domain.seeding.SlotSeeds

object DaySeeds {
    private const val STRAIGHT_SETS = 3

    /**
     * Which slots still need their ACTUAL log seeded from GOAL — every slot whose
     * (id, track) pair isn't already a key of [existing]. "Seeded once, then
     * persists": a slot with a stored log is never reseeded, so a changed GOAL
     * never rewrites a lifter's living record (spec principle 2).
     *
     * [existing] maps each stored track to its current row count, which the
     * partner path needs: a partner added to a lived-in slot must seed
     * row-aligned to the LIVE main track — the lifter may have added EXTRA sets
     * to it — not to the freshly computed seed.
     */
    fun plan(
        slots: List<ProgramSlot>,
        existing: Map<Pair<Long, String>, Int>,
        config: LifterConfig,
        catalog: ExerciseCatalog,
    ): Map<Pair<Long, String>, List<LoggedSet>> {
        val writes = LinkedHashMap<Pair<Long, String>, List<LoggedSet>>()
        for (slot in slots) {
            val pe = slot.exercise
            val mainSeed = SlotSeeds.main(pe, catalog.find(pe.exerciseId), config) ?: continue
            if (slot.programExerciseId to Slot.MAIN !in existing) {
                writes[slot.programExerciseId to Slot.MAIN] = mainSeed
            }
            val partner = pe.superset
            if (partner != null && slot.programExerciseId to Slot.SS !in existing) {
                val rows = existing[slot.programExerciseId to Slot.MAIN] ?: mainSeed.size
                val partnerSeed = SlotSeeds.partner(catalog.find(partner.exerciseId), rows, config) ?: continue
                writes[slot.programExerciseId to Slot.SS] = partnerSeed
            }
        }
        return writes
    }

    /** Complete unopened-main preview policy shared by Today and snapshots. */
    fun previewMain(slot: ProgramSlot, config: LifterConfig, catalog: ExerciseCatalog): List<LoggedSet> =
        SlotSeeds.main(slot.exercise, catalog.find(slot.exercise.exerciseId), config)
            // Preserve the authored count without inventing a load or prescription.
            ?: List(slot.exercise.targetSets) { LoggedSet(0.0, 0, SetKind.WORK, done = false) }

    /** A different exercise arrives as straight sets; a ramp's six rows become three. */
    fun swap(
        slot: ProgramSlot,
        trackSlot: String,
        newExerciseId: String,
        existing: Map<Pair<Long, String>, Int>,
        config: LifterConfig,
        catalog: ExerciseCatalog,
    ): List<LoggedSet>? {
        val exercise = when (trackSlot) {
            Slot.MAIN -> if (newExerciseId == slot.exercise.exerciseId) slot.exercise else slot.exercise.copy(
                exerciseId = newExerciseId,
                prescription = null,
                structure = SetStructure.STRAIGHT,
                targetSets = if (slot.exercise.structure.hasTopSet) STRAIGHT_SETS else slot.exercise.targetSets,
            )
            Slot.SS -> slot.exercise.copy(superset = requireNotNull(slot.exercise.superset).copy(exerciseId = newExerciseId))
            else -> return null
        }
        return plan(listOf(slot.copy(exercise = exercise)), existing - (slot.programExerciseId to trackSlot), config, catalog)
            .get(slot.programExerciseId to trackSlot)
    }
}
