package cloud.trotter.log.strength.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Upsert
import cloud.trotter.log.strength.data.db.entity.ExerciseLogEntity
import cloud.trotter.log.strength.data.db.entity.ProgramDayEntity
import cloud.trotter.log.strength.data.db.entity.ProgramExerciseEntity
import kotlinx.coroutines.flow.Flow

/** Program (days + exercise slots) and the live per-slot logs. */
@Dao
interface ProgramDao {

    // --- reads (Flow) --------------------------------------------------------

    @Query("SELECT * FROM program_day WHERE planId = :planId ORDER BY position")
    fun observeDays(planId: Long): Flow<List<ProgramDayEntity>>

    @Query("SELECT * FROM program_exercise WHERE planId = :planId ORDER BY dayId, position")
    fun observeExercises(planId: Long): Flow<List<ProgramExerciseEntity>>

    @Query("SELECT * FROM exercise_log WHERE planId = :planId AND dayId = :dayId")
    fun observeLogs(planId: Long, dayId: String): Flow<List<ExerciseLogEntity>>

    @Query("SELECT * FROM program_day ORDER BY planId, position")
    fun observeAllDays(): Flow<List<ProgramDayEntity>>

    // --- one-shot reads ------------------------------------------------------

    @Query("SELECT * FROM exercise_log WHERE planId = :planId ORDER BY dayId, programExerciseId, slot")
    suspend fun logsForPlan(planId: Long): List<ExerciseLogEntity>

    @Query("SELECT * FROM program_day ORDER BY planId, position")
    suspend fun allDays(): List<ProgramDayEntity>

    @Query("SELECT * FROM program_day WHERE planId = :planId ORDER BY position")
    suspend fun daysForPlan(planId: Long): List<ProgramDayEntity>

    @Query("SELECT * FROM program_exercise ORDER BY planId, dayId, position")
    suspend fun allExercises(): List<ProgramExerciseEntity>

    @Query("SELECT * FROM program_exercise WHERE planId = :planId ORDER BY dayId, position")
    suspend fun exercisesForPlan(planId: Long): List<ProgramExerciseEntity>

    @Query("SELECT * FROM program_day WHERE planId = :planId AND dayId = :dayId")
    suspend fun day(planId: Long, dayId: String): ProgramDayEntity?

    @Query("SELECT * FROM program_exercise WHERE planId = :planId AND dayId = :dayId ORDER BY position")
    fun observeExercisesForDay(planId: Long, dayId: String): Flow<List<ProgramExerciseEntity>>

    @Query("SELECT * FROM program_exercise WHERE planId = :planId AND dayId = :dayId ORDER BY position")
    suspend fun exercisesForDay(planId: Long, dayId: String): List<ProgramExerciseEntity>

    @Query("SELECT * FROM program_exercise WHERE id = :id")
    suspend fun exercise(id: Long): ProgramExerciseEntity?

    @Query("SELECT * FROM program_exercise WHERE planId = :planId AND dayId = :dayId ORDER BY position LIMIT 1 OFFSET :ordinal")
    suspend fun exerciseAt(planId: Long, dayId: String, ordinal: Int): ProgramExerciseEntity?

    @Query("SELECT * FROM exercise_log WHERE planId = :planId AND dayId = :dayId")
    suspend fun logsForDay(planId: Long, dayId: String): List<ExerciseLogEntity>

    /** One track, read inside the transaction that is about to decide whether to
     *  seed it (`TrackerRepository.seedIfEmpty`). Null means "never seeded". */
    @Query(
        "SELECT * FROM exercise_log " +
            "WHERE planId = :planId AND dayId = :dayId " +
            "AND programExerciseId = :programExerciseId AND slot = :slot LIMIT 1",
    )
    suspend fun logForSlot(
        planId: Long,
        dayId: String,
        programExerciseId: Long,
        slot: String,
    ): ExerciseLogEntity?

    /** Every live log across all days, in a stable order (backup export, A2). */
    @Query("SELECT * FROM exercise_log ORDER BY planId, dayId, programExerciseId, slot")
    suspend fun allLogs(): List<ExerciseLogEntity>

