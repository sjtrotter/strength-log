package io.github.sjtrotter.strengthlog.data.db.dao

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.Query
import io.github.sjtrotter.strengthlog.data.db.entity.SessionSetEntity
import io.github.sjtrotter.strengthlog.data.db.entity.WorkoutSessionEntity
import kotlinx.coroutines.flow.Flow

/**
 * One row of [SessionDao.observeSessionSummaries] — a session plus its total
 * set count, computed in the same aggregate query (the Log screen list, #14,
 * would otherwise need one extra query per row to show a count).
 */
data class SessionSummaryRow(
    @Embedded val session: WorkoutSessionEntity,
    val setCount: Int,
)

/**
 * One flat row behind [SessionDao.lastPerformedRows] — see
 * [io.github.sjtrotter.strengthlog.data.TrackerRepository.lastPerformed] for how
 * this is reduced to one entry per exercise.
 */
data class LastPerformedRow(
    val exerciseId: String,
    val weightLb: Double,
    val reps: Int,
)

/**
 * One flat row behind [SessionDao.personalRecordRows] — see
 * [io.github.sjtrotter.strengthlog.data.TrackerRepository.personalRecords] for
 * how this is reduced to one entry per exercise.
 */
data class PersonalRecordRow(
    val exerciseId: String,
    val weightLb: Double,
    val reps: Int,
    val completedAt: Long,
)

/** Append-only workout history (PLAN.md A1). Rows are inserted, never updated. */
@Dao
interface SessionDao {

    @Query("SELECT * FROM workout_session ORDER BY completedAt DESC")
    fun observeSessions(): Flow<List<WorkoutSessionEntity>>

    /**
     * The Log screen's list (#14): every session, newest first, each paired with
     * its total set count via one aggregate query — no N+1 as history grows.
     */
    @Query(
        """
        SELECT ws.*, COUNT(ss.id) AS setCount
        FROM workout_session ws
        LEFT JOIN session_set ss ON ss.sessionId = ws.id
        GROUP BY ws.id
        ORDER BY ws.completedAt DESC
        """,
    )
    fun observeSessionSummaries(): Flow<List<SessionSummaryRow>>

    @Query("SELECT * FROM session_set WHERE sessionId = :sessionId ORDER BY id")
    suspend fun setsForSession(sessionId: Long): List<SessionSetEntity>

    /** One session by id, for the Health Connect publish path (#17) — the session
     *  header (dayTitle, start/end times) that pairs with [setsForSession]. */
    @Query("SELECT * FROM workout_session WHERE id = :sessionId")
    suspend fun sessionById(sessionId: Long): WorkoutSessionEntity?

    /**
     * Every completed ([SessionSetEntity.done]) set ever logged for any of
     * [exerciseIds], newest session first (ties — i.e. rows from the same
     * session — broken by heaviest weight). One query for a whole day's worth
     * of exercise ids (#14 "last time" chip) instead of one per exercise; see
     * [io.github.sjtrotter.strengthlog.data.TrackerRepository.lastPerformed] for
     * how the flat result collapses to one row per exercise. For a ramped main
     * lift, every round (ramp/top/back-off) shares one `exerciseId`, so "heaviest
     * weight in the session" naturally picks its TOP set.
     */
    @Query(
        """
        SELECT ss.exerciseId AS exerciseId, ss.weightLb AS weightLb, ss.reps AS reps
        FROM session_set ss
        INNER JOIN workout_session ws ON ws.id = ss.sessionId
        WHERE ss.exerciseId IN (:exerciseIds) AND ss.done = 1
        ORDER BY ws.completedAt DESC, ss.weightLb DESC
        """,
    )
    suspend fun lastPerformedRows(exerciseIds: List<String>): List<LastPerformedRow>

    /**
     * Every completed set ever logged for any of [exerciseIds], heaviest weight
     * first (ties broken by more reps, then by which was achieved earliest) —
     * the profile "Best" chip (performance-profile.md Phase 1). One query for a
     * whole day's worth of exercise ids, same batching shape as
     * [lastPerformedRows]; see
     * [io.github.sjtrotter.strengthlog.data.TrackerRepository.personalRecords]
     * for how the flat result collapses to one row per exercise.
     */
    @Query(
        """
        SELECT ss.exerciseId AS exerciseId, ss.weightLb AS weightLb, ss.reps AS reps, ws.completedAt AS completedAt
        FROM session_set ss
        INNER JOIN workout_session ws ON ws.id = ss.sessionId
        WHERE ss.exerciseId IN (:exerciseIds) AND ss.done = 1
        ORDER BY ss.weightLb DESC, ss.reps DESC, ws.completedAt ASC
        """,
    )
    suspend fun personalRecordRows(exerciseIds: List<String>): List<PersonalRecordRow>

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
