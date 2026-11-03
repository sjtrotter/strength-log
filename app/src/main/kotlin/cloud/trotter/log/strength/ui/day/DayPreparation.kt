package cloud.trotter.log.strength.ui.day

import cloud.trotter.log.strength.data.ProgramSlot
import cloud.trotter.log.strength.data.TrackerRepository
import cloud.trotter.log.strength.data.catalog.ExerciseCatalog
import cloud.trotter.log.strength.data.db.entity.Slot
import cloud.trotter.log.strength.data.performedExercise
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

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

/** Refresh history when slots, performed identities, or archived sessions change. */
fun TrackerRepository.dayHistoryKeys(
    planId: Long,
    dayId: String,
): Flow<Pair<List<ProgramSlot>, Map<Pair<Long, String>, String?>>> = combine(
    slotsFor(planId, dayId),
    dayTracksFlow(planId, dayId).map { tracks ->
        tracks.tracks.associate { (it.programExerciseId to it.slot) to it.exerciseId }
    }.distinctUntilChanged(),
    sessionSummariesFor(planId),
) { slots, performedIds, _ -> slots to performedIds }
