package cloud.trotter.log.strength.ui.day

import cloud.trotter.log.strength.data.DayTracks
import cloud.trotter.log.strength.data.ProgramSlot
import cloud.trotter.log.strength.data.TrackerRepository
import cloud.trotter.log.strength.data.catalog.ExerciseCatalog
import cloud.trotter.log.strength.data.db.entity.Slot
import cloud.trotter.log.strength.data.performedExercise
import kotlinx.coroutines.flow.first

/** The same missing-track seed plan for a preview, explicit Start, and the workout. */
suspend fun TrackerRepository.daySeeds(
    planId: Long,
    dayId: String,
    slots: List<ProgramSlot>,
    tracks: DayTracks? = null,
): List<DayScreenBuilder.SeedWrite> {
    val existing = (tracks ?: dayTracksFlow(planId, dayId).first()).tracks
        .associate { (it.programExerciseId to it.slot) to it.sets.size }
    return DayScreenBuilder.seedPlan(slots, existing, wizardAnswersFor(planId).first().config, catalogFlow.first())
}

/** History follows performed identities, including independently swapped partners. */
fun performedDayExerciseIds(
    slots: List<ProgramSlot>,
    performedIds: Map<Pair<Long, String>, String?>,
    catalog: ExerciseCatalog,
): List<String> = slots.flatMap { slot ->
    listOfNotNull(
        performedExercise(slot, performedIds[slot.programExerciseId to Slot.MAIN], catalog).exerciseId,
        slot.exercise.superset?.let {
            performedExercise(slot, performedIds[slot.programExerciseId to Slot.SS], catalog, Slot.SS).exerciseId
        },
    )
}.distinct()
