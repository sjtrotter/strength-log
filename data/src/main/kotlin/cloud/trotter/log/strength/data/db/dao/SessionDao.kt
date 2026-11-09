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
 * One row of [SessionDao.observeSessionSummaries] — a session plus its completed
 * set count, computed in the same aggregate query (the Log screen list would
 * otherwise need one extra query per row to show a count).
 */
data class SessionSummaryRow(
    @Embedded val session: WorkoutSessionEntity,
    val setCount: Int,
)

/**
 * One flat row behind [SessionDao.lastPerformedRows] — see
 * [cloud.trotter.log.strength.data.HistoryRepository.lastPerformed] for how
 * this is reduced to one entry per exercise.
 */
data class LastPerformedRow(
    val exerciseId: String,
    val weightLb: Double,
    val reps: Int,
    val seconds: Int = 0,
)

data class LastPerformedSourceRow(
    val exerciseId: String,
    val slot: String,
    val weightLb: Double,
    val reps: Int,
    val seconds: Int,
    val planId: Long?,
    val planName: String?,
    val dayId: String,
    val sessionId: Long,
    val setIndex: Int,
    val kind: String,
)

data class LastPerformedDateRow(val exerciseId: String, val completedAt: Long)

/**
 * One flat row behind [SessionDao.personalRecordRows] — see
 * [cloud.trotter.log.strength.data.HistoryRepository.personalRecords] for
 * how this is reduced to one entry per exercise.
 */
data class PersonalRecordRow(
    val exerciseId: String,
    val weightLb: Double,
    val reps: Int,
    val completedAt: Long,
    val kind: String,
    val seconds: Int = 0,
)

data class TrajectorySetRow(
    @Embedded val set: SessionSetEntity,
    val completedAt: Long,
)

/** One session's heaviest set counted by PersonalBests for one exercise. */
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

data class PlanLastSessionRow(val planId: Long, val completedAt: Long)

/** Workout history (PLAN.md A1), editable in the single-user on-device store. */
@Dao
interface SessionDao {
    /** Finds a completed run so Finish can safely be replayed. */
    @Query("SELECT * FROM workout_session WHERE runId = :runId")
    suspend fun sessionByRunId(runId: String): WorkoutSessionEntity?


    @Query(
        """
        SELECT planId, MAX(completedAt) AS completedAt FROM (
            SELECT planId, completedAt FROM workout_session WHERE planId IS NOT NULL
            UNION ALL
            SELECT planId, completedAt FROM cardio_session WHERE planId IS NOT NULL
        ) GROUP BY planId
        """,
    )
    fun lastSessionByPlan(): Flow<List<PlanLastSessionRow>>

    @Query("SELECT * FROM workout_session ORDER BY completedAt DESC, id DESC")
    fun observeSessions(): Flow<List<WorkoutSessionEntity>>

    /**
     * The Log screen's list: every session, newest first, each paired with its
     * completed set count via one aggregate query — no N+1 as history grows.
     */
    @Query(
        """
        SELECT ws.*, COUNT(ss.id) AS setCount
        FROM workout_session ws
        LEFT JOIN session_set ss ON ss.sessionId = ws.id AND ss.done = 1
        GROUP BY ws.id
        ORDER BY ws.completedAt DESC, ws.id DESC
        """,
    )
    fun observeSessionSummaries(): Flow<List<SessionSummaryRow>>

    @Query("SELECT * FROM session_set WHERE sessionId = :sessionId ORDER BY id")
    suspend fun setsForSession(sessionId: Long): List<SessionSetEntity>

    /** One session by id, for the Health Connect publish path — the session
     *  header (dayTitle, start/end times) that pairs with [setsForSession]. */
    @Query("SELECT * FROM workout_session WHERE id = :sessionId")
    suspend fun sessionById(sessionId: Long): WorkoutSessionEntity?

    @Query("SELECT * FROM workout_session WHERE planId = :planId AND dayId = :dayId ORDER BY completedAt DESC, id DESC LIMIT 1")
    suspend fun lastSessionForDay(planId: Long, dayId: String): WorkoutSessionEntity?

    @Query(
        """
        SELECT ws.* FROM workout_session ws
        WHERE ws.planId = :planId
        ORDER BY ws.completedAt DESC, ws.id DESC
        """,
    )
    suspend fun sessionsForPlan(planId: Long): List<WorkoutSessionEntity>

    @Query(
        """
        SELECT ss.* FROM session_set ss
        INNER JOIN workout_session ws ON ws.id = ss.sessionId
        WHERE ws.planId = :planId AND ss.exerciseId IN (:exerciseIds)
        ORDER BY ss.id
        """,
    )
    suspend fun setsForPlan(planId: Long, exerciseIds: List<String>): List<SessionSetEntity>

