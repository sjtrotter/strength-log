package cloud.trotter.log.strength.sync

import kotlinx.coroutines.flow.Flow

/** Durable processed wrist cardio stamps, including duplicate and invalid outcomes. */
interface CardioAckLog {
    suspend fun record(stamp: Long)

    /** Replay detection survives history edits, deletion and acknowledgment window eviction. */
    suspend fun hasProcessed(stamp: Long): Boolean

    /** Both acknowledgements, read from one stored state. */
    val acksFlow: Flow<CardioAcks>
}

/** The highest processed stamp (for older watches) and the most recent processed
 *  stamps, newest last, at most 32. One value so a new highest stamp never
 *  publishes beside a list that does not yet hold it. */
data class CardioAcks(val highest: Long = 0L, val recent: List<Long> = emptyList())
