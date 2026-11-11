package cloud.trotter.log.strength.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import cloud.trotter.log.strength.data.db.entity.CardioSessionEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface CardioSessionDao {
    @Insert
    suspend fun insert(session: CardioSessionEntity): Long

    @Update
    suspend fun update(session: CardioSessionEntity): Int

    @Insert
    suspend fun insertAll(sessions: List<CardioSessionEntity>)

    @Query("SELECT * FROM cardio_session ORDER BY completedAt DESC, id DESC")
    fun observeSessions(): Flow<List<CardioSessionEntity>>

    @Query("SELECT * FROM cardio_session WHERE id = :id")
    suspend fun byId(id: Long): CardioSessionEntity?

    @Query("SELECT * FROM cardio_session WHERE dayId IS :dayId AND startedAt = :startedAt ORDER BY id LIMIT 1")
    suspend fun forRun(dayId: String?, startedAt: Long): CardioSessionEntity?

    @Query("SELECT * FROM cardio_session ORDER BY id")
    suspend fun all(): List<CardioSessionEntity>

    @Query("SELECT * FROM cardio_session WHERE planId = :planId AND dayId = :dayId ORDER BY completedAt DESC, id DESC LIMIT 1")
    suspend fun newestForDay(planId: Long, dayId: String): CardioSessionEntity?

    @Query("SELECT * FROM cardio_session WHERE activity IN (:activities) ORDER BY completedAt, id")
    suspend fun forActivities(activities: List<String>): List<CardioSessionEntity>

    @Query("DELETE FROM cardio_session WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM cardio_session")
    suspend fun deleteAll()
}
