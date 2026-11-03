package cloud.trotter.log.strength.ui.day

import cloud.trotter.log.strength.data.TrackerRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** Refresh history when slots, performed identities, or archived sessions change. */
fun TrackerRepository.dayHistoryKeys(
    planId: Long,
    dayId: String,
): Flow<List<String>> = combine(
    observeDay(planId, dayId).map { it.historyExerciseIds }.distinctUntilChanged(),
    sessionSummariesFor(planId),
) { ids, _ -> ids }
