package cloud.trotter.log.strength.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import cloud.trotter.log.strength.data.db.entity.RunTrackEntity
import cloud.trotter.log.strength.data.db.entity.WorkoutRunEntity
import kotlinx.coroutines.flow.Flow

/** Open workouts and their captured tracks. */
@Dao
interface RunDao {
    /** Returns the earliest open run. */
    @Query("SELECT * FROM workout_run ORDER BY startedAt LIMIT 1")
    suspend fun openRun(): WorkoutRunEntity?

    /** Observes the earliest open run. */
    @Query("SELECT * FROM workout_run ORDER BY startedAt LIMIT 1")
    fun observeOpenRun(): Flow<WorkoutRunEntity?>

    /** Returns a run's tracks in display order. */
    @Query("SELECT * FROM run_track WHERE runId = :runId ORDER BY position, slot, id")
    suspend fun tracks(runId: String): List<RunTrackEntity>

    /** Observes a run's tracks in display order. */
    @Query("SELECT * FROM run_track WHERE runId = :runId ORDER BY position, slot, id")
    fun observeTracks(runId: String): Flow<List<RunTrackEntity>>

    /** Inserts an open run. */
    @Insert
    suspend fun insertRun(run: WorkoutRunEntity)

    /** Inserts a track and returns its assigned identity. */
    @Insert
    suspend fun insertTrack(track: RunTrackEntity): Long

    /** Replaces a track's captured state. */
    @Update
    suspend fun updateTrack(track: RunTrackEntity)

    /** Deletes one run track. */
    @Query("DELETE FROM run_track WHERE id = :id")
    suspend fun deleteTrack(id: Long)

    /** Deletes a run's tracks. */
    @Query("DELETE FROM run_track WHERE runId = :runId")
    suspend fun deleteTracks(runId: String)

    @Query("UPDATE workout_run SET startedAt = :startedAt WHERE id = :id")
    suspend fun updateStartedAt(id: String, startedAt: Long)

    /** Deletes an open run. */
    @Query("DELETE FROM workout_run WHERE id = :id")
    suspend fun deleteRun(id: String)

    /** Returns all open runs in stable backup order. */
    @Query("SELECT * FROM workout_run ORDER BY id")
    suspend fun allRuns(): List<WorkoutRunEntity>

    /** Returns all tracks in stable backup order. */
    @Query("SELECT * FROM run_track ORDER BY id")
    suspend fun allTracks(): List<RunTrackEntity>

    /** Restores runs with their original identities. */
    @Insert
    suspend fun insertRuns(runs: List<WorkoutRunEntity>)

    /** Restores tracks with their original identities. */
    @Insert
    suspend fun insertTracks(tracks: List<RunTrackEntity>)

    /** Deletes all open runs. */
    @Query("DELETE FROM workout_run")
    suspend fun deleteAllRuns()

    /** Deletes all run tracks. */
    @Query("DELETE FROM run_track")
    suspend fun deleteAllTracks()
}
