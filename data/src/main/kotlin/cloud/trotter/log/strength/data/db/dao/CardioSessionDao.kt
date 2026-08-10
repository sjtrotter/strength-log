package cloud.trotter.log.strength.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import cloud.trotter.log.strength.data.db.entity.CardioSessionEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface CardioSessionDao {
    @Insert
    suspend fun insert(session: CardioSessionEntity): Long

    @Insert
    suspend fun insertAll(sessions: List<CardioSessionEntity>)

    @Query("SELECT * FROM cardio_session ORDER BY completedAt DESC")
    fun observeSessions(): Flow<List<CardioSessionEntity>>

    @Query("SELECT * FROM cardio_session WHERE id = :id")
    suspend fun byId(id: Long): CardioSessionEntity?

    @Query("SELECT * FROM cardio_session ORDER BY id")
    suspend fun all(): List<CardioSessionEntity>

    @Query("DELETE FROM cardio_session")
    suspend fun deleteAll()
}
