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

    /** Stable order for backup export (A2). */
    @Query("SELECT * FROM custom_exercise ORDER BY id")
    suspend fun allOrdered(): List<CustomExerciseEntity>

    @Upsert
    suspend fun upsert(exercise: CustomExerciseEntity)

    @Upsert
    suspend fun upsertAll(exercises: List<CustomExerciseEntity>)

    @Query("DELETE FROM custom_exercise WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM custom_exercise")
    suspend fun deleteAll()
}
