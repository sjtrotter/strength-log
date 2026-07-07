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

    /** Whole history in a stable order (backup export, A2). */
    @Query("SELECT * FROM workout_session ORDER BY id")
    suspend fun allSessions(): List<WorkoutSessionEntity>

    @Query("SELECT * FROM session_set ORDER BY id")
    suspend fun allSessionSets(): List<SessionSetEntity>

    @Insert
    suspend fun insertSession(session: WorkoutSessionEntity): Long

    /** Bulk insert preserving each row's id so [SessionSetEntity.sessionId] links
     *  survive a backup restore (A2). */
    @Insert
    suspend fun insertSessions(sessions: List<WorkoutSessionEntity>)

    @Insert
    suspend fun insertSets(sets: List<SessionSetEntity>)

    @Query("DELETE FROM workout_session")
    suspend fun deleteAllSessions()

    @Query("DELETE FROM session_set")
    suspend fun deleteAllSessionSets()
}
