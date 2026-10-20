package cloud.trotter.log.strength.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
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

    @Query("UPDATE plan SET recipeJson = :recipeJson WHERE id = :id")
    suspend fun updateRecipe(id: Long, recipeJson: String)

    @Query("UPDATE plan SET suggestedDayId = :dayId WHERE id = :id")
    suspend fun updateSuggestedDay(id: Long, dayId: String?)

    @Update
    suspend fun updatePlan(plan: PlanEntity)

    @Query("UPDATE plan SET name = :name WHERE id = :id")
    suspend fun updateName(id: Long, name: String)

    @Query("UPDATE plan SET archived = :archived WHERE id = :id")
    suspend fun updateArchived(id: Long, archived: Boolean)

    @Query("SELECT MAX(position) FROM plan")
    suspend fun maxPosition(): Int?

    @Query("UPDATE plan SET position = position + 1 WHERE position > :position")
    suspend fun shiftAfter(position: Int)

    @Query("DELETE FROM plan")
    suspend fun deleteAllPlans()
}
