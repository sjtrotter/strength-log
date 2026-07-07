package io.github.sjtrotter.strengthlog.data.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import io.github.sjtrotter.strengthlog.data.db.entity.CustomExerciseEntity
import kotlinx.coroutines.flow.Flow

/** The user-created exercise overlay (PLAN.md A4). */
@Dao
interface CustomExerciseDao {

    @Query("SELECT * FROM custom_exercise ORDER BY name")
    fun observeAll(): Flow<List<CustomExerciseEntity>>

    @Query("SELECT * FROM custom_exercise")
    suspend fun getAll(): List<CustomExerciseEntity>

    @Upsert
    suspend fun upsert(exercise: CustomExerciseEntity)

    @Query("DELETE FROM custom_exercise WHERE id = :id")
    suspend fun delete(id: String)
}
