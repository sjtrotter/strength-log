package cloud.trotter.log.strength.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import cloud.trotter.log.strength.data.db.entity.CardioSetResultEntity

@Dao
interface CardioSetResultDao {
    @Insert
    suspend fun insertAll(sets: List<CardioSetResultEntity>)

    @Query("SELECT * FROM cardio_set_result WHERE cardioSessionId = :sessionId ORDER BY position, id")
    suspend fun forSession(sessionId: Long): List<CardioSetResultEntity>

    @Query("SELECT * FROM cardio_set_result ORDER BY cardioSessionId, position, id")
    suspend fun all(): List<CardioSetResultEntity>

    @Query("UPDATE cardio_set_result SET metres = :metres, distanceSource = :source WHERE cardioSessionId = :sessionId AND position = :position")
    suspend fun amendDistance(sessionId: Long, position: Int, metres: Int?, source: String?)

    @Query("SELECT * FROM cardio_set_result ORDER BY cardioSessionId, position, id")
    fun observeAll(): Flow<List<CardioSetResultEntity>>

    @Query("DELETE FROM cardio_set_result WHERE cardioSessionId = :sessionId")
    suspend fun deleteForSession(sessionId: Long)

    @Query("DELETE FROM cardio_set_result")
    suspend fun deleteAll()
}