    @Query("SELECT COALESCE(MAX(position), -1) FROM program_exercise WHERE planId = :planId AND dayId = :dayId")
    suspend fun maxPosition(planId: Long, dayId: String): Int

    // --- writes --------------------------------------------------------------

    @Upsert
    suspend fun upsertDay(day: ProgramDayEntity)

    @Upsert
    suspend fun upsertDays(days: List<ProgramDayEntity>)

    @Insert
    suspend fun insertExercise(exercise: ProgramExerciseEntity): Long

    /** Bulk insert preserving each row's [ProgramExerciseEntity.id] so live logs,
     *  which key on that surrogate id, keep resolving after a backup restore (A2). */
    @Insert
    suspend fun insertExercises(exercises: List<ProgramExerciseEntity>)

    @Query("UPDATE program_exercise SET exerciseId = :exerciseId WHERE id = :id")
    suspend fun setExerciseId(id: Long, exerciseId: String)

    @Query("UPDATE program_exercise SET prescriptionJson = :json, targetSets = :sets WHERE id = :id")
    suspend fun setPrescription(id: Long, json: String, sets: Int)

    @Query("UPDATE program_exercise SET position = :position WHERE id = :id")
    suspend fun setPosition(id: Long, position: Int)

    @Query("DELETE FROM program_day WHERE planId = :planId AND dayId = :dayId")
    suspend fun deleteDay(planId: Long, dayId: String)

    @Query("UPDATE program_exercise SET note = :note WHERE id = :id")
    suspend fun setExerciseNote(id: Long, note: String)

    /** Sets (or with a null [partnerId], clears) a slot's superset partner. */
    @Query("UPDATE program_exercise SET supersetExerciseId = :partnerId WHERE id = :id")
    suspend fun setSupersetExerciseId(id: Long, partnerId: String?)

    @Query("DELETE FROM program_exercise WHERE id = :id")
    suspend fun deleteExercise(id: Long)

    @Query("DELETE FROM program_exercise WHERE planId = :planId AND dayId = :dayId")
    suspend fun deleteExercisesForDay(planId: Long, dayId: String)

    @Upsert
    suspend fun upsertLog(log: ExerciseLogEntity)

    /** Bulk insert of live logs into a freshly cleared table (backup restore, A2). */
    @Insert
    suspend fun insertLogs(logs: List<ExerciseLogEntity>)

    @Query(
        "DELETE FROM exercise_log " +
            "WHERE planId = :planId AND dayId = :dayId AND programExerciseId = :programExerciseId",
    )
    suspend fun deleteLogsForExercise(planId: Long, dayId: String, programExerciseId: Long)

    /** Clears one of a slot's two tracks, leaving the other alone. */
    @Query(
        "DELETE FROM exercise_log WHERE planId = :planId AND dayId = :dayId " +
            "AND programExerciseId = :programExerciseId AND slot = :slot",
    )
    suspend fun deleteLogForSlot(planId: Long, dayId: String, programExerciseId: Long, slot: String)

    @Query("DELETE FROM exercise_log WHERE planId = :planId AND dayId = :dayId")
    suspend fun deleteLogsForDay(planId: Long, dayId: String)

    /**
     * Clears the day's checkmarks by invalidating each log's [ExerciseLogEntity.checkDate].
     * The `done` flags stay in the stored JSON but read back as cleared (a stale
     * date always surfaces unchecked), which is exactly the daily-reset rule — no
     * need to rewrite every JSON blob.
     */
    @Query("UPDATE exercise_log SET checkDate = '' WHERE planId = :planId AND dayId = :dayId")
    suspend fun clearChecksForDay(planId: Long, dayId: String)

    @Query("DELETE FROM program_day WHERE planId = :planId")
    suspend fun deleteDaysForPlan(planId: Long)

    @Query("DELETE FROM program_exercise WHERE planId = :planId")
    suspend fun deleteExercisesForPlan(planId: Long)

    @Query("DELETE FROM exercise_log WHERE planId = :planId")
    suspend fun deleteLogsForPlan(planId: Long)

    @Query("DELETE FROM program_day")
    suspend fun deleteAllDays()

    @Query("DELETE FROM program_exercise")
    suspend fun deleteAllExercises()

    @Query("DELETE FROM exercise_log")
    suspend fun deleteAllLogs()
}
