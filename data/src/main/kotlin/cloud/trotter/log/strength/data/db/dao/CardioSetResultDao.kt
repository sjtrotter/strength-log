package cloud.trotter.log.strength.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow
import cloud.trotter.log.strength.data.db.entity.CardioSetResultEntity

@Dao
interface CardioSetResultDao {
    @Insert
    suspend fun insertAll(sets: List<CardioSetResultEntity>)

    @Update
    suspend fun update(result: CardioSetResultEntity): Int

    @Query("SELECT * FROM cardio_set_result WHERE cardioSessionId = :sessionId ORDER BY position, id")
    suspend fun forSession(sessionId: Long): List<CardioSetResultEntity>

    @Query("""
        SELECT r.* FROM cardio_set_result r
        JOIN cardio_session s ON s.id = r.cardioSessionId
        WHERE s.activity = :activity AND r.status = :finished
          AND (:excludedStartedAt IS NULL OR NOT (s.dayId IS :excludedDayId AND s.startedAt = :excludedStartedAt))
        ORDER BY s.completedAt, r.position, r.id
    """)
    suspend fun finishedForActivity(
        activity: String,
        finished: String,
        excludedDayId: String? = null,
        excludedStartedAt: Long? = null,
    ): List<CardioSetResultEntity>

    @Query("SELECT * FROM cardio_set_result ORDER BY cardioSessionId, position, id")
    suspend fun all(): List<CardioSetResultEntity>

    @Query("SELECT * FROM cardio_set_result ORDER BY cardioSessionId, position, id")
    fun observeAll(): Flow<List<CardioSetResultEntity>>

    @Query("""
        SELECT r.* FROM cardio_set_result r
        JOIN cardio_session s ON s.id = r.cardioSessionId
        WHERE s.activity IN (:activities)
        ORDER BY r.cardioSessionId, r.position, r.id
    """)
    suspend fun forActivities(activities: List<String>): List<CardioSetResultEntity>

    @Query("DELETE FROM cardio_set_result WHERE cardioSessionId = :sessionId")
    suspend fun deleteForSession(sessionId: Long)

    @Query("DELETE FROM cardio_set_result")
    suspend fun deleteAll()
}
