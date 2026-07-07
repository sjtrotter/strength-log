package io.github.sjtrotter.strengthlog.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Upsert
import io.github.sjtrotter.strengthlog.data.db.entity.ExerciseLogEntity
import io.github.sjtrotter.strengthlog.data.db.entity.ProgramDayEntity
import io.github.sjtrotter.strengthlog.data.db.entity.ProgramExerciseEntity
import kotlinx.coroutines.flow.Flow

/** Program (days + exercise slots) and the live per-slot logs. */
@Dao
interface ProgramDao {

    // --- reads (Flow) --------------------------------------------------------

    @Query("SELECT * FROM program_day ORDER BY position")
    fun observeDays(): Flow<List<ProgramDayEntity>>

    @Query("SELECT * FROM program_exercise ORDER BY dayId, position")
    fun observeExercises(): Flow<List<ProgramExerciseEntity>>

    @Query("SELECT * FROM exercise_log WHERE dayId = :dayId")
    fun observeLogs(dayId: String): Flow<List<ExerciseLogEntity>>

    // --- one-shot reads ------------------------------------------------------

    @Query("SELECT * FROM program_day ORDER BY position")
    suspend fun allDays(): List<ProgramDayEntity>

    @Query("SELECT * FROM program_exercise ORDER BY dayId, position")
    suspend fun allExercises(): List<ProgramExerciseEntity>

    @Query("SELECT * FROM program_day WHERE dayId = :dayId")
    suspend fun day(dayId: String): ProgramDayEntity?

    @Query("SELECT * FROM program_exercise WHERE dayId = :dayId ORDER BY position")
    fun observeExercisesForDay(dayId: String): Flow<List<ProgramExerciseEntity>>

    @Query("SELECT * FROM program_exercise WHERE dayId = :dayId ORDER BY position")
    suspend fun exercisesForDay(dayId: String): List<ProgramExerciseEntity>

    @Query("SELECT * FROM program_exercise WHERE dayId = :dayId ORDER BY position LIMIT 1 OFFSET :ordinal")
    suspend fun exerciseAt(dayId: String, ordinal: Int): ProgramExerciseEntity?

    @Query("SELECT * FROM exercise_log WHERE dayId = :dayId")
    suspend fun logsForDay(dayId: String): List<ExerciseLogEntity>

    /** Every live log across all days, in a stable order (backup export, A2). */
    @Query("SELECT * FROM exercise_log ORDER BY dayId, programExerciseId, slot")
    suspend fun allLogs(): List<ExerciseLogEntity>

    @Query("SELECT COALESCE(MAX(position), -1) FROM program_exercise WHERE dayId = :dayId")
    suspend fun maxPosition(dayId: String): Int

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

    @Query("DELETE FROM program_exercise WHERE id = :id")
    suspend fun deleteExercise(id: Long)

    @Query("DELETE FROM program_exercise WHERE dayId = :dayId")
    suspend fun deleteExercisesForDay(dayId: String)

    @Upsert
    suspend fun upsertLog(log: ExerciseLogEntity)

    /** Bulk insert of live logs into a freshly cleared table (backup restore, A2). */
    @Insert
    suspend fun insertLogs(logs: List<ExerciseLogEntity>)

    @Query("DELETE FROM exercise_log WHERE dayId = :dayId AND programExerciseId = :programExerciseId")
    suspend fun deleteLogsForExercise(dayId: String, programExerciseId: Long)

    @Query("DELETE FROM exercise_log WHERE dayId = :dayId")
    suspend fun deleteLogsForDay(dayId: String)

    /**
     * Clears the day's checkmarks by invalidating each log's [ExerciseLogEntity.checkDate].
     * The `done` flags stay in the stored JSON but read back as cleared (a stale
     * date always surfaces unchecked), which is exactly the daily-reset rule — no
     * need to rewrite every JSON blob.
     */
    @Query("UPDATE exercise_log SET checkDate = '' WHERE dayId = :dayId")
    suspend fun clearChecksForDay(dayId: String)

    @Query("DELETE FROM program_day")
    suspend fun deleteAllDays()

    @Query("DELETE FROM program_exercise")
    suspend fun deleteAllExercises()

    @Query("DELETE FROM exercise_log")
    suspend fun deleteAllLogs()
}
