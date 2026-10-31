package cloud.trotter.log.strength.sync

import kotlinx.coroutines.flow.Flow

/** Durable wrist set-edit and swap stamps, recorded only after a terminal outcome. */
interface EditAckLog {
    suspend fun recordProcessedEdit(stamp: Long)

    /** Most recent processed stamps, newest last, at most 32. */
    val processedEditsFlow: Flow<List<Long>>
}