    /**
     * Every completed ([SessionSetEntity.done]) set ever logged for any of
     * [exerciseIds], newest session first (ties — i.e. rows from the same session
     * — broken by heaviest weight). One query for a whole day's worth of exercise
     * ids ("last time" chip) instead of one per exercise; see
     * [cloud.trotter.log.strength.data.HistoryRepository.lastPerformed] for how
     * the flat result collapses to one row per exercise. For a ramped main lift,
     * every round (ramp/top/back-off) shares one `exerciseId`, so "heaviest
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

    @Query(
        """
        SELECT ss.exerciseId AS exerciseId, ss.slot AS slot, ss.weightLb AS weightLb,
            ss.reps AS reps, ss.seconds AS seconds, ws.planId AS planId,
            ws.planName AS planName, ws.dayId AS dayId,
            ss.sessionId AS sessionId, ss.setIndex AS setIndex, ss.kind AS kind
        FROM session_set ss
        INNER JOIN workout_session ws ON ws.id = ss.sessionId
        WHERE ss.exerciseId IN (:exerciseIds) AND ss.done = 1
        ORDER BY ws.completedAt DESC, ss.weightLb DESC, ss.reps DESC, ss.seconds DESC
        """,
    )
    suspend fun lastPerformedSourceRows(exerciseIds: List<String>): List<LastPerformedSourceRow>

    /** The heaviest done non-RAMP set of the latest session that has one: the "last working set" that opening
     *  loads estimate from (design §3.1.1) and that remembered weights read. */
    @Query(
        """
        SELECT ss.* FROM session_set ss
        INNER JOIN workout_session ws ON ws.id = ss.sessionId
        WHERE ss.exerciseId = :exerciseId AND ss.done = 1 AND ss.kind != 'RAMP'
        ORDER BY ws.completedAt DESC, ws.id DESC, ss.weightLb DESC, ss.id DESC
        LIMIT 1
        """,
    )
    suspend fun lastWorkingSet(exerciseId: String): SessionSetEntity?

    @Query("""
        SELECT ss.exerciseId AS exerciseId, MAX(ws.completedAt) AS completedAt
        FROM session_set ss INNER JOIN workout_session ws ON ws.id = ss.sessionId
        WHERE ss.exerciseId IN (:exerciseIds) AND ss.done = 1
        GROUP BY ss.exerciseId
    """)
    suspend fun lastPerformedDates(exerciseIds: List<String>): List<LastPerformedDateRow>

    /**
     * Every done set for [exerciseIds], oldest first, for the profile "Best" chip.
     * Ranking lives in [cloud.trotter.log.strength.domain.records.PersonalBests].
     * One query for a whole day's exercise ids, like [lastPerformedRows]; see
     * [cloud.trotter.log.strength.data.HistoryRepository.personalRecords] for the reduction.
     */
    @Query(
        """
        SELECT ss.exerciseId AS exerciseId, ss.weightLb AS weightLb, ss.reps AS reps, ws.completedAt AS completedAt, ss.kind AS kind, ss.seconds AS seconds
        FROM session_set ss
        INNER JOIN workout_session ws ON ws.id = ss.sessionId
        WHERE ss.exerciseId IN (:exerciseIds) AND ss.done = 1
        AND (:excludedRunId IS NULL OR ws.runId IS NOT :excludedRunId)
        ORDER BY ws.completedAt ASC, ss.id ASC
        """,
    )
    suspend fun personalRecordRows(exerciseIds: List<String>, excludedRunId: String?): List<PersonalRecordRow>

    /** Raw trajectory rows, oldest first; PersonalBests decides which sets count. */
    @Query(
        """
        SELECT ss.*, ws.completedAt AS completedAt
        FROM session_set ss
        INNER JOIN workout_session ws ON ws.id = ss.sessionId
        ORDER BY ws.completedAt ASC, ws.id ASC, ss.id ASC
        """,
    )
    fun observeTrajectorySets(): Flow<List<TrajectorySetRow>>

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

    @Query("UPDATE workout_session SET note = :note WHERE id = :sessionId")
    suspend fun setNote(sessionId: Long, note: String)

    @Query("DELETE FROM workout_session WHERE id = :sessionId")
    suspend fun deleteSessionById(sessionId: Long)

    @Query("DELETE FROM workout_session")
    suspend fun deleteAllSessions()

    @Query("DELETE FROM session_set")
    suspend fun deleteAllSessionSets()
}
