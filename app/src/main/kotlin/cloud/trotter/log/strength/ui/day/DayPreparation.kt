package cloud.trotter.log.strength.ui.day

import cloud.trotter.log.strength.data.PerformedDay
import cloud.trotter.log.strength.data.db.dao.SessionSummaryRow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** Refresh history when slots, performed identities, or archived sessions change. */
fun dayHistoryKeys(
    days: Flow<PerformedDay>,
    sessions: Flow<List<SessionSummaryRow>>,
): Flow<List<String>> = combine(
    days.map { it.historyExerciseIds }.distinctUntilChanged(),
    sessions,
) { ids, _ -> ids }
