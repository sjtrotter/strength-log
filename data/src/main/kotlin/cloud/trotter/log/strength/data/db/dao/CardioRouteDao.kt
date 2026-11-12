package cloud.trotter.log.strength.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import cloud.trotter.log.strength.data.db.entity.CardioRouteEntity

@Dao
interface CardioRouteDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(route: CardioRouteEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertAll(routes: List<CardioRouteEntity>)

    @Query("SELECT * FROM cardio_route WHERE cardioSessionId = :id")
    suspend fun forSession(id: Long): CardioRouteEntity?

    @Query("DELETE FROM cardio_route WHERE cardioSessionId = :id")
    suspend fun deleteForSession(id: Long)

    @Query("SELECT * FROM cardio_route ORDER BY cardioSessionId")
    suspend fun all(): List<CardioRouteEntity>

    @Query("DELETE FROM cardio_route")
    suspend fun deleteAll()
}
