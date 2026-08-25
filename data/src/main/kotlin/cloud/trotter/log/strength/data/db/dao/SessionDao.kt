package cloud.trotter.log.strength.data.db.dao

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import cloud.trotter.log.strength.data.db.entity.SessionSetEntity
import cloud.trotter.log.strength.data.db.entity.WorkoutSessionEntity
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
 * [cloud.trotter.log.strength.data.TrackerRepository.lastPerformed] for how
 * this is reduced to one entry per exercise.
 */
data class LastPerformedRow(
    val exerciseId: String,
    val weightLb: Double,
    val reps: Int,
    val seconds: Int = 0,
)

/**
 * One flat row behind [SessionDao.personalRecordRows] — see
 * [cloud.trotter.log.strength.data.TrackerRepository.personalRecords] for
 * how this is reduced to one entry per exercise.
 */
data class PersonalRecordRow(
    val exerciseId: String,
    val weightLb: Double,
    val reps: Int,
    val completedAt: Long,
    val seconds: Int = 0,
)

/**
 * One session's heaviest completed TOP set for one exercise — the journal's
 * trajectory series (docs/briefs/journal.md §1.1) and the cascade ceremony's
 * "what was the all-time high before this advance" read. Aggregated in SQL so
 * the whole trajectory section costs one query, never one per lift.
 */
data class TopSetRow(
    val sessionId: Long,
    val completedAt: Long,
    val exerciseId: String,
    val topWeightLb: Double,
)

/**
 * One session's total completed tonnage (Σ weight × reps) — the journal's
 * weekly volume bars (docs/briefs/journal.md §1.2). Bucketing into ISO weeks
 * happens in Kotlin; SQL only collapses sets to sessions.
 */
data class SessionTonnageRow(
    val sessionId: Long,
    val completedAt: Long,
    val tonnageLb: Double,
)

/** Workout history (PLAN.md A1), editable in the single-user on-device store. */
@Dao
interface SessionDao {

    @Query("SELECT * FROM workout_session ORDER BY completedAt DESC, id DESC")
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
        ORDER BY ws.completedAt DESC, ws.id DESC
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
     * [cloud.trotter.log.strength.data.TrackerRepository.lastPerformed] for
     * how the flat result collapses to one row per exercise. For a ramped main
     * lift, every round (ramp/top/back-off) shares one `exerciseId`, so "heaviest
     * weight in the session" naturally picks its TOP set. The `reps`/`seconds`
     * tiebreaks make the pick type-correct without the SQL knowing the type: a
     * REPS exercise's rows all tie at weight 0 so `reps DESC` picks its best set,
     * a TIMED exercise's rows tie at weight/reps so `seconds DESC` picks its
     * longest hold, and for a WEIGHTED lift both are constant across the winning
     * (heaviest) tier so nothing changes.
     */
    @Query(
        """
        SELECT ss.exerciseId AS exerciseId, ss.weightLb AS weightLb, ss.reps AS reps, ss.seconds AS seconds
        FROM session_set ss
        INNER JOIN workout_session ws ON ws.id = ss.sessionId
        WHERE ss.exerciseId IN (:exerciseIds) AND ss.done = 1
        ORDER BY ws.completedAt DESC, ss.weightLb DESC, ss.reps DESC, ss.seconds DESC
        """,
    )
    suspend fun lastPerformedRows(exerciseIds: List<String>): List<LastPerformedRow>

    /**
     * Every completed set ever logged for any of [exerciseIds], heaviest weight
     * first (ties broken by more reps, then by which was achieved earliest) —
     * the profile "Best" chip (performance-profile.md Phase 1). One query for a
     * whole day's worth of exercise ids, same batching shape as
     * [lastPerformedRows]; see
     * [cloud.trotter.log.strength.data.TrackerRepository.personalRecords]
     * for how the flat result collapses to one row per exercise.
     */
    @Query(
        """
        SELECT ss.exerciseId AS exerciseId, ss.weightLb AS weightLb, ss.reps AS reps, ws.completedAt AS completedAt, ss.seconds AS seconds
        FROM session_set ss
        INNER JOIN workout_session ws ON ws.id = ss.sessionId
        WHERE ss.exerciseId IN (:exerciseIds) AND ss.done = 1
        ORDER BY ss.weightLb DESC, ss.reps DESC, ss.seconds DESC, ws.completedAt ASC
        """,
    )
    suspend fun personalRecordRows(exerciseIds: List<String>): List<PersonalRecordRow>

    /**
     * The trajectory series for every ramped lift at once (journal §1.1),
     * oldest session first. [topKind] is passed in rather than spelled `'TOP'`
     * here so the stored [cloud.trotter.log.strength.domain.model.SetKind]
     * name has exactly one source. Only ramped mains ever log a TOP set, so no
     * exercise-id filter is needed — the result is a couple of rows per session.
     */
    @Query(
        """
        SELECT ss.sessionId AS sessionId, ws.completedAt AS completedAt,
               ss.exerciseId AS exerciseId, MAX(ss.weightLb) AS topWeightLb
        FROM session_set ss
        INNER JOIN workout_session ws ON ws.id = ss.sessionId
        WHERE ss.done = 1 AND ss.kind = :topKind
        GROUP BY ss.sessionId, ss.exerciseId
        ORDER BY ws.completedAt ASC
        """,
    )
    fun observeTopSets(topKind: String): Flow<List<TopSetRow>>

    /** Completed tonnage per session, oldest first (journal §1.2) — one row per
     *  session, so the twelve-week volume chart is one query, not twelve. */
    @Query(
        """
        SELECT ss.sessionId AS sessionId, ws.completedAt AS completedAt,
               SUM(ss.weightLb * ss.reps) AS tonnageLb
        FROM session_set ss
        INNER JOIN workout_session ws ON ws.id = ss.sessionId
        WHERE ss.done = 1
        GROUP BY ss.sessionId
        ORDER BY ws.completedAt ASC
        """,
    )
    fun observeSessionTonnage(): Flow<List<SessionTonnageRow>>

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

    @Update
    suspend fun updateSet(set: SessionSetEntity): Int

    @Query("DELETE FROM session_set WHERE sessionId = :sessionId")
    suspend fun deleteSetsForSession(sessionId: Long)

    @Query("DELETE FROM workout_session WHERE id = :sessionId")
    suspend fun deleteSessionById(sessionId: Long)

    @Query("DELETE FROM workout_session")
    suspend fun deleteAllSessions()

    @Query("DELETE FROM session_set")
    suspend fun deleteAllSessionSets()
}
