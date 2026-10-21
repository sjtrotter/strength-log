package cloud.trotter.log.strength.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import cloud.trotter.log.strength.data.db.entity.CardioSetResultEntity

@Dao
interface CardioSetResultDao {
    @Insert
    suspend fun insertAll(sets: List<CardioSetResultEntity>)

    @Query("SELECT * FROM cardio_set_result WHERE cardioSessionId = :sessionId ORDER BY position, id")
    suspend fun forSession(sessionId: Long): List<CardioSetResultEntity>

    @Query("SELECT * FROM cardio_set_result ORDER BY cardioSessionId, position, id")
    suspend fun all(): List<CardioSetResultEntity>

    @Query("DELETE FROM cardio_set_result WHERE cardioSessionId = :sessionId")
    suspend fun deleteForSession(sessionId: Long)

    @Query("DELETE FROM cardio_set_result")
    suspend fun deleteAll()
}
