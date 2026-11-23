package cloud.trotter.log.strength.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import cloud.trotter.log.strength.data.db.entity.TestResultEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface TestResultDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(result: TestResultEntity): Long

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertAll(results: List<TestResultEntity>)

    @Query("SELECT * FROM test_result ORDER BY completedAt, id")
    fun observeAll(): Flow<List<TestResultEntity>>

    @Query("SELECT * FROM test_result ORDER BY completedAt, id")
    suspend fun all(): List<TestResultEntity>

    @Query("DELETE FROM test_result")
    suspend fun deleteAll()

    @Query("DELETE FROM test_result WHERE runId = :runId AND programExerciseId = :programExerciseId AND setIndex = :setIndex")
    suspend fun deleteForSet(runId: String, programExerciseId: Long, setIndex: Int)

    @Query("SELECT MAX(completedAt) FROM test_result WHERE eventId = :eventId AND eventVersion = :version AND ((:window IS NULL AND windowSeconds IS NULL) OR windowSeconds = :window)")
    suspend fun newestCompletedAt(eventId: String, version: Int, window: Int?): Long?
}
