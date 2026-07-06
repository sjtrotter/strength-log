package io.github.sjtrotter.strengthlog.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import io.github.sjtrotter.strengthlog.data.db.entity.SessionSetEntity
import io.github.sjtrotter.strengthlog.data.db.entity.WorkoutSessionEntity
import kotlinx.coroutines.flow.Flow

/** Append-only workout history (PLAN.md A1). Rows are inserted, never updated. */
@Dao
interface SessionDao {

    @Query("SELECT * FROM workout_session ORDER BY completedAt DESC")
    fun observeSessions(): Flow<List<WorkoutSessionEntity>>

    @Query("SELECT * FROM session_set WHERE sessionId = :sessionId ORDER BY id")
    suspend fun setsForSession(sessionId: Long): List<SessionSetEntity>

    @Insert
    suspend fun insertSession(session: WorkoutSessionEntity): Long

    @Insert
    suspend fun insertSets(sets: List<SessionSetEntity>)
}
