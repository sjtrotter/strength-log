package cloud.trotter.log.strength.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** An open workout, removed when finished or discarded. */
@Entity(tableName = "workout_run")
data class WorkoutRunEntity(
    @PrimaryKey val id: String,
    val planId: Long?,
    val dayId: String?,
    val dayTitle: String,
    val planName: String?,
    val startedAt: Long,
)

/** Captured prescription and actual rows for an exercise in an open workout. */
@Entity(
    tableName = "run_track",
    indices = [Index("runId"), Index(value = ["runId", "programExerciseId", "slot", "exerciseId"], unique = true)],
)
data class RunTrackEntity(
    @PrimaryKey(autoGenerate = true) val id: Long,
    val runId: String,
    val position: Int,
    val programExerciseId: Long?,
    val slot: String,
    val exerciseId: String,
    val retired: Boolean = false,
    val plannedJson: String,
    val setsJson: String,
    val updatedAt: Long,
    @ColumnInfo(defaultValue = "0") val dropped: Boolean = false,
)
