package cloud.trotter.log.strength.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import cloud.trotter.log.strength.data.db.entity.PlanEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface PlanDao {
    @Query("SELECT * FROM plan ORDER BY position, id")
    fun observePlans(): Flow<List<PlanEntity>>

    @Query("SELECT * FROM plan ORDER BY position, id")
    suspend fun allPlans(): List<PlanEntity>

    @Query("SELECT * FROM plan WHERE id = :id")
    suspend fun plan(id: Long): PlanEntity?

    @Insert
    suspend fun insertPlan(plan: PlanEntity): Long

    @Insert
    suspend fun insertPlans(plans: List<PlanEntity>)

    @Query("DELETE FROM plan")
    suspend fun deleteAllPlans()
}
